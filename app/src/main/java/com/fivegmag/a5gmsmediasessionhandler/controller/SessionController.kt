package com.fivegmag.a5gmsmediasessionhandler.controller

import android.content.Context
import android.os.Bundle
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.fivegmag.a5gmscommonlibrary.helpers.PlayerStates
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerMessageTypes
import com.fivegmag.a5gmscommonlibrary.models.EntryPoint
import com.fivegmag.a5gmscommonlibrary.models.PlaybackRequest
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmscommonlibrary.models.ServiceListEntry
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionModel
import com.fivegmag.a5gmsmediasessionhandler.network.ServiceAccessInformationApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import java.lang.Exception


class SessionController(
    private val clientsSessionData: HashMap<Int, ClientSessionModel>,
    private val retrofitBuilder: Retrofit.Builder,
    private val consumptionReportingController: ConsumptionReportingController,
    private val qoeMetricsReportingController: QoeMetricsReportingController
) {

    companion object {
        const val TAG = "5GMS-SessionController"
    }

    private val serviceAccessInformationController = ServiceAccessInformationController(
        clientsSessionData,
        consumptionReportingController
    )

    fun registerClient(msg: Message) {
        clientsSessionData[msg.sendingUid] = ClientSessionModel(msg.replyTo)
    }

    fun unregisterClient(msg: Message) {
        clientsSessionData.remove(msg.sendingUid)
    }

    fun handleStartPlaybackByServiceListEntryMessage(msg: Message) {
        val bundle: Bundle = msg.data
        val clientId = msg.sendingUid
        val responseMessenger: Messenger = msg.replyTo
        bundle.classLoader = ServiceListEntry::class.java.classLoader
        val serviceListEntry: ServiceListEntry = bundle.getParcelable("serviceListEntry")
            ?: throw Exception("No valid ServiceListEntry found")

        resetClientSession(clientId)
        CoroutineScope(Dispatchers.Main).launch {
            val serviceAccessInformation = withContext(Dispatchers.IO) {
                serviceAccessInformationController.fetchServiceAccessInformation(
                    serviceListEntry,
                    clientId
                )
            }

            triggerPlayback(serviceListEntry, serviceAccessInformation, responseMessenger)
            qoeMetricsReportingController.initializeMetricsReporting(
                serviceAccessInformation,
                clientId
            )
        }
    }

    private fun resetClientSession(clientId: Int) {
        if (clientsSessionData[clientId] != null) {
            Log.i(TAG, "Resetting information for client $clientId")
            clientsSessionData[clientId]?.initializedSession = false
            serviceAccessInformationController.resetClientSession(clientId)
            qoeMetricsReportingController.resetClientSession(clientId)
            consumptionReportingController.resetClientSession(clientId)
        }
    }

    private fun triggerPlayback(
        serviceListEntry: ServiceListEntry,
        serviceAccessInformation: ServiceAccessInformation?,
        responseMessenger: Messenger
    ) {
        // Trigger the playback by providing all available entry points
        val msgResponse: Message = Message.obtain(
            null,
            SessionHandlerMessageTypes.TRIGGER_PLAYBACK
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

    fun handleStatusMessage(msg: Message, applicationContext: Context) {
        val clientId = msg.sendingUid
        val bundle: Bundle = msg.data as Bundle

        when (bundle.getString("playbackState", "")) {
            PlayerStates.ENDED -> {
                consumptionReportingController.stopConsumptionReportingTimer(
                    clientId
                )
                qoeMetricsReportingController.stopMetricsReportingTimer(clientId)
            }

            PlayerStates.READY -> {
                initializeNewPlaybackSession(clientId)
            }
        }
    }

    private fun initializeNewPlaybackSession(clientId: Int) {
        if (clientsSessionData[clientId] != null && clientsSessionData[clientId]?.initializedSession == false) {
            consumptionReportingController.initializeReportingTimer(
                clientId,
                0
            )
            qoeMetricsReportingController.initializeReportingTimer(
                clientId
            )
            clientsSessionData[clientId]?.initializedSession = true
        }
    }

    fun setM5Endpoint(msg: Message) {
        val bundle: Bundle = msg.data
        val m5BaseUrl: String? = bundle.getString("m5BaseUrl")
        Log.i(TAG, "Setting M5 endpoint to $m5BaseUrl")
        if (m5BaseUrl != null) {
            val retrofit = retrofitBuilder
                .baseUrl(m5BaseUrl)
                .build()
            clientsSessionData[msg.sendingUid]?.serviceAccessInformationApi =
                retrofit.create(ServiceAccessInformationApi::class.java)
        }
    }

}