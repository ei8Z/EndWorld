package com.ei8z.endworld.domain.npc.model

/**
 * NPC 三层结构 + 核心属性
 * 对应文档：NPC自主行为系统 §2-3
 */

// === 三层结构 ===
enum class NpcTier { BACKGROUND, CONTACT, KEY }

// === 基础信息 ===
data class NpcState(
    val npcId: String,
    val name: String,
    val identity: String,               // "哨卡士兵", "流浪商人", "势力领袖"
    val combatRole: CombatRole,
    val currentLocationId: String,      // 当前所在的Location ID
    val factionId: String?,             // 所属势力ID（null=无势力，如流浪者）
    val personalityTags: List<String>,  // "谨慎", "慷慨", "多疑", "勇敢"
    val statusTags: MutableList<String>,// "受伤", "饥饿", "士气低落"
    val tier: NpcTier = NpcTier.CONTACT
)

enum class CombatRole(val deathProbabilityModifier: Float, val countsTowardMilitary: Boolean) {
    COMBATANT(1.0f, true),        // 战斗人员（哨兵/士兵/守卫）
    NON_COMBATANT(0.3f, false),   // 非战斗人员（平民/商人/工匠）
    LEADER(1.0f, true)            // 领袖（势力领袖/关键决策者）
}

// === 情感系统（好感度 -100~+100） ===
data class AffinityState(
    var affinity: Int,                    // -100 ~ +100（可变——继承时转移）
    var relationshipStage: RelationshipStage, // 可变——好感度变化时更新
    val emotionalTags: MutableList<String>, // "感激", "失望", "亏欠", "敬佩", "恐惧" (最多3个)
    var interactionCount: Int,
    var lastInteractionGameDay: Int,
    val stageCrossingHistory: MutableList<StageCrossingEvent>
)

enum class RelationshipStage {
    HATRED,       // -100 ~ -60  仇恨
    HOSTILITY,    // -60 ~ -20   敌意
    NEUTRAL,      // -20 ~ +20   中立/陌生
    FAMILIAR,     // +20 ~ +50   熟悉
    TRUST,        // +50 ~ +80   信任
    INTIMATE      // +80 ~ +100  亲密
}

data class StageCrossingEvent(
    val gameDay: Int,
    val fromStage: RelationshipStage,
    val toStage: RelationshipStage,
    val affinityAtCrossing: Int,
    val reason: String
)

/** 对外展示与提示词使用的中文阶段名，必须与 LightweightAIValidator.validRelationshipStages 一致。 */
fun RelationshipStage.toDisplayName(): String = when (this) {
    RelationshipStage.HATRED -> "仇恨"
    RelationshipStage.HOSTILITY -> "敌意"
    RelationshipStage.NEUTRAL -> "中立或陌生"
    RelationshipStage.FAMILIAR -> "熟悉"
    RelationshipStage.TRUST -> "信任"
    RelationshipStage.INTIMATE -> "亲密"
}

fun Int.toRelationshipStage(): RelationshipStage = when {
    this < -60 -> RelationshipStage.HATRED
    this < -20 -> RelationshipStage.HOSTILITY
    this < 20 -> RelationshipStage.NEUTRAL
    this < 50 -> RelationshipStage.FAMILIAR
    this < 80 -> RelationshipStage.TRUST
    else -> RelationshipStage.INTIMATE
}

// === 需求系统 ===
data class NeedState(
    val foodUrgency: Int = 30 + (0..40).random(),
    val waterUrgency: Int = 30 + (0..40).random(),
    val medicalUrgency: Int = 0,
    val safetyUrgency: Int = 30 + (0..40).random(),
    val socialUrgency: Int = 30 + (0..40).random(),
    val selfActualizationUrgency: Int = 0,
    val consecutiveSaturatedFoodDays: Int = 0,  // 食物需求连续饱和天数
    val consecutiveSaturatedWaterDays: Int = 0,  // 饮水需求连续饱和天数
    val consecutiveSaturatedMedicalDays: Int = 0  // 医疗需求连续饱和天数
) {
    fun dominantNeed(): NeedType {
        return mapOf(
            NeedType.FOOD to foodUrgency,
            NeedType.WATER to waterUrgency,
            NeedType.MEDICAL to medicalUrgency,
            NeedType.SAFETY to safetyUrgency,
            NeedType.SOCIAL to socialUrgency,
            NeedType.SELF_ACTUALIZATION to selfActualizationUrgency
        ).maxBy { it.value }.key
    }
}

enum class NeedType(val dailyIncrement: Int) {
    FOOD(120),
    WATER(192),
    MEDICAL(360), // 受伤时
    SAFETY(240),  // 区域威胁>50时
    SOCIAL(72),   // 生存/安全满足时
    SELF_ACTUALIZATION(24) // 其他需求满足时
}

// === 记忆系统 ===
data class MemoryQueue(
    val detailedMemories: MutableList<MemoryEntry>,  // 最近N条详细记忆
    var remoteSummary: String = "",                   // 远期摘要（可变——压缩时更新）
    val capacity: Int = 6                             // 接触NPC=6, 关键NPC=10
)

data class MemoryEntry(
    val timestamp: String,       // "[Day 5 下午]"
    val location: String,
    val summary: String,         // 一句话描述
    val affinityChange: Int = 0,
    val emotionalTag: String? = null,
    val source: MemorySource
)

enum class MemorySource {
    PLAYER_INTERACTION,
    NPC_SOCIAL_SNAPSHOT,
    ENVIRONMENT,
    MEMOS_EXTRACTED
}

// === 时间表 ===
data class Schedule(
    val timeSlots: Map<TimeSlot, String>,  // 时间段 -> 位置ID
    val overriddenBy: String? = null,      // 当前被什么覆盖
    val overrideUntilDay: Int? = null
)

enum class TimeSlot(val label: String, val startHour: Int, val endHour: Int) {
    DAWN("清晨", 5, 8),
    MORNING("上午", 8, 12),
    AFTERNOON("下午", 12, 17),
    EVENING("傍晚", 17, 20),
    NIGHT("夜晚", 20, 5)
}

// === 离开状态 ===
enum class DepartureStatus {
    PRESENT,              // 在场
    EXPLORING,            // 外出探索
    MISSING,              // 失踪（超时未归）
    MIGRATED,             // 已迁移
    DEAD,                 // 已死亡
    CAPTURED,             // 被俘
    DEMOTED               // 降级为背景NPC
}

data class DepartureInfo(
    val status: DepartureStatus = DepartureStatus.PRESENT,
    val reason: String = "",
    val expectedReturnDay: Int? = null,    // 预期归来游戏日
    val targetRegionId: String? = null,    // 目标区域
    val newFactionId: String? = null       // 新势力（若加入其他势力）
)
