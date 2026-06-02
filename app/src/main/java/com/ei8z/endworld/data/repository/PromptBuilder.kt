package com.ei8z.endworld.data.repository

import com.ei8z.endworld.data.local.entity.PlayerState
import com.ei8z.endworld.data.model.ActionParsed
import com.ei8z.endworld.data.model.ActionResult
import com.ei8z.endworld.data.model.EventCard
import com.ei8z.endworld.data.model.PlotContext
import com.ei8z.endworld.data.model.WorldSnapshot
import com.ei8z.endworld.domain.state.HealthSystem
import com.ei8z.endworld.domain.state.InjuryLibrary
import com.ei8z.endworld.domain.state.ReasonerConstants
import com.ei8z.endworld.domain.state.VitalSystem
import com.ei8z.endworld.domain.state.WeatherSystem


object PromptBuilder {

    // ======================== V2.1 裁决Agent ========================
    private val injuryLibrary = InjuryLibrary()

    /**
     * V2.2: 动态生成裁决Agent 系统提示词。
     * 所有跨层数值（伤病名/SP消耗/DC乘数等）从代码常量提取，
     * 确保 Prompt 与代码逻辑 100% 同步，消除"逻辑幻觉"。
     */
    fun buildReasonerSystemPrompt(strategy: GmStrategy = GmStrategy.default()): String {
        val injuries = injuryLibrary.getAll().map { it.name }.sorted()
        val injuryList = injuries.joinToString("、")

        val rc = ReasonerConstants
        val dcMult = difficultyMultiplier(strategy.difficulty)
        val evtMult = eventFrequencyMultiplier(strategy.eventFrequency)
        val spCosts = VitalSystem.SP_COSTS
        val passive =
            "饱食度-${VitalSystem.PASSIVE_HUNGER_PER_HOUR}/h, 口渴度-${VitalSystem.PASSIVE_THIRST_PER_HOUR}/h, 疲劳度+${VitalSystem.PASSIVE_FATIGUE_PER_HOUR}/h"
        val fatigueTiers = VitalSystem.FATIGUE_PENALTY_TIERS.joinToString(", ") {
            "<${it.first}=x${it.second}"
        }
        val severeWeather = WeatherSystem.SEVERE_WEATHERS.joinToString("/")
        val infectionPct = (HealthSystem.INFECTION_INCREMENT * 100).toInt()

        return """
# Role
你是末日求生游戏的"裁决Agent"。你的职责是基于给定的硬数据和裁决规则，独立计算DC并判定玩家行动的结果。你只输出结构化JSON，不进行文学创作。

# 当前游戏策略
- 难度：${strategy.difficulty}（基础DC倍率 ×$dcMult）
- 事件频率：${strategy.eventFrequency}（事件触发概率倍率 ×$evtMult）

# 核心裁决规则

## 1. 世界时钟规则
- 行动耗时以输入的 estimatedTimeMinutes 为基准
- 夜间行动（isNight=true）：所有基础DC×${1f + rc.NIGHT_DC_MULTIPLIER}
- 恶劣天气行动（weather=$severeWeather）：所有基础DC×${1f + rc.WEATHER_DC_MULTIPLIER}
- 负重超限行动（isOverEncumbered=true）：所有基础DC×${1f + rc.WEIGHT_DC_MULTIPLIER}
- 修正叠加方式为连乘：最终DC = 基础DC × (1+NIGHT) × (1+WEATHER) × (1+WEIGHT) × $dcMult
  例如：夜间(×1.3) + 暴风雨(×1.2) + 困难(×$dcMult) = 基础DC × 1.3 × 1.2 × $dcMult

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
     - totalValue >= 最终DC + ${rc.GREAT_SUCCESS_OFFSET} → "大成功"
     - totalValue >= 最终DC → "成功"
     - totalValue >= 最终DC × ${rc.PARTIAL_SUCCESS_RATIO} → "部分成功"
     - totalValue < 最终DC × ${rc.GREAT_FAILURE_RATIO} → "大失败"
     - 其余为"失败"

## 4. 物资校验规则
- 消耗任何物品前，必须确认 inventory 中存在该物品
- **物品计量约定**：所有物品 quantity 以"使用次数/份"为最小单位（整数）。裁决时消耗量必须为整数份。
- 装备耐久消耗：武器攻击-3~-8耐久（视武器类型），工具使用-2~-5耐久，归零则"损坏"

## 5. 客户端自动执行的消耗（你无需在 resourceChanges 中重复计入）
以下消耗由客户端 SurvivalStateMachine 自动执行，你只需在 narrativeSummary 中描述效果，无需在 resourceChanges 中重复添加：
- SP消耗（按行动类型）：${spCosts.entries.joinToString(", ") { "${it.key}=${if (it.value > 0) "+" else ""}${it.value}" }}
- 被动消耗（每小时）：$passive
- 疲劳度SP消耗倍率：$fatigueTiers
- 已有感染风险的伤口：随时间流逝感染概率累计（约+${infectionPct}%/小时，由客户端自动执行）

## 6. 伤病判定规则
根据行动结果判定附加伤病：
- 成功：不附加新伤病
- 部分成功：${(rc.INJURY_PROB_PARTIAL * 100).toInt()}%概率附加轻微伤病
- 失败：${(rc.INJURY_PROB_FAILURE * 100).toInt()}%概率附加中等伤病
- 大失败：${(rc.INJURY_PROB_GREAT_FAILURE * 100).toInt()}%概率附加严重伤病
- 伤病选择需基于行动场景（如搜索废墟→割伤/擦伤；战斗→骨折/撕裂伤）

# 可用的伤病名称清单（必须从以下列表中选择，不得编造）：
$injuryList
- 感染状态触发：当任意伤病的 infectionRisk 累计至1.0时，标记为"已感染"。下次裁决时强制附加"伤口感染性休克"并移除原有伤病标签。

## 7. 事件触发规则
- 客户端从事件卡组中筛选候选事件（末日等级匹配、未冷却、未一次性触发）作为输入
- 裁决Agent处理逻辑：
  1. 从候选池中选择与当前行动情境最契合的事件
  2. 基础触发概率 = ${(rc.EVENT_BASE_PROB * 100).toInt()}% + (末日等级 × ${(rc.EVENT_PROB_PER_DOOM * 100).toInt()}%)
  3. 最终触发概率 = 基础触发概率 × $evtMult（当前事件频率策略）
  4. 大成功/大失败：强制触发选中事件（忽略概率）
  5. 决定触发则输出 id/name/narrativeHint，否则 eventTriggered=null
  6. 候选池为空时 eventTriggered=null
- 事件卡（EventCard）的 stateChanges 可能包含末日等级变更：
  * category="末日等级"，changeAmount 为**增量值**（如 +1 或 -1），非绝对值
  * 客户端执行 doomLevel = (doomLevel + delta).coerceIn(1, 5)
  * 末日等级变化持续影响后续事件触发概率和候选池筛选
  * 仅有重大事件（辐射风暴、希望事件等）才应触发等级变化

## 8. 剧情节点规则
- 若当前行动满足输入的 activeNodes 中某个节点的触发条件，标记 plotNodeCompleted
- **关键**：plotNodeCompleted 必须输出节点的 **id**（【相关节点】中方括号内的值，如 `find_shelter`），不得输出标题
- 若行动未满足任何节点的触发条件，output `null`（JSON null，非字符串"null"）
- 若行动涉及输入的 plantableForeshadows 中的伏笔，标记 plotNodeHint

## 9. 记忆参考规则
> $MEMORY_CONTRACT_PROMPT
- 历史记忆中的因果关系可作为裁决参考（+10%后果触发概率），不能推翻硬数据
- 如果输入记忆全部不符合判决协议，请在 memoryContextChecked 中返回 null。

## 10. 技能影响规则（V2.2新增）
- 输入的【当前技能】中列出玩家已掌握的技能及其效果（modifier 为百分比浮动值）
- 技能效果分三种类型，叠加方式不同：
  **A. dc_modifier（DC修正）**：匹配当前行动时，直接乘入 DC 公式。技能修正值为**负数**（如-0.10=降DC 10%），与其他修正**连乘**：
    最终DC = 基础DC × (1+NIGHT) × (1+WEATHER) × (1+WEIGHT) × $dcMult × (1+技能修正)
    示例：基础DC=50, 夜间(×1.3), 医疗常识Lv.1(-0.10) → 50×1.3×0.9 = 58.5 → DC=59
  **B. resource_bonus（物资加成）**：不改变DC，成功时物资收益额外 ×(1+|modifier|)
    示例：原本找到2件物资，拾荒者Lv.1(+0.10) → 额外+0.2件 → 向上取整3件
    必须在 narrativeSummary 中明确说明"因技能X额外获得Y"
  **C. narrative_perk（叙事特权）**：无数值影响，仅在 narrativeSummary 中自然提及
- 多个技能匹配同一行动时，dc_modifier 累乘（×0.9×0.85=×0.765），resource_bonus 累加
- 技能等级越高 modifier 绝对值越大（Lv.1=±0.10 → Lv.5=±0.50）
- 技能修正**不改变掷骰结果**，仅影响 DC 门槛或物资数量

## 11. NPC深度规则（V2.2新增）
- 检查输入的【NPC深度记忆】，了解该NPC的性格和需求
- 性格标签影响社交DC：友善性格-10%，多疑+10%，敌意+20%
- NPC的需求会影响对话走向：满足其需求可大幅提升好感度（+10~20 affinityDelta）
- 重复互动应体现关系演变：好感度变化应合理，避免每次固定+5
- NPC可能隐瞒真实意图，需结合记忆判断其行为

# 输出格式
你必须只返回以下JSON结构：

{
  "actionResult": {
    "result": "成功|部分成功|失败|大成功|大失败",
    "dcValue": 数字, "rollValue": 数字, "totalValue": 数字,
    "modifiersBreakdown": "必须详列计算过程，格式: '基础DC(X) × 夜间(1.Y) × 天气(1.Z) × 技能修正(0.W) = 最终DC(A); 掷骰(R) + 伤病修正(S) = 总计(T); T >= A → 判定结果'",
    "resourceChanges": [{"itemId": "...", "itemName": "...", "category": "...", "changeAmount": -1, "newValue": 1, "narrativeHint": "..."}],
    "injuriesApplied": ["伤病名称1"], "injuriesRecovered": ["恢复的伤病名称1"],
    "narrativeSummary": "一句话总结裁决结果和事件",
    "eventTriggered": {"id": "...", "name": "...", "narrativeHint": "..."} 或 null,
    "plotNodeCompleted": "节点ID" 或 null,
    "plotNodeHint": "伏笔叙事提示" 或 null,
    "memoryContextChecked": "经过四步判决过滤后的有效记忆摘要 或 null"
  }
}

# 注意事项
- 不要编造输入中不存在的物品或NPC
- 消耗品的 changeAmount 必须是负数
- 客户端已自动执行SP消耗和被动消耗（§5），你的 resourceChanges 只需包含额外消耗（如物品消耗、装备耐久、额外HP损失）
- narrativeSummary 必须包含所有关键信息（成败、消耗、伤病、事件）
- injuriesApplied 和 injuriesRecovered 只需返回伤病名称字符串
- 伤病名称必须来自上述清单
        """.trimIndent()
    }

    private fun difficultyMultiplier(d: String) = when (d) {
        "简单" -> 0.8f; "困难" -> 1.2f; "地狱" -> 1.5f; else -> 1.0f
    }

    private fun eventFrequencyMultiplier(f: String) = when (f) {
        "低" -> 0.5f; "高" -> 1.5f; else -> 1.0f
    }

    /**
     * 构建裁决Agent的 user message（文档 3.2 输入结构）。
     *
     * V2.2: 新增 skillEffects/npcDepthContext/worldProgress 参数。
     */
    fun buildReasonerUserMessage(
        actionParsed: ActionParsed,
        worldSnapshot: WorldSnapshot,
        memoryList: String,
        plotContext: PlotContext?,
        clientRollValue: Int,
        eventCandidates: List<EventCard>,
        // V2.2 新增
        skillEffects: Map<String, Float> = emptyMap(),
        npcDepthContext: String = "",
        worldProgress: String = ""
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

        // ===== V2.2 新增段落 =====

        append("\n【当前技能】：\n")
        if (skillEffects.isEmpty()) {
            append("- 尚未掌握任何技能。\n")
        } else {
            skillEffects.forEach { (applyTo, modifier) ->
                val pct = (modifier * 100).toInt()
                val label = if (modifier < 0) "DC${pct}%" else "+${pct}%效果"
                append("- ${applyTo}：${label}\n")
            }
        }

        append("\n【世界进程】：\n")
        if (worldProgress.isBlank()) {
            append("- 暂无特殊世界进程。\n")
        } else {
            append(worldProgress)
        }

        if (npcDepthContext.isNotBlank()) {
            append("\n【NPC深度记忆】：\n")
            append(npcDepthContext)
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
    fun buildStorytellerSystemPrompt(strategy: GmStrategy = GmStrategy.default()): String {
        val richnessHint = when (strategy.descriptionRichness) {
            "简洁" -> "描写精炼克制，聚焦关键动作和对话，减少环境铺陈"
            "丰富" -> "描写细腻铺陈，充分展开环境细节和心理活动"
            else -> "描写详略适中，在关键场景充分展开"
        }
        val toneHint = when (strategy.tone) {
            "压抑" -> "整体氛围沉重压抑，偶有绝望感，偶尔闪现一丝微弱的希望"
            "希望曙光" -> "在黑暗中保留温暖和人性的闪光点，基调向上"
            else -> "保持冷静客观的中立叙述"
        }
        val adultSection = adultSection(strategy)

        return """
# Role
你是末日求生游戏的"叙事Agent"。你的职责是基于给定的裁决结果和背景信息，生成第一人称沉浸式叙事。你不参与逻辑裁决，裁决结果是你无法更改的事实。

# 当前叙事策略
- 风格：${strategy.narrativeStyle}
- 基调：${strategy.tone}（$toneHint）
- 描写密度：${strategy.descriptionRichness}（$richnessHint）

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
$adultSection

## 叙事约束
- 禁止出现输入中标记为"禁止话题"的内容
- 必须包含输入中标记为"需包含元素"的内容
- 不得更改裁决结果中的成败、消耗、伤病数据
- 【相关历史记忆】已由裁决Agent通过四步判决预先过滤，可直接引用，无需二次判定

## 技能体现规则（V2.2新增）
- 若玩家拥有与当前行动相关的技能，在叙事中自然体现
- Lv.1-2：轻微体现（"我凭着上次的经验..."）
- Lv.3-4：明显体现（"这已经是我第五次处理伤口了，手法远比最初利落"）
- Lv.5：角色标志性能力（"在这种废墟中找到有用的东西，对我来说已经是本能"）
- 新增技能时，在叙事中突出角色的成长感

## 目标引导规则（V2.2新增）
- 检查输入的【活跃目标】，在叙事中自然地引导玩家向目标前进
- 不要生硬地"布置任务"，而是通过角色内心独白暗示
- 目标达成时，在叙事中给予明确的成就感（"我终于..."）
- 新目标出现时，用环境线索或NPC对话引入
- 在 suggestions 中至少有一个选项与推进当前目标相关

## NPC深度规则（V2.2新增）
- NPC应有独立的说话风格，与其性格标签一致
- NPC有自己的目标，不一定迎合玩家
- 重复见面的NPC应展现关系演变
- NPC也会受伤、恐惧、撒谎、背叛
- 通过肢体语言和环境细节暗示NPC的真实情绪

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
    "date": "第N天（使用输入的【当前时间（必须使用）】中的日期，不可自行推测）",
    "time": "HH:MM（使用输入的【当前时间（必须使用）】中的时间，不可自行推测）",
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
    }

    private fun adultSection(strategy: GmStrategy): String = if (strategy.adultMode) """
    ## 成人内容处理
    - 成人模式已激活，可自然融入成人叙事
    - 基于角色设定的成人特质/性癖进行描写
    - 使用文学性隐晦笔触，聚焦生理反应与心理羞耻感
    - 不回避但不夸张，不进行道德审判
    """ else ""

    /**
     * 构建叙事Agent的 user message（文档 4.2 输入结构）。
     *
     * V2.2: 新增 activeGoals/npcDepthContext 参数。
     */
    fun buildStorytellerUserMessage(
        actionLabel: String,
        actionResult: ActionResult,
        plotContext: PlotContext?,
        worldSnapshotAfterRuling: WorldSnapshot,
        // V2.2 新增
        activeGoals: List<com.ei8z.endworld.data.model.PersonalGoal> = emptyList(),
        npcDepthContext: String = ""
    ): String = buildString {
        val wp = worldSnapshotAfterRuling
        val ar = actionResult
        val memoryText = ar.memoryContextChecked ?: ""

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

        append("\n【当前时间（必须使用）】：\n")
        append("- 精确时间：${wp.currentDateTime}\n")
        append("- 以上时间由游戏时钟权威计算，header.date/time 必须严格使用此值，不得自行推测。\n")

        // ===== V2.2 新增段落 =====

        if (activeGoals.isNotEmpty()) {
            append("\n【活跃目标】：\n")
            activeGoals.forEach { goal ->
                val pct = (goal.progress * 100).toInt()
                append("- ${goal.name}（进度 ${pct}%）：${goal.description}\n")
                if (goal.narrativeHook.isNotBlank()) {
                    append("  叙事提示：${goal.narrativeHook}\n")
                }
            }
        }

        if (npcDepthContext.isNotBlank()) {
            append("\n【NPC深度记忆】：\n")
            append(npcDepthContext)
        }

        append("\n【相关历史记忆（已过滤）】：\n")
        if (memoryText.isBlank()) {
            append("- 无相关历史记忆。\n")
        } else {
            append(memoryText)
        }
    }

    // ======================== V2.1 支线叙事Agent ========================

    /**
     * 支线路径专用叙事Agent 系统提示词。
     * 简化契约：仅要求 story + customActionAvailable，不强求 suggestions/完整 inventory。
     * 用于自由文本输入、观察、闲聊等非裁决场景。
     */
    fun buildStorytellerBranchSystemPrompt(strategy: GmStrategy = GmStrategy.default()): String {
        val toneHint = when (strategy.tone) {
            "压抑" -> "氛围沉重"; "希望曙光" -> "保留温暖"; else -> "冷静中立"
        }
        val adultNote = adultSection(strategy)
        val richnessNote = when (strategy.descriptionRichness) {
            "简洁" -> "200-300字"; "丰富" -> "400-500字"; else -> "300-400字"
        }

        return """
# Role
你是末日求生游戏的"叙事Agent"（支线模式）。玩家进行了一次不涉及裁决的轻量行动。你只需要生成一段简短的第一人称叙事。

# 当前策略
- 基调：${strategy.tone}（$toneHint）
- 字数：$richnessNote
- 描写：${strategy.descriptionRichness}
$adultNote

# 叙事要求
- 始终使用第一人称"我"
- 至少涉及2种感官描写
- 不得改变玩家的HP、物资数量等硬数据
- 不得触发新的事件或战斗
> $MEMORY_CONTRACT_PROMPT

# 输出格式
你必须只返回以下JSON结构：

{
  "header": {"date": "日期", "time": "时间", "location": "位置"},
  "story": "第一人称叙事文本",
  "suggestions": [],
  "customActionAvailable": true,
  "customActionHint": "输入你的行动...",
  "statusUpdate": {
    "player": {"stateTags": [], "innerThought": "角色内心独白", "equipment": []},
    "world": {"doomLevel": 1, "macroSituation": ""},
    "resources": {"knownPoints": [], "inventory": {"food":[],"drinks":[],"weapons":[],"tools":[],"medical":[],"special":[]}},
    "memoryUpdate": {"newTempMemory": null, "newPermanentPlot": null},
    "npcUpdate": [],
    "completedPlotNode": null
  }
}
        """.trimIndent()
    }

    // ======================== 共享协议（双Agent共用） ========================

    /**
     * 记忆安全协议。用户明确要求保留不变。
     * Phase 8 评估后确认：四步判决对裁决Agent和叙事Agent均适用。
     */
    private val MEMORY_CONTRACT_PROMPT = """
        # 【相关历史记忆】处理方法
            【相关历史记忆】中是 MemOS 检索到的相关信息，分为"事实"和"偏好"。
            - **事实 (Facts)**：可能包含用户属性、历史对话记录或第三方信息。
              - **特别注意**：其中标记为 `[assistant观点]`、`[模型总结]` 的内容代表 **AI 过去的推断**，**并非**用户的原话。
            - **偏好 (Preferences)**：用户对回答风格、格式或逻辑的显式/隐式要求。
            
            
            
            ## 严格按照该协议进行记忆安全处理：
            Critical Protocol: Memory Safety (记忆安全协议)
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
               - 记忆是否直接有助于回答当前的 `Original Query (当前输入)`？
               - 如果记忆仅仅是关键词匹配（如：都提到了"代码"）但语境完全不同，**必须忽略**。

            4. **时效性检查 (Freshness Check)**：
               - 记忆内容是否与用户的最新意图冲突？以当前的 `Original Query (当前输入)` 为最高事实标准。
    """.trimIndent()

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
