package me.rerere.rikkahub.ui.pages.gallery

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Clean
import me.rerere.hugeicons.stroke.CloudDownload
import me.rerere.hugeicons.stroke.CloudUpload
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.TextSelection
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.sync.r2.R2MediaStore
import me.rerere.rikkahub.data.sync.r2.R2Ref
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.MEDIA_GRID_COLUMNS_OPTIONS
import me.rerere.rikkahub.ui.hooks.rememberMediaGridColumns
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 相册页。
 *
 * 与「文件管理」(SettingFilesPage) 的分工:
 * - 文件管理: 全部类型文件, 面向存储清理, 后续演进为文件树。
 * - 相册(本页): 只收 image/*, 面向浏览与批量整理。
 *
 * 本期(S1)先复用文件管理已有的搜索 / 批量 / 列数能力, UI 不做大改。
 * Tag 过滤与 NSFW 的真实数据来源在 S2/S3 落地, 本页先把入口与骨架留好。
 */
private const val GALLERY_FOLDER_ALL = "__all__"

/** Modifier.blur 需要 RenderEffect(API 31+), 低版本会静默失效。 */
private val BLUR_SUPPORTED = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun GalleryPage(
    filesManager: FilesManager = koinInject(),
    r2MediaStore: R2MediaStore = koinInject(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridState = rememberLazyStaggeredGridState()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current

    // 「全部」置顶; llm_previews 是喂给模型的压缩图, 不入相册; tts_cache 是音频, 与相册无关。
    val folders = remember {
        listOf(GALLERY_FOLDER_ALL, FileFolders.UPLOAD, FileFolders.IMAGES, FileFolders.AVATARS)
    }

    val deletedToast = stringResource(R.string.setting_files_page_deleted_toast)
    val deleteFailedToast = stringResource(R.string.setting_files_page_delete_failed_toast)
    val selectedTemplate = stringResource(R.string.setting_files_page_selected_count)
    val columnsTemplate = stringResource(R.string.setting_files_page_columns_per_row)
    val bulkResultTemplate = stringResource(R.string.setting_files_page_bulk_result)

    var selectedFolder by remember { mutableStateOf(GALLERY_FOLDER_ALL) }
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
    // 单张临时解除模糊(点击生效, 不持久化)
    var unblurredIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedFileIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingBulkAction by remember { mutableStateOf<GalleryBulkAction?>(null) }
    var bulkRunning by remember { mutableStateOf(false) }
    var previewImages by remember { mutableStateOf<List<String>>(emptyList()) }

    val imageFlow: Flow<List<ManagedFileEntity>> = remember(selectedFolder, filesManager) {
        if (selectedFolder == GALLERY_FOLDER_ALL) {
            combine(
                filesManager.observe(FileFolders.UPLOAD),
                filesManager.observe(FileFolders.IMAGES),
                filesManager.observe(FileFolders.AVATARS),
            ) { upload, images, avatars -> upload + images + avatars }
        } else {
            filesManager.observe(selectedFolder)
        }
    }
    val rawFiles by imageFlow.collectAsState(initial = emptyList())

    // 相册只认图片。存量库里同一张图可能既有本地行又有云端行, 按 id 去重后按时间倒序。
    val images = remember(rawFiles) {
        rawFiles.asSequence()
            .filter { it.isGalleryImage() }
            .distinctBy { it.id }
            .sortedByDescending { it.createdAt }
            .toList()
    }
    val visibleFiles = remember(images, searchQuery) { images.filterByGalleryQuery(searchQuery) }
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
        // 切 folder 时收起已解除的模糊, 避免"看过一次就一直清晰"
        unblurredIds = emptySet()
    }

    LaunchedEffect(visibleFiles) {
        if (selectionMode) {
            selectedFileIds = selectedFileIds.intersect(visibleFiles.mapTo(mutableSetOf()) { it.id })
        }
    }

    LaunchedEffect(selectedFolder, refreshTick) {
        if (selectedFolder == GALLERY_FOLDER_ALL) {
            filesManager.syncFolder(FileFolders.UPLOAD)
            filesManager.syncFolder(FileFolders.IMAGES)
            filesManager.syncFolder(FileFolders.AVATARS)
        } else {
            filesManager.syncFolder(selectedFolder)
        }
    }

    if (previewImages.isNotEmpty()) {
        ImagePreviewDialog(images = previewImages) {
            previewImages = emptyList()
        }
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
                        bulkRunning = true
                        scope.launch {
                            val targets = visibleFiles.filter { it.id in selectedFileIds }
                            var ok = 0
                            var failed = 0
                            when (action) {
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
                                        context.saveImageToRikkaHubDownloads(local, file.galleryTitle(), file.mimeType)
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
                            }
                        }
                    } else {
                        // NSFW 睁眼/闭眼
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
                folders = folders,
                selectedFolder = selectedFolder,
                onFolderSelected = { selectedFolder = it },
            )

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

            if (visibleFiles.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(
                            if (searchQuery.isNotBlank()) R.string.setting_files_page_search_no_result
                            else R.string.gallery_page_no_images
                        )
                    )
                }
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
                            GalleryDateHeader(date = date, count = filesOfDay.size)
                        }
                        items(filesOfDay, key = { "image-${it.id}" }) { file ->
                            val fileOnDisk = filesManager.getFile(file)
                            // NSFW 判定的数据源在 S3 接入; 现在恒为 false, 保证行为不变。
                            val isNsfw = file.isNsfw()
                            GalleryImageItem(
                                file = file,
                                fileOnDisk = fileOnDisk,
                                compact = compact,
                                blurred = isNsfw && !nsfwRevealed && file.id !in unblurredIds,
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
                                    selectionMode = true
                                    selectedFileIds = selectedFileIds + file.id
                                },
                                onRevealOnce = { unblurredIds = unblurredIds + file.id },
                                onOpen = {
                                    // 从当前图开始看, 并且跳过仍处于模糊态的敏感图
                                    val urls = visibleFiles
                                        .filterNot { candidate ->
                                            candidate.isNsfw() &&
                                                !nsfwRevealed &&
                                                candidate.id !in unblurredIds
                                        }
                                        .mapNotNull { it.previewModel(filesManager) }
                                    val current = file.previewModel(filesManager)
                                    previewImages = if (current != null) {
                                        urls.startingAt(current)
                                    } else urls
                                },
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
                label = { Text(galleryFolderDisplayName(folder)) },
            )
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
private fun GalleryDateHeader(date: LocalDate, count: Int) {
    val today = remember { LocalDate.now() }
    val label = when (date) {
        today -> stringResource(R.string.gallery_page_date_today)
        today.minusDays(1) -> stringResource(R.string.gallery_page_date_yesterday)
        else -> if (date.year == today.year) {
            stringResource(R.string.gallery_page_date_month_day, date.monthValue, date.dayOfMonth)
        } else {
            stringResource(R.string.gallery_page_date_full, date.year, date.monthValue, date.dayOfMonth)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.titleSmall)
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GalleryImageItem(
    file: ManagedFileEntity,
    fileOnDisk: File,
    compact: Boolean,
    blurred: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onRevealOnce: () -> Unit,
    onOpen: () -> Unit,
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

            if (selectionMode) {
                GallerySelectionCheckbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelect() },
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            } else if (blurred) {
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

        if (!compact) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(
                    text = file.galleryTitle(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
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

/**
 * 相册收录判定: 只要 image/*, 并排除 llm 预览图(喂模型用的压缩件)。
 */private fun ManagedFileEntity.isGalleryImage(): Boolean {
    if (!mimeType.startsWith("image/")) return false
    if (relativePath.endsWith("_llm_preview.jpg", ignoreCase = true)) return false
    if (folder == FileFolders.LLM_PREVIEWS) return false
    return true
}

/**
 * 显示名优先级: nameZh > displayName。
 * S2 会给 managed_files 加 name_zh 列, 届时这里替换为真实字段。
 */
private fun ManagedFileEntity.galleryTitle(): String = displayName

/**
 * NSFW 判定。真实数据源(asset_label_ref 的 tag)在 S3 接入,
 * 现在恒为 false, 保证本期上线后行为与文件管理一致。
 */
private fun ManagedFileEntity.isNsfw(): Boolean = false

/**
 * 搜索: 与文件管理保持同一套 AND 语义(空格分词, 全部命中)。
 * S4 会把 ocrText / nameZh / nameEn 加入 haystack。
 */
private fun List<ManagedFileEntity>.filterByGalleryQuery(query: String): List<ManagedFileEntity> {
    val tokens = query.trim().split(' ', '\t').filter { it.isNotBlank() }
    if (tokens.isEmpty()) return this
    return filter { file ->
        val haystack = listOfNotNull(
            file.displayName,
            file.prompt,
            file.description,
            file.ocrText,
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
