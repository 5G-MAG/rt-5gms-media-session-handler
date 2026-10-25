package com.fivegmag.a5gmsmediasessionhandler.controller

import android.os.Bundle
import android.os.Message
import android.util.Log
import com.fivegmag.a5gmscommonlibrary.helpers.ContentTypes
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerBundleKeys
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerEvents
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerMessageTypes
import com.fivegmag.a5gmscommonlibrary.models.ClientMetricsReportingConfiguration
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmscommonlibrary.qoeMetricsReporting.QoeMetricsRequest
import com.fivegmag.a5gmscommonlibrary.qoeMetricsReporting.QoeMetricsResponse
import com.fivegmag.a5gmsmediasessionhandler.eventbus.ServiceAccessInformationUpdatedEvent
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
import com.fivegmag.a5gmsmediasessionhandler.models.bearer
import com.fivegmag.a5gmsmediasessionhandler.models.QoeMetricsReportingSessionDataEntry
import com.fivegmag.a5gmsmediasessionhandler.network.IMetricsReportingApi
import com.fivegmag.a5gmsmediasessionhandler.service.OutgoingMessageHandler
import okhttp3.MediaType
import okhttp3.RequestBody
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import java.util.Timer
import java.util.TimerTask
import kotlin.collections.ArrayList

class QoeMetricsReportingController(
    clientsSessionData: HashMap<Int, ClientSessionData>,
    private val retrofitBuilder: Retrofit.Builder,
    override val outgoingMessageHandler: OutgoingMessageHandler
) :
    ReportingController(clientsSessionData) {

    companion object {
        const val TAG = "5GMS-QoeMetricsReportingController"
    }

    private fun appliesTo(clientId: Int, configuration: ClientMetricsReportingConfiguration, mediaEntryPointUrl: String?): Boolean =
        matchesUrlFilters(configuration.urlFilters, mediaEntryPointUrl) &&
                SliceScope.applies(configuration.sliceScope, clientsSessionData[clientId]?.availableSlices)

    /** Gives a Retrofit builder whose connections use the network of a Data Network (set by the owner of the HTTP client) */
    var retrofitBuilderForNetwork: ((android.net.Network) -> Retrofit.Builder)? = null

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    private fun windowOf(configuration: ClientMetricsReportingConfiguration) =
        ReportingWindow(configuration.reportingStartOffset, configuration.reportingDuration)

    fun initializeReporting(
        clientId: Int,
        serviceAccessInformation: ServiceAccessInformation,
        mediaEntryPointUrl: String?
    ) {
        if (serviceAccessInformation.clientMetricsReportingConfigurations != null && serviceAccessInformation.clientMetricsReportingConfigurations!!.size > 0) {
            for (clientMetricsReportingConfiguration in serviceAccessInformation.clientMetricsReportingConfigurations!!.filter { appliesTo(clientId, it, mediaEntryPointUrl) })
                clientsSessionData[clientId]?.qoeMetricsReportingSessionData?.set(
                    clientMetricsReportingConfiguration.metricsReportingConfigurationId,
                    QoeMetricsReportingSessionDataEntry()
                )
        }
    }

    fun getQoeMetricsRequests(clientId: Int, serviceAccessInformation: ServiceAccessInformation?, mediaEntryPointUrl: String?): ArrayList<QoeMetricsRequest> {
        if (serviceAccessInformation?.clientMetricsReportingConfigurations != null) {
            return getQoeMetricsRequestsByClientMetricsReportingConfigurations(
                ArrayList(serviceAccessInformation.clientMetricsReportingConfigurations!!.filter { appliesTo(clientId, it, mediaEntryPointUrl) })
            )
        }

        return ArrayList()
    }

    private fun getQoeMetricsRequestsByClientMetricsReportingConfigurations(
        clientMetricsReportingConfigurations: ArrayList<ClientMetricsReportingConfiguration>
    ): ArrayList<QoeMetricsRequest> {
        val qoeMetricsRequests: ArrayList<QoeMetricsRequest> = ArrayList()

        for (clientMetricsReportingConfiguration in clientMetricsReportingConfigurations) {
            val qoeMetricsRequest = QoeMetricsRequest(
                clientMetricsReportingConfiguration.scheme,
                clientMetricsReportingConfiguration.samplingPeriod,
                clientMetricsReportingConfiguration.reportingInterval,
                clientMetricsReportingConfiguration.metrics,
                clientMetricsReportingConfiguration.metricsReportingConfigurationId,
            )
            qoeMetricsRequests.add(qoeMetricsRequest)
        }

        return qoeMetricsRequests
    }

    fun handleQoeMetricsReportMessage(msg: Message) {
        val bundle: Bundle = msg.data
        bundle.classLoader = QoeMetricsResponse::class.java.classLoader
        val playbackMetricsResponse: QoeMetricsResponse? =
            bundle.getParcelable("qoeMetricsResponse")
        val clientId = ClientKey.of(msg)

        if (clientsSessionData[clientId]?.serviceAccessInformationSessionData?.serviceAccessInformation?.clientMetricsReportingConfigurations == null
        ) {
            return
        }

        val clientSessionData = clientsSessionData[clientId] ?: return
        val metricsReportingConfigurationId =
            playbackMetricsResponse?.metricsReportingConfigurationId
        val clientMetricsReportingConfiguration =
            clientsSessionData[clientId]?.serviceAccessInformationSessionData?.serviceAccessInformation?.clientMetricsReportingConfigurations?.find { it.metricsReportingConfigurationId == metricsReportingConfigurationId }
                ?: return

        val qoeMetricsReportingSessionDataEntry =
            clientSessionData.qoeMetricsReportingSessionData[metricsReportingConfigurationId]
                ?: return

        // a client that was not selected by samplePercentage sends no report (TS 26.510 V19.2.0 clause 5.3.5.2)
        if (!qoeMetricsReportingSessionDataEntry.selected) {
            return
        }

        // a report that was not requested comes from the end of the media delivery session: it is sent while the window of
        // reportingStartOffset and reportingDuration is open (clause 5.3.5.2 and table 8.7.3.1-1)
        if (qoeMetricsReportingSessionDataEntry.reportRequested) {
            qoeMetricsReportingSessionDataEntry.reportRequested = false
        } else if (!windowOf(clientMetricsReportingConfiguration).contains(nowMs() - qoeMetricsReportingSessionDataEntry.sessionStartMs)) {
            Log.d(TAG, "Metrics report of [$metricsReportingConfigurationId] is outside the reporting window, not sent")
            return
        }

        // a restart after a report is not an end and a new start of metrics reporting; without a reportingInterval the report of the
        // end of the session is the only one and there is no timer
        val interval = clientMetricsReportingConfiguration.reportingInterval
        if (interval != null && interval > 0) {
            cancelSingleReportingTimer(qoeMetricsReportingSessionDataEntry)
            startSingleReportingTimer(clientId, null, clientMetricsReportingConfiguration)
        }

        val api = qoeMetricsReportingSessionDataEntry.api ?: return
        val metricsString = playbackMetricsResponse?.metricsString
        val mediaType = MediaType.parse(ContentTypes.QOE_REPORT_XML)
        val requestBody: RequestBody? = metricsString?.let { RequestBody.create(mediaType, it) }
        val provisioningSessionId =
            clientSessionData.serviceAccessInformationSessionData.serviceAccessInformation?.provisioningSessionId

        val call: Call<Void>? = if (metricsReportingConfigurationId?.startsWith(ManifestMetricsConfiguration.ID_PREFIX) == true) {
            // a manifest configuration: the report goes to the reporting server of the manifest, in the format that it asks for
            val gzip = clientSessionData.manifestGzip[metricsReportingConfigurationId] == true
            val server = qoeMetricsReportingSessionDataEntry.reportingSelectedServerAddress ?: return
            val body = if (gzip && metricsString != null) {
                val out = java.io.ByteArrayOutputStream()
                java.util.zip.GZIPOutputStream(out).use { it.write(metricsString.toByteArray(Charsets.UTF_8)) }
                RequestBody.create(mediaType, out.toByteArray())
            } else requestBody
            api.sendMetricsReportToServer(server, ContentTypes.QOE_REPORT_XML, if (gzip) "gzip" else null, body)
        } else {
            api.sendMetricsReport(
                provisioningSessionId,
                metricsReportingConfigurationId,
                bearer(clientSessionData.accessToken),
                requestBody
            )
        }

        call?.enqueue(object : retrofit2.Callback<Void?> {
            override fun onResponse(call: Call<Void?>, response: Response<Void?>) {
                handleResponseCode(response, TAG)
                if (response.isSuccessful) {
                    qoeMetricsReportingSessionDataEntry.scheme = clientMetricsReportingConfiguration.scheme
                    qoeMetricsReportingSessionDataEntry.lastReport = metricsString
                    notifyClient(clientId, SessionHandlerEvents.MetricsReporting.NEW_METRICS_REPORT)
                    clientSessionData.lastMetricsConfigurationId = metricsReportingConfigurationId
                } else {
                    notifyError(clientId, provisioningSessionId, metricsReportingConfigurationId, qoeMetricsReportingSessionDataEntry, response.code(), response.message())
                }
            }

            override fun onFailure(call: Call<Void?>, t: Throwable) {
                Log.d(TAG, "Error sending metrics report")
                notifyError(clientId, provisioningSessionId, metricsReportingConfigurationId, qoeMetricsReportingSessionDataEntry, 0, t.message)
            }
        })

    }

    /** ERROR_METRICS_REPORTING (table 11.6.2-3); the HTTP response code is 0 when there was no response */
    private fun notifyError(
        clientId: Int,
        provisioningSessionId: String?,
        metricsReportingConfigurationId: String?,
        entry: QoeMetricsReportingSessionDataEntry,
        httpResponseCode: Int,
        message: String?
    ) {
        notifyClient(clientId, SessionHandlerEvents.Error.ERROR_METRICS_REPORTING) {
            putString(SessionHandlerBundleKeys.PROVISIONING_SESSION_ID, provisioningSessionId)
            putString(SessionHandlerBundleKeys.SERVER_ADDRESS, entry.reportingSelectedServerAddress)
            putString(SessionHandlerBundleKeys.METRICS_REPORTING_CONFIGURATION_ID, metricsReportingConfigurationId)
            putInt(SessionHandlerBundleKeys.HTTP_RESPONSE_CODE, httpResponseCode)
            putString(SessionHandlerBundleKeys.ERROR_MESSAGE, message)
        }
    }

    /** The status of table 11.6.2-1: lastMetricsReport with its identifiers, scheme and the report */
    fun handleGetMetricsReportingStatus(msg: Message) {
        val clientId = ClientKey.of(msg)
        val session = clientsSessionData[clientId]
        val reply = Bundle()
        reply.putString(SessionHandlerBundleKeys.MEDIA_DELIVERY_SESSION_IDENTIFIER, session?.mediaDeliverySessionId)
        val configurationId = session?.lastMetricsConfigurationId
        val entry = configurationId?.let { session.qoeMetricsReportingSessionData[it] }
        if (entry?.lastReport != null) {
            reply.putString(
                SessionHandlerBundleKeys.PROVISIONING_SESSION_ID,
                session.serviceAccessInformationSessionData.serviceAccessInformation?.provisioningSessionId
            )
            reply.putString(SessionHandlerBundleKeys.METRICS_REPORTING_CONFIGURATION_ID, configurationId)
            reply.putString(SessionHandlerBundleKeys.SCHEME, entry.scheme)
            reply.putString(SessionHandlerBundleKeys.METRICS_REPORT, entry.lastReport)
        }
        outgoingMessageHandler.sendMessage(
            SessionHandlerMessageTypes.GET_METRICS_REPORTING_STATUS_REPLY,
            reply,
            msg.replyTo ?: session?.messenger
        )
    }

    override fun initializeReportingTimer(clientId: Int, delay: Long?) {
        val clientMetricsReportingConfigurations: ArrayList<ClientMetricsReportingConfiguration> =
            clientsSessionData[clientId]?.serviceAccessInformationSessionData?.serviceAccessInformation?.clientMetricsReportingConfigurations
                ?: return

        for (clientMetricsReportingConfiguration in clientMetricsReportingConfigurations) {
            // the draw of samplePercentage is made once per configuration and media delivery session (clause 5.3.5.2)
            val entry = getQoeMetricsReportingSessionDataEntry(
                clientId,
                clientMetricsReportingConfiguration.metricsReportingConfigurationId
            ) ?: continue
            entry.sessionStartMs = nowMs()
            entry.selected = shouldReportAccordingToSamplePercentage(clientMetricsReportingConfiguration.samplePercentage)
            entry.drawnForSamplePercentage = clientMetricsReportingConfiguration.samplePercentage
            if (entry.selected) activate(clientId, entry)
            initializeReportingTimerForConfiguration(
                clientId,
                delay,
                clientMetricsReportingConfiguration
            )
        }
    }

    private fun initializeReportingTimerForConfiguration(
        clientId: Int,
        delay: Long?,
        clientMetricsReportingConfiguration: ClientMetricsReportingConfiguration
    ) {
        setReportingEndpoint(clientId, clientMetricsReportingConfiguration)

        val qoeMetricsReportingSessionDataEntry =
            getQoeMetricsReportingSessionDataEntry(
                clientId,
                clientMetricsReportingConfiguration.metricsReportingConfigurationId
            ) ?: return

        if (qoeMetricsReportingSessionDataEntry.api == null) {
            return
        }

        val window = windowOf(clientMetricsReportingConfiguration)
        if (qoeMetricsReportingSessionDataEntry.selected && window.isSingleReport) {
            // reportingDuration zero: a single report at reportingStartOffset
            startSingleReportTimer(clientId, clientMetricsReportingConfiguration, window)
        } else if (qoeMetricsReportingSessionDataEntry.selected && shouldReport(clientMetricsReportingConfiguration)) {
            // a timer only with a reportingInterval; without one the report of the end of the session is the only one
            startSingleReportingTimer(clientId, delay, clientMetricsReportingConfiguration)
        }
    }

    private fun startSingleReportTimer(
        clientId: Int,
        clientMetricsReportingConfiguration: ClientMetricsReportingConfiguration,
        window: ReportingWindow
    ) {
        val entry = getQoeMetricsReportingSessionDataEntry(
            clientId,
            clientMetricsReportingConfiguration.metricsReportingConfigurationId
        ) ?: return
        if (entry.singleReportTimer != null) return
        val timer = Timer()
        timer.schedule(object : TimerTask() {
            override fun run() {
                requestMetricsForSchemeFromClient(clientId, clientMetricsReportingConfiguration)
            }
        }, window.startOffsetMs)
        entry.singleReportTimer = timer
    }

    private fun startSingleReportingTimer(
        clientId: Int,
        delay: Long? = null,
        clientMetricsReportingConfiguration: ClientMetricsReportingConfiguration
    ) {

        val qoeMetricsReportingSessionDataEntry = getQoeMetricsReportingSessionDataEntry(
            clientId,
            clientMetricsReportingConfiguration.metricsReportingConfigurationId
        ) ?: return


        // Endpoint not set
        if (qoeMetricsReportingSessionDataEntry.api == null) {
            setReportingEndpoint(clientId, clientMetricsReportingConfiguration)
        }

        // Do nothing if the timer is already running
        if (qoeMetricsReportingSessionDataEntry.reportingTimer != null) {
            return
        }

        val configuredInterval = clientMetricsReportingConfiguration.reportingInterval ?: return
        if (configuredInterval <= 0) return
        val timer = Timer()
        val reportingInterval =
            configuredInterval.times(1000)
        var finalDelay = delay
        if (finalDelay == null) {
            finalDelay = reportingInterval
        }

        timer.scheduleAtFixedRate(
            object : TimerTask() {
                override fun run() {
                    // periodic reports only while the window of reportingStartOffset and reportingDuration is open
                    val window = windowOf(clientMetricsReportingConfiguration)
                    val elapsed = nowMs() - qoeMetricsReportingSessionDataEntry.sessionStartMs
                    if (window.hasEnded(elapsed)) {
                        cancel()
                        return
                    }
                    if (!window.hasStarted(elapsed)) return
                    requestMetricsForSchemeFromClient(
                        clientId,
                        clientMetricsReportingConfiguration
                    )
                }
            },
            finalDelay,
            reportingInterval
        )

        qoeMetricsReportingSessionDataEntry.reportingTimer = timer
    }

    /** Metrics reporting is active from the draw that selects the client for a configuration, with or without a timer */
    private fun activate(clientId: Int, entry: QoeMetricsReportingSessionDataEntry) {
        if (entry.active) return
        val anyActive = clientsSessionData[clientId]?.qoeMetricsReportingSessionData?.values?.any { it.active } == true
        entry.active = true
        if (!anyActive) {
            notifyClient(clientId, SessionHandlerEvents.MetricsReporting.METRICS_REPORTING_ACTIVATED)
        }
    }

    private fun getQoeMetricsReportingSessionDataEntry(
        clientId: Int,
        metricsReportingConfigurationId: String
    ): QoeMetricsReportingSessionDataEntry? {
        return clientsSessionData[clientId]?.qoeMetricsReportingSessionData?.get(
            metricsReportingConfigurationId
        )
    }


    private fun setReportingEndpoint(
        clientId: Int,
        clientMetricsReportingConfiguration: ClientMetricsReportingConfiguration
    ) {
        val metricsReportingConfigurationId =
            clientMetricsReportingConfiguration.metricsReportingConfigurationId
        val qoeMetricsReportingSessionDataEntry =
            clientsSessionData[clientId]?.qoeMetricsReportingSessionData?.get(
                metricsReportingConfigurationId
            )
                ?: return

        qoeMetricsReportingSessionDataEntry.reportingSelectedServerAddress = null

        val serverAddress = selectReportingEndpoint(
            clientMetricsReportingConfiguration.serverAddresses
        )

        // dataNetworkName: the Data Network that the reports are sent over (TS 26.510 V19.2.0 table 8.7.3.1-1)
        val builder: Retrofit.Builder = when (val selection = DataNetwork.select(clientMetricsReportingConfiguration.dataNetworkName)) {
            is DataNetwork.Selection.Default -> retrofitBuilder
            else -> {
                val network = (selection as? DataNetwork.Selection.Capability)?.let {
                    clientsSessionData[clientId]?.dataNetworks?.get(clientMetricsReportingConfiguration.dataNetworkName!!.lowercase())
                }
                val forNetwork = network?.let { retrofitBuilderForNetwork?.invoke(it) }
                if (forNetwork == null) {
                    qoeMetricsReportingSessionDataEntry.api = null
                    notifyError(
                        clientId,
                        clientsSessionData[clientId]?.serviceAccessInformationSessionData?.serviceAccessInformation?.provisioningSessionId,
                        metricsReportingConfigurationId, qoeMetricsReportingSessionDataEntry, 0,
                        "the Data Network [" + clientMetricsReportingConfiguration.dataNetworkName + "] is not available to the application"
                    )
                    return
                }
                forNetwork
            }
        }

        if (serverAddress != null && metricsReportingConfigurationId.startsWith(ManifestMetricsConfiguration.ID_PREFIX)) {
            // the reporting server of the manifest is used as it is: Retrofit takes its origin as the base and the whole URL per call
            val uri = java.net.URI(clientMetricsReportingConfiguration.serverAddresses[0])
            val base = "${uri.scheme}://${uri.authority}/"
            qoeMetricsReportingSessionDataEntry.api =
                builder.baseUrl(base).build().create(IMetricsReportingApi::class.java)
            qoeMetricsReportingSessionDataEntry.reportingSelectedServerAddress =
                clientMetricsReportingConfiguration.serverAddresses[0]
            return
        }

        val retrofit = serverAddress?.let { builder.baseUrl(it).build() }
        if (retrofit != null) {
            qoeMetricsReportingSessionDataEntry.api =
                retrofit.create(IMetricsReportingApi::class.java)
            qoeMetricsReportingSessionDataEntry.reportingSelectedServerAddress =
                serverAddress
        }
    }

    private fun shouldReport(clientMetricsReportingConfiguration: ClientMetricsReportingConfiguration): Boolean {
        return clientMetricsReportingConfiguration.reportingInterval != null &&
                clientMetricsReportingConfiguration.reportingInterval!! > 0
    }

    private fun requestMetricsForSchemeFromClient(
        clientId: Int,
        clientMetricsReportingConfiguration: ClientMetricsReportingConfiguration
    ) {

        val qoeMetricsRequest =
            QoeMetricsRequest(
                clientMetricsReportingConfiguration.scheme,
                clientMetricsReportingConfiguration.samplingPeriod
            )
        if (clientMetricsReportingConfiguration.reportingInterval != null) {
            qoeMetricsRequest.reportingInterval =
                clientMetricsReportingConfiguration.reportingInterval!!
        }
        qoeMetricsRequest.metrics = clientMetricsReportingConfiguration.metrics
        qoeMetricsRequest.metricsReportingConfigurationId =
            clientMetricsReportingConfiguration.metricsReportingConfigurationId
        getQoeMetricsReportingSessionDataEntry(clientId, clientMetricsReportingConfiguration.metricsReportingConfigurationId)
            ?.reportRequested = true
        val bundle = Bundle()
        bundle.putParcelable("qoeMetricsRequest", qoeMetricsRequest)
        val messenger = clientsSessionData[clientId]?.messenger
        if (messenger != null) {
            outgoingMessageHandler.sendMessage(
                SessionHandlerMessageTypes.GET_QOE_METRICS_REPORT,
                bundle,
                messenger
            )
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    override fun handleServiceAccessInformationChanges(serviceAccessInformationUpdatedEvent: ServiceAccessInformationUpdatedEvent) {
        val previousClientMetricsReportingConfigurations =
            serviceAccessInformationUpdatedEvent.previousServiceAccessInformation?.clientMetricsReportingConfigurations
        val updatedClientMetricsReportingConfigurations =
            serviceAccessInformationUpdatedEvent.updatedServiceAccessInformation?.clientMetricsReportingConfigurations

        if (previousClientMetricsReportingConfigurations == null || updatedClientMetricsReportingConfigurations == null) {
            return
        }

        val clientId = serviceAccessInformationUpdatedEvent.clientId

        // No more reporting configurations. Reset the existing ones
        handleNoReportingConfigurations(clientId, updatedClientMetricsReportingConfigurations)

        // Check if any of the active metricsReportingConfigurationIds has been removed. If so cancel reporting and remove from list.
        handleConfigurationRemoval(clientId, updatedClientMetricsReportingConfigurations)

        // Handle updates to existing configurations
        handleConfigurationUpdate(
            clientId,
            updatedClientMetricsReportingConfigurations
        )

    }

    private fun handleNoReportingConfigurations(
        clientId: Int,
        clientMetricsReportingConfigurations: ArrayList<ClientMetricsReportingConfiguration>
    ) {
        if (clientMetricsReportingConfigurations.isEmpty()) {
            resetClientSession(clientId)
        }
    }

    private fun handleConfigurationRemoval(
        clientId: Int,
        clientMetricsReportingConfigurations: ArrayList<ClientMetricsReportingConfiguration>
    ) {
        val qoeMetricsReportingSessionData =
            clientsSessionData[clientId]?.qoeMetricsReportingSessionData
        for (key in qoeMetricsReportingSessionData?.keys!!) {
            val hasKey =
                clientMetricsReportingConfigurations.find { it.metricsReportingConfigurationId == key }
            if (hasKey == null) {
                val qoeMetricsReportingSessionDataEntry = qoeMetricsReportingSessionData[key]
                if (qoeMetricsReportingSessionDataEntry != null) {
                    resetSingleQoeMetricsReportingSession(qoeMetricsReportingSessionDataEntry)
                    qoeMetricsReportingSessionData.remove(key)
                }
            }
        }
    }

    private fun resetSingleQoeMetricsReportingSession(qoeMetricsReportingSessionDataEntry: QoeMetricsReportingSessionDataEntry) {
        qoeMetricsReportingSessionDataEntry.reportingTimer?.cancel()
        qoeMetricsReportingSessionDataEntry.singleReportTimer?.cancel()
        qoeMetricsReportingSessionDataEntry.singleReportTimer = null
    }

    private fun handleConfigurationUpdate(
        clientId: Int,
        updatedClientMetricsReportingConfigurations: ArrayList<ClientMetricsReportingConfiguration>
    ) {

        for (updatedConfiguration in updatedClientMetricsReportingConfigurations) {
            val qoeMetricsReportingSessionDataEntry =
                clientsSessionData[clientId]?.qoeMetricsReportingSessionData?.get(
                    updatedConfiguration.metricsReportingConfigurationId
                )

            // if the list of endpoints is empty we stop reporting. No need to check anything else
            if (updatedConfiguration.serverAddresses.isEmpty()) {
                stopReportingTimer(clientId)
                if (qoeMetricsReportingSessionDataEntry != null) {
                    qoeMetricsReportingSessionDataEntry.reportingSelectedServerAddress =
                        null
                    qoeMetricsReportingSessionDataEntry.api =
                        null
                }
                return
            }

            // a changed samplePercentage is a new draw for the rest of the session
            if (qoeMetricsReportingSessionDataEntry != null &&
                qoeMetricsReportingSessionDataEntry.drawnForSamplePercentage != updatedConfiguration.samplePercentage
            ) {
                qoeMetricsReportingSessionDataEntry.drawnForSamplePercentage = updatedConfiguration.samplePercentage
                qoeMetricsReportingSessionDataEntry.selected =
                    shouldReportAccordingToSamplePercentage(updatedConfiguration.samplePercentage)
                if (qoeMetricsReportingSessionDataEntry.selected) {
                    startSingleReportingTimer(clientId, 0, updatedConfiguration)
                } else {
                    stopSingleReportingTimer(qoeMetricsReportingSessionDataEntry)
                }
            }

        }

    }

    private fun stopSingleReportingTimer(qoeMetricsReportingSessionDataEntry: QoeMetricsReportingSessionDataEntry) {
        cancelSingleReportingTimer(qoeMetricsReportingSessionDataEntry)
    }

    /** Cancel the timer without telling the client; returns whether one was running */
    private fun cancelSingleReportingTimer(qoeMetricsReportingSessionDataEntry: QoeMetricsReportingSessionDataEntry): Boolean {
        qoeMetricsReportingSessionDataEntry.singleReportTimer?.cancel()
        qoeMetricsReportingSessionDataEntry.singleReportTimer = null
        val timer = qoeMetricsReportingSessionDataEntry.reportingTimer ?: return false
        timer.cancel()
        qoeMetricsReportingSessionDataEntry.reportingTimer = null
        return true
    }

    override fun resetClientSession(clientId: Int) {
        stopReportingTimer(clientId)
        clientsSessionData[clientId]?.qoeMetricsReportingSessionData?.clear()
    }

    override fun reset() {
        for (clientId in clientsSessionData.keys) {
            resetClientSession(clientId)
        }
    }

    override fun stopReportingTimer(clientId: Int) {
        val qoeMetricsReportingSessionData =
            clientsSessionData[clientId]?.qoeMetricsReportingSessionData ?: return

        var stopped = false
        for (qoeMetricsReportingSessionDataEntry in qoeMetricsReportingSessionData.values) {
            cancelSingleReportingTimer(qoeMetricsReportingSessionDataEntry)
            if (qoeMetricsReportingSessionDataEntry.active) {
                qoeMetricsReportingSessionDataEntry.active = false
                stopped = true
            }
        }
        if (stopped) {
            notifyClient(clientId, SessionHandlerEvents.MetricsReporting.METRICS_REPORTING_STOPPED)
        }
    }

}