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
import com.fivegmag.a5gmsmediasessionhandler.network.IServiceAccessInformationApi
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Headers
import org.greenrobot.eventbus.EventBus
import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Properties
import java.util.TimeZone
import java.util.Timer
import java.util.TimerTask
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

class ServiceAccessInformationController(
    private val clientsSessionData: HashMap<Int, ClientSessionData>,
    private val retrofitBuilder: Retrofit.Builder,
) : IController {

    companion object {
        const val TAG = "5GMS-ServiceAccessInformationController"
    }

    private val utils = Utils()
    private var defaultServiceAccessInformationTimerInterval: Long = 0

    fun initialize(configurationProperties: Properties) {
        defaultServiceAccessInformationTimerInterval =
            configurationProperties.getProperty("defaultServiceAccessInformationTimerInterval")
                .toLong()

    }

    fun setM5Endpoint(msg: Message) {
        val bundle: Bundle = msg.data
        val clientId = msg.sendingUid
        val m5BaseUrl: String? = bundle.getString("m5BaseUrl")
        Log.i(SessionController.TAG, "Setting M5 endpoint to $m5BaseUrl")
        if (m5BaseUrl != null) {
            clientsSessionData[clientId]?.serviceAccessInformationSessionData =
                ServiceAccessInformationSessionData()
            val retrofit = retrofitBuilder
                .baseUrl(m5BaseUrl)
                .build()
            clientsSessionData[msg.sendingUid]?.serviceAccessInformationSessionData?.api =
                retrofit.create(IServiceAccessInformationApi::class.java)
        }
    }

    override fun resetClientSession(clientId: Int) {
        clientsSessionData[clientId]?.let { client ->
            client.serviceAccessInformationSessionData.requestTimer?.cancel()
            client.serviceAccessInformationSessionData = ServiceAccessInformationSessionData(
                api = client.serviceAccessInformationSessionData.api
            )
        }
    }

    fun stopUpdateTimer(
        clientId: Int
    ) {
        clientsSessionData[clientId]?.let { client ->
            val playbackContext = client.serviceAccessInformationSessionData
            playbackContext.requestTimer?.cancel()
            // Invalidate in-flight responses even when the delivery session continues.
            client.serviceAccessInformationSessionData = playbackContext.copy(requestTimer = null)
        }
    }

    suspend fun updateServiceAccessInformation(
        serviceListEntry: ServiceListEntry?,
        clientId: Int,
        mediaDeliverySessionId: String
    ): ServiceAccessInformation? {
        if (clientsSessionData[clientId]?.mediaDeliverySessionId != mediaDeliverySessionId) return null
        val playbackContext = clientsSessionData[clientId]?.serviceAccessInformationSessionData
        val provisioningSessionId: String = serviceListEntry!!.provisioningSessionId
        val call: Call<ServiceAccessInformation>? =
            clientsSessionData[clientId]?.serviceAccessInformationSessionData?.api?.fetchServiceAccessInformation(
                provisioningSessionId,
                null,
                null
            )
        if (call == null) {
            Log.w(TAG, "No SAI API call available; using entry points from the ServiceListEntry")
            return null
        }
        return suspendCancellableCoroutine { continuation ->
            call.enqueue(object : retrofit2.Callback<ServiceAccessInformation?> {

                override fun onResponse(
                    call: Call<ServiceAccessInformation?>,
                    response: Response<ServiceAccessInformation?>
                ) {
                    if (clientsSessionData[clientId]?.mediaDeliverySessionId != mediaDeliverySessionId ||
                        clientsSessionData[clientId]?.serviceAccessInformationSessionData !== playbackContext
                    ) {
                        continuation.resume(null)
                        return
                    }
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
                call.cancel()
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
        val mediaDeliverySessionId = clientsSessionData[sendingUid]?.mediaDeliverySessionId ?: return
        val playbackContext = clientsSessionData[sendingUid]?.serviceAccessInformationSessionData
        // Get MaxAge. If an age header is present, that value shall be subtracted from the value defined in max-age.
        var periodByMaxAgeHeader = getMaxAge(headers)

        val ageHeader = headers.get("Age")
        if (null != ageHeader && -1 != periodByMaxAgeHeader.toInt()) {
            periodByMaxAgeHeader -= ageHeader.toLong()
        }

        val periodByExpiresHeader = getPeriodFromExpiresHeader(headers)
        val periodTimerInSeconds: Long = getRefreshTimerValue(
            periodByMaxAgeHeader,
            periodByExpiresHeader,
            defaultServiceAccessInformationTimerInterval
        )

        val timer = Timer()
        clientsSessionData[sendingUid]?.serviceAccessInformationSessionData?.requestTimer = timer

        timer.schedule(
            object : TimerTask() {
                override fun run() {
                    if (clientsSessionData[sendingUid]?.mediaDeliverySessionId != mediaDeliverySessionId ||
                        clientsSessionData[sendingUid]?.serviceAccessInformationSessionData !== playbackContext
                    ) return
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
                            if (clientsSessionData[sendingUid]?.mediaDeliverySessionId != mediaDeliverySessionId ||
                                clientsSessionData[sendingUid]?.serviceAccessInformationSessionData !== playbackContext
                            ) return
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
            periodTimerInSeconds * 1000
        )
    }

    private fun getRefreshTimerValue(
        periodByMaxAgeHeader: Long,
        periodByExpiresHeader: Long,
        defaultPeriodInSec: Long
    ): Long {
        var periodInSec: Long = defaultPeriodInSec
        if (periodByMaxAgeHeader.toInt() != -1
            && periodByExpiresHeader.toInt() != -1
        ) {
            periodInSec = listOf<Long>(periodByMaxAgeHeader, periodByExpiresHeader).min()
        } else if (periodByMaxAgeHeader.toInt() != -1) {
            periodInSec = periodByMaxAgeHeader
        } else if (periodByExpiresHeader.toInt() != -1) {
            periodInSec = periodByExpiresHeader
        }

        return periodInSec
    }

    private fun getPeriodFromExpiresHeader(headers: Headers): Long {
        var periodByExpiresHeader: Long = -1
        val dateInExpHeader = headers.get("Expires")

        return try {
            if (dateInExpHeader != null) {
                val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH)
                dateFormat.timeZone = TimeZone.getTimeZone("UTC")
                val expHeaderFormattedDate = dateFormat.parse(dateInExpHeader)
                val currentTimeInSeconds = System.currentTimeMillis() / 1000
                val difference = abs(currentTimeInSeconds * 1000 - expHeaderFormattedDate!!.time)
                periodByExpiresHeader = difference / 1000
            }

            periodByExpiresHeader
        } catch (e: Exception) {
            periodByExpiresHeader
        }
    }

    private fun getMaxAge(headers: Headers): Long {
        var periodByMaxAgeHeader: Long = -1
        val cacheControlHeader = headers.get("cache-control")
        if (null != cacheControlHeader) {
            val cacheControlHeaderItems = cacheControlHeader.split(',')
            val maxAgeHeader = cacheControlHeaderItems.filter { it.trim().startsWith("max-age=") }

            if (maxAgeHeader.isNotEmpty()) {
                periodByMaxAgeHeader = maxAgeHeader[0].trim().substring(8).toLong()
            }
        }

        return periodByMaxAgeHeader
    }

    override fun reset() {
        for (clientId in clientsSessionData.keys) {
            resetClientSession(clientId)
        }
    }
}