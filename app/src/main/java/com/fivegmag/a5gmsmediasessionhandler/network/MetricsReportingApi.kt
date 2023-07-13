package com.fivegmag.a5gmsmediasessionhandler.network

import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path


interface MetricsReportingApi {

    @POST("metrics-reporting/{provisioningSessionId}/{metricsReportingConfigurationId}")
    fun sendMetricsReport(
        @Path("provisioningSessionId") provisioningSessionId: String?,
        @Path("metricsReportingConfigurationId") metricsReportingConfigurationId: String?,
        @Body requestBody: RequestBody?
    ): Call<Void>?

}