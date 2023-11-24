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
import com.fivegmag.a5gmscommonlibrary.helpers.Utils
import com.fivegmag.a5gmscommonlibrary.models.ClientConsumptionReportingConfiguration
import com.fivegmag.a5gmscommonlibrary.models.EntryPoint
import com.fivegmag.a5gmscommonlibrary.models.PlaybackConsumptionReportingConfiguration
import com.fivegmag.a5gmscommonlibrary.models.PlaybackRequest
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmscommonlibrary.models.ServiceListEntry
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionModel
import com.fivegmag.a5gmsmediasessionhandler.network.HeaderInterceptor
import com.fivegmag.a5gmsmediasessionhandler.network.ServiceAccessInformationApi
import com.fivegmag.a5gmsmediasessionhandler.network.ConsumptionReportingApi

import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
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
                SessionHandlerMessageTypes.UNREGISTER_CLIENT -> unregisterClient(msg)
                SessionHandlerMessageTypes.STATUS_MESSAGE -> handleStatusMessage(msg)
                SessionHandlerMessageTypes.START_PLAYBACK_BY_SERVICE_LIST_ENTRY_MESSAGE -> handleStartPlaybackByServiceListEntryMessage(
                    msg
                )

                SessionHandlerMessageTypes.SET_M5_ENDPOINT -> setM5Endpoint(msg)
                SessionHandlerMessageTypes.CONSUMPTION_REPORT -> handleConsumptionReportMessage(msg)
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
            val sendingUid = msg.sendingUid
            val bundle: Bundle = msg.data as Bundle
            val state: String = bundle.getString("playbackState", "")
            Log.i(TAG, "[ConsumptionReporting] playbackState updated【$state】")
            Toast.makeText(
                applicationContext,
                "Media Session Handler Service received state message: $state",
                Toast.LENGTH_SHORT
            ).show()

            when (state) {
                PlayerStates.ENDED -> {
                    stopConsumptionReportingTimer(sendingUid)
                }

                PlayerStates.READY -> {
                    initializeNewPlaybackSession(sendingUid)
                }
            }
        }

        private fun initializeNewPlaybackSession(clientId: Int) {
            if (clientsSessionData[clientId] != null && clientsSessionData[clientId]?.initializedSession == false) {

                // Set AF endpoint for consumption reporting
                setConsumptionReportingEndpoint(clientId)

                // Start consumption reporting
                initializeConsumptionReportingTimer(clientId, 0)

                clientsSessionData[clientId]?.initializedSession = true
            }

        }

        private fun handleStartPlaybackByServiceListEntryMessage(msg: Message) {
            val bundle: Bundle = msg.data
            bundle.classLoader = ServiceListEntry::class.java.classLoader
            val serviceListEntry: ServiceListEntry? = bundle.getParcelable("serviceListEntry")
            val responseMessenger: Messenger = msg.replyTo
            val sendingUid = msg.sendingUid;

            finalizeCurrentPlaybackSession(sendingUid)

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
                        val playbackConsumptionReportingConfiguration =
                            PlaybackConsumptionReportingConfiguration()
                        if (resource?.clientConsumptionReportingConfiguration != null) {
                            playbackConsumptionReportingConfiguration.accessReporting =
                                resource.clientConsumptionReportingConfiguration!!.accessReporting
                            playbackConsumptionReportingConfiguration.locationReporting =
                                resource.clientConsumptionReportingConfiguration!!.locationReporting
                        }
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

                override fun onFailure(call: Call<ServiceAccessInformation?>, t: Throwable) {
                    Log.i(TAG, "debug onFailure")
                    call.cancel()
                }
            })
        }

        private fun finalizeCurrentPlaybackSession(clientId: Int) {
            if (clientsSessionData[clientId] != null) {
                Log.i(TAG, "Resetting information for client $clientId")
                clientsSessionData[clientId]?.serviceAccessInformation = null
                clientsSessionData[clientId]?.serviceAccessInformationRequestTimer?.cancel()
                clientsSessionData[clientId]?.serviceAccessInformationRequestTimer = null
                clientsSessionData[clientId]?.serviceAccessInformationResponseHeaders = null
                clientsSessionData[clientId]?.initializedSession = false
                stopConsumptionReportingTimer(clientId)
            }
        }

        /**
         * Starts the timer task to re-request and saves the current state of the Service Access Information
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
            if (resource != null && response.code() != 304 && utils.hasResponseChanged(
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

        private fun setConsumptionReportingEndpoint(clientId: Int) {
            val clientConsumptionReportingConfiguration: ClientConsumptionReportingConfiguration? =
                clientsSessionData[clientId]?.serviceAccessInformation?.clientConsumptionReportingConfiguration
            // Select one of the servers to report the metrics to
            var serverAddress =
                clientConsumptionReportingConfiguration?.serverAddresses?.random()
            // Add a "/" in the end if not present
            serverAddress = serverAddress?.let { utils.addTrailingSlashIfNeeded(it) }
            val retrofit = serverAddress?.let { retrofitBuilder.baseUrl(it).build() }
            if (retrofit != null) {
                clientsSessionData[clientId]?.consumptionReportingApi =
                    retrofit.create(ConsumptionReportingApi::class.java)
            }
        }

        private fun initializeConsumptionReportingTimer(clientId: Int, delay: Long? = null) {
            val clientConsumptionReportingConfiguration: ClientConsumptionReportingConfiguration? =
                clientsSessionData[clientId]?.serviceAccessInformation?.clientConsumptionReportingConfiguration

            if (clientConsumptionReportingConfiguration?.reportingInterval != null &&
                clientConsumptionReportingConfiguration.reportingInterval!! > 0 &&
                shouldReportAccordingToSamplePercentage(clientConsumptionReportingConfiguration.samplePercentage)
            ) {
                startConsumptionReportingTimer(clientId, delay)
            }
        }

    }

    private fun startConsumptionReportingTimer(
        clientId: Int,
        delay: Long? = null
    ) {
        val clientConsumptionReportingConfiguration: ClientConsumptionReportingConfiguration =
            clientsSessionData[clientId]?.serviceAccessInformation?.clientConsumptionReportingConfiguration
                ?: return
        val timer = Timer()
        val reportingInterval =
            clientConsumptionReportingConfiguration.reportingInterval!!.times(1000).toLong()
        var finalDelay = delay
        if (finalDelay == null) {
            finalDelay = reportingInterval
        }
        timer.scheduleAtFixedRate(
            object : TimerTask() {
                override fun run() {
                    requestConsumptionReportFromClient(
                        clientId
                    )
                }
            },
            finalDelay,
            reportingInterval
        )
        clientsSessionData[clientId]?.consumptionReportingTimer = timer
    }

    private fun stopConsumptionReportingTimer(clientId: Int) {
        clientsSessionData[clientId]?.consumptionReportingTimer?.cancel()
        clientsSessionData[clientId]?.consumptionReportingTimer = null
    }

    private fun shouldReportAccordingToSamplePercentage(samplePercentage: Float?): Boolean {
        if (samplePercentage != null && samplePercentage <= 0) {
            return false
        }

        if (samplePercentage == null || samplePercentage >= 100.0) {
            return true
        }

        return utils.generateRandomFloat() < samplePercentage
    }

    private fun requestConsumptionReportFromClient(
        clientId: Int
    ) {
        val msg: Message = Message.obtain(
            null,
            SessionHandlerMessageTypes.GET_CONSUMPTION_REPORT
        )
        val bundle = Bundle()
        val locationReporting =
            clientsSessionData[clientId]?.serviceAccessInformation?.clientConsumptionReportingConfiguration?.locationReporting == true
        val accessReporting =
            clientsSessionData[clientId]?.serviceAccessInformation?.clientConsumptionReportingConfiguration?.accessReporting == true
        val consumptionReportingConfiguration =
            PlaybackConsumptionReportingConfiguration(accessReporting, locationReporting)
        bundle.putParcelable(
            "playbackConsumptionReportingConfiguration",
            consumptionReportingConfiguration
        )
        val messenger = clientsSessionData[clientId]?.messenger
        msg.data = bundle
        msg.replyTo = mMessenger
        try {
            Log.i(
                TAG,
                "Request consumption report for client $clientId"
            )
            messenger?.send(msg)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    private fun handleConsumptionReportMessage(msg: Message) {
        val bundle: Bundle = msg.data
        val consumptionReport: String? =
            bundle.getString("consumptionReport")
        val sendingUid = msg.sendingUid

        if (clientsSessionData[sendingUid]?.serviceAccessInformation?.clientConsumptionReportingConfiguration == null) {
            return
        }

        // Reset the timer once we received a report
        stopConsumptionReportingTimer(sendingUid)
        startConsumptionReportingTimer(sendingUid)

        val clientSessionModel = clientsSessionData[sendingUid]
        val provisioningSessionId =
            clientSessionModel?.serviceAccessInformation?.provisioningSessionId
        val mediaType = MediaType.parse("application/json")
        val requestBody: RequestBody? =
            consumptionReport?.let { RequestBody.create(mediaType, it) }

        if (clientSessionModel?.consumptionReportingApi != null) {
            val call: Call<Void>? =
                clientSessionModel.consumptionReportingApi!!.sendConsumptionReport(
                    provisioningSessionId,
                    requestBody
                )

            call?.enqueue(object : retrofit2.Callback<Void?> {
                override fun onResponse(call: Call<Void?>, response: Response<Void?>) {
                    Log.d(TAG, "Successfully send consumption report")
                }

                override fun onFailure(call: Call<Void?>, t: Throwable) {
                    Log.d(TAG, "Error sending consumption report")
                }
            })
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