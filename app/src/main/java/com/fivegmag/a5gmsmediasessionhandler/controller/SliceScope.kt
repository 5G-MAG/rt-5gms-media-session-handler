package com.fivegmag.a5gmsmediasessionhandler.controller

import com.fivegmag.a5gmscommonlibrary.models.Snssai

/** An S-NSSAI of the network slicing configuration of the UE: the slice service type and the slice differentiator (null without one) */
data class SliceId(val sst: Int, val sd: Int?)

/**
 * sliceScope of a metrics reporting configuration (TS 26.510 V19.2.0 table 8.7.3.1-1): the set of network slices for which metrics are
 * collected and reported; without it metrics are reported regardless of network slice. A client reports for the configuration when
 * the network slicing configuration of the UE contains one of the slices of the scope. When the UE does not tell its slices the
 * client cannot show that it is in the scope and does not report.
 */
object SliceScope {
    fun applies(scope: List<Snssai>?, slices: List<SliceId>?): Boolean {
        if (scope.isNullOrEmpty()) return true
        if (slices == null) return false
        return scope.any { wanted ->
            val wantedSd = wanted.sd?.toIntOrNull(16)
            slices.any { it.sst == wanted.sst && (wantedSd == null || it.sd == wantedSd) }
        }
    }
}
