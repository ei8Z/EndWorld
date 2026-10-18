package com.ei8z.endworld.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.ei8z.endworld.BuildConfig
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
        NpcStateEntity::class,
        NpcScheduleEntity::class
    ],
    version = DatabasePolicy.VERSION,
    exportSchema = true
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
    abstract fun npcScheduleDao(): NpcScheduleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val builder = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "end_world_database"
                ).addMigrations(DatabasePolicy.migration10To11(), DatabasePolicy.migration11To12())
                if (DatabasePolicy.destructiveFallbackEnabled(BuildConfig.DEBUG)) {
                    builder.fallbackToDestructiveMigration()
                }
                val instance = builder.build()
                INSTANCE = instance
                instance
            }
        }
    }
}
