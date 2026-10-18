# 3e2-timeline：场景 2 好感度时间线

## Goal

`buildScene2UserMessage(timeline = "")` 改为真实时间线：最近 5 条阶段跨越事件（含本次），格式「第X天 A→B（好感N，原因）」。

## 背景

`NpcBehaviorEngine.applyAffinityDelta` 已在阶段变化时写 `stageCrossingHistory`（3E-1 前完成），但场景 2 调用点仍传空串，§5.5 的「好感度变化时间线」恒为空。

## Steps

- [ ] 1. `PromptBuilder` 增加纯函数：

```kotlin
internal fun buildAffinityTimeline(
    history: List<StageCrossingEvent>,
    limit: Int = 5
): String = history.takeLast(limit).joinToString("；") {
    "第${it.gameDay}天 ${it.fromStage.toDisplayName()}→${it.toStage.toDisplayName()}（好感${it.atAffinity}，${it.reason}）"
}
```

（`StageCrossingEvent` 字段名以实际为准：`gameDay/fromStage/toStage/affinityAtCrossing/reason`。）

- [ ] 2. 场景 2 调用点：

```kotlin
timeline = PromptBuilder.buildAffinityTimeline(updated.stageCrossingHistory),
```

`updated` 已包含本次跨越；不要再手工拼接。

- [ ] 3. 测试（`PromptBuilderTest` 或新 `AffinityTimelineTest`）：

```kotlin
@Test fun `timeline renders crossings in order`() {
    val history = listOf(
        StageCrossingEvent(1, RelationshipStage.NEUTRAL, RelationshipStage.FAMILIAR, 25, "AI增量"),
        StageCrossingEvent(3, RelationshipStage.FAMILIAR, RelationshipStage.TRUSTED, 55, "AI增量")
    )
    assertEquals(
        "第1天 中立或陌生→熟悉（好感25，AI增量）；第3天 熟悉→信任（好感55，AI增量）",
        PromptBuilder.buildAffinityTimeline(history)
    )
}

@Test fun `timeline keeps only the last five and handles empty`() {
    assertTrue(PromptBuilder.buildAffinityTimeline(emptyList()).isEmpty())
    assertEquals(5, PromptBuilder.buildAffinityTimeline(sevenEvents).split("；").size)
}
```

（显示名以 `toDisplayName()` 实际输出为准，测试按真实值写。）

- [ ] 4. 场景 2 提示词测试：断言包含「第1天」且不再包含「时间线= |」。

## Acceptance

- 全量 306+ 全绿；新增 2-3 条测试。
- 空历史仍为空串，不产生「时间线=」空段以外的格式变化。
- 不改触发条件、不改场景 2 决策 schema。
