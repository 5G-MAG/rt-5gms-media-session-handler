package com.fivegmag.a5gmsmediasessionhandler

import com.fivegmag.a5gmscommonlibrary.models.BdtWindow
import com.fivegmag.a5gmscommonlibrary.models.DynamicPolicyInvocationConfiguration
import com.fivegmag.a5gmscommonlibrary.models.PolicyTemplateBinding
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmscommonlibrary.models.TimeWindow
import com.fivegmag.a5gmsmediasessionhandler.controller.DynamicPolicyRequests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.OffsetDateTime

class DynamicPolicyRequestsUnitTest {
    private fun window(start: String, stop: String, dl: String? = null) = BdtWindow(TimeWindow(start, stop), dl)
    private val now = OffsetDateTime.parse("2026-10-28T10:30:00Z")
    private val binding = PolicyTemplateBinding("sop-1", "pt1", bdtWindows = arrayListOf(
        window("2026-10-28T08:00:00Z", "2026-10-28T09:00:00Z"),
        window("2026-10-28T13:00:00Z", "2026-10-28T14:00:00Z"),
        window("2026-10-28T10:00:00Z", "2026-10-28T11:00:00Z", "5 Mbps")
    ))

    private fun sai(vararg bindings: PolicyTemplateBinding) = ServiceAccessInformation(
        "ps1", null, null, null, null,
        dynamicPolicyInvocationConfiguration = DynamicPolicyInvocationConfiguration(arrayListOf("http://af/"), arrayListOf(*bindings), arrayListOf("5_TUPLE"))
    )

    // TS 26.510 V19.2.0 table 11.3.1.2-1: the Service Operation Point is named by its external reference
    @Test
    fun theServiceOperationPointIsTheBindingWithThatExternalReference() {
        assertEquals("pt1", DynamicPolicyRequests.bindingFor(sai(binding), "sop-1")!!.policyTemplateId)
        assertNull(DynamicPolicyRequests.bindingFor(sai(binding), "other"))
        assertNull(DynamicPolicyRequests.bindingFor(null, "sop-1"))
    }

    // Table 11.3.1.1-2: the next window is the one that has not ended and starts first
    @Test
    fun theNextWindowIsTheEarliestThatHasNotEnded() {
        assertEquals("2026-10-28T10:00:00Z", DynamicPolicyRequests.nextWindow(binding, now)!!.timeWindow.startTime)
        assertEquals("2026-10-28T13:00:00Z", DynamicPolicyRequests.nextWindow(binding, OffsetDateTime.parse("2026-10-28T11:30:00Z"))!!.timeWindow.startTime)
        assertNull(DynamicPolicyRequests.nextWindow(binding, OffsetDateTime.parse("2026-10-28T15:00:00Z")))
        assertNull(DynamicPolicyRequests.nextWindow(PolicyTemplateBinding("sop-2", "pt2"), now))
    }

    // Clause 11.3.1.2: all flows to the Media AS; the Media AF takes a 5_TUPLE filter with IPv4 addresses, a protocol and both ports
    @Test
    fun theFlowsOfTheMediaAsAreFiveTupleFiltersWithOpenPorts() {
        val flows = DynamicPolicyRequests.flowDescriptions("10.0.0.2", listOf("192.0.2.1", "192.0.2.2", "192.0.2.1"))
        assertEquals(2, flows.size)
        val filter = flows[0].packetFilter!!
        assertEquals("5_TUPLE", flows[0].filterMethod)
        assertEquals("10.0.0.2", filter.sourceAddress!!.ipv4Addr)
        assertEquals("192.0.2.1", filter.destinationAddress!!.ipv4Addr)
        assertEquals(6, filter.protocolNumber)
        assertEquals(0, filter.sourcePort)
        assertEquals(0, filter.destinationPort)
        assertEquals("BIDIRECTIONAL", filter.direction)
        assertNull(flows[0].domainName)
    }

    // Clause 5.3.3.2 item 4: the three bit rates of a direction are all mandatory; a direction without a target takes that of the other
    @Test
    fun theQosSpecificationComesFromTheTargetBitRates() {
        assertNull(DynamicPolicyRequests.qosSpecification(null, null))
        val both = DynamicPolicyRequests.qosSpecification("6 Mbps", "1 Mbps")!!
        assertEquals("6 Mbps", both.downlinkBitRates.maximumRequestedBitRate)
        assertEquals("6 Mbps", both.downlinkBitRates.minimumRequestedBitRate)
        assertEquals("1 Mbps", both.uplinkBitRates.minimumDesiredBitRate)
        assertEquals("6 Mbps", DynamicPolicyRequests.qosSpecification("6 Mbps", null)!!.uplinkBitRates.maximumRequestedBitRate)
    }

    // Clause 5.3.3.2: sessionId, Policy Template, one binding per flow, the bdtSpecification with the volume of the window
    @Test
    fun theDynamicPolicyCitesTheTemplateTheFlowsAndTheWindow() {
        val flows = DynamicPolicyRequests.flowDescriptions("10.0.0.2", listOf("192.0.2.1", "192.0.2.2"))
        val window = DynamicPolicyRequests.nextWindow(binding, now)
        val policy = DynamicPolicyRequests.dynamicPolicy("s1", binding, flows, null, null, window, 5000L)
        assertEquals("s1", policy.sessionId)
        assertEquals("pt1", policy.policyTemplateId)
        assertEquals(2, policy.applicationFlowBindings.size)
        assertEquals("sop-1", policy.applicationFlowBindings[0].componentReference)
        assertNull(policy.applicationFlowBindings[0].qosSpecification)
        assertEquals(5000L, policy.bdtSpecification!!.estimatedDataTransferVolume!!.totalVolume)
        assertEquals("5 Mbps", policy.bdtSpecification!!.windows.single().maximumDownlinkBitRate)
        assertNull(policy.dynamicPolicyId)
        assertNull(policy.l4SEnabled)
    }

    @Test
    fun aComponentReferenceAndAQosSpecificationAreCarriedToEveryBinding() {
        val flows = DynamicPolicyRequests.flowDescriptions("10.0.0.2", listOf("192.0.2.1"))
        val policy = DynamicPolicyRequests.dynamicPolicy("s1", binding, flows, "video", DynamicPolicyRequests.qosSpecification("2 Mbps", "2 Mbps"), null, null)
        assertEquals("video", policy.applicationFlowBindings[0].componentReference)
        assertNotNull(policy.applicationFlowBindings[0].qosSpecification)
        assertNull(policy.bdtSpecification)
    }

    // Clause 5.3.4.2: the Policy Template and the component are cited only if a dynamic QoS policy is in force; no mediaTransportParameters
    @Test
    fun theNetworkAssistanceSessionCitesThePolicyOnlyWhenOneIsInForce() {
        val flow = DynamicPolicyRequests.flowDescriptions("10.0.0.2", listOf("192.0.2.1"))[0]
        val with = DynamicPolicyRequests.networkAssistanceSession("s1", flow, "pt1", "video")
        assertEquals("pt1", with.policyTemplateId)
        assertEquals("video", with.componentReference)
        val without = DynamicPolicyRequests.networkAssistanceSession("s1", flow, null, "video")
        assertNull(without.policyTemplateId)
        assertNull(without.componentReference)
        assertNull(without.applicationFlowDescription.mediaType)
    }

    // TS 26.512 V19.3.0 clause 4.7.3: l4SRequired only for a binding that prefers L4S and a Media Player that declares CAPABILITY_L4S
    @Test
    fun l4sRequiredNeedsThePreferenceAndTheCapability() {
        val prefers = PolicyTemplateBinding("sop", "pt", l4SEnablementPreference = true)
        val indifferent = PolicyTemplateBinding("sop", "pt")
        val l4s = setOf(com.fivegmag.a5gmscommonlibrary.helpers.MediaPlayerCapabilities.CAPABILITY_L4S)
        val qos = setOf(com.fivegmag.a5gmscommonlibrary.helpers.MediaPlayerCapabilities.CAPABILITY_QOS)
        assertTrue(DynamicPolicyRequests.l4SRequired(prefers, l4s))
        assertFalse(DynamicPolicyRequests.l4SRequired(prefers, qos))
        assertFalse(DynamicPolicyRequests.l4SRequired(prefers, emptySet()))
        assertFalse(DynamicPolicyRequests.l4SRequired(indifferent, l4s))
    }

    @Test
    fun theQosSpecificationCarriesL4sRequiredOnlyWhenTrue() {
        assertNull(DynamicPolicyRequests.qosSpecification("6 Mbps", "1 Mbps")!!.l4SRequired)
        assertEquals(true, DynamicPolicyRequests.qosSpecification("6 Mbps", "1 Mbps", l4SRequired = true)!!.l4SRequired)
        assertNull(DynamicPolicyRequests.qosSpecification(null, null, l4SRequired = true))
    }

    // TS 26.512 V19.3.0 clause 4.7.3: qoSMonitoringRequired only for a binding that prefers QoS monitoring and a Media Player with CAPABILITY_QOS
    @Test
    fun qosMonitoringRequiredNeedsThePreferenceAndTheCapability() {
        val prefers = PolicyTemplateBinding("sop", "pt", qoSMonitoringEnablementPreference = true)
        val indifferent = PolicyTemplateBinding("sop", "pt")
        val l4s = setOf(com.fivegmag.a5gmscommonlibrary.helpers.MediaPlayerCapabilities.CAPABILITY_L4S)
        val qos = setOf(com.fivegmag.a5gmscommonlibrary.helpers.MediaPlayerCapabilities.CAPABILITY_QOS)
        assertTrue(DynamicPolicyRequests.qoSMonitoringRequired(prefers, qos))
        assertFalse(DynamicPolicyRequests.qoSMonitoringRequired(prefers, l4s))
        assertFalse(DynamicPolicyRequests.qoSMonitoringRequired(prefers, emptySet()))
        assertFalse(DynamicPolicyRequests.qoSMonitoringRequired(indifferent, qos))
        assertNull(DynamicPolicyRequests.qosSpecification("6 Mbps", "1 Mbps")!!.qoSMonitoringRequired)
        assertEquals(true, DynamicPolicyRequests.qosSpecification("6 Mbps", "1 Mbps", qoSMonitoringRequired = true)!!.qoSMonitoringRequired)
    }
}
