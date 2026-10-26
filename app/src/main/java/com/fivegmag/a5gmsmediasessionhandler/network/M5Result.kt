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
    val failure: String? = null
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

    /** Run the call and read the answer; a response with no body, or one that is not JSON, has no resource */
    fun <T> execute(call: Call<ResponseBody>, type: Class<T>?): M5Result<T> {
        return try {
            val response = call.execute()
            val text = (if (response.isSuccessful) response.body() else response.errorBody())?.use { it.string() }
            val location = response.headers()["Location"]
            if (response.isSuccessful) {
                M5Result(response.code(), if (type != null && !text.isNullOrBlank()) parse(text, type) else null, null, location)
            } else {
                M5Result(response.code(), null, if (text.isNullOrBlank()) null else parse(text, ProblemDetails::class.java), location)
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
