package me.rerere.rikkahub.data.files

import me.rerere.rikkahub.data.files.AppPaths
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.system.Os
import android.util.Log
import androidx.core.net.toFile
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.utils.exportImage
import me.rerere.rikkahub.utils.exportImageFile
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.getActivity
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.max
import kotlin.math.roundToInt

class FilesManager(
    private val context: Context,
    private val repository: FilesRepository,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "FilesManager"
        private const val IMAGE_COMPRESS_MAX_EDGE = 2560
        private const val IMAGE_COMPRESS_JPEG_QUALITY = 85
        private const val IMAGE_COMPRESS_SKIP_BYTES = 1024 * 1024L
    }

    suspend fun saveManagedFromUri(
        folder: String,
        uri: Uri,
        displayName: String? = null,
        mimeType: String? = null,
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val resolvedName = displayName ?: getFileNameFromUri(uri) ?: "file"
        val resolvedMime = mimeType ?: getFileMimeType(uri) ?: "application/octet-stream"
        val target = createTargetFile(folder, resolvedName, resolvedMime)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        createManagedFileEntity(
            folder = folder,
            file = target,
            displayName = resolvedName,
            mimeType = resolvedMime,
        )
    }

    suspend fun saveManagedFromBytes(
        folder: String,
        bytes: ByteArray,
        displayName: String,
        mimeType: String = "application/octet-stream",
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val target = createTargetFile(folder, displayName, mimeType)
        target.writeBytes(bytes)
        createManagedFileEntity(
            folder = folder,
            file = target,
            displayName = displayName,
            mimeType = mimeType,
        )
    }

    suspend fun saveManagedText(
        folder: String,
        text: String,
        displayName: String = "pasted_text.txt",
        mimeType: String = "text/plain",
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val target = createTargetFile(folder, displayName, mimeType)
        target.writeText(text)
        createManagedFileEntity(
            folder = folder,
            file = target,
            displayName = displayName,
            mimeType = mimeType,
        )
    }

    fun observe(folder: String = FileFolders.UPLOAD): Flow<List<ManagedFileEntity>> =
        repository.listByFolder(folder).map { list ->
            filterFolderEntities(folder, list)
        }

    fun observeAllImages(): Flow<List<ManagedFileEntity>> =
        repository.listAll().map { list ->
            list.filter { !it.deleted && it.isGalleryImageEntity() }
                .sortedByDescending { it.createdAt }
        }

    suspend fun list(folder: String = FileFolders.UPLOAD): List<ManagedFileEntity> =
        filterFolderEntities(folder, repository.listByFolder(folder).first())

    private fun filterFolderEntities(folder: String, list: List<ManagedFileEntity>): List<ManagedFileEntity> =
        when (folder) {
            FileFolders.IMAGES -> list.filterNot { isLlmPreviewPath(it.relativePath) || isLlmPreviewPath(it.displayName) }
            else -> list
        }

    private fun isLlmPreviewPath(path: String): Boolean =
        path.endsWith("_llm_preview.jpg", ignoreCase = true)

    suspend fun get(id: String): ManagedFileEntity? = repository.getById(id)

    /** 重命名（只改中文名；物理文件名恒为 UUID，避免破坏历史 file:// 引用与同步身份） */
    suspend fun rename(id: String, nameZh: String?) = repository.rename(id, nameZh)

    /** OCR 结构化结果回写 */
    suspend fun updateOcrResult(
        id: String,
        ocrText: String?,
        description: String?,
        nameZh: String?,
        nameEn: String?,
    ) = repository.updateOcrResult(id, ocrText, description, nameZh, nameEn)

    suspend fun getByRelativePath(relativePath: String): ManagedFileEntity? = repository.getByPath(relativePath)

    fun getFile(entity: ManagedFileEntity): File =
        if (entity.folder == FileFolders.TTS_CACHE) {
            val relative = if (entity.relativePath.startsWith("${FileFolders.TTS_CACHE}/")) {
                entity.relativePath
            } else {
                "${FileFolders.TTS_CACHE}/${entity.relativePath}"
            }
            File(context.cacheDir, relative)
        } else {
            File(AppPaths.filesDir(context), entity.relativePath)
        }

    fun createChatFilesByContents(uris: List<Uri>, asAsset: Boolean = false): List<Uri> =
        createFilesByContents(folder = FileFolders.UPLOAD, uris = uris, logTag = "createChatFilesByContents", asAsset = asAsset)

    fun createAvatarFilesByContents(uris: List<Uri>): List<Uri> =
        createFilesByContents(folder = FileFolders.AVATARS, uris = uris, logTag = "createAvatarFilesByContents")

    private fun createFilesByContents(
        folder: String,
        uris: List<Uri>,
        logTag: String,
        asAsset: Boolean = false,
    ): List<Uri> {
        val newUris = mutableListOf<Uri>()
        val dir = AppPaths.filesDir(context).resolve(folder)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        uris.forEach { uri ->
            runCatching {
                val sourceName = getFileNameFromUri(uri) ?: uri.lastPathSegment ?: "file"
                val sourceMime = getFileMimeType(uri)
                val fileName = buildUuidFileName(displayName = sourceName, mimeType = sourceMime)
                val file = dir.resolve(fileName)
                val sourceSize = getUriSize(uri)
                val sourceHash = sha256OfUri(uri)
                val duplicate = sourceHash?.let { hash -> findDuplicateFileByHash(folder, sourceSize, hash) }
                if (duplicate != null && createHardLinkOrNull(source = duplicate, target = file) != null) {
                    Log.i(TAG, "$logTag: reuse duplicate file by sha256 ${duplicate.name} -> ${file.name}")
                } else {
                    if (!file.exists()) {
                        file.createNewFile()
                    }
                    val inputStream = context.contentResolver.openInputStream(uri)
                        ?: error("Failed to open input stream for $uri")
                    inputStream.use { input ->
                        file.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    deduplicateWrittenFile(file, folder)
                }
                val guessedMime = sourceMime ?: guessMimeType(file, sourceName)
                // 发送前附件只复制成本地临时文件（file://），不登记托管资产：
                // asset id / OCR 缓存 / R2 上传统一在发送时由 AssetResolver.indexPartForStorage 落库。
                // asAsset=true 仅用于少数需要创建即登记的调用方。
                val entity = if (asAsset) {
                    trackManagedFile(
                        folder = folder,
                        file = file,
                        displayName = sourceName,
                        mimeType = guessedMime,
                    )
                } else {
                    null
                }
                newUris.add(
                    if (asAsset && entity != null) AssetUri.fromId(entity.id).toUri()
                    else file.toUri()
                )
            }.onFailure {
                it.printStackTrace()
                Log.e(TAG, "$logTag: Failed to save file from $uri", it)
                Logging.log(
                    TAG,
                    "$logTag: Failed to save file from $uri ${it.message} | ${it.stackTraceToString()}"
                )
            }
        }
        return newUris
    }


    fun createChatImageFilesByContents(uris: List<Uri>, compress: Boolean): List<Uri> {
        if (!compress) return createChatFilesByContents(uris)
        val result = mutableListOf<Uri>()
        uris.forEach { uri ->
            val file = runCatching { createCompressedChatImageFile(uri) }
                .onFailure {
                    Log.e(TAG, "createChatImageFilesByContents: compression failed for $uri", it)
                }
                .getOrNull()
            if (file != null) {
                result.add(file.toUri())
            } else {
                result.addAll(createChatFilesByContents(listOf(uri)))
            }
        }
        return result
    }

    private fun createCompressedChatImageFile(uri: Uri): File? {
        val sourceName = getFileNameFromUri(uri) ?: uri.lastPathSegment ?: "image"
        val sourceMime = getFileMimeType(uri)
        if (sourceMime?.startsWith("image/") == false) return null
        if (sourceMime?.contains("gif", ignoreCase = true) == true) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sourceSize = getUriSize(uri)
        val compressSetting = settingsStore.settingsFlow.value.fileCompressSetting
        val skipBytes = compressSetting.chatImageSkipBytes.coerceAtLeast(0L)
        val jpegQuality = compressSetting.chatImageJpegQuality.coerceIn(1, 100)
        val maxEdgeConfig = compressSetting.chatImageMaxEdge.coerceAtLeast(200)
        val currentMaxEdge = max(bounds.outWidth, bounds.outHeight)
        if (sourceSize in 1L until skipBytes && currentMaxEdge <= maxEdgeConfig) {
            return null
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = ImageUtils.calculateInSampleSize(bounds, maxEdgeConfig, maxEdgeConfig)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        } ?: return null
        val oriented = ImageUtils.correctImageOrientation(context, uri, decoded)
        val resized = resizeBitmapIfNeeded(oriented, maxEdgeConfig)
        val jpegBitmap = drawBitmapOnWhiteBackground(resized)

        val dir = AppPaths.filesDir(context).resolve(FileFolders.UPLOAD)
        if (!dir.exists()) dir.mkdirs()
        val compressedDisplayName = sourceName.substringBeforeLast('.', sourceName) + "_compressed.jpg"
        val file = dir.resolve(buildUuidFileName(displayName = compressedDisplayName, mimeType = "image/jpeg"))
        file.outputStream().use { output ->
            jpegBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)
        }
        deduplicateWrittenFile(file, FileFolders.UPLOAD)
        // 压缩产物同样只做本地临时文件，发送时统一索引成资产。
        if (jpegBitmap != resized) ImageUtils.recycleBitmapSafely(jpegBitmap)
        if (resized != oriented) ImageUtils.recycleBitmapSafely(resized)
        ImageUtils.recycleBitmapSafely(oriented)
        return file
    }

    private fun resizeBitmapIfNeeded(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val currentMaxEdge = max(bitmap.width, bitmap.height)
        if (currentMaxEdge <= maxEdge) return bitmap
        val scale = maxEdge / currentMaxEdge.toFloat()
        val targetWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun drawBitmapOnWhiteBackground(bitmap: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return output
    }

    private fun getUriSize(uri: Uri): Long {
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)
    }

    private fun findDuplicateFileByHash(folder: String, sizeBytes: Long, sha256: String, exclude: File? = null): File? {
        val dir = File(AppPaths.filesDir(context), folder)
        if (!dir.exists()) return null
        return dir.listFiles()
            ?.asSequence()
            ?.filter { file -> file.isFile && file.absolutePath != exclude?.absolutePath && (sizeBytes <= 0L || file.length() == sizeBytes) }
            ?.firstOrNull { file -> sha256OfFile(file) == sha256 }
    }

    private fun deduplicateWrittenFile(file: File, folder: String) {
        val sha256 = sha256OfFile(file) ?: return
        val duplicate = findDuplicateFileByHash(folder, file.length(), sha256, exclude = file) ?: return
        val temp = File(file.parentFile, "${file.name}.dedupe_tmp")
        runCatching {
            if (!file.renameTo(temp)) return
            if (createHardLinkOrNull(source = duplicate, target = file) != null) {
                temp.delete()
                Log.i(TAG, "deduplicateWrittenFile: hard-linked ${file.name} to existing ${duplicate.name}")
            } else {
                temp.renameTo(file)
            }
        }.onFailure {
            if (!file.exists() && temp.exists()) temp.renameTo(file)
            Log.w(TAG, "deduplicateWrittenFile: failed for ${file.name}", it)
        }
    }

    private fun createHardLinkOrNull(source: File, target: File): File? {
        return runCatching {
            if (target.exists()) target.delete()
            Os.link(source.absolutePath, target.absolutePath)
            target.takeIf { it.exists() }
        }.getOrNull()
    }

    private fun sha256OfUri(uri: Uri): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        } ?: return@runCatching null
        digest.digest().toHexString()
    }.getOrNull()

    private fun sha256OfFile(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().toHexString()
    }.getOrNull()

    private fun ByteArray.toHexString(): String = joinToString("") { byte -> "%02x".format(byte) }

    fun createChatFilesByByteArrays(byteArrays: List<ByteArray>): List<Uri> {
        val newUris = mutableListOf<Uri>()
        val dir = AppPaths.filesDir(context).resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        byteArrays.forEach { byteArray ->
            val fileName = buildUuidFileName(displayName = "image.png", mimeType = "image/png")
            val file = dir.resolve(fileName)
            if (!file.exists()) {
                file.createNewFile()
            }
            val newUri = file.toUri()
            file.outputStream().use { outputStream ->
                outputStream.write(byteArray)
            }
            trackManagedFile(
                folder = FileFolders.UPLOAD,
                file = file,
                displayName = "image.png",
                mimeType = "image/png"
            )
            newUris.add(newUri)
        }
        return newUris
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun convertBase64ImagePartToLocalFile(message: UIMessage): UIMessage =
        withContext(Dispatchers.IO) {
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Image -> {
                            if (part.url.startsWith("data:image")) {
                                val sourceByteArray = Base64.decode(part.url.substringAfter("base64,").toByteArray())
                                val bitmap = BitmapFactory.decodeByteArray(sourceByteArray, 0, sourceByteArray.size)
                                val byteArray = FileUtils.compressBitmapToPng(bitmap)
                                val urls = createChatFilesByByteArrays(listOf(byteArray))
                                Log.i(
                                    TAG,
                                    "convertBase64ImagePartToLocalFile: convert base64 img to ${urls.joinToString(", ")}"
                                )
                                part.copy(
                                    url = urls.first().toString(),
                                )
                            } else {
                                part
                            }
                        }

                        else -> part
                    }
                }
            )
        }

    fun migrateAvatarsToAvatarFolder(settings: Settings): Settings {
        val migratedUserAvatar = migrateAvatarToAvatarFolder(settings.displaySetting.userAvatar)
        var changed = migratedUserAvatar != settings.displaySetting.userAvatar
        val migratedAssistants = settings.assistants.map { assistant ->
            val migratedAvatar = migrateAvatarToAvatarFolder(assistant.avatar)
            if (migratedAvatar != assistant.avatar) {
                changed = true
                assistant.copy(avatar = migratedAvatar)
            } else {
                assistant
            }
        }
        return if (changed) {
            settings.copy(
                displaySetting = settings.displaySetting.copy(userAvatar = migratedUserAvatar),
                assistants = migratedAssistants,
            )
        } else {
            settings
        }
    }

    private fun migrateAvatarToAvatarFolder(avatar: Avatar): Avatar {
        if (avatar !is Avatar.Image) return avatar
        val source = resolveLocalAvatarSourceFile(avatar.url) ?: return avatar
        val avatarsDir = File(AppPaths.filesDir(context), FileFolders.AVATARS).apply { mkdirs() }
        if (runCatching { source.canonicalFile.parentFile == avatarsDir.canonicalFile }.getOrDefault(false)) {
            return Avatar.Image(source.toUri().toString())
        }
        val displayName = source.name.ifBlank { "avatar.png" }
        val mimeType = guessMimeType(source, displayName)
        val target = createTargetFile(FileFolders.AVATARS, displayName, mimeType)
        val duplicate = runCatching { sha256OfFile(source) }.getOrNull()?.let { hash ->
            findDuplicateFileByHash(FileFolders.AVATARS, source.length(), hash)
        }
        if (duplicate != null && createHardLinkOrNull(duplicate, target) != null) {
            Log.i(TAG, "migrateAvatarToAvatarFolder: reuse duplicate avatar ${duplicate.name} -> ${target.name}")
        } else {
            source.copyTo(target, overwrite = true)
            deduplicateWrittenFile(target, FileFolders.AVATARS)
        }
        trackManagedFile(
            folder = FileFolders.AVATARS,
            file = target,
            displayName = displayName,
            mimeType = mimeType,
        )
        return Avatar.Image(target.toUri().toString())
    }

    private fun resolveLocalAvatarSourceFile(url: String): File? {
        val value = url.trim()
        if (value.isBlank()) return null
        val uri = runCatching { value.toUri() }.getOrNull()
        if (uri?.scheme == "http" || uri?.scheme == "https" || uri?.scheme == "content") return null
        val directFile = when {
            uri?.scheme == "file" -> runCatching { uri.toFile() }.getOrNull()
            uri?.scheme.isNullOrBlank() && value.startsWith(File.separator) -> File(value)
            else -> null
        }
        if (directFile?.isFile == true) return directFile

        fun candidate(name: String?): File? {
            val fileName = name?.takeIf { it.isNotBlank() } ?: return null
            return listOf(
                File(AppPaths.filesDir(context), "${FileFolders.AVATARS}/$fileName"),
                File(AppPaths.filesDir(context), "${FileFolders.UPLOAD}/$fileName"),
            ).firstOrNull { it.isFile }
        }
        return candidate(directFile?.name)
            ?: candidate(uri?.lastPathSegment)
            ?: candidate(value.substringAfterLast('/'))
    }

    fun deleteChatFiles(uris: List<Uri>) {
        val relativePaths = mutableSetOf<String>()
        uris.filter { it.toString().startsWith("file:") }.forEach { uri ->
            val file = uri.toFile()
            getRelativePathInFilesDir(file)?.let { relativePaths.add(it) }
            if (file.exists()) {
                file.delete()
            }
        }
        if (relativePaths.isNotEmpty()) {
            appScope.launch(Dispatchers.IO) {
                relativePaths.forEach { path ->
                    repository.deleteByPath(path)
                }
            }
        }
    }

    suspend fun countChatFiles(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val folders = listOf(FileFolders.UPLOAD, FileFolders.AI_READ_IMAGES, FileFolders.IMAGES, FileFolders.LLM_PREVIEWS, FileFolders.AVATARS, FileFolders.TTS_CACHE)
        var count = 0
        var size = 0L
        val diskRelativePaths = HashSet<String>()
        folders.forEach { folder ->
            val dir = if (folder == FileFolders.TTS_CACHE) {
                File(context.cacheDir, "tts_cache")
            } else {
                File(AppPaths.filesDir(context), folder)
            }
            dir.listFiles()?.filter { it.isFile }?.forEach { file ->
                count += 1
                size += file.length()
                diskRelativePaths += if (folder == FileFolders.TTS_CACHE) {
                    "${FileFolders.TTS_CACHE}/${file.name}"
                } else {
                    "$folder/${file.name}"
                }
            }
        }
        folders.forEach { folder ->
            repository.listByFolder(folder).first().forEach { entity ->
                val localFile = getFile(entity)
                val hasLocalDiskCounted = entity.relativePath in diskRelativePaths || localFile.isFile
                if (!hasLocalDiskCounted && (entity.relativePath.isRemoteUrl() || entity.hasCloudCopy() || !entity.externalUrl.isNullOrBlank() || entity.relativePath.startsWith("remote/"))) {
                    count += 1
                    size += when {
                        entity.sizeBytes > 0 -> entity.sizeBytes
                        !entity.externalUrl.isNullOrBlank() -> entity.externalUrl.toByteArray(Charsets.UTF_8).size.toLong()
                        entity.relativePath.isRemoteUrl() -> entity.relativePath.toByteArray(Charsets.UTF_8).size.toLong()
                        else -> 0L
                    }
                }
            }
        }
        Pair(count, size)
    }

    fun trackRemoteUrl(
        folder: String = FileFolders.UPLOAD,
        url: String,
        displayName: String = url.substringBefore('?').substringBefore('#').substringAfterLast('/').ifBlank { "URL" },
        mimeType: String = "image/url",
    ) {
        appScope.launch(Dispatchers.IO) {
            runCatching {
                val now = System.currentTimeMillis()
                val existing = repository.getByPath(url)
                if (existing != null) return@runCatching
                repository.insert(
                    ManagedFileEntity(
                        folder = folder,
                        relativePath = url,
                        displayName = displayName,
                        mimeType = mimeType,
                        sizeBytes = url.toByteArray(Charsets.UTF_8).size.toLong(),
                        createdAt = now,
                        updatedAt = now,
                    )
                )
            }.onFailure {
                Log.e(TAG, "trackRemoteUrl: Failed to track url $url", it)
            }
        }
    }

    fun createChatTextFile(text: String): UIMessagePart.Document {
        val dir = AppPaths.filesDir(context).resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val fileName = buildUuidFileName(displayName = "pasted_text.txt", mimeType = "text/plain")
        val file = dir.resolve(fileName)
        file.writeText(text)
        // 发送前不登记资产，发送时统一索引。
        return UIMessagePart.Document(
            url = file.toUri().toString(),
            fileName = "pasted_text.txt",
            mime = "text/plain"
        )
    }

    fun getImagesDir(): File {
        val dir = AppPaths.filesDir(context).resolve(FileFolders.IMAGES)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getLlmPreviewsDir(): File {
        val dir = AppPaths.filesDir(context).resolve(FileFolders.LLM_PREVIEWS)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun createImageFileFromBase64(base64Data: String, filePath: String): File {
        val data = if (base64Data.startsWith("data:image")) {
            base64Data.substringAfter("base64,")
        } else {
            base64Data
        }

        val byteArray = Base64.decode(data.toByteArray())
        val file = File(filePath)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArray)
        return file
    }


    fun createLlmPreviewImageBytes(
        source: File,
        maxEdge: Int = -1,
        jpegQuality: Int = -1,
        skipBytes: Long = -1L,
    ): ByteArray? {
        if (!source.isFile) return null
        if (source.extension.lowercase() == "gif") return source.readBytes()

        val compressSetting = settingsStore.settingsFlow.value.fileCompressSetting
        val targetMaxEdge = if (maxEdge > 0) maxEdge else compressSetting.llmPreviewMaxEdge
        val targetQuality = if (jpegQuality > 0) jpegQuality else compressSetting.llmPreviewJpegQuality
        val targetSkipBytes = if (skipBytes >= 0) skipBytes else compressSetting.llmPreviewSkipBytes

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val currentMaxEdge = max(bounds.outWidth, bounds.outHeight)
        if (source.length() in 1L until targetSkipBytes && currentMaxEdge <= targetMaxEdge) {
            return source.readBytes()
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = ImageUtils.calculateInSampleSize(bounds, targetMaxEdge, targetMaxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(source.absolutePath, decodeOptions) ?: return null
        val resized = resizeBitmapIfNeeded(decoded, targetMaxEdge)
        val jpegBitmap = drawBitmapOnWhiteBackground(resized)
        return try {
            ByteArrayOutputStream().use { output ->
                jpegBitmap.compress(Bitmap.CompressFormat.JPEG, targetQuality.coerceIn(1, 100), output)
                output.toByteArray()
            }
        } finally {
            if (jpegBitmap != resized) ImageUtils.recycleBitmapSafely(jpegBitmap)
            if (resized != decoded) ImageUtils.recycleBitmapSafely(resized)
            ImageUtils.recycleBitmapSafely(decoded)
        }
    }

    fun createManualCompressBytes(
        source: File,
        maxEdge: Int = -1,
        jpegQuality: Int = -1,
        skipBytes: Long = -1L,
    ): ByteArray? {
        if (!source.isFile) return null
        if (source.extension.lowercase() == "gif") return source.readBytes()

        val compressSetting = settingsStore.settingsFlow.value.fileCompressSetting
        val targetMaxEdge = if (maxEdge > 0) maxEdge else compressSetting.manualCompressMaxEdge
        val targetQuality = if (jpegQuality > 0) jpegQuality else compressSetting.manualCompressJpegQuality
        val targetSkipBytes = if (skipBytes >= 0) skipBytes else compressSetting.manualCompressSkipBytes

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val currentMaxEdge = max(bounds.outWidth, bounds.outHeight)
        if (source.length() in 1L until targetSkipBytes && currentMaxEdge <= targetMaxEdge) {
            return source.readBytes()
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = ImageUtils.calculateInSampleSize(bounds, targetMaxEdge, targetMaxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(source.absolutePath, decodeOptions) ?: return null
        val resized = resizeBitmapIfNeeded(decoded, targetMaxEdge)
        val jpegBitmap = drawBitmapOnWhiteBackground(resized)
        return try {
            ByteArrayOutputStream().use { output ->
                jpegBitmap.compress(Bitmap.CompressFormat.JPEG, targetQuality.coerceIn(1, 100), output)
                output.toByteArray()
            }
        } finally {
            if (jpegBitmap != resized) ImageUtils.recycleBitmapSafely(jpegBitmap)
            if (resized != decoded) ImageUtils.recycleBitmapSafely(resized)
            ImageUtils.recycleBitmapSafely(decoded)
        }
    }

    fun createLlmPreviewImageFile(
        source: File,
        maxEdge: Int = -1,
        jpegQuality: Int = -1,
        skipBytes: Long = -1L,
    ): File? {
        if (!source.isFile) return null
        if (source.extension.lowercase() == "gif") return null

        val compressSetting = settingsStore.settingsFlow.value.fileCompressSetting
        val targetMaxEdge = if (maxEdge > 0) maxEdge else compressSetting.llmPreviewMaxEdge
        val targetQuality = if (jpegQuality > 0) jpegQuality else compressSetting.llmPreviewJpegQuality
        val targetSkipBytes = if (skipBytes >= 0) skipBytes else compressSetting.llmPreviewSkipBytes

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val currentMaxEdge = max(bounds.outWidth, bounds.outHeight)
        if (source.length() in 1L until targetSkipBytes && currentMaxEdge <= targetMaxEdge) {
            return source
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = ImageUtils.calculateInSampleSize(bounds, targetMaxEdge, targetMaxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(source.absolutePath, decodeOptions) ?: return null
        val resized = resizeBitmapIfNeeded(decoded, targetMaxEdge)
        val jpegBitmap = drawBitmapOnWhiteBackground(resized)

        val file = getLlmPreviewsDir().resolve(
            buildUuidFileName(
                displayName = source.nameWithoutExtension + "_llm_preview.jpg",
                mimeType = "image/jpeg",
            )
        )
        file.outputStream().use { output ->
            jpegBitmap.compress(Bitmap.CompressFormat.JPEG, targetQuality.coerceIn(1, 100), output)
        }
        deduplicateWrittenFile(file, FileFolders.LLM_PREVIEWS)
        trackManagedFile(
            folder = FileFolders.LLM_PREVIEWS,
            file = file,
            displayName = source.nameWithoutExtension + "_llm_preview.jpg",
            mimeType = "image/jpeg",
        )
        if (jpegBitmap != resized) ImageUtils.recycleBitmapSafely(jpegBitmap)
        if (resized != decoded) ImageUtils.recycleBitmapSafely(resized)
        ImageUtils.recycleBitmapSafely(decoded)
        return file
    }

    fun listImageFiles(): List<File> {
        val imagesDir = getImagesDir()
        return imagesDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp") }
            ?.toList()
            ?: emptyList()
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun saveMessageImage(activityContext: Context, image: String) = withContext(Dispatchers.IO) {
        val activity = requireNotNull(activityContext.getActivity()) { "Activity not found" }
        when {
            image.startsWith("data:image") -> {
                val byteArray = Base64.decode(image.substringAfter("base64,").toByteArray())
                val bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                activityContext.exportImage(activity, bitmap)
            }

            image.startsWith("file:") -> {
                val file = image.toUri().toFile()
                activityContext.exportImageFile(activity, file)
            }

            image.startsWith("/") -> {
                activityContext.exportImageFile(activity, File(image))
            }

            image.startsWith("http") -> {
                runCatching {
                    val url = URL(image)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connect()

                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val bitmap = BitmapFactory.decodeStream(connection.inputStream)
                        activityContext.exportImage(activity, bitmap)
                    } else {
                        Log.e(
                            TAG,
                            "saveMessageImage: Failed to download image from $image, response code: ${connection.responseCode}"
                        )
                    }
                }.getOrNull()
            }

            else -> error("Invalid image format")
        }
    }

    suspend fun syncFolder(folder: String = FileFolders.UPLOAD): SyncResult = withContext(Dispatchers.IO) {
        if (folder == FileFolders.LLM_PREVIEWS) {
            migrateLegacyLlmPreviews()
        }

        val dir = if (folder == FileFolders.TTS_CACHE) {
            File(context.cacheDir, "tts_cache")
        } else {
            File(AppPaths.filesDir(context), folder)
        }
        val diskFiles = if (dir.exists()) {
            dir.listFiles()?.filter { file ->
                file.isFile && (folder != FileFolders.IMAGES || !isLlmPreviewPath(file.name))
            } ?: return@withContext SyncResult(inserted = 0, removed = 0)
        } else {
            emptyList()
        }

        // 磁盘 -> 数据库：补录尚未登记的文件
        var inserted = 0
        val diskRelativePaths = HashSet<String>()
        diskFiles.forEach { file ->
            val relativePath = "${folder}/${file.name}"
            diskRelativePaths.add(relativePath)
            val existing = repository.getByPath(relativePath)
            if (existing == null) {
                val now = System.currentTimeMillis()
                val displayName = file.name
                val mimeType = guessMimeType(file, displayName)
                repository.insert(
                    ManagedFileEntity(
                        folder = folder,
                        relativePath = relativePath,
                        displayName = displayName,
                        mimeType = mimeType,
                        sizeBytes = file.length(),
                        createdAt = file.lastModified().takeIf { it > 0 } ?: now,
                        updatedAt = now,
                    )
                )
                inserted += 1
            }
        }

        // 文件管理只展示仍可管理的文件：本地缓存已不存在且没有云端引用的索引行，
        // 对聊天历史没有帮助，会让文件管理里堆满“不可用”占位，直接清掉即可。
        //
        // 但「有 sha256」= 这行曾经真的落过盘、并且已入队 R2 上传（AssetResolver.enqueueCloudUpload）。
        // 这种行只是「云副本还没补上」，不是垃圾索引：一旦删掉，聊天记录 / 相册里的
        // asset://managed-files/<uuid> 立刻变死链，AI 读图与相册全空。
        // ai_read_image 就是这么被清空的（13 条全灭）——它只落本地、R2 靠后台异步补，
        // 每次启动 syncManagedFiles() 都会撞上这个条件。保留索引 + 重新入队补云副本才对。
        var removed = 0
        repository.listByFolder(folder).first().forEach { entity ->
            if (folder == FileFolders.IMAGES && (isLlmPreviewPath(entity.displayName) || isLlmPreviewPath(entity.relativePath))) {
                repository.update(
                    entity.copy(
                        folder = FileFolders.LLM_PREVIEWS,
                        relativePath = "${FileFolders.LLM_PREVIEWS}/${File(entity.relativePath).name}"
                    )
                )
                return@forEach
            }
            val localMissing = !entity.relativePath.isRemoteUrl() &&
                entity.relativePath !in diskRelativePaths &&
                !getFile(entity).isFile
            if (!localMissing || entity.hasCloudCopy()) return@forEach
            // 曾成功落盘过的资产：保住索引，顺手重试云备份，等 R2 补齐后就能重新解析。
            if (!entity.sha256.isNullOrBlank() || !entity.contentSha256.isNullOrBlank()) {
                repository.enqueueCloudUpload(entity.id)
                return@forEach
            }
            removed += repository.deleteByPath(entity.relativePath)
        }

        SyncResult(inserted = inserted, removed = removed)
    }

    private suspend fun migrateLegacyLlmPreviews() {
        val imagesDir = File(AppPaths.filesDir(context), FileFolders.IMAGES)
        if (!imagesDir.exists()) return
        val llmDir = getLlmPreviewsDir()
        imagesDir.listFiles()?.filter { it.isFile && isLlmPreviewPath(it.name) }?.forEach { legacyFile ->
            val targetFile = llmDir.resolve(legacyFile.name)
            val moved = if (targetFile.exists()) {
                legacyFile.delete()
                true
            } else {
                legacyFile.renameTo(targetFile)
            }
            if (moved) {
                val oldRelative = "${FileFolders.IMAGES}/${legacyFile.name}"
                val newRelative = "${FileFolders.LLM_PREVIEWS}/${legacyFile.name}"
                val existing = repository.getByPath(oldRelative)
                if (existing != null) {
                    repository.update(
                        existing.copy(
                            folder = FileFolders.LLM_PREVIEWS,
                            relativePath = newRelative,
                        )
                    )
                }
            }
        }
    }

    suspend fun deleteLocalCache(id: String): Boolean = withContext(Dispatchers.IO) {
        val entity = repository.getById(id) ?: return@withContext false
        if (!entity.relativePath.isRemoteUrl() && !entity.relativePath.startsWith("remote/")) {
            runCatching { getFile(entity).delete() }
        }
        val now = System.currentTimeMillis()
        if (!entity.hasCloudCopy() && entity.externalUrl.isNullOrBlank()) {
            markAssetDeleted(entity, now)
        } else {
            repository.update(entity.copy(updatedAt = now))
        }
        true
    }

    suspend fun setCloudCopy(id: String, r2Key: String, r2Acct: String): Boolean = withContext(Dispatchers.IO) {
        val entity = repository.getById(id) ?: return@withContext false
        repository.update(entity.copy(r2Key = r2Key, r2Acct = r2Acct, updatedAt = System.currentTimeMillis()))
        true
    }

    suspend fun replaceLocalCache(id: String, bytes: ByteArray, mimeType: String? = null): ManagedFileEntity? = withContext(Dispatchers.IO) {
        val entity = repository.getById(id) ?: return@withContext null
        val file = getFile(entity)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        val updated = entity.copy(
            mimeType = mimeType ?: entity.mimeType,
            sizeBytes = bytes.size.toLong(),
            updatedAt = System.currentTimeMillis(),
        )
        repository.update(updated)
        updated
    }

    suspend fun restoreLocalCache(id: String, bytes: ByteArray): Boolean =
        replaceLocalCache(id, bytes) != null

    suspend fun deleteAllLocalCache(folder: String = FileFolders.UPLOAD): Boolean = withContext(Dispatchers.IO) {
        val dir = if (folder == FileFolders.TTS_CACHE) {
            File(context.cacheDir, "tts_cache")
        } else {
            File(AppPaths.filesDir(context), folder)
        }
        val entries = dir.listFiles()
        if (dir.exists() && entries == null) {
            return@withContext false
        }
        val allDeleted = entries.orEmpty().all { entry ->
            runCatching { entry.deleteRecursively() }.getOrDefault(false)
        }
        repository.listByFolder(folder).first().forEach { entity ->
            if (!entity.hasCloudCopy() && entity.externalUrl.isNullOrBlank() && !entity.relativePath.isRemoteUrl()) {
                markAssetDeleted(entity)
            }
        }
        allDeleted
    }

    suspend fun delete(id: String, deleteFromDisk: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val entity = repository.getById(id) ?: return@withContext false
        if (deleteFromDisk && !entity.relativePath.isRemoteUrl() && !entity.relativePath.startsWith("remote/")) {
            runCatching { getFile(entity).delete() }
        }
        markAssetDeleted(entity)
        true
    }

    suspend fun deleteAll(folder: String = FileFolders.UPLOAD): Boolean = withContext(Dispatchers.IO) {
        val dir = if (folder == FileFolders.TTS_CACHE) {
            File(context.cacheDir, "tts_cache")
        } else {
            File(AppPaths.filesDir(context), folder)
        }
        val entries = dir.listFiles()
        if (dir.exists() && entries == null) {
            return@withContext false
        }

        var allDeletedFromDisk = true
        entries.orEmpty().forEach { entry ->
            if (!runCatching { entry.deleteRecursively() }.getOrDefault(false)) {
                allDeletedFromDisk = false
            }
        }

        val rows = repository.listByFolder(folder).first()
        if (allDeletedFromDisk) {
            rows.forEach { entity -> markAssetDeleted(entity) }
            return@withContext true
        }

        rows.forEach { entity ->
            if (!getFile(entity).exists()) {
                markAssetDeleted(entity)
            }
        }
        false
    }

    private suspend fun markAssetDeleted(entity: ManagedFileEntity, now: Long = System.currentTimeMillis()) {
        repository.update(
            entity.copy(
                deleted = true,
                r2Key = null,
                r2Acct = null,
                externalUrl = null,
                updatedAt = now,
            )
        )
    }

    private fun createTargetFile(folder: String, displayName: String, mimeType: String?): File {
        val dir = File(AppPaths.filesDir(context), folder)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, FileUtils.buildUuidFileName(displayName = displayName, mimeType = mimeType))
    }

    private fun buildUuidFileName(displayName: String?, mimeType: String?): String =
        FileUtils.buildUuidFileName(displayName, mimeType)

    private suspend fun createManagedFileEntity(
        folder: String,
        file: File,
        displayName: String,
        mimeType: String,
    ): ManagedFileEntity {
        val now = System.currentTimeMillis()
        return repository.insert(
            ManagedFileEntity(
                folder = folder,
                relativePath = buildRelativePath(folder, file),
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = file.length(),
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    /**
     * 登记托管资产并**同步**返回实体（含 Asset ID）。
     *
     * 消息附件在创建瞬间就要绑定 Asset ID（统一 asset:// 寻址），
     * 所以这里不能像以前那样 appScope 异步插入 —— 插入只是单条 SQL，
     * 用 runBlocking 同步完成，返回的 id 立即可用。
     */
    private fun trackManagedFile(
        folder: String,
        file: File,
        displayName: String,
        mimeType: String,
    ): ManagedFileEntity? = runBlocking(Dispatchers.IO) {
        runCatching {
            val relativePath = buildRelativePath(folder, file)
            val existing = repository.getByPath(relativePath)
            if (existing != null) {
                existing
            } else {
                repository.insert(
                    ManagedFileEntity(
                        folder = folder,
                        relativePath = relativePath,
                        displayName = displayName,
                        mimeType = mimeType,
                        sizeBytes = file.length(),
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
        }.onFailure {
            Log.e(TAG, "trackManagedFile: Failed to track file ${file.absolutePath}", it)
            Logging.log(
                TAG,
                "trackManagedFile: Failed to track file ${file.absolutePath} ${it.message} | ${it.stackTraceToString()}"
            )
        }.getOrNull()
    }

    private fun buildRelativePath(folder: String, file: File): String =
        FileUtils.buildRelativePath(folder, file)

    private fun getRelativePathInFilesDir(file: File): String? =
        FileUtils.getRelativePathInFilesDir(AppPaths.filesDir(context), file)

    private fun ManagedFileEntity.hasCloudCopy(): Boolean =
        !r2Key.isNullOrBlank() && !r2Acct.isNullOrBlank()

    private fun String.isRemoteUrl(): Boolean =
        startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

internal fun ManagedFileEntity.isGalleryImageEntity(): Boolean =
    mimeType.startsWith("image/") &&
        !relativePath.endsWith("_llm_preview.jpg", ignoreCase = true) &&
        folder != FileFolders.LLM_PREVIEWS

    fun getFileNameFromUri(uri: Uri): String? =
        FileUtils.getFileNameFromUri(context, uri)

    fun getFileMimeType(uri: Uri): String? =
        FileUtils.getFileMimeType(context, uri)

    private fun guessMimeType(file: File, fileName: String): String =
        FileUtils.guessMimeType(file, fileName)
}

data class SyncResult(
    val inserted: Int,
    val removed: Int,
)

object FileFolders {
    const val UPLOAD = "upload"

    /**
     * AI 通过工具（workspace read_file / 绘图参考图）读取的本地图片。
     * 与 [UPLOAD]（用户在聊天页主动上传）分开，避免相册「上传」分类被 AI 读图污染。
     */
    const val AI_READ_IMAGES = "ai_read_image"
    const val AVATARS = "avatars"
    const val IMAGES = "images"
    const val LLM_PREVIEWS = "llm_previews"
    const val SKILLS = "skills"
    const val FONTS = "fonts"
    const val TOOL_OUTPUTS = "tool_outputs"
    const val TTS_CACHE = "tts_cache"
}

suspend fun FilesManager.saveUploadFromUri(
    uri: Uri,
    displayName: String? = null,
    mimeType: String? = null,
): ManagedFileEntity = saveManagedFromUri(
    folder = FileFolders.UPLOAD,
    uri = uri,
    displayName = displayName,
    mimeType = mimeType,
)

suspend fun FilesManager.saveUploadFromBytes(
    bytes: ByteArray,
    displayName: String,
    mimeType: String = "application/octet-stream",
): ManagedFileEntity = saveManagedFromBytes(
    folder = FileFolders.UPLOAD,
    bytes = bytes,
    displayName = displayName,
    mimeType = mimeType,
)

suspend fun FilesManager.saveUploadText(
    text: String,
    displayName: String = "pasted_text.txt",
    mimeType: String = "text/plain",
): ManagedFileEntity = saveManagedText(
    folder = FileFolders.UPLOAD,
    text = text,
    displayName = displayName,
    mimeType = mimeType,
)
