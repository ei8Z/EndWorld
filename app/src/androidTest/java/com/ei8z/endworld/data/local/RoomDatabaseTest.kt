package com.ei8z.endworld.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ei8z.endworld.data.local.entity.PlayerState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomDatabaseTest {
    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun writeAndReadPlayerState() = runBlocking {
        val player = PlayerState(
            id = 1,
            userId = "test_user_001",
            name = "TestPlayer",
            genderPhysique = "Male",
            personality = "Brave",
            baseDescription = "Survivor",
            doomType = "Zombie",
            mentalStateTags = listOf("Hungry", "Scared"),
            innerThought = "I need food",
            equippedItems = listOf("Knife")
        )
        db.playerDao().updatePlayerState(player)
        val result = db.playerDao().getPlayer().first()
        
        assertEquals("test_user_001", result?.userId)
        assertEquals(2, result?.mentalStateTags?.size)
        assertEquals("Hungry", result?.mentalStateTags?.get(0))
    }
}
