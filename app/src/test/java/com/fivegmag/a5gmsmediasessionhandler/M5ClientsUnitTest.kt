package com.fivegmag.a5gmsmediasessionhandler

import com.fivegmag.a5gmscommonlibrary.models.ApplicationFlowDescription
import com.fivegmag.a5gmscommonlibrary.models.DynamicPolicy
import com.fivegmag.a5gmscommonlibrary.models.IpAddr
import com.fivegmag.a5gmscommonlibrary.models.IpPacketFilterSet
import com.fivegmag.a5gmscommonlibrary.models.NetworkAssistanceSession
import com.fivegmag.a5gmsmediasessionhandler.network.DynamicPolicyM5Client
import com.fivegmag.a5gmsmediasessionhandler.network.IDynamicPolicyApi
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

class M5ClientsUnitTest {
    private val af = MediaAfDouble()
    private val retrofit = Retrofit.Builder().baseUrl(af.baseUrl).addConverterFactory(GsonConverterFactory.create()).client(OkHttpClient()).build()
    private val dp = DynamicPolicyM5Client(retrofit.create(IDynamicPolicyApi::class.java), "Bearer t")
    private val na = NetworkAssistanceM5Client(retrofit.create(INetworkAssistanceApi::class.java), "Bearer t")
    private val flow = ApplicationFlowDescription("5_TUPLE", IpPacketFilterSet("BIDIRECTIONAL", IpAddr("10.0.0.2"), IpAddr("192.0.2.1"), 6, 0, 0))
    private val base = "provisioning-sessions/ps1"

    @After
    fun stop() = af.close()

    private val policyJson = """{"dynamicPolicyId":"d1","provisioningSessionId":"ps1","sessionId":"s1","policyTemplateId":"pt1",
        "applicationFlowBindings":[],"qosEnforcement":true,"l4SEnabled":false,"qoSMonitoringEnabled":true}"""

    // TS 26.510 V19.2.0 clause 5.3.3.2: POST on the collection, 201 with the new resource and its Location
    @Test
    fun createsADynamicPolicy() {
        af.answer("POST", "$base/dynamic-policies/", MediaAfDouble.Response(201, policyJson, "http://af/x/d1"))
        val result = dp.create("ps1", DynamicPolicy("s1", "pt1", arrayListOf()))
        assertEquals(201, result.code)
        assertEquals("d1", result.resource!!.dynamicPolicyId)
        assertEquals("http://af/x/d1", result.location)
        val request = af.requests.single()
        assertEquals("Bearer t", request.authorization)
        assertTrue(request.contentType!!.startsWith("application/json"))
        assertEquals("pt1", JsonParser().parse(request.body).asJsonObject["policyTemplateId"].asString)
        assertFalse(request.body.contains("dynamicPolicyId"))
    }

    // Clause 5.3.3.3
    @Test
    fun retrievesADynamicPolicy() {
        af.answer("GET", "$base/dynamic-policies/d1", MediaAfDouble.Response(200, policyJson))
        val result = dp.retrieve("ps1", "d1")
        assertTrue(result.isSuccess)
        assertTrue(result.resource!!.qoSMonitoringEnabled!!)
        assertEquals("GET", af.requests.single().method)
    }

    // Clause 5.3.3.4: PUT replaces; 204 is "no change" and has no body
    @Test
    fun replacesADynamicPolicy() {
        af.answer("PUT", "$base/dynamic-policies/d1", MediaAfDouble.Response(200, policyJson))
        assertEquals(200, dp.replace("ps1", "d1", DynamicPolicy("s1", "pt2", arrayListOf())).code)
        af.answer("PUT", "$base/dynamic-policies/d1", MediaAfDouble.Response(204))
        val unchanged = dp.replace("ps1", "d1", DynamicPolicy("s1", "pt2", arrayListOf()))
        assertEquals(204, unchanged.code)
        assertTrue(unchanged.isSuccess)
        assertNull(unchanged.resource)
    }

    // Clause 5.3.3.5
    @Test
    fun destroysADynamicPolicy() {
        af.answer("DELETE", "$base/dynamic-policies/d1", MediaAfDouble.Response(204))
        val result = dp.destroy("ps1", "d1")
        assertTrue(result.isSuccess)
        assertEquals("Bearer t", af.requests.single().authorization)
    }

    // Clause 7.1.7: the error message body is read, the Media AF names the property that it refuses
    @Test
    fun readsTheProblemDetailsOfARefusal() {
        af.answer("POST", "$base/dynamic-policies/", MediaAfDouble.Response(400, """{"title":"Bad","detail":"no","invalidParams":[{"param":"policyTemplateId","reason":"unknown"}]}"""))
        val result = dp.create("ps1", DynamicPolicy("s1", "pt1", arrayListOf()))
        assertEquals(400, result.code)
        assertNull(result.resource)
        assertTrue(result.complainsAbout("policyTemplateId"))
        assertFalse(result.complainsAbout("bdtSpecification"))
    }

    @Test
    fun anAuthorisationRefusalIsRecognised() {
        af.answer("POST", "$base/dynamic-policies/", MediaAfDouble.Response(403, """{"title":"Forbidden"}"""))
        assertTrue(dp.create("ps1", DynamicPolicy("s1", "pt1", arrayListOf())).isUnauthorised)
        af.answer("POST", "$base/dynamic-policies/", MediaAfDouble.Response(401))
        assertTrue(dp.create("ps1", DynamicPolicy("s1", "pt1", arrayListOf())).isUnauthorised)
    }

    @Test
    fun noResponseGivesCodeZeroAndTheReason() {
        af.close()
        val result = dp.retrieve("ps1", "d1")
        assertEquals(0, result.code)
        assertFalse(result.isSuccess)
        assertNotNull(result.failure)
    }

    private val naJson = """{"naSessionId":"n1","provisioningSessionId":"ps1","sessionId":"s1","applicationFlowDescription":{"filterMethod":"5_TUPLE"}}"""

    // Clause 5.3.4.2
    @Test
    fun createsANetworkAssistanceSession() {
        af.answer("POST", "$base/network-assistance-sessions/", MediaAfDouble.Response(201, naJson, "http://af/x/n1"))
        val result = na.create("ps1", NetworkAssistanceSession("s1", flow, policyTemplateId = "pt1", componentReference = "video"))
        assertEquals("n1", result.resource!!.naSessionId)
        val sent = JsonParser().parse(af.requests.single().body).asJsonObject
        assertEquals("s1", sent["sessionId"].asString)
        assertEquals("192.0.2.1", sent["applicationFlowDescription"].asJsonObject["packetFilter"].asJsonObject["destinationAddress"].asJsonObject["ipv4Addr"].asString)
        assertFalse(sent.has("naSessionId"))
        assertFalse(sent.has("mediaTransportParameters"))
    }

    // Clauses 5.3.4.3, 5.3.4.6 and 5.3.4.7
    @Test
    fun retrievesReplacesAndDestroysANetworkAssistanceSession() {
        af.answer("GET", "$base/network-assistance-sessions/n1", MediaAfDouble.Response(200, naJson))
        af.answer("PUT", "$base/network-assistance-sessions/n1", MediaAfDouble.Response(200, naJson))
        af.answer("DELETE", "$base/network-assistance-sessions/n1", MediaAfDouble.Response(204))
        assertEquals("n1", na.retrieve("ps1", "n1").resource!!.naSessionId)
        assertEquals(200, na.replace("ps1", "n1", NetworkAssistanceSession("s1", flow, policyTemplateId = "pt2", componentReference = "video")).code)
        assertTrue(na.destroy("ps1", "n1").isSuccess)
        assertEquals(listOf("GET", "PUT", "DELETE"), af.requests.map { it.method })
    }

    // Clause 5.3.4.4: GET on the recommendation sub-resource, a ClientPolicySpecification in the answer
    @Test
    fun requestsABitRateRecommendation() {
        val rates = """{"maximumRequestedBitRate":"8 Mbps","minimumDesiredBitRate":"4 Mbps","minimumRequestedBitRate":"4 Mbps"}"""
        af.answer("GET", "$base/network-assistance-sessions/n1/recommendation", MediaAfDouble.Response(200, """{"downlinkBitRates":$rates,"uplinkBitRates":$rates}"""))
        val result = na.recommendation("ps1", "n1")
        assertEquals("8 Mbps", result.resource!!.downlinkBitRates.maximumRequestedBitRate)
        assertEquals("4 Mbps", result.resource!!.downlinkBitRates.minimumRequestedBitRate)
    }

    // Clause 5.3.4.5: POST on the delivery boost sub-resource (the path of table 9.4.2-1), an OperationSuccessResponse in the answer
    @Test
    fun requestsADeliveryBoost() {
        af.answer("POST", "$base/network-assistance-sessions/n1/boost-request", MediaAfDouble.Response(200, """{"success":true}"""))
        assertTrue(na.boostRequest("ps1", "n1").resource!!.success)
        af.answer("POST", "$base/network-assistance-sessions/n1/boost-request", MediaAfDouble.Response(403, """{"title":"refused"}"""))
        assertTrue(na.boostRequest("ps1", "n1").isUnauthorised)
    }
}
