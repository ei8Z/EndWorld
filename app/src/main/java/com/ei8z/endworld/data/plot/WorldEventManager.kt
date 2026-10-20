package com.ei8z.endworld.data.plot

import com.ei8z.endworld.data.model.WorldEvent
import com.ei8z.endworld.data.model.WorldEventEffect

/**
 * V2.2 世界进程事件管理器。按游戏天数触发全局事件。
 * 塑造"世界正在死去"的进程感。
 */
class WorldEventManager {

    /** 已触发的事件ID集合 */
    private val triggeredEvents = mutableSetOf<String>()

    /** 预设事件列表（按V2.2 §6.2） */
    val allEvents: List<WorldEvent> = buildPresetEvents()

    /**
     * 检查是否有待触发的全局事件。
     * 语义为"已到天数且未触发"：跨天/加速跳过的天数不会漏事件，同一事件只触发一次。
     * @param daysSurvived 当前存活天数
     * @param doomLevel 当前末日等级
     * @return 本轮触发的新事件列表（按 triggerDay 升序）
     */
    fun checkAndTrigger(daysSurvived: Int, doomLevel: Int): List<WorldEvent> {
        val matched = allEvents
            .filter { event ->
                event.triggerDay <= daysSurvived &&
                    doomLevel >= event.doomLevelRequired &&
                    event.id !in triggeredEvents
            }
            .sortedBy { it.triggerDay }
        matched.forEach { triggeredEvents.add(it.id) }
        return matched
    }

    /** 获取已触发事件的世界描述文本（用于注入PlotContext.worldContext） */
    fun getActiveWorldContext(daysSurvived: Int): String {
        val triggered = allEvents.filter { it.id in triggeredEvents }
        if (triggered.isEmpty()) return ""
        return triggered.joinToString("; ") { event ->
            "${event.name}（第${event.triggerDay}天）：${event.narrativeBroadcast}"
        }
    }

    /** 从持久化恢复已触发事件 */
    fun seed(previouslyTriggered: List<String>) {
        triggeredEvents.clear()
        triggeredEvents.addAll(previouslyTriggered)
    }

    /** 获取已触发事件ID列表（用于持久化） */
    fun getTriggeredEventIds(): List<String> = triggeredEvents.toList()

    /** 重置（新游戏） */
    fun reset() {
        triggeredEvents.clear()
    }

    companion object {
        fun buildPresetEvents(): List<WorldEvent> = listOf(
            WorldEvent(
                id = "last_broadcast",
                name = "最后广播",
                triggerDay = 1,
                doomLevelRequired = 1,
                description = "末日等级确定，世界背景确立",
                effects = emptyList(),
                narrativeBroadcast = "收音机里播报了最后一条紧急广播后，只剩下沙沙的电流声。文明社会，正式宣告终结。"
            ),
            WorldEvent(
                id = "blackout",
                name = "大规模断电",
                triggerDay = 3,
                doomLevelRequired = 1,
                description = "所有电器失效，夜间搜索DC+20%",
                effects = listOf(WorldEventEffect("dc_modifier", "搜索", 0.20f)),
                narrativeBroadcast = "电力系统彻底崩溃。从今天起，夜晚不再有灯光，黑暗成了最危险的猎手。"
            ),
            WorldEvent(
                id = "horde_warning",
                name = "尸潮预警",
                triggerDay = 5,
                doomLevelRequired = 2,
                description = "未来3天内尸潮概率+30%",
                effects = listOf(WorldEventEffect("dc_modifier", "移动", 0.10f)),
                narrativeBroadcast = "远处传来异样的低吼声——数量不少，而且越来越近。"
            ),
            WorldEvent(
                id = "military_retreat",
                name = "军队撤离",
                triggerDay = 7,
                doomLevelRequired = 2,
                description = "安全区消失，物资点被军方清空",
                effects = listOf(WorldEventEffect("resource_scarcity", "食物", 0.30f)),
                narrativeBroadcast = "最后一支军队的引擎声消失在地平线。他们走了，留下了我们。安全区的概念从此不复存在。"
            ),
            WorldEvent(
                id = "survivor_broadcast",
                name = "幸存者广播",
                triggerDay = 10,
                doomLevelRequired = 2,
                description = "出现新的NPC聚集点",
                effects = emptyList(),
                narrativeBroadcast = "无线电里突然传来一个陌生的声音：'这里是绿洲营地……我们还有食物和药品……重复……'"
            ),
            WorldEvent(
                id = "plague_worsening",
                name = "疫情恶化",
                triggerDay = 14,
                doomLevelRequired = 3,
                description = "所有伤病感染概率+15%",
                effects = listOf(WorldEventEffect("dc_modifier", "医疗", 0.10f)),
                narrativeBroadcast = "尸体腐烂的速度比预想的快得多。空气中的病菌浓度急剧上升，每一道伤口都可能致命。"
            ),
            WorldEvent(
                id = "first_cold_wave",
                name = "第一次大寒流",
                triggerDay = 21,
                doomLevelRequired = 3,
                description = "室外行动SP消耗+50%，夜间DC+30%",
                effects = listOf(WorldEventEffect("dc_modifier", "移动", 0.30f)),
                narrativeBroadcast = "刺骨的寒风一夜之间席卷了整片废墟。在室外多待一分钟都是对生命的透支。"
            ),
            WorldEvent(
                id = "silence_of_civilization",
                name = "文明静默",
                triggerDay = 30,
                doomLevelRequired = 4,
                description = "末日等级强制升至最高，所有旧秩序痕迹消失",
                effects = listOf(WorldEventEffect("doom_level_change", "末日等级", 5f)),
                narrativeBroadcast = "三十天。世界彻底静默了。没有广播、没有信号、没有救援队。从今天起，这就是我们的世界。旧的文明，已成传说。"
            )
        )
    }
}
