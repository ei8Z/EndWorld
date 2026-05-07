package com.ei8z.endworld.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ei8z.endworld.data.local.entity.PlayerState
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayerDao {
    @Query("SELECT * FROM player_state WHERE id = 1")
    fun getPlayer(): Flow<PlayerState?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updatePlayerState(playerState: PlayerState)
}
