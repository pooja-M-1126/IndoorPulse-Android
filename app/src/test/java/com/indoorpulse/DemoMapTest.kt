package com.indoorpulse

import com.indoorpulse.data.local.innovationCentreGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoMapTest {
    @Test fun innovationCentreIsConnectedWithSeparateStairAndElevatorOptions() {
        val (nodes, edges) = innovationCentreGraph()
        val byId = nodes.associateBy { it.nodeId }
        assertEquals(nodes.size, byId.size)
        assertEquals(edges.size, edges.map { it.edgeId }.toSet().size)
        assertEquals((-1..3).toSet(), nodes.map { it.floorLevel }.toSet())

        val connections = mutableMapOf<String, MutableList<String>>()
        for (edge in edges) {
            val from = byId.getValue(edge.fromNodeId)
            val to = byId.getValue(edge.toNodeId)
            assertEquals(from.buildingId, edge.buildingId)
            assertEquals(to.buildingId, edge.buildingId)
            assertTrue(edge.lengthMeters > 0f)
            if (edge.edgeType == "vertical_portal") {
                assertEquals(from.floorLevel + 1, to.floorLevel)
                assertEquals(from.nodeType, to.nodeType)
                assertTrue(from.nodeType in setOf("Elevator", "Stairs"))
                assertEquals(from.x to from.y, to.x to to.y)
            } else {
                assertEquals(from.floorLevel, to.floorLevel)
            }
            connections.getOrPut(from.nodeId, ::mutableListOf).add(to.nodeId)
            connections.getOrPut(to.nodeId, ::mutableListOf).add(from.nodeId)
        }

        val entry = nodes.single { it.nodeType == "Building Entry" }
        for (excluded in listOf(null, "Stairs", "Elevator")) {
            val pending = ArrayDeque(listOf(entry.nodeId))
            val visited = mutableSetOf<String>()
            while (pending.isNotEmpty()) {
                val id = pending.removeFirst()
                if (byId.getValue(id).nodeType != excluded && visited.add(id)) {
                    pending.addAll(connections[id].orEmpty())
                }
            }
            assertEquals(nodes.filter { it.nodeType != excluded }.map { it.nodeId }.toSet(), visited)
        }
    }
}
