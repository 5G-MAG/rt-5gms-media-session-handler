package com.fivegmag.a5gmsmediasessionhandler.controller

import com.fivegmag.a5gmscommonlibrary.helpers.ClientDataReportingModes
import com.fivegmag.a5gmscommonlibrary.helpers.CmcdSchemes
import com.fivegmag.a5gmscommonlibrary.session.ClientDataReportingEntry
import com.fivegmag.a5gmscommonlibrary.session.MediaPlayerEntryContext
import com.fivegmag.a5gmscommonlibrary.session.ServiceLocationUrls
import android.util.Log
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
 * - A ServiceDescription is selected when it has no Scope (table K.10). One with the Scope urn:mpeg:dash:event:service-description:2024 is read too
 *   (K.6) but marked activatedByEvent: the player applies it only while an Event activates it. The Event can name it by its @id, or carry it as a
 *   ServiceDescription child (K.6.2, K.6.4), which is read the same way. A ServiceDescription with any other Scope is never applied. A Period that
 *   carries ServiceDescription elements overrides those of the MPD (table 4, Period.ServiceDescription): this is applied for an MPD with one Period;
 *   with several Periods and one of them carrying descriptions none is applied, because the Period of a request is not known to the player.
 * - serviceLocations names BaseURL@serviceLocation labels (clause 5.6.6) of the BaseURL elements at any level, resolved against the URL of the MPD.
 * - adaptationSets names AdaptationSet@id values. The entry carries them with the AdaptationSet ids of the MPD (MediaPlayerEntryContext): the player
 *   cannot tell the AdaptationSet of a request, so it evaluates the filter only when it names every AdaptationSet (then it restricts nothing) and
 *   leaves the entry out otherwise. The settings API of the application (clause 13.2.4) can replace the filter, which is why it is not decided here.
 * - includeInRequests holds request types (table I.4); a type that is not known is dropped. The request types are given to the player as the
 *   CMCD object types (ot) of the requests they cover: segment a, v, av, c and tt; init i; mpd m; * every request. The other known types cover no
 *   request that the player marks with an object type.
 * - keys are separated by white space; those that CTA-5004 version 1 does not define are ignored. contentID and sessionID are valid with at most
 *   64 characters and no white space; a value that is absent or invalid is not derived from ExtUrlQueryInfo (clause I.3 is not implemented).
 */
object ClientDataReportingParser {
    const val SCHEME = "urn:mpeg:dash:cta-5004:2023"
    private const val TAG = "ClientDataReporting"
    const val EVENT_SCHEME = "urn:mpeg:dash:event:service-description:2024"
    // the filters of table K.7 are attributes of ClientDataReporting (tables K.16 and K.17, the schema), not of CMCDParameters
    private val FILTER_ATTRIBUTES = listOf("serviceLocations", "adaptationSets")
    private const val MAX_IDENTIFIER_LENGTH = 64

    // the object types (CTA-5004 table 2, key ot) of the request types of table I.4 that the player can tell apart
    private val REQUEST_TYPES = mapOf("segment" to listOf("a", "v", "av", "c", "tt"), "init" to listOf("i"), "mpd" to listOf("m"))
    private val OTHER_REQUEST_TYPES = setOf("xlink", "callback", "chaining", "fallback", "sbd", "steering", "mpdpatch", "altmpd", "mpdlink")
    private const val ALL_REQUEST_TYPES = "*"
    private val CMCD_KEYS: Set<String> = CmcdSchemes.KEYS.values.flatten().toSet()

    /** The entries of the manifest at [mpdUrl] and what the player needs of the manifest to apply the settings of the application to them */
    class Result(val entries: List<ClientDataReportingEntry>, val context: MediaPlayerEntryContext?)

    /** The entries of the manifest at [mpdUrl] (which resolves relative BaseURL elements); an unreadable manifest has none */
    fun parse(mpd: String, mpdUrl: String): List<ClientDataReportingEntry> = parseAll(mpd, mpdUrl).entries

    fun parseAll(mpd: String, mpdUrl: String): Result {
        val result = ArrayList<ClientDataReportingEntry>()
        var context: MediaPlayerEntryContext? = null
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
            val adaptationSetIds = ArrayList<String>()
            for (i in 0 until adaptationSets.length) {
                adaptationSetIds.add((adaptationSets.item(i) as Element).getAttribute("id"))
            }
            val descriptions = selectedDescriptions(root)
            context = MediaPlayerEntryContext(
                ArrayList(locations.map { (label, urls) -> ServiceLocationUrls(label, ArrayList(urls.distinct())) }),
                adaptationSetIds,
                ArrayList(descriptions.mapNotNull { it.getAttribute("id").toLongOrNull() }.distinct())
            )
            for (description in descriptions + eventDescriptions(root)) {
                val byEvent = description !in descriptions
                for (reporting in children(description, "ClientDataReporting")) {
                    if (reporting.getAttribute("schemeIdUri") != SCHEME) continue
                    for (parameters in children(reporting, "CMCDParameters")) {
                        val id = description.getAttribute("id")
                        for (name in FILTER_ATTRIBUTES.filter { parameters.hasAttribute(it) }) {
                            Log.w(TAG, "ServiceDescription $id: $name is an attribute of ClientDataReporting, it is ignored on CMCDParameters")
                        }
                        val entry = entryOf(description, reporting, parameters, locations, byEvent)
                        Log.d(TAG, "ServiceDescription $id: " + (entry?.toString() ?: "left out"))
                        entry?.let { result.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "The manifest cannot be read for its client data reporting: " + e.message)
            return Result(emptyList(), null)
        }
        return Result(result, context)
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

    /**
     * The descriptions that an Event activates: those with the Scope of clause K.6 in the MPD and in its Periods, and the ServiceDescription that an
     * Event of such an EventStream carries
     */
    private fun eventDescriptions(root: Element): List<Element> {
        fun scoped(parent: Element) = children(parent, "ServiceDescription").filter { description ->
            children(description, "Scope").any { it.getAttribute("schemeIdUri") == EVENT_SCHEME }
        }
        val result = ArrayList<Element>(scoped(root))
        for (period in children(root, "Period")) {
            result.addAll(scoped(period))
            for (stream in children(period, "EventStream").filter { it.getAttribute("schemeIdUri") == EVENT_SCHEME }) {
                for (event in children(stream, "Event")) result.addAll(children(event, "ServiceDescription"))
            }
        }
        return result
    }

    private fun entryOf(
        description: Element,
        reporting: Element,
        parameters: Element,
        locations: Map<String, List<String>>,
        activatedByEvent: Boolean
    ): ClientDataReportingEntry? {
        // @keys is mandatory (table K.17)
        if (!parameters.hasAttribute("keys")) return null
        val mode = when (if (parameters.hasAttribute("mode")) parameters.getAttribute("mode") else "query") {
            "header" -> ClientDataReportingModes.CMCD_HEADERS
            "query" -> ClientDataReportingModes.CMCD_QUERY
            else -> return null
        }
        // a token that is not an unsigned integer names no AdaptationSet (UIntVectorType)
        val adaptationSets = if (reporting.hasAttribute("adaptationSets")) {
            ArrayList(tokens(reporting.getAttribute("adaptationSets")).mapNotNull { it.toLongOrNull() }.distinct())
        } else null
        val urls = if (reporting.hasAttribute("serviceLocations")) {
            ArrayList(tokens(reporting.getAttribute("serviceLocations")).flatMap { locations[it].orEmpty() }.distinct())
        } else null
        // the BaseURL of one service location can lie below that of another: the entry hands over the others so that the player can tell them apart
        val others = urls?.let { named -> ArrayList(locations.values.flatten().distinct().filter { it !in named }) }?.takeIf { it.isNotEmpty() }
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
            keys.takeIf { it.isNotEmpty() }?.let { ArrayList(it) },
            others,
            description.getAttribute("id").toLongOrNull(),
            adaptationSets,
            activatedByEvent
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
