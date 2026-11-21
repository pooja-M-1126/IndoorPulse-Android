package com.indoorpulse.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.filled.Palette
import com.indoorpulse.ui.theme.Appearance
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.Accessible
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.material.icons.filled.Elevator
import androidx.compose.material.icons.filled.Stairs
import androidx.compose.material.icons.filled.Accessible
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.indoorpulse.data.local.entity.BuildingEntity
import com.indoorpulse.data.local.entity.NodeEntity
import com.indoorpulse.data.local.entity.type
import com.indoorpulse.data.local.entity.id
import com.indoorpulse.data.local.entity.label
import com.indoorpulse.domain.model.Coordinate
import com.indoorpulse.presentation.ui.components.EmergencyFab
import com.indoorpulse.presentation.ui.components.ArrivalCard
import com.indoorpulse.presentation.ui.components.NavigationBanner
import com.indoorpulse.presentation.ui.components.SearchBottomSheetContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddLocation
import androidx.compose.material.icons.filled.Explore
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import com.indoorpulse.presentation.viewmodel.MapViewModel
import com.indoorpulse.presentation.viewmodel.TrackingState
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.ui.window.Dialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MapViewModel,
    discoveryLocation: android.location.Location? = null,
    locationStatus: String? = null,
    onFindNearby: (() -> Unit)? = null,
    appearance: Appearance = Appearance.SYSTEM,
    onAppearanceChange: ((Appearance) -> Unit)? = null
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var showAppearance by rememberSaveable { mutableStateOf(false) }
    val jsonPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.importMapFromJson(context, it) }
    }
    LaunchedEffect(viewModel, context) {
        viewModel.routeError.collect { errorMessage ->
            withContext(Dispatchers.Main.immediate) {
                Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
            }
        }
    }

    val isSurveyorMode by viewModel.isSurveyorMode.collectAsState()
    val isSosActive by viewModel.isSosActive.collectAsState()
    val activeDestination by viewModel.activeDestination.collectAsState()
    val activeRoute by viewModel.activeRoute.collectAsState()
    val arrival by viewModel.arrival.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val estimatedPosition by viewModel.estimatedPosition.collectAsState()
    val activeNodes by viewModel.activeNodes.collectAsState()
    val surveyorEdges by viewModel.edges.collectAsState()
    val lastDroppedNodeId by viewModel.lastDroppedNodeId.collectAsState()
    val snapTargetNodeId by viewModel.snapTargetNodeId.collectAsState()
    val currentFloor by viewModel.currentFloor.collectAsState()
    val is3DMode by viewModel.is3DMode.collectAsState()
    val availableFloors by viewModel.availableFloors.collectAsState()
    val selectedNode by viewModel.selectedNode.collectAsState()
    val recenterTrigger by viewModel.recenterTrigger.collectAsState()
    val exploreTrigger by viewModel.exploreTrigger.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val transitInfo by viewModel.transitState.collectAsState()
    val isAccessible by viewModel.accessibleMode.collectAsState()
    val trackingState by viewModel.trackingState.collectAsState()
    val stagedNode by viewModel.stagedNode.collectAsState()
    val maps by viewModel.buildingMaps.collectAsState()
    val buildingId by viewModel.activeBuildingId.collectAsState()
    val buildingName = maps.firstOrNull { it.building.buildingId == buildingId }?.building?.name
    var openingId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var leaving by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable(buildingId) { mutableStateOf(false) }
    var showOverview by rememberSaveable(buildingId) { mutableStateOf(false) }
    var routeFitTrigger by remember(buildingId) { mutableIntStateOf(0) }
    var topLeftMapControls by remember { mutableStateOf(Rect.Zero) }
    var topRightMapControls by remember { mutableStateOf(Rect.Zero) }
    var navigationCardBounds by remember { mutableStateOf(Rect.Zero) }
    var previewDestination by rememberSaveable(buildingId) { mutableStateOf<String?>(null) }
    var previewAvoidsStairs by rememberSaveable(buildingId) { mutableStateOf(false) }
    var previewPath by remember(buildingId) { mutableStateOf<List<NodeEntity>?>(null) }
    LaunchedEffect(buildingId, previewDestination) {
        val destinationId = previewDestination ?: return@LaunchedEffect
        if (trackingState != TrackingState.INDOORS) return@LaunchedEffect
        previewPath = viewModel.previewRoute(destinationId, previewAvoidsStairs)
        if (previewPath == null) previewDestination = null
    }

    var following by remember { mutableStateOf(true) }
    var targetSyncX by remember { mutableStateOf(0f) }
    var targetSyncY by remember { mutableStateOf(0f) }

    var selectedNodeType by rememberSaveable { mutableStateOf("Waypoint") }
    var showNamingDialog by rememberSaveable { mutableStateOf(false) }
    var nodeNameInput by rememberSaveable { mutableStateOf("") }

    val opening = maps.firstOrNull { it.building.buildingId == openingId }?.building
    if (opening != null && trackingState == TrackingState.OUTDOORS && !creating) {
        BuildingEntryDialog(opening, viewModel::getBuildingEntryPoints, onEnter = { node ->
            viewModel.confirmBuildingEntry(opening.buildingId, node)
            openingId = null
        }, onDismiss = {
            openingId = null
        })
    }
    if (creating) CreateMapDialog(onCreate = { name, radius ->
        viewModel.createNewBuilding(name, radius)
        creating = false
    }, onDismiss = { creating = false })
    if (leaving) AlertDialog(
        onDismissRequest = { leaving = false }, title = { Text("Leave this map?") },
        text = { Text("Your mapped points are saved. Return to the building library?") },
        confirmButton = { Button(onClick = { viewModel.confirmBuildingExit(); leaving = false }) { Text("Leave map") } },
        dismissButton = { TextButton(onClick = { leaving = false }) { Text("Stay here") } }
    )
    if (searching && trackingState == TrackingState.INDOORS && !isSurveyorMode) {
        ModalBottomSheet(onDismissRequest = { searching = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            SearchBottomSheetContent(activeNodes, onPreview = { node ->
                searching = false
                previewAvoidsStairs = isAccessible
                previewDestination = node.id
            }) { node ->
                searching = false
                viewModel.selectNode(null)
                viewModel.requestRoute(node.nodeId)
            }
        }
    }

    if (trackingState == TrackingState.INDOORS) {
        val path = previewPath
        if (path != null) {
            MapOverview(buildingName.orEmpty(), activeNodes, surveyorEdges,
                route = remember(path) { path.map(NodeEntity::stackPoint) },
                position = null,
                onClose = {
                    previewDestination = null
                    previewPath = null
                },
                preview = path, avoidsStairs = previewAvoidsStairs)
        } else if (showOverview) {
            MapOverview(buildingName.orEmpty(), activeNodes, surveyorEdges,
                route = activeRoute,
                position = Coordinate(estimatedPosition.first, estimatedPosition.second, currentFloor),
                onClose = { showOverview = false }, emergency = isSosActive)
        }
    }

    if (showNamingDialog) {
        AlertDialog(
            onDismissRequest = { showNamingDialog = false },
            title = { Text("Add ${selectedNodeType.lowercase()}") },
            text = {
                OutlinedTextField(
                    value = nodeNameInput,
                    onValueChange = { nodeNameInput = it },
                    label = { Text("Name (optional)") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.forceDropNewNode(nodeNameInput, selectedNodeType)
                    nodeNameInput = ""
                    showNamingDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNamingDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showAppearance && onAppearanceChange != null) {
        AlertDialog(
            onDismissRequest = { showAppearance = false },
            title = { Text("Appearance") },
            text = {
                Column {
                    Appearance.entries.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                                selected = option == appearance, role = Role.RadioButton,
                                onClick = { onAppearanceChange(option) }
                            ).padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = option == appearance, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(option.label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAppearance = false }) { Text("Done") } }
        )
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    BackHandler(trackingState == TrackingState.INDOORS && !drawerState.isOpen && !searching && !leaving && stagedNode == null && !showNamingDialog) {
        leaving = true
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Map tools",
                    modifier = Modifier.padding(16.dp),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                HorizontalDivider()
                if (trackingState == TrackingState.INDOORS) NavigationDrawerItem(
                    label = { Text("Surveyor Mode") },
                    selected = isSurveyorMode,
                    onClick = {
                        viewModel.toggleSurveyorMode(!isSurveyorMode)
                        scope.launch { drawerState.close() }
                    },
                    badge = { Switch(checked = isSurveyorMode, onCheckedChange = null) }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                if (trackingState == TrackingState.INDOORS) NavigationDrawerItem(
                    label = { Text("Export Map Backup") },
                    icon = { Icon(Icons.Default.Save, contentDescription = "Export") },
                    selected = false,
                    onClick = {
                        viewModel.exportMapToJson(context)
                        scope.launch { drawerState.close() }
                    }
                )
                NavigationDrawerItem(
                    label = { Text("Import Map Backup") },
                    icon = { Icon(Icons.Default.UploadFile, contentDescription = "Import") },
                    selected = false,
                    onClick = {
                        jsonPickerLauncher.launch("application/json")
                        scope.launch { drawerState.close() }
                    }
                )
                if (onAppearanceChange != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    NavigationDrawerItem(
                        label = { Text("Appearance") },
                        icon = { Icon(Icons.Default.Palette, null) },
                        badge = { Text(appearance.label, style = MaterialTheme.typography.labelMedium) },
                        selected = false,
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                showAppearance = true
                            }
                        }
                    )
                }
            }
        }
    ) {
        Scaffold(
            containerColor = colors.surface,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(buildingName.takeIf { trackingState == TrackingState.INDOORS } ?: "IndoorPulse",
                                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                            if (trackingState == TrackingState.INDOORS) Text(
                                if (isSosActive) "Exit route" else if (isSurveyorMode) "Survey · L$currentFloor" else "Navigate · L$currentFloor",
                                style = MaterialTheme.typography.labelMedium)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        if (trackingState == TrackingState.INDOORS) {
                            var mapMenu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { mapMenu = true }) { Icon(Icons.Default.MoreVert, "Map options") }
                                DropdownMenu(mapMenu, { mapMenu = false }) {
                                    DropdownMenuItem(text = { Text("Leave map") }, onClick = { mapMenu = false; leaving = true })
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = if (isSosActive) Color(0xFF7C231C) else colors.surface,
                        titleContentColor = if (isSosActive) Color.White else colors.onSurface,
                        navigationIconContentColor = if (isSosActive) Color.White else colors.primary,
                        actionIconContentColor = if (isSosActive) Color.White else colors.onSurface
                    )
                )
            }
        ) { innerPadding ->

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(colors.background)
            ) {
                if (trackingState == TrackingState.INDOORS) {
                    // 1. RENDER MAP CANVAS (using activeNodes instead of raw DB nodes)
                MapCanvas(
                    uiState = uiState,
                    emergencyRoute = isSosActive,
                    estimatedPosition = estimatedPosition,
                    isSurveyorMode = isSurveyorMode,
                    surveyorNodes = activeNodes,
                    surveyorEdges = surveyorEdges,
                    lastDroppedNodeId = lastDroppedNodeId,
                    snapTargetNodeId = snapTargetNodeId,
                    selectedNode = selectedNode,
                    onNodeSelected = { viewModel.selectNode(it) },
                    recenterTrigger = recenterTrigger,
                    exploreTrigger = exploreTrigger,
                    onFollowingChanged = { following = it },
                    viewModel = viewModel,
                    onNodeLongClick = { clickedNode ->
                        viewModel.stageNodeForDeletion(clickedNode)
                    },
                    onCrosshairCoordinateChanged = { x, y ->
                        targetSyncX = x
                        targetSyncY = y
                    },
                    allNodes = activeNodes,
                    currentFloor = currentFloor,
                    is3DMode = isSurveyorMode && is3DMode,
                    activeRoute = activeRoute,
                    routeFitTrigger = routeFitTrigger,
                    mapControlBounds = if (activeDestination == null) emptyList() else
                        listOf(topLeftMapControls, topRightMapControls, navigationCardBounds).filter { it.width > 0f && it.height > 0f },
                    modifier = Modifier.fillMaxSize().semantics { contentDescription = "Indoor map, floor L$currentFloor" }
                )

                if (isSurveyorMode && !following) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        // Draw a subtle, professional crosshair
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Center Crosshair",
                            tint = colors.primary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                // 3. CONTEXTUAL OVERLAYS
                if (isSurveyorMode) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = colors.surfaceContainerHigh,
                        contentColor = colors.onSurface
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            val heading = if (uiState.hasHeading) "%.0f°".format(uiState.headingDegrees) else "Waiting for heading"
                            val accuracy = uiState.headingAccuracyDegrees?.let { "  ±$it°" }.orEmpty()
                            Text(heading + accuracy, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "X %.1f   Y %.1f m".format(estimatedPosition.first, estimatedPosition.second),
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }


                    // RENDER SURVEYOR HUD AT ABSOLUTE BOTTOM
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .imePadding() // Pushes up if keyboard opens
                    ) {
                        SurveyorHUD(
                            viewModel = viewModel,
                            selectedNodeType = selectedNodeType,
                            onNodeTypeSelected = { selectedNodeType = it },
                            onDropNode = {
                                if (selectedNodeType == "Waypoint") {
                                    viewModel.forceDropNewNode("", selectedNodeType)
                                } else {
                                    nodeNameInput = ""
                                    showNamingDialog = true
                                }
                            },
                            onUndo = { viewModel.performUndo() },
                            onRedo = { viewModel.performRedo() },
                            onSyncPosition = {
                                viewModel.syncPositionTo(targetSyncX, targetSyncY)
                            },
                            canUndo = canUndo,
                            canRedo = canRedo,
                            canSync = !following,
                            origin = activeNodes.firstOrNull { it.nodeId == lastDroppedNodeId },
                            suggestion = activeNodes.firstOrNull { it.nodeId == snapTargetNodeId }
                        )
                    }
                } else {
                    Column(Modifier.align(Alignment.TopStart).onGloballyPositioned { topLeftMapControls = it.boundsInParent() }.padding(12.dp)) {
                        FilterChip(selected = isAccessible, onClick = viewModel::toggleAccessibleMode, label = { Text("Avoid stairs") })
                        if (activeDestination == null && arrival == null) {
                            EmergencyFab(isActive = isSosActive, onToggle = viewModel::toggleSosMode)
                        }
                    }
                    if (activeDestination == null && arrival == null) {
                        val destination = selectedNode
                        if (destination != null) {
                            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(18.dp)) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(destination.label, style = MaterialTheme.typography.titleMedium)
                                    Text("${destination.type} · L${destination.floorLevel}", style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { viewModel.selectNode(null); viewModel.requestRoute(destination.id) }) { Text("Directions") }
                                        TextButton(onClick = {
                                            viewModel.setFloor(destination.floorLevel)
                                            viewModel.syncPositionTo(destination.x, destination.y)
                                            viewModel.recenterCamera()
                                        }) { Text("Start here") }
                                    }
                                }
                            }
                        } else FilledTonalButton(
                            onClick = { searching = true },
                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp).heightIn(min = 52.dp)
                        ) {
                            Icon(Icons.Default.Search, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Find a destination")
                        }
                    }
                }

                val completed = arrival
                val destination = activeDestination
                if (completed != null) {
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        ArrivalCard(
                            destinationName = completed.destination.label,
                            floor = completed.destination.floorLevel,
                            isExit = completed.isExit,
                            onDone = viewModel::dismissArrival,
                            onLeave = { leaving = true }
                        )
                    }
                } else if (destination != null) {
                    Box(Modifier.align(Alignment.BottomCenter).onGloballyPositioned { navigationCardBounds = it.boundsInParent() }) {
                        NavigationBanner(
                            destination.label, isSosActive, viewModel::clearRoute,
                            onShowRoute = if (activeRoute.any { it.floor == currentFloor }) {
                                { routeFitTrigger++ }
                            } else null
                        )
                    }
                }

                // RIGHT-SIDE FLOATING CONTROLS (Floor Selector, 3D Button, Recenter Button)
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .onGloballyPositioned { topRightMapControls = it.boundsInParent() }
                        .padding(top = 12.dp, end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    // Filter out floors if standard user, or allow extended range for surveyor
                    val displayFloors = if (isSurveyorMode) {
                        val min = (availableFloors.minOrNull() ?: 0)
                        val max = (availableFloors.maxOrNull() ?: 0)
                        ((min - 1)..(max + 1)).toList().sortedDescending() // HIGHEST TO LOWEST
                    } else {
                        availableFloors.sortedDescending() // HIGHEST TO LOWEST
                    }

                    var floorsExpanded by remember { mutableStateOf(false) }
                    Box {
                        FilledTonalButton(
                            onClick = { floorsExpanded = true },
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Text("L$currentFloor ▾")
                        }
                        DropdownMenu(expanded = floorsExpanded, onDismissRequest = { floorsExpanded = false }) {
                            displayFloors.forEach { floor ->
                                DropdownMenuItem(text = { Text("Floor L$floor") }, onClick = {
                                    if (currentFloor != floor) viewModel.setFloor(floor)
                                    floorsExpanded = false
                                })
                            }
                        }
                    }
                    FilledTonalButton(
                        onClick = { if (isSurveyorMode) viewModel.toggle3DMode() else showOverview = true },
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        if (!isSurveyorMode) {
                            Icon(Icons.Default.ViewInAr, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("3D map")
                        } else {
                            Text(if (is3DMode) "2D" else "3D")
                        }
                    }
                    val cameraMode = when {
                        !following -> "Free pan and rotate"
                        isSurveyorMode && is3DMode -> "Centered on you"
                        else -> "Following your heading"
                    }
                    FilledTonalButton(
                        onClick = { if (following) viewModel.exploreMap() else viewModel.recenterCamera() },
                        modifier = Modifier.semantics { stateDescription = cameraMode },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(if (following) Icons.Default.Explore else Icons.Default.MyLocation, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (following) "Explore" else "Recenter", style = MaterialTheme.typography.labelMedium)
                    }
                }

                // TRANSIT UI OVERLAY
                if (transitInfo != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.85f)) // Dim the map heavily
                            .clickable(enabled = false) {} // Consume clicks so map underneath isn't touched
                            .pointerInput(Unit) { detectTapGestures { } }
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (transitInfo!!.portalType == "Elevator") Icons.Default.Elevator else Icons.Default.Stairs,
                                contentDescription = "Transit",
                                tint = Color.White,
                                modifier = Modifier.size(80.dp)
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            
                            Text(
                                text = "Take ${transitInfo!!.portalType}",
                                color = Color.White,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "to Floor L${transitInfo!!.targetFloor}",
                                color = Color.Cyan,
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Black
                            )
                            
                            Spacer(modifier = Modifier.height(64.dp))
                            
                            Button(
                                onClick = { viewModel.completeTransit() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(64.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("I've Arrived at L${transitInfo!!.targetFloor}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                } else {
                    BuildingLibrary(
                        maps, onOpen = { openingId = it.buildingId }, onCreate = { creating = true },
                        location = discoveryLocation, locationStatus = locationStatus, onFindNearby = onFindNearby
                    )
                }
            }
        }
    }

    if (stagedNode != null) {
        NodeEditDialog(
            node = stagedNode!!,
            currentFloor = currentFloor,
            viewModel = viewModel,
            onDismiss = { viewModel.clearStagedNode() }
        )
    }
}

@Composable
fun SurveyorHUD(
    viewModel: MapViewModel,
    selectedNodeType: String,
    onNodeTypeSelected: (String) -> Unit,
    onDropNode: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSyncPosition: () -> Unit = {},
    modifier: Modifier = Modifier,
    canUndo: Boolean = true,
    canRedo: Boolean = true,
    canSync: Boolean = true,
    origin: NodeEntity? = null,
    suggestion: NodeEntity? = null
) {
    val selected by viewModel.selectedNode.collectAsState()
    val node = selected
    val portal = node?.nodeType in listOf("Stairs", "Elevator")
    val target = node?.takeUnless { it.id == origin?.id } ?: suggestion
    Surface(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 4.dp
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when {
                    origin != null && target != null -> "Link ${origin.label} → ${target.label}"
                    origin != null -> "Continuing from ${origin.label}"
                    node != null -> "Selected · ${node.label}"
                    else -> "Stand at a landmark, then add a point."
                },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    var typesOpen by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { typesOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("$selectedNodeType ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(typesOpen, { typesOpen = false }) {
                        surveyNodeTypes.forEach { type ->
                            DropdownMenuItem(text = { Text(type) }, onClick = { onNodeTypeSelected(type); typesOpen = false })
                        }
                    }
                }
                Button(onClick = onDropNode, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.AddLocation, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add point")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                SurveyAction("Undo", Icons.AutoMirrored.Filled.Undo, canUndo, onUndo)
                SurveyAction("Redo", Icons.AutoMirrored.Filled.Redo, canRedo, onRedo)
                SurveyAction("Position", Icons.Default.MyLocation, canSync, onSyncPosition)
                SurveyAction("Edit", Icons.Default.Edit, node != null) { node?.let(viewModel::stageNodeForDeletion) }
                SurveyAction("Link", Icons.Default.Link, origin != null && target != null) {
                    target?.let { viewModel.selectNode(it); viewModel.snapToNearestNode() }
                }
            }
            if (portal) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { viewModel.extrudePortal(1) }) { Text("Extend up a floor") }
                    TextButton(onClick = { viewModel.extrudePortal(-1) }) { Text("Extend down a floor") }
                }
            }
        }
    }
}

private val surveyNodeTypes = listOf("Waypoint", "Junction", "Room Door", "Washroom", "Stairs", "Elevator", "Emergency Exit", "Building Entry")

@Composable
private fun RowScope.SurveyAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Column(
        Modifier.weight(1f).clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .heightIn(min = 48.dp).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp), tint = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
fun NodeEditDialog(
    node: NodeEntity,
    currentFloor: Int,
    viewModel: MapViewModel,
    onDismiss: () -> Unit
) {
    var rename by rememberSaveable(node.nodeId) { mutableStateOf(false) }
    var name by rememberSaveable(node.nodeId, node.name) { mutableStateOf(node.name) }
    var confirmDelete by rememberSaveable(node.nodeId) { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete ${node.label}?") },
            text = { Text("This removes the point and its links from the map.") },
            confirmButton = { TextButton(onClick = { viewModel.deleteNode(node); onDismiss() }) { Text("Delete point") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep point") } })
        return
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header: Node ID & Floor
                Column {
                    Text(
                        text = "Edit Node: ${node.label}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text("Floor L${node.floorLevel}", style = MaterialTheme.typography.labelMedium)
                    if (rename) {
                        OutlinedTextField(name, { name = it }, label = { Text("Point name") }, singleLine = true)
                        TextButton(onClick = { viewModel.renameNode(node, name); rename = false }, enabled = name.isNotBlank()) { Text("Save name") }
                    } else {
                        TextButton(onClick = { rename = true }) { Text("Rename point") }
                    }
                }

                // Type selector
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Node Type",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    val types = surveyNodeTypes
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        types.forEach { type ->
                            FilterChip(
                                selected = node.nodeType.equals(type, ignoreCase = true),
                                onClick = { viewModel.updateNodeType(node, type) },
                                label = { Text(type, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }

                // Retroactive Portal Link (if Stairs or Elevator)
                if (node.nodeType.equals("Stairs", ignoreCase = true) || node.nodeType.equals("Elevator", ignoreCase = true)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider()
                        Text(
                            text = "Link an existing portal",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.linkPortalToFloor(node, node.floorLevel + 1)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Link to L${node.floorLevel + 1}")
                            }
                            OutlinedButton(
                                onClick = {
                                    viewModel.linkPortalToFloor(node, node.floorLevel - 1)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Link to L${node.floorLevel - 1}")
                            }
                        }
                    }
                }

                HorizontalDivider()

                OutlinedButton(
                    onClick = {
                        viewModel.selectNode(node)
                        viewModel.snapToNearestNode()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Link from Current Origin")
                }
                if (node.floorLevel == currentFloor) {
                    OutlinedButton(onClick = {
                        viewModel.syncPositionTo(node.x, node.y)
                        viewModel.recenterCamera()
                        onDismiss()
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Set my position here")
                    }
                    OutlinedButton(onClick = {
                        viewModel.alignHeadingToward(node)
                        onDismiss()
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Facing ${node.label} · Align heading")
                    }
                }
                if (node.floorLevel != currentFloor) {
                    OutlinedButton(
                        onClick = {
                            viewModel.moveNodeToCurrentFloor(node)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Move Node to Floor L$currentFloor")
                    }
                }

                OutlinedButton(onClick = { viewModel.resumePathFromNode(node); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Resume path here")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Delete point", color = MaterialTheme.colorScheme.error)
                    }
                    Button(onClick = onDismiss) { Text("Done") }
                }
            }
        }
    }
}


