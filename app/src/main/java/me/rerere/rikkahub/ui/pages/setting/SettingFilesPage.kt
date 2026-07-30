package me.rerere.rikkahub.ui.pages.setting

import android.media.MediaPlayer
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.Video01
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Clean
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.ImageUpload
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.writeClipboardText
import org.koin.compose.koinInject
import java.io.File

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
    val files by filesManager.observe(selectedFolder).collectAsState(initial = emptyList())

    LaunchedEffect(selectedFolder, refreshTick) {
        filesManager.syncFolder(selectedFolder)
        // 远端/云端生图历史：http(s) 渠道直链 + r2:// 私有桶引用
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

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_files_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(
                        onClick = { showCleanDialog = true },
                        enabled = files.isNotEmpty() || remoteImageUrls.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Clean,
                            contentDescription = stringResource(R.string.setting_files_page_clean_content_description),
                        )
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

            if (files.isEmpty() && remoteImageUrls.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.setting_files_page_no_files))
                }
            } else {
                val imagePreviewUrls = remember(files, remoteImageUrls, selectedFolder) {
                    files.mapNotNull { file ->
                        when {
                            file.relativePath.isRemoteImageUrl() -> file.relativePath
                            file.mimeType.startsWith("image/") -> filesManager.getFile(file).toUri().toString()
                            else -> null
                        }
                    } + remoteImageUrls.map { it.path }
                }
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
                    columns = StaggeredGridCells.Fixed(2)
                ) {
                    items(files, key = { "file-${it.id}" }) { file ->
                        val fileOnDisk = filesManager.getFile(file)
                        FileItem(
                            file = file,
                            fileOnDisk = fileOnDisk,
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
                    items(remoteImageUrls, key = { "remote-${it.id}" }) { image ->
                        RemoteImageItem(
                            image = image,
                            onDelete = { pendingRemoteDelete = image },
                            onOpen = { previewImages = imagePreviewUrls.startingAt(image.path) },
                        )
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

@Composable
private fun RemoteImageItem(
    image: GenMediaEntity,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    val r2MediaStore: R2MediaStore = koinInject()
    var displayUrl by remember(image.path) { mutableStateOf(image.path) }
    val cloudExists = image.path.startsWith("r2://") || image.path.isRemoteImageUrl()
    LaunchedEffect(image.path) {
        displayUrl = if (image.path.startsWith("r2://")) {
            r2MediaStore.displayUrl(image.path)
        } else {
            image.path
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
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
                        .clickable(onClick = onOpen),
                    contentScale = ContentScale.Crop,
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (cloudExists) StatusBadge(HugeIcons.Database02, MaterialTheme.colorScheme.primary)
                }
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
                        val bytes = filesManager.createLlmPreviewImageBytes(src)
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
                }) { Icon(HugeIcons.Clean, null, tint = Color.White) }
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
                }) { Icon(HugeIcons.ImageUpload, null, tint = Color.White) }
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
                }) { Icon(HugeIcons.Download01, null, tint = Color.White) }
            }

            if (hasLocal || hasCloud) {
                IconButton(onClick = {
                    scope.launch {
                        val url = displayUrl ?: return@launch
                        runCatching { filesManager.saveMessageImage(context, url) }
                            .onSuccess { toaster.show("已保存图片") }
                            .onFailure { toaster.show(it.message ?: it.toString()) }
                    }
                }) { Icon(HugeIcons.File02, null, tint = Color.White) }
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
        modifier = Modifier.fillMaxWidth(),
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
                                .clickable(enabled = localExists || cloudExists, onClick = onOpenImage),
                            contentScale = ContentScale.Crop
                        )
                    }
                    file.mimeType.startsWith("audio/") -> {
                        FilePlaceholder(
                            icon = HugeIcons.MusicNote03,
                            label = file.displayName,
                            tint = MaterialTheme.colorScheme.primary,
                            onClick = if (localExists) onOpenAudio else if (cloudExists) onOpenCloud else null,
                        )
                    }
                    file.mimeType.startsWith("video/") -> {
                        FilePlaceholder(
                            icon = HugeIcons.Video01,
                            label = file.displayName,
                            tint = MaterialTheme.colorScheme.primary,
                            onClick = if (localExists || cloudExists) onOpenCloud else null,
                        )
                    }
                    else -> {
                        FilePlaceholder(
                            icon = file.documentIcon(),
                            label = file.displayName,
                            tint = MaterialTheme.colorScheme.primary,
                            onClick = if (localExists || cloudExists) onOpenCloud else null,
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    when {
                        unavailable -> StatusBadge(HugeIcons.Alert01, MaterialTheme.colorScheme.error)
                        else -> {
                            if (cloudExists) StatusBadge(HugeIcons.Database02, MaterialTheme.colorScheme.primary)
                            if (localExists && !file.relativePath.isRemoteImageUrl()) StatusBadge(HugeIcons.File02, MaterialTheme.colorScheme.secondary)
                        }
                    }
                }

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
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = tint,
            )
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
