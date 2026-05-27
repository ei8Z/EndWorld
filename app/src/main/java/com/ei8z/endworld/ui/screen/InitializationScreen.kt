package com.ei8z.endworld.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * V2.2 开局界面：增加末日烈度选择器（1-5）和性格修正提示。
 *
 * @param onStartGame 回调：(name, gender, personality, adultProfile, doomType, doomLevel)
 */
@Composable
fun InitializationScreen(
    onStartGame: (String, String, String, String, String, Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var genderPhysique by remember { mutableStateOf("成年男性") }
    var personality by remember { mutableStateOf("冷静") }
    var adultProfile by remember { mutableStateOf("") }
    var doomType by remember { mutableStateOf("丧尸危机") }
    var doomLevel by remember { mutableIntStateOf(1) }

    val genders = listOf("成年男性", "成年女性", "青少年男性", "青少年女性")
    val personalities = listOf("冷静", "勇敢", "谨慎", "狡猾", "暴躁", "强壮", "敏捷", "坚韧")
    val doomTypes = listOf("丧尸危机", "核冬天", "极度严寒", "智械危机")

    var genderExpanded by remember { mutableStateOf(false) }
    var personalityExpanded by remember { mutableStateOf(false) }
    var doomExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "命运启示录",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "基础信息填写",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("幸存者姓名") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 性别
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { genderExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text("性别/体格: $genderPhysique")
            }
            DropdownMenu(expanded = genderExpanded, onDismissRequest = { genderExpanded = false }) {
                genders.forEach { g ->
                    DropdownMenuItem(text = { Text(g) }, onClick = { genderPhysique = g; genderExpanded = false })
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 性格
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { personalityExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text("性格: $personality ${personalityHint(personality)}")
            }
            DropdownMenu(expanded = personalityExpanded, onDismissRequest = { personalityExpanded = false }) {
                personalities.forEach { p ->
                    DropdownMenuItem(text = { Text("$p ${personalityHint(p)}") }, onClick = {
                        personality = p; personalityExpanded = false
                    })
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 末日类型
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { doomExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text("末日类型: $doomType")
            }
            DropdownMenu(expanded = doomExpanded, onDismissRequest = { doomExpanded = false }) {
                doomTypes.forEach { t ->
                    DropdownMenuItem(text = { Text(t) }, onClick = { doomType = t; doomExpanded = false })
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = adultProfile,
            onValueChange = { adultProfile = it },
            label = { Text("成人特质/性癖（可选）") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // V2.2: 末日烈度选择器
        Text(
            text = "末日烈度: $doomLevel — ${doomLevelLabel(doomLevel)}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Slider(
            value = doomLevel.toFloat(),
            onValueChange = { doomLevel = it.toInt() },
            valueRange = 1f..5f,
            steps = 3,
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("1", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("3", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("5", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = doomLevelDescription(doomLevel),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (name.isNotBlank()) {
                    onStartGame(name, genderPhysique, personality, adultProfile, doomType, doomLevel)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = name.isNotBlank()
        ) {
            Text("抽取生存档案")
        }
    }
}

private fun personalityHint(personality: String): String = when (personality) {
    "强壮", "军人", "战士" -> "(HP+10)"
    "敏捷", "跑者", "猎人" -> "(SP+10)"
    "坚韧", "生存" -> "(HP+5 SP+5)"
    else -> ""
}

private fun doomLevelLabel(level: Int): String = when (level) {
    1 -> "生存微光"
    2 -> "暗流涌动"
    3 -> "末日降临"
    4 -> "绝地求生"
    5 -> "灭绝倒计时"
    else -> ""
}

private fun doomLevelDescription(level: Int): String = when (level) {
    1 -> "DC基础倍率 0.8x，事件触发率极低"
    2 -> "DC基础倍率 1.0x，标准末日体验"
    3 -> "DC基础倍率 1.2x，事件频繁，生存困难"
    4 -> "DC基础倍率 1.4x，夜间/恶劣天气修正叠加"
    5 -> "DC基础倍率 1.5x，致命事件频繁，随时可能死亡"
    else -> ""
}
