package com.fivegmag.a5gmsmediasessionhandler

import android.os.Messenger
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerEvents
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerMessageTypes
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmsmediasessionhandler.controller.ConsumptionReportingController
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Configurations that are added to or removed from the Service Access Information during a media delivery session (TS 26.510 V19.2.0 clauses 5.3.5.1 and 5.3.6.1) */
class ConfigurationChangesUnitTest {
    private val sessions = HashMap<Int, ClientSessionData>()
    private val outgoing: OutgoingMessageHandler = mockk(relaxed = true)
    private val retrofitBuilder = Retrofit.Builder().baseUrl("http://127.0.0.1:1/").addConverterFactory(GsonConverterFactory.create()).client(OkHttpClient())
    private val consumption = ConsumptionReportingController(sessions, retrofitBuilder, outgoing)
    private val qoe = QoeMetricsReportingController(sessions, retrofitBuilder, outgoing)

    private fun session(running: Boolean) {
        sessions[1] = ClientSessionData(Messenger(null as android.os.Handler?)).also {
            it.initializedSession = running
            it.generalStatus = GeneralSessionStatus.after("service", null, true)
        }
    }

    private fun sai(consumption: Boolean, metricsIds: List<String>?, locationReporting: Boolean? = null): ServiceAccessInformation {
        val parts = arrayListOf("\"provisioningSessionId\":\"ps1\"")
        if (locationReporting != null) parts.add("\"locationReporting\":$locationReporting")
        if (consumption) parts.add("\"clientConsumptionReportingConfiguration\":{\"serverAddresses\":[\"http://127.0.0.1:1/m5/\"],\"samplePercentage\":100,\"accessReporting\":false}")
        if (metricsIds != null) {
            val configurations = metricsIds.joinToString(",") {
                "{\"metricsReportingConfigurationId\":\"$it\",\"serverAddresses\":[\"http://127.0.0.1:1/m5/\"],\"scheme\":\"urn:3GPP:ns:PSS:DASH:QM10\",\"samplingPeriod\":1000,\"metrics\":[],\"samplePercentage\":100}"
            }
            parts.add("\"clientMetricsReportingConfigurations\":[$configurations]")
        }
        return Gson().fromJson("{" + parts.joinToString(",") + "}", ServiceAccessInformation::class.java)
    }

    private fun changed(previous: ServiceAccessInformation?, updated: ServiceAccessInformation) {
        sessions[1]!!.serviceAccessInformationSessionData.serviceAccessInformation = updated
        val event = ServiceAccessInformationUpdatedEvent(1, previous, updated)
        consumption.handleServiceAccessInformationChanges(event)
        qoe.handleServiceAccessInformationChanges(event)
    }

    @After
    fun stop() {
        consumption.stopReportingTimer(1)
        qoe.stopReportingTimer(1)
    }

    @Test
    fun aConsumptionConfigurationThatAppearsInARunningSessionIsActivated() {
        session(running = true)
        changed(sai(false, null), sai(true, null))
        val data = sessions[1]!!.consumptionReportingSessionData
        assertNotNull(data.api)
        assertTrue(data.selected)
        assertTrue(data.active)
        verify { outgoing.sendMessage(SessionHandlerMessageTypes.UPDATE_PLAYBACK_CONSUMPTION_REPORTING_CONFIGURATION, any(), any()) }
        verify { outgoing.sendNotification(SessionHandlerEvents.ConsumptionReporting.CONSUMPTION_REPORTING_ACTIVATED, any(), any()) }
    }

    @Test
    fun aConsumptionConfigurationThatAppearsBeforeReportingStartsIsLeftToTheStart() {
        session(running = false)
        changed(sai(false, null), sai(true, null))
        val data = sessions[1]!!.consumptionReportingSessionData
        assertNull(data.api)
        assertFalse(data.active)
    }

    @Test
    fun aConsumptionConfigurationThatDisappearsIsDeactivated() {
        session(running = true)
        changed(sai(false, null), sai(true, null))
        changed(sai(true, null), sai(false, null))
        val data = sessions[1]!!.consumptionReportingSessionData
        assertNull(data.api)
        assertFalse(data.selected)
        assertFalse(data.active)
        verify { outgoing.sendNotification(SessionHandlerEvents.ConsumptionReporting.CONSUMPTION_REPORTING_STOPPED, any(), any()) }
    }

    @Test
    fun theFirstServiceAccessInformationOfTheSessionIsNotAChange() {
        session(running = true)
        changed(null, sai(true, listOf("c1")))
        assertNull(sessions[1]!!.consumptionReportingSessionData.api)
        assertTrue(sessions[1]!!.qoeMetricsReportingSessionData.isEmpty())
    }

    @Test
    fun aMetricsConfigurationThatAppearsInARunningSessionIsActivated() {
        session(running = true)
        changed(sai(false, null), sai(false, listOf("c1")))
        val entry = sessions[1]!!.qoeMetricsReportingSessionData["c1"]
        assertNotNull(entry)
        assertTrue(entry!!.selected)
        assertTrue(entry.active)
        assertNotNull(entry.api)
        verify { outgoing.sendNotification(SessionHandlerEvents.MetricsReporting.METRICS_REPORTING_ACTIVATED, any(), any()) }
    }

    @Test
    fun aMetricsConfigurationAddedToOthersIsActivatedAndTheOthersStay() {
        session(running = true)
        sessions[1]!!.qoeMetricsReportingSessionData["c1"] = com.fivegmag.a5gmsmediasessionhandler.models.QoeMetricsReportingSessionDataEntry(active = true)
        changed(sai(false, listOf("c1")), sai(false, listOf("c1", "c2")))
        assertEquals(setOf("c1", "c2"), sessions[1]!!.qoeMetricsReportingSessionData.keys)
        assertTrue(sessions[1]!!.qoeMetricsReportingSessionData["c2"]!!.active)
    }

    @Test
    fun aMetricsConfigurationThatAppearsBeforeReportingStartsGetsAnEntryOnly() {
        session(running = false)
        changed(sai(false, null), sai(false, listOf("c1")))
        val entry = sessions[1]!!.qoeMetricsReportingSessionData["c1"]
        assertNotNull(entry)
        assertFalse(entry!!.active)
    }

    @Test
    fun metricsConfigurationsThatDisappearAreStopped() {
        session(running = true)
        changed(sai(false, null), sai(false, listOf("c1")))
        changed(sai(false, listOf("c1")), sai(false, null))
        assertTrue(sessions[1]!!.qoeMetricsReportingSessionData.isEmpty())
        verify { outgoing.sendNotification(SessionHandlerEvents.MetricsReporting.METRICS_REPORTING_STOPPED, any(), any()) }
    }

    // the location data of consumption reports follows the locationReporting of the Service Access Information (table 9.2.3.1-1 of TS 26.510 V19.2.0)
    @Test
    fun locationIsRequestedForConsumptionWhenTheServiceAccessInformationSaysSo() {
        assertTrue(consumption.getConsumptionRequest(sai(true, null, true)).locationReporting == true)
        assertFalse(consumption.getConsumptionRequest(sai(true, null, false)).locationReporting == true)
        assertFalse(consumption.getConsumptionRequest(sai(true, null)).locationReporting == true)
    }

    @Test
    fun aChangeOfLocationReportingDuringTheSessionIsPassedToThePlayer() {
        session(running = true)
        changed(sai(false, null), sai(true, null, false))
        io.mockk.clearMocks(outgoing, answers = false)
        changed(sai(true, null, false), sai(true, null, true))
        verify(exactly = 1) { outgoing.sendMessage(SessionHandlerMessageTypes.UPDATE_PLAYBACK_CONSUMPTION_REPORTING_CONFIGURATION, any(), any()) }
        io.mockk.clearMocks(outgoing, answers = false)
        changed(sai(true, null, true), sai(true, null, true))
        verify(exactly = 0) { outgoing.sendMessage(SessionHandlerMessageTypes.UPDATE_PLAYBACK_CONSUMPTION_REPORTING_CONFIGURATION, any(), any()) }
    }

    // clause 5.3.6.1: "At the start of consumption of a media item in a media delivery session."
    @Test
    fun theStartOfConsumptionIsReportedWithoutAReportingInterval() {
        session(running = false)
        sessions[1]!!.serviceAccessInformationSessionData.serviceAccessInformation = sai(true, null)
        consumption.initializeReportingTimer(1, 0)
        verify(exactly = 1) { outgoing.sendMessage(SessionHandlerMessageTypes.GET_CONSUMPTION_REPORT, any(), any()) }
    }

    @Test
    fun aClientThatTheDrawDidNotSelectReportsNothingAtTheStart() {
        session(running = false)
        sessions[1]!!.serviceAccessInformationSessionData.serviceAccessInformation =
            Gson().fromJson(
                """{"provisioningSessionId":"ps1","clientConsumptionReportingConfiguration":{"serverAddresses":["http://127.0.0.1:1/m5/"],"samplePercentage":0,"accessReporting":false}}""",
                ServiceAccessInformation::class.java
            )
        consumption.initializeReportingTimer(1, 0)
        verify(exactly = 0) { outgoing.sendMessage(SessionHandlerMessageTypes.GET_CONSUMPTION_REPORT, any(), any()) }
    }

    private fun saiWith(vararg configurations: Pair<String, Pair<String, Int>>): ServiceAccessInformation {
        val list = configurations.joinToString(",") { (id, schemeAndPercentage) ->
            "{\"metricsReportingConfigurationId\":\"$id\",\"serverAddresses\":[\"http://127.0.0.1:1/m5/\"],\"scheme\":\"${schemeAndPercentage.first}\",\"samplingPeriod\":1000,\"metrics\":[],\"samplePercentage\":${schemeAndPercentage.second}}"
        }
        return Gson().fromJson("{\"provisioningSessionId\":\"ps1\",\"clientMetricsReportingConfigurations\":[$list]}", ServiceAccessInformation::class.java)
    }

    // clause 5.3.5.1: the draw of samplePercentage decides whether the procedure is activated; the player reports CMCD from the start
    @Test
    fun aCmcdConfigurationThatTheDrawDoesNotSelectIsNotHandedToThePlayer() {
        session(running = false)
        val cmcd = com.fivegmag.a5gmscommonlibrary.helpers.CmcdSchemes.REQUEST
        val requests = qoe.getQoeMetricsRequests(
            1, saiWith("drawn" to (cmcd to 100), "notDrawn" to (cmcd to 0), "qm10" to ("urn:3GPP:ns:PSS:DASH:QM10" to 0)), null
        )
        assertEquals(listOf("drawn", "qm10"), requests.map { it.metricsReportingConfigurationId })
    }

    @Test
    fun theDrawOfACmcdConfigurationIsKeptAtTheStart() {
        session(running = false)
        val cmcd = com.fivegmag.a5gmscommonlibrary.helpers.CmcdSchemes.REQUEST
        val sai = saiWith("drawn" to (cmcd to 100), "notDrawn" to (cmcd to 0))
        qoe.getQoeMetricsRequests(1, sai, null)
        sessions[1]!!.serviceAccessInformationSessionData.serviceAccessInformation = sai
        qoe.initializeReporting(1, sai, null)
        qoe.initializeReportingTimer(1, 0)
        assertTrue(sessions[1]!!.qoeMetricsReportingSessionData["drawn"]!!.selected)
        assertFalse(sessions[1]!!.qoeMetricsReportingSessionData["notDrawn"]!!.selected)
    }
}
