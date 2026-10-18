package com.ei8z.endworld.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 场景7（时间表异常）的持久化载体。
 *
 * [timeSlotsJson] 是 `Map<TimeSlot, locationId>` 的 Gson 序列化（键为枚举名）。
 * [overriddenBy] / [overrideUntilDay] 表示时间表被剧情或事件覆盖的来源与到期游戏日。
 * [anomalySinceDay] 是场景7 首次位置不符的游戏日（null = 当前无异常），用于去重与持续天数。
 */
@Entity(tableName = "npc_schedules")
data class NpcScheduleEntity(
    @PrimaryKey val npcId: String,
    val timeSlotsJson: String,
    val overriddenBy: String? = null,
    val overrideUntilDay: Int? = null,
    val anomalySinceDay: Int? = null
)
