package com.indoorpulse.presentation.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.indoorpulse.domain.model.Coordinate

internal fun routeLegsOnFloor(route: List<Coordinate>, floor: Int): List<List<Coordinate>> = buildList {
    var start = 0
    for (index in route.indices) {
        if (route[index].floor != floor) {
            if (index > start) add(route.subList(start, index))
            start = index + 1
        }
    }
    if (start < route.size) add(route.subList(start, route.size))
}

internal fun routeTurnPoints(leg: List<Coordinate>): List<Coordinate> =
    leg.windowed(3).mapNotNull { (before, at, after) ->
        val incoming = Offset(at.x - before.x, at.y - before.y)
        val outgoing = Offset(after.x - at.x, after.y - at.y)
        val length = incoming.getDistance() * outgoing.getDistance()
        val dot = incoming.x * outgoing.x + incoming.y * outgoing.y
        at.takeIf { length > 0.01f && dot / length < 0.8f }
    }

internal fun DrawScope.drawCasedRoute(points: List<Offset>, ink: Color, casing: Color) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        for (index in 1 until points.size) lineTo(points[index].x, points[index].y)
    }
    for ((width, color) in listOf(10.dp to casing, 6.dp to ink)) {
        drawPath(path, color, style = Stroke(width.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

internal fun DrawScope.drawRouteEndpoint(
    point: Offset,
    ink: Color,
    casing: Color,
    portal: StackPortal? = null,
    portalColor: Color = ink
) {
    drawCircle(casing, 11.dp.toPx(), point)
    drawCircle(ink, 8.5.dp.toPx(), point)
    if (portal == null) drawCircle(casing, 3.5.dp.toPx(), point)
    else drawPortal(portal, point, portalColor, casing, 4.dp.toPx())
}
