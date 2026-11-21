package com.indoorpulse.data.local

import androidx.room.withTransaction
import com.indoorpulse.data.local.entity.BuildingEntity
import com.indoorpulse.data.local.entity.EdgeEntity
import com.indoorpulse.data.local.entity.NodeEntity

internal const val DEMO_MAP_ID = "indoorpulse_demo_v1"

internal suspend fun IndoorPulseDatabase.ensureDemoMap() {
    withTransaction {
        if (mapDao().getBuildingById(DEMO_MAP_ID) != null) return@withTransaction
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
}
