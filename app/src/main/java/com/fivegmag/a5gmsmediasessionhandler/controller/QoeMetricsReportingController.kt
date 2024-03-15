package com.fivegmag.a5gmsmediasessionhandler.controller

import android.os.Bundle
import android.os.Message
import android.util.Log
import com.fivegmag.a5gmscommonlibrary.helpers.ContentTypes
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerMessageTypes
import com.fivegmag.a5gmscommonlibrary.models.ClientMetricsReportingConfiguration
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmscommonlibrary.qoeMetricsReporting.QoeMetricsRequest
import com.fivegmag.a5gmscommonlibrary.qoeMetricsReporting.QoeMetricsResponse
import com.fivegmag.a5gmsmediasessionhandler.eventbus.ServiceAccessInformationUpdatedEvent
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
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
    private val outgoingMessageHandler: OutgoingMessageHandler
) :
    ReportingController(clientsSessionData) {

    companion object {
        const val TAG = "5GMS-QoeMetricsReportingController"
    }

    fun initializeReporting(
        clientId: Int,
        serviceAccessInformation: ServiceAccessInformation
    ) {
        if (serviceAccessInformation.clientMetricsReportingConfigurations != null && serviceAccessInformation.clientMetricsReportingConfigurations!!.size > 0) {
            for (clientMetricsReportingConfiguration in serviceAccessInformation.clientMetricsReportingConfigurations!!)
                clientsSessionData[clientId]?.qoeMetricsReportingSessionData?.set(
                    clientMetricsReportingConfiguration.metricsReportingConfigurationId,
                    QoeMetricsReportingSessionDataEntry()
                )
        }
    }

    fun getQoeMetricsRequests(serviceAccessInformation: ServiceAccessInformation?): ArrayList<QoeMetricsRequest> {
        if (serviceAccessInformation?.clientMetricsReportingConfigurations != null) {
            return getQoeMetricsRequestsByClientMetricsReportingConfigurations(
                serviceAccessInformation.clientMetricsReportingConfigurations!!
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
        val clientId = msg.sendingUid

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

        if (clientMetricsReportingConfiguration.samplePercentage!! <= 0) {
            return
        }

        val qoeMetricsReportingSessionDataEntry =
            clientSessionData.qoeMetricsReportingSessionData[metricsReportingConfigurationId]
                ?: return

        stopSingleReportingTimer(qoeMetricsReportingSessionDataEntry)

        startSingleReportingTimer(clientId, null, clientMetricsReportingConfiguration)

        val api = qoeMetricsReportingSessionDataEntry.api ?: return
        val metricsString = playbackMetricsResponse?.metricsString
        val mediaType = MediaType.parse(ContentTypes.XML)
        val requestBody: RequestBody? = metricsString?.let { RequestBody.create(mediaType, it) }
        val provisioningSessionId =
            clientSessionData.serviceAccessInformationSessionData.serviceAccessInformation?.provisioningSessionId

        val call: Call<Void>? = api.sendMetricsReport(
            provisioningSessionId,
            metricsReportingConfigurationId,
            requestBody
        )

        call?.enqueue(object : retrofit2.Callback<Void?> {
            override fun onResponse(call: Call<Void?>, response: Response<Void?>) {
                handleResponseCode(response, TAG)
            }

            override fun onFailure(call: Call<Void?>, t: Throwable) {
                Log.d(TAG, "Error sending metrics report")
            }
        })

    }

    override fun initializeReportingTimer(clientId: Int, delay: Long?) {
        val clientMetricsReportingConfigurations: ArrayList<ClientMetricsReportingConfiguration> =
            clientsSessionData[clientId]?.serviceAccessInformationSessionData?.serviceAccessInformation?.clientMetricsReportingConfigurations
                ?: return

        for (clientMetricsReportingConfiguration in clientMetricsReportingConfigurations) {
            if (shouldReport(clientMetricsReportingConfiguration)) {
                initializeReportingTimerForConfiguration(
                    clientId,
                    delay,
                    clientMetricsReportingConfiguration
                )
            }
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

        startSingleReportingTimer(clientId, delay, clientMetricsReportingConfiguration)
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

        val timer = Timer()
        val reportingInterval =
            clientMetricsReportingConfiguration.reportingInterval!!.times(1000)
        var finalDelay = delay
        if (finalDelay == null) {
            finalDelay = reportingInterval
        }

        timer.scheduleAtFixedRate(
            object : TimerTask() {
                override fun run() {
                    requestMetricsForSchemeFromClient(
                        clientId,
                        clientMetricsReportingConfiguration
                    )
                }
            },
            finalDelay,
            clientMetricsReportingConfiguration.reportingInterval!!.times(1000)
        )

        qoeMetricsReportingSessionDataEntry.reportingTimer = timer
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

        val retrofit = serverAddress?.let { retrofitBuilder.baseUrl(it).build() }
        if (retrofit != null) {
            qoeMetricsReportingSessionDataEntry.api =
                retrofit.create(IMetricsReportingApi::class.java)
            qoeMetricsReportingSessionDataEntry.reportingSelectedServerAddress =
                serverAddress
        }
    }

    private fun shouldReport(clientMetricsReportingConfiguration: ClientMetricsReportingConfiguration): Boolean {
        return clientMetricsReportingConfiguration.reportingInterval != null &&
                clientMetricsReportingConfiguration.reportingInterval!! > 0 &&
                shouldReportAccordingToSamplePercentage(clientMetricsReportingConfiguration.samplePercentage)
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

            // if sample percentage is set to 0 or no server addresses are available stop reporting
            if (updatedConfiguration.samplePercentage!! <= 0 && qoeMetricsReportingSessionDataEntry != null) {
                stopSingleReportingTimer(qoeMetricsReportingSessionDataEntry)
            }

            // if sample percentage is set to 100 start reporting
            if (updatedConfiguration.samplePercentage!! >= 100 && qoeMetricsReportingSessionDataEntry != null) {
                startSingleReportingTimer(clientId, 0, updatedConfiguration)
            }

        }

    }

    private fun stopSingleReportingTimer(qoeMetricsReportingSessionDataEntry: QoeMetricsReportingSessionDataEntry) {
        qoeMetricsReportingSessionDataEntry.reportingTimer?.cancel()
        qoeMetricsReportingSessionDataEntry.reportingTimer = null
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

        for (qoeMetricsReportingSessionDataEntry in qoeMetricsReportingSessionData.values) {
            stopSingleReportingTimer(qoeMetricsReportingSessionDataEntry)
        }
    }

}