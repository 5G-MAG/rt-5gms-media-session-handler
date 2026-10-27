package com.fivegmag.a5gmsmediasessionhandler.network

/** The HTTP header that carries the media delivery session identifier (TS 26.512 V19.3.0 clause 6.2.3.6, key sid of table 1 of CTA-5004) */
object SessionIdentifierHeader {
    const val NAME = "CMCD-Session"

    /** The sid key as a CTA-5004 string value */
    fun value(mediaDeliverySessionId: String): String = "sid=\"$mediaDeliverySessionId\""
}
