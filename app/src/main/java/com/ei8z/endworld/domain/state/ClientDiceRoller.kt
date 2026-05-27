package com.ei8z.endworld.domain.state

import kotlin.random.Random

/**
 * 客户端掷骰器。仅生成 1-100 的随机整数，不做任何修正或判定。
 * 所有 DC 计算和最终判定由裁决Agent完成。
 *
 * 独立模块设计便于测试时替换为固定值的 Mock 实现。
 */
object ClientDiceRoller {
    /** 掷骰，返回 1-100 的随机整数 */
    fun roll(): Int = Random.nextInt(1, 101)
}
