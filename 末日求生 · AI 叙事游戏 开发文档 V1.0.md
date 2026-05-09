# 末日求生 · AI 叙事游戏 开发文档 V1.0

**角色**：单人 PvE 沉浸式叙事 RPG  
**核心引擎**：DeepSeek V4 API（非流式，MVP 阶段）  
**架构模式**：MVVM + Repository + Room 本地持久化  
**UI 框架**：Jetpack Compose  
**运行环境**：Android 8.0+ (API 26)

---

## 一、产品概述

玩家在末日背景下自由行动，通过输入或选择行动建议与 AI 城主互动。AI 城主内部集成“裁决者思维”，既负责讲述第一人称故事，又同时完成事件判定、物资消耗和记忆更新。所有游戏状态持久化在本地数据库，确保重启不丢失进度。

---

## 二、技术选型与约束

| 类别 | 选择 | 原因 |
|------|------|------|
| 网络调用 | Retrofit + Gson | 成熟稳定，同步非流式调用 |
| AI 通信格式 | JSON 请求 / JSON 响应 | 配合 `response_format: json_object` 强制 100% 合法 JSON |
| 本地数据库 | Room | Google 官方 ORM，支持 Flow 观察 |
| UI | Jetpack Compose | 声明式 UI，与 ViewModel/Flow 天然集成 |
| 线程调度 | Kotlin Coroutines | `viewModelScope` / `Dispatchers.IO` |
| 安全 | 本地 Properties 或加密 SharedPreferences | 保护 API Key |

> **MVP 阶段不启用流式输出**。等主循环跑通后，再通过 SSE 升级。

---

## 三、项目模块与职责划分

```
com.example.doomsurvival
├── data
│   ├── local          (Room 数据库、DAO、实体)
│   ├── remote         (Retrofit API 接口、请求/响应模型)
│   └── repository     (游戏状态聚合与更新逻辑)
├── domain
│   └── model          (CurrentGameState 等组装用数据类)
├── ui
│   ├── screen         (主游戏界面、初始化界面)
│   ├── viewmodel      (GameViewModel)
│   └── components     (可复用 Compose 组件)
└── util               (PromptBuilder、时间工具等)
```

**分层职责**：
- **ui**：仅负责展示与交互，不直接操作数据。
- **viewmodel**：持有 UI 状态，处理用户意图，调用 Repository。
- **repository**：执行单次 API 请求，并将返回结果**完整更新**到本地数据库。是唯一有权同时操作网络和本地数据的层。
- **local**：提供所有数据库查询的挂起函数或 Flow。
- **remote**：定义与 DeepSeek API 对应的请求体和响应体。

---

## 四、数据架构

### 4.1 本地持久化 (Room)

| 表名 | 主键 | 核心字段 | 说明 |
|------|------|---------|------|
| `player_state` | id=1 (单例) | name, gender_physique, personality, base_description, doom_type, doom_level, mental_state_tags(JSON), inner_thought, equipped_items(JSON) | 玩家核心属性 |
| `inventory` | 自增 id | category, name, quantity, description | 随身物资清单 |
| `memories` | 自增 id | timestamp, location, event, result, is_permanent | 记忆系统 |
| `npc_relations` | 自增 id | name, relation, affinity | 角色好感度 |
| `world_info` | id=1 (单例) | current_date, current_time, location_scene, macro_situation, known_points(JSON) | 当前世界宏观状态 |

- `mental_state_tags` 存储为 JSON 字符串列表，例如 `["轻微饥饿","左臂擦伤"]`。
- `equipped_items` 同理存储为 JSON 字符串列表。
- `known_points` 存储为 JSON 数组，结构 `[{"location":"...","possibleItems":"...","risk":"..."}]`。

**DAO 关键操作**：
- `PlayerDao`：`getPlayer()`, `updatePlayerStates(...)`
- `InventoryDao`：`deleteAll()`, `insertAll(...)` （用于每次全新覆盖）
- `MemoryDao`：`getRecentTemporary(limit)`, `deleteExtraTemporary(maxCount)` （维护 FIFO 队列）
- `NpcDao`：`updateAffinity(name, delta)`
- `WorldDao`：`updateWorldInfo(...)`

### 4.2 通信数据模型 (Remote)

**请求体 ChatRequest**：
```json
{
  "model": "deepseek-chat",
  "messages": [
    {"role": "system", "content": "系统提示词（静态部分 + 当前末日参数）"},
    {"role": "user", "content": "组装后的玩家行动+完整状态快照"}
  ],
  "response_format": {"type": "json_object"},
  "reasoning_effort": "medium"
}
```

**响应体 GameResponse**（要求 AI 严格输出的结构）：
```
{
  "header": {"date": "...", "time": "...", "location": "..."},
  "story": "第一人称叙事文本（>1000字）",
  "suggestions": ["保守策略", "激进策略", "环境/特长策略"],
  "statusUpdate": {
    "player": {
      "stateTags": ["标签1", "标签2"],
      "innerThought": "内心独白",
      "equipment": ["携带的物品名称"]
    },
    "world": {
      "doomLevel": 整数,
      "macroSituation": "宏观叙事"
    },
    "resources": {
      "knownPoints": [{"location": "..", "possibleItems": "..", "risk": ".."}],
      "inventory": {
        "food": [{"name": "...", "quantity": ".."}],
        "drinks": [{"name": "...", "quantity": ".."}],
        "weapons": [{"name": "...", "quantity": ".."}],
        "special": [{"name": "...", "quantity": ".."}]
      }
    },
    "memoryUpdate": {
      "newTempMemory": "时间@地点 事件->结果",
      "newPermanentPlot": "可选的重要剧情摘要"
    }
    "npcUpdate": {
      "name": "李湘"
      "relation": "友好"
      "affinityDelta": "50"
     }
  }
}
```

**契约**：`response_format: json_object` 确保返回纯 JSON，无额外文本。客户端可以直接用 Gson 反序列化为此结构。

### 4.3 领域组装模型 CurrentGameState

每次请求前，Repository 从各 DAO 查询最新数据，组装成一个普通数据类：
```
CurrentGameState {
    player: PlayerState,
    world: WorldInfo,
    inventory: List<InventoryItem>,
    recentMemories: List<Memory>,
    knownPoints: String (直接取 world_info 中的 JSON)
}
```
这个对象被传递给 `PromptBuilder`，用于生成用户消息。

---

## 五、提示词设计 (内置裁决者)

### 5.1 系统提示词 (System Prompt)
包含两部分：
1. **静态规则**：故事风格、五感描写、成人内容处理、输出 JSON 格式要求、建议模块规则。
2. **内部裁决流程指令**（核心优化）：
   ```
   在生成叙事前，你必须作为世界裁决者完成以下隐藏步骤：
   - 分析玩家行动难度，结合末日等级、环境、角色状态得出成功/失败/意外结果。
   - 判定是否触发随机事件（如远处枪声、NPC 出现、天气变化）。
   - 决定物资消耗、搜寻所得、战斗损伤。
   - 然后切换为“沉浸叙事者”模式，将裁决结果编织成第一人称故事。
   ```
3. **动态背景参数**：末日类型、当前等级（通过占位符在运行时注入）。

### 5.2 用户消息 (User Message)
每次行动前，Repository 调用 `PromptBuilder` 生成结构化文本：
```
玩家行动：{用户输入}

【当前状态】
玩家：姓名、生理、性格
状态标签：...
内心想法：...
装备：...
据点：...
日期时间：...
场景：...
末日等级：...
宏观状况：...

随身物资：
食物：压缩饼干×2
武器：棒球棍×1

已知资源点：药店(可能药品,高风险)

【临时记忆（最近10条）】
- 第一天@超市 发现丧尸->成功逃脱
...

请据此执行内部裁决并生成故事JSON。
```

### 5.3 预填充技巧（可选进阶）
若 AI 有时忘记输出 JSON，可在请求中加入一条 `assistant` 消息作为前缀引导：
```json
{"role": "assistant", "content": "{"}
```
模型会接续生成完整 JSON。此项在 MVP 阶段可不启用。

---

## 六、交互流程与生命周期

### 6.1 首次初始化流
1. App 冷启动，ViewModel 检查 `player_state` 表是否存在记录。
2. **若不存在**：UI 显示初始化界面，要求用户填写：
    - 姓名、性别/生理特征、性格
    - 初始据点、初始物资（可选）
    - 末日类型（丧尸/核战/凛冬…）
    - 起始末日等级（1-5）
3. 点击“开始”，ViewModel 调用 Repository 的初始化方法：
    - 写入 `PlayerState` 单例
    - 写入初始 `WorldInfo`（日期、时间、场景）
    - 写入初始物资列表
    - 插入第一条临时记忆（“游戏开始”）
4. 初始化完成后，自动触发第一轮 AI 调用：发送“我环顾四周，开始求生”或自定义开场白，让 AI 生成第一个故事场景。
5. 返回的故事和建议显示到主界面。

### 6.2 游戏主循环
1. 玩家从三个建议中选择一个，或在自定义输入框中输入行动。
2. ViewModel 设置 `isLoading = true`。
3. Repository 拉取当前全量状态 → 组装用户消息 → 调用 API。
4. 收到 `GameResponse` 后：
    - 将叙事文本、建议、面板数据推入 UI 状态。
    - 调用 `updateLocalData(response)` 同步数据库：
        - 更新 `world_info`（日期/时间/场景/宏观状况）
        - 更新 `player_state`（状态标签、内心想法、装备、末日等级）
        - 覆盖 `inventory` 表（全部删除后插入新数据）
        - 若 `newTempMemory` 非空，解析并插入 `memories` 表，触发 `deleteExtraTemporary(10)`
        - 若 `newPermanentPlot` 非空，插入一条永久记忆或追加到永久剧情摘要。
5. 设置 `isLoading = false`，UI 自动刷新。

### 6.3 状态面板
主界面底部或抽屉中展示：
- 人物卡：状态标签、内心独白、装备列表
- 物资表格：按类别显示
- 当前世界情报
- 最近几条临时记忆
- NPC 关系列表

这些数据**直接观察 Room 的 Flow**，保证每次更新后自动刷新，无需手动赋值。

### 6.4 错误与重试
- 网络异常或 API 返回非预期 JSON 时，ViewModel 设置 `error` 字段。
- UI 展示 Snackbar 或重试按钮。
- 玩家可手动重新发送最后一次行动。

---

## 七、记忆与进度的智能维护

### 7.1 临时记忆 FIFO
- 每次新插入一条临时记忆后，`MemoryDao.deleteExtraTemporary(10)` 确保总数 ≤ 10 条。
- 查询最近 10 条时按时间戳降序。

### 7.2 好感度数值化
- NPC 关系采用整型 `affinity`，范围 0-100。
- AI 叙事中若出现明显关系变化，可在 `GameResponse` 中附加字段（如 `npcAffectionDelta: -5`），但 MVP 阶段先不做自动解析，可留待后续用情感分析或额外 AI 调用来提取。

### 7.3 后期摘要机制（预留）
当临时记忆总量过大（未来可能放宽上限），可在后台线程调用 DeepSeek API，将旧记忆总结为永久剧情摘要。MVP 不实现。

---

## 八、开发里程碑

| 阶段 | 任务 | 产出 |
|------|------|------|
| **M1: 骨架** | 创建项目，添加依赖，定义所有 Room 实体、DAO、Database 抽象类 | 编译通过的可持久化模块 |
| **M2: 网络层** | 定义 `DeepSeekApiService`、`ChatRequest`、`GameResponse`，用静态 JSON 测试解析 | 网络模块可用 |
| **M3: 初始化流程** | 实现 UI 初始化表单，写入数据库，触发首次 API 调用并显示故事 | 可从新游戏开始并看到首个故事 |
| **M4: 核心循环** | 实现建议按钮和自定义输入，完整的请求-响应-更新-UI刷新循环 | 可连续游玩多轮 |
| **M5: 状态面板** | 观察 Room Flow，在状态面板中展示实时角色数据、物资、记忆 | 完整的信息展示 |
| **M6: 打磨与流式** | 错误处理、重试、Token 缓存意识、升级到流式输出 | 生产级体验 |

---

## 九、风险与注意事项

- **JSON 解析失败**：即使强制格式，AI 也可能偶尔出错。需在 Repository 层 `try-catch`，解析失败时尝试提取 `story` 字段，其余部分降级处理（不更新状态）。
- **响应超时**：1000 字生成可能超过默认读超时，确保 `readTimeout` ≥ 120 秒。
- **API Key 泄露**：不上传至版本管理。使用 `local.properties` 动态读取。
- **状态漂移**：由于 AI 负责物资数量，可能出现数量不一致。可在客户端增加简单的验证逻辑（负数归零），或后续改用客户端发牌员模式解决。
- **成人内容策略**：系统提示词中已给出隐晦化指引，如触发平台审查可随时调整。

---

## 十、未来扩展方向

- 客户端发牌员模式：将事件裁决完全本地化，AI 仅负责叙事。
- 流式输出 + SSE：改善长文本等待体验。
- 上下文缓存：降低重复设定 Token 消耗。
- 多角色 / NPC 记忆摘要：引入长期记忆压缩。
- 图像生成：配合 DALL·E 或本地模型生成场景插画。
