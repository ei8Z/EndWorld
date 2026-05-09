package com.ei8z.endworld.data.repository

import android.content.Context
import android.util.Log
import com.ei8z.endworld.BuildConfig
import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.data.local.entity.InventoryItem
import com.ei8z.endworld.data.local.entity.Memory
import com.ei8z.endworld.data.local.entity.NpcRelation
import com.ei8z.endworld.data.local.entity.PlayerState
import com.ei8z.endworld.data.local.entity.StoryHistory
import com.ei8z.endworld.data.local.entity.WorldInfo
import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.model.ChatChunkResponse
import com.ei8z.endworld.data.remote.model.ChatMessage
import com.ei8z.endworld.data.remote.model.ChatRequest
import com.ei8z.endworld.data.remote.model.GameResponse
import com.ei8z.endworld.data.remote.model.InventoryUpdate
import com.ei8z.endworld.data.remote.model.MemoryUpdate
import com.ei8z.endworld.data.remote.model.PlayerUpdate
import com.ei8z.endworld.data.remote.model.ResourceUpdate
import com.ei8z.endworld.data.remote.model.ResponseHeader
import com.ei8z.endworld.data.remote.model.StatusUpdate
import com.ei8z.endworld.data.remote.model.WorldUpdate
import com.ei8z.endworld.util.JsonExtractor
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.ResponseBody
import java.util.UUID

class GameRepository(
    private val database: AppDatabase,
    private val apiService: DeepSeekApiService,
    private val apiKey: String,
    private val context: Context
) {
    companion object {
        private const val TAG = "GameRepo"
    }

    private val gson = Gson()
    private val authHeader = "Bearer $apiKey"
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val playerDao = database.playerDao()
    private val inventoryDao = database.inventoryDao()
    private val memoryDao = database.memoryDao()
    private val worldDao = database.worldDao()
    private val npcDao = database.npcDao()
    private val historyDao = database.historyDao()

    private fun getOrCreateStableUserId(): String {
        val prefs = context.getSharedPreferences("endworld_cache", Context.MODE_PRIVATE)
        var uid = prefs.getString("stable_user_id", null)
        if (uid == null) {
            uid = "user_${UUID.randomUUID()}"
            prefs.edit().putString("stable_user_id", uid).apply()
        }
        return uid
    }

    val playerState: Flow<PlayerState?> = playerDao.getPlayer()
    val inventory: Flow<List<InventoryItem>> = inventoryDao.getAllItems()
    val recentMemories: Flow<List<Memory>> = memoryDao.getRecentMemories(10)
    val worldInfo: Flow<WorldInfo?> = worldDao.getWorldInfo()
    val npcs: Flow<List<NpcRelation>> = npcDao.getAllNpcs()
    val storyHistory: Flow<List<StoryHistory>> = historyDao.getAllHistory()

    /**
     * 流式发送行动并处理回复（改为 suspend 直接调用）
     */
    fun sendActionStream(action: String): Flow<StreamEvent> = callbackFlow {
        Log.d(TAG, "sendActionStream: action=$action")

        val currentPlayer = playerState.first() ?: run {
            trySend(StreamEvent.Error("未找到玩家数据"))
            close()
            return@callbackFlow
        }
        val currentItems = inventory.first()
        val currentMemories = recentMemories.first()
        val currentWeather = worldInfo.first() ?: run {
            trySend(StreamEvent.Error("未找到世界数据"))
            close()
            return@callbackFlow
        }

        val request = buildChatRequest(action, currentPlayer, currentItems, currentMemories, currentWeather)
        val fullContent = StringBuilder()

        val job = repositoryScope.launch {
            try {
                // 直接使用 suspend 方式调用 API
                // 这会在收到 HTTP 头部后立即返回 ResponseBody
                val responseBody = apiService.getChatCompletionsStream(authHeader, request)
                
                // 实时处理流
                processStreamChunks(responseBody, fullContent)

                val finalJson = fullContent.toString()
                Log.d(TAG, "流读取完成, content长度=${finalJson.length}")
                saveJsonToDebugFile(finalJson)
                parseAndEmitResponse(finalJson, currentPlayer, currentWeather)
            } catch (e: Exception) {
                Log.e(TAG, "流请求异常: ${e.message}", e)
                trySend(StreamEvent.Error("连接异常: ${e.message}", retryable = true))
            } finally {
                close()
            }
        }

        awaitClose { job.cancel() }
    }

    private fun buildChatRequest(
        action: String,
        player: PlayerState,
        items: List<InventoryItem>,
        memories: List<Memory>,
        world: WorldInfo
    ): ChatRequest {
        return ChatRequest(
            messages = listOf(
                ChatMessage("system", PromptBuilder.buildSystemPrompt()),
                ChatMessage(
                    "user",
                    PromptBuilder.buildUserSnapshot(player, items, memories, world, action)
                )
            ),
            userId = player.userId,
            stream = true
        )
    }

    private suspend fun ProducerScope<StreamEvent>.processStreamChunks(
        responseBody: ResponseBody,
        fullContent: StringBuilder
    ) {
        // 使用 charStream().buffered() 确保逐行读取 SSE 格式
        responseBody.charStream().buffered().use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val row = line ?: ""
                if (row.startsWith("data: ")) {
                    val data = row.substring(6)
                    if (data == "[DONE]") break

                    try {
                        val chunk = gson.fromJson(data, ChatChunkResponse::class.java)
                        val delta = chunk.choices.firstOrNull()?.delta
                        
                        delta?.reasoningContent?.let { trySend(StreamEvent.Reasoning(it)) }
                        delta?.content?.let {
                            fullContent.append(it)
                            trySend(StreamEvent.Content(it))
                        }
                        chunk.usage?.let { u ->
                            Log.d(
                                TAG,
                                "CacheStats: hit=${u.promptCacheHitTokens ?: 0} miss=${u.promptCacheMissTokens ?: 0}"
                            )
                        }
                    } catch (e: Exception) {
                        // 忽略解析失败的 chunk
                        Log.w(
                            TAG,
                            "processStream: 解析单个chunk失败, data=${data.take(100)}, error=${e.message}"
                        )
                    }
                }
            }
        }
    }

    private suspend fun ProducerScope<StreamEvent>.parseAndEmitResponse(
        rawJson: String,
        player: PlayerState,
        world: WorldInfo
    ) {
        try {
            val gameData = gson.fromJson(rawJson, GameResponse::class.java)
            syncGameData(gameData, player, world)
            trySend(StreamEvent.FinalResponse(gameData))
        } catch (e: Exception) {
            val result = buildSalvagedResponse(rawJson, player, world)
            if (result != null) {
                syncGameData(result, player, world)
                trySend(StreamEvent.FinalResponse(result))
            } else {
                trySend(StreamEvent.Error("解析失败且无法修复内容"))
            }
        }
    }

    private fun buildSalvagedResponse(
        raw: String,
        player: PlayerState,
        world: WorldInfo
    ): GameResponse? {
        val salvagedStory = JsonExtractor.tryExtractStory(raw)
        if (salvagedStory != null) {
            val salvagedSuggestions = JsonExtractor.tryExtractSuggestions(raw)
                .ifEmpty { listOf("继续探索", "原地休整", "谨慎行动") }
            return GameResponse(
                header = ResponseHeader(world.currentDate, world.currentTime, world.locationScene),
                story = salvagedStory,
                suggestions = salvagedSuggestions,
                statusUpdate = StatusUpdate(
                    player = PlayerUpdate(
                        player.mentalStateTags,
                        player.innerThought,
                        player.equippedItems
                    ),
                    world = WorldUpdate(player.doomLevel, world.macroSituation),
                    resources = ResourceUpdate(emptyList(), InventoryUpdate()),
                    memoryUpdate = MemoryUpdate(null, null)
                )
            )
        }
        return null
    }

    private suspend fun syncGameData(
        data: GameResponse,
        oldPlayer: PlayerState,
        oldWorld: WorldInfo
    ) {
        val update = data.statusUpdate
        val safeDoomLevel = update.world.doomLevel.coerceIn(1, 5)
        val safeStateTags = update.player.stateTags.filter { it.isNotBlank() }

        playerDao.updatePlayerState(
            oldPlayer.copy(
                mentalStateTags = safeStateTags,
                innerThought = update.player.innerThought,
                equippedItems = update.player.equipment,
                doomLevel = safeDoomLevel
            )
        )

        worldDao.updateWorldInfo(
            oldWorld.copy(
                currentDate = data.header.date,
                currentTime = data.header.time,
                locationScene = data.header.location,
                macroSituation = update.world.macroSituation,
                knownPointsJson = gson.toJson(update.resources.knownPoints)
            )
        )

        val allNewItems = mutableListOf<InventoryItem>()
        fun sanitizeQuantity(q: String) = if (q.startsWith("-")) "0" else q
        update.resources.inventory.food.filter { it.name.isNotBlank() }.forEach {
            allNewItems.add(InventoryItem(category = "食物", name = it.name, quantity = sanitizeQuantity(it.quantity)))
        }
        update.resources.inventory.drinks.filter { it.name.isNotBlank() }.forEach {
            allNewItems.add(InventoryItem(category = "饮水", name = it.name, quantity = sanitizeQuantity(it.quantity)))
        }
        update.resources.inventory.weapons.filter { it.name.isNotBlank() }.forEach {
            allNewItems.add(InventoryItem(category = "武器", name = it.name, quantity = sanitizeQuantity(it.quantity)))
        }
        update.resources.inventory.special.filter { it.name.isNotBlank() }.forEach {
            allNewItems.add(InventoryItem(category = "特殊", name = it.name, quantity = sanitizeQuantity(it.quantity)))
        }
        inventoryDao.syncInventory(allNewItems)

        update.memoryUpdate.newTempMemory?.let {
            memoryDao.insertMemory(Memory(location = data.header.location, event = it, result = "AI判定"))
            memoryDao.deleteExtraTemporary(10)
        }
        update.memoryUpdate.newPermanentPlot?.let {
            memoryDao.insertMemory(Memory(location = data.header.location, event = it, result = "重大剧情", isPermanent = true))
        }

        update.npcUpdate?.forEach { npcUpdate ->
            npcDao.upsertNpc(npcUpdate.name?:"", npcUpdate.relation?:"", npcUpdate.affinityDelta?:0)
        }
    }

    suspend fun saveHistory(action: String?, reasoning: String, story: String) {
        historyDao.insertHistory(StoryHistory(action = action, reasoning = reasoning, story = story))
    }

    suspend fun clearHistory() {
        historyDao.clearHistory()
    }

    suspend fun initializeNewGame(playerName: String, doomType: String) {
        val initialPlayer = PlayerState(
            userId = getOrCreateStableUserId(),
            name = playerName,
            genderPhysique = "成年人/女/自慰成瘾/性爱成瘾",
            personality = "冷静",
            baseDescription = "末日幸存者",
            doomType = doomType,
            mentalStateTags = listOf("健康"),
            innerThought = "游戏开始了。",
            equippedItems = emptyList()
        )
        val initialWorld = WorldInfo(
            currentDate = "第1天",
            currentTime = "清晨",
            locationScene = "避难所",
            macroSituation = "灾难爆发。",
            knownPointsJson = "[]"
        )
        playerDao.updatePlayerState(initialPlayer)
        worldDao.updateWorldInfo(initialWorld)
        memoryDao.insertMemory(Memory(location = "系统", event = "降临世界", result = "初始"))
        historyDao.clearHistory()
    }

    private fun saveJsonToDebugFile(json: String) {
        if (BuildConfig.DEBUG) try {
            val debugDir = java.io.File(context.filesDir, "debug_json")
            if (!debugDir.exists()) debugDir.mkdirs()
            val files = debugDir.listFiles { _, name -> name.startsWith("response_") && name.endsWith(".json") }
                ?.sortedBy { it.lastModified() } ?: emptyList()
            if (files.size >= 10) files.first().delete()
            val timestamp = java.text.SimpleDateFormat("HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            java.io.File(debugDir, "response_$timestamp.json").writeText(json)
        } catch (e: Exception) { }
    }
}
