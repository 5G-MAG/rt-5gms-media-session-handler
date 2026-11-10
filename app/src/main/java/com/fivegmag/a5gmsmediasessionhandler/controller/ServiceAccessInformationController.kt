package com.fivegmag.a5gmsmediasessionhandler.controller

import android.os.Bundle
import android.os.Message
import android.util.Log
import com.fivegmag.a5gmscommonlibrary.helpers.Utils
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmscommonlibrary.models.ServiceListEntry
import com.fivegmag.a5gmscommonlibrary.models.StreamingAccess
import com.fivegmag.a5gmsmediasessionhandler.eventbus.ServiceAccessInformationUpdatedEvent
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
import com.fivegmag.a5gmsmediasessionhandler.models.bearer
import com.fivegmag.a5gmsmediasessionhandler.models.ServiceAccessInformationCacheKey
import com.fivegmag.a5gmsmediasessionhandler.models.ServiceAccessInformationSessionData
import com.fivegmag.a5gmsmediasessionhandler.network.IServiceAccessInformationApi
import com.fivegmag.a5gmsmediasessionhandler.network.OAuthClientCredentials
import com.google.gson.Gson
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

class ServiceAccessInformationController(
    private val clientsSessionData: HashMap<Int, ClientSessionData>,
    private val retrofitBuilder: Retrofit.Builder,
) : IController {

    companion object {
        const val TAG = "5GMS-ServiceAccessInformationController"
    }

    private val utils = Utils()

    /** Called when an update of the Service Access Information changes the streaming access (TS 26.510 V19.2.0 table 11.2.3-2) */
    var onStreamingAccessUpdated: ((Int, StreamingAccess) -> Unit)? = null
    /** Called when the Media AF refuses the Media Session Handler's access token for want of scope (403 insufficient_scope), with the client identifier */
    var onAccessDenied: ((Int) -> Unit)? = null
    private var defaultServiceAccessInformationTimerInterval: Long = 0

    /**
     * The copy of the Service Access Information as the Media AF sent it (json, so that every media delivery session gets an object of its own),
     * the headers of that response and the time (ms) until which it is fresh
     */
    private data class CachedServiceAccessInformation(val json: String, val headers: Headers, val freshUntilMs: Long)

    // the fresh copies that a new media delivery session reuses (TS 26.510 V19.2.0 clause 5.4.2.1); read by the main and the network threads
    private val cache = HashMap<ServiceAccessInformationCacheKey, CachedServiceAccessInformation>()
    private val gson = Gson()

    /** The time in ms; replaced by the tests */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /** The client credentials of the Media Session Handler (TS 26.510 V19.2.0 clause 7.4.3); null when none are configured */
    var oAuthClient: OAuthClientCredentials? = null

    fun initialize(configurationProperties: Properties) {
        defaultServiceAccessInformationTimerInterval =
            configurationProperties.getProperty("defaultServiceAccessInformationTimerInterval")
                .toLong()
        configurationProperties.getProperty("minServiceAccessInformationTimerInterval")?.toLongOrNull()?.let { minimumRefreshSeconds = it }
        val tokenUrl = configurationProperties.getProperty("oauthTokenUrl")?.trim()
        val clientId = configurationProperties.getProperty("oauthClientId")?.trim()
        if (!tokenUrl.isNullOrEmpty() && !clientId.isNullOrEmpty()) {
            oAuthClient = OAuthClientCredentials(
                tokenUrl,
                clientId,
                configurationProperties.getProperty("oauthClientSecret") ?: "",
                configurationProperties.getProperty("oauthScope")?.trim()
            )
        }
    }

    /** The access token that the application gave stays the first choice; only without one is a token obtained with the client credentials */
    private fun ensureAccessToken(clientId: Int) {
        val session = clientsSessionData[clientId] ?: return
        val client = oAuthClient ?: return
        if (session.accessToken != null) return
        acquireAccessToken(clientId, client)
    }

    fun hasClientCredentials(): Boolean = oAuthClient != null

    /** A new access token with the client credentials, for the other M5 calls of the client; false without client credentials */
    fun renewAccessToken(clientId: Int): Boolean {
        val client = oAuthClient ?: return false
        return acquireAccessToken(clientId, client)
    }

    private fun acquireAccessToken(clientId: Int, client: OAuthClientCredentials): Boolean {
        return try {
            clientsSessionData[clientId]?.accessToken = client.requestToken().value
            true
        } catch (e: Exception) {
            Log.w(TAG, "No access token could be obtained: ${e.message}")
            false
        }
    }

    /** RFC 6750 clause 3.1: 401 with error="invalid_token" in WWW-Authenticate, that a new token can answer when client credentials exist */
    private fun canRenewAfter(response: Response<*>): Boolean =
        oAuthClient != null && response.code() == 401 &&
            response.headers()["WWW-Authenticate"]?.contains("invalid_token") == true

    /** 403 with error="insufficient_scope" in WWW-Authenticate (RFC 6750 clause 3.1) */
    private fun isInsufficientScope(response: Response<*>): Boolean =
        response.code() == 403 && response.headers()["WWW-Authenticate"]?.contains("insufficient_scope") == true

    fun setM5Endpoint(msg: Message) {
        val bundle: Bundle = msg.data
        val clientId = ClientKey.of(msg)
        val m5BaseUrl: String? = bundle.getString("m5BaseUrl")
        if (m5BaseUrl != null) setM5Endpoint(clientId, m5BaseUrl)
    }

    /** The Media AF that the Service Access Information of the client is acquired from; the implicit service launch takes it from the 3GPP Service URL (TS 26.512 V19.3.0 clause 4.8.3.2) */
    fun setM5Endpoint(clientId: Int, m5BaseUrl: String) {
        Log.i(SessionController.TAG, "Setting M5 endpoint to $m5BaseUrl")
        resetAfterM5Change(clientId)
        val retrofit = retrofitBuilder
            .baseUrl(m5BaseUrl)
            .build()
        clientsSessionData[clientId]?.serviceAccessInformationSessionData?.api =
            retrofit.create(IServiceAccessInformationApi::class.java)
        clientsSessionData[clientId]?.serviceAccessInformationSessionData?.m5BaseUrl = m5BaseUrl
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
        clientsSessionData[clientId]?.serviceAccessInformationSessionData?.cacheKey = null
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
        val data = clientsSessionData[clientId]?.serviceAccessInformationSessionData
        val key = ServiceAccessInformationCacheKey(data?.m5BaseUrl, serviceListEntry!!.externalServiceId, clientsSessionData[clientId]?.accessToken)
        data?.cacheKey = key
        ensureAccessToken(clientId)
        // clause 5.4.2.1: a fresh copy that is cached is used; only without one is the Service Access Information acquired from the Media AF
        useFreshCopy(clientId, key)?.let { return it }
        val tokenRejected = BooleanArray(1)
        val result = requestServiceAccessInformation(serviceListEntry, clientId, tokenRejected)
        // the token is no longer valid: one new token (clause 7.4.3 of TS 26.510), one retry
        val client = oAuthClient
        if (tokenRejected[0] && client != null && acquireAccessToken(clientId, client)) {
            return requestServiceAccessInformation(serviceListEntry, clientId, BooleanArray(1))
        }
        return result
    }

    private suspend fun requestServiceAccessInformation(
        serviceListEntry: ServiceListEntry?,
        clientId: Int,
        tokenRejected: BooleanArray
    ): ServiceAccessInformation? {

        val provisioningSessionId: String = serviceListEntry!!.externalServiceId
        val call: Call<ServiceAccessInformation>? =
            clientsSessionData[clientId]?.serviceAccessInformationSessionData?.api?.fetchServiceAccessInformation(
                provisioningSessionId,
                null,
                null,
                bearer(clientsSessionData[clientId]?.accessToken)
            )
        return suspendCancellableCoroutine { continuation ->
            call?.enqueue(object : retrofit2.Callback<ServiceAccessInformation?> {

                override fun onResponse(
                    call: Call<ServiceAccessInformation?>,
                    response: Response<ServiceAccessInformation?>
                ) {
                    if (tokenRejected[0].not() && canRenewAfter(response)) {
                        tokenRejected[0] = true
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
        if (isInsufficientScope(response)) onAccessDenied?.invoke(clientId)
        val previousServiceAccessInformation: ServiceAccessInformation? =
            clientsSessionData[clientId]?.serviceAccessInformationSessionData?.serviceAccessInformation

        // Save the ServiceAccessInformation if it has changed
        val resource: ServiceAccessInformation? = response.body()
        // the copy for the cache is taken before the media delivery session adds its own to the resource (the manifest configurations)
        val received: String? = if (resource != null && response.code() == 200) gson.toJson(resource) else null
        if (resource != null && response.code() != 304 && utils.hasResponseChanged(
                headers,
                clientsSessionData[clientId]?.serviceAccessInformationSessionData?.responseHeaders
            )
        ) {
            adoptServiceAccessInformation(clientId, resource, previousServiceAccessInformation)
        }

        // Start the re-requesting of the Service Access Information according to the max-age header
        startServiceAccessInformationUpdateTimer(
            headers,
            clientId,
            provisioningSessionId
        )

        // Save current headers
        clientsSessionData[clientId]?.serviceAccessInformationSessionData?.responseHeaders = headers

        updateCache(clientsSessionData[clientId]?.serviceAccessInformationSessionData?.cacheKey, response.code(), received, headers)
    }

    /** What the Media AF answered becomes the cached copy (200), stays it for a further time-to-live (304) or is dropped (any other answer) */
    private fun updateCache(key: ServiceAccessInformationCacheKey?, code: Int, received: String?, headers: Headers) {
        if (key == null) return
        synchronized(cache) {
            val now = clock()
            val cached = cache[key]
            if (received != null) {
                cache[key] = CachedServiceAccessInformation(received, headers, now + getFreshnessSeconds(headers, now) * 1000)
            } else if (code == 304) {
                if (cached != null && cached.headers["etag"] == headers["etag"]) {
                    cache[key] = cached.copy(headers = headers, freshUntilMs = now + getFreshnessSeconds(headers, now) * 1000)
                }
            } else {
                cache.remove(key)
            }
        }
    }

    /**
     * The cached copy for the media delivery session when it is still fresh (TS 26.510 V19.2.0 clause 5.4.2.1); null when there is none or when
     * it is not. The session then checks for an update as it would after a fetch, when the time-to-live of the copy has elapsed (clause 7.1.4.3).
     */
    private fun useFreshCopy(clientId: Int, key: ServiceAccessInformationCacheKey): ServiceAccessInformation? {
        val now = clock()
        val cached = synchronized(cache) { cache[key] }?.takeIf { it.freshUntilMs > now } ?: return null
        val data = clientsSessionData[clientId]?.serviceAccessInformationSessionData ?: return null
        adoptServiceAccessInformation(clientId, gson.fromJson(cached.json, ServiceAccessInformation::class.java), data.serviceAccessInformation)
        data.responseHeaders = cached.headers
        startServiceAccessInformationUpdateTimer(
            cached.headers,
            clientId,
            key.externalServiceId,
            remainingSeconds = maxOf(minimumRefreshSeconds, (cached.freshUntilMs - now + 999) / 1000)
        )
        return data.serviceAccessInformation
    }

    private fun adoptServiceAccessInformation(
        clientId: Int,
        resource: ServiceAccessInformation,
        previousServiceAccessInformation: ServiceAccessInformation?
    ) {
        // the configurations of the manifest stay in force for the session (TS 26.512 V19.3.0 clause 4.9.2)
        clientsSessionData[clientId]?.manifestMetricsConfigurations?.let { resource.clientMetricsReportingConfigurations = it }
        clientsSessionData[clientId]?.serviceAccessInformationSessionData?.serviceAccessInformation =
            resource

        val updatedStreamingAccess = resource.streamingAccess
        if (previousServiceAccessInformation != null && updatedStreamingAccess != null &&
            updatedStreamingAccess != previousServiceAccessInformation.streamingAccess
        ) {
            onStreamingAccessUpdated?.invoke(clientId, updatedStreamingAccess)
        }

        EventBus.getDefault().post(
            ServiceAccessInformationUpdatedEvent(
                clientId,
                previousServiceAccessInformation,
                resource
            )
        )
    }

    /**
     * An update of the Service Access Information that the Media AF published on the notification channel (TS 26.510 V19.2.0 clause 10.2);
     * it is taken over when it differs from the one the Media Session Handler holds.
     */
    fun handleNotifiedServiceAccessInformation(clientId: Int, resource: ServiceAccessInformation) {
        val previous = clientsSessionData[clientId]?.serviceAccessInformationSessionData?.serviceAccessInformation
        Log.d(TAG, "Notified Service Access Information for client $clientId: held=${previous != null}, differs=${previous != resource}")
        if (previous != null && previous != resource) {
            // the copy that the Media AF made obsolete is no longer reused by a new media delivery session
            clientsSessionData[clientId]?.serviceAccessInformationSessionData?.cacheKey?.let { synchronized(cache) { cache.remove(it) } }
            adoptServiceAccessInformation(clientId, resource, previous)
        }
    }

    private fun startServiceAccessInformationUpdateTimer(
        headers: Headers,
        sendingUid: Int,
        provisioningSessionId: String,
        immediately: Boolean = false,
        remainingSeconds: Long? = null
    ) {
        val (periodByMaxAgeHeader, periodByExpiresHeader) = headerPeriods(headers, System.currentTimeMillis())
        val periodTimerInSeconds: Long = remainingSeconds ?: getRefreshTimerValue(
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
                            ),
                            bearer(clientsSessionData[sendingUid]?.accessToken)
                        )

                    call?.enqueue(object : retrofit2.Callback<ServiceAccessInformation?> {
                        override fun onResponse(
                            call: Call<ServiceAccessInformation?>,
                            response: Response<ServiceAccessInformation?>
                        ) {
                            val client = oAuthClient
                            if (client != null && canRenewAfter(response)) {
                                // the token has expired: a new one, then the check again at once (the thread keeps the network off the callback)
                                Thread {
                                    if (acquireAccessToken(sendingUid, client)) {
                                        startServiceAccessInformationUpdateTimer(response.headers(), sendingUid, provisioningSessionId, true)
                                    }
                                }.start()
                                return
                            }

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
            if (immediately) 0 else periodTimerInSeconds * 1000
        )
    }

    /** The periods in seconds of the max-age less the Age header field (if one is present, its value shall be subtracted) and of the Expires header field; -1 for one that is absent */
    private fun headerPeriods(headers: Headers, now: Long): Pair<Long, Long> {
        var periodByMaxAgeHeader = getMaxAge(headers)

        val ageHeader = headers.get("Age")
        if (null != ageHeader && -1 != periodByMaxAgeHeader.toInt()) {
            periodByMaxAgeHeader = maxOf(0L, periodByMaxAgeHeader - ageHeader.toLong())
        }

        return Pair(periodByMaxAgeHeader, getPeriodFromExpiresHeader(headers, now))
    }

    /**
     * The seconds for which a response can be used without asking the Media AF again (TS 26.510 V19.2.0 clause 7.1.4.2: the max-age of Cache-Control
     * is the time-to-live of the resource; RFC 9111 clause 4.2): the smaller of the max-age less Age and the time to Expires; the refresh period of
     * config.properties.xml when the response has neither; none for no-cache and no-store
     */
    internal fun getFreshnessSeconds(headers: Headers, now: Long = clock()): Long {
        val directives = headers.get("cache-control")?.split(',')?.map { it.trim().lowercase() } ?: emptyList()
        if (directives.any { it.startsWith("no-cache") || it == "no-store" }) return 0L
        val (periodByMaxAgeHeader, periodByExpiresHeader) = headerPeriods(headers, now)
        return computeRefreshTimerValue(periodByMaxAgeHeader, periodByExpiresHeader, defaultServiceAccessInformationTimerInterval)
    }

    /** The shortest refresh period in seconds (config.properties.xml, default 1): a max-age of 0 would make the check loop without pause */
    var minimumRefreshSeconds: Long = 1L

    /** The refresh period in seconds, not below minimumRefreshSeconds */
    internal fun getRefreshTimerValue(
        periodByMaxAgeHeader: Long,
        periodByExpiresHeader: Long,
        defaultPeriodInSec: Long
    ): Long = maxOf(minimumRefreshSeconds, computeRefreshTimerValue(periodByMaxAgeHeader, periodByExpiresHeader, defaultPeriodInSec))

    private fun computeRefreshTimerValue(
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

    /** The seconds until the Expires header field, 0 once that time has passed or when it is not a valid date (RFC 9111 clause 5.3), -1 without the field */
    fun getPeriodFromExpiresHeader(headers: Headers, now: Long = System.currentTimeMillis()): Long {
        var periodByExpiresHeader: Long = -1
        val dateInExpHeader = headers.get("Expires")

        return try {
            if (dateInExpHeader != null) {
                val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH)
                dateFormat.timeZone = TimeZone.getTimeZone("UTC")
                val expires = dateFormat.parse(dateInExpHeader)!!.time
                periodByExpiresHeader = maxOf(expires - now, 0L) / 1000
            }

            periodByExpiresHeader
        } catch (e: Exception) {
            // an invalid date, especially "0", represents a time in the past (RFC 9111 clause 5.3)
            if (dateInExpHeader != null) 0L else periodByExpiresHeader
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
        synchronized(cache) { cache.clear() }
    }
}