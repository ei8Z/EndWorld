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
    val dangerWarnings: List<String>,

    // ===== V2.2 新增字段 =====

    // 技能：当前生效的技能效果汇总（key=applyTo，value=累计modifier）
    val activeSkillEffects: Map<String, Float> = emptyMap(),

    // 资源压力指标
    val foodHoursRemaining: Float = 0f,  // 当前食物够吃几小时
    val waterHoursRemaining: Float = 0f, // 当前水够喝几小时
    val medicalEmergency: Boolean = false, // 是否有需要紧急处理的伤病
    val totalThreatLevel: String = "警惕", // "安全"|"警惕"|"危险"|"绝境"
    val daysSurvived: Int = 1           // 已存活天数
)

/**
 * V2.1 物资物品模型。与 Room 实体 InventoryItem 不同，此模型使用强类型数值字段。
 * InventoryManager 使用此模型，syncGameData 负责向 Room 实体转换。
 */
data class InventoryItemModel(
    val id: String,
    val name: String,
    val category: String,             // "食物"|"饮品"|"武器"|"工具"|"医疗"|"特殊"
    val quantity: Int,
    val durability: Int? = null,      // null for consumables
    val nutrition: Int? = null,
    val hydration: Int? = null,
    val healingValue: Int? = null,
    val weight: Float,
    val unit: String = "份",
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

/**
 * 伤病进度状态。存储 tickHourly() 累计的运行期可变数据，
 * 与 InjuryLibrary 的预设默认值区分，用于跨重启恢复。
 */
data class InjuryProgress(
    val infectionRisk: Float,
    val naturalRecoveryHours: Int
)
