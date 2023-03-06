package com.fivegmag.a5gmsmediasessionhandler

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerMessageTypes
import com.fivegmag.a5gmscommonlibrary.models.M8Model
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmsmediasessionhandler.network.ServiceAccessInformationApi
import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory


const val TAG ="5GMS Media Session Handler"

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
    private val provisioningSessionIdLookupTable = mutableMapOf<String, String>()
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
                SessionHandlerMessageTypes.START_PLAYBACK_BY_MEDIA_PLAYER_ENTRY_MESSAGE -> handleStartPlaybackByMediaPlayerEntryMessage(
                    msg
                )
                SessionHandlerMessageTypes.UPDATE_LOOKUP_TABLE -> updateLookupTable(msg)
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

        private fun handleStartPlaybackByMediaPlayerEntryMessage(msg: Message) {
            val bundle: Bundle = msg.data
            val mediaPlayerEntry: String = bundle.getString("mediaPlayerEntry", "")
            val responseMessenger: Messenger = msg.replyTo
            val provisioningSessionId: String? =
                provisioningSessionIdLookupTable[mediaPlayerEntry]
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
                    val bundle = Bundle()
                    bundle.putString("mediaPlayerEntry", mediaPlayerEntry)
                    msgResponse.data = bundle
                    responseMessenger.send(msgResponse)
                }

                override fun onFailure(call: Call<ServiceAccessInformation?>, t: Throwable) {
                    call.cancel()
                }
            })

        }

        private fun updateLookupTable(msg: Message) {

            val bundle: Bundle = msg.data
            bundle.classLoader = M8Model::class.java.classLoader
            val m8Model: M8Model? = bundle.getParcelable("m8Data")
            val iterator = m8Model?.serviceAccessInformation?.iterator()
            if (iterator != null) {
                while (iterator.hasNext()) {
                    val current: ServiceAccessInformation = iterator.next()
                    provisioningSessionIdLookupTable[current.streamingAccess.mediaPlayerEntry] =
                        current.provisioningSessionId
                }
            }


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