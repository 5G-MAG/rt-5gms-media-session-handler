package com.fivegmag.a5gmsmediasessionhandler.controller

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException

/** The addresses of the UE and of the Media AS from the platform: the first non-loopback IPv4 address of the default network, and DNS */
class AndroidFlowEndpoints(private val context: Context) : FlowEndpoints {
    override fun ueIpv4Address(): String? {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val network = connectivityManager.activeNetwork ?: return null
        return connectivityManager.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }

    override fun resolve(domainName: String): List<String> = try {
        InetAddress.getAllByName(domainName).filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.distinct()
    } catch (_: UnknownHostException) {
        emptyList()
    }
}
