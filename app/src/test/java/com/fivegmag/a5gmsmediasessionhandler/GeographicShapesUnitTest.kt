package com.fivegmag.a5gmsmediasessionhandler

import com.fivegmag.a5gmscommonlibrary.models.GeographicArea
import com.fivegmag.a5gmscommonlibrary.models.GeographicalCoordinates
import com.fivegmag.a5gmscommonlibrary.models.LocationArea5G
import com.fivegmag.a5gmscommonlibrary.models.UncertaintyEllipse
import com.fivegmag.a5gmsmediasessionhandler.controller.GeographicShapes
import com.fivegmag.a5gmsmediasessionhandler.controller.LocationFilter
import com.fivegmag.a5gmsmediasessionhandler.controller.UePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// TS 29.572 V19.7.0 clause 6.1.6.2 and the shapes of TS 23.032 V19.0.0 clause 5
class GeographicShapesUnitTest {
    // Geneva
    private val lon = 6.1432
    private val lat = 46.2044
    private fun at(northM: Double, eastM: Double) = UePosition(
        lon + Math.toDegrees(eastM / (6371008.8 * Math.cos(Math.toRadians(lat)))),
        lat + Math.toDegrees(northM / 6371008.8)
    )
    private val centre = GeographicalCoordinates(lon, lat)

    @Test
    fun aCircleHasItsUncertaintyAsRadius() {
        val circle = GeographicArea("POINT_UNCERTAINTY_CIRCLE", point = centre, uncertainty = 1000.0)
        assertTrue(GeographicShapes.contains(circle, at(900.0, 0.0)))
        assertFalse(GeographicShapes.contains(circle, at(1100.0, 0.0)))
        assertTrue(GeographicShapes.contains(circle, at(0.0, -900.0)))
    }

    @Test
    fun anEllipseIsOrientedClockwiseFromNorth() {
        // semi-major 2000 m towards the east (90 degrees), semi-minor 500 m
        val ellipse = GeographicArea("POINT_UNCERTAINTY_ELLIPSE", point = centre, uncertaintyEllipse = UncertaintyEllipse(2000.0, 500.0, 90.0))
        assertTrue(GeographicShapes.contains(ellipse, at(0.0, 1900.0)))
        assertFalse(GeographicShapes.contains(ellipse, at(1900.0, 0.0)))
        assertTrue(GeographicShapes.contains(ellipse, at(400.0, 0.0)))
        assertFalse(GeographicShapes.contains(ellipse, at(600.0, 0.0)))
    }

    @Test
    fun aClockwisePolygonIsItsInside() {
        // a square of 2 km, corners in clockwise order seen from above (north-west, north-east, south-east, south-west)
        val square = GeographicArea("POLYGON", pointList = arrayListOf(corner(1000.0, -1000.0), corner(1000.0, 1000.0), corner(-1000.0, 1000.0), corner(-1000.0, -1000.0)))
        assertTrue(GeographicShapes.contains(square, at(0.0, 0.0)))
        assertFalse(GeographicShapes.contains(square, at(0.0, 1500.0)))
    }

    @Test
    fun aCounterClockwisePolygonIsEverythingElse() {
        val square = GeographicArea("POLYGON", pointList = arrayListOf(corner(1000.0, -1000.0), corner(-1000.0, -1000.0), corner(-1000.0, 1000.0), corner(1000.0, 1000.0)))
        assertFalse(GeographicShapes.contains(square, at(0.0, 0.0)))
        assertTrue(GeographicShapes.contains(square, at(0.0, 1500.0)))
    }

    @Test
    fun anEllipsoidArcIsARingSectorFromTheOffsetAngle() {
        // from 1000 m to 3000 m, from the east (90 degrees) for 90 degrees: the sector between east and south
        val arc = GeographicArea("ELLIPSOID_ARC", point = centre, innerRadius = 1000.0, uncertaintyRadius = 2000.0, offsetAngle = 90.0, includedAngle = 90.0)
        assertTrue(GeographicShapes.contains(arc, at(-1500.0, 1500.0)))
        assertFalse(GeographicShapes.contains(arc, at(500.0, 500.0)))
        assertFalse(GeographicShapes.contains(arc, at(1500.0, 1500.0)))
        assertFalse(GeographicShapes.contains(arc, at(-4000.0, 4000.0)))
    }

    @Test
    fun aPointIsNotAnArea() {
        assertFalse(GeographicShapes.contains(GeographicArea("POINT", point = centre), UePosition(lon, lat)))
    }

    @Test
    fun theLocationFilterUsesThePositionForGeographicAreas() {
        val filter = listOf(LocationArea5G(geographicAreas = arrayListOf(GeographicArea("POINT_UNCERTAINTY_CIRCLE", point = centre, uncertainty = 1000.0))))
        assertTrue(LocationFilter.isLocated(filter, null, at(100.0, 100.0)))
        assertFalse(LocationFilter.isLocated(filter, null, at(5000.0, 0.0)))
        assertFalse(LocationFilter.isLocated(filter, null, null))
    }

    @Test
    fun theDistanceBetweenTwoPointsIsInMetres() {
        assertEquals(1000.0, GeographicShapes.distanceM(lat, lon, at(1000.0, 0.0).lat, at(1000.0, 0.0).lon), 1.0)
    }

    private fun corner(northM: Double, eastM: Double): GeographicalCoordinates {
        val p = at(northM, eastM)
        return GeographicalCoordinates(p.lon, p.lat)
    }
}
