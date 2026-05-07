package com.ei8z.endworld.data.repository

import com.ei8z.endworld.data.local.AppDatabase
import com.ei8z.endworld.data.local.entity.*
import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.model.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class GameRepository(
    private val database: AppDatabase,
    private val apiService: DeepSeekApiService,
    private val apiKey: String
) {
    private val gson = Gson()
    private val authHeader = "Bearer $apiKey"

    // DAOs
    private val playerDao = database.playerDao()
    private val inventoryDao = database.inventoryDao()
    private val memoryDao = database.memoryDao()
    private val worldDao = database.worldDao()
    private val npcDao = database.npcDao()

    // 暴露给 UI 的数据流
    val playerState: Flow<PlayerState?> = playerDao.getPlayer()
    val inventory: Flow<List<InventoryItem>> = inventoryDao.getAllItems()
    val recentMemories: Flow<List<Memory>> = memoryDao.getRecentMemories(10)
    val worldInfo: Flow<WorldInfo?> = worldDao.getWorldInfo()

    /**
     * 发送玩家行动并处理 AI 回复
     */
    suspend fun sendAction(action: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            // 1. 获取当前所有状态
            val currentPlayer = playerState.first() ?: return@withContext Result.failure(Exception("未找到玩家数据"))
            val currentItems = inventory.first()
            val currentMemories = recentMemories.first()
            val currentWeather = worldInfo.first() ?: return@withContext Result.failure(Exception("未找到世界数据"))

            // 2. 组装 Prompt
            val systemPrompt = PromptBuilder.buildSystemPrompt()
            val userSnapshot = PromptBuilder.buildUserSnapshot(
                currentPlayer, currentItems, currentMemories, currentWeather, action
            )

            // 3. 调用 API
            val request = ChatRequest(
                messages = listOf(
                    ChatMessage("system", systemPrompt),
                    ChatMessage("user", userSnapshot)
                ),
                userId = currentPlayer.userId
            )

            val response = apiService.getChatCompletions(authHeader, request)
            val jsonResponse = response.choices.firstOrNull()?.message?.content 
                ?: return@withContext Result.failure(Exception("AI 未返回有效内容"))

            // 4. 解析并同步数据库
            val gameData = gson.fromJson(jsonResponse, GameResponse::class.java)
            syncGameData(gameData, currentPlayer, currentWeather)

            Result.success(gameData.story)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    /**
     * 核心逻辑：将 AI 的判定结果同步到本地数据库
     */
    private suspend fun syncGameData(
        data: GameResponse,
        oldPlayer: PlayerState,
        oldWorld: WorldInfo
    ) {
        val update = data.statusUpdate

        // 1. 更新玩家属性
        val newPlayer = oldPlayer.copy(
            mentalStateTags = update.player.stateTags,
            innerThought = update.player.innerThought,
            equippedItems = update.player.equipment,
            doomLevel = update.world.doomLevel
        )
        playerDao.updatePlayerState(newPlayer)

        // 2. 更新世界信息
        val newWorld = oldWorld.copy(
            currentDate = data.header.date,
            currentTime = data.header.time,
            locationScene = data.header.location,
            macroSituation = update.world.macroSituation
        )
        worldDao.updateWorldInfo(newWorld)

        // 3. 更新物资 (使用全量覆盖事务)
        val allNewItems = mutableListOf<InventoryItem>()
        update.resources.inventory.food.forEach { allNewItems.add(InventoryItem(category = "food", name = it.name, quantity = it.quantity)) }
        update.resources.inventory.drinks.forEach { allNewItems.add(InventoryItem(category = "drinks", name = it.name, quantity = it.quantity)) }
        update.resources.inventory.weapons.forEach { allNewItems.add(InventoryItem(category = "weapons", name = it.name, quantity = it.quantity)) }
        update.resources.inventory.special.forEach { allNewItems.add(InventoryItem(category = "special", name = it.name, quantity = it.quantity)) }
        inventoryDao.syncInventory(allNewItems)

        // 4. 处理记忆
        update.memoryUpdate.newTempMemory?.let {
            memoryDao.insertMemory(Memory(location = data.header.location, event = it, result = "AI判定"))
            memoryDao.deleteExtraTemporary(10) // FIFO
        }
        update.memoryUpdate.newPermanentPlot?.let {
            memoryDao.insertMemory(Memory(location = data.header.location, event = it, result = "重大节点", isPermanent = true))
        }
    }

    /**
     * 初始化游戏
     */
    suspend fun initializeNewGame(playerName: String, doomType: String) {
        val initialPlayer = PlayerState(
            userId = "user_${System.currentTimeMillis()}",
            name = playerName,
            genderPhysique = "成年男性",
            personality = "冷静",
            baseDescription = "前搜救队成员",
            doomType = doomType,
            mentalStateTags = listOf("健康", "迷茫"),
            innerThought = "我必须活下去。",
            equippedItems = listOf("手电筒")
        )
        
        val initialWorld = WorldInfo(
            currentDate = "第1天",
            currentTime = "清晨",
            locationScene = "避难所地下室",
            macroSituation = "灾难刚刚爆发，通讯中断。",
            knownPointsJson = "[]"
        )

        playerDao.updatePlayerState(initialPlayer)
        worldDao.updateWorldInfo(initialWorld)
        // 插入一个初始记忆
        memoryDao.insertMemory(Memory(location = "地下室", event = "在一场大混乱后躲进了这里", result = "幸存"))
    }
}
