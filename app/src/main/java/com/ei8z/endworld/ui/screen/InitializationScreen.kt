package com.ei8z.endworld.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ei8z.endworld.data.model.GameConfig

/**
 * V2.2 开局界面。所有配置由 [gameConfig] 驱动（assets/game_config.json），无硬编码。
 *
 * @param gameConfig 游戏系统配置（性格修正、末日等级、初始数值等）
 * @param onStartGame 回调：(name, gender, personality, adultProfile, doomType, doomLevel)
 */
@Composable
fun InitializationScreen(
    gameConfig: GameConfig,
    onStartGame: (String, String, String, String, String, Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var genderPhysique by remember { mutableStateOf(gameConfig.genders.first()) }
    var personality by remember { mutableStateOf(gameConfig.personalities.first().label) }
    var adultProfile by remember { mutableStateOf("") }
    var doomType by remember { mutableStateOf(gameConfig.doomTypes.first()) }
    var doomLevel by remember { mutableIntStateOf(gameConfig.initialStats.doomLevelMin) }

    var genderExpanded by remember { mutableStateOf(false) }
    var personalityExpanded by remember { mutableStateOf(false) }
    var doomExpanded by remember { mutableStateOf(false) }

    val currentDoom = gameConfig.doomLevels.find { it.id == doomLevel }
    val currentPersonality = gameConfig.personalities.find { it.label == personality }

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
                gameConfig.genders.forEach { g ->
                    DropdownMenuItem(text = { Text(g) }, onClick = { genderPhysique = g; genderExpanded = false })
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 性格（显示 config 中的修正提示）
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { personalityExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text("性格: $personality ${currentPersonality?.hint.orEmpty()}")
            }
            DropdownMenu(expanded = personalityExpanded, onDismissRequest = { personalityExpanded = false }) {
                gameConfig.personalities.forEach { p ->
                    DropdownMenuItem(
                        text = { Text("${p.label} ${p.hint}") },
                        onClick = { personality = p.label; personalityExpanded = false }
                    )
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
                gameConfig.doomTypes.forEach { t ->
                    DropdownMenuItem(text = { Text(t) }, onClick = { doomType = t; doomExpanded = false })
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = adultProfile,
            onValueChange = { adultProfile = it },
            label = { Text("成人特质（可选）") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 末日烈度选择器（数据驱动）
        Text(
            text = "末日烈度: $doomLevel — ${currentDoom?.label ?: ""}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Slider(
            value = doomLevel.toFloat(),
            onValueChange = { doomLevel = it.toInt() },
            valueRange = gameConfig.initialStats.doomLevelMin.toFloat()
                ..gameConfig.initialStats.doomLevelMax.toFloat(),
            steps = (gameConfig.doomLevels.size - 2).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val levels = gameConfig.doomLevels
            Text("${levels.first().id}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (levels.size > 2) {
                Text("${levels[levels.size / 2].id}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (levels.size > 1) {
                Text("${levels.last().id}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            text = currentDoom?.description ?: "",
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
