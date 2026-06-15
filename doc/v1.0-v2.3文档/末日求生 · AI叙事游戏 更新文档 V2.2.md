# 末日求生 · AI叙事游戏 更新文档 V2.2

> **版本**：V2.2 游戏化更新
> **基于**：V2.1 双Agent架构
> **核心更新**：角色成长系统 + 动态目标系统 + 资源压力可视化 + NPC深度 + 世界进程感
> **设计原则**：最小侵入、数据驱动、提示词增强


## 一、更新概览

V2.1完成了一个能“正确运行”的叙事引擎。V2.2的目标是让它成为一个“好玩”的游戏。

### 新增系统

| 系统 | 职责 | 核心价值 |
|------|------|----------|
| **技能标签系统** | 记录角色成长，影响裁决结果 | 解决“角色没有成长” |
| **动态目标系统** | 为玩家提供短期驱动力 | 解决“不知道做什么” |
| **资源压力可视化** | 展示物资消耗速率 | 增加决策紧迫感 |
| **NPC记忆深度** | NPC记住历史，拥有需求 | 让世界有“人情味” |
| **世界进程事件** | 阶段性全局事件 | 让世界有“进程感” |

### 设计原则

- **不新增Agent**：所有增强通过扩展现有数据结构和提示词规则实现
- **不改变流程**：主线/支线双路径保持不变
- **数据驱动**：新系统以数据字段形式存在，客户端负责计算，AI负责叙事体现


## 二、技能标签系统

### 2.1 数据结构

```
SkillTag {
    id: String,
    name: String,                    // 如“医疗常识”
    level: Int,                      // 1-5
    description: String,             // 给玩家看的描述
    effects: List<SkillEffect>,      // 对裁决的影响
    acquisitionStory: String,        // 获取该技能的故事背景（供AI引用）
    narrativeHint: String            // AI如何在叙事中体现该技能
}

SkillEffect {
    type: String,                    // "dc_modifier" | "resource_bonus" | "narrative_perk"
    applyTo: String,                 // 适用的primaryType或物品类别
    modifier: Float                  // 修正值（负数=降低DC，正数=增加收益）
}
```

### 2.2 预设技能库

| 技能 | 获取条件 | Lv.1效果 | Lv.3效果 | Lv.5效果 |
|------|----------|----------|----------|----------|
| 医疗常识 | 搜索医疗物资≥3次 | 医疗DC-10% | 医疗消耗品效果+30% | 可识别过期药品 |
| 战斗直觉 | 战斗≥5次 | 战斗DC-5% | 武器耐久消耗-20% | 概率回避致命伤 |
| 拾荒者 | 搜索任意物资≥8次 | 搜索DC-10% | 搜索额外+1件物品 | 可发现隐藏物资点 |
| 察言观色 | 社交≥3次 | 社交DC-10% | 可感知NPC真实意图 | 谈判成功率大幅提升 |
| 潜行本能 | 潜行≥3次 | 潜行DC-10% | 潜行失败可重试一次 | 潜行中可顺手牵羊 |
| 铁打的身板 | 从感染中幸存 | 感染概率-10% | HP自然恢复+50% | 伤病恢复时间-30% |
| 工程师 | 制作/修理≥5次 | 制作DC-10% | 修理效果+50% | 可制作高级物品 |
| 生存大师 | 游戏天数≥7天 | SP消耗-10% | 饱食/口渴消耗-20% | 可预判天气变化 |

### 2.3 获取与升级规则

**获取条件**：
- 技能获取条件由 `PlotManager` 在章节配置中定义
- 当 `completedNodes` 中包含特定节点时，自动附加对应技能
- AI在叙事JSON中可通过 `completedPlotNode` 触发技能获取

**升级规则**：
- Lv.1→Lv.2：对应行动执行次数达到阈值（如医疗5次）
- Lv.2→Lv.3：在极端条件下成功执行对应行动（如重伤时成功医疗）
- Lv.3→Lv.4：对应行动累计成功次数达到更高阈值
- Lv.4→Lv.5：完成与该技能相关的特殊剧情节点

**数值影响**：
- `dc_modifier` 类型效果直接参与DC计算：`最终DC = 基础DC × (1 - skillModifier)`
- `resource_bonus` 类型效果在裁决Agent判定物资数量时生效
- `narrative_perk` 类型效果无数值影响，仅影响AI叙事内容

### 2.4 对现有系统的改动

**PlayerState 增加字段**：
```
skillTags: List<SkillTag>  // 当前拥有的技能标签
```

**WorldSnapshot 增加字段**：
```
activeSkillEffects: Map<String, Float>  // 当前生效的技能效果汇总（key=applyTo，value=累计modifier）
```

**裁决Agent提示词增加规则**：
```
## 7. 技能影响规则（新增）
- 输入的【当前技能】中列出玩家已掌握的技能
- 若技能的 applyTo 匹配当前行动类型，必须应用其 modifier
- dc_modifier：降低最终DC（已反映在掷骰结果中）
- resource_bonus：成功时额外增加物资收益
- narrative_perk：在 narrativeSummary 中提及技能如何影响行动
- 技能等级越高，效果越显著
```

**叙事Agent提示词增加规则**：
```
## 技能体现规则（新增）
- 若玩家拥有与当前行动相关的技能，在叙事中自然体现
- Lv.1-2：轻微体现（“我凭着上次的经验...”）
- Lv.3-4：明显体现（“这已经是我第五次处理伤口了，手法远比最初利落”）
- Lv.5：角色标志性能力（“在这种废墟中找到有用的东西，对我来说已经是本能”）
- 新增技能时，在叙事中突出角色的成长感
```

**User Message 增加段落**：
```
【当前技能】：
- 医疗常识 Lv.2（医疗DC-10%，医疗效果+10%）
- 拾荒者 Lv.1（搜索DC-10%）
```


## 三、动态目标系统

### 3.1 数据结构

```
PersonalGoal {
    id: String,
    name: String,                    // 如“治好感染”
    description: String,             // 目标描述
    type: String,                    // "生存" | "关系" | "探索" | "复仇" | "建造"
    progress: Float,                 // 0.0-1.0
    milestones: List<GoalMilestone>, // 里程碑列表
    reward: GoalReward,              // 完成奖励
    deadline: String?,               // 时间限制描述
    isActive: Boolean,               // 是否当前活跃
    narrativeHook: String            // AI引导玩家追求目标的叙事提示
}

GoalMilestone {
    description: String,             // 如“找到抗生素”
    progressContribution: Float,     // 完成后贡献的进度(0-1)
    completionCondition: String      // 完成条件描述
}

GoalReward {
    skillUnlock: String?,            // 解锁的技能ID
    itemReward: String?,             // 奖励物品
    plotUnlock: String?,             // 解锁的剧情节点
    description: String              // 奖励描述
}
```

### 3.2 目标生命周期

**目标来源**：
1. **AI叙事中自然引出**：叙事Agent在生成叙事时，根据玩家当前困境创建目标
2. **剧情管理器预设**：章节配置中定义该章节的推荐目标
3. **状态驱动自动生成**：客户端检测到紧急状态时自动创建（如“HP<30→目标：寻找医疗物资”）

**目标激活**：
- 同时最多3个活跃目标
- 优先级：紧急状态 > 剧情预设 > AI建议
- 目标可被玩家在UI中手动放弃

**进度更新**：
- 每次行动后，客户端检查是否达成里程碑
- 达成里程碑时，更新进度值
- 进度=1.0时，目标完成

**完成奖励**：
- 客户端直接附加技能标签或物品
- AI叙事中庆祝目标达成
- 已完成目标移入“历史目标”存档

### 3.3 对现有系统的改动

**PlotContext 增加字段**：
```
suggestedGoals: List<PersonalGoal>  // 当前场景建议的目标
urgentGoal: PersonalGoal?           // 紧急目标（如伤病恶化）
```

**叙事Agent提示词增加规则**：
```
## 目标引导规则（新增）
- 检查输入的【活跃目标】，在叙事中自然地引导玩家向目标前进
- 不要生硬地“布置任务”，而是通过角色内心独白暗示
- 目标达成时，在叙事中给予明确的成就感
- 新目标出现时，用环境线索或NPC对话引入
- 在 suggestions 中至少有一个选项与推进目标相关
```

**状态面板增加**：
- 活跃目标列表（带进度条）
- 最近完成的里程碑提示
- 紧急目标高亮闪烁

**GameResponse 增加字段**：
```
statusUpdate.goalUpdate: {
    completedGoals: List<String>,         // 本轮完成的目标ID
    newGoals: List<PersonalGoal>,         // 本轮新增的目标
    progressUpdates: Map<String, Float>   // 目标ID→新进度值
}
```


## 四、资源压力可视化

### 4.1 新增计算字段

在 `WorldSnapshot` 中增加以下计算字段：

```
WorldSnapshot {
    // ... 原有字段 ...

    // 新增：资源压力指标
    foodHoursRemaining: Float,       // 当前食物够吃几小时（按3餐/天计算）
    waterHoursRemaining: Float,      // 当前水够喝几小时
    medicalEmergency: Boolean,       // 是否有需要紧急处理的伤病
    totalThreatLevel: String,        // "安全"|"警惕"|"危险"|"绝境"（综合评估）
    daysSurvived: Int               // 已存活天数
}
```

### 4.2 计算逻辑

```
foodHoursRemaining = 库存食物总饱食度 / 每小时消耗速率
waterHoursRemaining = 库存水总水分 / 每小时消耗速率
medicalEmergency = activeTags中是否有感染/严重伤病
totalThreatLevel = 综合评估（HP、弹药、周边威胁、末日等级）
```

### 4.3 UI展示

状态面板增加：
- “预计存活：X小时” 指示器
- “医疗紧急！” 红色警告（如适用）
- 总威胁等级图标

### 4.4 叙事影响

- 裁决Agent在 `narrativeSummary` 中增加资源紧迫度提示
- 叙事Agent在环境描写中体现资源压力（“肚子又开始咕咕叫了...”）


## 五、NPC记忆深度

### 5.1 数据结构扩展

**NpcRelation 增加字段**：
```
NpcRelation {
    // ... 原有字段 ...
    personalityTags: List<String>,   // 性格标签：["多疑","慷慨","胆小","复仇心强"]
    currentNeed: String?,            // NPC当前需求：“寻找失踪的同伴”
    lastInteractionSummary: String?, // 上次互动摘要
    interactionCount: Int            // 总互动次数
}
```

### 5.2 MemOS查询增强

新增**查询四：NPC专属记忆**：
```
查询类型："npc_memory"
查询词："{npcName} 的 性格 需求 与玩家 互动历史"
返回上限：5条
```

格式化后注入User Message的【相关历史记忆】段落：
```
【NPC记忆 - 军队小队长】：
- 性格：多疑、纪律严明
- 当前需求：执行上级命令，检查所有幸存者
- 互动历史：这是第二次见面，上次他对你的回答不满意
- 上次互动摘要：你含糊其辞地搪塞了他的问题，他显得更加警惕
```

### 5.3 裁决Agent规则增强

```
## 8. NPC深度规则（新增）
- 检查输入的【NPC记忆】，了解该NPC的性格和需求
- 性格标签影响社交DC：友善-10%，多疑+10%，敌意+20%
- NPC的需求会影响对话走向：满足其需求可大幅提升好感度
- 重复互动应体现关系演变：好感度变化应合理
- NPC可能隐瞒真实意图，需结合记忆判断
```

### 5.4 叙事Agent规则增强

```
## NPC深度规则（新增）
- NPC应有独立的说话风格，与其性格标签一致
- NPC有自己的目标，不一定迎合玩家
- 重复见面的NPC应展现关系演变
- NPC也会受伤、恐惧、撒谎、背叛
- 通过肢体语言和环境细节暗示NPC的真实情绪
```


## 六、世界进程事件

### 6.1 数据结构

```
WorldEvent {
    id: String,
    name: String,                    // 事件名称
    triggerDay: Int,                 // 触发天数（游戏内）
    doomLevelRequired: Int,          // 要求的最低末日等级
    description: String,             // 事件描述
    effects: List<WorldEventEffect>, // 对世界状态的影响
    narrativeBroadcast: String,      // 给叙事Agent的全局事件描写指令
    oneTimeOnly: Boolean
}

WorldEventEffect {
    type: String,                    // "doom_level_change"|"resource_scarcity"|"npc_exodus"|"area_blocked"
    target: String,                  // 影响对象
    newValue: Any                    // 新值
}
```

### 6.2 预设事件示例

| 触发天 | 事件 | 效果 |
|--------|------|------|
| 第1天 | 最后广播 | 末日等级确定，世界背景确立 |
| 第3天 | 大规模断电 | 所有电器失效，夜间搜索DC+20% |
| 第5天 | 尸潮预警 | 未来3天内尸潮概率+30% |
| 第7天 | 军队撤离 | 安全区消失，物资点被军方清空 |
| 第10天 | 幸存者广播 | 出现新的NPC聚集点 |
| 第14天 | 疫情恶化 | 所有伤病感染概率+15% |
| 第21天 | 第一次大寒流 | 室外行动SP消耗+50%，夜间DC+30% |
| 第30天 | 文明静默 | 末日等级强制升至最高，所有旧秩序痕迹消失 |

### 6.3 触发与叙事

- `PlotManager` 在每次行动后检查游戏天数，触发符合条件的全局事件
- 触发后，`PlotContext` 的 `worldContext` 字段更新
- 叙事Agent在环境描写中注入全局事件的影响
- 全局事件不可逆，塑造“世界正在死去”的进程感


## 七、对现有流程的变更汇总

### 7.1 状态机新增

| 新增项 | 所在模块 |
|--------|----------|
| 技能效果累计计算 | `SurvivalStateMachine` 新增 `SkillManager` 子模块 |
| 资源消耗速率计算 | `VitalSystem` 新增计算方法 |
| 技能获取条件检查 | `PlotManager` 新增技能触发逻辑 |
| 目标进度检查 | `GameViewModel` 新增 `GoalTracker` |
| 全局事件触发检查 | `PlotManager` 新增 `WorldEventManager` |

### 7.2 提示词变更

| Agent | 新增规则 |
|-------|----------|
| 裁决Agent | 技能影响规则（第7条）、NPC深度规则（第8条） |
| 叙事Agent | 技能体现规则、目标引导规则、NPC深度规则 |

### 7.3 User Message 新增段落

```
【当前技能】：
- {技能名} Lv.{等级}（{效果描述}）

【活跃目标】：
- {目标名}（进度 {百分比}%）：{描述}

【NPC深度记忆 - {NPC名}】：
- 性格：{标签}
- 当前需求：{需求}
- 互动历史：{摘要}

【世界进程】：
- 存活天数：{daysSurvived}
- 当前威胁等级：{totalThreatLevel}
- 资源预估：食物够{hours}小时，水够{hours}小时
- {医疗紧急警告}
```

### 7.4 JSON契约变更

**GameResponse.statusUpdate 增加**：
```json
{
  "goalUpdate": {
    "completedGoals": ["goal_id"],
    "newGoals": [{...}],
    "progressUpdates": {"goal_id": 0.5}
  },
  "skillUpdate": {
    "newSkills": [{"id": "medical_knowledge", "level": 1}],
    "levelUpSkills": [{"id": "scavenger", "newLevel": 2}]
  }
}
```


## 八、开发优先级

| 顺序 | 内容 | 工作量 | 依赖 |
|------|------|--------|------|
| 1 | 技能标签系统（数据模型+提示词规则+预设技能库） | 2-3天 | 无 |
| 2 | 动态目标系统（数据模型+提示词规则+客户端进度检查） | 2-3天 | 无 |
| 3 | 资源压力可视化（WorldSnapshot新增字段+UI展示） | 0.5天 | 1 |
| 4 | NPC记忆深度（MemOS查询增强+提示词规则） | 1-2天 | 无 |
| 5 | 世界进程事件（事件配置+PlotManager触发逻辑） | 2-3天 | 1, 3 |
| 6 | 集成测试与平衡调优 | 2-3天 | 全部 |

**总新增工作量**：约10-15天


## 九、验收标准

1. **技能成长可感知**：玩家能明确感受到角色在变强，AI叙事中有体现
2. **目标有驱动力**：玩家知道“该做什么”，完成后有获得感
3. **资源有紧迫感**：玩家需要做取舍，不能无限囤积
4. **NPC有人情味**：不同NPC有不同性格，互动有回忆
5. **世界在变化**：每次游戏都有独特的进程感

---

V2.2无需新增Agent，无需改变流程，全部改动仅涉及数据扩展和提示词规则增强。可逐模块实现，互不阻塞。