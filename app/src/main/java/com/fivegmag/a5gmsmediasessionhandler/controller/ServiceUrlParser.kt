package com.fivegmag.a5gmsmediasessionhandler.controller

import java.io.ByteArrayOutputStream

/** Why a URL handed to the Media Session Handler is not accepted as a 3GPP Service URL for 5G Media Streaming (TS 26.510 V19.2.0 clause 6.3) */
enum class ServiceUrlReason {
    OK,
    NOT_A_3GPP_SERVICE_URL,
    UNSUPPORTED_SERVICE_TYPE,
    INVALID_PARAMETERS
}

/** An endpoint of the Media AF at reference point M5 (TS 26.512 V19.3.0 clause 4.8.3.2) */
data class AfEndpoint(val host: String, val port: Int)

/** The 5GMS-specific parameters of the suffix part that the launch step uses (TS 26.512 V19.3.0 table 12.4-1), percent-decoded */
data class LaunchParameters(
    val mediaEntryPoint: String?,
    val serviceOperationPoint: String?,
    val estimatedVolume: String?,
    val contentId: String?,
    val accessToken: String?,
    val deliveryProtocols: List<String>,
    val contentTypes: List<String>,
    val profiles: List<String>,
    val multipathMode: String?,
    val clientDataReportingMode: String?
)

/**
 * A 3GPP Service URL decomposed into prefix and suffix (TS 26.510 V19.2.0 clause 6.3). The service identifier and the extra path segments are
 * kept as written in the URL; query keys and values are percent-decoded and a flag has the value null. The Media AF endpoints and the Service
 * Access Information request URL are only set when the URL is accepted.
 */
data class ServiceUrl(
    val reason: ServiceUrlReason,
    val scheme: String? = null,
    val authority: String? = null,
    val service: String? = null,
    val serviceId: String? = null,
    val extraPath: List<String> = emptyList(),
    val query: List<Pair<String, String?>> = emptyList(),
    val fragment: String? = null,
    val afCandidates: List<AfEndpoint> = emptyList(),
    val serviceAccessInformationUrl: String? = null,
    val launch: LaunchParameters? = null,
    // the percent-decoded service_id and the M5 API root of the first Media AF endpoint, as the launch step needs them
    val externalServiceId: String? = null,
    val m5BaseUrl: String? = null
) {
    val accepted: Boolean get() = reason == ServiceUrlReason.OK
}

/**
 * Parser of the 3GPP Service URL (TS 26.510 V19.2.0 clause 6.2, listing 6.2-1) and of the parameters of the service type ms, 5G Media Streaming
 * (TS 26.512 V19.3.0 clauses 4.8.3.2, 4.8.3.3 and 12.4). It decomposes the URL and chooses the M5 endpoint; it does not contact the network.
 *
 * Where the text is silent the choice is conservative: nothing is launched with parameters that cannot be read.
 * - uchar of the ABNF is not defined; it is read as unreserved or percent-encoded (RFC 3986).
 * - A service type other than ms is well-formed but not supported (clause 6.3); the case of the label is significant, as in any path.
 * - For ms the service_id is required (table 12.4-1), although the ABNF makes it optional.
 * - A known parameter without value, a repeated single-occurrence parameter, a malformed af-host-address, a media-entry-point that is not an
 *   absolute URL and a client-data-reporting-mode outside the enumeration are errors. Parameters not specified in table 12.4-1 are ignored
 *   (clause 4.8.3.3).
 * - The scheme of {apiRoot} at M5 is the scheme of the 3GPP Service URL; a port that is the default of that scheme is left out.
 */
object ServiceUrlParser {
    const val DOMAIN = "launch.3gppservices.org"
    const val SERVICE_MEDIA_STREAMING = "ms"
    const val DEFAULT_AF_HOST = "ms.af.3gppservices.org"
    const val M5_API_PATH = "/3gpp-maf-session-handling/v1/"
    const val SERVICE_ACCESS_INFORMATION_PATH = M5_API_PATH + "service-access-information/"

    const val PARAM_AF_HOST_ADDRESS = "af-host-address"
    const val PARAM_ACCESS_TOKEN = "access-token"
    const val PARAM_SERVICE_OPERATION_POINT = "service-operation-point"
    const val PARAM_CONTENT_ID = "content-id"
    const val PARAM_ESTIMATED_VOLUME = "estimated-volume"
    const val PARAM_MEDIA_ENTRY_POINT = "media-entry-point"
    const val PARAM_DELIVERY_PROTOCOL = "delivery-protocol"
    const val PARAM_MULTIPATH_MODE = "multipath-mode"
    const val PARAM_CLIENT_DATA_REPORTING_MODE = "client-data-reporting-mode"
    const val PARAM_CONTENT_TYPE = "content-type"
    const val PARAM_PROFILE = "profile"

    private val SCHEMES = listOf("http", "https")
    private val CLIENT_DATA_REPORTING_MODES = setOf("cmcd-headers", "cmcd-query")
    private val SINGLE_PARAMS = listOf(
        PARAM_ACCESS_TOKEN, PARAM_SERVICE_OPERATION_POINT, PARAM_CONTENT_ID, PARAM_ESTIMATED_VOLUME,
        PARAM_MEDIA_ENTRY_POINT, PARAM_MULTIPATH_MODE, PARAM_CLIENT_DATA_REPORTING_MODE
    )
    private val MULTIPLE_PARAMS = listOf(PARAM_AF_HOST_ADDRESS, PARAM_DELIVERY_PROTOCOL, PARAM_CONTENT_TYPE, PARAM_PROFILE)

    private const val LABEL = "[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
    private val HOSTNAME = Regex("^(?=.{1,253}$)$LABEL(\\.$LABEL)*$")
    private val ABSOLUTE_URL = Regex("^[A-Za-z][A-Za-z0-9+.-]*://[^/?#]+([/?#].*)?$")

    /** Decomposes the URL; the reason tells whether the Media Session Handler accepts it and, if not, why not */
    fun parse(url: String): ServiceUrl {
        // clause 6.2: the prefix starts with http:// or https:// (scheme names are case-insensitive)
        val prefix = SCHEMES.firstOrNull { url.startsWith("$it://", ignoreCase = true) } ?: return notAServiceUrl()
        val scheme = prefix.lowercase()
        var rest = url.substring(prefix.length + 3)
        val hash = rest.indexOf('#')
        val fragment = if (hash >= 0) rest.substring(hash + 1) else null
        if (hash >= 0) rest = rest.substring(0, hash)
        val questionMark = rest.indexOf('?')
        val rawQuery = if (questionMark >= 0) rest.substring(questionMark + 1) else null
        if (questionMark >= 0) rest = rest.substring(0, questionMark)

        // listing 6.2-1: 3gpp-domain is the literal launch.3gppservices.org (no user information, no port) followed by "/"
        val slash = rest.indexOf('/')
        if (slash < 0 || !rest.substring(0, slash).equals(DOMAIN, ignoreCase = true)) return notAServiceUrl()
        if (rawQuery != null && !conforms(rawQuery) { isPchar(it) || it == '/' || it == '?' }) return notAServiceUrl()
        if (fragment != null && !conforms(fragment) { isPchar(it) || it == '/' || it == '?' }) return notAServiceUrl()

        // prefix part: service, service_id and further path segments; "&" is not allowed unless escaped (clause 6.2)
        val segments = rest.substring(slash + 1).split('/')
        val service = segments[0]
        if (service.isEmpty() || !conforms(service) { isUnreserved(it) }) return notAServiceUrl()
        val serviceId = segments.getOrNull(1)?.takeIf { it.isNotEmpty() && conforms(it) { c -> isUnreserved(c) } }
        val extraPath = segments.drop(if (serviceId != null) 2 else 1)
        if (!extraPath.all { conforms(it) { c -> isPchar(c) && c != '&' } }) return notAServiceUrl()

        val query = rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map {
            val equals = it.indexOf('=')
            if (equals < 0) decode(it) to null else decode(it.substring(0, equals)) to decode(it.substring(equals + 1))
        }
        val decomposed = ServiceUrl(
            ServiceUrlReason.OK, scheme, DOMAIN, service, serviceId, extraPath, query, fragment
        )

        // clause 6.3: a service type that is not supported gets an error response; only ms is handled (TS 26.512 clause 12.4)
        if (service != SERVICE_MEDIA_STREAMING) return decomposed.copy(reason = ServiceUrlReason.UNSUPPORTED_SERVICE_TYPE)
        // table 12.4-1: service_id has cardinality 1
        if (serviceId == null) return decomposed.copy(reason = ServiceUrlReason.INVALID_PARAMETERS)
        val launch = launchParameters(query) ?: return decomposed.copy(reason = ServiceUrlReason.INVALID_PARAMETERS)
        val candidates = afCandidates(query, scheme) ?: return decomposed.copy(reason = ServiceUrlReason.INVALID_PARAMETERS)
        val externalServiceId = decode(serviceId)
        return decomposed.copy(
            afCandidates = candidates,
            serviceAccessInformationUrl = serviceAccessInformationUrl(scheme, candidates[0], externalServiceId),
            launch = launch,
            externalServiceId = externalServiceId,
            m5BaseUrl = apiRoot(scheme, candidates[0]) + M5_API_PATH
        )
    }

    private fun notAServiceUrl() = ServiceUrl(ServiceUrlReason.NOT_A_3GPP_SERVICE_URL)

    /**
     * The Media AF endpoints in order of appearance of af-host-address, or the default endpoint when there is none (TS 26.512 clause 4.8.3.2); a
     * port that is omitted is 80 for HTTP and 443 for HTTPS. Null when an af-host-address is not of the form hostname[:port].
     */
    private fun afCandidates(query: List<Pair<String, String?>>, scheme: String): List<AfEndpoint>? {
        val defaultPort = if (scheme == "https") 443 else 80
        val addresses = valuesOf(query, PARAM_AF_HOST_ADDRESS)
        if (addresses.isEmpty()) return listOf(AfEndpoint(DEFAULT_AF_HOST, defaultPort))
        return addresses.map { address ->
            val colon = address.indexOf(':')
            val host = if (colon >= 0) address.substring(0, colon) else address
            val port = if (colon >= 0) address.substring(colon + 1) else null
            if (!HOSTNAME.matches(host)) return null
            if (port == null) {
                AfEndpoint(host, defaultPort)
            } else {
                val number = port.takeIf { it.isNotEmpty() && it.length <= 5 && it.all { c -> c in '0'..'9' } }?.toInt()
                if (number == null || number !in 1..65535) return null
                AfEndpoint(host, number)
            }
        }
    }

    /** {apiRoot} of the M5 requests (TS 26.510 clause 9.2.2): scheme and endpoint, the port left out when it is the default of the scheme */
    private fun apiRoot(scheme: String, endpoint: AfEndpoint): String {
        val port = if (endpoint.port == (if (scheme == "https") 443 else 80)) "" else ":${endpoint.port}"
        return "$scheme://${endpoint.host}$port"
    }

    /** The request URL of the Service Access Information API (TS 26.510 clause 9.2.2), with the external service identifier as one path element */
    private fun serviceAccessInformationUrl(scheme: String, endpoint: AfEndpoint, externalServiceId: String): String =
        "${apiRoot(scheme, endpoint)}$SERVICE_ACCESS_INFORMATION_PATH${encodeElement(externalServiceId)}"

    /** The parameters of table 12.4-1 as the launch step uses them; null when they are not consistent (clause 4.8.3.3 and table 12.4-1) */
    private fun launchParameters(query: List<Pair<String, String?>>): LaunchParameters? {
        for (name in SINGLE_PARAMS) {
            val occurrences = query.filter { it.first == name }
            if (occurrences.size > 1) return null
            // multipath-mode has no value space (table 12.4-1), its presence is the indication; the other parameters carry a value
            if (name != PARAM_MULTIPATH_MODE && occurrences.any { it.second.isNullOrEmpty() }) return null
        }
        for (name in MULTIPLE_PARAMS) {
            if (query.any { it.first == name && it.second.isNullOrEmpty() }) return null
        }
        val mediaEntryPoint = valuesOf(query, PARAM_MEDIA_ENTRY_POINT).firstOrNull()
        val serviceOperationPoint = valuesOf(query, PARAM_SERVICE_OPERATION_POINT).firstOrNull()
        val estimatedVolume = valuesOf(query, PARAM_ESTIMATED_VOLUME).firstOrNull()
        val contentTypes = valuesOf(query, PARAM_CONTENT_TYPE)
        val profiles = valuesOf(query, PARAM_PROFILE)
        val mode = valuesOf(query, PARAM_CLIENT_DATA_REPORTING_MODE).firstOrNull()
        // table 12.4-1 media-entry-point: a fully qualified URL
        if (mediaEntryPoint != null && !isAbsoluteUrl(mediaEntryPoint)) return null
        // clause 4.8.3.3 step 3: estimated-volume requires service-operation-point
        if (estimatedVolume != null && serviceOperationPoint == null) return null
        // table 12.4-1: content-type and profile are an error together with media-entry-point
        if (mediaEntryPoint != null && (contentTypes.isNotEmpty() || profiles.isNotEmpty())) return null
        // table 12.4-1 client-data-reporting-mode: one of the enumeration (more than one mode is an error)
        if (mode != null && mode !in CLIENT_DATA_REPORTING_MODES) return null
        return LaunchParameters(
            mediaEntryPoint, serviceOperationPoint, estimatedVolume,
            valuesOf(query, PARAM_CONTENT_ID).firstOrNull(),
            valuesOf(query, PARAM_ACCESS_TOKEN).firstOrNull(),
            valuesOf(query, PARAM_DELIVERY_PROTOCOL), contentTypes, profiles,
            query.firstOrNull { it.first == PARAM_MULTIPATH_MODE }?.let { it.second.orEmpty() },
            mode
        )
    }

    private fun valuesOf(query: List<Pair<String, String?>>, name: String): List<String> =
        query.filter { it.first == name }.mapNotNull { it.second }

    private fun isAbsoluteUrl(value: String) =
        ABSOLUTE_URL.matches(value) && conforms(value) { isUnreserved(it) || it in ":/?#[]@" || isSubDelim(it) }

    // characters of RFC 3986: unreserved, sub-delims and pchar
    private fun isUnreserved(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-._~"

    private fun isSubDelim(c: Char) = c in "!$&'()*+,;="

    private fun isPchar(c: Char) = isUnreserved(c) || isSubDelim(c) || c == ':' || c == '@'

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** True when every character is allowed or part of a well-formed percent-encoded octet */
    private fun conforms(text: String, allowed: (Char) -> Boolean): Boolean {
        var i = 0
        while (i < text.length) {
            if (text[i] == '%') {
                if (i + 2 >= text.length || !isHex(text[i + 1]) || !isHex(text[i + 2])) return false
                i += 3
            } else {
                if (!allowed(text[i])) return false
                i++
            }
        }
        return true
    }

    /** Percent-decoding of text that conforms (ASCII only); octets are read as UTF-8 */
    private fun decode(text: String): String {
        val octets = ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            if (text[i] == '%') {
                octets.write(text.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                octets.write(text[i].code)
                i++
            }
        }
        return String(octets.toByteArray(), Charsets.UTF_8)
    }

    /** Percent-encoding of one path element: everything but unreserved characters is escaped */
    private fun encodeElement(text: String): String {
        val encoded = StringBuilder()
        for (octet in text.toByteArray(Charsets.UTF_8)) {
            val value = octet.toInt() and 0xFF
            if (isUnreserved(value.toChar())) encoded.append(value.toChar()) else encoded.append('%').append("%02X".format(value))
        }
        return encoded.toString()
    }
}
