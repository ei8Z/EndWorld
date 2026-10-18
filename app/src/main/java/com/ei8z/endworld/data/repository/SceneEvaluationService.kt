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
 * 统一 launch、统一把决策与叙事提示交给调用方。
 *
 * [onDecision] 在提示词抽取前执行，保留场景 3/6 "先应用裁决结果、再收集 hint" 的旧顺序。
 * [onSuccess]/[onFailure] 只上报"本次 AI 评估是否拿到合法决策"：无决策（null）也算失败，
 * 供熔断与 §5.12 降级使用；调用方据此决定是否走降级。
 *
 * 只有 [SceneDecisionSource.evaluate] 抛错或返回 null 才算评估失败。拿到合法决策后，
 * [onSuccess]/[onDecision]/hint 的异常只记日志——它们不能反过来触发 §5.12 降级，否则会把
 * AI 已判的裁决覆盖成降级裁决（null hint 曾触发降级路径，可能把 AI 已判的 spare 改判为 kill）。
 */
class SceneEvaluationService(
    private val source: SceneDecisionSource,
    private val scope: CoroutineScope
) {
    fun evaluate(
        trigger: SceneTrigger,
        message: () -> String,
        onDecision: ((JsonObject) -> Unit)? = null,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Throwable?) -> Unit)? = null,
        onHint: ((String) -> Unit)? = null
    ): Job = scope.launch {
        val decision = try {
            source.evaluate(trigger, message())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "场景${trigger.type.id}评估失败 npc=${trigger.npcId}: ${e.message}", e)
            notifyFailure(onFailure, e)
            return@launch
        }
        if (decision == null) {
            notifyFailure(onFailure, null)
            return@launch
        }

        safely("onSuccess") { onSuccess?.invoke() }
        safely("onDecision") { onDecision?.invoke(decision) }
        safely("hint") {
            // null hint 是合法输出：JsonNull.asString 会抛异常，不能再让它触发降级
            decision.get("suggestedNarrativeAngle")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.takeIf { it.isNotBlank() }
                ?.let { hint -> onHint?.invoke(hint) }
        }
    }

    private fun notifyFailure(onFailure: ((Throwable?) -> Unit)?, error: Throwable?) {
        if (onFailure != null) safely("onFailure") { onFailure(error) }
    }

    /** 决策后的回调异常只记日志；取消照常穿透。 */
    private fun safely(label: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "场景评估 $label 回调异常: ${e.message}", e)
        }
    }

    private companion object {
        const val TAG = "SceneEvaluation"
    }
}
