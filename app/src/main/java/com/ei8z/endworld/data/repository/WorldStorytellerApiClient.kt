package com.ei8z.endworld.data.repository

import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.model.ChatRequest
import com.ei8z.endworld.data.remote.model.ChatMessage
import com.ei8z.endworld.data.remote.model.ResponseFormat
import com.google.gson.Gson
import com.google.gson.JsonParser

/**
 * 世界叙事Agent 云端 API 客户端
 * 加速结束时调用一次，生成最终叙事总结或死亡报告
 * Agent 配置规范 §4.2
 */
class WorldStorytellerApiClient(
    private val apiService: DeepSeekApiService,
    private val apiKey: String,
    private val gson: Gson = Gson()
) {
    data class WorldNarrativeResponse(
        val mode: String = "summary",
        val timeMarker: String = "",
        val summary: String = "",
        val notableChanges: List<NotableChange> = emptyList(),
        val playerStatus: PlayerStatusSnapshot? = null,
        val deathCause: String? = null,
        val deathGameTime: String? = null,
        val finalMoments: String? = null,
        val `final`: Boolean = true
    )

    data class NotableChange(
        val type: String = "",
        val detail: String = ""
    )

    data class PlayerStatusSnapshot(
        val hp: Int = 0,
        val hunger: Int = 0,
        val thirst: Int = 0,
        val consumedFactionResources: String = "",
        val injuriesHealed: List<String> = emptyList(),
        val injuriesSustained: List<Map<String, String>> = emptyList()
    )

    /**
     * 调用世界叙事Agent（非流式，单次完整输出）
     */
    suspend fun narrate(
        systemPrompt: String,
        userMessage: String,
        userId: String = "default_user"
    ): Result<WorldNarrativeResponse> {
        return try {
            val request = ChatRequest(
                model = "deepseek-flash",
                messages = listOf(
                    ChatMessage("system", systemPrompt),
                    ChatMessage("user", userMessage)
                ),
                stream = false,
                responseFormat = ResponseFormat("json_object"),
                maxTokens = ChatRequest.MAX_OUTPUT_TOKENS,
                reasoningEffort = "high"
            )

            val response = apiService.getChatCompletions(apiKey, request)
            val content = response.choices.firstOrNull()?.message?.content
                ?: return Result.failure(Exception("Empty response"))

            val json = JsonParser.parseString(content).asJsonObject
            val result = WorldNarrativeResponse(
                mode = json.get("mode")?.asString ?: "summary",
                timeMarker = json.get("timeMarker")?.asString ?: "",
                summary = json.get("summary")?.asString ?: "",
                notableChanges = json.getAsJsonArray("notableChanges")?.map { elem ->
                    val obj = elem.asJsonObject
                    NotableChange(
                        obj.get("type")?.asString ?: "",
                        obj.get("detail")?.asString ?: ""
                    )
                } ?: emptyList(),
                playerStatus = json.getAsJsonObject("playerStatus")?.let { ps ->
                    PlayerStatusSnapshot(
                        hp = ps.get("hp")?.asInt ?: 0,
                        hunger = ps.get("hunger")?.asInt ?: 0,
                        thirst = ps.get("thirst")?.asInt ?: 0,
                        consumedFactionResources = ps.get("consumedFactionResources")?.asString ?: "",
                        injuriesHealed = ps.getAsJsonArray("injuriesHealed")
                            ?.map { it.asString } ?: emptyList(),
                        injuriesSustained = ps.getAsJsonArray("injuriesSustained")?.map { elem ->
                            val obj = elem.asJsonObject
                            mapOf(
                                "type" to (obj.get("type")?.asString ?: ""),
                                "severity" to (obj.get("severity")?.asString ?: "")
                            )
                        } ?: emptyList()
                    )
                },
                deathCause = json.get("deathCause")?.asString,
                deathGameTime = json.get("deathGameTime")?.asString,
                finalMoments = json.get("finalMoments")?.asString,
                `final` = json.get("final")?.asBoolean ?: true
            )
            Result.success(result)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 降级响应
     */
    fun buildFallbackResponse(
        timeMarker: String,
        playerStatus: PlayerStatusSnapshot?,
        playerSurvived: Boolean = true
    ): WorldNarrativeResponse {
        return if (playerSurvived) {
            WorldNarrativeResponse(
                mode = "summary",
                timeMarker = timeMarker,
                summary = "时间加速流逝。当你回过神来，世界已经悄然改变。",
                notableChanges = emptyList(),
                playerStatus = playerStatus,
                `final` = true
            )
        } else {
            WorldNarrativeResponse(
                mode = "death_report",
                timeMarker = timeMarker,
                summary = "生命在这一刻画上了句点……",
                playerStatus = playerStatus?.copy(hp = 0),
                `final` = true
            )
        }
    }
}
