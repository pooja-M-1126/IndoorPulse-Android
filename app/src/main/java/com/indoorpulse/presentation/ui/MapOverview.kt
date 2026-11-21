package com.indoorpulse.presentation.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Elevator
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Stairs
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.indoorpulse.data.local.entity.EdgeEntity
import com.indoorpulse.data.local.entity.NodeEntity
import com.indoorpulse.data.local.entity.label
import com.indoorpulse.domain.model.Coordinate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapOverview(
    building: String,
    nodes: List<NodeEntity>,
    edges: List<EdgeEntity>,
    route: List<Coordinate>,
    position: Coordinate?,
    onClose: () -> Unit,
    preview: List<NodeEntity>? = null,
    avoidsStairs: Boolean = false,
    emergency: Boolean = false
) {
    val steps = remember(preview) { preview?.let(::routePreviewSteps).orEmpty() }
    var stepIndex by rememberSaveable(preview?.lastOrNull()?.nodeId) { mutableIntStateOf(0) }
    val index = stepIndex.coerceAtMost(steps.lastIndex.coerceAtLeast(0))
    val step = steps.getOrNull(index)
    var floor by rememberSaveable { mutableStateOf<Int?>(null) }
    var spacing by rememberSaveable { mutableFloatStateOf(1f) }
    var showFloorPicker by rememberSaveable { mutableStateOf(false) }
    var resetView by remember { mutableIntStateOf(0) }
    var selectedPortalId by rememberSaveable { mutableStateOf<String?>(null) }
    val floors = remember(nodes, route) { (nodes.map { it.floorLevel } + route.map { it.floor }).distinct().sorted() }
    val nodesById = remember(nodes) { nodes.associateBy { it.nodeId } }
    val selectedPortal = nodesById[selectedPortalId]
    val connections = remember(selectedPortal, nodesById, edges) {
        selectedPortal?.let { portalDestinations(it, nodesById, edges) }.orEmpty()
    }
    fun focusFloor(level: Int?) {
        floor = level
        selectedPortalId = null
    }
    fun inspectPortal(node: NodeEntity) {
        floor = node.floorLevel
        selectedPortalId = node.nodeId
    }
    val portalsOnFloor = remember(floor, nodesById, edges) {
        nodesById.values.filter { it.floorLevel == floor && portalDestinations(it, nodesById, edges).isNotEmpty() }
    }
    val focus = when {
        step != null -> setOf(step.start.floorLevel, step.end.floorLevel)
        floor in floors -> setOfNotNull(floor)
        else -> emptySet()
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(if (preview == null) "3D building map" else "Route preview",
                                style = MaterialTheme.typography.titleLarge)
                            Text(building, style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, if (preview == null) "Close 3D map" else "Close route preview")
                        }
                    },
                    actions = {
                        if (preview == null && floors.isNotEmpty()) {
                            FilledTonalButton(
                                onClick = { showFloorPicker = true },
                                modifier = Modifier.padding(end = 8.dp)
                                    .semantics { contentDescription = "Choose floors and view settings" },
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) {
                                Text(floor?.let { "L$it" } ?: "All floors")
                                Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp))
                            }
                        }
                    }
                )
            }) { insets ->
                BoxWithConstraints(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp)) {
                    val detailsHeight = maxHeight * 0.57f
                    val map: @Composable (Modifier) -> Unit = { modifier ->
                        if (floors.isEmpty()) {
                            Box(modifier, contentAlignment = Alignment.Center) { Text("No mapped floors yet") }
                        } else {
                            FloorStack(nodes, edges, floors, focus, route, step, position, spacing,
                                selectedPortalId, ::inspectPortal, modifier, emergency,
                                resetTrigger = resetView, showResetButton = preview != null)
                        }
                    }
                    val details: @Composable () -> Unit = {
                        if (step == null) {
                            if (floors.isNotEmpty()) {
                                OverviewControls(selectedPortal, connections, portalsOnFloor, ::inspectPortal, ::focusFloor)
                            }
                        } else {
                            PreviewControls(
                                step, steps.last().end.label, index, steps.size, avoidsStairs,
                                previous = { stepIndex = index - 1 },
                                next = { if (index == steps.lastIndex) onClose() else stepIndex = index + 1 }
                            )
                        }
                    }
                    if (maxHeight < 380.dp && maxWidth >= 560.dp) {
                        Row(Modifier.fillMaxSize().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            map(Modifier.weight(1f).fillMaxHeight())
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) { details() }
                        }
                    } else {
                        Column(Modifier.fillMaxSize().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            map(Modifier.weight(1f).fillMaxWidth())
                            Column(Modifier.heightIn(max = detailsHeight).verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) { details() }
                        }
                    }
                }
            }
        }
        if (showFloorPicker && preview == null) {
            FloorPicker(
                floors = floors,
                floor = floor,
                currentFloor = position?.floor?.takeIf { it in floors },
                spacing = spacing,
                onSpacing = { spacing = it },
                onFloor = {
                    focusFloor(it)
                    showFloorPicker = false
                },
                onReset = {
                    spacing = 1f
                    resetView++
                    showFloorPicker = false
                },
                onDismiss = { showFloorPicker = false }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FloorPicker(
    floors: List<Int>,
    floor: Int?,
    currentFloor: Int?,
    spacing: Float,
    onSpacing: (Float) -> Unit,
    onFloor: (Int?) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val levels = remember(floors) { floors.asReversed() }
    val gridState = rememberLazyGridState()
    LaunchedEffect(floor, levels) {
        val index = levels.indexOf(floor)
        if (levels.size > 12 && index >= 0) gridState.scrollToItem(index)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
            Text("Floors and view", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            FloorOption("All floors", floor == null) { onFloor(null) }
            if (currentFloor != null) {
                FloorOption("Current floor · L$currentFloor", floor == currentFloor) { onFloor(currentFloor) }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            val colors = MaterialTheme.colorScheme
            LazyVerticalGrid(
                columns = GridCells.Adaptive(98.dp),
                modifier = Modifier.heightIn(max = 228.dp).weight(1f, fill = false).testTag("Overview floor list"),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(levels, key = { it }) { level ->
                    Surface(selected = level == floor, onClick = { onFloor(level) }, shape = MaterialTheme.shapes.medium,
                        color = if (level == floor) colors.primaryContainer else colors.surfaceContainerHigh,
                        contentColor = if (level == floor) colors.onPrimaryContainer else colors.onSurfaceVariant) {
                        Box(Modifier.height(48.dp).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                            Text("Floor L$level", style = MaterialTheme.typography.labelLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            if (floors.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Floor spacing", style = MaterialTheme.typography.labelLarge)
                    Slider(spacing, onSpacing, Modifier.weight(1f).semantics { contentDescription = "Floor spacing" },
                        valueRange = 0.6f..1.4f, steps = 3)
                }
            }
            TextButton(onClick = onReset) {
                Icon(Icons.Default.CenterFocusStrong, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Reset view")
            }
        }
    }
}

@Composable
private fun FloorOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(selected = selected, onClick = onClick, shape = MaterialTheme.shapes.medium,
        color = if (selected) colors.primaryContainer else Color.Transparent,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            if (selected) Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun OverviewControls(
    portal: NodeEntity?,
    connections: List<Int>,
    portalsOnFloor: List<NodeEntity>,
    onPortal: (NodeEntity) -> Unit,
    onFloor: (Int) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    when {
        portal != null -> {
            Surface(color = colors.surfaceContainer, shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    val kind = StackPortal.from(portal.nodeType)
                    Text("${portal.name.ifBlank { kind?.label.orEmpty() }} · L${portal.floorLevel}",
                        style = MaterialTheme.typography.titleSmall)
                    if (connections.isEmpty()) Text("No connected floor", style = MaterialTheme.typography.bodySmall)
                    else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        connections.forEach { target ->
                            AssistChip(onClick = { onFloor(target) }, label = { Text("View L$target") })
                        }
                    }
                }
            }
        }
        portalsOnFloor.isNotEmpty() -> Row(
            Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            portalsOnFloor.forEach { node ->
                val kind = StackPortal.from(node.nodeType) ?: return@forEach
                val icon = if (kind == StackPortal.ELEVATOR) Icons.Default.Elevator else Icons.Default.Stairs
                AssistChip(onClick = { onPortal(node) }, label = { Text(node.name.ifBlank { kind.label }) },
                    leadingIcon = { Icon(icon, null, Modifier.size(18.dp), tint = stackPortalColor(kind)) })
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        for (portalType in StackPortal.entries) {
            val icon = if (portalType == StackPortal.ELEVATOR) Icons.Default.Elevator else Icons.Default.Stairs
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(icon, null, Modifier.size(17.dp), tint = stackPortalColor(portalType))
                Text(portalType.label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
        }
    }
    Text("Drag to rotate · Pinch to zoom · Two fingers to pan",
        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
}

@Composable
private fun PreviewControls(
    step: RoutePreviewStep,
    destination: String,
    index: Int,
    count: Int,
    avoidsStairs: Boolean,
    previous: () -> Unit,
    next: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val icon = when (step.maneuver) {
        PreviewManeuver.LEFT -> Icons.Default.TurnLeft
        PreviewManeuver.RIGHT -> Icons.Default.TurnRight
        PreviewManeuver.ELEVATOR -> Icons.Default.Elevator
        PreviewManeuver.STAIRS -> Icons.Default.Stairs
        PreviewManeuver.ARRIVE -> Icons.Default.CheckCircle
        else -> Icons.Default.Route
    }
    Text(if (avoidsStairs) "$destination · Avoid stairs" else destination,
        style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
    LinearProgressIndicator(progress = { (index + 1f) / count }, modifier = Modifier.fillMaxWidth())
    Card(colors = CardDefaults.cardColors(containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(14.dp).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(26.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Step ${index + 1} of $count", style = MaterialTheme.typography.labelMedium)
                Text(step.title, style = MaterialTheme.typography.titleLarge)
                Text(step.detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = previous, enabled = index > 0, modifier = Modifier.weight(1f)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Previous")
        }
        Button(onClick = next, modifier = Modifier.weight(1f)) {
            Text(if (index == count - 1) "Done" else "Next")
            if (index < count - 1) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp))
            }
        }
    }
}
