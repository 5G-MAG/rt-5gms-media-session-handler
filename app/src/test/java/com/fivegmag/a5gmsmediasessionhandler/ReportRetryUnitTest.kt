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
        assertEquals(30L, PendingReports.retryDelaySeconds("Wed, 21 Oct 2026 07:28:00 GMT", 30))
        assertEquals(30L, PendingReports.retryDelaySeconds("-5", 30))
        assertTrue(PendingReports.retryDelaySeconds("0", 30) == 0L)
    }
}
