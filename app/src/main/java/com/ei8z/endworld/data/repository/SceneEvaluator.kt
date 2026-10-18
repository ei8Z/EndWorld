package com.ei8z.endworld.data.repository

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 场景 2-7 的统一评估入口：
 * 提示词 → 裁决客户端（非流式）→ 原始 JSON → LightweightAIValidator 白名单校验。
 * 校验失败返回 null，调用方按 §5.12 降级策略处理。
 */
class SceneEvaluator(private val reasoner: ReasonerApiClient) : SceneDecisionSource {

    override suspend fun evaluate(trigger: SceneTrigger, message: String): JsonObject? {
        val raw = reasoner.completeRaw(
            systemPrompt = PromptBuilder.buildLightweightEvalSystemPrompt(),
            userMessage = message,
            userId = "scene_${trigger.type.id}"
        ) ?: return null
        val json = try {
            JsonParser.parseString(raw).asJsonObject
        } catch (e: Exception) {
            Log.w("SceneEvaluator", "场景 ${trigger.type.id} 返回非 JSON")
            return null
        }
        val result = LightweightAIValidator.validate(json, trigger.type.id)
        if (!result.isValid) {
            Log.w("SceneEvaluator", "场景 ${trigger.type.id} 校验失败: ${result.warnings}")
            return null
        }
        return result.correctedJson
    }
}
