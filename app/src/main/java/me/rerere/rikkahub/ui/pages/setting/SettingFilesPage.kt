package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import android.content.ContentValues
import android.media.MediaPlayer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.ImageDownload
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.Video01
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.ShrinkDot
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Cloud
import me.rerere.hugeicons.stroke.Clean
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.CloudDownload
import me.rerere.hugeicons.stroke.CloudUpload
import me.rerere.hugeicons.stroke.FileLink
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.hugeicons.stroke.TextSelection
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Download01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.sync.r2.R2MediaStore
import me.rerere.rikkahub.data.sync.r2.R2Ref
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.DateHeader
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.rememberMediaGridColumns
import me.rerere.rikkahub.ui.hooks.MEDIA_GRID_COLUMNS_OPTIONS
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.writeClipboardText
import org.koin.compose.koinInject
import java.io.File
import java.time.Instant
import java.time.ZoneId

@Composable
fun SettingFilesPage(
    filesManager: FilesManager = koinInject(),
    genMediaRepository: GenMediaRepository = koinInject(),
    r2MediaStore: R2MediaStore = koinInject(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridState = rememberLazyStaggeredGridState()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val folders = remember { listOf(FileFolders.UPLOAD, FileFolders.IMAGES, FileFolders.LLM_PREVIEWS, FileFolders.AVATARS, FileFolders.TTS_CACHE) }

    // 预先获取字符串资源
    val deletedToast = stringResource(R.string.setting_files_page_deleted_toast)
    val deleteFailedToast = stringResource(R.string.setting_files_page_delete_failed_toast)
    val cleanedToast = stringResource(R.string.setting_files_page_cleaned_toast)
    val cleanFailedToast = stringResource(R.string.setting_files_page_clean_failed_toast)
    val selectedTemplate = stringResource(R.string.setting_files_page_selected_count)
    val columnsTemplate = stringResource(R.string.setting_files_page_columns_per_row)
    val bulkResultTemplate = stringResource(R.string.setting_files_page_bulk_result)

    var selectedFolder by remember { mutableStateOf(FileFolders.UPLOAD) }
    var pendingCloudDelete by remember { mutableStateOf<ManagedFileEntity?>(null) }
    var pendingCloudActions by remember { mutableStateOf<ManagedFileEntity?>(null) }
    var pendingRemoteDelete by remember { mutableStateOf<GenMediaEntity?>(null) }
    var remoteImageUrls by remember { mutableStateOf<List<GenMediaEntity>>(emptyList()) }
    var showCleanDialog by remember { mutableStateOf(false) }
    var previewImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var managedImagePreview by remember { mutableStateOf<ManagedFileEntity?>(null) }
    var audioPreview by remember { mutableStateOf<ManagedFileEntity?>(null) }
    var refreshTick by remember { mutableStateOf(0) }

    // 每行列数：device-local（SharedPreferences，不参与 D1 同步）
    var gridColumns by rememberMediaGridColumns()
    var showColumnsMenu by remember { mutableStateOf(false) }
    var showBulkMenu by remember { mutableStateOf(false) }

    // 搜索
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // 批量选择
    var selectionMode by remember { mutableStateOf(false) }
    var selectedFileIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedRemoteIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var pendingBulkAction by remember { mutableStateOf<BulkAction?>(null) }
    var bulkRunning by remember { mutableStateOf(false) }

    val files by filesManager.observe(selectedFolder).collectAsState(initial = emptyList())
    val displayedRemoteImageUrls = remember(files, remoteImageUrls) {
        remoteImageUrls.filterNot { image ->
            files.any { file -> image.matchesManagedFile(file) }
        }
    }
    val visibleFiles = remember(files, searchQuery) { files.filterByQuery(searchQuery) }
    val visibleRemoteImages = remember(displayedRemoteImageUrls, searchQuery) {
        displayedRemoteImageUrls.filterRemoteByQuery(searchQuery)
    }
    // 按创建日期分组：先时间倒序再 groupBy（LinkedHashMap 保留顺序 → 日期天然倒序），
    // 与相册的时间分组保持一致。
    val groupedFiles = remember(visibleFiles) {
        visibleFiles.sortedByDescending { it.createdAt }
            .groupBy { Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault()).toLocalDate() }
    }
    val groupedRemoteImages = remember(visibleRemoteImages) {
        visibleRemoteImages.sortedByDescending { it.createAt }
            .groupBy { Instant.ofEpochMilli(it.createAt).atZone(ZoneId.systemDefault()).toLocalDate() }
    }
    val selectedCount = selectedFileIds.size + selectedRemoteIds.size

    fun exitSelection() {
        selectionMode = false
        selectedFileIds = emptySet()
        selectedRemoteIds = emptySet()
        showBulkMenu = false
    }

    LaunchedEffect(selectedFolder) {
        exitSelection()
    }

    // 列表变化后剔除已消失的选中项
    LaunchedEffect(visibleFiles, visibleRemoteImages) {
        if (selectionMode) {
            val fileIds = visibleFiles.mapTo(mutableSetOf()) { it.id }
            val remoteIds = visibleRemoteImages.mapTo(mutableSetOf()) { it.id }
            selectedFileIds = selectedFileIds.intersect(fileIds)
            selectedRemoteIds = selectedRemoteIds.intersect(remoteIds)
        }
    }

    LaunchedEffect(selectedFolder, refreshTick) {
        filesManager.syncFolder(selectedFolder)
        remoteImageUrls = if (selectedFolder == FileFolders.IMAGES) {
            genMediaRepository.getAllMediaList()
                .filter { (it.path.isRemoteImageUrl() || it.path.startsWith("r2://")) && !it.path.endsWith("_llm_preview.jpg") }
        } else {
            emptyList()
        }
    }

    if (previewImages.isNotEmpty()) {
        ImagePreviewDialog(images = previewImages) {
            previewImages = emptyList()
        }
    }

    audioPreview?.let { audio ->
        AudioPreviewDialog(
            file = audio,
            fileOnDisk = filesManager.getFile(audio),
            onDismiss = { audioPreview = null },
        )
    }

    managedImagePreview?.let { image ->
        ManagedImagePreviewDialog(
            file = image,
            filesManager = filesManager,
            r2MediaStore = r2MediaStore,
            onChanged = { refreshTick++ },
            onDismiss = { managedImagePreview = null },
        )
    }

    pendingCloudDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingCloudDelete = null },
            title = { Text(stringResource(R.string.setting_files_page_delete_cloud_title)) },
            text = { Text(stringResource(R.string.setting_files_page_delete_cloud_confirmation, target.displayName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            target.r2RefOrNull()?.let { ref -> r2MediaStore.delete(ref) }
                            val ok = filesManager.delete(target.id, deleteFromDisk = true)
                            if (ok) {
                                if (selectedFolder == FileFolders.IMAGES) {
                                    genMediaRepository.deleteMediaByPath(target.relativePath)
                                }
                                toaster.show(deletedToast)
                            } else {
                                toaster.show(deleteFailedToast)
                            }
                            pendingCloudDelete = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.setting_files_page_delete_cloud_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCloudDelete = null }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            }
        )
    }

    pendingCloudActions?.let { target ->
        val ref = target.r2RefOrNull()
        val local = filesManager.getFile(target)
        AlertDialog(
            onDismissRequest = { pendingCloudActions = null },
            title = { Text(target.displayName) },
            text = { Text("文件操作") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(
                        enabled = local.isFile && ref == null,
                        onClick = {
                            scope.launch {
                                val newRef = r2MediaStore.upload(local.readBytes(), target.mimeType, R2MediaStore.PREFIX_CHAT_UPLOADS).getOrNull()
                                if (newRef != null) {
                                    filesManager.setCloudCopy(target.id, newRef.key, newRef.acctId)
                                    toaster.show("已上传云端")
                                    refreshTick++
                                } else toaster.show("上传失败")
                                pendingCloudActions = null
                            }
                        }
                    ) { Text("上传") }
                    TextButton(onClick = { pendingCloudDelete = target; pendingCloudActions = null }) { Text("删除") }
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(
                        enabled = ref != null,
                        onClick = {
                            val cloudRef = ref ?: return@TextButton
                            scope.launch {
                                val url = r2MediaStore.presign(cloudRef).getOrNull()
                                if (url != null) {
                                    context.writeClipboardText(url)
                                    toaster.show("已复制 URL")
                                } else {
                                    toaster.show("复制 URL 失败")
                                }
                                pendingCloudActions = null
                            }
                        }
                    ) { Text("复制 URL") }
                    TextButton(
                        enabled = ref != null && !local.isFile,
                        onClick = {
                            val cloudRef = ref ?: return@TextButton
                            scope.launch {
                                val bytes = r2MediaStore.downloadBytes(cloudRef).getOrNull()
                                if (bytes != null) {
                                    filesManager.restoreLocalCache(target.id, bytes)
                                    toaster.show("已下载到本地缓存")
                                    refreshTick++
                                } else {
                                    toaster.show("下载失败")
                                }
                                pendingCloudActions = null
                            }
                        }
                    ) { Text("下载") }
                    TextButton(
                        enabled = local.isFile || ref != null,
                        onClick = {
                            scope.launch {
                                var fileToSave = local
                                if (!fileToSave.isFile && ref != null) {
                                    r2MediaStore.downloadBytes(ref).getOrNull()?.let { bytes ->
                                        filesManager.restoreLocalCache(target.id, bytes)
                                        fileToSave = filesManager.getFile(target)
                                    }
                                }
                                if (fileToSave.isFile && context.saveFileToRikkaHubDownloads(fileToSave, target.displayName, target.mimeType)) {
                                    toaster.show("已保存到 Download/rikkahub")
                                } else toaster.show("保存失败")
                                refreshTick++
                                pendingCloudActions = null
                            }
                        }
                    ) { Text("保存") }
                    TextButton(onClick = { pendingCloudActions = null }) {
                        Text(stringResource(R.string.setting_files_page_cancel_action))
                    }
                }
            },
        )
    }

    pendingRemoteDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRemoteDelete = null },
            title = { Text(stringResource(R.string.setting_files_page_delete_file_title)) },
            text = { Text(target.path) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            // P3：r2:// 引用联动删除 R2 对象（资产的唯一主人）
                            R2Ref.parse(target.path)?.let { ref -> r2MediaStore.delete(ref) }
                            genMediaRepository.deleteMedia(target.id)
                            remoteImageUrls = remoteImageUrls.filterNot { it.id == target.id }
                            pendingRemoteDelete = null
                            toaster.show(deletedToast)
                        }
                    }
                ) {
                    Text(stringResource(R.string.setting_files_page_delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoteDelete = null }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            }
        )
    }

    if (showCleanDialog) {
        val isLlmPreviews = selectedFolder == FileFolders.LLM_PREVIEWS
        AlertDialog(
            onDismissRequest = { showCleanDialog = false },
            title = {
                Text(
                    stringResource(
                        if (isLlmPreviews) R.string.setting_files_page_clean_previews_title
                        else R.string.setting_files_page_clean_local_title
                    )
                )
            },
            text = {
                Text(
                    stringResource(
                        if (isLlmPreviews) R.string.setting_files_page_clean_previews_confirmation
                        else R.string.setting_files_page_clean_local_confirmation
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCleanDialog = false
                        scope.launch {
                            val ok = if (isLlmPreviews) {
                                files.forEach { file ->
                                    file.r2RefOrNull()?.let { ref -> r2MediaStore.delete(ref) }
                                }
                                filesManager.deleteAll(selectedFolder)
                            } else {
                                filesManager.deleteAllLocalCache(selectedFolder)
                            }
                            toaster.show(if (ok) cleanedToast else cleanFailedToast)
                        }
                    }
                ) {
                    Text(stringResource(R.string.setting_files_page_clean_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCleanDialog = false }) {
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
                            val remoteTargets = visibleRemoteImages.filter { it.id in selectedRemoteIds }
                            var ok = 0
                            var failed = 0
                            when (action) {
                                BulkAction.DELETE_LOCAL -> {
                                    targets.forEach { file ->
                                        if (filesManager.deleteLocalCache(file.id)) ok++ else failed++
                                    }
                                }

                                BulkAction.DELETE_ALL -> {
                                    targets.forEach { file ->
                                        file.r2RefOrNull()?.let { ref -> r2MediaStore.delete(ref) }
                                        if (filesManager.delete(file.id, deleteFromDisk = true)) {
                                            if (selectedFolder == FileFolders.IMAGES) {
                                                genMediaRepository.deleteMediaByPath(file.relativePath)
                                            }
                                            ok++
                                        } else failed++
                                    }
                                    remoteTargets.forEach { image ->
                                        R2Ref.parse(image.path)?.let { ref -> r2MediaStore.delete(ref) }
                                        genMediaRepository.deleteMedia(image.id)
                                        ok++
                                    }
                                    remoteImageUrls = remoteImageUrls.filterNot { it.id in selectedRemoteIds }
                                }

                                BulkAction.UPLOAD -> {
                                    targets.forEach { file ->
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
                                }

                                BulkAction.DOWNLOAD -> {
                                    targets.forEach { file ->
                                        val ref = file.r2RefOrNull()
                                        if (ref == null || filesManager.getFile(file).isFile) {
                                            failed++
                                            return@forEach
                                        }
                                        val bytes = r2MediaStore.downloadBytes(ref).getOrNull()
                                        if (bytes != null && filesManager.restoreLocalCache(file.id, bytes)) ok++ else failed++
                                    }
                                }

                                BulkAction.SAVE -> {
                                    targets.forEach { file ->
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
                                            context.saveFileToRikkaHubDownloads(local, file.displayName, file.mimeType)
                                        ) ok++ else failed++
                                    }
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
                    Text(stringResource(if (bulkRunning) R.string.setting_files_page_bulk_running else action.confirmRes))
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
                        else stringResource(R.string.setting_files_page_title)
                    )
                },
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = { exitSelection() }) {
                            Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.setting_files_page_cancel_action))
                        }
                    } else {
                        BackButton()
                    }
                },
                actions = {
                    if (selectionMode) {
                        val allSelected = selectedCount > 0 &&
                            selectedCount == visibleFiles.size + visibleRemoteImages.size
                        IconButton(
                            onClick = {
                                if (allSelected) {
                                    selectedFileIds = emptySet()
                                    selectedRemoteIds = emptySet()
                                } else {
                                    selectedFileIds = visibleFiles.mapTo(mutableSetOf()) { it.id }
                                    selectedRemoteIds = visibleRemoteImages.mapTo(mutableSetOf()) { it.id }
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
                            onClick = { pendingBulkAction = BulkAction.DELETE_ALL },
                        ) {
                            Icon(
                                HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.setting_files_page_bulk_delete_all_title),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                        Box {
                            IconButton(
                                enabled = selectedFileIds.isNotEmpty(),
                                onClick = { showBulkMenu = true },
                            ) {
                                Icon(HugeIcons.MoreVertical, contentDescription = stringResource(R.string.setting_files_page_bulk_select))
                            }
                            DropdownMenu(
                                expanded = showBulkMenu,
                                onDismissRequest = { showBulkMenu = false },
                            ) {
                                listOf(
                                    BulkAction.SAVE to HugeIcons.Download01,
                                    BulkAction.UPLOAD to HugeIcons.CloudUpload,
                                    BulkAction.DOWNLOAD to HugeIcons.CloudDownload,
                                    BulkAction.DELETE_LOCAL to HugeIcons.Clean,
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
                        IconButton(onClick = {
                            searchActive = !searchActive
                            if (!searchActive) searchQuery = ""
                        }) {
                            Icon(
                                imageVector = if (searchActive) HugeIcons.Cancel01 else HugeIcons.Search01,
                                contentDescription = stringResource(R.string.setting_files_page_search_hint),
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
                                                Icon(HugeIcons.Tick02, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
                            enabled = visibleFiles.isNotEmpty() || visibleRemoteImages.isNotEmpty(),
                        ) {
                            Icon(
                                imageVector = HugeIcons.TextSelection,
                                contentDescription = stringResource(R.string.setting_files_page_bulk_select),
                            )
                        }
                        IconButton(
                            onClick = { showCleanDialog = true },
                            enabled = files.isNotEmpty() || remoteImageUrls.isNotEmpty(),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Clean,
                                contentDescription = stringResource(R.string.setting_files_page_clean_content_description),
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
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
            FolderRow(
                folders = folders,
                selectedFolder = selectedFolder,
                onFolderSelected = { selectedFolder = it }
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
                    placeholder = { Text(stringResource(R.string.setting_files_page_search_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (visibleFiles.isEmpty() && visibleRemoteImages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(
                            if (searchQuery.isNotBlank()) R.string.setting_files_page_search_no_result
                            else R.string.setting_files_page_no_files
                        )
                    )
                }
            } else {
                val imagePreviewUrls = remember(visibleFiles, visibleRemoteImages, selectedFolder) {
                    visibleFiles.mapNotNull { file ->
                        when {
                            file.relativePath.isRemoteImageUrl() -> file.relativePath
                            file.mimeType.startsWith("image/") -> filesManager.getFile(file).toUri().toString()
                            else -> null
                        }
                    } + visibleRemoteImages.map { it.path }
                }
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
                    columns = StaggeredGridCells.Fixed(columns)
                ) {
                    groupedFiles.forEach { (date, filesOfDay) ->
                        item(key = "file-date-$date", span = StaggeredGridItemSpan.FullLine) {
                            DateHeader(date = date, count = filesOfDay.size)
                        }
                        items(filesOfDay, key = { "file-${it.id}" }) { file ->
                            val fileOnDisk = filesManager.getFile(file)
                            FileItem(
                                file = file,
                                fileOnDisk = fileOnDisk,
                                compact = compact,
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
                                onDelete = {
                                    scope.launch {
                                        val localExists = fileOnDisk.isFile || file.relativePath.isRemoteImageUrl()
                                        val cloudExists = file.hasCloudCopy
                                        when {
                                            localExists && !file.relativePath.isRemoteImageUrl() -> {
                                                if (filesManager.deleteLocalCache(file.id)) toaster.show(deletedToast) else toaster.show(deleteFailedToast)
                                            }
                                            cloudExists -> pendingCloudDelete = file
                                            else -> toaster.show(deleteFailedToast)
                                        }
                                    }
                                },
                                onOpenImage = {
                                    managedImagePreview = file
                                },
                                onOpenAudio = { audioPreview = file },
                                onOpenCloud = { pendingCloudActions = file },
                            )
                        }
                    }
                    groupedRemoteImages.forEach { (date, imagesOfDay) ->
                        item(key = "remote-date-$date", span = StaggeredGridItemSpan.FullLine) {
                            DateHeader(date = date, count = imagesOfDay.size)
                        }
                        items(imagesOfDay, key = { "remote-${it.id}" }) { image ->
                            RemoteImageItem(
                                image = image,
                                compact = compact,
                                selectionMode = selectionMode,
                                selected = image.id in selectedRemoteIds,
                                onToggleSelect = {
                                    selectedRemoteIds = if (image.id in selectedRemoteIds) {
                                        selectedRemoteIds - image.id
                                    } else {
                                        selectedRemoteIds + image.id
                                    }
                                },
                                onLongPress = {
                                    selectionMode = true
                                    selectedRemoteIds = selectedRemoteIds + image.id
                                },
                                onDelete = { pendingRemoteDelete = image },
                                onOpen = { previewImages = imagePreviewUrls.startingAt(image.path) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(
    folders: List<String>,
    selectedFolder: String,
    onFolderSelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        folders.forEach { folder ->
            FilterChip(
                selected = selectedFolder == folder,
                onClick = { onFolderSelected(folder) },
                label = { Text(folderDisplayName(folder)) }
            )
        }
    }
}

@Composable
private fun folderDisplayName(folder: String): String = when (folder) {
    FileFolders.UPLOAD -> stringResource(R.string.setting_files_page_folder_upload)
    FileFolders.IMAGES -> stringResource(R.string.setting_files_page_folder_images)
    FileFolders.LLM_PREVIEWS -> stringResource(R.string.setting_files_page_folder_llm_previews)
    FileFolders.AVATARS -> stringResource(R.string.setting_files_page_folder_avatars)
    FileFolders.TTS_CACHE -> stringResource(R.string.setting_files_page_folder_tts_cache)
    else -> folder
}

private fun String.isRemoteImageUrl(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

private fun Context.saveFileToRikkaHubDownloads(file: File, displayName: String, mimeType: String): Boolean = runCatching {
    val category = when {
        mimeType.startsWith("image/") -> "image"
        mimeType.startsWith("video/") -> "video"
        mimeType.startsWith("audio/") -> "audio"
        mimeType.startsWith("application/") || mimeType.startsWith("text/") -> "document"
        else -> "others"
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/rikkahub/$category")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
        contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
        true
    } else {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "rikkahub/$category").apply { mkdirs() }
        file.copyTo(File(dir, displayName), overwrite = true)
        true
    }
}.getOrDefault(false)

@Composable
private fun RemoteImageItem(
    image: GenMediaEntity,
    compact: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    val r2MediaStore: R2MediaStore = koinInject()
    var displayUrl by remember(image.path) { mutableStateOf(image.path) }
    LaunchedEffect(image.path) {
        displayUrl = if (image.path.startsWith("r2://")) {
            r2MediaStore.displayUrl(image.path)
        } else {
            image.path
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectionBorder(selectionMode, selected, MaterialTheme.colorScheme.primary),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor)
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth()) {
                AsyncImage(
                    model = displayUrl,
                    contentDescription = image.prompt,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .combinedClickable(
                            onClick = { if (selectionMode) onToggleSelect() else onOpen() },
                            onLongClick = onLongPress,
                        ),
                    contentScale = ContentScale.Crop,
                )
                if (!selectionMode) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val isR2 = image.path.startsWith("r2://") || image.r2Key != null
                        val isExternal = image.originalUrl != null || image.path.isRemoteImageUrl()
                        if (isR2) StatusBadge(HugeIcons.Cloud, MaterialTheme.colorScheme.primary)
                        if (isExternal) StatusBadge(HugeIcons.FileLink, MaterialTheme.colorScheme.tertiary)
                    }
                }
                if (selectionMode) {
                    SelectionCheckbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelect() },
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                } else {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.setting_files_page_delete_content_description),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            if (!compact) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = image.prompt.ifBlank { image.path },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOf(
                            if (image.path.startsWith("r2://")) stringResource(R.string.setting_files_page_status_cloud_only) else "URL",
                            image.path.toByteArray(Charsets.UTF_8).size.toLong().fileSizeToString()
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Text(
                    text = image.prompt.ifBlank { image.path },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private fun List<String>.startingAt(url: String): List<String> {
    val index = indexOf(url)
    return if (index <= 0) this else drop(index) + take(index)
}


@Composable
private fun ManagedImagePreviewDialog(
    file: ManagedFileEntity,
    filesManager: FilesManager,
    r2MediaStore: R2MediaStore,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var localFile by remember(file.id) { mutableStateOf(filesManager.getFile(file)) }
    var r2Ref by remember(file.id, file.r2Key, file.r2Acct) { mutableStateOf(file.r2RefOrNull()) }
    var displayUrl by remember(file.id, r2Ref, localFile.absolutePath) {
        mutableStateOf(if (localFile.isFile) localFile.toUri().toString() else r2Ref?.toString())
    }
    var confirmDelete by remember { mutableStateOf(false) }

    suspend fun refreshDisplay() {
        localFile = filesManager.get(file.id)?.let { filesManager.getFile(it) } ?: localFile
        r2Ref = filesManager.get(file.id)?.r2RefOrNull() ?: r2Ref
        displayUrl = when {
            localFile.isFile -> localFile.toUri().toString()
            r2Ref != null -> r2MediaStore.presign(r2Ref!!).getOrNull() ?: r2Ref.toString()
            else -> null
        }
        onChanged()
    }

    suspend fun ensureLocal(): File? {
        if (localFile.isFile) return localFile
        val ref = r2Ref ?: return null
        val bytes = r2MediaStore.downloadBytes(ref).getOrNull() ?: return null
        filesManager.restoreLocalCache(file.id, bytes)
        refreshDisplay()
        return localFile.takeIf { it.isFile }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除文件？") },
            text = { Text("将删除本地缓存、云端对象和文件索引。聊天历史中的引用可能显示不可用。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        r2Ref?.let { r2MediaStore.delete(it) }
                        filesManager.delete(file.id, deleteFromDisk = true)
                        toaster.show("已删除")
                        confirmDelete = false
                        onChanged()
                        onDismiss()
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }

    ImagePreviewDialog(
        images = listOfNotNull(displayUrl),
        onDismissRequest = onDismiss,
        bottomActions = {
            val hasLocal = localFile.isFile
            val hasCloud = r2Ref != null
            if (hasLocal || hasCloud) {
                IconButton(onClick = {
                    scope.launch {
                        val src = ensureLocal()
                        if (src == null) {
                            toaster.show("压缩失败：文件不可用")
                            return@launch
                        }
                        val bytes = filesManager.createManualCompressBytes(src)
                        if (bytes != null) {
                            filesManager.replaceLocalCache(file.id, bytes, "image/jpeg")
                            val oldRef = r2Ref
                            if (oldRef != null) {
                                r2MediaStore.upload(bytes, "image/jpeg", R2MediaStore.PREFIX_CHAT_UPLOADS).getOrNull()?.let { newRef ->
                                    filesManager.setCloudCopy(file.id, newRef.key, newRef.acctId)
                                    if (newRef != oldRef) r2MediaStore.delete(oldRef)
                                }
                            }
                            toaster.show("已压缩")
                            refreshDisplay()
                        }
                    }
                }) { Icon(HugeIcons.ShrinkDot, null, tint = Color.White) }
            }

            if (hasLocal && !hasCloud) {
                IconButton(onClick = {
                    scope.launch {
                        val src = ensureLocal()
                        if (src == null) {
                            toaster.show("上传失败：本地文件不可用")
                            return@launch
                        }
                        val ref = r2MediaStore.upload(src.readBytes(), file.mimeType, R2MediaStore.PREFIX_CHAT_UPLOADS).getOrNull()
                        if (ref != null) {
                            filesManager.setCloudCopy(file.id, ref.key, ref.acctId)
                            toaster.show("已上传云端")
                            refreshDisplay()
                        } else toaster.show("上传失败")
                    }
                }) { Icon(HugeIcons.CloudUpload, null, tint = Color.White) }
            }

            if (hasCloud) {
                IconButton(onClick = {
                    scope.launch {
                        val ref = r2Ref
                        if (ref == null) {
                            toaster.show("没有云端 URL")
                            return@launch
                        }
                        val url = r2MediaStore.presign(ref).getOrNull()
                        if (url != null) {
                            context.writeClipboardText(url)
                            toaster.show("已复制 URL")
                        } else toaster.show("复制失败")
                    }
                }) { Icon(HugeIcons.Copy01, null, tint = Color.White) }
            }

            if (hasCloud && !hasLocal) {
                IconButton(onClick = {
                    scope.launch {
                        val src = ensureLocal()
                        if (src != null) toaster.show("已下载到本地") else toaster.show("下载失败")
                        refreshDisplay()
                    }
                }) { Icon(HugeIcons.CloudDownload, null, tint = Color.White) }
            }

            if (hasLocal || hasCloud) {
                IconButton(onClick = {
                    scope.launch {
                        val url = displayUrl ?: return@launch
                        runCatching { filesManager.saveMessageImage(context, url) }
                            .onSuccess { toaster.show("已保存图片") }
                            .onFailure { toaster.show(it.message ?: it.toString()) }
                    }
                }) { Icon(HugeIcons.ImageDownload, null, tint = Color.White) }
            }

            if (hasLocal || hasCloud) {
                IconButton(onClick = { confirmDelete = true }) { Icon(HugeIcons.Delete01, null, tint = Color.White) }
            }
        }
    )
}

@Composable
private fun AudioPreviewDialog(
    file: ManagedFileEntity,
    fileOnDisk: File,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var duration by remember(file.id) { mutableStateOf(0) }
    var position by remember(file.id) { mutableStateOf(0) }
    var isPlaying by remember(file.id) { mutableStateOf(false) }
    val player = remember(file.id, fileOnDisk.absolutePath) {
        runCatching {
            require(fileOnDisk.isFile) { "音频文件不存在，可能已被系统清理" }
            MediaPlayer().apply {
                setDataSource(context, fileOnDisk.toUri())
                prepare()
            }
        }.getOrNull()
    }
    LaunchedEffect(player) {
        duration = runCatching { player?.duration ?: 0 }.getOrDefault(0)
    }
    DisposableEffect(player) {
        onDispose {
            runCatching { player?.release() }
        }
    }
    LaunchedEffect(player, isPlaying) {
        while (isActive) {
            position = runCatching { player?.currentPosition ?: position }.getOrDefault(position)
            delay(300)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.displayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (player == null) {
                    Text("音频文件不存在，可能已被系统清理。请删除这条缓存记录。")
                } else {
                    Slider(
                        value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                        onValueChange = { value ->
                            position = value.toInt()
                            player.seekTo(position)
                        },
                        valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                    )
                    Text("${position / 1000}s / ${duration / 1000}s")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val mediaPlayer = player ?: return@TextButton
                    if (mediaPlayer.isPlaying) {
                        mediaPlayer.pause()
                        isPlaying = false
                    } else {
                        mediaPlayer.start()
                        isPlaying = true
                    }
                }
            ) {
                Text(if (player == null) "不可播放" else if (isPlaying) "暂停" else "播放")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun FileItem(
    file: ManagedFileEntity,
    fileOnDisk: File,
    compact: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onDelete: () -> Unit,
    onOpenImage: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenCloud: () -> Unit,
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
            .selectionBorder(selectionMode, selected, MaterialTheme.colorScheme.primary),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor)
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                when {
                    unavailable -> {
                        FilePlaceholder(
                            icon = HugeIcons.Alert01,
                            label = stringResource(R.string.setting_files_page_file_unavailable),
                            tint = MaterialTheme.colorScheme.error,
                            compact = compact,
                            onLongClick = onLongPress,
                        )
                    }
                    file.relativePath.isRemoteImageUrl() || file.mimeType.startsWith("image/") -> {
                        AsyncImage(
                            model = when {
                                file.relativePath.isRemoteImageUrl() -> file.relativePath
                                fileOnDisk.isFile -> fileOnDisk
                                cloudDisplayUrl != null -> cloudDisplayUrl
                                else -> fileOnDisk
                            },
                            contentDescription = file.displayName,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(4f / 3f)
                                .combinedClickable(
                                    enabled = selectionMode || localExists || cloudExists,
                                    onClick = {
                                        if (selectionMode) onToggleSelect() else onOpenImage()
                                    },
                                    onLongClick = onLongPress,
                                ),
                            contentScale = ContentScale.Crop
                        )
                    }
                    file.mimeType.startsWith("audio/") -> {
                        FilePlaceholder(
                            icon = HugeIcons.MusicNote03,
                            label = file.displayName,
                            tint = MaterialTheme.colorScheme.primary,
                            compact = compact,
                            onLongClick = onLongPress,
                            onClick = when {
                                selectionMode -> onToggleSelect
                                localExists -> onOpenAudio
                                cloudExists -> onOpenCloud
                                else -> null
                            },
                        )
                    }
                    file.mimeType.startsWith("video/") -> {
                        FilePlaceholder(
                            icon = HugeIcons.Video01,
                            label = file.displayName,
                            tint = MaterialTheme.colorScheme.primary,
                            compact = compact,
                            onLongClick = onLongPress,
                            onClick = if (selectionMode) onToggleSelect else if (localExists || cloudExists) onOpenCloud else null,
                        )
                    }
                    else -> {
                        FilePlaceholder(
                            icon = file.documentIcon(),
                            label = file.displayName,
                            tint = MaterialTheme.colorScheme.primary,
                            compact = compact,
                            onLongClick = onLongPress,
                            onClick = if (selectionMode) onToggleSelect else if (localExists || cloudExists) onOpenCloud else null,
                        )
                    }
                }

                if (!selectionMode) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        when {
                            unavailable -> StatusBadge(HugeIcons.Alert01, MaterialTheme.colorScheme.error)
                            else -> {
                                val hasExternalUrl = !file.externalUrl.isNullOrBlank() || file.relativePath.isRemoteImageUrl()
                                if (cloudExists) StatusBadge(HugeIcons.Cloud, MaterialTheme.colorScheme.primary)
                                if (hasExternalUrl) StatusBadge(HugeIcons.FileLink, MaterialTheme.colorScheme.tertiary)
                                if (localExists && !file.relativePath.isRemoteImageUrl()) StatusBadge(HugeIcons.File02, MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }

                if (selectionMode) {
                    SelectionCheckbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelect() },
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                } else {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Icon(
                            HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.setting_files_page_delete_content_description)
                        )
                    }
                }
            }

            if (compact) {
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (unavailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Text(
                        text = file.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOfNotNull(
                            file.mimeType,
                            when {
                                unavailable -> stringResource(R.string.setting_files_page_file_unavailable)
                                cloudExists && localExists && !file.relativePath.isRemoteImageUrl() -> stringResource(R.string.setting_files_page_status_local_cloud)
                                cloudExists -> stringResource(R.string.setting_files_page_status_cloud_only)
                                localExists -> stringResource(R.string.setting_files_page_status_local_only)
                                else -> null
                            }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (unavailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (file.relativePath.isRemoteImageUrl()) {
                            file.relativePath.toByteArray(Charsets.UTF_8).size.toLong().fileSizeToString()
                        } else {
                            file.sizeBytes.fileSizeToString()
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(icon: ImageVector, tint: androidx.compose.ui.graphics.Color) {
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

@Composable
private fun FilePlaceholder(
    icon: ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.combinedClickable(
                        onClick = { onClick?.invoke() },
                        onLongClick = onLongClick,
                    )
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
            modifier = Modifier.padding(if (compact) 6.dp else 16.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(if (compact) 28.dp else 40.dp),
                tint = tint,
            )
            if (!compact) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 选中态描边：让批量选择在密集网格下也一眼看清。 */
private fun Modifier.selectionBorder(
    selectionMode: Boolean,
    selected: Boolean,
    color: Color,
): Modifier =
    if (selectionMode && selected) {
        this.border(2.dp, color, RoundedCornerShape(12.dp))
    } else this

@Composable
private fun SelectionCheckbox(
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

/** 批量操作类型。 */
private enum class BulkAction(
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

/** 搜索：文件名 / MIME / 相对路径 / prompt / 描述，空格分词后全部命中才算匹配。 */
private fun List<ManagedFileEntity>.filterByQuery(query: String): List<ManagedFileEntity> {
    val tokens = query.trim().split(' ', '\t').filter { it.isNotBlank() }
    if (tokens.isEmpty()) return this
    return filter { file ->
        val haystack = listOfNotNull(
            file.displayName,
            file.mimeType,
            file.relativePath,
            file.prompt,
            file.description,
        ).joinToString(" ")
        tokens.all { haystack.contains(it, ignoreCase = true) }
    }
}

private fun List<GenMediaEntity>.filterRemoteByQuery(query: String): List<GenMediaEntity> {
    val tokens = query.trim().split(' ', '\t').filter { it.isNotBlank() }
    if (tokens.isEmpty()) return this
    return filter { image ->
        val haystack = listOfNotNull(image.prompt, image.path, image.modelId, image.originalUrl)
            .joinToString(" ")
        tokens.all { haystack.contains(it, ignoreCase = true) }
    }
}

private fun GenMediaEntity.matchesManagedFile(file: ManagedFileEntity): Boolean {
    val genRef = R2Ref.parse(path)
        ?: r2Key?.takeIf { !r2Acct.isNullOrBlank() }?.let { R2Ref(r2Acct!!, it) }
        ?: return false
    return file.r2Key == genRef.key && file.r2Acct == genRef.acctId
}

private val ManagedFileEntity.hasCloudCopy: Boolean
    get() = !r2Key.isNullOrBlank() && !r2Acct.isNullOrBlank()

private fun ManagedFileEntity.r2RefOrNull(): R2Ref? =
    if (hasCloudCopy) R2Ref(r2Acct!!, r2Key!!) else null

private fun ManagedFileEntity.documentIcon(): ImageVector = when {
    mimeType == "application/pdf" -> HugeIcons.File02
    mimeType.startsWith("text/") || displayName.endsWith(".md", ignoreCase = true) -> HugeIcons.File02
    mimeType.contains("word", ignoreCase = true) || displayName.endsWith(".doc", true) || displayName.endsWith(".docx", true) -> HugeIcons.Files02
    mimeType.contains("spreadsheet", ignoreCase = true) || displayName.endsWith(".xls", true) || displayName.endsWith(".xlsx", true) -> HugeIcons.Files02
    mimeType.contains("presentation", ignoreCase = true) || displayName.endsWith(".ppt", true) || displayName.endsWith(".pptx", true) -> HugeIcons.Files02
    else -> HugeIcons.File02
}
