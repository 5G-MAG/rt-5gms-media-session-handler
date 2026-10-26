package com.fivegmag.a5gmsmediasessionhandler.service

import com.fivegmag.a5gmscommonlibrary.models.DynamicPolicy
import com.fivegmag.a5gmscommonlibrary.models.NetworkAssistanceSession
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * A NotificationMessage published by the Media AF on the notification channel (TS 26.510 V19.2.0 clause 10.2.3, table 10.2.3-1): exactly
 * one of the three resources is present, the one that the type names.
 */
data class NotificationMessage(
    val type: String?,
    val reason: String?,
    val entityTag: String?,
    val serviceAccessInformation: ServiceAccessInformation?,
    val dynamicPolicy: DynamicPolicy? = null,
    val networkAssistanceSession: NetworkAssistanceSession? = null
)

object NotificationMessageParser {
    const val NOTIFICATION_SERVICE_ACCESS_INFORMATION = "NOTIFICATION_SERVICE_ACCESS_INFORMATION"
    // table 10.2.3-2
    const val NOTIFICATION_DYNAMIC_POLICY_INSTANCE = "NOTIFICATION_DYNAMIC_POLICY_INSTANCE"
    const val NOTIFICATION_NETWORK_ASSISTANCE_SESSION = "NOTIFICATION_NETWORK_ASSISTANCE_SESSION"

    // table 10.2.3-3
    const val REASON_BACKGROUND_DATA_TRANSFER_WINDOW_CANCELLATION = "NOTIFICATION_REASON_BACKGROUND_DATA_TRANSFER_WINDOW_CANCELLATION"
    const val REASON_QOS_MONITORING_RESULTS = "NOTIFICATION_REASON_QOS_MONITORING_RESULTS"

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
