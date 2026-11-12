package com.fivegmag.a5gmsmediasessionhandler.controller

import android.os.Message
import com.fivegmag.a5gmscommonlibrary.models.EntryPoint
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmscommonlibrary.models.ServiceListEntry
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
import java.util.UUID

class SessionController(
    private val clientsSessionData: HashMap<Int, ClientSessionData>,
) : IController {

    companion object {
        const val TAG = "5GMS-SessionController"
    }

    fun registerClient(msg: Message) {
        val messenger = msg.replyTo
        clientsSessionData[msg.sendingUid] = ClientSessionData(messenger)
    }

    fun unregisterClient(clientId: Int) {
        clientsSessionData.remove(clientId)
    }

    override fun resetClientSession(clientId: Int) {
        resetPlayback(clientId)
        clientsSessionData[clientId]?.mediaDeliverySessionId = null
        clientsSessionData[clientId]?.playbackId = null
    }

    fun resetPlayback(clientId: Int) {
        clientsSessionData[clientId]?.initializedSession = false
    }

    fun startMediaDeliverySession(clientId: Int, newMediaDeliverySession: Boolean): String {
        val client = requireNotNull(clientsSessionData[clientId]) { "Client is not registered: $clientId" }
        if (!newMediaDeliverySession) {
            return requireNotNull(client.mediaDeliverySessionId) {
                "No active media delivery session to continue for client: $clientId"
            }
        }
        return UUID.randomUUID().toString().also { client.mediaDeliverySessionId = it }
    }

    fun getFinalEntryPoints(
        serviceListEntry: ServiceListEntry,
        serviceAccessInformation: ServiceAccessInformation?
    ): ArrayList<EntryPoint>? {
        var finalEntryPoints: ArrayList<EntryPoint>? = serviceListEntry.entryPoints
        if (serviceAccessInformation != null && (finalEntryPoints == null || finalEntryPoints.size == 0)) {
            finalEntryPoints =
                serviceAccessInformation.streamingAccess.entryPoints
        }

        return finalEntryPoints
    }

    override fun reset() {
        for (clientId in clientsSessionData.keys) {
            resetClientSession(clientId)
        }
    }

}