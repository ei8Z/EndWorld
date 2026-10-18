package com.ei8z.endworld.data.repository

/** 场景3 裁决结果对应的状态效果。 */
enum class Scene3Effect { DEAD, SEVERE_INJURY, NONE }

/**
 * §5.6：场景3 的 `decision.outcome` 只有 kill / spare / severe_injury 三种合法值。
 * 未知值（包括旧的 "dead"）按"不改变状态"处理，避免错误 token 被当成死亡。
 */
fun mapScene3Outcome(outcome: String?): Scene3Effect = when (outcome) {
    "kill" -> Scene3Effect.DEAD
    "severe_injury" -> Scene3Effect.SEVERE_INJURY
    else -> Scene3Effect.NONE
}
