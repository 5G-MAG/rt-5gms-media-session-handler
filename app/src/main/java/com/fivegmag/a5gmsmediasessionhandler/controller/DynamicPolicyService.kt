package com.fivegmag.a5gmsmediasessionhandler.controller

import com.fivegmag.a5gmscommonlibrary.helpers.MediaPlayerCapabilities
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerBundleKeys as Keys
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerEvents
import com.fivegmag.a5gmscommonlibrary.models.BdtWindow
import com.fivegmag.a5gmscommonlibrary.models.ClientPolicySpecification
import com.fivegmag.a5gmscommonlibrary.models.DynamicPolicy
import com.fivegmag.a5gmscommonlibrary.models.DynamicPolicyStatus
import com.fivegmag.a5gmscommonlibrary.models.PolicyTemplateBinding
import com.fivegmag.a5gmscommonlibrary.models.QosMonitoringReport
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmscommonlibrary.models.TypedLocation
import com.fivegmag.a5gmsmediasessionhandler.network.DynamicPolicyM5Client
import com.fivegmag.a5gmsmediasessionhandler.network.M5Result
import java.time.OffsetDateTime

/** What the Media Session Handler needs to know about the network of the UE to describe the application flows of a media delivery session */
interface FlowEndpoints {
    /** The IPv4 address of the UE on the network that carries the media, or null */
    fun ueIpv4Address(): String?

    /** The IPv4 addresses that a Media AS domain name resolves to */
    fun resolve(domainName: String): List<String>
}

/** The return value of getBDTInfo() (table 11.3.1.1-2); null when the invocation is unsuccessful, with the reason in the reply */
data class BdtInfo(val windowStart: String, val windowEnd: String)

/** The return value of activatePolicy() (table 11.3.1.2-2) */
data class ActivationResult(
    val recommendedDownlinkBitRate: String?,
    val recommendedUplinkBitRate: String?,
    val grantedBackgroundDataTransferWindows: List<BdtWindow>,
    val l4SEnabled: Boolean,
    val qoSMonitoringEnabled: Boolean
)

/**
 * The Dynamic Policy client API of one media delivery session (TS 26.510 V19.2.0 clause 11.3): the Service Operation Points of the Service
 * Access Information, getBDTInfo(), activatePolicy(), deactivatePolicy(), the status currentDynamicPolicies and the events. The methods
 * block on the M5 operations; the caller runs them off the main thread. Each method reports why it failed in lastFailure.
 *
 * The scope of the policy is all application flows that match the Media AS domain name(s) of the media delivery session (clause 11.3.1.2);
 * the flows are described by one of the methods of sdfMethods (a domain name as it is, or the addresses of the Media AS that it resolves to).
 *
 * L4S (TS 26.512 V19.3.0 clause 4.7.3): the Media Session Handler interrogates the capabilities of the Media Player once per media delivery
 * session (playerCapabilities), at the first activation. It sets l4SRequired in the qosSpecification only if the Service Operation Point
 * prefers L4S and the Media Player declares CAPABILITY_L4S; table 11.3.1.2-2 calls l4SEnabled the activation "by both the Media AF and by
 * the Media Access Function", so l4SEnabled, L4S_ENABLED and the status report ECN marking for L4S only for a Media Player with CAPABILITY_L4S.
 * qoSMonitoringRequired is set only if the Service Operation Point prefers QoS monitoring and the Media Player declares CAPABILITY_QOS (clause
 * 4.7.3). The Media AF's qoSMonitoringEnabled is returned and kept in the status; QOS_MONITORING_ENABLED and QOS_MONITORING_RESULTS go only to
 * a Media Player with CAPABILITY_QOS, which clause 4.9.3 has subscribe to them ("If the Media Player is capable of consuming QoS monitoring results").
 * A requirement needs a qosSpecification (clause 5.3.3.2 item 4), so neither flag is sent without target bit rates.
 */
class DynamicPolicyService(
    val mediaDeliverySessionId: String,
    private val serviceAccessInformation: () -> ServiceAccessInformation?,
    private val clientFor: (ServiceAccessInformation) -> DynamicPolicyM5Client?,
    private val endpoints: () -> FlowEndpoints?,
    private val domainNames: () -> List<String>,
    private val now: () -> OffsetDateTime,
    private val emit: (ClientEvent) -> Unit,
    private val location: () -> TypedLocation? = { null },
    /** The capabilities of the Media Player of the media delivery session (table 13.2.4-2); blocks while the Media Player is interrogated */
    private val playerCapabilities: () -> Set<String> = { emptySet() }
) {
    var lastFailure: String? = null
        private set

    /** Subscribe to, and leave, the sub-topic of the notification channel that is named by the identifier of the Dynamic Policy Instance (clauses 5.3.3.2, 5.3.3.5) */
    var subscribeTopic: (String) -> Unit = {}
    var unsubscribeTopic: (String) -> Unit = {}

    /** Called when a dynamic QoS policy has been put in force or taken out of force, so that a Network Assistance Session that cites it can follow (clause 5.3.4.6) */
    var onPolicyChanged: () -> Unit = {}

    private var policy: DynamicPolicy? = null
    private var serviceOperationPointReference: String? = null
    private var provisioningSessionId: String? = null
    private var client: DynamicPolicyM5Client? = null
    private var capabilities: Set<String>? = null

    /** The capabilities of the Media Player, interrogated at the first use (clause 4.7.3: the Media Session Handler "shall interrogate" them) and kept for the session */
    private fun capabilitiesOfPlayer(): Set<String> = capabilities ?: playerCapabilities().also { capabilities = it }

    private fun isL4sCapable() = MediaPlayerCapabilities.CAPABILITY_L4S in (capabilities ?: emptySet())
    private fun isQosCapable() = MediaPlayerCapabilities.CAPABILITY_QOS in (capabilities ?: emptySet())

    // what the policy in force asked for (without the requirements): the Service Operation Point it was activated for and its bit rates
    private var requestedSpecification: ClientPolicySpecification? = null

    fun serviceOperationPoints(): List<PolicyTemplateBinding> =
        serviceAccessInformation()?.dynamicPolicyInvocationConfiguration?.policyTemplateBindings.orEmpty()

    val policyInstanceId: String? @Synchronized get() = policy?.dynamicPolicyId

    /** The Policy Template of the dynamic QoS policy in force, which a Network Assistance Session cites (clause 11.4.1.1); null when none is in force */
    val activePolicyTemplateId: String? @Synchronized get() = policy?.policyTemplateId

    /** The QoS that the client asked for in the dynamic QoS policy in force, which a Network Assistance Session requests as its initial QoS (TS 26.510 V19.2.0 clause 11.4.1.1); null when none was asked for */
    val activeRequestedQoS: ClientPolicySpecification? @Synchronized get() = policy?.applicationFlowBindings?.firstOrNull { it.qosSpecification != null }?.qosSpecification
        ?.copy(l4SRequired = null, qoSMonitoringRequired = null) // the requirements are those of the Dynamic Policy request (clause 5.3.3.2), not of a Network Assistance Session

    private fun fail(reason: String): Nothing? {
        lastFailure = reason
        return null
    }

    private fun invalidServiceOperationPoint(reference: String): Nothing? {
        emit(ClientEvent.of(SessionHandlerEvents.Error.ERROR_INVALID_SERVICE_OPERATION_POINT, mediaDeliverySessionId, Keys.SERVICE_OPERATION_POINT_REFERENCE to reference))
        return fail("the Service Operation Point [$reference] is not one of this media delivery session")
    }

    /** getBDTInfo() (clause 11.3.1.1): the next Background Data Transfer window that the Service Operation Point offers */
    @Synchronized
    fun getBdtInfo(reference: String): BdtInfo? {
        lastFailure = null
        val binding = DynamicPolicyRequests.bindingFor(serviceAccessInformation(), reference) ?: return invalidServiceOperationPoint(reference)
        val window = DynamicPolicyRequests.nextWindow(binding, now()) ?: return fail("the Service Operation Point offers no Background Data Transfer window")
        return BdtInfo(window.timeWindow.startTime, window.timeWindow.stopTime)
    }

    /**
     * activatePolicy() (clause 11.3.1.2). A policy that is in force is replaced by the new one (the flows of one media delivery session do not
     * overlap, clause 5.3.3.1). targetDownlinkBitRate and targetUplinkBitRate are not parameters of the method: see Keys.TARGET_DOWNLINK_BIT_RATE.
     */
    @Synchronized
    fun activate(
        reference: String,
        estimatedTransferVolume: Long?,
        componentReference: String? = null,
        targetDownlinkBitRate: String? = null,
        targetUplinkBitRate: String? = null
    ): ActivationResult? {
        lastFailure = null
        val sai = serviceAccessInformation() ?: return fail("no Service Access Information")
        val binding = DynamicPolicyRequests.bindingFor(sai, reference) ?: return invalidServiceOperationPoint(reference)

        // clause 5.3.3.2 item 5: the Policy Template that is provisioned with a Background Data Transfer specification needs the bdtSpecification
        val isBackgroundDataTransfer = !binding.bdtWindows.isNullOrEmpty()
        var window: BdtWindow? = null
        if (isBackgroundDataTransfer) {
            if (estimatedTransferVolume == null || estimatedTransferVolume < 1) return backgroundDataTransferError("estimatedTransferVolume of at least 1 byte is required by a Background Data Transfer policy")
            window = DynamicPolicyRequests.nextWindow(binding, now()) ?: return backgroundDataTransferError("the Service Operation Point offers no Background Data Transfer window that has not ended")
        }

        val domains = domainNames()
        if (domains.isEmpty()) return fail("the media delivery session has no Media AS domain name")
        // sdfMethods of the Service Access Information says how the application flows are described
        val sdfMethods = sai.dynamicPolicyInvocationConfiguration?.sdfMethods
        val method = DynamicPolicyRequests.filterMethodFor(sdfMethods)
            ?: return fail("none of the Service Data Flow description methods $sdfMethods can be used")
        var ue = ""
        var addresses = emptyList<String>()
        // a domain name is described as it is: neither the UE address nor the addresses of the Media AS are needed
        if (method != DynamicPolicyRequests.FILTER_METHOD_DOMAIN_NAME) {
            val flowEndpoints = endpoints() ?: return fail("the network of the UE is not known")
            ue = flowEndpoints.ueIpv4Address() ?: return fail("the UE has no IPv4 address")
            addresses = domains.flatMap { flowEndpoints.resolve(it) }
            if (addresses.isEmpty()) return fail("the Media AS domain name(s) do not resolve to an IPv4 address")
        }
        val m5 = clientFor(sai) ?: return fail("the Service Access Information has no Dynamic Policy invocation server")

        val playerCapabilities = capabilitiesOfPlayer()
        val request = DynamicPolicyRequests.dynamicPolicy(
            mediaDeliverySessionId, binding, DynamicPolicyRequests.flowDescriptions(method, ue, addresses, domains), componentReference,
            DynamicPolicyRequests.qosSpecification(
                targetDownlinkBitRate, targetUplinkBitRate,
                DynamicPolicyRequests.l4SRequired(binding, playerCapabilities), DynamicPolicyRequests.qoSMonitoringRequired(binding, playerCapabilities)
            ), window, estimatedTransferVolume,
            DynamicPolicyRequests.locationFor(sai, location)
        )
        val held = policy
        val result: M5Result<DynamicPolicy> =
            if (held?.dynamicPolicyId != null) m5.replace(sai.provisioningSessionId, held.dynamicPolicyId!!, request)
            else m5.create(sai.provisioningSessionId, request)

        if (!result.isSuccess) return refused(result, reference, isBackgroundDataTransfer)

        // 204 on a replacement: nothing changed in the resource
        val resource = result.resource ?: held ?: return fail("the Media AF returned no Dynamic Policy Instance")
        client = m5
        provisioningSessionId = sai.provisioningSessionId
        if (held?.dynamicPolicyId == null) resource.dynamicPolicyId?.let(subscribeTopic)
        policy = resource
        serviceOperationPointReference = reference
        requestedSpecification = DynamicPolicyRequests.qosSpecification(targetDownlinkBitRate, targetUplinkBitRate)
        return announce(resource, reference)
    }

    /**
     * The notification of a change of the Service Operation Point at M11 (TS 26.510 V19.2.0 clause 5.4.3, TS 26.512 V19.3.0 table 13.2.5-1
     * OPERATION_POINT_CHANGED): "If the QoS of the new Service Operation Point is not satisfied by the currently instantiated Dynamic Policy, the
     * Media Session Handler shall attempt to instantiate a Dynamic Policy satisfying the Media Access Function's requirements [...] if the target QoS
     * lies within the bounds of a Policy Template with the corresponding external reference advertised in the Service Access Information". The
     * policy in force satisfies the new point when it was activated for the same external reference with the same target bit rates. The bounds
     * of a Policy Template are not in the Service Access Information, so the Media AF decides them when it creates the policy (a refusal is the
     * usual one). A Background Data Transfer point needs an estimated volume that the notification does not carry: it is not instantiated here.
     * Returns the activation, or null when nothing was instantiated (the reason is in lastFailure).
     */
    @Synchronized
    fun onOperationPointChanged(reference: String, targetDownlinkBitRate: String?, targetUplinkBitRate: String?): ActivationResult? {
        lastFailure = null
        val binding = DynamicPolicyRequests.bindingFor(serviceAccessInformation(), reference)
            ?: return fail("no Policy Template with the external reference [$reference] is advertised")
        if (!binding.bdtWindows.isNullOrEmpty()) return fail("the Service Operation Point [$reference] is a Background Data Transfer policy, which needs an estimated volume")
        if (policy != null && serviceOperationPointReference == reference &&
            requestedSpecification == DynamicPolicyRequests.qosSpecification(targetDownlinkBitRate, targetUplinkBitRate)
        ) return fail("the Dynamic Policy in force satisfies the Service Operation Point [$reference]")
        return activate(reference, null, null, targetDownlinkBitRate, targetUplinkBitRate)
    }

    private fun backgroundDataTransferError(reason: String): Nothing? {
        emit(ClientEvent.of(SessionHandlerEvents.Error.ERROR_BACKGROUND_DATA_TRANSFER, mediaDeliverySessionId, Keys.ERROR_REASON to reason))
        return fail(reason)
    }

    /**
     * The Media AF refused or did not answer. 401 and 403 are the application not being authorised to instantiate the policy
     * (ERROR_UNAUTHORISED); a 400 that names policyTemplateId is the reference not being valid (ERROR_INVALID_SERVICE_OPERATION_POINT);
     * any other failure of a Background Data Transfer policy is ERROR_BACKGROUND_DATA_TRANSFER with the reason.
     */
    private fun refused(result: M5Result<DynamicPolicy>, reference: String, isBackgroundDataTransfer: Boolean): Nothing? {
        val reason = result.problem?.detail ?: result.problem?.title ?: result.failure ?: "HTTP status code ${result.code}"
        when {
            result.isUnauthorised -> emit(ClientEvent.of(SessionHandlerEvents.Error.ERROR_UNAUTHORISED, mediaDeliverySessionId, Keys.SERVICE_OPERATION_POINT_REFERENCE to reference))
            result.code == 400 && result.complainsAbout("policyTemplateId") -> return invalidServiceOperationPoint(reference)
            isBackgroundDataTransfer -> emit(ClientEvent.of(SessionHandlerEvents.Error.ERROR_BACKGROUND_DATA_TRANSFER, mediaDeliverySessionId, Keys.ERROR_REASON to reason))
        }
        return fail(reason)
    }

    /** The return value and the events of a policy that is in force */
    private fun announce(resource: DynamicPolicy, reference: String): ActivationResult {
        val qos = resource.applicationFlowBindings.firstOrNull { it.qosSpecification != null }?.qosSpecification
        val granted = resource.bdtSpecification?.windows.orEmpty()
        val l4s = resource.l4SEnabled == true && isL4sCapable()
        val qosMonitoring = resource.qoSMonitoringEnabled == true
        val result = ActivationResult(
            qos?.downlinkBitRates?.maximumRequestedBitRate, qos?.uplinkBitRates?.maximumRequestedBitRate, granted, l4s, qosMonitoring
        )
        emit(
            ClientEvent.of(
                SessionHandlerEvents.DynamicPolicy.POLICY_ACTIVATED, mediaDeliverySessionId,
                Keys.SERVICE_OPERATION_POINT_REFERENCE to reference,
                Keys.WINDOW_START to granted.firstOrNull()?.timeWindow?.startTime,
                Keys.WINDOW_END to granted.firstOrNull()?.timeWindow?.stopTime,
                Keys.RECOMMENDED_DOWNLINK_BIT_RATE to result.recommendedDownlinkBitRate,
                Keys.RECOMMENDED_UPLINK_BIT_RATE to result.recommendedUplinkBitRate
            )
        )
        if (l4s) emit(ClientEvent.of(SessionHandlerEvents.DynamicPolicy.L4S_ENABLED, mediaDeliverySessionId))
        if (qosMonitoring && isQosCapable()) emit(ClientEvent.of(SessionHandlerEvents.DynamicPolicy.QOS_MONITORING_ENABLED, mediaDeliverySessionId))
        return result
    }

    /** deactivatePolicy() (clause 11.3.1.3): true when the policy is no longer applied; a policy that the Media AF no longer has (404, 410) is gone */
    @Synchronized
    fun deactivate(): Boolean {
        lastFailure = null
        val held = policy
        val id = held?.dynamicPolicyId
        val m5 = client
        val reference = serviceOperationPointReference
        val session = provisioningSessionId
        if (held == null || id == null || m5 == null || reference == null || session == null) {
            lastFailure = "no Dynamic Policy is in force for this media delivery session"
            return false
        }
        val result = m5.destroy(session, id)
        if (!result.isSuccess && result.code != 404 && result.code != 410) {
            lastFailure = result.problem?.detail ?: result.failure ?: "HTTP status code ${result.code}"
            return false
        }
        unsubscribeTopic(id)
        clear()
        emit(ClientEvent.of(SessionHandlerEvents.DynamicPolicy.POLICY_DEACTIVATED, mediaDeliverySessionId, Keys.SERVICE_OPERATION_POINT_REFERENCE to reference))
        onPolicyChanged()
        return true
    }

    /** Release the policy at the Media AF when the media delivery session ends; no event is sent */
    @Synchronized
    fun release() {
        val id = policy?.dynamicPolicyId
        val session = provisioningSessionId
        if (id != null && session != null) client?.destroy(session, id)
        id?.let(unsubscribeTopic)
        clear()
    }

    private fun clear() {
        policy = null
        serviceOperationPointReference = null
        requestedSpecification = null
    }

    /** The status currentDynamicPolicies of this media delivery session (table 11.3.2-1); null when no policy is in force */
    @Synchronized
    fun status(): DynamicPolicyStatus? {
        val held = policy ?: return null
        val reference = serviceOperationPointReference ?: return null
        return DynamicPolicyStatus(
            mediaDeliverySessionId, reference, held.dynamicPolicyId ?: "",
            ArrayList(held.bdtSpecification?.windows.orEmpty()),
            held.l4SEnabled == true && isL4sCapable(), held.qoSMonitoringEnabled == true, held.qoSMonitoringResults
        )
    }

    // The payloads of the replies (tables 11.3.1.1-2, 11.3.1.2-2, 11.3.1.3-2)

    fun bdtInfoReply(reference: String, info: BdtInfo?) = linkedMapOf<String, Any?>(
        Keys.MEDIA_DELIVERY_SESSION_IDENTIFIER to mediaDeliverySessionId,
        Keys.SERVICE_OPERATION_POINT_REFERENCE to reference,
        Keys.SUCCEEDED to (info != null),
        Keys.WINDOW_START to info?.windowStart,
        Keys.WINDOW_END to info?.windowEnd,
        Keys.ERROR_REASON to if (info == null) lastFailure else null
    )

    fun activationReply(reference: String, result: ActivationResult?) = linkedMapOf<String, Any?>(
        Keys.MEDIA_DELIVERY_SESSION_IDENTIFIER to mediaDeliverySessionId,
        Keys.SERVICE_OPERATION_POINT_REFERENCE to reference,
        Keys.SUCCEEDED to (result != null),
        Keys.RECOMMENDED_DOWNLINK_BIT_RATE to result?.recommendedDownlinkBitRate,
        Keys.RECOMMENDED_UPLINK_BIT_RATE to result?.recommendedUplinkBitRate,
        Keys.GRANTED_BACKGROUND_DATA_TRANSFER_WINDOWS to result?.let { ArrayList(it.grantedBackgroundDataTransferWindows) },
        Keys.L4S_ENABLED to result?.l4SEnabled,
        Keys.QOS_MONITORING_ENABLED to result?.qoSMonitoringEnabled,
        Keys.ERROR_REASON to if (result == null) lastFailure else null
    )

    fun deactivationReply(succeeded: Boolean) = linkedMapOf<String, Any?>(
        Keys.MEDIA_DELIVERY_SESSION_IDENTIFIER to mediaDeliverySessionId,
        Keys.SUCCEEDED to succeeded,
        Keys.ERROR_REASON to if (!succeeded) lastFailure else null
    )

    /** The most recent results that the Media AF passed on, kept in the status */
    fun latestQosMonitoringResults(): QosMonitoringReport? = policy?.qoSMonitoringResults

    /**
     * A NOTIFICATION_DYNAMIC_POLICY_INSTANCE of the Media AF for the policy in force (clause 5.3.3.2, table 10.2.3-3). The representation replaces
     * the one held, so the status shows the latest. A flag that became true is signalled; QOS_MONITORING_RESULTS carries the results of a
     * notification for that reason; a cancelled Background Data Transfer window is ERROR_BACKGROUND_DATA_TRANSFER, after which the policy, which the
     * Media AF has taken away with the window, is no longer in force (POLICY_DEACTIVATED; the specification does not say what follows a cancellation).
     * A notification for another instance is ignored.
     */
    @Synchronized
    fun onNotification(notified: DynamicPolicy, reason: String) {
        val held = policy ?: return
        val reference = serviceOperationPointReference ?: return
        if (notified.dynamicPolicyId != held.dynamicPolicyId) return
        policy = notified
        if (notified.l4SEnabled == true && held.l4SEnabled != true && isL4sCapable()) emit(ClientEvent.of(SessionHandlerEvents.DynamicPolicy.L4S_ENABLED, mediaDeliverySessionId))
        if (notified.qoSMonitoringEnabled == true && held.qoSMonitoringEnabled != true && isQosCapable()) {
            emit(ClientEvent.of(SessionHandlerEvents.DynamicPolicy.QOS_MONITORING_ENABLED, mediaDeliverySessionId))
        }
        when (reason) {
            REASON_QOS_MONITORING_RESULTS -> if (isQosCapable()) emit(
                ClientEvent.of(SessionHandlerEvents.DynamicPolicy.QOS_MONITORING_RESULTS, mediaDeliverySessionId, Keys.QOS_MONITORING_RESULTS to notified.qoSMonitoringResults)
            )
            REASON_WINDOW_CANCELLATION -> {
                emit(
                    ClientEvent.of(
                        SessionHandlerEvents.Error.ERROR_BACKGROUND_DATA_TRANSFER, mediaDeliverySessionId,
                        Keys.ERROR_REASON to "the Background Data Transfer window was cancelled by the Media AF"
                    )
                )
                notified.dynamicPolicyId?.let(unsubscribeTopic)
                clear()
                emit(ClientEvent.of(SessionHandlerEvents.DynamicPolicy.POLICY_DEACTIVATED, mediaDeliverySessionId, Keys.SERVICE_OPERATION_POINT_REFERENCE to reference))
                onPolicyChanged()
            }
        }
    }

    /**
     * BACKGROUND_DATA_TRANSFER_OPPORTUNITY (table 11.3.2-2): a Service Access Information update offers a window that the previous one did
     * not offer, for a Service Operation Point, and the window has not ended. Nothing is sent when there was no previous Service Access Information.
     */
    @Synchronized
    fun onServiceAccessInformationUpdated(previous: ServiceAccessInformation?, current: ServiceAccessInformation) {
        if (previous == null) return
        for (opportunity in newOpportunities(previous, current)) {
            val ends = try {
                OffsetDateTime.parse(opportunity.windowEnd)
            } catch (_: java.time.format.DateTimeParseException) {
                continue
            }
            if (!ends.isAfter(now())) continue
            emit(
                ClientEvent.of(
                    SessionHandlerEvents.DynamicPolicy.BACKGROUND_DATA_TRANSFER_OPPORTUNITY, mediaDeliverySessionId,
                    Keys.SERVICE_OPERATION_POINT_REFERENCE to opportunity.reference,
                    Keys.WINDOW_START to opportunity.windowStart,
                    Keys.WINDOW_END to opportunity.windowEnd
                )
            )
        }
    }

    data class Opportunity(val reference: String, val windowStart: String, val windowEnd: String)

    companion object {
        const val REASON_QOS_MONITORING_RESULTS = "NOTIFICATION_REASON_QOS_MONITORING_RESULTS"
        const val REASON_WINDOW_CANCELLATION = "NOTIFICATION_REASON_BACKGROUND_DATA_TRANSFER_WINDOW_CANCELLATION"

        /** The windows of the bindings of the current Service Access Information that the previous one did not offer for the same Service Operation Point */
        fun newOpportunities(previous: ServiceAccessInformation, current: ServiceAccessInformation): List<Opportunity> {
            val known = previous.dynamicPolicyInvocationConfiguration?.policyTemplateBindings.orEmpty()
                .associate { binding -> binding.externalReference to binding.bdtWindows.orEmpty().map { it.timeWindow }.toSet() }
            return current.dynamicPolicyInvocationConfiguration?.policyTemplateBindings.orEmpty().flatMap { binding ->
                binding.bdtWindows.orEmpty().map { it.timeWindow }
                    .filter { it !in (known[binding.externalReference] ?: emptySet()) }
                    .map { Opportunity(binding.externalReference, it.startTime, it.stopTime) }
            }
        }
    }
}
