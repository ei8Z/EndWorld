package com.ei8z.endworld.domain.state

import com.ei8z.endworld.data.model.SkillTag

/**
 * V2.2 技能管理器。管理玩家技能标签的获取、升级和效果计算。
 * 作为 SurvivalStateMachine 的子模块使用。
 */
class SkillManager {
    private val skills = mutableMapOf<String, Int>() // skillId -> level

    /** 获取当前所有技能（含等级） */
    fun getAll(): List<SkillTag> {
        return skills.map { (id, level) ->
            SkillLibrary.atLevel(SkillLibrary.get(id) ?: return@map null, level)
        }.filterNotNull()
    }

    /** 获取当前生效的技能效果汇总 */
    fun getActiveEffects(): Map<String, Float> {
        return SkillLibrary.aggregateEffects(getAll())
    }

    /** 添加或升级技能 */
    fun addOrUpgrade(skillId: String, level: Int = 1) {
        val currentLevel = skills[skillId] ?: 0
        skills[skillId] = maxOf(currentLevel, level).coerceIn(1, 5)
    }

    /** 通过技能ID列表初始化（从持久化恢复） */
    fun seed(skillIds: List<String>) {
        skills.clear()
        skillIds.forEach { id ->
            skills[id] = (skills[id] ?: 0) + 1
        }
        // 修正：seed时每个ID只出现一次，应取Lv.1
        skillIds.forEach { id ->
            skills[id] = 1
        }
    }

    /** 获取技能ID列表（用于持久化） */
    fun getSkillIds(): List<String> = skills.keys.toList()

    /** 是否拥有指定技能 */
    fun hasSkill(skillId: String): Boolean = skills.containsKey(skillId)

    /** 获取指定技能等级 */
    fun getLevel(skillId: String): Int = skills[skillId] ?: 0
}
