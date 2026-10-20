package com.ei8z.endworld.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ei8z.endworld.data.local.AppDatabase
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
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P1-4：自由文本 location 先映射到唯一 regionId，再取区域的控制势力。
 *
 * 回退到“首个区域”是临时口径：当前玩家地点（避难所）不是区域名，先保证 NPC 状态完整，
 * 等叙事层统一输出 regionId 后可以去掉这个回退。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameRepositoryRegionMappingTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    /** 主线裁决 prompt 快照：验证区域/NPC 上下文真的进了提示词，而不只是内存模型对。 */
    private val reasonerPrompts = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        prefs().edit().putInt(SettlementLedger.KEY, 1).commit()
        reasonerPrompts.clear()
    }

    @After
    fun tearDown() = db.close()

    private fun prefs() = context.getSharedPreferences("endworld_cache", Context.MODE_PRIVATE)

    private fun newRepo(api: DeepSeekApiService = mockk(relaxed = true)) = GameRepository(
        db, api, mockk<MemosApiService>(relaxed = true), "key", "key", context
    )

    // === 区域映射（纯函数，世界初始化后即可验证） ===

    @Test
    fun `region id resolves to the region`() {
        val repo = newRepo()
        repo.initV3World()

        assertEquals("R01", repo.resolveRegion("R01")?.regionId)
    }

    @Test
    fun `region name resolves to the region`() {
        val repo = newRepo()
        repo.initV3World()

        assertEquals("R01", repo.resolveRegion("红松营地")?.regionId)
    }

    @Test
    fun `player shelter falls back to the first region`() {
        val repo = newRepo()
        repo.initV3World()

        assertEquals("R01", repo.resolveRegion("避难所")?.regionId)
    }

    @Test
    fun `unknown location falls back to the first region`() {
        val repo = newRepo()
        repo.initV3World()

        assertEquals("R01", repo.resolveRegion("某处无名的废墟")?.regionId)
    }

    @Test
    fun `region id matching wins over the first region fallback`() {
        val repo = newRepo()
        repo.initV3World()

        assertEquals("R04", repo.resolveRegion("R04")?.regionId)
    }

    @Test
    fun `region name matching wins over the first region fallback`() {
        val repo = newRepo()
        repo.initV3World()

        assertEquals("R04", repo.resolveRegion("灰烬峡谷")?.regionId)
    }

    // === 真 Room 提取接缝 ===

    /** 主线两条 AI 流都塞合法响应；header.location 与 npcUpdate 由用例决定。 */
    private fun stubMainlineApis(
        api: DeepSeekApiService,
        npcUpdateJson: String,
        headerLocation: String,
        narrativeSummary: String = "你环顾四周"
    ) {
        val actionResult = """
            {"actionResult":{"result":"部分成功","dcValue":50,"rollValue":60,"totalValue":55,
            "modifiersBreakdown":"","resourceChanges":[],"injuriesApplied":[],"injuriesRecovered":[],
            "narrativeSummary":"$narrativeSummary","eventTriggered":null}}
        """.trimIndent()
        coEvery { api.getChatCompletions(any(), any()) } coAnswers {
            reasonerPrompts.add(
                secondArg<ChatRequest>().messages.mapNotNull { it.content }.joinToString("\n")
            )
            ChatResponse(
                "t", "m",
                listOf(Choice(ChatMessage("assistant", actionResult), "stop", 0)),
                Usage(1, 1, 2)
            )
        }
        val gameResponse = """
            {"header":{"date":"第1天","time":"06:10","location":"$headerLocation"},
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

    /** 取响应 header 的区域（灰烬峡谷→R04/F02），而不是 action 地点（R01），证明用到 header.location。 */
    @Test
    fun `npc update extraction maps the response header location to region and faction`() = runTest {
        val api = mockk<DeepSeekApiService>(relaxed = true)
        stubMainlineApis(
            api,
            npcUpdateJson = """[{"name":"赵四","relation":"居民","affinityDelta":10,"lastInteractionSummary":"聊了聊"}]""",
            headerLocation = "灰烬峡谷"
        )
        val repo = newRepo(api)
        repo.initializeNewGameV22(
            "新角色", "成年男性", "冷静", "", "丧尸", 1,
            100, 100, 100, 100, 50, 50, 0
        )

        repo.sendActionStream(
            "找赵四聊天", ActionParsed("社交", "聊天", "赵四", "红松营地", "", "低", 10)
        ).toList()

        val state = repo.npcRuntime.getValue("npc_赵四").state
        assertEquals("R04", state.currentLocationId)
        assertEquals("F02", state.factionId)
    }

    /** action.location=灰烬峡谷、header/时钟=避难所：证明直接提取用的是 action 地点。 */
    @Test
    fun `direct v3 extraction maps the action location to region and faction`() = runTest {
        val api = mockk<DeepSeekApiService>(relaxed = true)
        stubMainlineApis(
            api,
            npcUpdateJson = "[]",
            headerLocation = "避难所",
            narrativeSummary = "老李向你透露了藏身处的位置"
        )
        val repo = newRepo(api)
        repo.initializeNewGameV22(
            "新角色", "成年男性", "冷静", "", "丧尸", 1,
            100, 100, 100, 100, 50, 50, 0
        )

        repo.sendActionStream(
            "向老李打听藏身处", ActionParsed("社交", "打听", "老李", "灰烬峡谷", "", "低", 10)
        ).toList()

        val state = repo.npcRuntime.getValue("npc_老李").state
        assertEquals("R04", state.currentLocationId)
        assertEquals("F02", state.factionId)
    }

    // === 提示词上下文接缝：NPC 的 location 已是 regionId，玩家 location 仍是自由文本 ===

    @Test
    fun `world progress injects the region mapped from a free text player location`() = runTest {
        val api = mockk<DeepSeekApiService>(relaxed = true)
        stubMainlineApis(api, npcUpdateJson = "[]", headerLocation = "避难所")
        val repo = newRepo(api)
        repo.initializeNewGameV22(
            "新角色", "成年男性", "冷静", "", "丧尸", 1,
            100, 100, 100, 100, 50, 50, 0
        )

        repo.sendActionStream(
            "观察四周", ActionParsed("探索", "观察", "", "避难所", "", "低", 5)
        ).toList()

        assertTrue(
            "reasoner prompt 应注入玩家地点映射出的区域状态，实际 prompt：$reasonerPrompts",
            reasonerPrompts.any { "【V3区域状态】红松营地" in it }
        )
    }

    @Test
    fun `extracted npc stays in the v3 context after its location becomes a region id`() = runTest {
        val api = mockk<DeepSeekApiService>(relaxed = true)
        stubMainlineApis(
            api,
            npcUpdateJson = """[{"name":"赵四","relation":"居民","affinityDelta":10,"lastInteractionSummary":"聊了聊"}]""",
            headerLocation = "红松营地"
        )
        val repo = newRepo(api)
        repo.initializeNewGameV22(
            "新角色", "成年男性", "冷静", "", "丧尸", 1,
            100, 100, 100, 100, 50, 50, 0
        )
        repo.sendActionStream(
            "找赵四聊天", ActionParsed("社交", "聊天", "赵四", "红松营地", "", "低", 10)
        ).toList()
        assertEquals("R01", repo.npcRuntime.getValue("npc_赵四").state.currentLocationId)

        // 第二轮 prompt 的 location 是同步后的自由文本“红松营地”，NPC 在 R01
        repo.sendActionStream(
            "继续和赵四聊天", ActionParsed("社交", "聊天", "赵四", "红松营地", "", "低", 10)
        ).toList()

        assertTrue(
            "reasoner prompt 应包含已提取的 V3 NPC，实际 prompt：$reasonerPrompts",
            reasonerPrompts.any { "【V3-NPC:赵四" in it }
        )
    }
}
