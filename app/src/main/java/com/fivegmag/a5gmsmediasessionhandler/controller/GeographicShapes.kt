package com.fivegmag.a5gmsmediasessionhandler.controller

import com.fivegmag.a5gmscommonlibrary.models.GeographicArea
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The position of the UE: longitude and latitude in degrees */
data class UePosition(val lon: Double, val lat: Double)

/**
 * Whether a position is inside a geographic area (TS 29.572 V19.7.0 clause 6.1.6.2, the shapes of TS 23.032 V19.0.0 clause 5). Distances are
 * taken on a sphere of the mean radius of the Earth (the shapes are metres on the ellipsoid; the difference is below the uncertainty of any
 * position that a phone gives). The position is the best estimate of the UE; its own uncertainty is not used. A point and a point with altitude
 * are not areas: nothing is inside them. The altitude of the shapes that have one is not evaluated.
 */
object GeographicShapes {
    private const val EARTH_RADIUS_M = 6371008.8

    fun contains(area: GeographicArea, position: UePosition): Boolean = when (area.shape) {
        "POINT_UNCERTAINTY_CIRCLE" -> {
            val c = area.point
            val r = area.uncertainty
            c != null && r != null && distanceM(c.lat, c.lon, position.lat, position.lon) <= r
        }
        "POINT_UNCERTAINTY_ELLIPSE", "POINT_ALTITUDE_UNCERTAINTY" -> {
            val c = area.point
            val e = area.uncertaintyEllipse
            c != null && e != null && inEllipse(c.lat, c.lon, e.semiMajor, e.semiMinor, e.orientationMajor, position)
        }
        "POLYGON" -> area.pointList?.let { list -> inPolygon(list.map { it.lat to it.lon }, position) } ?: false
        "ELLIPSOID_ARC" -> {
            val c = area.point
            val inner = area.innerRadius
            val width = area.uncertaintyRadius
            val offset = area.offsetAngle
            val included = area.includedAngle
            c != null && inner != null && width != null && offset != null && included != null &&
                    inArc(c.lat, c.lon, inner, width, offset, included, position)
        }
        else -> false
    }

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** The bearing from the first to the second point in degrees clockwise from north, 0 to 360 */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** East and north offsets in metres of the position from a centre (a local flat approximation, good for the tens of kilometres of an area) */
    private fun offsets(lat: Double, lon: Double, position: UePosition): Pair<Double, Double> {
        val east = Math.toRadians(position.lon - lon) * cos(Math.toRadians((lat + position.lat) / 2)) * EARTH_RADIUS_M
        val north = Math.toRadians(position.lat - lat) * EARTH_RADIUS_M
        return east to north
    }

    // the semi-major axis lies at the angle (0 to 180) measured clockwise from north (TS 23.032 clause 5.3)
    private fun inEllipse(lat: Double, lon: Double, r1: Double, r2: Double, orientationDegrees: Double, position: UePosition): Boolean {
        if (r1 <= 0.0 || r2 <= 0.0) return false
        val (east, north) = offsets(lat, lon, position)
        val a = Math.toRadians(orientationDegrees)
        val along = east * sin(a) + north * cos(a)
        val across = east * cos(a) - north * sin(a)
        return (along / r1) * (along / r1) + (across / r2) * (across / r2) <= 1.0
    }

    // the arc spans the radii from inner to inner + uncertainty, and the angles from the offset angle (from north) for the included angle (clause 5.7)
    private fun inArc(lat: Double, lon: Double, inner: Double, width: Double, offset: Double, included: Double, position: UePosition): Boolean {
        val d = distanceM(lat, lon, position.lat, position.lon)
        if (d < inner || d > inner + width) return false
        if (included >= 360.0) return true
        val bearing = bearingDegrees(lat, lon, position.lat, position.lon)
        val delta = ((bearing - offset) % 360.0 + 360.0) % 360.0
        return delta <= included
    }

    /**
     * The described area of a polygon is to the right of the connecting lines, the forward direction being from a point to the next
     * (TS 23.032 clause 5.4): for the points in clockwise order seen from above it is the inside, for counter-clockwise order it is everything
     * else. The edges are taken as straight lines of the longitude and latitude plane (the polygons of areas are small).
     */
    private fun inPolygon(points: List<Pair<Double, Double>>, position: UePosition): Boolean {
        if (points.size < 3) return false
        var signedArea = 0.0
        for (i in points.indices) {
            val (lat1, lon1) = points[i]
            val (lat2, lon2) = points[(i + 1) % points.size]
            signedArea += (lon2 - lon1) * (lat2 + lat1)
        }
        // with x = longitude and y = latitude the sum is positive for clockwise order
        val clockwise = signedArea > 0
        var inside = false
        var j = points.size - 1
        for (i in points.indices) {
            val (latI, lonI) = points[i]
            val (latJ, lonJ) = points[j]
            if ((latI > position.lat) != (latJ > position.lat) &&
                position.lon < (lonJ - lonI) * (position.lat - latI) / (latJ - latI) + lonI
            ) inside = !inside
            j = i
        }
        return if (clockwise) inside else !inside
    }

}
