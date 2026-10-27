package com.fivegmag.a5gmsmediasessionhandler

import android.os.Messenger
import com.fivegmag.a5gmscommonlibrary.helpers.CmcdSchemes
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerMessageTypes
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmsmediasessionhandler.controller.QoeMetricsReportingController
import com.fivegmag.a5gmsmediasessionhandler.controller.UeCell
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
import com.fivegmag.a5gmsmediasessionhandler.service.OutgoingMessageHandler
import com.google.gson.Gson
import io.mockk.mockk
import io.mockk.verify
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

// TS 26.510 V19.2.0 table 8.7.3.1-1: the locationFilter also holds for CMCD, whose keys the Media Stream Handler adds to the requests
class CmcdLocationUpdatesUnitTest {
    private val sessions = HashMap<Int, ClientSessionData>()
    private val outgoing: OutgoingMessageHandler = mockk(relaxed = true)
    private val retrofitBuilder = Retrofit.Builder().baseUrl("http://127.0.0.1:1/").addConverterFactory(GsonConverterFactory.create()).client(OkHttpClient())
    private val qoe = QoeMetricsReportingController(sessions, retrofitBuilder, outgoing).also { it.cmcdLocationCheckMs = 30 }
    private var cell = UeCell("001", "01", 7, 1, null)

    init {
        sessions[1] = ClientSessionData(Messenger(null as android.os.Handler?))
        qoe.currentCell = { cell }
    }

    private fun sai(scheme: String, filter: Boolean): ServiceAccessInformation {
        val location = if (filter) ",\"locationFilter\":[{\"nwAreaInfo\":{\"ncgis\":[{\"plmnId\":{\"mcc\":\"001\",\"mnc\":\"01\"},\"nrCellId\":\"1\"}]}}]" else ""
        return Gson().fromJson(
            "{\"provisioningSessionId\":\"ps1\",\"clientMetricsReportingConfigurations\":[{\"metricsReportingConfigurationId\":\"c1\"," +
                "\"serverAddresses\":[\"http://127.0.0.1:1/m5/\"],\"scheme\":\"$scheme\",\"samplingPeriod\":1,\"metrics\":[],\"samplePercentage\":100$location}]}",
            ServiceAccessInformation::class.java
        )
    }

    @After
    fun stop() = qoe.stopCmcdLocationUpdates(1)

    private fun updates(count: Int) = verify(exactly = count, timeout = 2000) {
        outgoing.sendMessage(SessionHandlerMessageTypes.CMCD_LOCATION_UPDATE, any(), any())
    }

    @Test
    fun theStartIsReportedOnceAndNothingMoreWhileTheAnswerStaysTheSame() {
        qoe.startCmcdLocationUpdates(1, sai(CmcdSchemes.OBJECT, true), null)
        updates(1)
        Thread.sleep(300)
        updates(1)
    }

    @Test
    fun aChangeOfCellIsReported() {
        qoe.startCmcdLocationUpdates(1, sai(CmcdSchemes.OBJECT, true), null)
        updates(1)
        cell = UeCell("001", "01", 7, 2, null)
        updates(2)
    }

    @Test
    fun aConfigurationWithoutLocationFilterOrNotCmcdSendsNothing() {
        qoe.startCmcdLocationUpdates(1, sai(CmcdSchemes.OBJECT, false), null)
        qoe.startCmcdLocationUpdates(1, sai("urn:3GPP:ns:PSS:DASH:QM10", true), null)
        Thread.sleep(300)
        updates(0)
    }
}
