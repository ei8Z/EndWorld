# 末日求生 · AI叙事游戏 更新文档 V2.3

> **版本**：V2.3 物品精确计量系统
> **基于**：V2.2 游戏化更新 + 双Agent流水线
> **核心更新**：物品计量类型系统 + 连续量精确追踪 + 裁决Agent精确消耗输出 + 叙事Agent消耗边界约束
> **设计原则**：裁决Agent权威消耗、状态机安全校验、叙事Agent最佳努力+容错兜底


## 一、更新背景与目标

### 1.1 V2.2的遗留问题

当前系统的物品管理存在计量模糊的问题：

```
玩家有两瓶水，喝了三口 → 当前系统只能记录"半瓶水×1"
玩家有两包饼干，吃了一包 → 当前系统记录"压缩饼干×1"
玩家有一瓶胶水，修补多次 → 当前系统无法处理
```

根本原因：物品的"数量"是单一维度，无法统一描述连续量（液体体积、固体重量、工具耐久）和离散量（饼干包数、子弹发数）的差异。

### 1.2 V2.3设计目标

1. **精确计量**：每件物品拥有独立的计量类型和当前余量
2. **裁决权威**：消耗量由裁决Agent在ActionResult中精确输出，客户端只做安全校验
3. **叙事边界**：叙事Agent在安全边界内创作，越权消耗被轻量检测但不阻断
4. **向后兼容**：现有count类型物品（饼干、罐头）逻辑不变


## 二、核心设计：裁决Agent精确消耗输出

### 2.1 架构原则重申

```
物品消耗的权威来源：裁决Agent → ActionResult.resourceChanges
客户端职责：安全校验 + 精确执行 + 容错兜底
叙事Agent职责：基于ActionResult进行文学创作，遵守消耗边界
```

**与V2.2一致**：所有状态变更仍以状态机为权威。V2.3的升级在于让裁决Agent能够输出精确的连续量消耗（如-150ml），而非仅输出离散量（如-1瓶）。

### 2.2 裁决Agent的新能力

裁决Agent在判定资源变化时，需要根据物品的计量类型输出不同精度的消耗：

| 计量类型 | 消耗输出格式 | 示例 |
|----------|-------------|------|
| `count` | Float（逻辑上为-1.0） | `changeAmount: -1.0`（吃了一包饼干） |
| `volume` | Float（毫升） | `changeAmount: -150.0`（喝了150ml水） |
| `weight` | Float（克） | `changeAmount: -30.0`（用了30g面粉） |
| `durability` | Float（百分比） | `changeAmount: -8.0`（武器耐久-8%） |
| `ammo` | Float（逻辑上为整数发） | `changeAmount: -3.0`（打了3发子弹） |

> **注意**：`changeAmount` 和 `newValue` 字段类型统一为 Float。count/ammo 类型虽逻辑上是整数，但字段中统一用 Float（-1.0, -3.0）表示，客户端在 displayText 中按需取整展示。


## 三、数据模型变更

### 3.1 新建物品模板数据结构

**变更类型**：**新建** `ItemTemplate` 数据类及 `game_config.json` 中对应的物品配置段。当前项目中尚无物品模板体系——`GameConfig` 仅包含性格、末日等级等信息，物品定义分散在各处硬编码。V2.3 将其统一提取为配置驱动的模板。

**新建数据结构**（建议放在 `data/model/ItemTemplate.kt`）：

```
ItemTemplate：
├── id: String                 // 模板ID，如 "bottled_water"、"compressed_biscuit"
├── name: String               // 显示名称："瓶装水"、"压缩饼干"
├── category: String           // "食物"|"饮品"|"武器"|"工具"|"医疗"|"特殊"
├── measureType: String        // "count"|"volume"|"weight"|"durability"|"ammo"
├── measureUnit: String        // 显示单位："包"|"毫升"|"克"|"发"|"%"
├── totalMeasure: Float        // 单件物品的总容量（一瓶水=500.0ml、一包饼干=1.0包）
├── unitNutrition: Float?      // 每 measureUnit 提供的饱食度（如每毫升水=0.0，每克饼干≈0.05）
├── unitHydration: Float?      // 每 measureUnit 提供的水分（如每毫升水=1.0，每克饼干=0.0）
├── weight: Float              // 单件物品重量(kg)
├── typicalUseAmount: Float?   // 典型单次使用量（喝水约150ml、吃饼干约30g）
└── minUseAmount: Float?       // 最小使用量（抿一口约10ml）
```

**game_config.json 新增配置段**：
```json
{
  "items": [
    { "id": "bottled_water", "name": "瓶装水", "category": "饮品", "measureType": "volume",
      "measureUnit": "毫升", "totalMeasure": 500.0, "unitNutrition": 0.0, "unitHydration": 1.0,
      "weight": 0.5, "typicalUseAmount": 150.0, "minUseAmount": 10.0 },
    { "id": "compressed_biscuit", "name": "压缩饼干", "category": "食物", "measureType": "count",
      "measureUnit": "包", "totalMeasure": 1.0, "unitNutrition": 10.0, "unitHydration": 0.0,
      "weight": 0.2 }
  ]
}
```

**与既有 `InventoryItemModel` 字段的关系**：
- `unitNutrition` / `unitHydration` **替代** `InventoryItemModel` 中原有的 `nutrition: Int?` 和 `hydration: Int?` 字段。旧字段表示单件物品的绝对营养/水分值；新字段为每单位值，实际营养 = `unitNutrition × currentMeasure`。
- `WorldStateProvider.calculateFoodHours()` 和 `calculateWaterHours()` 的公式需同步更新：从 `nutrition × quantity` 改为 `unitNutrition × currentMeasure`。
- 旧 `nutrition`/`hydration` 字段在迁移期保留但标记 `@Deprecated`，待 Phase 8 完成全部迁移后移除。

**向后兼容**：现有 `count` 类型物品（饼干、罐头、绷带）的 `totalMeasure` 设为 `1.0`，`measureUnit` 设为物品自然单位（"包"/"盒"/"条"），逻辑完全不变。旧存档中缺失计量字段的物品在首次加载时自动补全（见 §9.2）。

### 3.2 背包物品实例扩展

**变更类型**：扩展 `InventoryItem`（Room 实体 `data/local/entity/InventoryItem.kt`）和 `InventoryItemModel`（领域模型 `data/model/WorldSnapshot.kt`）两个物品数据类。

> **注意**：项目中存在两个物品数据类——
> - `InventoryItem`：Room 数据库实体，负责持久化
> - `InventoryItemModel`：领域模型，在 `WorldSnapshot` 和 `InventoryManager` 中使用
> 
> 两个类都需要扩展，并在 `GameRepository.seedStateMachine()` 中维护双向转换逻辑。

**InventoryItemModel 新增字段**（运行时使用）：
```
InventoryItemModel 新增：
├── measureType: String        // 从模板继承
├── measureUnit: String        // 从模板继承
├── totalMeasure: Float        // 该实例的总容量
├── currentMeasure: Float      // 当前余量（与totalMeasure相同单位）
└── isStackable: Boolean       // 是否可与其他同物品合并
```

**InventoryItem (Room实体) 新增字段**（持久化）：
```
InventoryItem 现有字段：
├── id: Long (自增主键)
├── itemId: String             // ⚠️ 已存在，非新增
├── category: String
├── name: String
├── quantity: Int              // count类型继续使用
├── unit: String

InventoryItem 新增字段：
├── measureType: String        // 计量类型
├── measureUnit: String        // 计量单位
├── totalMeasure: Float        // 总容量
├── currentMeasure: Float      // 当前余量
└── isStackable: Boolean       // 是否可合并
```

**关键设计**：
- `itemId` 已存在于当前 `InventoryItem` 实体中（字符串类型），在物品获得时由客户端生成（UUID或自增ID），终身不变。**此字段非 V2.3 新增。**
- `count` 类型物品的 `totalMeasure` 和 `currentMeasure` 均为 `1.0`，消耗时直接移除实例
- `volume` 类型物品（如水）的 `currentMeasure` 在每次消耗后扣减，归零时移除实例
- `quantity: Int` 字段保留供 count 类型使用，volume 类型以 `currentMeasure` 为准

### 3.3 ActionResult资源变化扩展

**变更类型**：扩展 `ActionResult.resourceChanges` 中的条目结构。

**原有字段**（保留）：
```
ResourceChange {
    itemId: String,
    itemName: String,
    category: String,
    changeAmount: Int,        // V2.2为整数，V2.3改为Float
    newValue: Int,
    maxValue: Int?,
    narrativeHint: String
}
```

**V2.3变更**：

| 字段 | 变更 | 说明 |
|------|------|------|
| `changeAmount` | 类型从 `Int` 改为 `Float` | 支持连续量（-150.0ml）和离散量（-1.0包） |
| `newValue` | 类型从 `Int` 改为 `Float` | 消耗后的新余量值 |
| `measureType` | **新增** String | 计量类型，便于客户端校验 |
| `measureUnit` | **新增** String | 计量单位，用于叙事提示 |

**级联变更清单**（`changeAmount` 改为 Float 会影响的上下游）：

| 受影响位置 | 当前类型 | 变更 |
|-----------|----------|------|
| `ResourceChange.changeAmount` | `Int` | → `Float` |
| `ResourceChange.newValue` | `Int` | → `Float` |
| `InventoryItemModel.quantity` | `Int` | 保留（count 类型），新增 `currentMeasure: Float` |
| `InventoryManager.consumeItem(itemId, amount: Int)` | 参数 `Int` | 新增重载 `consumeItemMeasure(itemId, amount: Float)` |
| `InventoryItem.quantity` (Room) | `Int` | 保留，新增 `currentMeasure: Float` 列 |
| `VitalSystem.applyResourceChange()` | 接收 `Int` changeAmount | 对 vitals（HP/SP/hunger/thirst）取整：`roundToInt()` |
| `ItemUpdate.quantity` (GameResponse) | `Int` | 保留兼容，叙事Agent statusUpdate 中 quantity 仍为大致整数 |

**裁决Agent输出的resourceChanges示例**：

```json
{
  "resourceChanges": [
    {
      "itemId": "item_002",
      "itemName": "瓶装水",
      "category": "饮品",
      "measureType": "volume",
      "measureUnit": "毫升",
      "changeAmount": -150.0,
      "newValue": 350.0,
      "maxValue": 500.0,
      "narrativeHint": "灌了一大口，水壶轻了不少"
    },
    {
      "itemId": "item_001",
      "itemName": "压缩饼干",
      "category": "食物",
      "measureType": "count",
      "measureUnit": "包",
      "changeAmount": -1.0,
      "newValue": 0.0,
      "maxValue": 1.0,
      "narrativeHint": "撕开包装，一整包饼干下了肚"
    }
  ]
}
```

### 3.4 WorldSnapshot中物品段落的扩展

**变更类型**：扩展 `WorldSnapshot` 中传递给裁决Agent的物品列表格式。

**V2.3格式**（裁决Agent的User Message中）：

```
【随身物资 — 精确计量】：
- [item_001] 压缩饼干 × 1包 (count)
- [item_002] 瓶装水 350/500毫升 (volume, 实例item_002)
- [item_003] 瓶装水 500/500毫升 (volume, 实例item_003, 满)
- [item_004] 棒球棍 72/100% (durability)
- [item_005] 胶水 35/50毫升 (volume, 实例item_005)
- [item_006] 9mm子弹 × 7发 (ammo, 可合并)
```

> **格式说明**：count 类型 `totalMeasure=1.0` 天然不可合并，无需额外标注；volume/durability 类型显示 `当前/总容量`；ammo 类型可合并（多盒子弹合并计数）。

**关键信息**：每个物品必须携带 `itemId`、`currentMeasure/totalMeasure`、`measureType`，以便裁决Agent精确引用。

**前置依赖**：`nearbyThreats`、`nearbyNpcs`、`nearbyResources` 当前在 `WorldStateProvider.kt:51-53` 中硬编码为空列表。NPC 深度记忆功能和周边威胁感知依赖这些字段。V2.3 需要实现周边感知逻辑，至少支持 `nearbyNpcs` 的填充。


## 四、裁决Agent提示词变更

### 4.1 新增规则：精确计量消耗

在裁决Agent的系统提示词中，**替换**原有的"物资校验规则"（当前提示词中的第4条规则），新增为：

```
## 2. 物资校验与精确计量规则（V2.3升级）

### 2.1 物品识别
- 输入的【随身物资】中，每件物品都带有 itemId、measureType、currentMeasure、totalMeasure
- 消耗任何物品前，必须确认该 itemId 存在且 currentMeasure 足够

### 2.2 消耗量判定（按计量类型）
- **count类型**（饼干、罐头、绷带）：每次消耗 changeAmount = -1.0，newValue = 0.0（直接用完一个）
- **volume类型**（水、酒精、胶水、药剂）：
  * 根据行动描述判定消耗量
  * 小口/少量：30-50ml
  * 一口/正常：80-120ml
  * 大口/大量：150-200ml
  * 喝饱/用完：全部剩余量
  * 判定后输出精确的 changeAmount（如-150.0），newValue = currentMeasure + changeAmount
- **weight类型**（面粉、糖、药品粉末）：
  * 少量：10-20g
  * 正常：30-60g
  * 大量：80-150g
- **durability类型**（武器、工具）：
  * 轻度使用：-3%~-5%
  * 正常使用：-5%~-10%
  * 粗暴使用：-10%~-20%
  * newValue = currentMeasure + changeAmount
- **ammo类型**（子弹、箭矢）：
  * 根据战斗描述判定消耗发数
  * 单发：-1
  * 短点射：-3
  * 持续射击：-5~-10

### 2.3 消耗后处理
- 若 newValue <= 0，物品耗尽，标记为"消耗完毕"
- 若 newValue > 0，物品保留，currentMeasure更新为newValue
- 必须在 narrativeHint 中描述消耗后的余量状态（如"瓶子里大概还有一半"）

### 2.4 多实例选择
- 若玩家有多个同物品的volume实例（如两瓶水），优先消耗 currentMeasure 较少的实例（先喝完一瓶再开另一瓶）
- 在 resourceChanges 中明确引用消耗的 itemId
```

### 4.2 原有规则保留

裁决Agent的其他规则（世界时钟、伤病判定、事件触发、剧情节点、技能影响、NPC深度）保持不变。


## 五、叙事Agent提示词变更

### 5.1 新增规则：消耗描述边界

在叙事Agent的系统提示词中，**新增**以下规则（作为独立段落，插入到"叙事风格要求"之后，"建议选项生成规则"之前）：

```
## 消耗描述边界规则（V2.3新增 — 严格遵守）

### 5.1 核心原则
- 裁决结果中已经列出本轮行动的所有物资变化（resourceChanges），这是你描述消耗的唯一依据。
- 你只能描述裁决结果中已列出的消耗。裁决结果未列出的物品，不得描述其被使用或消耗。

### 5.2 允许的例外（不可量化的微小动作，不视为消耗）
以下微小动作可在叙事中自由使用，不计入实际消耗：
- "抿了抿干裂的嘴唇"、"舔了舔嘴角的水渍"
- "握紧了匕首的刀柄"（未实际使用）
- "瞥了一眼背包里的罐头"（未取出）
- "擦了擦额头上的汗"
- "深吸了一口气"

### 5.3 严禁的越权描述
以下描述在裁决结果未列出对应消耗时，属于越权，不得出现：
- "吃掉了半包饼干"、"啃了一口干粮" — 若裁决未列出食物消耗
- "子弹打空了"、"射出了三发子弹" — 若裁决未列出弹药消耗
- "用胶水修补了背包" — 若裁决未列出胶水消耗
- "喝光了最后一口水" — 若裁决未列出饮水消耗
- "绷带被血浸透了，不得不换一条" — 若裁决未列出绷带消耗

### 5.4 不确定时的处理
- 若你不确定某个动作是否构成"消耗"，倾向于不描述该动作，或将其描述为"未遂的意图"
- 示例："我想打开那包饼干，但还是忍住了"（未消耗，安全）
- 示例："手指摩挲着水壶的盖子"（未消耗，安全）

### 5.5 消耗后余量的描述
- 若裁决结果中某物品的 newValue 表明已耗尽（newValue <= 0），可在叙事中明确提及"空了"、"吃完了"、"用光了"
- 若 newValue 仍大于0，描述余量时应与裁决结果一致：
  * newValue ≈ 50% totalMeasure → "大概还有半瓶"
  * newValue ≈ 25% totalMeasure → "只剩下一个底儿了"
  * newValue ≈ 10% totalMeasure → "几乎见底了"
```

### 5.2 原有规则保留

叙事Agent的其他规则（视角、五感描写、节奏控制、成人内容处理、技能体现、目标引导、NPC深度）保持不变。


## 六、客户端处理逻辑变更

### 6.1 状态机新增：ConsumptionValidator

**新增模块**：`ConsumptionValidator`（消耗校验器），在 `applyActionResult` 之前调用。

**职责**：
1. 遍历 `ActionResult.resourceChanges`
2. 对每条资源变化，在背包中查找 `itemId` 对应的物品实例
3. 校验：`changeAmount` 的绝对值是否 ≤ `currentMeasure`
4. 校验通过：执行消耗，更新 `currentMeasure`，若归零则移除实例
5. 校验失败（如裁决Agent要求消耗200ml但只剩150ml）：记录异常日志，消耗全部余量（150ml），标记为"容错兜底"

**处理逻辑**（伪代码）：

```
// 新增方法：InventoryManager.consumeItemMeasure（Float版本）
// 与现有 consumeItem(itemId, amount: Int) 并存，count类型走旧方法，volume/weight/durability走新方法
fun validateAndApply(change: ResourceChange, inventory: List<InventoryItemModel>): ApplyResult {
    val instance = inventory.find { it.id == change.itemId }
        // 注意：itemId 可能因 DB 持久化而改变，InventoryManager 内部有回退按 name 匹配的逻辑
    
    if (instance == null) {
        // 实例不存在 — 记录异常，跳过
        return ApplyResult.Skipped("实例不存在: ${change.itemId}")
    }
    
    val requestedAmount = abs(change.changeAmount)
    val actualConsumption = minOf(requestedAmount, instance.currentMeasure)
    
    if (actualConsumption < requestedAmount) {
        // 裁决Agent要求的消耗超过实际余量 — 容错兜底
        logWarning("消耗超额: 要求${requestedAmount}${change.measureUnit}, 实际只有${instance.currentMeasure}${change.measureUnit}")
    }
    
    instance.currentMeasure -= actualConsumption
    
    if (instance.currentMeasure <= 0f) {
        inventory.remove(instance)
        return ApplyResult.Removed(instance)
    }
    
    return ApplyResult.Updated(instance)
}
```

### 6.2 叙事校验：NarrativeConsumptionValidator

**新增模块**：`NarrativeConsumptionValidator`（叙事消耗校验器），在收到叙事Agent响应后调用。

**职责**：
1. 扫描 `story` 文本，提取提到的物品使用关键词
2. 与 `ActionResult.resourceChanges` 比对
3. 发现越权描述时，记录 `warning` 级别日志到 `debug_json`
4. **不阻断流程，不修改状态，不在UI中展示**

**处理逻辑**（伪代码）：

```
fun validate(story: String, actionResult: ActionResult): List<Discrepancy> {
    val discrepancies = mutableListOf<Discrepancy>()
    
    // 提取叙事中提到的物品消耗关键词
    val mentionedConsumptions = extractConsumptionKeywords(story)
    
    // 提取裁决结果中已消耗的物品名称
    val authorizedConsumptions = actionResult.resourceChanges
        .filter { it.changeAmount < 0 }
        .map { it.itemName.lowercase() }
        .toSet()
    
    // 比对
    for (mentioned in mentionedConsumptions) {
        if (mentioned.itemName !in authorizedConsumptions) {
            discrepancies.add(Discrepancy(
                itemName = mentioned.itemName,
                narrativeFragment = mentioned.context,
                severity = "warning",
                reason = "叙事中提到消耗但裁决结果未列出"
            ))
        }
    }
    
    return discrepancies
}
```

**关键词提取规则**（正则匹配）：
- 食物消耗：`吃了`、`啃了`、`咽下`、`吞下` + 食物名称
- 饮品消耗：`喝了`、`饮下`、`灌了` + 饮品名称
- 工具使用：`用...撬`、`用...砸`、`用...修补` + 工具名称
- 弹药消耗：`射出`、`打空`、`开火` + 弹药相关词

### 6.3 物品合并提示

**新增功能**：背包中同名物品的 volume 类型实例合并检测。

**触发时机**：每次 `applyActionResult` 完成后。

**逻辑**：
1. 扫描背包中所有 `measureType == "volume"` 的物品
2. 找到同**名称**（name）且 `isStackable == true`、有多个实例的物品（注意：`itemId` 是实例唯一ID，同名物品的 itemId 不同——如两瓶水为 `item_002` 和 `item_003`）
3. 在 `WorldSnapshot` 中**新增** `suggestedActions: List<String>` 字段，加入提示："你可以将两瓶水合并（共XXX毫升）"
4. 玩家可通过支线行动（"合并水瓶"）触发合并

**合并执行**（客户端直接处理，不经过AI）：
- 选择 currentMeasure 最小的实例为目标
- 将其他同物品实例的 currentMeasure 累加到目标
- 移除其他实例
- 无需调用AI，直接更新背包状态


## 七、GameResponse JSON契约变更

### 7.1 inventory段落的扩展

**变更类型**：`statusUpdate.resources.inventory` 中物品的表示从简单字符串升级为结构化对象。

**V2.2格式**（旧）：
```json
{
  "drinks": [{"name": "半瓶水", "quantity": "1"}]
}
```

**V2.3格式**（新）：
```json
{
  "drinks": [
    {
      "name": "瓶装水",
      "itemId": "item_002",
      "measureType": "volume",
      "measureUnit": "毫升",
      "totalMeasure": 500.0,
      "currentMeasure": 350.0,
      "displayText": "瓶装水 350/500毫升"
    }
  ]
}
```

**新增字段说明**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `itemId` | String | 物品实例唯一ID |
| `measureType` | String | 计量类型 |
| `measureUnit` | String | 计量单位 |
| `totalMeasure` | Float | 总容量 |
| `currentMeasure` | Float | 当前余量 |
| `displayText` | String | UI展示用的格式化文本 |

**向后兼容**：`count` 类型物品的 `displayText` 保持原有风格（"压缩饼干 × 1包"），UI层优先使用 `displayText`，确保界面展示不受影响。

### 7.2 叙事Agent输出中的消耗描述

叙事Agent的 `story` 文本中，消耗相关的描述应遵循"最佳努力+容错兜底"原则。客户端通过 `NarrativeConsumptionValidator` 进行轻量校验，越权消耗仅记录日志，不阻断流程。

### 7.3 ActionResult中的resourceChanges

已在第四节详述，核心变更：`changeAmount` 和 `newValue` 从 `Int` 改为 `Float`，新增 `measureType`、`measureUnit` 字段。


## 八、对现有系统的增量变更汇总

### 8.1 数据模型

| 变更项 | 影响范围 | 变更类型 |
|--------|----------|----------|
| **新建** ItemTemplate 数据类 | `data/model/ItemTemplate.kt`（新文件）+ `game_config.json` 新增 items 配置段 | 新建 |
| InventoryItemModel 扩展 | `WorldSnapshot.kt` 中的数据类 + `InventoryManager` | 新增计量字段 |
| InventoryItem 扩展（Room实体） | `entity/InventoryItem.kt` + DAO + 数据库 Migration | 新增列 + 迁移 |
| ActionResult.ResourceChange 扩展 | 裁决Agent输出结构 + `VitalSystem.applyResourceChange()` + `InventoryManager` | 字段类型 Int→Float + 新增字段 |
| WorldSnapshot 物品段落格式 | `PromptBuilder.formatInventory()` | 格式扩展 |
| WorldSnapshot 新增 suggestedActions | `WorldSnapshot.kt` + `WorldStateProvider` | 新增字段 |

### 8.2 提示词

| 变更项 | 影响Agent | 变更类型 |
|--------|-----------|----------|
| 精确计量消耗规则 | 裁决Agent | 替换原有"物资校验规则" |
| 消耗描述边界规则 | 叙事Agent | 新增独立规则段落 |

### 8.3 客户端模块

| 变更项 | 位置 | 变更类型 |
|--------|------|----------|
| ConsumptionValidator | `SurvivalStateMachine` | 新增子模块 |
| InventoryManager.consumeItemMeasure() | `InventoryManager` | 新增 Float 版本方法 |
| NarrativeConsumptionValidator | `GameRepository` 或 ViewModel | 新增校验模块 |
| 物品合并提示 + suggestedActions | `WorldStateProvider` + `WorldSnapshot` | 新增字段 + 检测逻辑 |
| 背包展示格式 | `PromptBuilder.formatInventory()` | displayText优先，旧格式兼容 |
| 周边感知（nearbyNpcs） | `WorldStateProvider` | 新增实现（V2.2遗留空壳） |

### 8.4 不变的部分

- ✅ 双Agent流水线架构（processPreRuling → Reasoner → Storyteller）
- ✅ 数据权威分层（状态机权威，AI软数据）
- ✅ 客户端掷骰（ClientDiceRoller）
- ✅ 被动消耗累加器
- ✅ NPC深度记忆
- ✅ 技能系统、目标追踪、剧情管理、世界事件
- ✅ count类型物品的现有逻辑
- ✅ `ItemUpdate.quantity: Int` 保留（叙事Agent statusUpdate 中为软数据，不用于权威同步）


## 九、实施建议

### 9.1 分阶段实施

| 阶段 | 内容 | 优先级 | 预计工作量 |
|------|------|--------|-----------|
| **Phase 1** | ItemTemplate + InventoryItem 数据模型扩展 | 🔴 必须 | 1-2天 |
| **Phase 2** | 裁决Agent提示词更新 + ActionResult结构扩展 | 🔴 必须 | 1天 |
| **Phase 3** | ConsumptionValidator实现 | 🔴 必须 | 1天 |
| **Phase 4** | WorldSnapshot物品段落格式更新 | 🔴 必须 | 0.5天 |
| **Phase 5** | 叙事Agent提示词更新 | 🟡 高 | 0.5天 |
| **Phase 6** | NarrativeConsumptionValidator实现 | 🟡 高 | 0.5天 |
| **Phase 7** | UI层displayText适配 + 物品合并提示 | 🟢 中 | 1天 |
| **Phase 8** | 集成测试与调优 | 🔴 必须 | 2天 |

**总新增工作量**：约7-9天

### 9.2 向后兼容策略

1. **数据库迁移**：使用 Room Migration 增加 `measureType`、`measureUnit`、`totalMeasure`、`currentMeasure`、`isStackable` 列（Migration 版本号视当前 `AppDatabase` 实际版本而定，需 `@Database(version = N)` 递增）。为现有物品设置默认计量字段（count 类型全部设为 `totalMeasure=1.0, currentMeasure=1.0, measureType="count", isStackable=false`）
2. **旧存档兼容**：首次加载时在 `GameRepository.seedStateMachine()` 中自动补充缺失的计量字段，从 `game_config.json` 的 ItemTemplate 查找对应模板
3. **API响应兼容**：客户端解析 `resourceChanges` 时，若 `changeAmount` 为整数类型，自动转换为 `Float`；解析旧存档的 `ItemUpdate.quantity`（Int）时，自动映射为 `currentMeasure.toInt() * totalMeasure`
4. **UI兼容**：`displayText` 优先，若缺失则用旧格式拼接（`"${name}×${quantity}${unit}"`）

### 9.3 测试场景

| 场景 | 预期行为 |
|------|----------|
| 喝水（单瓶） | 裁决Agent输出精确ml消耗，状态机扣减，余量正确 |
| 喝水（多瓶） | 裁决Agent优先消耗余量少的瓶子，客户端正确追踪各实例 |
| 吃饼干（count） | 裁决Agent输出-1，客户端移除实例（逻辑不变） |
| 胶水多次使用 | 每次裁决Agent输出ml消耗，客户端累减至归零 |
| 裁决Agent要求超额消耗 | 客户端兜底：消耗全部余量，记录warning日志 |
| 叙事Agent越权描述消耗 | 客户端校验记录warning，不阻断，不修改状态 |
| 合并水瓶 | 客户端直接处理，不调用AI，背包正确更新 |


## 十、验收标准

1. **精确计量**：连续量物品（水、胶水、弹药）的余量精确到毫升/克/百分比/发
2. **裁决权威**：所有消耗由裁决Agent在ActionResult中输出，客户端校验但不自行估算
3. **叙事边界**：叙事Agent在提示词约束下不越权描述消耗，越权发生时客户端记录但不影响游戏
4. **向后兼容**：count类型物品逻辑不变，旧存档可正常加载
5. **多实例追踪**：同物品多实例（两瓶水）的消耗被正确追踪到具体实例
6. **物品合并**：玩家可合并同物品volume实例
