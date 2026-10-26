package com.fivegmag.a5gmsmediasessionhandler.network

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * The Dynamic Policy API at reference point M5 (TS 26.510 V19.2.0 clause 9.3.2, table 9.3.2-1). The base URL is one of the serverAddresses of the
 * dynamicPolicyInvocationConfiguration of the Service Access Information. PATCH is not used: the Media Session Handler replaces the resource.
 */
interface IDynamicPolicyApi {
    @POST("provisioning-sessions/{provisioningSessionId}/dynamic-policies/")
    fun create(
        @Path("provisioningSessionId") provisioningSessionId: String,
        @Header("Authorization") authorization: String?,
        @Body body: RequestBody
    ): Call<ResponseBody>

    @GET("provisioning-sessions/{provisioningSessionId}/dynamic-policies/{dynamicPolicyId}")
    fun retrieve(
        @Path("provisioningSessionId") provisioningSessionId: String,
        @Path("dynamicPolicyId") dynamicPolicyId: String,
        @Header("Authorization") authorization: String?
    ): Call<ResponseBody>

    @PUT("provisioning-sessions/{provisioningSessionId}/dynamic-policies/{dynamicPolicyId}")
    fun replace(
        @Path("provisioningSessionId") provisioningSessionId: String,
        @Path("dynamicPolicyId") dynamicPolicyId: String,
        @Header("Authorization") authorization: String?,
        @Body body: RequestBody
    ): Call<ResponseBody>

    @DELETE("provisioning-sessions/{provisioningSessionId}/dynamic-policies/{dynamicPolicyId}")
    fun destroy(
        @Path("provisioningSessionId") provisioningSessionId: String,
        @Path("dynamicPolicyId") dynamicPolicyId: String,
        @Header("Authorization") authorization: String?
    ): Call<ResponseBody>
}
