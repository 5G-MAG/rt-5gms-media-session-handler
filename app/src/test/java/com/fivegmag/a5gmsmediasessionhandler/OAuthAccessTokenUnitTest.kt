package com.fivegmag.a5gmsmediasessionhandler

import android.os.Messenger
import com.fivegmag.a5gmscommonlibrary.models.ServiceListEntry
import com.fivegmag.a5gmsmediasessionhandler.controller.ServiceAccessInformationController
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
import com.fivegmag.a5gmsmediasessionhandler.network.OAuthClientCredentials
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.Properties

class OAuthAccessTokenUnitTest {
    private val af = MediaAfDouble()
    private val retrofitBuilder = Retrofit.Builder().addConverterFactory(GsonConverterFactory.create()).client(OkHttpClient())
    private val sessions = HashMap<Int, ClientSessionData>()
    private val controller = ServiceAccessInformationController(sessions, retrofitBuilder)
    private val entry = ServiceListEntry("ps1", "service", null)
    private val saiJson = """{"provisioningSessionId":"ps1"}"""
    private val saiPath = "service-access-information/ps1"

    @After
    fun stop() {
        controller.stopUpdateTimer(1)
        af.close()
    }

    private fun start(accessToken: String?, withClientCredentials: Boolean = true) {
        // a long refresh period: the periodic check does not run during a test
        controller.initialize(Properties().apply { setProperty("defaultServiceAccessInformationTimerInterval", "3600") })
        sessions[1] = ClientSessionData(Messenger(null as android.os.Handler?)).also { it.accessToken = accessToken }
        val retrofit = retrofitBuilder.baseUrl(af.baseUrl).build()
        sessions[1]!!.serviceAccessInformationSessionData.api =
            retrofit.create(com.fivegmag.a5gmsmediasessionhandler.network.IServiceAccessInformationApi::class.java)
        if (withClientCredentials) {
            controller.oAuthClient = OAuthClientCredentials(af.baseUrl + "token", "client", "secret", "scope-a", OkHttpClient())
        }
    }

    private fun token(value: String) = MediaAfDouble.Response(200, """{"access_token":"$value","token_type":"Bearer","expires_in":3600}""")

    // RFC 6749 clause 4.4.2: grant_type=client_credentials, client authentication, optional scope
    @Test
    fun requestsATokenWithTheClientCredentialsGrant() {
        af.answer("POST", "token", token("t1"))
        val result = OAuthClientCredentials(af.baseUrl + "token", "client", "secret", "scope-a", OkHttpClient()).requestToken()
        assertEquals("t1", result.value)
        assertEquals(3600L, result.expiresInSeconds)
        val request = af.requests.single()
        assertEquals(Credentials.basic("client", "secret"), request.authorization)
        assertTrue(request.contentType!!.startsWith("application/x-www-form-urlencoded"))
        assertEquals("grant_type=client_credentials&scope=scope-a", request.body)
    }

    @Test
    fun aRefusedTokenRequestIsAnError() {
        af.answer("POST", "token", MediaAfDouble.Response(401, """{"error":"invalid_client"}"""))
        try {
            OAuthClientCredentials(af.baseUrl + "token", "client", "wrong", null, OkHttpClient()).requestToken()
            fail("a refused request is an error")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("401"))
        }
    }

    @Test
    fun theTokenOfTheApplicationStaysTheFirstChoice() {
        start("app-token")
        af.answer("GET", saiPath, MediaAfDouble.Response(200, saiJson))
        assertNotNull(runBlocking { controller.updateServiceAccessInformation(entry, 1) })
        assertEquals(listOf("Bearer app-token"), af.requests.map { it.authorization })
    }

    @Test
    fun aTokenIsObtainedBeforeTheFirstRequestWhenTheApplicationGaveNone() {
        start(null)
        af.answer("POST", "token", token("t1"))
        af.answer("GET", saiPath, MediaAfDouble.Response(200, saiJson))
        assertNotNull(runBlocking { controller.updateServiceAccessInformation(entry, 1) })
        assertEquals(listOf("POST", "GET"), af.requests.map { it.method })
        assertEquals("Bearer t1", af.requests.last().authorization)
        assertEquals("t1", sessions[1]!!.accessToken)
    }

    // RFC 6750 clause 3.1: 401 with error="invalid_token" after expiry: a new token and one retry
    @Test
    fun anExpiredTokenIsRenewedAndTheRequestRepeatedOnce() {
        start("old")
        af.answerOnce("GET", saiPath, MediaAfDouble.Response(401, "{}", wwwAuthenticate = "Bearer realm=\"5GMS Media AF\", error=\"invalid_token\""))
        af.answer("GET", saiPath, MediaAfDouble.Response(200, saiJson))
        af.answer("POST", "token", token("new"))
        assertNotNull(runBlocking { controller.updateServiceAccessInformation(entry, 1) })
        assertEquals(listOf("Bearer old", "Bearer new"), af.requests.filter { it.method == "GET" }.map { it.authorization })
        assertEquals("new", sessions[1]!!.accessToken)
    }

    @Test
    fun aSecondRefusalIsNotRetried() {
        start("old")
        af.answer("GET", saiPath, MediaAfDouble.Response(401, "{}", wwwAuthenticate = "Bearer error=\"invalid_token\""))
        af.answer("POST", "token", token("new"))
        assertNull(runBlocking { controller.updateServiceAccessInformation(entry, 1) })
        assertEquals(2, af.requests.count { it.method == "GET" })
    }

    @Test
    fun withoutClientCredentialsAnExpiredTokenIsNotRenewed() {
        start("old", withClientCredentials = false)
        af.answer("GET", saiPath, MediaAfDouble.Response(401, "{}", wwwAuthenticate = "Bearer error=\"invalid_token\""))
        assertNull(runBlocking { controller.updateServiceAccessInformation(entry, 1) })
        assertEquals(1, af.requests.size)
    }

    // RFC 6750 clause 3.1: 403 insufficient_scope is reported to the client
    @Test
    fun anInsufficientScopeIsReported() {
        start("t")
        var denied: Int? = null
        controller.onAccessDenied = { denied = it }
        af.answer("GET", saiPath, MediaAfDouble.Response(403, "{}", wwwAuthenticate = "Bearer error=\"insufficient_scope\", scope=\"x\""))
        assertNull(runBlocking { controller.updateServiceAccessInformation(entry, 1) })
        assertEquals(1, denied)
    }

    @Test
    fun configuredCredentialsComeFromTheProperties() {
        val properties = Properties().apply {
            setProperty("defaultServiceAccessInformationTimerInterval", "10")
            setProperty("oauthTokenUrl", af.baseUrl + "token")
            setProperty("oauthClientId", "client")
            setProperty("oauthClientSecret", "secret")
            setProperty("oauthScope", "")
        }
        controller.initialize(properties)
        assertNotNull(controller.oAuthClient)
        properties.setProperty("oauthTokenUrl", "")
        val other = ServiceAccessInformationController(sessions, retrofitBuilder)
        other.initialize(properties)
        assertNull(other.oAuthClient)
    }
}
