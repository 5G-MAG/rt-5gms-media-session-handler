package com.fivegmag.a5gmsmediasessionhandler.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Messenger
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData

/**
 * Create a bound service when you want to interact with the service from activities and other components in your application
 * or to expose some of your application's functionality to other applications through interprocess communication (IPC).
 */
class MediaSessionHandlerMessengerService : Service() {

    companion object {
        const val TAG = "5GMS-MediaSessionHandlerMessengerService"
    }

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
        incomingMessageHandler.initialize()
        nativeIncomingMessenger = incomingMessageHandler.getIncomingMessenger()
        outgoingMessageHandler.setNativeIncomingMessenger(nativeIncomingMessenger)

        return nativeIncomingMessenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        reset()
        return super.onUnbind(intent)
    }

    fun reset() {
        incomingMessageHandler.reset()
        outgoingMessageHandler.reset()
        clientsSessionData.clear()
    }
}