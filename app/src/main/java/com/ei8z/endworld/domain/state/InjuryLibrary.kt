package com.ei8z.endworld.domain.state

import com.ei8z.endworld.data.model.InjuryTag

/**
 * 伤病预设模板库。裁决Agent仅返回伤病名称字符串，
 * 客户端通过此库查找完整的 InjuryTag 对象并应用到 HealthSystem。
 *
 * TODO: 支持从 JSON 配置文件加载，方便运行时调整伤病参数。
 */
class InjuryLibrary {

    private val injuries: Map<String, InjuryTag> = buildMap()

    fun get(name: String): InjuryTag? = injuries[name]

    fun getAll(): List<InjuryTag> = injuries.values.toList()

    private fun buildMap(): Map<String, InjuryTag> {
        val list = listOf(
            // ===== 擦伤/割伤类 =====
            InjuryTag(
                id = "minor_abrasion",
                name = "轻微擦伤",
                hpLossPerHour = 0,
                spPenalty = 1.0f,
                infectionRisk = 0.05f,
                naturalRecoveryHours = 24,
                narrativeHint = "表皮擦伤，微微渗血，动作时有些刺痛"
            ),
            InjuryTag(
                id = "moderate_laceration",
                name = "中度割伤",
                hpLossPerHour = 1,
                spPenalty = 1.0f,
                infectionRisk = 0.10f,
                naturalRecoveryHours = 48,
                requiredTreatment = "绷带",
                narrativeHint = "伤口较深，需要包扎止血，否则感染风险持续上升"
            ),
            InjuryTag(
                id = "deep_laceration",
                name = "深度撕裂伤",
                hpLossPerHour = 2,
                spPenalty = 1.1f,
                infectionRisk = 0.20f,
                naturalRecoveryHours = 72,
                requiredTreatment = "缝合包",
                narrativeHint = "皮肉翻卷，失血不止，每一次心跳都让伤口渗出一股温热"
            ),

            // ===== 骨折类 =====
            InjuryTag(
                id = "finger_fracture",
                name = "手指骨折",
                hpLossPerHour = 0,
                spPenalty = 1.2f,
                infectionRisk = 0.0f,
                naturalRecoveryHours = 120,
                narrativeHint = "一根手指不自然地弯曲着，握持物品时剧痛难忍"
            ),
            InjuryTag(
                id = "arm_fracture",
                name = "手臂骨折",
                hpLossPerHour = 0,
                spPenalty = 1.3f,
                infectionRisk = 0.05f,
                naturalRecoveryHours = 200,
                requiredTreatment = "夹板",
                narrativeHint = "前臂出现异常弯折，完全无法持握任何重物"
            ),
            InjuryTag(
                id = "rib_crack",
                name = "肋骨骨裂",
                hpLossPerHour = 1,
                spPenalty = 1.3f,
                infectionRisk = 0.0f,
                naturalRecoveryHours = 168,
                narrativeHint = "深呼吸都会引发胸腔刺痛，剧烈运动可能导致骨裂加重"
            ),
            InjuryTag(
                id = "leg_fracture",
                name = "腿部骨折",
                hpLossPerHour = 1,
                spPenalty = 1.5f,
                infectionRisk = 0.05f,
                naturalRecoveryHours = 240,
                requiredTreatment = "夹板",
                narrativeHint = "小腿肿胀变形，每走一步都像踩在刀刃上，移动速度大幅下降"
            ),
            InjuryTag(
                id = "clavicle_fracture",
                name = "锁骨骨折",
                hpLossPerHour = 0,
                spPenalty = 1.25f,
                infectionRisk = 0.0f,
                naturalRecoveryHours = 180,
                requiredTreatment = "悬吊带",
                narrativeHint = "肩膀塌陷，抬臂动作几乎不可能完成"
            ),
            InjuryTag(
                id = "ankle_fracture",
                name = "脚踝骨折",
                hpLossPerHour = 0,
                spPenalty = 1.4f,
                infectionRisk = 0.05f,
                naturalRecoveryHours = 200,
                requiredTreatment = "夹板",
                narrativeHint = "脚踝肿胀成紫黑色，任何负重都会引起撕心裂肺的疼痛"
            ),
            InjuryTag(
                id = "pelvis_crack",
                name = "骨盆骨裂",
                hpLossPerHour = 1,
                spPenalty = 1.6f,
                infectionRisk = 0.02f,
                naturalRecoveryHours = 300,
                requiredTreatment = "外科手术包",
                narrativeHint = "骨盆区域剧痛，站立和行走极度困难，甚至翻身都会晕眩"
            ),

            // ===== 软组织损伤 =====
            InjuryTag(
                id = "muscle_strain",
                name = "肌肉拉伤",
                hpLossPerHour = 0,
                spPenalty = 1.1f,
                infectionRisk = 0.0f,
                naturalRecoveryHours = 36,
                narrativeHint = "肌肉纤维轻微撕裂，发力时酸痛无力"
            ),
            InjuryTag(
                id = "ligament_tear",
                name = "韧带撕裂",
                hpLossPerHour = 0,
                spPenalty = 1.4f,
                infectionRisk = 0.0f,
                naturalRecoveryHours = 120,
                requiredTreatment = "护具",
                narrativeHint = "关节异常松动，每一次承重都像要脱臼一般"
            ),
            InjuryTag(
                id = "severe_contusion",
                name = "重度挫伤",
                hpLossPerHour = 0,
                spPenalty = 1.1f,
                infectionRisk = 0.0f,
                naturalRecoveryHours = 48,
                narrativeHint = "大块皮下淤血，触碰时剧痛，但骨骼尚完好"
            ),

            // ===== 内脏损伤 =====
            InjuryTag(
                id = "internal_hemorrhage",
                name = "内出血",
                hpLossPerHour = 5,
                spPenalty = 1.2f,
                infectionRisk = 0.0f,
                naturalRecoveryHours = 240,
                requiredTreatment = "止血剂",
                narrativeHint = "皮肤苍白湿冷，脉搏细速，体内某处正在无声地失血"
            ),
            InjuryTag(
                id = "lung_contusion",
                name = "肺挫伤",
                hpLossPerHour = 2,
                spPenalty = 1.4f,
                infectionRisk = 0.1f,
                naturalRecoveryHours = 120,
                requiredTreatment = "呼吸机",
                narrativeHint = "呼吸短促，咳出带血丝的泡沫痰，每一次吸气都如火烧"
            ),
            InjuryTag(
                id = "kidney_contusion",
                name = "肾挫伤",
                hpLossPerHour = 2,
                spPenalty = 1.2f,
                infectionRisk = 0.1f,
                naturalRecoveryHours = 96,
                narrativeHint = "腰背部剧烈疼痛，尿液可能呈现淡红色"
            ),

            // ===== 感染与疾病 =====
            InjuryTag(
                id = "mild_infection",
                name = "轻度感染",
                hpLossPerHour = 2,
                spPenalty = 1.1f,
                infectionRisk = 0.30f,
                naturalRecoveryHours = 72,
                requiredTreatment = "抗生素",
                narrativeHint = "伤口周围红肿发热，体温略微升高——这是感染的征兆"
            ),
            InjuryTag(
                id = "common_cold",
                name = "普通感冒",
                hpLossPerHour = 0,
                spPenalty = 1.2f,
                infectionRisk = 0.2f,
                naturalRecoveryHours = 48,
                narrativeHint = "鼻塞流涕，浑身酸痛，免疫力低下时的小麻烦"
            ),
            InjuryTag(
                id = "purulent_infection",
                name = "化脓性感染",
                hpLossPerHour = 4,
                spPenalty = 1.3f,
                infectionRisk = 0.50f,
                naturalRecoveryHours = 120,
                requiredTreatment = "抗生素",
                narrativeHint = "伤口流出黄绿色脓液，散发恶臭，全身乏力伴低烧"
            ),
            InjuryTag(
                id = "pneumonia",
                name = "肺炎",
                hpLossPerHour = 3,
                spPenalty = 1.4f,
                infectionRisk = 0.4f,
                naturalRecoveryHours = 168,
                requiredTreatment = "抗生素",
                narrativeHint = "持续高热、剧烈咳嗽，每一次呼吸都像在泥沼中挣扎"
            ),
            InjuryTag(
                id = "tetanus",
                name = "破伤风",
                hpLossPerHour = 2,
                spPenalty = 1.3f,
                infectionRisk = 0.5f,
                naturalRecoveryHours = 120,
                requiredTreatment = "抗毒素",
                narrativeHint = "肌肉僵硬痉挛，牙关紧咬，对外界刺激极度敏感"
            ),
            InjuryTag(
                id = "septic_shock",
                name = "伤口感染性休克",
                hpLossPerHour = 8,
                spPenalty = 1.5f,
                infectionRisk = 0.80f,
                naturalRecoveryHours = 168,
                requiredTreatment = "强效抗生素",
                narrativeHint = "感染已扩散至全身，意识时而模糊时而清醒，生命垂危"
            ),

            // ===== 中毒类 =====
            InjuryTag(
                id = "food_poisoning",
                name = "食物中毒",
                hpLossPerHour = 1,
                spPenalty = 1.3f,
                infectionRisk = 0.0f,
                naturalRecoveryHours = 24,
                narrativeHint = "恶心呕吐，腹部绞痛，浑身虚汗不止"
            ),
            InjuryTag(
                id = "snake_venom",
                name = "蛇毒中毒",
                hpLossPerHour = 4,
                spPenalty = 1.2f,
                infectionRisk = 0.1f,
                naturalRecoveryHours = 48,
                requiredTreatment = "抗蛇毒血清",
                narrativeHint = "伤口周围迅速肿胀发黑，毒素随血液蔓延，头晕目眩"
            ),

            // ===== 烧伤类 =====
            InjuryTag(
                id = "first_degree_burn",
                name = "一级烧伤",
                hpLossPerHour = 0,
                spPenalty = 1.05f,
                infectionRisk = 0.05f,
                naturalRecoveryHours = 48,
                narrativeHint = "皮肤红肿刺痛，如同剧烈晒伤，但未起水泡"
            ),
            InjuryTag(
                id = "second_degree_burn",
                name = "二级烧伤",
                hpLossPerHour = 1,
                spPenalty = 1.15f,
                infectionRisk = 0.15f,
                naturalRecoveryHours = 72,
                requiredTreatment = "烫伤膏",
                narrativeHint = "密集水泡覆盖红肿皮肤，破裂后露出湿润的红色创面"
            ),
            InjuryTag(
                id = "third_degree_burn",
                name = "三级烧伤",
                hpLossPerHour = 3,
                spPenalty = 1.3f,
                infectionRisk = 0.3f,
                naturalRecoveryHours = 200,
                requiredTreatment = "植皮手术包",
                narrativeHint = "皮肤焦黑或蜡白，神经末梢已毁，反而感觉不到疼痛"
            ),

            // ===== 环境伤害 =====
            InjuryTag(
                id = "radiation_burn",
                name = "辐射灼伤",
                hpLossPerHour = 3,
                spPenalty = 1.2f,
                infectionRisk = 0.15f,
                naturalRecoveryHours = 96,
                requiredTreatment = "碘片",
                narrativeHint = "皮肤出现大片红斑和水泡，伴随着持续的灼痛感和恶心"
            ),
            InjuryTag(
                id = "chemical_burn",
                name = "化学灼伤",
                hpLossPerHour = 2,
                spPenalty = 1.1f,
                infectionRisk = 0.10f,
                naturalRecoveryHours = 72,
                narrativeHint = "皮肤被化学物质腐蚀，呈暗褐色坏死，疼痛反而被麻痹感取代"
            ),
            InjuryTag(
                id = "frostbite",
                name = "冻伤",
                hpLossPerHour = 1,
                spPenalty = 1.1f,
                infectionRisk = 0.05f,
                naturalRecoveryHours = 48,
                narrativeHint = "手指和脚趾失去知觉，皮肤呈蜡白色，回暖时剧痛钻心"
            ),
            InjuryTag(
                id = "heatstroke",
                name = "热射病",
                hpLossPerHour = 3,
                spPenalty = 1.5f,
                infectionRisk = 0.0f,
                naturalRecoveryHours = 24,
                narrativeHint = "体温急剧升高，皮肤干热无汗，意识开始模糊"
            ),
            InjuryTag(
                id = "hypothermia",
                name = "低体温症",
                hpLossPerHour = 2,
                spPenalty = 1.4f,
                infectionRisk = 0.05f,
                naturalRecoveryHours = 12,
                narrativeHint = "浑身剧烈颤抖，动作迟缓僵硬，只想蜷缩成一团"
            ),
            InjuryTag(
                id = "altitude_sickness",
                name = "高原反应",
                hpLossPerHour = 1,
                spPenalty = 1.3f,
                infectionRisk = 0.0f,
                naturalRecoveryHours = 48,
                narrativeHint = "头痛欲裂，呼吸急促，恶心无力——空气太稀薄了"
            ),

            // ===== 特殊感官损伤 =====
            InjuryTag(
                id = "corneal_abrasion",
                name = "眼角膜擦伤",
                hpLossPerHour = 0,
                spPenalty = 1.1f,
                infectionRisk = 0.05f,
                naturalRecoveryHours = 48,
                narrativeHint = "眼睛异物感极强，畏光流泪，视野中蒙上一层模糊"
            ),
            InjuryTag(
                id = "eardrum_perforation",
                name = "耳膜穿孔",
                hpLossPerHour = 0,
                spPenalty = 1.0f,
                infectionRisk = 0.1f,
                naturalRecoveryHours = 72,
                narrativeHint = "一侧听力骤降，耳内持续嗡鸣，偶尔伴有尖锐刺痛"
            ),

            // ===== 精神与疲劳 =====
            InjuryTag(
                id = "mild_concussion",
                name = "轻微脑震荡",
                hpLossPerHour = 0,
                spPenalty = 1.2f,
                infectionRisk = 0.0f,
                naturalRecoveryHours = 48,
                narrativeHint = "头晕目眩，视线偶尔出现重影，集中注意力变得困难"
            ),
            InjuryTag(
                id = "acute_stress",
                name = "急性应激障碍",
                hpLossPerHour = 0,
                spPenalty = 1.2f,
                infectionRisk = 0.0f,
                naturalRecoveryHours = 24,
                narrativeHint = "心悸手抖，易受惊吓，脑海中反复闪现可怕画面"
            ),
            InjuryTag(
                id = "insomnia",
                name = "失眠",
                hpLossPerHour = 0,
                spPenalty = 1.3f,
                infectionRisk = 0.0f,
                naturalRecoveryHours = 24,
                narrativeHint = "无法入睡或浅眠易醒，精神涣散，体能恢复极慢"
            ),
            InjuryTag(
                id = "severe_exhaustion",
                name = "重度疲惫",
                hpLossPerHour = 0,
                spPenalty = 1.4f,
                infectionRisk = 0.0f,
                naturalRecoveryHours = 12,
                narrativeHint = "连抬起手臂都变得奢侈，世界仿佛隔着一层毛玻璃"
            ),

            // ===== 营养与代谢 =====
            InjuryTag(
                id = "dehydration",
                name = "脱水症状",
                hpLossPerHour = 1,
                spPenalty = 1.2f,
                infectionRisk = 0.0f,
                naturalRecoveryHours = 12,
                narrativeHint = "嘴唇干裂，皮肤失去弹性，需要尽快补充水分"
            ),
            InjuryTag(
                id = "malnutrition",
                name = "营养不良",
                hpLossPerHour = 1,
                spPenalty = 1.2f,
                infectionRisk = 0.05f,
                naturalRecoveryHours = 72,
                narrativeHint = "长期缺乏足够营养，身体日渐消瘦，免疫系统变得脆弱"
            ),

            // ===== 其他 =====
            InjuryTag(
                id = "sprain",
                name = "扭伤",
                hpLossPerHour = 0,
                spPenalty = 1.15f,
                infectionRisk = 0.0f,
                naturalRecoveryHours = 36,
                narrativeHint = "关节肿胀，活动受限，虽不致命却让人行动不便"
            )
        )
        return list.associateBy { it.name }
    }
}
