package com.ei8z.endworld.domain.opening

import com.ei8z.endworld.data.model.ActionParsed
import com.ei8z.endworld.data.model.GameConfig
import com.ei8z.endworld.data.repository.GameRepository
import com.ei8z.endworld.domain.state.ClientDiceRoller

/**
 * V2.2 "命运启示录" 开局模块。
 *
 * 流程：
 * 1. 从 [GameConfig] 读取性格修正，ClientDiceRoller 掷 HP/SP 上限
 * 2. 将掷骰结果写入 Room DB（含数值字段，初始值从 config 读取）
 * 3. 返回 OpeningResult 供 ViewModel 触发主线双Agent流程
 *
 * 性格修正由 game_config.json.personalities 驱动，无硬编码。
 */
class FateRevelationOpening(
    private val gameConfig: GameConfig
) : OpeningModule {

    override suspend fun prepare(repository: GameRepository, params: OpeningParams): OpeningResult {
        val stats = gameConfig.initialStats

        // 1. 命运掷骰
        val (hpRoll, spRoll) = rollInitialStats(params.personality)
        val hpMax = hpRoll.coerceIn(stats.statCoerceMin, stats.statCoerceMax)
        val spMax = spRoll.coerceIn(stats.statCoerceMin, stats.statCoerceMax)

        // 2. 写入 Room DB
        repository.initializeNewGameV22(
            playerName = params.playerName,
            genderPhysique = params.genderPhysique,
            personality = params.personality,
            adultProfile = params.adultProfile,
            doomType = params.doomType,
            doomLevel = params.doomLevel,
            hp = hpMax, hpMax = hpMax,
            sp = spMax, spMax = spMax,
            hunger = stats.hungerDefault,
            thirst = stats.thirstDefault,
            fatigue = stats.fatigueDefault
        )

        // 3. 返回统计结果（开场行动由 PlotManager 从章节 JSON 生成）
        return OpeningResult(
            initialAction = "", initialActionParsed = ActionParsed.placeholder(),
            initialHp = hpMax, initialHpMax = hpMax,
            initialSp = spMax, initialSpMax = spMax
        )
    }

    private fun rollInitialStats(personality: String): Pair<Int, Int> {
        val baseHp = ClientDiceRoller.roll()
        val baseSp = ClientDiceRoller.roll()
        val match = gameConfig.personalities.find { it.label == personality }
            ?: gameConfig.personalities.find { personality.contains(it.label) }
            ?: return Pair(baseHp, baseSp)
        return Pair(baseHp + match.hpMod, baseSp + match.spMod)
    }
}
