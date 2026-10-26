package com.fivegmag.a5gmsmediasessionhandler.controller

import android.content.Context
import android.location.Geocoder
import com.fivegmag.a5gmscommonlibrary.models.CivicAddress
import java.util.Locale
import java.util.concurrent.Executors

/** The civic address of the UE as far as the platform can tell: the elements that match the elements of a CivicAddress (IETF RFC 4776) */
data class UeCivic(
    val country: String?, val a1: String?, val a2: String?, val a3: String?, val a4: String?,
    val rd: String?, val hno: String?, val pc: String?
)

/**
 * Whether the UE is at a civic address (TS 29.572 V19.7.0 clause 6.1.6.2.14): every element that the address names has to be the element
 * of the address of the UE, compared without case and surrounding white space; an element that the platform does not give (A5, A6, the
 * building, the floor and the other elements) cannot be shown, so an address that names one is not matched. An address with only
 * the country is the country.
 */
object CivicLookup {
    fun matches(area: CivicAddress, ue: UeCivic?): Boolean {
        if (ue == null) return false
        val unsupported = listOf(
            area.a5, area.a6, area.prd, area.pod, area.sts, area.hns, area.lmk, area.loc, area.nam, area.bld, area.unit, area.flr, area.room,
            area.plc, area.pcn, area.pobox, area.addcode, area.seat, area.rdsec, area.rdbr, area.rdsubbr, area.prm, area.pom
        )
        if (unsupported.any { !it.isNullOrBlank() }) return false
        return same(area.country, ue.country) && same(area.a1, ue.a1) && same(area.a2, ue.a2) && same(area.a3, ue.a3) &&
                same(area.a4, ue.a4) && same(area.rd, ue.rd) && same(area.hno, ue.hno) && same(area.pc, ue.pc)
    }

    // an element that the address does not name is not compared
    private fun same(wanted: String?, actual: String?): Boolean =
        wanted.isNullOrBlank() || (actual != null && wanted.trim().equals(actual.trim(), ignoreCase = true))

    /**
     * The address of a position from the geocoder of the platform (a network service on most devices); the answer is kept for the position
     * rounded to 4 decimal degrees, and the lookup runs in the background: until it has finished the address is not known.
     */
    class Resolver(private val context: Context) {
        private val executor = Executors.newSingleThreadExecutor()
        @Volatile private var key: String? = null
        @Volatile private var cached: UeCivic? = null
        @Volatile private var pendingKey: String? = null

        fun addressOf(position: UePosition?): UeCivic? {
            if (position == null) return null
            val k = "%.4f,%.4f".format(Locale.ROOT, position.lat, position.lon)
            if (k == key) return cached
            if (pendingKey != k) {
                pendingKey = k
                executor.execute {
                    val found = lookup(position)
                    cached = found
                    key = k
                    pendingKey = null
                }
            }
            return if (k == key) cached else null
        }

        @Suppress("DEPRECATION")
        private fun lookup(position: UePosition): UeCivic? {
            if (!Geocoder.isPresent()) return null
            return try {
                val a = Geocoder(context, Locale.ENGLISH).getFromLocation(position.lat, position.lon, 1)?.firstOrNull() ?: return null
                UeCivic(a.countryCode, a.adminArea, a.subAdminArea, a.locality, a.subLocality, a.thoroughfare, a.subThoroughfare, a.postalCode)
            } catch (_: Exception) {
                null
            }
        }
    }
}
