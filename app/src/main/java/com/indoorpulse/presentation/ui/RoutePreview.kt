package com.indoorpulse.presentation.ui

import com.indoorpulse.data.local.entity.NodeEntity
import com.indoorpulse.data.local.entity.label
import kotlin.math.atan2

internal enum class PreviewManeuver { WALK, LEFT, RIGHT, TURN, ELEVATOR, STAIRS, CHANGE_FLOOR, ARRIVE }

internal data class RoutePreviewStep(
    val maneuver: PreviewManeuver,
    val points: List<NodeEntity>
) {
    val start get() = points.first()
    val end get() = points.last()
    val title: String
        get() = when (maneuver) {
            PreviewManeuver.WALK -> if (end.name.isBlank()) "Follow the corridor" else "Walk to ${end.label}"
            PreviewManeuver.LEFT -> "Turn left"
            PreviewManeuver.RIGHT -> "Turn right"
            PreviewManeuver.TURN -> "Turn around"
            PreviewManeuver.ELEVATOR -> "Take the elevator to L${end.floorLevel}"
            PreviewManeuver.STAIRS -> "Take the stairs to L${end.floorLevel}"
            PreviewManeuver.CHANGE_FLOOR -> "Continue to L${end.floorLevel}"
            PreviewManeuver.ARRIVE -> "Arrive at ${end.label}"
        }
    val detail: String
        get() = when (maneuver) {
            PreviewManeuver.WALK -> "From ${start.name.ifBlank { "the starting point" }} · L${start.floorLevel}"
            PreviewManeuver.LEFT, PreviewManeuver.RIGHT, PreviewManeuver.TURN ->
                if (start.name.isBlank()) "At the junction · L${start.floorLevel}"
                else "At ${start.label} · L${start.floorLevel}"
            PreviewManeuver.ELEVATOR, PreviewManeuver.STAIRS, PreviewManeuver.CHANGE_FLOOR ->
                "L${start.floorLevel} to L${end.floorLevel}"
            PreviewManeuver.ARRIVE -> "Destination · L${end.floorLevel}"
        }
}

internal fun routePreviewSteps(path: List<NodeEntity>): List<RoutePreviewStep> {
    if (path.isEmpty()) return emptyList()
    return buildList {
        var legStart = 0
        var maneuver = PreviewManeuver.WALK
        fun addLeg(end: Int) {
            if (end > legStart) add(RoutePreviewStep(maneuver, path.subList(legStart, end + 1)))
        }
        for (i in 0 until path.lastIndex) {
            val point = path[i]
            val next = path[i + 1]
            if (point.floorLevel != next.floorLevel) {
                addLeg(i)
                val transit = when {
                    listOf(point, next).all { it.nodeType.equals("Elevator", ignoreCase = true) } -> PreviewManeuver.ELEVATOR
                    listOf(point, next).all { it.nodeType.equals("Stairs", ignoreCase = true) } -> PreviewManeuver.STAIRS
                    else -> PreviewManeuver.CHANGE_FLOOR
                }
                add(RoutePreviewStep(transit, listOf(point, next)))
                legStart = i + 1
                maneuver = PreviewManeuver.WALK
            } else if (i > legStart) {
                val previous = path[i - 1]
                val ax = point.x - previous.x
                val ay = point.y - previous.y
                val bx = next.x - point.x
                val by = next.y - point.y
                val angle = Math.toDegrees(atan2((ax * by - ay * bx).toDouble(), (ax * bx + ay * by).toDouble()))
                val turn = when {
                    kotlin.math.abs(angle) >= 150 -> PreviewManeuver.TURN
                    angle >= 40 -> PreviewManeuver.LEFT
                    angle <= -40 -> PreviewManeuver.RIGHT
                    else -> null
                }
                if (turn != null) {
                    addLeg(i)
                    legStart = i
                    maneuver = turn
                }
            }
        }
        addLeg(path.lastIndex)
        add(RoutePreviewStep(PreviewManeuver.ARRIVE, listOf(path.last())))
    }
}
