package com.indoorpulse

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.indoorpulse.data.local.entity.NodeEntity
import com.indoorpulse.data.sensor.portraitHeading
import com.indoorpulse.domain.navigation.ParticleFilter
import com.indoorpulse.presentation.ui.MapCamera
import com.indoorpulse.presentation.ui.angleDelta
import com.indoorpulse.presentation.ui.findNodeAt
import com.indoorpulse.presentation.ui.mapLabelBounds
import com.indoorpulse.presentation.ui.routeViewport
import com.indoorpulse.presentation.ui.routeLegsOnFloor
import com.indoorpulse.presentation.ui.routeTurnPoints
import com.indoorpulse.domain.model.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class WalkingMapTest {
    @Test
    fun portraitHeadingHoldsThroughFlatTiltedAndUprightPoses() {
        for (yaw in listOf(0, 89, 180, 291, 359)) {
            val a = Math.toRadians(yaw.toDouble())
            for (tilt in listOf(0, 30, 45, 65, 89, 90, 95)) {
                val t = Math.toRadians(tilt.toDouble())
                val matrix = doubleArrayOf(
                    cos(a), sin(a) * cos(t), -sin(a) * sin(t),
                    -sin(a), cos(a) * cos(t), -cos(a) * sin(t),
                    0.0, sin(t), cos(t)
                ).map { it.toFloat() }.toFloatArray()
                assertEquals("yaw=$yaw tilt=$tilt", 0f, angleDelta(yaw.toFloat(), portraitHeading(matrix)!!), 0.01f)
            }
        }
    }

    @Test
    fun projectedForwardStaysAbovePuckWhenFollowingAcrossNorth() {
        val center = Offset(210f, 340f)
        val origin = Offset(-15.19f, -3.51f)
        assertEquals(2f, angleDelta(359f, 1f), 0f)
        assertEquals(-2f, angleDelta(1f, 359f), 0f)
        for (bearing in listOf(0f, 90f, 291f, 359f, 1f)) {
            val camera = MapCamera(20f, Offset.Zero, -bearing, false).anchor(origin, center, 1)
            val radians = Math.toRadians(bearing.toDouble())
            val ahead = camera.project(origin.x + sin(radians).toFloat(), origin.y + cos(radians).toFloat(), 1)
            assertEquals(center.x, ahead.x, 0.001f)
            assertEquals(center.y - 20f, ahead.y, 0.001f)
        }
    }

    @Test
    fun gestureAnchorPickingAndCrosshairAgreeOnRotatedFloors() {
        val centroid = Offset(194f, 312f)
        val pan = Offset(-17f, 28f)
        for (is3D in listOf(false, true)) {
            for (floor in listOf(-1, 0, 2)) {
                val camera = MapCamera(35f, Offset(91f, -70f), 37f, is3D)
                val anchor = camera.pointAt(centroid, floor)
                val moved = camera.gesture(centroid, pan, 1.45f, -68f, floor)
                val rendered = moved.project(anchor.x, anchor.y, floor)
                assertEquals((centroid + pan).x, rendered.x, 0.001f)
                assertEquals((centroid + pan).y, rendered.y, 0.001f)
                val target = NodeEntity("D112", "f$floor", anchor.x, anchor.y, "Room Door", floorLevel = floor)
                assertEquals(target, findNodeAt(centroid + pan, listOf(target), floor, is3D, 64f, moved::project))
                val crosshair = moved.pointAt(rendered, floor)
                assertEquals(anchor.x, crosshair.x, 0.001f)
                assertEquals(anchor.y, crosshair.y, 0.001f)
            }
        }
    }

    @Test
    fun surveyStepsMoveEastAndNorthInSavedMapCoordinates() {
        val tracker = ParticleFilter()
        tracker.forcePosition(-15f, -3f)
        repeat(10) { tracker.update(1f, 90f, emptyList(), emptyMap(), false) }
        repeat(5) { tracker.update(1f, 0f, emptyList(), emptyMap(), false) }
        val (x, y) = tracker.getEstimatedPosition()
        assertEquals(-5f, x, 0.15f)
        assertEquals(2f, y, 0.15f)
    }

    @Test fun routeFitKeepsBothEndpointsClearOfFloatingControlsAndBanner() {
        val blocked = listOf(Rect(275f, 12f, 388f, 175f), Rect(6f, 671f, 394f, 780f))
        val visible = checkNotNull(routeViewport(IntSize(400, 800), blocked, 20f))
        assertEquals(Rect(20f, 195f, 380f, 651f), visible)
        val points = listOf(Offset(-45f, -19f), Offset(70f, 35f), Offset(90f, 100f))
        val camera = MapCamera(25f, Offset(90f, 210f), -47f, false)
        val fitted = camera.fit(points, 0, visible)
        assertEquals(camera.rotation, fitted.rotation, 0f)
        assertTrue(fitted.scale < 5f)
        for (point in points) {
            val at = fitted.project(point.x, point.y, 0)
            assertTrue(visible.inflate(0.01f).contains(at))
            assertFalse(blocked.any { it.contains(at) })
        }
    }

    @Test fun mapLabelChoosesAnAlternateSideWhenControlsOccupyItsFirstChoice() {
        val obstacle = Rect(160f, 15f, 295f, 144f)
        val placement = checkNotNull(mapLabelBounds(Offset(155f, 68f), 90f, 23f, 12f,
            Rect(0f, 0f, 300f, 400f), listOf(obstacle)))
        assertTrue(placement.right < 155f)
        assertFalse(placement.overlaps(obstacle))
    }

    @Test fun destinationLabelDoesNotCoverTheApproachLine() {
        val destination = Offset(150f, 90f)
        val placement = checkNotNull(mapLabelBounds(destination, 70f, 24f, 12f,
            Rect(0f, 0f, 400f, 300f), emptyList(), listOf(destination to Offset(270f, 105f)), 5f))
        assertTrue(placement.right < destination.x)
    }

    @Test fun routeDrawingKeepsSeparatedFloorLegsAndMarksOnlyActualTurns() {
        val path = listOf(Coordinate(0f, 0f, 0), Coordinate(0f, 5f, 0), Coordinate(0f, 10f, 0),
            Coordinate(4f, 10f, 0), Coordinate(4f, 10f, 1), Coordinate(18f, 10f, 1), Coordinate(18f, 10f, 0),
            Coordinate(22f, 10f, 0))
        val legs = routeLegsOnFloor(path, 0)
        assertEquals(listOf(path.subList(0, 4), path.subList(6, 8)), legs)
        assertEquals(listOf(path[2]), legs.flatMap(::routeTurnPoints))
    }
}
