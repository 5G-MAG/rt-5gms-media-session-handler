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
import com.fivegmag.a5gmscommonlibrary.models.EntryPoint
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmscommonlibrary.models.ServiceListEntry
import com.fivegmag.a5gmsmediasessionhandler.network.ServiceAccessInformationApi
import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory


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
    private lateinit var serviceAccessInformationApi: ServiceAccessInformationApi
    private lateinit var currentServiceAccessInformation: ServiceAccessInformation

    /** Keeps track of all current registered clients.  */
    var mClients = ArrayList<Messenger>()

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
            mClients.add(msg.replyTo)
        }

        private fun unregisterClient(msg: Message) {
            mClients.remove(msg.replyTo)
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
            val provisioningSessionId: String = serviceListEntry!!.provisioningSessionId
            val call: Call<ServiceAccessInformation>? =
                serviceAccessInformationApi.fetchServiceAccessInformation(provisioningSessionId)

            call?.enqueue(object : retrofit2.Callback<ServiceAccessInformation?> {
                override fun onResponse(
                    call: Call<ServiceAccessInformation?>,
                    response: Response<ServiceAccessInformation?>
                ) {
                    val resource: ServiceAccessInformation? = response.body()
                    if (resource != null) {
                        currentServiceAccessInformation = resource
                    }
                    val msgResponse: Message = Message.obtain(
                        null,
                        SessionHandlerMessageTypes.SESSION_HANDLER_TRIGGERS_PLAYBACK
                    )
                    var finalEntryPoints : ArrayList<EntryPoint>? = serviceListEntry.entryPoints
                    if (finalEntryPoints == null || finalEntryPoints.size == 0) {
                        finalEntryPoints =
                            currentServiceAccessInformation.streamingAccess.entryPoints
                    }


                    val bundle = Bundle()
                    if (finalEntryPoints != null && finalEntryPoints.size > 0) {
                        bundle.putParcelableArrayList("entryPoints", finalEntryPoints)
                        msgResponse.data = bundle
                        responseMessenger.send(msgResponse)
                    }
                }

                override fun onFailure(call: Call<ServiceAccessInformation?>, t: Throwable) {
                    call.cancel()
                }
            })
        }


        private fun setM5Endpoint(msg: Message) {
            try {
                val bundle: Bundle = msg.data
                val m5Url: String? = bundle.getString("m5Url")
                Log.i(TAG, "Setting M5 endpoint to $m5Url")
                if (m5Url != null) {
                    initializeRetrofitForServiceAccessInformation(m5Url)
                }
            } catch (e: Exception) {
            }
        }

        private fun triggerEvent() {

        }

    }

    private fun initializeRetrofitForServiceAccessInformation(url: String) {
        val retrofitServiceAccessInformation: Retrofit = Retrofit.Builder()
            .baseUrl(url)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        serviceAccessInformationApi =
            retrofitServiceAccessInformation.create(ServiceAccessInformationApi::class.java)
    }

    /**
     * When binding to the service, we return an interface to our messenger
     * for sending messages to the service. To create a bound service, you must define the interface that specifies
     * how a client can communicate with the service. This interface between the service and a client must be an implementation of
     * IBinder and is what your service must return from the onBind() callback method.
     */
    override fun onBind(intent: Intent): IBinder? {
        Log.i("MediaSessionHandler-New", "Service bound new")
        return initializeMessenger()
    }

    private fun initializeMessenger(): IBinder? {
        mMessenger = Messenger(IncomingHandler(this))
        return mMessenger.binder
    }

}