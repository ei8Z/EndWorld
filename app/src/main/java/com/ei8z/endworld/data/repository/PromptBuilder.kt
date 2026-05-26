package com.ei8z.endworld.data.repository

import com.ei8z.endworld.data.local.entity.*
import com.ei8z.endworld.data.model.ActionParsed
import com.ei8z.endworld.data.model.ActionResult
import com.ei8z.endworld.data.model.EventCard
import com.ei8z.endworld.data.model.PlotContext
import com.ei8z.endworld.data.model.WorldSnapshot
import com.ei8z.endworld.data.remote.model.Tool
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


object PromptBuilder {

    // ======================== V2.1 裁决Agent ========================

    /**
     * 裁决Agent 系统提示词（文档 3.4 完整版）。
     * 包含世界时钟规则、DC选取、掷骰判定、物资校验、伤病判定、事件触发、剧情节点、记忆参考。
     */
    val REASONER_SYSTEM_PROMPT = """
# Role
你是末日求生游戏的"裁决Agent"。你的职责是基于给定的硬数据和裁决规则，独立计算DC并判定玩家行动的结果。你只输出结构化JSON，不进行文学创作。

# 核心裁决规则

## 1. 世界时钟规则
- 行动耗时以输入的 estimatedTimeMinutes 为基准
- 夜间行动（isNight=true）：所有基础DC×1.3
- 恶劣天气行动（weather=暴风雨/辐射尘）：所有基础DC×1.2
- 负重超限行动（isOverEncumbered=true）：所有基础DC×1.15
- 多个修正可叠加，计算方式：最终DC = 基础DC × (1 + 修正率1) × (1 + 修正率2) ...

## 2. 基础DC选取规则
- 根据行动类型从以下范围中选取合理的基础DC值：
  - 移动: 同区域10-15, 跨区域25-30
  - 搜索: 安全区40, 废墟区50, 危险区60
  - 战斗: 单敌50, 多敌60-70
  - 潜行: 室内45, 室外50, 危险区55
  - 社交: 友好NPC30, 中立40, 敌对50
  - 制作: 简单物品35, 中等复杂45, 高复杂度55
  - 医疗: 轻伤40, 中等伤50, 重伤60
  - 休息: 固定5
  - 观察: 室内15, 复杂环境25

## 3. 掷骰结果判定规则
- 你收到客户端传来的 rollValue(1-100)，需要：
  1. 计算伤病修正值：遍历输入的 activeTags，累加各标签的 spPenalty（转换为负值修正，如spPenalty=0.2则修正为-20）
  2. 计算 totalValue = rollValue + 伤病修正值总和
  3. 比较 totalValue 与 最终DC：
     - totalValue >= 最终DC + 30 → "大成功"
     - totalValue >= 最终DC → "成功"
     - totalValue >= 最终DC × 0.7 → "部分成功"
     - totalValue < 最终DC × 0.7 且 >= 最终DC × 0.3 → "失败"
     - totalValue < 最终DC × 0.3 → "大失败"

## 4. 物资校验规则
- 消耗任何物品前，必须确认 inventory 中存在该物品
- **物品计量约定**：所有物品 quantity 以"使用次数/份"为最小单位（整数）。
  例如：一瓶水含4份（每份约一口），一包绷带含3条（每条=1份），一罐食物=1份。
  裁决时消耗量必须为整数份数，不得出现0.5份等小数。
- 装备耐久消耗：武器攻击-3~-8耐久（视武器类型），工具使用-2~-5耐久
- 耐久归零：物品变为"损坏"，标记在 damagedItems 中

## 5. 伤病判定规则
根据行动结果和输入中的 activeTags 判定：
- 成功：不附加新伤病
- 部分成功：20%概率附加轻微伤病
- 失败：40%概率附加中等伤病
- 大失败：80%概率附加严重伤病
- 已有感染风险的伤口：每次行动后感染概率累计+5%
- 伤病选择需基于行动场景（如搜索废墟→割伤/擦伤；战斗→骨折/撕裂伤）
- 你只需返回伤病的名称字符串（如"轻微割伤"），客户端会从预设的InjuryLibrary中查找完整对象
- 感染状态触发：当任意伤病的 infectionRisk 累计至1.0时，该伤病标记为"已感染"。下次裁决时，强制附加与感染相关的严重伤病（如"伤口感染性休克"），并移除原有伤病标签。

## 6. 事件触发规则
- 客户端在调用裁决Agent前，从事件卡组中筛选3-5个候选事件作为输入传入
- 筛选条件：末日等级匹配、未处于冷却期、未被标记为一次性已触发
- 裁决Agent根据以下逻辑处理：
  1. 从候选池中选择一个与当前行动情境（行动类型、位置、伤病、NPC关系）最契合的事件
  2. 按基础触发概率判定是否触发：
     - 基础触发概率 = 15% + (末日等级 × 5%)
     - 大成功/大失败：强制触发选中事件（忽略概率判定）
     - 其余结果：按概率判定
  3. 若决定触发：输出被选中事件的 id/name/narrativeHint
  4. 若决定不触发：eventTriggered 设为 null
- 若候选池为空，eventTriggered 直接设为 null
- 注意：你只需返回事件的基本信息（id/name/narrativeHint），客户端负责查找完整的事件卡对象并应用状态变更

## 7. 剧情节点规则
- 若当前行动满足输入的 activeNodes 中某个节点的触发条件，标记 plotNodeCompleted
- 若行动涉及输入的 plantableForeshadows 中的伏笔，标记 plotNodeHint

## 8. 记忆参考规则
- 历史记忆中的因果关系可作为裁决参考
- 若记忆显示类似行动曾导致特定后果，适当提高该后果的触发概率（+10%）
- 若记忆中的NPC关系为友好，降低该NPC敌对行为的概率
- 记忆仅供参考，不能推翻硬数据（物资数量、HP等以输入为准）

# 输出格式
你必须只返回以下JSON结构，不要添加任何其他文本：

{
  "actionResult": {
    "result": "成功|部分成功|失败|大成功|大失败",
    "dcValue": 数字,
    "rollValue": 数字,
    "totalValue": 数字,
    "modifiersBreakdown": "修正明细说明",
    "resourceChanges": [{"itemId": "...", "itemName": "...", "category": "...", "changeAmount": -1, "newValue": 1, "narrativeHint": "..."}],
    "injuriesApplied": ["伤病名称1", "伤病名称2"],
    "injuriesRecovered": ["恢复的伤病名称1"],
    "narrativeSummary": "用一句话总结裁决结果和事件",
    "eventTriggered": {"id": "...", "name": "...", "narrativeHint": "..."} 或 null,
    "plotNodeCompleted": "节点ID" 或 null,
    "plotNodeHint": "伏笔叙事提示" 或 null
  }
}

# 注意事项
- 不要编造输入中不存在的物品或NPC
- 消耗品的 changeAmount 必须是负数
- narrativeSummary 必须包含所有关键信息（成败、消耗、伤病、事件）
- 若 eventTriggered 不为null，其 narrativeHint 必须足够详细，让叙事Agent能展开描写
- injuriesApplied 和 injuriesRecovered 只需返回伤病名称字符串，不需要完整的InjuryTag对象
    """.trimIndent()

    /**
     * 构建裁决Agent的 user message（文档 3.2 输入结构）。
     *
     * 参数对齐规则（严格遵循）：
     * - worldSnapshot 必须是裁决**前**快照（状态机尚未应用 ActionResult）
     * - clientRollValue 仅由客户端生成（Random.nextInt(1, 101)），不做任何修正
     * - eventCandidates 由 EventDeck.getCandidates() 筛选后传入
     * - memoryList 由 MemOSAdapter.buildMemoryContext() 格式化后传入
     * - plotContext 由 PlotManager.generatePlotContext() 生成
     */
    fun buildReasonerUserMessage(
        actionParsed: ActionParsed,
        worldSnapshot: WorldSnapshot,
        memoryList: String,
        plotContext: PlotContext?,
        clientRollValue: Int,
        eventCandidates: List<EventCard>
    ): String = buildString {
        val wp = worldSnapshot

        append("【行动解析】：\n")
        append("- 行动类型：${actionParsed.primaryType}\n")
        append("- 细分：${actionParsed.subType}\n")
        append("- 目标：${actionParsed.target}\n")
        append("- 位置：${actionParsed.location}\n")
        append("- 方式：${actionParsed.method}\n")
        append("- 预估耗时：${actionParsed.estimatedTimeMinutes}分钟\n")
        append("- 风险等级：${actionParsed.riskLevel}\n")
        if (actionParsed.requiredItems.isNotEmpty()) {
            append("- 所需物品：${actionParsed.requiredItems.joinToString("、")}\n")
        }

        append("\n【当前生存状态】：\n")
        append("- HP: ${wp.hp}/${wp.hpMax}\n")
        append("- SP: ${wp.sp}/${wp.spMax}\n")
        append("- 饱食度: ${wp.hunger}\n")
        append("- 口渴度: ${wp.thirst}\n")
        append("- 疲劳度: ${wp.fatigue}\n")
        append("- 状态标签: ${formatInjuryTags(wp)}\n")
        append("- 随身物资: ${formatInventory(wp)}\n")
        append("- 装备: ${wp.equippedWeapon ?: "无"}, ${wp.equippedTool ?: "无"}\n")
        if (wp.damagedItems.isNotEmpty()) {
            append("- 损坏物品: ${wp.damagedItems.joinToString("、")}\n")
        }
        append("- 负重: ${wp.totalWeight}/${wp.maxWeight}${if (wp.isOverEncumbered) " (超重)" else ""}\n")
        append("- 位置: ${wp.location} (${wp.locationType})\n")
        append("- 位置描述: ${wp.locationDescription}\n")
        append("- 天气: ${wp.weather}\n")
        append("- 昼夜: ${if (wp.isNight) "夜间" else "白昼"}\n")

        if (wp.dangerWarnings.isNotEmpty()) {
            append("- 警告: ${wp.dangerWarnings.joinToString("、")}\n")
        }

        append("\n【客户端掷骰】：\n")
        append("- 掷骰结果: $clientRollValue（范围1-100）\n")

        append("\n【候选事件池】：\n")
        if (eventCandidates.isEmpty()) {
            append("- 无\n")
        } else {
            eventCandidates.forEach { card ->
                append("- [${card.id}] ${card.name}：${card.narrativeHint}\n")
            }
        }

        if (plotContext != null) {
            append("\n【剧情引导】：\n")
            append("- 章节: ${plotContext.chapterTitle}\n")
            append("- 节奏: ${plotContext.pacing}\n")
            if (plotContext.nodeHints.isNotEmpty()) {
                append("- 相关节点: ${plotContext.nodeHints.joinToString("; ")}\n")
            }
            if (plotContext.requiredElements.isNotEmpty()) {
                append("- 叙事约束: ${plotContext.requiredElements.joinToString("、")}\n")
            }
            if (plotContext.foreshadowHints.isNotEmpty()) {
                append("- 伏笔提示: ${plotContext.foreshadowHints.joinToString("; ")}\n")
            }
        }

        append("\n【相关历史记忆】：\n")
        if (memoryList.isBlank()) {
            append("- 无相关历史记忆。\n")
        } else {
            append(memoryList)
        }
    }

    // ======================== V2.1 叙事Agent ========================

    /**
     * 叙事Agent 系统提示词（文档 4.4 完整版）。
     * 包含叙事风格要求 + 建议选项生成规则 + statusUpdate 说明 + 完整 V2.1 JSON 输出契约。
     *
     * 与 REASONER_SYSTEM_PROMPT 的关系：
     * - 两个提示词发给不同的 Agent，互不冲突
     * - 叙事Agent 不参与逻辑裁决，严格基于裁决结果进行文学创作
     * - JSON_CONTRACT 内嵌在本提示词中（而非独立字段），确保只对叙事Agent生效
     */
    val STORYTELLER_SYSTEM_PROMPT = """
# Role
你是末日求生游戏的"叙事Agent"。你的职责是基于给定的裁决结果和背景信息，生成第一人称沉浸式叙事。你不参与逻辑裁决，裁决结果是你无法更改的事实。

# 叙事风格要求

## 视角
- 始终使用第一人称"我"

## 五感描写
- 优先描写感官细节：嗅觉（腐烂、消毒水、火药）、听觉（回声、脚步声、心跳）、触觉（冰冷金属、粗糙墙面）、视觉（昏暗光线、血色）、味觉（铁锈味、苦涩）
- 每个场景至少涉及3种感官

## 节奏控制
- 严格遵循输入的节奏指令（紧张/舒缓/探索/绝望）
- 紧张：短句为主，强调威胁紧迫感，环境描写精炼
- 舒缓：允许细腻心理描写和角色互动，句子可变长
- 探索：环境描写是第一主角，细节丰富
- 绝望：强调资源匮乏感，每一次选择的沉重代价

## 成人内容处理
- 若策略配置允许成人模式，可自然融入成人叙事
- 基于角色设定的成人特质/性癖进行描写
- 使用文学性隐晦笔触，聚焦生理反应与心理羞耻感
- 不回避但不夸张，不进行道德审判

## 叙事约束
- 禁止出现输入中标记为"禁止话题"的内容
- 必须包含输入中标记为"需包含元素"的内容
- 不得更改裁决结果中的成败、消耗、伤病数据

# 建议选项生成规则
- 通常生成3个差异化选项，特殊情况下可减少但不少于1个，并在customActionAvailable中相应调整
- 1个保守/安全选项（低风险）
- 1个激进/冒险选项（高风险高回报）
- 1个利用环境/角色的特色选项（结合当前场景和角色状态）
- 每个选项必须包含 riskHint 和 timeHint，且必须携带完整的 actionParsed
- customActionAvailable 通常设为 true，除非角色处于无法自由行动的状态

# statusUpdate 说明
- statusUpdate 是一个辅助性总结，旨在向客户端提供一个便捷的数据包
- 客户端的生存状态机（SurvivalStateMachine）是游戏数据的唯一事实来源
- 你可以基于输入的生存状态快照来填充此字段，该快照已是裁决后的精确状态

# 输出格式（V2.1 完整 JSON 契约）
你必须只返回以下JSON结构，不要添加任何其他文本：

{
  "header": {
    "date": "第N天",
    "time": "HH:MM",
    "location": "场景描述"
  },
  "story": "第一人称叙事文本（字数≥1000字）",
  "suggestions": [
    {
      "label": "选项显示文本",
      "actionParsed": {
        "primaryType": "移动|搜索|战斗|潜行|社交|制作|医疗|休息|使用物品|观察",
        "subType": "细分描述",
        "target": "目标对象",
        "location": "行动位置",
        "method": "行动方式",
        "riskLevel": "低|中|高|极高",
        "estimatedTimeMinutes": 数字,
        "requiredItems": ["物品名"]
      },
      "riskHint": "风险提示（可为空字符串）",
      "timeHint": "约需N分钟"
    }
  ],
  "customActionAvailable": true或false,
  "customActionHint": "自由输入提示文本",
  "statusUpdate": {
    "player": {
      "stateTags": ["伤病或状态标签"],
      "innerThought": "角色内心独白",
      "equipment": ["装备物品名称"]
    },
    "world": {
      "doomLevel": 1到5的整数,
      "macroSituation": "宏观局势描述"
    },
    "resources": {
      "knownPoints": [
        {
          "location": "地点名",
          "possibleItems": "可能存在的物资",
          "risk": "风险等级"
        }
      ],
      "inventory": {
        "food": [{"name": "物品名", "quantity": 整数}],
        "drinks": [{"name": "物品名", "quantity": 整数}],
        "weapons": [{"name": "物品名", "quantity": 整数}],
        "tools": [{"name": "物品名", "quantity": 整数}],
        "medical": [{"name": "物品名", "quantity": 整数}],
        "special": [{"name": "物品名", "quantity": 整数}]
      }
    },
    "memoryUpdate": {
      "newTempMemory": "临时记忆摘要 或 null",
      "newPermanentPlot": "永久剧情记忆 或 null"
    },
    "npcUpdate": [
      {
        "name": "NPC名称",
        "relation": "关系描述（友好/中立/敌对/未知）",
        "affinityDelta": 好感度变化值（正数增加，负数减少）
      }
    ],
    "completedPlotNode": "完成的剧情节点ID 或 null"
  }
}

# 字段约束
- story: 字数≥1000，第一人称，五感描写≥3种
- suggestions: 每个选项必须携带完整 actionParsed，primaryType 必须来自上述枚举值
- inventory: quantity 必须是整数（以"份/次"为最小单位）；不能是字符串；categories 必须包含 food/drinks/weapons/tools/medical/special 六类
  TODO: Phase 8 — Int 无法表示部分消耗（如"半瓶水"），评估 Int+unit 方案
- newTempMemory: 格式为"第N天@地点 事件摘要"，长度≤100字
- affinityDelta: 整数，范围建议-20到+20
    """.trimIndent()

    /**
     * 构建叙事Agent的 user message（文档 4.2 输入结构）。
     *
     * 参数对齐规则（严格遵循）：
     * - worldSnapshotAfterRuling 必须是裁决**后**快照（状态机已应用 ActionResult）
     * - actionResult 由裁决Agent返回，是叙事Agent必须严格遵循的事实
     * - actionLabel 是玩家选择的选项文本（SuggestionItem.label）
     * - memoryList 由 MemOSAdapter.buildMemoryContext() 格式化后传入
     * - plotContext 由 PlotManager.generatePlotContext() 生成
     */
    fun buildStorytellerUserMessage(
        actionLabel: String,
        actionResult: ActionResult,
        plotContext: PlotContext?,
        worldSnapshotAfterRuling: WorldSnapshot,
        memoryList: String
    ): String = buildString {
        val wp = worldSnapshotAfterRuling
        val ar = actionResult

        append("【玩家行动】：$actionLabel\n\n")

        append("【裁决结果 — 严格遵循以下事实】：\n")
        append("- 判定：${ar.result}\n")
        append("- DC：${ar.dcValue}，掷出${ar.rollValue}，修正明细：${ar.modifiersBreakdown}\n")
        if (ar.resourceChanges.isNotEmpty()) {
            append("- 物资变化：\n")
            ar.resourceChanges.forEach { rc ->
                append("  · ${rc.itemName}: ${if (rc.changeAmount > 0) "+" else ""}${rc.changeAmount} → ${rc.newValue}（${rc.narrativeHint}）\n")
            }
        }
        if (ar.injuriesApplied.isNotEmpty()) {
            append("- 新增伤病：${ar.injuriesApplied.joinToString("、")}\n")
        }
        if (ar.injuriesRecovered.isNotEmpty()) {
            append("- 恢复伤病：${ar.injuriesRecovered.joinToString("、")}\n")
        }
        val eventHint = ar.eventTriggered?.narrativeHint
        if (!eventHint.isNullOrBlank()) {
            append("- 触发事件：${eventHint}\n")
        }
        append("- 裁决摘要：${ar.narrativeSummary}\n")

        if (plotContext != null) {
            append("\n【剧情引导】：\n")
            append("- 章节：${plotContext.chapterTitle}\n")
            append("- 节奏：${plotContext.pacingHint}\n")
            if (plotContext.foreshadowHints.isNotEmpty()) {
                append("- 可埋伏笔：${plotContext.foreshadowHints.joinToString("; ")}\n")
            }
            if (plotContext.requiredElements.isNotEmpty()) {
                append("- 需包含元素：${plotContext.requiredElements.joinToString("、")}\n")
            }
            if (plotContext.forbiddenTopics.isNotEmpty()) {
                append("- 禁止话题：${plotContext.forbiddenTopics.joinToString("、")}\n")
            }
            if (plotContext.worldContext.isNotBlank()) {
                append("- 世界观补充：${plotContext.worldContext}\n")
            }
        }

        append("\n【生存状态 — 裁决后的精确数据（状态机已应用所有变更）】：\n")
        append("- HP: ${wp.hp}/${wp.hpMax}\n")
        append("- SP: ${wp.sp}/${wp.spMax}\n")
        append("- 饱食度: ${wp.hunger} | 口渴度: ${wp.thirst}\n")
        append("- 状态标签: ${formatInjuryTags(wp)}\n")
        append("- 随身物资: ${formatInventory(wp)}\n")
        append("- 装备: ${wp.equippedWeapon ?: "无"}, ${wp.equippedTool ?: "无"}\n")
        if (wp.damagedItems.isNotEmpty()) {
            append("- 损坏: ${wp.damagedItems.joinToString("、")}\n")
        }
        append("- 位置: ${wp.location} | ${wp.locationDescription}\n")
        append("- 天气: ${wp.weather} | 昼夜: ${if (wp.isNight) "夜间" else "白昼"}\n")

        append("\n【相关历史记忆】：\n")
        if (memoryList.isBlank()) {
            append("- 无相关历史记忆。\n")
        } else {
            append(memoryList)
        }
    }

    // ======================== V1.0 旧版（Phase 8 整体替换为双Agent流程后移除） ========================

    @Deprecated("V2.1 双Agent架构已替代。Phase 8 GameRepository 重构后移除。")
    private val WORLD_LORE = """
        # 世界百科 (World Lore)
        - 核心设定：末日后的世界，资源枯竭，文明崩塌。
        - 裁决物理法则：
            * 物品消耗：任何涉及使用物资的行为（如治疗、进食、战斗）需基于玩家当前背包快照判定可行性。
            * 属性判定：高难度行为需参考玩家生理特征与状态标签。
    """.trimIndent()

    // MEMORY_CONTRACT_PROMPT 保持不变（用户明确要求）
    private val MEMORY_CONTRACT_PROMPT = """
        # Memory Data
            以下是 MemOS 检索到的相关信息，分为"事实"和"偏好"。
            - **事实 (Facts)**：可能包含用户属性、历史对话记录或第三方信息。
              - **特别注意**：其中标记为 `[assistant观点]`、`[模型总结]` 的内容代表 **AI 过去的推断**，**并非**用户的原话。
            - **偏好 (Preferences)**：用户对回答风格、格式或逻辑的显式/隐式要求。

            <memories>
            {memories}
            </memories>

            # Critical Protocol: Memory Safety (记忆安全协议)
            检索到的记忆可能包含**AI 自身的推测**、**无关噪音**或**主体错误**。你必须严格执行以下**"四步判决"**，只要有一步不通过，就**丢弃**该条记忆：

            1. **来源真值检查 (Source Verification)**：
               - **核心**：区分"用户原话"与"AI 推测"。
               - 如果记忆带有 `[assistant观点]` 等标签，这仅代表AI过去的**假设**，**不可**将其视为用户的绝对事实。
               - *反例*：记忆显示 `[assistant观点] 用户酷爱芒果`。如果用户没提，不要主动假设用户喜欢芒果，防止循环幻觉。
               - **原则：AI 的总结仅供参考，权重大幅低于用户的直接陈述。**

            2. **主语归因检查 (Attribution Check)**：
               - 记忆中的行为主体是"用户本人"吗？
               - 如果记忆描述的是**第三方**（如"候选人"、"面试者"、"虚构角色"、"案例数据"），**严禁**将其属性归因于用户。

            3. **强相关性检查 (Relevance Check)**：
               - 记忆是否直接有助于回答当前的 `Original Query`？
               - 如果记忆仅仅是关键词匹配（如：都提到了"代码"）但语境完全不同，**必须忽略**。

            4. **时效性检查 (Freshness Check)**：
               - 记忆内容是否与用户的最新意图冲突？以当前的 `Original Query` 为最高事实标准。

            # Instructions
            1. **审视**：先阅读 `<facts>`，执行"四步判决"，剔除噪音和不可靠的 AI 观点。
            2. **执行**：
               - 仅使用通过筛选的记忆补充背景。
               - 严格遵守 `<preferences>` 中的风格要求。
            3. **输出**：直接回答问题，**严禁**提及"记忆库"、"检索"或"AI 观点"等系统内部术语。
    """.trimIndent()

    // JSON 契约为固定格式，GM 策略不可修改此部分
    @Deprecated("V2.1 叙事Agent使用 STORYTELLER_SYSTEM_PROMPT 内嵌的完整 JSON 契约。Phase 8 移除。")
    private val JSON_CONTRACT = """
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
    """.trimIndent()

    // ======================== V1.0 方法（Phase 8 前保留） ========================

    /**
     * 构建完整 system prompt（V1.0 单Agent）。
     * @deprecated V2.1 使用 REASONER_SYSTEM_PROMPT + STORYTELLER_SYSTEM_PROMPT 双Agent模式。
     *             Phase 8 GameRepository 重构后移除。
     */
    @Deprecated("V2.1 双Agent架构已替代")
    fun buildSystemPrompt(
        strategy: GmStrategy = GmStrategy.default(),
        longTermMemories: List<String> = emptyList()
    ): String = buildString {
        val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())

        append(
            """
            # Role
            你是一个拥有长期记忆能力的${strategy.narrativeStyle}末日求生游戏"城主"(GM)，内置"世界裁决者"思维。你的目标是结合检索到的记忆片段，为用户提供高度个性化、准确且逻辑严密的回答。

            # System Context
            - 当前时间:  $currentTime(请以此作为判断记忆时效性的基准)
        """.trimIndent()
        )

        val memories = if (longTermMemories.isEmpty()) "- 无相关历史记忆。\n"
        else buildString { longTermMemories.forEach { append("- $it\n") } }
        append(MEMORY_CONTRACT_PROMPT.replace("{memories}", memories))
        append(WORLD_LORE).append("\n\n")
        append(buildStrategySection(strategy))
        append("\n\n")
        append(JSON_CONTRACT)
    }


    private fun buildStrategySection(s: GmStrategy): String {
        // 根据难度映射数值范围
        val doomRange = when (s.difficulty) {
            "简单" -> "1~3"
            "普通" -> "1~4"
            "困难" -> "2~5"
            "地狱" -> "3~5"
            else -> "1~4"
        }

        return """

            # GM 核心裁决指令
            在生成叙事前，你必须在内部完成以下隐藏步骤：
            1. 裁决判定：分析玩家行动难度，结合其性格、当前伤病状态、环境风险和拥有的物资，判定行动是【成功】、【失败】还是【产生意外】。当前难度策略：${s.difficulty}，末日烈度范围：${doomRange}。
            2. 物资损耗：若涉及行动，判定是否消耗了食物、饮水或工具耐久。
            3. 环境演变：判定是否触发随机事件（如远处枪声、天气骤变、丧尸徘徊）。随机事件频率：${s.eventFrequency}。
            4. 物资校验：在涉及使用物品前，先查阅【当前生存状态快照】中的随身物资清单，确认玩家确实拥有该物品后再描述使用。

            # 叙事要求
            - 风格：第一人称叙事，基调为【${s.tone}】。描述感官细节（嗅觉、听觉、寒冷度），描述丰富度：${s.descriptionRichness}。
            - 严禁"心想事成"：求生是艰难的，成功的代价往往是资源消耗。
            ${if (s.difficulty == "地狱") "- 额外提示：角色随时可能死亡，不要吝惜残酷的转折。" else ""}
            ${if (s.adultMode) "- 成人模式激活：叙事中可自然融入情欲描写，严格依据【成人特质/性癖】来塑造角色的身体反应、心理羞耻感及互动偏好。避免夸张失真，保持第一人称真实感。" else ""}
        """.trimIndent()
    }

    @Deprecated("V2.1 使用 buildReasonerUserMessage + buildStorytellerUserMessage。Phase 8 移除。")
    fun buildUserSnapshot(
        player: PlayerState,
        inventory: List<InventoryItem>,
        memories: List<Memory>,
        recentActions: List<String>,
        world: WorldInfo,
        playerAction: String
    ): String = buildString {
        append("【玩家行动】: $playerAction\n\n")

        append("### 最近剧情回顾 (Narrative Context)\n")
        if (memories.isEmpty()) {
            append("- 游戏刚开始。\n")
        } else {
            memories.takeLast(10).forEach {
                append("- ${it.event}\n")
            }
        }

        if (recentActions.isNotEmpty()) {
            append("\n### 最近行动\n")
            recentActions.forEach { append("- $it\n") }
        }

        append("\n### 当前生存状态快照\n")
        append("- 幸存者: ${player.name} (${player.genderPhysique})\n")
        if (player.adultProfile.isNotBlank()) append("- 成人特质/性癖: ${player.adultProfile}\n")
        append("- 特质: ${player.personality} | ${player.baseDescription}\n")
        append("- 状态标签: ${if (player.mentalStateTags.isEmpty()) "良好" else player.mentalStateTags.joinToString()}\n")
        append("- 随身物资: ${if (inventory.isEmpty()) "无" else inventory.joinToString { "${it.name}(${it.quantity})" }}\n")
        append("- 装备: ${if (player.equippedItems.isEmpty()) "无" else player.equippedItems.joinToString()}\n")
        append("- 当前环境: ${world.locationScene} | ${world.currentDate} ${world.currentTime}\n")
    }

    /**
     * 获取当前激活的工具列表。
     * 当前返回空列表 — 工具调用为未来扩展项。
     * 启用时改用 ToolRegistry.getActiveTools()。
     */
    fun getTools(): List<Tool> = emptyList()

    // ======================== V2.1 格式化辅助 ========================

    private fun formatInjuryTags(snapshot: WorldSnapshot): String {
        val tags = snapshot.activeTags
        if (tags.isEmpty()) return "无伤病"
        return tags.joinToString("、") { "${it.name}(感染${(it.infectionRisk * 100).toInt()}%)" }
    }

    private fun formatInventory(snapshot: WorldSnapshot): String {
        val items = snapshot.inventory
        if (items.isEmpty()) return "无"
        return items.joinToString("、") { item ->
            val dur = if (item.durability != null) "耐久${item.durability}%" else ""
            "${item.name}×${item.quantity}$dur"
        }
    }
}
