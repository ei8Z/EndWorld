package com.ei8z.endworld.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ei8z.endworld.data.repository.GmStrategy

@Composable
fun GmSettingsDialog(
    current: GmStrategy,
    onSave: (GmStrategy) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var narrativeStyle by remember { mutableStateOf(current.narrativeStyle) }
    var difficulty by remember { mutableStateOf(current.difficulty) }
    var eventFrequency by remember { mutableStateOf(current.eventFrequency) }
    var descriptionRichness by remember { mutableStateOf(current.descriptionRichness) }
    var tone by remember { mutableStateOf(current.tone) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("GM 策略设置") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "调整世界规则（不影响 JSON 契约）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                StrategyDropdown("叙事风格", narrativeStyle, GmStrategy.NARRATIVE_STYLES) {
                    narrativeStyle = it
                }
                StrategyDropdown("难度", difficulty, GmStrategy.DIFFICULTIES) { difficulty = it }
                StrategyDropdown("随机事件频率", eventFrequency, GmStrategy.EVENT_FREQUENCIES) {
                    eventFrequency = it
                }
                StrategyDropdown("描述丰富度", descriptionRichness, GmStrategy.RICHNESS_LEVELS) {
                    descriptionRichness = it
                }
                StrategyDropdown("基调", tone, GmStrategy.TONES) { tone = it }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onReset) { Text("重置默认") }
                Row {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(onClick = {
                        onSave(
                            GmStrategy(narrativeStyle, difficulty, eventFrequency, descriptionRichness, tone)
                        )
                        onDismiss()
                    }) { Text("保存") }
                }
            }
        },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = true)
    )
}

@Composable
private fun StrategyDropdown(
    label: String,
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(selected)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = { onSelect(option); expanded = false }
                    )
                }
            }
        }
    }
}
