package com.indoorpulse.data.local

import androidx.room.withTransaction
import com.indoorpulse.data.local.entity.BuildingEntity
import com.indoorpulse.data.local.entity.EdgeEntity
import com.indoorpulse.data.local.entity.NodeEntity
import kotlin.math.hypot

internal const val DEMO_MAP_ID = "indoorpulse_demo_v1"
internal const val LARGE_DEMO_MAP_ID = "indoorpulse_demo_innovation_v1"

internal fun isDemoMap(buildingId: String) = buildingId == DEMO_MAP_ID || buildingId == LARGE_DEMO_MAP_ID

internal suspend fun IndoorPulseDatabase.ensureDemoMap() {
    withTransaction {
        val dao = mapDao()
        if (dao.getBuildingById(DEMO_MAP_ID) == null) insertLearningCentre()
        if (dao.getBuildingById(LARGE_DEMO_MAP_ID) == null) {
            val (nodes, edges) = innovationCentreGraph()
            insertMap(BuildingEntity(LARGE_DEMO_MAP_ID, "Demo Innovation Centre", 0.0, 0.0, 0f), nodes, edges)
        }
    }
}

private suspend fun IndoorPulseDatabase.insertLearningCentre() {
    fun point(id: String, name: String, type: String, x: Float, y: Float, level: Int = 0) =
        NodeEntity("${DEMO_MAP_ID}_$id", "f$level", x, y, type, name, level, DEMO_MAP_ID)

    val entry = point("front", "Front Entry", "Building Entry", 0f, 0f)
    val junction = point("hall", "Main corridor", "Junction", 0f, 8f)
    val reception = point("reception", "Reception", "Room Door", -5f, 8f)
    val lift0 = point("lift0", "Elevator (L0)", "Elevator", 6f, 8f)
    val lift1 = point("lift1", "Elevator (L1)", "Elevator", 6f, 8f, 1)
    val room = point("room104", "Room 104", "Room Door", -4f, 8f, 1)
    val exit = point("exit", "Side Exit", "Emergency Exit", 0f, 18f)
    fun link(a: NodeEntity, b: NodeEntity, metres: Float, type: String = "corridor") =
        EdgeEntity("${a.nodeId}_${b.nodeId}", a.nodeId, b.nodeId, metres, type, DEMO_MAP_ID)

    insertMap(
        BuildingEntity(DEMO_MAP_ID, "Demo Learning Centre", 0.0, 0.0, 0f),
        listOf(entry, junction, reception, lift0, lift1, room, exit),
        listOf(
            link(entry, junction, 8f), link(junction, reception, 5f), link(junction, exit, 10f),
            link(junction, lift0, 6f), link(lift0, lift1, 3.5f, "vertical_portal"), link(lift1, room, 10f)
        )
    )
}

internal fun innovationCentreGraph(): Pair<List<NodeEntity>, List<EdgeEntity>> {
    val building = LARGE_DEMO_MAP_ID
    val nodes = mutableListOf<NodeEntity>()
    val edges = mutableListOf<EdgeEntity>()
    fun point(floor: Int, id: String, x: Float, y: Float, type: String = "Junction", name: String = ""): NodeEntity =
        NodeEntity("${building}_${floor}_$id", "f$floor", x, y, type, name, floor, building).also(nodes::add)

    fun connect(from: NodeEntity, to: NodeEntity) {
        val vertical = from.floorLevel != to.floorLevel
        edges += EdgeEntity(
            "${from.nodeId}_${to.nodeId}", from.nodeId, to.nodeId,
            if (vertical) 3.5f else hypot(to.x - from.x, to.y - from.y),
            if (vertical) "vertical_portal" else "corridor", building
        )
    }

    val roomNames = listOf(
        listOf("Storage", "Bike Room", "Print Workshop", "Archive"),
        listOf("Reception", "Auditorium", "Café", "Visitor Services"),
        listOf("Robotics Lab", "Electronics Lab", "Design Studio", "Seminar Room"),
        listOf("Quiet Study", "Project Room", "Research Library", "Meeting Room"),
        listOf("Media Lab", "Observation Studio", "Exhibition Hall", "Terrace Lounge")
    )
    val liftLandings = mutableListOf<NodeEntity>()
    val stairLandings = mutableListOf<NodeEntity>()
    for ((index, rooms) in roomNames.withIndex()) {
        val floor = index - 1
        val west = point(floor, "west", 0f, 8f)
        val south = point(floor, "south", 16f, 8f)
        val east = point(floor, "east", 32f, 8f)
        val northEast = point(floor, "north_east", 32f, 24f)
        val north = point(floor, "north", 16f, 24f)
        val northWest = point(floor, "north_west", 0f, 24f)
        val loop = listOf(west, south, east, northEast, north, northWest, west)
        loop.zipWithNext().forEach { (from, to) -> connect(from, to) }
        connect(south, north)

        val lift = point(floor, "lift", 36f, 24f, "Elevator", "East Elevator")
        val stairs = point(floor, "stairs", -4f, 8f, "Stairs", "West Stairs")
        connect(northEast, lift)
        connect(west, stairs)
        liftLandings += lift
        stairLandings += stairs

        connect(south, point(floor, "room_south", 16f, 3f, "Room Door", rooms[0]))
        connect(east, point(floor, "room_east", 38f, 8f, "Room Door", rooms[1]))
        connect(north, point(floor, "room_north", 16f, 30f, "Room Door", rooms[2]))
        connect(northWest, point(floor, "room_west", -5f, 24f, "Room Door", rooms[3]))
        if (floor == 0) {
            connect(west, point(floor, "entry", 0f, 0f, "Building Entry", "Main Entrance"))
            connect(east, point(floor, "exit", 32f, 0f, "Emergency Exit", "East Exit"))
        }
    }
    for (landings in listOf(liftLandings, stairLandings)) {
        landings.zipWithNext().forEach { (from, to) -> connect(from, to) }
    }
    return nodes to edges
}
