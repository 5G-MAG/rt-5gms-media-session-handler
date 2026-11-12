package com.fivegmag.a5gmsmediasessionhandler.service

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.fivegmag.a5gmscommonlibrary.helpers.ContentTypes
import com.fivegmag.a5gmscommonlibrary.helpers.MetricReportingSchemes
import com.fivegmag.a5gmscommonlibrary.helpers.PlayerStates
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerMessageTypes
import com.fivegmag.a5gmscommonlibrary.models.EntryPoint
import com.fivegmag.a5gmscommonlibrary.models.ClientConsumptionReportingConfiguration
import com.fivegmag.a5gmscommonlibrary.models.ClientMetricsReportingConfiguration
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmscommonlibrary.models.ServiceListEntry
import com.fivegmag.a5gmscommonlibrary.models.StreamingAccess
import com.fivegmag.a5gmscommonlibrary.session.PlaybackRequest
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
import com.fivegmag.a5gmsmediasessionhandler.controller.ServiceAccessInformationController
import com.fivegmag.a5gmsmediasessionhandler.network.IServiceAccessInformationApi
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.greenrobot.eventbus.EventBus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import java.io.IOException
import java.util.Properties
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class MediaDeliverySessionTest {
    private val clients = HashMap<Int, ClientSessionData>()
    private val playbackRequests = HashMap<Int, MutableList<PlaybackRequest>>()
    private val pendingRequests = mutableListOf<PendingRequest>()
    private val dispatcher = StandardTestDispatcher()
    private lateinit var handler: IncomingMessageHandler
    private lateinit var incoming: IncomingMessageHandler.IncomingHandler
    private val api = mockk<IServiceAccessInformationApi>()

    private data class PendingRequest(
        val provisioningSessionId: String,
        val call: Call<ServiceAccessInformation>,
        val callback: Callback<ServiceAccessInformation>
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val eventBus = EventBus.builder().logNoSubscriberMessages(false).build()
        mockkStatic(EventBus::class)
        every { EventBus.getDefault() } returns eventBus
        every { api.fetchServiceAccessInformation(any(), any(), any()) } answers {
            val provisioningSessionId = requireNotNull(firstArg<String?>())
            val call = mockk<Call<ServiceAccessInformation>>()
            every { call.cancel() } returns Unit
            every { call.enqueue(any()) } answers {
                pendingRequests.add(PendingRequest(provisioningSessionId, call, firstArg()))
            }
            call
        }
        handler = IncomingMessageHandler(clients, OutgoingMessageHandler())
        handler.initialize(Properties().apply {
            setProperty("defaultServiceAccessInformationTimerInterval", "3600")
        })
        incoming = handler.IncomingHandler()
        registerClient(1)
        registerClient(2)
    }

    @After
    fun tearDown() {
        handler.reset()
        drain()
        Dispatchers.resetMain()
        unmockkStatic(EventBus::class)
    }

    @Test
    fun playlistElementEndAndNextElementKeepTheSameId() {
        startPlayback(1, "first", newMediaDeliverySession = true)
        respond(0)
        val sessionId = clients.getValue(1).mediaDeliverySessionId
        assertEquals(sessionId, UUID.fromString(sessionId).toString())

        sendStatus(1, PlayerStates.ENDED)
        assertEquals(sessionId, clients.getValue(1).mediaDeliverySessionId)
        assertFalse(clients.getValue(1).initializedSession)

        startPlayback(1, "second", newMediaDeliverySession = false)
        respond(1)

        assertEquals(listOf(sessionId, sessionId), playbackRequests.getValue(1).map { it.mediaDeliverySessionId })
        assertNotEquals(playbackRequests.getValue(1)[0].playbackId, playbackRequests.getValue(1)[1].playbackId)
        assertEquals("second", clients.getValue(1).serviceAccessInformationSessionData.serviceAccessInformation?.provisioningSessionId)
    }

    @Test
    fun explicitlyStartingANewSessionAllocatesANewId() {
        startPlayback(1, "first", newMediaDeliverySession = true)
        respond(0)
        val oldId = clients.getValue(1).mediaDeliverySessionId

        startPlayback(1, "second", newMediaDeliverySession = true)
        respond(1)

        val newId = clients.getValue(1).mediaDeliverySessionId
        assertNotEquals(oldId, newId)
        assertEquals(newId, playbackRequests.getValue(1).last().mediaDeliverySessionId)
    }

    @Test
    fun missingSessionChoiceIsRejectedWithoutAllocatingAnId() {
        val context = clients.getValue(1).serviceAccessInformationSessionData
        ShadowLog.clear()

        send(1, SessionHandlerMessageTypes.START_PLAYBACK_BY_SERVICE_LIST_ENTRY_MESSAGE, Bundle().apply {
            putParcelable("serviceListEntry", ServiceListEntry("item", "Item", entryPoints("item")))
            putString("playbackId", "missing-choice")
        })
        drain()

        assertNull(clients.getValue(1).mediaDeliverySessionId)
        assertNull(clients.getValue(1).playbackId)
        assertSame(context, clients.getValue(1).serviceAccessInformationSessionData)
        assertTrue(pendingRequests.isEmpty())
        assertTrue(playbackRequests.getValue(1).isEmpty())
        assertTrue(ShadowLog.getLogsForTag(MediaSessionHandlerMessengerService.TAG).any {
            it.type == Log.ERROR && it.msg == "Missing or invalid newMediaDeliverySession Boolean"
        })
    }

    @Test
    fun invalidSessionChoiceCannotReplaceAnExistingPlayback() {
        val playbackId = startPlayback(1, "current", newMediaDeliverySession = true)
        val client = clients.getValue(1)
        val sessionId = client.mediaDeliverySessionId
        val context = client.serviceAccessInformationSessionData

        for (bundle in listOf(
            Bundle(),
            Bundle().apply { putString("newMediaDeliverySession", "true") },
            Bundle().apply { putInt("newMediaDeliverySession", 1) },
            Bundle().apply { putString("newMediaDeliverySession", null) }
        )) {
            ShadowLog.clear()
            bundle.putParcelable("serviceListEntry", ServiceListEntry("invalid", "Invalid", entryPoints("invalid")))
            bundle.putString("playbackId", "invalid-choice")
            send(1, SessionHandlerMessageTypes.START_PLAYBACK_BY_SERVICE_LIST_ENTRY_MESSAGE, bundle)
            drain()

            assertEquals(sessionId, client.mediaDeliverySessionId)
            assertEquals(playbackId, client.playbackId)
            assertSame(context, client.serviceAccessInformationSessionData)
            assertEquals(1, pendingRequests.size)
            assertTrue(ShadowLog.getLogsForTag(MediaSessionHandlerMessengerService.TAG).any {
                it.type == Log.ERROR && it.msg == "Missing or invalid newMediaDeliverySession Boolean"
            })
        }

        respond(0)
        assertEquals(playbackId, playbackRequests.getValue(1).single().playbackId)
    }

    @Test
    fun parallelClientsDoNotOverwriteOrEndEachOthersSessions() {
        startPlayback(1, "one", newMediaDeliverySession = true)
        startPlayback(2, "two", newMediaDeliverySession = true)
        respond(0)
        respond(1)
        val firstId = clients.getValue(1).mediaDeliverySessionId
        val secondId = clients.getValue(2).mediaDeliverySessionId
        assertNotEquals(firstId, secondId)
        assertEquals(firstId, playbackRequests.getValue(1).single().mediaDeliverySessionId)
        assertEquals(secondId, playbackRequests.getValue(2).single().mediaDeliverySessionId)

        startPlayback(1, "new-one", newMediaDeliverySession = true)
        respond(2)
        assertNotEquals(firstId, clients.getValue(1).mediaDeliverySessionId)
        assertEquals(secondId, clients.getValue(2).mediaDeliverySessionId)

        send(1, SessionHandlerMessageTypes.END_MEDIA_DELIVERY_SESSION)
        assertNull(clients.getValue(1).mediaDeliverySessionId)
        assertEquals(secondId, clients.getValue(2).mediaDeliverySessionId)
        assertEquals("two", clients.getValue(2).serviceAccessInformationSessionData.serviceAccessInformation?.provisioningSessionId)
    }

    @Test
    fun continuingWithoutASessionIsRejectedWithoutAllocatingAnId() {
        val context = clients.getValue(1).serviceAccessInformationSessionData
        ShadowLog.clear()

        startPlayback(1, "first", newMediaDeliverySession = false)

        assertNull(clients.getValue(1).mediaDeliverySessionId)
        assertNull(clients.getValue(1).playbackId)
        assertSame(context, clients.getValue(1).serviceAccessInformationSessionData)
        assertTrue(pendingRequests.isEmpty())
        assertTrue(playbackRequests.getValue(1).isEmpty())
        assertTrue(ShadowLog.getLogsForTag(MediaSessionHandlerMessengerService.TAG).any {
            it.type == Log.ERROR && it.msg == "No active media delivery session to continue for client: 1"
        })
    }

    @Test
    fun continuingAfterExplicitEndRequiresAnExplicitNewSession() {
        startPlayback(1, "first", newMediaDeliverySession = true)
        respond(0)
        val oldId = clients.getValue(1).mediaDeliverySessionId

        send(1, SessionHandlerMessageTypes.END_MEDIA_DELIVERY_SESSION)
        assertNull(clients.getValue(1).mediaDeliverySessionId)
        assertNull(clients.getValue(1).serviceAccessInformationSessionData.serviceAccessInformation)
        assertFalse(clients.getValue(1).initializedSession)
        ShadowLog.clear()
        startPlayback(1, "second", newMediaDeliverySession = false)

        assertNull(clients.getValue(1).mediaDeliverySessionId)
        assertNull(clients.getValue(1).playbackId)
        assertEquals(1, pendingRequests.size)
        assertEquals(1, playbackRequests.getValue(1).size)
        assertTrue(ShadowLog.getLogsForTag(MediaSessionHandlerMessengerService.TAG).any {
            it.type == Log.ERROR && it.msg == "No active media delivery session to continue for client: 1"
        })

        startPlayback(1, "second", newMediaDeliverySession = true)
        respond(1)

        assertNotEquals(oldId, clients.getValue(1).mediaDeliverySessionId)
    }

    @Test
    fun lateResponseFromPreviousPlaylistElementIsIgnoredEvenWithTheSameId() {
        startPlayback(1, "old", newMediaDeliverySession = true)
        val sessionId = clients.getValue(1).mediaDeliverySessionId
        startPlayback(1, "current", newMediaDeliverySession = false)
        respond(1)
        respond(0)

        assertEquals(sessionId, clients.getValue(1).mediaDeliverySessionId)
        assertEquals("current", clients.getValue(1).serviceAccessInformationSessionData.serviceAccessInformation?.provisioningSessionId)
        assertEquals(1, playbackRequests.getValue(1).size)
        assertTrue(playbackRequests.getValue(1).single().entryPoints.single().locator.endsWith("/current.mpd"))
    }

    @Test
    fun endOfPlaybackInvalidatesPendingResponsesWithoutEndingTheDeliverySession() {
        startPlayback(1, "old", newMediaDeliverySession = true)
        val sessionId = clients.getValue(1).mediaDeliverySessionId
        sendStatus(1, PlayerStates.ENDED)
        respond(0)

        assertEquals(sessionId, clients.getValue(1).mediaDeliverySessionId)
        assertTrue(playbackRequests.getValue(1).isEmpty())
        assertNull(clients.getValue(1).serviceAccessInformationSessionData.requestTimer)
    }

    @Test
    fun m5EndpointUpdateKeepsTheIdButInvalidatesPreviousResponses() {
        startPlayback(1, "old", newMediaDeliverySession = true)
        val sessionId = clients.getValue(1).mediaDeliverySessionId
        send(1, SessionHandlerMessageTypes.SET_M5_ENDPOINT, Bundle().apply {
            putString("m5BaseUrl", "https://example.test/m5/")
        })
        respond(0)

        assertEquals(sessionId, clients.getValue(1).mediaDeliverySessionId)
        assertTrue(playbackRequests.getValue(1).isEmpty())
        assertNull(clients.getValue(1).serviceAccessInformationSessionData.serviceAccessInformation)
    }

    @Test
    fun unregisterAndGlobalResetInvalidatePendingSessions() {
        startPlayback(1, "one", newMediaDeliverySession = true)
        startPlayback(2, "two", newMediaDeliverySession = true)
        send(1, SessionHandlerMessageTypes.UNREGISTER_CLIENT)
        handler.reset()
        respond(0)
        respond(1)

        assertFalse(clients.containsKey(1))
        assertNull(clients.getValue(2).mediaDeliverySessionId)
        assertTrue(playbackRequests.values.all { it.isEmpty() })
    }

    @Test
    fun oldEndedNeitherCancelsTheNextPlaylistStartNorStopsItsTimers() {
        val firstPlaybackId = startPlayback(1, "first", newMediaDeliverySession = true)
        respond(0)
        val sessionId = clients.getValue(1).mediaDeliverySessionId
        val nextPlaybackId = startPlayback(1, "next", newMediaDeliverySession = false)
        val nextContext = clients.getValue(1).serviceAccessInformationSessionData

        sendStatus(1, PlayerStates.ENDED, firstPlaybackId)
        assertSame(nextContext, clients.getValue(1).serviceAccessInformationSessionData)
        respond(1, reporting = true)
        assertEquals(nextPlaybackId, playbackRequests.getValue(1).last().playbackId)
        assertEquals(sessionId, playbackRequests.getValue(1).last().mediaDeliverySessionId)

        sendStatus(1, PlayerStates.READY, nextPlaybackId)
        val client = clients.getValue(1)
        val saiTimer = client.serviceAccessInformationSessionData.requestTimer
        val consumptionTimer = client.consumptionReportingSessionData.reportingTimer
        val qoeTimer = client.qoeMetricsReportingSessionData.getValue("qoe").reportingTimer
        assertNotNull(saiTimer)
        assertNotNull(consumptionTimer)
        assertNotNull(qoeTimer)

        sendStatus(1, PlayerStates.ENDED, firstPlaybackId)
        assertTrue(client.initializedSession)
        assertSame(saiTimer, client.serviceAccessInformationSessionData.requestTimer)
        assertSame(consumptionTimer, client.consumptionReportingSessionData.reportingTimer)
        assertSame(qoeTimer, client.qoeMetricsReportingSessionData.getValue("qoe").reportingTimer)

        sendStatus(1, PlayerStates.ENDED, nextPlaybackId)
        assertFalse(client.initializedSession)
        assertNull(client.serviceAccessInformationSessionData.requestTimer)
        assertNull(client.consumptionReportingSessionData.reportingTimer)
        assertNull(client.qoeMetricsReportingSessionData.getValue("qoe").reportingTimer)
        assertEquals(sessionId, client.mediaDeliverySessionId)
    }

    @Test
    fun oldOrUncorrelatedReadyDoesNotInitializeTheNextPlayback() {
        val oldId = startPlayback(1, "first", newMediaDeliverySession = true)
        respond(0)
        val currentId = startPlayback(1, "next", newMediaDeliverySession = false)

        sendStatus(1, PlayerStates.READY, oldId)
        sendStatus(1, PlayerStates.READY, null)
        assertFalse(clients.getValue(1).initializedSession)
        respond(1)
        sendStatus(1, PlayerStates.READY, currentId)
        assertTrue(clients.getValue(1).initializedSession)
    }

    @Test
    fun supersededStartDoesNotFetchSaiUsingTheNextItemsContext() {
        send(1, SessionHandlerMessageTypes.START_PLAYBACK_BY_SERVICE_LIST_ENTRY_MESSAGE, Bundle().apply {
            putParcelable("serviceListEntry", ServiceListEntry("old", "Old", entryPoints("old")))
            putString("playbackId", "old-playback")
            putBoolean("newMediaDeliverySession", true)
        })
        val sessionId = clients.getValue(1).mediaDeliverySessionId
        val nextId = startPlayback(1, "next", newMediaDeliverySession = false)

        assertEquals("next", pendingRequests.single().provisioningSessionId)
        respond(0)
        assertEquals(nextId, playbackRequests.getValue(1).single().playbackId)
        assertEquals(sessionId, clients.getValue(1).mediaDeliverySessionId)
    }

    @Test
    fun staticEntryPointsWithoutM5StartPlaybackInsteadOfSuspendingForever() {
        clients.getValue(1).serviceAccessInformationSessionData.api = null
        val playbackId = startPlayback(1, "static", newMediaDeliverySession = true)

        assertTrue(pendingRequests.isEmpty())
        assertEquals(playbackId, playbackRequests.getValue(1).single().playbackId)
        assertEquals(entryPoints("static"), playbackRequests.getValue(1).single().entryPoints)
    }

    @Test
    fun networkFailureAbortsTheRequestWithoutCrashingAndAllowsAnotherStart() {
        startPlayback(1, "offline", newMediaDeliverySession = true)
        val failed = pendingRequests.single()
        failed.callback.onFailure(failed.call, IOException("Network unavailable"))
        drain()
        assertTrue(playbackRequests.getValue(1).isEmpty())

        val nextId = startPlayback(1, "online", newMediaDeliverySession = true)
        respond(1)
        assertEquals(nextId, playbackRequests.getValue(1).single().playbackId)
    }

    @Test
    fun cancellationCancelsTheCallAndAcceptsItsLateFailureWithoutDoubleResume() {
        val controller = ServiceAccessInformationController(clients, Retrofit.Builder())
        val sessionId = UUID.randomUUID().toString()
        clients.getValue(1).mediaDeliverySessionId = sessionId
        val job = CoroutineScope(dispatcher).launch {
            controller.updateServiceAccessInformation(
                ServiceListEntry("cancelled", "Cancelled", entryPoints("cancelled")),
                1,
                sessionId
            )
        }
        drain()

        job.cancel()
        drain()
        val pending = pendingRequests.single()
        verify { pending.call.cancel() }
        pending.callback.onFailure(pending.call, IOException("Cancelled"))
        drain()
        assertTrue(job.isCancelled)
    }

    private fun registerClient(clientId: Int) {
        playbackRequests[clientId] = mutableListOf()
        val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what == SessionHandlerMessageTypes.TRIGGER_PLAYBACK) {
                    msg.data.classLoader = PlaybackRequest::class.java.classLoader
                    playbackRequests.getValue(clientId).add(requireNotNull(msg.data.getParcelable("playbackRequest")))
                }
            }
        })
        incoming.handleMessage(Message.obtain().apply {
            what = SessionHandlerMessageTypes.REGISTER_CLIENT
            sendingUid = clientId
            replyTo = messenger
        })
        clients.getValue(clientId).serviceAccessInformationSessionData.api = api
    }

    private fun startPlayback(clientId: Int, item: String, newMediaDeliverySession: Boolean): String {
        val playbackId = UUID.randomUUID().toString()
        send(clientId, SessionHandlerMessageTypes.START_PLAYBACK_BY_SERVICE_LIST_ENTRY_MESSAGE, Bundle().apply {
            putParcelable("serviceListEntry", ServiceListEntry(item, item, entryPoints(item)))
            putString("playbackId", playbackId)
            putBoolean("newMediaDeliverySession", newMediaDeliverySession)
        })
        drain()
        return playbackId
    }

    private fun respond(index: Int, reporting: Boolean = false) {
        val pending = pendingRequests[index]
        pending.callback.onResponse(
            pending.call,
            Response.success(ServiceAccessInformation(
                pending.provisioningSessionId,
                null,
                StreamingAccess(entryPoints(pending.provisioningSessionId)),
                if (reporting) ClientConsumptionReportingConfiguration(
                    arrayListOf("https://example.test/"),
                    locationReporting = false,
                    samplePercentage = 100f,
                    reportingInterval = 3600,
                    accessReporting = true
                ) else null,
                if (reporting) arrayListOf(ClientMetricsReportingConfiguration(
                    metricsReportingConfigurationId = "qoe",
                    serverAddresses = arrayListOf("https://example.test/"),
                    scheme = MetricReportingSchemes.THREE_GPP_DASH_METRIC_REPORTING,
                    dataNetworkName = null,
                    reportingInterval = 3600L,
                    samplePercentage = 100f,
                    urlFilters = null,
                    samplingPeriod = 1L,
                    metrics = arrayListOf()
                )) else null
            ))
        )
        drain()
    }

    private fun entryPoints(item: String) =
        arrayListOf(EntryPoint("https://example.test/$item.mpd", ContentTypes.DASH, null))

    private fun sendStatus(clientId: Int, state: String, playbackId: String? = clients[clientId]?.playbackId) {
        send(clientId, SessionHandlerMessageTypes.STATUS_MESSAGE, Bundle().apply {
            putString("playbackState", state)
            putString("playbackId", playbackId)
        })
    }

    private fun send(clientId: Int, type: Int, bundle: Bundle = Bundle()) {
        incoming.handleMessage(Message.obtain().apply {
            what = type
            sendingUid = clientId
            replyTo = clients[clientId]?.messenger
            data = bundle
        })
    }

    private fun drain() {
        dispatcher.scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        dispatcher.scheduler.runCurrent()
    }
}
