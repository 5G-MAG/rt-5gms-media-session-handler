package com.fivegmag.a5gmsmediasessionhandler.network

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Call

/** The error message body of TS 26.510 V19.2.0 clause 7.1.7 (ProblemDetails), of which the parts that the client reads */
data class InvalidParam(val param: String? = null, val reason: String? = null)

data class ProblemDetails(
    val title: String? = null,
    val detail: String? = null,
    val status: Int? = null,
    val invalidParams: List<InvalidParam>? = null
)

/**
 * The outcome of one M5 operation: the HTTP status code (0 when there was no response, with the reason in failure), the resource of a 2xx
 * response that has a body, the problem details of any other response and the Location header of a 201 response.
 */
data class M5Result<T>(
    val code: Int,
    val resource: T? = null,
    val problem: ProblemDetails? = null,
    val location: String? = null,
    val failure: String? = null,
    /** A 401 whose WWW-Authenticate header field says error="invalid_token" (RFC 6750 clause 3.1): a new access token may be accepted */
    val invalidToken: Boolean = false
) {
    val isSuccess: Boolean get() = code in 200..299
    val isUnauthorised: Boolean get() = code == 401 || code == 403

    /** Whether the Media AF named this property in the invalidParams of its answer */
    fun complainsAbout(property: String): Boolean = problem?.invalidParams?.any { it.param == property } == true
}

object M5Calls {
    private val gson = Gson()
    private val json = MediaType.parse("application/json")

    fun body(resource: Any): RequestBody = RequestBody.create(json, gson.toJson(resource))

    /**
     * Run [call] with [authorization]; when the Media AF answers 401 invalid_token and [renew] gives a new Authorization value, run it once more
     * with that value (RFC 6750 clause 3.1). Runs on the thread of the caller, which must not be the main thread.
     */
    fun <T> withRenewal(authorization: String?, renew: (() -> String?)?, call: (String?) -> M5Result<T>): M5Result<T> {
        val first = call(authorization)
        if (!first.invalidToken || renew == null) return first
        val renewed = renew() ?: return first
        return call(renewed)
    }

    /** Run the call and read the answer; a response with no body, or one that is not JSON, has no resource */
    fun <T> execute(call: Call<ResponseBody>, type: Class<T>?): M5Result<T> {
        return try {
            val response = call.execute()
            val text = (if (response.isSuccessful) response.body() else response.errorBody())?.use { it.string() }
            val location = response.headers()["Location"]
            val invalidToken = response.code() == 401 && response.headers()["WWW-Authenticate"]?.contains("invalid_token") == true
            if (response.isSuccessful) {
                M5Result(response.code(), if (type != null && !text.isNullOrBlank()) parse(text, type) else null, null, location)
            } else {
                M5Result(response.code(), null, if (text.isNullOrBlank()) null else parse(text, ProblemDetails::class.java), location, null, invalidToken)
            }
        } catch (e: java.io.IOException) {
            M5Result(0, failure = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun <T> parse(text: String, type: Class<T>): T? = try {
        gson.fromJson(text, type)
    } catch (_: JsonSyntaxException) {
        null
    }
}
