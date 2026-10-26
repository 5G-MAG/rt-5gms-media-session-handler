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
import com.fivegmag.a5gmsmediasessionhandler.controller.ClientKey
import com.fivegmag.a5gmsmediasessionhandler.controller.CivicLookup
import com.fivegmag.a5gmsmediasessionhandler.controller.LocationFilter
import com.fivegmag.a5gmsmediasessionhandler.controller.ManifestMetricsConfiguration
import com.fivegmag.a5gmsmediasessionhandler.controller.AndroidFlowEndpoints
import com.fivegmag.a5gmsmediasessionhandler.controller.ConsumptionReportingController
import com.fivegmag.a5gmsmediasessionhandler.controller.DynamicPolicyController
import com.fivegmag.a5gmsmediasessionhandler.controller.QoeMetricsReportingController
import com.fivegmag.a5gmsmediasessionhandler.controller.NetworkAssistanceController
import com.fivegmag.a5gmsmediasessionhandler.controller.NotificationChannelController
import com.fivegmag.a5gmsmediasessionhandler.controller.ServiceAccessInformationController
import com.fivegmag.a5gmsmediasessionhandler.controller.SessionController
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
import com.fivegmag.a5gmsmediasessionhandler.models.ManifestParameters
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
    private lateinit var dynamicPolicyController: DynamicPolicyController
    private lateinit var networkAssistanceController: NetworkAssistanceController
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

                    SessionHandlerMessageTypes.MANIFEST_NOTIFICATION -> handleManifestNotification(msg)

                    SessionHandlerMessageTypes.METRIC_THRESHOLD_CROSSED -> qoeMetricsReportingController.handleMetricThresholdCrossed(msg)

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

                    SessionHandlerMessageTypes.GET_SERVICE_OPERATION_POINTS -> dynamicPolicyController.handleGetServiceOperationPoints(msg)

                    SessionHandlerMessageTypes.GET_BDT_INFO -> dynamicPolicyController.handleGetBdtInfo(msg)

                    SessionHandlerMessageTypes.ACTIVATE_POLICY -> dynamicPolicyController.handleActivatePolicy(msg)

                    SessionHandlerMessageTypes.DEACTIVATE_POLICY -> dynamicPolicyController.handleDeactivatePolicy(msg)

                    SessionHandlerMessageTypes.GET_DYNAMIC_POLICY_STATUS -> dynamicPolicyController.handleGetDynamicPolicyStatus(msg)

                    SessionHandlerMessageTypes.REQUEST_BIT_RATE_RECOMMENDATION -> networkAssistanceController.handleRequestBitRateRecommendation(msg)

                    SessionHandlerMessageTypes.REQUEST_DELIVERY_BOOST -> networkAssistanceController.handleRequestDeliveryBoost(msg)

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
        val clientId = ClientKey.of(msg)
        resetClientSession(clientId)
        sessionController.unregisterClient(clientId)
    }

    private fun resetClientSession(clientId: Int) {
        networkAssistanceController.resetClientSession(clientId)
        dynamicPolicyController.resetClientSession(clientId)
        sessionController.resetClientSession(clientId)
        serviceAccessInformationController.resetClientSession(clientId)
        consumptionReportingController.resetClientSession(clientId)
        qoeMetricsReportingController.resetClientSession(clientId)
        notificationChannelController.disconnect(clientId)
    }

    fun reset() {
        notificationChannelController.reset()
        networkAssistanceController.reset()
        dynamicPolicyController.reset()
        sessionController.reset()
        serviceAccessInformationController.reset()
        consumptionReportingController.reset()
        qoeMetricsReportingController.reset()
    }

    private fun handleStatusMessage(msg: Message) {
        val clientId = ClientKey.of(msg)
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

    // Step 10 of TS 26.501 V19.4.0 clause 5.2.3
    private fun handleManifestNotification(msg: Message) {
        val clientId = ClientKey.of(msg)
        val bundle: Bundle = msg.data as Bundle
        val parameters = ManifestParameters(
            bundle.getBoolean(SessionHandlerBundleKeys.MPD_DYNAMIC),
            if (bundle.containsKey(SessionHandlerBundleKeys.MPD_DURATION_MS)) bundle.getLong(SessionHandlerBundleKeys.MPD_DURATION_MS) else null,
            if (bundle.containsKey(SessionHandlerBundleKeys.MPD_MIN_BUFFER_TIME_MS)) bundle.getLong(SessionHandlerBundleKeys.MPD_MIN_BUFFER_TIME_MS) else null,
            bundle.getInt(SessionHandlerBundleKeys.MPD_PERIOD_COUNT)
        )
        clientsSessionData[clientId]?.manifestParameters = parameters
        Log.i(TAG, "Manifest notification of client $clientId: $parameters")
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
        val clientId = ClientKey.of(msg)
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
        responseMessenger: Messenger?,
        accessToken: String? = null,
        domainNames: List<String> = emptyList()
    ): Boolean {
        resetClientSession(clientId)
        clientsSessionData[clientId]?.accessToken = accessToken
        clientsSessionData[clientId]?.domainNames = domainNames
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

            // clause 11.2.2.1: the session is ACTIVE when the Service Access Information was acquired and the Media Access Function was
            // initialised if there is a Media Entry Point; without one there is nothing to hand over
            if (serviceAccessInformation != null && (finalEntryPoints == null || finalEntryPoints.isEmpty())) {
                serviceAccessInformation.notificationURL?.let {
                    notificationChannelController.connect(clientId, it, serviceListEntry.externalServiceId)
                }
                succeeded = true
            } else if (serviceAccessInformation != null && finalEntryPoints != null) {
                mediaEntryPointUrl = finalEntryPoints[0].locator
                // clause 4.9.2 of TS 26.512: a client metrics configuration in the manifest is used for the session
                val manifestConfigurations = withContext(Dispatchers.IO) { fetchManifestMetricsConfigurations(finalEntryPoints[0]) }
                if (manifestConfigurations.isNotEmpty()) {
                    val converted = ArrayList(manifestConfigurations.mapIndexed { index, c -> c.toClientConfiguration(index) })
                    serviceAccessInformation.clientMetricsReportingConfigurations = converted
                    clientsSessionData[clientId]?.manifestMetricsConfigurations = converted
                    manifestConfigurations.forEachIndexed { index, c ->
                        clientsSessionData[clientId]?.manifestGzip?.put(ManifestMetricsConfiguration.ID_PREFIX + index, c.gzip)
                    }
                }
                // sliceScope and dataNetworkName of the metrics configurations need what the platform tells about slices and data networks
                prepareSlicesAndDataNetworks(clientId, serviceAccessInformation)
                val consumptionRequest =
                    consumptionReportingController.getConsumptionRequest(
                        serviceAccessInformation
                    )
                val qoeMetricsRequests =
                    qoeMetricsReportingController.getQoeMetricsRequests(clientId, serviceAccessInformation, mediaEntryPointUrl)
                val mediaStreamingSessionIdentifier = utils.generateUuidToHexBinary()
                val playbackRequest =
                    PlaybackRequest(
                        finalEntryPoints,
                        consumptionRequest,
                        qoeMetricsRequests,
                        mediaStreamingSessionIdentifier,
                        mediaDeliverySessionId,
                        serviceListEntry.name.ifEmpty { null }
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
                    serviceAccessInformation,
                    mediaEntryPointUrl
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

    var context: android.content.Context? = null
    private var dataNetworkRequestTimeout = 5000

    private suspend fun prepareSlicesAndDataNetworks(clientId: Int, sai: com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation) {
        val ctx = context ?: return
        val configurations = sai.clientMetricsReportingConfigurations ?: return
        val session = clientsSessionData[clientId] ?: return
        if (configurations.any { !it.sliceScope.isNullOrEmpty() }) {
            session.availableSlices = com.fivegmag.a5gmsmediasessionhandler.controller.PlatformNetworks.slices(ctx)
        }
        for (name in configurations.mapNotNull { it.dataNetworkName }.map { it.lowercase() }.distinct()) {
            val selection = com.fivegmag.a5gmsmediasessionhandler.controller.DataNetwork.select(name)
            if (selection is com.fivegmag.a5gmsmediasessionhandler.controller.DataNetwork.Selection.Capability) {
                session.dataNetworks[name] = com.fivegmag.a5gmsmediasessionhandler.controller.PlatformNetworks.network(ctx, selection.capability, dataNetworkRequestTimeout)
            }
        }
    }

    /** The client metrics configurations that the manifest of a DASH Media Entry Point carries; none for another kind of entry point */
    private fun fetchManifestMetricsConfigurations(entryPoint: EntryPoint): List<ManifestMetricsConfiguration> {
        val locator = entryPoint.locator
        val isDash = entryPoint.contentType == ContentTypes.DASH || locator.substringBefore('?').lowercase().endsWith(".mpd")
        if (!isDash || !(locator.startsWith("http://") || locator.startsWith("https://"))) return emptyList()
        return try {
            val request = okhttp3.Request.Builder().url(locator).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) emptyList() else ManifestMetricsConfiguration.parse(response.body()?.string() ?: "")
            }
        } catch (e: Exception) {
            Log.w(TAG, "The manifest cannot be read for its metrics configuration: " + e.message)
            emptyList()
        }
    }

    /** createMediaDeliverySession() (TS 26.510 V19.2.0 table 11.2.2.1-1); the reply carries the media delivery session identifier */
    private fun handleCreateMediaDeliverySession(msg: Message) {
        val bundle: Bundle = msg.data
        val clientId = ClientKey.of(msg)
        val responseMessenger = msg.replyTo
        val serviceId = bundle.getString(SessionHandlerBundleKeys.SERVICE_ID)
            ?: throw java.lang.Exception("createMediaDeliverySession: serviceId is mandatory")
        val contentId = bundle.getString(SessionHandlerBundleKeys.CONTENT_ID)
        val entryPoint = bundle.getString(SessionHandlerBundleKeys.ENTRY_POINT)
        // the method gives only the URL of the Media Entry Point: its media type is taken from the name of the resource
        val entryPoints = if (entryPoint != null) arrayListOf(EntryPoint(entryPoint, contentTypeOfUrl(entryPoint), null)) else null
        val serviceListEntry = ServiceListEntry(serviceId, contentId ?: "", entryPoints)
        val accessToken = bundle.getString(SessionHandlerBundleKeys.ACCESS_TOKEN)
        val domainNames = bundle.getStringArrayList(SessionHandlerBundleKeys.DOMAIN_NAMES) ?: emptyList<String>()

        CoroutineScope(Dispatchers.Main).launch {
            val succeeded = startMediaDeliverySession(serviceListEntry, clientId, responseMessenger, accessToken, domainNames)
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
        val clientId = ClientKey.of(msg)
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
        val clientId = ClientKey.of(msg)
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
    /** ERROR_SESSION_HANDLING (table 11.2.3-3) for a media delivery session whose access the Media AF refuses */
    private fun notifySessionHandlingError(clientId: Int) {
        val session = clientsSessionData[clientId] ?: return
        val notification = Bundle()
        notification.putString(
            SessionHandlerBundleKeys.MEDIA_DELIVERY_SESSION_IDENTIFIER,
            session.mediaDeliverySessionId
        )
        outgoingMessageHandler.sendNotification(
            SessionHandlerEvents.Error.ERROR_SESSION_HANDLING,
            notification,
            session.messenger
        )
    }

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
        configurationProperties.getProperty("maxStoredReports")?.toIntOrNull()?.let { consumptionReportingController.maxStoredReports = it }
        configurationProperties.getProperty("reportRetrySeconds")?.toLongOrNull()?.let { consumptionReportingController.retryDefaultSeconds = it }
        qoeMetricsReportingController =
            QoeMetricsReportingController(
                clientsSessionData,
                retrofitBuilder,
                outgoingMessageHandler
            )
        qoeMetricsReportingController.initialize()
        configurationProperties.getProperty("maxStoredReports")?.toIntOrNull()?.let { qoeMetricsReportingController.maxStoredReports = it }
        configurationProperties.getProperty("reportRetrySeconds")?.toLongOrNull()?.let { qoeMetricsReportingController.retryDefaultSeconds = it }
        sessionController =
            SessionController(
                clientsSessionData
            )
        sessionController.supportedProfiles = (configurationProperties.getProperty("supportedProfiles") ?: "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        serviceAccessInformationController =
            ServiceAccessInformationController(clientsSessionData, retrofitBuilder)
        serviceAccessInformationController.initialize(configurationProperties)
        configurationProperties.getProperty("dataNetworkRequestTimeout")?.toIntOrNull()?.let { dataNetworkRequestTimeout = it }
        qoeMetricsReportingController.currentCell = { context?.let { LocationFilter.currentCell(it) } }
        val locationMaxAgeMs = (configurationProperties.getProperty("locationMaxAgeSeconds")?.toLongOrNull() ?: 300L) * 1000L
        qoeMetricsReportingController.currentPosition = { context?.let { LocationFilter.currentPosition(it, locationMaxAgeMs) } }
        val civicResolver = context?.let { CivicLookup.Resolver(it) }
        qoeMetricsReportingController.civicAddressOf = { position -> civicResolver?.addressOf(position) }
        qoeMetricsReportingController.retrofitBuilderForNetwork = { network ->
            Retrofit.Builder()
                .addConverterFactory(GsonConverterFactory.create())
                .client(okHttpClient.newBuilder().socketFactory(network.socketFactory).build())
        }
        dynamicPolicyController = DynamicPolicyController(clientsSessionData, retrofitBuilder, outgoingMessageHandler)
        dynamicPolicyController.initialize()
        dynamicPolicyController.endpoints = { context?.let { AndroidFlowEndpoints(it) } }
        networkAssistanceController = NetworkAssistanceController(clientsSessionData, retrofitBuilder, outgoingMessageHandler, dynamicPolicyController)
        networkAssistanceController.endpoints = dynamicPolicyController.endpoints
        serviceAccessInformationController.onStreamingAccessUpdated = ::notifyStreamingAccessUpdated
        serviceAccessInformationController.onAccessDenied = ::notifySessionHandlingError
        notificationChannelController = NotificationChannelController(
            serviceAccessInformationController::handleNotifiedServiceAccessInformation,
            dynamicPolicyController::handleNotification,
            { clientId, session, _ -> networkAssistanceController.handleNotification(clientId, session) }
        )
        notificationChannelController.initialize(configurationProperties)
        dynamicPolicyController.subscribeTopic = notificationChannelController::subscribe
        dynamicPolicyController.unsubscribeTopic = notificationChannelController::unsubscribe
        networkAssistanceController.subscribeTopic = notificationChannelController::subscribe
        networkAssistanceController.unsubscribeTopic = notificationChannelController::unsubscribe
    }
}