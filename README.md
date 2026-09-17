# 末日求生 · AI 叙事游戏 (EndWorld)

基于 DeepSeek V4 大语言模型驱动的硬核末日生存叙事游戏。AI 担任"城主"(GM)，通过**双Agent流水线**（裁决Agent + 叙事Agent）实时裁决玩家的每一个行动，生成压抑、冷酷、充满临场感的第一人称叙事。

## 架构概览

```
┌──────────────────────────────────────────────────────────────┐
│  UI Layer (Jetpack Compose)                                   │
│  GameScreen / InitializationScreen                            │
├──────────────────────────────────────────────────────────────┤
│  ViewModel Layer                                              │
│  GameViewModel (流式状态机 + 增量 JSON 解析 + 重试/降级)        │
├──────────────────────────────────────────────────────────────┤
│  Repository Layer                                             │
│  GameRepository (processMainline 6 步流水线编排)               │
│  PromptBuilder (System Prompt 动态提取 + 快照拼接)              │
│  GmStrategy / ReasonerApiClient / StorytellerApiClient         │
├──────────────────────┬───────────────────────────────────────┤
│  Domain State         │  Local (Room DB)                       │
│  SurvivalStateMachine │  6 Entities / 6 DAOs                   │
│  ├─ GameClock         │  Remote (Retrofit)                     │
│  ├─ WeatherSystem     │  DeepSeek V4 Pro SSE 流式              │
│  ├─ VitalSystem       │  MemOS OpenMem API (长期记忆)           │
│  ├─ HealthSystem      │                                       │
│  ├─ InventoryManager  │                                       │
│  ├─ InjuryLibrary     │                                       │
│  ├─ EventDeck         │                                       │
│  ├─ SkillManager      │                                       │
│  ├─ GoalTracker       │                                       │
│  └─ WorldEventManager │                                       │
└──────────────────────┴───────────────────────────────────────┘
```

## 已实现功能

### 核心系统 (V2.2)
- **双Agent裁决流水线**：`processPreRuling → Reasoner → ActionResult → applyActionResult → Storyteller → 流式叙事`
- **生存状态机**：GameClock（游戏内时钟）、WeatherSystem（天气演变）、VitalSystem（HP/SP/饱食/口渴/疲劳）、被动消耗累加器
- **伤病系统**：InjuryLibrary（42种预设伤病），感染概率累计、自然恢复倒计时、休克触发，进度跨重启持久化
- **物资管理**：InventoryManager 精确负重计算、装备耐久追踪、物品增删改查
- **NPC 深度记忆**：好感度追踪、性格标签、当前需求、互动摘要、互动次数。`NpcDao` 基于名称去重 UPSERT + COALESCE 合并保护
- **世界事件系统**：WorldEventManager 按存活天数触发全局事件，doomLevel 增量累加
- **技能系统**：SkillManager + SkillLibrary，DC修正 / 物资加成 / 叙事特权三种效果类型
- **目标追踪**：GoalTracker 客户端权威验证，防止 AI 生成非法目标
- **剧情管理**：PlotManager 章节推进 + 伏笔埋设/回收 + 剧情节点完成

### 扩展功能
- **客户端发牌员模式**：通过 `check_inventory` Function Calling 工具，让 AI 在叙事前先查询玩家物资
- **流式输出 + SSE**：基于 OkHttp SSE 的逐字流式渲染，推理过程（fate）和故事文本（story）分阶段展示
- **上下文缓存 (KV Cache)**：持久化 `user_id`，利用 DeepSeek API 的服务端缓存
- **MemOS 长期记忆**：每次对话自动检索历史记忆注入 prompt，结束后回写，跨会话叙事连续性
- **动态推理强度**：日常 `high`，战斗/逃跑等关键抉择 `max`
- **JSON 断裂修复**：流中断或 JSON 不完整时，正则降级提取 story 文本
- **记忆安检单次化**：裁决Agent 唯一过滤记忆，结果通过 `memoryContextChecked` 传给叙事Agent
- **Prompt 动态提取**：所有跨层数值从代码常量动态提取，确保 Prompt 与代码逻辑 100% 同步
- **debug_json 调试系统**：每轮自动保存 reasoner_user_msg / action_result / storyteller_user_msg / final_response

## 技术栈

| 层 | 技术 |
|---|---|
| 语言 | Kotlin 2.0 |
| UI | Jetpack Compose + Material 3 |
| 架构 | MVVM (手动 DI) |
| 本地数据库 | Room (6 表, Flow 响应式, fallbackToDestructiveMigration) |
| 网络 | Retrofit + OkHttp SSE |
| JSON | Gson (序列化) + org.json (兜底提取) |
| 异步 | Kotlin Coroutines + Flow + callbackFlow |
| AI 接口 | DeepSeek V4 Pro API (Chat Completions, reasoning_effort=max/medium) |
| 记忆服务 | MemOS OpenMem API |

## 快速开始

### 前置条件

- Android Studio Hedgehog (2024.1+) 或更新版本
- JDK 17
- Android SDK 26+
- DeepSeek API Key（[获取地址](https://platform.deepseek.com)）
- MemOS API Key（[获取地址](https://memos.memtensor.cn)）

### 1. 克隆项目

```bash
git clone <repo-url>
cd EndWorld
```

### 2. 配置 API Key

编辑项目根目录下的 `local.properties`，填入你的 API Key：

```properties
sdk.dir=/path/to/Android/sdk
DEEPSEEK_API_KEY=sk-your-deepseek-api-key
MEMOS_API_KEY=mpg-your-memos-api-key
```

> `local.properties` 已被 `.gitignore` 排除，不会提交到仓库。

### 3. 构建运行

用 Android Studio 打开项目，Sync Gradle，然后 Run → Run 'app'。

或使用命令行：

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 4. 开始游戏

1. 启动 App，进入初始化页面
2. 输入幸存者姓名，选择末日类型
3. 点击"降临末世"，游戏开始
4. 输入你的行动（或点击建议按钮），AI 城主会实时裁决并生成叙事

## 项目结构

```
app/src/main/java/com/ei8z/endworld/
├── MainActivity.kt                    # 入口，路由：初始化 / 游戏
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt             # Room DB (v5), DESTRUCTIVE MIGRATION
│   │   ├── Converters.kt              # List<String> ↔ JSON 转换
│   │   ├── dao/
│   │   │   ├── PlayerDao.kt           # 玩家状态 (单例 id=1)
│   │   │   ├── WorldDao.kt            # 世界信息 (单例 id=1)
│   │   │   ├── InventoryDao.kt        # 物资定向同步 (deleteByNames + deleteExcept)
│   │   │   ├── MemoryDao.kt           # 记忆 FIFO + 永久标签
│   │   │   ├── NpcDao.kt              # NPC UPSERT + COALESCE 合并保护
│   │   │   └── HistoryDao.kt          # 故事历史
│   │   └── entity/                    # PlayerState / WorldInfo / InventoryItem / NpcRelation / Memory / StoryHistory
│   ├── model/                         # ActionParsed / ActionResult / WorldSnapshot / EventCard / PersonalGoal / SkillTag / WorldEvent
│   ├── plot/                          # PlotManager / ChapterDataLoader / WorldEventManager
│   ├── remote/
│   │   ├── DeepSeekApiService.kt      # /chat/completions (含 SSE Streaming)
│   │   ├── MemosApiService.kt         # /search/memory + /add/message
│   │   ├── ReasonerApiClient.kt       # 裁决Agent (非流式, reasoning_effort=max)
│   │   ├── StorytellerApiClient.kt    # 叙事Agent (流式, reasoning_effort=medium)
│   │   ├── MemOSAdapter.kt            # MemOS 记忆检索/回写适配
│   │   ├── RetrofitClient.kt          # OkHttp 客户端 (60s/120s timeout)
│   │   └── model/                     # DTO: ChatRequest / ChatResponse / GameResponse / SSE Chunk
│   └── repository/
│       ├── GameRepository.kt          # 核心流水线：检索→裁决→应用→叙事→同步→回写
│       ├── PromptBuilder.kt           # 裁决/叙事 System Prompt + User Snapshot 构建
│       ├── GmStrategy.kt              # GM 策略配置 (难度/事件频率/成人模式)
│       └── StreamEvent.kt             # Sealed Class: PhaseChanged | Reasoning | Content | FinalResponse | Error
├── domain/state/
│   ├── SurvivalStateMachine.kt        # 生存状态机门面
│   ├── GameClock.kt                   # 游戏内时钟 (totalMinutes 持久化)
│   ├── WeatherSystem.kt               # 天气演变
│   ├── VitalSystem.kt                 # HP/SP/饱食/口渴/疲劳 + 被动消耗累加器
│   ├── HealthSystem.kt                # 伤病管理 (42种预设 + 感染累计 + 自然恢复)
│   ├── InjuryLibrary.kt               # 伤病模板库
│   ├── InventoryManager.kt            # 物资管理 (负重/耐久/增删改)
│   ├── EventDeck.kt                   # 事件卡组 (末日等级筛选/冷却/一次性触发)
│   ├── SkillManager.kt                # 技能管理 (DC修正/物资加成/叙事特权)
│   ├── SkillLibrary.kt                # 技能模板库
│   ├── GoalTracker.kt                 # 目标追踪 (客户端权威验证)
│   ├── WorldStateProvider.kt          # 世界状态快照生成器 (聚合所有子系统)
│   └── ClientDiceRoller.kt            # 客户端掷骰 (1-100)
├── di/
│   └── DependencyContainer.kt         # 手动单例 DI
├── ui/
│   ├── screen/
│   │   ├── GameScreen.kt              # 主游戏界面 (叙事流、状态终端、物资面板)
│   │   └── InitializationScreen.kt    # 初始化页面 (姓名、末日类型)
│   └── viewmodel/
│       └── GameViewModel.kt           # 流式状态机 + 增量 JSON 解析 + 重试逻辑
└── util/
    ├── Config.kt                      # BuildConfig 桥接
    └── JsonExtractor.kt               # 断裂 JSON 的 story/suggestions 正则提取
```

## 数据流 (V2.2 主线)

```
玩家输入 action
    ↓
GameViewModel.sendAction()
    ↓ saveHistory(action, "", "")  ← 先存空壳条目
GameRepository.sendActionStream() [callbackFlow]
    ↓
  ┌─ seedStateMachine()             ← 从 Room DB 恢复状态到状态机
  ↓
  ┌─① processPreRuling()            ← 推进时钟、天气演变、被动消耗、掷骰、事件池筛选
  │   preSnapshot                    ← 裁决前世界快照
  ↓
  ┌─② 并行获取上下文                 ← MemOS 记忆检索 / PlotContext / 事件候选 / 技能效果 / NPC深度
  ↓
  ┌─③ ReasonerClient.reason()       ← 裁决Agent (deepseek-v4-pro, reasoning_effort=max, 非流式)
  │   action_result                  ← 判定结果 + 物资变化 + 伤病 + 触发事件 + 剧情节点
  ↓
  ┌─④ applyActionResult()           ← 物资增减 / 伤病应用 / 事件状态变更 / 剧情更新 / 世界事件检查
  │   postSnapshot                   ← 裁决后世界快照
  ↓
  ┌─⑤ StorytellerClient.stream()    ← 叙事Agent (deepseek-v4-pro, reasoning_effort=medium, 流式 SSE)
  │   finalGameResponse              ← story/suggestions/statusUpdate/header
  ↓
  ┌─⑥ syncGameData()                ← Room DB 持久化 (权威数据从 postSnapshot, 软数据从 AI)
  │   writebackToMemos()             ← 回写 MemOS 长期记忆
  ↓
UI 通过 Room Flow 自动刷新列表
```

### 数据权威分层

| 数据类别 | 来源 | 说明 |
|---------|------|------|
| HP/SP/饱食/口渴/疲劳 | postSnapshot (状态机) | 客户端权威，不信任 AI |
| 伤病标签 + 进度 | postSnapshot + injuryStatesJson | 持久化感染风险/恢复进度 |
| 物资背包 | postSnapshot.inventory | 状态机权威，每次定向同步 |
| 负重/装备/损坏 | postSnapshot | 状态机权威 |
| 时钟分钟数 | stateMachine.clock.elapsedMinutes | 持久化到 WorldInfo.totalGameMinutes |
| 末日等级 | stateMachine.doomLevel | 增量累加 (世界事件驱动) |
| 内心独白/宏观局势 | AI statusUpdate | AI 软数据 |
| NPC 关系/好感度/深度 | AI npcUpdate | COALESCE 合并保护 |
| 记忆 | AI memoryUpdate | 临时 + 永久剧情记忆 |

## 核心设计决策

### 为什么使用双Agent流水线？

裁决Agent 专注逻辑判定（DC掷骰、物资校验、伤病判定、事件触发），输出结构化 JSON。叙事Agent 专注文学创作，基于裁决结果展开第一人称叙事。分离职责让每个 Agent 的 prompt 更精简，减少幻觉。裁决Agent 使用 `reasoning_effort=max` 保证判定精度，叙事Agent 使用 `medium` 平衡速度与质量。

### 为什么状态机数值从 postSnapshot 取而非 AI statusUpdate？

AI 生成的 statusUpdate.inventory 可能和裁决结果不一致（幻觉风险）。客户端以状态机 applyActionResult 后的 postSnapshot 为唯一权威来源，AI 仅提供软数据（内心独白、宏观叙事、已知地点）。

### 为什么被动消耗使用累加器而非逐次 toInt？

短时间行动（如5-10分钟的观察/小范围移动）会导致 `(rate * hours).toInt()` 截断为 0，玩家可通过拆分行动绕过饥饿系统。累加器 `accumulatedPartialHours` 将小数时间累积到 ≥1 小时再统一结算，且该值持久化到 PlayerState 防止重启丢失。

### 为什么 NpcRelation 以 name 为主键 + COALESCE 合并？

早期使用 `autoGenerate id` 导致每轮 NPC 互动新增一条重复记录。改为 `name` 主键后，UPSERT 通过 IGNORE + UPDATE 实现去重。UPDATE SQL 使用 `COALESCE(:newValue, oldValue)` 确保 AI 未返回的深度字段（比如 lastInteractionSummary）不会被意外清空。

### 为什么 syncInventory 使用定向删除而非全表清空？

`DELETE FROM inventory WHERE name IN (:names)` + `DELETE FROM inventory WHERE name NOT IN (:namesToKeep)` 替代 `DELETE ALL`，为未来多位置仓库系统（随身背囊 vs 避难所箱子）预留扩展空间。

### 为什么伤病进度单独序列化 (injuryStatesJson)？

PlayerState.mentalStateTags 只存伤病名称列表，不存运行时进度（感染概率、恢复倒计时）。应用重启后 seed() 从 InjuryLibrary 取全新对象（默认值），之前 tickHourly() 累积的进度全部丢失。`injuryStatesJson` 以 JSON 持久化 `Map<伤病名, InjuryProgress>`，恢复时叠加上静态属性。

## License

MIT
