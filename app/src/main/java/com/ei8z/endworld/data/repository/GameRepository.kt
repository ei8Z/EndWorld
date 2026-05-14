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
import com.ei8z.endworld.data.remote.MemosApiService
import com.ei8z.endworld.data.remote.MemosAddMessageRequest
import com.ei8z.endworld.data.remote.MemosMessage
import com.ei8z.endworld.data.remote.MemosSearchRequest
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
import java.io.File
import java.util.UUID

class GameRepository(
    private val database: AppDatabase,
    private val apiService: DeepSeekApiService,
    private val memosApiService: MemosApiService,
    private val apiKey: String,
    private val memosApiKey: String,
    private val context: Context
) {
    companion object {
        private const val TAG = "GameRepo"
    }

    private val gson = Gson()
    private val authHeader = "Bearer $apiKey"
    private val memosAuthHeader = "Token $memosApiKey"
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val strategyManager = GmStrategyManager(context)

    private val playerDao = database.playerDao()
    private val inventoryDao = database.inventoryDao()
    private val memoryDao = database.memoryDao()
    private val worldDao = database.worldDao()
    private val npcDao = database.npcDao()
    private val historyDao = database.historyDao()

    /**
     * 获取或创建稳定用户 ID，用于 DeepSeek KV Cache 优化。
     * 相同 user_id 的请求可复用服务端上下文缓存，降低重复 system prompt 的 Token 消耗。
     */
    private fun getOrCreateStableUserId(): String {
        val prefs = context.getSharedPreferences("endworld_cache", Context.MODE_PRIVATE)
        var uid = prefs.getString("stable_user_id", null)
        if (uid == null) {
            uid = "user_${UUID.randomUUID()}"
            prefs.edit().putString("stable_user_id", uid).apply()
        }
        return uid
    }

    private fun getConversationId(): String {
        return "conv_${getOrCreateStableUserId()}"
    }

    val playerState: Flow<PlayerState?> = playerDao.getPlayer()
    val inventory: Flow<List<InventoryItem>> = inventoryDao.getAllItems()
    val recentMemories: Flow<List<Memory>> = memoryDao.getRecentMemories(10)
    val worldInfo: Flow<WorldInfo?> = worldDao.getWorldInfo()
    val npcs: Flow<List<NpcRelation>> = npcDao.getAllNpcs()
    val storyHistory: Flow<List<StoryHistory>> = historyDao.getAllHistory()

    /**
     * 4 步流水线：MemOS 检索 → DeepSeek 流式生成 → 本地 DB 同步 → MemOS 回写记忆
     * 每一步失败都有降级策略，不会中断流程。
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
        val currentHistory = storyHistory.first()
        val currentWorld = worldInfo.first() ?: run {
            trySend(StreamEvent.Error("未找到世界数据"))
            close()
            return@callbackFlow
        }

        val job = repositoryScope.launch {
            try {
                // 1. MemOS 检索长期记忆
                val memosMemories = try {
                    val searchResponse = memosApiService.searchMemory(
                        memosAuthHeader,
                        MemosSearchRequest(currentPlayer.userId, getConversationId(), action)
                    )
                    searchResponse.data?.memories?.map { it.value } ?: emptyList()
                } catch (e: Exception) {
                    Log.e(TAG, "MemOS Search Failed: ${e.message}")
                    emptyList()
                }

                // 2. 构建请求并发送给 DeepSeek
                val request = buildChatRequest(action, currentPlayer, currentItems, currentMemories, currentHistory, currentWorld, memosMemories)
                saveJsonToDebugFile(gson.toJson(request), "request")
                val fullContent = StringBuilder()

                val responseBody = apiService.getChatCompletionsStream(authHeader, request)
                processStreamChunks(responseBody, fullContent)

                val finalJson = fullContent.toString()
                saveJsonToDebugFile(finalJson)

                // 3. 解析并同步本地数据库
                parseAndEmitResponse(finalJson, currentPlayer, currentWorld)

                // 4. 将本轮对话同步回 MemOS 形成长期记忆（NonCancellable 确保不被 awaitClose 中断）
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        memosApiService.addMessage(
                            memosAuthHeader,
                            MemosAddMessageRequest(
                                userId = currentPlayer.userId,
                                conversationId = getConversationId(),
                                messages = listOf(
                                    MemosMessage("user", action),
                                    MemosMessage("assistant", finalJson)
                                )
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "MemOS Add Message Failed: ${e.message}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "流请求异常: ${e.message}", e)
                trySend(StreamEvent.Error("连接异常: ${e.message}", retryable = true))
            } finally {
                close()
            }
        }

        awaitClose { job.cancel() }
    }

    /**
     * 构建 ChatRequest：注入 system prompt + 用户快照，动态调整推理强度。
     * 关键抉择（战斗/逃跑）使用 max 推理，日常行动使用 high。
     */
    private fun buildChatRequest(
        action: String,
        player: PlayerState,
        items: List<InventoryItem>,
        memories: List<Memory>,
        history: List<StoryHistory>,
        world: WorldInfo,
        memosMemories: List<String>
    ): ChatRequest {
        val currentStrategy = strategyManager.load()
        val messages = mutableListOf(
            ChatMessage("system", PromptBuilder.buildSystemPrompt(currentStrategy, memosMemories)),
            ChatMessage(
                "user",
                PromptBuilder.buildUserSnapshot(player, items, memories, history, world, action)
            ),
        )

        return ChatRequest(
            messages = messages,
            userId = player.userId,
            reasoningEffort = effortLevel(action),
            stream = true,
            tools = PromptBuilder.getTools()
        )
    }

    /**是否需要更慎重的思考*/
    private fun effortLevel(action: String): String {
        val check = action.contains("战斗") || action.contains("逃跑") || action.contains("杀")
        return if (check) "max" else "high"
    }

    /**
     * 按行读取 SSE 流，拆出 "data: " 前缀的 JSON chunk，
     * 将 reasoning_content 和 content 增量转发给 UI，同时拼接 fullContent 用于最终解析。
     */
    private suspend fun ProducerScope<StreamEvent>.processStreamChunks(
        responseBody: ResponseBody,
        fullContent: StringBuilder
    ) {
        responseBody.charStream().buffered().use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val row = line ?: ""
                if (row.startsWith("data: ")) {
                    val data = row.substring(6)
                    if (data == "[DONE]") break

                    try {
                        val chunk = gson.fromJson(data, ChatChunkResponse::class.java)

                        // 监控缓存命中情况
                        chunk.usage?.let {
                            if ((it.promptCacheHitTokens ?: 0) > 0) {
                                Log.d(TAG, "KV Cache Hit: ${it.promptCacheHitTokens} tokens")
                            }
                        }

                        val delta = chunk.choices.firstOrNull()?.delta
                        delta?.reasoningContent?.let { trySend(StreamEvent.Reasoning(it)) }
                        delta?.content?.let {
                            fullContent.append(it)
                            trySend(StreamEvent.Content(it))
                        }
                    } catch (e: Exception) {
                        // 忽略解析失败的 chunk
                    }
                }
            }
        }
    }

    /**
     * 先尝试将完整 JSON 反序列化为 GameResponse；失败则用 [buildSalvagedResponse] 尝试从残缺 JSON 中提取 story 文本兜底。
     */
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
                trySend(StreamEvent.Error("解析失败且无法修复内容: ${e.message}"))
            }
        }
    }

    /**
     * JSON 解析失败时的优雅降级：用正则提取 story 文本，维持当前状态不变，给出默认行动建议。
     */
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

    /**
     * 将 AI 返回的 GameResponse 同步到本地 Room DB，包含安全裁剪（doomLevel 夹到 1-5，过滤空白标签）。
     */
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

    suspend fun saveHistory(action: String, reasoning: String, story: String) {
        historyDao.insertHistory(StoryHistory(action = action, reasoning = reasoning, story = story))
    }

    fun loadStrategy(): GmStrategy = strategyManager.load()
    fun saveStrategy(strategy: GmStrategy) = strategyManager.save(strategy)
    fun resetStrategy() = strategyManager.reset()

    suspend fun clearHistory() {
        historyDao.clearHistory()
    }

    suspend fun initializeNewGame(
        playerName: String,
        genderPhysique: String,
        personality: String,
        doomType: String
    ) {
        val initialPlayer = PlayerState(
            userId = getOrCreateStableUserId(),
            name = playerName,
            genderPhysique = genderPhysique,
            personality = personality,
            baseDescription = "一个${personality}的末日幸存者",
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

    private fun saveJsonToDebugFile(json: String, fileName: String = "response") {
        if (BuildConfig.DEBUG) try {
            val debugDir = File(context.filesDir, "debug_json")
            if (!debugDir.exists()) debugDir.mkdirs()
            deleteFile(debugDir, fileName)
            val timestamp = java.text.SimpleDateFormat("HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            File(debugDir, "${fileName}_${timestamp}.json").writeText(json).let {
                Log.i(TAG, "saveJsonToDebugFile: 保存参数 $it")
            }
        } catch (e: Exception) { }
    }

    private fun deleteFile(debugDir: File,startWith: String) {
        val files =
            debugDir.listFiles { _, name -> name.startsWith(startWith) && name.endsWith(".json") }
                ?.sortedBy { it.lastModified() } ?: emptyList()
        if (files.size >= 10) files.first().delete()
    }

}
