package com.ei8z.endworld.data.repository

import com.ei8z.endworld.data.remote.MemosApiService
import com.ei8z.endworld.data.remote.MemosExtractRequest
import com.ei8z.endworld.data.remote.MemosMessage
import com.ei8z.endworld.domain.npc.model.MemoryEntry
import com.ei8z.endworld.domain.npc.model.MemorySource

/**
 * MemOS 记忆抽取管理器
 * 每日结算时调用 /extract/memory API，将交互文本转化为结构化记忆。
 * NPC 文档 §6.4
 */
class MemosExtractionManager(
    private val memosApiService: MemosApiService,
    private val memosApiKey: String
) {
    // 连续失败计数（按 NPC 分组）
    private val consecutiveFailures = mutableMapOf<String, Int>()

    // 暂停到期游戏日：gameDay < pausedUntilDay 时视为暂停
    private val pausedUntilDay = mutableMapOf<String, Int>()

    /** 连续失败 5 次后暂停 30 个游戏日（不是永久停用）。 */
    fun isExtractionPaused(npcId: String, gameDay: Int): Boolean {
        val until = pausedUntilDay[npcId] ?: return false
        return gameDay < until
    }

    /**
     * 批量抽取记忆
     * @param npcId NPC ID
     * @param interactionTexts 当日该 NPC 的玩家交互文本列表
     * @param timestamp 时间戳（用于生成记忆条目）
     * @param location 交互场景
     * @param gameDay 当前游戏日（用于暂停判定）
     * @return 抽取到的结构化记忆条目列表
     */
    suspend fun extractMemories(
        npcId: String,
        interactionTexts: List<String>,
        timestamp: String,
        location: String,
        gameDay: Int
    ): List<MemoryEntry> {
        if (isExtractionPaused(npcId, gameDay)) return emptyList()
        if (interactionTexts.isEmpty()) return emptyList()

        return try {
            val request = MemosExtractRequest(
                messages = interactionTexts.mapIndexed { i, text ->
                    MemosMessage(role = if (i % 2 == 0) "user" else "assistant", content = text)
                },
                extractionTypes = listOf("memory", "preference")
            )
            val response = memosApiService.extractMemory("Token $memosApiKey", request)
            if (response.code != 0) {
                recordFailure(npcId, gameDay)
                return emptyList()
            }
            val data = response.data ?: run {
                recordFailure(npcId, gameDay)
                return emptyList()
            }
            consecutiveFailures.remove(npcId)
            pausedUntilDay.remove(npcId)
            data.memories.orEmpty().map { m ->
                MemoryEntry(
                    timestamp = timestamp,
                    location = location,
                    summary = "[${m.key ?: ""}]: ${m.value ?: ""}",
                    source = MemorySource.MEMOS_EXTRACTED,
                    emotionalTag = m.tags?.firstOrNull()
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            recordFailure(npcId, gameDay)
            emptyList()
        }
    }

    private fun recordFailure(npcId: String, gameDay: Int) {
        val failures = (consecutiveFailures[npcId] ?: 0) + 1
        consecutiveFailures[npcId] = failures
        if (failures >= 5) {
            pausedUntilDay[npcId] = gameDay + 30
        }
    }
}
