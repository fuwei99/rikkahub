package me.rerere.rikkahub.ui.pages.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.ImageTag
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

/** 标签可选的作用域（folder 名）。多选：全选 = 全局，不选 = 未使用 */
private val TAG_SCOPES = listOf(FileFolders.UPLOAD, FileFolders.IMAGES, FileFolders.AVATARS)

/**
 * 相册标签维护页。
 *
 * OCR 只能从这里的列表里挑标签，所以这页是整个标签体系的唯一源头。
 */
@Composable
fun GalleryTagSettingPage(vm: GalleryVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val settings by vm.settings.collectAsState()
    val tags = remember(settings.imageTags) { ImageTag.withBuiltins(settings.imageTags) }

    var editing by remember { mutableStateOf<ImageTag?>(null) }
    var pendingDelete by remember { mutableStateOf<ImageTag?>(null) }
    var showMergeConfirm by remember { mutableStateOf(false) }
    val toaster = LocalToaster.current

    editing?.let { target ->
        GalleryTagEditDialog(
            tag = target,
            onConfirm = {
                vm.upsertTag(it)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.gallery_tag_delete_title)) },
            text = { Text(stringResource(R.string.gallery_tag_delete_message, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteTag(target)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.setting_files_page_delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            },
        )
    }

    if (showMergeConfirm) {
        // 文案在组合阶段取好：onDone 回调跑在协程里，不能碰 stringResource
        val mergeTitle = stringResource(R.string.gallery_tag_merge_default)
        val mergeMessage = stringResource(R.string.gallery_tag_merge_default_message)
        val mergeNone = stringResource(R.string.gallery_tag_merge_default_none)
        val mergeDone = stringResource(R.string.gallery_tag_merge_default_done)
        val missingCount = ImageTag.SEED_TAGS.count { seed -> tags.none { it.id == seed.id } }
        AlertDialog(
            onDismissRequest = { showMergeConfirm = false },
            title = { Text(mergeTitle) },
            text = {
                Text(
                    if (missingCount == 0) mergeNone
                    else mergeMessage.format(missingCount)
                )
            },
            confirmButton = {
                TextButton(
                    enabled = missingCount > 0,
                    onClick = {
                        showMergeConfirm = false
                        vm.mergeDefaultTags { added ->
                            toaster.show(if (added > 0) mergeDone.format(added) else mergeNone)
                        }
                    }
                ) { Text(stringResource(R.string.setting_files_page_bulk_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showMergeConfirm = false }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.gallery_tag_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    // 一键合并默认标签：老用户/同步过的设备自动补齐缺失的种子标签
                    IconButton(onClick = { showMergeConfirm = true }) {
                        Icon(
                            HugeIcons.Download01,
                            contentDescription = stringResource(R.string.gallery_tag_merge_default),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                // 新建默认全选作用域（= 全局），与旧版默认「全局」保持一致
                onClick = { editing = ImageTag(id = Uuid.random(), name = "", scopes = ImageTag.ALL_SCOPES) }
            ) {
                Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.gallery_tag_add))
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            contentPadding = innerPadding,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.gallery_tag_page_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            items(tags, key = { it.id.toString() }) { tag ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
                ) {
                    var menuExpanded by remember { mutableStateOf(false) }
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        headlineContent = { Text(tag.name) },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    galleryTagScopeName(tag.scopes),
                                    if (tag.sensitive) stringResource(R.string.gallery_tag_sensitive) else null,
                                    if (tag.builtin) stringResource(R.string.gallery_tag_builtin) else null,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(HugeIcons.MoreVertical, contentDescription = null)
                                }
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.gallery_tag_edit)) },
                                        onClick = {
                                            menuExpanded = false
                                            editing = tag
                                        },
                                    )
                                    // 内置标签不给删：删掉 NSFW 之后敏感图会直接裸奔进「全部」
                                    if (!tag.builtin) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.setting_files_page_delete_action)) },
                                            leadingIcon = {
                                                Icon(
                                                    HugeIcons.Delete01,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error,
                                                )
                                            },
                                            onClick = {
                                                menuExpanded = false
                                                pendingDelete = tag
                                            },
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GalleryTagEditDialog(
    tag: ImageTag,
    onConfirm: (ImageTag) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(tag.name) }
    var scopes by remember { mutableStateOf(tag.scopes) }
    var sensitive by remember { mutableStateOf(tag.sensitive) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (tag.name.isBlank()) R.string.gallery_tag_add else R.string.gallery_tag_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    // 内置标签名字写死：OCR 提示词和敏感图逻辑都按名字对齐，改了会对不上
                    enabled = !tag.builtin,
                    label = { Text(stringResource(R.string.gallery_tag_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.gallery_tag_scope),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TAG_SCOPES.forEach { candidate ->
                        FilterChip(
                            selected = candidate in scopes,
                            onClick = {
                                scopes = if (candidate in scopes) scopes - candidate else scopes + candidate
                            },
                            label = { Text(galleryTagFolderName(candidate)) },
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.gallery_tag_scope_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.gallery_tag_sensitive))
                        Text(
                            text = stringResource(R.string.gallery_tag_sensitive_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = sensitive, onCheckedChange = { sensitive = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onConfirm(tag.copy(name = name.trim(), scopes = scopes, sensitive = sensitive))
                }
            ) { Text(stringResource(R.string.setting_files_page_bulk_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.setting_files_page_cancel_action))
            }
        },
    )
}

@Composable
private fun galleryTagScopeName(scopes: Set<String>): String {
    if (scopes.isEmpty()) return stringResource(R.string.gallery_tag_scope_unused)
    // joinToString 的 lambda 不是 composable 上下文，先在循环里把名字解析好
    val names = ArrayList<String>(scopes.size)
    for (folder in scopes) {
        names += galleryTagFolderName(folder)
    }
    return names.joinToString(" · ")
}

@Composable
private fun galleryTagFolderName(folder: String): String = when (folder) {
    FileFolders.UPLOAD -> stringResource(R.string.setting_files_page_folder_upload)
    FileFolders.IMAGES -> stringResource(R.string.setting_files_page_folder_images)
    FileFolders.AVATARS -> stringResource(R.string.setting_files_page_folder_avatars)
    else -> folder
}
