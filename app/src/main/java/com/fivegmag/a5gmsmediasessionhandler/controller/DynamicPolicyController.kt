package com.fivegmag.a5gmsmediasessionhandler.controller

import android.os.Bundle
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerBundleKeys as Keys
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerMessageTypes
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
import com.fivegmag.a5gmsmediasessionhandler.models.bearer
import com.fivegmag.a5gmsmediasessionhandler.network.DynamicPolicyM5Client
import com.fivegmag.a5gmsmediasessionhandler.network.IDynamicPolicyApi
import com.fivegmag.a5gmsmediasessionhandler.service.OutgoingMessageHandler
import com.fivegmag.a5gmsmediasessionhandler.service.toBundle
import com.fivegmag.a5gmsmediasessionhandler.eventbus.ServiceAccessInformationUpdatedEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import retrofit2.Retrofit
import java.net.URI
import java.time.OffsetDateTime

/**
 * The messages of the Dynamic Policy client API (TS 26.510 V19.2.0 clause 11.3) of the Media Session Handler. The work is in
 * DynamicPolicyService; this class finds the service of the media delivery session, runs the M5 operations off the main thread and turns the
 * answers and events into messages.
 */
class DynamicPolicyController(
    private val clientsSessionData: HashMap<Int, ClientSessionData>,
    private val retrofitBuilder: Retrofit.Builder,
    private val outgoingMessageHandler: OutgoingMessageHandler
) : IController {

    companion object {
        const val TAG = "5GMS-DynamicPolicyController"

        /** How long the Media Session Handler waits for the capabilities of the Media Player */
        const val CAPABILITY_TIMEOUT_MS = 1500L
    }

    var endpoints: () -> FlowEndpoints? = { null }

    /** The location of the UE (set by the owner), for the locationReporting of the Service Access Information */
    var ueLocation: () -> com.fivegmag.a5gmscommonlibrary.models.TypedLocation? = { null }

    /** Obtains a new access token for a client (set by the incoming message handler); false when it could not be obtained */
    var renewAccessToken: ((Int) -> Boolean)? = null

    /** Whether the Media Session Handler has client credentials with which a new access token can be requested */
    var hasClientCredentials: () -> Boolean = { false }

    /** The way to a new Authorization value after a 401 invalid_token (RFC 6750 clause 3.1), only with client credentials */
    private fun renewal(clientId: Int, session: ClientSessionData): (() -> String?)? {
        val renew = renewAccessToken ?: return null
        if (!hasClientCredentials()) return null
        return { if (renew(clientId)) bearer(session.accessToken) else null }
    }

    /** The sub-topic of the notification channel of a resource that this client created at M5 */
    var subscribeTopic: (Int, String) -> Unit = { _, _ -> }
    var unsubscribeTopic: (Int, String) -> Unit = { _, _ -> }
    private val scope = CoroutineScope(Dispatchers.IO)
    private val capabilityInterrogation = PlayerCapabilityInterrogation(CAPABILITY_TIMEOUT_MS)

    /**
     * The interrogation of the Media Player of this media delivery session (TS 26.512 V19.3.0 clause 4.7.3): the request goes to the Media Stream
     * Handler of the client; the call blocks until the reply or the timeout, so it is made off the main thread.
     */
    private fun interrogatePlayer(clientId: Int, session: ClientSessionData, mediaDeliverySessionId: String): Set<String> {
        val capabilities = capabilityInterrogation.interrogate("$clientId/$mediaDeliverySessionId") {
            reply(SessionHandlerMessageTypes.GET_PLAYER_CAPABILITIES, mapOf(Keys.MEDIA_DELIVERY_SESSION_IDENTIFIER to mediaDeliverySessionId), session.messenger)
        }
        Log.i(TAG, "capabilities of the Media Player of media delivery session $mediaDeliverySessionId: ${capabilities.ifEmpty { "none (none reported)" }}")
        return capabilities
    }

    /** The reply of the Media Stream Handler to the interrogation: the capabilities of table 13.2.4-2 */
    fun handleGetPlayerCapabilitiesReply(msg: Message) {
        val data: Bundle = msg.data
        val sessionId = data.getString(Keys.MEDIA_DELIVERY_SESSION_IDENTIFIER) ?: return
        capabilityInterrogation.onReply("${ClientKey.of(msg)}/$sessionId", data.getStringArrayList(Keys.CAPABILITIES))
    }

    /** The service of the media delivery session of this client; null when the client has none (createMediaDeliverySession() was not called) */
    fun serviceOf(clientId: Int, mediaDeliverySessionId: String?): DynamicPolicyService? {
        val session = clientsSessionData[clientId] ?: return null
        if (session.generalStatus == null || session.mediaDeliverySessionId != mediaDeliverySessionId) return null
        val held = session.dynamicPolicyService
        if (held != null && held.mediaDeliverySessionId == session.mediaDeliverySessionId) return held
        val created = DynamicPolicyService(
            session.mediaDeliverySessionId,
            { session.serviceAccessInformationSessionData.serviceAccessInformation },
            { sai -> clientFor(sai, session, clientId) },
            { endpoints() },
            { session.domainNames.ifEmpty { listOfNotNull(hostOf(session.generalStatus?.mediaEntryPointUrl)) } },
            { OffsetDateTime.now() },
            { event -> outgoingMessageHandler.sendNotification(event.name, event.payload.toBundle(), session.messenger) },
            { ueLocation() },
            { interrogatePlayer(clientId, session, session.mediaDeliverySessionId) }
        )
        created.subscribeTopic = { subscribeTopic(clientId, it) }
        created.unsubscribeTopic = { unsubscribeTopic(clientId, it) }
        session.dynamicPolicyService = created
        return created
    }

    private fun clientFor(sai: com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation, session: ClientSessionData, clientId: Int): DynamicPolicyM5Client? {
        // clause 9.3.2: one of the serverAddresses of the dynamicPolicyInvocationConfiguration
        val address = sai.dynamicPolicyInvocationConfiguration?.serverAddresses?.firstOrNull() ?: return null
        val api = retrofitBuilder.baseUrl(if (address.endsWith("/")) address else "$address/").build().create(IDynamicPolicyApi::class.java)
        return DynamicPolicyM5Client(api, bearer(session.accessToken), renewal(clientId, session))
    }

    private fun hostOf(url: String?): String? = try {
        url?.let { URI(it).host }
    } catch (_: Exception) {
        null
    }

    private fun reply(type: Int, payload: Map<String, Any?>, messenger: Messenger?) =
        outgoingMessageHandler.sendMessage(type, payload.toBundle(), messenger)

    private fun unknownSession(type: Int, sessionId: String?, msg: Message, clientId: Int) = reply(
        type,
        mapOf(
            Keys.MEDIA_DELIVERY_SESSION_IDENTIFIER to sessionId,
            Keys.SUCCEEDED to false,
            Keys.ERROR_REASON to "no media delivery session [$sessionId]"
        ),
        msg.replyTo ?: clientsSessionData[clientId]?.messenger
    )

    /** The Service Operation Points of the media delivery session: the policy template bindings of the Service Access Information */
    fun handleGetServiceOperationPoints(msg: Message) {
        val clientId = ClientKey.of(msg)
        val sessionId = msg.data.getString(Keys.MEDIA_DELIVERY_SESSION_IDENTIFIER)
        val service = serviceOf(clientId, sessionId)
        val replyTo = msg.replyTo ?: clientsSessionData[clientId]?.messenger
        if (service == null) return unknownSession(SessionHandlerMessageTypes.GET_SERVICE_OPERATION_POINTS_REPLY, sessionId, msg, clientId)
        reply(
            SessionHandlerMessageTypes.GET_SERVICE_OPERATION_POINTS_REPLY,
            mapOf(
                Keys.MEDIA_DELIVERY_SESSION_IDENTIFIER to sessionId,
                Keys.SUCCEEDED to true,
                Keys.SERVICE_OPERATION_POINTS to ArrayList(service.serviceOperationPoints())
            ),
            replyTo
        )
    }

    /** getBDTInfo() (table 11.3.1.1-1) */
    fun handleGetBdtInfo(msg: Message) {
        val clientId = ClientKey.of(msg)
        val data: Bundle = msg.data
        val sessionId = data.getString(Keys.MEDIA_DELIVERY_SESSION_IDENTIFIER)
        val reference = data.getString(Keys.SERVICE_OPERATION_POINT_REFERENCE) ?: ""
        val replyTo = msg.replyTo ?: clientsSessionData[clientId]?.messenger
        val service = serviceOf(clientId, sessionId) ?: return unknownSession(SessionHandlerMessageTypes.GET_BDT_INFO_REPLY, sessionId, msg, clientId)
        scope.launch {
            reply(SessionHandlerMessageTypes.GET_BDT_INFO_REPLY, service.bdtInfoReply(reference, service.getBdtInfo(reference)), replyTo)
        }
    }

    /** activatePolicy() (table 11.3.1.2-1) */
    fun handleActivatePolicy(msg: Message) {
        val clientId = ClientKey.of(msg)
        val data: Bundle = msg.data
        val sessionId = data.getString(Keys.MEDIA_DELIVERY_SESSION_IDENTIFIER)
        val reference = data.getString(Keys.SERVICE_OPERATION_POINT_REFERENCE) ?: ""
        val volume = if (data.containsKey(Keys.ESTIMATED_TRANSFER_VOLUME)) data.getLong(Keys.ESTIMATED_TRANSFER_VOLUME) else null
        val component = data.getString(Keys.COMPONENT_REFERENCE)
        val downlink = data.getString(Keys.TARGET_DOWNLINK_BIT_RATE)
        val uplink = data.getString(Keys.TARGET_UPLINK_BIT_RATE)
        val replyTo = msg.replyTo ?: clientsSessionData[clientId]?.messenger
        val service = serviceOf(clientId, sessionId) ?: return unknownSession(SessionHandlerMessageTypes.ACTIVATE_POLICY_REPLY, sessionId, msg, clientId)
        scope.launch {
            val result = service.activate(reference, volume, component, downlink, uplink)
            reply(SessionHandlerMessageTypes.ACTIVATE_POLICY_REPLY, service.activationReply(reference, result), replyTo)
        }
    }

    /**
     * OPERATION_POINT_CHANGED (TS 26.510 V19.2.0 clause 5.4.3): the Media Access Function names the new Service Operation Point and its target QoS;
     * a Dynamic Policy is instantiated for it unless the one in force satisfies it. The Media Session Handler takes these notifications from every
     * client, which is what the subscription of clause 5.4.3 comes to here. There is no reply: the events of the activation tell the outcome.
     */
    fun handleOperationPointChanged(msg: Message) {
        val clientId = ClientKey.of(msg)
        val data: Bundle = msg.data
        val sessionId = data.getString(Keys.MEDIA_DELIVERY_SESSION_IDENTIFIER)
        val reference = data.getString(Keys.SERVICE_OPERATION_POINT_REFERENCE) ?: return
        val downlink = data.getString(Keys.TARGET_DOWNLINK_BIT_RATE)
        val uplink = data.getString(Keys.TARGET_UPLINK_BIT_RATE)
        val service = serviceOf(clientId, sessionId) ?: run {
            Log.w(TAG, "OPERATION_POINT_CHANGED for [$sessionId]: no media delivery session")
            return
        }
        scope.launch {
            val result = service.onOperationPointChanged(reference, downlink, uplink)
            Log.i(TAG, "OPERATION_POINT_CHANGED [$reference] of $sessionId: " + if (result != null) "a Dynamic Policy was instantiated" else "nothing instantiated, ${service.lastFailure}")
        }
    }

    /** deactivatePolicy() (table 11.3.1.3-1) */
    fun handleDeactivatePolicy(msg: Message) {
        val clientId = ClientKey.of(msg)
        val sessionId = msg.data.getString(Keys.MEDIA_DELIVERY_SESSION_IDENTIFIER)
        val replyTo = msg.replyTo ?: clientsSessionData[clientId]?.messenger
        val service = serviceOf(clientId, sessionId) ?: return unknownSession(SessionHandlerMessageTypes.DEACTIVATE_POLICY_REPLY, sessionId, msg, clientId)
        scope.launch {
            reply(SessionHandlerMessageTypes.DEACTIVATE_POLICY_REPLY, service.deactivationReply(service.deactivate()), replyTo)
        }
    }

    /** The status currentDynamicPolicies (table 11.3.2-1); the status is absent when no policy is in force */
    fun handleGetDynamicPolicyStatus(msg: Message) {
        val clientId = ClientKey.of(msg)
        val sessionId = msg.data.getString(Keys.MEDIA_DELIVERY_SESSION_IDENTIFIER)
        val replyTo = msg.replyTo ?: clientsSessionData[clientId]?.messenger
        val service = serviceOf(clientId, sessionId) ?: return unknownSession(SessionHandlerMessageTypes.GET_DYNAMIC_POLICY_STATUS_REPLY, sessionId, msg, clientId)
        // the service is locked while an M5 operation runs: not on the main thread
        scope.launch {
            reply(
                SessionHandlerMessageTypes.GET_DYNAMIC_POLICY_STATUS_REPLY,
                mapOf(Keys.MEDIA_DELIVERY_SESSION_IDENTIFIER to sessionId, Keys.SUCCEEDED to true, Keys.CURRENT_DYNAMIC_POLICIES to service.status()),
                replyTo
            )
        }
    }

    /** A notification of the sub-topic of a Dynamic Policy Instance (clause 5.3.3.2); the service is locked while an M5 operation runs, so not on the main thread */
    fun handleNotification(clientId: Int, notified: com.fivegmag.a5gmscommonlibrary.models.DynamicPolicy, reason: String) {
        val service = clientsSessionData[clientId]?.dynamicPolicyService ?: return
        scope.launch { service.onNotification(notified, reason) }
    }

    /** An update of the Service Access Information may offer a new Background Data Transfer window (table 11.3.2-2) */
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    fun handleServiceAccessInformationChanges(event: ServiceAccessInformationUpdatedEvent) {
        val updated = event.updatedServiceAccessInformation ?: return
        val service = clientsSessionData[event.clientId]?.dynamicPolicyService ?: serviceOfActiveSession(event.clientId) ?: return
        service.onServiceAccessInformationUpdated(event.previousServiceAccessInformation, updated)
    }

    private fun serviceOfActiveSession(clientId: Int): DynamicPolicyService? = serviceOf(clientId, clientsSessionData[clientId]?.mediaDeliverySessionId)

    fun initialize() {
        EventBus.getDefault().register(this)
    }

    /** The media delivery session ends: the policy is destroyed at the Media AF (clause 5.3.3.5) */
    override fun resetClientSession(clientId: Int) {
        val session = clientsSessionData[clientId] ?: return
        val service = session.dynamicPolicyService ?: return
        session.dynamicPolicyService = null
        scope.launch { service.release() }
    }

    override fun reset() {
        for (clientId in clientsSessionData.keys.toList()) resetClientSession(clientId)
    }
}
