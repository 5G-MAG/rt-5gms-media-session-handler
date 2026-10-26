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
 * The Network Assistance API at reference point M5 (TS 26.510 V19.2.0 clause 9.4.2, table 9.4.2-1). The base URL is one of the serverAddresses of
 * the networkAssistanceConfiguration of the Service Access Information.
 *
 * The delivery boost request is at {naSessionId}/boost-request in table 9.4.2-1 and at {naSessionId}/boost in
 * TS26510_Maf_SessionHandling_NetworkAssistance.yaml; the Media AF takes the path of the table (BOOST_REQUEST_PATH).
 */
interface INetworkAssistanceApi {
    companion object {
        const val BOOST_REQUEST_PATH = "boost-request"
    }

    @POST("provisioning-sessions/{provisioningSessionId}/network-assistance-sessions/")
    fun create(
        @Path("provisioningSessionId") provisioningSessionId: String,
        @Header("Authorization") authorization: String?,
        @Body body: RequestBody
    ): Call<ResponseBody>

    @GET("provisioning-sessions/{provisioningSessionId}/network-assistance-sessions/{naSessionId}")
    fun retrieve(
        @Path("provisioningSessionId") provisioningSessionId: String,
        @Path("naSessionId") naSessionId: String,
        @Header("Authorization") authorization: String?
    ): Call<ResponseBody>

    @PUT("provisioning-sessions/{provisioningSessionId}/network-assistance-sessions/{naSessionId}")
    fun replace(
        @Path("provisioningSessionId") provisioningSessionId: String,
        @Path("naSessionId") naSessionId: String,
        @Header("Authorization") authorization: String?,
        @Body body: RequestBody
    ): Call<ResponseBody>

    @DELETE("provisioning-sessions/{provisioningSessionId}/network-assistance-sessions/{naSessionId}")
    fun destroy(
        @Path("provisioningSessionId") provisioningSessionId: String,
        @Path("naSessionId") naSessionId: String,
        @Header("Authorization") authorization: String?
    ): Call<ResponseBody>

    @GET("provisioning-sessions/{provisioningSessionId}/network-assistance-sessions/{naSessionId}/recommendation")
    fun recommendation(
        @Path("provisioningSessionId") provisioningSessionId: String,
        @Path("naSessionId") naSessionId: String,
        @Header("Authorization") authorization: String?
    ): Call<ResponseBody>

    @POST("provisioning-sessions/{provisioningSessionId}/network-assistance-sessions/{naSessionId}/boost-request")
    fun boostRequest(
        @Path("provisioningSessionId") provisioningSessionId: String,
        @Path("naSessionId") naSessionId: String,
        @Header("Authorization") authorization: String?
    ): Call<ResponseBody>
}
