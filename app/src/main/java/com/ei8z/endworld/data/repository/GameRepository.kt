package com.ei8z.endworld.data.repository

import android.content.Context
import android.util.Log
import com.ei8z.endworld.BuildConfig
import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.data.local.entity.*
import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.model.*
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
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
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

    /**
     * 流式发送行动并处理回复（带 JSON 容错）
     */
    fun sendActionStream(action: String): Flow<StreamEvent> = callbackFlow {
        Log.d(TAG, "sendActionStream: action=$action")

        val currentPlayer = playerState.first() ?: run {
            Log.e(TAG, "sendActionStream: 玩家数据为空")
            trySend(StreamEvent.Error("未找到玩家数据"))
            close()
            return@callbackFlow
        }
        val currentItems = inventory.first()
        val currentMemories = recentMemories.first()
        val currentWeather = worldInfo.first() ?: run {
            Log.e(TAG, "sendActionStream: 世界数据为空")
            trySend(StreamEvent.Error("未找到世界数据"))
            close()
            return@callbackFlow
        }

        val request =
            buildChatRequest(action, currentPlayer, currentItems, currentMemories, currentWeather)
        Log.d(
            TAG,
            "sendActionStream: 请求构建完成, model=${request.model}, stream=${request.stream}"
        )

        val call = apiService.getChatCompletionsStream(authHeader, request)
        val fullContent = StringBuilder()

        call.enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                if (!response.isSuccessful) {
                    val code = response.code()
                    val retryable = code in 500..599
                    val msg = when {
                        code == 401 || code == 403 -> "API 认证失败，请检查密钥配置"
                        code == 429 -> "请求过于频繁，请稍后重试"
                        else -> "服务器错误 ($code)"
                    }
                    Log.e(TAG, "onResponse: HTTP $code, msg=$msg, retryable=$retryable")
                    trySend(StreamEvent.Error(msg, retryable = retryable))
                    close()
                    return
                }

                Log.d(TAG, "onResponse: HTTP ${response.code()}, 开始读取流")

                repositoryScope.launch {
                    try {
                        processStreamChunks(response, fullContent)

                        val finalJson = fullContent.toString()
                        Log.d(TAG, "流读取完成, content长度=${finalJson.length}")
                        saveJsonToDebugFile(finalJson)
                        parseAndEmitResponse(finalJson, currentPlayer, currentWeather)
                        close()
                    } catch (e: Exception) {
                        Log.e(TAG, "流处理崩溃: ${e.message}", e)
                        trySend(StreamEvent.Error("流处理崩溃: ${e.message}"))
                        close()
                    }
                }
            }

            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                Log.e(TAG, "onFailure: ${t.message}", t)
                val partial = fullContent.toString()
                repositoryScope.launch {
                    if (partial.isNotEmpty()) {
                        Log.d(TAG, "onFailure: 已接收部分内容长度=${partial.length}, 尝试修复")
                        val result = buildSalvagedResponse(partial, currentPlayer, currentWeather)
                        if (result != null) {
                            syncGameData(result, currentPlayer, currentWeather)
                            trySend(StreamEvent.FinalResponse(result))
                            close()
                            return@launch
                        }
                    }
                    trySend(StreamEvent.Error("网络连接失败: ${t.message}"))
                    close()
                }
            }
        })

        awaitClose { call.cancel() }
    }

    /**
     * 构建聊天请求
     */
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

    /**
     * 读取 SSE 流，逐块发送 Reasoning/Content 事件
     */
    private suspend fun ProducerScope<StreamEvent>.processStreamChunks(
        response: Response<ResponseBody>,
        fullContent: StringBuilder
    ) {
        response.body()?.charStream()?.buffered()?.use { reader ->
            var line: String?
            var chunkCount = 0
            while (reader.readLine().also { line = it } != null) {
                val row = line ?: ""
                if (row.startsWith("data: ")) {
                    val data = row.substring(6)
                    if (data == "[DONE]") {
                        Log.d(TAG, "processStream: 收到 [DONE], 共处理 $chunkCount 个chunk")
                        break
                    }

                    try {
                        val chunk = gson.fromJson(data, ChatChunkResponse::class.java)
                        chunkCount++
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
                        Log.w(
                            TAG,
                            "processStream: 解析单个chunk失败, data=${data.take(100)}, error=${e.message}"
                        )
                    }
                }
            }
        }
    }

    /**
     * 解析完整 JSON，失败时尝试修复
     */
    private suspend fun ProducerScope<StreamEvent>.parseAndEmitResponse(
        rawJson: String,
        player: PlayerState,
        world: WorldInfo
    ) {
        try {
            val gameData = gson.fromJson(rawJson, GameResponse::class.java)
            Log.d(
                TAG,
                "JSON解析成功: story长度=${gameData.story.length}, suggestions=${gameData.suggestions}"
            )
            syncGameData(gameData, player, world)
            trySend(StreamEvent.FinalResponse(gameData))
        } catch (e: Exception) {
            Log.w(TAG, "JSON解析失败: ${e.message}, 尝试修复", e)
            val result = buildSalvagedResponse(rawJson, player, world)
            if (result != null) {
                Log.d(TAG, "修复成功: story长度=${result.story.length}")
                syncGameData(result, player, world)
                trySend(StreamEvent.FinalResponse(result))
            } else {
                Log.e(TAG, "修复失败，无法提取任何内容")
                trySend(StreamEvent.Error("解析失败且无法修复内容"))
            }
        }
    }

    /**
     * 从残缺 JSON 中提取可用数据构建 GameResponse
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
            Log.d(
                TAG,
                "buildSalvagedResponse: 提取到story长度=${salvagedStory.length}, suggestions=${salvagedSuggestions.size}"
            )
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
        Log.d(
            TAG,
            "syncGameData: header=${data.header}, doomLevel=${update.world.doomLevel}, stateTags=${update.player.stateTags}"
        )

        // 客户端状态验证：防止 AI 返回脏数据
        val safeDoomLevel = update.world.doomLevel.coerceIn(1, 5)
        val safeStateTags = update.player.stateTags.filter { it.isNotBlank() }

        if (update.world.doomLevel != safeDoomLevel) {
            Log.w(TAG, "syncGameData: doomLevel ${update.world.doomLevel} 被修正为 $safeDoomLevel")
        }

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
            allNewItems.add(
                InventoryItem(
                    category = "食物",
                    name = it.name,
                    quantity = sanitizeQuantity(it.quantity)
                )
            )
        }
        update.resources.inventory.drinks.filter { it.name.isNotBlank() }.forEach {
            allNewItems.add(
                InventoryItem(
                    category = "饮水",
                    name = it.name,
                    quantity = sanitizeQuantity(it.quantity)
                )
            )
        }
        update.resources.inventory.weapons.filter { it.name.isNotBlank() }.forEach {
            allNewItems.add(
                InventoryItem(
                    category = "武器",
                    name = it.name,
                    quantity = sanitizeQuantity(it.quantity)
                )
            )
        }
        update.resources.inventory.special.filter { it.name.isNotBlank() }.forEach {
            allNewItems.add(
                InventoryItem(
                    category = "特殊",
                    name = it.name,
                    quantity = sanitizeQuantity(it.quantity)
                )
            )
        }
        Log.d(TAG, "syncGameData: 背包同步 ${allNewItems.size} 件物品")
        inventoryDao.syncInventory(allNewItems)

        update.memoryUpdate.newTempMemory?.let {
            Log.d(TAG, "syncGameData: 新临时记忆 - ${it.take(50)}")
            memoryDao.insertMemory(
                Memory(
                    location = data.header.location,
                    event = it,
                    result = "AI判定"
                )
            )
            memoryDao.deleteExtraTemporary(10)
        }
        update.memoryUpdate.newPermanentPlot?.let {
            Log.d(TAG, "syncGameData: 新永久剧情 - ${it.take(50)}")
            memoryDao.insertMemory(
                Memory(
                    location = data.header.location,
                    event = it,
                    result = "重大剧情",
                    isPermanent = true
                )
            )
        }

        update.npcUpdate?.forEach { npcUpdate ->
            Log.d(
                TAG,
                "syncGameData: NPC更新 - ${npcUpdate.name}, relation=${npcUpdate.relation}, delta=${npcUpdate.affinityDelta}"
            )
            npcDao.upsertNpc(npcUpdate.name, npcUpdate.relation, npcUpdate.affinityDelta)
        }
    }

    suspend fun initializeNewGame(playerName: String, doomType: String) {
        val initialPlayer = PlayerState(
            userId = getOrCreateStableUserId(),
            name = playerName,
            genderPhysique = "成年人",
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
    }

    private fun saveJsonToDebugFile(json: String) {
        if (BuildConfig.DEBUG) try {
            // 使用 context.filesDir，不需要申请外部存储权限
            val debugDir = java.io.File(context.filesDir, "debug_json")
            if (!debugDir.exists()) debugDir.mkdirs()

            // 使用固定文件名以便覆盖，或者加上时间戳
            val file = java.io.File(debugDir, "last_full_response.json")

            file.writeText(json)
            Log.d(TAG, "响应日志已保存完整JSON到文件: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "响应日志保存JSON文件失败: ${e.message}")
        }
    }
}
