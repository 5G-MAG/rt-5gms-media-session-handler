package com.fivegmag.a5gmsmediasessionhandler.controller

import android.net.NetworkCapabilities

/**
 * dataNetworkName of a metrics reporting configuration (TS 26.510 V19.2.0 table 8.7.3.1-1): the Data Network that shall be used when
 * sending metrics reports; without it the default Data Network. An application chooses a network by capability: the Data Network
 * names that Android has a network capability for are mapped to it, the default (internet) needs none; any other name cannot be
 * selected and the reports of the configuration are not sent.
 *
 * The names that can be selected (case-insensitive), with the Android capability each is mapped to:
 * internet (the default Data Network, no capability), ims (NET_CAPABILITY_IMS), mms (NET_CAPABILITY_MMS), supl (NET_CAPABILITY_SUPL),
 * xcap (NET_CAPABILITY_XCAP), dun (NET_CAPABILITY_DUN), fota (NET_CAPABILITY_FOTA), cbs (NET_CAPABILITY_CBS), eims (NET_CAPABILITY_EIMS).
 * Android has no capability for other Data Network names; a configuration that names one gets ERROR_METRICS_REPORTING and no report
 * until the platform can select more networks.
 */
object DataNetwork {
    /** The network capability of a Data Network name; null for the default Data Network */
    sealed class Selection {
        object Default : Selection()
        data class Capability(val capability: Int) : Selection()
        object Unsupported : Selection()
    }

    fun select(dataNetworkName: String?): Selection {
        if (dataNetworkName.isNullOrBlank()) return Selection.Default
        return when (dataNetworkName.lowercase()) {
            "internet" -> Selection.Default
            "ims" -> Selection.Capability(NetworkCapabilities.NET_CAPABILITY_IMS)
            "mms" -> Selection.Capability(NetworkCapabilities.NET_CAPABILITY_MMS)
            "supl" -> Selection.Capability(NetworkCapabilities.NET_CAPABILITY_SUPL)
            "xcap" -> Selection.Capability(NetworkCapabilities.NET_CAPABILITY_XCAP)
            "dun" -> Selection.Capability(NetworkCapabilities.NET_CAPABILITY_DUN)
            "fota" -> Selection.Capability(NetworkCapabilities.NET_CAPABILITY_FOTA)
            "cbs" -> Selection.Capability(NetworkCapabilities.NET_CAPABILITY_CBS)
            "eims" -> Selection.Capability(NetworkCapabilities.NET_CAPABILITY_EIMS)
            else -> Selection.Unsupported
        }
    }
}
