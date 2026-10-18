# EndWorld 执行约定（给 agent 的入口说明）

本文件只记录**项目特有**的约定；通用的多 Spec 编排流程由 `~/.dsh/skills/exec/SKILL.md` 提供，不在此复制。

## 触发方式

- 用户说“继续 3E-2”“按 exec 跑下一波”或调用 `/exec` 时，加载 `exec` 技能。
- 前提：`doc/plans/` 下已有**已批准的主 Spec** 和对应子 Spec；没有就先写 Spec，不要直接开工。
- 只执行决策已闭合的波次；产品决策未定的项（见 `doc/plans/2026-10-18-第三批E-收尾清单.md` 的 C 类）先停下问人。

## 目录与产物

| 内容 | 位置 |
|---|---|
| 主 Spec / 子 Spec | `doc/plans/`、`doc/plans/3e/` |
| 批次记录、遗留清单 | `doc/全盘检查报告-2026-10-17.md` |
| 收尾清单（优先级） | `doc/plans/2026-10-18-第三批E-收尾清单.md` |
| 隔离 worktree | `.worktrees/exec-<任务>/<子任务>/`（已 gitignore） |

## 波次执行

1. 从主工作区 HEAD 建分支和 worktree，复制 `local.properties`。
2. 一波一次 `workflow` 调用：实现节点 → 全新审查节点 → 最多 5 轮修复。
3. 主线程负责 worktree、合并、清理；workflow 脚本不碰 git。
4. 同一文件上的多个 Spec **串行**，不要并行。
5. 合并前主代理独立跑全量；合并后主工作区再跑一次。

## 验证命令

```bash
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:assembleDebug --no-daemon
```

- 当前基线：**337 tests / 0 failed**（2026-10-18，审计闭合）。
- 测试数不得减少；新增测试必须断言行为。
- JDK 17/20/25 均可（`jvmTarget=11`）；daemon 由 `gradle/gradle-daemon-jvm.properties` 指定。

## 代码约定

- 唯一 NPC 状态容器：`GameRepository.npcRuntime: LinkedHashMap<String, NpcRecord>`；不新增平行 map。
- 同名 NPC 唯一：id `npc_<name>`；旧 `npc_<name>_<day>` 保持可读。
- 好感度只由模拟层写：AI 增量经 `NpcBehaviorEngine.applyAffinityDelta`（±20 夹取、阶段重算）。
- 场景 3/6 效果函数是纯函数：收/返 `NpcRecord?`，不直接写外部状态。
- 不引入 `!!`；不为编译通过改断言或删测试。

## 明确不做

- 场景 7 实时化：前置条件（永久偏离源）未满足，不接假信号。
- Room `PlayerDao` REPLACE 改 `@Update`、`deathProbabilityModifier` 改名：已评估，不划算。
- `WorldStateProvider` / `GmSettingsDialog`：不是死代码，不清。

## 提交与合并

- 中文提交；一个 Spec 至少一个提交，审查修复可追加。
- 合并到 `main` 用快进/普通合并均可；合并后删除 worktree 与子分支。
- 未经用户选择不创建 PR、不删除 `main` 以外的长期分支。
