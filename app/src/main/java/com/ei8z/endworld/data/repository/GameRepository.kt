package com.ei8z.endworld.data.repository

import android.content.Context
import android.util.Log
import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.data.local.entity.*
import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.model.*
import com.ei8z.endworld.util.JsonExtractor
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
     * [M6] 流式发送行动并处理回复（带 JSON 容错）
     */
    fun sendActionStream(action: String): Flow<StreamEvent> = callbackFlow {
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

        val request = ChatRequest(
            messages = listOf(
                ChatMessage("system", PromptBuilder.buildSystemPrompt()),
                ChatMessage("user", PromptBuilder.buildUserSnapshot(currentPlayer, currentItems, currentMemories, currentWeather, action))
            ),
            userId = currentPlayer.userId,
            stream = true
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
                    trySend(StreamEvent.Error(msg, retryable = retryable))
                    close()
                    return
                }

                repositoryScope.launch {
                    try {
                        response.body()?.charStream()?.buffered()?.use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                val row = line ?: ""
                                if (row.startsWith("data: ")) {
                                    val data = row.substring(6)
                                    if (data == "[DONE]") break

                                    val chunk = gson.fromJson(data, ChatChunkResponse::class.java)
                                    val delta = chunk.choices.firstOrNull()?.delta

                                    delta?.reasoningContent?.let { trySend(StreamEvent.Reasoning(it)) }
                                    delta?.content?.let {
                                        fullContent.append(it)
                                        trySend(StreamEvent.Content(it))
                                    }
                                    chunk.usage?.let { u ->
                                        Log.d("CacheStats", "hit=${u.promptCacheHitTokens ?: 0} miss=${u.promptCacheMissTokens ?: 0}")
                                    }
                                }
                            }
                        }

                        val finalJson = fullContent.toString()
                        try {
                            val gameData = gson.fromJson(finalJson, GameResponse::class.java)
                            syncGameData(gameData, currentPlayer, currentWeather)
                            trySend(StreamEvent.FinalResponse(gameData))
                        } catch (e: Exception) {
                            val salvagedStory = JsonExtractor.tryExtractStory(finalJson)
                            if (salvagedStory != null) {
                                val salvagedSuggestions = JsonExtractor.tryExtractSuggestions(finalJson).ifEmpty { listOf("继续探索", "原地休整", "谨慎行动") }
                                val fakeResponse = GameResponse(
                                    header = ResponseHeader(currentWeather.currentDate, currentWeather.currentTime, currentWeather.locationScene),
                                    story = salvagedStory,
                                    suggestions = salvagedSuggestions,
                                    statusUpdate = StatusUpdate(
                                        player = PlayerUpdate(currentPlayer.mentalStateTags, currentPlayer.innerThought, currentPlayer.equippedItems),
                                        world = WorldUpdate(currentPlayer.doomLevel, currentWeather.macroSituation),
                                        resources = ResourceUpdate(emptyList(), InventoryUpdate()),
                                        memoryUpdate = MemoryUpdate(null, null)
                                    )
                                )
                                trySend(StreamEvent.FinalResponse(fakeResponse))
                            } else {
                                trySend(StreamEvent.Error("解析失败且无法修复内容"))
                            }
                        }
                        close()
                    } catch (e: Exception) {
                        trySend(StreamEvent.Error("流处理崩溃: ${e.message}"))
                        close()
                    }
                }
            }

            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                // 部分流恢复：如果已接收到内容，尝试解析
                val partial = fullContent.toString()
                if (partial.isNotEmpty()) {
                    val salvagedStory = JsonExtractor.tryExtractStory(partial)
                    if (salvagedStory != null) {
                        val salvagedSuggestions = JsonExtractor.tryExtractSuggestions(partial).ifEmpty { listOf("继续探索", "原地休整", "谨慎行动") }
                        val fakeResponse = GameResponse(
                            header = ResponseHeader(currentWeather.currentDate, currentWeather.currentTime, currentWeather.locationScene),
                            story = salvagedStory,
                            suggestions = salvagedSuggestions,
                            statusUpdate = StatusUpdate(
                                player = PlayerUpdate(currentPlayer.mentalStateTags, currentPlayer.innerThought, currentPlayer.equippedItems),
                                world = WorldUpdate(currentPlayer.doomLevel, currentWeather.macroSituation),
                                resources = ResourceUpdate(emptyList(), InventoryUpdate()),
                                memoryUpdate = MemoryUpdate(null, null)
                            )
                        )
                        trySend(StreamEvent.FinalResponse(fakeResponse))
                        close()
                        return
                    }
                }
                trySend(StreamEvent.Error("网络连接失败: ${t.message}"))
                close()
            }
        })

        awaitClose { call.cancel() }
    }

    private suspend fun syncGameData(data: GameResponse, oldPlayer: PlayerState, oldWorld: WorldInfo) {
        val update = data.statusUpdate

        // 客户端状态验证：防止 AI 返回脏数据
        val safeDoomLevel = update.world.doomLevel.coerceIn(1, 5)
        val safeStateTags = update.player.stateTags.filter { it.isNotBlank() }

        playerDao.updatePlayerState(oldPlayer.copy(
            mentalStateTags = safeStateTags,
            innerThought = update.player.innerThought,
            equippedItems = update.player.equipment,
            doomLevel = safeDoomLevel
        ))

        worldDao.updateWorldInfo(oldWorld.copy(
            currentDate = data.header.date,
            currentTime = data.header.time,
            locationScene = data.header.location,
            macroSituation = update.world.macroSituation,
            knownPointsJson = gson.toJson(update.resources.knownPoints)
        ))

        val allNewItems = mutableListOf<InventoryItem>()
        fun sanitizeQuantity(q: String) = if (q.startsWith("-")) "0" else q
        update.resources.inventory.food.filter { it.name.isNotBlank() }.forEach { allNewItems.add(InventoryItem(category = "食物", name = it.name, quantity = sanitizeQuantity(it.quantity))) }
        update.resources.inventory.drinks.filter { it.name.isNotBlank() }.forEach { allNewItems.add(InventoryItem(category = "饮水", name = it.name, quantity = sanitizeQuantity(it.quantity))) }
        update.resources.inventory.weapons.filter { it.name.isNotBlank() }.forEach { allNewItems.add(InventoryItem(category = "武器", name = it.name, quantity = sanitizeQuantity(it.quantity))) }
        update.resources.inventory.special.filter { it.name.isNotBlank() }.forEach { allNewItems.add(InventoryItem(category = "特殊", name = it.name, quantity = sanitizeQuantity(it.quantity))) }
        inventoryDao.syncInventory(allNewItems)

        update.memoryUpdate.newTempMemory?.let {
            memoryDao.insertMemory(Memory(location = data.header.location, event = it, result = "AI判定"))
            memoryDao.deleteExtraTemporary(10)
        }
        update.memoryUpdate.newPermanentPlot?.let {
            memoryDao.insertMemory(Memory(location = data.header.location, event = it, result = "重大剧情", isPermanent = true))
        }
        
        update.npcUpdate?.forEach { npcUpdate ->
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
}
