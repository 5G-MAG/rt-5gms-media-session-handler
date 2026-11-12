package com.fivegmag.a5gmsmediasessionhandler.controller

import com.fivegmag.a5gmscommonlibrary.helpers.MediaPlayerCapabilities
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
import com.fivegmag.a5gmscommonlibrary.models.TypedLocation
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
    const val FILTER_METHOD_2_TUPLE = "2_TUPLE"
    const val FILTER_METHOD_DOMAIN_NAME = "DOMAIN_NAME"
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
     * The location of the UE for a request (tables 9.3.3.1-1 and 9.4.3.1-1): given when locationReporting of the Service Access Information is
     * true; "If false or omitted, UE location shall not be populated" (table 9.2.3.1-1 of the Provisioning Session).
     */
    fun locationFor(serviceAccessInformation: ServiceAccessInformation, location: () -> TypedLocation?): TypedLocation? =
        if (serviceAccessInformation.locationReporting) location() else null

    /**
     * The Service Data Flow description method to use (sdfMethods of the dynamicPolicyInvocationConfiguration, TS 26.510 V19.2.0 table 9.2.3.1-1:
     * "A list of Service Data Flow description methods ... to be used to describe the application flows"): only a method of the list is used.
     * 5_TUPLE if it is listed, else the first listed one of the others that can be built from what the client knows (2_TUPLE, DOMAIN_NAME;
     * TYPE_OF_SERVICE_MARKING and FLOW_LABEL need values that the client does not have). Null when the list names none of them. A Service Access
     * Information without the list (it is mandatory) keeps 5_TUPLE.
     */
    fun filterMethodFor(sdfMethods: List<String>?): String? {
        if (sdfMethods.isNullOrEmpty()) return FILTER_METHOD_5_TUPLE
        if (FILTER_METHOD_5_TUPLE in sdfMethods) return FILTER_METHOD_5_TUPLE
        return sdfMethods.firstOrNull { it == FILTER_METHOD_2_TUPLE || it == FILTER_METHOD_DOMAIN_NAME }
    }

    /**
     * The application flows of the Media AS for a description method of clause 7.3.4.2: 5_TUPLE as below; 2_TUPLE is the source (the UE) and the
     * destination (the Media AS) address only; DOMAIN_NAME is the domain name of the Media AS (exactly one of packetFilter and domainName, table 7.3.3.2-1).
     */
    fun flowDescriptions(method: String, ueAddress: String, mediaAsAddresses: List<String>, mediaAsDomainNames: List<String>): List<ApplicationFlowDescription> = when (method) {
        FILTER_METHOD_2_TUPLE -> mediaAsAddresses.distinct().map {
            ApplicationFlowDescription(FILTER_METHOD_2_TUPLE, IpPacketFilterSet("BIDIRECTIONAL", IpAddr(ueAddress), IpAddr(it)))
        }
        FILTER_METHOD_DOMAIN_NAME -> mediaAsDomainNames.distinct().map { ApplicationFlowDescription(FILTER_METHOD_DOMAIN_NAME, domainName = it) }
        else -> flowDescriptions(ueAddress, mediaAsAddresses)
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
    fun qosSpecification(downlink: String?, uplink: String?, l4SRequired: Boolean = false, qoSMonitoringRequired: Boolean = false): ClientPolicySpecification? {
        if (downlink == null && uplink == null) return null
        fun rates(rate: String) = UnidirectionalBitRateSpecification(rate, rate, rate)
        return ClientPolicySpecification(
            rates(downlink ?: uplink!!), rates(uplink ?: downlink!!),
            l4SRequired = if (l4SRequired) true else null, qoSMonitoringRequired = if (qoSMonitoringRequired) true else null
        )
    }

    /**
     * l4SRequired of the request (TS 26.512 V19.3.0 clause 4.7.3): "When instantiating a Policy Template whose Policy Template Binding indicates
     * l4SEnablementPreference set to true, the Media Session Handler shall set the l4SRequired flag to true in the Dynamic Policy request only if
     * an L4S-capable media transport stack is present and in use. This is the case if the Media Session Handler detects that [...] or if the Media
     * Player configuration and settings API (see clause 13.2.4) declares CAPABILITY_L4S." The specification defines no detection, so the
     * declaration of the Media Player (table 13.2.4-1, capabilities) is the only source; without it the flag stays off.
     */
    fun l4SRequired(binding: PolicyTemplateBinding, playerCapabilities: Set<String>): Boolean =
        binding.l4SEnablementPreference && MediaPlayerCapabilities.CAPABILITY_L4S in playerCapabilities

    /**
     * qoSMonitoringRequired of the request (TS 26.512 V19.3.0 clause 4.7.3): "When instantiating a Policy Template whose Policy Template Binding
     * indicates qoSMonitoringEnablementPreference set to true, the Media Session Handler shall set the qoSMonitoringRequired flag to true in the
     * Dynamic Policy request only if the Media Player is capable of consuming QoS monitoring results." The Media Player says so with
     * CAPABILITY_QOS (table 13.2.4-2: "capable of reacting to QoS monitoring results"). The Policy Template itself stays the one that the
     * application named by the Service Operation Point reference: an external reference identifies one Policy Template (clause 11.3.1.2).
     */
    fun qoSMonitoringRequired(binding: PolicyTemplateBinding, playerCapabilities: Set<String>): Boolean =
        binding.qoSMonitoringEnablementPreference && MediaPlayerCapabilities.CAPABILITY_QOS in playerCapabilities

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
        estimatedTransferVolume: Long?,
        location: TypedLocation? = null
    ): DynamicPolicy = DynamicPolicy(
        sessionId = mediaDeliverySessionId,
        policyTemplateId = binding.policyTemplateId,
        applicationFlowBindings = ArrayList(flows.map {
            ApplicationFlowBinding(componentReference ?: binding.externalReference, it, qosSpecification)
        }),
        bdtSpecification = window?.let {
            ClientBdtSpecification(arrayListOf(it), estimatedTransferVolume?.let { v -> UsageThreshold(totalVolume = v) })
        },
        location = location
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
        requestedQoS: ClientPolicySpecification? = null,
        location: TypedLocation? = null
    ) = NetworkAssistanceSession(
        sessionId = mediaDeliverySessionId,
        applicationFlowDescription = flow,
        policyTemplateId = policyTemplateId,
        componentReference = if (policyTemplateId != null) componentReference else null,
        requestedQoS = if (policyTemplateId != null) requestedQoS else null,
        location = location
    )
}
