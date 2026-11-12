package com.fivegmag.a5gmsmediasessionhandler

import android.os.Bundle
import android.os.Messenger
import com.fivegmag.a5gmscommonlibrary.helpers.CmcdSchemes
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerBundleKeys
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerMessageTypes
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmscommonlibrary.qoeMetricsReporting.QoeMetricsRequest
import com.fivegmag.a5gmsmediasessionhandler.controller.QoeMetricsReportingController
import com.fivegmag.a5gmsmediasessionhandler.eventbus.ServiceAccessInformationUpdatedEvent
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
import com.fivegmag.a5gmsmediasessionhandler.models.GeneralSessionStatus
import com.fivegmag.a5gmsmediasessionhandler.service.OutgoingMessageHandler
import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** TS 26.510 V19.2.0 clause 5.3.5.1: a change of a client metrics reporting configuration during the session reaches the Media Stream Handler */
class CmcdConfigurationUpdateUnitTest {
    private val sessions = HashMap<Int, ClientSessionData>()
    private val outgoing: OutgoingMessageHandler = mockk(relaxed = true)
    private val retrofitBuilder = Retrofit.Builder().baseUrl("http://127.0.0.1:1/").addConverterFactory(GsonConverterFactory.create()).client(OkHttpClient())
    private val qoe = QoeMetricsReportingController(sessions, retrofitBuilder, outgoing)
    private val qm10 = "urn:3GPP:ns:PSS:DASH:QM10"
    // the requests that the controller put in the bundle of each CMCD_CONFIGURATION_UPDATE message (the Bundle of a plain unit test keeps nothing)
    private val sent = ArrayList<List<String>>()

    init {
        sessions[1] = ClientSessionData(Messenger(null as android.os.Handler?)).also {
            it.initializedSession = true
            it.generalStatus = GeneralSessionStatus.after("service", null, true)
        }
        mockkConstructor(Bundle::class)
        every { anyConstructed<Bundle>().putParcelableArrayList(SessionHandlerBundleKeys.CMCD_CONFIGURATIONS, any<ArrayList<QoeMetricsRequest>>()) } answers {
            sent.add(secondArg<ArrayList<QoeMetricsRequest>>().map { it.metricsReportingConfigurationId })
        }
    }

    private fun configuration(id: String, scheme: String, percentage: Int = 100, metrics: String = "[]") =
        "{\"metricsReportingConfigurationId\":\"$id\",\"serverAddresses\":[\"http://127.0.0.1:1/m5/\"],\"scheme\":\"$scheme\",\"samplingPeriod\":1000," +
            "\"reportingInterval\":0,\"metrics\":$metrics,\"samplePercentage\":$percentage}"

    private fun sai(vararg configurations: String): ServiceAccessInformation = Gson().fromJson(
        "{\"provisioningSessionId\":\"ps1\",\"clientMetricsReportingConfigurations\":[${configurations.joinToString(",")}]}",
        ServiceAccessInformation::class.java
    )

    private fun start(sai: ServiceAccessInformation) {
        sessions[1]!!.serviceAccessInformationSessionData.serviceAccessInformation = sai
        qoe.initializeReporting(1, sai, null)
        // what the playback request carries at the start
        qoe.getQoeMetricsRequests(1, sai, null)
        qoe.initializeReportingTimer(1, 0)
    }

    private fun change(before: ServiceAccessInformation, after: ServiceAccessInformation) {
        sessions[1]!!.serviceAccessInformationSessionData.serviceAccessInformation = after
        qoe.handleServiceAccessInformationChanges(ServiceAccessInformationUpdatedEvent(1, before, after))
    }

    private fun updates(count: Int) = verify(exactly = count) {
        outgoing.sendMessage(SessionHandlerMessageTypes.CMCD_CONFIGURATION_UPDATE, any(), any())
    }

    @After
    fun stop() {
        qoe.stopReportingTimer(1)
        unmockkConstructor(Bundle::class)
    }

    @Test
    fun aNewCmcdConfigurationIsSentToThePlayer() {
        val before = sai(configuration("c1", CmcdSchemes.REQUEST))
        start(before)
        change(before, sai(configuration("c1", CmcdSchemes.REQUEST), configuration("c2", CmcdSchemes.OBJECT)))
        updates(1)
        assertEquals(listOf(listOf("c1", "c2")), sent)
    }

    @Test
    fun aRemovedCmcdConfigurationIsSentToThePlayer() {
        val before = sai(configuration("c1", CmcdSchemes.REQUEST), configuration("c2", CmcdSchemes.OBJECT))
        start(before)
        val after = sai(configuration("c1", CmcdSchemes.REQUEST))
        change(before, after)
        updates(1)
        assertEquals(listOf(listOf("c1")), sent)
    }

    @Test
    fun aChangedMetricsListOfACmcdConfigurationIsSentToThePlayer() {
        val before = sai(configuration("c1", CmcdSchemes.REQUEST))
        start(before)
        change(before, sai(configuration("c1", CmcdSchemes.REQUEST, metrics = "[\"urn:3GPP:ns:PSS:DASH:QM10#CMCDRequest/br\"]")))
        updates(1)
        assertEquals(listOf(listOf("c1")), sent)
    }

    @Test
    fun anUnchangedServiceAccessInformationSendsNothing() {
        val before = sai(configuration("c1", CmcdSchemes.REQUEST))
        start(before)
        change(before, sai(configuration("c1", CmcdSchemes.REQUEST)))
        updates(0)
    }

    @Test
    fun aChangeOfAConfigurationOfAnotherSchemeSendsNothing() {
        val before = sai(configuration("c1", CmcdSchemes.REQUEST), configuration("q1", qm10))
        start(before)
        change(before, sai(configuration("c1", CmcdSchemes.REQUEST), configuration("q1", qm10, percentage = 50)))
        updates(0)
    }

    // the draw of samplePercentage is made again for a changed value and the player is told only the configurations that it selects
    @Test
    fun aConfigurationThatTheDrawDoesNotSelectIsLeftOut() {
        val before = sai(configuration("c1", CmcdSchemes.REQUEST))
        start(before)
        val after = sai(configuration("c1", CmcdSchemes.REQUEST, percentage = 0))
        change(before, after)
        updates(1)
        assertEquals(listOf(emptyList<String>()), sent)
    }

    // a configuration that is added gets one draw, which the player and the Media Session Handler share
    @Test
    fun anAddedConfigurationIsDrawnOnce() {
        val before = sai(configuration("c1", CmcdSchemes.REQUEST))
        start(before)
        val after = sai(configuration("c1", CmcdSchemes.REQUEST), configuration("c2", CmcdSchemes.OBJECT, percentage = 0))
        change(before, after)
        val entry = sessions[1]!!.qoeMetricsReportingSessionData["c2"]!!
        assertEquals(true, entry.drawn)
        assertEquals(false, entry.selected)
    }

    // an update before any configuration existed and one that removes all of them are also changes
    @Test
    fun theRemovalOfAllConfigurationsIsSentToThePlayer() {
        val before = sai(configuration("c1", CmcdSchemes.REQUEST))
        start(before)
        change(before, sai())
        updates(1)
        assertEquals(listOf(emptyList<String>()), sent)
    }
}
