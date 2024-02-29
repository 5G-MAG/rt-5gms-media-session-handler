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
import com.fivegmag.a5gmscommonlibrary.qoeMetricsReporting.SchemeSupport
import com.fivegmag.a5gmsmediasessionhandler.eventbus.ServiceAccessInformationUpdatedEvent
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
import com.fivegmag.a5gmsmediasessionhandler.models.QoeMetricsReportingSessionDataEntry
import com.fivegmag.a5gmsmediasessionhandler.network.MetricsReportingApi
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
            requestMetricCapabilities(clientId)
        }
    }

    private fun requestMetricCapabilities(clientId: Int) {

        val clientMetricsReportingConfigurations: ArrayList<ClientMetricsReportingConfiguration> =
            clientsSessionData[clientId]?.serviceAccessInformationSessionData?.serviceAccessInformation?.clientMetricsReportingConfigurations
                ?: return
        val qoeMetricsRequests: ArrayList<QoeMetricsRequest> = ArrayList()
        for (clientMetricsReportingConfiguration in clientMetricsReportingConfigurations) {
            val qoeMetricsRequest = QoeMetricsRequest(
                clientMetricsReportingConfiguration.scheme,
                clientMetricsReportingConfiguration.reportingInterval,
                clientMetricsReportingConfiguration.metrics,
                clientMetricsReportingConfiguration.metricsReportingConfigurationId
            )
            qoeMetricsRequests.add(qoeMetricsRequest)
        }

        if (qoeMetricsRequests.size == 0) {
            return
        }

        val bundle = Bundle()
        bundle.putParcelableArrayList("qoeMetricsRequest", qoeMetricsRequests)
        val messenger = clientsSessionData[clientId]?.messenger
        outgoingMessageHandler.sendMessage(
            SessionHandlerMessageTypes.GET_QOE_METRICS_CAPABILITIES,
            bundle,
            messenger
        )
    }

    fun handleQoeMetricsCapabilitiesMessage(msg: Message) {
        val bundle: Bundle = msg.data
        bundle.classLoader = SchemeSupport::class.java.classLoader
        val schemeSupport: ArrayList<SchemeSupport>? =
            bundle.getParcelableArrayList("schemeSupport")
        val clientId = msg.sendingUid
        val clientMetricsReportingConfigurations: ArrayList<ClientMetricsReportingConfiguration> =
            clientsSessionData[clientId]?.serviceAccessInformationSessionData?.serviceAccessInformation?.clientMetricsReportingConfigurations
                ?: return

        for (clientMetricsReportingConfiguration in clientMetricsReportingConfigurations) {
            val schemeSupportedInfo =
                schemeSupport?.filter { it.scheme == clientMetricsReportingConfiguration.scheme }
            if (schemeSupportedInfo != null) {
                clientMetricsReportingConfiguration.isSchemeSupported =
                    schemeSupportedInfo[0].supported
            }
            if (clientMetricsReportingConfiguration.isSchemeSupported == true) {
                clientsSessionData[clientId]?.qoeMetricsReportingSessionData?.set(
                    clientMetricsReportingConfiguration.metricsReportingConfigurationId,
                    QoeMetricsReportingSessionDataEntry()
                )
            }
        }

    }


    fun handleQoeMetricsReportMessage(msg: Message) {
        val bundle: Bundle = msg.data
        bundle.classLoader = QoeMetricsResponse::class.java.classLoader
        val playbackMetricsResponse: QoeMetricsResponse? =
            bundle.getParcelable("qoeMetricsResponse")
        val clientId = msg.sendingUid
        val clientSessionData = clientsSessionData[clientId] ?: return
        val metricsReportingConfigurationId =
            playbackMetricsResponse?.metricsReportingConfigurationId
        val qoeMetricsReportingSessionDataEntry =
            clientSessionData.qoeMetricsReportingSessionData[metricsReportingConfigurationId]
        val api = qoeMetricsReportingSessionDataEntry?.api ?: return
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

        startReportingTimer(clientId, delay, clientMetricsReportingConfiguration)
    }

    private fun startReportingTimer(
        clientId: Int,
        delay: Long?,
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
                retrofit.create(MetricsReportingApi::class.java)
            qoeMetricsReportingSessionDataEntry.reportingSelectedServerAddress =
                serverAddress
        }
    }

    private fun shouldReport(clientMetricsReportingConfiguration: ClientMetricsReportingConfiguration): Boolean {
        return clientMetricsReportingConfiguration.isSchemeSupported == true &&
                clientMetricsReportingConfiguration.reportingInterval != null &&
                clientMetricsReportingConfiguration.reportingInterval!! > 0 &&
                shouldReportAccordingToSamplePercentage(clientMetricsReportingConfiguration.samplePercentage)
    }

    private fun requestMetricsForSchemeFromClient(
        clientId: Int,
        clientMetricsReportingConfiguration: ClientMetricsReportingConfiguration
    ) {

        if (clientMetricsReportingConfiguration.isSchemeSupported == false) {
            return
        }

        val qoeMetricsRequest =
            QoeMetricsRequest(clientMetricsReportingConfiguration.scheme)
        if (clientMetricsReportingConfiguration.reportingInterval != null) {
            qoeMetricsRequest.reportPeriod =
                clientMetricsReportingConfiguration.reportingInterval!!
        }
        qoeMetricsRequest.metrics = clientMetricsReportingConfiguration.metrics
        qoeMetricsRequest.metricReportingConfigurationId =
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
        return
    }

    override fun resetClientSession(clientId: Int) {
        stopReportingTimer(clientId)
        clientsSessionData[clientId]?.qoeMetricsReportingSessionData?.clear()
    }

    override fun stopReportingTimer(clientId: Int) {
        val qoeMetricsReportingSessionData =
            clientsSessionData[clientId]?.qoeMetricsReportingSessionData ?: return

        for (qoeMetricsReportingSessionDataEntry in qoeMetricsReportingSessionData.values) {
            qoeMetricsReportingSessionDataEntry.reportingTimer?.cancel()
        }
    }
}