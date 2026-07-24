package me.rerere.rikkahub.ui.pages.setting

import android.media.MediaPlayer
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Clean
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
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
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import org.koin.compose.koinInject
import java.io.File

@Composable
fun SettingFilesPage(
    filesManager: FilesManager = koinInject(),
    genMediaRepository: GenMediaRepository = koinInject(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridState = rememberLazyStaggeredGridState()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val folders = remember { listOf(FileFolders.UPLOAD, FileFolders.IMAGES, FileFolders.AVATARS, FileFolders.TTS_CACHE) }

    // 预先获取字符串资源
    val deletedToast = stringResource(R.string.setting_files_page_deleted_toast)
    val deleteFailedToast = stringResource(R.string.setting_files_page_delete_failed_toast)
    val cleanedToast = stringResource(R.string.setting_files_page_cleaned_toast)
    val cleanFailedToast = stringResource(R.string.setting_files_page_clean_failed_toast)

    var selectedFolder by remember { mutableStateOf(FileFolders.UPLOAD) }
    var pendingDelete by remember { mutableStateOf<ManagedFileEntity?>(null) }
    var pendingRemoteDelete by remember { mutableStateOf<GenMediaEntity?>(null) }
    var remoteImageUrls by remember { mutableStateOf<List<GenMediaEntity>>(emptyList()) }
    var showCleanDialog by remember { mutableStateOf(false) }
    var previewImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var audioPreview by remember { mutableStateOf<ManagedFileEntity?>(null) }
    val files by filesManager.observe(selectedFolder).collectAsState(initial = emptyList())

    LaunchedEffect(selectedFolder) {
        filesManager.syncFolder(selectedFolder)
        remoteImageUrls = if (selectedFolder == FileFolders.IMAGES) {
            genMediaRepository.getAllMediaList().filter { it.path.isRemoteImageUrl() }
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

    if (pendingDelete != null) {
        val target = pendingDelete!!
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.setting_files_page_delete_file_title)) },
            text = { Text(target.displayName) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val ok = filesManager.delete(target.id, deleteFromDisk = true)
                            if (ok) {
                                if (selectedFolder == FileFolders.IMAGES) {
                                    genMediaRepository.deleteMediaByPath(target.relativePath)
                                }
                                toaster.show(deletedToast)
                            } else {
                                toaster.show(deleteFailedToast)
                            }
                            pendingDelete = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.setting_files_page_delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            }
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
        AlertDialog(
            onDismissRequest = { showCleanDialog = false },
            title = { Text(stringResource(R.string.setting_files_page_clean_title)) },
            text = { Text(stringResource(R.string.setting_files_page_clean_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCleanDialog = false
                        scope.launch {
                            val ok = filesManager.deleteAll(selectedFolder)
                            if (selectedFolder == FileFolders.IMAGES) {
                                genMediaRepository.getAllMediaList()
                                    .filter { it.path.isRemoteImageUrl() || it.path.startsWith("${FileFolders.IMAGES}/") }
                                    .forEach { genMediaRepository.deleteMedia(it.id) }
                                remoteImageUrls = emptyList()
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
                            onDelete = { pendingDelete = file },
                            onOpenImage = {
                                val url = if (file.relativePath.isRemoteImageUrl()) file.relativePath else fileOnDisk.toUri().toString()
                                previewImages = imagePreviewUrls.startingAt(url)
                            },
                            onOpenAudio = { audioPreview = file },
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor)
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth()) {
                AsyncImage(
                    model = image.path,
                    contentDescription = image.prompt,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .clickable(onClick = onOpen),
                    contentScale = ContentScale.Crop,
                )
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
                    text = "URL · ${image.path.toByteArray(Charsets.UTF_8).size.toLong().fileSizeToString()}",
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
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor)
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                when {
                    file.relativePath.isRemoteImageUrl() || file.mimeType.startsWith("image/") -> {
                        AsyncImage(
                            model = if (file.relativePath.isRemoteImageUrl()) file.relativePath else fileOnDisk,
                            contentDescription = file.displayName,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(4f / 3f)
                                .clickable(onClick = onOpenImage),
                            contentScale = ContentScale.Crop
                        )
                    }
                    file.mimeType.startsWith("audio/") -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(4f / 3f)
                                .clickable(onClick = onOpenAudio),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = HugeIcons.Image02,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    else -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(4f / 3f),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = HugeIcons.Image02,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                    text = file.mimeType,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
