package com.fivegmag.a5gmsmediasessionhandler.controller

import android.os.Bundle
import android.os.Message
import android.util.Log
import com.fivegmag.a5gmscommonlibrary.helpers.ContentTypes
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerMessageTypes
import com.fivegmag.a5gmscommonlibrary.models.ClientConsumptionReportingConfiguration
import com.fivegmag.a5gmscommonlibrary.models.PlaybackConsumptionReportingConfiguration
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmsmediasessionhandler.eventbus.ServiceAccessInformationUpdatedEvent
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
import com.fivegmag.a5gmsmediasessionhandler.models.ConsumptionReportingSessionData
import com.fivegmag.a5gmsmediasessionhandler.network.ConsumptionReportingApi
import com.fivegmag.a5gmsmediasessionhandler.service.OutgoingMessageHandler
import okhttp3.MediaType
import okhttp3.RequestBody
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import java.util.Timer
import java.util.TimerTask

class ConsumptionReportingController(
    clientsSessionData: HashMap<Int, ClientSessionData>,
    private val retrofitBuilder: Retrofit.Builder,
    private val outgoingMessageHandler: OutgoingMessageHandler
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
        val clientId = msg.sendingUid

        if (clientsSessionData[clientId]?.serviceAccessInformationSessionData?.serviceAccessInformation?.clientConsumptionReportingConfiguration == null
            || clientsSessionData[clientId]?.consumptionReportingSessionData?.api == null
        ) {
            return
        }

        // Reset the timer once we received a report
        stopReportingTimer(clientId)
        startReportingTimer(clientId, null)

        val clientSessionData = clientsSessionData[clientId]
        val provisioningSessionId =
            clientSessionData?.serviceAccessInformationSessionData?.serviceAccessInformation?.provisioningSessionId
        val mediaType = MediaType.parse(ContentTypes.JSON)
        val requestBody: RequestBody? =
            consumptionReport?.let { RequestBody.create(mediaType, it) }

        if (clientSessionData?.consumptionReportingSessionData?.api != null) {
            val call: Call<Void>? =
                clientSessionData.consumptionReportingSessionData.api!!.sendConsumptionReport(
                    provisioningSessionId,
                    requestBody
                )

            call?.enqueue(object : retrofit2.Callback<Void?> {
                override fun onResponse(call: Call<Void?>, response: Response<Void?>) {
                    handleResponseCode(response, TAG)
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

    private fun setReportingEndpoint(clientId: Int) {
        clientsSessionData[clientId]?.consumptionReportingSessionData?.reportingSelectedServerAddress =
            null
        val clientConsumptionReportingConfiguration: ClientConsumptionReportingConfiguration? =
            clientsSessionData[clientId]?.serviceAccessInformationSessionData?.serviceAccessInformation?.clientConsumptionReportingConfiguration

        val serverAddress = clientConsumptionReportingConfiguration?.let {
            selectReportingEndpoint(
                it.serverAddresses
            )
        }

        val retrofit = serverAddress?.let { retrofitBuilder.baseUrl(it).build() }
        if (retrofit != null) {
            clientsSessionData[clientId]?.consumptionReportingSessionData?.api =
                retrofit.create(ConsumptionReportingApi::class.java)
            clientsSessionData[clientId]?.consumptionReportingSessionData?.reportingSelectedServerAddress =
                serverAddress
        }
    }

    override fun initializeReportingTimer(
        clientId: Int,
        delay: Long?
    ) {
        setReportingEndpoint(clientId)

        // Do not start the consumption reporting timer if we dont have an endpoint
        if (clientsSessionData[clientId]?.consumptionReportingSessionData?.api == null) {
            return
        }

        val clientConsumptionReportingConfiguration: ClientConsumptionReportingConfiguration? =
            clientsSessionData[clientId]?.serviceAccessInformationSessionData?.serviceAccessInformation?.clientConsumptionReportingConfiguration

        if (clientConsumptionReportingConfiguration?.reportingInterval != null &&
            clientConsumptionReportingConfiguration.reportingInterval!! > 0 &&
            shouldReportAccordingToSamplePercentage(clientConsumptionReportingConfiguration.samplePercentage)
        ) {
            startReportingTimer(clientId, delay)
        }
    }

    private fun startReportingTimer(
        clientId: Int,
        delay: Long? = null
    ) {

        // Endpoint not set
        if (clientsSessionData[clientId]?.consumptionReportingSessionData?.api == null) {
            setReportingEndpoint(clientId)
        }

        // Do nothing if the timer is already running
        if (clientsSessionData[clientId]?.consumptionReportingSessionData?.reportingTimer != null) {
            return
        }

        val clientConsumptionReportingConfiguration: ClientConsumptionReportingConfiguration =
            clientsSessionData[clientId]?.serviceAccessInformationSessionData?.serviceAccessInformation?.clientConsumptionReportingConfiguration
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
        clientsSessionData[clientId]?.consumptionReportingSessionData?.reportingTimer = timer
    }

    fun requestConsumptionReportFromClient(
        clientId: Int
    ) {
        val bundle = Bundle()
        val locationReporting =
            clientsSessionData[clientId]?.serviceAccessInformationSessionData?.serviceAccessInformation?.clientConsumptionReportingConfiguration?.locationReporting == true
        val accessReporting =
            clientsSessionData[clientId]?.serviceAccessInformationSessionData?.serviceAccessInformation?.clientConsumptionReportingConfiguration?.accessReporting == true
        val consumptionReportingConfiguration =
            PlaybackConsumptionReportingConfiguration(accessReporting, locationReporting)
        bundle.putParcelable(
            "playbackConsumptionReportingConfiguration",
            consumptionReportingConfiguration
        )
        val messenger = clientsSessionData[clientId]?.messenger
        if (messenger != null) {
            outgoingMessageHandler.sendMessage(
                SessionHandlerMessageTypes.GET_CONSUMPTION_REPORT,
                bundle,
                messenger
            )
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    override fun handleServiceAccessInformationChanges(
        serviceAccessInformationUpdatedEvent: ServiceAccessInformationUpdatedEvent
    ) {
        val previousClientConsumptionReportingConfiguration =
            serviceAccessInformationUpdatedEvent.previousServiceAccessInformation?.clientConsumptionReportingConfiguration
        val updatedClientConsumptionReportingConfiguration =
            serviceAccessInformationUpdatedEvent.updatedServiceAccessInformation?.clientConsumptionReportingConfiguration

        if (previousClientConsumptionReportingConfiguration == null || updatedClientConsumptionReportingConfiguration == null) {
            return
        }

        val clientId = serviceAccessInformationUpdatedEvent.clientId

        // location or access reporting has changed update the representation in the Media Stream Handler
        if (previousClientConsumptionReportingConfiguration.accessReporting != updatedClientConsumptionReportingConfiguration.accessReporting || previousClientConsumptionReportingConfiguration.locationReporting != updatedClientConsumptionReportingConfiguration.locationReporting) {
            updatePlaybackConsumptionReportingConfiguration(
                clientId,
                updatedClientConsumptionReportingConfiguration
            )
        }

        // if the list of endpoints is empty we stop reporting. No need to check anything else
        if (updatedClientConsumptionReportingConfiguration.serverAddresses.isEmpty()) {
            stopReportingTimer(clientId)
            clientsSessionData[clientId]?.consumptionReportingSessionData?.reportingSelectedServerAddress =
                null
            clientsSessionData[clientId]?.consumptionReportingSessionData?.api = null
            return
        }

        // the currently used reporting endpoint has been removed from the list. Pick a new one
        if (!updatedClientConsumptionReportingConfiguration.serverAddresses.contains(
                clientsSessionData[clientId]?.consumptionReportingSessionData?.reportingSelectedServerAddress
            )
        ) {
            setReportingEndpoint(clientId)
        }

        // if sample percentage is set to 0 or no server addresses are available stop consumption reporting
        if (updatedClientConsumptionReportingConfiguration.samplePercentage <= 0) {
            stopReportingTimer(clientId)
        }

        // if sample percentage is set to 100 start consumption reporting
        if (updatedClientConsumptionReportingConfiguration.samplePercentage >= 100) {
            startReportingTimer(clientId)
        }

        // if sample percentage was previously zero and is now higher than zero evaluate again
        if (updatedClientConsumptionReportingConfiguration.samplePercentage > 0 && previousClientConsumptionReportingConfiguration.samplePercentage <= 0 && shouldReportAccordingToSamplePercentage(
                updatedClientConsumptionReportingConfiguration.samplePercentage
            )
        ) {
            startReportingTimer(clientId)
        }

        // updates of the reporting interval are handled automatically when stopping / starting the timer
    }
    private fun updatePlaybackConsumptionReportingConfiguration(
        clientId: Int,
        updatedClientConsumptionReportingConfiguration: ClientConsumptionReportingConfiguration
    ) {
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
        if (messenger != null) {
            outgoingMessageHandler.sendMessage(
                SessionHandlerMessageTypes.UPDATE_PLAYBACK_CONSUMPTION_REPORTING_CONFIGURATION,
                bundle,
                messenger
            )
        }
    }

    override fun resetClientSession(clientId: Int) {
        stopReportingTimer(clientId)
        clientsSessionData[clientId]?.consumptionReportingSessionData =
            ConsumptionReportingSessionData()
    }

    override fun stopReportingTimer(
        clientId: Int
    ) {
        if (clientsSessionData[clientId]?.consumptionReportingSessionData?.reportingTimer != null) {
            clientsSessionData[clientId]?.consumptionReportingSessionData?.reportingTimer?.cancel()
            clientsSessionData[clientId]?.consumptionReportingSessionData?.reportingTimer = null
        }
    }
}