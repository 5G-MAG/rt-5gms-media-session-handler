package com.fivegmag.a5gmsmediasessionhandler.network

import com.fivegmag.a5gmscommonlibrary.models.DynamicPolicy

/** The operations of TS 26.510 V19.2.0 clauses 5.3.3.2 to 5.3.3.5 that the Media Session Handler invokes on the Media AF at reference point M5 */
class DynamicPolicyM5Client(
    private val api: IDynamicPolicyApi,
    private val authorization: String?,
    /** A new Authorization value after a 401 invalid_token (null when none can be obtained); set only when the client has client credentials */
    private val renew: (() -> String?)? = null
) {
    // the value that worked last: a client that is kept for the media delivery session does not start every call with the expired token
    private var current = authorization

    private fun <T> run(call: (String?) -> M5Result<T>): M5Result<T> =
        M5Calls.withRenewal(current, renew?.let { r -> { r()?.also { current = it } } }, call)

    /** Create (clause 5.3.3.2): 201 with the Dynamic Policy Instance that the Media AF created */
    fun create(provisioningSessionId: String, policy: DynamicPolicy): M5Result<DynamicPolicy> =
        run { auth -> M5Calls.execute(api.create(provisioningSessionId, auth, M5Calls.body(policy)), DynamicPolicy::class.java) }

    /** Retrieve (clause 5.3.3.3): 200 with the current state */
    fun retrieve(provisioningSessionId: String, dynamicPolicyId: String): M5Result<DynamicPolicy> =
        run { auth -> M5Calls.execute(api.retrieve(provisioningSessionId, dynamicPolicyId, auth), DynamicPolicy::class.java) }

    /** Update (clause 5.3.3.4) by replacement: 200 with the new state, or 204 when nothing changed */
    fun replace(provisioningSessionId: String, dynamicPolicyId: String, policy: DynamicPolicy): M5Result<DynamicPolicy> =
        run { auth -> M5Calls.execute(api.replace(provisioningSessionId, dynamicPolicyId, auth, M5Calls.body(policy)), DynamicPolicy::class.java) }

    /** Destroy (clause 5.3.3.5): 204 */
    fun destroy(provisioningSessionId: String, dynamicPolicyId: String): M5Result<Unit> =
        run { auth -> M5Calls.execute(api.destroy(provisioningSessionId, dynamicPolicyId, auth), null) }
}
