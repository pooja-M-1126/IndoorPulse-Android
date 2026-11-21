package com.indoorpulse.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.indoorpulse.data.local.dao.MapDao
import com.indoorpulse.data.local.entity.BuildingEntity
import com.indoorpulse.data.local.entity.EdgeEntity
import com.indoorpulse.data.local.entity.FloorEntity
import com.indoorpulse.data.local.entity.NodeEntity

@Database(
    entities = [BuildingEntity::class, FloorEntity::class, NodeEntity::class, EdgeEntity::class],
    version = 4,
    exportSchema = false
)
abstract class IndoorPulseDatabase : RoomDatabase() {

    abstract fun mapDao(): MapDao

    suspend fun insertMap(building: BuildingEntity, nodes: List<NodeEntity>, edges: List<EdgeEntity>) {
        withTransaction {
            val dao = mapDao()
            dao.insertBuildings(listOf(building))
            dao.insertNodes(nodes)
            dao.insertEdges(edges)
        }
    }

    suspend fun removePoint(nodeId: String) {
        withTransaction {
            val dao = mapDao()
            dao.deleteEdgesForNode(nodeId)
            dao.deleteNode(nodeId)
        }
    }

    suspend fun restorePoint(node: NodeEntity, edges: List<EdgeEntity>) {
        withTransaction {
            val dao = mapDao()
            dao.insertNodes(listOf(node))
            dao.insertEdges(edges)
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: IndoorPulseDatabase? = null

        fun getDatabase(context: Context): IndoorPulseDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    IndoorPulseDatabase::class.java,
                    "indoorpulse_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
