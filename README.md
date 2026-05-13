# 末日求生 · AI 叙事游戏 (EndWorld)

基于 DeepSeek V4 大语言模型驱动的硬核末日生存叙事游戏。AI 担任"城主"(GM)，实时裁决玩家的每一个行动，生成压抑、冷酷、充满临场感的第一人称叙事。

## 架构概览

```
┌─────────────────────────────────────────────────┐
│  UI Layer (Jetpack Compose)                      │
│  GameScreen / InitializationScreen               │
├─────────────────────────────────────────────────┤
│  ViewModel Layer                                 │
│  GameViewModel (流式状态机 + 增量 JSON 解析)       │
├─────────────────────────────────────────────────┤
│  Repository Layer                                │
│  GameRepository (4 步流水线编排)                  │
│  PromptBuilder (System Prompt + 快照拼接)         │
├──────────────────────┬──────────────────────────┤
│  Local (Room DB)      │  Remote (Retrofit)        │
│  6 Entities / 6 DAOs  │  DeepSeek SSE 流式        │
│                       │  MemOS 长期记忆 API        │
└──────────────────────┴──────────────────────────┘
```

## 已实现功能

### 核心系统
- **AI 城主裁决**：所有玩家行动由 AI 判定成功/失败/意外，并驱动叙事
- **末日世界模拟**：末日烈度、资源消耗、环境演变、随机事件
- **角色系统**：生理特征、性格特质、状态标签（伤病/饥饿）、内心独白
- **物资管理**：食物、饮水、武器、特殊物品四类背包系统
- **NPC 关系**：好感度数值追踪，关系标签动态变化
- **多末日类型**：丧尸危机、核冬天、极度严寒、智械危机

### 扩展功能
- **客户端发牌员模式**：通过 `check_inventory` Function Calling 工具，让 AI 在叙事前先查询玩家物资，将事件裁决锁定在客观数据之上
- **流式输出 + SSE**：基于 OkHttp SSE 的逐字流式渲染，首字延迟极低，推理过程和故事文本分别展示
- **上下文缓存 (KV Cache)**：持久化 `user_id`，利用 DeepSeek API 的服务端缓存，重复 system prompt 前缀仅计增量 Token
- **MemOS 长期记忆**：每次对话自动检索历史记忆注入 prompt，结束后回写，形成跨会话的叙事连续性
- **动态推理强度**：日常行动使用 `high`，战斗/逃跑等关键抉择自动切换到 `max`
- **JSON 断裂修复**：流中断或 JSON 不完整时，正则降级提取 story 文本，保证流程不中断
- **流式状态机**：三阶段增量 JSON 解析（累积 → story 提取 → suggestions 切换），无需等待完整响应即可渲染

## 技术栈

| 层 | 技术 |
|---|---|
| 语言 | Kotlin 2.0 |
| UI | Jetpack Compose + Material 3 |
| 架构 | MVVM (手动 DI) |
| 本地数据库 | Room (6 表, Flow 响应式) |
| 网络 | Retrofit + OkHttp SSE |
| JSON | Gson (序列化) + org.json (兜底提取) |
| 异步 | Kotlin Coroutines + Flow + callbackFlow |
| AI 接口 | DeepSeek V4 Pro API (Chat Completions) |
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
├── MainActivity.kt                  # 入口，路由：初始化 / 游戏
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt           # Room DB (v2), DESTRUCTIVE MIGRATION
│   │   ├── Converters.kt            # List<String> ↔ JSON 转换
│   │   ├── dao/
│   │   │   ├── PlayerDao.kt         # 玩家状态 (单例 id=1)
│   │   │   ├── WorldDao.kt          # 世界信息 (单例 id=1)
│   │   │   ├── InventoryDao.kt      # 物资 CRUD + 全量同步
│   │   │   ├── MemoryDao.kt         # 记忆 FIFO + 永久标签
│   │   │   ├── NpcDao.kt            # NPC 好感度 upsert
│   │   │   └── HistoryDao.kt        # 故事历史
│   │   └── entity/                  # 6 个 @Entity 数据类
│   ├── remote/
│   │   ├── DeepSeekApiService.kt    # /chat/completions (含 SSE Streaming)
│   │   ├── MemosApiService.kt       # /search/memory + /add/message
│   │   ├── RetrofitClient.kt        # OkHttp 客户端 (60s/120s timeout)
│   │   └── model/                   # DTO: ChatRequest, ChatResponse, GameResponse, SSE Chunk
│   └── repository/
│       ├── GameRepository.kt        # 核心流水线：检索→生成→同步→回写
│       ├── PromptBuilder.kt         # System Prompt + User Snapshot 拼接
│       └── StreamEvent.kt           # Sealed Class: Reasoning | Content | FinalResponse | Error
├── di/
│   └── DependencyContainer.kt       # 手动单例 DI
├── ui/
│   ├── screen/
│   │   ├── GameScreen.kt            # 主游戏界面 (叙事流、状态终端、物资面板)
│   │   └── InitializationScreen.kt  # 初始化页面 (姓名、末日类型)
│   └── viewmodel/
│       └── GameViewModel.kt         # 流式状态机 + 增量 JSON 解析 + 重试逻辑
└── util/
    ├── Config.kt                    # BuildConfig 桥接
    └── JsonExtractor.kt             # 断裂 JSON 的 story/suggestions 正则提取
```

## 数据流

```
玩家输入 action
    ↓
GameViewModel.sendAction()
    ↓ saveHistory(action, "", "")  ← 先存空壳条目
GameRepository.sendActionStream() [callbackFlow]
    ↓
  ① MemOS.searchMemory()          ← 检索历史记忆，失败 → 空列表
    ↓
  ② PromptBuilder 拼接 prompt      ← system(世界百科+长期记忆) + user(状态快照)
    ↓
  ③ DeepSeek SSE 流式推理          ← reasoning_effort 动态调整
    ↓
  ④ processStreamChunks()          ← SSE → Reasoning/Content 增量流 → UI
    ↓
  ⑤ parseAndEmitResponse()         ← Gson 反序列化 → GameResponse
    ↓ (失败 → buildSalvagedResponse 正则兜底)
  ⑥ syncGameData()                 ← 同步到 Room DB (player, world, inventory, memory, npc)
    ↓
  ⑦ MemOS.addMessage()             ← 回写本轮对话到长期记忆 (NonCancellable)
    ↓
  ⑧ StreamEvent.FinalResponse      ← 触发 saveHistory(action=null, reasoning, story)
    ↓
UI 通过 Room Flow 自动刷新列表
```

## 核心设计决策

### 为什么用字符串匹配而不是完整 JSON 解析来做流式 story 提取？

完整 JSON 解析（如 Gson）在流到达一半时会抛异常，无法增量输出。当前方案通过检测 `"story":` 和 `"suggestions":` 两个 JSON key 作为状态机切换点，在 story 区间逐字追加到 UI，实现极低首字延迟。实际使用中，story 文本几乎不可能包含这些精确的 JSON key 字符串。

### 为什么 player state 和 world info 使用单例 (id=1) 而不是 UPSERT？

简化并发控制。单例设计配合 `@Insert(REPLACE)` 天然原子，不需要 `@Update` 的事务管理。这对单玩家本地游戏完全够用。

### 为什么 MemOS addMessage 使用 NonCancellable？

`callbackFlow` 在收到 `FinalResponse` 后立即 `close()`，然后是 `awaitClose { job.cancel() }` 清理。如果不加 `NonCancellable`，addMessage 协程会在完成前被取消，导致长期记忆丢失。

## License

MIT
