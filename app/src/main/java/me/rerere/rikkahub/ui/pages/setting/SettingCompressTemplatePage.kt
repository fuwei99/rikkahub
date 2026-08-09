package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.data.ai.prompts.CompressTemplate
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_TEMPLATES
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.modifier.onClick
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

private val SCENES = listOf("general", "roleplay", "search", "coding", "custom")

private val EFFORTS = listOf<String?>(null) + ReasoningLevel.entries.map { it.name.lowercase() }

/**
 * 压缩模板管理页（方案 2026-08-08 §4.3）：
 * 新建 / 从内置复制 / 编辑 / 删除（内置不可删）/ 设为全局默认模板。
 */
@Composable
fun SettingCompressTemplatePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var editing by remember { mutableStateOf<CompressTemplate?>(null) }
    var pendingDelete by remember { mutableStateOf<CompressTemplate?>(null) }

    val templates = settings.compressTemplates.ifEmpty { DEFAULT_COMPRESS_TEMPLATES }

    fun persist(list: List<CompressTemplate>, defaultId: Uuid? = settings.defaultCompressTemplateId) {
        vm.updateSettings(
            settings.copy(
                compressTemplates = list,
                defaultCompressTemplateId = defaultId,
            )
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("压缩模板") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    editing = CompressTemplate(
                        name = "新模板",
                        scene = "custom",
                        prompt = DEFAULT_COMPRESS_TEMPLATES.first().prompt,
                    )
                },
                icon = { Icon(HugeIcons.Add01, null) },
                text = { Text("新建模板") },
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp + innerPadding.calculateStartPadding(LayoutDirection.Ltr),
                top = innerPadding.calculateTopPadding() + 8.dp,
                end = 16.dp + innerPadding.calculateEndPadding(LayoutDirection.Ltr),
                bottom = innerPadding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "压缩模板决定「对话怎么被总结」：每个模板自带压缩模型、思考强度和提示词。" +
                        "助手可以绑定默认模板，压缩时也能临时换。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(templates, key = { it.id }) { template ->
                val isDefault = settings.defaultCompressTemplateId == template.id
                ListItem(
                    headlineContent = {
                        Text(template.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            val modelName = template.modelId
                                ?.let { id -> settings.providers.flatMap { it.models }.firstOrNull { it.id == id } }
                                ?.displayName ?: "跟随对话模型"
                            Text(
                                listOfNotNull(
                                    template.scene,
                                    modelName,
                                    template.reasoningEffort?.takeIf { it.isNotBlank() },
                                    if (template.builtin) "内置" else null,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (isDefault) {
                                    AssistChip(onClick = {}, label = { Text("全局默认") })
                                } else {
                                    AssistChip(
                                        onClick = { persist(templates, defaultId = template.id) },
                                        label = { Text("设为默认") },
                                    )
                                }
                            }
                        }
                    },
                    trailingContent = {
                        Row {
                            IconButton(onClick = {
                                editing = template.copy(
                                    id = Uuid.random(),
                                    name = "${template.name} 副本",
                                    builtin = false,
                                )
                            }) {
                                Icon(HugeIcons.Copy01, "复制", modifier = Modifier.size(18.dp))
                            }
                            if (!template.builtin) {
                                IconButton(onClick = { pendingDelete = template }) {
                                    Icon(
                                        HugeIcons.Delete01,
                                        "删除",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier.clip(MaterialTheme.shapes.large).onClick { editing = template },
                )
            }
        }
    }

    editing?.let { target ->
        CompressTemplateEditor(
            template = target,
            settings = settings,
            onDismiss = { editing = null },
            onConfirm = { updated ->
                editing = null
                val exists = templates.any { it.id == updated.id }
                val next = if (exists) {
                    templates.map { if (it.id == updated.id) updated else it }
                } else {
                    templates + updated
                }
                persist(next)
            },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除模板") },
            text = { Text("确定删除「${target.name}」？已生成的总结不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    persist(
                        templates.filterNot { it.id == target.id },
                        defaultId = settings.defaultCompressTemplateId
                            ?.takeIf { it != target.id }
                            ?: DEFAULT_COMPRESS_TEMPLATES.first().id,
                    )
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CompressTemplateEditor(
    template: CompressTemplate,
    settings: me.rerere.rikkahub.data.datastore.Settings,
    onDismiss: () -> Unit,
    onConfirm: (CompressTemplate) -> Unit,
) {
    var name by remember(template.id) { mutableStateOf(template.name) }
    var scene by remember(template.id) { mutableStateOf(template.scene) }
    var prompt by remember(template.id) { mutableStateOf(template.prompt) }
    var modelId by remember(template.id) { mutableStateOf(template.modelId) }
    var effort by remember(template.id) { mutableStateOf(template.reasoningEffort) }
    var sceneMenu by remember { mutableStateOf(false) }
    var effortMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (template.builtin) "编辑内置模板" else "编辑模板") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()).imePadding(),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("模板名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Text("场景", style = MaterialTheme.typography.labelMedium)
                Box {
                    OutlinedButton(
                        onClick = { sceneMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(scene) }
                    DropdownMenu(expanded = sceneMenu, onDismissRequest = { sceneMenu = false }) {
                        SCENES.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s) },
                                onClick = { scene = s; sceneMenu = false },
                            )
                        }
                    }
                }

                Text("压缩模型（不选=跟随对话模型）", style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ModelSelector(
                        modelId = modelId,
                        providers = settings.providers,
                        type = ModelType.CHAT,
                        allowClear = true,
                        onSelect = { modelId = it.id },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { modelId = null }) { Text("清除") }
                }

                Text("思考强度", style = MaterialTheme.typography.labelMedium)
                Box {
                    OutlinedButton(
                        onClick = { effortMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(effort ?: "跟随模型默认") }
                    DropdownMenu(expanded = effortMenu, onDismissRequest = { effortMenu = false }) {
                        EFFORTS.forEach { e ->
                            DropdownMenuItem(
                                text = { Text(e ?: "跟随模型默认") },
                                onClick = { effort = e; effortMenu = false },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("提示词") },
                    supportingText = {
                        Text("占位符：{content} {previous_summary} {target_tokens} {locale} {additional_context}")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    maxLines = 14,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    template.copy(
                        name = name.ifBlank { "未命名模板" },
                        scene = scene,
                        modelId = modelId,
                        reasoningEffort = effort,
                        prompt = prompt.ifBlank { DEFAULT_COMPRESS_TEMPLATES.first().prompt },
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
