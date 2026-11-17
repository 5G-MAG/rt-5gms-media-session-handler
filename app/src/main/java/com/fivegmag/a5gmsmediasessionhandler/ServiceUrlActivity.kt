package com.fivegmag.a5gmsmediasessionhandler

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Messenger
import android.util.Log
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerBundleKeys
import com.fivegmag.a5gmsmediasessionhandler.controller.ImplicitLaunch
import com.fivegmag.a5gmsmediasessionhandler.service.MediaSessionHandlerMessengerService

/**
 * The entry of 3GPP Service URLs (TS 26.510 V19.2.0 clause 6.3, TS 26.512 V19.3.0 clause 4.8.3.1): the Media Session Handler is registered as the URL
 * handler for the domain name launch.3gppservices.org and the initial path element /ms by the intent filter of this activity in the manifest.
 * An application requests a Service URL with an ACTION_VIEW intent; the Messenger that it registered goes along as the extra
 * SessionHandlerBundleKeys.LAUNCH_CLIENT_MESSENGER, so that the launch is the one of that client (see IncomingMessageHandler.launchServiceUrlByIntent).
 * The HTTP status for the invoker (clause 4.8.3.4) is the extra SessionHandlerBundleKeys.HTTP_RESPONSE_CODE of the activity result: RESULT_OK below 400.
 * The activity shows nothing.
 */
class ServiceUrlActivity : Activity() {

    private var connection: ServiceConnection? = null
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val serviceUrl = intent?.dataString
        if (serviceUrl == null) {
            answer(ImplicitLaunch.STATUS_BAD_REQUEST)
            return
        }
        val invoker = invokerOf(intent)
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val launchBinder = binder as? MediaSessionHandlerMessengerService.LaunchBinder
                if (launchBinder == null) answer(STATUS_INTERNAL_ERROR)
                else launchBinder.launchServiceUrl(serviceUrl, invoker) { status -> answer(status) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                answer(STATUS_INTERNAL_ERROR)
            }
        }
        connection = serviceConnection
        val bound = bindService(
            Intent(this, MediaSessionHandlerMessengerService::class.java).setAction(MediaSessionHandlerMessengerService.ACTION_LAUNCH_SERVICE_URL),
            serviceConnection,
            BIND_AUTO_CREATE
        )
        if (!bound) answer(STATUS_INTERNAL_ERROR)
    }

    override fun onDestroy() {
        connection?.let { runCatching { unbindService(it) } }
        connection = null
        super.onDestroy()
    }

    private fun answer(status: Int) {
        if (answered) return
        answered = true
        Log.i(TAG, "Service URL intent: HTTP $status")
        setResult(if (status < 400) RESULT_OK else RESULT_CANCELED, Intent().putExtra(SessionHandlerBundleKeys.HTTP_RESPONSE_CODE, status))
        finish()
    }

    private fun invokerOf(intent: Intent): Messenger? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(SessionHandlerBundleKeys.LAUNCH_CLIENT_MESSENGER, Messenger::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(SessionHandlerBundleKeys.LAUNCH_CLIENT_MESSENGER)

    companion object {
        const val TAG = "5GMS-ServiceUrlActivity"

        /** The Media Session Handler could not take the request (the service is not available) */
        const val STATUS_INTERNAL_ERROR = 500
    }
}
