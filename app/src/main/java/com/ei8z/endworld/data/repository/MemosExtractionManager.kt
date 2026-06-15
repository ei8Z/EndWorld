package com.ei8z.endworld.data.repository

import com.ei8z.endworld.data.remote.MemosApiService
import com.ei8z.endworld.domain.npc.model.MemoryEntry
import com.ei8z.endworld.domain.npc.model.MemorySource
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType

/**
 * MemOS 记忆抽取管理器
 * 每日结算时调用 /extract/memory API，将交互文本转化为结构化记忆
 * NPC 文档 §6.4
 */
class MemosExtractionManager(
    private val memosApiService: MemosApiService,
    private val memosApiKey: String,
    private val gson: Gson = Gson()
) {
    data class ExtractionResult(
        val memories: List<ExtractedMemory>,
        val preferences: List<ExtractedPreference>
    )

    data class ExtractedMemory(
        val memoryKey: String,
        val memoryValue: String,
        val memoryType: String = "LongTermMemory",
        val tags: List<String> = emptyList()
    )

    data class ExtractedPreference(
        val preference: String,
        val reasoning: String,
        val preferenceType: String
    )

    // 连续失败计数器（按NPC分组）
    private val consecutiveFailures = mutableMapOf<String, Int>()

    /**
     * 批量抽取记忆
     * @param npcId NPC ID
     * @param interactionTexts 当日该NPC的玩家交互文本列表
     * @param timestamp 时间戳（用于生成记忆条目）
     * @param location 交互场景
     * @return 抽取到的结构化记忆条目列表
     */
    suspend fun extractMemories(
        npcId: String,
        interactionTexts: List<String>,
        timestamp: String,
        location: String
    ): List<MemoryEntry> {
        // 检查是否已暂停
        if ((consecutiveFailures[npcId] ?: 0) >= 5) {
            return emptyList() // 暂停30日
        }

        if (interactionTexts.isEmpty()) return emptyList()

        return try {
            val messages = interactionTexts.mapIndexed { index, text ->
                mapOf(
                    "role" to if (index % 2 == 0) "user" else "assistant",
                    "content" to text
                )
            }

            val requestBody = gson.toJson(mapOf(
                "messages" to messages,
                "extraction_types" to listOf("memory", "preference")
            ))

            val response = memosApiService.extractMemory(
                authorization = "Token $memosApiKey",
                body = requestBody
            )

            val json = JsonParser.parseString(response).asJsonObject
            if (json.get("code")?.asInt != 0 && json.get("code")?.asInt != null) {
                recordFailure(npcId)
                return emptyList()
            }

            val data = json.getAsJsonObject("data") ?: run {
                recordFailure(npcId)
                return emptyList()
            }

            val memoryList = data.getAsJsonArray("memory_detail_list")
            val entries = mutableListOf<MemoryEntry>()

            memoryList?.forEach { elem ->
                val obj = elem.asJsonObject
                entries.add(
                    MemoryEntry(
                        timestamp = timestamp,
                        location = location,
                        summary = "[${obj.get("memory_key")?.asString}]: " +
                            "${obj.get("memory_value")?.asString}",
                        source = MemorySource.MEMOS_EXTRACTED,
                        emotionalTag = obj.getAsJsonArray("tags")
                            ?.firstOrNull()?.asString
                    )
                )
            }

            // 处理偏好记忆（存储为NPC的行为标签，不在MemoryEntry中）
            val preferenceList = data.getAsJsonArray("preference_detail_list")
            // preferences 由调用方处理（更新NPC行为倾向标签）

            // 成功：重置失败计数
            consecutiveFailures.remove(npcId)
            entries
        } catch (e: Exception) {
            recordFailure(npcId)
            emptyList()
        }
    }

    private fun recordFailure(npcId: String) {
        consecutiveFailures[npcId] = (consecutiveFailures[npcId] ?: 0) + 1
    }

    /**
     * 检查是否已暂停该NPC的自动抽取
     */
    fun isExtractionPaused(npcId: String): Boolean {
        return (consecutiveFailures[npcId] ?: 0) >= 5
    }

    /**
     * 手动重置失败计数（如NPC被重新接触）
     */
    fun resetFailures(npcId: String) {
        consecutiveFailures.remove(npcId)
    }
}

/**
 * MemOS API 的 extract/memory 端点定义
 * 如果 MemosApiService 中没有此方法，使用 OkHttp 直接调用
 */
suspend fun MemosApiService.extractMemory(
    authorization: String,
    body: String
): String {
    // 使用 Retrofit 的底层 OkHttp 或定义单独的 endpoint
    // 此处假设 MemosApiService 已扩展该方法
    // 直接通过 Retrofit/OkHttp 调用
    // 直接通过 OkHttp 调用 MemOS API
    val client = okhttp3.OkHttpClient()
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val requestBody = okhttp3.RequestBody.create(mediaType, body)
    val request = okhttp3.Request.Builder()
        .url("${getBaseUrl()}/extract/memory")
        .addHeader("Authorization", authorization)
        .addHeader("Content-Type", "application/json")
        .post(requestBody)
        .build()
    val response = client.newCall(request).execute()
    return response.body?.string() ?: "{}"
}

private fun getBaseUrl(): String = "https://memos.memtensor.cn/api/openmem/v1"
