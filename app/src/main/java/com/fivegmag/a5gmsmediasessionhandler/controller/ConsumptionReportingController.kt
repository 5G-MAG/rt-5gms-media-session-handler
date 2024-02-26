package com.fivegmag.a5gmsmediasessionhandler.controller

import android.os.Bundle
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerMessageTypes
import com.fivegmag.a5gmscommonlibrary.models.ClientConsumptionReportingConfiguration
import com.fivegmag.a5gmscommonlibrary.models.PlaybackConsumptionReportingConfiguration
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionModel
import com.fivegmag.a5gmsmediasessionhandler.network.ConsumptionReportingApi
import okhttp3.MediaType
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import java.util.Timer
import java.util.TimerTask

class ConsumptionReportingController(
    clientsSessionData: HashMap<Int, ClientSessionModel>,
    private val retrofitBuilder: Retrofit.Builder,
    private val mMessenger: Messenger
) :
    ReportingController(
        clientsSessionData
    ) {

    companion object {
        const val TAG = "5GMS-ConsumptionReportingController"
    }

    fun handleConsumptionReportMessage(
        msg: Message
    ) {
        val bundle: Bundle = msg.data
        val consumptionReport: String? =
            bundle.getString("consumptionReport")
        val sendingUid = msg.sendingUid

        if (clientsSessionData[sendingUid]?.serviceAccessInformation?.clientConsumptionReportingConfiguration == null || clientsSessionData[sendingUid]?.consumptionReportingApi == null) {
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
                    when (val responseCode = response.code()) {
                        204 -> Log.d(TAG, "Consumption Report: Accepted")
                        400 -> Log.d(TAG, "Consumption Report: Bad Request")
                        415 -> Log.d(TAG, "Consumption Report: Unsupported Media Type")
                        else -> Log.d(TAG, "Consumption Report: Return code $responseCode")
                    }

                }

                override fun onFailure(call: Call<Void?>, t: Throwable) {
                    Log.d(TAG, "Error sending consumption report")
                }
            })
        }
    }

    fun getPlaybackConsumptionReportingConfiguration(serviceAccessInformation: ServiceAccessInformation?): PlaybackConsumptionReportingConfiguration {
        val playbackConsumptionReportingConfiguration = PlaybackConsumptionReportingConfiguration()

        if (serviceAccessInformation?.clientConsumptionReportingConfiguration != null) {
            playbackConsumptionReportingConfiguration.accessReporting =
                serviceAccessInformation.clientConsumptionReportingConfiguration!!.accessReporting
            playbackConsumptionReportingConfiguration.locationReporting =
                serviceAccessInformation.clientConsumptionReportingConfiguration!!.locationReporting
        }

        return playbackConsumptionReportingConfiguration
    }

    private fun setConsumptionReportingEndpoint(clientId: Int) {
        clientsSessionData[clientId]?.consumptionReportingSelectedServerAddress = null
        val clientConsumptionReportingConfiguration: ClientConsumptionReportingConfiguration? =
            clientsSessionData[clientId]?.serviceAccessInformation?.clientConsumptionReportingConfiguration

        // Nothing to choose from
        if (clientConsumptionReportingConfiguration?.serverAddresses?.isEmpty() == true) {
            return
        }

        // Select one of the servers to report the metrics to
        var serverAddress =
            clientConsumptionReportingConfiguration?.serverAddresses?.random()

        // Add a "/" in the end if not present
        serverAddress = serverAddress?.let { utils.addTrailingSlashIfNeeded(it) }
        val retrofit = serverAddress?.let { retrofitBuilder.baseUrl(it).build() }
        if (retrofit != null) {
            clientsSessionData[clientId]?.consumptionReportingApi =
                retrofit.create(ConsumptionReportingApi::class.java)
            clientsSessionData[clientId]?.consumptionReportingSelectedServerAddress =
                serverAddress
        }
    }

    fun initializeReportingTimer(
        clientId: Int,
        delay: Long? = null
    ) {
        setConsumptionReportingEndpoint(clientId)

        // Do not start the consumption reporting timer if we dont have an endpoint
        if (clientsSessionData[clientId]?.consumptionReportingApi == null) {
            return
        }

        val clientConsumptionReportingConfiguration: ClientConsumptionReportingConfiguration? =
            clientsSessionData[clientId]?.serviceAccessInformation?.clientConsumptionReportingConfiguration

        if (clientConsumptionReportingConfiguration?.reportingInterval != null &&
            clientConsumptionReportingConfiguration.reportingInterval!! > 0 &&
            shouldReportAccordingToSamplePercentage(clientConsumptionReportingConfiguration.samplePercentage)
        ) {
            startConsumptionReportingTimer(clientId, delay)
        }
    }

    private fun startConsumptionReportingTimer(
        clientId: Int,
        delay: Long? = null
    ) {

        // Endpoint not set
        if (clientsSessionData[clientId]?.consumptionReportingApi == null) {
            setConsumptionReportingEndpoint(clientId)
        }

        // Do nothing if the timer is already running
        if (clientsSessionData[clientId]?.consumptionReportingTimer != null) {
            return
        }

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

    override fun resetClientSession(clientId: Int) {
        clientsSessionData[clientId]?.consumptionReportingSelectedServerAddress = null
        stopConsumptionReportingTimer(clientId)
    }

    fun stopConsumptionReportingTimer(
        clientId: Int
    ) {
        if (clientsSessionData[clientId]?.consumptionReportingTimer != null) {
            clientsSessionData[clientId]?.consumptionReportingTimer?.cancel()
            clientsSessionData[clientId]?.consumptionReportingTimer = null
        }
    }

    fun requestConsumptionReportFromClient(
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

    /**
     * Once the SAI is updated we need to react to changes in the consumption reporting configuration
     */
    fun handleConfigurationChanges(
        previousClientConsumptionReportingConfiguration: ClientConsumptionReportingConfiguration?,
        updatedClientConsumptionReportingConfiguration: ClientConsumptionReportingConfiguration?,
        clientId: Int
    ) {
        if (previousClientConsumptionReportingConfiguration != null && updatedClientConsumptionReportingConfiguration != null) {

            // location or access reporting has changed update the representation in the Media Stream Handler
            if (previousClientConsumptionReportingConfiguration.accessReporting != updatedClientConsumptionReportingConfiguration.accessReporting || previousClientConsumptionReportingConfiguration.locationReporting != updatedClientConsumptionReportingConfiguration.locationReporting) {
                updatePlaybackConsumptionReportingConfiguration(
                    clientId,
                    updatedClientConsumptionReportingConfiguration
                )
            }

            // if the list of endpoints is empty we stop reporting. No need to check anything else
            if (updatedClientConsumptionReportingConfiguration.serverAddresses.isEmpty()) {
                stopConsumptionReportingTimer(clientId)
                clientsSessionData[clientId]?.consumptionReportingSelectedServerAddress = null
                clientsSessionData[clientId]?.consumptionReportingApi = null
                return
            }

            // the currently used reporting endpoint has been removed from the list. Pick a new one
            if (!updatedClientConsumptionReportingConfiguration.serverAddresses.contains(
                    clientsSessionData[clientId]?.consumptionReportingSelectedServerAddress
                )
            ) {
                setConsumptionReportingEndpoint(clientId)
            }

            // if sample percentage is set to 0 or no server addresses are available stop consumption reporting
            if (updatedClientConsumptionReportingConfiguration.samplePercentage!! <= 0) {
                stopConsumptionReportingTimer(clientId)
            }

            // if sample percentage is set to 100 start consumption reporting
            if (updatedClientConsumptionReportingConfiguration.samplePercentage!! >= 100) {
                startConsumptionReportingTimer(clientId)
            }

            // if sample percentage was previously zero and is now higher than zero evaluate again
            if (updatedClientConsumptionReportingConfiguration.samplePercentage!! > 0 && previousClientConsumptionReportingConfiguration.samplePercentage <= 0 && shouldReportAccordingToSamplePercentage(
                    updatedClientConsumptionReportingConfiguration.samplePercentage
                )
            ) {
                startConsumptionReportingTimer(clientId)
            }

            // updates of the reporting interval are handled automatically when stopping / starting the timer
        }
    }

    private fun updatePlaybackConsumptionReportingConfiguration(
        clientId: Int,
        updatedClientConsumptionReportingConfiguration: ClientConsumptionReportingConfiguration
    ) {
        val msg: Message = Message.obtain(
            null,
            SessionHandlerMessageTypes.UPDATE_PLAYBACK_CONSUMPTION_REPORTING_CONFIGURATION
        )
        val bundle = Bundle()
        val locationReporting =
            updatedClientConsumptionReportingConfiguration.locationReporting
        val accessReporting =
            updatedClientConsumptionReportingConfiguration.accessReporting
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
}