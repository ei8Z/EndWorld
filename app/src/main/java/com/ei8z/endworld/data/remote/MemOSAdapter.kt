package com.ei8z.endworld.data.remote

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * MemOS 记忆系统适配器。封装多维度查询（事实/关系/因果/NPC深度），
 * 将 MemOS API 返回的原始记忆格式化为 PromptBuilder 所需文本。
 *
 * V2.3: 重构检索逻辑，引入细粒度关键词（target/subType）避免宽泛枚举导致的搜索噪声。
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
     * V2.3: 引入 subType 和 target 提升检索精度。
     *
     * @return 格式化记忆文本，可直接嵌入 PromptBuilder 的 user message。
     */
    suspend fun buildMemoryContext(
        primaryType: String,
        subType: String = "",
        target: String = "",
        location: String,
        nearbyNpcs: List<String>
    ): String = coroutineScope {
        // 事实检索：优先查特定目标和行为
        val factsDeferred = async {
            runCatching { queryFacts(primaryType, subType, target, location) }.getOrDefault("")
        }
        // 关系检索：针对周边 NPC
        val relationsDeferred = async {
            runCatching { queryRelations(nearbyNpcs) }.getOrDefault("")
        }
        // 因果检索：查特定行为或目标的过往后果
        val causalDeferred = async {
            runCatching { queryCausal(primaryType, target) }.getOrDefault("")
        }

        val facts = factsDeferred.await()
        val relations = relationsDeferred.await()
        val causal = causalDeferred.await()

        buildFormattedMemory(facts, relations, causal)
    }

    // ---- 三类查询 ----

    /**
     * 事实查询：检索与当前行动具体目标、方式和位置相关的历史事件。
     * V2.3: 关键词权重：target > subType > location > primaryType
     */
    suspend fun queryFacts(primaryType: String, subType: String, target: String, location: String): String {
        val query = buildString {
            if (target.isNotBlank()) append("$target ")
            if (subType.isNotBlank()) append("$subType ")
            if (location.isNotBlank()) append(location)
            
            // 如果上述都为空，才使用宽泛的 primaryType 兜底
            if (this.isEmpty() && primaryType.isNotBlank()) {
                append(primaryType)
            }
        }.trim()

        if (query.isEmpty()) return ""
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
     * 因果查询：检索与当前行动目标或类型相关的因果链。
     * V2.3: 优先检索特定目标的交互后果。
     */
    suspend fun queryCausal(primaryType: String, target: String): String {
        val query = when {
            target.isNotBlank() -> "与${target}交互的后果"
            primaryType.isNotBlank() -> "${primaryType}的风险与影响"
            else -> return ""
        }
        return executeQuery(query, "[因果记忆]")
    }

    /**
     * NPC深度查询：检索与指定NPC的重大互动历史。
     */
    suspend fun queryNpcDepth(npcName: String): String {
        if (npcName.isBlank()) return ""
        val importantQuery = "[关系转折] $npcName OR [重要] $npcName"
        val importantResult = executeQuery(importantQuery, "[NPC重要记忆]")
        val generalQuery = "$npcName 互动历史 性格"
        val generalResult = executeQuery(generalQuery, "[NPC互动]")

        val parts = listOf(importantResult, generalResult).filter { it.isNotBlank() }
        return parts.joinToString("\n")
    }

    // ---- 内部 ----

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

    private fun buildFormattedMemory(facts: String, relations: String, causal: String): String {
        val parts = listOf(facts, relations, causal).filter { it.isNotBlank() }
        return parts.joinToString("\n\n")
    }
}
