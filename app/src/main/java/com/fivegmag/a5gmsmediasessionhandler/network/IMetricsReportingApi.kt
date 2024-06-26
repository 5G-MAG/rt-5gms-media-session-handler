package com.fivegmag.a5gmsmediasessionhandler.network

import com.fivegmag.a5gmscommonlibrary.helpers.ContentTypes
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path

interface IMetricsReportingApi {
    @Headers("Content-Type: ${ContentTypes.XML}")
    @POST("metrics-reporting/{provisioningSessionId}/{metricsReportingConfigurationId}")
    fun sendMetricsReport(
        @Path("provisioningSessionId") provisioningSessionId: String?,
        @Path("metricsReportingConfigurationId") metricsReportingConfigurationId: String?,
        @Body requestBody: RequestBody?
    ): Call<Void>?

}