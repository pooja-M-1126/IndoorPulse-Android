package com.indoorpulse.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.indoorpulse.data.sensor.SensorDataSource
import com.indoorpulse.data.sensor.bearingDegrees
import com.indoorpulse.domain.model.Coordinate
import com.indoorpulse.domain.model.IndoorMap
import com.indoorpulse.domain.model.Node
import com.indoorpulse.domain.model.Edge
import com.indoorpulse.domain.model.NodeType
import com.indoorpulse.domain.usecase.FindShortestPathUseCase
import com.indoorpulse.domain.usecase.GpsLeashResult
import com.indoorpulse.domain.usecase.RoutingEngine
import com.indoorpulse.data.local.IndoorPulseDatabase
import com.indoorpulse.data.local.ensureDemoMap
import com.indoorpulse.data.local.entity.BuildingEntity
import com.indoorpulse.data.local.entity.EdgeEntity
import com.indoorpulse.data.local.entity.FloorEntity
import com.indoorpulse.data.local.entity.NodeEntity
import com.indoorpulse.domain.navigation.ParticleFilter
import com.indoorpulse.data.local.entity.id
import com.indoorpulse.data.local.entity.label
import com.indoorpulse.data.local.entity.type
import com.indoorpulse.data.local.entity.startNodeId
import com.indoorpulse.data.local.entity.endNodeId
import com.indoorpulse.data.local.entity.weight
import com.indoorpulse.domain.model.floorLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.content.ContentValues
import android.content.Context
import android.location.Location
import com.indoorpulse.data.sensor.isUsableForDiscovery
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class NavigationArrival(val destination: NodeEntity, val isExit: Boolean)

private const val ARRIVAL_RADIUS_METRES = 2.5

enum class ViewMode { MAP_2D, AR_CAMERA }

enum class TrackingState { OUTDOORS, INDOORS }

data class TransitInfo(
    val targetFloor: Int,
    val portalType: String,
    val targetX: Float,
    val targetY: Float,
    val nextNodeIndex: Int // How many nodes to pop off the route upon arrival
)

sealed interface SurveyorAction {
    data class DeleteNode(val node: NodeEntity, val edges: List<EdgeEntity>, val previousOrigin: String?) : SurveyorAction
    data class DropNode(val node: NodeEntity, val edges: List<EdgeEntity>) : SurveyorAction {
        constructor(node: NodeEntity, edge: EdgeEntity?) : this(node, listOfNotNull(edge))
    }
    data class SnapEdge(val edge: EdgeEntity) : SurveyorAction
}

class MapViewModel(
    private val sensorManager: android.hardware.SensorManager,
    private val sensorDataSource: SensorDataSource,
    private val findShortestPathUseCase: FindShortestPathUseCase,
    private val database: IndoorPulseDatabase,
    private val includeDemo: Boolean = false
) : ViewModel() {

    private val mapDao = database.mapDao()
    val buildingMaps = mapDao.observeBuildingMaps().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val trackingState = MutableStateFlow(TrackingState.OUTDOORS)
    private var entryAnchorNode: NodeEntity? = null
    private var latestGpsLocation: Location? = null

    private val _activeBuildingId = MutableStateFlow<String?>(null)
    val activeBuildingId: StateFlow<String?> = _activeBuildingId.asStateFlow()

    val currentViewMode = MutableStateFlow(ViewMode.MAP_2D)
    val isAutoArEnabled = MutableStateFlow(true)
    val estimatedPosition = MutableStateFlow(Pair(0f, 0f))
    
    private val _isSurveyorMode = MutableStateFlow(false)
    val isSurveyorMode: StateFlow<Boolean> = _isSurveyorMode.asStateFlow()
    val lastDroppedNodeId = MutableStateFlow<String?>(null)
    val snapTargetNodeId = MutableStateFlow<String?>(null)
    private val _selectedNode = MutableStateFlow<NodeEntity?>(null)
    val selectedNode: StateFlow<NodeEntity?> = _selectedNode.asStateFlow()
    private val _stagedNode = MutableStateFlow<NodeEntity?>(null)
    val stagedNode: StateFlow<NodeEntity?> = _stagedNode.asStateFlow()

    private val _transitState = MutableStateFlow<TransitInfo?>(null)
    val transitState: StateFlow<TransitInfo?> = _transitState.asStateFlow()

    fun selectNode(node: NodeEntity?) {
        _selectedNode.value = node
    }

    fun resumePathFromNode(node: NodeEntity) {
        lastDroppedNodeId.value = node.nodeId
        snapTargetNodeId.value = null
        _selectedNode.value = node
        _uiState.update { it.copy(statusMessage = "Resumed path from ${node.label}") }
    }

    private val _currentFloor = MutableStateFlow(0)
    val currentFloor: StateFlow<Int> = _currentFloor.asStateFlow()

    private val _is3DMode = MutableStateFlow(false)
    val is3DMode: StateFlow<Boolean> = _is3DMode.asStateFlow()

    fun clearSelectedNode() {
        lastDroppedNodeId.value = null
        snapTargetNodeId.value = null
        _selectedNode.value = null
    }

    fun setFloor(floor: Int) {
        _currentFloor.value = floor
        clearSelectedNode() // CRITICAL: Break temporal continuity when changing floors
        _uiState.update { it.copy(statusMessage = "Switched to floor L$floor") }
    }

    fun toggle3DMode() {
        _is3DMode.value = !_is3DMode.value
    }
    val recenterTrigger = MutableStateFlow(0L)
    val exploreTrigger = MutableStateFlow(0L)
    private val _isSosActive = MutableStateFlow(false)
    val isSosActive: StateFlow<Boolean> = _isSosActive.asStateFlow()
    val activeDestination = MutableStateFlow<NodeEntity?>(null)
    private val _arrival = MutableStateFlow<NavigationArrival?>(null)
    val arrival = _arrival.asStateFlow()
    private var routeRequest: Job? = null

    private val _accessibleMode = MutableStateFlow(false)
    val accessibleMode: StateFlow<Boolean> = _accessibleMode.asStateFlow()

    fun toggleAccessibleMode() {
        _accessibleMode.value = !_accessibleMode.value
        val modeStr = if (_accessibleMode.value) "ON (Avoiding Stairs)" else "OFF"
        _uiState.update { it.copy(statusMessage = "Accessible Mode: $modeStr") }
        if (_isSosActive.value) triggerEmergencySOS()
        else activeDestination.value?.let { calculateRoute(it) }
    }

    fun calculateRoute(destinationNode: NodeEntity) {
        requestRoute(destinationNode.nodeId)
    }
    
    private val _nodes = MutableStateFlow<List<NodeEntity>>(emptyList())
    private val historyMutex = Mutex()
    private val _undoStack = MutableStateFlow<List<SurveyorAction>>(emptyList())
    private val _redoStack = MutableStateFlow<List<SurveyorAction>>(emptyList())

    val canUndo: StateFlow<Boolean> = _undoStack.map { it.isNotEmpty() }.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val canRedo: StateFlow<Boolean> = _redoStack.map { it.isNotEmpty() }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    private val _routeError = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 64)
    val routeError: SharedFlow<String> = _routeError.asSharedFlow()

    private val _activeRoute = MutableStateFlow<List<Coordinate>>(emptyList())
    val activeRoute: StateFlow<List<Coordinate>> = _activeRoute.asStateFlow()

    val routingEngine = RoutingEngine()

    inner class Repository {
        fun getAllNodesFlow(): Flow<List<NodeEntity>> = _nodes
        fun getAllEdgesFlow(): Flow<List<EdgeEntity>> = _edges
        suspend fun deleteNodeAndEdges(nodeId: String) {
            mapDao.deleteEdgesForNode(nodeId)
            mapDao.deleteNode(nodeId)
        }
        suspend fun updateNode(node: NodeEntity) {
            mapDao.insertNodes(listOf(node))
        }
        suspend fun insertEdge(edge: EdgeEntity) {
            mapDao.insertEdges(listOf(edge))
        }
    }
    val repository = Repository()

    fun stageNodeForDeletion(node: NodeEntity) {
        _stagedNode.value = node
    }

    fun clearStagedNode() {
        _stagedNode.value = null
    }

    fun deleteNode(node: NodeEntity) {
        viewModelScope.launch {
            historyMutex.withLock {
                val current = cachedNodes[node.id] ?: return@withLock
                val action = SurveyorAction.DeleteNode(current, linksFor(node.id), lastDroppedNodeId.value)
                database.removePoint(node.id)
                forgetPoint(node.id)
                _undoStack.value += action
                _redoStack.value = emptyList()
                clearStagedNode()
                refreshCache()
                reportSurveyMessage("Deleted ${node.label}. Use Undo to restore it.")
            }
        }
    }

    private fun linksFor(id: String) = cachedEdges.filter { it.fromNodeId == id || it.toNodeId == id }

    private fun forgetPoint(id: String) {
        droppedNodesChain.removeAll { it == id }
        if (lastDroppedNodeId.value == id) lastDroppedNodeId.value = droppedNodesChain.lastOrNull()
        if (snapTargetNodeId.value == id) snapTargetNodeId.value = null
        _selectedNode.update { if (it?.id == id) null else it }
    }

    fun renameNode(node: NodeEntity, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            if (mapDao.renameNode(node.nodeId, trimmed) == 0) return@launch
            refreshCache()
            val updated = cachedNodes[node.nodeId] ?: return@launch
            _selectedNode.update { if (it?.nodeId == updated.nodeId) updated else it }
            _stagedNode.update { if (it?.nodeId == updated.nodeId) updated else it }
            reportSurveyMessage("Renamed to $trimmed")
        }
    }

    fun updateNodeType(node: NodeEntity, newType: String) {
        viewModelScope.launch {
            if (mapDao.setNodeType(node.id, newType) == 0) return@launch
            refreshCache()
            val updatedNode = cachedNodes[node.id] ?: return@launch
            _selectedNode.update { if (it?.nodeId == node.nodeId) updatedNode else it }
            if (_stagedNode.value?.nodeId == node.nodeId) {
                _stagedNode.value = updatedNode
            }
            _uiState.update { it.copy(statusMessage = "Updated ${node.label} to $newType") }
        }
    }

    fun moveNodeToCurrentFloor(node: NodeEntity) {
        val targetFloor = _currentFloor.value
        val current = cachedNodes[node.nodeId] ?: return reportSurveyMessage("This node is no longer on this map.")
        val updated = current.copy(floorId = "f$targetFloor", floorLevel = targetFloor)
        viewModelScope.launch {
            try {
                repository.updateNode(updated)
                refreshCache()
                _selectedNode.update { if (it?.nodeId == node.nodeId) updated else it }
                updateProximitySnapTarget(estimatedPosition.value.first, estimatedPosition.value.second)
                reportSurveyMessage("Moved ${updated.label} to Floor L$targetFloor")
            } catch (e: android.database.SQLException) {
                reportSurveyMessage("Could not move ${updated.label} to L$targetFloor: ${e.message}")
            }
        }
    }

    fun linkPortalToFloor(sourceNode: NodeEntity, targetFloor: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val candidates = cachedNodes.values.filter {
                it.floorLevel == targetFloor && (
                    it.nodeType.equals("Stairs", ignoreCase = true) ||
                    it.nodeType.equals("Elevator", ignoreCase = true)
                )
            }
            val nearest = candidates.minByOrNull {
                Math.hypot((it.x - sourceNode.x).toDouble(), (it.y - sourceNode.y).toDouble())
            }
            if (nearest != null) {
                val edge = EdgeEntity(
                    edgeId = java.util.UUID.randomUUID().toString(),
                    fromNodeId = sourceNode.nodeId,
                    toNodeId = nearest.nodeId,
                    lengthMeters = 30.0f,
                    edgeType = "vertical_portal",
                    buildingId = sourceNode.buildingId
                )
                mapDao.insertEdges(listOf(edge))
                refreshCache(_activeBuildingId.value ?: sourceNode.buildingId)
                _uiState.update { it.copy(statusMessage = "Linked ${sourceNode.label} to ${nearest.label} on Floor L$targetFloor") }
            } else {
                _routeError.tryEmit("No portal found on Floor L$targetFloor")
            }
        }
    }

    fun performUndo() = changeHistory(undo = true)
    fun performRedo() = changeHistory(undo = false)

    private fun changeHistory(undo: Boolean) {
        val building = _activeBuildingId.value
        viewModelScope.launch {
            historyMutex.withLock {
                if (building != _activeBuildingId.value || !_isSurveyorMode.value) return@withLock
                val source = if (undo) _undoStack else _redoStack
                val destination = if (undo) _redoStack else _undoStack
                val action = source.value.lastOrNull() ?: return@withLock
                val next = when (action) {
                    is SurveyorAction.DropNode -> {
                        if (undo) {
                            val snapshot = action.copy(node = cachedNodes[action.node.id] ?: action.node, edges = linksFor(action.node.id))
                            database.removePoint(action.node.id)
                            forgetPoint(action.node.id)
                            snapshot
                        } else {
                            database.restorePoint(action.node, action.edges)
                            droppedNodesChain.add(action.node.id)
                            lastDroppedNodeId.value = action.node.id
                            _selectedNode.value = action.node
                            action
                        }
                    }
                    is SurveyorAction.DeleteNode -> {
                        if (undo) {
                            database.restorePoint(action.node, action.edges)
                            lastDroppedNodeId.value = action.previousOrigin
                            if (action.previousOrigin == action.node.id) droppedNodesChain.add(action.node.id)
                            _selectedNode.value = action.node
                        } else {
                            database.removePoint(action.node.id)
                            forgetPoint(action.node.id)
                        }
                        action
                    }
                    is SurveyorAction.SnapEdge -> {
                        if (undo) {
                            mapDao.deleteEdge(action.edge.edgeId)
                            if (droppedNodesChain.lastOrNull() == action.edge.toNodeId) droppedNodesChain.removeAt(droppedNodesChain.lastIndex)
                            lastDroppedNodeId.value = action.edge.fromNodeId
                        } else {
                            mapDao.insertEdges(listOf(action.edge))
                            droppedNodesChain.add(action.edge.toNodeId)
                            lastDroppedNodeId.value = action.edge.toNodeId
                        }
                        action
                    }
                }
                refreshCache()
                source.value = source.value.dropLast(1)
                destination.value += next
            }
        }
    }

    fun undoDeletion(node: NodeEntity) {
        performUndo()
    }

    fun undoLastNode() {
        performUndo()
    }

    val activeNodes: StateFlow<List<NodeEntity>> = _nodes.asStateFlow()

    val nodes: StateFlow<List<NodeEntity>> = activeNodes
    val allNodes: StateFlow<List<NodeEntity>> = activeNodes

    val availableFloors: StateFlow<List<Int>> = repository.getAllNodesFlow()
        .map { nodes -> 
            val floors = nodes.map { it.floorLevel }.distinct().sorted()
            if (floors.isEmpty()) listOf(0) else floors
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, listOf(0))

    fun cycleFloor(isSurveyorMode: Boolean) {
        val floors = availableFloors.value
        val newFloor = if (isSurveyorMode) {
            // Surveyors can always map one floor above the highest existing floor, or go below 0.
            val max = floors.maxOrNull() ?: 0
            val min = floors.minOrNull() ?: 0
            if (_currentFloor.value >= max + 1) min - 1 else _currentFloor.value + 1
        } else {
            // Standard users can only cycle through floors that physically exist in the DB
            val currentIndex = floors.indexOf(_currentFloor.value)
            val nextIndex = if (currentIndex >= 0) (currentIndex + 1) % floors.size else 0
            floors[nextIndex]
        }
        setFloor(newFloor)
    }
    
    private val _edges = MutableStateFlow<List<EdgeEntity>>(emptyList())
    val edges: StateFlow<List<EdgeEntity>> = _edges.asStateFlow()
    
    private val droppedNodesChain = mutableListOf<String>()
    private val actionHistory = java.util.Stack<String>()
    private val particleFilter = ParticleFilter()
    
    private var cachedEdges: List<EdgeEntity> = emptyList()
    private var cachedNodes: Map<String, NodeEntity> = emptyMap()

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    val currentPoseNodeId: String? = null
    val currentPoseState: StateFlow<Coordinate?> = _uiState.map { it.userPosition }.stateIn(viewModelScope, SharingStarted.Eagerly, _uiState.value.userPosition)

    private val stepLengthMeters = 0.7f

    private var headingOffset = 0f

    private val kinematicPDR = com.indoorpulse.data.sensor.KinematicPDR()

    // History for step debouncing and spike rejection
    private var lastStepTimestampMillis = 0L
    private val orientationHistory = mutableListOf<Pair<Long, Float>>()
    private var lastResetTimestamp = 0L

    init {
        viewModelScope.launch {
            val floors = mapDao.getFloors()
            if (floors.isEmpty()) {
                mapDao.insertFloors(listOf(
                    FloorEntity(
                        floorId = "f1",
                        level = 0,
                        name = "Ground Floor",
                        altitudeOffsetMeters = 0f
                    )
                ))
            }

            if (includeDemo) database.ensureDemoMap()
            var buildings = mapDao.getAllBuildings()
            if (buildings.isEmpty()) {
                val defaultBuilding = BuildingEntity(
                    buildingId = "default_building",
                    name = "Main Campus",
                    centerLat = 0.0,
                    centerLng = 0.0,
                    geofenceRadius = 150f
                )
                mapDao.insertBuildings(listOf(defaultBuilding))
                buildings = listOf(defaultBuilding)
            }

            val currentBuildingId = _activeBuildingId.value ?: buildings.firstOrNull()?.buildingId
            if (currentBuildingId != null) {
                refreshCache(currentBuildingId)
            }

        }

        viewModelScope.launch {
            currentPoseState.collect { pose ->
                if (pose != null) {
                    // CRITICAL FIX: The proximity check must be actively driven by the PDR state
                    onLocationUpdated(pose.x, pose.y)
                }
            }
        }

        observeSensors()
        val gravity = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY)
        val linearAccel = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_LINEAR_ACCELERATION)
        val rotVector = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
        if (gravity != null) sensorManager.registerListener(kinematicPDR, gravity, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        if (linearAccel != null) sensorManager.registerListener(kinematicPDR, linearAccel, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        kinematicPDR.onHeadingChanged = { raw, accuracy ->
            val heading = mapHeading(raw)
            val state = _uiState.value
            if (!state.hasHeading || accuracy != state.headingAccuracyDegrees || abs(shortestAngleDiff(heading, state.headingDegrees)) >= 0.5f) {
                _uiState.update { it.copy(headingDegrees = heading, hasHeading = true, headingAccuracyDegrees = accuracy) }
            }
        }
        if (rotVector != null) sensorManager.registerListener(kinematicPDR, rotVector, android.hardware.SensorManager.SENSOR_DELAY_UI)

        kinematicPDR.onStepDetected = onStepDetected@{ stepLength, rawHeading ->
            if (_transitState.value != null) return@onStepDetected
            val heading = mapHeading(rawHeading)
            val survey = isSurveyorMode.value
            val floor = _currentFloor.value
            val floorEdges = if (survey) emptyList() else cachedEdges.filter {
                cachedNodes[it.fromNodeId]?.floorLevel == floor && cachedNodes[it.toNodeId]?.floorLevel == floor
            }
            particleFilter.update(stepLength, heading, floorEdges, cachedNodes, constrainToGraph = !survey)
            val (x, y) = particleFilter.getEstimatedPosition()
            val estimate = _uiState.value.userPosition.copy(x = x, y = y, floor = floor)
            val position = if (survey) estimate else snapToNearestCorridor(estimate, heading, floorEdges)
            if (position != estimate) particleFilter.translate(position.x - x, position.y - y)
            estimatedPosition.value = position.x to position.y
            _uiState.update { it.copy(userPosition = position) }
            if (!finishArrivalIfReached(position.x, position.y)) updateActiveRouteBasedOnPosition()
        }
    }

    private fun mapHeading(raw: Float) = (raw + headingOffset + 360f) % 360f

    fun alignHeadingToward(target: NodeEntity) {
        val raw = kinematicPDR.currentHeading ?: return reportSurveyMessage("Waiting for a phone heading.")
        if (target.buildingId != _activeBuildingId.value || target.floorLevel != _currentFloor.value) {
            return reportSurveyMessage("Choose a reference on the current floor.")
        }
        val (x, y) = estimatedPosition.value
        val dx = target.x - x
        val dy = target.y - y
        if (dx * dx + dy * dy < 1f) return reportSurveyMessage("Face a reference at least one metre away.")
        val bearing = bearingDegrees(dx, dy)
        headingOffset = shortestAngleDiff(bearing, raw)
        _uiState.update { it.copy(headingDegrees = bearing, hasHeading = true) }
        reportSurveyMessage("Walking direction aligned toward ${target.label}")
    }

    private fun resetHeadingAlignment() {
        headingOffset = 0f
        kinematicPDR.currentHeading?.let { raw -> _uiState.update { it.copy(headingDegrees = raw) } }
    }

    override fun onCleared() {
        sensorManager.unregisterListener(kinematicPDR)
        super.onCleared()
    }


    private suspend fun refreshCache(buildingId: String = _activeBuildingId.value ?: "default_building") {
        val nodesList = mapDao.getNodesByBuilding(buildingId)
        val edgesList = mapDao.getEdgesByBuilding(buildingId)
        
        cachedEdges = edgesList
        cachedNodes = nodesList.associateBy { it.nodeId }
        
        _nodes.value = nodesList
        _edges.value = cachedEdges
        routingEngine.buildGraph(nodesList, cachedEdges)
    }

    fun confirmBuildingEntry(buildingId: String, entryNode: NodeEntity) {
        viewModelScope.launch {
            _activeBuildingId.value = buildingId
            entryAnchorNode = entryNode
            refreshCache(buildingId)
            setFloor(entryNode.floorLevel)
            syncPositionTo(entryNode.x, entryNode.y)
            trackingState.value = TrackingState.INDOORS
        }
    }

    fun confirmBuildingExit() {
        resetHeadingAlignment()
        clearSelectedNode()
        clearStagedNode()
        droppedNodesChain.clear()
        _undoStack.value = emptyList()
        _redoStack.value = emptyList()
        _activeBuildingId.value = null
        clearRoute()
        entryAnchorNode = null
        trackingState.value = TrackingState.OUTDOORS
        _uiState.update { it.copy(statusMessage = "Exited building. Switched to OUTDOORS.") }
    }

    fun updateDiscoveryLocation(location: Location?) {
        latestGpsLocation = location
    }

    fun createNewBuilding(name: String, radius: Float = 100f) {
        val newBuildingId = java.util.UUID.randomUUID().toString()
        val fix = latestGpsLocation?.takeIf { it.isUsableForDiscovery() && it.accuracy <= 50f }

        val newBuilding = BuildingEntity(
            buildingId = newBuildingId,
            name = name.ifBlank { "New Building" },
            centerLat = fix?.latitude ?: 0.0,
            centerLng = fix?.longitude ?: 0.0,
            geofenceRadius = radius
        )

        viewModelScope.launch {
            mapDao.insertBuildings(listOf(newBuilding))
            _activeBuildingId.value = newBuildingId
            trackingState.value = TrackingState.INDOORS
            refreshCache(newBuildingId)
            setFloor(0)
            resetPosition()
            toggleSurveyorMode(true)
            _uiState.update { 
                it.copy(statusMessage = "Created & switched to: ${newBuilding.name}") 
            }
        }
    }

    suspend fun getBuildingEntryPoints(buildingId: String): List<NodeEntity> {
        return mapDao.getEntryPoints(buildingId)
    }

    val validPortals = listOf("Stairs", "Elevator", "Emergency Exit")

    fun canLinkNodes(nodeA: NodeEntity, nodeB: NodeEntity): Boolean {
        // 1. Cross-Floor Validation
        if (nodeA.floorLevel != nodeB.floorLevel) {
            val isNodeAValid = nodeA.nodeType.equals("Stairs", ignoreCase = true) || 
                               nodeA.nodeType.equals("Elevator", ignoreCase = true) ||
                               nodeA.nodeType.equals("Emergency Exit", ignoreCase = true)
            val isNodeBValid = nodeB.nodeType.equals("Stairs", ignoreCase = true) || 
                               nodeB.nodeType.equals("Elevator", ignoreCase = true) ||
                               nodeB.nodeType.equals("Emergency Exit", ignoreCase = true)
            if (!isNodeAValid || !isNodeBValid) {
                // Reject the edge: You cannot connect standard nodes across floors
                return false
            }
        }
        return true
    }

    fun createEdgeEntity(nodeA: NodeEntity, nodeB: NodeEntity): EdgeEntity? {
        if (!canLinkNodes(nodeA, nodeB)) {
            return null
        }
        val dx = nodeB.x - nodeA.x
        val dy = nodeB.y - nodeA.y
        val length = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat().coerceAtLeast(0.1f)
        val edgeType = if (nodeA.floorLevel != nodeB.floorLevel) {
            if (nodeA.nodeType.equals("Elevator", ignoreCase = true) || nodeB.nodeType.equals("Elevator", ignoreCase = true)) {
                "elevator"
            } else {
                "stairs"
            }
        } else {
            "corridor"
        }
        return EdgeEntity(
            edgeId = java.util.UUID.randomUUID().toString(),
            fromNodeId = nodeA.nodeId,
            toNodeId = nodeB.nodeId,
            lengthMeters = length,
            edgeType = edgeType,
            buildingId = _activeBuildingId.value ?: "default_building"
        )
    }

    // Locate your function that creates edges (e.g., linkNodes or addEdge)
    fun linkNodes(nodeA: NodeEntity, nodeB: NodeEntity): EdgeEntity? {
        // 1. Cross-Floor Validation
        if (nodeA.floorLevel != nodeB.floorLevel) {
            val isNodeAValid = nodeA.nodeType.equals("Stairs", ignoreCase = true) || 
                               nodeA.nodeType.equals("Elevator", ignoreCase = true) ||
                               nodeA.nodeType.equals("Emergency Exit", ignoreCase = true)
            val isNodeBValid = nodeB.nodeType.equals("Stairs", ignoreCase = true) || 
                               nodeB.nodeType.equals("Elevator", ignoreCase = true) ||
                               nodeB.nodeType.equals("Emergency Exit", ignoreCase = true)
            if (!isNodeAValid || !isNodeBValid) {
                // Reject the edge: You cannot connect standard nodes across floors
                return null
            }
        }
        
        val newEdge = createEdgeEntity(nodeA, nodeB) ?: return null
        viewModelScope.launch(Dispatchers.IO) {
            mapDao.insertEdges(listOf(newEdge))
            refreshCache()
        }
        return newEdge
    }

    private fun isSnappableCandidate(candidate: NodeEntity, lastNode: NodeEntity?): Boolean {
        if (candidate.nodeId == lastNode?.nodeId) return false
        return if (candidate.floorLevel == _currentFloor.value) {
            true
        } else if (lastNode != null) {
            canLinkNodes(lastNode, candidate)
        } else {
            false
        }
    }

    private fun getUniqueNodeName(requestedName: String, existingNames: List<String>): String {
        if (!existingNames.contains(requestedName)) {
            return requestedName
        }
        
        var counter = 2
        var newName = "$requestedName $counter"
        
        // Keep incrementing until we find a clean name
        while (existingNames.contains(newName)) {
            counter++
            newName = "$requestedName $counter"
        }
        
        return newName
    }

    fun forceDropNewNode(nodeName: String, nodeType: String) {
        viewModelScope.launch {
            try {
                val pos = estimatedPosition.value
                val lastId = lastDroppedNodeId.value
                
                mapDao.insertFloors(listOf(FloorEntity(floorId = "f1", level = 0, name = "Ground Floor", altitudeOffsetMeters = 0f)))
                val newNodeId = java.util.UUID.randomUUID().toString()
                
                val currentNames = cachedNodes.values.map { it.name }
                val finalLabel = if (nodeName.isNotBlank()) {
                    getUniqueNodeName(nodeName.trim(), currentNames)
                } else {
                    val currentNodes = if (activeNodes.value.isNotEmpty()) activeNodes.value else cachedNodes.values.toList()
                    val count = currentNodes.count { it.type.equals(nodeType, ignoreCase = true) }
                    "$nodeType ${count + 1}"
                }

                var computedLat = 0.0
                var computedLng = 0.0

                if (_isSurveyorMode.value) {
                    val anchor = entryAnchorNode ?: cachedNodes.values.firstOrNull {
                        it.nodeType == "Building Entry" || it.nodeType == "Emergency Exit"
                    }
                    val anchorLat = anchor?.lat?.takeIf { it != 0.0 }
                    val anchorLng = anchor?.lng?.takeIf { it != 0.0 }

                    if (anchorLat != null && anchorLng != null) {
                        val anchorX = anchor.x
                        val anchorY = anchor.y
                        val deltaX = (pos.first - anchorX).toDouble()
                        val deltaY = (pos.second - anchorY).toDouble()

                        val deltaLat = deltaY / 111139.0
                        val deltaLng = deltaX / (111139.0 * kotlin.math.cos(Math.toRadians(anchorLat)))

                        computedLat = anchorLat + deltaLat
                        computedLng = anchorLng + deltaLng
                    }
                }

                val newNode = NodeEntity(
                    nodeId = newNodeId,
                    floorId = "f1",
                    x = pos.first,
                    y = pos.second,
                    nodeType = nodeType,
                    name = finalLabel,
                    floorLevel = _currentFloor.value,
                    buildingId = _activeBuildingId.value ?: "default_building",
                    lat = computedLat,
                    lng = computedLng
                )
                mapDao.insertNodes(listOf(newNode))
                var createdEdge: EdgeEntity? = null
                if (lastId != null) {
                    val lastNode = cachedNodes[lastId]
                    if (lastNode != null) {
                        val newEdge = createEdgeEntity(lastNode, newNode)
                        if (newEdge != null) {
                            mapDao.insertEdges(listOf(newEdge))
                            createdEdge = newEdge
                        }
                    }
                }
                
                droppedNodesChain.add(newNodeId)
                lastDroppedNodeId.value = newNodeId
                _selectedNode.value = newNode
                _undoStack.value = _undoStack.value + SurveyorAction.DropNode(newNode, createdEdge)
                _redoStack.value = emptyList()
                refreshCache()
            } catch (e: Exception) {
                _uiState.update { it.copy(statusMessage = "Error saving: ${e.message}") }
            }
        }
    }

    fun extrudePortal(direction: Int) {
        val selected = _selectedNode.value ?: return
        if (!selected.nodeType.equals("Stairs", ignoreCase = true) &&
            !selected.nodeType.equals("Elevator", ignoreCase = true)) {
            return
        }

        viewModelScope.launch {
            val sourceNode = selected
            val targetFloor = _currentFloor.value + direction
            val targetFloorLevel = targetFloor
            val targetFloorId = "f$targetFloorLevel"
            val newNodeId = java.util.UUID.randomUUID().toString()

            mapDao.insertFloors(
                listOf(
                    FloorEntity(
                        floorId = targetFloorId,
                        level = targetFloorLevel,
                        name = if (targetFloorLevel == 0) "Ground Floor" else "Floor $targetFloorLevel",
                        altitudeOffsetMeters = targetFloorLevel * 3.5f
                    )
                )
            )

            val cleanName = sourceNode.label.replace(Regex(" \\(L[-0-9]+\\)$"), "")
            val label = "$cleanName (L$targetFloor)"
            val newNode = NodeEntity(
                nodeId = newNodeId,
                floorId = targetFloorId,
                x = sourceNode.x,
                y = sourceNode.y,
                nodeType = sourceNode.nodeType,
                name = label,
                floorLevel = targetFloorLevel,
                buildingId = sourceNode.buildingId,
                lat = sourceNode.lat,
                lng = sourceNode.lng
            )
            mapDao.insertNodes(listOf(newNode))

            val newEdge = EdgeEntity(
                edgeId = java.util.UUID.randomUUID().toString(),
                fromNodeId = sourceNode.nodeId,
                toNodeId = newNodeId,
                lengthMeters = 30.0f,
                edgeType = "vertical_portal",
                buildingId = sourceNode.buildingId
            )
            mapDao.insertEdges(listOf(newEdge))
            _undoStack.value = _undoStack.value + SurveyorAction.DropNode(newNode, newEdge)
            _redoStack.value = emptyList()

            refreshCache(_activeBuildingId.value ?: "default_building")
            setFloor(targetFloorLevel)
            _selectedNode.value = newNode
            droppedNodesChain.add(newNodeId)
            lastDroppedNodeId.value = newNodeId
            _uiState.update { it.copy(statusMessage = "Extruded portal to floor L$targetFloorLevel") }
        }
    }

    fun snapToGhostPortal(currentNode: NodeEntity, targetGhostNode: NodeEntity) {
        val isCurrentPortal = currentNode.nodeType.equals("Stairs", ignoreCase = true) || 
                              currentNode.nodeType.equals("Elevator", ignoreCase = true) ||
                              currentNode.nodeType.equals("Emergency Exit", ignoreCase = true)
        val isTargetPortal = targetGhostNode.nodeType.equals("Stairs", ignoreCase = true) || 
                             targetGhostNode.nodeType.equals("Elevator", ignoreCase = true) ||
                             targetGhostNode.nodeType.equals("Emergency Exit", ignoreCase = true)
        
        // Ensure both are valid portals and on adjacent floors
        if (isCurrentPortal && isTargetPortal) {
            if (kotlin.math.abs(currentNode.floorLevel - targetGhostNode.floorLevel) == 1) {
                
                // 1. Align the X/Y coordinates perfectly
                val alignedNode = currentNode.copy(x = targetGhostNode.x, y = targetGhostNode.y)
                viewModelScope.launch(Dispatchers.IO) {
                    repository.updateNode(alignedNode)
                    
                    // 2. Automatically generate the vertical Edge
                    val verticalEdge = EdgeEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        startNodeId = alignedNode.id,
                        endNodeId = targetGhostNode.id,
                        weight = 0f // Zero-cost vertical jump
                    )
                    repository.insertEdge(verticalEdge)
                    refreshCache()
                }
            }
        }
    }

    fun snapToTargetNode() = snapToNearestNode()

    private var snapJob: kotlinx.coroutines.Job? = null

    fun snapToNearestNode() {
        val originId = lastDroppedNodeId.value
            ?: return reportSurveyMessage("No active origin. Open a node and choose 'Resume Path Here' first.")
        val targetId = _selectedNode.value?.nodeId ?: snapTargetNodeId.value
            ?: return reportSurveyMessage("No target node selected or within 5 m.")
        if (originId == targetId) {
            return reportSurveyMessage("Choose a different target. This node is already the active origin.")
        }
        if (snapJob?.isActive == true) return reportSurveyMessage("Finishing the previous link.")

        val origin = cachedNodes[originId] ?: return reportSurveyMessage("The origin is no longer on this map.")
        val target = cachedNodes[targetId] ?: return reportSurveyMessage("The target is no longer on this map.")
        val edge = createEdgeEntity(origin, target) ?: return reportSurveyMessage(
            "Cannot link ${origin.label} (L${origin.floorLevel}) to ${target.label} (L${target.floorLevel}). " +
                "Both ends of a cross-floor link must be stairs, elevators, or emergency exits."
        )

        snapJob = viewModelScope.launch {
            try {
                repository.insertEdge(edge)
                _undoStack.value = _undoStack.value + SurveyorAction.SnapEdge(edge)
                _redoStack.value = emptyList()
                droppedNodesChain.add(targetId)
                if (lastDroppedNodeId.compareAndSet(originId, targetId)) {
                    _selectedNode.update { if (it?.nodeId == targetId) null else it }
                }
                refreshCache()
                updateProximitySnapTarget(estimatedPosition.value.first, estimatedPosition.value.second)
                reportSurveyMessage("Linked ${origin.label} → ${target.label}")
            } catch (e: android.database.SQLException) {
                reportSurveyMessage("Could not finish linking ${origin.label} to ${target.label}: ${e.message}")
            }
        }
    }

    private fun reportSurveyMessage(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
        _routeError.tryEmit(message)
    }

    private fun shortestAngleDiff(target: Float, current: Float): Float {
        var diff = (target - current) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return diff
    }

    private fun observeSensors() {
        viewModelScope.launch {
            sensorDataSource.gravityEvents.collect { gravity ->
                val pitchDegrees = Math.toDegrees(kotlin.math.atan2(gravity.gy.toDouble(), gravity.gz.toDouble()))
                if (isAutoArEnabled.value) {
                    if (pitchDegrees > 65) {
                        currentViewMode.value = ViewMode.AR_CAMERA
                    } else if (pitchDegrees < 45) {
                        currentViewMode.value = ViewMode.MAP_2D
                    }
                }
            }
        }

    }

    private fun snapToNearestCorridor(
        candidatePos: Coordinate, 
        currentHeading: Float,
        floorEdges: List<EdgeEntity>
    ): Coordinate {
        var closestDistSq = Float.MAX_VALUE
        var bestProjX = candidatePos.x
        var bestProjY = candidatePos.y
        var bestEdgeBearing = 0f

        for (edge in floorEdges) {
            val n1 = cachedNodes[edge.fromNodeId] ?: continue
            val n2 = cachedNodes[edge.toNodeId] ?: continue
            val p1 = Coordinate(n1.x, n1.y)
            val p2 = Coordinate(n2.x, n2.y)

            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            val l2 = dx * dx + dy * dy
            if (l2 == 0f) continue

            val t = ((candidatePos.x - p1.x) * dx + (candidatePos.y - p1.y) * dy) / l2
            val tClamped = t.coerceIn(0f, 1f)
            
            val projX = p1.x + tClamped * dx
            val projY = p1.y + tClamped * dy

            val distSq = (candidatePos.x - projX) * (candidatePos.x - projX) + (candidatePos.y - projY) * (candidatePos.y - projY)
            if (distSq < closestDistSq) {
                closestDistSq = distSq
                bestProjX = projX
                bestProjY = projY
                
                bestEdgeBearing = Math.toDegrees(kotlin.math.atan2(dx.toDouble(), dy.toDouble())).toFloat()
            }
        }

        val dist = kotlin.math.sqrt(closestDistSq.toDouble()).toFloat()
        
        val diffForward = abs(shortestAngleDiff(currentHeading, bestEdgeBearing))
        val diffReverse = abs(shortestAngleDiff(currentHeading, bestEdgeBearing + 180f))
        
        val isAligned = diffForward <= 35f || diffReverse <= 35f

        if (dist <= 1.2f && isAligned) {
            val pullFactor = 0.25f
            return candidatePos.copy(
                x = candidatePos.x + (bestProjX - candidatePos.x) * pullFactor,
                y = candidatePos.y + (bestProjY - candidatePos.y) * pullFactor
            )
        }

        return candidatePos
    }

    private fun buildDynamicMap(): IndoorMap {
        return IndoorMap(
            buildingId = "db_map",
            buildingName = "Surveyed Map",
            originLat = 0.0,
            originLng = 0.0,
            boundingRadiusMeters = 500f,
            nodes = cachedNodes.values.associate { it.nodeId to Node(it.nodeId, Coordinate(it.x, it.y, floor = it.floorLevel), it.name, try { NodeType.valueOf(it.nodeType.uppercase()) } catch(e: Exception) { NodeType.CORRIDOR_JUNCTION }) },
            edges = cachedEdges.flatMap { 
                listOf(
                    Edge(fromNodeId = it.fromNodeId, toNodeId = it.toNodeId, distanceMeters = it.lengthMeters, isAccessible = true),
                    Edge(fromNodeId = it.toNodeId, toNodeId = it.fromNodeId, distanceMeters = it.lengthMeters, isAccessible = true)
                )
            }
        )
    }

    private fun updateProximitySnapTarget(posX: Float, posY: Float) {
        if (!isSurveyorMode.value) {
            snapTargetNodeId.value = null
            return
        }
        val ignoreId = lastDroppedNodeId.value
        val candidates = cachedNodes.values.filter { 
            it.nodeId != ignoreId && it.floorLevel == _currentFloor.value 
        }
        val closestNode = candidates.minByOrNull { node ->
            Math.hypot((node.x - posX).toDouble(), (node.y - posY).toDouble())
        }
        if (closestNode != null) {
            val dist = Math.hypot((closestNode.x - posX).toDouble(), (closestNode.y - posY).toDouble())
            if (dist <= 5.0) {
                snapTargetNodeId.value = closestNode.nodeId
            } else {
                snapTargetNodeId.value = null
            }
        } else {
            snapTargetNodeId.value = null
        }
    }

    fun onLocationUpdated(newX: Float, newY: Float) {
        if (_transitState.value != null) return // CRITICAL: Pause PDR during transit

        updateProximitySnapTarget(newX, newY)
        if (finishArrivalIfReached(newX, newY)) return

        val route = _activeRoute.value
        if (route.isNotEmpty()) {
            val nextWaypoint = route.first() // Assuming we drop nodes as we reach them
            
            // 1. Check if we reached a waypoint
            val distance = Math.hypot((newX - nextWaypoint.x).toDouble(), (newY - nextWaypoint.y).toDouble())
            if (nextWaypoint.floorLevel == _currentFloor.value && distance <= ARRIVAL_RADIUS_METRES) {
                
                // 2. Is this a floor transition?
                if (route.size > 1 && route[1].floorLevel != _currentFloor.value) {
                    val targetPortal = route[1]
                    var finalPortalIndex = 1
                    // Look ahead to skip intermediate floors in the same elevator/stair shaft
                    while (finalPortalIndex + 1 < route.size && 
                           route[finalPortalIndex + 1].type == targetPortal.type && 
                           route[finalPortalIndex + 1].x == targetPortal.x && 
                           route[finalPortalIndex + 1].y == targetPortal.y) {
                        finalPortalIndex++
                    }
                    val finalPortal = route[finalPortalIndex]

                    val portalType = if (finalPortal.type.equals("Elevator", ignoreCase = true) || nextWaypoint.type.equals("Elevator", ignoreCase = true)) {
                        "Elevator"
                    } else if (finalPortal.type.equals("Stairs", ignoreCase = true) || nextWaypoint.type.equals("Stairs", ignoreCase = true)) {
                        "Stairs"
                    } else {
                        finalPortal.type
                    }
                    // Trigger Transit UX
                    _transitState.value = TransitInfo(
                        targetFloor = finalPortal.floorLevel,
                        portalType = portalType,
                        targetX = finalPortal.x,
                        targetY = finalPortal.y,
                        nextNodeIndex = finalPortalIndex 
                    )
                } else {
                    // Normal waypoint reached, pop it off the route so UI points to the next one
                    _activeRoute.value = route.drop(1)
                }
            }
        }
    }

    fun completeTransit() {
        val info = _transitState.value ?: return
        
        // 1. Switch Floor
        _currentFloor.value = info.targetFloor
        
        // 2. Teleport Blue Dot to the new portal to zero-out drift
        syncPositionTo(info.targetX, info.targetY)
        
        // 3. Advance the route past the transition nodes
        _activeRoute.value = _activeRoute.value.drop(info.nextNodeIndex + 1)
        
        // Unpause PDR and complete routes that end at the portal itself.
        _transitState.value = null
        finishArrivalIfReached(info.targetX, info.targetY)
    }

    fun findShortestPath(startId: String, targetId: String): List<NodeEntity>? =
        findShortestPath(startId, targetId, _accessibleMode.value)

    private fun findShortestPath(startId: String, targetId: String, avoidStairs: Boolean): List<NodeEntity>? {
        val nodesMap = cachedNodes
        if (!nodesMap.containsKey(startId) || !nodesMap.containsKey(targetId)) return null

        val adj = mutableMapOf<String, MutableList<EdgeEntity>>()
        cachedEdges.forEach { edge ->
            adj.getOrPut(edge.fromNodeId) { mutableListOf() }.add(edge)
            adj.getOrPut(edge.toNodeId) { mutableListOf() }.add(
                EdgeEntity(edge.edgeId, edge.toNodeId, edge.fromNodeId, edge.lengthMeters, edge.edgeType, edge.buildingId)
            )
        }

        fun heuristic(n1Id: String, n2Id: String): Float {
            val n1 = nodesMap[n1Id] ?: return Float.MAX_VALUE
            val n2 = nodesMap[n2Id] ?: return Float.MAX_VALUE
            val dx = n1.x - n2.x
            val dy = n1.y - n2.y
            val dz = (n1.floorLevel - n2.floorLevel) * 10f
            return Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
        }

        val openSet = java.util.PriorityQueue<Pair<String, Float>>(compareBy { it.second })
        val cameFrom = mutableMapOf<String, String>()
        val gScore = mutableMapOf<String, Float>().withDefault { Float.MAX_VALUE }

        gScore[startId] = 0f
        openSet.add(startId to heuristic(startId, targetId))

        while (openSet.isNotEmpty()) {
            val current = openSet.poll()?.first ?: break

            if (current == targetId) {
                val path = mutableListOf<NodeEntity>()
                var curr = current
                while (cameFrom.containsKey(curr)) {
                    path.add(0, nodesMap[curr]!!)
                    curr = cameFrom[curr]!!
                }
                path.add(0, nodesMap[startId]!!)
                return path
            }

            for (edge in adj[current] ?: emptyList()) {
                val neighbor = edge.toNodeId
                val neighborNode = nodesMap[neighbor] ?: continue

                // HARD BLOCK: Stairs are impassable in accessible mode
                if (avoidStairs && neighborNode.nodeType.equals("Stairs", ignoreCase = true)) {
                    continue 
                }

                var physicalDistance = if (edge.lengthMeters == 0f) 0f else heuristic(current, neighbor)

                // INJECT TIE-BREAKER:
                if (!avoidStairs && neighborNode.nodeType.equals("Elevator", ignoreCase = true)) {
                    physicalDistance += 20.0f // Mild penalty to prioritize stairs for able-bodied users
                }

                val tentativeGScore = gScore.getValue(current) + physicalDistance

                if (tentativeGScore < gScore.getValue(neighbor)) {
                    cameFrom[neighbor] = current
                    gScore[neighbor] = tentativeGScore
                    val fScore = tentativeGScore + heuristic(neighbor, targetId)
                    openSet.removeAll { it.first == neighbor }
                    openSet.add(neighbor to fScore)
                }
            }
        }
        return null
    }

    private fun updateActiveRouteBasedOnPosition() {
        if (_transitState.value != null) return
        val destId = _uiState.value.selectedDestinationNodeId ?: return
        
        val pos = estimatedPosition.value
        val closestNode = cachedNodes.values
            .filter { it.floorLevel == _currentFloor.value }
            .minByOrNull { node ->
                val dx = node.x - pos.first
                val dy = node.y - pos.second
                (dx * dx) + (dy * dy)
            }
        
        if (closestNode != null) {
            val pathNodes = findShortestPath(closestNode.nodeId, destId)
            if (!pathNodes.isNullOrEmpty()) {
                val route = pathNodes.map { Coordinate(it.x, it.y, floor = it.floorLevel, type = it.type) }
                _activeRoute.value = route
                _uiState.update { it.copy(activeRoute = route) }
            }
        }
    }

    fun requestRoute(destinationId: String) {
        if (_isSurveyorMode.value) toggleSurveyorMode(false)
        clearRoute()
        val destination = cachedNodes[destinationId] ?: return reportSurveyMessage("Destination is unavailable.")
        val start = closestRouteStart() ?: return reportSurveyMessage("Cannot locate starting position on this floor.")
        routeRequest = viewModelScope.launch {
            val path = withContext(Dispatchers.Default) { findShortestPath(start, destinationId) }
            if (path.isNullOrEmpty()) {
                reportSurveyMessage(if (_accessibleMode.value) "No route found that avoids stairs." else "Destination unreachable.")
                return@launch
            }
            publishRoute(destination, path, isExit = false)
        }
    }

    internal suspend fun previewRoute(destinationId: String, avoidStairs: Boolean): List<NodeEntity>? {
        val start = closestRouteStart()
        if (start == null) {
            reportSurveyMessage("Cannot locate starting position on this floor.")
            return null
        }
        val path = withContext(Dispatchers.Default) { findShortestPath(start, destinationId, avoidStairs) }
        if (path.isNullOrEmpty()) {
            reportSurveyMessage(if (avoidStairs) "No route found that avoids stairs." else "Destination unreachable.")
        }
        return path
    }

    private fun closestRouteStart(): String? {
        val (x, y) = estimatedPosition.value
        return currentPoseNodeId ?: cachedNodes.values
            .filter { it.floorLevel == _currentFloor.value }
            .minByOrNull { kotlin.math.hypot((x - it.x).toDouble(), (y - it.y).toDouble()) }
            ?.id
    }

    private fun publishRoute(destination: NodeEntity, path: List<NodeEntity>, isExit: Boolean) {
        val coordinates = path.map { Coordinate(it.x, it.y, floor = it.floorLevel, type = it.type) }
        _activeRoute.value = coordinates
        _isSosActive.value = isExit
        activeDestination.value = destination
        _uiState.update {
            it.copy(selectedDestinationNodeId = destination.id, activeRoute = coordinates, statusMessage = "Navigating to ${destination.label}")
        }
        val (x, y) = estimatedPosition.value
        finishArrivalIfReached(x, y)
    }

    private fun finishArrivalIfReached(x: Float, y: Float): Boolean {
        val destination = activeDestination.value ?: return false
        if (_transitState.value != null || destination.floorLevel != _currentFloor.value) return false
        if (kotlin.math.hypot((x - destination.x).toDouble(), (y - destination.y).toDouble()) > ARRIVAL_RADIUS_METRES) return false

        _arrival.value = NavigationArrival(destination, _isSosActive.value)
        stopNavigation("Arrived at ${destination.label}")
        return true
    }

    fun dismissArrival() {
        _arrival.value = null
    }

    fun selectDestination(nodeId: String) {
        requestRoute(nodeId)
    }

    fun resetPosition() {
        clearRoute()
        lastResetTimestamp = android.os.SystemClock.elapsedRealtime()
        _transitState.value = null
        
        // Atomic wipe
        particleFilter.initialize(0f, 0f)
        estimatedPosition.value = Pair(0f, 0f)
        resetHeadingAlignment()
        
        if (isSurveyorMode.value) {
            lastDroppedNodeId.value = null
        }
        
        _isSosActive.value = false
        activeDestination.value = null
        
        _uiState.update {
            it.copy(
                userPosition = Coordinate(0f, 0f),
                headingDegrees = kinematicPDR.currentHeading ?: 0f,
                statusMessage = "Reset position to origin",
                activeRoute = emptyList(),
                selectedDestinationNodeId = null
            )
        }
        _activeRoute.value = emptyList()
    }

    fun syncPositionTo(x: Float, y: Float) {
        particleFilter.forcePosition(x, y)
        val pos = Pair(x, y)
        estimatedPosition.value = pos
        _uiState.update { 
            it.copy(
                userPosition = Coordinate(x, y),
                statusMessage = "Position synced to (%.1f, %.1f)".format(x, y)
            ) 
        }
        updateProximitySnapTarget(x, y)
    }

    fun toggleSurveyorMode(isActive: Boolean = !_isSurveyorMode.value) {
        _isSurveyorMode.value = isActive
        if (isActive) {
            // Entering Surveyor: Interlock - kill active routes
            clearRoute()
            clearSelectedNode()
            _isSosActive.value = false
        } else {
            _undoStack.value = emptyList()
            _redoStack.value = emptyList()
            clearSelectedNode()
            droppedNodesChain.clear()
        }
    }

    fun switchFloor(floor: String? = null) {
        cycleFloor(isSurveyorMode.value)
    }

    fun recenterCamera() {
        recenterTrigger.update { it + 1 }
    }

    fun exploreMap() {
        exploreTrigger.update { it + 1 }
    }

    fun triggerEmergencySOS() {
        clearRoute()
        val exits = cachedNodes.values.filter { it.type == "Emergency Exit" || it.type == "Building Entry" }
        if (exits.isEmpty()) return reportSurveyMessage("No exits are mapped in this building.")
        val start = closestRouteStart() ?: return reportSurveyMessage("Cannot locate starting position on this floor.")
        routeRequest = viewModelScope.launch {
            val best = withContext(Dispatchers.Default) {
                exits.mapNotNull { exit ->
                    findShortestPath(start, exit.id)?.let { exit to it }
                }.minByOrNull { (_, path) ->
                    path.zipWithNext { a, b -> kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) }.sum()
                }
            }
            if (best == null) reportSurveyMessage("No viable path to an exit found.")
            else publishRoute(best.first, best.second, isExit = true)
        }
    }

    fun triggerEgressRouting() {
        triggerEmergencySOS()
    }

    fun toggleSosMode(isActive: Boolean) {
        if (isActive) {
            if (_isSurveyorMode.value) {
                toggleSurveyorMode(false)
            }
            triggerEmergencySOS()
        } else {
            clearDestination()
            _uiState.update { it.copy(statusMessage = "SOS deactivated") }
        }
    }

    fun clearRoute() {
        _transitState.value = null
        clearDestination()
    }

    fun clearDestination() {
        routeRequest?.cancel()
        routeRequest = null
        dismissArrival()
        stopNavigation("Navigation stopped")
    }

    private fun stopNavigation(message: String) {
        _isSosActive.value = false
        activeDestination.value = null
        _activeRoute.value = emptyList()
        _uiState.update {
            it.copy(
                selectedDestinationNodeId = null,
                activeRoute = emptyList(),
                statusMessage = message
            )
        }
    }

    suspend fun getAllBuildings(): List<BuildingEntity> = mapDao.getAllBuildings()

    fun exportMapToJson(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val buildingId = _activeBuildingId.value ?: return@launch
                val exportNodes = mapDao.getNodesByBuilding(buildingId)
                val exportEdges = mapDao.getEdgesByBuilding(buildingId)
                val building = mapDao.getBuildingById(buildingId)
                val safeName = building?.name?.replace(Regex("[^a-zA-Z0-9]"), "_") ?: "Map"
                val timeStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())

                val root = JSONObject().apply {
                    put("version", "1.0")
                    put("building_id", buildingId)
                    if (building != null) {
                        put("building_name", building.name)
                        put("centerLat", building.centerLat)
                        put("centerLng", building.centerLng)
                        put("geofenceRadius", building.geofenceRadius.toDouble())
                    }
                    put("timestamp", System.currentTimeMillis())
                }

                val nodesArray = JSONArray()
                exportNodes.forEach { node ->
                    val nodeObj = JSONObject().apply {
                        put("nodeId", node.nodeId)
                        put("x", node.x.toDouble())
                        put("y", node.y.toDouble())
                        put("floorLevel", node.floorLevel)
                        put("type", node.nodeType)
                        put("label", node.label)
                        put("floorId", node.floorId)
                        put("lat", node.lat)
                        put("lng", node.lng)
                    }
                    nodesArray.put(nodeObj)
                }
                root.put("nodes", nodesArray)

                val edgesArray = JSONArray()
                exportEdges.forEach { edge ->
                    val edgeObj = JSONObject().apply {
                        put("edgeId", edge.edgeId)
                        put("fromNodeId", edge.fromNodeId)
                        put("toNodeId", edge.toNodeId)
                        put("lengthMeters", edge.lengthMeters.toDouble())
                        put("edgeType", edge.edgeType)
                    }
                    edgesArray.put(edgeObj)
                }
                root.put("edges", edgesArray)

                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "${safeName}_v1.0_${timeStr}.json")
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }

                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    contentValues
                )

                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(root.toString(4).toByteArray(Charsets.UTF_8))
                    }
                    _uiState.update { it.copy(statusMessage = "Map exported to Downloads!") }
                    _routeError.tryEmit("Exported $safeName to Downloads!")
                } else {
                    _uiState.update { it.copy(statusMessage = "Failed to export: URI creation failed") }
                    _routeError.tryEmit("Failed to export: URI creation failed")
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(statusMessage = "Export failed: ${e.message}") }
                _routeError.tryEmit("Export failed: ${e.message}")
            }
        }
    }

    fun importMapFromJson(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonString = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.bufferedReader().use { it.readText() }
                } ?: throw IllegalStateException("Failed to open input stream for URI: $uri")

                val root = JSONObject(jsonString)
                val newBuildingId = UUID.randomUUID().toString()

                val timestamp = root.optLong("timestamp", System.currentTimeMillis())
                val dateStr = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(timestamp))
                val buildingName = root.optString("building_name").ifBlank { "Imported Building" }
                val finalBuildingName = "$buildingName [Import $dateStr]"

                val centerLat = root.optDouble("centerLat", 0.0)
                val centerLng = root.optDouble("centerLng", 0.0)
                val geofenceRadius = root.optDouble("geofenceRadius", 150.0).toFloat()
                val newBuilding = BuildingEntity(
                    buildingId = newBuildingId,
                    name = finalBuildingName,
                    centerLat = centerLat,
                    centerLng = centerLng,
                    geofenceRadius = geofenceRadius
                )
                val nodeIdMap = mutableMapOf<String, String>()
                val newNodes = mutableListOf<NodeEntity>()

                val nodesArray = root.optJSONArray("nodes")
                if (nodesArray != null) {
                    for (i in 0 until nodesArray.length()) {
                        val nodeObj = nodesArray.optJSONObject(i) ?: continue
                        val oldId = nodeObj.optString("nodeId").ifBlank { nodeObj.optString("id") }
                        val newId = UUID.randomUUID().toString()
                        if (oldId.isNotBlank()) {
                            nodeIdMap[oldId] = newId
                        }
                        val x = nodeObj.optDouble("x", 0.0).toFloat()
                        val y = nodeObj.optDouble("y", 0.0).toFloat()
                        val floorLevel = nodeObj.optInt("floorLevel", 0)
                        val type = nodeObj.optString("type").ifBlank { nodeObj.optString("nodeType", "Hallway") }
                        val label = nodeObj.optString("label").ifBlank { nodeObj.optString("name", "") }
                        val floorId = nodeObj.optString("floorId").ifBlank { "f1" }
                        val lat = nodeObj.optDouble("lat", 0.0)
                        val lng = nodeObj.optDouble("lng", 0.0)

                        val node = NodeEntity(
                            nodeId = newId,
                            floorId = floorId,
                            x = x,
                            y = y,
                            nodeType = type,
                            name = label,
                            floorLevel = floorLevel,
                            buildingId = newBuildingId,
                            lat = lat,
                            lng = lng
                        )
                        newNodes.add(node)
                    }
                }

                val newEdges = mutableListOf<EdgeEntity>()
                val edgesArray = root.optJSONArray("edges")
                if (edgesArray != null) {
                    for (i in 0 until edgesArray.length()) {
                        val edgeObj = edgesArray.optJSONObject(i) ?: continue
                        val oldFrom = edgeObj.optString("fromNodeId").ifBlank { edgeObj.optString("startNodeId") }
                        val oldTo = edgeObj.optString("toNodeId").ifBlank { edgeObj.optString("endNodeId") }
                        val mappedFrom = nodeIdMap[oldFrom]
                        val mappedTo = nodeIdMap[oldTo]
                        if (mappedFrom != null && mappedTo != null) {
                            val lengthMeters = edgeObj.optDouble("lengthMeters", edgeObj.optDouble("weight", 0.0)).toFloat()
                            val edgeType = edgeObj.optString("edgeType").ifBlank { "hallway" }
                            val edge = EdgeEntity(
                                edgeId = UUID.randomUUID().toString(),
                                fromNodeId = mappedFrom,
                                toNodeId = mappedTo,
                                lengthMeters = lengthMeters,
                                edgeType = edgeType,
                                buildingId = newBuildingId
                            )
                            newEdges.add(edge)
                        }
                    }
                }

                database.insertMap(newBuilding, newNodes, newEdges)
                reportSurveyMessage("Imported $finalBuildingName into Building maps.")
            } catch (e: Exception) {
                _uiState.update { it.copy(statusMessage = "Import failed: ${e.message}") }
                _routeError.tryEmit("Import failed: ${e.message}")
            }
        }
    }
}
