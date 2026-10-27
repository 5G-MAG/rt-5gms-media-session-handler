package com.fivegmag.a5gmsmediasessionhandler.controller

import android.util.Log
import android.os.Bundle
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerBundleKeys
import com.fivegmag.a5gmscommonlibrary.helpers.Utils
import com.fivegmag.a5gmsmediasessionhandler.service.OutgoingMessageHandler
import com.fivegmag.a5gmsmediasessionhandler.eventbus.ServiceAccessInformationUpdatedEvent
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
import org.greenrobot.eventbus.EventBus
import retrofit2.Response

abstract class ReportingController(
    val clientsSessionData: HashMap<Int, ClientSessionData>
) : IController {

    val utils = Utils()

    /** How many reports that the Media AF answered with 503 are kept, and how long to wait without a Retry-After header (config.properties.xml) */
    var maxStoredReports = 10
    var retryDefaultSeconds = 30L

    protected abstract val outgoingMessageHandler: OutgoingMessageHandler

    /** Obtains a new access token for a client (set by the incoming message handler); false when it could not be obtained */
    var renewAccessToken: ((Int) -> Boolean)? = null

    /** Whether the Media Session Handler has client credentials with which a new access token can be requested */
    var hasClientCredentials: () -> Boolean = { false }

    /**
     * RFC 6750 clause 3.1: a 401 with error="invalid_token" in WWW-Authenticate. When client credentials exist, a new token is requested on a
     * thread of its own (the response callback runs on the main thread, where a network request is not allowed) and [retry] sends the request
     * again; [onFailed] runs when no token could be obtained. Returns true when the response is handled here.
     */
    protected fun renewAndRetry(clientId: Int, response: Response<*>, retry: () -> Unit, onFailed: () -> Unit): Boolean {
        if (response.code() != 401 || response.headers()["WWW-Authenticate"]?.contains("invalid_token") != true) return false
        val renew = renewAccessToken ?: return false
        if (!hasClientCredentials()) return false
        Thread {
            if (renew(clientId)) retry() else onFailed()
        }.start()
        return true
    }

    /**
     * Send a notification event of TS 26.510 V19.2.0 clause 11.5 or 11.6 to the client of a media delivery session; the payload starts
     * with the media delivery session identifier. Nothing is sent when the client has no media delivery session.
     */
    fun notifyClient(clientId: Int, event: String, addPayload: Bundle.() -> Unit = {}) {
        val session = clientsSessionData[clientId] ?: return
        if (session.generalStatus == null) return
        val payload = Bundle()
        payload.putString(SessionHandlerBundleKeys.MEDIA_DELIVERY_SESSION_IDENTIFIER, session.mediaDeliverySessionId)
        payload.addPayload()
        outgoingMessageHandler.sendNotification(event, payload, session.messenger)
    }

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