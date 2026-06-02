package com.ei8z.endworld.data.model

/**
 * V2.2 技能标签。记录角色成长，影响裁决结果。
 * PlayerState 仅存储技能ID列表，完整数据由 SkillLibrary 提供。
 */
data class SkillTag(
    val id: String,
    val name: String,                    // 如"医疗常识"
    val level: Int,                      // 1-5
    val description: String,             // 给玩家看的描述
    val effects: List<SkillEffect>,      // 对裁决的影响
    val acquisitionStory: String,        // 获取该技能的故事背景
    val narrativeHint: String            // AI如何在叙事中体现该技能
)

data class SkillEffect(
    val type: String,                    // "dc_modifier" | "resource_bonus" | "narrative_perk"
    val applyTo: String,                 // 适用的primaryType或物品类别
    val modifier: Float                  // 修正值（负数=降低DC，正数=增加收益）
)
