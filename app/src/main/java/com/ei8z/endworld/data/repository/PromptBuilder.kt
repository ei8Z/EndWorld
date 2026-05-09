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

    fun buildSystemPrompt(memosMemories: List<String>): String {
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
            你是一个硬核末日求生游戏的“城主”(GM)。
            
            # 核心指令
            1. **先查后说**：在描述玩家使用任何物品（如：吃罐头、开火、包扎）前，**必须调用 `check_inventory` 工具**。如果工具返回库存不足，你必须在故事中描述玩家搜寻无果或尝试失败。
            2. **逻辑一致性**：结合 MemOS 提供的长期记忆和最近剧情，确保故事逻辑连贯。
            
            # JSON 契约
            你必须返回合法的 JSON。
            {
              "header": { "date": "...", "time": "...", "location": "..." },
              "story": "叙事文本...",
              "suggestions": ["...", "...", "..."],
              "statusUpdate": { ... }
            }
        """.trimIndent())
        
        return sb.toString()
    }

    fun buildUserSnapshot(
        player: PlayerState,
        inventory: List<InventoryItem>,
        history: List<StoryHistory>,
        world: WorldInfo,
        playerAction: String
    ): String {
        val sb = StringBuilder()
        sb.append("【玩家行动】: $playerAction\n\n")
        
        sb.append("### 最近 3 轮剧情 (显式上下文)\n")
        if (history.isEmpty()) {
            sb.append("- 游戏刚开始。\n")
        } else {
            history.takeLast(3).forEach {
                sb.append("- 行od: ${it.action ?: "无"}\n")
                sb.append("- 遭遇: ${it.story.take(150)}...\n")
            }
        }
        
        sb.append("\n### 当前快照 (Status)\n")
        sb.append("- 状态: ${player.name} | ${player.mentalStateTags.joinToString()}\n")
        sb.append("- 位置: ${world.locationScene}\n")
        
        return sb.toString()
    }

    /**
     * 定义“客户端发牌员”工具集
     */
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
            ),
            Tool(
                function = FunctionDef(
                    name = "get_full_inventory",
                    description = "获取玩家当前所有物资的清单。",
                    parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
                )
            )
        )
    }
}
