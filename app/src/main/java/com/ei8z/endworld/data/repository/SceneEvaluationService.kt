package com.ei8z.endworld.data.repository

import android.util.Log
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 场景评估的决策来源；生产实现是 [SceneEvaluator]，单测可传纯函数。 */
fun interface SceneDecisionSource {
    suspend fun evaluate(trigger: SceneTrigger, message: String): JsonObject?
}

/**
 * 场景 2-6 的统一异步评估入口：
 * 统一 launch、统一 try/catch（取消穿透）、统一把决策与叙事提示交给调用方。
 *
 * [onDecision] 在提示词抽取前执行，保留场景 3/6 "先应用裁决结果、再收集 hint" 的旧顺序。
 */
class SceneEvaluationService(
    private val source: SceneDecisionSource,
    private val scope: CoroutineScope
) {
    fun evaluate(
        trigger: SceneTrigger,
        message: () -> String,
        onDecision: ((JsonObject) -> Unit)? = null,
        onHint: ((String) -> Unit)? = null
    ): Job = scope.launch {
        try {
            source.evaluate(trigger, message())?.let { decision ->
                onDecision?.invoke(decision)
                decision.get("suggestedNarrativeAngle")?.asString
                    ?.takeIf { it.isNotBlank() }
                    ?.let { hint -> onHint?.invoke(hint) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "场景${trigger.type.id}评估失败 npc=${trigger.npcId}: ${e.message}", e)
        }
    }

    private companion object {
        const val TAG = "SceneEvaluation"
    }
}
