package com.fivegmag.a5gmsmediasessionhandler.service

import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.fivegmag.a5gmscommonlibrary.helpers.ContentTypes
import com.fivegmag.a5gmscommonlibrary.helpers.PlayerStates
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerBundleKeys
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerEvents
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerMessageTypes
import com.fivegmag.a5gmscommonlibrary.helpers.Utils
import com.fivegmag.a5gmscommonlibrary.models.EntryPoint
import com.fivegmag.a5gmscommonlibrary.models.ServiceListEntry
import com.fivegmag.a5gmscommonlibrary.models.StreamingAccess
import com.fivegmag.a5gmscommonlibrary.session.PlaybackRequest
import com.fivegmag.a5gmsmediasessionhandler.controller.ConsumptionReportingController
import com.fivegmag.a5gmsmediasessionhandler.controller.QoeMetricsReportingController
import com.fivegmag.a5gmsmediasessionhandler.controller.NotificationChannelController
import com.fivegmag.a5gmsmediasessionhandler.controller.ServiceAccessInformationController
import com.fivegmag.a5gmsmediasessionhandler.controller.SessionController
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
import com.fivegmag.a5gmsmediasessionhandler.models.GeneralSessionStatus
import com.fivegmag.a5gmsmediasessionhandler.network.HeaderInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Properties

class IncomingMessageHandler(
    private val clientsSessionData: HashMap<Int, ClientSessionData>,
    private val outgoingMessageHandler: OutgoingMessageHandler
) {

    companion object {
        const val TAG = "5GMS-IncomingMessageHandler"
    }


    private val headerInterceptor = HeaderInterceptor()
    private val utils = Utils()
    private val okHttpClient = OkHttpClient()
        .newBuilder()
        .addInterceptor(headerInterceptor)
        .build()
    private val retrofitBuilder = Retrofit.Builder()
        .addConverterFactory(GsonConverterFactory.create())
        .client(okHttpClient)
    private lateinit var consumptionReportingController:
            ConsumptionReportingController
    private lateinit var qoeMetricsReportingController:
            QoeMetricsReportingController
    private lateinit var serviceAccessInformationController: ServiceAccessInformationController
    private lateinit var sessionController: SessionController
    private lateinit var notificationChannelController: NotificationChannelController
    private val incomingMessenger = Messenger(IncomingHandler())

    inner class IncomingHandler(
    ) : Handler() {

        override fun handleMessage(msg: Message) {
            try {
                when (msg.what) {
                    SessionHandlerMessageTypes.REGISTER_CLIENT -> handleRegisterClient(msg)

                    SessionHandlerMessageTypes.UNREGISTER_CLIENT -> handleUnregisterClient(msg)

                    SessionHandlerMessageTypes.STATUS_MESSAGE -> handleStatusMessage(msg)

                    SessionHandlerMessageTypes.START_PLAYBACK_BY_SERVICE_LIST_ENTRY_MESSAGE -> handleStartPlaybackByServiceListEntryMessage(
                        msg
                    )

                    SessionHandlerMessageTypes.SET_M5_ENDPOINT -> handleSetM5Endpoint(msg)

                    SessionHandlerMessageTypes.CREATE_MEDIA_DELIVERY_SESSION -> handleCreateMediaDeliverySession(
                        msg
                    )

                    SessionHandlerMessageTypes.DESTROY_MEDIA_DELIVERY_SESSION -> handleDestroyMediaDeliverySession(
                        msg
                    )

                    SessionHandlerMessageTypes.GET_SESSION_STATUS -> handleGetSessionStatus(msg)

                    SessionHandlerMessageTypes.GET_CONSUMPTION_REPORTING_STATUS -> consumptionReportingController.handleGetConsumptionReportingStatus(
                        msg
                    )

                    SessionHandlerMessageTypes.GET_METRICS_REPORTING_STATUS -> qoeMetricsReportingController.handleGetMetricsReportingStatus(
                        msg
                    )

                    SessionHandlerMessageTypes.CONSUMPTION_REPORT -> handleConsumptionReportMessage(
                        msg
                    )

                    SessionHandlerMessageTypes.REPORT_QOE_METRICS -> handleReportQoeMetricsMessage(
                        msg
                    )

                    else -> super.handleMessage(msg)
                }
            } catch (e: Exception) {
                Log.e(MediaSessionHandlerMessengerService.TAG, e.message.toString())
            }
        }
    }

    private fun handleRegisterClient(msg: Message) {
        sessionController.registerClient(msg)
    }

    private fun handleUnregisterClient(msg: Message) {
        val clientId = msg.sendingUid
        resetClientSession(clientId)
        sessionController.unregisterClient(clientId)
    }

    private fun resetClientSession(clientId: Int) {
        sessionController.resetClientSession(clientId)
        serviceAccessInformationController.resetClientSession(clientId)
        consumptionReportingController.resetClientSession(clientId)
        qoeMetricsReportingController.resetClientSession(clientId)
        notificationChannelController.disconnect(clientId)
    }

    fun reset() {
        notificationChannelController.reset()
        sessionController.reset()
        serviceAccessInformationController.reset()
        consumptionReportingController.reset()
        qoeMetricsReportingController.reset()
    }

    private fun handleStatusMessage(msg: Message) {
        val clientId = msg.sendingUid
        val bundle: Bundle = msg.data as Bundle

        when (bundle.getString("playbackState", "")) {
            PlayerStates.ENDED -> {
                handlePlaybackStateEnded(clientId)
            }

            PlayerStates.READY -> {
                handlePlaybackStateReady(clientId)
            }
        }
    }

    private fun handlePlaybackStateEnded(clientId: Int) {
        consumptionReportingController.stopReportingTimer(
            clientId
        )
        qoeMetricsReportingController.stopReportingTimer(clientId)
        serviceAccessInformationController.stopUpdateTimer(clientId)
    }

    private fun handlePlaybackStateReady(clientId: Int) {
        if (clientsSessionData[clientId] != null && clientsSessionData[clientId]?.initializedSession == false) {
            consumptionReportingController.initializeReportingTimer(
                clientId,
                0
            )
            qoeMetricsReportingController.initializeReportingTimer(
                clientId,
                0
            )
            clientsSessionData[clientId]?.initializedSession = true
        }
    }

    private fun handleStartPlaybackByServiceListEntryMessage(msg: Message) {
        val bundle: Bundle = msg.data
        val clientId = msg.sendingUid
        bundle.classLoader = ServiceListEntry::class.java.classLoader
        val serviceListEntry: ServiceListEntry = bundle.getParcelable("serviceListEntry")
            ?: throw java.lang.Exception("No valid ServiceListEntry found")
        val responseMessenger = msg.replyTo

        CoroutineScope(Dispatchers.Main).launch {
            startMediaDeliverySession(serviceListEntry, clientId, responseMessenger)
        }
    }

    /**
     * Fetch the Service Access Information of the Provisioning Session and, if there is a Media Entry Point, hand the playback request to the
     * Media Access Function (TS 26.510 V19.2.0 clause 11.2.2.1). The general status becomes ACTIVE and SESSION_HANDLING_ACTIVATED is sent if all
     * of it succeeds; otherwise the status becomes ERRORED and ERROR_SESSION_HANDLING is sent. Returns whether it succeeded.
     */
    private suspend fun startMediaDeliverySession(
        serviceListEntry: ServiceListEntry,
        clientId: Int,
        responseMessenger: Messenger?
    ): Boolean {
        resetClientSession(clientId)
        val mediaDeliverySessionId = clientsSessionData[clientId]?.mediaDeliverySessionId
        var serviceAccessInformation: com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation? = null
        var succeeded = false
        var mediaEntryPointUrl: String? = null

        try {
            serviceAccessInformation = withContext(Dispatchers.IO) {
                serviceAccessInformationController.updateServiceAccessInformation(
                    serviceListEntry,
                    clientId
                )
            }
            val finalEntryPoints = sessionController.getFinalEntryPoints(serviceListEntry, clientId)

            if (serviceAccessInformation != null && finalEntryPoints != null && finalEntryPoints.size > 0) {
                mediaEntryPointUrl = finalEntryPoints[0].locator
                val consumptionRequest =
                    consumptionReportingController.getConsumptionRequest(
                        serviceAccessInformation
                    )
                val qoeMetricsRequests =
                    qoeMetricsReportingController.getQoeMetricsRequests(serviceAccessInformation)
                val mediaStreamingSessionIdentifier = utils.generateUuidToHexBinary()
                val playbackRequest =
                    PlaybackRequest(
                        finalEntryPoints,
                        consumptionRequest,
                        qoeMetricsRequests,
                        mediaStreamingSessionIdentifier
                    )
                val responseBundle = Bundle()
                responseBundle.putParcelable("playbackRequest", playbackRequest)
                outgoingMessageHandler.sendMessage(
                    SessionHandlerMessageTypes.TRIGGER_PLAYBACK,
                    responseBundle,
                    responseMessenger
                )
                qoeMetricsReportingController.initializeReporting(
                    clientId,
                    serviceAccessInformation
                )
                // clause 5.3.2.3: connect to the notification channel the Media AF nominates
                serviceAccessInformation.notificationURL?.let {
                    notificationChannelController.connect(clientId, it, serviceListEntry.externalServiceId)
                }
                succeeded = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "The media delivery session cannot be started: " + e.message)
        }

        val status = GeneralSessionStatus.after(
            serviceListEntry.name.ifEmpty { serviceListEntry.externalServiceId },
            mediaEntryPointUrl,
            succeeded
        )
        clientsSessionData[clientId]?.generalStatus = status
        val notification = Bundle()
        notification.putString(
            SessionHandlerBundleKeys.MEDIA_DELIVERY_SESSION_IDENTIFIER,
            mediaDeliverySessionId
        )
        if (succeeded) {
            notification.putString(SessionHandlerBundleKeys.MEDIA_ENTRY_POINT_URL, mediaEntryPointUrl)
            outgoingMessageHandler.sendNotification(
                SessionHandlerEvents.Notification.SESSION_HANDLING_ACTIVATED,
                notification,
                responseMessenger
            )
        } else {
            outgoingMessageHandler.sendNotification(
                SessionHandlerEvents.Error.ERROR_SESSION_HANDLING,
                notification,
                responseMessenger
            )
        }
        return succeeded
    }

    /** createMediaDeliverySession() (TS 26.510 V19.2.0 table 11.2.2.1-1); the reply carries the media delivery session identifier */
    private fun handleCreateMediaDeliverySession(msg: Message) {
        val bundle: Bundle = msg.data
        val clientId = msg.sendingUid
        val responseMessenger = msg.replyTo
        val serviceId = bundle.getString(SessionHandlerBundleKeys.SERVICE_ID)
            ?: throw java.lang.Exception("createMediaDeliverySession: serviceId is mandatory")
        val contentId = bundle.getString(SessionHandlerBundleKeys.CONTENT_ID)
        val entryPoint = bundle.getString(SessionHandlerBundleKeys.ENTRY_POINT)
        // the method gives only the URL of the Media Entry Point: its media type is taken from the name of the resource
        val entryPoints = if (entryPoint != null) arrayListOf(EntryPoint(entryPoint, contentTypeOfUrl(entryPoint), null)) else null
        val serviceListEntry = ServiceListEntry(serviceId, contentId ?: "", entryPoints)

        CoroutineScope(Dispatchers.Main).launch {
            val succeeded = startMediaDeliverySession(serviceListEntry, clientId, responseMessenger)
            val reply = Bundle()
            reply.putString(
                SessionHandlerBundleKeys.MEDIA_DELIVERY_SESSION_IDENTIFIER,
                clientsSessionData[clientId]?.mediaDeliverySessionId
            )
            reply.putString(
                SessionHandlerBundleKeys.SESSION_HANDLING_STATE,
                clientsSessionData[clientId]?.generalStatus?.sessionHandlingState
            )
            reply.putBoolean("succeeded", succeeded)
            outgoingMessageHandler.sendMessage(
                SessionHandlerMessageTypes.CREATE_MEDIA_DELIVERY_SESSION_REPLY,
                reply,
                responseMessenger
            )
        }
    }

    private fun contentTypeOfUrl(url: String): String? {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".mpd") -> ContentTypes.DASH
            path.endsWith(".m3u8") -> ContentTypes.HLS
            else -> null
        }
    }

    /** destroyMediaDeliverySession() (table 11.2.2.2-1): release the session and send SESSION_HANDLING_TERMINATED */
    private fun handleDestroyMediaDeliverySession(msg: Message) {
        val clientId = msg.sendingUid
        val identifier = msg.data.getString(SessionHandlerBundleKeys.MEDIA_DELIVERY_SESSION_IDENTIFIER)
        val session = clientsSessionData[clientId]
        if (session == null || session.generalStatus == null || session.mediaDeliverySessionId != identifier) {
            Log.w(TAG, "destroyMediaDeliverySession: no media delivery session [$identifier]")
            return
        }
        val mediaEntryPointUrl = session.generalStatus?.mediaEntryPointUrl
        handlePlaybackStateEnded(clientId)
        resetClientSession(clientId)
        session.generalStatus = null
        val notification = Bundle()
        notification.putString(SessionHandlerBundleKeys.MEDIA_DELIVERY_SESSION_IDENTIFIER, identifier)
        notification.putString(SessionHandlerBundleKeys.MEDIA_ENTRY_POINT_URL, mediaEntryPointUrl)
        outgoingMessageHandler.sendNotification(
            SessionHandlerEvents.Notification.SESSION_HANDLING_TERMINATED,
            notification,
            msg.replyTo ?: session.messenger
        )
    }

    /** The general status of table 11.2.3-1 for one media delivery session */
    private fun handleGetSessionStatus(msg: Message) {
        val clientId = msg.sendingUid
        val identifier = msg.data.getString(SessionHandlerBundleKeys.MEDIA_DELIVERY_SESSION_IDENTIFIER)
        val session = clientsSessionData[clientId]
        val reply = Bundle()
        reply.putString(SessionHandlerBundleKeys.MEDIA_DELIVERY_SESSION_IDENTIFIER, identifier)
        val status = session?.generalStatus
        if (session != null && status != null && session.mediaDeliverySessionId == identifier) {
            reply.putString(SessionHandlerBundleKeys.CONTENT_IDENTIFIER, status.contentIdentifier)
            reply.putString(SessionHandlerBundleKeys.SESSION_HANDLING_STATE, status.sessionHandlingState)
        }
        outgoingMessageHandler.sendMessage(
            SessionHandlerMessageTypes.GET_SESSION_STATUS_REPLY,
            reply,
            msg.replyTo ?: session?.messenger
        )
    }

    /** STREAMING_ACCESS_UPDATED (table 11.2.3-2): the update of the Service Access Information changed the streaming access */
    private fun notifyStreamingAccessUpdated(clientId: Int, streamingAccess: StreamingAccess) {
        val session = clientsSessionData[clientId] ?: return
        if (session.generalStatus == null) return
        val notification = Bundle()
        notification.putString(
            SessionHandlerBundleKeys.MEDIA_DELIVERY_SESSION_IDENTIFIER,
            session.mediaDeliverySessionId
        )
        notification.putParcelable(SessionHandlerBundleKeys.STREAMING_ACCESS, streamingAccess)
        outgoingMessageHandler.sendNotification(
            SessionHandlerEvents.Notification.STREAMING_ACCESS_UPDATED,
            notification,
            session.messenger
        )
    }

    private fun handleSetM5Endpoint(msg: Message) {
        serviceAccessInformationController.setM5Endpoint(msg)
    }

    private fun handleConsumptionReportMessage(msg: Message) {
        consumptionReportingController.handleConsumptionReportMessage(msg)
    }

    private fun handleReportQoeMetricsMessage(msg: Message) {
        qoeMetricsReportingController.handleQoeMetricsReportMessage(msg)
    }

    fun getIncomingMessenger(): Messenger {
        return incomingMessenger
    }

    fun initialize(configurationProperties: Properties) {
        consumptionReportingController =
            ConsumptionReportingController(
                clientsSessionData,
                retrofitBuilder,
                outgoingMessageHandler
            )
        consumptionReportingController.initialize()
        qoeMetricsReportingController =
            QoeMetricsReportingController(
                clientsSessionData,
                retrofitBuilder,
                outgoingMessageHandler
            )
        qoeMetricsReportingController.initialize()
        sessionController =
            SessionController(
                clientsSessionData
            )
        serviceAccessInformationController =
            ServiceAccessInformationController(clientsSessionData, retrofitBuilder)
        serviceAccessInformationController.initialize(configurationProperties)
        serviceAccessInformationController.onStreamingAccessUpdated = ::notifyStreamingAccessUpdated
        notificationChannelController = NotificationChannelController(
            serviceAccessInformationController::handleNotifiedServiceAccessInformation
        )
    }
}