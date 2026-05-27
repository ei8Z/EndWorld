package com.ei8z.endworld.domain.state

import com.ei8z.endworld.data.model.EventCard

/**
 * 事件卡组。管理全部事件卡的筛选、冷却和一次性触发标记。
 *
 * 筛选逻辑（对应文档 3.2 裁决Agent输入 — 候选事件池）：
 * 1. 末日等级匹配：event.doomLevel == currentDoomLevel
 * 2. 未处于冷却期：cooldowns[eventId] == 0
 * 3. 未被标记为一次性已触发：eventId !in triggeredOneShot
 *
 * 裁决Agent从候选池中最终选择一个事件（或全不选），返回 EventCardRef。
 * 客户端根据 ref.id 查找完整 EventCard 并：
 * - 调用 markTriggered(id) 标记已触发
 * - 若 affectsState=true，应用 stateChanges
 *
 * 末日等级采用精确匹配，冷却在主流程每轮 processPreRuling 推进。
 */
class EventDeck(private val allCards: List<EventCard> = buildPresetCards()) {

    /** 剩余冷却回合数。key=eventId, value=剩余回合。0=不在冷却中 */
    private val cooldowns = mutableMapOf<String, Int>()

    /** 已触发的一次性事件 id 集合 */
    private val triggeredOneShot = mutableSetOf<String>()

    // ---- 候选筛选 ----

    /**
     * 筛选候选事件池（3-5个），供裁决Agent选择触发。
     *
     * @param doomLevel 当前末日等级
     * @return 符合条件的候选事件列表，最多 5 个。空列表表示无可用事件。
     */
    fun getCandidates(doomLevel: Int): List<EventCard> {
        return allCards
            .filter { it.doomLevel == doomLevel }
            .filter { (cooldowns[it.id] ?: 0) <= 0 }
            .filter { it.id !in triggeredOneShot }
            .shuffled()
            .take(5)
    }

    // ---- 触发管理 ----

    /**
     * 标记事件已触发。设置冷却（如有），记录一次性标记（如有）。
     */
    fun markTriggered(eventId: String) {
        val card = allCards.find { it.id == eventId } ?: return

        if (card.cooldownRounds > 0) {
            cooldowns[eventId] = card.cooldownRounds
        }
        if (card.oneTimeOnly) {
            triggeredOneShot.add(eventId)
        }
    }

    // ---- 冷却推进 ----

    /**
     * 每回合结束时调用，所有冷却计数 -1。
     */
    fun tickRound() {
        val toRemove = mutableListOf<String>()
        for ((id, remaining) in cooldowns) {
            val next = remaining - 1
            if (next <= 0) {
                toRemove.add(id)
            } else {
                cooldowns[id] = next
            }
        }
        toRemove.forEach { cooldowns.remove(it) }
    }

    // ---- 查询 ----

    /** 根据 id 查找完整事件卡 */
    fun getById(id: String): EventCard? = allCards.find { it.id == id }

    /** 全部事件卡数量 */
    val size: Int get() = allCards.size

    /** 当前冷却中的事件数量 */
    val cooldownCount: Int get() = cooldowns.count { it.value > 0 }

    /** 已触发的一次性事件数量 */
    val triggeredCount: Int get() = triggeredOneShot.size
}

/**
 * 构建预设事件卡库。≥20 张，覆盖环境/遭遇/资源/剧情四类 × 末日等级 1-5。
 *
 * TODO: 支持从 JSON 配置文件加载事件卡，便于运行时调整和扩展。
 */
fun buildPresetCards(): List<EventCard> = listOf(
    // ===== 环境类 =====
    EventCard(
        id = "toxic_spore", name = "毒孢子爆发",
        doomLevel = 1, category = "环境",
        narrativeHint = "空气中弥漫着绿色孢子，吸入后喉咙灼痛",
        cooldownRounds = 3
    ),
    EventCard(
        id = "acid_rain", name = "酸雨降临",
        doomLevel = 2, category = "环境",
        narrativeHint = "天空降下腐蚀性酸雨，露天行动将受到持续伤害",
        cooldownRounds = 4,
        affectsState = true,
        stateChanges = listOf(
            com.ei8z.endworld.data.model.ResourceChange("", "", "生命值", -5, 0, 100, "酸雨灼伤皮肤")
        )
    ),
    EventCard(
        id = "earthquake", name = "余震",
        doomLevel = 3, category = "环境",
        narrativeHint = "地面突然剧烈震动，建筑物发出不祥的呻吟声",
        cooldownRounds = 5
    ),
    EventCard(
        id = "rad_storm", name = "辐射风暴",
        doomLevel = 4, category = "环境",
        narrativeHint = "含有高剂量辐射的沙尘暴席卷而来，必须立即寻找掩体",
        cooldownRounds = 6,
        affectsState = true,
        stateChanges = listOf(
            com.ei8z.endworld.data.model.ResourceChange("", "", "生命值", -10, 0, 100, "辐射风暴灼伤")
        )
    ),
    EventCard(
        id = "black_rain", name = "黑雨",
        doomLevel = 5, category = "环境",
        narrativeHint = "漆黑的雨水从天而降，接触皮肤后留下灼烧般的水泡",
        cooldownRounds = 8
    ),
    EventCard(
        id = "dense_fog", name = "浓雾封锁",
        doomLevel = 1, category = "环境",
        narrativeHint = "乳白色的浓雾笼罩四周，能见度不足五米",
        cooldownRounds = 2
    ),

    // ===== 遭遇类 =====
    EventCard(
        id = "lone_scavenger", name = "独行拾荒者",
        doomLevel = 1, category = "遭遇",
        narrativeHint = "一个衣衫褴褛的拾荒者从废墟后探出头，手中紧握着一根铁管",
        cooldownRounds = 4
    ),
    EventCard(
        id = "feral_dogs", name = "野狗群",
        doomLevel = 2, category = "遭遇",
        narrativeHint = "两三只骨瘦如柴的野狗挡住去路，嘴角挂着涎水，低吼着缓缓逼近",
        cooldownRounds = 3
    ),
    EventCard(
        id = "raider_scout", name = "掠夺者侦察兵",
        doomLevel = 3, category = "遭遇",
        narrativeHint = "远处传来摩托引擎声——掠夺者的侦察兵正在巡逻",
        cooldownRounds = 5
    ),
    EventCard(
        id = "military_checkpoint", name = "军队检查站",
        doomLevel = 3, category = "遭遇",
        narrativeHint = "三名武装士兵正在盘查接近者，他们的眼神中写满了警惕和疲惫",
        cooldownRounds = 6
    ),
    EventCard(
        id = "mutant_pack", name = "变异体群",
        doomLevel = 4, category = "遭遇",
        narrativeHint = "数只皮肤溃烂的变异体从下水道井口爬出，它们的眼睛在黑暗中发出幽绿色荧光",
        cooldownRounds = 6
    ),
    EventCard(
        id = "death_squad", name = "清洗小队",
        doomLevel = 5, category = "遭遇",
        narrativeHint = "全副武装的清洗小队正在逐户搜查——他们接到的命令是'不留活口'",
        cooldownRounds = 10,
        oneTimeOnly = true
    ),
    EventCard(
        id = "wandering_trader", name = "流浪商人",
        doomLevel = 2, category = "遭遇",
        narrativeHint = "一个背着巨大行囊的老人友好地挥手，他的骡车上似乎有不少好东西",
        cooldownRounds = 8
    ),

    // ===== 资源类 =====
    EventCard(
        id = "abandoned_supplies", name = "遗弃物资",
        doomLevel = 1, category = "资源",
        narrativeHint = "路边躺着一个被匆忙遗弃的背包，上面覆满了灰尘",
        cooldownRounds = 3,
        oneTimeOnly = true
    ),
    EventCard(
        id = "water_source", name = "未污染水源",
        doomLevel = 2, category = "资源",
        narrativeHint = "一条清澈的小溪从废墟中穿过——在末日中这简直是神迹",
        cooldownRounds = 5,
        oneTimeOnly = true
    ),
    EventCard(
        id = "medical_cache", name = "医疗物资储藏点",
        doomLevel = 2, category = "资源",
        narrativeHint = "一间诊室的药柜里还有未开封的药品，瓶身上的标签已经泛黄",
        cooldownRounds = 6,
        oneTimeOnly = true
    ),
    EventCard(
        id = "fuel_depot", name = "废弃加油站",
        doomLevel = 3, category = "资源",
        narrativeHint = "加油站的储油罐似乎还有残留，但周围弥漫着浓重的汽油味——任何火星都可能是致命的",
        cooldownRounds = 5
    ),
    EventCard(
        id = "weapon_stash", name = "武器藏匿点",
        doomLevel = 4, category = "资源",
        narrativeHint = "一面松动的墙砖后藏着一个小型武器箱，里面有几发子弹和一把手枪",
        cooldownRounds = 8,
        oneTimeOnly = true
    ),
    EventCard(
        id = "emergency_broadcast", name = "应急广播信号",
        doomLevel = 3, category = "资源",
        narrativeHint = "收音机中突然传出微弱的应急广播，似乎在播报某个安全区的坐标",
        cooldownRounds = 10
    ),

    // ===== 剧情类 =====
    EventCard(
        id = "strange_signal", name = "奇怪信号",
        doomLevel = 1, category = "剧情",
        narrativeHint = "对讲机中传来断断续续的神秘信号，似乎有人在反复念诵一串数字",
        cooldownRounds = 5
    ),
    EventCard(
        id = "survivor_note", name = "幸存者笔记",
        doomLevel = 1, category = "剧情",
        narrativeHint = "地上散落着几页日记，记录着一个家庭从避难到绝望的最后时光",
        oneTimeOnly = true
    ),
    EventCard(
        id = "faction_conflict", name = "派系交火",
        doomLevel = 4, category = "剧情",
        narrativeHint = "远处传来密集的枪声和爆炸声——两个武装派系正在激烈交火",
        cooldownRounds = 7
    ),
    EventCard(
        id = "quarantine_zone", name = "隔离区封锁线",
        doomLevel = 5, category = "剧情",
        narrativeHint = "前方竖立着生物危害警示牌和铁丝网——这是军方划定的永久隔离区",
        cooldownRounds = 10,
        oneTimeOnly = true
    ),
    EventCard(
        id = "old_ally", name = "旧日同伴",
        doomLevel = 3, category = "剧情",
        narrativeHint = "一个似曾相识的身影出现在视线中——可能是灾难前认识的人",
        cooldownRounds = 8,
        oneTimeOnly = true
    ),
    EventCard(
        id = "child_cry", name = "孩童哭声",
        doomLevel = 2, category = "剧情",
        narrativeHint = "从一栋半塌的建筑中传出微弱的孩童哭声——是陷阱还是真实的呼救？",
        cooldownRounds = 6,
        oneTimeOnly = true
    ),
)
