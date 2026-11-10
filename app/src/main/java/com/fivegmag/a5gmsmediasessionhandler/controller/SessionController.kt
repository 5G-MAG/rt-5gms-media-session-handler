package com.fivegmag.a5gmsmediasessionhandler.controller

import android.os.Message
import com.fivegmag.a5gmscommonlibrary.helpers.ContentTypes
import com.fivegmag.a5gmscommonlibrary.helpers.PlayerStates
import com.fivegmag.a5gmscommonlibrary.models.EntryPoint
import com.fivegmag.a5gmscommonlibrary.models.ServiceListEntry
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
import java.util.UUID

class SessionController(
    private val clientsSessionData: HashMap<Int, ClientSessionData>,
) : IController {

    companion object {
        const val TAG = "5GMS-SessionController"

        /**
         * The entry points that the Media Stream Handler can use, in the order in which it will try them (TS 26.512 V19.3.0 clause 4.7.2.1:
         * selection by MIME media type and conformance profiles): an entry point that declares profiles is left out when none of them is
         * in supportedProfiles (an empty set supports all); DASH comes first, then HLS, then the others, each in the order given.
         */
        fun orderEntryPoints(entryPoints: List<EntryPoint>, supportedProfiles: Set<String>): ArrayList<EntryPoint> {
            val usable = entryPoints.filter { entryPoint ->
                val declared = entryPoint.profiles
                supportedProfiles.isEmpty() || declared.isNullOrEmpty() || declared.any { it in supportedProfiles }
            }
            return ArrayList(
                usable.filter { it.contentType == ContentTypes.DASH } +
                    usable.filter { it.contentType == ContentTypes.HLS } +
                    usable.filter { it.contentType != ContentTypes.DASH && it.contentType != ContentTypes.HLS }
            )
        }
    }

    /** The conformance profiles that the device supports (property supportedProfiles of config.properties.xml); empty: all */
    var supportedProfiles: Set<String> = emptySet()

    fun registerClient(msg: Message) {
        val messenger = msg.replyTo
        clientsSessionData[ClientKey.of(msg)] = ClientSessionData(messenger)
    }

    fun unregisterClient(clientId: Int) {
        clientsSessionData.remove(clientId)
    }

    override fun resetClientSession(clientId: Int) {
        clientsSessionData[clientId]?.initializedSession = false
        clientsSessionData[clientId]?.playbackState = PlayerStates.UNKNOWN
        clientsSessionData[clientId]?.mediaDeliverySessionId = UUID.randomUUID().toString()
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
                serviceAccessInformation.streamingAccess?.entryPoints
        }

        return finalEntryPoints?.let { orderEntryPoints(it, supportedProfiles) }
    }

    override fun reset() {
        for (clientId in clientsSessionData.keys) {
            resetClientSession(clientId)
        }
    }

}