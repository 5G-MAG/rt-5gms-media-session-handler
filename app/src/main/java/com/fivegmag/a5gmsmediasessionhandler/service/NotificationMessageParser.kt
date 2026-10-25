package com.fivegmag.a5gmsmediasessionhandler.service

import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * A NotificationMessage published by the Media AF on the notification channel (TS 26.510 V19.2.0 clause 10.2.3, table 10.2.3-1). Of
 * the three resources only the Service Access Information is carried here; the others are for the Dynamic Policy and Network Assistance
 * clients.
 */
data class NotificationMessage(
    val type: String?,
    val reason: String?,
    val entityTag: String?,
    val serviceAccessInformation: ServiceAccessInformation?
)

object NotificationMessageParser {
    const val NOTIFICATION_SERVICE_ACCESS_INFORMATION = "NOTIFICATION_SERVICE_ACCESS_INFORMATION"

    /** The message, or null when the payload is not a NotificationMessage with a type and a reason (both are mandatory) */
    fun parse(payload: String): NotificationMessage? {
        return try {
            val message = Gson().fromJson(payload, NotificationMessage::class.java)
            if (message?.type == null || message.reason == null) null else message
        } catch (_: JsonSyntaxException) {
            null
        }
    }

    /** The notification channel URL as the MQTT client takes it: mqtt:// is tcp:// and mqtts:// is ssl:// */
    fun brokerUri(notificationUrl: String): String = when {
        notificationUrl.startsWith("mqtts://") -> "ssl://" + notificationUrl.removePrefix("mqtts://")
        notificationUrl.startsWith("mqtt://") -> "tcp://" + notificationUrl.removePrefix("mqtt://")
        else -> notificationUrl
    }
}
