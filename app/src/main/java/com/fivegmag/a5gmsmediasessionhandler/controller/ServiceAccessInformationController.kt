package com.fivegmag.a5gmsmediasessionhandler.controller

import android.util.Log
import com.fivegmag.a5gmscommonlibrary.helpers.Utils
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmscommonlibrary.models.ServiceListEntry
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionModel
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Headers
import retrofit2.Call
import retrofit2.Response
import java.util.Timer
import java.util.TimerTask
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ServiceAccessInformationController(
    private val clientsSessionData: HashMap<Int, ClientSessionModel>,
    private val consumptionReportingController: ConsumptionReportingController,
) {

    companion object {
        const val TAG = "5GMS-ServiceAccessInformationController"
    }

    private val utils = Utils()

    suspend fun fetchServiceAccessInformation(
        serviceListEntry: ServiceListEntry?,
        clientId: Int
    ): ServiceAccessInformation? {

        val provisioningSessionId: String = serviceListEntry!!.provisioningSessionId
        val call: Call<ServiceAccessInformation>? =
            clientsSessionData[clientId]?.serviceAccessInformationApi?.fetchServiceAccessInformation(
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
                    val serviceAccessInformation =
                        processServiceAccessInformationResponse(
                            response,
                            clientId,
                            provisioningSessionId
                        )

                    // Complete the coroutine with the result
                    continuation.resume(serviceAccessInformation)
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
    ): ServiceAccessInformation? {
        val headers = response.headers()
        val previousServiceAccessInformation: ServiceAccessInformation? =
            clientsSessionData[clientId]?.serviceAccessInformation

        // Save the ServiceAccessInformation if it has changed
        val resource: ServiceAccessInformation? = response.body()
        if (resource != null && response.code() != 304 && utils.hasResponseChanged(
                headers,
                clientsSessionData[clientId]?.serviceAccessInformationResponseHeaders
            )
        ) {
            clientsSessionData[clientId]?.serviceAccessInformation = resource

            // handle changes of the SAI compared to the previous one
            if (previousServiceAccessInformation != null) {
                handleServiceAccessInformationChanges(previousServiceAccessInformation, clientId)
            }
        }

        // Start the re-requesting of the Service Access Information according to the max-age header
        startServiceAccessInformationUpdateTimer(
            headers,
            clientId,
            provisioningSessionId
        )

        // Save current headers
        clientsSessionData[clientId]?.serviceAccessInformationResponseHeaders = headers

        return clientsSessionData[clientId]?.serviceAccessInformation
    }

    private fun handleServiceAccessInformationChanges(
        previousServiceAccessInformation: ServiceAccessInformation,
        clientId: Int
    ) {
        consumptionReportingController.handleConfigurationChanges(
            previousServiceAccessInformation.clientConsumptionReportingConfiguration,
            clientsSessionData[clientId]?.serviceAccessInformation?.clientConsumptionReportingConfiguration,
            clientId
        )
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

    fun resetClientSession(clientId: Int) {
        clientsSessionData[clientId]?.serviceAccessInformation = null
        clientsSessionData[clientId]?.serviceAccessInformationRequestTimer?.cancel()
        clientsSessionData[clientId]?.serviceAccessInformationRequestTimer = null
        clientsSessionData[clientId]?.serviceAccessInformationResponseHeaders = null
    }
}