@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
package com.ei8z.endworld.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ei8z.endworld.data.local.entity.*
import com.ei8z.endworld.data.remote.model.KnownPoint
import com.ei8z.endworld.ui.viewmodel.GameViewModel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@Composable
fun GameScreen(viewModel: GameViewModel) {
    val story by viewModel.lastStory.collectAsStateWithLifecycle()
    val reasoning by viewModel.reasoning.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val canRetry by viewModel.canRetry.collectAsStateWithLifecycle()
    
    val playerState by viewModel.playerState.collectAsStateWithLifecycle(initialValue = null)
    val worldInfo by viewModel.worldInfo.collectAsStateWithLifecycle(initialValue = null)
    val inventory by viewModel.inventory.collectAsStateWithLifecycle(initialValue = emptyList())
    val memories by viewModel.memories.collectAsStateWithLifecycle(initialValue = emptyList())
    val npcs by viewModel.npcs.collectAsStateWithLifecycle(initialValue = emptyList())

    var customAction by remember { mutableStateOf("") }
    var showStatusDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = worldInfo?.locationScene ?: "求生中...",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "${worldInfo?.currentDate ?: ""} ${worldInfo?.currentTime ?: ""}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showStatusDialog = true }) {
                        Icon(Icons.Default.Person, contentDescription = "状态")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 8.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .navigationBarsPadding()
                        .imePadding()
                ) {
                    if (suggestions.isNotEmpty() && !isLoading) {
                        suggestions.forEach { suggestion ->
                            OutlinedButton(
                                onClick = { viewModel.sendAction(suggestion) },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            ) {
                                Text(suggestion, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customAction,
                            onValueChange = { customAction = it },
                            placeholder = { Text("输入你的行动...") },
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            enabled = !isLoading
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                if (customAction.isNotBlank()) {
                                    viewModel.sendAction(customAction)
                                    customAction = ""
                                }
                            },
                            enabled = !isLoading && customAction.isNotBlank()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
            ) {
                // AI 推理展示
                AnimatedVisibility(
                    visible = reasoning.isNotEmpty(),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.padding(bottom = 16.dp).fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Psychology, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("城主裁决中...", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                text = reasoning,
                                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                fontStyle = FontStyle.Italic,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                if (story.isNotEmpty()) {
                    Text(
                        text = story,
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp, letterSpacing = 0.5.sp),
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                }

                if (error != null) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("系统异常: $error", color = MaterialTheme.colorScheme.onErrorContainer)
                            if (canRetry) {
                                Button(onClick = { viewModel.retryLastAction() }, modifier = Modifier.padding(top = 8.dp)) {
                                    Text("重试")
                                }
                            }
                        }
                    }
                }
            }

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }

    if (showStatusDialog) {
        StatusDialog(playerState, worldInfo, inventory, memories, npcs) { showStatusDialog = false }
    }
}

@Composable
fun StatusDialog(
    playerState: PlayerState?,
    worldInfo: WorldInfo?,
    inventory: List<InventoryItem>,
    memories: List<Memory>,
    npcs: List<NpcRelation>,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("状态", "物资", "记忆", "关系")

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.85f),
        title = {
            Column {
                Text("幸存者终端", style = MaterialTheme.typography.headlineSmall)
                TabRow(selectedTabIndex = selectedTab, containerColor = Color.Transparent) {
                    tabs.forEachIndexed { index, title ->
                        Tab(selected = selectedTab == index, onClick = { selectedTab = index },
                            text = { Text(title, style = MaterialTheme.typography.labelMedium) })
                    }
                }
            }
        },
        text = {
            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    0 -> StatusTab(playerState, worldInfo)
                    1 -> InventoryTab(inventory)
                    2 -> MemoryTab(memories)
                    3 -> NpcTab(npcs)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭终端") } }
    )
}

@Composable
fun StatusTab(playerState: PlayerState?, worldInfo: WorldInfo?) {
    val gson = remember { Gson() }
    val knownPoints = remember(worldInfo?.knownPointsJson) {
        try {
            val type = object : TypeToken<List<KnownPoint>>() {}.type
            gson.fromJson<List<KnownPoint>>(worldInfo?.knownPointsJson, type) ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            InfoSection("生存档案", Icons.Default.AssignmentInd) {
                InfoRow("代号", playerState?.name ?: "...")
                InfoRow("特质", playerState?.personality ?: "...")
                InfoRow("背景", playerState?.baseDescription ?: "...")
                Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("末日烈度", modifier = Modifier.width(80.dp), style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                    repeat(5) { i ->
                        Icon(Icons.Default.Warning, null, modifier = Modifier.size(16.dp),
                            tint = if (i < (playerState?.doomLevel ?: 1)) MaterialTheme.colorScheme.error else Color.LightGray)
                    }
                }
            }
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            
            InfoSection("生命体征", Icons.Default.Favorite) {
                FlowRow(modifier = Modifier.fillMaxWidth()) {
                    playerState?.mentalStateTags?.forEach { tag ->
                        val color = if (tag.contains("伤") || tag.contains("病")) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
                        AssistChip(onClick = {}, label = { Text(tag) }, modifier = Modifier.padding(end = 4.dp),
                            colors = AssistChipDefaults.assistChipColors(containerColor = color))
                    }
                }
                Text("\"${playerState?.innerThought}\"", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, modifier = Modifier.padding(top = 8.dp))
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            InfoSection("随身装备", Icons.Default.Shield) {
                val equipped = playerState?.equippedItems
                if (equipped.isNullOrEmpty()) {
                    Text("无装备", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                } else {
                    FlowRow(modifier = Modifier.fillMaxWidth()) {
                        equipped.forEach { item ->
                            AssistChip(onClick = {}, label = { Text(item) }, modifier = Modifier.padding(end = 4.dp, bottom = 4.dp),
                                colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer))
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            InfoSection("世界动态", Icons.Default.Public) {
                Text(worldInfo?.macroSituation ?: "未知", style = MaterialTheme.typography.bodyMedium)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            InfoSection("探索发现", Icons.Default.Explore) {
                if (knownPoints.isEmpty()) {
                    Text("尚未发现据点", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                } else {
                    knownPoints.forEach { point ->
                        ListItem(headlineContent = { Text(point.location) }, supportingContent = { Text("风险: ${point.risk} | 物资: ${point.possibleItems}") })
                    }
                }
            }
        }
    }
}

@Composable
fun InventoryTab(inventory: List<InventoryItem>) {
    val categories = listOf("食物", "饮水", "武器", "特殊")
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (inventory.isEmpty()) {
            item { Text("行囊空空", modifier = Modifier.padding(16.dp), fontStyle = FontStyle.Italic) }
        } else {
            categories.forEach { cat ->
                val items = inventory.filter { it.category == cat }
                if (items.isNotEmpty()) {
                    item { Text(cat, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp)) }
                    items(items) { item ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(item.name); Text(item.quantity, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                    item { HorizontalDivider(modifier = Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)) }
                }
            }
        }
    }
}

@Composable
fun MemoryTab(memories: List<Memory>) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(memories) { memory ->
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (memory.isPermanent) Icons.Default.Star else Icons.Default.History, null, modifier = Modifier.size(14.dp),
                        tint = if (memory.isPermanent) Color(0xFFFFD700) else Color.Gray)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(memory.location, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
                Text(memory.event, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text("结果: ${memory.result}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            }
        }
    }
}

@Composable
fun NpcTab(npcs: List<NpcRelation>) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(npcs) { npc ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(npc.name, fontWeight = FontWeight.Bold); Text(npc.relation, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { npc.affinity / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("好感度: ${npc.affinity}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))
                }
            }
        }
    }
}

@Composable
fun InfoSection(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(8.dp)); Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(8.dp)); content()
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.width(80.dp), style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun FlowRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    maxItemsInEachRow: Int = Int.MAX_VALUE,
    content: @Composable FlowRowScope.() -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(modifier, horizontalArrangement, verticalArrangement, maxItemsInEachRow, content = content)
}
