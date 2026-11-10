package com.fivegmag.a5gmsmediasessionhandler.controller

import com.fivegmag.a5gmscommonlibrary.helpers.DeliveryProtocols
import com.fivegmag.a5gmscommonlibrary.helpers.MultipathModes
import com.fivegmag.a5gmscommonlibrary.helpers.PlayerStates
import com.fivegmag.a5gmscommonlibrary.models.EntryPoint
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import java.time.OffsetDateTime

/** The outcome of handing a URL to the implicit service launch: the launch to carry out, or the status for the requesting application */
data class PreparedLaunch(val launch: ImplicitLaunch?, val status: Int)

/**
 * One implicit service launch of 5G Media Streaming (TS 26.510 V19.2.0 clause 5.4.2.2, TS 26.512 V19.3.0 clause 4.8.3): what the Media Session
 * Handler does with the parameters of an accepted 3GPP Service URL once it holds whole Service Access Information, and the HTTP status that it
 * returns to the requesting application (clause 4.8.3.4, which names no code: the codes are the constants below).
 *
 * Where the text is silent or two sources differ the choice is the narrowest:
 * - Clause 4.8.3.3 item 2 has the Media Session Handler select a Media Entry Point whenever media-entry-point is omitted; the last paragraph of
 *   clause 12.4 says that an application that launches media streaming itself omits media-entry-point and the filtering parameters, and then only
 *   media session handling is initiated. The Media Stream Handler is instantiated only if the URL asks for it: with media-entry-point,
 *   content-type, profile or service-operation-point (the item 3 procedures presume it).
 * - One Media Entry Point is selected (item 2): the first that passes the filters, in the order of SessionController.orderEntryPoints. A
 *   media type is compared without parameters and case-insensitively (RFC 2045 clause 5.1); an entry point without a declared profile does not
 *   satisfy a profile filter.
 * - estimated-volume has no unit in table 12.4-1: a whole number of at least 1 is read as bytes, like estimatedTransferVolume of activatePolicy().
 * - delivery-protocol values other than HTTP_1_1, HTTP_2 and HTTP_3 are dropped (a suggestion, clause 12.4). multipath-mode has no value space:
 *   its presence is the preference (MULTIPATH_PREFERRED).
 * - Of several af-host-address values the first is used; no other is tried (clause 4.8.3.2: "may").
 */
class ImplicitLaunch private constructor(
    private val url: ServiceUrl,
    val estimatedVolume: Long?
) {
    private val parameters: LaunchParameters = url.launch!!

    val externalServiceId: String get() = url.externalServiceId!!

    /** {apiRoot} of the M5 requests of the Media AF that the URL names (clause 4.8.3.2) */
    val m5BaseUrl: String get() = url.m5BaseUrl!!

    val mediaEntryPoint: String? get() = parameters.mediaEntryPoint
    val contentId: String? get() = parameters.contentId
    val accessToken: String? get() = parameters.accessToken
    val serviceOperationPoint: String? get() = parameters.serviceOperationPoint

    /** The status for the requesting application if the launch does not succeed; a step that finds a reason sets it (the Media AF is the default) */
    var failureStatus = STATUS_BAD_GATEWAY
        private set

    /** Whether the URL asks the Media Session Handler to instantiate the Media Stream Handler (see above) */
    val launchesMedia: Boolean
        get() = parameters.mediaEntryPoint != null || parameters.contentTypes.isNotEmpty() || parameters.profiles.isNotEmpty() ||
            parameters.serviceOperationPoint != null

    /** desiredContentDeliveryConfiguration of the Media Player (clause 4.9.1A): the protocols in the order of the URL, and the multipath preference */
    val deliveryProtocols: ArrayList<String>? = parameters.deliveryProtocols.mapNotNull { DELIVERY_PROTOCOLS[it] }.distinct()
        .takeIf { it.isNotEmpty() }?.let { ArrayList(it) }

    val multipathMode: String? = if (parameters.multipathMode != null) MultipathModes.PREFERRED else null

    /**
     * Item 1 and 2 of clause 4.8.3.3: the Media Entry Point of the launch out of the ordered candidates (those of the Service Access Information, or
     * the one that media-entry-point named). Empty when the URL does not ask for the Media Stream Handler, or when no candidate passes the filters.
     */
    fun select(candidates: List<EntryPoint>): ArrayList<EntryPoint> =
        if (!launchesMedia) ArrayList() else ArrayList(candidates.filter { accepts(it) }.take(1))

    private fun accepts(entryPoint: EntryPoint): Boolean {
        val types = parameters.contentTypes.map { mediaType(it) }
        if (types.isNotEmpty() && entryPoint.contentType?.let { mediaType(it) } !in types) return false
        return parameters.profiles.isEmpty() || entryPoint.profiles.orEmpty().any { it in parameters.profiles }
    }

    /**
     * Whether nothing is to be started although whole Service Access Information is held (clause 4.8.3.3): the URL asks for the Media Stream
     * Handler and no Media Entry Point is left; the service-operation-point matches no Policy Template binding; or, with estimated-volume, that
     * binding offers no Background Data Transfer window that has not ended (item 3). The status is then 404.
     */
    fun refuses(serviceAccessInformation: ServiceAccessInformation, selected: List<EntryPoint>, now: OffsetDateTime): Boolean {
        val refused = (launchesMedia && selected.isEmpty()) || parameters.serviceOperationPoint?.let { reference ->
            val binding = DynamicPolicyRequests.bindingFor(serviceAccessInformation, reference)
            binding == null || (estimatedVolume != null && DynamicPolicyRequests.nextWindow(binding, now) == null)
        } == true
        if (refused) failureStatus = STATUS_NOT_FOUND
        return refused
    }

    companion object {
        const val STATUS_OK = 200

        /** The URL is not a 3GPP Service URL for 5G Media Streaming, or its parameters are not consistent (clause 6.3, table 12.4-1) */
        const val STATUS_BAD_REQUEST = 400

        /** The Service Access Information holds nothing that the URL asks for: no Media Entry Point, no Service Operation Point, no window */
        const val STATUS_NOT_FOUND = 404

        /** The Media AF gave no Service Access Information or refused the Dynamic Policy */
        const val STATUS_BAD_GATEWAY = 502

        /**
         * The period without activity of the Media Stream Handler after which a media delivery session that was launched implicitly is terminated
         * (clause 5.4.2.2: "an implementation-specific period of inactivity")
         */
        const val INACTIVITY_PERIOD_MS = 5 * 60 * 1000L

        // the playback states of the Media Stream Handler in which media is being consumed: the session is not inactive then
        private val CONSUMING = setOf(PlayerStates.READY, PlayerStates.PLAYING, PlayerStates.BUFFERING)

        private val DELIVERY_PROTOCOLS = mapOf(
            "HTTP_1_1" to DeliveryProtocols.HTTP_1_1,
            "HTTP_2" to DeliveryProtocols.HTTP_2,
            "HTTP_3" to DeliveryProtocols.HTTP_3
        )

        /** Decomposes the URL (clause 6.3) and prepares the launch; without a launch the status is the error response for the application */
        fun prepare(serviceUrl: String): PreparedLaunch {
            val url = ServiceUrlParser.parse(serviceUrl)
            if (!url.accepted) return PreparedLaunch(null, STATUS_BAD_REQUEST)
            val text = url.launch!!.estimatedVolume
            val volume = text?.let { v -> v.takeIf { it.all { c -> c in '0'..'9' } }?.toLongOrNull()?.takeIf { it >= 1 } }
            if (text != null && volume == null) return PreparedLaunch(null, STATUS_BAD_REQUEST)
            return PreparedLaunch(ImplicitLaunch(url, volume), STATUS_OK)
        }

        /** Whether the Media Stream Handler is consuming media in this playback state, so that the media delivery session is not inactive */
        fun isConsuming(playbackState: String): Boolean = playbackState in CONSUMING

        private fun mediaType(value: String) = value.substringBefore(';').trim().lowercase()
    }
}
