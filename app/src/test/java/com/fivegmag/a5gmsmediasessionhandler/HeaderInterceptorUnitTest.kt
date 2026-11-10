package com.fivegmag.a5gmsmediasessionhandler

import com.fivegmag.a5gmsmediasessionhandler.network.HeaderInterceptor
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/** TS 26.512 V19.3.0 clause 6.2.3.2.2: the first element of the User-Agent at M5 is 5GMSMediaSessionHandler with the version of the specification */
class HeaderInterceptorUnitTest {
    @Test
    fun theUserAgentStartsWithTheMediaSessionHandlerTokenOfTheSpecificationVersion() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var seen: String? = null
        server.createContext("/") { exchange ->
            seen = exchange.requestHeaders.getFirst("User-Agent")
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.start()
        try {
            val client = OkHttpClient.Builder().addInterceptor(HeaderInterceptor()).build()
            client.newCall(Request.Builder().url("http://127.0.0.1:${server.address.port}/").build()).execute().close()
        } finally {
            server.stop(0)
        }
        assertTrue(seen!!.startsWith("5GMSMediaSessionHandler/19.3.0 "))
        assertEquals(1, seen!!.split(" ").count { it.startsWith("5GMSMediaSessionHandler/") })
    }
}
