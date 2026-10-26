package com.fivegmag.a5gmsmediasessionhandler.controller

import android.os.Message
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerBundleKeys as Keys
import com.fivegmag.a5gmscommonlibrary.helpers.SessionHandlerMessageTypes
import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import com.fivegmag.a5gmsmediasessionhandler.models.ClientSessionData
import com.fivegmag.a5gmsmediasessionhandler.models.bearer
import com.fivegmag.a5gmsmediasessionhandler.network.INetworkAssistanceApi
import com.fivegmag.a5gmsmediasessionhandler.network.NetworkAssistanceM5Client
import com.fivegmag.a5gmsmediasessionhandler.service.OutgoingMessageHandler
import com.fivegmag.a5gmsmediasessionhandler.service.toBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import java.net.URI

/** The messages of the Network Assistance client API (TS 26.510 V19.2.0 clause 11.4) of the Media Session Handler; the work is in NetworkAssistanceService */
class NetworkAssistanceController(
    private val clientsSessionData: HashMap<Int, ClientSessionData>,
    private val retrofitBuilder: Retrofit.Builder,
    private val outgoingMessageHandler: OutgoingMessageHandler,
    private val dynamicPolicyController: DynamicPolicyController
) : IController {

    var endpoints: () -> FlowEndpoints? = { null }

    /** The sub-topic of the notification channel of a resource that this client created at M5 */
    var subscribeTopic: (Int, String) -> Unit = { _, _ -> }
    var unsubscribeTopic: (Int, String) -> Unit = { _, _ -> }
    private val scope = CoroutineScope(Dispatchers.IO)

    fun serviceOf(clientId: Int, mediaDeliverySessionId: String?): NetworkAssistanceService? {
        val session = clientsSessionData[clientId] ?: return null
        if (session.generalStatus == null || session.mediaDeliverySessionId != mediaDeliverySessionId) return null
        val held = session.networkAssistanceService
        if (held != null && held.mediaDeliverySessionId == session.mediaDeliverySessionId) return held
        val created = NetworkAssistanceService(
            session.mediaDeliverySessionId,
            { session.serviceAccessInformationSessionData.serviceAccessInformation },
            { sai -> clientFor(sai, session) },
            { endpoints() },
            { session.domainNames.ifEmpty { listOfNotNull(hostOf(session.generalStatus?.mediaEntryPointUrl)) } },
            { dynamicPolicyController.serviceOf(clientId, session.mediaDeliverySessionId)?.activePolicyTemplateId },
            { event -> outgoingMessageHandler.sendNotification(event.name, event.payload.toBundle(), session.messenger) }
        )
        created.subscribeTopic = { subscribeTopic(clientId, it) }
        created.unsubscribeTopic = { unsubscribeTopic(clientId, it) }
        session.networkAssistanceService = created
        return created
    }

    private fun clientFor(sai: ServiceAccessInformation, session: ClientSessionData): NetworkAssistanceM5Client? {
        // clause 9.4.2: one of the serverAddresses of the networkAssistanceConfiguration
        val address = sai.networkAssistanceConfiguration?.serverAddresses?.firstOrNull() ?: return null
        val api = retrofitBuilder.baseUrl(if (address.endsWith("/")) address else "$address/").build().create(INetworkAssistanceApi::class.java)
        return NetworkAssistanceM5Client(api, bearer(session.accessToken))
    }

    private fun hostOf(url: String?): String? = try {
        url?.let { URI(it).host }
    } catch (_: Exception) {
        null
    }

    private fun unknownSession(type: Int, sessionId: String?, component: String, msg: Message, clientId: Int) = outgoingMessageHandler.sendMessage(
        type,
        mapOf(
            Keys.MEDIA_DELIVERY_SESSION_IDENTIFIER to sessionId,
            Keys.COMPONENT_REFERENCE to component,
            Keys.SUCCEEDED to false,
            Keys.ERROR_REASON to "no media delivery session [$sessionId]"
        ).toBundle(),
        msg.replyTo ?: clientsSessionData[clientId]?.messenger
    )

    /** requestBitRateRecommendation() (table 11.4.1.1-1) */
    fun handleRequestBitRateRecommendation(msg: Message) {
        val clientId = ClientKey.of(msg)
        val sessionId = msg.data.getString(Keys.MEDIA_DELIVERY_SESSION_IDENTIFIER)
        val component = msg.data.getString(Keys.COMPONENT_REFERENCE) ?: ""
        val replyTo = msg.replyTo ?: clientsSessionData[clientId]?.messenger
        val service = serviceOf(clientId, sessionId)
            ?: return unknownSession(SessionHandlerMessageTypes.REQUEST_BIT_RATE_RECOMMENDATION_REPLY, sessionId, component, msg, clientId)
        scope.launch {
            val recommendation = service.requestBitRateRecommendation(component)
            outgoingMessageHandler.sendMessage(
                SessionHandlerMessageTypes.REQUEST_BIT_RATE_RECOMMENDATION_REPLY, service.recommendationReply(component, recommendation).toBundle(), replyTo
            )
        }
    }

    /** requestDeliveryBoost() (table 11.4.1.2-1) */
    fun handleRequestDeliveryBoost(msg: Message) {
        val clientId = ClientKey.of(msg)
        val sessionId = msg.data.getString(Keys.MEDIA_DELIVERY_SESSION_IDENTIFIER)
        val component = msg.data.getString(Keys.COMPONENT_REFERENCE) ?: ""
        val replyTo = msg.replyTo ?: clientsSessionData[clientId]?.messenger
        val service = serviceOf(clientId, sessionId)
            ?: return unknownSession(SessionHandlerMessageTypes.REQUEST_DELIVERY_BOOST_REPLY, sessionId, component, msg, clientId)
        scope.launch {
            val granted = service.requestDeliveryBoost(component)
            outgoingMessageHandler.sendMessage(
                SessionHandlerMessageTypes.REQUEST_DELIVERY_BOOST_REPLY, service.boostReply(component, granted).toBundle(), replyTo
            )
        }
    }

    /** A notification of the sub-topic of a Network Assistance Session (clause 5.3.4.2); not on the main thread, see DynamicPolicyController.handleNotification */
    fun handleNotification(clientId: Int, notified: com.fivegmag.a5gmscommonlibrary.models.NetworkAssistanceSession) {
        val service = clientsSessionData[clientId]?.networkAssistanceService ?: return
        scope.launch { service.onNotification(notified) }
    }

    /** The media delivery session ends: the Network Assistance Session is destroyed at the Media AF (clause 5.3.4.7) */
    override fun resetClientSession(clientId: Int) {
        val session = clientsSessionData[clientId] ?: return
        val service = session.networkAssistanceService ?: return
        session.networkAssistanceService = null
        scope.launch { service.release() }
    }

    override fun reset() {
        for (clientId in clientsSessionData.keys.toList()) resetClientSession(clientId)
    }
}
