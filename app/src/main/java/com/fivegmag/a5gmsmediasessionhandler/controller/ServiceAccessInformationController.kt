package com.fivegmag.a5gmsmediasessionhandler.controller

import android.os.Bundle
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerMessageTypes
import com.fivegmag.a5gmscommonlibrary.helpers.Utils
import com.fivegmag.a5gmscommonlibrary.models.EntryPoint
import com.fivegmag.a5gmscommonlibrary.models.PlaybackRequest
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmscommonlibrary.models.ServiceListEntry
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionModel
import okhttp3.Headers
import retrofit2.Call
import retrofit2.Response
import java.util.Timer
import java.util.TimerTask

class ServiceAccessInformationController(
    private val clientsSessionData: HashMap<Int, ClientSessionModel>,
    private val consumptionReportingController: ConsumptionReportingController,
    private val qoeMetricsReportingController: QoeMetricsReportingController
) {

    companion object {
        const val TAG = "5GMS-ServiceAccessInformationController"
    }

    private val utils = Utils()

    fun initialize(msg: Message) {
        val bundle: Bundle = msg.data
        val responseMessenger: Messenger = msg.replyTo
        val clientId = msg.sendingUid
        bundle.classLoader = ServiceListEntry::class.java.classLoader
        val serviceListEntry: ServiceListEntry? = bundle.getParcelable("serviceListEntry")
        val provisioningSessionId: String = serviceListEntry!!.provisioningSessionId
        val call: Call<ServiceAccessInformation>? =
            clientsSessionData[msg.sendingUid]?.serviceAccessInformationApi?.fetchServiceAccessInformation(
                provisioningSessionId,
                null,
                null
            )

        call?.enqueue(object : retrofit2.Callback<ServiceAccessInformation?> {
            override fun onResponse(
                call: Call<ServiceAccessInformation?>,
                response: Response<ServiceAccessInformation?>
            ) {
                val serviceAccessInformation =
                    processServiceAccessInformation(response, clientId, provisioningSessionId)

                triggerPlayback(serviceListEntry, serviceAccessInformation, responseMessenger)
                initializeMetricsReporting(serviceAccessInformation, clientId)
            }

            override fun onFailure(call: Call<ServiceAccessInformation?>, t: Throwable) {
                Log.i(TAG, "Failure when requesting ServiceAccessInformation")
                call.cancel()
            }
        })
    }


    private fun triggerPlayback(
        serviceListEntry: ServiceListEntry,
        serviceAccessInformation: ServiceAccessInformation?,
        responseMessenger: Messenger
    ) {
        // Trigger the playback by providing all available entry points
        val msgResponse: Message = Message.obtain(
            null,
            SessionHandlerMessageTypes.SESSION_HANDLER_TRIGGERS_PLAYBACK
        )
        var finalEntryPoints: ArrayList<EntryPoint>? = serviceListEntry.entryPoints
        if (serviceAccessInformation != null && (finalEntryPoints == null || finalEntryPoints.size == 0)) {
            finalEntryPoints =
                serviceAccessInformation.streamingAccess.entryPoints
        }

        val responseBundle = Bundle()
        if (finalEntryPoints != null && finalEntryPoints.size > 0) {
            val playbackConsumptionReportingConfiguration =
                consumptionReportingController.getPlaybackConsumptionReportingConfiguration(
                    serviceAccessInformation
                )
            val playbackRequest =
                PlaybackRequest(
                    finalEntryPoints,
                    playbackConsumptionReportingConfiguration
                )
            responseBundle.putParcelable("playbackRequest", playbackRequest)
            msgResponse.data = responseBundle
            responseMessenger.send(msgResponse)
        }
    }

    private fun initializeMetricsReporting(
        serviceAccessInformation: ServiceAccessInformation?,
        clientId: Int
    ) {
        if (serviceAccessInformation != null) {
            if (serviceAccessInformation.clientMetricsReportingConfigurations != null && serviceAccessInformation.clientMetricsReportingConfigurations!!.size > 0) {
                qoeMetricsReportingController.requestMetricCapabilities(clientId)
            }
        }
    }

    private fun startServiceAccessInformationUpdateTimer(
        headers: Headers,
        sendingUid: Int,
        provisioningSessionId: String
    ) {
        val cacheControlHeader = headers.get("cache-control") ?: return
        val cacheControlHeaderItems = cacheControlHeader.split(',')
        val maxAgeHeader = cacheControlHeaderItems.filter { it.trim().startsWith("max-age=") }

        if (maxAgeHeader.isEmpty()) {
            return
        }

        val maxAgeValue = maxAgeHeader[0].trim().substring(8).toLong()
        val timer = Timer()
        clientsSessionData[sendingUid]?.serviceAccessInformationRequestTimer = timer

        timer.schedule(
            object : TimerTask() {
                override fun run() {
                    val call: Call<ServiceAccessInformation>? =
                        clientsSessionData[sendingUid]?.serviceAccessInformationApi?.fetchServiceAccessInformation(
                            provisioningSessionId,
                            clientsSessionData[sendingUid]?.serviceAccessInformationResponseHeaders?.get(
                                "etag"
                            ),
                            clientsSessionData[sendingUid]?.serviceAccessInformationResponseHeaders?.get(
                                "last-modified"
                            )
                        )

                    call?.enqueue(object : retrofit2.Callback<ServiceAccessInformation?> {
                        override fun onResponse(
                            call: Call<ServiceAccessInformation?>,
                            response: Response<ServiceAccessInformation?>
                        ) {

                            processServiceAccessInformation(
                                response,
                                sendingUid,
                                provisioningSessionId
                            )
                        }

                        override fun onFailure(
                            call: Call<ServiceAccessInformation?>,
                            t: Throwable
                        ) {
                            call.cancel()
                        }
                    })
                }
            },
            maxAgeValue * 1000
        )
    }

    private fun processServiceAccessInformation(
        response: Response<ServiceAccessInformation?>,
        clientId: Int,
        provisioningSessionId: String
    ): ServiceAccessInformation? {
        val headers = response.headers()
        val previousServiceAccessInformation: ServiceAccessInformation? =
            clientsSessionData[clientId]?.serviceAccessInformation

        // Save the ServiceAccessInformation if it has changed
        val resource: ServiceAccessInformation? = response.body()
        if (resource != null && response.code() != 304 && utils.hasResponseChanged(
                headers,
                clientsSessionData[clientId]?.serviceAccessInformationResponseHeaders
            )
        ) {
            clientsSessionData[clientId]?.serviceAccessInformation = resource

            // handle changes of the SAI compared to the previous one
            if (previousServiceAccessInformation != null) {
                consumptionReportingController.handleConsumptionReportingChanges(
                    previousServiceAccessInformation.clientConsumptionReportingConfiguration,
                    clientsSessionData[clientId]?.serviceAccessInformation?.clientConsumptionReportingConfiguration,
                    clientId
                )
            }
        }

        // Start the re-requesting of the Service Access Information according to the max-age header
        startServiceAccessInformationUpdateTimer(
            headers,
            clientId,
            provisioningSessionId
        )

        // Save current headers
        clientsSessionData[clientId]?.serviceAccessInformationResponseHeaders = headers

        return clientsSessionData[clientId]?.serviceAccessInformation
    }
}