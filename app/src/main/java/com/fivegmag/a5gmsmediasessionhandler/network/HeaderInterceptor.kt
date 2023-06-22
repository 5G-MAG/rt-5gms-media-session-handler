package com.fivegmag.a5gmsmediasessionhandler.network

import com.fivegmag.a5gmscommonlibrary.helpers.UserAgentTokens
import okhttp3.Interceptor
import okhttp3.Response

class HeaderInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.run {
        proceed(
            request()
                .newBuilder()
                .removeHeader("User-Agent")
                .addHeader("User-Agent", "${UserAgentTokens.FIVE_G_MS_REL_17_MEDIA_SESSION_HANDLER} ${okhttp3.internal.Version.userAgent()}")
                .build()
        )
    }
}