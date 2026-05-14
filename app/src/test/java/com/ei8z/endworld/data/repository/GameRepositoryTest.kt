package com.ei8z.endworld.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.data.local.dao.*
import com.ei8z.endworld.data.local.entity.*
import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.MemosApiService
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
        db = mockk(relaxed = true)
        api = mockk(relaxed = true)
        memosApi = mockk(relaxed = true)
        context = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        prefsEdit = mockk(relaxed = true)

        playerDao = mockk(relaxed = true)
        inventoryDao = mockk(relaxed = true)
        memoryDao = mockk(relaxed = true)
        worldDao = mockk(relaxed = true)
        npcDao = mockk(relaxed = true)
        historyDao = mockk(relaxed = true)

        every { db.playerDao() } returns playerDao
        every { db.inventoryDao() } returns inventoryDao
        every { db.memoryDao() } returns memoryDao
        every { db.worldDao() } returns worldDao
        every { db.npcDao() } returns npcDao
        every { db.historyDao() } returns historyDao

        every { context.getSharedPreferences("endworld_cache", Context.MODE_PRIVATE) } returns prefs
        every { prefs.getString("stable_user_id", null) } returns "user_existing_id"
        every { prefs.edit() } returns prefsEdit
        every { prefsEdit.putString(any(), any()) } returns prefsEdit
        every { prefsEdit.apply() } just Runs
    }

    @After
    fun tearDown() = clearAllMocks()

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
    fun `storyHistory is non-nullable action String`() = runTest {
        val entry = StoryHistory(action = "搜索", story = "你找到了物资")
        every { historyDao.getAllHistory() } returns flowOf(listOf(entry))
        val repo = createRepo()
        val list = repo.storyHistory.first()
        assertEquals(1, list.size)
        assertEquals("搜索", list[0].action)
        assertEquals("你找到了物资", list[0].story)
    }

    // endregion

    // region initializeNewGame

    @Test
    fun `initializeNewGame clears history and inserts initial data`() = runTest {
        coEvery { playerDao.updatePlayerState(any()) } just Runs
        coEvery { worldDao.updateWorldInfo(any()) } just Runs
        coEvery { memoryDao.insertMemory(any()) } just Runs
        coEvery { historyDao.clearHistory() } just Runs

        val repo = createRepo()
        repo.initializeNewGame("幸存者A", "成年男性", "勇敢", "核冬天")

        coVerify { historyDao.clearHistory() }
        coVerify { memoryDao.insertMemory(any()) }
        coVerify { playerDao.updatePlayerState(match {
            it.name == "幸存者A" && it.genderPhysique == "成年男性" &&
                    it.personality == "勇敢" && it.doomType == "核冬天"
        }) }
        coVerify { worldDao.updateWorldInfo(match { it.currentDate == "第1天" }) }
    }

    // endregion

    // region saveHistory / clearHistory

    @Test
    fun `saveHistory — action is now non-nullable, single record per turn`() = runTest {
        coEvery { historyDao.insertHistory(any()) } just Runs
        val repo = createRepo()

        // 一个回合一条记录：action + reasoning + story 合并
        repo.saveHistory(action = "搜索", reasoning = "环境分析", story = "你找到了物资")

        coVerify { historyDao.insertHistory(match {
            it.action == "搜索" && it.reasoning == "环境分析" && it.story == "你找到了物资"
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
