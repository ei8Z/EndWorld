# EndWorld 执行约定（给 agent 的入口说明）

本文件只记录**项目特有**的约定；通用的多 Spec 编排流程由 `~/.dsh/skills/exec/SKILL.md` 提供，不在此复制。

## 触发方式

- 用户说“继续 3E-2”“按 exec 跑下一波”或调用 `/exec` 时，加载 `exec` 技能。
- 前提：`doc/plans/` 下已有**已批准的主 Spec** 和对应子 Spec；没有就先写 Spec，不要直接开工。
- 只执行决策已闭合的波次；产品决策未定的项（见 `doc/plans/2026-10-18-第三批E-收尾清单.md` 的 C 类）先停下问人。

## 阶段与入口（老项目审计）

| 阶段 | 做什么 | 出口 |
|---|---|---|
| 0 基线 | 构建命令、全量测试、失败/flaky 数、热点 | 数字入档、构建可复现 |
| 1 止血 | CI、密钥/日志、破坏性迁移、崩溃/卡死 | P0 关闭或书面接受 |
| 2 审计 | 每批一个范围，finding 带 file:line + 证据 | P0/P1/P2 报告，批次 ≤5 任务 |
| 3 修复 | 逐任务 TDD、小 diff、按批合并 | 批绿；main 复验 |
| 4 收口 | 闭合声明：已修/接受/去向 | 无开放 P0/P1 未被书面接受 |

**本项目已完成审计**：见 `doc/plans/2026-10-18-审计闭合声明.md`。新工作从 Phase 3/4 开始，不重审全仓。引用旧报告时：

1. 先钉住报告对应的 commit；
2. 自跑基线，数字冲突以当前 build 为准；
3. 抽样 5 条（2 已修 + 3 未修）对 file:line 核验，标确认/过时/误报；
4. 过时/误报 > 2 条视为报告整体失真，退回受影响模块重审，不逐条打补丁。

**反模式**：重审全仓；大爆炸重写；每件小事都双轴审；先建基础设施再证明触发；修尽所有 nit；没有闭合声明就说"完成"。

## 目录与产物

| 内容 | 位置 |
|---|---|
| 主 Spec / 子 Spec | `doc/plans/`、`doc/plans/3e/` |
| 批次记录、遗留清单 | `doc/全盘检查报告-2026-10-17.md` |
| 收尾清单（优先级） | `doc/plans/2026-10-18-第三批E-收尾清单.md` |
| 隔离 worktree | `.worktrees/exec-<任务>/<子任务>/`（已 gitignore） |

## 波次执行

1. 从主工作区 HEAD 建分支和 worktree，复制 `local.properties`。
2. 一波一次 `workflow` 调用：实现节点 → 审查节点（按下方分级）→ 修复轮次。
3. 主线程负责 worktree、合并、清理；workflow 脚本不碰 git。
4. 同一文件上的多个 Spec **串行**，不要并行。
5. 合并前主代理独立跑全量；合并后主工作区再跑一次。

## 审查分级（反过度审计）

| 批次类型 | 审查方式 | 修复上限 |
|---|---|---|
| 行为 / DB / 安全 / 状态机 | 两轴（规范 + 规格） | 2 轮，只修 Critical/Important |
| 纯重构（行为不变） | 1 个审查者 | 1 轮，只修真实回归 |
| 文档 / 格式 / 命名 | 不派审查，主代理 diff 自检 | 不修 nit |

- **只修 Critical / Important**；低危与 nit 记入 `doc/plans/nit台账.md`，攒批处理，不阻塞合并。
- CR 最多 2 轮；第 2 轮仍有 Important 时停下问人，不无限循环。
- 最终整合审查只在“行为/DB/安全”批次后跑；纯重构不重复跑。

## 接线前先证明可达

任何“接信号/接场景/接触发器”的 Spec，第一步必须是一个**会失败的端到端测试或 10 行探针**，证明信号真的会产生；不可达就停止，不写表、不写检测器。禁止先写基础设施再验证触发源。

## Spec 与测试预算

- 子 Spec 控制在 1 页：目标、文件:行、接口、验收；不贴大段实现代码。
- 每个 Spec 至少 1 条**接缝/集成**测试；纯函数测试够用即可，不为数量凑测试。
- 测试数基线只增不减；新增测试必须断言行为，不测注释/格式。
- 一批只写一个 Spec 汇总，避免主 Spec + 子 Spec + 清单三处重复。

## 收口

- 每阶段结束写《审计闭合声明》：已修、接受遗留（带影响与去向）、验收标准。
- 收口后新事项按新批次立项，不以“补上一批”的名义扩范围。

## 验证命令

```bash
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:assembleDebug --no-daemon
```

- 当前基线：**350 tests / 0 failed**（2026-10-19，第 1A 批后）。
- 测试数不得减少；新增测试必须断言行为。
- JDK 17/20/25 均可（`jvmTarget=11`）；daemon 由 `gradle/gradle-daemon-jvm.properties` 指定。

## 代码约定

- 唯一 NPC 状态容器：`GameRepository.npcRuntime: LinkedHashMap<String, NpcRecord>`；不新增平行 map。
- 同名 NPC 唯一：id `npc_<name>`；旧 `npc_<name>_<day>` 保持可读。
- 好感度只由模拟层写：AI 增量经 `NpcBehaviorEngine.applyAffinityDelta`（±20 夹取、阶段重算）。
- 场景 3/6 效果函数是纯函数：收/返 `NpcRecord?`，不直接写外部状态。
- 不引入 `!!`；不为编译通过改断言或删测试。

## 临时开关

- `NpcBehaviorEngine.NEED_DEATH_ENABLED = false`：NPC 需求/伤病暂不致死，等满足/治疗模型落地再打开；两个死亡落点（每日结算、加速追赶）共用此常量。

## 明确不做

- 场景 7 实时化：前置条件（永久偏离源）未满足，不接假信号。
- Room `PlayerDao` REPLACE 改 `@Update`、`deathProbabilityModifier` 改名：已评估，不划算。
- `WorldStateProvider` / `GmSettingsDialog`：不是死代码，不清。

## 提交与合并

- 中文提交；一个 Spec 至少一个提交，审查修复可追加。
- 合并到 `main` 用快进/普通合并均可；合并后删除 worktree 与子分支。
- 未经用户选择不创建 PR、不删除 `main` 以外的长期分支。
