package com.ei8z.endworld.data.model

/**
 * V2.2 动态个人目标。为玩家提供短期驱动力。
 */
data class PersonalGoal(
    val id: String,
    val name: String,                    // 如"治好感染"
    val description: String,             // 目标描述
    val type: String,                    // "生存" | "关系" | "探索" | "复仇" | "建造"
    val progress: Float,                 // 0.0-1.0
    val milestones: List<GoalMilestone>, // 里程碑列表
    val reward: GoalReward,              // 完成奖励
    val deadline: String? = null,        // 时间限制描述
    val isActive: Boolean = true,        // 是否当前活跃
    val narrativeHook: String            // AI引导玩家追求目标的叙事提示
)

data class GoalMilestone(
    val description: String,             // 如"找到抗生素"
    val progressContribution: Float,     // 完成后贡献的进度(0-1)
    val completionCondition: String      // 完成条件描述
)

data class GoalReward(
    val skillUnlock: String? = null,     // 解锁的技能ID
    val itemReward: String? = null,      // 奖励物品
    val plotUnlock: String? = null,      // 解锁的剧情节点
    val description: String              // 奖励描述
)
