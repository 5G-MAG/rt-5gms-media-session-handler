package com.fivegmag.a5gmsmediasessionhandler.controller

import android.os.Message
import com.fivegmag.a5gmscommonlibrary.models.EntryPoint
import com.fivegmag.a5gmscommonlibrary.models.ServiceListEntry
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData

class SessionController(
    private val clientsSessionData: HashMap<Int, ClientSessionData>,
) : Controller {

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
        clientsSessionData[clientId]?.initializedSession = false
    }

    fun getFinalEntryPoints(
        serviceListEntry: ServiceListEntry,
        clientId: Int
    ): ArrayList<EntryPoint>? {
        var finalEntryPoints: ArrayList<EntryPoint>? = serviceListEntry.entryPoints
        val serviceAccessInformation =
            clientsSessionData[clientId]?.serviceAccessInformationSessionData?.serviceAccessInformation
        if (serviceAccessInformation != null && (finalEntryPoints == null || finalEntryPoints.size == 0)) {
            finalEntryPoints =
                serviceAccessInformation.streamingAccess.entryPoints
        }

        return finalEntryPoints
    }

}