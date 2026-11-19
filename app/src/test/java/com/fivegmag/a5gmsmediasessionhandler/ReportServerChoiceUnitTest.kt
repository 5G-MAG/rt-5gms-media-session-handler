package com.fivegmag.a5gmsmediasessionhandler

import android.os.Messenger
import com.fivegmag.a5gmscommonlibrary.models.ClientMetricsReportingConfiguration
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmsmediasessionhandler.controller.ConsumptionReportingController
import com.fivegmag.a5gmsmediasessionhandler.controller.QoeMetricsReportingController
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
import com.fivegmag.a5gmsmediasessionhandler.models.QoeMetricsReportingSessionDataEntry
import com.fivegmag.a5gmsmediasessionhandler.models.ServiceAccessInformationSessionData
import com.fivegmag.a5gmsmediasessionhandler.network.IMetricsReportingApi
import com.fivegmag.a5gmsmediasessionhandler.service.OutgoingMessageHandler
import com.google.gson.Gson
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit

/** With several Media AF addresses, one is chosen at random for each report that is submitted (TS 26.510 V19.2.0 clauses 5.3.5.2 and 5.3.6.2) */
class ReportServerChoiceUnitTest {
    private val first = MediaAfDouble()
    private val second = MediaAfDouble()
    private val sessions = HashMap<Int, ClientSessionData>()
    private val outgoing: OutgoingMessageHandler = mockk(relaxed = true)
    private val consumption = ConsumptionReportingController(sessions, Retrofit.Builder(), outgoing)
    private val qoe = QoeMetricsReportingController(sessions, Retrofit.Builder(), outgoing)
    private val reports = 40

    init {
        sessions[1] = ClientSessionData(Messenger(null as android.os.Handler?))
        sessions[1]!!.serviceAccessInformationSessionData = ServiceAccessInformationSessionData(
            serviceAccessInformation = Gson().fromJson(
                """{"provisioningSessionId":"ps1","clientConsumptionReportingConfiguration":{"serverAddresses":["${first.baseUrl}","${second.baseUrl}"],"samplePercentage":100,"accessReporting":false}}""",
                ServiceAccessInformation::class.java
            )
        )
        sessions[1]!!.accessToken = "t"
        listOf("consumption-reporting/", "metrics-reporting/c1").forEach { path ->
            listOf(first, second).forEach { it.answer("POST", "provisioning-sessions/ps1/$path", MediaAfDouble.Response(204)) }
        }
    }

    @After
    fun stop() {
        consumption.resetClientSession(1)
        qoe.resetClientSession(1)
        first.close()
        second.close()
    }

    private fun waitForAll() {
        val end = System.currentTimeMillis() + 10000
        while (first.requests.size + second.requests.size < reports && System.currentTimeMillis() < end) Thread.sleep(20)
        Thread.sleep(100)
    }

    // the probability that 40 random choices all fall on one of two addresses is below one in 500 billion
    private fun bothWereUsed() {
        assertTrue("the first address got ${first.requests.size} reports, the second ${second.requests.size}", first.requests.isNotEmpty() && second.requests.isNotEmpty())
    }

    @Test
    fun eachConsumptionReportGoesToAnAddressChosenAtRandom() {
        repeat(reports) { consumption.postConsumptionReport(1, """{"sessionId":"s"}""") }
        waitForAll()
        bothWereUsed()
    }

    @Test
    fun eachMetricsReportGoesToAnAddressChosenAtRandom() {
        val configuration = Gson().fromJson(
            """{"metricsReportingConfigurationId":"c1","serverAddresses":["${first.baseUrl}","${second.baseUrl}"],"samplingPeriod":1000,"metrics":[]}""",
            ClientMetricsReportingConfiguration::class.java
        )
        sessions[1]!!.qoeMetricsReportingSessionData["c1"] = QoeMetricsReportingSessionDataEntry(
            api = Retrofit.Builder().baseUrl(first.baseUrl).build().create(IMetricsReportingApi::class.java)
        )
        repeat(reports) { qoe.postMetricsReport(1, configuration, "<QoEReport/>") }
        waitForAll()
        bothWereUsed()
    }
}
