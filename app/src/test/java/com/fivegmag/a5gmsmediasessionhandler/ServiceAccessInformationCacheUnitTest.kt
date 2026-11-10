package com.fivegmag.a5gmsmediasessionhandler

import android.os.Messenger
import com.fivegmag.a5gmscommonlibrary.models.ServiceListEntry
import com.fivegmag.a5gmsmediasessionhandler.controller.ServiceAccessInformationController
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
import com.fivegmag.a5gmsmediasessionhandler.network.IServiceAccessInformationApi
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Properties

/**
 * TS 26.510 V19.2.0 clause 5.4.2.1: "If it does not already have a fresh copy cached, the Media Session Handler shall attempt to acquire full
 * Service Access Information ... from the Media AF using the operation defined in clause 5.3.2.3". The max-age of Cache-Control is the
 * time-to-live of the resource (clause 7.1.4.2).
 */
class ServiceAccessInformationCacheUnitTest {
    private val af = MediaAfDouble()
    private val retrofitBuilder = Retrofit.Builder().addConverterFactory(GsonConverterFactory.create()).client(OkHttpClient())
    private val sessions = HashMap<Int, ClientSessionData>()
    private val controller = ServiceAccessInformationController(sessions, retrofitBuilder)
    private val entry = ServiceListEntry("ps1", "service", null)
    private val saiJson = """{"provisioningSessionId":"ps1"}"""
    private val saiPath = "service-access-information/ps1"
    private var now = 1_800_000_000_000L

    init {
        // a long refresh period: the periodic check does not run during a test
        controller.initialize(Properties().apply { setProperty("defaultServiceAccessInformationTimerInterval", "3600") })
        controller.clock = { now }
    }

    @After
    fun stop() {
        sessions.keys.forEach { controller.stopUpdateTimer(it) }
        af.close()
    }

    /** A media delivery session of its own (a Media Stream Handler instance) that uses the M5 endpoint of the test double */
    private fun session(id: Int, accessToken: String? = null, baseUrl: String = af.baseUrl) {
        sessions[id] = ClientSessionData(Messenger(null as android.os.Handler?)).also { it.accessToken = accessToken }
        val data = sessions[id]!!.serviceAccessInformationSessionData
        data.api = retrofitBuilder.baseUrl(baseUrl).build().create(IServiceAccessInformationApi::class.java)
        data.m5BaseUrl = baseUrl
    }

    private fun acquire(id: Int, serviceEntry: ServiceListEntry = entry) = runBlocking { controller.updateServiceAccessInformation(serviceEntry, id) }

    private fun answer(vararg headers: Pair<String, String>) =
        af.answer("GET", saiPath, MediaAfDouble.Response(200, saiJson, headers = mapOf(*headers)))

    private val gets get() = af.requests.count { it.method == "GET" }

    @Test
    fun aFreshCopyIsUsedByTheNextSessionsOfTheSameService() {
        answer("Cache-Control" to "max-age=60", "ETag" to "\"v1\"")
        (1..3).forEach { session(it) }
        val first = acquire(1)
        val second = acquire(2)
        val third = acquire(3)
        assertEquals(1, gets)
        assertEquals("ps1", first!!.provisioningSessionId)
        assertEquals(first, second)
        assertEquals(first, third)
    }

    @Test
    fun everySessionHoldsAnObjectOfItsOwn() {
        answer("Cache-Control" to "max-age=60")
        session(1)
        session(2)
        val first = acquire(1)!!
        val second = acquire(2)!!
        assertNotSame(first, second)
        // what a session adds to the resource (the manifest configurations) does not reach another session
        first.clientMetricsReportingConfigurations = ArrayList()
        assertNull(second.clientMetricsReportingConfigurations)
    }

    @Test
    fun aSessionThatTookTheCopyChecksForUpdatesWhenItsTimeToLiveHasElapsed() {
        answer("Cache-Control" to "max-age=60", "ETag" to "\"v1\"")
        session(1)
        session(2)
        acquire(1)
        now += 20_000
        acquire(2)
        assertNotNull(sessions[2]!!.serviceAccessInformationSessionData.requestTimer)
        assertEquals("\"v1\"", sessions[2]!!.serviceAccessInformationSessionData.responseHeaders!!["etag"])
    }

    @Test
    fun aStaleCopyIsNotUsed() {
        answer("Cache-Control" to "max-age=10")
        session(1)
        session(2)
        acquire(1)
        now += 9_000
        acquire(2)
        assertEquals(1, gets)
        now += 2_000
        session(3)
        assertNotNull(acquire(3))
        assertEquals(2, gets)
    }

    @Test
    fun theAgeOfTheResponseIsSubtractedFromTheMaxAge() {
        answer("Cache-Control" to "max-age=10", "Age" to "8")
        session(1)
        session(2)
        acquire(1)
        now += 3_000
        acquire(2)
        assertEquals(2, gets)
    }

    @Test
    fun aMaxAgeOfZeroIsNeverFresh() {
        answer("Cache-Control" to "max-age=0")
        session(1)
        session(2)
        acquire(1)
        acquire(2)
        assertEquals(2, gets)
    }

    @Test
    fun aResponseThatMustBeRevalidatedIsNotReused() {
        answer("Cache-Control" to "no-cache, max-age=60")
        session(1)
        session(2)
        acquire(1)
        acquire(2)
        assertEquals(2, gets)
    }

    @Test
    fun aFailedAcquisitionIsNotCached() {
        session(1)
        session(2)
        assertNull(acquire(1))
        answer("Cache-Control" to "max-age=60")
        assertNotNull(acquire(2))
        assertEquals(2, gets)
    }

    @Test
    fun theCopyIsDroppedWhenTheMediaAfAnswersWithAnError() {
        answer("Cache-Control" to "max-age=1")
        session(1)
        acquire(1)
        // the copy is fresh for 1 s; the Media AF has withdrawn the resource when the session checks again
        now += 2_000
        af.answer("GET", saiPath, MediaAfDouble.Response(404, "{}"))
        session(2)
        assertNull(acquire(2))
        answer("Cache-Control" to "max-age=60")
        session(3)
        assertNotNull(acquire(3))
        assertEquals(3, gets)
    }

    @Test
    fun anotherServiceIsNotServedFromTheCopy() {
        answer("Cache-Control" to "max-age=60")
        af.answer("GET", "service-access-information/ps2", MediaAfDouble.Response(200, """{"provisioningSessionId":"ps2"}""", headers = mapOf("Cache-Control" to "max-age=60")))
        session(1)
        session(2)
        assertEquals("ps1", acquire(1)!!.provisioningSessionId)
        assertEquals("ps2", acquire(2, ServiceListEntry("ps2", "service", null))!!.provisioningSessionId)
        assertEquals(2, gets)
    }

    @Test
    fun anotherMediaAfIsNotServedFromTheCopy() {
        MediaAfDouble().use { other ->
            other.answer("GET", saiPath, MediaAfDouble.Response(200, """{"provisioningSessionId":"other"}""", headers = mapOf("Cache-Control" to "max-age=60")))
            answer("Cache-Control" to "max-age=60")
            session(1)
            session(2, baseUrl = other.baseUrl)
            assertEquals("ps1", acquire(1)!!.provisioningSessionId)
            assertEquals("other", acquire(2)!!.provisioningSessionId)
            assertEquals(1, other.requests.size)
        }
    }

    // the Media AF authorises the answer with the access token: a copy that was fetched with another token is not used
    @Test
    fun anotherAccessTokenIsNotServedFromTheCopy() {
        answer("Cache-Control" to "max-age=60")
        session(1, "token-a")
        session(2, "token-a")
        session(3, "token-b")
        acquire(1)
        acquire(2)
        assertEquals(1, gets)
        acquire(3)
        assertEquals(listOf("Bearer token-a", "Bearer token-b"), af.requests.map { it.authorization })
    }

    @Test
    fun anUpdateThatTheMediaAfNotifiedWithdrawsTheCopy() {
        answer("Cache-Control" to "max-age=60")
        session(1)
        session(2)
        val first = acquire(1)!!
        controller.handleNotifiedServiceAccessInformation(1, first.copy(locationReporting = true))
        acquire(2)
        assertEquals(2, gets)
    }

    @Test
    fun theCopyIsGoneAfterAReset() {
        answer("Cache-Control" to "max-age=60")
        session(1)
        session(2)
        acquire(1)
        controller.reset()
        acquire(2)
        assertEquals(2, gets)
    }

    @Test
    fun freshnessIsTheSmallerOfMaxAgeAndExpires() {
        val expires = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.ENGLISH)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(now + 20_000))
        assertEquals(60L, controller.getFreshnessSeconds(Headers.of("Cache-Control", "max-age=60"), now))
        assertEquals(20L, controller.getFreshnessSeconds(Headers.of("Cache-Control", "max-age=60", "Expires", expires), now))
        assertEquals(20L, controller.getFreshnessSeconds(Headers.of("Expires", expires), now))
    }

    @Test
    fun withoutMaxAgeOrExpiresTheConfiguredRefreshPeriodApplies() =
        assertEquals(3600L, controller.getFreshnessSeconds(Headers.of("ETag", "\"v1\""), now))

    @Test
    fun noStoreAndNoCacheLeaveNoFreshness() {
        assertEquals(0L, controller.getFreshnessSeconds(Headers.of("Cache-Control", "no-store"), now))
        assertEquals(0L, controller.getFreshnessSeconds(Headers.of("Cache-Control", "no-cache"), now))
    }
}
