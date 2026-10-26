package com.fivegmag.a5gmsmediasessionhandler

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A test double for the Media AF at reference point M5: a local HTTP server that records the requests it receives and answers each with the
 * response that the test has prepared for the method and path. It exists only in the tests.
 */
class MediaAfDouble : AutoCloseable {
    data class Request(val method: String, val path: String, val authorization: String?, val contentType: String?, val body: String)
    data class Response(val code: Int, val body: String? = null, val location: String? = null)

    val requests = CopyOnWriteArrayList<Request>()
    private val responses = HashMap<String, Response>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    /** The base URL of the Service Access Information's serverAddresses form */
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/3gpp-maf-session-handling/v1/"

    fun answer(method: String, pathSuffix: String, response: Response) {
        responses["$method /3gpp-maf-session-handling/v1/$pathSuffix"] = response
    }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        val path = exchange.requestURI.path
        requests.add(
            Request(exchange.requestMethod, path, exchange.requestHeaders.getFirst("Authorization"), exchange.requestHeaders.getFirst("Content-Type"), body)
        )
        val response = responses["${exchange.requestMethod} $path"] ?: Response(404)
        response.location?.let { exchange.responseHeaders.add("Location", it) }
        val bytes = (response.body ?: "").toByteArray()
        if (response.body != null) exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(response.code, if (bytes.isEmpty() || response.code == 204) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty() && response.code != 204) exchange.responseBody.use { it.write(bytes) } else exchange.close()
    }

    override fun close() = server.stop(0)
}
