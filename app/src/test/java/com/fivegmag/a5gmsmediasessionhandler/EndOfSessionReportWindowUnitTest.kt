package com.fivegmag.a5gmsmediasessionhandler

import android.os.Bundle
import android.os.Message
import android.os.Messenger
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmscommonlibrary.qoeMetricsReporting.QoeMetricsResponse
import com.fivegmag.a5gmsmediasessionhandler.controller.ClientKey
import com.fivegmag.a5gmsmediasessionhandler.controller.QoeMetricsReportingController
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
import com.fivegmag.a5gmsmediasessionhandler.models.QoeMetricsReportingSessionDataEntry
import com.fivegmag.a5gmsmediasessionhandler.models.ServiceAccessInformationSessionData
import com.fivegmag.a5gmsmediasessionhandler.network.IMetricsReportingApi
import com.fivegmag.a5gmsmediasessionhandler.service.OutgoingMessageHandler
import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * The report of the end of the media delivery session against the window of reportingStartOffset and reportingDuration. Implementation choice,
 * texts in conflict, see CR-022 (open item O-22): TS 26.510 V19.2.0 clause 5.3.5.1 lists "at the end of the media delivery session" as a trigger
 * of a report, and table 8.11.3.1 (the one of the property) says that the client stops reporting after reportingDuration. The text of the property
 * is the more specific: the report of the end of the session is not sent when the window has ended.
 */
class EndOfSessionReportWindowUnitTest {
    private val af = MediaAfDouble()
    private val retrofit = Retrofit.Builder().baseUrl(af.baseUrl).addConverterFactory(GsonConverterFactory.create()).client(OkHttpClient()).build()
    private val sessions = HashMap<Int, ClientSessionData>()
    private val qoe = QoeMetricsReportingController(sessions, Retrofit.Builder(), mockk<OutgoingMessageHandler>(relaxed = true))
    private val message: Message = mockk(relaxed = true)
    private val clientId = ClientKey.of(message)

    private fun endOfSessionReport(durationSeconds: Int) {
        sessions[clientId] = ClientSessionData(Messenger(null as android.os.Handler?))
        val session = sessions[clientId]!!
        session.accessToken = "t"
        session.serviceAccessInformationSessionData = ServiceAccessInformationSessionData(
            serviceAccessInformation = Gson().fromJson(
                """{"provisioningSessionId":"ps1","clientMetricsReportingConfigurations":[{"metricsReportingConfigurationId":"c1","serverAddresses":[],"scheme":"urn:3GPP:ns:PSS:DASH:QM10","samplingPeriod":1000,"metrics":[],"reportingStartOffset":0,"reportingDuration":$durationSeconds}]}""",
                ServiceAccessInformation::class.java
            )
        )
        // the session started ten seconds ago
        session.qoeMetricsReportingSessionData["c1"] = QoeMetricsReportingSessionDataEntry(
            api = retrofit.create(IMetricsReportingApi::class.java), selected = true, sessionStartMs = System.nanoTime() / 1_000_000L - 10_000L
        )
        af.answer("POST", "provisioning-sessions/ps1/metrics-reporting/c1", MediaAfDouble.Response(204))
        val bundle: Bundle = mockk(relaxed = true)
        every { bundle.getParcelable<QoeMetricsResponse>("qoeMetricsResponse") } returns QoeMetricsResponse("<ReceptionReport/>", "c1")
        every { message.data } returns bundle
        qoe.handleQoeMetricsReportMessage(message)
        val end = System.currentTimeMillis() + 1500
        while (af.requests.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(20)
    }

    @After
    fun stop() {
        qoe.resetClientSession(clientId)
        af.close()
    }

    @Test
    fun theReportOfTheEndOfTheSessionIsNotSentOnceTheReportingDurationHasEnded() {
        endOfSessionReport(durationSeconds = 5)
        assertEquals(0, af.requests.size)
    }

    @Test
    fun theReportOfTheEndOfTheSessionIsSentWhileTheReportingWindowIsOpen() {
        endOfSessionReport(durationSeconds = 3600)
        assertEquals(1, af.requests.size)
    }
}
