package com.ei8z.endworld.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.MemosApiService
import com.ei8z.endworld.domain.npc.engine.NpcBehaviorEngine
import com.ei8z.endworld.domain.npc.model.CombatRole
import com.ei8z.endworld.domain.npc.model.DepartureInfo
import com.ei8z.endworld.domain.npc.model.DepartureStatus
import com.ei8z.endworld.domain.npc.model.NeedState
import com.ei8z.endworld.domain.npc.model.NpcState
import com.ei8z.endworld.domain.npc.model.NpcTier
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 场景6 的结算接线（真 Room + relaxed AI）：外出探索超过预期归来日 → MISSING、落库、
 * 次日结算不再重复改状态。AI 只当噪声源，不验证决策映射（那部分已有纯函数单测）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameRepositorySettlementIntegrationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun newRepo() = GameRepository(
        db, mockk<DeepSeekApiService>(relaxed = true),
        mockk<MemosApiService>(relaxed = true), "key", "key", context
    )

    @Test
    fun `restarted repository resumes the persisted settled day`() = runTest {
        // 上一次进程已结算到第 10 天，重启不能回到第 1 天重放 9 次结算
        context.getSharedPreferences("endworld_cache", Context.MODE_PRIVATE)
            .edit().putInt(SettlementLedger.KEY, 10).commit()

        val repo = newRepo()

        assertEquals(10, repo.lastMidnightSettlementDay)
    }

    @Test
    fun `new game resets the persisted settled day`() = runTest {
        val prefs = context.getSharedPreferences("endworld_cache", Context.MODE_PRIVATE)
        prefs.edit().putInt(SettlementLedger.KEY, 10).commit()
        val repo = newRepo()
        assertEquals(10, repo.lastMidnightSettlementDay)

        repo.initializeNewGameV22(
            playerName = "测试", genderPhysique = "男", personality = "冷静",
            adultProfile = "", doomType = "丧尸", doomLevel = 1,
            hp = 100, hpMax = 100, sp = 100, spMax = 100,
            hunger = 80, thirst = 80, fatigue = 20
        )

        assertEquals(1, repo.lastMidnightSettlementDay)
        assertEquals(1, newRepo().lastMidnightSettlementDay)
    }

    @Test
    fun `npc needs saturate without need death until the satisfy model lands`() = runTest {
        // 开关显式：本批决定需求与伤病不致死（P0-1）
        assertFalse(NpcBehaviorEngine.NEED_DEATH_ENABLED)

        val repo = newRepo()
        repo.ensureV3WorldLoaded()
        repo.npcRuntime["n1"] = npcRecord(
            state = NpcState(
                npcId = "n1", name = "老李", identity = "居民",
                combatRole = CombatRole.NON_COMBATANT,
                currentLocationId = repo.regions.keys.first(), factionId = null,
                personalityTags = emptyList(), statusTags = mutableListOf(), tier = NpcTier.CONTACT
            ),
            needs = NeedState(
                foodUrgency = 30, waterUrgency = 30,
                medicalUrgency = 0, safetyUrgency = 30, socialUrgency = 30
            )
        )

        repeat(5) { repo.executeDailySettlement() }

        val record = repo.npcRuntime.getValue("n1")
        assertNotEquals(DepartureStatus.DEAD, record.departure.status)
        // 需求仍在推进：连续饱和已超过致死阈值（3 天），只是不再落成死亡
        assertEquals(100, record.needs.foodUrgency)
        assertTrue(record.needs.consecutiveSaturatedFoodDays >= 3)
    }

    @Test
    fun `overdue explorer turns missing once and stays missing after the next settlement`() = runTest {
        val repo = newRepo()
        repo.ensureV3WorldLoaded()
        repo.npcRuntime["n1"] = npcRecord(
            state = NpcState(
                npcId = "n1", name = "老张", identity = "探索者",
                combatRole = CombatRole.NON_COMBATANT,
                currentLocationId = repo.regions.keys.first(), factionId = null,
                personalityTags = emptyList(), statusTags = mutableListOf(), tier = NpcTier.CONTACT
            ),
            departure = DepartureInfo(DepartureStatus.EXPLORING, "寻找物资", expectedReturnDay = 1)
        )
        repo.stateMachine.clock.setTotalMinutes(24 * 60) // 第 2 天，超过 expectedReturnDay
        repo.executeDailySettlement()

        assertEquals(DepartureStatus.MISSING, repo.npcRuntime.getValue("n1").departure.status)
        assertEquals("超时未归", repo.npcRuntime.getValue("n1").departure.reason)

        // 重启：读同一个 DB，MISSING 只能来自落库
        val reopened = newRepo()
        reopened.ensureV3WorldLoaded()
        assertEquals(DepartureStatus.MISSING, reopened.npcRuntime.getValue("n1").departure.status)
        assertEquals("超时未归", reopened.npcRuntime.getValue("n1").departure.reason)

        // 次日再结算：已 MISSING，不再重复改状态
        reopened.stateMachine.clock.setTotalMinutes(2 * 24 * 60)
        reopened.executeDailySettlement()

        assertEquals(DepartureStatus.MISSING, reopened.npcRuntime.getValue("n1").departure.status)
        assertEquals("超时未归", reopened.npcRuntime.getValue("n1").departure.reason)
    }
}
