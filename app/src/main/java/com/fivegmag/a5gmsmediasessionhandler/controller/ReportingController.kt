package com.fivegmag.a5gmsmediasessionhandler.controller

import android.util.Log
import com.fivegmag.a5gmscommonlibrary.helpers.Utils
import com.fivegmag.a5gmsmediasessionhandler.eventbus.ServiceAccessInformationUpdatedEvent
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
import org.greenrobot.eventbus.EventBus
import retrofit2.Response

abstract class ReportingController(
    val clientsSessionData: HashMap<Int, ClientSessionData>
) : IController {

    val utils = Utils()

    abstract fun handleServiceAccessInformationChanges(
        serviceAccessInformationUpdatedEvent: ServiceAccessInformationUpdatedEvent
    )
    abstract fun stopReportingTimer(clientId: Int)

    abstract fun initializeReportingTimer(clientId: Int, delay: Long?)

    fun initialize() {
        EventBus.getDefault().register(this)
    }

    fun shouldReportAccordingToSamplePercentage(samplePercentage: Float?): Boolean {
        if (samplePercentage != null && samplePercentage <= 0) {
            return false
        }

        if (samplePercentage == null || samplePercentage >= 100.0) {
            return true
        }

        return utils.generateRandomFloat() < samplePercentage
    }


    fun selectReportingEndpoint(serverAddresses: ArrayList<String>): String? {
        if (serverAddresses.isEmpty()) {
            return null
        }

        var serverAddress = serverAddresses.random()

        // Add a "/" in the end if not present
        serverAddress = serverAddress.let { utils.addTrailingSlashIfNeeded(it) }

        return serverAddress
    }

    fun handleResponseCode(response: Response<Void?>, tag: String) {
        when (val responseCode = response.code()) {
            204 -> Log.d(tag, "Accepted Request")
            400 -> Log.d(tag, "Bad Request")
            415 -> Log.d(tag, "Unsupported Media Type")
            else -> Log.d(tag, "Return code $responseCode")
        }
    }


}