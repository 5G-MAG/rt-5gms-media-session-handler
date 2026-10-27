package com.fivegmag.a5gmsmediasessionhandler.controller

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.TelephonyManager
import com.fivegmag.a5gmscommonlibrary.models.LocationArea5G

/** The cell that the UE is registered on: the PLMN, the tracking area code and the cell identity (NR or E-UTRA) */
data class UeCell(val mcc: String?, val mnc: String?, val tac: Long?, val nrCellId: Long?, val eutraCellId: Long?)

/**
 * The locationFilter of a metrics reporting configuration (TS 26.510 V19.2.0 table 8.7.3.1-1): "When present a Media Client shall collect
 * the metrics only when it is located in these locations". A location is a network area (TS 29.554 V19.2.1 clause 5.6.2.8): the UE is in it
 * when its cell is one of the Ecgi or Ncgi of the area, or its tracking area is one of the Tai; it is in a geographic area when its position
 * is inside one of the shapes (GeographicShapes) or at one of the civic addresses (CivicLookup). Without a filter the UE is everywhere.
 */
object LocationFilter {
    fun isLocated(filter: List<LocationArea5G>?, cell: UeCell?, position: UePosition? = null, civic: UeCivic? = null): Boolean {
        if (filter.isNullOrEmpty()) return true
        return filter.any { area ->
            val network = area.nwAreaInfo
            (cell != null && network != null && (
                    network.ncgis.orEmpty().any { plmn(it.plmnId.mcc, it.plmnId.mnc, cell) && hex(it.nrCellId) == cell.nrCellId } ||
                            network.ecgis.orEmpty().any { plmn(it.plmnId.mcc, it.plmnId.mnc, cell) && hex(it.eutraCellId) == cell.eutraCellId } ||
                            network.tais.orEmpty().any { plmn(it.plmnId.mcc, it.plmnId.mnc, cell) && hex(it.tac) == cell.tac })) ||
                    (position != null && area.geographicAreas.orEmpty().any { GeographicShapes.contains(it, position) }) ||
                    area.civicAddresses.orEmpty().any { CivicLookup.matches(it, civic) }
        }
    }

    /**
     * The position of the UE: the most recent last known location of the providers that is not older than maxAgeMs; null when the platform
     * does not tell (no permission, no location). The age is the option locationMaxAgeSeconds of config.properties.xml.
     */
    @SuppressLint("MissingPermission")
    fun currentPosition(context: Context, maxAgeMs: Long): UePosition? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return null
        return try {
            val now = System.currentTimeMillis()
            manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }
                .filter { now - it.time <= maxAgeMs }
                .maxByOrNull { it.time }
                ?.let { UePosition(it.longitude, it.latitude) }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun plmn(mcc: String, mnc: String, cell: UeCell): Boolean = cell.mcc == mcc && cell.mnc == mnc

    /** A value of TS 29.571 (Tac, NrCellId, EutraCellId) is a string of hexadecimal digits */
    private fun hex(value: String): Long? = value.toLongOrNull(16)

    @Volatile private var lastCells: List<CellInfo> = emptyList()

    /**
     * The registered cell of the UE. allCellInfo is the answer of the platform without a request, and is often empty until a request has been made
     * (the same as for the Media Stream Handler, which asks for it at the playback trigger), so every call also asks the platform for an update;
     * the answer is used by the next call, and the call itself uses the update of the last one when allCellInfo is empty. Null when the platform
     * does not tell (no permission, no cell).
     */
    @SuppressLint("MissingPermission")
    fun currentCell(context: Context): UeCell? {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
        val cells: List<CellInfo> = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                telephony.requestCellInfoUpdate(context.mainExecutor, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        lastCells = cellInfo
                    }
                })
            }
            (telephony.allCellInfo ?: emptyList()).ifEmpty { lastCells }
        } catch (_: SecurityException) {
            return null
        }
        for (info in cells) {
            if (!info.isRegistered) continue
            when (info) {
                is CellInfoNr -> {
                    val id = info.cellIdentity as? CellIdentityNr ?: continue
                    return UeCell(id.mccString, id.mncString, id.tac.toLong().takeIf { it != CellInfo.UNAVAILABLE.toLong() }, id.nci.takeIf { it != CellInfo.UNAVAILABLE_LONG }, null)
                }
                is CellInfoLte -> {
                    val id = info.cellIdentity as? CellIdentityLte ?: continue
                    return UeCell(id.mccString, id.mncString, id.tac.toLong().takeIf { it != CellInfo.UNAVAILABLE.toLong() }, null, id.ci.toLong().takeIf { it != CellInfo.UNAVAILABLE.toLong() })
                }
            }
        }
        return null
    }
}
