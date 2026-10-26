package com.fivegmag.a5gmsmediasessionhandler

import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerBundleKeys as Keys
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerEvents
import com.fivegmag.a5gmscommonlibrary.models.BdtWindow
import com.fivegmag.a5gmscommonlibrary.models.DynamicPolicyInvocationConfiguration
import com.fivegmag.a5gmscommonlibrary.models.PolicyTemplateBinding
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmscommonlibrary.models.TimeWindow
import com.fivegmag.a5gmsmediasessionhandler.controller.ClientEvent
import com.fivegmag.a5gmsmediasessionhandler.controller.DynamicPolicyService
import com.fivegmag.a5gmsmediasessionhandler.controller.FlowEndpoints
import com.fivegmag.a5gmsmediasessionhandler.network.DynamicPolicyM5Client
import com.fivegmag.a5gmsmediasessionhandler.network.IDynamicPolicyApi
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.OffsetDateTime

class DynamicPolicyServiceUnitTest {
    private val af = MediaAfDouble()
    private val events = ArrayList<ClientEvent>()
    private val now = OffsetDateTime.parse("2026-10-28T10:30:00Z")
    private val base = "provisioning-sessions/ps1/dynamic-policies/"

    private val qosBinding = PolicyTemplateBinding("sop-qos", "pt-qos", l4SEnablementPreference = true, qoSMonitoringEnablementPreference = true)
    private val bdtBinding = PolicyTemplateBinding(
        "sop-bdt", "pt-bdt",
        bdtWindows = arrayListOf(BdtWindow(TimeWindow("2026-10-28T10:00:00Z", "2026-10-28T11:00:00Z"), "5 Mbps"))
    )
    private val sai = ServiceAccessInformation(
        "ps1", null, null, null, null,
        dynamicPolicyInvocationConfiguration = DynamicPolicyInvocationConfiguration(arrayListOf(af.baseUrl), arrayListOf(qosBinding, bdtBinding), arrayListOf("5_TUPLE"))
    )
    private var domains = listOf("media.example")
    private var ueAddress: String? = "10.0.0.2"
    private var resolved = listOf("192.0.2.1")

    private val endpoints = object : FlowEndpoints {
        override fun ueIpv4Address() = ueAddress
        override fun resolve(domainName: String) = if (domainName == "media.example") resolved else emptyList()
    }

    private fun service(): DynamicPolicyService {
        val retrofit = Retrofit.Builder().baseUrl(af.baseUrl).addConverterFactory(GsonConverterFactory.create()).client(OkHttpClient()).build()
        val client = DynamicPolicyM5Client(retrofit.create(IDynamicPolicyApi::class.java), null)
        return DynamicPolicyService("s1", { sai }, { client }, { endpoints }, { domains }, { now }, { events.add(it) })
    }

    @After
    fun stop() = af.close()

    private fun policyJson(template: String, l4s: Boolean = false, qosMonitoring: Boolean = false, withWindow: Boolean = false, withQos: Boolean = true) = """{
        "dynamicPolicyId":"d1","provisioningSessionId":"ps1","sessionId":"s1","policyTemplateId":"$template",
        "applicationFlowBindings":[{"componentReference":"c","applicationFlowDescription":{"filterMethod":"5_TUPLE"}${
        if (withQos) ""","qosSpecification":{"downlinkBitRates":{"maximumRequestedBitRate":"6 Mbps","minimumDesiredBitRate":"6 Mbps","minimumRequestedBitRate":"6 Mbps"},"uplinkBitRates":{"maximumRequestedBitRate":"1 Mbps","minimumDesiredBitRate":"1 Mbps","minimumRequestedBitRate":"1 Mbps"}}""" else ""}}],
        ${if (withWindow) """"bdtSpecification":{"windows":[{"timeWindow":{"startTime":"2026-10-28T10:00:00Z","stopTime":"2026-10-28T11:00:00Z"},"maximumDownlinkBitRate":"5 Mbps"}]},""" else ""}
        "qosEnforcement":true,"l4SEnabled":$l4s,"qoSMonitoringEnabled":$qosMonitoring}"""

    private fun names() = events.map { it.name }

    // TS 26.510 V19.2.0 clause 11.3.1: the Service Operation Points are the policy template bindings of the Service Access Information
    @Test
    fun theServiceOperationPointsShowWhichOnesPreferL4s() {
        val points = service().serviceOperationPoints()
        assertEquals(listOf("sop-qos", "sop-bdt"), points.map { it.externalReference })
        assertTrue(points[0].l4SEnablementPreference)
        assertFalse(points[1].l4SEnablementPreference)
    }

    // Table 11.3.1.1-2
    @Test
    fun getBdtInfoReturnsTheNextWindow() {
        val s = service()
        val info = s.getBdtInfo("sop-bdt")!!
        assertEquals("2026-10-28T10:00:00Z", info.windowStart)
        assertEquals("2026-10-28T11:00:00Z", info.windowEnd)
        assertTrue(events.isEmpty())
        val reply = s.bdtInfoReply("sop-bdt", info)
        assertEquals(true, reply[Keys.SUCCEEDED])
        assertEquals("2026-10-28T11:00:00Z", reply[Keys.WINDOW_END])
    }

    @Test
    fun getBdtInfoOfAnUnknownPointIsAnError() {
        val s = service()
        assertNull(s.getBdtInfo("nope"))
        assertEquals(listOf(SessionHandlerEvents.Error.ERROR_INVALID_SERVICE_OPERATION_POINT), names())
        assertEquals("nope", events[0].payload[Keys.SERVICE_OPERATION_POINT_REFERENCE])
        assertEquals("s1", events[0].payload[Keys.MEDIA_DELIVERY_SESSION_IDENTIFIER])
        assertEquals(false, s.bdtInfoReply("nope", null)[Keys.SUCCEEDED])
    }

    @Test
    fun getBdtInfoOfAPointWithoutWindowsIsUnsuccessful() {
        val s = service()
        assertNull(s.getBdtInfo("sop-qos"))
        assertNotNull(s.lastFailure)
        assertTrue(events.isEmpty())
    }

    // Table 11.3.1.2-2 and 11.3.2-2: return values, POLICY_ACTIVATED, L4S_ENABLED and QOS_MONITORING_ENABLED
    @Test
    fun activatePolicyReturnsTheRatesAndTheFlagsAndSendsTheEvents() {
        af.answer("POST", base, MediaAfDouble.Response(201, policyJson("pt-qos", l4s = true, qosMonitoring = true), "http://af/d1"))
        val s = service()
        val result = s.activate("sop-qos", null, null, "6 Mbps", "1 Mbps")!!
        assertEquals("6 Mbps", result.recommendedDownlinkBitRate)
        assertEquals("1 Mbps", result.recommendedUplinkBitRate)
        assertTrue(result.grantedBackgroundDataTransferWindows.isEmpty())
        assertTrue(result.l4SEnabled)
        assertTrue(result.qoSMonitoringEnabled)
        assertEquals(
            listOf(SessionHandlerEvents.DynamicPolicy.POLICY_ACTIVATED, SessionHandlerEvents.DynamicPolicy.L4S_ENABLED, SessionHandlerEvents.DynamicPolicy.QOS_MONITORING_ENABLED),
            names()
        )
        val activated = events[0].payload
        assertEquals("s1", activated[Keys.MEDIA_DELIVERY_SESSION_IDENTIFIER])
        assertEquals("sop-qos", activated[Keys.SERVICE_OPERATION_POINT_REFERENCE])
        assertEquals("6 Mbps", activated[Keys.RECOMMENDED_DOWNLINK_BIT_RATE])
        assertEquals(mapOf(Keys.MEDIA_DELIVERY_SESSION_IDENTIFIER to "s1"), events[1].payload)
        val reply = s.activationReply("sop-qos", result)
        assertEquals(true, reply[Keys.L4S_ENABLED])
        assertEquals(true, reply[Keys.QOS_MONITORING_ENABLED])
        assertEquals(true, reply[Keys.SUCCEEDED])
    }

    // What is sent: the template of the point, a 5_TUPLE to each address of the Media AS, the QoS of the point, none of the Media AF's properties
    @Test
    fun activatePolicySendsTheFlowsOfTheMediaAs() {
        af.answer("POST", base, MediaAfDouble.Response(201, policyJson("pt-qos")))
        resolved = listOf("192.0.2.1", "192.0.2.9")
        service().activate("sop-qos", null, "video", "6 Mbps", null)
        val sent = JsonParser().parse(af.requests.single().body).asJsonObject
        assertEquals("pt-qos", sent["policyTemplateId"].asString)
        assertEquals("s1", sent["sessionId"].asString)
        val bindings = sent["applicationFlowBindings"].asJsonArray
        assertEquals(2, bindings.size())
        val filter = bindings[1].asJsonObject["applicationFlowDescription"].asJsonObject["packetFilter"].asJsonObject
        assertEquals("192.0.2.9", filter["destinationAddress"].asJsonObject["ipv4Addr"].asString)
        assertEquals("10.0.0.2", filter["sourceAddress"].asJsonObject["ipv4Addr"].asString)
        assertEquals("video", bindings[0].asJsonObject["componentReference"].asString)
        assertTrue(bindings[0].asJsonObject.has("qosSpecification"))
        assertFalse(sent.has("l4SEnabled"))
        assertFalse(sent.toString().contains("l4SRequired"))
        assertFalse(sent.toString().contains("qoSMonitoringRequired"))
    }

    @Test
    fun noEventsForFlagsThatAreFalse() {
        af.answer("POST", base, MediaAfDouble.Response(201, policyJson("pt-qos")))
        val result = service().activate("sop-qos", null, null, "6 Mbps", null)!!
        assertFalse(result.l4SEnabled)
        assertFalse(result.qoSMonitoringEnabled)
        assertEquals(listOf(SessionHandlerEvents.DynamicPolicy.POLICY_ACTIVATED), names())
    }

    // Table 11.3.1.2-1: estimatedTransferVolume is required for a Background Data Transfer policy
    @Test
    fun aBackgroundDataTransferPolicyNeedsTheEstimatedVolume() {
        val s = service()
        assertNull(s.activate("sop-bdt", null))
        assertEquals(listOf(SessionHandlerEvents.Error.ERROR_BACKGROUND_DATA_TRANSFER), names())
        assertNotNull(events[0].payload[Keys.ERROR_REASON])
        assertTrue(af.requests.isEmpty())
    }

    @Test
    fun aBackgroundDataTransferPolicyIsRequestedForTheNextWindowAndTheGrantIsReturned() {
        af.answer("POST", base, MediaAfDouble.Response(201, policyJson("pt-bdt", withWindow = true, withQos = false)))
        val result = service().activate("sop-bdt", 5000L)!!
        assertEquals("2026-10-28T11:00:00Z", result.grantedBackgroundDataTransferWindows.single().timeWindow.stopTime)
        val sent = JsonParser().parse(af.requests.single().body).asJsonObject["bdtSpecification"].asJsonObject
        assertEquals(5000L, sent["estimatedDataTransferVolume"].asJsonObject["totalVolume"].asLong)
        assertEquals("2026-10-28T10:00:00Z", sent["windows"].asJsonArray[0].asJsonObject["timeWindow"].asJsonObject["startTime"].asString)
        val activated = events[0].payload
        assertEquals("2026-10-28T10:00:00Z", activated[Keys.WINDOW_START])
        assertEquals("2026-10-28T11:00:00Z", activated[Keys.WINDOW_END])
        assertFalse(activated.containsKey(Keys.RECOMMENDED_DOWNLINK_BIT_RATE))
    }

    // Table 11.3.2-3
    @Test
    fun aRefusalOfAuthorisationIsErrorUnauthorised() {
        for (code in listOf(401, 403)) {
            events.clear()
            af.answer("POST", base, MediaAfDouble.Response(code, """{"title":"no"}"""))
            val s = service()
            assertNull(s.activate("sop-qos", null))
            assertEquals(listOf(SessionHandlerEvents.Error.ERROR_UNAUTHORISED), names())
            assertEquals("sop-qos", events[0].payload[Keys.SERVICE_OPERATION_POINT_REFERENCE])
            assertEquals(false, s.activationReply("sop-qos", null)[Keys.SUCCEEDED])
        }
    }

    @Test
    fun anUnknownPointOrATemplateThatTheMediaAfDoesNotKnowIsErrorInvalidServiceOperationPoint() {
        assertNull(service().activate("nope", null))
        assertTrue(af.requests.isEmpty())
        assertEquals(listOf(SessionHandlerEvents.Error.ERROR_INVALID_SERVICE_OPERATION_POINT), names())
        events.clear()
        af.answer("POST", base, MediaAfDouble.Response(400, """{"title":"bad","invalidParams":[{"param":"policyTemplateId","reason":"unknown"}]}"""))
        assertNull(service().activate("sop-qos", null))
        assertEquals(listOf(SessionHandlerEvents.Error.ERROR_INVALID_SERVICE_OPERATION_POINT), names())
    }

    @Test
    fun aFailureOfABackgroundDataTransferPolicyIsErrorBackgroundDataTransferWithTheReason() {
        af.answer("POST", base, MediaAfDouble.Response(500, """{"title":"x","detail":"the window is full"}"""))
        assertNull(service().activate("sop-bdt", 100L))
        assertEquals(listOf(SessionHandlerEvents.Error.ERROR_BACKGROUND_DATA_TRANSFER), names())
        assertEquals("the window is full", events[0].payload[Keys.ERROR_REASON])
    }

    @Test
    fun aFailureOfAnotherPolicyIsUnsuccessfulWithoutAnEvent() {
        af.answer("POST", base, MediaAfDouble.Response(500, """{"title":"x"}"""))
        val s = service()
        assertNull(s.activate("sop-qos", null))
        assertTrue(events.isEmpty())
        assertEquals("x", s.lastFailure)
    }

    // Clause 11.3.1.2: the scope is the Media AS domain name of the media delivery session
    @Test
    fun withoutAMediaAsAddressNothingIsSent() {
        domains = emptyList()
        assertNull(service().activate("sop-qos", null))
        domains = listOf("other.example")
        assertNull(service().activate("sop-qos", null))
        domains = listOf("media.example")
        ueAddress = null
        assertNull(service().activate("sop-qos", null))
        assertTrue(af.requests.isEmpty())
    }

    // Clause 5.3.3.1: the flows of one media delivery session do not overlap, so a policy in force is replaced
    @Test
    fun aSecondActivationReplacesThePolicyInForce() {
        af.answer("POST", base, MediaAfDouble.Response(201, policyJson("pt-qos")))
        af.answer("PUT", base + "d1", MediaAfDouble.Response(200, policyJson("pt-bdt", withWindow = true)))
        val s = service()
        s.activate("sop-qos", null, null, "6 Mbps", null)
        s.activate("sop-bdt", 10L, null, "6 Mbps", null)
        assertEquals(listOf("POST", "PUT"), af.requests.map { it.method })
        assertEquals("sop-bdt", s.status()!!.serviceOperationPointReference)
    }

    // Table 11.3.1.3-2 and 11.3.2-2
    @Test
    fun deactivatePolicyDestroysThePolicyAndSendsPolicyDeactivated() {
        af.answer("POST", base, MediaAfDouble.Response(201, policyJson("pt-qos")))
        af.answer("DELETE", base + "d1", MediaAfDouble.Response(204))
        val s = service()
        s.activate("sop-qos", null, null, "6 Mbps", null)
        events.clear()
        assertTrue(s.deactivate())
        assertEquals(listOf(SessionHandlerEvents.DynamicPolicy.POLICY_DEACTIVATED), names())
        assertEquals("sop-qos", events[0].payload[Keys.SERVICE_OPERATION_POINT_REFERENCE])
        assertEquals("DELETE", af.requests.last().method)
        assertNull(s.status())
        assertEquals(true, s.deactivationReply(true)[Keys.SUCCEEDED])
    }

    @Test
    fun deactivateWithoutAPolicyIsFalse() {
        val s = service()
        assertFalse(s.deactivate())
        assertTrue(events.isEmpty())
        assertEquals(false, s.deactivationReply(false)[Keys.SUCCEEDED])
    }

    @Test
    fun deactivateOfAPolicyThatTheMediaAfNoLongerHasIsTrueAndAFailureIsFalse() {
        af.answer("POST", base, MediaAfDouble.Response(201, policyJson("pt-qos")))
        af.answer("DELETE", base + "d1", MediaAfDouble.Response(404))
        val s = service()
        s.activate("sop-qos", null, null, "6 Mbps", null)
        assertTrue(s.deactivate())
        s.activate("sop-qos", null, null, "6 Mbps", null)
        af.answer("DELETE", base + "d1", MediaAfDouble.Response(500))
        events.clear()
        assertFalse(s.deactivate())
        assertTrue(events.isEmpty())
        assertNotNull(s.status())
    }

    // Table 11.3.2-1
    @Test
    fun theStatusHoldsThePointTheQuotaTheFlagsAndTheResults() {
        assertNull(service().status())
        val withResults = policyJson("pt-bdt", l4s = true, qosMonitoring = true, withWindow = true).replace("\"qosEnforcement\"", "\"qoSMonitoringResults\":{\"dlDelays\":[7]},\"qosEnforcement\"")
        af.answer("POST", base, MediaAfDouble.Response(201, withResults))
        val s = service()
        s.activate("sop-bdt", 10L)
        val status = s.status()!!
        assertEquals("s1", status.sessionId)
        assertEquals("sop-bdt", status.serviceOperationPointReference)
        assertEquals("d1", status.dynamicPolicyId)
        assertEquals("5 Mbps", status.grantedBackgroundDataTransferWindows.single().maximumDownlinkBitRate)
        assertTrue(status.l4SEnabled)
        assertTrue(status.qoSMonitoringEnabled)
        assertEquals(7, status.qoSMonitoringResults!!.dlDelays!![0])
    }

    // The policy is released at the Media AF when the media delivery session ends, without an event
    @Test
    fun releaseDestroysThePolicyWithoutAnEvent() {
        af.answer("POST", base, MediaAfDouble.Response(201, policyJson("pt-qos")))
        af.answer("DELETE", base + "d1", MediaAfDouble.Response(204))
        val s = service()
        s.activate("sop-qos", null, null, "6 Mbps", null)
        events.clear()
        s.release()
        assertEquals("DELETE", af.requests.last().method)
        assertTrue(events.isEmpty())
        assertNull(s.status())
    }

    // ---- the notification channel ----

    private fun activated(topics: MutableList<String>? = null): DynamicPolicyService {
        af.answer("POST", base, MediaAfDouble.Response(201, policyJson("pt-qos")))
        val s = service()
        if (topics != null) s.subscribeTopic = { topics.add("+$it") }
        if (topics != null) s.unsubscribeTopic = { topics.add("-$it") }
        s.activate("sop-qos", null, null, "6 Mbps", null)
        events.clear()
        return s
    }

    private fun notified(json: String) = com.google.gson.Gson().fromJson(json, com.fivegmag.a5gmscommonlibrary.models.DynamicPolicy::class.java)

    // Clauses 5.3.3.2 and 5.3.3.5: the sub-topic of the instance is subscribed to when it is created and left when it is destroyed
    @Test
    fun theSubTopicOfTheInstanceIsSubscribedAndLeft() {
        val topics = ArrayList<String>()
        af.answer("DELETE", base + "d1", MediaAfDouble.Response(204))
        val s = activated(topics)
        assertEquals(listOf("+d1"), topics)
        s.deactivate()
        assertEquals(listOf("+d1", "-d1"), topics)
        val again = activated(topics)
        again.release()
        assertEquals("-d1", topics.last())
    }

    // Table 11.3.2-2: QOS_MONITORING_RESULTS carries the results; the status shows the latest
    @Test
    fun qosMonitoringResultsAreSignalledAndKept() {
        val s = activated()
        s.onNotification(notified(policyJson("pt-qos", qosMonitoring = true).replace("\"qosEnforcement\"", "\"qoSMonitoringResults\":{\"dlDelays\":[9],\"pdmf\":true},\"qosEnforcement\"")),
            "NOTIFICATION_REASON_QOS_MONITORING_RESULTS")
        assertEquals(
            listOf(SessionHandlerEvents.DynamicPolicy.QOS_MONITORING_ENABLED, SessionHandlerEvents.DynamicPolicy.QOS_MONITORING_RESULTS),
            names()
        )
        val results = events[1].payload[Keys.QOS_MONITORING_RESULTS] as com.fivegmag.a5gmscommonlibrary.models.QosMonitoringReport
        assertEquals(9, results.dlDelays!![0])
        assertEquals("s1", events[1].payload[Keys.MEDIA_DELIVERY_SESSION_IDENTIFIER])
        assertEquals(9, s.status()!!.qoSMonitoringResults!!.dlDelays!![0])
    }

    // A flag that becomes true in a notification is signalled once
    @Test
    fun aFlagThatBecomesTrueInANotificationIsSignalledOnce() {
        val s = activated()
        val update = notified(policyJson("pt-qos", l4s = true))
        s.onNotification(update, "NOTIFICATION_REASON_PROVISIONING_UPDATE")
        s.onNotification(update, "NOTIFICATION_REASON_PROVISIONING_UPDATE")
        assertEquals(listOf(SessionHandlerEvents.DynamicPolicy.L4S_ENABLED), names())
        assertTrue(s.status()!!.l4SEnabled)
    }

    // Table 10.2.3-3 and 11.3.2-3: a cancelled window is ERROR_BACKGROUND_DATA_TRANSFER, and the policy is gone with it
    @Test
    fun aCancelledWindowIsErrorBackgroundDataTransferAndEndsThePolicy() {
        val topics = ArrayList<String>()
        val s = activated(topics)
        s.onNotification(notified(policyJson("pt-qos")), "NOTIFICATION_REASON_BACKGROUND_DATA_TRANSFER_WINDOW_CANCELLATION")
        assertEquals(
            listOf(SessionHandlerEvents.Error.ERROR_BACKGROUND_DATA_TRANSFER, SessionHandlerEvents.DynamicPolicy.POLICY_DEACTIVATED),
            names()
        )
        assertNotNull(events[0].payload[Keys.ERROR_REASON])
        assertEquals("sop-qos", events[1].payload[Keys.SERVICE_OPERATION_POINT_REFERENCE])
        assertNull(s.status())
        assertEquals("-d1", topics.last())
    }

    @Test
    fun aNotificationForAnotherInstanceIsIgnored() {
        val s = activated()
        s.onNotification(notified(policyJson("pt-qos").replace("\"d1\"", "\"other\"")), "NOTIFICATION_REASON_QOS_MONITORING_RESULTS")
        assertTrue(events.isEmpty())
    }

    // Table 11.3.2-2: BACKGROUND_DATA_TRANSFER_OPPORTUNITY when an update of the Service Access Information offers a new window
    private fun saiWith(vararg windows: BdtWindow) = ServiceAccessInformation(
        "ps1", null, null, null, null,
        dynamicPolicyInvocationConfiguration = DynamicPolicyInvocationConfiguration(
            arrayListOf(af.baseUrl), arrayListOf(PolicyTemplateBinding("sop-bdt", "pt-bdt", bdtWindows = arrayListOf(*windows))), arrayListOf("5_TUPLE")
        )
    )

    private val earlier = BdtWindow(TimeWindow("2026-10-28T10:00:00Z", "2026-10-28T11:00:00Z"))
    private val later = BdtWindow(TimeWindow("2026-10-28T13:00:00Z", "2026-10-28T14:00:00Z"))
    private val ended = BdtWindow(TimeWindow("2026-10-28T06:00:00Z", "2026-10-28T07:00:00Z"))

    @Test
    fun aNewWindowIsABackgroundDataTransferOpportunity() {
        service().onServiceAccessInformationUpdated(saiWith(earlier), saiWith(earlier, later))
        assertEquals(listOf(SessionHandlerEvents.DynamicPolicy.BACKGROUND_DATA_TRANSFER_OPPORTUNITY), names())
        val payload = events[0].payload
        assertEquals("sop-bdt", payload[Keys.SERVICE_OPERATION_POINT_REFERENCE])
        assertEquals("2026-10-28T13:00:00Z", payload[Keys.WINDOW_START])
        assertEquals("2026-10-28T14:00:00Z", payload[Keys.WINDOW_END])
        assertEquals("s1", payload[Keys.MEDIA_DELIVERY_SESSION_IDENTIFIER])
    }

    @Test
    fun knownEndedAndFirstWindowsAreNoOpportunity() {
        val s = service()
        s.onServiceAccessInformationUpdated(saiWith(earlier), saiWith(earlier))
        s.onServiceAccessInformationUpdated(saiWith(earlier), saiWith(earlier, ended))
        s.onServiceAccessInformationUpdated(null, saiWith(earlier, later))
        assertTrue(events.isEmpty())
    }
}
