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
import android.widget.Toast
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerMessageTypes
import com.fivegmag.a5gmscommonlibrary.helpers.Utils
import com.fivegmag.a5gmscommonlibrary.models.EntryPoint
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmscommonlibrary.models.ServiceListEntry
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionModel
import com.fivegmag.a5gmsmediasessionhandler.network.HeaderInterceptor
import com.fivegmag.a5gmsmediasessionhandler.network.ServiceAccessInformationApi
import okhttp3.Headers
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Timer
import java.util.TimerTask


const val TAG = "5GMS Media Session Handler"

/**
 * Create a bound service when you want to interact with the service from activities and other components in your application
 * or to expose some of your application's functionality to other applications through interprocess communication (IPC).
 */
class MediaSessionHandlerMessengerService() : Service() {

    /**
     * Target we publish for clients to send messages to IncomingHandler.
     */
    private lateinit var mMessenger: Messenger
    private var clientsSessionData = HashMap<Int, ClientSessionModel>()
    private val headerInterceptor = HeaderInterceptor()
    private val utils = Utils()
    private val okHttpClient = OkHttpClient()
        .newBuilder()
        .addInterceptor(headerInterceptor)
        .build()
    private val retrofitBuilder = Retrofit.Builder()
        .addConverterFactory(GsonConverterFactory.create())
        .client(okHttpClient)

    /**
     * Handler of incoming messages from clients.
     */
    inner class IncomingHandler(
        context: Context,
        private val applicationContext: Context = context.applicationContext
    ) : Handler() {

        override fun handleMessage(msg: Message) {
            when (msg.what) {
                SessionHandlerMessageTypes.REGISTER_CLIENT -> registerClient(msg)
                SessionHandlerMessageTypes.UNREGISTER_CLIENT -> registerClient(msg)
                SessionHandlerMessageTypes.STATUS_MESSAGE -> handleStatusMessage(msg)
                SessionHandlerMessageTypes.START_PLAYBACK_BY_SERVICE_LIST_ENTRY_MESSAGE -> handleStartPlaybackByServiceListEntryMessage(
                    msg
                )

                SessionHandlerMessageTypes.SET_M5_ENDPOINT -> setM5Endpoint(msg)
                else -> super.handleMessage(msg)
            }
        }

        private fun registerClient(msg: Message) {
            clientsSessionData[msg.sendingUid] = ClientSessionModel(msg.replyTo)
        }

        private fun unregisterClient(msg: Message) {
            clientsSessionData.remove(msg.sendingUid)
        }

        private fun handleStatusMessage(msg: Message) {

            val bundle: Bundle = msg.data as Bundle
            val state: String = bundle.getString("playbackState", "")
            Toast.makeText(
                applicationContext,
                "Media Session Handler Service received state message: $state",
                Toast.LENGTH_SHORT
            ).show()

        }


        private fun handleStartPlaybackByServiceListEntryMessage(msg: Message) {
            val bundle: Bundle = msg.data
            bundle.classLoader = ServiceListEntry::class.java.classLoader
            val serviceListEntry: ServiceListEntry? = bundle.getParcelable("serviceListEntry")
            val responseMessenger: Messenger = msg.replyTo
            val sendingUid = msg.sendingUid;
            resetClientSession(sendingUid)

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

                    val resource =
                        handleServiceAccessResponse(response, sendingUid, provisioningSessionId)

                    // Trigger the playback by providing all available entry points
                    val msgResponse: Message = Message.obtain(
                        null,
                        SessionHandlerMessageTypes.SESSION_HANDLER_TRIGGERS_PLAYBACK
                    )
                    var finalEntryPoints: ArrayList<EntryPoint>? = serviceListEntry.entryPoints
                    if (resource != null && (finalEntryPoints == null || finalEntryPoints.size == 0)) {
                        finalEntryPoints =
                            resource.streamingAccess.entryPoints
                    }

                    val responseBundle = Bundle()
                    if (finalEntryPoints != null && finalEntryPoints.size > 0) {
                        responseBundle.putParcelableArrayList("entryPoints", finalEntryPoints)
                        msgResponse.data = responseBundle
                        responseMessenger.send(msgResponse)
                    }

                }

                override fun onFailure(call: Call<ServiceAccessInformation?>, t: Throwable) {
                    call.cancel()
                }
            })
        }

        /**
         * Starts the timer task to re-request and saves the current state of the Service Access Information e
         *
         * @param response
         * @param sendingUid
         * @param provisioningSessionId
         * @return
         */
        private fun handleServiceAccessResponse(
            response: Response<ServiceAccessInformation?>,
            sendingUid: Int,
            provisioningSessionId: String
        ): ServiceAccessInformation? {
            val headers = response.headers()


            // Save the ServiceAccessInformation if it has changed
            val resource: ServiceAccessInformation? = response.body()
            if (resource != null && utils.hasResponseChanged(
                    headers,
                    clientsSessionData[sendingUid]?.serviceAccessInformationResponseHeaders
                )
            ) {
                clientsSessionData[sendingUid]?.serviceAccessInformation = resource
            }


            // Start the re-requesting of the Service Access Information according to the max-age header
            startServiceAccessInformationUpdateTimer(
                headers,
                sendingUid,
                provisioningSessionId
            )

            // Save current headers
            clientsSessionData[sendingUid]?.serviceAccessInformationResponseHeaders = headers

            return clientsSessionData[sendingUid]?.serviceAccessInformation
        }


        /**
         * Starts the timer task to re-request the the Service Access Information
         *
         * @param headers
         * @param sendingUid
         * @param provisioningSessionId
         */
        private fun startServiceAccessInformationUpdateTimer(
            headers: Headers,
            sendingUid: Int,
            provisioningSessionId: String
        ) {
            val cacheControlHeader = headers.get("cache-control") ?: return
            val cacheControlHeaderItems = cacheControlHeader.split(',');
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
                                clientsSessionData[sendingUid]?.serviceAccessInformationResponseHeaders?.get("etag"),
                                clientsSessionData[sendingUid]?.serviceAccessInformationResponseHeaders?.get("last-modified")
                            )

                        call?.enqueue(object : retrofit2.Callback<ServiceAccessInformation?> {
                            override fun onResponse(
                                call: Call<ServiceAccessInformation?>,
                                response: Response<ServiceAccessInformation?>
                            ) {

                                handleServiceAccessResponse(
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

        /**
         * Reset a client session once a new playback session is started. Remove the ServiceAccessInformation
         * for the corresponding client id and reset all metric reporting timers.
         *
         * @param clientId
         */
        private fun resetClientSession(clientId: Int) {
            if (clientsSessionData[clientId] != null) {
                Log.i(TAG, "Resetting information for client $clientId")
                clientsSessionData[clientId]?.serviceAccessInformation = null
                clientsSessionData[clientId]?.serviceAccessInformationRequestTimer?.cancel()
                clientsSessionData[clientId]?.serviceAccessInformationRequestTimer = null
                clientsSessionData[clientId]?.serviceAccessInformationResponseHeaders = null
            }
        }


        private fun setM5Endpoint(msg: Message) {
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

        private fun triggerEvent() {

        }

    }

    /**
     * When binding to the service, we return an interface to our messenger
     * for sending messages to the service. To create a bound service, you must define the interface that specifies
     * how a client can communicate with the service. This interface between the service and a client must be an implementation of
     * IBinder and is what your service must return from the onBind() callback method.
     */
    override fun onBind(intent: Intent): IBinder? {
        Log.i("MediaSessionHandler-New", "Service bound new")
        mMessenger = Messenger(IncomingHandler(this))
        return mMessenger.binder
    }


}