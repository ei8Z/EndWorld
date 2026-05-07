package com.ei8z.endworld.data.repository

import com.ei8z.endworld.data.local.entity.*
import java.lang.StringBuilder

object PromptBuilder {

    /**
     * 构建系统提示词：定义 AI 身份、叙事规则和 JSON 契约
     * 对应 KV Cache 的固定前缀部分
     */
    fun buildSystemPrompt(): String {
        return """
            你是一个硬核末日求生游戏的“城主”(GM)。
            你需要基于玩家提供的【当前状态快照】，推进故事并判定行动结果。
            
            ### 叙事原则：
            1. 第一人称视角，充满压抑感和临场感。
            2. AI 充当裁决者：根据玩家能力和物资判定行动成败，不准“心想事成”。
            3. 每次回复必须包含下一步的 3 个建议行动。
            ### JSON 约束 (必须严格遵守)：
            你必须且只能返回合法的 JSON 对象。格式示例如下：
            {
              "header": { "date": "第1天", "time": "黄昏", "location": "废弃超市" },
              "story": "你推开门，冷风灌进领口...",
              "suggestions": ["搜寻货架", "检查后门", "原地扎营"],
              "statusUpdate": {
                "player": { "stateTags": ["轻微饥饿"], "innerThought": "这里应该有罐头...", "equipment": ["折叠刀"] },
                "world": { "doomLevel": 1, "macroSituation": "丧尸危机爆发初期" },
                "resources": { 
                  "knownPoints": [{"location": "五金店", "possibleItems": "工具", "risk": "高"}],
                  "inventory": { "food": [{"name": "过期压缩饼干", "quantity": "1块"}] }
                },
                "memoryUpdate": { "newTempMemory": "在超市门口发现血迹", "newPermanentPlot": null }
              }
            }
        """.trimIndent()
    }

    /**
     * 构建用户消息：提供实时的状态快照
     */
    fun buildUserSnapshot(
        player: PlayerState,
        inventory: List<InventoryItem>,
        memories: List<Memory>,
        world: WorldInfo,
        playerAction: String
    ): String {
        val sb = StringBuilder()
        sb.append("【玩家行动】: $playerAction\n\n")
        sb.append("【当前快照】\n")
        sb.append("- 玩家: ${player.name}, 性格: ${player.personality}, 状态: ${player.mentalStateTags.joinToString()}\n")
        sb.append("- 物资: ${inventory.joinToString { "${it.name}(${it.quantity})" }}\n")
        sb.append("- 最近记忆: ${memories.joinToString("; ") { it.event }}\n")
        sb.append("- 环境: ${world.locationScene}, 时间: ${world.currentDate} ${world.currentTime}\n")
        sb.append("- 世界背景: ${world.macroSituation}\n")
        
        return sb.toString()
    }
}
