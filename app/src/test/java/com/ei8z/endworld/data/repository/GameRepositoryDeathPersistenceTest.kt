package com.ei8z.endworld.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.data.local.entity.PlayerState
import com.ei8z.endworld.data.local.entity.WorldInfo
import com.ei8z.endworld.data.model.ActionParsed
import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.MemosApiService
import com.ei8z.endworld.data.remote.model.ChatMessage
import com.ei8z.endworld.data.remote.model.ChatResponse
import com.ei8z.endworld.data.remote.model.Choice
import com.ei8z.endworld.data.remote.model.Usage
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P1-6 死亡落库：死亡时刻的世界时间/结算账本/HP 必须真的写进 Room（真库，AI 只当噪声源）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameRepositoryDeathPersistenceTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // 账本 0 表示"从未结算"，死亡落库后应被 mark(当前日) 覆盖
        prefs().edit().putInt(SettlementLedger.KEY, 0).commit()
    }

    @After
    fun tearDown() = db.close()

    private fun prefs() = context.getSharedPreferences("endworld_cache", Context.MODE_PRIVATE)

    private fun newRepo(api: DeepSeekApiService = mockk(relaxed = true)) = GameRepository(
        db, api, mockk<MemosApiService>(relaxed = true), "key", "key", context
    )

    private fun testPlayer() = PlayerState(
        userId = "u1", name = "测试", genderPhysique = "男",
        personality = "冷静", adultProfile = "", baseDescription = "幸存者",
        doomType = "丧尸", mentalStateTags = listOf("健康"),
        innerThought = "", equippedItems = emptyList()
    )

    private fun testWorld() = WorldInfo(
        currentDate = "第1天", currentTime = "06:00",
        locationScene = "避难所", macroSituation = "",
        knownPointsJson = "[]"
    )

    @Test
    fun `mainline death persists world clock and settlement ledger`() = runTest {
        db.playerDao().updatePlayerState(testPlayer().copy(hp = 5, hpMax = 100))
        db.worldDao().updateWorldInfo(testWorld())

        val api = mockk<DeepSeekApiService>(relaxed = true)
        coEvery { api.getChatCompletions(any(), any()) } returns ChatResponse(
            "t", "m",
            listOf(Choice(ChatMessage("assistant", LETHAL_ACTION_RESULT), "stop", 0)),
            Usage(1, 1, 2)
        )
        val repo = newRepo(api)

        val events = repo.sendActionStream(
            "战斗",
            ActionParsed("战斗", "", "敌人", "", "", "高", 30)
        ).toList()

        assertTrue(events.any { it is StreamEvent.PlayerDied })
        assertEquals(0, db.playerDao().getPlayer().first()!!.hp)
        // 06:00 + 30 分钟：死亡时刻的世界时间不能丢
        val world = db.worldDao().getWorldInfo().first()!!
        assertEquals(390, world.totalGameMinutes)
        assertEquals("第1天", world.currentDate)
        assertEquals("06:30", world.currentTime)
        assertEquals(1, prefs().getInt(SettlementLedger.KEY, -1))
    }

    @Test
    fun `exitAcceleration persists the lethal player hp`() = runTest {
        db.playerDao().updatePlayerState(
            testPlayer().copy(hp = 1, hpMax = 100, mentalStateTags = listOf("深度撕裂伤"))
        )
        db.worldDao().updateWorldInfo(testWorld())
        val repo = newRepo()
        val player = db.playerDao().getPlayer().first()!!

        // 直接驱动状态机：加速切线 45 分钟 × 2 = 90 分钟，2 HP/小时打空最后 1 HP
        repo.stateMachine.health.applyInjury("深度撕裂伤")
        repo.stateMachine.vital.hp = 1
        repo.enterAcceleration(player)
        repeat(2) { repo.advanceAcceleration(realMillis = 3_000) }
        repo.exitAcceleration(player, db.worldDao().getWorldInfo().first()!!)

        assertEquals(0, db.playerDao().getPlayer().first()!!.hp)
    }

    private companion object {
        val LETHAL_ACTION_RESULT = """
            {"actionResult":{"result":"失败","dcValue":10,"rollValue":10,"totalValue":10,
            "modifiersBreakdown":"","resourceChanges":[{"itemId":"hp","itemName":"生命值",
            "category":"生命值","changeAmount":-10,"newValue":0,"maxValue":100,
            "narrativeHint":"致命打击"}],"injuriesApplied":[],"injuriesRecovered":[],
            "narrativeSummary":"你倒下了","eventTriggered":null}}
        """.trimIndent()
    }
}
