package com.ei8z.endworld.domain.state

/**
 * 支线关键字分类器。将玩家自由文本输入分类为 6 种支线类型。
 *
 * 分类依据（文档 6.1）：
 * - 观察: 看/观察/打量/环顾/查看 → Storyteller
 * - 闲聊: 说/问/聊/告诉/询问 → Storyteller
 * - 小操作: 喝/吃/检查/整理/装备 → 客户端直接执行（无 Storyteller）
 * - 移动: 走/去/前往（短距离） → Storyteller
 * - 等待: 等/等待/待着/休息 → 客户端直接执行（无 Storyteller）
 * - 其他: 无法匹配 → Storyteller
 *
 * TODO: Phase 11 根据实际使用数据调整关键词库，目标准确率≥80%。
 */
object BranchClassifier {

    /** 支线类型枚举 */
    enum class Type(val label: String, val needsStoryteller: Boolean, val defaultMinutes: Int, val spCost: Int) {
        OBSERVE("观察", true, 8, -2),
        CHAT("闲聊", true, 10, -2),
        SMALL_ACTION("小操作", false, 3, 0),
        MOVE("移动", true, 10, -3),
        WAIT("等待", false, 20, 5),
        OTHER("其他", true, 5, 0)
    }

    /** 各类型触发关键词 */
    private val keywords: Map<Type, List<String>> = mapOf(
        Type.OBSERVE to listOf("看", "观察", "打量", "环顾", "查看", "看看", "望", "注视", "扫视"),
        Type.CHAT to listOf("说", "问", "聊", "告诉", "询问", "回答", "交谈", "对话", "打招呼"),
        Type.SMALL_ACTION to listOf("喝", "吃", "检查", "整理", "装备", "打开", "关上", "拿起", "放下", "穿上", "脱下"),
        Type.MOVE to listOf("走", "去", "前往", "移动", "跑", "前进", "后退", "离开", "进入", "爬", "翻过"),
        Type.WAIT to listOf("等", "等待", "待着", "休息", "歇", "停留", "蹲下")
    )

    /**
     * 分类自由文本。按优先级匹配：小操作 > 移动 > 等待 > 观察 > 闲聊 > 其他。
     * "小操作"和"移动"优先级较高，因为它们的触发词容易与其他类型重叠。
     *
     * @return 匹配的支线类型
     */
    fun classify(input: String): Type {
        if (input.isBlank()) return Type.OTHER
        val text = input.trim()

        // 按优先级依次匹配
        val priorityOrder = listOf(Type.SMALL_ACTION, Type.MOVE, Type.WAIT, Type.OBSERVE, Type.CHAT)
        for (type in priorityOrder) {
            val triggers = keywords[type] ?: continue
            if (triggers.any { text.contains(it) }) {
                return type
            }
        }
        return Type.OTHER
    }
}
