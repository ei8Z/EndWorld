# 3F-2 KEY seed 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development 或 superpowers:executing-plans。步骤用 `- [ ]` 跟踪。

**Goal:** 好感 ≥80/≤-50 或互动 >10 的接触 NPC 自动升为 KEY，层级持久化，并让场景 3/5 在测试中真实可达。

**Architecture:** `GameRepository.promoteEligibleNpcs()` 扫描 `npcRuntime` 就地升级；两个调用点（好感落地、每日结算末尾）。复用 `NpcStateEntity.tier`，无迁移。

**Tech Stack:** Kotlin、Room、JUnit4/Robolectric、MockK。

**Spec:** `doc/plans/2026-10-19-3F-2-KEY-seed-Spec.md`

## Global Constraints

- 只做自动两条判定；不做剧情注册、玩家标记、降级、数量上限。
- 不新增 DB 迁移；不改场景 3 的触发条件本身。
- 测试数只增不减；当前基线 **401 / 0**。
- 不引入 `!!`；不为编译改断言。

## Review Focus

- **阈值边界**：80 与 79、-50 与 -49、10 与 11 必须分别覆盖，避免 off-by-one。
- **BACKGROUND**：即使好感 100 也不能提升。
- **遍历中修改**：`npcRuntime` 是 LinkedHashMap，升级时必须先拷贝 key 列表，避免 ConcurrentModification。
- **持久化时机**：升级后若进程被杀，是否会在下一次持久化前丢失；本批接受“最迟下一次结算/行动落库”。
- **场景 5 误触发**：升级后 KEY 会重新进入稀有互动；确认只对 KEY 或破坏性互动，不要退回旧行为。

---

### Task 1: 提升函数与调用点

**Files:**
- Modify: `app/src/main/java/com/ei8z/endworld/data/repository/GameRepository.kt`
- Test: `app/src/test/java/com/ei8z/endworld/data/repository/GameRepositoryKeySeedTest.kt`（新建）

**Interfaces:**
- Produces: `internal fun GameRepository.promoteEligibleNpcs()`
- Consumes: `npcRuntime`、`updateRecord`、`NpcTier`

**Step 1: 写失败测试**

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameRepositoryKeySeedTest {
    // helper: seedNpc(repo, name, affinity, interactions, tier = CONTACT)

    @Test
    fun `affinity 80 promotes contact to key`() = runTest {
        val repo = createRepo()
        seedNpc(repo, "A", affinity = 80)
        repo.promoteEligibleNpcs()
        assertEquals(NpcTier.KEY, repo.npcRuntime.getValue("npc_A").state.tier)
    }

    @Test
    fun `affinity 79 does not promote`() = runTest {
        val repo = createRepo()
        seedNpc(repo, "A", affinity = 79)
        repo.promoteEligibleNpcs()
        assertEquals(NpcTier.CONTACT, repo.npcRuntime.getValue("npc_A").state.tier)
    }

    @Test
    fun `affinity minus 50 promotes and minus 49 does not`() = runTest { ... }

    @Test
    fun `interactions 11 promotes and 10 does not`() = runTest { ... }

    @Test
    fun `background never promotes`() = runTest { ... }
}
```

**Step 2: RED**

```bash
JAVA_TOOL_OPTIONS="-Duser.home=$(pwd)/app/build/robolectric-home -Dmaven.repo.local=/Users/ei8z/.m2/repository" \
GRADLE_USER_HOME=/Users/ei8z/.gradle \
./gradlew :app:testDebugUnitTest --no-daemon --tests 'com.ei8z.endworld.data.repository.GameRepositoryKeySeedTest'
```

Expected: 编译失败（`promoteEligibleNpcs` 不存在）。

**Step 3: 实现**

```kotlin
internal fun promoteEligibleNpcs() {
    for (npcId in npcRuntime.keys.toList()) {
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

调用点：

- `applyInteractionAffinity` 的 `updateRecord` 之后：`promoteEligibleNpcs()`。
- `executeDailySettlement` 末尾（NPC 互动/需求推进之后）：`promoteEligibleNpcs()`。

**Step 4: GREEN + 提交**

```bash
./gradlew :app:testDebugUnitTest --no-daemon --tests '...GameRepositoryKeySeedTest'
git add ... && git commit -m "feat: KEY层级自动提升"
```

---

### Task 2: 持久化与场景可达性

**Files:**
- Test: `GameRepositoryKeySeedTest.kt`（追加）
- Test: `SceneTriggerTest.kt`（追加）

**Step 1: 写测试**

1. **持久化**：提升后调用 `repo.executeDailySettlement()`；新建 repo（同 DB）调用 `ensureV3WorldLoaded()`；断言 tier 仍 KEY。
2. **场景 5**：同一 NPC，CONTACT 普通互动 → `detectRareInteraction` null；升级 KEY 后 → 触发。
3. **场景 3 数据链**：KEY + faction，`BattleCasualtyEngine(Random(0)).roll(..., rates=1.0)` 产出 casualty，`detectKeyNpcCasualty` 非空；同条件 CONTACT → null。

**Step 2: RED/GREEN + 提交**

```bash
git commit -m "test: KEY持久化与场景3/5可达性"
```

---

### Task 3: 文档收口

**Files:**
- Modify: `AGENTS.md`（按需加 KEY 规则一行）
- Modify: `doc/核心业务流程检查报告-2026-10-19.md`（P1-4 场景 3 状态改“数据链可达”）
- Modify: `doc/plans/2026-10-19-3F-2-KEY-seed-Spec.md`（状态改“已实现”）
- Modify: `doc/plans/nit台账.md`（低危）

**Step 1: 更新 + 提交**

```bash
git commit -m "docs: 3F-2 KEY seed收口"
```

## Acceptance

- 全量 401+ 全绿，`assembleDebug` 成功。
- 阈值边界、BACKGROUND、持久化、场景 3/5 均有测试。
- 无迁移、无场景 3 触发条件改动、无剧情/玩家标记。
