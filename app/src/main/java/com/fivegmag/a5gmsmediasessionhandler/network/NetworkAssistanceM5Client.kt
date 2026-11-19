package com.fivegmag.a5gmsmediasessionhandler.network

import com.fivegmag.a5gmscommonlibrary.models.ClientPolicySpecification
import com.fivegmag.a5gmscommonlibrary.models.NetworkAssistanceSession
import com.fivegmag.a5gmscommonlibrary.models.OperationSuccessResponse

/** The operations of TS 26.510 V19.2.0 clauses 5.3.4.2 to 5.3.4.7 that the Media Session Handler invokes on the Media AF at reference point M5 */
class NetworkAssistanceM5Client(
    private val api: INetworkAssistanceApi,
    private val authorization: String?,
    /** A new Authorization value after a 401 invalid_token (null when none can be obtained); set only when the client has client credentials */
    private val renew: (() -> String?)? = null
) {
    // the value that worked last: a client that is kept for the media delivery session does not start every call with the expired token
    private var current = authorization

    private fun <T> run(call: (String?) -> M5Result<T>): M5Result<T> =
        M5Calls.withRenewal(current, renew?.let { r -> { r()?.also { current = it } } }, call)

    // No If-Match on creation. Implementation choice, text silent, see CR-028 (open item O-38): clause 7.1.4.4 names POST, but a client that creates
    // a Network Assistance Session holds no strong entity tag of the target (the tag of the Service Access Information belongs to another resource), so none is sent.
    /** Create (clause 5.3.4.2): 201 with the Network Assistance Session that the Media AF created */
    fun create(provisioningSessionId: String, session: NetworkAssistanceSession): M5Result<NetworkAssistanceSession> =
        run { auth -> M5Calls.execute(api.create(provisioningSessionId, auth, M5Calls.body(session)), NetworkAssistanceSession::class.java) }

    /** Retrieve (clause 5.3.4.3): 200 with the current state */
    fun retrieve(provisioningSessionId: String, naSessionId: String): M5Result<NetworkAssistanceSession> =
        run { auth -> M5Calls.execute(api.retrieve(provisioningSessionId, naSessionId, auth), NetworkAssistanceSession::class.java) }

    /** Update (clause 5.3.4.6) by replacement: 200 with the new state, or 204 when nothing changed; ifMatch is the strong entity tag held for the resource (clause 7.1.4.4) */
    fun replace(provisioningSessionId: String, naSessionId: String, session: NetworkAssistanceSession, ifMatch: String? = null): M5Result<NetworkAssistanceSession> =
        run { auth -> M5Calls.execute(api.replace(provisioningSessionId, naSessionId, auth, ifMatch, M5Calls.body(session)), NetworkAssistanceSession::class.java) }

    /** Destroy (clause 5.3.4.7): 204; ifMatch as for replace */
    fun destroy(provisioningSessionId: String, naSessionId: String, ifMatch: String? = null): M5Result<Unit> =
        run { auth -> M5Calls.execute(api.destroy(provisioningSessionId, naSessionId, auth, ifMatch), null) }

    /** Bit rate recommendation request (clause 5.3.4.4): 200 with a ClientPolicySpecification */
    fun recommendation(provisioningSessionId: String, naSessionId: String): M5Result<ClientPolicySpecification> =
        run { auth -> M5Calls.execute(api.recommendation(provisioningSessionId, naSessionId, auth), ClientPolicySpecification::class.java) }

    /** Delivery boost request (clause 5.3.4.5): 200 with an OperationSuccessResponse */
    fun boostRequest(provisioningSessionId: String, naSessionId: String): M5Result<OperationSuccessResponse> =
        run { auth -> M5Calls.execute(api.boostRequest(provisioningSessionId, naSessionId, auth), OperationSuccessResponse::class.java) }
}
