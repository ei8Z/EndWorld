# 3F-2 KEY seed Spec

> 日期：2026-10-19　｜　状态：已实现
> 上游：`doc/v3/NPC自主行为系统详细设计 V1.0.md` §2.3；10-19 报告 P1-4 未闭合项
> 范围：只实现自动 KEY 判定（好感阈值 + 互动次数）；剧情注册、玩家标记、降级不做。
> 实现：`f2624fe`（提升函数与两个调用点）、`7edc654`/`504c43f`（持久化与场景 3/5 可达性）；全量 **413 / 0（52 类）**，`assembleDebug` 成功。

## 目标

满足 NPC 文档 §2.3 的自动条件时，接触 NPC 升级为关键 NPC（KEY），从而：

- 场景 3（KEY 战斗伤亡裁决）可达；
- 场景 5（稀有互动）对 KEY 恢复触发；
- 层级随 V3 持久化，重启后保持。

## 判定规则

| 条件 | 阈值 |
|---|---|
| 好感度 | `>= 80` 或 `<= -50` |
| 互动次数 | `> 10` |

- 仅 `NpcTier.CONTACT → KEY`；`BACKGROUND` 不参与，`KEY` 不重复处理。
- 不降级；不设 KEY 数量上限。
- 剧情管理器注册、玩家主动标记：不做。

## 设计

### 1. 提升函数

`GameRepository` 新增：

```kotlin
/** NPC 文档 §2.3：满足任一自动条件，CONTACT 升为 KEY。 */
internal fun promoteEligibleNpcs() {
    val keyed = npcRuntime.keys.toList()
    for (npcId in keyed) {
        val record = npcRuntime[npcId] ?: continue
        if (record.state.tier != NpcTier.CONTACT) continue
        val eligible = record.affinity.affinity >= 80 ||
            record.affinity.affinity <= -50 ||
            record.affinity.interactionCount > 10
        if (!eligible) continue
        updateRecord(npcId) { it.copy(state = it.state.copy(tier = NpcTier.KEY)) }
        Log.i(TAG, "NPC 升为 KEY: ${record.state.name} (affinity=${record.affinity.affinity}, interactions=${record.affinity.interactionCount})")
    }
}
```

快照 `keys.toList()` 避免遍历中修改 map。

### 2. 调用点

- **好感增量落地后**：`applyInteractionAffinity` 返回更新记录后调用 `promoteEligibleNpcs()`。
- **每日结算末尾**：`executeDailySettlement` 完成 NPC 互动/需求推进后调用一次，覆盖 `interactionCount` 增长但好感未跨阈值的情况。

不新增定时器/事件，不逐 NPC 重复触发。

### 3. 持久化

- `NpcStateEntity.tier` 已存在，`V3StateMapper` 已映射；`persistV3State` 自然写入。
- **不加 DB 迁移。**

### 4. 场景可达性

- 场景 5：1E 已改为 `tier == KEY || destructive`；提升后自然命中。
- 场景 3：`detectKeyNpcCasualty` 只对 KEY 触发；1E 已补齐 faction，提升后只要有战斗伤亡即可达。
- 不做额外接线。

## 测试

1. **好感阈值**：+80 提升；-50 提升；79 / -49 不提升。
2. **互动次数**：11 次提升；10 次不提升。
3. **持久化**：提升后 `persistV3State` → 新 repo `ensureV3WorldLoaded` → tier 仍为 KEY。
4. **场景 5**：同一对 NPC，CONTACT 普通互动不触发；提升为 KEY 后触发。
5. **场景 3 可达**：带 faction 的 KEY + 一次战斗伤亡 → 触发 `KEY_NPC_DEATH` 检测信号；同条件 CONTACT 不触发。`executeDailySettlement`→`evaluateScene3` 的异步 AI 裁决与回写未做集成，属书面遗留（见 nit 台账）。
6. **不越界**：BACKGROUND 即使好感 100 也不提升。

测试优先用真 Room（层级持久化）与现有场景触发纯函数；场景 3 裁决 AI 可用 relaxed mock。

## 非目标

- 剧情注册接口、玩家“信任”标记 UI。
- KEY 降级/再提取恢复规则（文档 §2.2 其他部分）。
- KEY 数量上限、提示词层级过滤、UI 展示。

## 验收

- 全量 401+ 全绿，`assembleDebug` 成功；新增测试 ≥6。
- 新档通过自然互动可产生 KEY；场景 5/3 的触发判定与检测信号在测试中覆盖（场景 3 的异步裁决分支为书面遗留）。
- 层级跨重启保持；无 DB 迁移；不改场景 3 触发条件本身。
