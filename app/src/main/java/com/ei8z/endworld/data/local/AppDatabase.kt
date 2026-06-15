package com.ei8z.endworld.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.ei8z.endworld.data.local.dao.*
import com.ei8z.endworld.data.local.entity.*
import com.ei8z.endworld.domain.world.dao.*

@Database(
    entities = [
        PlayerState::class,
        InventoryItem::class,
        Memory::class,
        NpcRelation::class,
        WorldInfo::class,
        StoryHistory::class,
        FactionEntity::class,
        RegionEntity::class,
        GlobalEventEntity::class,
        NpcStateEntity::class
    ],
    version = 10,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playerDao(): PlayerDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun memoryDao(): MemoryDao
    abstract fun npcDao(): NpcDao
    abstract fun worldDao(): WorldDao
    abstract fun historyDao(): HistoryDao
    abstract fun factionDao(): FactionDao
    abstract fun regionDao(): RegionDao
    abstract fun globalEventDao(): GlobalEventDao
    abstract fun npcStateDao(): NpcStateDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "end_world_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
