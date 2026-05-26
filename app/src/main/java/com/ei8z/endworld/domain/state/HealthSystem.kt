package com.ei8z.endworld.domain.state

import com.ei8z.endworld.data.model.InjuryTag

/**
 * 伤病系统。管理玩家当前活跃伤病标签的增删、每小时自然恢复/恶化处理、感染判定。
 *
 * 裁决Agent返回伤病名称 → 客户端通过 InjuryLibrary 查找完整对象 → 调用 applyInjury。
 * 伤病名称在库中未命中时，记录警告并忽略（不做客户端推测）。
 */
class HealthSystem(private val injuryLibrary: InjuryLibrary) {

    private val _activeTags = mutableListOf<InjuryTag>()

    /** 当前活跃伤病标签（只读） */
    val activeTags: List<InjuryTag> get() = _activeTags.toList()

    /** 标签效果汇总描述（供 WorldSnapshot.tagEffects 使用） */
    val tagEffects: Map<String, String>
        get() = _activeTags.associate { it.name to it.narrativeHint }

    // ---- 伤病管理 ----

    /**
     * 根据伤病名称从 InjuryLibrary 查找并添加到活跃列表。
     * 名称未命中时忽略（不抛异常，不添加占位对象）。
     */
    fun applyInjury(injuryName: String) {
        val tag = injuryLibrary.get(injuryName)
        if (tag != null) {
            _activeTags.add(tag)
        }
        // 未命中的伤病名称由裁决Agent负责，客户端不做推测
    }

    /**
     * 移除指定名称的伤病标签。
     */
    fun recoverInjury(injuryName: String) {
        _activeTags.removeAll { it.name == injuryName }
    }

    // ---- 数值计算 ----

    /**
     * 汇总所有活跃伤病的 SP 消耗倍率。
     * 计算方式：累加各标签的 (spPenalty - 1.0)，以最严重者为准并叠加。
     * 如：spPenalty=1.2 贡献 +0.2，spPenalty=1.5 贡献 +0.5
     * 返回：总修正率（如 0.7 表示 +70% SP 消耗）
     */
    fun calculateSpPenalty(): Float {
        if (_activeTags.isEmpty()) return 0.0f
        return _activeTags.sumOf { (it.spPenalty - 1.0f).toDouble() }.toFloat()
    }

    /**
     * 每小时 HP 损失总量（所有伤病 hpLossPerHour 之和）。
     */
    fun calculateHourlyHpLoss(): Int = _activeTags.sumOf { it.hpLossPerHour }

    // ---- 每小时处理 ----

    /**
     * 每游戏小时调用一次。处理：
     * 1. 自然恢复计时递减
     * 2. 感染概率累计（每个有 infectionRisk 的伤病 +=5%/小时）
     */
    fun tickHourly() {
        // 自然恢复：每个 tag 递减 naturalRecoveryHours，归零则移除
        val recovered = mutableListOf<String>()
        for (i in _activeTags.indices) {
            val tag = _activeTags[i]
            val newHours = tag.naturalRecoveryHours - 1
            if (newHours <= 0) {
                recovered.add(tag.name)
            } else {
                _activeTags[i] = tag.copy(naturalRecoveryHours = newHours)
            }
        }
        _activeTags.removeAll { it.name in recovered }

        // 感染累计：每个仍有感染风险的伤病，每小时 +0.05
        for (i in _activeTags.indices) {
            val tag = _activeTags[i]
            if (tag.infectionRisk > 0f) {
                val newRisk = (tag.infectionRisk + 0.05f).coerceAtMost(1.0f)
                _activeTags[i] = tag.copy(infectionRisk = newRisk)
            }
        }
    }

    /**
     * 检查是否有伤病感染风险达到 1.0（已感染）。
     *
     * 感染触发规则：
     * - 将该伤病标记移除
     * - 强制附加 "伤口感染性休克"
     * - 返回被感染的原始伤病名称列表（供日志/叙事使用）
     *
     * TODO: Phase 8 主线串通时，确认感染触发后是否需要通知叙事Agent补写一段叙事。
     */
    fun checkInfection(): List<String> {
        val infected = _activeTags.filter { it.infectionRisk >= 1.0f }
        if (infected.isEmpty()) return emptyList()

        val infectedNames = infected.map { it.name }
        _activeTags.removeAll { it.infectionRisk >= 1.0f }

        val septicTag = injuryLibrary.get("伤口感染性休克")
        if (septicTag != null) {
            _activeTags.add(septicTag)
        }

        return infectedNames
    }

    /** 清空全部伤病 */
    fun clear() {
        _activeTags.clear()
    }
}
