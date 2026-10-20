package com.ei8z.endworld.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.data.local.entity.NpcRelation
import com.ei8z.endworld.data.local.entity.PlayerState
import com.ei8z.endworld.data.local.entity.WorldInfo
import com.ei8z.endworld.data.model.ActionParsed
import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.MemosApiService
import com.ei8z.endworld.data.remote.model.ChatChunkResponse
import com.ei8z.endworld.data.remote.model.ChatMessage
import com.ei8z.endworld.data.remote.model.ChatRequest
import com.ei8z.endworld.data.remote.model.ChatResponse
import com.ei8z.endworld.data.remote.model.Choice
import com.ei8z.endworld.data.remote.model.ChunkChoice
import com.ei8z.endworld.data.remote.model.ChunkDelta
import com.ei8z.endworld.data.remote.model.Usage
import com.ei8z.endworld.domain.npc.model.DepartureStatus
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P1-5：首次提取 NPC 的好感度基准必须来自写库之前的 legacy 快照。
 *
 * 污染发生在真库里——`syncGameData` 的 upsert 先插一行 `affinity=0`，之后再读同名行会得到
 * `-100` 基准，所以这里用真 Room 而不是 mock DAO，否则复现不出来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameRepositoryFirstExtractionAffinityTest {

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

    private fun newRepo(api: DeepSeekApiService = mockk(relaxed = true)) = GameRepository(
        db, api, mockk<MemosApiService>(relaxed = true), "key", "key", context
    )

    private suspend fun seedGame() {
        db.playerDao().updatePlayerState(
            PlayerState(
                userId = "u1", name = "测试", genderPhysique = "男", personality = "冷静",
                adultProfile = "", baseDescription = "幸存者", doomType = "丧尸",
                mentalStateTags = listOf("健康"), innerThought = "", equippedItems = emptyList()
            )
        )
        db.worldDao().updateWorldInfo(
            WorldInfo(
                currentDate = "第1天", currentTime = "06:00",
                locationScene = "避难所", macroSituation = "", knownPointsJson = "[]",
                totalGameMinutes = 6 * 60
            )
        )
    }

    /** 主线两条 AI 流都塞合法响应；叙事流的 npcUpdate / 裁决摘要由用例指定。 */
    private fun stubMainlineApis(
        api: DeepSeekApiService,
        npcUpdateJson: String,
        narrativeSummary: String = "你环顾四周"
    ) {
        val actionResult = """
            {"actionResult":{"result":"部分成功","dcValue":50,"rollValue":60,"totalValue":55,
            "modifiersBreakdown":"","resourceChanges":[],"injuriesApplied":[],"injuriesRecovered":[],
            "narrativeSummary":"$narrativeSummary","eventTriggered":null}}
        """.trimIndent()
        coEvery { api.getChatCompletions(any(), any()) } returns ChatResponse(
            "t", "m",
            listOf(Choice(ChatMessage("assistant", actionResult), "stop", 0)),
            Usage(1, 1, 2)
        )
        val gameResponse = """
            {"header":{"date":"第1天","time":"06:10","location":"避难所"},
            "story":"四周很安静。","suggestions":[],"customActionAvailable":true,
            "statusUpdate":{"player":{"stateTags":[],"innerThought":"","equipment":[]},
            "world":{"doomLevel":1,"macroSituation":""},
            "resources":{"knownPoints":[],"inventory":{"items":[]}},
            "memoryUpdate":{"newTempMemory":null,"newPermanentPlot":null},
            "npcUpdate":$npcUpdateJson}}
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

    private val chatWithA = ActionParsed("社交", "聊天", "A", "避难所", "", "低", 10)
    private val npcUpdateA = """[{"name":"A","relation":"邻居","affinityDelta":10,"lastInteractionSummary":"聊了聊"}]"""
    private val chatWithB = ActionParsed("社交", "聊天", "B", "避难所", "", "低", 10)
    private val npcUpdateB = """[{"name":"B","relation":"邻居","affinityDelta":10,"lastInteractionSummary":"聊了聊"}]"""
    private val chatWithC = ActionParsed("社交", "聊天", "C", "避难所", "", "低", 10)

    @Test
    fun `first extraction in a new game starts from the no-faction base instead of a zeroed legacy row`() =
        runTest {
            val api = mockk<DeepSeekApiService>(relaxed = true)
            stubMainlineApis(api, npcUpdateA)
            val repo = newRepo(api)
            repo.initializeNewGameV22(
                "新角色", "成年男性", "冷静", "", "丧尸", 1,
                100, 100, 100, 100, 50, 50, 0
            )

            repo.sendActionStream("找A聊天", chatWithA).toList()

            val record = repo.npcRuntime.getValue("npc_A")
            // 无 legacy、无阵营：-10 基准 + 10×2 增量
            assertEquals(10, repo.affinityFor("npc_A")?.affinity)
            assertNotEquals(DepartureStatus.MIGRATED, record.departure.status)
        }

    @Test
    fun `a zero row left by a previous process does not poison the frozen snapshot`() = runTest {
        // 模拟进程重启：库里已有上一局 upsert 留下的 affinity=0 行，新 repo 首次加载时冻结快照
        db.npcDao().insertIfNotExist(NpcRelation(name = "A", relation = "邻居", affinity = 0))
        seedGame()
        val api = mockk<DeepSeekApiService>(relaxed = true)
        stubMainlineApis(api, npcUpdateA)
        val repo = newRepo(api)

        repo.sendActionStream("找A聊天", chatWithA).toList()

        // 0 行不算 legacy：回落到无阵营基准 -10，再吃 10×2 增量
        assertEquals(10, repo.affinityFor("npc_A")?.affinity)
        assertNotEquals(DepartureStatus.MIGRATED, repo.npcRuntime.getValue("npc_A").departure.status)
    }

    @Test
    fun `first extraction still uses the legacy affinity snapshot when the legacy row exists`() = runTest {
        db.npcDao().insertIfNotExist(NpcRelation(name = "A", relation = "邻居", affinity = 80))
        seedGame()
        val api = mockk<DeepSeekApiService>(relaxed = true)
        stubMainlineApis(api, npcUpdateA)
        val repo = newRepo(api)

        repo.sendActionStream("找A聊天", chatWithA).toList()

        // legacy 80 → (80-50)*2 = 60 基准，再吃 10×2 增量
        assertEquals(80, repo.affinityFor("npc_A")?.affinity)
    }

    @Test
    fun `an earlier round that only upserts the npc does not poison the first extraction`() = runTest {
        val api = mockk<DeepSeekApiService>(relaxed = true)
        // 第 1 轮：B 出现在 npcUpdate 但增量不足 10，不提取，只在 V2 表留一行 affinity=0
        stubMainlineApis(
            api,
            """[{"name":"B","relation":"邻居","affinityDelta":5,"lastInteractionSummary":"寒暄"}]"""
        )
        val repo = newRepo(api)
        repo.initializeNewGameV22(
            "新角色", "成年男性", "冷静", "", "丧尸", 1,
            100, 100, 100, 100, 50, 50, 0
        )
        repo.sendActionStream("和B寒暄", chatWithB).toList()
        // 前提钉死：上一轮确实只写了 0 行、没有提取
        assertEquals(0, db.npcDao().getAllNpcs().first().single { it.name == "B" }.affinity)
        assertNull(repo.affinityFor("npc_B"))

        // 第 2 轮：增量达标触发提取；基准不能被上一轮插的 0 行污染
        stubMainlineApis(api, npcUpdateB)
        repo.sendActionStream("和B聊天", chatWithB).toList()

        // 无 legacy、无阵营：-10 基准 + 10×2 增量
        assertEquals(10, repo.affinityFor("npc_B")?.affinity)
        assertNotEquals(DepartureStatus.MIGRATED, repo.npcRuntime.getValue("npc_B").departure.status)
    }

    @Test
    fun `an earlier upsert does not poison the direct extraction path either`() = runTest {
        val api = mockk<DeepSeekApiService>(relaxed = true)
        stubMainlineApis(
            api,
            """[{"name":"C","relation":"邻居","affinityDelta":5,"lastInteractionSummary":"寒暄"}]"""
        )
        val repo = newRepo(api)
        repo.initializeNewGameV22(
            "新角色", "成年男性", "冷静", "", "丧尸", 1,
            100, 100, 100, 100, 50, 50, 0
        )
        repo.sendActionStream("和C寒暄", chatWithC).toList()
        assertEquals(0, db.npcDao().getAllNpcs().first().single { it.name == "C" }.affinity)

        // 第 2 轮：情报触发 tryV3NpcExtraction（在 syncGameData 写库之前执行）
        stubMainlineApis(api, "[]", narrativeSummary = "C向你透露了藏身处的位置")
        repo.sendActionStream("把水给C", chatWithC).toList()

        // 无 legacy、无阵营：-10 基准 + 首次互动 5
        assertEquals(-5, repo.affinityFor("npc_C")?.affinity)
        assertNotEquals(DepartureStatus.MIGRATED, repo.npcRuntime.getValue("npc_C").departure.status)
    }

    @Test
    fun `new game clears legacy npc relations`() = runTest {
        db.npcDao().insertIfNotExist(NpcRelation(name = "老张", relation = "邻居", affinity = 80))
        val repo = newRepo()

        repo.initializeNewGameV22(
            "新角色", "成年男性", "冷静", "", "丧尸", 1,
            100, 100, 100, 100, 50, 50, 0
        )

        assertTrue(db.npcDao().getAllNpcs().first().isEmpty())
    }
}
