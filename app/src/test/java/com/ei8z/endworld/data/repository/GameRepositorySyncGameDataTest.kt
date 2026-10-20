package com.ei8z.endworld.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.data.local.entity.PlayerState
import com.ei8z.endworld.data.local.entity.WorldInfo
import com.ei8z.endworld.data.model.ActionParsed
import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.MemosApiService
import com.ei8z.endworld.data.remote.model.ChatChunkResponse
import com.ei8z.endworld.data.remote.model.ChatMessage
import com.ei8z.endworld.data.remote.model.ChatResponse
import com.ei8z.endworld.data.remote.model.Choice
import com.ei8z.endworld.data.remote.model.ChunkChoice
import com.ei8z.endworld.data.remote.model.ChunkDelta
import com.ei8z.endworld.data.remote.model.Usage
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.flow.first
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
 * P1-9：`syncGameData` 的 V2 五表写入要整体回滚，AI 响应缺 `macroSituation`/`innerThought`
 * 时不能把 null 带进 Room 实体（Gson 绕过 Kotlin 构造器，缺字段的非空类型实际是 null）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameRepositorySyncGameDataTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
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
                mentalStateTags = listOf("健康"), innerThought = "旧念头", equippedItems = emptyList()
            )
        )
        db.worldDao().updateWorldInfo(
            WorldInfo(
                currentDate = "第1天", currentTime = "06:00",
                locationScene = "避难所", macroSituation = "旧世界", knownPointsJson = "[]",
                totalGameMinutes = 6 * 60
            )
        )
    }

    /** 叙事流返回一段合法的 GameResponse；statusUpdate 由用例决定（可缺字段）。 */
    private fun stubMainline(api: DeepSeekApiService, statusUpdateJson: String) {
        val actionResult = """
            {"actionResult":{"result":"部分成功","dcValue":50,"rollValue":60,"totalValue":55,
            "modifiersBreakdown":"","resourceChanges":[],"injuriesApplied":[],"injuriesRecovered":[],
            "narrativeSummary":"你环顾四周","eventTriggered":null}}
        """.trimIndent()
        coEvery { api.getChatCompletions(any(), any()) } returns ChatResponse(
            "t", "m",
            listOf(Choice(ChatMessage("assistant", actionResult), "stop", 0)),
            Usage(1, 1, 2)
        )
        val gameResponse = """
            {"header":{"date":"第1天","time":"06:10","location":"避难所"},
            "story":"四周很安静。","suggestions":[],"customActionAvailable":true,
            "statusUpdate":$statusUpdateJson}
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

    private val observe = ActionParsed("观察", "查看", "", "避难所", "", "低", 10)

    @Test
    fun `response missing macroSituation and innerThought keeps the stored values instead of crashing`() =
        runTest {
            seedGame()
            val api = mockk<DeepSeekApiService>(relaxed = true)
            stubMainline(
                api,
                """
                {"player":{"stateTags":[],"equipment":[]},
                "world":{"doomLevel":1},
                "resources":{"knownPoints":[],"inventory":{"items":[]}},
                "memoryUpdate":{"newTempMemory":null,"newPermanentPlot":null},
                "npcUpdate":[]}
                """.trimIndent()
            )
            val repo = newRepo(api)

            val events = repo.sendActionStream("观察四周", observe).toList()

            assertTrue("缺字段不应让同步抛错: $events", events.none { it is StreamEvent.Error })
            assertEquals("旧世界", db.worldDao().getWorldInfo().first()?.macroSituation)
            assertEquals("旧念头", db.playerDao().getPlayer().first()?.innerThought)
        }

    @Test
    fun `response with the fields still writes the values the AI gave`() = runTest {
        seedGame()
        val api = mockk<DeepSeekApiService>(relaxed = true)
        stubMainline(
            api,
            """
            {"player":{"stateTags":[],"innerThought":"我在想什么","equipment":[]},
            "world":{"doomLevel":1,"macroSituation":"新世界"},
            "resources":{"knownPoints":[],"inventory":{"items":[]}},
            "memoryUpdate":{"newTempMemory":null,"newPermanentPlot":null},
            "npcUpdate":[]}
            """.trimIndent()
        )
        val repo = newRepo(api)

        repo.sendActionStream("观察四周", observe).toList()

        assertEquals("新世界", db.worldDao().getWorldInfo().first()?.macroSituation)
        assertEquals("我在想什么", db.playerDao().getPlayer().first()?.innerThought)
    }

    @Test
    fun `room rolls back every write in a transaction that throws`() = runTest {
        seedGame()
        val player = db.playerDao().getPlayer().first() ?: error("玩家数据缺失")
        val world = db.worldDao().getWorldInfo().first() ?: error("世界数据缺失")

        val thrown = runCatching {
            db.withTransaction {
                db.playerDao().updatePlayerState(player.copy(hp = 1, innerThought = "坏念头"))
                db.worldDao().updateWorldInfo(world.copy(macroSituation = "坏世界"))
                throw IOException("boom")
            }
        }.exceptionOrNull()

        assertTrue("事务块异常应向上传递: $thrown", thrown is IOException)
        assertEquals(100, db.playerDao().getPlayer().first()?.hp)
        assertEquals("旧念头", db.playerDao().getPlayer().first()?.innerThought)
        assertEquals("旧世界", db.worldDao().getWorldInfo().first()?.macroSituation)
    }
}
