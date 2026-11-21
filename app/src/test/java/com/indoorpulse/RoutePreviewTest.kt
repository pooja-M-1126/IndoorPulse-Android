package com.indoorpulse

import androidx.compose.ui.geometry.Offset
import com.indoorpulse.data.local.entity.EdgeEntity
import com.indoorpulse.data.local.entity.NodeEntity
import com.indoorpulse.domain.model.Coordinate
import com.indoorpulse.presentation.ui.FloorStackLayout
import com.indoorpulse.presentation.ui.PreviewManeuver
import com.indoorpulse.presentation.ui.StackOrbit
import com.indoorpulse.presentation.ui.portalDestinations
import com.indoorpulse.presentation.ui.routePreviewSteps
import com.indoorpulse.presentation.ui.stackVisibleFloors
import org.junit.Assert.*
import org.junit.Test

class RoutePreviewTest {
    private fun point(name: String, x: Float, y: Float, floor: Int = 0, type: String = "Junction") =
        NodeEntity("$name-$floor", "f$floor", x, y, type, name, floor)

    @Test fun splitsAtTurnsAndElevatorWhileKeepingEveryRouteEdge() {
        val path = listOf(
            point("Front Entry", 0f, 0f),
            point("Main corridor", 0f, 8f),
            point("Elevator", 6f, 8f, type = "Elevator"),
            point("Elevator", 6f, 8f, 1, "Elevator"),
            point("Room 104", -4f, 8f, 1)
        )
        val steps = routePreviewSteps(path)
        assertEquals(
            listOf(PreviewManeuver.WALK, PreviewManeuver.RIGHT, PreviewManeuver.ELEVATOR,
                PreviewManeuver.WALK, PreviewManeuver.ARRIVE),
            steps.map { it.maneuver }
        )
        assertEquals(path.zipWithNext(), steps.flatMap { it.points.zipWithNext() })
        assertEquals("Walk to Main corridor", steps.first().title)
        assertEquals("At Main corridor · L0", steps[1].detail)
        assertEquals("Take the elevator to L1", steps[2].title)
        assertEquals("Arrive at Room 104", steps.last().title)
    }

    @Test fun describesLeftTurnsAndDescendingStairsOnTheirActualFloors() {
        val path = listOf(
            point("Entry", 0f, 0f, 4),
            point("Corner", 0f, 2f, 4),
            point("Stairwell", -2f, 2f, 4, "Stairs"),
            point("Stairwell", -2f, 2f, -1, "Stairs"),
            point("Lab", -5f, 2f, -1)
        )
        val steps = routePreviewSteps(path)
        assertEquals(PreviewManeuver.LEFT, steps[1].maneuver)
        assertEquals(PreviewManeuver.STAIRS, steps[2].maneuver)
        assertEquals("L4 to L-1", steps[2].detail)
        assertEquals("Walk to Lab", steps[3].title)
    }

    @Test fun handlesEmptyAlreadyThereAndUnknownPortalRoutes() {
        val start = point("Entry", 0f, 0f)
        val destination = point("Lab", 0f, 0f, 1)
        assertTrue(routePreviewSteps(emptyList()).isEmpty())
        assertEquals(PreviewManeuver.ARRIVE, routePreviewSteps(listOf(start)).single().maneuver)
        val steps = routePreviewSteps(listOf(start, destination))
        assertEquals(PreviewManeuver.CHANGE_FLOOR, steps.first().maneuver)
        assertEquals("Destination · L1", steps.last().detail)
    }

    @Test fun floorStackFitsOffOriginAndSpacesSparseLevelsEqually() {
        val point = Coordinate(1100f, -510f, -2)
        val layout = FloorStackLayout(listOf(point, Coordinate(1110f, -495f, 12)), listOf(12, -2, 0))
        val basement = layout.project(point)
        val ground = layout.project(point.copy(floor = 0))
        val top = layout.project(point.copy(floor = 12))
        assertEquals(basement.x, top.x, 0.001f)
        assertTrue(basement.y > ground.y)
        assertEquals(basement.y - ground.y, ground.y - top.y, 0.001f)
        assertTrue(layout.bounds(listOf(-2, 0, 12)).contains(top))
        assertTrue(layout.bounds(listOf(-2)).height < layout.bounds(listOf(-2, 12)).height)
    }

    @Test fun singlePointAndEmptyFloorStillHaveFitBounds() {
        val point = Coordinate(-28f, 11f, -1)
        val layout = FloorStackLayout(listOf(point), listOf(-1))
        assertTrue(layout.bounds(listOf(-1)).contains(layout.project(point)))
        val emptyBounds = FloorStackLayout(emptyList(), emptyList()).bounds(emptyList())
        assertTrue(emptyBounds.width > 0f && emptyBounds.height > 0f)
    }

    @Test fun orbitRotatesGeometryAndKeepsTiltWithinUsableRange() {
        val point = Coordinate(12f, 5f, 0)
        val layout = FloorStackLayout(listOf(Coordinate(0f, 0f, 0), point), listOf(0))
        val initial = StackOrbit(yaw = 0f)
        val opposite = initial.copy(yaw = 180f)
        val atStart = layout.project(point, initial)
        val atOpposite = layout.project(point, opposite)
        assertEquals(-atStart.x, atOpposite.x, 0.001f)
        assertEquals(-atStart.y, atOpposite.y, 0.001f)
        assertEquals(12f, initial.drag(Offset(0f, -10000f)).tilt, 0.001f)
        assertEquals(58f, initial.drag(Offset(0f, 10000f)).tilt, 0.001f)
    }

    @Test fun spacingIncreasesDeckSeparationAndFocusKeepsOnlyNeighbors() {
        val levels = (-1..7).toList()
        val point = Coordinate(0f, 0f, 1)
        val above = point.copy(floor = 2)
        val layout = FloorStackLayout(listOf(point, Coordinate(20f, 12f, 2)), levels)
        fun gap(spacing: Float) = layout.project(point, spacing = spacing).y - layout.project(above, spacing = spacing).y
        assertTrue(gap(1.4f) > gap(0.6f) * 2)
        assertEquals(levels, stackVisibleFloors(levels, emptySet()))
        assertEquals(listOf(0, 1, 2), stackVisibleFloors(levels, setOf(1)))
        assertEquals(listOf(0, 1, 2, 3), stackVisibleFloors(levels, setOf(1, 2)))
    }

    @Test fun portalTargetsUseActualBidirectionalLinksAndDeduplicateFloors() {
        val lift = point("Lift", 0f, 0f, 1, "Elevator")
        val below = point("Lift", 0f, 0f, 0, "Elevator")
        val above = point("Lift", 0f, 0f, 3, "Elevator")
        val stairs = point("Stairwell", 1f, 0f, 2, "Stairs")
        val unlinked = point("Lift", 0f, 0f, 2, "Elevator")
        fun edge(id: String, from: NodeEntity, to: NodeEntity) =
            EdgeEntity(id, from.nodeId, to.nodeId, 3f, "vertical_portal", "building")
        val nodes = listOf(lift, below, above, stairs, unlinked).associateBy { it.nodeId }
        val edges = listOf(edge("down", below, lift), edge("up", lift, above), edge("duplicate", above, lift),
            edge("mixed", lift, stairs))
        assertEquals(listOf(0, 3), portalDestinations(lift, nodes, edges))
        assertTrue(portalDestinations(unlinked, nodes, edges).isEmpty())
    }
}
