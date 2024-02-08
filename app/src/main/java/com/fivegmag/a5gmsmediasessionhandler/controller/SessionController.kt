package com.fivegmag.a5gmsmediasessionhandler.controller

import android.content.Context
import android.os.Bundle
import android.os.Message
import android.util.Log
import android.widget.Toast
import com.fivegmag.a5gmscommonlibrary.helpers.PlayerStates
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionModel
import com.fivegmag.a5gmsmediasessionhandler.network.ServiceAccessInformationApi
import retrofit2.Retrofit


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
        consumptionReportingController,
        qoeMetricsReportingController
    )

    fun registerClient(msg: Message) {
        clientsSessionData[msg.sendingUid] = ClientSessionModel(msg.replyTo)
    }

    fun unregisterClient(msg: Message) {
        clientsSessionData.remove(msg.sendingUid)
    }

    fun handleStartPlaybackByServiceListEntryMessage(msg: Message) {
        val clientId = msg.sendingUid;
        resetClientSessionData(clientId)
        serviceAccessInformationController.initialize(msg)
    }

    private fun resetClientSessionData(clientId: Int) {
        if (clientsSessionData[clientId] != null) {
            Log.i(TAG, "Resetting information for client $clientId")

            clientsSessionData[clientId]?.serviceAccessInformation = null
            clientsSessionData[clientId]?.serviceAccessInformationRequestTimer?.cancel()
            clientsSessionData[clientId]?.serviceAccessInformationRequestTimer = null
            clientsSessionData[clientId]?.serviceAccessInformationResponseHeaders = null
            clientsSessionData[clientId]?.initializedSession = false
            clientsSessionData[clientId]?.consumptionReportingSelectedServerAddress = null

            consumptionReportingController.stopConsumptionReportingTimer(clientId)
        }
    }

    fun handleStatusMessage(msg: Message, applicationContext: Context) {
        val clientId = msg.sendingUid
        val bundle: Bundle = msg.data as Bundle
        val state: String = bundle.getString("playbackState", "")
        Toast.makeText(
            applicationContext,
            "Media Session Handler Service received state message: $state",
            Toast.LENGTH_SHORT
        ).show()

        when (state) {
            PlayerStates.ENDED -> {
                consumptionReportingController.stopConsumptionReportingTimer(
                    clientId
                )
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
        try {
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
        } catch (e: Exception) {
        }
    }

}