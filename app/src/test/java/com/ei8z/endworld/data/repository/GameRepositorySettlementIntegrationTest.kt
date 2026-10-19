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
import com.ei8z.endworld.data.remote.model.ChatChunkResponse
import com.ei8z.endworld.data.remote.model.ChatMessage
import com.ei8z.endworld.data.remote.model.ChatRequest
import com.ei8z.endworld.data.remote.model.ChatResponse
import com.ei8z.endworld.data.remote.model.ChunkChoice
import com.ei8z.endworld.data.remote.model.ChunkDelta
import com.ei8z.endworld.data.remote.model.Choice
import com.ei8z.endworld.data.remote.model.Usage
import com.ei8z.endworld.domain.npc.engine.NpcBehaviorEngine
import com.ei8z.endworld.domain.npc.model.CombatRole
import com.ei8z.endworld.domain.npc.model.DepartureInfo
import com.ei8z.endworld.domain.npc.model.DepartureStatus
import com.ei8z.endworld.domain.npc.model.NeedState
import com.ei8z.endworld.domain.npc.model.NpcState
import com.ei8z.endworld.domain.npc.model.NpcTier
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
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
        // 结算账本落 Prefs，同一 Robolectric 进程内跨用例共享：每条用例都从第 1 天开始
        prefs().edit().putInt(SettlementLedger.KEY, 1).commit()
    }

    @After
    fun tearDown() = db.close()

    private fun prefs() = context.getSharedPreferences("endworld_cache", Context.MODE_PRIVATE)

    private fun newRepo(
        api: DeepSeekApiService = mockk(relaxed = true)
    ) = GameRepository(
        db, api,
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

    // region 时间结算：主线/支线同轮 + 加速退出账本

    private val lateNightMinutes = 23 * 60 + 55

    /** 主线裁决是唯一传玩家 userId 的 reasoner 调用；场景1 轻量评估传 "v3_eval"。 */
    private val mainlineUserId = "u1"

    private suspend fun seedPlayerAndWorld(totalGameMinutes: Int) {
        db.playerDao().updatePlayerState(
            PlayerState(
                userId = mainlineUserId, name = "测试", genderPhysique = "男", personality = "冷静",
                adultProfile = "", baseDescription = "幸存者", doomType = "丧尸",
                mentalStateTags = listOf("健康"), innerThought = "", equippedItems = emptyList()
            )
        )
        db.worldDao().updateWorldInfo(
            WorldInfo(
                currentDate = "第1天",
                currentTime = String.format(
                    java.util.Locale.ROOT, "%02d:%02d",
                    (totalGameMinutes % 1440) / 60, totalGameMinutes % 60
                ),
                locationScene = "避难所", macroSituation = "", knownPointsJson = "[]",
                totalGameMinutes = totalGameMinutes
            )
        )
    }

    /** 主线要走到裁决 Agent 才能验证"prompt 构建前已结算"，因此给两条 AI 流都塞合法响应。 */
    private fun stubMainlineApis(api: DeepSeekApiService, onMainlinePrompt: () -> Unit) {
        val actionResult = """
            {"actionResult":{"result":"部分成功","dcValue":50,"rollValue":60,"totalValue":55,
            "modifiersBreakdown":"","resourceChanges":[],"injuriesApplied":[],
            "injuriesRecovered":[],"narrativeSummary":"你环顾四周","eventTriggered":null}}
        """.trimIndent()
        coEvery { api.getChatCompletions(any(), any()) } coAnswers {
            // 只认主线裁决这一次调用：结算里的场景1评估不是 prompt 构建点
            if (secondArg<ChatRequest>().userId == mainlineUserId) onMainlinePrompt()
            ChatResponse(
                "t", "m",
                listOf(Choice(ChatMessage("assistant", actionResult), "stop", 0)),
                Usage(1, 1, 2)
            )
        }
        val gameResponse = """
            {"header":{"date":"第1天","time":"00:05","location":"避难所"},
            "story":"四周很安静。","suggestions":[],"customActionAvailable":true,
            "statusUpdate":{"player":{"stateTags":[],"innerThought":"","equipment":[]},
            "world":{"doomLevel":1,"macroSituation":""},
            "resources":{"knownPoints":[],"inventory":{"items":[]}},
            "memoryUpdate":{"newTempMemory":null,"newPermanentPlot":null},
            "npcUpdate":[]}}
        """.trimIndent()
        val chunk = ChatChunkResponse(
            id = "1",
            choices = listOf(
                ChunkChoice(
                    delta = ChunkDelta(role = "assistant", content = gameResponse, reasoningContent = null),
                    finishReason = "stop",
                    index = 0
                )
            )
        )
        val sse = "data: ${Gson().toJson(chunk)}\n\ndata: [DONE]\n\n"
        coEvery { api.getChatCompletionsStream(any(), any()) } answers {
            sse.toResponseBody("text/event-stream".toMediaType())
        }
    }

    @Test
    fun `mainline crossing midnight settles before the reasoner prompt is built`() = runTest {
        seedPlayerAndWorld(lateNightMinutes)
        val api = mockk<DeepSeekApiService>(relaxed = true)
        val repo = newRepo(api)
        // 给结算真实工作量：修复前它跑在异步 launch 上，prompt 构建时几乎必然还没跑完
        repo.ensureV3WorldLoaded()
        repeat(120) { index ->
            repo.npcRuntime["n$index"] = npcRecord(
                NpcState(
                    npcId = "n$index", name = "居民$index", identity = "居民",
                    combatRole = CombatRole.NON_COMBATANT,
                    currentLocationId = repo.regions.keys.first(), factionId = null,
                    personalityTags = emptyList(), statusTags = mutableListOf()
                )
            )
        }
        val ledgerAtReasonerPrompt = AtomicInteger(-1)
        stubMainlineApis(api) { ledgerAtReasonerPrompt.set(repo.lastMidnightSettlementDay) }

        repo.sendActionStream(
            "观察四周",
            ActionParsed("观察", "查看", "", "避难所", "", "低", 10)
        ).toList()

        assertEquals(2, repo.currentGameDay)
        // 结算必须在构建 prompt 之前完成，而不是事后异步补算
        assertEquals("prompt 构建时结算游标应已到第 2 天", 2, ledgerAtReasonerPrompt.get())
        assertEquals(2, repo.lastMidnightSettlementDay)
    }

    @Test
    fun `branch crossing midnight also settles the day`() = runTest {
        seedPlayerAndWorld(lateNightMinutes)
        val repo = newRepo()

        // 自由文本支线（等待 10~30 分钟）：23:55 出发必跨午夜
        repo.sendActionStream("等待").toList()

        assertEquals(2, repo.currentGameDay)
        assertEquals(2, repo.lastMidnightSettlementDay)
    }

    @Test
    fun `exit acceleration marks the accelerated day as settled`() = runTest {
        seedPlayerAndWorld(6 * 60)
        val repo = newRepo()
        val player = repo.playerState.first() ?: error("玩家数据缺失")
        val world = repo.worldInfo.first() ?: error("世界数据缺失")

        repo.enterAcceleration(player)
        // 现实 96 秒 × 15 游戏分钟/秒 = 1 游戏日，走批量追赶分支
        repo.advanceAcceleration(realMillis = 96_000)
        repo.exitAcceleration(player, world)

        assertEquals(2, repo.currentGameDay)
        // 追赶已覆盖这一天，下一次主线不能再补算同一段
        assertEquals(repo.currentGameDay, repo.lastMidnightSettlementDay)
        assertEquals(2, prefs().getInt(SettlementLedger.KEY, 1))
    }

    @Test
    fun `exit acceleration without a full day leaves the crossed day to the next mainline`() = runTest {
        seedPlayerAndWorld(lateNightMinutes)
        val api = mockk<DeepSeekApiService>(relaxed = true)
        val repo = newRepo(api)
        repo.ensureV3WorldLoaded()
        repo.stateMachine.clock.setTotalMinutes(lateNightMinutes)
        val player = repo.playerState.first() ?: error("玩家数据缺失")
        val world = repo.worldInfo.first() ?: error("世界数据缺失")

        repo.enterAcceleration(player)
        // 现实 12 秒 × 15 游戏分钟/秒 = 3 游戏小时：23:55 → 第 2 天 02:55，fullDays=0
        repo.advanceAcceleration(realMillis = 12_000)
        repo.exitAcceleration(player, world)

        assertEquals(2, repo.currentGameDay)
        // 没有批量追赶任何一天：游标不能跳过第 2 天，否则这一天永久不结算
        assertEquals(1, repo.lastMidnightSettlementDay)
        assertEquals(1, prefs().getInt(SettlementLedger.KEY, 1))

        // 交给下一次主线正常结算：游标推进到第 2 天并落盘
        stubMainlineApis(api) {}
        repo.sendActionStream(
            "观察四周",
            ActionParsed("观察", "查看", "", "避难所", "", "低", 10)
        ).toList()

        assertEquals(2, repo.lastMidnightSettlementDay)
        assertEquals(2, prefs().getInt(SettlementLedger.KEY, 1))
    }

    @Test
    fun `next mainline does not re-settle the accelerated day`() = runTest {
        seedPlayerAndWorld(6 * 60)
        val api = mockk<DeepSeekApiService>(relaxed = true)
        val repo = newRepo(api)
        repo.ensureV3WorldLoaded()
        repo.stateMachine.clock.setTotalMinutes(6 * 60)
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
        val player = repo.playerState.first() ?: error("玩家数据缺失")
        val world = repo.worldInfo.first() ?: error("世界数据缺失")

        repo.enterAcceleration(player)
        // 现实 96 秒 × 15 游戏分钟/秒 = 1 游戏日：走 fullDays=1 的批量追赶
        repo.advanceAcceleration(realMillis = 96_000)
        repo.exitAcceleration(player, world)

        assertEquals(2, repo.lastMidnightSettlementDay)
        val needsAfterExit = repo.npcRuntime.getValue("n1").needs

        // 退出后发一次主线动作：同一天不应再跑一次每日结算（NPC 需求会再推进一天）
        stubMainlineApis(api) {}
        repo.sendActionStream(
            "观察四周",
            ActionParsed("观察", "查看", "", "避难所", "", "低", 10)
        ).toList()

        assertEquals(2, repo.currentGameDay)
        assertEquals(2, repo.lastMidnightSettlementDay)
        assertEquals(needsAfterExit, repo.npcRuntime.getValue("n1").needs)
    }

    // endregion
}
