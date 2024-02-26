/*
License: 5G-MAG Public License (v1.0)
Author: Daniel Silhavy
Copyright: (C) 2023 Fraunhofer FOKUS
For full license terms please see the LICENSE file distributed with this
program. If this file is missing then the license can be retrieved from
https://drive.google.com/file/d/1cinCiA778IErENZ3JN52VFW-1ffHpx7Z/view
*/

package com.fivegmag.a5gmsmediasessionhandler

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerMessageTypes
import com.fivegmag.a5gmsmediasessionhandler.controller.ConsumptionReportingController
import com.fivegmag.a5gmsmediasessionhandler.controller.QoeMetricsReportingController
import com.fivegmag.a5gmsmediasessionhandler.controller.SessionController
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionModel
import com.fivegmag.a5gmsmediasessionhandler.network.HeaderInterceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory


/**
 * Create a bound service when you want to interact with the service from activities and other components in your application
 * or to expose some of your application's functionality to other applications through interprocess communication (IPC).
 */
class MediaSessionHandlerMessengerService : Service() {
    companion object {
        const val TAG = "5GMS-MediaSessionHandlerMessengerService"
    }

    private lateinit var mMessenger: Messenger
    private var clientsSessionData = HashMap<Int, ClientSessionModel>()
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
    private lateinit var sessionController: SessionController


    inner class IncomingHandler(
        context: Context,
        private val applicationContext: Context = context.applicationContext
    ) : Handler() {

        override fun handleMessage(msg: Message) {
            try {
                when (msg.what) {
                    SessionHandlerMessageTypes.REGISTER_CLIENT -> sessionController.registerClient(
                        msg
                    )

                    SessionHandlerMessageTypes.UNREGISTER_CLIENT -> sessionController.unregisterClient(
                        msg
                    )

                    SessionHandlerMessageTypes.STATUS_MESSAGE -> sessionController.handleStatusMessage(
                        msg,
                        applicationContext
                    )

                    SessionHandlerMessageTypes.START_PLAYBACK_BY_SERVICE_LIST_ENTRY_MESSAGE -> sessionController.handleStartPlaybackByServiceListEntryMessage(
                        msg
                    )

                    SessionHandlerMessageTypes.SET_M5_ENDPOINT -> sessionController.setM5Endpoint(
                        msg
                    )

                    SessionHandlerMessageTypes.CONSUMPTION_REPORT -> consumptionReportingController.handleConsumptionReportMessage(
                        msg
                    )

                    SessionHandlerMessageTypes.REPORT_QOE_METRICS_CAPABILITIES -> qoeMetricsReportingController.handleQoeMetricsCapabilitiesMessage(
                        msg
                    )

                    SessionHandlerMessageTypes.REPORT_QOE_METRICS -> qoeMetricsReportingController.handleQoeMetricsReportMessage(
                        msg
                    )

                    else -> super.handleMessage(msg)
                }
            } catch (e: Exception) {
                Log.e(TAG, e.message.toString())
            }
        }
    }

    /**
     * When binding to the service, we return an interface to our messenger
     * for sending messages to the service. To create a bound service, you must define the interface that specifies
     * how a client can communicate with the service. This interface between the service and a client must be an implementation of
     * IBinder and is what your service must return from the onBind() callback method.
     */
    override fun onBind(intent: Intent): IBinder? {
        Log.i(TAG, "New service was bound")
        mMessenger = Messenger(IncomingHandler(this))
        consumptionReportingController =
            ConsumptionReportingController(clientsSessionData, retrofitBuilder, mMessenger)
        qoeMetricsReportingController =
            QoeMetricsReportingController(clientsSessionData, retrofitBuilder, mMessenger)
        sessionController =
            SessionController(
                clientsSessionData,
                retrofitBuilder,
                consumptionReportingController,
                qoeMetricsReportingController
            )
        return mMessenger.binder
    }
}