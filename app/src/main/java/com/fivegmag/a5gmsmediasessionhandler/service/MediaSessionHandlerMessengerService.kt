package com.fivegmag.a5gmsmediasessionhandler.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Messenger
import com.fivegmag.a5gmscommonlibrary.helpers.Utils
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
import java.util.Properties

/**
 * Create a bound service when you want to interact with the service from activities and other components in your application
 * or to expose some of your application's functionality to other applications through interprocess communication (IPC).
 */
class MediaSessionHandlerMessengerService : Service() {

    companion object {
        const val TAG = "5GMS-MediaSessionHandlerMessengerService"

        /** The action of the binding of ServiceUrlActivity (TS 26.512 V19.3.0 clause 4.8.3.1), which is not a client of the messenger API */
        const val ACTION_LAUNCH_SERVICE_URL = "com.fivegmag.a5gmsmediasessionhandler.LaunchServiceUrl"
    }

    /** What ServiceUrlActivity calls: the launch of a 3GPP Service URL that arrived by intent */
    inner class LaunchBinder : Binder() {
        fun launchServiceUrl(serviceUrl: String, invoker: Messenger?, onStatus: (Int) -> Unit) {
            initialize()
            incomingMessageHandler.launchServiceUrlByIntent(serviceUrl, invoker) { status ->
                // an anonymous invoker's media delivery session outlives the activity and its binding: keep the service until it ends
                if (incomingMessageHandler.hasAnonymousSessions()) startService(Intent(this@MediaSessionHandlerMessengerService, MediaSessionHandlerMessengerService::class.java))
                onStatus(status)
            }
        }
    }

    private val launchBinder = LaunchBinder()
    private var initialized = false

    private lateinit var nativeIncomingMessenger: Messenger
    private val clientsSessionData = HashMap<Int, ClientSessionData>()
    private val outgoingMessageHandler = OutgoingMessageHandler()
    private val incomingMessageHandler =
        IncomingMessageHandler(clientsSessionData, outgoingMessageHandler)


    /**
     * When binding to the service, we return an interface to our messenger
     * for sending messages to the service. To create a bound service, you must define the interface that specifies
     * how a client can communicate with the service. This interface between the service and a client must be an implementation of
     * IBinder and is what your service must return from the onBind() callback method.
     */
    override fun onBind(intent: Intent): IBinder? {
        if (intent.action == ACTION_LAUNCH_SERVICE_URL) return launchBinder
        initialize()
        return nativeIncomingMessenger.binder
    }

    /** The handlers are set up once, whichever of the two bindings comes first */
    private fun initialize() {
        if (initialized) return
        val configurationProperties = loadConfiguration()
        incomingMessageHandler.context = this
        incomingMessageHandler.initialize(configurationProperties)
        incomingMessageHandler.onAnonymousSessionsEnded = { stopSelf() }
        nativeIncomingMessenger = incomingMessageHandler.getIncomingMessenger()
        outgoingMessageHandler.setNativeIncomingMessenger(nativeIncomingMessenger)
        initialized = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onUnbind(intent: Intent?): Boolean {
        // the binding of the service URL activity does not own the state, and sessions of anonymous invokers have no client to unbind
        if (intent?.action != ACTION_LAUNCH_SERVICE_URL && !incomingMessageHandler.hasAnonymousSessions()) reset()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        reset()
        super.onDestroy()
    }

    private fun loadConfiguration() : Properties{
        return Utils().loadConfiguration(this.assets, "config.properties.xml")
    }

    private fun reset() {
        if (!initialized) return
        incomingMessageHandler.reset()
        outgoingMessageHandler.reset()
        clientsSessionData.clear()
        initialized = false
    }
}