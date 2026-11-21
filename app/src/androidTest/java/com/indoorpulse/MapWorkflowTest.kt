package com.indoorpulse

import android.content.Context
import android.graphics.Bitmap
import android.hardware.SensorManager
import android.location.Location
import android.net.Uri
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.indoorpulse.data.local.IndoorPulseDatabase
import com.indoorpulse.data.local.DEMO_MAP_ID
import com.indoorpulse.data.local.ensureDemoMap
import com.indoorpulse.data.local.dao.BuildingMapSummary
import com.indoorpulse.data.local.entity.BuildingEntity
import com.indoorpulse.data.local.entity.NodeEntity
import com.indoorpulse.data.local.entity.EdgeEntity
import com.indoorpulse.data.sensor.*
import com.indoorpulse.domain.usecase.FindShortestPathUseCase
import com.indoorpulse.presentation.ui.MainScreen
import com.indoorpulse.presentation.ui.FloorStackLayout
import com.indoorpulse.presentation.ui.nearbyMaps
import com.indoorpulse.presentation.ui.stackPoint
import com.indoorpulse.presentation.viewmodel.MapViewModel
import com.indoorpulse.ui.theme.IndoorPulseTheme
import com.indoorpulse.ui.theme.Appearance
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class MapWorkflowTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private lateinit var db: IndoorPulseDatabase
    private lateinit var viewModel: MapViewModel
    private var appearance by mutableStateOf(Appearance.SYSTEM)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun prepareMap() {
        db = Room.inMemoryDatabaseBuilder(context, IndoorPulseDatabase::class.java).build()
        val dao = db.mapDao()
        val building = BuildingEntity("annex", "North Annex", 0.0, 0.0, 100f)
        val lobby = NodeEntity("lobby", "f0", 0f, 0f, "Building Entry", "Lobby", buildingId = "annex")
        val studio = lobby.copy(nodeId = "studio", x = 9f, nodeType = "Room Door", name = "Studio")
        runBlocking {
            dao.insertBuildings(listOf(building))
            dao.insertNodes(listOf(lobby, studio, studio.copy(nodeId = "upstairs", floorLevel = 1, floorId = "f1", name = "Gallery")))
            dao.insertEdges(listOf(EdgeEntity("hall", "lobby", "studio", 9f, "corridor", "annex")))
        }
        val source = object : SensorDataSource {
            override val gravityEvents = emptyFlow<GravityEvent>()
            override val stepEvents = emptyFlow<StepEvent>()
            override val gpsLocations = emptyFlow<GpsLocation>()
            override val orientationEvents = emptyFlow<OrientationEvent>()
        }
        viewModel = ui.runOnUiThread {
            ui.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ViewModelProvider(ui.activity, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = MapViewModel(
                    context.getSystemService(Context.SENSOR_SERVICE) as SensorManager, source,
                    FindShortestPathUseCase(), db
                ) as T
            })[MapViewModel::class.java]
        }
        ui.setContent {
            val dark = when (appearance) {
                Appearance.SYSTEM -> isSystemInDarkTheme()
                Appearance.LIGHT -> false
                Appearance.DARK -> true
            }
            IndoorPulseTheme(darkTheme = dark) {
                MainScreen(viewModel, appearance = appearance, onAppearanceChange = { appearance = it })
            }
        }
    }

    @After fun closeMap() {
        ui.activityRule.scenario.close()
        db.close()
    }

    private fun waitFor(text: String) {
        try {
            ui.waitUntil(8_000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("Missing '$text'. " + ui.onAllNodes(isRoot()).printToString(maxDepth = 6), e)
        }
    }

    private fun click(text: String) {
        val action = hasText(text) and isEnabled() and hasClickAction() and !hasSetTextAction()
        try {
            ui.waitUntil(8000) { ui.onAllNodes(action).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("Missing action '$text'. " + ui.onAllNodes(isRoot()).printToString(maxDepth = 12), e)
        }
        ui.onNode(action).performClick()
    }

    private fun openMap() {
        click("North Annex")
        click("Lobby")
        waitFor("Navigate · L0")
    }

    private fun toggleSurveyorMode() {
        ui.onNodeWithContentDescription("Menu").performClick()
        click("Surveyor Mode")
    }

    private fun capture(name: String, dialog: Boolean = false) {
        if (InstrumentationRegistry.getArguments().getString("captureUi") != "true") return
        val image = if (dialog) {
            ui.waitForIdle()
            // Dialog window transitions run outside the Compose test clock.
            SystemClock.sleep(380)
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                ?: error("Could not capture the dialog")
        } else {
            ui.onRoot().captureToImage().asAndroidBitmap()
        }
        File(context.cacheDir, "ui-audit-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun appearanceCanSwitchWithoutClosingTheMap() {
        fun option(label: String) = ui.onNode(
            hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        )
        openMap()
        ui.onNodeWithContentDescription("Menu").performClick()
        click("Appearance")
        option("System").assertIsSelected()
        option("Light").performClick()
        option("Light").assertIsSelected()
        click("Done")
        waitFor("Navigate · L0")
        capture("theme-light")

        ui.onNodeWithContentDescription("Menu").performClick()
        click("Appearance")
        option("Dark").performClick()
        option("Dark").assertIsSelected()
        click("Done")
        waitFor("Navigate · L0")
        capture("theme-dark")
    }

    @Test fun libraryAndNewSurvey() {
        waitFor("North Annex")
        ui.onNodeWithText("2 floors · 3 mapped points").assertExists()
        click("Create map")
        ui.onNodeWithText("Start survey").assertIsNotEnabled()
        ui.onNodeWithText("Building name").performTextInput("New wing")
        ui.onNodeWithText("Detection radius (m)").performTextReplacement("0")
        ui.onNodeWithText("Start survey").assertIsNotEnabled()
        ui.onNodeWithText("Detection radius (m)").performTextReplacement("80")
        click("Start survey")
        waitFor("Survey · L0")
        click("Add point")
        click("Undo")
        click("Redo")
        ui.waitUntil(8000) { runBlocking { db.mapDao().getNodesByBuilding(db.mapDao().getAllBuildings().single { it.name == "New wing" }.buildingId).size == 1 } }
        ui.onNodeWithContentDescription("Map options").performClick()
        click("Leave map")
        click("Stay here")
        ui.onNodeWithContentDescription("Map options").performClick()
        click("Leave map")
        click("Leave map")
        waitFor("1 floor · 1 mapped point")
    }

    @Test fun searchNavigationExitAndMapOptions() {
        openMap()
        click("Avoid stairs")
        click("Find a destination")
        ui.onNodeWithText("Room, place or floor").performTextInput("zzzz")
        ui.onNodeWithText("No destinations match “zzzz”.").assertExists()
        ui.onNodeWithText("Room, place or floor").performTextReplacement("L1")
        waitFor("Gallery")
        ui.onNodeWithText("Room, place or floor").performTextReplacement("Studio")
        click("Studio")
        waitFor("NAVIGATING TO:")
        capture("route")
        ui.onNodeWithContentDescription("Cancel Route").performClick()
        click("Find exit")
        waitFor("At the mapped exit")
        click("Done")
        ui.onNodeWithContentDescription("Menu").performClick()
        ui.onNodeWithText("Export Map Backup").assertIsDisplayed()
        ui.onNodeWithText("Import Map Backup").assertIsDisplayed()
    }

    @Test fun arrivingStopsRouteAndKeepsConfirmationUntilDone() {
        openMap()
        click("Find a destination")
        click("Studio")
        waitFor("NAVIGATING TO:")
        ui.runOnIdle {
            viewModel.setFloor(1)
            viewModel.onLocationUpdated(9f, 0f)
        }
        ui.onNodeWithText("You’ve arrived").assertDoesNotExist()

        ui.runOnIdle {
            viewModel.setFloor(0)
            viewModel.onLocationUpdated(9f, 0f)
            viewModel.onLocationUpdated(12f, 0f)
            assertTrue(viewModel.activeRoute.value.isEmpty())
            assertTrue(viewModel.uiState.value.activeRoute.isEmpty())
        }
        waitFor("You’ve arrived")
        ui.onNodeWithText("Studio · L0").assertIsDisplayed()
        ui.onNodeWithText("NAVIGATING TO:").assertDoesNotExist()
        click("Done")
        waitFor("Find a destination")
    }

    @Test fun demoCanRouteWithoutLocation() {
        runBlocking {
            db.ensureDemoMap()
            db.ensureDemoMap()
        }
        click("Try demo map")
        click("Front Entry")
        waitFor("Navigate · L0")
        click("Avoid stairs")
        click("Find a destination")
        click("Room 104")
        waitFor("NAVIGATING TO:")
        ui.onNodeWithText("Room 104").assertIsDisplayed()
        ui.onNodeWithText("Leaving the building?").assertDoesNotExist()
    }

    @Test fun routePreviewUsesElevatorAndLeavesLivePositionUntouched() {
        runBlocking {
            db.ensureDemoMap()
            val lower = NodeEntity("test-stair0", "f0", 0f, 9f, "Stairs", "Main stairs", 0, DEMO_MAP_ID)
            val upper = lower.copy(nodeId = "test-stair1", floorId = "f1", floorLevel = 1)
            db.mapDao().insertNodes(listOf(lower, upper))
            db.mapDao().insertEdges(listOf(
                EdgeEntity("test-stair-in", "${DEMO_MAP_ID}_hall", lower.nodeId, 1f, "corridor", DEMO_MAP_ID),
                EdgeEntity("test-stair-between", lower.nodeId, upper.nodeId, 3.5f, "vertical_portal", DEMO_MAP_ID),
                EdgeEntity("test-stair-out", upper.nodeId, "${DEMO_MAP_ID}_room104", 4.2f, "corridor", DEMO_MAP_ID)
            ))
        }
        click("Try demo map")
        click("Front Entry")
        waitFor("Navigate · L0")
        val position = ui.runOnIdle {
            val path = checkNotNull(viewModel.findShortestPath("${DEMO_MAP_ID}_front", "${DEMO_MAP_ID}_room104"))
            assertTrue(path.any { it.nodeType == "Stairs" })
            viewModel.estimatedPosition.value
        }
        click("Avoid stairs")
        click("Find a destination")
        ui.onNodeWithContentDescription("Preview route to Room 104, L1").assertIsDisplayed().performClick()
        waitFor("Route preview")
        waitFor("Walk to Main corridor")
        ui.onNodeWithText("Previous").assertIsNotEnabled()
        click("Next")
        waitFor("Turn right")
        click("Next")
        waitFor("Take the elevator to L1")
        ui.onNodeWithContentDescription("3D building map, floor L0, floor L1").assertIsDisplayed()
        capture("route-preview-elevator", dialog = true)
        click("Previous")
        waitFor("Turn right")
        click("Next")
        click("Next")
        ui.onNodeWithContentDescription("3D building map, floor L1").assertIsDisplayed()
        click("Next")
        waitFor("Arrive at Room 104")
        click("Done")
        waitFor("Navigate · L0")
        ui.runOnIdle {
            assertEquals(position, viewModel.estimatedPosition.value)
            assertEquals(0, viewModel.currentFloor.value)
            assertTrue(viewModel.activeRoute.value.isEmpty())
            assertEquals(null, viewModel.arrival.value)
        }
        click("Find a destination")
        click("Room 104")
        waitFor("NAVIGATING TO:")
    }

    @Test fun threeDimensionalOverviewRotationDoesNotChangeNavigationFloor() {
        ui.runOnIdle { appearance = Appearance.LIGHT }
        openMap()
        click("3D map")
        waitFor("3D building map")
        val canvas = ui.onNodeWithContentDescription("3D building map, all floors")
        canvas.assertIsDisplayed()
        chooseOverviewFloor("Floor L1")
        val floorCanvas = ui.onNodeWithContentDescription("3D building map, floor L1")
        floorCanvas.assertIsDisplayed()
        val initialCamera = floorCanvas.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        floorCanvas.performTouchInput { swipe(center, center + Offset(90f, -40f)) }
        assertTrue(initialCamera != floorCanvas.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        chooseOverviewFloor("Reset view")
        assertEquals(initialCamera, floorCanvas.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        floorCanvas.performTouchInput {
            down(0, center - Offset(35f, 0f))
            down(1, center + Offset(35f, 0f))
            for (radius in 45..105 step 15) {
                updatePointerTo(0, center - Offset(radius.toFloat(), 0f))
                updatePointerTo(1, center + Offset(radius.toFloat(), 0f))
                move()
            }
            up(1)
            up(0)
        }
        val zoomedCamera = floorCanvas.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertTrue(zoomedCamera.substringAfterLast("zoom ").substringBefore(' ').toInt() > 100)
        assertEquals(initialCamera.substringBefore("zoom"), zoomedCamera.substringBefore("zoom"))
        chooseOverviewFloor("Reset view")
        chooseOverviewFloor("All floors")
        canvas.assertIsDisplayed()
        capture("building-stack", dialog = true)
        ui.onNodeWithContentDescription("Close 3D map").performClick()
        waitFor("Navigate · L0")
        click("Find a destination")
        click("Studio")
        waitFor("NAVIGATING TO:")
        val route = ui.runOnIdle { viewModel.activeRoute.value }
        assertTrue(route.isNotEmpty())
        ui.onNodeWithContentDescription("Show route on map").performClick()
        waitFor("Recenter")
        click("3D map")
        chooseOverviewFloor("Floor L1")
        ui.onNodeWithContentDescription("Close 3D map").performClick()
        waitFor("NAVIGATING TO:")
        ui.runOnIdle {
            assertEquals(route, viewModel.activeRoute.value)
            assertEquals(0, viewModel.currentFloor.value)
        }
    }

    private fun openOverviewPicker() {
        ui.onNodeWithContentDescription("Choose floors and view settings").performClick()
        waitFor("Floors and view")
    }

    private fun chooseOverviewFloor(option: String) {
        openOverviewPicker()
        if (option.startsWith("Floor L")) {
            ui.onNodeWithTag("Overview floor list").performScrollToNode(hasText(option))
        }
        click(option)
        ui.onNodeWithText("Floors and view").assertDoesNotExist()
    }

    @Test fun nineFloorOverviewSeparatesPortalsAndFollowsLinkedFloors() {
        ui.runOnIdle { appearance = Appearance.DARK }
        val floors = (-1..7).toList()
        val building = BuildingEntity("tower", "Test tower", 0.0, 0.0, 0f)
        val floorsNodes = floors.associateWith { floor ->
            fun node(id: String, name: String, type: String, x: Float, y: Float) =
                NodeEntity("$id-$floor", "f$floor", x, y, type, name, floor, "tower")
            listOf(
                node("entry", if (floor == 0) "Tower entrance" else "West hall", if (floor == 0) "Building Entry" else "Junction", 0f, 0f),
                node("corner", "Main junction", "Junction", 0f, 16f),
                node("room", "Studio ${floor + 10}", "Room Door", 18f, 16f),
                node("lift", "East elevator", "Elevator", 18f, 4f),
                node("stairs", "West stairs", "Stairs", -6f, 14f)
            ) + (1..4).map { node("turn$it", "", "Waypoint", 3.6f * it, 16f) } +
                (1..4).map { node("desk$it", "Desk $it", "Room Door", 3.6f * it, 12.5f) }
        }
        val nodes = floorsNodes.values.flatten()
        fun link(from: NodeEntity, to: NodeEntity, type: String) =
            EdgeEntity("${from.nodeId}_${to.nodeId}", from.nodeId, to.nodeId, 5f, type, "tower")
        val corridors = floorsNodes.values.flatMap { points ->
            val hall = listOf(points[1]) + points.subList(5, 9) + points[2]
            hall.zipWithNext().map { (from, to) -> link(from, to, "corridor") } +
                (5..8).map { index -> link(points[index], points[index + 4], "corridor") } +
                listOf(link(points[0], points[1], "corridor"), link(points[2], points[3], "corridor"),
                    link(points[1], points[4], "corridor"))
        }
        val shafts = floors.zipWithNext().flatMap { (floor, next) ->
            listOf(3, 4).map { index -> link(floorsNodes.getValue(floor)[index], floorsNodes.getValue(next)[index], "vertical_portal") }
        }
        runBlocking { db.insertMap(building, nodes, corridors + shafts) }
        click("Test tower")
        click("Tower entrance")
        waitFor("Navigate · L0")
        click("3D map")
        val canvas = ui.onNodeWithContentDescription("3D building map, all floors")
        assertTrue(canvas.fetchSemanticsNode().size.width > ui.activity.window.decorView.width * 0.87f)
        ui.onNodeWithText("Floor L7").assertDoesNotExist()
        ui.onNodeWithContentDescription("Floor spacing").assertDoesNotExist()
        capture("full-width-nine-floors", dialog = true)
        chooseOverviewFloor("Current floor · L0")
        ui.onNodeWithContentDescription("3D building map, floor L0").assertIsDisplayed()
        chooseOverviewFloor("Floor L-1")
        ui.onNodeWithContentDescription("3D building map, floor L-1").assertIsDisplayed()
        openOverviewPicker()
        capture("compact-floor-picker", dialog = true)
        ui.onNodeWithContentDescription("Floor spacing").performSemanticsAction(SemanticsActions.SetProgress) { it(1.4f) }
        ui.onNodeWithTag("Overview floor list").performScrollToNode(hasText("Floor L1"))
        click("Floor L1")
        val focused = ui.onNodeWithContentDescription("3D building map, floor L1")
        focused.assertIsDisplayed()
        ui.onNodeWithText("East elevator").assertIsDisplayed()
        ui.onNodeWithText("West stairs").assertIsDisplayed()
        val layout = FloorStackLayout(nodes.map(NodeEntity::stackPoint), floors)
        val bounds = layout.bounds(listOf(1), spacing = 1.4f)
        val lift = floorsNodes.getValue(1)[3]
        val displayDensity = ui.activity.resources.displayMetrics.density
        focused.performTouchInput {
            val scale = minOf((width - 36 * displayDensity) / bounds.width, (height - 54 * displayDensity) / bounds.height)
            click(center + (layout.project(lift.stackPoint(), spacing = 1.4f) - bounds.center) * scale)
        }
        waitFor("East elevator · L1")
        capture("orbit-focused-portal", dialog = true)
        click("View L2")
        ui.onNodeWithContentDescription("3D building map, floor L2").assertIsDisplayed()
        click("West stairs")
        waitFor("West stairs · L2")
        click("View L1")
        ui.onNodeWithContentDescription("3D building map, floor L1").assertIsDisplayed()
        ui.onNodeWithContentDescription("Close 3D map").performClick()
        waitFor("Navigate · L0")
        click("Find a destination")
        ui.onNodeWithText("Room, place or floor").performTextInput("Studio 10")
        click("Studio 10")
        waitFor("NAVIGATING TO:")
        ui.onNodeWithContentDescription("Show route on map").performClick()
        waitFor("Recenter")
        capture("route-contrast-dark")
        ui.runOnIdle { appearance = Appearance.LIGHT }
        capture("route-contrast-light")
        ui.runOnIdle { assertEquals(0, viewModel.currentFloor.value) }
    }

    @Test fun nearbyMapsRejectsUnreliableFixesAndRanksMappedBuildings() {
        val closest = BuildingEntity("near", "Near wing", 12.0, 77.0, 150f)
        val next = closest.copy(buildingId = "next", name = "Next wing", centerLat = 12.0005)
        val outside = closest.copy(buildingId = "outside", centerLat = 12.05)
        val maps = listOf(next, outside, closest).map { BuildingMapSummary(it, pointCount = 2, floorCount = 1) }
        val fix = Location("fixture").apply {
            latitude = 12.0
            longitude = 77.0
            accuracy = 10f
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        assertEquals(listOf("near", "next"), nearbyMaps(maps, fix).map { it.building.buildingId })
        assertTrue(nearbyMaps(maps, Location(fix).apply { accuracy = 500f }).isEmpty())
        assertTrue(nearbyMaps(maps, Location(fix).apply { elapsedRealtimeNanos -= 121_000_000_000L }).isEmpty())
    }

    @Test fun deletingLinkedPointCanBeUndoneAndRedone() {
        openMap()
        toggleSurveyorMode()
        val dao = db.mapDao()
        val studio = runBlocking { dao.getNodesByBuilding("annex").single { it.nodeId == "studio" } }
        ui.runOnIdle { viewModel.selectNode(studio) }
        click("Edit")
        ui.onNodeWithText("Delete point").performScrollTo().performClick()
        click("Delete point")

        ui.waitUntil(8_000) { viewModel.activeNodes.value.none { it.nodeId == studio.nodeId } }
        assertTrue(runBlocking { dao.getEdgesByBuilding("annex") }.isEmpty())
        click("Undo")
        ui.waitUntil(8_000) { viewModel.activeNodes.value.any { it.nodeId == studio.nodeId } }
        val restored = runBlocking { dao.getEdgesByBuilding("annex").single() }
        assertEquals("hall", restored.edgeId)
        assertEquals(studio.nodeId, restored.toNodeId)
        click("Redo")
        ui.waitUntil(8_000) { viewModel.activeNodes.value.none { it.nodeId == studio.nodeId } }
        assertTrue(runBlocking { dao.getEdgesByBuilding("annex") }.isEmpty())
    }

    @Test fun importingBackupKeepsTheOpenMapAndRemapsLinks() {
        openMap()
        val backup = File.createTempFile("map-audit-", ".json", context.cacheDir)
        try {
            backup.writeText("""
                {"building_name":"Restored wing","nodes":[
                    {"nodeId":"lobby","label":"Imported lobby","x":0,"y":0},
                    {"nodeId":"studio","label":"Imported studio","x":9,"y":0,"floorLevel":1,"floorId":"f1"}
                ],"edges":[{"fromNodeId":"lobby","toNodeId":"studio","lengthMeters":9}]}
            """.trimIndent())
            ui.runOnIdle { viewModel.importMapFromJson(context, Uri.fromFile(backup)) }
            ui.waitUntil(8_000) {
                viewModel.buildingMaps.value.any { it.building.name.startsWith("Restored wing") && it.pointCount == 2 }
            }
            assertEquals("annex", viewModel.activeBuildingId.value)
            val imported = viewModel.buildingMaps.value.single { it.building.name.startsWith("Restored wing") }
            val dao = db.mapDao()
            runBlocking {
                val nodes = dao.getNodesByBuilding(imported.building.buildingId).associateBy { it.name }
                val link = dao.getEdgesByBuilding(imported.building.buildingId).single()
                assertEquals(nodes.getValue("Imported lobby").nodeId, link.fromNodeId)
                assertEquals(nodes.getValue("Imported studio").nodeId, link.toNodeId)
                assertEquals(1, nodes.getValue("Imported studio").floorLevel)
                assertEquals("hall", dao.getEdgesByBuilding("annex").single().edgeId)
            }
        } finally {
            backup.delete()
        }
    }

    @Test fun crossFloorLinksRequirePortalsAndElevatorTransitChangesFloors() {
        val dao = db.mapDao()
        val elevator = NodeEntity("lift", "f0", 4f, 0f, "Elevator", "Lift", buildingId = "annex")
        val nodes = runBlocking {
            dao.insertNodes(listOf(elevator))
            dao.insertEdges(listOf(EdgeEntity("lift-hall", "lobby", "lift", 4f, "corridor", "annex")))
            dao.getNodesByBuilding("annex").associateBy { it.nodeId }
        }
        openMap()
        toggleSurveyorMode()
        val gallery = nodes.getValue("upstairs")
        for (origin in listOf(nodes.getValue("studio"), elevator)) {
            ui.runOnIdle {
                viewModel.resumePathFromNode(origin)
                viewModel.selectNode(gallery)
                viewModel.snapToNearestNode()
                assertTrue(viewModel.uiState.value.statusMessage.startsWith("Cannot link"))
            }
        }
        assertEquals(2, runBlocking { dao.getEdgesByBuilding("annex") }.size)

        ui.runOnIdle { viewModel.selectNode(elevator) }
        click("Extend up a floor")
        waitFor("Survey · L1")
        val upperElevator = runBlocking {
            dao.getNodesByBuilding("annex").single { it.nodeType == "Elevator" && it.floorLevel == 1 }
        }
        val portal = runBlocking { dao.getEdgesByBuilding("annex").single { it.edgeType == "vertical_portal" } }
        assertEquals(elevator.nodeId, portal.fromNodeId)
        assertEquals(upperElevator.nodeId, portal.toNodeId)

        toggleSurveyorMode()
        ui.runOnIdle {
            viewModel.setFloor(0)
            viewModel.requestRoute(upperElevator.nodeId)
        }
        ui.waitUntil(8_000) { viewModel.activeRoute.value.size >= 3 }
        ui.runOnIdle {
            viewModel.onLocationUpdated(0f, 0f)
            viewModel.onLocationUpdated(elevator.x, elevator.y)
        }
        waitFor("Take Elevator")
        click("I've Arrived at L1")
        waitFor("Navigate · L1")
        waitFor("You’ve arrived")
        click("Done")
    }

    @Test fun surveyCameraAndEditing() {
        openMap()
        ui.onNodeWithText("Survey").assertDoesNotExist()
        toggleSurveyorMode()
        click("L0 ▾")
        click("Floor L1")
        waitFor("Survey · L1")
        click("3D")
        click("2D")
        val map = ui.onNodeWithContentDescription("Indoor map, floor L1")
        map.performTouchInput { swipe(center, center + androidx.compose.ui.geometry.Offset(90f, -60f)) }
        ui.onNodeWithText("Position").assertIsEnabled()
        click("Recenter")
        ui.onNodeWithText("Position").assertIsNotEnabled()
        click("Explore")
        ui.onNodeWithText("Position").assertIsEnabled()
        click("Recenter")
        click("Waypoint ▾")
        click("Room Door")
        click("Add point")
        ui.onNodeWithText("Name (optional)").performTextInput("Lab A")
        click("Save")
        ui.waitUntil(8_000) {
            ui.runOnIdle {
                ViewCompat.getRootWindowInsets(ui.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) != true
            }
        }
        click("Edit")
        waitFor("Edit Node: Lab A")
        click("Rename point")
        ui.onNodeWithText("Point name").performTextReplacement("Lab B")
        click("Save name")
        waitFor("Edit Node: Lab B")
        ui.onNodeWithText("Delete point").performScrollTo().performClick()
        click("Keep point")
        ui.onNodeWithText("Done").performScrollTo().performClick()
        capture("survey")
        click("Edit")
        ui.onNodeWithText("Delete point").performScrollTo().performClick()
        click("Delete point")
        ui.waitUntil(8000) { runBlocking { db.mapDao().getNodesByBuilding("annex").none { it.name == "Lab B" } } }
    }
}
