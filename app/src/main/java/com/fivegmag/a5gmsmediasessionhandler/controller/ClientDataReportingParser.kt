package com.fivegmag.a5gmsmediasessionhandler.controller

import com.fivegmag.a5gmscommonlibrary.helpers.ClientDataReportingModes
import com.fivegmag.a5gmscommonlibrary.helpers.CmcdSchemes
import com.fivegmag.a5gmscommonlibrary.session.ClientDataReportingEntry
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The client data reporting for CMCD that a DASH Media Player Entry declares in its in-band Service Description (TS 26.512 V19.3.0 clauses 10.5.1
 * and G.5.2, ISO/IEC 23009-1:2026 clauses K.3.7, K.4.2.7 and K.4.3.7). Where TS 26.512 listing G.5.2.4-1 and ISO/IEC 23009-1 differ (scheme and
 * comma-separated keys in the listing, adaptationSets="video", a Scope that the ISO text reserves for the activation by an Event; register
 * item SD-032), the ISO text is followed, so the listing as published is not applied: ClientDataReporting is a descriptor (@schemeIdUri
 * urn:mpeg:dash:cta-5004:2023) with @serviceLocations and @adaptationSets, and CMCDParameters has @version, @mode (default query),
 * @includeInRequests (default segment), @keys (mandatory), @contentID and @sessionID (table K.17 and the schema; table K.8 spells the last two
 * contentId and sessionID, the schema is followed).
 * - A ServiceDescription is selected when it has no Scope (table K.10); one with a Scope is not, because the only scope that this reads is the
 *   one of the activation by an Event (K.6), which is not implemented, so such a description is never applied. A Period that carries
 *   ServiceDescription elements overrides those of the MPD (table 4, Period.ServiceDescription): this is applied for an MPD with one Period; with
 *   several Periods and one of them carrying descriptions none is applied, because the Period of a request is not known to the player.
 * - serviceLocations names BaseURL@serviceLocation labels (clause 5.6.6) of the BaseURL elements at any level, resolved against the URL of the MPD.
 * - adaptationSets names AdaptationSet@id values. The player cannot tell the AdaptationSet of a request, so the filter is only evaluated when it
 *   names every AdaptationSet of the MPD (then it restricts nothing); otherwise the element is left out.
 * - includeInRequests holds request types (table I.4); a type that is not known is dropped. The request types are given to the player as the
 *   CMCD object types (ot) of the requests they cover: segment a, v, av, c and tt; init i; mpd m; * every request. The other known types cover no
 *   request that the player marks with an object type.
 * - keys are separated by white space; those that CTA-5004 version 1 does not define are ignored. contentID and sessionID are valid with at most
 *   64 characters and no white space; a value that is absent or invalid is not derived from ExtUrlQueryInfo (clause I.3 is not implemented).
 */
object ClientDataReportingParser {
    const val SCHEME = "urn:mpeg:dash:cta-5004:2023"
    private const val MAX_IDENTIFIER_LENGTH = 64

    // the object types (CTA-5004 table 2, key ot) of the request types of table I.4 that the player can tell apart
    private val REQUEST_TYPES = mapOf("segment" to listOf("a", "v", "av", "c", "tt"), "init" to listOf("i"), "mpd" to listOf("m"))
    private val OTHER_REQUEST_TYPES = setOf("xlink", "callback", "chaining", "fallback", "sbd", "steering", "mpdpatch", "altmpd", "mpdlink")
    private const val ALL_REQUEST_TYPES = "*"
    private val CMCD_KEYS: Set<String> = CmcdSchemes.KEYS.values.flatten().toSet()

    /** The entries of the manifest at [mpdUrl] (which resolves relative BaseURL elements); an unreadable manifest has none */
    fun parse(mpd: String, mpdUrl: String): List<ClientDataReportingEntry> {
        val result = ArrayList<ClientDataReportingEntry>()
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            // a manifest is data: no external entities
            for (feature in listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities")) {
                try {
                    factory.setFeature(feature, false)
                } catch (_: Exception) {
                    // the parser of Android does not offer the feature and does not resolve external entities either
                }
            }
            val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(mpd.toByteArray(Charsets.UTF_8)))
            val root = document.documentElement
            val locations = LinkedHashMap<String, MutableList<String>>()
            collectServiceLocations(root, listOf(mpdUrl), locations)
            val adaptationSets = document.getElementsByTagNameNS("*", "AdaptationSet")
            val adaptationSetIds = ArrayList<Long?>()
            for (i in 0 until adaptationSets.length) {
                adaptationSetIds.add((adaptationSets.item(i) as Element).getAttribute("id").toLongOrNull())
            }
            for (description in selectedDescriptions(root)) {
                for (reporting in children(description, "ClientDataReporting")) {
                    if (reporting.getAttribute("schemeIdUri") != SCHEME) continue
                    for (parameters in children(reporting, "CMCDParameters")) {
                        entryOf(reporting, parameters, locations, adaptationSetIds)?.let { result.add(it) }
                    }
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return result
    }

    /** The ServiceDescription elements without a Scope: those of the MPD, or those of its only Period if it has any (see above) */
    private fun selectedDescriptions(root: Element): List<Element> {
        fun unscoped(parent: Element) = children(parent, "ServiceDescription").filter { children(it, "Scope").isEmpty() }
        val periods = children(root, "Period")
        val ofPeriods = periods.map { unscoped(it) }.filter { it.isNotEmpty() }
        return when {
            ofPeriods.isEmpty() -> unscoped(root)
            periods.size == 1 -> ofPeriods[0]
            else -> emptyList()
        }
    }

    private fun entryOf(
        reporting: Element,
        parameters: Element,
        locations: Map<String, List<String>>,
        adaptationSetIds: List<Long?>
    ): ClientDataReportingEntry? {
        // @keys is mandatory (table K.17)
        if (!parameters.hasAttribute("keys")) return null
        val mode = when (if (parameters.hasAttribute("mode")) parameters.getAttribute("mode") else "query") {
            "header" -> ClientDataReportingModes.CMCD_HEADERS
            "query" -> ClientDataReportingModes.CMCD_QUERY
            else -> return null
        }
        // adaptationSets: only a filter that names every AdaptationSet of the MPD can be evaluated by the player
        if (reporting.hasAttribute("adaptationSets")) {
            val named = tokens(reporting.getAttribute("adaptationSets")).mapNotNull { it.toLongOrNull() }.toSet()
            if (adaptationSetIds.isEmpty() || adaptationSetIds.any { it == null || it !in named }) return null
        }
        val urls = if (reporting.hasAttribute("serviceLocations")) {
            ArrayList(tokens(reporting.getAttribute("serviceLocations")).flatMap { locations[it].orEmpty() }.distinct())
        } else null
        val requestTypes = tokens(if (parameters.hasAttribute("includeInRequests")) parameters.getAttribute("includeInRequests") else "segment")
            .filter { it == ALL_REQUEST_TYPES || it in REQUEST_TYPES || it in OTHER_REQUEST_TYPES }
        val types = if (ALL_REQUEST_TYPES in requestTypes) null else ArrayList(requestTypes.flatMap { REQUEST_TYPES[it].orEmpty() }.distinct())
        val keys = tokens(parameters.getAttribute("keys")).filter { it in CMCD_KEYS }.distinct()
        return ClientDataReportingEntry(
            urls,
            types,
            mode,
            identifier(parameters, "sessionID"),
            identifier(parameters, "contentID"),
            keys.takeIf { it.isNotEmpty() }?.let { ArrayList(it) }
        )
    }

    private fun identifier(parameters: Element, name: String): String? =
        parameters.getAttribute(name).takeIf { it.isNotEmpty() && it.length <= MAX_IDENTIFIER_LENGTH && tokens(it).size == 1 && it == it.trim() }

    /**
     * The directory of each BaseURL with a serviceLocation, at any level, by label. A relative BaseURL is resolved against each base of the
     * level above, an absolute one overrides them (clause 5.6.5).
     */
    private fun collectServiceLocations(element: Element, parentBases: List<String>, result: MutableMap<String, MutableList<String>>) {
        var bases = parentBases
        val own = children(element, "BaseURL")
        if (own.isNotEmpty()) {
            val resolved = ArrayList<String>()
            for (base in own) {
                val label = base.getAttribute("serviceLocation")
                for (parent in parentBases) {
                    val url = try {
                        java.net.URI(parent).resolve(base.textContent.trim())
                    } catch (_: Exception) {
                        continue
                    }
                    resolved.add(url.toString())
                    if (label.isNotBlank()) result.getOrPut(label) { ArrayList() }.add(directory(url))
                }
            }
            bases = resolved
        }
        for (child in listOf("Period", "AdaptationSet", "Representation")) {
            for (next in children(element, child)) collectServiceLocations(next, bases, result)
        }
    }

    /** The URL up to and including the last "/" of its path: what the Segment URLs resolved against it start with */
    private fun directory(url: java.net.URI): String {
        val path = url.rawPath.orEmpty()
        return "${url.scheme}://${url.rawAuthority}" + path.substring(0, path.lastIndexOf('/') + 1).ifEmpty { "/" }
    }

    private fun tokens(value: String): List<String> = value.split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun children(parent: Element, name: String): List<Element> {
        val result = ArrayList<Element>()
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            if ((node.localName ?: node.nodeName) == name) result.add(node)
        }
        return result
    }
}
