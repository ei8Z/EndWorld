package com.ei8z.endworld.data.repository

import com.ei8z.endworld.data.local.entity.*
import java.lang.StringBuilder

object PromptBuilder {

    fun buildSystemPrompt(): String {
        return """
            # 角色设定
            你是一个硬核末日求生游戏的“城主”(GM)，内置“世界裁决者”思维。
            
            # 核心指令
            在生成叙事前，你必须在内部完成以下隐藏步骤：
            1. 裁决判定：分析玩家行动难度，结合其性格、当前伤病状态、环境风险和拥有的物资，判定行动是【成功】、【失败】还是【产生意外】。
            2. 物资损耗：若涉及行动，判定是否消耗了食物、饮水或工具耐久。
            3. 环境演变：判定是否触发随机事件（如远处枪声、天气骤变、丧尸徘徊）。
            
            # 叙事要求
            - 风格：第一人称叙事，压抑、冷酷、充满临场感。描述感官细节（嗅觉、听觉、寒冷度）。
            - 严禁“心想事成”：求生是艰难的，成功的代价往往是资源消耗。
            
            # JSON 契约 (必须严格遵守)
            你必须且只能返回合法的 JSON 对象。确保 `story` 文本超过 500 字，且 `suggestions` 包含三个差异化的策略（保守/激进/环境）。
            
            格式示例：
            {
              "header": { "date": "第1天", "time": "黄昏", "location": "废弃超市" },
              "story": "...",
              "suggestions": ["搜寻货架", "检查后门", "原地扎营"],
              "statusUpdate": {
                "player": { "stateTags": ["轻微饥饿"], "innerThought": "...", "equipment": ["折叠刀"] },
                "world": { "doomLevel": 1, "macroSituation": "..." },
                "resources": { 
                  "knownPoints": [],
                  "inventory": { "food": [], "drinks": [], "weapons": [], "special": [] }
                },
                "memoryUpdate": { "newTempMemory": "事件摘要", "newPermanentPlot": null },
                "npcUpdate": []
              }
            }
        """.trimIndent()
    }

    fun buildUserSnapshot(
        player: PlayerState,
        inventory: List<InventoryItem>,
        memories: List<Memory>,
        world: WorldInfo,
        playerAction: String
    ): String {
        val sb = StringBuilder()
        sb.append("【玩家当前行动】: $playerAction\n\n")
        sb.append("### 当前生存状态快照\n")
        sb.append("- 幸存者: ${player.name} (${player.genderPhysique})\n")
        sb.append("- 特质/背景: ${player.personality} | ${player.baseDescription}\n")
        sb.append("- 末日背景: ${player.doomType} (危险等级: ${player.doomLevel})\n")
        sb.append("- 状态标签: ${if (player.mentalStateTags.isEmpty()) "良好" else player.mentalStateTags.joinToString()}\n")
        sb.append("- 随身物资: ${if (inventory.isEmpty()) "无" else inventory.joinToString { "${it.name}(${it.quantity})" }}\n")
        sb.append("- 最近记忆(FIFO): ${memories.joinToString(" -> ") { it.event }}\n")
        sb.append("- 当前位置: ${world.locationScene}\n")
        sb.append("- 时间线: ${world.currentDate} ${world.currentTime}\n")
        sb.append("- 世界情报: ${world.macroSituation}\n")
        
        return sb.toString()
    }
}
