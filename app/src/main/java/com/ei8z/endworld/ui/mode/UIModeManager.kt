package com.ei8z.endworld.ui.mode

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI 模式管理器。维护当前视觉模式，提供模式切换方法。
 *
 * 模式切换规则：
 * - 命运模式覆盖探索模式（主线行动触发）
 * - 阅读模式覆盖命运模式（裁决完成后）
 * - 探索模式在叙事结束后恢复
 * - 危险模式可叠加覆盖探索/宁静（威胁靠近）
 * - 宁静模式在安全区无威胁时替换探索
 * - 玩家点击选项时强制进入命运模式
 */
class UIModeManager {

    private val _currentMode = MutableStateFlow(UIMode.EXPLORE)
    val currentMode: StateFlow<UIMode> = _currentMode.asStateFlow()

    /** 进入命运模式（裁决Agent调用中） */
    fun enterFate() {
        _currentMode.value = UIMode.FATE
    }

    /** 进入阅读模式（叙事Agent流式输出中） */
    fun enterReading() {
        _currentMode.value = UIMode.READING
    }

    /** V3.0 加速模式：时间流摘要面板替换 suggestions */
    fun enterAccelerating() {
        _currentMode.value = UIMode.ACCELERATING
    }

    /** 退出加速：按当前世界状态恢复到探索/危险/宁静 */
    fun restoreFromAcceleration(hasThreats: Boolean, isSafeZone: Boolean) {
        settleMode(hasThreats = hasThreats, isSafeZone = isSafeZone)
    }

    /** 叙事结束，根据当前状态恢复到探索/危险/宁静 */
    fun settleMode(
        hasThreats: Boolean,
        isSafeZone: Boolean
    ) {
        _currentMode.value = when {
            hasThreats -> UIMode.DANGER
            isSafeZone -> UIMode.SERENE
            else -> UIMode.EXPLORE
        }
    }

    /**
     * 根据世界状态更新模式（不覆盖命运/阅读——它们由流程事件控制）。
     * 仅在探索/危险/宁静模式间切换。
     */
    fun updateFromWorld(hasThreats: Boolean, isSafeZone: Boolean) {
        val current = _currentMode.value
        // 命运/阅读模式由流程事件控制，不受世界状态影响
        if (current == UIMode.FATE || current == UIMode.READING) return

        _currentMode.value = when {
            hasThreats -> UIMode.DANGER
            isSafeZone -> UIMode.SERENE
            else -> UIMode.EXPLORE
        }
    }

    // ---- 音效触发占位 ----

    /**
     * 获取当前模式对应的音效标识。
     * TODO: Phase 11 接入实际音频播放器（MediaPlayer/SoundPool）。
     */
    fun getAudioHint(): String = when (_currentMode.value) {
        UIMode.EXPLORE -> "ambient_white_noise"
        UIMode.FATE -> "heartbeat_low_hum"
        UIMode.READING -> "ambient_wind_rain"
        UIMode.DANGER -> "rapid_heartbeat_metal_scrape"
        UIMode.SERENE -> "soft_piano_chime"
        UIMode.ACCELERATING -> "clock_ticking_fast"
    }
}
