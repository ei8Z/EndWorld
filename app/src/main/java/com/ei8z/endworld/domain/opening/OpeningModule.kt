package com.ei8z.endworld.domain.opening

import com.ei8z.endworld.data.model.ActionParsed
import com.ei8z.endworld.data.repository.GameRepository

/**
 * 开局参数。由初始化界面收集的玩家选择。
 */
data class OpeningParams(
    val playerName: String,
    val genderPhysique: String,
    val personality: String,
    val adultProfile: String,
    val doomType: String,
    val doomLevel: Int // 1-5，玩家可见的末日烈度选择
)

/**
 * 开局结果。由开局模块产出，ViewModel 据此初始化房间 DB 并发送首个行动。
 */
data class OpeningResult(
    val initialAction: String,
    val initialActionParsed: ActionParsed,
    val initialHp: Int,
    val initialHpMax: Int,
    val initialSp: Int,
    val initialSpMax: Int,
    val initialHunger: Int = 50,
    val initialThirst: Int = 50,
    val initialFatigue: Int = 0
)

/**
 * 开局模块接口。每个实现对应一种开局体验。
 *
 * 设计意图：开局体验是游戏中最频繁迭代的部分。通过接口抽象，
 * 可以在不修改 ViewModel / GameRepository 核心管线的前提下，
 * 自由更换开局逻辑（经典开局 / 命运裁决 / 活动事件 / 测试满配等）。
 */
interface OpeningModule {
    /**
     * 执行开局准备。
     * - 基于 [params] 掷骰生成初始数值
     * - 调用 [repository.initializeNewGameV22] 写入 Room DB
     * - 构建首个行动 ActionParsed
     * - 返回 [OpeningResult] 供 ViewModel 调度主线流程
     */
    suspend fun prepare(repository: GameRepository, params: OpeningParams): OpeningResult
}
