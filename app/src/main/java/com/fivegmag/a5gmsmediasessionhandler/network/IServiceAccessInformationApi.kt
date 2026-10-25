/*
License: 5G-MAG Public License (v1.0)
Author: Daniel Silhavy
Copyright: (C) 2023 Fraunhofer FOKUS
For full license terms please see the LICENSE file distributed with this
program. If this file is missing then the license can be retrieved from
https://drive.google.com/file/d/1cinCiA778IErENZ3JN52VFW-1ffHpx7Z/view
*/

package com.fivegmag.a5gmsmediasessionhandler.network

import com.fivegmag.a5gmscommonlibrary.models.ServiceAccessInformation
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path

interface IServiceAccessInformationApi {
    // TS 26.510 V19.2.0 clause 9.2.2: the Service Access Information is addressed by the externalServiceId
    @GET("service-access-information/{externalServiceId}")
    fun fetchServiceAccessInformation(
        @Path("externalServiceId") externalServiceId: String?,
        @Header("If-None-Match") ifNoneMatch: String?,
        @Header("If-Modified-Since") ifModifiedSince: String?,
        // the access token of the media delivery session as a bearer token (TS 26.510 V19.2.0 clause 7.4.3), absent without one
        @Header("Authorization") authorization: String?
    ): Call<ServiceAccessInformation>?
}