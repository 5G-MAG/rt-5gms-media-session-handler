package com.fivegmag.a5gmsmediasessionhandler

import com.fivegmag.a5gmscommonlibrary.models.ApplicationFlowDescription
import com.fivegmag.a5gmscommonlibrary.models.IpAddr
import com.fivegmag.a5gmscommonlibrary.models.IpPacketFilterSet
import com.fivegmag.a5gmscommonlibrary.models.NetworkAssistanceSession
import com.fivegmag.a5gmsmediasessionhandler.network.DynamicPolicyM5Client
import com.fivegmag.a5gmsmediasessionhandler.network.IDynamicPolicyApi
import com.fivegmag.a5gmsmediasessionhandler.network.INetworkAssistanceApi
import com.fivegmag.a5gmsmediasessionhandler.network.NetworkAssistanceM5Client
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

// RFC 6750 clause 3.1: an M5 call that the Media AF refuses with 401 invalid_token is made once more with a new access token
class M5TokenRenewalUnitTest {
    private val af = MediaAfDouble()
    private val retrofit = Retrofit.Builder().baseUrl(af.baseUrl).addConverterFactory(GsonConverterFactory.create()).client(OkHttpClient()).build()
    private val flow = ApplicationFlowDescription("5_TUPLE", IpPacketFilterSet("BIDIRECTIONAL", IpAddr("10.0.0.2"), IpAddr("192.0.2.1"), 6, 0, 0))
    private val base = "provisioning-sessions/ps1"
    private val invalid = MediaAfDouble.Response(401, null, null, "Bearer error=\"invalid_token\"")
    private val policyJson = """{"dynamicPolicyId":"d1","provisioningSessionId":"ps1","sessionId":"s1","policyTemplateId":"pt1",
        "applicationFlowBindings":[],"qosEnforcement":true,"l4SEnabled":false,"qoSMonitoringEnabled":false}"""

    @After
    fun stop() = af.close()

    private fun dp(renew: (() -> String?)?) = DynamicPolicyM5Client(retrofit.create(IDynamicPolicyApi::class.java), "Bearer old", renew)

    @Test
    fun dynamicPolicyCallIsMadeAgainWithTheNewToken() {
        af.answerOnce("GET", "$base/dynamic-policies/d1", invalid)
        af.answer("GET", "$base/dynamic-policies/d1", MediaAfDouble.Response(200, policyJson))
        val result = dp { "Bearer new" }.retrieve("ps1", "d1")
        assertTrue(result.isSuccess)
        assertEquals(listOf("Bearer old", "Bearer new"), af.requests.map { it.authorization })
    }

    @Test
    fun withoutRenewalTheRefusalIsReturned() {
        af.answer("GET", "$base/dynamic-policies/d1", invalid)
        val result = dp(null).retrieve("ps1", "d1")
        assertEquals(401, result.code)
        assertEquals(1, af.requests.size)
    }

    @Test
    fun noTokenFromTheRenewalReturnsTheFirstRefusal() {
        af.answer("GET", "$base/dynamic-policies/d1", invalid)
        val result = dp { null }.retrieve("ps1", "d1")
        assertEquals(401, result.code)
        assertEquals(1, af.requests.size)
    }

    @Test
    fun aRefusalOtherThanInvalidTokenIsNotRetried() {
        af.answer("GET", "$base/dynamic-policies/d1", MediaAfDouble.Response(403))
        var renewed = false
        val result = dp { renewed = true; "Bearer new" }.retrieve("ps1", "d1")
        assertEquals(403, result.code)
        assertFalse(renewed)
        assertEquals(1, af.requests.size)
    }

    @Test
    fun theSecondRefusalIsNotRetriedAgain() {
        af.answer("GET", "$base/dynamic-policies/d1", invalid)
        val result = dp { "Bearer new" }.retrieve("ps1", "d1")
        assertEquals(401, result.code)
        assertEquals(2, af.requests.size)
    }

    @Test
    fun networkAssistanceCallIsMadeAgainWithTheNewToken() {
        af.answerOnce("POST", "$base/network-assistance-sessions/", invalid)
        af.answer("POST", "$base/network-assistance-sessions/", MediaAfDouble.Response(201, """{"naSessionId":"n1","provisioningSessionId":"ps1","sessionId":"s1"}""", "http://af/x/n1"))
        val na = NetworkAssistanceM5Client(retrofit.create(INetworkAssistanceApi::class.java), "Bearer old") { "Bearer new" }
        val result = na.create("ps1", NetworkAssistanceSession("s1", flow, policyTemplateId = "pt1", componentReference = "video"))
        assertEquals(201, result.code)
        assertEquals(listOf("Bearer old", "Bearer new"), af.requests.map { it.authorization })
    }

    @Test
    fun theLaterCallsStartWithTheRenewedToken() {
        af.answerOnce("GET", "$base/dynamic-policies/d1", invalid)
        af.answer("GET", "$base/dynamic-policies/d1", MediaAfDouble.Response(200, policyJson))
        val client = dp { "Bearer new" }
        client.retrieve("ps1", "d1")
        client.retrieve("ps1", "d1")
        assertEquals(listOf("Bearer old", "Bearer new", "Bearer new"), af.requests.map { it.authorization })
    }
}
