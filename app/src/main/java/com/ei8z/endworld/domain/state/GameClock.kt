package com.ei8z.endworld.domain.state

/**
 * 游戏内时钟。以分钟为最小推进单位，负责时间推进、昼夜判定和位置管理。
 *
 * 初始时间：第1天 06:00
 * 夜间定义：20:00（含）至次日 06:00（不含）
 *
 * TODO: Phase 8 主线串通时，确认时间格式化与叙事Agent header.date/time 的对齐方式。
 */
class GameClock {

    /** 游戏开始以来的累计分钟数，初始=360（第1天 06:00） */
    private var totalMinutes: Int = 360

    /** 当前位置 */
    var location: String = "避难所"
        private set

    /** 位置类型 */
    var locationType: String = "室内"
        private set

    /** 位置详细描述 */
    var locationDescription: String = "简陋的地下避难所，铁门在寒风中嘎吱作响"
        private set

    // ---- 派生属性 ----

    val currentDay: Int get() = totalMinutes / 1440 + 1
    val currentHour: Int get() = (totalMinutes % 1440) / 60
    val currentMinute: Int get() = totalMinutes % 60

    /** 格式：第N天 HH:MM */
    val currentDateTime: String
        get() = "第${currentDay}天 ${String.format("%02d:%02d", currentHour, currentMinute)}"

    /** 20:00（含）至次日 06:00（不含）为夜间 */
    val isNight: Boolean
        get() = currentHour >= 20 || currentHour < 6

    // ---- 操作 ----

    /**
     * 推进游戏时间 [minutes] 分钟。
     */
    fun advance(minutes: Int) {
        require(minutes >= 0) { "时间推进量不能为负数: $minutes" }
        totalMinutes += minutes
    }

    /**
     * 推进游戏时间 [hours] 小时（浮点数，用于被动消耗等场景）。
     */
    fun advanceHours(hours: Float) {
        require(hours >= 0f) { "时间推进量不能为负数: $hours" }
        totalMinutes += (hours * 60).toInt()
    }

    /**
     * 更新当前位置信息。
     */
    fun updateLocation(location: String, type: String, description: String) {
        this.location = location
        this.locationType = type
        this.locationDescription = description
    }
}
