package me.rerere.rikkahub.ui.pages.gallery

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Clean
import me.rerere.hugeicons.stroke.Cloud
import me.rerere.hugeicons.stroke.CloudDownload
import me.rerere.hugeicons.stroke.CloudUpload
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileLink
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Sorting01
import me.rerere.hugeicons.stroke.Text
import me.rerere.hugeicons.stroke.TextSelection
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.ImageTag
import me.rerere.rikkahub.data.sync.r2.R2MediaStore
import me.rerere.rikkahub.data.sync.r2.R2Ref
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.DateHeader
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.MEDIA_GRID_COLUMNS_OPTIONS
import me.rerere.rikkahub.ui.hooks.rememberMediaGridColumns
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.writeClipboardText
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Modifier.blur 需要 RenderEffect(API 31+), 低版本会静默失效。 */
private val BLUR_SUPPORTED = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * 相册页。
 *
 * 与「文件管理」(SettingFilesPage) 的分工:
 * - 文件管理: 全部类型文件, 面向存储清理。
 * - 相册(本页): 只收图片, 面向浏览与整理 —— 标签、筛选、重命名、批量 OCR。
 *
 * 相册不是"文件管理的图片子集视图", 否则不如直接去文件管理搜。
 * 它多出来的能力集中在三处: 漏斗筛选(按标签)、长按信息面板(改名/打标签)、批量 OCR。
 */
@Composable
fun GalleryPage(
    vm: GalleryVM = koinViewModel(),
    filesManager: FilesManager = koinInject(),
    r2MediaStore: R2MediaStore = koinInject(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridState = rememberLazyStaggeredGridState()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val navController = LocalNavController.current
    val context = LocalContext.current

    val deletedToast = stringResource(R.string.setting_files_page_deleted_toast)
    val deleteFailedToast = stringResource(R.string.setting_files_page_delete_failed_toast)
    val selectedTemplate = stringResource(R.string.setting_files_page_selected_count)
    val columnsTemplate = stringResource(R.string.setting_files_page_columns_per_row)
    val bulkResultTemplate = stringResource(R.string.setting_files_page_bulk_result)
    val renamedToast = stringResource(R.string.gallery_page_renamed_toast)

    val allImages by vm.allImages.collectAsState()
    val tagMap by vm.tagMap.collectAsState()
    val extraFolderMap by vm.extraFolderMap.collectAsState()
    val settings by vm.settings.collectAsState()
    // 批量 OCR 进度在全局单例里：离开相册页任务继续在后台跑，重进可恢复显示
    val batchOcr by OcrBatchState.progress.collectAsState()

    var selectedFolder by remember { mutableStateOf(GALLERY_FOLDER_ALL) }
    var selectedTagIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showFilterRow by remember { mutableStateOf(false) }
    var pendingCloudDelete by remember { mutableStateOf<ManagedFileEntity?>(null) }
    var refreshTick by remember { mutableStateOf(0) }

    var gridColumns by rememberMediaGridColumns()
    var showColumnsMenu by remember { mutableStateOf(false) }
    var showBulkMenu by remember { mutableStateOf(false) }

    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // NSFW 睁眼/闭眼: 故意用 remember 而非 rememberSaveable ——
    // 每次重进页面都回到闭眼, 这是需求明确要求的。
    var nsfwRevealed by remember { mutableStateOf(false) }
    var unblurredIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedFileIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingBulkAction by remember { mutableStateOf<GalleryBulkAction?>(null) }
    var bulkRunning by remember { mutableStateOf(false) }
    var previewImages by remember { mutableStateOf<List<String>>(emptyList()) }

    // 长按打开的信息面板 / 重命名对话框
    var infoSheetTarget by remember { mutableStateOf<ManagedFileEntity?>(null) }
    var renameTarget by remember { mutableStateOf<ManagedFileEntity?>(null) }

    val sensitiveTagIds = remember(settings.imageTags) { vm.sensitiveTagIds() }
    val availableTags = remember(settings.imageTags, selectedFolder) { vm.tagsForFolder(selectedFolder) }

    fun ManagedFileEntity.tagIds(): Set<String> = tagMap[id] ?: emptySet()
    fun ManagedFileEntity.isSensitive(): Boolean = tagIds().any { it in sensitiveTagIds }
    fun ManagedFileEntity.isBlurred(): Boolean = isSensitive() && !nsfwRevealed && id !in unblurredIds

    // 分类归属 = 物理 folder 或附加分类。
    // 「全部」额外隐去敏感图 —— 睁眼也不会回来, 只有进它自己的分类才看得到。
    val folderFiltered = remember(allImages, selectedFolder, extraFolderMap, sensitiveTagIds, tagMap) {
        if (selectedFolder == GALLERY_FOLDER_ALL) {
            allImages.filterNot { it.tagIds().any { tag -> tag in sensitiveTagIds } }
        } else {
            allImages.filter { file ->
                file.folder == selectedFolder || extraFolderMap[file.id]?.contains(selectedFolder) == true
            }
        }
    }
    val tagFiltered = remember(folderFiltered, selectedTagIds, tagMap) {
        if (selectedTagIds.isEmpty()) folderFiltered
        // AND 语义: 选中多个标签时要求全部命中, 与搜索的分词语义保持一致
        else folderFiltered.filter { file -> selectedTagIds.all { it in file.tagIds() } }
    }
    val visibleFiles = remember(tagFiltered, searchQuery, tagMap, settings.imageTags) {
        tagFiltered.filterByGalleryQuery(searchQuery, tagMap, settings.imageTags)
    }
    // visibleFiles 已按 createdAt 倒序, groupBy 保留遍历顺序(LinkedHashMap),
    // 所以分组天然就是日期倒序, 无需再排序。
    val grouped = remember(visibleFiles) {
        visibleFiles.groupBy { entity ->
            Instant.ofEpochMilli(entity.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
        }
    }
    val selectedCount = selectedFileIds.size

    fun exitSelection() {
        selectionMode = false
        selectedFileIds = emptySet()
        showBulkMenu = false
    }

    LaunchedEffect(selectedFolder) {
        exitSelection()
        unblurredIds = emptySet()
        // 标签有作用域, 切分类后旧的专属标签可能已经不在候选里, 留着会筛出空结果
        selectedTagIds = emptySet()
    }

    LaunchedEffect(visibleFiles) {
        if (selectionMode) {
            selectedFileIds = selectedFileIds.intersect(visibleFiles.mapTo(mutableSetOf()) { it.id })
        }
    }

    LaunchedEffect(refreshTick) { vm.syncFolders() }

    // 批量 OCR 跑完后弹一次结果, 然后复位进度条
    LaunchedEffect(batchOcr.running) {
        if (!batchOcr.running && batchOcr.total > 0) {
            toaster.show(bulkResultTemplate.format(batchOcr.done - batchOcr.failed, batchOcr.failed))
            OcrBatchState.reset()
        }
    }

    if (previewImages.isNotEmpty()) {
        ImagePreviewDialog(images = previewImages) {
            previewImages = emptyList()
        }
    }

    infoSheetTarget?.let { target ->
        GalleryInfoSheet(
            file = target,
            fileOnDisk = filesManager.getFile(target),
            tags = availableTags,
            attachedTagIds = tagMap[target.id] ?: emptySet(),
            onToggleTag = { tag ->
                vm.toggleTag(target.id, tag.id, tag.id.toString() in (tagMap[target.id] ?: emptySet()))
            },
            onRename = {
                renameTarget = target
                infoSheetTarget = null
            },
            onCloudChanged = { refreshTick++ },
            onDismiss = { infoSheetTarget = null },
        )
    }

    renameTarget?.let { target ->
        GalleryRenameDialog(
            initial = target.nameZh ?: target.displayName,
            onConfirm = { newName ->
                vm.rename(target.id, newName)
                renameTarget = null
                toaster.show(renamedToast)
            },
            onDismiss = { renameTarget = null },
        )
    }

    pendingCloudDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingCloudDelete = null },
            title = { Text(stringResource(R.string.setting_files_page_delete_cloud_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.setting_files_page_delete_cloud_confirmation,
                        target.galleryTitle(),
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            target.r2RefOrNull()?.let { ref -> r2MediaStore.delete(ref) }
                            val ok = filesManager.delete(target.id, deleteFromDisk = true)
                            toaster.show(if (ok) deletedToast else deleteFailedToast)
                            pendingCloudDelete = null
                        }
                    }
                ) { Text(stringResource(R.string.setting_files_page_delete_cloud_action)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingCloudDelete = null }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            }
        )
    }

    pendingBulkAction?.let { action ->
        val count = selectedCount
        AlertDialog(
            onDismissRequest = { if (!bulkRunning) pendingBulkAction = null },
            title = { Text(stringResource(action.titleRes)) },
            text = { Text(stringResource(action.messageRes, count)) },
            confirmButton = {
                TextButton(
                    enabled = !bulkRunning,
                    onClick = {
                        val targets = visibleFiles.filter { it.id in selectedFileIds }
                        // OCR 是长任务, 交给 VM 在 viewModelScope 里跑 ——
                        // 挂在这里会随对话框消失被取消。
                        if (action == GalleryBulkAction.OCR) {
                            vm.batchOcr(targets)
                            pendingBulkAction = null
                            exitSelection()
                            return@TextButton
                        }
                        bulkRunning = true
                        scope.launch {
                            var ok = 0
                            var failed = 0
                            when (action) {
                                GalleryBulkAction.OCR -> Unit

                                GalleryBulkAction.DELETE_LOCAL -> targets.forEach { file ->
                                    if (filesManager.deleteLocalCache(file.id)) ok++ else failed++
                                }

                                GalleryBulkAction.DELETE_ALL -> targets.forEach { file ->
                                    file.r2RefOrNull()?.let { ref -> r2MediaStore.delete(ref) }
                                    if (filesManager.delete(file.id, deleteFromDisk = true)) ok++ else failed++
                                }

                                GalleryBulkAction.UPLOAD -> targets.forEach { file ->
                                    val local = filesManager.getFile(file)
                                    if (!local.isFile || file.hasCloudCopy) {
                                        failed++
                                        return@forEach
                                    }
                                    val ref = r2MediaStore
                                        .upload(local.readBytes(), file.mimeType, R2MediaStore.PREFIX_CHAT_UPLOADS)
                                        .getOrNull()
                                    if (ref != null) {
                                        filesManager.setCloudCopy(file.id, ref.key, ref.acctId)
                                        ok++
                                    } else failed++
                                }

                                GalleryBulkAction.DOWNLOAD -> targets.forEach { file ->
                                    val ref = file.r2RefOrNull()
                                    if (ref == null || filesManager.getFile(file).isFile) {
                                        failed++
                                        return@forEach
                                    }
                                    val bytes = r2MediaStore.downloadBytes(ref).getOrNull()
                                    if (bytes != null && filesManager.restoreLocalCache(file.id, bytes)) ok++ else failed++
                                }

                                GalleryBulkAction.SAVE -> targets.forEach { file ->
                                    var local = filesManager.getFile(file)
                                    if (!local.isFile) {
                                        val ref = file.r2RefOrNull()
                                        val bytes = ref?.let { r2MediaStore.downloadBytes(it).getOrNull() }
                                        if (bytes != null) {
                                            filesManager.restoreLocalCache(file.id, bytes)
                                            local = filesManager.getFile(file)
                                        }
                                    }
                                    if (local.isFile &&
                                        context.saveImageToRikkaHubDownloads(local, file.exportFileName(), file.mimeType)
                                    ) ok++ else failed++
                                }
                            }
                            refreshTick++
                            bulkRunning = false
                            pendingBulkAction = null
                            exitSelection()
                            toaster.show(bulkResultTemplate.format(ok, failed))
                        }
                    }
                ) {
                    Text(
                        stringResource(
                            if (bulkRunning) R.string.setting_files_page_bulk_running else action.confirmRes
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(enabled = !bulkRunning, onClick = { pendingBulkAction = null }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(
                        if (selectionMode) selectedTemplate.format(selectedCount)
                        else stringResource(R.string.gallery_page_title)
                    )
                },
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = { exitSelection() }) {
                            Icon(
                                HugeIcons.Cancel01,
                                contentDescription = stringResource(R.string.setting_files_page_cancel_action),
                            )
                        }
                    } else {
                        BackButton()
                    }
                },
                actions = {
                    if (selectionMode) {
                        val allSelected = selectedCount > 0 && selectedCount == visibleFiles.size
                        IconButton(
                            onClick = {
                                selectedFileIds = if (allSelected) {
                                    emptySet()
                                } else {
                                    visibleFiles.mapTo(mutableSetOf()) { it.id }
                                }
                            }
                        ) {
                            Icon(
                                HugeIcons.Tick02,
                                contentDescription = stringResource(R.string.setting_files_page_select_all),
                                tint = if (allSelected) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            )
                        }
                        IconButton(
                            enabled = selectedCount > 0 && !batchOcr.running,
                            onClick = { pendingBulkAction = GalleryBulkAction.OCR },
                        ) {
                            Icon(
                                HugeIcons.Text,
                                contentDescription = stringResource(R.string.gallery_page_bulk_ocr_title),
                            )
                        }
                        IconButton(
                            enabled = selectedCount > 0,
                            onClick = { pendingBulkAction = GalleryBulkAction.DELETE_ALL },
                        ) {
                            Icon(
                                HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.setting_files_page_bulk_delete_all_title),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                        Box {
                            IconButton(
                                enabled = selectedCount > 0,
                                onClick = { showBulkMenu = true },
                            ) {
                                Icon(
                                    HugeIcons.MoreVertical,
                                    contentDescription = stringResource(R.string.setting_files_page_bulk_select),
                                )
                            }
                            DropdownMenu(
                                expanded = showBulkMenu,
                                onDismissRequest = { showBulkMenu = false },
                            ) {
                                listOf(
                                    GalleryBulkAction.SAVE to HugeIcons.Download01,
                                    GalleryBulkAction.UPLOAD to HugeIcons.CloudUpload,
                                    GalleryBulkAction.DOWNLOAD to HugeIcons.CloudDownload,
                                    GalleryBulkAction.DELETE_LOCAL to HugeIcons.Clean,
                                ).forEach { (action, icon) ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(action.titleRes)) },
                                        leadingIcon = { Icon(icon, contentDescription = null) },
                                        onClick = {
                                            showBulkMenu = false
                                            pendingBulkAction = action
                                        }
                                    )
                                }
                                if (availableTags.isNotEmpty()) {
                                    HorizontalDivider()
                                    Text(
                                        text = stringResource(R.string.gallery_page_bulk_add_tag),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                    availableTags.forEach { tag ->
                                        DropdownMenuItem(
                                            text = { Text(tag.name) },
                                            onClick = {
                                                vm.addTagToAll(selectedFileIds, tag.id)
                                                showBulkMenu = false
                                                exitSelection()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // 漏斗: 只在该分类下确实有可用标签时才给, 否则是个点了没反应的按钮
                        if (availableTags.isNotEmpty()) {
                            IconButton(onClick = { showFilterRow = !showFilterRow }) {
                                Icon(
                                    imageVector = HugeIcons.Sorting01,
                                    contentDescription = stringResource(R.string.gallery_page_filter),
                                    tint = if (selectedTagIds.isNotEmpty()) {
                                        MaterialTheme.colorScheme.primary
                                    } else LocalContentColor.current,
                                )
                            }
                        }
                        IconButton(onClick = { nsfwRevealed = !nsfwRevealed }) {
                            Icon(
                                imageVector = if (nsfwRevealed) HugeIcons.Eye else HugeIcons.ViewOff,
                                contentDescription = stringResource(R.string.gallery_page_nsfw_toggle),
                                tint = if (nsfwRevealed) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            )
                        }
                        IconButton(onClick = {
                            searchActive = !searchActive
                            if (!searchActive) searchQuery = ""
                        }) {
                            Icon(
                                imageVector = if (searchActive) HugeIcons.Cancel01 else HugeIcons.Search01,
                                contentDescription = stringResource(R.string.gallery_page_search_hint),
                            )
                        }
                        Box {
                            IconButton(onClick = { showColumnsMenu = true }) {
                                Icon(
                                    imageVector = HugeIcons.ChartColumn,
                                    contentDescription = stringResource(R.string.setting_files_page_columns_title),
                                )
                            }
                            DropdownMenu(
                                expanded = showColumnsMenu,
                                onDismissRequest = { showColumnsMenu = false },
                            ) {
                                Text(
                                    text = stringResource(R.string.setting_files_page_columns_title),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                HorizontalDivider()
                                MEDIA_GRID_COLUMNS_OPTIONS.forEach { count ->
                                    DropdownMenuItem(
                                        text = { Text(columnsTemplate.format(count)) },
                                        trailingIcon = {
                                            if (count == gridColumns) {
                                                Icon(
                                                    HugeIcons.Tick02,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        },
                                        onClick = {
                                            gridColumns = count
                                            showColumnsMenu = false
                                        }
                                    )
                                }
                                HorizontalDivider()
                                Text(
                                    text = stringResource(R.string.setting_files_page_columns_device_local),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .widthIn(max = 240.dp)
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                        IconButton(
                            onClick = { selectionMode = true },
                            enabled = visibleFiles.isNotEmpty(),
                        ) {
                            Icon(
                                imageVector = HugeIcons.TextSelection,
                                contentDescription = stringResource(R.string.setting_files_page_bulk_select),
                            )
                        }
                        // OCR 设置入口：模型 / 思考强度 / 提示词 / 标签白名单
                        IconButton(onClick = { navController.navigate(Screen.GalleryOcrSettings) }) {
                            Icon(
                                imageVector = HugeIcons.Settings03,
                                contentDescription = stringResource(R.string.gallery_page_ocr_settings),
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                )
        ) {
            GalleryFolderRow(
                folders = GALLERY_FOLDERS,
                selectedFolder = selectedFolder,
                counts = remember(allImages, extraFolderMap, sensitiveTagIds, tagMap) {
                    GALLERY_FOLDERS.associateWith { folder ->
                        if (folder == GALLERY_FOLDER_ALL) {
                            allImages.count { file -> file.tagIds().none { it in sensitiveTagIds } }
                        } else {
                            allImages.count { file ->
                                file.folder == folder || extraFolderMap[file.id]?.contains(folder) == true
                            }
                        }
                    }
                },
                onFolderSelected = { selectedFolder = it },
            )

            if (showFilterRow && availableTags.isNotEmpty()) {
                GalleryTagFilterRow(
                    tags = availableTags,
                    selectedTagIds = selectedTagIds,
                    onToggle = { tag ->
                        val key = tag.id.toString()
                        selectedTagIds = if (key in selectedTagIds) selectedTagIds - key else selectedTagIds + key
                    },
                    onClear = { selectedTagIds = emptySet() },
                )
            }

            if (searchActive) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(HugeIcons.Cancel01, contentDescription = null)
                            }
                        }
                    },
                    placeholder = { Text(stringResource(R.string.gallery_page_search_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (batchOcr.running) {
                GalleryBatchOcrBar(progress = batchOcr)
            }

            if (visibleFiles.isEmpty()) {
                GalleryEmptyState(
                    searching = searchQuery.isNotBlank(),
                    filtering = selectedTagIds.isNotEmpty(),
                    // 「在整个分类中搜索」: 筛选后无结果时的逃生门, 一键清掉标签条件
                    onClearFilter = { selectedTagIds = emptySet() },
                )
            } else {
                val columns = gridColumns.coerceIn(1, 6)
                val compact = columns >= 3
                LazyVerticalStaggeredGrid(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = innerPadding.calculateBottomPadding() + 16.dp,
                    ),
                    verticalItemSpacing = 8.dp,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    state = gridState,
                    columns = StaggeredGridCells.Fixed(columns),
                ) {
                    grouped.forEach { (date, filesOfDay) ->
                        item(key = "header-$date", span = StaggeredGridItemSpan.FullLine) {
                            DateHeader(date = date, count = filesOfDay.size)
                        }
                        items(filesOfDay, key = { "image-${it.id}" }) { file ->
                            val fileOnDisk = filesManager.getFile(file)
                            GalleryImageItem(
                                file = file,
                                fileOnDisk = fileOnDisk,
                                compact = compact,
                                blurred = file.isBlurred(),
                                tagNames = remember(tagMap[file.id], settings.imageTags) {
                                    val attached = tagMap[file.id] ?: emptySet()
                                    ImageTag.withBuiltins(settings.imageTags)
                                        .filter { it.id.toString() in attached }
                                        .map { it.name }
                                },
                                selectionMode = selectionMode,
                                selected = file.id in selectedFileIds,
                                onToggleSelect = {
                                    selectedFileIds = if (file.id in selectedFileIds) {
                                        selectedFileIds - file.id
                                    } else {
                                        selectedFileIds + file.id
                                    }
                                },
                                onLongPress = {
                                    // 长按开信息面板(改名/打标签), 而不是直接进多选。
                                    // 多选有顶栏专门的入口, 长按留给"我想看看这张图是什么"更顺手。
                                    if (selectionMode) {
                                        selectedFileIds = selectedFileIds + file.id
                                    } else {
                                        infoSheetTarget = file
                                    }
                                },
                                onRevealOnce = { unblurredIds = unblurredIds + file.id },
                                onOpen = {
                                    val urls = visibleFiles
                                        .filterNot { it.isBlurred() }
                                        .mapNotNull { it.previewModel(filesManager) }
                                    val current = file.previewModel(filesManager)
                                    previewImages = if (current != null) {
                                        urls.startingAt(current)
                                    } else urls
                                },
                                onInfo = { infoSheetTarget = file },
                                onDelete = {
                                    scope.launch {
                                        val localExists = fileOnDisk.isFile
                                        when {
                                            localExists -> {
                                                if (filesManager.deleteLocalCache(file.id)) {
                                                    toaster.show(deletedToast)
                                                } else {
                                                    toaster.show(deleteFailedToast)
                                                }
                                            }

                                            file.hasCloudCopy -> pendingCloudDelete = file
                                            else -> toaster.show(deleteFailedToast)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryFolderRow(
    folders: List<String>,
    selectedFolder: String,
    counts: Map<String, Int>,
    onFolderSelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        folders.forEach { folder ->
            FilterChip(
                selected = selectedFolder == folder,
                onClick = { onFolderSelected(folder) },
                label = {
                    val count = counts[folder] ?: 0
                    Text(
                        if (count > 0) "${galleryFolderDisplayName(folder)} $count"
                        else galleryFolderDisplayName(folder)
                    )
                },
            )
        }
    }
}

@Composable
private fun GalleryTagFilterRow(
    tags: List<ImageTag>,
    selectedTagIds: Set<String>,
    onToggle: (ImageTag) -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectedTagIds.isNotEmpty()) {
            AssistChip(
                onClick = onClear,
                label = { Text(stringResource(R.string.gallery_page_filter_clear)) },
                leadingIcon = { Icon(HugeIcons.Cancel01, contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
        }
        tags.forEach { tag ->
            val selected = tag.id.toString() in selectedTagIds
            FilterChip(
                selected = selected,
                onClick = { onToggle(tag) },
                label = { Text(tag.name) },
                leadingIcon = if (selected) {
                    { Icon(HugeIcons.Tick02, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
                colors = if (tag.sensitive) {
                    FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

@Composable
private fun GalleryBatchOcrBar(progress: GalleryBatchOcrProgress) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.gallery_page_bulk_ocr_running),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = "${progress.done}/${progress.total}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = {
                if (progress.total == 0) 0f else progress.done.toFloat() / progress.total
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
    }
}

@Composable
private fun GalleryEmptyState(
    searching: Boolean,
    filtering: Boolean,
    onClearFilter: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(
                    when {
                        searching -> R.string.setting_files_page_search_no_result
                        filtering -> R.string.gallery_page_filter_no_result
                        else -> R.string.gallery_page_no_images
                    }
                )
            )
            if (filtering) {
                TextButton(onClick = onClearFilter) {
                    Text(stringResource(R.string.gallery_page_filter_search_whole_folder))
                }
            }
        }
    }
}

@Composable
private fun galleryFolderDisplayName(folder: String): String = when (folder) {
    GALLERY_FOLDER_ALL -> stringResource(R.string.gallery_page_folder_all)
    FileFolders.UPLOAD -> stringResource(R.string.setting_files_page_folder_upload)
    FileFolders.IMAGES -> stringResource(R.string.setting_files_page_folder_images)
    FileFolders.AVATARS -> stringResource(R.string.setting_files_page_folder_avatars)
    else -> folder
}

@Composable
private fun GalleryImageItem(
    file: ManagedFileEntity,
    fileOnDisk: File,
    compact: Boolean,
    blurred: Boolean,
    tagNames: List<String>,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onRevealOnce: () -> Unit,
    onOpen: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
) {
    val r2MediaStore: R2MediaStore = koinInject()
    val localExists = fileOnDisk.isFile || file.relativePath.isRemoteImageUrl()
    val cloudExists = file.hasCloudCopy
    val cloudUrl = file.r2RefOrNull()?.toString()
    var cloudDisplayUrl by remember(cloudUrl) { mutableStateOf(cloudUrl) }
    LaunchedEffect(cloudUrl) {
        cloudDisplayUrl = cloudUrl?.let { r2MediaStore.displayUrl(it) }
    }
    val unavailable = !localExists && !cloudExists

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selectionMode && selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                } else Modifier
            ),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (unavailable) {
                // 图片可能只存在于云端或已被清理, 本地缺失是正常状态, 不是错误。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .combinedClickable(
                            onClick = { if (selectionMode) onToggleSelect() },
                            onLongClick = onLongPress,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
                        modifier = Modifier.padding(if (compact) 6.dp else 16.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Image02,
                            contentDescription = null,
                            modifier = Modifier.size(if (compact) 28.dp else 40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!compact) {
                            Text(
                                text = stringResource(R.string.gallery_page_image_not_cached),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } else if (blurred && !BLUR_SUPPORTED) {
                // Modifier.blur 在 API < 31 上是静默空操作, 直接糊不住。
                // NSFW 是隐私开关, 不能靠一个可能失效的效果兜底 —— 低版本干脆不加载图片。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .combinedClickable(
                            onClick = { if (selectionMode) onToggleSelect() else onRevealOnce() },
                            onLongClick = onLongPress,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = HugeIcons.ViewOff,
                        contentDescription = stringResource(R.string.gallery_page_nsfw_hidden),
                        modifier = Modifier.size(if (compact) 28.dp else 40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                AsyncImage(
                    model = when {
                        file.relativePath.isRemoteImageUrl() -> file.relativePath
                        fileOnDisk.isFile -> fileOnDisk
                        cloudDisplayUrl != null -> cloudDisplayUrl
                        else -> fileOnDisk
                    },
                    contentDescription = file.galleryTitle(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .then(if (blurred) Modifier.blur(24.dp) else Modifier)
                        .combinedClickable(
                            onClick = {
                                when {
                                    selectionMode -> onToggleSelect()
                                    blurred -> onRevealOnce()
                                    else -> onOpen()
                                }
                            },
                            onLongClick = onLongPress,
                        ),
                    contentScale = ContentScale.Crop,
                )
            }

            // 云/本地/外链状态角标: 相册同样需要它 —— 没有它就分不清
            // "这张图只在云上" 和 "这张图本地有", 清缓存时全靠猜。
            if (!selectionMode) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val hasExternalUrl = !file.externalUrl.isNullOrBlank() || file.relativePath.isRemoteImageUrl()
                    if (cloudExists) GalleryStatusBadge(HugeIcons.Cloud, MaterialTheme.colorScheme.primary)
                    if (hasExternalUrl) GalleryStatusBadge(HugeIcons.FileLink, MaterialTheme.colorScheme.tertiary)
                    if (localExists && !file.relativePath.isRemoteImageUrl()) {
                        GalleryStatusBadge(HugeIcons.File02, MaterialTheme.colorScheme.secondary)
                    }
                }
            }

            if (selectionMode) {
                GallerySelectionCheckbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelect() },
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            } else {
                Row(modifier = Modifier.align(Alignment.TopEnd)) {
                    // 单项操作入口。小格子下只留信息(信息面板里能删), 避免图标盖住整张图。
                    GalleryIconButton(
                        icon = HugeIcons.MoreVertical,
                        contentDescription = stringResource(R.string.gallery_page_info),
                        onClick = onInfo,
                    )
                    if (!compact) {
                        GalleryIconButton(
                            icon = HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.setting_files_page_delete_content_description),
                            onClick = onDelete,
                        )
                    }
                }
            }

            if (!selectionMode && blurred) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(4.dp),
                    shape = RoundedCornerShape(percent = 50),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                ) {
                    Icon(
                        imageVector = HugeIcons.ViewOff,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(20.dp),
                    )
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                text = file.galleryTitle(),
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!compact) {
                Text(
                    text = listOfNotNull(
                        file.sizeBytes.fileSizeToString(),
                        when {
                            file.relativePath.isRemoteImageUrl() ->
                                stringResource(R.string.gallery_page_status_external)
                            cloudExists && localExists ->
                                stringResource(R.string.setting_files_page_status_local_cloud)
                            cloudExists -> stringResource(R.string.setting_files_page_status_cloud_only)
                            localExists -> stringResource(R.string.setting_files_page_status_local_only)
                            else -> null
                        }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (tagNames.isNotEmpty()) {
                    Text(
                        text = tagNames.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun GalleryIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.padding(4.dp),
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun GalleryStatusBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier
                .padding(4.dp)
                .size(16.dp),
            tint = tint,
        )
    }
}

/**
 * 图片信息面板。相册相对文件管理最主要的增量之一:
 * 在这里看得到 OCR 描述、改名、打标签, 不用回到对话里去翻。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryInfoSheet(
    file: ManagedFileEntity,
    fileOnDisk: File,
    tags: List<ImageTag>,
    attachedTagIds: Set<String>,
    onToggleTag: (ImageTag) -> Unit,
    onRename: () -> Unit,
    onCloudChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showTagPicker by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = file.galleryTitle(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onRename) {
                    Icon(HugeIcons.Edit02, contentDescription = stringResource(R.string.gallery_page_rename))
                }
            }

            GalleryInfoRow(
                label = stringResource(R.string.gallery_page_info_created),
                value = remember(file.createdAt) {
                    Instant.ofEpochMilli(file.createdAt)
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                },
            )
            GalleryInfoRow(
                label = stringResource(R.string.gallery_page_info_size),
                value = "${file.sizeBytes.fileSizeToString()} · ${file.mimeType}",
            )
            GalleryInfoRow(
                label = stringResource(R.string.gallery_page_info_location),
                value = listOfNotNull(
                    galleryFolderDisplayName(file.folder),
                    if (fileOnDisk.isFile) stringResource(R.string.setting_files_page_status_local_only) else null,
                    if (file.hasCloudCopy) stringResource(R.string.setting_files_page_status_cloud_only) else null,
                ).joinToString(" · "),
            )
            GalleryFileActionsRow(
                file = file,
                fileOnDisk = fileOnDisk,
                onCloudChanged = onCloudChanged,
                onDeleted = {
                    onCloudChanged()
                    onDismiss()
                },
            )
            file.nameEn?.takeIf { it.isNotBlank() }?.let {
                GalleryInfoRow(label = stringResource(R.string.gallery_page_info_name_en), value = it)
            }

            if (tags.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.gallery_page_info_tags),
                    style = MaterialTheme.typography.labelLarge,
                )
                val attachedTags = tags.filter { it.id.toString() in attachedTagIds }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 只展示已归属的标签，一眼看清图片归属；点 chip 直接移除
                    if (attachedTags.isEmpty()) {
                        Text(
                            text = stringResource(R.string.gallery_page_info_no_tags),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    attachedTags.forEach { tag ->
                        FilterChip(
                            selected = true,
                            onClick = { onToggleTag(tag) },
                            label = { Text(tag.name) },
                            colors = if (tag.sensitive) {
                                FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            } else FilterChipDefaults.filterChipColors(),
                        )
                    }
                    // 「+」追加入口：候选标签收进弹层，不把整个白名单堆在这里
                    AssistChip(
                        onClick = { showTagPicker = true },
                        label = { Text(stringResource(R.string.gallery_page_info_add_tag)) },
                        leadingIcon = {
                            Icon(
                                HugeIcons.Add01,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }

            if (showTagPicker) {
                GalleryTagPickerSheet(
                    tags = tags,
                    attachedTagIds = attachedTagIds,
                    onToggleTag = onToggleTag,
                    onDismiss = { showTagPicker = false },
                )
            }

            val descriptionText = file.description?.takeIf { it.isNotBlank() }
                ?: file.ocrText?.takeIf { it.isNotBlank() }
            if (descriptionText != null) {
                Text(
                    text = stringResource(R.string.gallery_page_info_description),
                    style = MaterialTheme.typography.labelLarge,
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Text(
                        text = descriptionText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            file.prompt?.takeIf { it.isNotBlank() }?.let { prompt ->
                Text(
                    text = stringResource(R.string.gallery_page_info_prompt),
                    style = MaterialTheme.typography.labelLarge,
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

/**
 * 标签选择弹层：把全部候选标签列出来（FlowRow），点选即切换归属，
 * 与信息面板里的已归属 chips 实时联动。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun GalleryTagPickerSheet(
    tags: List<ImageTag>,
    attachedTagIds: Set<String>,
    onToggleTag: (ImageTag) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.gallery_page_info_add_tag),
                style = MaterialTheme.typography.titleMedium,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tags.forEach { tag ->
                    val attached = tag.id.toString() in attachedTagIds
                    FilterChip(
                        selected = attached,
                        onClick = { onToggleTag(tag) },
                        label = { Text(tag.name) },
                        colors = if (tag.sensitive) {
                            FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        } else FilterChipDefaults.filterChipColors(),
                    )
                }
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.setting_files_page_cancel_action))
            }
        }
    }
}

@Composable
private fun GalleryInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 64.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * 信息面板里的文件操作区，与「文件管理」的预览面板对齐：
 * 上传云端 / 下载本地 / 复制链接 / 保存到系统相册。
 * 按钮按当前状态动态出现，避免一堆永远点不亮的灰按钮。
 */
@Composable
private fun GalleryFileActionsRow(
    file: ManagedFileEntity,
    fileOnDisk: File,
    onCloudChanged: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val r2MediaStore: R2MediaStore = koinInject()
    val filesManager: FilesManager = koinInject()
    val hasLocal = fileOnDisk.isFile
    val hasCloud = file.hasCloudCopy
    if (!hasLocal && !hasCloud) return

    // stringResource 是 @Composable，不能进协程 lambda，先在组合阶段全部取出
    val actionsTitle = stringResource(R.string.gallery_page_file_actions)
    val uploadDesc = stringResource(R.string.setting_files_page_bulk_upload_title)
    val downloadDesc = stringResource(R.string.setting_files_page_bulk_download_title)
    val copyDesc = stringResource(R.string.gallery_page_copy_url)
    val saveDesc = stringResource(R.string.setting_files_page_bulk_save_title)
    val uploadedToast = stringResource(R.string.gallery_page_uploaded)
    val uploadFailedToast = stringResource(R.string.gallery_page_upload_failed)
    val downloadedToast = stringResource(R.string.gallery_page_downloaded)
    val downloadFailedToast = stringResource(R.string.gallery_page_download_failed)
    val copiedToast = stringResource(R.string.gallery_page_copied_url)
    val copyFailedToast = stringResource(R.string.gallery_page_copy_failed)
    val savedToast = stringResource(R.string.gallery_page_saved)
    val saveFailedToast = stringResource(R.string.gallery_page_save_failed)
    // 删除相关（二次确认）：
    val deleteDesc = stringResource(R.string.setting_files_page_delete_content_description)
    val deleteTitle = stringResource(R.string.gallery_page_delete_title)
    val deleteConfirmation = stringResource(R.string.gallery_page_delete_confirmation)
    val deleteAction = stringResource(R.string.setting_files_page_delete_action)
    val cancelAction = stringResource(R.string.setting_files_page_cancel_action)
    val deleteOkToast = stringResource(R.string.setting_files_page_deleted_toast)
    val deleteFailToast = stringResource(R.string.setting_files_page_delete_failed_toast)
    var confirmDelete by remember { mutableStateOf(false) }

    Text(
        text = actionsTitle,
        style = MaterialTheme.typography.labelLarge,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 本地有、云端无 → 上传
        if (hasLocal && !hasCloud) {
            IconButton(onClick = {
                scope.launch {
                    val ref = r2MediaStore
                        .upload(fileOnDisk.readBytes(), file.mimeType, R2MediaStore.PREFIX_CHAT_UPLOADS)
                        .getOrNull()
                    if (ref != null) {
                        filesManager.setCloudCopy(file.id, ref.key, ref.acctId)
                        toaster.show(uploadedToast)
                        onCloudChanged()
                    } else toaster.show(uploadFailedToast)
                }
            }) {
                Icon(
                    HugeIcons.CloudUpload,
                    contentDescription = uploadDesc,
                )
            }
        }
        // 云端有、本地无 → 下载回本地缓存
        if (hasCloud && !hasLocal) {
            IconButton(onClick = {
                scope.launch {
                    val ref = file.r2RefOrNull()
                    val bytes = ref?.let { r2MediaStore.downloadBytes(it).getOrNull() }
                    if (bytes != null && filesManager.restoreLocalCache(file.id, bytes)) {
                        toaster.show(downloadedToast)
                        onCloudChanged()
                    } else toaster.show(downloadFailedToast)
                }
            }) {
                Icon(
                    HugeIcons.CloudDownload,
                    contentDescription = downloadDesc,
                )
            }
        }
        // 云端有 → 复制预签名链接
        if (hasCloud) {
            IconButton(onClick = {
                scope.launch {
                    val ref = file.r2RefOrNull()
                    val url = ref?.let { r2MediaStore.presign(it).getOrNull() }
                    if (url != null) {
                        context.writeClipboardText(url)
                        toaster.show(copiedToast)
                    } else toaster.show(copyFailedToast)
                }
            }) {
                Icon(
                    HugeIcons.Copy01,
                    contentDescription = copyDesc,
                )
            }
        }
        // 本地或云端有 → 保存到系统相册（<name_zh>.<ext> 可读文件名）
        if (hasLocal || hasCloud) {
            IconButton(onClick = {
                scope.launch {
                    var local = fileOnDisk
                    if (!local.isFile) {
                        val ref = file.r2RefOrNull()
                        val bytes = ref?.let { r2MediaStore.downloadBytes(it).getOrNull() }
                        if (bytes != null) {
                            filesManager.restoreLocalCache(file.id, bytes)
                            local = filesManager.getFile(file)
                        }
                    }
                    if (local.isFile &&
                        context.saveImageToRikkaHubDownloads(local, file.exportFileName(), file.mimeType)
                    ) {
                        toaster.show(savedToast)
                        onCloudChanged()
                    } else toaster.show(saveFailedToast)
                }
            }) {
                Icon(
                    HugeIcons.Download01,
                    contentDescription = saveDesc,
                )
            }
        }
        // 删除：点按后先弹二次确认，确认后才彻底删除（本地缓存 + 云端对象 + 索引）
        if (hasLocal || hasCloud) {
            IconButton(onClick = { confirmDelete = true }) {
                Icon(
                    HugeIcons.Delete01,
                    contentDescription = deleteDesc,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(deleteTitle) },
            text = { Text(deleteConfirmation) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            file.r2RefOrNull()?.let { r2MediaStore.delete(it) }
                            val ok = filesManager.delete(file.id, deleteFromDisk = true)
                            if (ok) toaster.show(deleteOkToast) else toaster.show(deleteFailToast)
                            confirmDelete = false
                            onDeleted()
                        }
                    }
                ) { Text(deleteAction) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(cancelAction)
                }
            },
        )
    }
}

@Composable
private fun GalleryRenameDialog(
    initial: String,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gallery_page_rename)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.gallery_page_rename_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim().takeIf { it.isNotEmpty() }) }) {
                Text(stringResource(R.string.setting_files_page_bulk_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.setting_files_page_cancel_action))
            }
        },
    )
}

@Composable
private fun GallerySelectionCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(4.dp),
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 相册批量操作。与文件管理的 BulkAction 复用同一批文案资源。 */
private enum class GalleryBulkAction(
    val titleRes: Int,
    val messageRes: Int,
    val confirmRes: Int,
) {
    DELETE_LOCAL(
        R.string.setting_files_page_bulk_delete_local_title,
        R.string.setting_files_page_bulk_delete_local_message,
        R.string.setting_files_page_clean_action,
    ),
    DELETE_ALL(
        R.string.setting_files_page_bulk_delete_all_title,
        R.string.setting_files_page_bulk_delete_all_message,
        R.string.setting_files_page_delete_action,
    ),
    UPLOAD(
        R.string.setting_files_page_bulk_upload_title,
        R.string.setting_files_page_bulk_upload_message,
        R.string.setting_files_page_bulk_confirm,
    ),
    DOWNLOAD(
        R.string.setting_files_page_bulk_download_title,
        R.string.setting_files_page_bulk_download_message,
        R.string.setting_files_page_bulk_confirm,
    ),
    SAVE(
        R.string.setting_files_page_bulk_save_title,
        R.string.setting_files_page_bulk_save_message,
        R.string.setting_files_page_bulk_confirm,
    ),
    OCR(
        R.string.gallery_page_bulk_ocr_title,
        R.string.gallery_page_bulk_ocr_message,
        R.string.setting_files_page_bulk_confirm,
    ),
}

/**
 * 大图预览用的 model。远端 URL 直接用, 本地文件转 file:// uri。
 * 只在云端的图这里返回 null(预览器拿不到可用地址), 交给缩略图的"本地无缓存"占位处理。
 */
private fun ManagedFileEntity.previewModel(filesManager: FilesManager): String? = when {
    relativePath.isRemoteImageUrl() -> relativePath
    else -> filesManager.getFile(this).takeIf { it.isFile }?.toUri()?.toString()
}

/** 让预览从点中的那张开始, 后面的循环补到末尾。 */
private fun List<String>.startingAt(url: String): List<String> {
    val index = indexOf(url)
    return if (index <= 0) this else drop(index) + take(index)
}

/** 显示名优先级: nameZh > displayName */
private fun ManagedFileEntity.galleryTitle(): String =
    nameZh?.takeIf { it.isNotBlank() } ?: displayName

/**
 * 导出到系统相册时用的文件名。
 * 磁盘上的名字是 UUID, 直接导出会得到一堆认不出来的乱码文件,
 * 所以这里用中文名 + 原扩展名重建一个可读名字。
 */
private fun ManagedFileEntity.exportFileName(): String {
    val ext = displayName.substringAfterLast('.', "").ifBlank {
        when {
            mimeType.contains("png") -> "png"
            mimeType.contains("webp") -> "webp"
            else -> "jpg"
        }
    }
    val base = nameZh?.takeIf { it.isNotBlank() }
        ?: displayName.substringBeforeLast('.').takeIf { it.isNotBlank() }
        ?: id
    return "$base.$ext"
}

/**
 * 搜索: 空格分词 + AND 语义。
 * haystack 覆盖 中文名 / 英文名 / 原始文件名 / prompt / 描述 / OCR 文本 / 标签名。
 */
private fun List<ManagedFileEntity>.filterByGalleryQuery(
    query: String,
    tagMap: Map<String, Set<String>>,
    allTags: List<ImageTag>,
): List<ManagedFileEntity> {
    val tokens = query.trim().split(' ', '\t').filter { it.isNotBlank() }
    if (tokens.isEmpty()) return this
    val tagNameById = ImageTag.withBuiltins(allTags).associate { it.id.toString() to it.name }
    return filter { file ->
        val tagNames = (tagMap[file.id] ?: emptySet()).mapNotNull { tagNameById[it] }
        val haystack = (
            listOfNotNull(
                file.nameZh,
                file.nameEn,
                file.displayName,
                file.prompt,
                file.description,
                file.ocrText,
            ) + tagNames
            ).joinToString(" ")
        tokens.all { haystack.contains(it, ignoreCase = true) }
    }
}

private fun String.isRemoteImageUrl(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

private val ManagedFileEntity.hasCloudCopy: Boolean
    get() = !r2Key.isNullOrBlank() && !r2Acct.isNullOrBlank()

private fun ManagedFileEntity.r2RefOrNull(): R2Ref? =
    if (hasCloudCopy) R2Ref(r2Acct!!, r2Key!!) else null

private fun Context.saveImageToRikkaHubDownloads(
    file: File,
    displayName: String,
    mimeType: String,
): Boolean = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/rikkahub/image")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching false
        contentResolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { it.copyTo(output) }
        }
        true
    } else {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "rikkahub/image",
        ).apply { mkdirs() }
        file.copyTo(File(dir, displayName), overwrite = true)
        true
    }
}.getOrDefault(false)
