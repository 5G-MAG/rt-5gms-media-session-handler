package com.fivegmag.a5gmsmediasessionhandler.network

import com.google.gson.JsonParser
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** An access token as the authorization server issues it (RFC 6749 clause 5.1); expiresInSeconds is null when the server gives none */
data class AccessToken(val value: String, val expiresInSeconds: Long?)

/**
 * The client credentials grant (RFC 6749 clause 4.4) that obtains the access token the Media Session Handler presents to the Media AF at
 * reference point M5 (TS 26.510 V19.2.0 clause 7.4.3). The client authenticates with HTTP Basic (RFC 6749 clause 2.3.1).
 */
class OAuthClientCredentials(
    private val tokenUrl: String,
    private val clientId: String,
    private val clientSecret: String,
    private val scope: String?,
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    /** Blocking; throws IOException when the server refuses or answers something that is not an access token */
    fun requestToken(): AccessToken {
        val form = FormBody.Builder().add("grant_type", "client_credentials")
        if (!scope.isNullOrEmpty()) form.add("scope", scope)
        val request = Request.Builder()
            .url(tokenUrl)
            .header("Authorization", Credentials.basic(clientId, clientSecret))
            .post(form.build())
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body()?.string() ?: ""
            if (!response.isSuccessful) throw IOException("The token endpoint answered ${response.code()}: $body")
            try {
                val json = JsonParser().parse(body).asJsonObject
                val token = json["access_token"].asString
                val expiresIn = json["expires_in"]?.takeIf { !it.isJsonNull }?.asLong
                return AccessToken(token, expiresIn)
            } catch (e: RuntimeException) {
                throw IOException("The token endpoint answer is not an access token response: $body")
            }
        }
    }
}
