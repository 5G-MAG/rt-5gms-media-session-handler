package com.fivegmag.a5gmsmediasessionhandler

import android.os.Messenger
import com.fivegmag.a5gmscommonlibrary.helpers.ContentTypes
import com.fivegmag.a5gmscommonlibrary.helpers.MetricReportingSchemes
import com.fivegmag.a5gmscommonlibrary.models.ClientMetricsReportingConfiguration
import com.fivegmag.a5gmsmediasessionhandler.controller.QoeMetricsReportingController
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
import com.fivegmag.a5gmsmediasessionhandler.models.QoeMetricsReportingSessionDataEntry
import com.fivegmag.a5gmsmediasessionhandler.models.ServiceAccessInformationSessionData
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

/** The metrics scheme of the virtual reality metrics (TS 26.512 V19.3.0 clauses 4.9.2 and 11.4.3.2) is reported like QM10, in the MIME type of TS 26.247 10.6.1 */
class VrMetricsReportingUnitTest {
    private val af = MediaAfDouble()
    private val retrofit = Retrofit.Builder().baseUrl(af.baseUrl).addConverterFactory(GsonConverterFactory.create()).client(OkHttpClient()).build()
    private val sessions = HashMap<Int, ClientSessionData>()
    private val qoe = QoeMetricsReportingController(sessions, Retrofit.Builder(), mockk<OutgoingMessageHandler>(relaxed = true))

    init {
        sessions[1] = ClientSessionData(Messenger(null as android.os.Handler?))
        sessions[1]!!.serviceAccessInformationSessionData = ServiceAccessInformationSessionData(
            serviceAccessInformation = Gson().fromJson("""{"provisioningSessionId":"ps1"}""", com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation::class.java)
        )
        sessions[1]!!.accessToken = "t"
    }

    @After
    fun stop() {
        qoe.resetClientSession(1)
        af.close()
    }

    @Test
    fun aReportOfTheVrSchemeIsPostedAsAQoeReport() {
        val configuration = Gson().fromJson(
            """{"metricsReportingConfigurationId":"vr1","scheme":"${MetricReportingSchemes.THREE_GPP_VR_METRIC_REPORTING}","serverAddresses":[],"samplingPeriod":1000,"metrics":[]}""",
            ClientMetricsReportingConfiguration::class.java
        )
        sessions[1]!!.qoeMetricsReportingSessionData["vr1"] = QoeMetricsReportingSessionDataEntry(api = retrofit.create(IMetricsReportingApi::class.java))
        af.answer("POST", "provisioning-sessions/ps1/metrics-reporting/vr1", MediaAfDouble.Response(204))
        qoe.postMetricsReport(1, configuration, "<ReceptionReport/>")
        val end = System.currentTimeMillis() + 5000
        while (af.requests.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals(1, af.requests.size)
        assertTrue(af.requests[0].contentType!!.startsWith(ContentTypes.QOE_REPORT_XML))
        assertEquals("<ReceptionReport/>", af.requests[0].body)
    }
}
