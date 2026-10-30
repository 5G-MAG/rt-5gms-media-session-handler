package com.fivegmag.a5gmsmediasessionhandler

import com.fivegmag.a5gmscommonlibrary.helpers.RecommendationSources
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerBundleKeys as Keys
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerEvents
import com.fivegmag.a5gmscommonlibrary.models.ClientPolicySpecification
import com.fivegmag.a5gmsmediasessionhandler.controller.DynamicPolicyRequests
import com.fivegmag.a5gmscommonlibrary.models.NetworkAssistanceConfiguration
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmsmediasessionhandler.controller.ClientEvent
import com.fivegmag.a5gmsmediasessionhandler.controller.FlowEndpoints
import com.fivegmag.a5gmsmediasessionhandler.controller.NetworkAssistanceService
import com.fivegmag.a5gmsmediasessionhandler.network.INetworkAssistanceApi
import com.fivegmag.a5gmsmediasessionhandler.network.NetworkAssistanceM5Client
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

class NetworkAssistanceServiceUnitTest {
    private val af = MediaAfDouble()
    private val events = ArrayList<ClientEvent>()
    private val base = "provisioning-sessions/ps1/network-assistance-sessions/"
    private val sai = ServiceAccessInformation("ps1", null, null, null, null, networkAssistanceConfiguration = NetworkAssistanceConfiguration(arrayListOf(af.baseUrl)))
    private var template: String? = null
    private var requestedQoS: ClientPolicySpecification? = null
    private var resolved = listOf("192.0.2.1", "192.0.2.2")
    private val rates = """{"maximumRequestedBitRate":"8 Mbps","minimumDesiredBitRate":"4 Mbps","minimumRequestedBitRate":"4 Mbps"}"""

    private val endpoints = object : FlowEndpoints {
        override fun ueIpv4Address() = "10.0.0.2"
        override fun resolve(domainName: String) = resolved
    }

    private fun service(): NetworkAssistanceService {
        val retrofit = Retrofit.Builder().baseUrl(af.baseUrl).addConverterFactory(GsonConverterFactory.create()).client(OkHttpClient()).build()
        val client = NetworkAssistanceM5Client(retrofit.create(INetworkAssistanceApi::class.java), null)
        return NetworkAssistanceService("s1", { sai }, { client }, { endpoints }, { listOf("media.example") }, { template }, { requestedQoS }, { events.add(it) })
    }

    private fun naJson(template: String? = null, component: String? = null) =
        """{"naSessionId":"n1","provisioningSessionId":"ps1","sessionId":"s1","applicationFlowDescription":{"filterMethod":"5_TUPLE"}${
            if (template != null) ""","policyTemplateId":"$template","componentReference":"$component"""" else ""}}"""

    @After
    fun stop() = af.close()

    private fun answerRecommendation() = af.answer("GET", base + "n1/recommendation", MediaAfDouble.Response(200, """{"downlinkBitRates":$rates,"uplinkBitRates":$rates}"""))

    // Table 11.4.1.1-2 and 11.4.2-2: the Network Assistance Session is created first; the recommendation is returned and signalled
    @Test
    fun aBitRateRecommendationCreatesTheSessionAndReturnsTheRates() {
        af.answer("POST", base, MediaAfDouble.Response(201, naJson()))
        answerRecommendation()
        val s = service()
        val recommendation = s.requestBitRateRecommendation("video")!!
        assertEquals("8 Mbps", recommendation.recommendedDownlinkBitRate)
        assertEquals("8 Mbps", recommendation.recommendedUplinkBitRate)
        assertEquals(listOf("POST", "GET"), af.requests.map { it.method })
        assertEquals(listOf(SessionHandlerEvents.NetworkAssistance.BIT_RATE_RECOMMENDATION), events.map { it.name })
        val payload = events[0].payload
        assertEquals("s1", payload[Keys.MEDIA_DELIVERY_SESSION_IDENTIFIER])
        assertEquals(RecommendationSources.MEDIA_AF, payload[Keys.RECOMMENDATION_SOURCE])
        assertEquals("8 Mbps", payload[Keys.RECOMMENDED_DOWNLINK_BIT_RATE])
        assertEquals(true, s.recommendationReply("video", recommendation)[Keys.SUCCEEDED])
    }

    // Clause 5.3.4.2: the flow of the Media AS, no policy cited when no dynamic QoS policy is in force, no mediaTransportParameters
    @Test
    fun theSessionDescribesTheFlowAndCitesNoPolicyWhenNoneIsInForce() {
        af.answer("POST", base, MediaAfDouble.Response(201, naJson()))
        answerRecommendation()
        service().requestBitRateRecommendation("video")
        val sent = JsonParser().parse(af.requests[0].body).asJsonObject
        assertEquals("s1", sent["sessionId"].asString)
        assertFalse(sent.has("policyTemplateId"))
        assertFalse(sent.has("componentReference"))
        val flow = sent["applicationFlowDescription"].asJsonObject
        assertEquals("192.0.2.1", flow["packetFilter"].asJsonObject["destinationAddress"].asJsonObject["ipv4Addr"].asString)
        assertFalse(flow.has("mediaTransportParameters"))
    }

    // Clause 11.4.1.1: "citing the relevant Policy Template identifier and service component reference if a dynamic QoS policy has been activated"
    @Test
    fun theSessionCitesThePolicyTemplateAndTheComponentWhenAPolicyIsInForce() {
        template = "pt1"
        af.answer("POST", base, MediaAfDouble.Response(201, naJson("pt1", "video")))
        answerRecommendation()
        service().requestBitRateRecommendation("video")
        val sent = JsonParser().parse(af.requests[0].body).asJsonObject
        assertEquals("pt1", sent["policyTemplateId"].asString)
        assertEquals("video", sent["componentReference"].asString)
    }

    // "If it has not already done so": the second request does not create another session
    @Test
    fun theSessionIsCreatedOnlyOnce() {
        af.answer("POST", base, MediaAfDouble.Response(201, naJson()))
        answerRecommendation()
        val s = service()
        s.requestBitRateRecommendation("video")
        s.requestBitRateRecommendation("video")
        assertEquals(listOf("POST", "GET", "GET"), af.requests.map { it.method })
    }

    // Clause 5.3.4.6: a change of the policy in force is notified to the Media AF by replacing the session
    @Test
    fun aChangeOfThePolicyInForceReplacesTheSession() {
        af.answer("POST", base, MediaAfDouble.Response(201, naJson()))
        af.answer("PUT", base + "n1", MediaAfDouble.Response(200, naJson("pt2", "video")))
        answerRecommendation()
        val s = service()
        s.requestBitRateRecommendation("video")
        template = "pt2"
        s.requestBitRateRecommendation("video")
        assertEquals(listOf("POST", "GET", "PUT", "GET"), af.requests.map { it.method })
        assertEquals("pt2", JsonParser().parse(af.requests[2].body).asJsonObject["policyTemplateId"].asString)
    }

    // Clause 5.3.4.2: the QoS that the client asked for in the policy in force is the initial QoS of the session; without a policy it is not sent
    @Test
    fun theSessionRequestsTheQoSOfThePolicyInForce() {
        af.answer("POST", base, MediaAfDouble.Response(201, naJson("pt1", "video")))
        answerRecommendation()
        template = "pt1"
        requestedQoS = DynamicPolicyRequests.qosSpecification("3 Mbps", "1 Mbps")
        service().requestBitRateRecommendation("video")
        val body = JsonParser().parse(af.requests[0].body).asJsonObject
        assertEquals("3 Mbps", body["requestedQoS"].asJsonObject["downlinkBitRates"].asJsonObject["maximumRequestedBitRate"].asString)
        assertEquals("1 Mbps", body["requestedQoS"].asJsonObject["uplinkBitRates"].asJsonObject["maximumRequestedBitRate"].asString)
    }

    @Test
    fun noPolicyInForceMeansNoRequestedQoS() {
        af.answer("POST", base, MediaAfDouble.Response(201, naJson()))
        answerRecommendation()
        requestedQoS = DynamicPolicyRequests.qosSpecification("3 Mbps", "1 Mbps")
        service().requestBitRateRecommendation("video")
        assertEquals(false, JsonParser().parse(af.requests[0].body).asJsonObject.has("requestedQoS"))
    }

    // Clause 5.3.4.6: when the policy is deactivated the session is replaced so that it no longer cites it
    @Test
    fun deactivatingThePolicyReplacesTheSessionWithoutIt() {
        af.answer("POST", base, MediaAfDouble.Response(201, naJson("pt1", "video")))
        af.answer("PUT", base + "n1", MediaAfDouble.Response(200, naJson()))
        answerRecommendation()
        template = "pt1"
        requestedQoS = DynamicPolicyRequests.qosSpecification("3 Mbps", "1 Mbps")
        val s = service()
        s.requestBitRateRecommendation("video")
        template = null
        requestedQoS = null
        s.policyChanged()
        assertEquals(listOf("POST", "GET", "PUT"), af.requests.map { it.method })
        assertEquals(false, JsonParser().parse(af.requests[2].body).asJsonObject.has("policyTemplateId"))
    }

    @Test
    fun aPolicyChangeWithoutASessionDoesNothing() {
        template = "pt1"
        service().policyChanged()
        assertEquals(0, af.requests.size)
    }

    // Table 11.4.2-3
    @Test
    fun aRefusedRecommendationIsErrorBitRateRecommendation() {
        af.answer("POST", base, MediaAfDouble.Response(201, naJson()))
        af.answer("GET", base + "n1/recommendation", MediaAfDouble.Response(403, """{"title":"no rights","detail":"no recommendation"}"""))
        val s = service()
        assertNull(s.requestBitRateRecommendation("video"))
        assertEquals(listOf(SessionHandlerEvents.Error.ERROR_BIT_RATE_RECOMMENDATION), events.map { it.name })
        assertEquals(RecommendationSources.MEDIA_AF, events[0].payload[Keys.RECOMMENDATION_SOURCE])
        assertEquals("s1", events[0].payload[Keys.MEDIA_DELIVERY_SESSION_IDENTIFIER])
        assertEquals("no recommendation", s.lastFailure)
        assertEquals(false, s.recommendationReply("video", null)[Keys.SUCCEEDED])
    }

    @Test
    fun aSessionThatCannotBeCreatedIsErrorBitRateRecommendation() {
        af.answer("POST", base, MediaAfDouble.Response(400, """{"title":"bad"}"""))
        assertNull(service().requestBitRateRecommendation("video"))
        assertEquals(listOf(SessionHandlerEvents.Error.ERROR_BIT_RATE_RECOMMENDATION), events.map { it.name })
        assertEquals(listOf("POST"), af.requests.map { it.method })
    }

    @Test
    fun withoutAnAddressOfTheMediaAsNothingIsSentAndItIsAnError() {
        resolved = emptyList()
        assertNull(service().requestBitRateRecommendation("video"))
        assertTrue(af.requests.isEmpty())
        assertEquals(listOf(SessionHandlerEvents.Error.ERROR_BIT_RATE_RECOMMENDATION), events.map { it.name })
    }

    // Table 11.4.1.2-2: true when the boost is granted
    @Test
    fun aGrantedDeliveryBoostIsTrue() {
        af.answer("POST", base, MediaAfDouble.Response(201, naJson()))
        af.answer("POST", base + "n1/boost-request", MediaAfDouble.Response(200, """{"success":true}"""))
        val s = service()
        assertTrue(s.requestDeliveryBoost("video"))
        assertTrue(events.isEmpty())
        assertEquals("boost-request", af.requests.last().path.substringAfterLast('/'))
        assertEquals(true, s.boostReply("video", true)[Keys.SUCCEEDED])
    }

    @Test
    fun aBoostThatTheMediaAfDidNotApplyIsFalseWithoutAnEvent() {
        af.answer("POST", base, MediaAfDouble.Response(201, naJson()))
        af.answer("POST", base + "n1/boost-request", MediaAfDouble.Response(200, """{"success":false,"reason":"busy"}"""))
        val s = service()
        assertFalse(s.requestDeliveryBoost("video"))
        assertTrue(events.isEmpty())
        assertEquals("busy", s.lastFailure)
    }

    // Table 11.4.2-3
    @Test
    fun aRefusedDeliveryBoostIsErrorDeliveryBoost() {
        af.answer("POST", base, MediaAfDouble.Response(201, naJson()))
        af.answer("POST", base + "n1/boost-request", MediaAfDouble.Response(403, """{"title":"refused"}"""))
        val s = service()
        assertFalse(s.requestDeliveryBoost("video"))
        assertEquals(listOf(SessionHandlerEvents.Error.ERROR_DELIVERY_BOOST), events.map { it.name })
        assertEquals(RecommendationSources.MEDIA_AF, events[0].payload[Keys.RECOMMENDATION_SOURCE])
        assertNotNull(s.lastFailure)
        assertEquals(false, s.boostReply("video", false)[Keys.SUCCEEDED])
    }

    // Clause 5.3.4.7: the session is destroyed when the media delivery session ends, without an event
    @Test
    fun releaseDestroysTheSession() {
        af.answer("POST", base, MediaAfDouble.Response(201, naJson()))
        answerRecommendation()
        af.answer("DELETE", base + "n1", MediaAfDouble.Response(204))
        val s = service()
        s.requestBitRateRecommendation("video")
        events.clear()
        s.release()
        assertEquals("DELETE", af.requests.last().method)
        assertTrue(events.isEmpty())
        assertNull(s.sessionIdentifier)
    }

    // ---- the notification channel ----

    private fun created(topics: MutableList<String>? = null): NetworkAssistanceService {
        af.answer("POST", base, MediaAfDouble.Response(201, naJson()))
        answerRecommendation()
        val s = service()
        if (topics != null) {
            s.subscribeTopic = { topics.add("+$it") }
            s.unsubscribeTopic = { topics.add("-$it") }
        }
        s.requestBitRateRecommendation("video")
        events.clear()
        return s
    }

    private fun notified(rate: String?) = com.google.gson.Gson().fromJson(
        naJson().dropLast(1) + if (rate == null) "}" else ""","recommendedQoS":{"downlinkBitRates":{"maximumRequestedBitRate":"$rate","minimumDesiredBitRate":"1 Mbps","minimumRequestedBitRate":"1 Mbps"},"uplinkBitRates":{"maximumRequestedBitRate":"$rate","minimumDesiredBitRate":"1 Mbps","minimumRequestedBitRate":"1 Mbps"}}}""",
        com.fivegmag.a5gmscommonlibrary.models.NetworkAssistanceSession::class.java
    )

    // Clauses 5.3.4.2 and 5.3.4.7
    @Test
    fun theSubTopicOfTheSessionIsSubscribedAndLeft() {
        val topics = ArrayList<String>()
        af.answer("DELETE", base + "n1", MediaAfDouble.Response(204))
        val s = created(topics)
        assertEquals(listOf("+n1"), topics)
        s.release()
        assertEquals(listOf("+n1", "-n1"), topics)
    }

    // Table 11.4.2-2: BIT_RATE_RECOMMENDATION when the Media AF publishes a new recommendation
    @Test
    fun aNewRecommendationOfTheMediaAfIsSignalled() {
        val s = created()
        s.onNotification(notified("3 Mbps"))
        assertEquals(listOf(SessionHandlerEvents.NetworkAssistance.BIT_RATE_RECOMMENDATION), events.map { it.name })
        assertEquals("3 Mbps", events[0].payload[Keys.RECOMMENDED_UPLINK_BIT_RATE])
        assertEquals(RecommendationSources.MEDIA_AF, events[0].payload[Keys.RECOMMENDATION_SOURCE])
        s.onNotification(notified("3 Mbps"))
        s.onNotification(notified(null))
        assertEquals(1, events.size)
    }

    @Test
    fun aNotificationForAnotherSessionIsIgnored() {
        val s = created()
        s.onNotification(com.google.gson.Gson().fromJson(naJson().replace("n1", "n2"), com.fivegmag.a5gmscommonlibrary.models.NetworkAssistanceSession::class.java))
        assertTrue(events.isEmpty())
    }
}
