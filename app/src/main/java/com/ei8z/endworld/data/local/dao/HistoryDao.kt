package com.ei8z.endworld.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ei8z.endworld.data.local.entity.StoryHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM story_history ORDER BY timestamp ASC")
    fun getAllHistory(): Flow<List<StoryHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: StoryHistory)

    @Query("DELETE FROM story_history")
    suspend fun clearHistory()
}
