package com.fivegmag.a5gmsmediasessionhandler.controller

import com.fivegmag.a5gmscommonlibrary.helpers.RecommendationSources
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerBundleKeys as Keys
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerEvents
import com.fivegmag.a5gmscommonlibrary.models.NetworkAssistanceSession
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmsmediasessionhandler.network.M5Result
import com.fivegmag.a5gmsmediasessionhandler.network.NetworkAssistanceM5Client

/** The return value of requestBitRateRecommendation() (table 11.4.1.1-2) */
data class BitRateRecommendation(val recommendedDownlinkBitRate: String, val recommendedUplinkBitRate: String)

/**
 * The Network Assistance client API of one media delivery session (TS 26.510 V19.2.0 clause 11.4): requestBitRateRecommendation(),
 * requestDeliveryBoost() and their events. Like DynamicPolicyService it has no platform classes and blocks on the M5 operations.
 *
 * "If it has not already done so, the Media Session Handler shall first create a Network Assistance Session": the session is created at the first
 * request and kept for the media delivery session. It describes the application flow of the service component as the first address of the Media AS
 * (one session cites one packet filter, clause 5.3.4.2), and cites the Policy Template of the dynamic QoS policy in force. When that policy, or
 * the component, differs from what the session cites, the session is replaced (clause 5.3.4.6).
 *
 * The source of every recommendation is the Media AF; recommendations from the UE modem are not obtained.
 */
class NetworkAssistanceService(
    val mediaDeliverySessionId: String,
    private val serviceAccessInformation: () -> ServiceAccessInformation?,
    private val clientFor: (ServiceAccessInformation) -> NetworkAssistanceM5Client?,
    private val endpoints: () -> FlowEndpoints?,
    private val domainNames: () -> List<String>,
    private val activePolicyTemplateId: () -> String?,
    private val emit: (ClientEvent) -> Unit
) {
    var lastFailure: String? = null
        private set

    /** Subscribe to, and leave, the sub-topic of the notification channel that is named by the identifier of the Network Assistance Session (clauses 5.3.4.2, 5.3.4.7) */
    var subscribeTopic: (String) -> Unit = {}
    var unsubscribeTopic: (String) -> Unit = {}

    private var session: NetworkAssistanceSession? = null
    private var client: NetworkAssistanceM5Client? = null
    private var provisioningSessionId: String? = null

    val sessionIdentifier: String? @Synchronized get() = session?.naSessionId

    /** The Network Assistance Session for the component: created if there is none, replaced if what it cites has changed; null with lastFailure otherwise */
    private fun ensureSession(componentReference: String): NetworkAssistanceSession? {
        val sai = serviceAccessInformation() ?: return fail("no Service Access Information")
        val m5 = clientFor(sai) ?: return fail("the Service Access Information has no Network Assistance server")
        val held = session
        val template = activePolicyTemplateId()
        val component = if (template != null) componentReference else null
        if (held != null && held.policyTemplateId == template && held.componentReference == component) return held

        val flowEndpoints = endpoints() ?: return fail("the network of the UE is not known")
        val ue = flowEndpoints.ueIpv4Address() ?: return fail("the UE has no IPv4 address")
        val flow = domainNames().flatMap { flowEndpoints.resolve(it) }.let { DynamicPolicyRequests.flowDescriptions(ue, it) }.firstOrNull()
            ?: return fail("the Media AS domain name(s) of the media delivery session do not resolve to an IPv4 address")
        val request = DynamicPolicyRequests.networkAssistanceSession(mediaDeliverySessionId, flow, template, componentReference)
        val result: M5Result<NetworkAssistanceSession> =
            if (held?.naSessionId != null) m5.replace(sai.provisioningSessionId, held.naSessionId!!, request)
            else m5.create(sai.provisioningSessionId, request)
        if (!result.isSuccess) return fail(describe(result))
        client = m5
        provisioningSessionId = sai.provisioningSessionId
        // 204 on a replacement: nothing changed
        session = result.resource ?: held
        if (held?.naSessionId == null) session?.naSessionId?.let(subscribeTopic)
        return session
    }

    private fun fail(reason: String): Nothing? {
        lastFailure = reason
        return null
    }

    private fun describe(result: M5Result<*>) = result.problem?.detail ?: result.problem?.title ?: result.failure ?: "HTTP status code ${result.code}"

    private fun error(event: String): Nothing? {
        emit(ClientEvent.of(event, mediaDeliverySessionId, Keys.RECOMMENDATION_SOURCE to RecommendationSources.MEDIA_AF))
        return null
    }

    /**
     * requestBitRateRecommendation() (clause 11.4.1.1). The recommendation is the maximumRequestedBitRate of each direction of the
     * ClientPolicySpecification that the Media AF returns (clause 5.3.4.4 names it as the maximum); BIT_RATE_RECOMMENDATION is sent with it.
     */
    @Synchronized
    fun requestBitRateRecommendation(componentReference: String): BitRateRecommendation? {
        lastFailure = null
        val created = ensureSession(componentReference)
        val id = created?.naSessionId
        val m5 = client
        val provisioning = provisioningSessionId
        if (id == null || m5 == null || provisioning == null) return error(SessionHandlerEvents.Error.ERROR_BIT_RATE_RECOMMENDATION)
        val result = m5.recommendation(provisioning, id)
        val rates = result.resource
        if (!result.isSuccess || rates == null) {
            lastFailure = if (result.isSuccess) "the Media AF returned no recommendation" else describe(result)
            return error(SessionHandlerEvents.Error.ERROR_BIT_RATE_RECOMMENDATION)
        }
        val recommendation = BitRateRecommendation(rates.downlinkBitRates.maximumRequestedBitRate, rates.uplinkBitRates.maximumRequestedBitRate)
        emit(recommendationEvent(recommendation))
        return recommendation
    }

    fun recommendationEvent(recommendation: BitRateRecommendation) = ClientEvent.of(
        SessionHandlerEvents.NetworkAssistance.BIT_RATE_RECOMMENDATION, mediaDeliverySessionId,
        Keys.RECOMMENDATION_SOURCE to RecommendationSources.MEDIA_AF,
        Keys.RECOMMENDED_DOWNLINK_BIT_RATE to recommendation.recommendedDownlinkBitRate,
        Keys.RECOMMENDED_UPLINK_BIT_RATE to recommendation.recommendedUplinkBitRate
    )

    /**
     * requestDeliveryBoost() (clause 11.4.1.2): true if the Media AF reports the boost as granted. A boost that the Media AF answers as not
     * applied is false without an event; a refusal or a failure is false with ERROR_DELIVERY_BOOST.
     */
    @Synchronized
    fun requestDeliveryBoost(componentReference: String): Boolean {
        lastFailure = null
        val created = ensureSession(componentReference)
        val id = created?.naSessionId
        val m5 = client
        val provisioning = provisioningSessionId
        if (id == null || m5 == null || provisioning == null) {
            boostError()
            return false
        }
        val result = m5.boostRequest(provisioning, id)
        val outcome = result.resource
        if (!result.isSuccess || outcome == null) {
            lastFailure = if (result.isSuccess) "the Media AF returned no result" else describe(result)
            boostError()
            return false
        }
        if (!outcome.success) lastFailure = outcome.reason ?: "the Media AF did not apply the delivery boost"
        return outcome.success
    }

    private fun boostError() {
        emit(ClientEvent.of(SessionHandlerEvents.Error.ERROR_DELIVERY_BOOST, mediaDeliverySessionId, Keys.RECOMMENDATION_SOURCE to RecommendationSources.MEDIA_AF))
    }

    /** Destroy the Network Assistance Session when the media delivery session ends (clause 5.3.4.7); no event */
    @Synchronized
    fun release() {
        val id = session?.naSessionId
        val provisioning = provisioningSessionId
        if (id != null && provisioning != null) client?.destroy(provisioning, id)
        id?.let(unsubscribeTopic)
        session = null
    }

    /**
     * A NOTIFICATION_NETWORK_ASSISTANCE_SESSION of the Media AF for the session (clause 5.3.4.2): "an up-to-date bit rate recommendation whenever this
     * changes" is BIT_RATE_RECOMMENDATION (table 11.4.2-2). A notification for another session, or one whose recommendation did not change, sends nothing.
     */
    @Synchronized
    fun onNotification(notified: NetworkAssistanceSession) {
        val held = session ?: return
        if (notified.naSessionId != held.naSessionId) return
        session = notified
        val recommended = notified.recommendedQoS ?: return
        if (recommended == held.recommendedQoS) return
        emit(
            recommendationEvent(
                BitRateRecommendation(recommended.downlinkBitRates.maximumRequestedBitRate, recommended.uplinkBitRates.maximumRequestedBitRate)
            )
        )
    }

    // The payloads of the replies (tables 11.4.1.1-2 and 11.4.1.2-2)

    fun recommendationReply(componentReference: String, recommendation: BitRateRecommendation?) = linkedMapOf<String, Any?>(
        Keys.MEDIA_DELIVERY_SESSION_IDENTIFIER to mediaDeliverySessionId,
        Keys.COMPONENT_REFERENCE to componentReference,
        Keys.SUCCEEDED to (recommendation != null),
        Keys.RECOMMENDED_DOWNLINK_BIT_RATE to recommendation?.recommendedDownlinkBitRate,
        Keys.RECOMMENDED_UPLINK_BIT_RATE to recommendation?.recommendedUplinkBitRate,
        Keys.ERROR_REASON to if (recommendation == null) lastFailure else null
    )

    fun boostReply(componentReference: String, granted: Boolean) = linkedMapOf<String, Any?>(
        Keys.MEDIA_DELIVERY_SESSION_IDENTIFIER to mediaDeliverySessionId,
        Keys.COMPONENT_REFERENCE to componentReference,
        Keys.SUCCEEDED to granted,
        Keys.ERROR_REASON to if (!granted) lastFailure else null
    )
}
