package com.fivegmag.a5gmsmediasessionhandler.service

import android.os.Bundle
import com.fivegmag.a5gmsmediasessionhandler.network.SessionIdentifierHeader
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
import com.fivegmag.a5gmsmediasessionhandler.controller.ClientDataReportingParser
import com.fivegmag.a5gmsmediasessionhandler.controller.ManifestMetricsConfiguration
import com.fivegmag.a5gmsmediasessionhandler.controller.AndroidFlowEndpoints
import com.fivegmag.a5gmsmediasessionhandler.controller.ConsumptionReportingController
import com.fivegmag.a5gmsmediasessionhandler.controller.DynamicPolicyController
import com.fivegmag.a5gmsmediasessionhandler.controller.ImplicitLaunch
import com.fivegmag.a5gmsmediasessionhandler.controller.InactivityWatch
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
import java.time.OffsetDateTime
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
    // the clients whose media delivery session was launched implicitly; the session ends when the client is inactive (clause 5.4.2.2)
    private val inactivityWatch = InactivityWatch(ImplicitLaunch.INACTIVITY_PERIOD_MS) { clientId ->
        CoroutineScope(Dispatchers.Main).launch { handleInactiveClient(clientId) }
    }
    private val incomingMessenger = Messenger(IncomingHandler())

    inner class IncomingHandler(
    ) : Handler() {

        override fun handleMessage(msg: Message) {
            try {
                inactivityWatch.touch(ClientKey.of(msg))
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

                    SessionHandlerMessageTypes.LAUNCH_SERVICE_URL -> handleLaunchServiceUrl(msg)

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
        inactivityWatch.resetClientSession(clientId)
    }

    fun reset() {
        inactivityWatch.reset()
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
        // the state tells whether the Media Stream Handler is consuming media, which an implicitly launched session needs to know (clause 5.4.2.2)
        clientsSessionData[clientId]?.playbackState = bundle.getString("playbackState", "")

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
        domainNames: List<String> = emptyList(),
        launch: ImplicitLaunch? = null
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
            // clause 4.8.3.3 items 1 and 2: the Media Entry Point of an implicit launch is the one that media-entry-point names or one that passes the filters
            val finalEntryPoints = sessionController.getFinalEntryPoints(serviceListEntry, clientId)?.let { launch?.select(it) ?: it }

            if (serviceAccessInformation != null && launch?.refuses(serviceAccessInformation, finalEntryPoints.orEmpty(), OffsetDateTime.now()) == true) {
                // clause 4.8.3.3 items 2 and 3: no Media Stream Handler is instantiated; the status for the application is in the launch
                Log.w(TAG, "The implicit launch finds nothing to start in the Service Access Information")
            } else if (serviceAccessInformation != null && (finalEntryPoints == null || finalEntryPoints.isEmpty())) {
                // clause 11.2.2.1: the session is ACTIVE when the Service Access Information was acquired and the Media Access Function was
                // initialised if there is a Media Entry Point; without one there is nothing to hand over
                serviceAccessInformation.notificationURL?.let {
                    notificationChannelController.connect(clientId, it, serviceListEntry.externalServiceId)
                }
                succeeded = true
            } else if (serviceAccessInformation != null && finalEntryPoints != null &&
                // clause 4.8.3.3 item 3: the Dynamic Policy of an implicit launch is instantiated before the Media Stream Handler
                (launch == null || instantiateLaunchPolicy(clientId, launch, serviceListEntry, finalEntryPoints[0]))
            ) {
                mediaEntryPointUrl = finalEntryPoints[0].locator
                // clause 4.9.2 of TS 26.512: a client metrics configuration in the manifest is used for the session
                val manifest = withContext(Dispatchers.IO) { fetchManifest(finalEntryPoints[0], mediaDeliverySessionId) }
                val manifestConfigurations = manifest?.let { ManifestMetricsConfiguration.parse(it.first) } ?: emptyList()
                // clause 10.5.1: the client data reporting for CMCD that the Media Player Entry declares goes to the Media Stream Handler
                val clientDataReporting = manifest?.let { ClientDataReportingParser.parse(it.first, it.second) }.orEmpty()
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
                        serviceListEntry.name.ifEmpty { null },
                        launch?.deliveryProtocols,
                        launch?.multipathMode,
                        launch?.clientDataReportingMode,
                        clientDataReporting.takeIf { it.isNotEmpty() }?.let { ArrayList(it) }
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
                qoeMetricsReportingController.startCmcdLocationUpdates(clientId, serviceAccessInformation, mediaEntryPointUrl)
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

    /** The manifest of a DASH Media Entry Point and its URL after any redirects (for its metrics configurations and client data reporting); null for another kind of entry point */
    private fun fetchManifest(entryPoint: EntryPoint, mediaDeliverySessionId: String?): Pair<String, String>? {
        val locator = entryPoint.locator
        val isDash = entryPoint.contentType == ContentTypes.DASH || locator.substringBefore('?').lowercase().endsWith(".mpd")
        if (!isDash || !(locator.startsWith("http://") || locator.startsWith("https://"))) return null
        return try {
            val builder = okhttp3.Request.Builder().url(locator)
            // the request goes to the Media AS at reference point M4: it cites the media delivery session like the requests of the Media Stream Handler (clause 10.1A of TS 26.512)
            mediaDeliverySessionId?.let { builder.header(SessionIdentifierHeader.NAME, SessionIdentifierHeader.value(it)) }
            val request = builder.build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else Pair(response.body()?.string() ?: "", response.request().url().toString())
            }
        } catch (e: Exception) {
            Log.w(TAG, "The manifest cannot be read for its metrics configuration: " + e.message)
            null
        }
    }

    /** createMediaDeliverySession() (TS 26.510 V19.2.0 table 11.2.2.1-1); the reply carries the media delivery session identifier */
    private fun handleCreateMediaDeliverySession(msg: Message) {
        val bundle: Bundle = msg.data
        val clientId = ClientKey.of(msg)
        val responseMessenger = msg.replyTo
        val serviceId = bundle.getString(SessionHandlerBundleKeys.SERVICE_ID)
        // clause 5.4.2.1: an external service identifier shall be provided; without one no media delivery session is created and the Media AF
        // is not asked. The text defines no error code for it: the reply says that the creation did not succeed, with no media delivery session identifier
        if (serviceId.isNullOrBlank()) {
            Log.w(TAG, "createMediaDeliverySession: serviceId is mandatory")
            val refusal = Bundle()
            refusal.putBoolean("succeeded", false)
            outgoingMessageHandler.sendMessage(
                SessionHandlerMessageTypes.CREATE_MEDIA_DELIVERY_SESSION_REPLY,
                refusal,
                responseMessenger
            )
            return
        }
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

    /**
     * Implicit service launch (TS 26.510 V19.2.0 clause 5.4.2.2, clause 6; TS 26.512 V19.3.0 clause 4.8.3) with the 3GPP Service URL of the message.
     * The media delivery session is the one of the sending client, as with createMediaDeliverySession(); the sender is also the Media Stream
     * Handler that the Media Session Handler invokes with the Media Entry Point. The reply carries only the HTTP status (clause 4.8.3.4); the
     * media delivery session identifier is not returned (clause 5.4.2.2). The entry by an intent filter (clause 4.8.3.1) is not part of this class.
     */
    private fun handleLaunchServiceUrl(msg: Message) {
        val clientId = ClientKey.of(msg)
        val responseMessenger = msg.replyTo
        val prepared = ImplicitLaunch.prepare(msg.data.getString(SessionHandlerBundleKeys.SERVICE_URL) ?: "")
        val launch = prepared.launch
        if (launch == null || clientsSessionData[clientId] == null) {
            if (launch != null) Log.w(TAG, "launchServiceUrl: the client has not registered")
            replyToLaunch(if (launch == null) prepared.status else ImplicitLaunch.STATUS_BAD_REQUEST, responseMessenger)
            return
        }
        // clause 4.8.3.2: the Media AF is the one that af-host-address (or the default host name) names
        serviceAccessInformationController.setM5Endpoint(clientId, launch.m5BaseUrl)
        val entryPoints = launch.mediaEntryPoint?.let { arrayListOf(EntryPoint(it, contentTypeOfUrl(it), null)) }
        val serviceListEntry = ServiceListEntry(launch.externalServiceId, launch.contentId ?: "", entryPoints)

        CoroutineScope(Dispatchers.Main).launch {
            val succeeded = startMediaDeliverySession(serviceListEntry, clientId, responseMessenger, launch.accessToken, emptyList(), launch)
            val session = clientsSessionData[clientId]
            // a launch that fails leaves nothing running; the status is the answer (clause 4.8.3.4)
            if (!succeeded && session != null) terminateMediaDeliverySession(clientId, session, session.mediaDeliverySessionId, null)
            if (succeeded) inactivityWatch.watch(clientId)
            replyToLaunch(if (succeeded) ImplicitLaunch.STATUS_OK else launch.failureStatus, responseMessenger)
        }
    }

    private fun replyToLaunch(status: Int, messenger: Messenger?) {
        outgoingMessageHandler.sendMessage(
            SessionHandlerMessageTypes.LAUNCH_SERVICE_URL_REPLY,
            mapOf<String, Any?>(SessionHandlerBundleKeys.HTTP_RESPONSE_CODE to status).toBundle(),
            messenger
        )
    }

    /**
     * Clause 4.8.3.3 item 3: the Dynamic Policy of the service-operation-point (with the estimated-volume, a Background Data Transfer policy) by the
     * procedures of clause 4.7.3, which DynamicPolicyService carries out. It follows the Media Entry Point, whose Media AS the policy applies to.
     * Returns whether the Media Stream Handler may be instantiated; no service-operation-point is nothing to do.
     */
    private suspend fun instantiateLaunchPolicy(clientId: Int, launch: ImplicitLaunch, serviceListEntry: ServiceListEntry, entryPoint: EntryPoint): Boolean {
        val reference = launch.serviceOperationPoint ?: return true
        val session = clientsSessionData[clientId] ?: return false
        // the service of the media delivery session is found by the general status, which holds the Media Entry Point until the session has started
        session.generalStatus = GeneralSessionStatus.after(serviceListEntry.name.ifEmpty { serviceListEntry.externalServiceId }, entryPoint.locator, true)
        val service = dynamicPolicyController.serviceOf(clientId, session.mediaDeliverySessionId) ?: return false
        val activated = withContext(Dispatchers.IO) { service.activate(reference, launch.estimatedVolume) } != null
        if (!activated) Log.w(TAG, "The Dynamic Policy of the implicit launch was not instantiated: ${service.lastFailure}")
        return activated
    }

    /** The client of an implicitly launched media delivery session was inactive for the period (clause 5.4.2.2): the session ends unless media is being consumed */
    private fun handleInactiveClient(clientId: Int) {
        val session = clientsSessionData[clientId] ?: return
        if (session.generalStatus == null) return
        if (ImplicitLaunch.isConsuming(session.playbackState)) {
            inactivityWatch.watch(clientId)
            return
        }
        Log.i(TAG, "Client $clientId was inactive: the implicitly launched media delivery session ends")
        terminateMediaDeliverySession(clientId, session, session.mediaDeliverySessionId, session.messenger)
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
        terminateMediaDeliverySession(clientId, session, identifier, msg.replyTo ?: session.messenger)
    }

    /** Release the media delivery session of the client; SESSION_HANDLING_TERMINATED goes to the messenger if there is one */
    private fun terminateMediaDeliverySession(clientId: Int, session: ClientSessionData, identifier: String?, notifyTo: Messenger?) {
        val mediaEntryPointUrl = session.generalStatus?.mediaEntryPointUrl
        handlePlaybackStateEnded(clientId)
        resetClientSession(clientId)
        session.generalStatus = null
        if (notifyTo == null) return
        val notification = Bundle()
        notification.putString(SessionHandlerBundleKeys.MEDIA_DELIVERY_SESSION_IDENTIFIER, identifier)
        notification.putString(SessionHandlerBundleKeys.MEDIA_ENTRY_POINT_URL, mediaEntryPointUrl)
        outgoingMessageHandler.sendNotification(
            SessionHandlerEvents.Notification.SESSION_HANDLING_TERMINATED,
            notification,
            notifyTo
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
        // a report that the Media AF refuses with 401 invalid_token is sent again once with a new access token (RFC 6750 clause 3.1)
        consumptionReportingController.renewAccessToken = { serviceAccessInformationController.renewAccessToken(it) }
        qoeMetricsReportingController.renewAccessToken = { serviceAccessInformationController.renewAccessToken(it) }
        consumptionReportingController.hasClientCredentials = { serviceAccessInformationController.hasClientCredentials() }
        qoeMetricsReportingController.hasClientCredentials = { serviceAccessInformationController.hasClientCredentials() }
        configurationProperties.getProperty("cmcdLocationCheckSeconds")?.toLongOrNull()?.let { if (it > 0) qoeMetricsReportingController.cmcdLocationCheckMs = it * 1000L }
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
        // an M5 call that the Media AF refuses with 401 invalid_token is made again once with a new access token (RFC 6750 clause 3.1)
        dynamicPolicyController.renewAccessToken = { serviceAccessInformationController.renewAccessToken(it) }
        networkAssistanceController.renewAccessToken = { serviceAccessInformationController.renewAccessToken(it) }
        dynamicPolicyController.hasClientCredentials = { serviceAccessInformationController.hasClientCredentials() }
        networkAssistanceController.hasClientCredentials = { serviceAccessInformationController.hasClientCredentials() }
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