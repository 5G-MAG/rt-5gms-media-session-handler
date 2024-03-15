package com.fivegmag.a5gmsmediasessionhandler.service

import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.fivegmag.a5gmscommonlibrary.helpers.PlayerStates
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerMessageTypes
import com.fivegmag.a5gmscommonlibrary.models.ServiceListEntry
import com.fivegmag.a5gmscommonlibrary.session.PlaybackRequest
import com.fivegmag.a5gmsmediasessionhandler.controller.ConsumptionReportingController
import com.fivegmag.a5gmsmediasessionhandler.controller.QoeMetricsReportingController
import com.fivegmag.a5gmsmediasessionhandler.controller.ServiceAccessInformationController
import com.fivegmag.a5gmsmediasessionhandler.controller.SessionController
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
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
    }

    fun reset() {
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

        resetClientSession(clientId)
        CoroutineScope(Dispatchers.Main).launch {
            val serviceAccessInformation = withContext(Dispatchers.IO) {
                serviceAccessInformationController.updateServiceAccessInformation(
                    serviceListEntry,
                    clientId
                )
            }

            val finalEntryPoints = sessionController.getFinalEntryPoints(serviceListEntry, clientId)

            if (finalEntryPoints != null && finalEntryPoints.size > 0) {
                val consumptionRequest =
                    consumptionReportingController.getConsumptionRequest(
                        serviceAccessInformation
                    )
                val qoeMetricsRequests =
                    qoeMetricsReportingController.getQoeMetricsRequests(serviceAccessInformation)
                val playbackRequest =
                    PlaybackRequest(
                        finalEntryPoints,
                        consumptionRequest,
                        qoeMetricsRequests
                    )
                val responseBundle = Bundle()
                responseBundle.putParcelable("playbackRequest", playbackRequest)
                outgoingMessageHandler.sendMessage(
                    SessionHandlerMessageTypes.TRIGGER_PLAYBACK,
                    responseBundle,
                    responseMessenger
                )
                if (serviceAccessInformation != null) {
                    qoeMetricsReportingController.initializeReporting(
                        clientId,
                        serviceAccessInformation
                    )
                }
            }
        }
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
    }
}