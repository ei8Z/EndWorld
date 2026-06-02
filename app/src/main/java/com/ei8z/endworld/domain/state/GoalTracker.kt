package com.ei8z.endworld.domain.state

import com.ei8z.endworld.data.model.PersonalGoal

/**
 * V2.2 目标追踪器。管理活跃目标的生命周期、进度更新和完成验证。
 * 作为 SurvivalStateMachine 的子模块使用，客户端是目标的唯一权威源。
 */
class GoalTracker {
    private val activeGoals = mutableMapOf<String, PersonalGoal>()
    private val completedGoals = mutableSetOf<String>()

    /** 获取所有活跃目标 */
    fun getActive(): List<PersonalGoal> = activeGoals.values.toList()

    /** 获取活跃目标ID列表 */
    fun getActiveIds(): List<String> = activeGoals.keys.toList()

    /** 添加目标（客户端验证后调用，重复ID自动忽略，最多3个活跃） */
    fun addGoals(goals: List<PersonalGoal>) {
        goals.forEach { goal ->
            if (activeGoals.size < 3 && goal.id !in activeGoals && goal.id !in completedGoals) {
                activeGoals[goal.id] = goal
            }
        }
    }

    /** 手动添加单个目标 */
    fun addGoal(goal: PersonalGoal): Boolean {
        if (activeGoals.size >= 3 || goal.id in activeGoals || goal.id in completedGoals) return false
        activeGoals[goal.id] = goal
        return true
    }

    /** 完成目标（移入已完成列表） */
    fun completeGoal(goalId: String) {
        activeGoals.remove(goalId)
        completedGoals.add(goalId)
    }

    /** 更新目标进度 */
    fun updateProgress(updates: Map<String, Float>) {
        updates.forEach { (id, progress) ->
            activeGoals[id]?.let { goal ->
                val newProgress = progress.coerceIn(0f, 1f)
                activeGoals[id] = goal.copy(progress = newProgress)
                if (newProgress >= 1f) {
                    completeGoal(id)
                }
            }
        }
    }

    /** 放弃目标 */
    fun abandonGoal(goalId: String) {
        activeGoals.remove(goalId)
    }

    /** 重置（新游戏） */
    fun reset() {
        activeGoals.clear()
        completedGoals.clear()
    }
}
