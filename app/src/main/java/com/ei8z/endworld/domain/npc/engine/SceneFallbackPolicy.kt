package com.ei8z.endworld.domain.npc.engine

import kotlin.random.Random

/** §5.12 场景3 的降级裁决结果。 */
enum class Scene3Fallback { KILL, SEVERE_INJURY, SPARE }

/**
 * §5.12 场景3 降级裁决：近 10 游戏日内该 NPC 已有一次 spare 降级则 kill，
 * 否则 50% spare / 50% severe_injury。随机源可注入，测试用固定序列。
 */
class Scene3FallbackDecider(private val random: Random = Random.Default) {
    fun decide(lastSpareFallbackDay: Int?, currentDay: Int): Scene3Fallback =
        if (lastSpareFallbackDay != null && currentDay - lastSpareFallbackDay <= 10) Scene3Fallback.KILL
        else if (random.nextBoolean()) Scene3Fallback.SPARE else Scene3Fallback.SEVERE_INJURY
}

/**
 * 连续失败熔断：同一场景连续 [maxConsecutiveFailures] 次失败后暂停 [pauseMinutes] 分钟
 * （默认 5 次 / 24 游戏小时），暂停期间调用方不再调用 AI、只走降级；成功清零。
 * 状态按场景 id 分开，互不影响。
 */
class SceneFallbackGuard(
    private val maxConsecutiveFailures: Int = 5,
    private val pauseMinutes: Int = 24 * 60
) {
    private data class State(var failures: Int = 0, var pausedUntil: Int = -1)
    private val states = mutableMapOf<Int, State>()

    @Synchronized
    fun isPaused(sceneId: Int, nowMinutes: Int): Boolean =
        states[sceneId]?.let { nowMinutes < it.pausedUntil } ?: false

    @Synchronized
    fun recordSuccess(sceneId: Int) { states[sceneId]?.failures = 0 }

    @Synchronized
    fun recordFailure(sceneId: Int, nowMinutes: Int) {
        val state = states.getOrPut(sceneId) { State() }
        state.failures += 1
        if (state.failures >= maxConsecutiveFailures) {
            state.pausedUntil = nowMinutes + pauseMinutes
            state.failures = 0
        }
    }

    /** 新开局清空所有场景的失败计数与暂停。 */
    @Synchronized
    fun reset() = states.clear()
}
