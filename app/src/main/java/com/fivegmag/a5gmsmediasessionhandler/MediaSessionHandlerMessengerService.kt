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
import com.fivegmag.a5gmscommonlibrary.helpers.PlayerStates
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerMessageTypes
import com.fivegmag.a5gmscommonlibrary.models.*
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionModel
import com.fivegmag.a5gmsmediasessionhandler.network.ServiceAccessInformationApi
import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap


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

    /** Keeps track of all current registered clients.  */
    private var clientsSessionData = HashMap<Int, ClientSessionModel>()

    /**
     * Handler of incoming messages from clients.
     */
    inner class IncomingHandler(
        context: Context,
        private val applicationContext: Context = context.applicationContext
    ) : Handler() {

        /**
         * Main function to handle all incoming messages from the Media Stream Handler and the 5GMSd Aware Application
         * @param msg - Message by the sender
         */
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                SessionHandlerMessageTypes.REGISTER_CLIENT -> registerClient(msg)
                SessionHandlerMessageTypes.UNREGISTER_CLIENT -> registerClient(msg)
                SessionHandlerMessageTypes.STATUS_MESSAGE -> handleStatusMessage(msg)
                SessionHandlerMessageTypes.START_PLAYBACK_BY_SERVICE_LIST_ENTRY_MESSAGE -> handleStartPlaybackByServiceListEntryMessage(
                    msg
                )
                SessionHandlerMessageTypes.SET_M5_ENDPOINT -> setM5Endpoint(msg)
                SessionHandlerMessageTypes.REPORT_PLAYBACK_METRICS_CAPABILITIES -> handlePlaybackMetricsCapabilitiesMessage(
                    msg
                )

                else -> super.handleMessage(msg)
            }
        }

        /**
         * Callback that is triggered when a client is connected to the Media Session Handler.
         * We save its messenger to be able to send messages to this client.
         *
         * @param msg
         */
        private fun registerClient(msg: Message) {
            clientsSessionData[msg.sendingUid] = ClientSessionModel(msg.replyTo)
        }

        /**
         * Callback that is triggered when a client is disconnecting
         *
         * @param msg
         */
        private fun unregisterClient(msg: Message) {
            clientsSessionData.remove(msg.sendingUid)
        }

        /**
         * Handles status messages coming from the Media Stream Handler
         *
         * @param msg
         */
        private fun handleStatusMessage(msg: Message) {
            val bundle: Bundle = msg.data as Bundle
            val sendingUid = msg.sendingUid
            val state: String = bundle.getString("playbackState", "")
            Toast.makeText(
                applicationContext,
                "Media Session Handler Service received state message: $state",
                Toast.LENGTH_SHORT
            ).show()
            when (state) {
                PlayerStates.READY -> startMetricTimer(sendingUid)
            }
        }

        /**
         * Handles the start playback message dispatched by the 5GMSd Aware Application. We get a
         * ServiceListEntry as part of the message data from which we derive the
         * provisioningSessionId. Afterwards we fetch the ServiceAccessInformation via M5 and return a list
         * of entryPoints to the Media Stream Handler for playback
         *
         * @param msg
         */
        private fun handleStartPlaybackByServiceListEntryMessage(msg: Message) {
            val bundle: Bundle = msg.data
            bundle.classLoader = ServiceListEntry::class.java.classLoader
            val serviceListEntry: ServiceListEntry? = bundle.getParcelable("serviceListEntry")
            val responseMessenger: Messenger = msg.replyTo
            val provisioningSessionId: String = serviceListEntry!!.provisioningSessionId
            val call: Call<ServiceAccessInformation>? =
                clientsSessionData[msg.sendingUid]?.serviceAccessInformationApi?.fetchServiceAccessInformation(provisioningSessionId)
            val sendingUid = msg.sendingUid;

            resetClientSession(sendingUid)

            call?.enqueue(object : retrofit2.Callback<ServiceAccessInformation?> {
                override fun onResponse(
                    call: Call<ServiceAccessInformation?>,
                    response: Response<ServiceAccessInformation?>
                ) {
                    val resource: ServiceAccessInformation = response.body() ?: return
                    clientsSessionData[sendingUid]?.serviceAccessInformation = resource

                    // Trigger the playback by providing all available entry points
                    val msgResponse: Message = Message.obtain(
                        null,
                        SessionHandlerMessageTypes.SESSION_HANDLER_TRIGGERS_PLAYBACK
                    )
                    var finalEntryPoints: ArrayList<EntryPoint>? = serviceListEntry.entryPoints
                    if (finalEntryPoints == null || finalEntryPoints.size == 0) {
                        finalEntryPoints =
                            resource.streamingAccess.entryPoints
                    }

                    val bundle = Bundle()
                    if (finalEntryPoints != null && finalEntryPoints.size > 0) {
                        bundle.putParcelableArrayList("entryPoints", finalEntryPoints)
                        msgResponse.data = bundle
                        responseMessenger.send(msgResponse)
                    }

                    // Handle metric reporting. Query the capabilities
                    if (resource.clientMetricsReportingConfigurations != null && resource.clientMetricsReportingConfigurations!!.size > 0) {
                        requestMetricCapabilities(sendingUid)
                    }
                }

                override fun onFailure(call: Call<ServiceAccessInformation?>, t: Throwable) {
                    call.cancel()
                }
            })
        }

        /**
         * Handles an incoming metric capabilities message from the Media Stream Handler.
         * We update the Service Access Information setting a supported flag for each metrics scheme
         *
         * @param msg
         */
        private fun handlePlaybackMetricsCapabilitiesMessage(msg: Message) {
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

            Toast.makeText(
                applicationContext,
                "Media Session Handler Service received scheme support message",
                Toast.LENGTH_SHORT
            ).show()

            //TODO: For unsupported schemes: An error message shall be sent by the Media Session Handler to the appropriate network entity, indicating that metrics reporting for the indicated metrics scheme cannot be supported for this streaming service

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
                for (timer in clientsSessionData[clientId]?.metricReportingTimer!!) {
                    timer.cancel()
                }
                clientsSessionData[clientId]?.metricReportingTimer?.clear()
            }
        }


        /**
         * Start the timers to fetch the metrics for each supported metrics scheme.
         *
         * @param clientId
         */
        private fun startMetricTimer(clientId: Int) {
            val clientMetricsReportingConfigurations: ArrayList<ClientMetricsReportingConfiguration>? =
                clientsSessionData[clientId]?.serviceAccessInformation?.clientMetricsReportingConfigurations

            if (clientMetricsReportingConfigurations != null) {
                for (clientMetricsReportingConfiguration in clientMetricsReportingConfigurations) {
                    if (clientMetricsReportingConfiguration.isSchemeSupported == true && clientMetricsReportingConfiguration.reportingInterval != null && clientMetricsReportingConfiguration.reportingInterval!! > 0) {
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
                        clientsSessionData[clientId]?.metricReportingTimer?.add(timer)
                    }
                }
            }

            Toast.makeText(
                applicationContext,
                "Starting metric timer for client id: $clientId",
                Toast.LENGTH_SHORT
            ).show()
        }

        /**
         * Request the supported metrics from the Media Stream Handler
         *
         * @param clientId
         */
        private fun requestMetricCapabilities(clientId: Int) {
            val msg: Message = Message.obtain(
                null,
                SessionHandlerMessageTypes.GET_PLAYBACK_METRIC_CAPABILITIES
            )
            val bundle = Bundle()
            val clientMetricsReportingConfigurations: ArrayList<ClientMetricsReportingConfiguration> =
                clientsSessionData[clientId]?.serviceAccessInformation?.clientMetricsReportingConfigurations
                    ?: return
            val metricsSchemes: ArrayList<String> = ArrayList()
            for (clientMetricsReportingConfiguration in clientMetricsReportingConfigurations) {
                metricsSchemes.add(clientMetricsReportingConfiguration.scheme)
            }
            if (metricsSchemes.size == 0) {
                return
            }
            val messenger = clientsSessionData[clientId]?.messenger
            bundle.putStringArrayList("metricsSchemes", metricsSchemes)
            msg.data = bundle
            msg.replyTo = mMessenger
            try {
                messenger?.send(msg)
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
        }


        /**
         * Set the M5 endpoint to fetch the Service Access Information from
         *
         * @param msg
         */
        private fun setM5Endpoint(msg: Message) {
            try {
                val bundle: Bundle = msg.data
                val m5BaseUrl: String? = bundle.getString("m5BaseUrl")
                Log.i(TAG, "Setting M5 endpoint to $m5BaseUrl")
                if (m5BaseUrl != null) {
                    val retrofitServiceAccessInformation: Retrofit = Retrofit.Builder()
                        .baseUrl(m5BaseUrl)
                        .addConverterFactory(GsonConverterFactory.create())
                        .build()
                    clientsSessionData[msg.sendingUid]?.serviceAccessInformationApi =
                        retrofitServiceAccessInformation.create(ServiceAccessInformationApi::class.java)
                }
            } catch (e: Exception) {
            }
        }

        /**
         * Request the metrics from the Media Stream Handler for a specific metrics scheme
         *
         * @param clientId
         * @param clientMetricsReportingConfiguration
         */
        private fun requestMetricsForSchemeFromClient(
            clientId: Int,
            clientMetricsReportingConfiguration: ClientMetricsReportingConfiguration
        ) {
            val msg: Message = Message.obtain(
                null,
                SessionHandlerMessageTypes.GET_PLAYBACK_METRICS
            )
            val bundle = Bundle()

            if (clientMetricsReportingConfiguration.isSchemeSupported == false) {
                return
            }

            val messenger = clientsSessionData[clientId]?.messenger
            bundle.putString("scheme", clientMetricsReportingConfiguration.scheme)
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

        private fun triggerEvent() {

        }

    }

    /**
     * When binding to the service, we return an interface to our messenger
     * for sending messages to the service. To create a bound service, you must define the interface that specifies
     * how a client can communicate with the service. This interface between the service and a client must be an implementation of
     * IBinder and is what your service must return from the onBind() callback method.
     *
     * @param intent
     * @return
     */
    override fun onBind(intent: Intent): IBinder? {
        Log.i("MediaSessionHandler-New", "Service bound new")
        mMessenger = Messenger(IncomingHandler(this))
        return mMessenger.binder
    }

}