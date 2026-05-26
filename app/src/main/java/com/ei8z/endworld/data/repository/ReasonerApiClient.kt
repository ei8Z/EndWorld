package com.ei8z.endworld.data.repository

import com.ei8z.endworld.data.model.ActionResult
import com.ei8z.endworld.data.model.EventCardRef
import com.ei8z.endworld.data.model.ResourceChange
import com.ei8z.endworld.data.remote.DeepSeekApiService
import com.ei8z.endworld.data.remote.model.ChatMessage
import com.ei8z.endworld.data.remote.model.ChatRequest
import com.ei8z.endworld.data.remote.model.ResponseFormat
import org.json.JSONArray
import org.json.JSONObject

/**
 * 裁决Agent API 客户端。封装对 DeepSeek API 的调用，发送裁决提示词和上下文，
 * 解析返回的 ActionResult JSON。
 *
 * API 参数（对应文档 3.5）：
 * - model: deepseek-v4-pro
 * - reasoningEffort: high（裁决需要高推理能力）
 * - responseFormat: json_object
 * - maxTokens: 1024（裁决结果较短）
 * - stream: false（裁决不需要流式）
 *
 * TODO: Phase 8 主线串通时，根据实际 API 返回的 JSON 结构调整解析路径。
 *       当前假设 response 的 choices[0].message.content 包含完整的 ActionResult JSON。
 */
class ReasonerApiClient(
    private val apiService: DeepSeekApiService,
    private val authHeader: String
) {
    /**
     * 调用裁决Agent，传入提示词和用户消息，返回 ActionResult。
     *
     * @param systemPrompt 裁决Agent系统提示词（PromptBuilder.REASONER_SYSTEM_PROMPT）
     * @param userMessage 用户消息（PromptBuilder.buildReasonerUserMessage 的输出）
     * @param userId 用于 KV Cache 优化的用户ID
     * @return ActionResult，解析失败时返回降级结果
     */
    suspend fun reason(
        systemPrompt: String,
        userMessage: String,
        userId: String
    ): ActionResult {
        val request = ChatRequest(
            model = "deepseek-v4-pro",
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userMessage)
            ),
            reasoningEffort = "max",
            responseFormat = ResponseFormat("json_object"),
            maxTokens = 8192,
            stream = false,
            userId = userId
        )

        return try {
            val response = apiService.getChatCompletions(authHeader, request)
            val content = response.choices.firstOrNull()?.message?.content
            if (content.isNullOrBlank()) {
                // Empty response from reasoner API — fallback
                return buildFallbackResult(userMessage)
            }
            parseActionResult(content)
        } catch (e: Exception) {
            // Reasoner API call failed — fallback
            buildFallbackResult(userMessage)
        }
    }

    /**
     * 解析 ActionResult JSON。
     * 裁决Agent 返回 {"actionResult": {...}}，需要提取内层对象。
     */
    internal fun parseActionResult(json: String): ActionResult {
        return try {
            val root = JSONObject(json)
            val arJson = if (root.has("actionResult")) {
                root.getJSONObject("actionResult")
            } else {
                root
            }
            ActionResult(
                result = arJson.optString("result", "失败"),
                dcValue = arJson.optInt("dcValue", 50),
                rollValue = arJson.optInt("rollValue", 50),
                totalValue = arJson.optInt("totalValue", 50),
                modifiersBreakdown = arJson.optString("modifiersBreakdown", ""),
                resourceChanges = parseResourceChanges(arJson),
                injuriesApplied = parseStringList(arJson, "injuriesApplied"),
                injuriesRecovered = parseStringList(arJson, "injuriesRecovered"),
                narrativeSummary = arJson.optString("narrativeSummary", ""),
                eventTriggered = parseEventTriggered(arJson),
                plotNodeCompleted = arJson.optString("plotNodeCompleted", null),
                plotNodeHint = arJson.optString("plotNodeHint", null)
            )
        } catch (e: Exception) {
            buildFallbackResult(json)
        }
    }

    private fun parseResourceChanges(root: JSONObject): List<ResourceChange> {
        val arr = root.optJSONArray("resourceChanges") ?: return emptyList()
        val result = mutableListOf<ResourceChange>()
        for (i in 0 until arr.length()) {
            try {
                val obj = arr.getJSONObject(i)
                result.add(ResourceChange(
                    itemId = obj.optString("itemId", ""),
                    itemName = obj.optString("itemName", ""),
                    category = obj.optString("category", ""),
                    changeAmount = obj.optInt("changeAmount", 0),
                    newValue = obj.optInt("newValue", 0),
                    maxValue = if (obj.has("maxValue") && !obj.isNull("maxValue")) obj.optInt("maxValue") else null,
                    narrativeHint = obj.optString("narrativeHint", "")
                ))
            } catch (e: Exception) { /* skip broken entry */ }
        }
        return result
    }

    private fun parseStringList(root: JSONObject, key: String): List<String> {
        val arr = root.optJSONArray(key) ?: return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, null)
            if (s != null) result.add(s)
        }
        return result
    }

    private fun parseEventTriggered(root: JSONObject): EventCardRef? {
        if (!root.has("eventTriggered") || root.isNull("eventTriggered")) return null
        return try {
            val obj = root.getJSONObject("eventTriggered")
            EventCardRef(
                id = obj.optString("id", "").takeIf { it.isNotBlank() } ?: return null,
                name = obj.optString("name", ""),
                narrativeHint = obj.optString("narrativeHint", "")
            )
        } catch (e: Exception) { null }
    }

    /**
     * API 调用或 JSON 解析失败时的降级结果。
     * 判定为"失败"但不附加伤病，确保流程不会因单次 API 异常而中断。
     */
    private fun buildFallbackResult(hint: String): ActionResult {
        return ActionResult(
            result = "失败",
            dcValue = 50,
            rollValue = 50,
            totalValue = 50,
            modifiersBreakdown = "降级结果：API异常",
            resourceChanges = emptyList(),
            injuriesApplied = emptyList(),
            injuriesRecovered = emptyList(),
            narrativeSummary = "行动因不明原因中断。（API异常降级）",
            eventTriggered = null,
            plotNodeCompleted = null,
            plotNodeHint = null
        )
    }
}
