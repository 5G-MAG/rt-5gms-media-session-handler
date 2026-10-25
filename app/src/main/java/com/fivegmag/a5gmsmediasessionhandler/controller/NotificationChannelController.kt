package com.fivegmag.a5gmsmediasessionhandler.controller

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmsmediasessionhandler.service.NotificationMessageParser
import org.eclipse.paho.mqttv5.client.IMqttToken
import org.eclipse.paho.mqttv5.client.MqttAsyncClient
import org.eclipse.paho.mqttv5.client.MqttCallback
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence
import org.eclipse.paho.mqttv5.common.MqttException
import org.eclipse.paho.mqttv5.common.MqttMessage
import org.eclipse.paho.mqttv5.common.packet.MqttProperties

/**
 * The client of the resource update notification channel (TS 26.510 V19.2.0 clause 10.2): if the Service Access Information has a
 * notificationURL, the Media Session Handler connects to the MQTT channel and subscribes to the root topic, which is the external
 * service identifier (clause 5.3.2.3). The Service Access Information update notifications published there are handed to the callback.
 */
class NotificationChannelController(
    private val onServiceAccessInformation: (Int, ServiceAccessInformation) -> Unit
) {
    companion object {
        const val TAG = "5GMS-NotificationChannelController"
    }

    private val clients = HashMap<Int, MqttAsyncClient>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun connect(clientId: Int, notificationUrl: String, externalServiceId: String) {
        disconnect(clientId)
        try {
            val client = MqttAsyncClient(NotificationMessageParser.brokerUri(notificationUrl), "5gms-msh-" + System.nanoTime(), MemoryPersistence())
            client.setCallback(object : MqttCallback {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    // the topic of the common notifications of the Provisioning Session
                    client.subscribe(externalServiceId, 1)
                    Log.i(TAG, "Subscribed to [$externalServiceId] at $serverURI")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val parsed = message?.let { NotificationMessageParser.parse(String(it.payload, Charsets.UTF_8)) }
                    Log.d(TAG, "Notification on [$topic]: " + (parsed?.type ?: "not a NotificationMessage"))
                    if (parsed == null) return
                    val resource = parsed.serviceAccessInformation
                    if (parsed.type == NotificationMessageParser.NOTIFICATION_SERVICE_ACCESS_INFORMATION && resource != null) {
                        mainHandler.post { onServiceAccessInformation(clientId, resource) }
                    }
                }

                override fun disconnected(disconnectResponse: MqttDisconnectResponse?) {}
                override fun mqttErrorOccurred(exception: MqttException?) {
                    Log.w(TAG, "MQTT error: " + exception?.message)
                }

                override fun deliveryComplete(token: IMqttToken?) {}
                override fun authPacketArrived(reasonCode: Int, properties: MqttProperties?) {}
            })
            val options = MqttConnectionOptions()
            options.isAutomaticReconnect = true
            options.isCleanStart = true
            client.connect(options)
            clients[clientId] = client
        } catch (e: MqttException) {
            Log.w(TAG, "Cannot connect to the notification channel [$notificationUrl]: " + e.message)
        }
    }

    fun disconnect(clientId: Int) {
        val client = clients.remove(clientId) ?: return
        try {
            client.disconnectForcibly()
            client.close()
        } catch (_: MqttException) {
        }
    }

    fun reset() {
        for (clientId in clients.keys.toList()) disconnect(clientId)
    }
}
