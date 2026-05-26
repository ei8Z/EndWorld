# 末日求生 · AI叙事游戏 开发文档 V2.1（修订版）

> **版本**：V2.1 双Agent架构 + 时间同步 + 主线/支线双路径  
> **核心变更**：从单Agent裁决升级为裁决Agent+叙事Agent双智能体协作  
> **设计原则**：主线预制选项驱动独立裁决，支线自由输入驱动轻量叙事，游戏时间与现实时间按比例映射  
> **修订记录**：修订于2026年，修正上下文逻辑矛盾、参数对齐问题及用词谬误


> **术语定义 — 硬数据**
>
> **硬数据** 指由客户端生存状态机（SurvivalStateMachine）唯一维护的精确数值与状态，是游戏世界的事实基础。裁决Agent与叙事Agent均无权修改或推翻硬数据。
>
> **硬数据范围**：
> - 生命值与体力值（HP/SP）及各自最大值
> - 饱食度（hunger）、口渴度（thirst）、疲劳度（fatigue）
> - 物资库存（inventory）：物品名称、数量、耐久度、重量
> - 装备状态（equippedWeapon/equippedTool/damagedItems）
> - 伤病标签（activeTags）：伤病名称及完整参数（hpLossPerHour、spPenalty、infectionRisk等）
> - 位置信息（location/locationType/locationDescription）
> - 游戏时间（currentDateTime/isNight）
> - 负重状态（totalWeight/maxWeight/isOverEncumbered）
>
> **非硬数据（软数据）**：
> - 历史记忆（MemoryList）：可作为裁决参考，但不可推翻硬数据
> - 剧情上下文（PlotContext）：提供叙事引导，不约束裁决逻辑
> - NPC关系与好感度：仅在社交类行动中作为修正参考
> - 叙事Agent的statusUpdate：辅助性总结，不具数据权威性


## 一、架构总览

### 1.1 核心组件

```
┌──────────────────────────────────────────────────────────────────┐
│                         GameViewModel                             │
│                    (协调层，管理流程与UI状态)                        │
└──────────────────────────────────────────────────────────────────┘
                                  │
        ┌─────────────────────────┼─────────────────────────┐
        ▼                         ▼                         ▼
┌───────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   PlotManager │     │ SurvivalState   │     │  MemOSAdapter   │
│  (剧情管理器)  │     │    Machine      │     │ (记忆系统适配)   │
│  客户端/云端   │     │   (生存状态机)   │     │    云端API      │
└───────────────┘     └─────────────────┘     └─────────────────┘
        │                       │                       │
        │  PlotContext          │  WorldSnapshot        │  MemoryList
        │                       │                       │
        └───────────────────────┼───────────────────────┘
                                │
                                ▼
                    ┌───────────────────────┐
                    │     裁决Agent          │
                    │   (Reasoner)           │
                    │   模型: V4 Pro          │
                    │   reasoning: high       │
                    │   输入: 快照+记忆+剧情  │
                    │   输出: ActionResult    │
                    └───────────────────────┘
                                │
                                │  ActionResult + 裁决后WorldSnapshot
                                ▼
                    ┌───────────────────────┐
                    │     叙事Agent          │
                    │   (Storyteller)        │
                    │   模型: V4 Pro          │
                    │   reasoning: medium     │
                    │   输入: 裁决+剧情+状态  │
                    │   输出: 叙事+suggestions│
                    └───────────────────────┘
                                │
                                ▼
                    ┌───────────────────────┐
                    │     GameResponse      │
                    │   解析→更新状态→UI刷新  │
                    └───────────────────────┘
```

### 1.2 数据流概览

```
┌──────────┐   主线选项(含actionParsed)   ┌───────────────┐
│  玩家输入  │ ──────────────────────────→ │  流程控制器    │
│  (UI点击)  │                            │  (ViewModel)  │
└──────────┘                              └───────────────┘
                                                   │
         并行获取上下文                               │
         ┌──────────────────────────────────────────┤
         ▼                    ▼                    ▼
  PlotContext          WorldSnapshot          MemoryList
  (章节/节奏/伏笔)     (HP/SP/物资/标签)      (事实/关系/因果)
         │                    │                    │
         └────────────────────┼────────────────────┘
                              │
                              │  客户端仅生成rollValue
                              │  DC修正与最终判定由裁决Agent完成
                              ▼
                    ┌─────────────────┐
                    │   裁决Agent      │
                    │   系统提示词:     │
                    │   - 世界时钟规则  │
                    │   - 行动裁决规则  │
                    │   - 伤病判定规则  │
                    │   - 事件触发规则  │
                    │   输入:           │
                    │   - actionParsed  │
                    │   - WorldSnapshot │
                    │   - MemoryList    │
                    │   - PlotContext   │
                    │   - clientRoll    │
                    │   输出:           │
                    │   - ActionResult  │
                    │   - 伤病名列表    │
                    └─────────────────┘
                              │
                              │ ActionResult + 更新后WorldSnapshot
                              ▼
                    ┌─────────────────┐
                    │   叙事Agent      │
                    │   系统提示词:     │
                    │   - 叙事风格要求  │
                    │   - JSON输出契约 │
                    │   输入:           │
                    │   - ActionResult  │
                    │   - PlotContext   │
                    │   - WorldSnapshot │
                    │   - MemoryList    │
                    │   输出(流式):      │
                    │   - story文本     │
                    │   - suggestions   │
                    │   - statusUpdate  │
                    │     (辅助性总结)  │
                    └─────────────────┘
                              │
                              ▼
                    ┌─────────────────┐
                    │   GameResponse  │
                    │   客户端解析      │
                    │   更新:          │
                    │   - 状态机状态    │
                    │   - MemOS记忆    │
                    │   - UI界面       │
                    └─────────────────┘
```

### 1.3 主线/支线双路径

| 维度 | 主线路径 | 支线路径 |
|------|----------|----------|
| **触发方式** | 点击预制选项（suggestions） | 自由输入文本 |
| **行动参数** | 选项携带完整 `actionParsed` | 客户端关键字匹配（6-8种支线类型） |
| **裁决方式** | 裁决Agent独立裁决 | 无裁决（仅轻量处理） |
| **状态影响** | 消耗资源、可能受伤、推进时间 | 最小化状态更新：仅被动时间消耗，无主动资源消耗 |
| **事件触发** | 触发随机事件卡组 | 不触发事件 |
| **剧情推进** | 检查章节过渡 | 不检查章节过渡 |
| **记忆写入** | 写入临时记忆 | 仅在重要对话时写入 |
| **Agent调用** | 双Agent串行 | 单Agent（仅叙事） |
| **延迟预期** | 3-6秒 | 1-2秒 |


## 二、数据模型定义

### 2.1 行动解析结构 (ActionParsed)

所有主线选项必须携带此结构。

```
ActionParsed {
    primaryType: String,          // 枚举值，见下表
    subType: String,              // 细分描述
    target: String,               // 目标对象/NPC/物品
    location: String,             // 行动发生的具体位置
    method: String,               // 行动方式描述
    riskLevel: String,            // "低"|"中"|"高"|"极高"
    estimatedTimeMinutes: Int,    // 预估游戏内耗时（分钟）
    requiredItems: List<String>,  // 行动所需物品（可选）
    // 注意：skillCheck字段已移至V3.0扩展计划，当前版本暂不实现
}
```

**primaryType 枚举表**：

| 值 | 说明 | 消耗类型 | 关联裁决树 |
|----|------|----------|------------|
| `移动` | 改变位置 | SP+时间 | 移动裁决 |
| `搜索` | 搜寻物资 | SP+时间 | 搜索裁决 |
| `战斗` | 与敌人交战 | SP+耐久+时间 | 战斗裁决 |
| `潜行` | 隐蔽行动 | SP+时间 | 潜行裁决 |
| `社交` | 与NPC互动 | 时间 | 社交裁决 |
| `制作` | 制造/修理物品 | SP+材料+时间 | 制作裁决 |
| `医疗` | 治疗伤病 | 医疗物资+时间 | 医疗裁决 |
| `休息` | 恢复体力 | 时间（正向恢复） | 休息裁决 |
| `使用物品` | 消耗物品 | 物品本身 | 直接执行（无裁决） |
| `观察` | 环境观察 | 时间 | 观察裁决 |

### 2.2 生存状态快照 (WorldSnapshot)

由生存状态机在每次行动前生成，作为裁决Agent的核心输入。

```
WorldSnapshot {
    // 时间
    currentDateTime: DateTime,
    isNight: Boolean,
    weather: String,                // "晴"|"阴"|"雨"|"暴风雨"|"辐射尘"
    weatherDuration: Int,           // 已持续回合数

    // 生命维持
    hp: Int,                        // 当前/最大值
    hpMax: Int,
    sp: Int,
    spMax: Int,
    hunger: Int,                    // 0-100
    thirst: Int,                    // 0-100
    fatigue: Int,                   // 0-100

    // 状态标签
    activeTags: List<InjuryTag>,    // 当前伤病标签
    tagEffects: Map<String, String>, // 标签效果描述（文本）

    // 物资
    inventory: List<InventoryItem>,
    totalWeight: Float,
    maxWeight: Float,
    isOverEncumbered: Boolean,

    // 装备
    equippedWeapon: String?,
    equippedTool: String?,
    damagedItems: List<String>,     // 损坏物品名称列表

    // 位置
    location: String,
    locationDescription: String,
    locationType: String,           // "室内"|"室外"|"废墟"|"安全区"

    // 周边
    nearbyThreats: List<String>,    // 已知周边威胁
    nearbyNpcs: List<String>,       // 周边已知NPC
    nearbyResources: List<String>,  // 周边已知资源点

    // 警告
    dangerWarnings: List<String>    // 自动生成的警告文本
}
```

**注意**：本版本暂不包含玩家技能数值字段，所有技能检定相关修正均为0。此功能将在V3.0引入NPC人格系统时同步实现。

**InventoryItem 定义**：

```
InventoryItem {
    id: String,
    name: String,
    category: String,               // "食物"|"饮品"|"武器"|"工具"|"医疗"|"特殊"
    quantity: Int,
    durability: Int?,               // 耐久度(0-100)，消耗品为null
    nutrition: Int?,                // 食用增加的饱食度
    hydration: Int?,                // 饮用增加的水分
    healingValue: Int?,             // 医疗增加的HP
    weight: Float,                  // 单件重量(kg)
    tags: List<String>              // 如["可投掷","易燃","感染风险"]
}
```

**InjuryTag 定义**：

```
InjuryTag {
    id: String,
    name: String,                   // 如"左臂擦伤"
    hpLossPerHour: Int,             // 每小时HP损失
    spPenalty: Float,               // SP消耗倍率（1.0=无惩罚）
    infectionRisk: Float,           // 当前累计感染概率(0-1)，初始值见预设模板，每次行动后根据裁决结果叠加，达到1.0视为"已感染"，"已感染"状态将触发特定病症等后续伤病判定
    naturalRecoveryHours: Int,      // 自然恢复所需小时数
    requiredTreatment: String?,     // 所需医疗物品名称
    narrativeHint: String           // 给AI的叙事提示
}
```

### 2.3 裁决结果 (ActionResult)

由裁决Agent输出，作为叙事Agent的核心输入。

```
ActionResult {
    // 判定
    result: String,                 // "成功"|"部分成功"|"失败"|"大成功"|"大失败"
    dcValue: Int,                   // 目标难度值（由裁决Agent计算）
    rollValue: Int,                 // 掷骰结果（客户端生成）
    totalValue: Int,                // 掷骰+修正=最终值（由裁决Agent计算）
    modifiersBreakdown: String,     // 修正明细说明（如"夜间+30%, 恶劣天气+20%, 负重+15%"）

    // 消耗
    resourceChanges: List<ResourceChange>,
    // 每项：{itemId, name, quantityBefore, quantityAfter, change}

    // 伤病（仅返回伤病名称，客户端从InjuryLibrary查找完整对象）
    injuriesApplied: List<String>,  // 新增伤病名称列表
    injuriesRecovered: List<String>, // 恢复的伤病名称列表

    // 叙事摘要（给叙事Agent的关键信息）
    narrativeSummary: String,       // 裁决结果的叙事摘要

    // 事件
    eventTriggered: EventCard?,     // 触发的事件卡

    // 剧情
    plotNodeCompleted: String?,     // 完成的剧情节点ID
    plotNodeHint: String?,          // 剧情节点叙事引导
}
```

**注意**：裁决Agent仅返回伤病的名称字符串。客户端维护一个 `InjuryLibrary`，根据AI返回的名称查找完整的 `InjuryTag` 对象并应用到状态机。伤病恢复也统一使用名称列表。

**ResourceChange 定义**：

```
ResourceChange {
    itemId: String,
    itemName: String,
    category: String,               // "消耗品"|"装备耐久"|"生命值"|"体力值"|"饱食度"|"口渴度"
    changeAmount: Int,              // 负数=减少，正数=增加
    newValue: Int,                  // 变化后的数值
    maxValue: Int?,                 // 最大值（如HP变化时）
    narrativeHint: String           // 如"罐头被打开了，食物已经消耗"
}
```

### 2.4 剧情上下文 (PlotContext)

由剧情管理器生成。

```
PlotContext {
    chapterId: String,
    chapterTitle: String,
    chapterDescription: String,

    // 节奏
    pacing: String,                 // "紧张"|"舒缓"|"探索"|"绝望"|"希望"
    pacingHint: String,             // 节奏叙事指令

    // 节点
    activeNodes: List<PlotNode>,
    nodeHints: List<String>,

    // 伏笔
    plantableForeshadows: List<Foreshadow>,
    payoffableForeshadows: List<Foreshadow>,
    foreshadowHints: List<String>,

    // 约束
    requiredElements: List<String>,
    forbiddenTopics: List<String>,

    // 世界观
    worldContext: String,
    factionInfo: String
}
```

### 2.5 事件卡 (EventCard)

由事件卡组在裁决阶段或状态机中抽取。

```
EventCard {
    id: String,
    name: String,
    doomLevel: Int,                 // 所属末日等级(1-5)
    category: String,               // "环境"|"遭遇"|"资源"|"剧情"
    narrativeHint: String,          // 给叙事Agent的提示
    cooldownRounds: Int,            // 冷却回合数
    oneTimeOnly: Boolean,           // 是否仅触发一次
    affectsState: Boolean,          // 是否改变客户端状态
    stateChanges: List<ResourceChange>? // 状态变更（如有）
}
```
> 事件触发流程说明：客户端在每次主线行动前，从事件卡组中筛选3-5个候选事件（匹配末日等级、未冷却、可触发），作为裁决Agent输入的一部分。裁决Agent从候选池中选择与当前行动情境最契合的事件，并根据行动结果判定是否触发。若触发，仅返回事件卡的基本信息（id/name/narrativeHint），客户端根据id查找完整EventCard对象并应用stateChanges。

### 2.6 预制选项 (SuggestionItem)

由叙事Agent在每轮返回时携带，供玩家下一轮选择。

```
SuggestionItem {
    label: String,                  // 显示文本，如"合作回答：如实告知身份"
    actionParsed: ActionParsed,     // 完整的行动解析参数
    riskHint: String?,              // 风险提示，如"可能激怒对方"
    timeHint: String?               // 时间提示，如"约需15分钟"
}
```

### 2.7 叙事Agent输出 (GameResponse)

叙事Agent的完整JSON输出契约。

```
GameResponse {
    header: {
        date: String,               // 如"第3天"
        time: String,               // 如"15:30"
        location: String            // 如"废弃医院·药房外走廊"
    },

    story: String,                  // 第一人称叙事文本（流式输出）

    suggestions: List<SuggestionItem>,  // 主线选项（含actionParsed）

    customActionAvailable: Boolean,     // 是否允许自由输入
    customActionHint: String?,          // 自由输入提示

    // statusUpdate为辅助性总结，客户端状态机是唯一权威数据源
    statusUpdate: {
        player: {
            stateTags: List<String>,
            innerThought: String,
            equipment: List<String>
        },
        world: {
            doomLevel: Int,
            macroSituation: String
        },
        resources: {
            knownPoints: List<KnownPoint>,
            inventory: InventorySnapshot
        },
        memoryUpdate: {
            newTempMemory: String?,
            newPermanentPlot: String?
        },
        npcUpdate: List<NpcUpdate>,
        completedPlotNode: String?
    }
}
```


## 三、裁决Agent设计

### 3.1 职责与定位

裁决Agent是游戏的“物理法则执行官”。它接收客户端生成的随机数，独立完成DC计算和最终结果判定，并输出结构化裁决数据。

**核心原则**：
- 接收完整的结构化输入（WorldSnapshot + ActionParsed + MemoryList + PlotContext + clientRoll）
- 独立计算DC修正值（环境、伤病、负重等），并基于客户端掷骰结果进行最终判定
- 输出精确的 ActionResult，不做文学修饰
- 记忆是裁决的参考依据，不是唯一标准

### 3.2 输入结构

**系统提示词**：包含裁决规则（见下文3.4）

**用户消息**：

```text
【行动解析】：
- 行动类型：{actionParsed.primaryType}
- 细分：{actionParsed.subType}
- 目标：{actionParsed.target}
- 位置：{actionParsed.location}
- 方式：{actionParsed.method}
- 预估耗时：{actionParsed.estimatedTimeMinutes}分钟

【当前生存状态】：
- HP: {worldSnapshot.hp}/{worldSnapshot.hpMax}
- SP: {worldSnapshot.sp}/{worldSnapshot.spMax}
- 饱食度: {worldSnapshot.hunger}
- 口渴度: {worldSnapshot.thirst}
- 疲劳度: {worldSnapshot.fatigue}
- 状态标签: {worldSnapshot.activeTags}
- 随身物资: {世界快照中的物品列表}
- 装备: {worldSnapshot.equippedWeapon}, {worldSnapshot.equippedTool}
- 负重: {worldSnapshot.totalWeight}/{worldSnapshot.maxWeight}
- 位置: {worldSnapshot.location} ({worldSnapshot.locationType})
- 天气: {worldSnapshot.weather}
- 昼夜: {"夜间" if isNight else "白昼"}

【客户端掷骰】：
- 掷骰结果: {rollValue}（范围1-100）

【候选事件池】：
{formattedEventCandidates}
- formattedEventCandidates 由客户端从事件卡组筛选后按以下格式拼装：
- 每行一个事件，格式："- [id] name：narrativeHint"
- 示例：
- - [military_checkpoint] 军队检查站：三名武装士兵正在盘查接近者
- - [toxic_spore] 毒孢子爆发：空气中弥漫着绿色孢子
- 若候选池为空，此行显示"- 无"

【剧情引导】：
- 章节: {plotContext.chapterTitle}
- 节奏: {plotContext.pacing}
- 相关节点: {plotContext.nodeHints}
- 叙事约束: {plotContext.requiredElements}

【相关历史记忆】：
{formattedMemoryList}
```

### 3.3 输出结构

```json
{
  "actionResult": {
    "result": "失败",
    "dcValue": 65,
    "rollValue": 23,
    "totalValue": 18,
    "modifiersBreakdown": "基础DC=45, 夜间+30%=13.5, 负重+15%=6.75, 最终DC=65; 掷骰23, 伤病修正-5, 最终值=18",
    "resourceChanges": [
      {
        "itemId": "bandage_01",
        "itemName": "绷带",
        "category": "消耗品",
        "changeAmount": -1,
        "newValue": 1,
        "narrativeHint": "用了一条绷带包扎伤口"
      }
    ],
    "injuriesApplied": ["轻微割伤"],
    "injuriesRecovered": [],
    "narrativeSummary": "搜索药房失败，手被碎玻璃割伤。未找到抗生素。军队检查站出现在医院侧翼。",
    "eventTriggered": {
      "id": "military_checkpoint",
      "name": "军队检查站",
      "narrativeHint": "三名武装士兵正在盘查接近者"
    },
    "plotNodeCompleted": null,
    "plotNodeHint": null
  }
}
```

### 3.4 系统提示词（完整版）

```text
# Role
你是末日求生游戏的“裁决Agent”。你的职责是基于给定的硬数据和裁决规则，独立计算DC并判定玩家行动的结果。你只输出结构化JSON，不进行文学创作。

# 核心裁决规则

## 1. 世界时钟规则
- 行动耗时以输入的 `estimatedTimeMinutes` 为基准
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
- 你收到客户端传来的 `rollValue`(1-100)，需要：
  1. 计算伤病修正值：遍历输入的 `activeTags`，累加各标签的 `spPenalty`（转换为负值修正，如spPenalty=0.2则修正为-20）
  2. 计算 `totalValue = rollValue + 伤病修正值总和`
  3. 比较 `totalValue` 与 `最终DC`：
     - totalValue >= 最终DC + 30 → "大成功"
     - totalValue >= 最终DC → "成功"
     - totalValue >= 最终DC × 0.7 → "部分成功"
     - totalValue < 最终DC × 0.7 且 >= 最终DC × 0.3 → "失败"
     - totalValue < 最终DC × 0.3 → "大失败"

## 4. 物资校验规则
- 消耗任何物品前，必须确认 `inventory` 中存在该物品
- 装备耐久消耗：武器攻击-3~-8耐久（视武器类型），工具使用-2~-5耐久
- 耐久归零：物品变为"损坏"，标记在 `damagedItems` 中

## 5. 伤病判定规则
根据行动结果和输入中的 `activeTags` 判定：
- 成功：不附加新伤病
- 部分成功：20%概率附加轻微伤病
- 失败：40%概率附加中等伤病
- 大失败：80%概率附加严重伤病
- 已有感染风险的伤口：每次行动后感染概率累计+5%
- 伤病选择需基于行动场景（如搜索废墟→割伤/擦伤；战斗→骨折/撕裂伤）
- 你只需返回伤病的名称字符串（如"轻微割伤"），客户端会从预设的InjuryLibrary中查找完整对象
- 感染状态触发：当任意伤病的 infectionRisk 累计至1.0时，该伤病标记为“已感染”。下次裁决时，强制附加与感染相关的严重伤病（如“伤口感染性休克”），并移除原有伤病标签。

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
- 若当前行动满足输入的 `activeNodes` 中某个节点的触发条件，标记 `plotNodeCompleted`
- 若行动涉及输入的 `plantableForeshadows` 中的伏笔，标记 `plotNodeHint`

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
- 消耗品的 `changeAmount` 必须是负数
- `narrativeSummary` 必须包含所有关键信息（成败、消耗、伤病、事件）
- 若 `eventTriggered` 不为null，其 `narrativeHint` 必须足够详细，让叙事Agent能展开描写
- `injuriesApplied` 和 `injuriesRecovered` 只需返回伤病名称字符串，不需要完整的InjuryTag对象
```

### 3.5 API调用参数

```kotlin
val reasonerRequest = ChatRequest(
    model = "deepseek-v4-pro",
    messages = listOf(
        Message("system", REASONER_SYSTEM_PROMPT),
        Message("user", buildReasonerUserMessage(actionParsed, worldSnapshot, memoryList, plotContext, clientRollValue))
    ),
    reasoningEffort = "high",           // 裁决需要高推理能力
    responseFormat = mapOf("type" to "json_object"),
    maxTokens = 1024,                   // 裁决结果较短，1024足够
    stream = false,                     // 裁决不需要流式
    temperature = null                  // 思考模式下忽略
)
```

**注意**：客户端仅负责生成随机数（`rollValue`），DC计算、修正值计算和最终结果判定全部由裁决Agent完成。


## 四、叙事Agent设计

### 4.1 职责与定位

叙事Agent是游戏的“文学说书人”。它不参与逻辑裁决，只负责将已有裁决结果转化为沉浸式叙事。

**核心原则**：
- 接收完整的结构化输入（ActionResult + PlotContext + 裁决后WorldSnapshot + MemoryList）
- 严格基于裁决结果进行文学创作，不得更改成败、消耗、伤病等硬数据
- 生成第一人称沉浸式叙事，字数≥1000
- 输出带完整actionParsed的预制选项，供下一轮使用
- `statusUpdate` 为辅助性总结，客户端状态机是游戏数据的唯一权威来源

### 4.2 输入结构

**系统提示词**：包含叙事风格要求和JSON输出契约（见下文4.4）

**用户消息**：

```text
【玩家行动】：{用户选择的主线选项label}

【裁决结果 — 严格遵循以下事实】：
- 判定：{actionResult.result}
- DC：{actionResult.dcValue}，掷出{actionResult.rollValue}，修正明细：{actionResult.modifiersBreakdown}
- 物资变化：{actionResult.resourceChanges}
- 新增伤病：{actionResult.injuriesApplied}
- 恢复伤病：{actionResult.injuriesRecovered}
- 事件：{actionResult.eventTriggered?.narrativeHint}
- 裁决摘要：{actionResult.narrativeSummary}

【剧情引导】：
- 章节：{plotContext.chapterTitle}
- 节奏：{plotContext.pacingHint}
- 可埋伏笔：{plotContext.foreshadowHints}
- 需包含元素：{plotContext.requiredElements}
- 禁止话题：{plotContext.forbiddenTopics}
- 世界观补充：{plotContext.worldContext}

【生存状态 — 裁决后的精确数据（状态机已应用所有变更）】：
- HP: {worldSnapshotAfterRuling.hp}/{worldSnapshotAfterRuling.hpMax}
- SP: {worldSnapshotAfterRuling.sp}/{worldSnapshotAfterRuling.spMax}
- 饱食度: {worldSnapshotAfterRuling.hunger} | 口渴度: {worldSnapshotAfterRuling.thirst}
- 状态标签: {worldSnapshotAfterRuling.activeTags}
- 随身物资: {worldSnapshotAfterRuling.inventory}
- 装备: {worldSnapshotAfterRuling.equippedWeapon}, {worldSnapshotAfterRuling.equippedTool}
- 损坏: {worldSnapshotAfterRuling.damagedItems}
- 位置: {worldSnapshotAfterRuling.location} | {worldSnapshotAfterRuling.locationDescription}
- 天气: {worldSnapshotAfterRuling.weather} | 昼夜: {"夜间" if isNight else "白昼"}

【相关历史记忆】：
{formattedMemoryList}
```

**说明**：`worldSnapshotAfterRuling` 是状态机应用 `ActionResult` 后生成的全新快照，确保叙事Agent获得的数据是裁决后的精确状态。

### 4.3 输出结构（完整JSON契约）

```json
{
  "header": {
    "date": "第3天",
    "time": "15:30",
    "location": "废弃医院·药房外走廊"
  },

  "story": "（第一人称叙事文本，字数≥1000）",

  "suggestions": [
    {
      "label": "合作回答：如实告知身份，请求医疗援助",
      "actionParsed": {
        "primaryType": "社交",
        "subType": "谈判",
        "target": "军队小队长",
        "location": "废弃医院走廊",
        "method": "对话交涉",
        "riskLevel": "中",
        "estimatedTimeMinutes": 15,
        "requiredItems": []
      },
      "riskHint": "对方态度不明，可能被扣留",
      "timeHint": "约需15分钟"
    }
  ],

  "customActionAvailable": true,
  "customActionHint": "或者输入你自己的行动...",

  "statusUpdate": {
    "player": {
      "stateTags": ["左臂擦伤", "轻微割伤"],
      "innerThought": "伤口在发炎，抗生素刻不容缓",
      "equipment": ["棒球棍"]
    },
    "world": {
      "doomLevel": 3,
      "macroSituation": "军队正在收缩防线，城市外围已失控"
    },
    "resources": {
      "knownPoints": [
        {
          "location": "军队检查站",
          "possibleItems": "医疗物资、情报、武器",
          "risk": "中（取决于交涉结果）"
        }
      ],
      "inventory": {
        "food": [],
        "drinks": [{"name": "半瓶水", "quantity": "1"}],
        "weapons": [{"name": "棒球棍", "quantity": "耐久72%"}],
        "special": [{"name": "绷带", "quantity": "1"}]
      }
    },
    "memoryUpdate": {
      "newTempMemory": "第3天@废弃医院 搜索抗生素失败，被碎玻璃割伤。遭遇军队检查站。",
      "newPermanentPlot": null
    },
    "npcUpdate": [
      {
        "name": "军队小队长",
        "relation": "未知",
        "affinityDelta": 0
      }
    ],
    "completedPlotNode": null
  }
}
```

### 4.4 系统提示词（完整版）

```text
# Role
你是末日求生游戏的“叙事Agent”。你的职责是基于给定的裁决结果和背景信息，生成第一人称沉浸式叙事。你不参与逻辑裁决，裁决结果是你无法更改的事实。

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
- 通常生成3个差异化选项，特殊情况下可减少但不少于1个，并在customActionAvailable中相应调整，携带完整的 actionParsed
- 1个保守/安全选项（低风险）
- 1个激进/冒险选项（高风险高回报）
- 1个利用环境/角色的特色选项（结合当前场景和角色状态）
- 每个选项必须包含 riskHint 和 timeHint
- customActionAvailable 通常设为 true，除非角色处于无法自由行动的状态

# statusUpdate 说明
- `statusUpdate` 是一个辅助性总结，旨在向客户端提供一个便捷的数据包
- **客户端的生存状态机（SurvivalStateMachine）是游戏数据的唯一事实来源**
- 你可以基于输入的【生存状态】快照来填充此字段，该快照已是裁决后的精确状态

# 输出格式
你必须只返回以下JSON结构，不要添加任何其他文本：

{
  "header": {"date": "日期", "time": "时间", "location": "场景描述"},
  "story": "第一人称叙事文本（字数≥1000字）",
  "suggestions": [{"label": "...", "actionParsed": {...}, "riskHint": "...", "timeHint": "..."}],
  "customActionAvailable": true/false,
  "customActionHint": "...",
  "statusUpdate": {
    "player": {"stateTags": [...], "innerThought": "...", "equipment": [...]},
    "world": {"doomLevel": 数字, "macroSituation": "..."},
    "resources": {"knownPoints": [...], "inventory": {...}},
    "memoryUpdate": {"newTempMemory": "...", "newPermanentPlot": "..." 或 null},
    "npcUpdate": [{"name": "...", "relation": "...", "affinityDelta": 数字}],
    "completedPlotNode": "..." 或 null
  }
}
```

### 4.5 API调用参数

```kotlin
val storytellerRequest = ChatRequest(
    model = "deepseek-v4-pro",
    messages = listOf(
        Message("system", STORYTELLER_SYSTEM_PROMPT),
        Message("user", buildStorytellerUserMessage(actionLabel, actionResult, plotContext, worldSnapshotAfterRuling, memoryList))
    ),
    reasoningEffort = "medium",         // 叙事不需要极限推理
    responseFormat = mapOf("type" to "json_object"),
    maxTokens = 8192,                   // 1000+字叙事需要较大输出空间
    stream = true,                      // 流式输出，打字机效果
    temperature = null                  // 思考模式下忽略
)
```


## 五、核心流程（主线路径）

### 5.1 完整的单轮交互流程

```
玩家点击主线选项（SuggestionItem）
        │
        │  0-0.1s: 点击反馈
        │  UI: 按钮短暂高亮 + 触觉反馈
        │
        ▼
┌───────────────────────────────────────────────────┐
│ Step 1: ViewModel 提取 actionParsed                │
│                                                   │
│ val actionParsed = selectedSuggestion.actionParsed│
└───────────────────────────────────────────────────┘
        │
        │  0.1-0.3s: 并行获取上下文
        │
        ├────────────────────────────────────────────┐
        ▼                                            ▼
┌──────────────────┐                     ┌──────────────────┐
│ Step 2a: 状态机生成 │                     │ Step 2b: 剧情管理器  │
│   裁决前快照       │                     │                   │
│                   │                     │ 检查章节过渡        │
│ GameClock.advance │                     │ 生成 PlotContext   │
│ VitalSystem.apply │                     │                   │
│ → WorldSnapshot   │                     │                   │
│ (裁决前快照)       │                     │                   │
└──────────────────┘                     └──────────────────┘
        │                                            │
        │  0.3-0.5s: 并行查询记忆                     │
        │                                            │
        ├────────────────────────────────────────────┤
        ▼                                            ▼
┌───────────────────────────────────────────────────┐
│ Step 2c: MemOS 查询                               │
│                                                   │
│ 查询1（事实）：用 actionParsed.primaryType +       │
│   actionParsed.location 拼装query                  │
│ 查询2（关系）：用 worldSnapshot.nearbyNpcs 拼装    │
│ 查询3（因果）：用 actionParsed.primaryType 拼装    │
│ → MemoryList                                      │
└───────────────────────────────────────────────────┘
        │
        │  0.5-0.55s（上下文获取完毕后）: 客户端掷骰
        │
        ▼
┌───────────────────────────────────────────────────┐
│ Step 3: 客户端掷骰（仅生成随机数）                  │
│                                                   │
│ rollValue = Random.nextInt(1, 101)                │
│ // 客户端不做任何修正和判定                        │
└───────────────────────────────────────────────────┘
        │
        │  0.55-0.8s: 组装裁决请求
        │
        ▼
┌───────────────────────────────────────────────────┐
│ Step 4: 调用裁决Agent                              │
│                                                   │
│ 组装：System Prompt + User Message                 │
│ 传入：actionParsed + WorldSnapshot + MemoryList    │
│       + PlotContext + rollValue                   │
│ → 发送至 DeepSeek V4 Pro                           │
│ → 裁决Agent独立计算DC、修正值、最终判定             │
│ → 获得 ActionResult（JSON）                        │
│                                                   │
│ 【用户体验：0.55-2.5s】                            │
│ UI: 命运模式 - 屏幕变暗 + 齿轮动画 + 心跳音效      │
└───────────────────────────────────────────────────┘
        │
        │  2.5-2.8s: 应用裁决结果到状态机
        │
        ▼
┌───────────────────────────────────────────────────┐
│ Step 5: 应用 ActionResult 到状态机                  │
│                                                   │
│ VitalSystem.applyResourceChanges()                │
│ HealthSystem.applyInjuries()                      │
│   - 根据injuriesApplied名称从InjuryLibrary查找完整对象 │
│ InventoryManager.applyItemChanges()               │
│ → 生成裁决后 WorldSnapshot（全新快照）              │
└───────────────────────────────────────────────────┘
        │
        │  2.8-3.0s: 组装叙事请求
        │
        ▼
┌───────────────────────────────────────────────────┐
│ Step 6: 调用叙事Agent                              │
│                                                   │
│ 组装：System Prompt + User Message                 │
│ 传入：actionLabel + ActionResult + PlotContext     │
│       + 裁决后WorldSnapshot + MemoryList           │
│ → 发送至 DeepSeek V4 Pro（stream=true）             │
│ → 流式接收 story 文本                              │
│                                                   │
│ 【用户体验：3.0-6.0s】                             │
│ UI: 阅读模式 - 环境音效 + 仿旧纸张 + 逐字显示      │
└───────────────────────────────────────────────────┘
        │
        │  6.0s+: 解析响应，更新状态
        │
        ▼
┌───────────────────────────────────────────────────┐
│ Step 7: 解析 GameResponse，更新所有状态             │
│                                                   │
│ - 更新 Room 数据库（WorldInfo, PlayerState,        │
│   Inventory, Memories, NpcRelations）              │
│ - 写入 MemOS 记忆（如有重要事件）                   │
│ - UI 更新：                                       │
│   · 叙事文本完整显示                               │
│   · 三个新选项从底部滑入                           │
│   · 状态面板可展开查看                             │
│   · HP/标签/物资实时刷新                           │
│                                                   │
│ → 等待玩家下一个选择                               │
└───────────────────────────────────────────────────┘
```

### 5.2 时间线总结

| 时间点 | 事件 | UI/UX |
|--------|------|-------|
| 0.0s | 玩家点击选项 | 按钮高亮 + 触觉反馈 |
| 0.1-0.55s | 状态机+剧情+记忆+掷骰（客户端并行） | 无感知（极快） |
| 0.55-2.5s | 裁决Agent调用（含DC计算+判定） | 命运模式动画 |
| 2.5-2.8s | 应用裁决结果，生成裁决后快照 | 无感知 |
| 2.8-6.0s | 叙事Agent流式调用 | 阅读模式+逐字显示 |
| 6.0s+ | 完整叙事呈现，新选项就位 | 等待玩家决策 |

**总延迟：约3-6秒（其中可见等待约5秒）**


## 六、支线路径处理

### 6.1 支线类型定义

| 支线类型 | 触发词 | 生存状态影响 | NPC/世界状态影响 | 处理方式 |
|----------|--------|-------------|-----------------|----------|
| 观察 | 看、观察、打量、环顾、查看 | 时间+5-10分钟，SP-1，被动饱食/口渴消耗 | 可能解锁已知资源点 | 仅叙事Agent |
| 闲聊 | 说、问、聊、告诉、询问 | 时间+5-15分钟，被动饱食/口渴消耗 | 好感度可能微调（±5），可能写入临时记忆 | 仅叙事Agent |
| 小操作 | 喝、吃、检查、整理、装备 | 时间+1-5分钟，物品直接消耗 | 无 | 客户端直接执行 |
| 移动几步 | 走、去、前往（短距离） | 时间+5-15分钟，SP-3，被动饱食/口渴消耗 | 可能改变location | 仅叙事Agent |
| 等待 | 等、等待、待着、休息片刻 | 时间+10-30分钟，SP+5，被动饱食/口渴消耗 | 无 | 客户端直接执行 |
| 其他 | 无法匹配 | 时间+5分钟，被动饱食/口渴消耗 | 无 | 叙事Agent自由发挥 |

> **被动饱食/口渴消耗**：支线路径的时间推进仍会触发生存状态机的被动消耗机制——每游戏小时消耗饱食度-3、口渴度-4（与主线相同），此为时间推进的自然结果，非行动本身带来的额外消耗。
> 
### 6.2 支线处理流程

```
玩家自由输入文本
        │
        ▼
┌───────────────────────────────────────────────────┐
│ Step 1: 关键字匹配（客户端）                        │
│                                                   │
│ val branchType = classifyFreeInput(userInput)     │
│ // 返回：观察/闲聊/小操作/移动几步/等待/其他        │
└───────────────────────────────────────────────────┘
        │
        ▼
┌───────────────────────────────────────────────────┐
│ Step 2: 按类型处理                                 │
│                                                   │
│ 若为"小操作"/"等待"：                              │
│   → 客户端直接执行，更新状态机                      │
│   → 生成简洁的系统消息告知玩家结果                  │
│   → 流程结束                                      │
│                                                   │
│ 若为"观察"/"闲聊"/"移动几步"/"其他"：              │
│   → 状态机仅推进时间（被动消耗饱食度/口渴度）        │
│   → 不掷骰，不触伤，不触发事件                     │
│   → 进入Step 3                                    │
└───────────────────────────────────────────────────┘
        │
        ▼
┌───────────────────────────────────────────────────┐
│ Step 3: MemOS查询（仅查询相关记忆）                 │
│                                                   │
│ 查询事实记忆：与当前NPC/场景相关                    │
│ 查询关系记忆：与涉及的NPC相关                       │
│ → MemoryList                                      │
└───────────────────────────────────────────────────┘
        │
        ▼
┌───────────────────────────────────────────────────┐
│ Step 4: 仅调用叙事Agent（跳过裁决Agent）            │
│                                                   │
│ User Message中标记"支线-无裁决"                    │
│ 叙事Agent知道这是支线，可以更自由地叙事             │
│ 但不得改变HP、物资等硬数据                         │
│                                                   │
│ 【延迟：1-2秒】                                    │
└───────────────────────────────────────────────────┘
        │
        ▼
┌───────────────────────────────────────────────────┐
│ Step 5: 返回支线叙事，不更新主线关键状态            │
│                                                   │
│ 叙事内容较短（300-500字）                          │
│ 可继续自由输入，或选择返回主线选项                  │
│ 主线选项保留（上一轮suggestions不变）              │
└───────────────────────────────────────────────────┘
```

### 6.3 支线限制与扩展预留

**当前V2.1限制**：
- 支线仅产生最小化状态更新（被动时间消耗）
- 支线不触发事件
- 支线不推进章节
- NPC对话不支持复杂分支

**未来扩展预留**（V3.0方向）：

```
// 扩展点1: NPC人格系统
// 为每个NPC维护独立的：
// - 性格特征（影响对话风格）
// - 知识范围（该NPC知道的信息边界）
// - 情感状态（恐惧、愤怒、友善）
// - 个人目标（想从玩家这里得到什么）

// 扩展点2: 玩家技能系统
// 为玩家角色添加技能数值：
// - 技能列表（如医疗、格斗、潜行等）
// - 技能等级影响DC修正
// - ActionParsed中的skillCheck字段启用

// 扩展点3: 支线激活条件
// 当满足特定条件时，自由输入可触发支线：
// - 与特定NPC对话多次后解锁新话题
// - 在特定地点自由观察可发现隐藏线索
// - 自由输入触发隐藏小型事件

// 扩展点4: 世界背景系统预留
// 未来可增加第三个Agent（氛围Agent）：
// - 根据当前场景状态控制音效
// - 根据天气/昼夜控制视觉效果
// - 根据紧张程度控制动画强度
// 当前V2.1：音效/动画用占位符
```


## 七、UI模式定义

### 7.1 五种UI模式

> 此模式名称用于UI显示，技术内部对应‘裁决Agent调用中’状态”

| 模式名称 | 触发条件 | 视觉特征 | 音效特征 |
|----------|----------|----------|----------|
| **探索模式** | 默认状态，等待玩家输入 | 正常色调，选项可见，自由输入框可用 | 环境白噪音 |
| **命运模式** | 调用裁决Agent期间 | 屏幕边缘变暗，中央齿轮动画，心跳波纹 | 心跳声+低沉嗡鸣 |
| **阅读模式** | 叙事Agent流式输出期间 | 仿旧纸张背景，文字逐字显现，暖黄色调 | 环境音效（风声/雨声/寂静） |
| **危险模式** | nearbyThreats非空或dangerWarnings触发 | 屏幕边缘红色渐变，警示图标闪烁 | 急促心跳声+金属摩擦声 |
| **宁静模式** | 安全区休息状态 | 柔和蓝绿色调，呼吸光晕动画 | 舒缓钢琴/风铃 |

### 7.2 模式切换逻辑

```
探索模式 ──(玩家点击选项)──→ 命运模式
命运模式 ──(裁决完成)────→ 阅读模式
阅读模式 ──(叙事结束)────→ 探索模式（+可能的危险/宁静模式）
探索模式 ──(威胁靠近)────→ 危险模式（叠加于当前模式之上）
探索模式 ──(进入安全区)──→ 宁静模式（替换探索模式）
```


## 八、生存状态机裁决参数表

### 8.1 行动基础DC范围（供裁决Agent参考选取）

| primaryType | 基础DC范围 | 说明 |
|-------------|-----------|------|
| 移动 | 10-30 | 同区域10-15，跨区域25-30 |
| 搜索 | 40-60 | 安全区40，废墟区50，危险区60 |
| 战斗 | 50-70 | 单敌50，多敌60-70 |
| 潜行 | 45-55 | 室内45，室外50，危险区55 |
| 社交 | 30-50 | 友好NPC30，中立40，敌对50 |
| 制作 | 35-55 | 简单物品35，中等复杂45，高复杂度55 |
| 医疗 | 40-60 | 轻伤40，中等伤50，重伤60 |
| 休息 | 5 | 固定值，几乎必定成功 |
| 观察 | 15-25 | 室内15，复杂环境25 |

### 8.2 DC环境修正规则

```
最终DC = 基础DC × (1 + 夜间修正) × (1 + 天气修正) × (1 + 负重修正)

夜间修正：isNight=true → +30%，否则0
天气修正：weather=暴风雨/辐射尘 → +20%，否则0
负重修正：isOverEncumbered=true → +15%，否则0
```

**注意**：当前版本无技能修正，所有技能相关修正均为0。

### 8.3 掷骰结果判定表

| 条件 | 结果 |
|------|------|
| totalValue >= 最终DC + 30 | 大成功 |
| totalValue >= 最终DC | 成功 |
| totalValue >= 最终DC × 0.7 | 部分成功（有代价） |
| totalValue < 最终DC × 0.7 且 >= 最终DC × 0.3 | 失败 |
| totalValue < 最终DC × 0.3 | 大失败 |

### 8.4 资源消耗表

| primaryType | SP消耗 | 额外消耗 |
|-------------|--------|----------|
| 移动（同区域） | -5 | 无 |
| 移动（跨区域） | -20 | 每小时-3饱食度，-4口渴度 |
| 搜索 | -8 | 搜索时间×饱食度/口渴度消耗 |
| 战斗 | -12 | 武器耐久-3~-8 |
| 潜行 | -10 | 无 |
| 社交 | -2 | 无 |
| 制作 | -5~-15 | 所需材料 |
| 医疗 | -5 | 所需医疗物资 |
| 休息 | +15 | 每小时+5饱食度（如有食物） |
| 观察 | -2 | 无 |

### 8.5 伤病附加概率表

| 判定结果 | 无现有伤病 | 已有感染风险 |
|----------|-----------|-------------|
| 大成功 | 0% | 0% |
| 成功 | 0% | 5% |
| 部分成功 | 15% | 25% |
| 失败 | 40% | 55% |
| 大失败 | 80% | 90% |


## 九、开发路线图

### 9.1 阶段划分

| 阶段 | 内容 | 优先级 | 预计工作量 |
|------|------|--------|-----------|
| **Phase 1** | SurvivalStateMachine核心（GameClock, VitalSystem, InventoryManager, HealthSystem, InjuryLibrary） | 🔴 最高 | 5-7天 |
| **Phase 2** | EventDeck + WorldSnapshot + ActionParsed模型 | 🔴 最高 | 4-6天 |
| **Phase 3** | PlotManager基础（第一章配置） | 🟡 高 | 3-4天 |
| **Phase 4** | MemOSAdapter多维度查询 | 🟡 高 | 2-3天 |
| **Phase 5** | 裁决Agent Prompt + API集成 | 🔴 最高 | 3-4天 |
| **Phase 6** | 叙事Agent Prompt重构 + API集成 | 🔴 最高 | 3-4天 |
| **Phase 7** | PromptBuilder重构（强类型参数+双Agent上下文组装） | 🔴 最高 | 2-3天 |
| **Phase 8** | 主线路径完整流程串通 | 🔴 最高 | 3-4天 |
| **Phase 9** | 支线路径实现（关键字匹配+轻量处理） | 🟡 高 | 2-3天 |
| **Phase 10** | UI模式切换框架（占位动画/音效） | 🟢 中 | 2-3天 |
| **Phase 11** | 集成测试与调优 | 🔴 最高 | 4-5天 |
| **Phase 12** | 扩展预留接口（NPC系统/技能系统/氛围系统） | 🔵 低 | 1-2天 |

### 9.2 各阶段验收标准

**Phase 1-2 验收标准**：
- 状态机各子模块单元测试覆盖率≥80%
- InjuryLibrary包含至少15种预设伤病模板
- 能模拟完整行动：actionParsed输入→WorldSnapshot输出
- 数值逻辑正确（HP、SP、饱食度、口渴度递减符合公式）

**Phase 3 验收标准**：
- 从JSON文件加载章节配置
- PlotContext根据WorldSnapshot正确生成
- 章节过渡逻辑通过测试

**Phase 4 验收标准**：
- 三类查询返回格式化记忆文本
- 查询失败降级为空列表
- 格式化符合Prompt要求

**Phase 5-6 验收标准**：
- 裁决Agent返回合法JSON，字段完整，DC计算正确
- 叙事Agent返回合法JSON，story字数≥1000
- suggestions携带完整actionParsed
- 两Agent独立调用均成功

**Phase 7 验收标准**：
- buildReasonerUserMessage接收强类型参数（含clientRollValue）
- buildStorytellerUserMessage接收强类型参数（含裁决后WorldSnapshot）
- 生成的User Message格式正确

**Phase 8 验收标准**：
- 端到端：点击预制选项→状态机→客户端掷骰→裁决Agent→状态机应用→叙事Agent→UI更新
- 连续20轮对话无崩溃
- 物资数量、伤病状态多轮一致

**Phase 9 验收标准**：
- 6种支线类型至少覆盖4种
- 关键字匹配准确率≥80%
- 支线不改变主线硬数据（仅被动时间消耗）

**Phase 10 验收标准**：
- UI可在5种模式间切换（探索/命运/阅读/危险/宁静）
- 占位动画可播放
- 占位音效触发机制就位

**Phase 11 验收标准**：
- 端到端集成测试通过（至少10种不同场景）
- 异常处理覆盖（网络超时、JSON解析失败、状态机异常）
- 性能达标（主线延迟<6秒，支线延迟<2秒）

## 十、V1.0迁移指南

### 10.1 保留的部分

- ✅ Room数据库实体和DAO（可能需要增加字段）
- ✅ DeepSeekApiService网络层（扩展双调用）
- ✅ GameViewModel基本结构（重构协调逻辑）
- ✅ UI层Compose界面（增加模式切换）
- ✅ GmStrategy配置管理
- ✅ MemOS API集成代码（封装为Adapter）
- ✅ JSON响应解析逻辑（适配新契约）

### 10.2 需要修改的部分

- ⚠️ `PromptBuilder`：从单Agent单消息组装改为双Agent双消息组装
- ⚠️ `GameRepository`：增加状态机调用、客户端掷骰、裁决Agent调用、叙事Agent调用
- ⚠️ `GameViewModel`：协调双Agent串行调用+UI状态管理
- ⚠️ Room实体：可能增加字段以持久化新状态数据
- ⚠️ UI：增加模式切换框架（命运模式/阅读模式等）

### 10.3 新增的部分

- 🆕 `SurvivalStateMachine`及所有子模块（GameClock, VitalSystem, InventoryManager, HealthSystem, EventDeck）
- 🆕 `InjuryLibrary`（预设伤病模板库）
- 🆕 `PlotManager`及章节配置文件
- 🆕 `MemOSAdapter`（封装现有MemOS调用+多维度查询）
- 🆕 裁决Agent System Prompt（含独立DC计算规则）
- 🆕 叙事Agent System Prompt（重构）
- 🆕 `ActionParsed`、`ActionResult`等新数据模型
- 🆕 `ClientDiceRoller`掷骰模块（仅生成随机数）
- 🆕 支线关键字分类器
- 🆕 UI模式管理器（ModeManager）

## 十一、风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 裁决Agent输出格式不稳定 | 中 | 流程中断 | `response_format: json_object`强制JSON；解析失败降级为默认失败结果 |
| 叙事Agent suggestions不带actionParsed | 中 | 下一轮无主线选项 | System Prompt强制要求；解析时校验，缺失则客户端生成默认选项 |
| 双Agent串行延迟超预期 | 中 | 用户体验下降 | 时间同步方案将延迟转化为体验；裁决阶段缩短max_tokens；必要时合并为单Agent |
| 状态机规则过于刚性 | 低 | 叙事公式化 | 保留叙事Agent的文学创作自由度；裁决Agent仅提供事实性摘要；EventDeck提供随机事件变量 |
| MemOS查询延迟高 | 低 | 整体流程延长 | 异步并行查询；缓存常见查询；查询失败降级为空列表 |
| 关键字匹配支线分类不准 | 中 | 支线体验差 | 持续优化关键字库；允许用户手动选择支线类型 |


## 十二、总结

V2.1的核心升级：

1. **双Agent架构**：裁决Agent独立完成DC计算和逻辑判定（推理能力要求高），叙事Agent负责文学创作（流式输出体验好）
2. **职责清晰划分**：客户端仅生成随机数，裁决Agent独立裁决，状态机是唯一数据权威，叙事Agent输出辅助性statusUpdate
3. **主线/支线双路径**：预制选项驱动主线（含完整独立裁决流程），自由输入驱动支线（最小化状态更新，无裁决环节）
4. **时间同步**：游戏时间与现实时间按比例映射，等待期用UI模式切换和占位动画填充
5. **记忆融入裁决**：裁决Agent同时接收WorldSnapshot和MemoryList，能根据历史因果和NPC关系进行动态决策
6. **完整参数体系**：ActionParsed→WorldSnapshot→裁决Agent→ActionResult→裁决后WorldSnapshot→叙事Agent→GameResponse，全程结构化数据传递，逻辑对齐
7. **统一伤病管理**：InjuryLibrary预设模板库，裁决Agent仅返回伤病名称，客户端负责完整对象查找和应用
8. **五种UI模式**：探索/命运/阅读/危险/宁静，覆盖不同游戏情境
9. **扩展预留**：为NPC人格系统、玩家技能系统、氛围Agent、支线深度化预留接口

---

## 修订说明

本次修订主要解决以下问题：

1. **裁决职责明确化**：客户端仅生成随机数，DC计算、修正值计算和最终结果判定全部由裁决Agent独立完成，避免“橡皮图章”问题
2. **状态机权威性强化**：明确状态机是游戏数据的唯一权威来源，叙事Agent的statusUpdate为辅助性总结
3. **伤病参数统一化**：引入InjuryLibrary预设模板库，裁决Agent仅返回伤病名称字符串，客户端负责完整对象查找
4. **支线状态影响精准表述**：将“不更新主线状态”修正为“最小化状态更新（仅被动时间消耗）”
5. **UI模式完整定义**：新增第七章，详细说明五种UI模式的触发条件、视觉特征和切换逻辑
6. **用词精准化**：将“硬裁决”统一为“独立裁决”，“软处理”统一为“无裁决/轻量处理”；修正风险章节中的不当表述
7. **扩展计划明晰化**：将暂不实现的技能系统、skillCheck字段明确标注为V3.0扩展内容
8. **数据流一致性**：修正所有流程图中涉及的参数传递路径，确保上下文逻辑自洽