package com.ei8z.endworld.domain.state

import com.ei8z.endworld.data.model.SkillEffect
import com.ei8z.endworld.data.model.SkillTag

/**
 * V2.2 预设技能库。8个技能，按V2.2文档 §2.2 定义。
 * SurvivalStateMachine 通过 SkillManager 使用此库。
 */
object SkillLibrary {
    val ALL: List<SkillTag> = listOf(
        SkillTag(
            id = "medical_knowledge",
            name = "医疗常识",
            level = 1,
            description = "掌握基本的医疗知识",
            effects = listOf(SkillEffect("dc_modifier", "医疗", -0.10f)),
            acquisitionStory = "在废墟中多次搜索医疗物资，渐渐学会了辨别药品",
            narrativeHint = "我凭着上次的经验，认出了这种抗生素"
        ),
        SkillTag(
            id = "combat_intuition",
            name = "战斗直觉",
            level = 1,
            description = "在战斗中培养出的本能反应",
            effects = listOf(SkillEffect("dc_modifier", "战斗", -0.05f)),
            acquisitionStory = "经历多次生死搏斗后，身体开始形成条件反射",
            narrativeHint = "我的手比大脑更快地挥出了这一击"
        ),
        SkillTag(
            id = "scavenger",
            name = "拾荒者",
            level = 1,
            description = "擅长在废墟中寻找有用物资",
            effects = listOf(SkillEffect("dc_modifier", "搜索", -0.10f)),
            acquisitionStory = "翻遍了无数废墟后，眼光变得格外敏锐",
            narrativeHint = "角落里一抹金属反光没有逃过我的眼睛"
        ),
        SkillTag(
            id = "read_people",
            name = "察言观色",
            level = 1,
            description = "擅长洞察他人的真实意图",
            effects = listOf(SkillEffect("dc_modifier", "社交", -0.10f)),
            acquisitionStory = "在末世中与各色人物周旋，学会了看透人心",
            narrativeHint = "他的眼神飘忽了一下——这句话不可全信"
        ),
        SkillTag(
            id = "stealth_instinct",
            name = "潜行本能",
            level = 1,
            description = "行动如猫般轻盈",
            effects = listOf(SkillEffect("dc_modifier", "潜行", -0.10f)),
            acquisitionStory = "数次在死亡边缘的潜行经历，让步伐变得无声",
            narrativeHint = "我几乎本能地选择了最暗的路径"
        ),
        SkillTag(
            id = "iron_body",
            name = "铁打的身板",
            level = 1,
            description = "从感染中幸存后获得的强韧体质",
            effects = listOf(SkillEffect("dc_modifier", "医疗", -0.05f)),
            acquisitionStory = "那次感染几乎要了我的命，但活下来后身体变得异常顽强",
            narrativeHint = "换作以前我早就倒下了，但这次不一样"
        ),
        SkillTag(
            id = "engineer",
            name = "工程师",
            level = 1,
            description = "擅长制作和修理物品",
            effects = listOf(SkillEffect("dc_modifier", "制作", -0.10f)),
            acquisitionStory = "反复拆装修理装备，不知不觉中掌握了技巧",
            narrativeHint = "这台设备的结构我早已烂熟于心"
        ),
        SkillTag(
            id = "survival_master",
            name = "生存大师",
            level = 1,
            description = "在末日中生存足够久后积累的综合经验",
            effects = listOf(SkillEffect("resource_bonus", "全部", -0.10f)),
            acquisitionStory = "七天的末日生存，每一天都让人变得更强大",
            narrativeHint = "这些天的经验告诉我，暴风雨就要来了"
        )
    )

    private val byId: Map<String, SkillTag> = ALL.associateBy { it.id }

    fun get(id: String): SkillTag? = byId[id]
    fun getByIds(ids: List<String>): List<SkillTag> = ids.mapNotNull { byId[it] }

    /** 按等级创建技能副本 */
    fun atLevel(skill: SkillTag, level: Int): SkillTag {
        val clampedLevel = level.coerceIn(1, 5)
        return skill.copy(
            level = clampedLevel,
            effects = skill.effects.map { it.copy(modifier = it.modifier * clampedLevel) }
        )
    }

    /** 汇总技能效果：合并所有技能的effects，按applyTo分组累加modifier */
    fun aggregateEffects(skills: List<SkillTag>): Map<String, Float> {
        val result = mutableMapOf<String, Float>()
        for (skill in skills) {
            for (effect in skill.effects) {
                val key = effect.applyTo
                result[key] = (result[key] ?: 0f) + effect.modifier
            }
        }
        return result
    }
}
