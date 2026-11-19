package com.fivegmag.a5gmsmediasessionhandler

import android.os.Messenger
import com.fivegmag.a5gmscommonlibrary.models.ClientMetricsReportingConfiguration
import com.fivegmag.a5gmsmediasessionhandler.controller.ConsumptionReportingController
import com.fivegmag.a5gmsmediasessionhandler.controller.PendingReports
import com.fivegmag.a5gmsmediasessionhandler.controller.QoeMetricsReportingController
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
import com.fivegmag.a5gmsmediasessionhandler.models.QoeMetricsReportingSessionDataEntry
import com.fivegmag.a5gmsmediasessionhandler.models.ServiceAccessInformationSessionData
import com.fivegmag.a5gmsmediasessionhandler.network.IConsumptionReportingApi
import com.fivegmag.a5gmsmediasessionhandler.network.IMetricsReportingApi
import com.fivegmag.a5gmsmediasessionhandler.service.OutgoingMessageHandler
import com.google.gson.Gson
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** A report that the Media AF answers with 503 is stored and sent again (TS 26.510 V19.2.0 clauses 5.3.5.2 and 5.3.6.2) */
class ReportRetryUnitTest {
    private val af = MediaAfDouble()
    private val retrofit = Retrofit.Builder().baseUrl(af.baseUrl).addConverterFactory(GsonConverterFactory.create()).client(OkHttpClient()).build()
    private val sessions = HashMap<Int, ClientSessionData>()
    private val outgoing: OutgoingMessageHandler = mockk(relaxed = true)
    private val consumption = ConsumptionReportingController(sessions, Retrofit.Builder(), outgoing)
    private val qoe = QoeMetricsReportingController(sessions, Retrofit.Builder(), outgoing)

    init {
        sessions[1] = ClientSessionData(Messenger(null as android.os.Handler?))
        sessions[1]!!.serviceAccessInformationSessionData = ServiceAccessInformationSessionData(
            serviceAccessInformation = Gson().fromJson("""{"provisioningSessionId":"ps1"}""", com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation::class.java)
        )
        sessions[1]!!.accessToken = "t"
    }

    @After
    fun stop() {
        consumption.resetClientSession(1)
        qoe.resetClientSession(1)
        af.close()
    }

    private fun waitFor(count: Int) {
        val end = System.currentTimeMillis() + 5000
        while (af.requests.size < count && System.currentTimeMillis() < end) Thread.sleep(20)
        Thread.sleep(100)
    }

    @Test
    fun aConsumptionReportThatGot503IsSentAgainAfterRetryAfter() {
        sessions[1]!!.consumptionReportingSessionData.api = retrofit.create(IConsumptionReportingApi::class.java)
        af.answerOnce("POST", "provisioning-sessions/ps1/consumption-reporting/", MediaAfDouble.Response(503, retryAfter = "0"))
        af.answer("POST", "provisioning-sessions/ps1/consumption-reporting/", MediaAfDouble.Response(204))
        consumption.postConsumptionReport(1, """{"sessionId":"s"}""")
        waitFor(2)
        assertEquals(2, af.requests.size)
        assertEquals(af.requests[0].body, af.requests[1].body)
        assertEquals("""{"sessionId":"s"}""", sessions[1]!!.consumptionReportingSessionData.lastReport)
    }

    @Test
    fun aMetricsReportThatGot503IsSentAgainAfterRetryAfter() {
        val configuration = Gson().fromJson(
            """{"metricsReportingConfigurationId":"c1","serverAddresses":[],"samplingPeriod":1000,"metrics":[]}""",
            ClientMetricsReportingConfiguration::class.java
        )
        sessions[1]!!.qoeMetricsReportingSessionData["c1"] = QoeMetricsReportingSessionDataEntry(api = retrofit.create(IMetricsReportingApi::class.java))
        af.answerOnce("POST", "provisioning-sessions/ps1/metrics-reporting/c1", MediaAfDouble.Response(503, retryAfter = "0"))
        af.answer("POST", "provisioning-sessions/ps1/metrics-reporting/c1", MediaAfDouble.Response(204))
        qoe.postMetricsReport(1, configuration, "<QoEReport/>")
        waitFor(2)
        assertEquals(2, af.requests.size)
        assertEquals("<QoEReport/>", af.requests[1].body)
        assertEquals("<QoEReport/>", sessions[1]!!.qoeMetricsReportingSessionData["c1"]!!.lastReport)
    }

    @Test
    fun anOtherErrorIsNotRetried() {
        sessions[1]!!.consumptionReportingSessionData.api = retrofit.create(IConsumptionReportingApi::class.java)
        af.answer("POST", "provisioning-sessions/ps1/consumption-reporting/", MediaAfDouble.Response(400))
        consumption.postConsumptionReport(1, "{}")
        waitFor(2)
        assertEquals(1, af.requests.size)
    }

    @Test
    fun theOldestReportsAreDroppedBeyondTheLimit() {
        val pending = PendingReports(2)
        listOf("a", "b", "c").forEach { pending.store(it) }
        assertEquals(listOf("b", "c"), pending.takeAll())
        assertEquals(0, pending.size())
    }

    @Test
    fun theRetryDelayIsTheRetryAfterSecondsOrTheDefault() {
        assertEquals(7L, PendingReports.retryDelaySeconds("7", 30))
        assertEquals(30L, PendingReports.retryDelaySeconds(null, 30))
        assertEquals(30L, PendingReports.retryDelaySeconds("tomorrow", 30))
        assertEquals(30L, PendingReports.retryDelaySeconds("-5", 30))
        assertTrue(PendingReports.retryDelaySeconds("0", 30) == 0L)
    }

    @Test
    fun aRetryAfterThatIsAnHttpDateIsNotTakenForTheDefault() {
        assertEquals(0L, PendingReports.retryDelaySeconds("Wed, 21 Oct 2015 07:28:00 GMT", 30))
        assertTrue(PendingReports.retryDelaySeconds("Fri, 31 Dec 2100 23:59:59 GMT", 30) > 3600L)
    }

    @Test
    fun aRetryAfterThatIsAnHttpDateIsTheTimeUntilThatDate() {
        val now = java.time.Instant.parse("2026-10-21T07:27:00Z")
        // the three formats of RFC 9110 clause 5.6.7
        assertEquals(60L, PendingReports.retryDelaySeconds("Wed, 21 Oct 2026 07:28:00 GMT", 30, now))
        assertEquals(60L, PendingReports.retryDelaySeconds("Wednesday, 21-Oct-26 07:28:00 GMT", 30, now))
        assertEquals(60L, PendingReports.retryDelaySeconds("Wed Oct 21 07:28:00 2026", 30, now))
        // a part of a second is waited for
        assertEquals(1L, PendingReports.retryDelaySeconds("Wed, 21 Oct 2026 07:27:01 GMT", 30, java.time.Instant.parse("2026-10-21T07:27:00.200Z")))
        // a date that has passed means now
        assertEquals(0L, PendingReports.retryDelaySeconds("Wed, 21 Oct 2026 07:26:00 GMT", 30, now))
    }

    // RFC 9110 clause 5.6.7: the asctime format pads a day of one digit with a space
    @Test
    fun anAsctimeDateWithADayOfOneDigitIsAnHttpDate() {
        val now = java.time.Instant.parse("2026-11-06T08:48:37Z")
        assertEquals(60L, PendingReports.retryDelaySeconds("Fri Nov  6 08:49:37 2026", 30, now))
    }

    // RFC 9110 clause 5.6.7: a two-digit year that appears to be more than 50 years in the future is the most recent year of the past with those digits
    @Test
    fun aTwoDigitYearMoreThanFiftyYearsAheadIsInThePast() {
        val now = java.time.Instant.parse("2026-10-21T07:27:00Z")
        assertEquals(0L, PendingReports.retryDelaySeconds("Sunday, 06-Nov-94 08:49:37 GMT", 30, now))
        // 42 years ahead is in the future
        assertTrue(PendingReports.retryDelaySeconds("Sunday, 01-Jan-68 00:00:00 GMT", 30, now) > 1_000_000_000L)
    }

    // RFC 9110 clause 10.2.3: delay-seconds = 1*DIGIT
    @Test
    fun aDelayThatIsNotDigitsIsNotADelay() {
        assertEquals(30L, PendingReports.retryDelaySeconds("+5", 30))
        assertEquals(30L, PendingReports.retryDelaySeconds("\u0665", 30))
        assertEquals(30L, PendingReports.retryDelaySeconds("5.5", 30))
        assertEquals(120L, PendingReports.retryDelaySeconds("120", 30))
    }

    private val invalidToken = "Bearer error=\"invalid_token\""

    @Test
    fun aConsumptionReportThatGot401InvalidTokenIsSentAgainOnceWithANewToken() {
        sessions[1]!!.consumptionReportingSessionData.api = retrofit.create(IConsumptionReportingApi::class.java)
        consumption.hasClientCredentials = { true }
        consumption.renewAccessToken = { id -> sessions[id]!!.accessToken = "t2"; true }
        af.answerOnce("POST", "provisioning-sessions/ps1/consumption-reporting/", MediaAfDouble.Response(401, wwwAuthenticate = invalidToken))
        af.answer("POST", "provisioning-sessions/ps1/consumption-reporting/", MediaAfDouble.Response(204))
        consumption.postConsumptionReport(1, """{"sessionId":"s"}""")
        waitFor(2)
        assertEquals(2, af.requests.size)
        assertEquals("Bearer t", af.requests[0].authorization)
        assertEquals("Bearer t2", af.requests[1].authorization)
    }

    @Test
    fun aConsumptionReportIsNotSentAgainMoreThanOnceAfterANewToken() {
        sessions[1]!!.consumptionReportingSessionData.api = retrofit.create(IConsumptionReportingApi::class.java)
        consumption.hasClientCredentials = { true }
        consumption.renewAccessToken = { true }
        af.answer("POST", "provisioning-sessions/ps1/consumption-reporting/", MediaAfDouble.Response(401, wwwAuthenticate = invalidToken))
        consumption.postConsumptionReport(1, """{"sessionId":"s"}""")
        waitFor(3)
        assertEquals(2, af.requests.size)
    }

    @Test
    fun aConsumptionReportThatGot401IsNotSentAgainWithoutClientCredentials() {
        sessions[1]!!.consumptionReportingSessionData.api = retrofit.create(IConsumptionReportingApi::class.java)
        consumption.hasClientCredentials = { false }
        consumption.renewAccessToken = { true }
        af.answer("POST", "provisioning-sessions/ps1/consumption-reporting/", MediaAfDouble.Response(401, wwwAuthenticate = invalidToken))
        consumption.postConsumptionReport(1, """{"sessionId":"s"}""")
        waitFor(2)
        assertEquals(1, af.requests.size)
    }

    @Test
    fun aMetricsReportThatGot401InvalidTokenIsSentAgainOnceWithANewToken() {
        val configuration = Gson().fromJson(
            """{"metricsReportingConfigurationId":"c1","serverAddresses":[],"samplingPeriod":1000,"metrics":[]}""",
            ClientMetricsReportingConfiguration::class.java
        )
        sessions[1]!!.qoeMetricsReportingSessionData["c1"] = QoeMetricsReportingSessionDataEntry(api = retrofit.create(IMetricsReportingApi::class.java))
        qoe.hasClientCredentials = { true }
        qoe.renewAccessToken = { id -> sessions[id]!!.accessToken = "t2"; true }
        af.answerOnce("POST", "provisioning-sessions/ps1/metrics-reporting/c1", MediaAfDouble.Response(401, wwwAuthenticate = invalidToken))
        af.answer("POST", "provisioning-sessions/ps1/metrics-reporting/c1", MediaAfDouble.Response(204))
        qoe.postMetricsReport(1, configuration, "<QoEReport/>")
        waitFor(2)
        assertEquals(2, af.requests.size)
        assertEquals("Bearer t2", af.requests[1].authorization)
    }
}
