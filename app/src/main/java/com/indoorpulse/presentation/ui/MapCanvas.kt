package com.indoorpulse.presentation.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import com.indoorpulse.presentation.viewmodel.MapUiState
import com.indoorpulse.data.local.entity.NodeEntity
import com.indoorpulse.data.local.entity.EdgeEntity

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import com.indoorpulse.data.local.entity.id
import com.indoorpulse.data.local.entity.label
import com.indoorpulse.data.local.entity.type
import com.indoorpulse.presentation.viewmodel.MapViewModel
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import com.indoorpulse.domain.model.Coordinate
import com.indoorpulse.domain.model.floorLevel

private fun getProjectedPoint(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Pair<Float, Float> {
    val l2 = (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)
    if (l2 == 0f) return Pair(x1, y1)
    val t = Math.max(0f, Math.min(1f, ((px - x1) * (x2 - x1) + (py - y1) * (y2 - y1)) / l2))
    return Pair(x1 + t * (x2 - x1), y1 + t * (y2 - y1))
}

private fun distanceTo(x1: Float, y1: Float, x2: Float, y2: Float): Float {
    return Math.hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat()
}

internal fun findNodeAt(
    tap: Offset,
    nodes: List<NodeEntity>,
    currentFloor: Int,
    is3DMode: Boolean,
    hitRadiusPx: Float,
    project: (Float, Float, Int) -> Offset
): NodeEntity? {
    var closest: NodeEntity? = null
    var bestDistance = Float.POSITIVE_INFINITY
    for (node in nodes) {
        if (!is3DMode && node.floorLevel != currentFloor) continue
        val distance = (tap - project(node.x, node.y, node.floorLevel)).getDistance()
        if (distance > hitRadiusPx) continue
        val winsTie = node.floorLevel == currentFloor && closest?.floorLevel != currentFloor
        if (distance < bestDistance || (distance == bestDistance && winsTie)) {
            closest = node
            bestDistance = distance
        }
    }
    return closest
}

@Composable
fun MapCanvas(
    uiState: MapUiState = MapUiState(),
    estimatedPosition: Pair<Float, Float> = Pair(0f, 0f),
    isSurveyorMode: Boolean = false,
    surveyorNodes: List<NodeEntity> = emptyList(),
    surveyorEdges: List<EdgeEntity> = emptyList(),
    lastDroppedNodeId: String? = null,
    snapTargetNodeId: String? = null,
    selectedNode: NodeEntity? = null,
    onNodeSelected: ((NodeEntity?) -> Unit)? = null,
    recenterTrigger: Long = 0L,
    modifier: Modifier = Modifier,
    viewModel: MapViewModel? = null,
    snackbarHostState: SnackbarHostState? = null,
    onNodeLongClick: ((NodeEntity) -> Unit)? = null,
    scale: Float = 20f,
    offset: Offset = Offset(500f, 500f),
    onTransform: ((Float, Offset) -> Unit)? = null,
    onSizeChanged: ((androidx.compose.ui.unit.IntSize) -> Unit)? = null,
    onCrosshairCoordinateChanged: ((Float, Float) -> Unit)? = null,
    allNodes: List<NodeEntity> = surveyorNodes,
    currentPose: Pair<Float, Float>? = estimatedPosition,
    currentHeading: Float = uiState.headingDegrees,
    currentFloor: Int = 0,
    is3DMode: Boolean = false,
    activeRoute: List<Coordinate>? = null,
    onFollowingChanged: (Boolean) -> Unit = {},
    exploreTrigger: Long = 0L,
    emergencyRoute: Boolean = false,
    routeFitTrigger: Int = 0,
    mapControlBounds: List<Rect> = emptyList()
) {
    val colors = MaterialTheme.colorScheme
    val routeColor = if (emergencyRoute) colors.error else colors.primary
    val elevatorColor = stackPortalColor(StackPortal.ELEVATOR)
    val stairsColor = stackPortalColor(StackPortal.STAIRS)
    val route = activeRoute ?: uiState.activeRoute
    val guiding = !isSurveyorMode && route.isNotEmpty()
    val legs = remember(route, currentFloor) { routeLegsOnFloor(route, currentFloor) }
    val floorRoutePoints = remember(legs) { legs.flatten() }
    val turns = remember(legs) { legs.flatMap(::routeTurnPoints).map { Offset(it.x, it.y) }.toSet() }
    val nextTransfer = remember(route, currentFloor) {
        route.zipWithNext().firstOrNull { (from, to) -> from.floor == currentFloor && to.floor != currentFloor }
    }
    val endpoint = route.lastOrNull()?.takeIf { it.floor == currentFloor } ?: nextTransfer?.first
    val endpointPoint = endpoint?.let { Offset(it.x, it.y) }
    val activeNodes = if (surveyorNodes.isNotEmpty()) surveyorNodes else allNodes
    val currentPosePosition = currentPose ?: estimatedPosition
    val hitRadiusPx = maxOf(64f, with(LocalDensity.current) { 48.dp.toPx() })

    val currentNodes by rememberUpdatedState(activeNodes)
    val nodesById = remember(activeNodes) { activeNodes.associateBy { it.nodeId } }
    val nodesByFloor = remember(activeNodes, selectedNode?.nodeId) {
        activeNodes.sortedByDescending { it.nodeId == selectedNode?.nodeId }.groupBy { it.floorLevel }
    }
    val connectedIds = remember(surveyorEdges) {
        surveyorEdges.flatMap { listOf(it.fromNodeId, it.toNodeId) }.toSet()
    }
    val currentIsSurveyorMode by rememberUpdatedState(isSurveyorMode)
    val currentOnNodeLongClick by rememberUpdatedState(onNodeLongClick)
    val currentViewModel by rememberUpdatedState(viewModel)
    val currentSelectedNode by rememberUpdatedState(selectedNode)
    val currentOnNodeSelected by rememberUpdatedState(onNodeSelected)

    var manualCamera by remember(is3DMode) { mutableStateOf(MapCamera(scale, offset, 0f, is3DMode)) }
    var following by remember(is3DMode) { mutableStateOf(true) }
    var canvasSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    LaunchedEffect(following) { onFollowingChanged(following) }
    val liveFloor by rememberUpdatedState(currentFloor)
    val onCrosshair by rememberUpdatedState(onCrosshairCoordinateChanged)
    val recenterProgress = remember(is3DMode) { Animatable(1f) }
    var recenterStart by remember(is3DMode) { mutableStateOf<MapCamera?>(null) }
    var handledExplore by remember { mutableStateOf(exploreTrigger) }
    var handledRouteFit by remember { mutableStateOf(0) }
    var beforeFitScale by remember(is3DMode) { mutableStateOf<Float?>(null) }


    val animatedX by animateFloatAsState(
        targetValue = currentPosePosition.first,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "userX"
    )
    val animatedY by animateFloatAsState(
        targetValue = currentPosePosition.second,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "userY"
    )

    var headingTarget by remember { mutableStateOf(currentHeading) }
    LaunchedEffect(currentHeading) {
        headingTarget += angleDelta(headingTarget, currentHeading)
    }
    val animatedHeading by animateFloatAsState(headingTarget, spring(stiffness = Spring.StiffnessMedium), label = "heading")
    fun center() = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
    val camera by remember(is3DMode) {
        derivedStateOf {
            if (!following || canvasSize.width <= 0) {
                manualCamera
            } else {
                val center = center()
                val position = Offset(animatedX, animatedY)
                val rotation = if (is3DMode) 0f else -animatedHeading
                val start = recenterStart
                val progress = recenterProgress.value
                if (start == null || progress >= 1f) {
                    manualCamera.copy(rotation = rotation).anchor(position, center, liveFloor)
                } else {
                    val anchor = start.pointAt(center, liveFloor)
                    val blended = anchor + (position - anchor) * progress
                    start.copy(
                        scale = start.scale + (manualCamera.scale - start.scale) * progress,
                        rotation = start.rotation + angleDelta(start.rotation, rotation) * progress
                    )
                        .anchor(blended, center, liveFloor)
                }
            }
        }
    }
    LaunchedEffect(recenterTrigger, is3DMode) {
        if (!following) {
            recenterStart = camera
            beforeFitScale?.let { manualCamera = manualCamera.copy(scale = it) }
            beforeFitScale = null
            recenterProgress.snapTo(0f)
            following = true
            recenterProgress.animateTo(1f, tween(durationMillis = 320))
            recenterStart = null
        }
    }
    LaunchedEffect(exploreTrigger) {
        if (exploreTrigger != handledExplore) {
            handledExplore = exploreTrigger
            manualCamera = camera
            following = false
        }
    }
    val fitMargin = with(LocalDensity.current) { 24.dp.toPx() }
    LaunchedEffect(routeFitTrigger) {
        if (routeFitTrigger == handledRouteFit) return@LaunchedEffect
        handledRouteFit = routeFitTrigger
        val viewport = routeViewport(canvasSize, mapControlBounds, fitMargin) ?: return@LaunchedEffect
        val points = floorRoutePoints.map { Offset(it.x, it.y) }
        if (!guiding || points.isEmpty()) return@LaunchedEffect
        val previous = camera
        if (beforeFitScale == null) beforeFitScale = previous.scale
        manualCamera = previous.fit(points + Offset(estimatedPosition.first, estimatedPosition.second), currentFloor, viewport)
        following = false
    }
    fun project(x: Float, y: Float, floor: Int) = camera.project(x, y, floor)
    fun pickNode(tap: Offset) = findNodeAt(tap, currentNodes, currentFloor, is3DMode, hitRadiusPx, ::project)
    LaunchedEffect(is3DMode) {
        snapshotFlow {
            if (following || canvasSize.width <= 0) null else camera.pointAt(center(), liveFloor)
        }.collect { point -> point?.let { onCrosshair?.invoke(it.x, it.y) } }
    }

    val textMeasurer = rememberTextMeasurer()
    val labels = remember(activeNodes, textMeasurer, colors.onSurface) {
        activeNodes.filter { it.name.isNotBlank() && it.nodeType != "Waypoint" }.associate { node ->
            node.nodeId to textMeasurer.measure(node.label, TextStyle(color = colors.onSurface, fontSize = 12.sp))
        }
    }
    val transferLabel = remember(nextTransfer, activeNodes, textMeasurer, colors.onSurface) {
        nextTransfer?.let { (from, to) ->
            val node = activeNodes.firstOrNull { it.floorLevel == from.floor && it.x == from.x && it.y == from.y }
            val name = node?.name?.takeIf { it.isNotBlank() } ?: StackPortal.from(from.type)?.label ?: "Continue"
            textMeasurer.measure("$name → L${to.floor}", TextStyle(color = colors.onSurface, fontSize = 12.sp))
        }
    }
    val labelOrder = remember(nodesByFloor, guiding, endpointPoint, turns) {
        if (!guiding) nodesByFloor else nodesByFloor.mapValues { (_, nodes) ->
            nodes.sortedBy { node ->
                val at = Offset(node.x, node.y)
                when {
                    at == endpointPoint -> 0
                    at in turns -> 1
                    StackPortal.from(node.nodeType) != null -> 2
                    else -> 3
                }
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surfaceContainerLowest)
            .onSizeChanged { size ->
                canvasSize = size
                onSizeChanged?.invoke(size)
            }
            .pointerInput(is3DMode, currentFloor) {
                detectTransformGestures { centroid, pan, zoom, rotation ->
                    manualCamera = camera.gesture(centroid, pan, zoom, rotation, currentFloor)
                    following = false
                    onTransform?.invoke(manualCamera.scale, manualCamera.origin)
                }
            }
            .pointerInput(is3DMode, currentFloor, hitRadiusPx) {
                detectTapGestures(
                    onTap = { tapOffset ->
                        val tappedNode = pickNode(tapOffset)
                        currentOnNodeSelected?.invoke(tappedNode)
                        currentViewModel?.selectNode(tappedNode)
                    },
                    onLongPress = { clickOffset ->
                        if (currentIsSurveyorMode) {
                            val clickedNode = pickNode(clickOffset)
                            if (clickedNode != null) {
                                currentViewModel?.selectNode(clickedNode)
                                if (currentOnNodeLongClick != null) {
                                    currentOnNodeLongClick?.invoke(clickedNode)
                                } else {
                                    currentViewModel?.stageNodeForDeletion(clickedNode)
                                }
                            }
                        }
                    }
                )
            }
    ) {

        val floorsToDraw = if (is3DMode) {
            (nodesByFloor.keys + currentFloor).sorted()
        } else {
            listOf(currentFloor)
        }

        val puckCenter = project(animatedX, animatedY, currentFloor)
        val puckBounds = Rect(puckCenter, 14.dp.toPx())
        val labelBounds = (mapControlBounds + puckBounds).toMutableList()
        val mapViewport = Rect(Offset.Zero, size)
        val endpointOnScreen = endpoint?.takeIf { guiding }?.let { project(it.x, it.y, currentFloor) }
        if (endpointOnScreen != null) labelBounds += Rect(endpointOnScreen, 11.dp.toPx())
        val projectedLegs = if (guiding) legs.map { leg -> leg.map { project(it.x, it.y, currentFloor) } } else emptyList()
        val projectedRouteEdges = projectedLegs.flatMap { it.zipWithNext() }
        floorsToDraw.forEach { floor ->
            val nodesOnFloor = nodesByFloor[floor].orEmpty()

            // Subtle floor plane boundary in 3D mode
            if (is3DMode) {
                val planeHalf = 40f
                val c1 = project(-planeHalf, -planeHalf, floor)
                val c2 = project(planeHalf, -planeHalf, floor)
                val c3 = project(planeHalf, planeHalf, floor)
                val c4 = project(-planeHalf, planeHalf, floor)

                val floorPath = Path().apply {
                    moveTo(c1.x, c1.y)
                    lineTo(c2.x, c2.y)
                    lineTo(c3.x, c3.y)
                    lineTo(c4.x, c4.y)
                    close()
                }
                drawPath(
                    path = floorPath,
                    color = colors.surfaceContainerHigh.copy(alpha = 0.35f)
                )
                drawPath(
                    path = floorPath,
                    color = if (floor == currentFloor) colors.primary.copy(alpha = 0.55f) else colors.outlineVariant,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
                )
                drawText(
                    textMeasurer = textMeasurer,
                    text = "Floor L$floor",
                    topLeft = Offset(c4.x, c4.y - 25f),
                    style = TextStyle(color = if (floor == currentFloor) colors.primary else colors.onSurfaceVariant)
                )
            }

            // 1. Draw Edges connected to this floor
            surveyorEdges.forEach { edge ->
                val from = nodesById[edge.fromNodeId]
                val to = nodesById[edge.toNodeId]
                if (from?.floorLevel == floor && to?.floorLevel == floor) {
                    drawLine(
                        color = colors.outline.copy(alpha = if (guiding) 0.46f else 1f),
                        start = project(from.x, from.y, floor),
                        end = project(to.x, to.y, floor),
                        strokeWidth = (if (guiding) 2 else 3).dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }


            // 1. Draw Ghost Portals (Surveyor Mode Only)
            if (!is3DMode && isSurveyorMode && currentFloor > 0) {
                val validPortals = listOf("Stairs", "Elevator", "Emergency Exit")
                val ghostNodes = activeNodes.filter { 
                    it.floorLevel == currentFloor - 1 && it.type in validPortals 
                }
                
                ghostNodes.forEach { ghost ->
                    val pos = project(ghost.x, ghost.y, currentFloor) // Project to current view
                    val scale = 1f
                    
                    // Draw a dashed circle or semi-transparent marker to indicate a portal below
                    drawCircle(
                        color = colors.outline.copy(alpha = 0.55f),
                        radius = 12f / scale,
                        center = pos,
                        style = Stroke(width = 2f / scale, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
                    )
                    
                    drawContext.canvas.nativeCanvas.drawText(
                        "▼ ${ghost.label}", // Down arrow indicates it's below
                        pos.x + (15f / scale),
                        pos.y,
                        android.graphics.Paint().apply {
                            color = colors.onSurfaceVariant.toArgb()
                            alpha = 160
                            textSize = 24f / scale
                        }
                    )

                }
            }

            // 2. Draw Nodes on this floor
            nodesOnFloor.forEach { node ->
                val pos = project(node.x, node.y, node.floorLevel)
                val scale = 1f

                val isConnected = node.nodeId in connectedIds
                if (!isConnected) {
                    drawCircle(
                        color = colors.error.copy(alpha = if (guiding) 0.30f else 1f),
                        radius = 18f,
                        center = pos,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
                    )
                }

                // CRITICAL FIX: Ensure highlight only draws on the correct floor
                if (currentSelectedNode?.id == node.id && currentSelectedNode?.floorLevel == currentFloor) {
                    drawCircle(
                        color = colors.tertiary,
                        radius = 16f / scale,
                        center = pos,
                        style = Stroke(width = 4f / scale)
                    )
                }

                if (node.nodeType == "Waypoint" || node.name.isBlank()) {
                    drawCircle(
                        color = colors.outline.copy(alpha = if (guiding) 0.37f else 1f),
                        radius = if (guiding) 4f else 6f,
                        center = pos
                    )
                } else {
                    val color = when (node.nodeType) {
                        "Junction" -> colors.outline
                        "Room Door", "Washroom" -> colors.secondary
                        "Stairs" -> if (isSurveyorMode) colors.tertiary else stairsColor
                        "Elevator" -> if (isSurveyorMode) colors.tertiary else elevatorColor
                        "Emergency Exit" -> colors.error
                        "Building Entry" -> colors.primary
                        else -> colors.secondary
                    }
                    val portal = StackPortal.from(node.nodeType).takeUnless { isSurveyorMode }
                    if (portal != null) {
                        drawPortal(portal, pos, color, colors.surfaceContainerLowest, 4.dp.toPx())
                    } else {
                        drawCircle(
                            color = color.copy(alpha = if (guiding) 0.52f else 1f),
                            radius = if (guiding) 5f else 8f,
                            center = pos
                        )
                    }
                }
            }

            if (guiding && floor == currentFloor) {
                val nearest = floorRoutePoints.minByOrNull { point ->
                    (Offset(point.x, point.y) - Offset(estimatedPosition.first, estimatedPosition.second)).getDistanceSquared()
                }
                if (nearest != null) {
                    drawLine(
                        routeColor.copy(alpha = 0.76f), puckCenter, project(nearest.x, nearest.y, currentFloor),
                        2.5.dp.toPx(), StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx()))
                    )
                }
                for (leg in projectedLegs) {
                    drawCasedRoute(leg, routeColor, colors.surfaceContainerLowest)
                }
                for (turn in turns) {
                    val at = project(turn.x, turn.y, floor)
                    drawCircle(colors.surfaceContainerLowest, 4.dp.toPx(), at)
                    drawCircle(routeColor, 2.dp.toPx(), at)
                }
                if (endpoint != null && endpointOnScreen != null) {
                    val portal = StackPortal.from(endpoint.type)
                    val portalColor = when (portal) {
                        StackPortal.ELEVATOR -> elevatorColor
                        StackPortal.STAIRS -> stairsColor
                        null -> routeColor
                    }
                    drawRouteEndpoint(endpointOnScreen, routeColor, colors.surfaceContainerLowest, portal, portalColor)
                }
            }

            for (node in labelOrder[floor].orEmpty()) {
                val label = if (guiding && nextTransfer != null &&
                    node.floorLevel == nextTransfer.first.floor && node.x == nextTransfer.first.x && node.y == nextTransfer.first.y
                ) transferLabel ?: labels[node.id] else labels[node.id]
                if (label == null) continue
                val at = project(node.x, node.y, node.floorLevel)
                val important = guiding && (Offset(node.x, node.y) == endpointPoint || Offset(node.x, node.y) in turns)
                val padding = 4.dp.toPx()
                val gap = when {
                    puckBounds.contains(at) -> 22.dp.toPx()
                    important -> 13.dp.toPx()
                    else -> 6.dp.toPx()
                }
                val bounds = mapLabelBounds(at, label.size.width + padding * 2, label.size.height + padding,
                    gap, mapViewport, labelBounds, projectedRouteEdges, 5.dp.toPx()) ?: continue
                labelBounds += bounds
                val background = if (important) colors.surfaceContainerHighest else colors.surfaceContainerHigh
                drawRoundRect(background, bounds.topLeft, bounds.size, CornerRadius(padding))
                drawText(label, color = if (guiding && !important) colors.onSurfaceVariant else colors.onSurface,
                    topLeft = bounds.topLeft + Offset(padding, padding / 2))
            }

            // 4. Draw Rubber-Band & Smart Snapping (Surveyor Mode Only)
            if (isSurveyorMode && floor == currentFloor && lastDroppedNodeId != null) {
                val lastNode = currentNodes.find { it.nodeId == lastDroppedNodeId }
                val selectionVisible = selectedNode == null || is3DMode || selectedNode.floorLevel == currentFloor
                if (lastNode != null && (is3DMode || lastNode.floorLevel == currentFloor) && selectionVisible) {
                    val startPos = project(lastNode.x, lastNode.y, lastNode.floorLevel)

                    // Priority 1: Manual Selection Override
                    if (selectedNode != null) {
                        val targetPos = project(selectedNode.x, selectedNode.y, selectedNode.floorLevel)
                        drawLine(
                            color = colors.secondary,
                            start = startPos,
                            end = targetPos,
                            strokeWidth = 6f,
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
                        )
                    } 
                    // Priority 2: Proximity Suggestion
                    else if (snapTargetNodeId != null) {
                        val snapNode = currentNodes.find { it.nodeId == snapTargetNodeId }
                        if (snapNode != null && (is3DMode || snapNode.floorLevel == currentFloor)) {
                            val targetPos = project(snapNode.x, snapNode.y, snapNode.floorLevel)
                            val isSameFloor = snapNode.floorLevel == currentFloor
                            val snapColor = if (isSameFloor) colors.tertiary else colors.secondary

                            // Draw the Suggestion Ring
                            drawCircle(
                                color = snapColor,
                                radius = 24f,
                                center = targetPos,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f)
                            )
                            // Draw the dashed rubber band
                            drawLine(
                                color = snapColor,
                                start = startPos,
                                end = targetPos,
                                strokeWidth = 6f,
                                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
                            )
                        }
                    } else {
                        val userCanvasPos = project(currentPosePosition.first, currentPosePosition.second, currentFloor)
                        drawLine(
                            color = colors.tertiary,
                            start = startPos,
                            end = userCanvasPos,
                            strokeWidth = 4f,
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                        )
                    }
                }
            }

            // 5. Draw Blue Dot (Only on current floor)
            if (floor == currentFloor) {
                val puckPos = project(animatedX, animatedY, currentFloor)
                val radius = 11.dp.toPx()
                val radians = Math.toRadians(animatedHeading.toDouble())
                val forward = project(animatedX + kotlin.math.sin(radians).toFloat(),
                    animatedY + kotlin.math.cos(radians).toFloat(), currentFloor) - puckPos
                val arrowAngle = Math.toDegrees(kotlin.math.atan2(forward.x, -forward.y).toDouble()).toFloat()
                drawCircle(colors.primary.copy(alpha = 0.20f), radius * 1.8f, puckPos)
                drawCircle(colors.surfaceContainerLowest, radius + 2.dp.toPx(), puckPos)
                drawCircle(colors.primary, radius, puckPos)
                withTransform({ rotate(degrees = arrowAngle, pivot = puckPos) }) {
                    val arrowPath = Path().apply {
                        moveTo(puckPos.x, puckPos.y - radius * 0.8f)
                        lineTo(puckPos.x + radius * 0.58f, puckPos.y + radius * 0.48f)
                        lineTo(puckPos.x, puckPos.y + radius * 0.2f)
                        lineTo(puckPos.x - radius * 0.58f, puckPos.y + radius * 0.48f)
                        close()
                    }
                    drawPath(path = arrowPath, color = colors.onPrimary)

                }
            }
        }

        // Draw Inter-floor connector edges in 3D Mode
        if (is3DMode) {
            surveyorEdges.forEach { edge ->
                val fromNode = nodesById[edge.fromNodeId]
                val toNode = nodesById[edge.toNodeId]
                if (fromNode != null && toNode != null && fromNode.floorLevel != toNode.floorLevel) {
                    val startPos = project(fromNode.x, fromNode.y, fromNode.floorLevel)
                    val endPos = project(toNode.x, toNode.y, toNode.floorLevel)
                    drawLine(
                        color = colors.tertiary,
                        start = startPos,
                        end = endPos,
                        strokeWidth = 6f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                    )
                }
            }
        }
    }
}
