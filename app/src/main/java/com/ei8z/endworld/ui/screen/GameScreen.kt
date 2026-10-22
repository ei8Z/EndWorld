@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
package com.ei8z.endworld.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ei8z.endworld.data.local.entity.*
import com.ei8z.endworld.data.model.SpeedMode
import com.ei8z.endworld.data.model.SuggestionItem
import com.ei8z.endworld.data.remote.model.KnownPoint
import com.ei8z.endworld.domain.state.SkillLibrary
import com.ei8z.endworld.ui.mode.UIMode
import com.ei8z.endworld.ui.viewmodel.GameViewModel
import com.ei8z.endworld.ui.viewmodel.StoryEntry
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun GameScreen(viewModel: GameViewModel) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val currentStory by viewModel.currentStory.collectAsStateWithLifecycle()
    val currentReasoning by viewModel.currentReasoning.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val canRetry by viewModel.canRetry.collectAsStateWithLifecycle()

    val playerState by viewModel.playerState.collectAsStateWithLifecycle(initialValue = null)
    val worldInfo by viewModel.worldInfo.collectAsStateWithLifecycle(initialValue = null)
    val inventory by viewModel.inventory.collectAsStateWithLifecycle(initialValue = emptyList())
    val memories by viewModel.memories.collectAsStateWithLifecycle(initialValue = emptyList())
    val npcs by viewModel.npcs.collectAsStateWithLifecycle(initialValue = emptyList())

    val gmStrategy by viewModel.gmStrategy.collectAsStateWithLifecycle()
    val uiMode by viewModel.uiMode.collectAsStateWithLifecycle()

    // V3.0 加速/死亡状态
    val speedMode by viewModel.speedMode.collectAsStateWithLifecycle()
    val accelerationText by viewModel.accelerationStatusText.collectAsStateWithLifecycle()
    val isDead by viewModel.isDead.collectAsStateWithLifecycle()
    val deathNarrative by viewModel.deathNarrative.collectAsStateWithLifecycle()
    val deathCause by viewModel.deathCause.collectAsStateWithLifecycle()
    val legacySummary by viewModel.legacySummary.collectAsStateWithLifecycle()

    var customAction by remember { mutableStateOf("") }
    var showStatusDialog by remember { mutableStateOf(false) }
    var showGmDialog by remember { mutableStateOf(false) }
    var suggestionsExpanded by remember { mutableStateOf(true) }
    var selectedSuggestion by remember { mutableStateOf<SuggestionItem?>(null) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(history.size, currentStory) {
        if (history.isNotEmpty() || currentStory.isNotEmpty()) {
            listState.animateScrollToItem(
                index = (history.size * 2).coerceAtLeast(0)
            )
        }
    }

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
                        // V3.0 三速切换器
                        SpeedModeSwitcher(
                            currentMode = speedMode,
                            onModeSelected = { viewModel.setSpeedMode(it) }
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showGmDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "GM策略")
                    }
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
                    // V3.0 加速模式时间流摘要面板
                    if (speedMode == SpeedMode.ACCELERATION) {
                        AccelerationPanel(
                            statusText = accelerationText,
                            onExitAcceleration = { viewModel.setSpeedMode(SpeedMode.NORMAL) }
                        )
                    }
                    // 可折叠的建议区域（非加速模式）
                    else if (suggestions.isNotEmpty() && !isLoading) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "行动建议",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            IconButton(
                                onClick = { suggestionsExpanded = !suggestionsExpanded },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    if (suggestionsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (suggestionsExpanded) "收起" else "展开",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        AnimatedVisibility(
                            visible = suggestionsExpanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                suggestions.forEach { suggestion ->
                                    OutlinedButton(
                                        onClick = { selectedSuggestion = suggestion },
                                        modifier = Modifier.padding(bottom = 4.dp)
                                    ) {
                                        Text(suggestion.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
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
                            maxLines = 3,
                            enabled = !isLoading && speedMode != SpeedMode.ACCELERATION
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                if (customAction.isNotBlank()) {
                                    viewModel.sendAction(customAction)
                                    customAction = ""
                                }
                            },
                            enabled = !isLoading && customAction.isNotBlank() &&
                                speedMode != SpeedMode.ACCELERATION
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        // 前代纪事卡片位于叙事区上方，只占自身高度，不遮挡叙事与输入区
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (legacySummary.isNotBlank()) {
                LegacySummaryCard(
                    summary = legacySummary,
                    onDismiss = { viewModel.dismissLegacySummary() }
                )
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(history) { entry ->
                        StoryEntryItem(entry, debugMode = gmStrategy.debugMode)
                    }

                    if (currentStory.isNotEmpty() || currentReasoning.isNotEmpty() || isLoading) {
                        item {
                            StoryEntryItem(
                                StoryEntry(
                                    reasoning = currentReasoning,
                                    story = currentStory,
                                    isStreaming = true
                                ),
                                debugMode = gmStrategy.debugMode
                            )
                        }
                    }

                    if (error != null) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("系统异常: $error", color = MaterialTheme.colorScheme.onErrorContainer)
                                    if (canRetry) {
                                        Button(
                                            onClick = { viewModel.retryLastAction() },
                                            modifier = Modifier.padding(top = 8.dp)
                                        ) {
                                            Text("重试")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(100.dp)) }
                }

                // V2.1 UI 模式视觉叠加层
                UIModeOverlay(uiMode)

                if (isLoading && currentStory.isEmpty()) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
            }
        }
    }

    // V3.0 死亡覆盖层
    if (isDead) {
        DeathOverlay(
            cause = deathCause,
            narrative = deathNarrative,
            error = error,
            isLoading = isLoading,
            onInherit = { viewModel.inheritWorld() },
            onRestart = { viewModel.restartGame() }
        )
    }

    if (showGmDialog) {
        GmSettingsDialog(
            current = gmStrategy,
            onSave = { viewModel.updateGmStrategy(it) },
            onReset = { viewModel.resetGmStrategy() },
            onDismiss = { showGmDialog = false }
        )
    }

    if (showStatusDialog) {
        StatusDialog(playerState, worldInfo, inventory, memories, npcs) { showStatusDialog = false }
    }

    selectedSuggestion?.let { suggestion ->
        SuggestionConfirmDialog(
            suggestion = suggestion,
            onConfirm = {
                viewModel.sendAction(suggestion.label, suggestion.actionParsed)
                selectedSuggestion = null
            },
            onDismiss = { selectedSuggestion = null }
        )
    }
}

@Composable
fun StoryEntryItem(entry: StoryEntry, debugMode: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (!entry.action.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(
                    text = entry.action,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp, 12.dp, 0.dp, 12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        // 推理过程：debug 模式显示完整内容，否则仅流式时显示动态省略号
        if (entry.reasoning.isNotEmpty()) {
            if (debugMode) {
                // 调试模式：显示完整推理
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.padding(bottom = 8.dp).fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Psychology, null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (entry.isStreaming) "城主裁决中..." else "裁决记录",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = entry.reasoning,
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            fontStyle = FontStyle.Italic,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            } else {
                // 非调试模式：流式时显示动态省略号，完成后不渲染
                if (entry.isStreaming) {
                    AnimatedThinkingDots(
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
        }

        if (entry.story.isNotEmpty()) {
            Text(
                text = entry.story,
                style = MaterialTheme.typography.bodyLarge.copy(
                    lineHeight = 28.sp,
                    letterSpacing = 0.5.sp
                ),
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun AnimatedThinkingDots(modifier: Modifier = Modifier) {
    var dotCount by remember { mutableIntStateOf(1) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            dotCount = (dotCount % 3) + 1
        }
    }

    Row(
        modifier = modifier.padding(start = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Psychology, null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "思考中" + ".".repeat(dotCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
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
                InfoRow("性", (playerState?.genderPhysique?:"") +","+ (playerState?.adultProfile ?: "..."))
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

            // V2.2 技能标签展示
            InfoSection("生存技能", Icons.Default.AutoGraph) {
                val skillIds = playerState?.skillTags ?: emptyList()
                if (skillIds.isEmpty()) {
                    Text("尚未掌握任何技能", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                } else {
                    FlowRow(modifier = Modifier.fillMaxWidth()) {
                        skillIds.forEach { skillId ->
                            val skill = SkillLibrary.get(skillId)
                            val label = skill?.let { "${it.name} Lv.1" } ?: skillId
                            AssistChip(
                                onClick = {},
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.padding(end = 4.dp, bottom = 4.dp),
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            )
                        }
                    }
                }
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
    val categories = listOf("食物", "饮水", "武器", "工具", "医疗", "特殊")
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
                            Text(item.name); Text("${item.quantity}${item.measureUnit}", color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                    item { HorizontalDivider(modifier = Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)) }
                }
            }
            val otherItems = inventory.filter { it.category !in categories }
            if (otherItems.isNotEmpty()) {
                item { Text("其他", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp)) }
                items(otherItems) { item ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(item.name); Text("${item.quantity}${item.measureUnit}", color = MaterialTheme.colorScheme.secondary)
                    }
                }
                item { HorizontalDivider(modifier = Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)) }
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
                    // V2.2 NPC性格标签
                    if (npc.personalityTags.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            npc.personalityTags.forEach { tag ->
                                AssistChip(
                                    onClick = {},
                                    label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.height(24.dp),
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                    )
                                )
                            }
                        }
                    }
                    // V2.2 NPC需求
                    if (!npc.currentNeed.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("需求：${npc.currentNeed}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    // V2.2 上次互动摘要
                    if (!npc.lastInteractionSummary.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(npc.lastInteractionSummary, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { npc.affinity / 100f }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("好感度: ${npc.affinity}%", style = MaterialTheme.typography.labelSmall)
                        if (npc.interactionCount > 0) {
                            Text("互动${npc.interactionCount}次", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
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

// ======================== V2.1 UI 模式视觉效果 ========================

/**
 * 根据 [UIMode] 渲染占位视觉效果叠加层。
 *
 * TODO: Phase 11 替换为实际动画（命运模式齿轮动画、危险模式心跳波纹等）。
 *       当前使用半透明颜色遮罩作为占位实现。
 */
@Composable
fun UIModeOverlay(mode: UIMode) {
    val color = when (mode) {
        UIMode.EXPLORE -> Color.Transparent
        UIMode.FATE -> Color(0x30_00_00_00)    // 暗色遮罩（命运感）
        UIMode.READING -> Color(0x15_FF_D7_00)  // 暖黄色滤镜
        UIMode.DANGER -> Color(0x20_FF_00_00)   // 红色边框感
        UIMode.SERENE -> Color(0x15_00_88_88)   // 蓝绿色调
        UIMode.ACCELERATING -> Color(0x15_88_00_CC) // 淡紫色调
    }

    val alpha by animateFloatAsState(
        targetValue = if (mode == UIMode.EXPLORE) 0f else 1f,
        animationSpec = androidx.compose.animation.core.tween(400),
        label = "modeAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color.copy(alpha = alpha * (color.alpha.coerceIn(0f, 1f))))
    ) {
        // 命运模式中央提示
        if (mode == UIMode.FATE && alpha > 0.5f) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = Color(0xFF_EFEFEF),
                    strokeWidth = 3.dp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "命运裁决中...",
                    color = Color(0xFF_EFEFEF),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * Suggestion 确认弹窗。展示选中建议的完整行动信息，
 * 用户确认后才提交，避免误触。
 */
@Composable
private fun SuggestionConfirmDialog(
    suggestion: SuggestionItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val ap = suggestion.actionParsed
    val riskColor = when (ap.riskLevel) {
        "低" -> Color(0xFF_4CAF50)
        "中" -> Color(0xFFFFA000)
        "高" -> Color(0xFF_E53935)
        "极高" -> Color(0xFF_B71C1C)
        else -> MaterialTheme.colorScheme.onSurface
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Info, contentDescription = null) },
        title = { Text("确认行动", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    suggestion.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )

                HorizontalDivider()

                InfoRow("行动类型", "${ap.primaryType} · ${ap.subType}")
                InfoRow("目标", ap.target)
                InfoRow("位置", ap.location)
                InfoRow("方式", ap.method)

                HorizontalDivider()

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("风险等级：", style = MaterialTheme.typography.bodyMedium)
                    Surface(
                        color = riskColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            ap.riskLevel,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            color = riskColor,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                InfoRow("预估耗时", "${ap.estimatedTimeMinutes} 分钟")

                if (!suggestion.riskHint.isNullOrBlank()) {
                    Text(
                        "⚠ ${suggestion.riskHint}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (!suggestion.timeHint.isNullOrBlank()) {
                    Text(
                        suggestion.timeHint,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("确认执行")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

// ======================== V3.0 Composable 组件 ========================

/** 三速模式切换器 */
@Composable
fun SpeedModeSwitcher(currentMode: SpeedMode, onModeSelected: (SpeedMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SpeedMode.entries.forEach { mode ->
            val label = when (mode) {
                SpeedMode.DECELERATION -> "减速"
                SpeedMode.NORMAL -> "正常"
                SpeedMode.ACCELERATION -> "加速"
            }
            val selected = currentMode == mode
            FilterChip(
                selected = selected,
                onClick = { onModeSelected(mode) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.height(28.dp)
            )
        }
    }
}

/** 加速模式时间流摘要面板 */
@Composable
fun AccelerationPanel(statusText: String, onExitAcceleration: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("⏩ 时间流逝中", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
            if (statusText.isNotBlank()) {
                Text(statusText, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            } else {
                Text("世界在加速运转……", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = onExitAcceleration, modifier = Modifier.height(36.dp)) {
            Text("恢复正常", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** C4 前代纪事：继承成功后展示上一局结尾，由玩家手动关闭。 */
@Composable
fun LegacySummaryCard(summary: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "前代纪事",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            // 纪事最长 10 行，限高滚动：长摘要只撑高卡片，不会把叙事区挤没
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        }
    }
}

/** 死亡覆盖层：死亡即本局终点。继承保留世界重建继承者，重新开始会清库，两者都必须二次确认。 */
@Composable
fun DeathOverlay(
    cause: String, narrative: String, error: String?, isLoading: Boolean,
    onInherit: () -> Unit, onRestart: () -> Unit
) {
    var showInheritConfirm by remember { mutableStateOf(false) }
    var showRestartConfirm by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.padding(32.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("你死了", style = MaterialTheme.typography.headlineMedium,
                    color = Color(0xFFE0E0E0))
                Spacer(modifier = Modifier.height(4.dp))
                Text("死因：$cause", style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB0B0B0))
                Spacer(modifier = Modifier.height(16.dp))
                Text(narrative, style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFD0D0D0), maxLines = 8, overflow = TextOverflow.Ellipsis)
                // 重开失败必须让玩家看见，否则覆盖层会静默停在原地
                if (error != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(error, style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF5252))
                }
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { showInheritConfirm = true },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("继承世界")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showRestartConfirm = true },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    // 卡片底色固定深色，默认 primary 文字在此对比度不足，统一用浅灰
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD0D0D0)),
                    border = BorderStroke(1.dp, Color(0xFFD0D0D0))
                ) {
                    Text("重新开始")
                }
            }
        }
    }

    if (showInheritConfirm) {
        AlertDialog(
            onDismissRequest = { showInheritConfirm = false },
            title = { Text("继承世界？") },
            text = { Text("创建继承者并保留这个世界？本局角色与背包将重置。") },
            confirmButton = {
                TextButton(onClick = {
                    showInheritConfirm = false
                    onInherit()
                }) {
                    Text("继承世界")
                }
            },
            dismissButton = {
                TextButton(onClick = { showInheritConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showRestartConfirm) {
        AlertDialog(
            onDismissRequest = { showRestartConfirm = false },
            title = { Text("重新开始？") },
            text = { Text("将清除本局所有进度（角色、世界、NPC、记忆、历史），不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    showRestartConfirm = false
                    onRestart()
                }) {
                    Text("重新开始")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestartConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}
