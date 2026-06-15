package com.ei8z.endworld.domain.world.model

/**
 * 势力 —— 世界中的主要行为体
 * 对应文档：世界规则引擎 §2.2
 */
data class Faction(
    val factionId: String,
    val name: String,
    val factionType: FactionType,
    val population: Int,                  // 势力总人数
    var militaryStrength: Int,            // 兵力（可变——战斗损失）
    val armamentRate: Float,              // 兵力/人口 (0.0-1.0)
    val unitCombatCoefficient: Float,     // 单位战力系数
    val controlledRegions: MutableList<String>,
    val relationshipMatrix: MutableMap<String, FactionRelation>, // 势力ID -> 关系
    val leaderPersonality: LeaderPersonality,
    var factionGoal: FactionGoal,         // 可变——AI评估可能改变
    val stateTags: MutableList<String>,   // "内部动荡", "食物短缺" 等
    var resources: FactionResources       // 可变——每日更新
)

data class FactionResources(
    val foodReserve: Float,         // 食物储备（人口天）
    val waterReserve: Float,        // 水资源（人口天）
    val medicalSupplies: Float,     // 医疗物资（人口天等效）
    val ammunitionReserve: Float,   // 弹药储备（可支持战斗次数）
    val morale: Int                 // 0-100
)

enum class FactionType {
    MILITARY_ORGANIZATION,   // 军事组织
    SURVIVOR_SETTLEMENT,     // 幸存者聚居地
    BANDIT_GANG,             // 匪帮
    WANDERING_GROUP,         // 游荡群体
    OFFICIAL_REMNANT         // 官方残余
}

enum class FactionRelation {
    ALLIANCE,       // 同盟
    NEUTRAL,        // 中立
    HOSTILE,        // 敌对
    SEEKING_AID     // 求援中
}

enum class LeaderPersonality(val conflictModifier: Float) {
    CAUTIOUS_CONSERVATIVE(0.5f),  // 谨慎保守
    AGGRESSIVE_EXPANSION(1.5f),  // 激进扩张
    DIPLOMACY_FIRST(0.4f),       // 外交优先
    SELF_PRESERVATION(0.2f),     // 自保为主
    NORMAL(1.0f)                 // 正常
}

enum class FactionGoal(val conflictModifier: Float) {
    EXPANSION(1.5f),        // 扩张
    DEFENSE(0.8f),          // 防守
    EVACUATION(0.2f),       // 撤离
    SEEKING_ALLIANCE(0.3f)  // 寻求同盟
}
