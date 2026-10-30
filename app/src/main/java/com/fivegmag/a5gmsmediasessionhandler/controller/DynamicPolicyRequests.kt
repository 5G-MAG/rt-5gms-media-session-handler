package com.fivegmag.a5gmsmediasessionhandler.controller

import com.fivegmag.a5gmscommonlibrary.models.ApplicationFlowBinding
import com.fivegmag.a5gmscommonlibrary.models.ApplicationFlowDescription
import com.fivegmag.a5gmscommonlibrary.models.BdtWindow
import com.fivegmag.a5gmscommonlibrary.models.ClientBdtSpecification
import com.fivegmag.a5gmscommonlibrary.models.ClientPolicySpecification
import com.fivegmag.a5gmscommonlibrary.models.DynamicPolicy
import com.fivegmag.a5gmscommonlibrary.models.IpAddr
import com.fivegmag.a5gmscommonlibrary.models.IpPacketFilterSet
import com.fivegmag.a5gmscommonlibrary.models.NetworkAssistanceSession
import com.fivegmag.a5gmscommonlibrary.models.PolicyTemplateBinding
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmscommonlibrary.models.UnidirectionalBitRateSpecification
import com.fivegmag.a5gmscommonlibrary.models.UsageThreshold
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * The representations that the Media Session Handler sends to the Media AF at reference point M5 for the Dynamic Policy API and the Network
 * Assistance API (TS 26.510 V19.2.0 clauses 5.3.3.2 and 5.3.4.2), and the choices that they need: the Service Operation Point, the
 * Background Data Transfer window and the application flow description.
 */
object DynamicPolicyRequests {
    const val FILTER_METHOD_5_TUPLE = "5_TUPLE"
    const val PROTOCOL_TCP = 6

    /** The Service Operation Point of a media delivery session: the policy template binding that carries this externalReference */
    fun bindingFor(serviceAccessInformation: ServiceAccessInformation?, serviceOperationPointReference: String): PolicyTemplateBinding? =
        serviceAccessInformation?.dynamicPolicyInvocationConfiguration?.policyTemplateBindings
            ?.firstOrNull { it.externalReference == serviceOperationPointReference }

    /** The window of the binding that has not ended and starts first; null when the binding offers none (or one that cannot be read) */
    fun nextWindow(binding: PolicyTemplateBinding, now: OffsetDateTime): BdtWindow? =
        binding.bdtWindows.orEmpty()
            .mapNotNull { window -> instants(window)?.let { window to it } }
            .filter { it.second.second.isAfter(now) }
            .minByOrNull { it.second.first }?.first

    fun instants(window: BdtWindow): Pair<OffsetDateTime, OffsetDateTime>? = try {
        OffsetDateTime.parse(window.timeWindow.startTime) to OffsetDateTime.parse(window.timeWindow.stopTime)
    } catch (_: DateTimeParseException) {
        null
    }

    /**
     * The application flows of a media delivery session as the Media AF accepts them: a 5_TUPLE packet filter per address of the Media AS
     * (the Media AF offers no domainName form, see sdfMethods of the Service Access Information). The source is the UE (clause 5.3.3.2 of the
     * Media AF reads the Media Access Function as the source), the protocol is TCP, and a port number of 0 leaves the port open, so that
     * all flows to the Media AS match.
     */
    fun flowDescriptions(ueAddress: String, mediaAsAddresses: List<String>): List<ApplicationFlowDescription> =
        mediaAsAddresses.distinct().map {
            ApplicationFlowDescription(
                FILTER_METHOD_5_TUPLE,
                IpPacketFilterSet("BIDIRECTIONAL", IpAddr(ueAddress), IpAddr(it), PROTOCOL_TCP, 0, 0)
            )
        }

    /**
     * qosSpecification of a binding from the target bit rates of the Service Operation Point. The three bit rates of a direction are all
     * mandatory in UnidirectionalBitRateSpecification; the one target is given to all three, and a direction without a target takes that
     * of the other. Null when no target is given.
     */
    fun qosSpecification(downlink: String?, uplink: String?): ClientPolicySpecification? {
        if (downlink == null && uplink == null) return null
        fun rates(rate: String) = UnidirectionalBitRateSpecification(rate, rate, rate)
        return ClientPolicySpecification(rates(downlink ?: uplink!!), rates(uplink ?: downlink!!))
    }

    /**
     * The Dynamic Policy Instance of clause 5.3.3.2: the media delivery session, the Policy Template of the Service Operation Point, one
     * binding per application flow (the componentReference is mandatory; the Service Access Information does not name the components of a
     * Policy Template, so the externalReference stands in when the caller gives none) and, for a Background Data Transfer window, the
     * bdtSpecification with the estimated volume. The properties that the Media AF owns are left out.
     */
    fun dynamicPolicy(
        mediaDeliverySessionId: String,
        binding: PolicyTemplateBinding,
        flows: List<ApplicationFlowDescription>,
        componentReference: String?,
        qosSpecification: ClientPolicySpecification?,
        window: BdtWindow?,
        estimatedTransferVolume: Long?
    ): DynamicPolicy = DynamicPolicy(
        sessionId = mediaDeliverySessionId,
        policyTemplateId = binding.policyTemplateId,
        applicationFlowBindings = ArrayList(flows.map {
            ApplicationFlowBinding(componentReference ?: binding.externalReference, it, qosSpecification)
        }),
        bdtSpecification = window?.let {
            ClientBdtSpecification(arrayListOf(it), estimatedTransferVolume?.let { v -> UsageThreshold(totalVolume = v) })
        }
    )

    /**
     * The Network Assistance Session of clause 5.3.4.2 for the application flow of a service component: the Policy Template and the component
     * are cited only if a dynamic QoS policy is in force. mediaTransportParameters are omitted.
     */
    fun networkAssistanceSession(
        mediaDeliverySessionId: String,
        flow: ApplicationFlowDescription,
        policyTemplateId: String?,
        componentReference: String?,
        requestedQoS: ClientPolicySpecification? = null
    ) = NetworkAssistanceSession(
        sessionId = mediaDeliverySessionId,
        applicationFlowDescription = flow,
        policyTemplateId = policyTemplateId,
        componentReference = if (policyTemplateId != null) componentReference else null,
        requestedQoS = if (policyTemplateId != null) requestedQoS else null
    )
}
