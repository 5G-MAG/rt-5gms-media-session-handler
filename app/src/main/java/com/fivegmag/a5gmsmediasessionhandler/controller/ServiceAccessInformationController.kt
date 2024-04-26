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
import java.util.Date
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
            resetAfterM5Change(clientId)
            val retrofit = retrofitBuilder
                .baseUrl(m5BaseUrl)
                .build()
            clientsSessionData[msg.sendingUid]?.serviceAccessInformationSessionData?.api =
                retrofit.create(IServiceAccessInformationApi::class.java)
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
                val currentDate = dateFormat.format(Date(System.currentTimeMillis()))
                val currentFormattedDate = dateFormat.parse(currentDate)
                val difference = abs(currentFormattedDate!!.time - expHeaderFormattedDate!!.time)
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