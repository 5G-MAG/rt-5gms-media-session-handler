package com.fivegmag.a5gmsmediasessionhandler.controller

import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerBundleKeys as Keys
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerEvents
import com.fivegmag.a5gmscommonlibrary.models.BdtWindow
import com.fivegmag.a5gmscommonlibrary.models.ClientPolicySpecification
import com.fivegmag.a5gmscommonlibrary.models.DynamicPolicy
import com.fivegmag.a5gmscommonlibrary.models.DynamicPolicyStatus
import com.fivegmag.a5gmscommonlibrary.models.PolicyTemplateBinding
import com.fivegmag.a5gmscommonlibrary.models.QosMonitoringReport
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
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
 * the Media AF accepts only 5_TUPLE descriptions, so the domain names are resolved to the addresses of the Media AS.
 *
 * L4S: this client cannot mark packets with ECN and does not try. It reads l4SEnabled and qoSMonitoringEnabled from the Media AF, returns
 * them, sends L4S_ENABLED and QOS_MONITORING_ENABLED when they are true and keeps them in the status; it never sets l4SRequired or
 * qoSMonitoringRequired (clause 4.7.3 of TS 26.512 V19.3.0 allows l4SRequired only when an L4S-capable transport stack is in use).
 */
class DynamicPolicyService(
    val mediaDeliverySessionId: String,
    private val serviceAccessInformation: () -> ServiceAccessInformation?,
    private val clientFor: (ServiceAccessInformation) -> DynamicPolicyM5Client?,
    private val endpoints: () -> FlowEndpoints?,
    private val domainNames: () -> List<String>,
    private val now: () -> OffsetDateTime,
    private val emit: (ClientEvent) -> Unit
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

    fun serviceOperationPoints(): List<PolicyTemplateBinding> =
        serviceAccessInformation()?.dynamicPolicyInvocationConfiguration?.policyTemplateBindings.orEmpty()

    val policyInstanceId: String? @Synchronized get() = policy?.dynamicPolicyId

    /** The Policy Template of the dynamic QoS policy in force, which a Network Assistance Session cites (clause 11.4.1.1); null when none is in force */
    val activePolicyTemplateId: String? @Synchronized get() = policy?.policyTemplateId

    /** The QoS that the client asked for in the dynamic QoS policy in force, which a Network Assistance Session requests as its initial QoS (TS 26.510 V19.2.0 clause 11.4.1.1); null when none was asked for */
    val activeRequestedQoS: ClientPolicySpecification? @Synchronized get() = policy?.applicationFlowBindings?.firstOrNull { it.qosSpecification != null }?.qosSpecification

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
        val flowEndpoints = endpoints() ?: return fail("the network of the UE is not known")
        val ue = flowEndpoints.ueIpv4Address() ?: return fail("the UE has no IPv4 address")
        val addresses = domains.flatMap { flowEndpoints.resolve(it) }
        if (addresses.isEmpty()) return fail("the Media AS domain name(s) do not resolve to an IPv4 address")
        val m5 = clientFor(sai) ?: return fail("the Service Access Information has no Dynamic Policy invocation server")

        val request = DynamicPolicyRequests.dynamicPolicy(
            mediaDeliverySessionId, binding, DynamicPolicyRequests.flowDescriptions(ue, addresses), componentReference,
            DynamicPolicyRequests.qosSpecification(targetDownlinkBitRate, targetUplinkBitRate), window, estimatedTransferVolume
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
        return announce(resource, reference)
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
        val l4s = resource.l4SEnabled == true
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
        if (qosMonitoring) emit(ClientEvent.of(SessionHandlerEvents.DynamicPolicy.QOS_MONITORING_ENABLED, mediaDeliverySessionId))
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
    }

    /** The status currentDynamicPolicies of this media delivery session (table 11.3.2-1); null when no policy is in force */
    @Synchronized
    fun status(): DynamicPolicyStatus? {
        val held = policy ?: return null
        val reference = serviceOperationPointReference ?: return null
        return DynamicPolicyStatus(
            mediaDeliverySessionId, reference, held.dynamicPolicyId ?: "",
            ArrayList(held.bdtSpecification?.windows.orEmpty()),
            held.l4SEnabled == true, held.qoSMonitoringEnabled == true, held.qoSMonitoringResults
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
        if (notified.l4SEnabled == true && held.l4SEnabled != true) emit(ClientEvent.of(SessionHandlerEvents.DynamicPolicy.L4S_ENABLED, mediaDeliverySessionId))
        if (notified.qoSMonitoringEnabled == true && held.qoSMonitoringEnabled != true) {
            emit(ClientEvent.of(SessionHandlerEvents.DynamicPolicy.QOS_MONITORING_ENABLED, mediaDeliverySessionId))
        }
        when (reason) {
            REASON_QOS_MONITORING_RESULTS -> emit(
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
