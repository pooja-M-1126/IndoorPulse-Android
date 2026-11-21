package com.indoorpulse.presentation.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.math.cos
import kotlin.math.sin

internal fun angleDelta(from: Float, to: Float): Float {
    val delta = (to - from) % 360f
    return when {
        delta > 180f -> delta - 360f
        delta < -180f -> delta + 360f
        else -> delta
    }
}

internal data class MapCamera(
    val scale: Float,
    val origin: Offset,
    val rotation: Float,
    val is3D: Boolean
) {
    private val radians = Math.toRadians(rotation.toDouble())
    private val c = cos(radians).toFloat()
    private val s = sin(radians).toFloat()

    fun project(x: Float, y: Float, floor: Int): Offset {
        val u = scale * (c * x + s * y)
        val v = scale * (s * x - c * y)
        return origin + if (is3D) Offset((u - v) * 0.866f, (u + v) * 0.5f - floor * 250f) else Offset(u, v)
    }

    fun pointAt(screen: Offset, floor: Int): Offset {
        val p = screen - origin
        val u = if (is3D) p.y + floor * 250f + p.x / 1.732f else p.x
        val v = if (is3D) p.y + floor * 250f - p.x / 1.732f else p.y
        return Offset((c * u + s * v) / scale, (s * u - c * v) / scale)
    }

    fun anchor(world: Offset, screen: Offset, floor: Int): MapCamera =
        copy(origin = origin + (screen - project(world.x, world.y, floor)))

    fun gesture(centroid: Offset, pan: Offset, zoom: Float, twist: Float, floor: Int): MapCamera =
        copy(scale = (scale * zoom).coerceIn(minOf(5f, scale), 100f), rotation = (rotation + twist) % 360f)
            .anchor(pointAt(centroid, floor), centroid + pan, floor)

    fun fit(points: List<Offset>, floor: Int, viewport: Rect): MapCamera {
        if (points.isEmpty()) return this
        val unitCamera = copy(scale = 1f, origin = Offset.Zero)
        val pointsOnScreen = points.map { unitCamera.project(it.x, it.y, floor) }
        val left = pointsOnScreen.minOf { it.x }
        val right = pointsOnScreen.maxOf { it.x }
        val top = pointsOnScreen.minOf { it.y }
        val bottom = pointsOnScreen.maxOf { it.y }
        val zoom = minOf(viewport.width / (right - left).coerceAtLeast(1f),
            viewport.height / (bottom - top).coerceAtLeast(1f), scale)
        return copy(scale = zoom).anchor(unitCamera.pointAt(Offset((left + right) / 2, (top + bottom) / 2), floor),
            viewport.center, floor)
    }
}

internal fun routeViewport(size: IntSize, obstacles: List<Rect>, margin: Float): Rect? {
    val (above, below) = obstacles.partition { it.center.y < size.height / 2f }
    val top = (above.maxOfOrNull { it.bottom } ?: 0f) + margin
    val bottom = (below.minOfOrNull { it.top } ?: size.height.toFloat()) - margin
    if (bottom <= top || size.width <= 2 * margin) return null
    return Rect(margin, top, size.width - margin, bottom)
}

internal fun mapLabelBounds(
    anchor: Offset,
    width: Float,
    height: Float,
    gap: Float,
    viewport: Rect,
    obstacles: List<Rect>,
    routeSegments: List<Pair<Offset, Offset>> = emptyList(),
    routeClearance: Float = 0f
): Rect? {
    val right = anchor.x + gap
    val left = anchor.x - gap - width
    return listOf(
        Rect(right, anchor.y - height / 2, right + width, anchor.y + height / 2),
        Rect(left, anchor.y - height / 2, left + width, anchor.y + height / 2),
        Rect(anchor.x - width / 2, anchor.y - gap - height, anchor.x + width / 2, anchor.y - gap),
        Rect(anchor.x - width / 2, anchor.y + gap, anchor.x + width / 2, anchor.y + gap + height)
    ).firstOrNull { box ->
        box.left >= viewport.left && box.top >= viewport.top && box.right <= viewport.right &&
            box.bottom <= viewport.bottom && obstacles.none { it.overlaps(box) } &&
            routeSegments.none { (start, end) -> box.inflate(routeClearance).intersectsSegment(start, end) }
    }
}

private fun Rect.intersectsSegment(start: Offset, end: Offset): Boolean {
    if (left > maxOf(start.x, end.x) || right < minOf(start.x, end.x) ||
        top > maxOf(start.y, end.y) || bottom < minOf(start.y, end.y)) return false
    val direction = end - start
    fun side(x: Float, y: Float) = direction.x * (y - start.y) - direction.y * (x - start.x)
    val diagonalA = side(left, top)
    val diagonalB = side(right, bottom)
    if (diagonalA * diagonalB <= 0f) return true
    return side(right, top) * side(left, bottom) <= 0f
}
