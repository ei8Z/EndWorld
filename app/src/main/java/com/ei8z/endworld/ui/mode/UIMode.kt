package com.ei8z.endworld.ui.mode

/**
 * UI 视觉模式（文档 7.1）。
 *
 * 切换逻辑：
 * 探索 →（玩家点击选项）→ 命运 →（裁决完成）→ 阅读 →（叙事结束）→ 探索
 * 危险模式可叠加于探索/宁静之上，由 dangerWarnings 触发。
 * 宁静模式在安全区无威胁时替换探索模式。
 */
enum class UIMode {
    /** 默认状态，等待玩家输入。正常色调，选项可见。 */
    EXPLORE,
    /** 裁决Agent调用期间。屏幕边缘变暗，中央齿轮动画，心跳波纹。 */
    FATE,
    /** 叙事Agent流式输出期间。仿旧纸张背景，暖黄色调。 */
    READING,
    /** nearbyThreats 非空或 dangerWarnings 触发。屏幕边缘红色渐变，警示图标闪烁。 */
    DANGER,
    /** 安全区休息状态。柔和蓝绿色调，呼吸光晕动画。 */
    SERENE,
    /** V3.0 加速模式。时间快速流逝，时间流摘要面板替换suggestions。淡紫色调。 */
    ACCELERATING
}
