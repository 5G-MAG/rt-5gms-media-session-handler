package com.fivegmag.a5gmsmediasessionhandler.network

import com.fivegmag.a5gmscommonlibrary.models.DynamicPolicy

/** The operations of TS 26.510 V19.2.0 clauses 5.3.3.2 to 5.3.3.5 that the Media Session Handler invokes on the Media AF at reference point M5 */
class DynamicPolicyM5Client(private val api: IDynamicPolicyApi, private val authorization: String?) {
    /** Create (clause 5.3.3.2): 201 with the Dynamic Policy Instance that the Media AF created */
    fun create(provisioningSessionId: String, policy: DynamicPolicy): M5Result<DynamicPolicy> =
        M5Calls.execute(api.create(provisioningSessionId, authorization, M5Calls.body(policy)), DynamicPolicy::class.java)

    /** Retrieve (clause 5.3.3.3): 200 with the current state */
    fun retrieve(provisioningSessionId: String, dynamicPolicyId: String): M5Result<DynamicPolicy> =
        M5Calls.execute(api.retrieve(provisioningSessionId, dynamicPolicyId, authorization), DynamicPolicy::class.java)

    /** Update (clause 5.3.3.4) by replacement: 200 with the new state, or 204 when nothing changed */
    fun replace(provisioningSessionId: String, dynamicPolicyId: String, policy: DynamicPolicy): M5Result<DynamicPolicy> =
        M5Calls.execute(api.replace(provisioningSessionId, dynamicPolicyId, authorization, M5Calls.body(policy)), DynamicPolicy::class.java)

    /** Destroy (clause 5.3.3.5): 204 */
    fun destroy(provisioningSessionId: String, dynamicPolicyId: String): M5Result<Unit> =
        M5Calls.execute(api.destroy(provisioningSessionId, dynamicPolicyId, authorization), null)
}
