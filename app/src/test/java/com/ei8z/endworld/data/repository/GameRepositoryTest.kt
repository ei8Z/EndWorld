package com.ei8z.endworld.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.data.local.dao.*
import com.ei8z.endworld.data.local.entity.*
import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.MemosApiService
import com.ei8z.endworld.data.remote.model.*
import io.mockk.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class GameRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var api: DeepSeekApiService
    private lateinit var memosApi: MemosApiService
    private lateinit var context: Context

    private lateinit var playerDao: PlayerDao
    private lateinit var inventoryDao: InventoryDao
    private lateinit var memoryDao: MemoryDao
    private lateinit var worldDao: WorldDao
    private lateinit var npcDao: NpcDao
    private lateinit var historyDao: HistoryDao
    private lateinit var prefs: SharedPreferences
    private lateinit var prefsEdit: SharedPreferences.Editor

    @Before
    fun setUp() {
        // Platform mocks
        db = mockk(relaxed = true)
        api = mockk(relaxed = true)
        memosApi = mockk(relaxed = true)
        context = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        prefsEdit = mockk(relaxed = true)

        // DAO mocks
        playerDao = mockk(relaxed = true)
        inventoryDao = mockk(relaxed = true)
        memoryDao = mockk(relaxed = true)
        worldDao = mockk(relaxed = true)
        npcDao = mockk(relaxed = true)
        historyDao = mockk(relaxed = true)

        // DB delegates
        every { db.playerDao() } returns playerDao
        every { db.inventoryDao() } returns inventoryDao
        every { db.memoryDao() } returns memoryDao
        every { db.worldDao() } returns worldDao
        every { db.npcDao() } returns npcDao
        every { db.historyDao() } returns historyDao

        // SharedPreferences
        every { context.getSharedPreferences("endworld_cache", Context.MODE_PRIVATE) } returns prefs
        every { prefs.getString("stable_user_id", null) } returns "user_existing_id"
        every { prefs.edit() } returns prefsEdit
        every { prefsEdit.putString(any(), any()) } returns prefsEdit
        every { prefsEdit.apply() } just Runs
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // Helper: creates repository AFTER all DAO flow mocks are set up
    private fun createRepo() = GameRepository(db, api, memosApi, "test-api-key", "test-memos-key", context)

    // region Exposed Flows

    @Test
    fun `playerState delegates to playerDao`() = runTest {
        val player = PlayerState(
            userId = "u1", name = "测试", genderPhysique = "男",
            personality = "冷静", baseDescription = "幸存者", doomType = "丧尸",
            mentalStateTags = listOf("健康"), innerThought = "", equippedItems = emptyList()
        )
        every { playerDao.getPlayer() } returns flowOf(player)

        val repo = createRepo()
        val result = repo.playerState.first()
        assertNotNull(result)
        assertEquals("测试", result?.name)
    }

    @Test
    fun `exposed flows delegate correctly to DAOs`() = runTest {
        every { playerDao.getPlayer() } returns flowOf(null)
        every { inventoryDao.getAllItems() } returns flowOf(emptyList())
        every { memoryDao.getRecentMemories(10) } returns flowOf(emptyList())
        every { worldDao.getWorldInfo() } returns flowOf(null)
        every { npcDao.getAllNpcs() } returns flowOf(emptyList())
        every { historyDao.getAllHistory() } returns flowOf(emptyList())

        val repo = createRepo()
        assertTrue(repo.inventory.first().isEmpty())
        assertTrue(repo.recentMemories.first().isEmpty())
        assertTrue(repo.npcs.first().isEmpty())
    }

    // endregion

    // region initializeNewGame

    @Test
    fun `initializeNewGame clears history and inserts initial data`() = runTest {
        val name = "幸存者A"
        val doom = "核冬天"

        coEvery { playerDao.updatePlayerState(any()) } just Runs
        coEvery { worldDao.updateWorldInfo(any()) } just Runs
        coEvery { memoryDao.insertMemory(any()) } just Runs
        coEvery { historyDao.clearHistory() } just Runs

        val repo = createRepo()
        repo.initializeNewGame(name, doom)

        coVerify { historyDao.clearHistory() }
        coVerify { memoryDao.insertMemory(any()) }
        coVerify { playerDao.updatePlayerState(match { it.name == name && it.doomType == doom }) }
        coVerify { worldDao.updateWorldInfo(match { it.currentDate == "第1天" }) }
    }

    // endregion

    // region saveHistory / clearHistory

    @Test
    fun `saveHistory delegates to historyDao`() = runTest {
        coEvery { historyDao.insertHistory(any()) } just Runs

        val repo = createRepo()
        repo.saveHistory(action = "搜索", reasoning = "分析了环境", story = "你找到了物资")

        coVerify { historyDao.insertHistory(match {
            it.action == "搜索" && it.story == "你找到了物资"
        })}
    }

    @Test
    fun `clearHistory delegates to historyDao`() = runTest {
        coEvery { historyDao.clearHistory() } just Runs

        val repo = createRepo()
        repo.clearHistory()

        coVerify { historyDao.clearHistory() }
    }

    // endregion
}
