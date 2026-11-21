package com.indoorpulse.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import kotlinx.coroutines.flow.Flow
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.indoorpulse.data.local.entity.BuildingEntity
import com.indoorpulse.data.local.entity.EdgeEntity
import com.indoorpulse.data.local.entity.FloorEntity
import com.indoorpulse.data.local.entity.NodeEntity

data class BuildingMapSummary(
    @Embedded val building: BuildingEntity,
    val pointCount: Int,
    val floorCount: Int
)

@Dao
@JvmSuppressWildcards
interface MapDao {
    @Query("""
        SELECT buildings.*, COUNT(nodes.nodeId) AS pointCount,
               COUNT(DISTINCT nodes.floorLevel) AS floorCount
        FROM buildings LEFT JOIN nodes ON nodes.buildingId = buildings.buildingId
        GROUP BY buildings.buildingId ORDER BY buildings.name COLLATE NOCASE
    """)
    fun observeBuildingMaps(): Flow<List<BuildingMapSummary>>

    // --- Building Queries ---
    @Query("SELECT * FROM buildings")
    suspend fun getAllBuildings(): List<BuildingEntity>

    @Query("SELECT * FROM buildings WHERE buildingId = :buildingId")
    suspend fun getBuildingById(buildingId: String): BuildingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBuildings(buildings: List<BuildingEntity>): List<Long>

    @Query("DELETE FROM buildings WHERE buildingId = :buildingId")
    suspend fun deleteBuilding(buildingId: String): Int

    // --- Building-Scoped Queries ---
    @Query("SELECT * FROM nodes WHERE buildingId = :buildingId")
    suspend fun getNodesByBuilding(buildingId: String): List<NodeEntity>

    @Query("SELECT * FROM edges WHERE buildingId = :buildingId")
    suspend fun getEdgesByBuilding(buildingId: String): List<EdgeEntity>

    @Query("SELECT * FROM nodes WHERE buildingId = :buildingId AND (nodeType = 'Building Entry' OR nodeType = 'Emergency Exit')")
    suspend fun getEntryPoints(buildingId: String): List<NodeEntity>

    // --- Floor & General Queries ---
    @Query("SELECT * FROM floors")
    suspend fun getFloors(): List<FloorEntity>

    @Query("SELECT * FROM nodes WHERE floorId = :floorId")
    suspend fun getNodesByFloor(floorId: String): List<NodeEntity>

    @Query("SELECT * FROM edges")
    suspend fun getAllEdges(): List<EdgeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFloors(floors: List<FloorEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNodes(nodes: List<NodeEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEdges(edges: List<EdgeEntity>): List<Long>

    @Query("UPDATE nodes SET nodeType = :type WHERE nodeId = :nodeId")
    suspend fun setNodeType(nodeId: String, type: String): Int

    @Query("UPDATE nodes SET name = :name WHERE nodeId = :nodeId")
    suspend fun renameNode(nodeId: String, name: String): Int

    @Query("DELETE FROM nodes WHERE nodeId = :nodeId")
    suspend fun deleteNode(nodeId: String): Int

    @Query("DELETE FROM edges WHERE fromNodeId = :nodeId OR toNodeId = :nodeId")
    suspend fun deleteEdgesForNode(nodeId: String): Int

    @Query("DELETE FROM edges WHERE edgeId = :edgeId")
    suspend fun deleteEdge(edgeId: String): Int
}
