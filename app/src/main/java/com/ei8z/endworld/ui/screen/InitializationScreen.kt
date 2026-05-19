package com.ei8z.endworld.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun InitializationScreen(onStartGame: (String, String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var genderPhysique by remember { mutableStateOf("成年男性") }
    var personality by remember { mutableStateOf("冷静") }
    var adultProfile by remember { mutableStateOf("") }
    var doomType by remember { mutableStateOf("丧尸危机") }

    val genders = listOf("成年男性", "成年女性", "青少年男性", "青少年女性")
    val personalities = listOf("冷静", "勇敢", "谨慎", "狡猾", "暴躁")
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
            text = "开启你的末日旅程",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("幸存者姓名") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 性别/生理特征
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { genderExpanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("性别/体格: $genderPhysique")
            }
            DropdownMenu(expanded = genderExpanded, onDismissRequest = { genderExpanded = false }) {
                genders.forEach { g ->
                    DropdownMenuItem(text = { Text(g) }, onClick = {
                        genderPhysique = g; genderExpanded = false
                    })
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 性格
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { personalityExpanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("性格: $personality")
            }
            DropdownMenu(expanded = personalityExpanded, onDismissRequest = { personalityExpanded = false }) {
                personalities.forEach { p ->
                    DropdownMenuItem(text = { Text(p) }, onClick = {
                        personality = p; personalityExpanded = false
                    })
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 末日类型
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { doomExpanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("末日类型: $doomType")
            }
            DropdownMenu(expanded = doomExpanded, onDismissRequest = { doomExpanded = false }) {
                doomTypes.forEach { t ->
                    DropdownMenuItem(text = { Text(t) }, onClick = {
                        doomType = t; doomExpanded = false
                    })
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = adultProfile,
            onValueChange = { adultProfile = it },
            label = { Text("成人特质/性癖") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                if (name.isNotBlank()) {
                    onStartGame(name, genderPhysique, personality, adultProfile, doomType)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = name.isNotBlank()
        ) {
            Text("降临末世")
        }
    }
}
