package com.ei8z.endworld.data.model

/**
 * 生存状态快照，由 WorldStateProvider 从 SurvivalStateMachine 各子系统聚合生成。
 * 裁决Agent接收裁决前快照，叙事Agent接收裁决后快照。
 */
data class WorldSnapshot(
    // 时间
    val currentDateTime: String,
    val isNight: Boolean,
    val weather: String,              // "晴"|"阴"|"雨"|"暴风雨"|"辐射尘"
    val weatherDuration: Int,

    // 生命维持
    val hp: Int,
    val hpMax: Int,
    val sp: Int,
    val spMax: Int,
    val hunger: Int,                  // 0-100
    val thirst: Int,                  // 0-100
    val fatigue: Int,                 // 0-100

    // 状态标签
    val activeTags: List<InjuryTag>,
    val tagEffects: Map<String, String>,

    // 物资
    val inventory: List<InventoryItemModel>,
    val totalWeight: Float,
    val maxWeight: Float,
    val isOverEncumbered: Boolean,

    // 装备
    val equippedWeapon: String?,
    val equippedTool: String?,
    val damagedItems: List<String>,

    // 位置
    val location: String,
    val locationDescription: String,
    val locationType: String,         // "室内"|"室外"|"废墟"|"安全区"

    // 周边
    val nearbyThreats: List<String>,
    val nearbyNpcs: List<String>,
    val nearbyResources: List<String>,

    // 警告（WorldStateProvider 自动生成，如 HP<20% → "生命垂危"）
    val dangerWarnings: List<String>
)

/**
 * V2.1 物资物品模型。与 Room 实体 InventoryItem 不同，此模型使用强类型数值字段。
 * InventoryManager 使用此模型，syncGameData 负责向 Room 实体转换。
 */
data class InventoryItemModel(
    val id: String,
    val name: String,
    val category: String,             // "食物"|"饮品"|"武器"|"工具"|"医疗"|"特殊"
    // TODO: Phase 8 — Int 无法表示部分消耗（如"一瓶水喝了两口"）。
    //       当前以"份/次"为最小单位（一瓶水=4份），由裁决Agent自行量化。
    //       评估改为 Int+unit 方案（如 quantity=3, unit="口"）。
    val quantity: Int,
    val durability: Int? = null,      // null for consumables
    val nutrition: Int? = null,
    val hydration: Int? = null,
    val healingValue: Int? = null,
    val weight: Float,
    val tags: List<String> = emptyList()
)

/**
 * 伤病标签。裁决Agent仅返回名称字符串，客户端通过 InjuryLibrary（42种预设）查找完整对象。
 */
data class InjuryTag(
    val id: String,
    val name: String,
    val hpLossPerHour: Int = 0,
    val spPenalty: Float = 1.0f,      // 1.0=无惩罚, 1.2=SP消耗+20%
    val infectionRisk: Float = 0.0f,  // 累计感染概率 0-1，达到1.0视为"已感染"
    val naturalRecoveryHours: Int = 0,
    val requiredTreatment: String? = null,
    val narrativeHint: String = ""
)
