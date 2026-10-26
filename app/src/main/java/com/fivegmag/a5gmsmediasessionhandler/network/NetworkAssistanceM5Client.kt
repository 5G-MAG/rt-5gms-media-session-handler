package com.fivegmag.a5gmsmediasessionhandler.network

import com.fivegmag.a5gmscommonlibrary.models.ClientPolicySpecification
import com.fivegmag.a5gmscommonlibrary.models.NetworkAssistanceSession
import com.fivegmag.a5gmscommonlibrary.models.OperationSuccessResponse

/** The operations of TS 26.510 V19.2.0 clauses 5.3.4.2 to 5.3.4.7 that the Media Session Handler invokes on the Media AF at reference point M5 */
class NetworkAssistanceM5Client(private val api: INetworkAssistanceApi, private val authorization: String?) {
    /** Create (clause 5.3.4.2): 201 with the Network Assistance Session that the Media AF created */
    fun create(provisioningSessionId: String, session: NetworkAssistanceSession): M5Result<NetworkAssistanceSession> =
        M5Calls.execute(api.create(provisioningSessionId, authorization, M5Calls.body(session)), NetworkAssistanceSession::class.java)

    /** Retrieve (clause 5.3.4.3): 200 with the current state */
    fun retrieve(provisioningSessionId: String, naSessionId: String): M5Result<NetworkAssistanceSession> =
        M5Calls.execute(api.retrieve(provisioningSessionId, naSessionId, authorization), NetworkAssistanceSession::class.java)

    /** Update (clause 5.3.4.6) by replacement: 200 with the new state, or 204 when nothing changed */
    fun replace(provisioningSessionId: String, naSessionId: String, session: NetworkAssistanceSession): M5Result<NetworkAssistanceSession> =
        M5Calls.execute(api.replace(provisioningSessionId, naSessionId, authorization, M5Calls.body(session)), NetworkAssistanceSession::class.java)

    /** Destroy (clause 5.3.4.7): 204 */
    fun destroy(provisioningSessionId: String, naSessionId: String): M5Result<Unit> =
        M5Calls.execute(api.destroy(provisioningSessionId, naSessionId, authorization), null)

    /** Bit rate recommendation request (clause 5.3.4.4): 200 with a ClientPolicySpecification */
    fun recommendation(provisioningSessionId: String, naSessionId: String): M5Result<ClientPolicySpecification> =
        M5Calls.execute(api.recommendation(provisioningSessionId, naSessionId, authorization), ClientPolicySpecification::class.java)

    /** Delivery boost request (clause 5.3.4.5): 200 with an OperationSuccessResponse */
    fun boostRequest(provisioningSessionId: String, naSessionId: String): M5Result<OperationSuccessResponse> =
        M5Calls.execute(api.boostRequest(provisioningSessionId, naSessionId, authorization), OperationSuccessResponse::class.java)
}
