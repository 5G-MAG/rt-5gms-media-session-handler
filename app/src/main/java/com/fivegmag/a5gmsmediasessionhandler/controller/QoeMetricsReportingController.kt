package com.fivegmag.a5gmsmediasessionhandler.controller

import android.os.Bundle
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerMessageTypes
import com.fivegmag.a5gmscommonlibrary.models.ClientMetricsReportingConfiguration
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmscommonlibrary.qoeMetricsReporting.QoeMetricsRequest
import com.fivegmag.a5gmscommonlibrary.qoeMetricsReporting.QoeMetricsResponse
import com.fivegmag.a5gmscommonlibrary.qoeMetricsReporting.SchemeSupport
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionModel
import com.fivegmag.a5gmsmediasessionhandler.network.MetricsReportingApi
import okhttp3.MediaType
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import java.util.ArrayList
import java.util.Timer
import java.util.TimerTask

class QoeMetricsReportingController(
    clientsSessionData: HashMap<Int, ClientSessionModel>,
    private val retrofitBuilder: Retrofit.Builder,
    private val mMessenger: Messenger
) :
    ReportingController(clientsSessionData) {

    companion object {
        const val TAG = "5GMS-ConsumptionReportingController"
    }

    fun handleQoeMetricsCapabilitiesMessage(msg: Message) {
        val bundle: Bundle = msg.data
        bundle.classLoader = SchemeSupport::class.java.classLoader
        val schemeSupport: ArrayList<SchemeSupport>? =
            bundle.getParcelableArrayList("schemeSupport")
        val sendingUid = msg.sendingUid
        val clientMetricsReportingConfigurations: ArrayList<ClientMetricsReportingConfiguration>? =
            clientsSessionData[sendingUid]?.serviceAccessInformation?.clientMetricsReportingConfigurations

        // Update our SAI with information about which scheme is supported. Later we only start an interval timer for the supported ones
        if (clientMetricsReportingConfigurations != null) {
            for (clientMetricsReportingConfiguration in clientMetricsReportingConfigurations) {
                val schemeSupportedInfo =
                    schemeSupport?.filter { it.scheme == clientMetricsReportingConfiguration.scheme }
                if (schemeSupportedInfo != null) {
                    clientMetricsReportingConfiguration.isSchemeSupported =
                        schemeSupportedInfo[0].supported
                }
            }
        }

        Log.i(TAG, "Media Session Handler Service received scheme support message")
    }

    fun handleQoeMetricsReportMessage(msg: Message) {
        val bundle: Bundle = msg.data
        bundle.classLoader = QoeMetricsResponse::class.java.classLoader
        val playbackMetricsResponse: QoeMetricsResponse? =
            bundle.getParcelable("qoeMetricsResponse")
        val sendingUid = msg.sendingUid
        val clientSessionModel = clientsSessionData[sendingUid]
        val provisioningSessionId =
            clientSessionModel?.serviceAccessInformation?.provisioningSessionId
        val metricsReportingConfigurationId =
            playbackMetricsResponse?.metricsReportingConfigurationId
        val metricsString = playbackMetricsResponse?.metricsString
        val mediaType = MediaType.parse("application/xml")
        val requestBody: RequestBody? = metricsString?.let { RequestBody.create(mediaType, it) }

        if (clientSessionModel?.qoeMetricsReportingApi != null) {
            val call: Call<Void>? = clientSessionModel.qoeMetricsReportingApi!!.sendMetricsReport(
                provisioningSessionId,
                metricsReportingConfigurationId,
                requestBody
            )

            call?.enqueue(object : retrofit2.Callback<Void?> {
                override fun onResponse(call: Call<Void?>, response: Response<Void?>) {
                    when (val responseCode = response.code()) {
                        204 -> Log.d(TAG, "QoE Metrics Report: Accepted")
                        400 -> Log.d(TAG, "QoE Metrics Report: Bad Request")
                        415 -> Log.d(TAG, "QoE Metrics Report: Unsupported Media Type")
                        else -> Log.d(TAG, "QoE Metrics Report: Return code $responseCode")
                    }
                }

                override fun onFailure(call: Call<Void?>, t: Throwable) {
                    Log.d(TAG, "Error sending metrics report")
                }
            })
        }
    }

    fun initializeMetricsReporting(
        serviceAccessInformation: ServiceAccessInformation?,
        clientId: Int
    ) {
        if (serviceAccessInformation != null) {
            if (serviceAccessInformation.clientMetricsReportingConfigurations != null && serviceAccessInformation.clientMetricsReportingConfigurations!!.size > 0) {
                requestMetricCapabilities(clientId)
            }
        }
    }

    private fun requestMetricCapabilities(clientId: Int) {
        val msg: Message = Message.obtain(
            null,
            SessionHandlerMessageTypes.GET_QOE_METRICS_CAPABILITIES
        )
        val bundle = Bundle()
        val clientMetricsReportingConfigurations: ArrayList<ClientMetricsReportingConfiguration> =
            clientsSessionData[clientId]?.serviceAccessInformation?.clientMetricsReportingConfigurations
                ?: return
        val qoeMetricsRequests: ArrayList<QoeMetricsRequest> = ArrayList()
        for (clientMetricsReportingConfiguration in clientMetricsReportingConfigurations) {
            val playbackMetricsRequest = QoeMetricsRequest(
                clientMetricsReportingConfiguration.scheme,
                clientMetricsReportingConfiguration.reportingInterval,
                clientMetricsReportingConfiguration.metrics,
                clientMetricsReportingConfiguration.metricsReportingConfigurationId
            )
            qoeMetricsRequests.add(playbackMetricsRequest)
        }
        if (qoeMetricsRequests.size == 0) {
            return
        }
        val messenger = clientsSessionData[clientId]?.messenger
        bundle.putParcelableArrayList("qoeMetricsRequest", qoeMetricsRequests)
        msg.data = bundle
        msg.replyTo = mMessenger
        try {
            messenger?.send(msg)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    fun initializeReportingTimer(clientId: Int) {
        val clientMetricsReportingConfigurations: ArrayList<ClientMetricsReportingConfiguration>? =
            clientsSessionData[clientId]?.serviceAccessInformation?.clientMetricsReportingConfigurations

        if (clientMetricsReportingConfigurations != null) {
            for (clientMetricsReportingConfiguration in clientMetricsReportingConfigurations) {
                if (clientMetricsReportingConfiguration.isSchemeSupported == true &&
                    clientMetricsReportingConfiguration.reportingInterval != null &&
                    clientMetricsReportingConfiguration.reportingInterval!! > 0 &&
                    shouldReportAccordingToSamplePercentage(clientMetricsReportingConfiguration.samplePercentage)
                ) {
                    val timer = Timer()
                    timer.scheduleAtFixedRate(
                        object : TimerTask() {
                            override fun run() {
                                requestMetricsForSchemeFromClient(
                                    clientId,
                                    clientMetricsReportingConfiguration
                                )
                            }
                        },
                        0,
                        clientMetricsReportingConfiguration.reportingInterval!!.times(1000)
                    )
                    clientsSessionData[clientId]?.qoeMetricsReportingTimer?.add(timer)
                    // Select one of the servers to report the metrics to
                    var serverAddress =
                        clientMetricsReportingConfiguration.serverAddresses.random()
                    // Add a "/" in the end if not present
                    serverAddress = utils.addTrailingSlashIfNeeded(serverAddress)
                    val retrofit = retrofitBuilder.baseUrl(serverAddress).build()
                    clientsSessionData[clientId]?.qoeMetricsReportingApi =
                        retrofit.create(MetricsReportingApi::class.java)
                }
            }
        }
    }

    private fun requestMetricsForSchemeFromClient(
        clientId: Int,
        clientMetricsReportingConfiguration: ClientMetricsReportingConfiguration
    ) {
        val msg: Message = Message.obtain(
            null,
            SessionHandlerMessageTypes.GET_QOE_METRICS_REPORT
        )
        val bundle = Bundle()

        if (clientMetricsReportingConfiguration.isSchemeSupported == false) {
            return
        }

        val messenger = clientsSessionData[clientId]?.messenger
        val qoeMetricsRequest =
            QoeMetricsRequest(clientMetricsReportingConfiguration.scheme)
        if (clientMetricsReportingConfiguration.reportingInterval != null) {
            qoeMetricsRequest.reportPeriod =
                clientMetricsReportingConfiguration.reportingInterval!!
        }
        qoeMetricsRequest.metrics = clientMetricsReportingConfiguration.metrics
        qoeMetricsRequest.metricReportingConfigurationId =
            clientMetricsReportingConfiguration.metricsReportingConfigurationId
        bundle.putParcelable("qoeMetricsRequest", qoeMetricsRequest)
        msg.data = bundle
        msg.replyTo = mMessenger
        try {
            Log.i(
                TAG,
                "Request metrics for client $clientId and scheme ${clientMetricsReportingConfiguration.scheme}"
            )
            messenger?.send(msg)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    override fun handleConfigurationChanges() {

    }

    override fun resetClientSession(clientId: Int) {
        clientsSessionData[clientId]?.qoeMetricsReportingSelectedServerAddress = null
        stopMetricsReportingTimer(clientId)
    }

    fun stopMetricsReportingTimer(
        clientId: Int
    ) {
        if (clientsSessionData[clientId]?.consumptionReportingTimer != null) {
            clientsSessionData[clientId]?.consumptionReportingTimer?.cancel()
            clientsSessionData[clientId]?.consumptionReportingTimer = null
        }
    }
}