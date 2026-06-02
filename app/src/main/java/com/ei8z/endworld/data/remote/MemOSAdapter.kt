package com.ei8z.endworld.data.remote

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope

/**
 * MemOS 记忆系统适配器。封装多维度查询（事实/关系/因果/NPC深度），
 * 将 MemOS API 返回的原始记忆格式化为 PromptBuilder 所需文本。
 *
 * V2.2: 新增NPC深度查询，优先筛选 [重要]/[关系转折] 标签记忆。
 */
class MemOSAdapter(
    private val apiService: MemosApiService,
    private val authHeader: String,
    private val userId: String,
    private val conversationId: String
) {
    companion object {
        private const val TAG = "MemOSAdapter"
    }

    /**
     * 执行三类并行查询并返回格式化记忆文本。
     * 任一类查询失败时该类别返回空字符串，不阻塞其他查询。
     *
     * @return 格式化记忆文本，可直接嵌入 PromptBuilder 的 user message。
     *         无记忆时返回空字符串。
     */
    suspend fun buildMemoryContext(
        actionType: String,
        location: String,
        nearbyNpcs: List<String>
    ): String = coroutineScope {
        val factsDeferred = async { runCatching { queryFacts(actionType, location) }.getOrDefault("") }
        val relationsDeferred = async { runCatching { queryRelations(nearbyNpcs) }.getOrDefault("") }
        val causalDeferred = async { runCatching { queryCausal(actionType) }.getOrDefault("") }

        val facts = factsDeferred.await()
        val relations = relationsDeferred.await()
        val causal = causalDeferred.await()

        buildFormattedMemory(facts, relations, causal)
    }

    // ---- 三类查询 ----

    /**
     * 事实查询：检索与当前行动类型和位置相关的历史事件。
     */
    suspend fun queryFacts(actionType: String, location: String): String {
        if (actionType.isBlank()) return ""
        val query = buildString {
            append(actionType)
            if (location.isNotBlank()) append(" $location")
        }
        return executeQuery(query, "[事实记忆]")
    }

    /**
     * 关系查询：检索与周边 NPC 的互动历史。
     */
    suspend fun queryRelations(nearbyNpcs: List<String>): String {
        if (nearbyNpcs.isEmpty()) return ""
        val query = nearbyNpcs.joinToString(" ") { it }
        if (query.isBlank()) return ""
        return executeQuery(query, "[关系记忆]")
    }

    /**
     * 因果查询：检索与当前行动类型相关的因果链。
     * 例如："搜索" → 检索历史上搜索行动导致的后果。
     */
    suspend fun queryCausal(actionType: String): String {
        if (actionType.isBlank()) return ""
        val query = "${actionType}的后果"
        return executeQuery(query, "[因果记忆]")
    }

    /**
     * V2.2 NPC深度查询：检索与指定NPC的重大互动历史。
     * 优先级：优先返回含 [关系转折]/[重要] 标签的记忆。
     */
    suspend fun queryNpcDepth(npcName: String): String {
        if (npcName.isBlank()) return ""
        // 双查询策略：先查重要标记，再查一般互动
        val importantQuery = "[关系转折] $npcName OR [重要] $npcName"
        val importantResult = executeQuery(importantQuery, "[NPC重要记忆]")
        val generalQuery = "$npcName 互动历史 性格"
        val generalResult = executeQuery(generalQuery, "[NPC互动]")

        val parts = listOf(importantResult, generalResult).filter { it.isNotBlank() }
        return parts.joinToString("\n")
    }

    // ---- 内部 ----

    /**
     * 执行单次 MemOS 搜索并格式化结果。
     * 查询失败时记录日志并返回空字符串（降级策略）。
     */
    private suspend fun executeQuery(query: String, label: String): String {
        return try {
            val response = apiService.searchMemory(
                authHeader,
                MemosSearchRequest(userId, conversationId, query)
            )
            val memories = response.data?.memories ?: emptyList()
            val preferences = response.data?.preferences ?: emptyList()

            if (memories.isEmpty() && preferences.isEmpty()) return ""

            val lines = mutableListOf<String>()
            lines.add("$label:")
            memories.forEach { lines.add("- ${it.value}") }
            preferences.forEach { lines.add("- [偏好] ${it.value}") }
            lines.joinToString("\n")
        } catch (e: Exception) {
            Log.e(TAG, "MemOS query failed ($label): ${e.message}")
            ""
        }
    }

    /**
     * 将三类查询结果拼接为最终的记忆文本。
     * 若全部为空则返回空字符串。
     */
    private fun buildFormattedMemory(facts: String, relations: String, causal: String): String {
        val parts = listOf(facts, relations, causal).filter { it.isNotBlank() }
        return parts.joinToString("\n\n")
    }
}
