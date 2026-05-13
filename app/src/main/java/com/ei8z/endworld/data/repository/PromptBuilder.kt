package com.ei8z.endworld.data.repository

import com.ei8z.endworld.data.local.entity.*
import com.ei8z.endworld.data.remote.model.FunctionDef
import com.ei8z.endworld.data.remote.model.Tool
import java.lang.StringBuilder

object PromptBuilder {

    private val WORLD_LORE = """
        # 世界百科 (World Lore)
        - 核心设定：末日后的世界，资源枯竭，文明崩塌。
        - 裁决物理法则：
            * 物品消耗：任何涉及使用物资的行为（如治疗、进食、战斗）必须先通过 `check_inventory` 工具确认物资充足。
            * 属性判定：高难度行为需参考玩家生理特征与状态标签。
    """.trimIndent()

    /**
     * 构建完整 system prompt：世界百科 → MemOS 长期记忆 → 角色设定 → 核心指令 → JSON 契约。
     * MemOS 记忆注入在 system prompt 层级以利用 KV Cache，重复请求下仅增量 token 计费。
     */
    fun buildSystemPrompt(memosMemories: List<String> = emptyList()): String {
        val sb = StringBuilder()
        sb.append(WORLD_LORE).append("\n\n")

        if (memosMemories.isNotEmpty()) {
            sb.append("# 长期记忆回顾 (来自 MemOS)\n")
            memosMemories.forEachIndexed { index, memory ->
                sb.append("${index + 1}. $memory\n")
            }
            sb.append("\n")
        }

        sb.append("""
            # 角色设定
            你是一个硬核末日求生游戏的“城主”(GM)，内置“世界裁决者”思维。
            
            # 核心指令
            在生成叙事前，你必须在内部完成以下隐藏步骤：
            1. 裁决判定：分析玩家行动难度，结合其性格、当前伤病状态、环境风险和拥有的物资，判定行动是【成功】、【失败】还是【产生意外】。
            2. 物资损耗：若涉及行动，判定是否消耗了食物、饮水或工具耐久。
            3. 环境演变：判定是否触发随机事件（如远处枪声、天气骤变、丧尸徘徊）。
            4. **先查后说**：在描述玩家使用任何物品前，必须调用 `check_inventory` 工具。
            
            # 叙事要求
            - 风格：第一人称叙事，压抑、冷酷、充满临场感。描述感官细节（嗅觉、听觉、寒冷度）。
            - 严禁“心想事成”：求生是艰难的，成功的代价往往是资源消耗。
            
            # JSON 契约 (必须严格遵守)
            你必须且只能返回合法的 JSON 对象。确保 `story` 文本丰富，且 `suggestions` 包含三个差异化的策略。
            
            格式示例：
            {
              "header": { "date": "第1天", "time": "黄昏", "location": "废弃超市" },
              "story": "...",
              "suggestions": ["搜寻货架", "检查后门", "原地扎营"],
              "statusUpdate": {
                "player": { 
                  "stateTags": ["轻微饥饿", "左臂擦伤"], 
                  "innerThought": "伤口在发炎，我得快点找到酒精...", 
                  "equipment": ["破旧的棒球棍"] 
                },
                "world": { "doomLevel": 2, "macroSituation": "四周的嘶吼声越来越近，空气中弥漫着腐烂的气味。" },
                "resources": { 
                  "knownPoints": [{"location": "康复药店", "possibleItems": "抗生素, 绷带", "risk": "极高"}],
                  "inventory": {
                    "food": [{"name": "过期罐头", "quantity": "1"}],
                    "drinks": [{"name": "浑浊的水", "quantity": "0.5L"}],
                    "weapons": [{"name": "棒球棍", "quantity": "耐久80%"}],
                    "special": [{"name": "家人相片", "quantity": "1"}]
                  } 
                },
                "memoryUpdate": { "newTempMemory": "第1天@超市 遭遇小规模尸群，由于及时躲入冷库幸免于难。", "newPermanentPlot": "可选的重要剧情摘要" },
                "npcUpdate": [
                  { "name": "老李", "relation": "警惕", "affinityDelta": -5 }
                ]
              }
            }
        """.trimIndent())

        return sb.toString()
    }

    fun buildUserSnapshot(
        player: PlayerState,
        inventory: List<InventoryItem>,
        memories: List<Memory>,
        history: List<StoryHistory>,
        world: WorldInfo,
        playerAction: String
    ): String {
        val sb = StringBuilder()
        sb.append("【玩家行动】: $playerAction\n\n")

        sb.append("### 最近剧情回顾 (Narrative Context)\n")
        if (history.isEmpty()) {
            sb.append("- 游戏刚开始。\n")
        } else {
            history.takeLast(3).forEach {
                sb.append("- 行动: ${it.action ?: "无"}\n")
                sb.append("- 遭遇: ${it.story.take(100)}...\n")
            }
        }

        sb.append("\n### 当前生存状态快照\n")
        sb.append("- 幸存者: ${player.name} (${player.genderPhysique})\n")
        sb.append("- 特质: ${player.personality} | ${player.baseDescription}\n")
        sb.append("- 状态标签: ${if (player.mentalStateTags.isEmpty()) "良好" else player.mentalStateTags.joinToString()}\n")
        sb.append("- 随身物资: ${if (inventory.isEmpty()) "无" else inventory.joinToString { "${it.name}(${it.quantity})" }}\n")
        sb.append("- 装备: ${if (player.equippedItems.isEmpty()) "无" else player.equippedItems.joinToString()}\n")
        sb.append("- 最近记忆摘要: ${memories.joinToString(" -> ") { it.event }}\n")
        sb.append("- 当前环境: ${world.locationScene} | ${world.currentDate} ${world.currentTime}\n")
        
        return sb.toString()
    }

    fun getTools(): List<Tool> {
        return listOf(
            Tool(
                function = FunctionDef(
                    name = "check_inventory",
                    description = "查询玩家背包中特定物品的数量，确保行动有据可依。",
                    parameters = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "item_name" to mapOf(
                                "type" to "string",
                                "description" to "物品名称，如：'过期罐头', '子弹', '绷带'"
                            )
                        ),
                        "required" to listOf("item_name")
                    )
                )
            )
        )
    }
}
