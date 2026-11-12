package com.fivegmag.a5gmsmediasessionhandler

import android.os.Messenger
import com.fivegmag.a5gmscommonlibrary.helpers.CmcdSchemes
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerMessageTypes
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmsmediasessionhandler.controller.QoeMetricsReportingController
import com.fivegmag.a5gmsmediasessionhandler.eventbus.ServiceAccessInformationUpdatedEvent
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
import com.fivegmag.a5gmsmediasessionhandler.models.GeneralSessionStatus
import com.fivegmag.a5gmsmediasessionhandler.service.OutgoingMessageHandler
import com.google.gson.Gson
import io.mockk.mockk
import io.mockk.verify
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** A CMCD scheme is reported in-band at M4d: no M5 reporting timer, no report requested from the player (TS 26.512 V19.3.0 clauses 7.8.1 and 10.5.1) */
class CmcdNoM5ReportingUnitTest {
    private val sessions = HashMap<Int, ClientSessionData>()
    private val outgoing: OutgoingMessageHandler = mockk(relaxed = true)
    private val retrofitBuilder = Retrofit.Builder().baseUrl("http://127.0.0.1:1/").addConverterFactory(GsonConverterFactory.create()).client(OkHttpClient())
    private val qoe = QoeMetricsReportingController(sessions, retrofitBuilder, outgoing)
    private val qm10 = "urn:3GPP:ns:PSS:DASH:QM10"

    private fun session() {
        sessions[1] = ClientSessionData(Messenger(null as android.os.Handler?)).also {
            it.initializedSession = true
            it.generalStatus = GeneralSessionStatus.after("service", null, true)
        }
    }

    // a reportingInterval, and reportingDuration 0 for the ids that start with "single": what arms an M5 timer
    private fun sai(vararg configurations: Pair<String, String>, servers: String = "\"http://127.0.0.1:1/m5/\"", percentage: Int = 100, dataNetwork: String? = null): ServiceAccessInformation {
        val list = configurations.joinToString(",") { (id, scheme) ->
            "{\"metricsReportingConfigurationId\":\"$id\",\"serverAddresses\":[$servers],\"scheme\":\"$scheme\",\"samplingPeriod\":1000," +
                "\"reportingInterval\":1,\"reportingDuration\":${if (id.startsWith("single")) 0 else 60},${dataNetwork?.let { "\"dataNetworkName\":\"$it\"," } ?: ""}\"metrics\":[],\"samplePercentage\":$percentage}"
        }
        return Gson().fromJson("{\"provisioningSessionId\":\"ps1\",\"clientMetricsReportingConfigurations\":[$list]}", ServiceAccessInformation::class.java)
    }

    private fun start(sai: ServiceAccessInformation) {
        sessions[1]!!.serviceAccessInformationSessionData.serviceAccessInformation = sai
        qoe.initializeReporting(1, sai, null)
        qoe.initializeReportingTimer(1, 0)
    }

    @After
    fun stop() {
        qoe.stopReportingTimer(1)
    }

    @Test
    fun aCmcdConfigurationArmsNoTimerAndHasNoEndpoint() {
        session()
        start(sai("c1" to CmcdSchemes.REQUEST, "single1" to CmcdSchemes.OBJECT))
        for (id in listOf("c1", "single1")) {
            val entry = sessions[1]!!.qoeMetricsReportingSessionData[id]!!
            assertNull(entry.reportingTimer)
            assertNull(entry.singleReportTimer)
            assertNull(entry.api)
            // the draw still decides whether the player reports CMCD, and the configuration counts as active
            assertTrue(entry.selected)
            assertTrue(entry.active)
        }
        verify(exactly = 0) { outgoing.sendMessage(SessionHandlerMessageTypes.GET_QOE_METRICS_REPORT, any(), any()) }
    }

    @Test
    fun aDataNetworkThatIsNotAvailableIsNoErrorForACmcdConfiguration() {
        session()
        start(sai("c1" to CmcdSchemes.STATUS, dataNetwork = "nowhere"))
        verify(exactly = 0) { outgoing.sendNotification(com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerEvents.Error.ERROR_METRICS_REPORTING, any(), any()) }
    }

    // the same configuration with the QM10 scheme does arm its timers: the test above is not vacuous
    @Test
    fun aQm10ConfigurationWithTheSameValuesArmsItsTimers() {
        session()
        start(sai("q1" to qm10, "single1" to qm10))
        assertNotNull(sessions[1]!!.qoeMetricsReportingSessionData["q1"]!!.reportingTimer)
        assertNotNull(sessions[1]!!.qoeMetricsReportingSessionData["single1"]!!.singleReportTimer)
    }

    @Test
    fun aConfigurationUpdateOfACmcdConfigurationArmsNoTimerAndStopsNothing() {
        session()
        val before = sai("c1" to CmcdSchemes.REQUEST, percentage = 100)
        start(before)
        // no endpoint and a new samplePercentage: for QM10 this stops reporting, resp. starts a timer
        val after = sai("c1" to CmcdSchemes.REQUEST, servers = "", percentage = 50)
        sessions[1]!!.serviceAccessInformationSessionData.serviceAccessInformation = after
        qoe.handleServiceAccessInformationChanges(ServiceAccessInformationUpdatedEvent(1, before, after))
        val entry = sessions[1]!!.qoeMetricsReportingSessionData["c1"]!!
        assertNull(entry.reportingTimer)
        assertNull(entry.singleReportTimer)
        assertTrue(entry.active)
        assertFalse(entry.api != null)
    }
}
