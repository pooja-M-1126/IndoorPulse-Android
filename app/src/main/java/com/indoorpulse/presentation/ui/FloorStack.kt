package com.indoorpulse.presentation.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
import com.indoorpulse.data.local.entity.EdgeEntity
import com.indoorpulse.data.local.entity.NodeEntity
import com.indoorpulse.domain.model.Coordinate
import kotlin.math.cos
import kotlin.math.sin

internal data class StackOrbit(val yaw: Float = -35f, val tilt: Float = 26f) {
    private val yawRadians = Math.toRadians(yaw.toDouble())
    private val tiltRadians = Math.toRadians(tilt.toDouble())
    val cosYaw = cos(yawRadians).toFloat()
    val sinYaw = sin(yawRadians).toFloat()
    val sinTilt = sin(tiltRadians).toFloat()
    val cosTilt = cos(tiltRadians).toFloat()

    fun drag(delta: Offset) = StackOrbit((yaw + delta.x * 0.48f) % 360f, (tilt + delta.y * 0.22f).coerceIn(12f, 58f))
}

internal enum class StackPortal(val label: String) {
    ELEVATOR("Elevator"), STAIRS("Stairs");

    companion object {
        fun from(type: String): StackPortal? = entries.firstOrNull { it.label.equals(type, ignoreCase = true) }
    }
}

internal fun portalDestinations(portal: NodeEntity, nodesById: Map<String, NodeEntity>, edges: List<EdgeEntity>): List<Int> {
    val type = StackPortal.from(portal.nodeType) ?: return emptyList()
    return edges.mapNotNull { edge ->
        val otherId = when (portal.nodeId) {
            edge.fromNodeId -> edge.toNodeId
            edge.toNodeId -> edge.fromNodeId
            else -> return@mapNotNull null
        }
        nodesById[otherId]?.takeIf { it.floorLevel != portal.floorLevel && StackPortal.from(it.nodeType) == type }?.floorLevel
    }.distinct().sorted()
}

internal fun stackVisibleFloors(floors: List<Int>, focus: Set<Int>): List<Int> {
    if (focus.isEmpty()) return floors
    val indices = focus.map(floors::indexOf).filter { it >= 0 }
    return floors.filterIndexed { index, _ -> indices.any { kotlin.math.abs(it - index) <= 1 } }
}

internal class FloorStackLayout(points: List<Coordinate>, floors: List<Int>) {
    private val levels = floors.distinct().sorted().withIndex().associate { it.value to it.index }
    private val minX = points.minOfOrNull { it.x } ?: 0f
    private val maxX = points.maxOfOrNull { it.x } ?: 0f
    private val minY = points.minOfOrNull { it.y } ?: 0f
    private val maxY = points.maxOfOrNull { it.y } ?: 0f
    private val extent = maxOf(maxX - minX, maxY - minY, 4f)
    private val margin = extent * 0.10f
    private val gap = maxOf(extent * 0.63f, 3f)

    fun project(point: Coordinate, orbit: StackOrbit = StackOrbit(), spacing: Float = 1f): Offset {
        val x = point.x - (minX + maxX) / 2
        val y = point.y - (minY + maxY) / 2
        val screenX = orbit.cosYaw * x - orbit.sinYaw * y
        val depth = orbit.sinYaw * x + orbit.cosYaw * y
        val height = (levels[point.floor] ?: 0) * gap * spacing
        return Offset(screenX, -depth * orbit.sinTilt - height * orbit.cosTilt)
    }

    fun slab(floor: Int, orbit: StackOrbit, spacing: Float): List<Offset> =
        listOf(
            Coordinate(minX - margin, minY - margin, floor), Coordinate(maxX + margin, minY - margin, floor),
            Coordinate(maxX + margin, maxY + margin, floor), Coordinate(minX - margin, maxY + margin, floor)
        ).map { project(it, orbit, spacing) }

    fun bounds(floors: List<Int>, orbit: StackOrbit = StackOrbit(), spacing: Float = 1f): Rect {
        val corners = floors.ifEmpty { listOf(0) }.flatMap { slab(it, orbit, spacing) }
        return Rect(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
    }
}

internal fun NodeEntity.stackPoint() = Coordinate(x, y, floorLevel, type = nodeType)

@Composable
internal fun stackPortalColor(portal: StackPortal): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.45f
    return when (portal) {
        StackPortal.ELEVATOR -> if (dark) Color(0xFFB9B3FF) else Color(0xFF5145AD)
        StackPortal.STAIRS -> if (dark) Color(0xFFFFC46B) else Color(0xFF8E4D0B)
    }
}

internal fun DrawScope.drawPortal(portal: StackPortal, at: Offset, color: Color, background: Color, radius: Float) {
    drawCircle(background, radius + 2.dp.toPx(), at)
    val points = when (portal) {
        StackPortal.ELEVATOR -> listOf(Offset(-radius, -radius), Offset(radius, -radius), Offset(radius, radius), Offset(-radius, radius))
        StackPortal.STAIRS -> listOf(Offset(0f, -radius), Offset(radius, 0f), Offset(0f, radius), Offset(-radius, 0f))
    }
    val path = Path().apply {
        moveTo(at.x + points.first().x, at.y + points.first().y)
        points.drop(1).forEach { lineTo(at.x + it.x, at.y + it.y) }
        close()
    }
    drawPath(path, color)
}

@Composable
internal fun FloorStack(
    nodes: List<NodeEntity>,
    edges: List<EdgeEntity>,
    floors: List<Int>,
    focusedFloors: Set<Int>,
    route: List<Coordinate>,
    step: RoutePreviewStep?,
    position: Coordinate?,
    spacing: Float,
    selectedPortal: String?,
    onPortalClick: (NodeEntity) -> Unit,
    modifier: Modifier = Modifier,
    emergency: Boolean = false,
    resetTrigger: Int = 0,
    showResetButton: Boolean = true
) {
    val colors = MaterialTheme.colorScheme
    val elevatorColor = stackPortalColor(StackPortal.ELEVATOR)
    val stairsColor = stackPortalColor(StackPortal.STAIRS)
    val routeColor = if (emergency) colors.error else colors.primary
    val density = LocalDensity.current
    val nodesById = remember(nodes) { nodes.associateBy { it.nodeId } }
    val pointsByFloor = remember(nodes) { nodes.groupBy { it.floorLevel } }
    val links = remember(nodesById, edges) {
        edges.mapNotNull { edge ->
            val from = nodesById[edge.fromNodeId]
            val to = nodesById[edge.toNodeId]
            if (from == null || to == null) null else from.stackPoint() to to.stackPoint()
        }
    }
    val (corridors, connectors) = remember(links) { links.partition { (from, to) -> from.floor == to.floor } }
    val corridorsByFloor = remember(corridors) { corridors.groupBy { it.first.floor } }
    val visibleFloors = remember(floors, focusedFloors) { stackVisibleFloors(floors, focusedFloors) }
    val layout = remember(nodes, floors) { FloorStackLayout(nodes.map(NodeEntity::stackPoint), floors) }
    val stepPoints = remember(step) { step?.points.orEmpty().map(NodeEntity::stackPoint) }
    val routeSegments = remember(route) { route.zipWithNext() }
    val routeLegs = remember(route, floors) { floors.flatMap { routeLegsOnFloor(route, it) } }
    val stepSegments = remember(stepPoints) { stepPoints.zipWithNext() }
    val portalNodes = remember(nodes, focusedFloors, step) {
        if (step != null) emptyList() else nodes.filter {
            StackPortal.from(it.nodeType) != null && (focusedFloors.isEmpty() || it.floorLevel in focusedFloors)
        }
    }
    val reveal = remember { Animatable(1f) }
    LaunchedEffect(step) {
        if (step != null) {
            reveal.snapTo(0.4f)
            reveal.animateTo(1f, tween(320))
        }
    }

    var orbit by remember { mutableStateOf(StackOrbit()) }
    var pan by remember(focusedFloors, step) { mutableStateOf(Offset.Zero) }
    var zoom by remember(focusedFloors, step) { mutableStateOf(1f) }
    fun resetCamera() { orbit = StackOrbit(); pan = Offset.Zero; zoom = 1f }
    LaunchedEffect(resetTrigger) { if (resetTrigger != 0) resetCamera() }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val fitFloors = focusedFloors.toList().ifEmpty { floors }
    val bounds = remember(layout, fitFloors, orbit, spacing) { layout.bounds(fitFloors, orbit, spacing) }
    // Preview transitions use the same framed path without delaying direct orbit gestures.
    val focusY = animateFloatAsState(bounds.center.y, tween(if (step == null) 0 else 300), label = "floor focus")
    val focusHeight = animateFloatAsState(bounds.height, tween(if (step == null) 0 else 300), label = "floor frame height")
    val viewportCenter = Offset(viewport.width / 2f, viewport.height / 2f)
    val fit = with(density) {
        minOf(
            (viewport.width - 36.dp.toPx()).coerceAtLeast(1f) / bounds.width,
            (viewport.height - 54.dp.toPx()).coerceAtLeast(1f) / focusHeight.value
        )
    }
    val projectToScreen: (Coordinate) -> Offset = { point ->
        val center = Offset(bounds.center.x, focusY.value)
        viewportCenter + pan + (layout.project(point, orbit, spacing) - center) * (fit * zoom)
    }
    val hitRadius = with(density) { 22.dp.toPx() }
    val tapAt by rememberUpdatedState<(Offset) -> Unit> { position ->
        portalNodes.minByOrNull { (projectToScreen(it.stackPoint()) - position).getDistanceSquared() }
            ?.takeIf { (projectToScreen(it.stackPoint()) - position).getDistanceSquared() <= hitRadius * hitRadius }
            ?.let(onPortalClick)
    }

    val textMeasurer = rememberTextMeasurer()
    val floorTags = remember(textMeasurer, visibleFloors) {
        visibleFloors.associateWith { floor ->
            textMeasurer.measure("L$floor", TextStyle(fontWeight = FontWeight.Bold, fontSize = 11.sp))
        }
    }
    val placesToLabel = remember(nodes, focusedFloors, floors, step) {
        if (step != null || focusedFloors.isEmpty() && floors.size != 1) emptyList()
        else nodes.filter { it.name.isNotBlank() && (it.floorLevel in focusedFloors || floors.size == 1) }
    }
    val placeLabels = remember(placesToLabel, textMeasurer) {
        placesToLabel.sortedByDescending { StackPortal.from(it.nodeType) != null }
            .associateWith { textMeasurer.measure(it.name, TextStyle(fontSize = 11.sp), maxLines = 1) }
    }

    Box(modifier.clip(RoundedCornerShape(24.dp)).background(colors.surfaceContainerLowest)) {
        Canvas(
            Modifier.fillMaxSize()
                .onSizeChanged { viewport = it }
                .semantics {
                    contentDescription = if (focusedFloors.isEmpty()) "3D building map, all floors"
                    else "3D building map, " + focusedFloors.sorted().joinToString { "floor L$it" }
                    stateDescription = "Rotation ${((orbit.yaw.toInt() % 360) + 360) % 360} degrees, " +
                        "tilt ${orbit.tilt.toInt()} degrees, zoom ${(zoom * 100).toInt()} percent"
                    customActions = listOf(
                        CustomAccessibilityAction("Rotate left") { orbit = orbit.copy(yaw = orbit.yaw - 30f); true },
                        CustomAccessibilityAction("Rotate right") { orbit = orbit.copy(yaw = orbit.yaw + 30f); true }
                    )
                }
                .pointerInput(density.density, focusedFloors, step) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var moved = Offset.Zero
                        var dragging = false
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.any { it.isConsumed }) break
                            val motion = event.calculatePan()
                            val magnification = event.calculateZoom()
                            val rotation = event.calculateRotation()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val fingerCount = event.changes.count { it.pressed && it.previousPressed }
                            if (!dragging) {
                                moved += motion
                                dragging = moved.getDistance() > viewConfiguration.touchSlop ||
                                    fingerCount > 1 && (kotlin.math.abs(magnification - 1f) > 0.02f || kotlin.math.abs(rotation) > 1.5f)
                            }
                            if (dragging && fingerCount > 1) {
                                val nextZoom = (zoom * magnification).coerceIn(0.65f, 5f)
                                val origin = Offset(size.width / 2f, size.height / 2f)
                                pan += motion + (centroid - origin - pan) * (1f - nextZoom / zoom)
                                zoom = nextZoom
                                orbit = orbit.copy(yaw = (orbit.yaw + rotation) % 360f)
                            } else if (dragging && fingerCount == 1) {
                                orbit = orbit.drag(motion / density.density)
                            }
                            if (dragging) event.changes.filter { it.pressed && it.position != it.previousPosition }.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onTap = { tapAt(it) }) }
        ) {
            val visible = visibleFloors.toSet()
            fun project(point: Coordinate) = projectToScreen(point)
            fun isFocused(floor: Int) = focusedFloors.isEmpty() || floor in focusedFloors
            fun slabPath(corners: List<Offset>, depth: Float = 0f) = Path().apply {
                moveTo(corners.first().x, corners.first().y + depth)
                corners.drop(1).forEach { lineTo(it.x, it.y + depth) }
                close()
            }
            fun portalColor(portal: StackPortal?) = when (portal) {
                StackPortal.STAIRS -> stairsColor
                StackPortal.ELEVATOR -> elevatorColor
                null -> colors.outline
            }
            fun connectorPattern(type: StackPortal?) = when (type) {
                StackPortal.ELEVATOR -> null
                StackPortal.STAIRS -> PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))
                null -> PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
            }
            fun canDraw(from: Coordinate, to: Coordinate) =
                from.floor in visible && to.floor in visible && (isFocused(from.floor) || isFocused(to.floor))

            val slabs = visibleFloors.associateWith { floor ->
                val center = Offset(bounds.center.x, focusY.value)
                layout.slab(floor, orbit, spacing).map { viewportCenter + pan + (it - center) * (fit * zoom) }
            }
            val orderedFloors = visibleFloors.sortedBy(::isFocused)
            for (floor in orderedFloors) {
                val corners = slabs.getValue(floor)
                val focused = isFocused(floor)
                val slab = slabPath(corners)
                val border = if (focusedFloors.isNotEmpty() && focused) colors.primary else colors.outline
                drawPath(slabPath(corners, 5.dp.toPx()), border.copy(alpha = if (focused) 0.24f else 0.12f))
                drawPath(slab, (if (focusedFloors.isNotEmpty() && focused) colors.primaryContainer else colors.surfaceContainerHigh)
                    .copy(alpha = if (focused) 0.86f else 0.52f))
                drawPath(slab, border.copy(alpha = if (focused) 0.69f else 0.30f), style = Stroke(1.dp.toPx()))
                val ink = colors.onSurfaceVariant.copy(alpha = when {
                    !focused -> 0.16f
                    route.isNotEmpty() -> 0.42f
                    else -> 0.88f
                })
                for ((from, to) in corridorsByFloor[floor].orEmpty()) {
                    drawLine(ink, project(from), project(to), (if (focused) 2.5f else 1.5f).dp.toPx(), StrokeCap.Round)
                }
                for (node in pointsByFloor[floor].orEmpty()) {
                    if (StackPortal.from(node.nodeType) == null) {
                        drawCircle(ink, (if (focused) 3.2f else 1.8f).dp.toPx(), project(node.stackPoint()))
                    }
                }
            }
            for ((from, to) in connectors) {
                if (!canDraw(from, to)) continue
                val type = StackPortal.from(from.type).takeIf { it == StackPortal.from(to.type) }
                drawLine(portalColor(type).copy(alpha = if (focusedFloors.isEmpty()) 0.72f else 0.9f),
                    project(from), project(to), 2.dp.toPx(), pathEffect = connectorPattern(type))
            }
            if (step == null) {
                for (leg in routeLegs) {
                    if (leg.first().floor in visible && isFocused(leg.first().floor)) {
                        drawCasedRoute(leg.map(::project), routeColor, colors.surfaceContainerLowest)
                    }
                }
            }
            for ((from, to) in routeSegments) {
                if (!canDraw(from, to) || (step == null && from.floor == to.floor)) continue
                if (from.floor != to.floor && step == null) {
                    val type = StackPortal.from(from.type).takeIf { it == StackPortal.from(to.type) }
                    drawLine(routeColor, project(from), project(to), 8.dp.toPx(), StrokeCap.Round)
                    drawLine(if (emergency) routeColor else portalColor(type), project(from), project(to),
                        3.dp.toPx(), StrokeCap.Round, pathEffect = connectorPattern(type))
                } else {
                    drawLine(routeColor.copy(alpha = 0.46f), project(from), project(to), 3.dp.toPx(), StrokeCap.Round)
                }
            }
            if (stepPoints.size > 1 && stepPoints.first().floor == stepPoints.last().floor) {
                drawCasedRoute(stepPoints.map(::project), routeColor, colors.surfaceContainerLowest)
            }
            for ((from, to) in stepSegments) {
                if (from.floor == to.floor) continue
                val type = StackPortal.from(from.type).takeIf { from.floor != to.floor && it == StackPortal.from(to.type) }
                drawLine(routeColor, project(from), project(to), 9.dp.toPx(), StrokeCap.Round)
                drawLine((if (type == null) routeColor else portalColor(type)).copy(alpha = reveal.value),
                    project(from), project(to), 4.dp.toPx(), StrokeCap.Round, pathEffect = connectorPattern(type))
            }
            for (floor in orderedFloors) {
                for (node in pointsByFloor[floor].orEmpty()) {
                    val type = StackPortal.from(node.nodeType) ?: continue
                    val at = project(node.stackPoint())
                    if (node.nodeId == selectedPortal) drawCircle(portalColor(type).copy(alpha = 0.30f), 13.dp.toPx(), at)
                    drawPortal(type, at, portalColor(type).copy(alpha = if (isFocused(floor)) 1f else 0.33f),
                        colors.surface.copy(alpha = if (isFocused(floor)) 1f else 0.65f),
                        (if (isFocused(floor)) 4.5f else 3f).dp.toPx())
                }
            }
            val labelBounds = mutableListOf<Rect>()
            for (floor in orderedFloors.asReversed()) {
                val tag = floorTags.getValue(floor)
                val focused = isFocused(floor)
                val padding = 5.dp.toPx()
                val corner = slabs.getValue(floor).minBy { it.x }
                val anchor = corner + Offset(padding, -tag.size.height / 2f)
                val tagBounds = Rect(anchor - Offset(padding, 3.dp.toPx()),
                    Size(tag.size.width + 2 * padding, tag.size.height + 6.dp.toPx()))
                if (labelBounds.any { it.overlaps(tagBounds) }) continue
                labelBounds += tagBounds
                drawRoundRect(if (focused) colors.secondaryContainer else colors.surfaceContainer,
                    tagBounds.topLeft, tagBounds.size, CornerRadius(6.dp.toPx()))
                drawText(tag, color = if (focused) colors.onSecondaryContainer else colors.onSurfaceVariant, topLeft = anchor)
            }
            val marker = stepPoints.lastOrNull() ?: position
            val destination = route.lastOrNull().takeIf { step == null }
            if (destination != null && destination.floor in visible && isFocused(destination.floor)) {
                val portal = StackPortal.from(destination.type)
                drawRouteEndpoint(project(destination), routeColor, colors.surfaceContainerLowest, portal, portalColor(portal))
            }
            if (marker != null && marker.floor in visible && isFocused(marker.floor)) {
                val at = project(marker)
                labelBounds += Rect(at, 12.dp.toPx())
                drawCircle(routeColor.copy(alpha = 0.24f), 12.dp.toPx(), at)
                val portal = StackPortal.from(marker.type).takeIf { stepPoints.size > 1 && stepPoints.first().floor != marker.floor }
                if (portal != null) drawPortal(portal, at, portalColor(portal), colors.surface, 5.dp.toPx())
                else {
                    drawCircle(colors.surface, 7.dp.toPx(), at)
                    drawCircle(routeColor, 4.5.dp.toPx(), at)
                }
            }
            for ((node, label) in placeLabels) {
                val at = project(node.stackPoint()) + Offset(8.dp.toPx(), -label.size.height / 2f)
                val padding = Offset(3.dp.toPx(), 2.dp.toPx())
                val rect = Rect(at - padding, Size(label.size.width + padding.x * 2, label.size.height + padding.y * 2))
                if (rect.left < 0 || rect.right > size.width || rect.top < 0 ||
                    rect.bottom > size.height || labelBounds.any { it.overlaps(rect) }) continue
                labelBounds += rect
                drawRoundRect(colors.surface.copy(alpha = 0.96f), rect.topLeft, rect.size, CornerRadius(5.dp.toPx()))
                drawText(label, color = colors.onSurface, topLeft = at)
            }
        }
        if (showResetButton && (orbit != StackOrbit() || pan != Offset.Zero || zoom != 1f)) {
            FilledTonalIconButton(
                onClick = ::resetCamera,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) { Icon(Icons.Default.CenterFocusStrong, "Reset view") }
        }
    }
}
