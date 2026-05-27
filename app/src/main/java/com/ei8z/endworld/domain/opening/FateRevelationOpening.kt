package com.ei8z.endworld.domain.opening

import com.ei8z.endworld.data.model.ActionParsed
import com.ei8z.endworld.data.repository.GameRepository
import com.ei8z.endworld.domain.state.ClientDiceRoller
import kotlin.random.Random

/**
 * V2.2 "命运启示录" 开局模块。
 *
 * 流程：
 * 1. ClientDiceRoller 掷 HP/SP 上限（1-100，性格提供修正）
 * 2. 将掷骰结果写入 Room DB（含数值字段）
 * 3. 构建"开局突围" ActionParsed（risk="极高"）
 * 4. 返回 OpeningResult 供 ViewModel 触发主线双Agent流程
 *
 * 性格修正表：
 * - 强壮/军人/战士 → HP +10
 * - 敏捷/跑者/猎人 → SP +10
 * - 坚韧/生存者 → HP +5, SP +5
 * - 其他 → 无修正
 */
class FateRevelationOpening : OpeningModule {

    override suspend fun prepare(repository: GameRepository, params: OpeningParams): OpeningResult {
        // 1. 命运掷骰
        val (hpRoll, spRoll) = rollInitialStats(params.personality)
        val hpMax = hpRoll.coerceIn(50, 120)
        val spMax = spRoll.coerceIn(50, 120)

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
            hunger = 50, thirst = 50, fatigue = 0
        )

        // 3. 开局突围 ActionParsed
        val action = buildInitialAction(params.doomLevel)

        return OpeningResult(
            initialAction = action.first,
            initialActionParsed = action.second,
            initialHp = hpMax, initialHpMax = hpMax,
            initialSp = spMax, initialSpMax = spMax
        )
    }

    /**
     * 性格加权掷骰。
     * 基础值：d100（1-100），性格修正叠加。
     */
    private fun rollInitialStats(personality: String): Pair<Int, Int> {
        val baseHp = ClientDiceRoller.roll()
        val baseSp = ClientDiceRoller.roll()
        val (hpMod, spMod) = personalityModifier(personality)
        return Pair(baseHp + hpMod, baseSp + spMod)
    }

    private fun personalityModifier(personality: String): Pair<Int, Int> {
        val p = personality.lowercase()
        return when {
            p.contains("强壮") || p.contains("军人") || p.contains("战士") -> Pair(10, 0)
            p.contains("敏捷") || p.contains("跑者") || p.contains("猎人") -> Pair(0, 10)
            p.contains("坚韧") || p.contains("生存") -> Pair(5, 5)
            else -> Pair(0, 0)
        }
    }

    /**
     * 构建开局突袭行动。
     * 末日烈度越高，风险描述越极端。
     */
    private fun buildInitialAction(doomLevel: Int): Pair<String, ActionParsed> {
        val scenario = when (doomLevel) {
            1 -> "外面传来零散的警笛声，似乎局势尚可控制。"
            2 -> "街道上人群四散奔逃，混乱已经开始蔓延。"
            3 -> "爆炸声从远处传来，浓烟遮蔽了半边天空。"
            4 -> "整栋建筑都在震动，尖叫声此起彼伏，末日已至。"
            5 -> "大地裂开，火光冲天，这是人类文明的最后时刻。"
            else -> "外面传来零散的警笛声，似乎局势尚可控制。"
        }

        val label = when {
            doomLevel <= 2 -> "你深吸一口气，推开避难所的厚重铁门，试图弄清外面发生了什么。"
            doomLevel <= 4 -> "铁门刚推开一条缝，一股热浪和尖叫声便扑面而来——你必须立刻决定突围方向。"
            else -> "铁门外只有火光与嘶吼。你抓起仅有的物品，准备冲向这片炼狱。"
        }

        val actionParsed = ActionParsed(
            primaryType = "移动",
            subType = "开局突围",
            target = "安全地带",
            location = "避难所出口",
            method = "冲出避难所，寻找生路",
            riskLevel = if (doomLevel >= 4) "极高" else "高",
            estimatedTimeMinutes = if (doomLevel >= 4) 30 else 20
        )

        return Pair(
            "$label $scenario",
            actionParsed
        )
    }
}
