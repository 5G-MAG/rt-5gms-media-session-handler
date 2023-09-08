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
import com.fivegmag.a5gmscommonlibrary.models.ConsumptionReporting
import com.fivegmag.a5gmscommonlibrary.models.ServiceListEntry
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionModel
import com.fivegmag.a5gmsmediasessionhandler.network.HeaderInterceptor
import com.fivegmag.a5gmsmediasessionhandler.network.ServiceAccessInformationApi
import com.fivegmag.a5gmsmediasessionhandler.network.ConsumptionReportingApi

import okhttp3.Headers
import okhttp3.ResponseBody
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Timer
import java.util.TimerTask

import kotlin.math.abs
import kotlin.random.Random


const val TAG = "5GMS Media Session Handler"
const val SamplePercentageMax: Float    = 100.0F;
const val EPSILON: Float                = 0.0001F;

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
                SessionHandlerMessageTypes.CONSUMPTION_REPORTING_MESSAGE -> reportConsumption(msg)
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

                    // create Retrofit for ConsumptionReporting
                    createRetrofitForConsumpReport(sendingUid)

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
                    Log.i(TAG, "debug onFailure")
                    call.cancel()
                }
            })
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

    /** If the consumption reporting procedure is activated. Refer to TS26.512 Clause 4.7.4
     * When the clientConsumptionReportingConfiguration.samplePercentage value is 100, the Media Session Handler shall activate the consumption reporting procedure.
     * If the samplePercentage is less than 100, the Media Session Handler shall generate a random number which is uniformly distributed in the range of 0 to 100,
     * and the Media Session Handler shall activate the consumption report procedure when the generated random number is of a lower value than the samplePercentage value.
     */
    private fun isConsumptionReportingActivated(clientId: Int): Boolean {
        //if(currentServiceAccessInformation.isInitialized)
        if(clientsSessionData[clientId]?.serviceAccessInformation == null) {
            Log.i(TAG, "[ConsumptionReporting] IsConsumptionReportingActivated: ServiceAccessInformation is 【NULL】")
            return false
        }

        // validate samplePercentage in ServiceAccessInformation.clientConsumptionReportingConfiguration
        var samplePercentage: Float =  clientsSessionData[clientId]?.serviceAccessInformation!!.clientConsumptionReportingConfiguration.samplePercentage;
        if(samplePercentage > SamplePercentageMax || samplePercentage < 0)
        {
            Log.i(TAG, "[ConsumptionReporting] Invaild samplePercentage[$samplePercentage] in ServiceAccessInformation.clientConsumptionReportingConfiguration")
            return false;
        }

        // if samplePercentage == 100, always report
        if(abs(SamplePercentageMax - samplePercentage) < EPSILON)
        {
            Log.i(TAG, "[ConsumptionReporting] SamplePercentage==SamplePercentageMax, always report")
            return true;
        }

        val randomFloat:Float     = Random.nextFloat()
        val randomInt:Int         = Random.nextInt(0, SamplePercentageMax.toInt())
        val randomValue:Float     = randomInt - 1 + randomFloat
        Log.i(TAG, "[ConsumptionReporting] isConsumptionReportingActivated:myRandomValue[$randomValue],samplePercentage[$samplePercentage]")

        return randomValue < samplePercentage
    }

    /** Refer to TS26.512 Clause 4.7.4 TS26.501 Clause 5.6.3
        If the consumption reporting procedure is activated, the Media Session Handler shall submit a consumption report to the 5GMSd AF
        when any of the 5 conditions occur
     */
    private fun isNeedReportConsumption(clientId: Int): Boolean {
        // check IsConsumptionReportingActivated
        if (!isConsumptionReportingActivated(clientId))
        {
            Log.i(TAG, "[ConsumptionReporting] IsConsumptionReportingActivated is 【FALSE】")
            return false;
        }

        // Condition 1/2: Start/stop of consumption of a downlink streaming session
        // In Media Stream handler, when condition 1/2 occur, reportConsumption
        //// to check, need return

        // Condition 3: check clientConsumptionReportingConfiguration.reportingInterval, timer trigger
        // In Media Stream handler, reportConsumptionTimer()
        //// to check, need return

        // Condition 4/5:check clientConsumptionReportingConfiguration.locationReporting and clientConsumptionReportingConfiguration.accessReporting
        if(clientsSessionData[clientId]?.serviceAccessInformation!!.clientConsumptionReportingConfiguration.locationReporting
            || clientsSessionData[clientId]?.serviceAccessInformation!!.clientConsumptionReportingConfiguration.accessReporting)
        {
             return true;
        }

        return false;
    }

    private fun reportConsumption(msg: Message) {
        val sendingUid = msg.sendingUid;
        if (!isNeedReportConsumption(sendingUid))
        {
            Log.i(TAG, "[ConsumptionReporting] Not need ReportConsumption")
            return
        }

        val bundle: Bundle = msg.data
        bundle.classLoader = ConsumptionReporting::class.java.classLoader
        val dataReporting: ConsumptionReporting? = bundle.getParcelable("consumptionData")

        Log.i(TAG, "[ConsumptionReporting] reportConsumption ClientId: ${dataReporting?.reportingClientId}.")
        Toast.makeText(
            applicationContext,
            "MSH recv Consumption-ID: ${dataReporting?.reportingClientId}",
            Toast.LENGTH_LONG
        ).show()


        // call m5 report consumption to AF - TS26.512 Clause 4.7.4
        val consumptionReportingApi: ConsumptionReportingApi? = clientsSessionData[sendingUid]?.consumptionReportingApi
        if(consumptionReportingApi == null)
        {
            Log.i(TAG, "[ConsumptionReporting] consumptionReportingApi is 【NULL】")
            return;
        }

        val aspId: String = "2";
        val call: Call<ResponseBody>? = consumptionReportingApi.postConsumptionReporting(aspId,dataReporting?.reportingClientId);

        call?.enqueue(object : retrofit2.Callback<ResponseBody> {
            override fun onResponse(
                call: Call<ResponseBody?>,
                response: Response<ResponseBody?>
            ) {
                Log.i(TAG, "[ConsumptionReporting] resp from AF:"+ response.body()?.string())
            }

            override fun onFailure(call: Call<ResponseBody?>, t: Throwable) {
                Log.i(TAG, "[ConsumptionReporting] onFailure")
                call.cancel()
            }
        })
    }
    
    private fun createRetrofitForConsumpReport(clientId: Int) {
        try {
            var consumpReportUrl: String = "";
            if(clientsSessionData[clientId]?.serviceAccessInformation!!.clientConsumptionReportingConfiguration.serverAddresses.isNotEmpty())
            {
                consumpReportUrl = clientsSessionData[clientId]?.serviceAccessInformation!!.clientConsumptionReportingConfiguration.serverAddresses[0];
            }
            Log.i(TAG, "[ConsumptionReporting] createRetrofitForConsumpReport URL: $consumpReportUrl.")

            if (consumpReportUrl != "") {
                val retrofit = retrofitBuilder
                    .baseUrl(consumpReportUrl)
                    .build()

                clientsSessionData[clientId]?.consumptionReportingApi = retrofit.create(ConsumptionReportingApi::class.java)
            }
        } catch (e: Exception) {
        }
    }

}