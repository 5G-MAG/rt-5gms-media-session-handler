package com.fivegmag.a5gmsmediasessionhandler.controller

import android.os.Bundle
import android.os.Message
import android.util.Log
import com.fivegmag.a5gmscommonlibrary.helpers.Utils
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmscommonlibrary.models.ServiceListEntry
import com.fivegmag.a5gmsmediasessionhandler.eventbus.ServiceAccessInformationUpdatedEvent
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
import com.fivegmag.a5gmsmediasessionhandler.models.ServiceAccessInformationSessionData
import com.fivegmag.a5gmsmediasessionhandler.network.ServiceAccessInformationApi
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Headers
import org.greenrobot.eventbus.EventBus
import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import java.util.Timer
import java.util.TimerTask
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ServiceAccessInformationController(
    private val clientsSessionData: HashMap<Int, ClientSessionData>,
    private val retrofitBuilder: Retrofit.Builder,
) : Controller {

    companion object {
        const val TAG = "5GMS-ServiceAccessInformationController"
    }

    private val utils = Utils()

    fun setM5Endpoint(msg: Message) {
        val bundle: Bundle = msg.data
        val clientId = msg.sendingUid
        val m5BaseUrl: String? = bundle.getString("m5BaseUrl")
        Log.i(SessionController.TAG, "Setting M5 endpoint to $m5BaseUrl")
        if (m5BaseUrl != null) {
            resetAfterM5Change(clientId)
            val retrofit = retrofitBuilder
                .baseUrl(m5BaseUrl)
                .build()
            clientsSessionData[msg.sendingUid]?.serviceAccessInformationSessionData?.api =
                retrofit.create(ServiceAccessInformationApi::class.java)
        }
    }

    private fun resetAfterM5Change(clientId: Int) {
        resetClientSession(clientId)
        clientsSessionData[clientId]?.serviceAccessInformationSessionData =
            ServiceAccessInformationSessionData()
    }

    override fun resetClientSession(clientId: Int) {
        stopUpdateTimer(clientId)
        clientsSessionData[clientId]?.serviceAccessInformationSessionData?.serviceAccessInformation =
            null
        clientsSessionData[clientId]?.serviceAccessInformationSessionData?.responseHeaders = null
    }

    fun stopUpdateTimer(
        clientId: Int
    ) {
        if (clientsSessionData[clientId]?.serviceAccessInformationSessionData?.requestTimer != null) {
            clientsSessionData[clientId]?.serviceAccessInformationSessionData?.requestTimer?.cancel()
            clientsSessionData[clientId]?.serviceAccessInformationSessionData?.requestTimer = null
        }
    }

    suspend fun updateServiceAccessInformation(
        serviceListEntry: ServiceListEntry?,
        clientId: Int
    ): ServiceAccessInformation? {

        val provisioningSessionId: String = serviceListEntry!!.provisioningSessionId
        val call: Call<ServiceAccessInformation>? =
            clientsSessionData[clientId]?.serviceAccessInformationSessionData?.api?.fetchServiceAccessInformation(
                provisioningSessionId,
                null,
                null
            )
        return suspendCancellableCoroutine { continuation ->
            call?.enqueue(object : retrofit2.Callback<ServiceAccessInformation?> {

                override fun onResponse(
                    call: Call<ServiceAccessInformation?>,
                    response: Response<ServiceAccessInformation?>
                ) {

                    processServiceAccessInformationResponse(
                        response,
                        clientId,
                        provisioningSessionId
                    )


                    continuation.resume(clientsSessionData[clientId]?.serviceAccessInformationSessionData?.serviceAccessInformation)
                }

                override fun onFailure(call: Call<ServiceAccessInformation?>, t: Throwable) {
                    Log.i(TAG, "Failure when requesting ServiceAccessInformation")
                    call.cancel()

                    // Complete the coroutine with an exception
                    continuation.resumeWithException(t)
                }
            })

            // Cancel the network request if the coroutine is cancelled
            continuation.invokeOnCancellation {
                call?.cancel()
                continuation.resume(null)
            }

        }
    }

    private fun processServiceAccessInformationResponse(
        response: Response<ServiceAccessInformation?>,
        clientId: Int,
        provisioningSessionId: String
    ) {
        val headers = response.headers()
        val previousServiceAccessInformation: ServiceAccessInformation? =
            clientsSessionData[clientId]?.serviceAccessInformationSessionData?.serviceAccessInformation

        // Save the ServiceAccessInformation if it has changed
        val resource: ServiceAccessInformation? = response.body()
        if (resource != null && response.code() != 304 && utils.hasResponseChanged(
                headers,
                clientsSessionData[clientId]?.serviceAccessInformationSessionData?.responseHeaders
            )
        ) {
            clientsSessionData[clientId]?.serviceAccessInformationSessionData?.serviceAccessInformation =
                resource

            EventBus.getDefault().post(
                ServiceAccessInformationUpdatedEvent(
                    clientId,
                    previousServiceAccessInformation,
                    clientsSessionData[clientId]?.serviceAccessInformationSessionData?.serviceAccessInformation
                )
            )

        }

        // Start the re-requesting of the Service Access Information according to the max-age header
        startServiceAccessInformationUpdateTimer(
            headers,
            clientId,
            provisioningSessionId
        )

        // Save current headers
        clientsSessionData[clientId]?.serviceAccessInformationSessionData?.responseHeaders = headers
    }

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
        clientsSessionData[sendingUid]?.serviceAccessInformationSessionData?.requestTimer = timer

        timer.schedule(
            object : TimerTask() {
                override fun run() {
                    val call: Call<ServiceAccessInformation>? =
                        clientsSessionData[sendingUid]?.serviceAccessInformationSessionData?.api?.fetchServiceAccessInformation(
                            provisioningSessionId,
                            clientsSessionData[sendingUid]?.serviceAccessInformationSessionData?.responseHeaders?.get(
                                "etag"
                            ),
                            clientsSessionData[sendingUid]?.serviceAccessInformationSessionData?.responseHeaders?.get(
                                "last-modified"
                            )
                        )

                    call?.enqueue(object : retrofit2.Callback<ServiceAccessInformation?> {
                        override fun onResponse(
                            call: Call<ServiceAccessInformation?>,
                            response: Response<ServiceAccessInformation?>
                        ) {

                            processServiceAccessInformationResponse(
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

    override fun reset() {
        for (clientId in clientsSessionData.keys) {
            resetClientSession(clientId)
        }
    }
}