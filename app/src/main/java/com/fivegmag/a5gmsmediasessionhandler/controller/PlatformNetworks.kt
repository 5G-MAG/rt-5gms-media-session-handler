package com.fivegmag.a5gmsmediasessionhandler.controller

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.OutcomeReceiver
import android.telephony.TelephonyManager
import android.telephony.data.NetworkSliceInfo
import android.telephony.data.NetworkSlicingConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** What the platform tells an application about its network slices and data networks */
object PlatformNetworks {
    /**
     * The slices that the UE can use, from its network slicing configuration (Android 13 and later, permission READ_PHONE_STATE);
     * null when the platform does not tell (no support, no permission)
     */
    suspend fun slices(context: Context): List<SliceId>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
        return try {
            suspendCancellableCoroutine { continuation ->
                telephony.getNetworkSlicingConfiguration(context.mainExecutor, object : OutcomeReceiver<NetworkSlicingConfig, TelephonyManager.NetworkSlicingException> {
                    override fun onResult(result: NetworkSlicingConfig) {
                        val usable = result.sliceInfo.filter {
                            it.status == NetworkSliceInfo.SLICE_STATUS_ALLOWED || it.status == NetworkSliceInfo.SLICE_STATUS_DEFAULT_CONFIGURED
                        }
                        if (continuation.isActive) continuation.resume(usable.map {
                            SliceId(it.sliceServiceType, if (it.sliceDifferentiator == NetworkSliceInfo.SLICE_DIFFERENTIATOR_NO_SLICE) null else it.sliceDifferentiator)
                        })
                    }

                    override fun onError(error: TelephonyManager.NetworkSlicingException) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                })
            }
        } catch (_: SecurityException) {
            null
        }
    }

    /** A network with the capability, or null when there is none within the timeout (milliseconds) */
    suspend fun network(context: Context, capability: Int, timeoutMs: Int): Network? {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val request = NetworkRequest.Builder().addCapability(capability).build()
        return try {
            suspendCancellableCoroutine { continuation ->
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (continuation.isActive) continuation.resume(network)
                    }

                    override fun onUnavailable() {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                connectivity.requestNetwork(request, callback, timeoutMs)
                continuation.invokeOnCancellation { connectivity.unregisterNetworkCallback(callback) }
            }
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }
}
