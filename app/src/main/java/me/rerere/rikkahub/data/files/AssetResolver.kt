package me.rerere.rikkahub.data.files

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.toImageDataUriOrRemote
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.MediaUploadOutboxEntity
import me.rerere.rikkahub.data.db.entity.SyncOutboxEntity
import me.rerere.rikkahub.data.sync.core.BUNDLE_MANAGED_FILES
import me.rerere.rikkahub.data.sync.core.SyncAdvancedConfigStore
import me.rerere.rikkahub.data.sync.r2.R2MediaStore
import me.rerere.rikkahub.data.sync.r2.R2Ref
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.uuid.Uuid

class AssetResolver(
    private val context: Context,
    private val database: AppDatabase,
    private val filesManager: FilesManager,
    private val r2MediaStore: R2MediaStore,
    private val appScope: AppScope,
    private val syncAdvancedConfigStore: SyncAdvancedConfigStore,
) {
    private val uploadProcessorRunning = AtomicBoolean(false)

    init {
        appScope.launch(Dispatchers.IO) { processCloudUploadOutbox() }
    }

    suspend fun createFromUri(
        uri: Uri,
        folder: String = FileFolders.UPLOAD,
        displayName: String? = null,
        mimeType: String? = null,
        prompt: String? = null,
        description: String? = null,
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val entity = filesManager.saveManagedFromUri(folder, uri, displayName, mimeType)
        val file = filesManager.getFile(entity)
        val sha = sha256(file.takeIf { it.isFile }?.readBytes())
        val updated = entity.copy(sha256 = sha, prompt = prompt, description = description)
        database.managedFileDao().update(updated)
        enqueueManagedFilesBundleSync()
        enqueueCloudUpload(updated)
        updated
    }

    suspend fun createFromLocalFileUri(
        uri: Uri,
        folder: String = FileFolders.UPLOAD,
        displayName: String? = null,
        mimeType: String? = null,
        prompt: String? = null,
        description: String? = null,
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val file = uri.toFile()
        val filesDir = context.filesDir.absolutePath + File.separator
        val relative = file.absolutePath.takeIf { it.startsWith(filesDir) }?.removePrefix(filesDir)
            ?: return@withContext createFromUri(uri, folder, displayName, mimeType, prompt, description)
        database.managedFileDao().getByPath(relative)?.takeIf { !it.deleted }?.let {
            val updated = it.copy(
                prompt = prompt ?: it.prompt,
                description = description ?: it.description,
                updatedAt = System.currentTimeMillis(),
            )
            database.managedFileDao().update(updated)
            enqueueManagedFilesBundleSync()
            enqueueCloudUpload(updated)
            return@withContext updated
        }
        val now = System.currentTimeMillis()
        val bytes = file.takeIf { it.isFile }?.readBytes()
        val entity = ManagedFileEntity(
            folder = folder,
            relativePath = relative,
            displayName = displayName ?: file.name,
            mimeType = mimeType ?: filesManager.getFileMimeType(uri) ?: "application/octet-stream",
            sizeBytes = file.length(),
            createdAt = now,
            updatedAt = now,
            sha256 = sha256(bytes),
            prompt = prompt,
            description = description,
        )
        val inserted = database.managedFileDao().insert(entity)
        val stored = if (inserted == -1L) {
            database.managedFileDao().getByPath(relative) ?: database.managedFileDao().getById(entity.id) ?: entity
        } else {
            entity
        }
        enqueueManagedFilesBundleSync()
        enqueueCloudUpload(stored)
        stored
    }

    suspend fun createFromBytes(
        bytes: ByteArray,
        displayName: String,
        mimeType: String,
        folder: String = FileFolders.UPLOAD,
        prompt: String? = null,
        description: String? = null,
        externalUrl: String? = null,
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val sha = sha256(bytes) ?: error("SHA-256 calculation failed")
        database.managedFileDao().getBySha256(sha)?.takeIf { !it.deleted }?.let { existing ->
            val updated = if (externalUrl != null && existing.externalUrl != externalUrl) {
                existing.copy(externalUrl = externalUrl).also { database.managedFileDao().update(it) }
            } else existing
            enqueueCloudUpload(updated)
            return@withContext updated
        }
        val entity = filesManager.saveManagedFromBytes(folder, bytes, displayName, mimeType)
        val updated = entity.copy(sha256 = sha, prompt = prompt, description = description, externalUrl = externalUrl)
        database.managedFileDao().update(updated)
        enqueueManagedFilesBundleSync()
        enqueueCloudUpload(updated)
        updated
    }

    suspend fun createFromExternalUrl(
        url: String,
        displayName: String = url.substringBefore('?').substringAfterLast('/').ifBlank { "URL" },
        mimeType: String = "image/url",
        prompt: String? = null,
        description: String? = null,
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        r2MediaStore.refFromConfiguredUrl(url)?.let { return@withContext createFromR2Ref(it, displayName, mimeType, externalUrl = url, prompt = prompt, description = description) }
        database.managedFileDao().getByExternalUrl(url)?.takeIf { !it.deleted }?.let {
            enqueueCloudUpload(it)
            return@withContext it
        }
        val now = System.currentTimeMillis()
        val relative = "remote/${Uuid.random()}"
        val entity = ManagedFileEntity(
            folder = FileFolders.UPLOAD,
            relativePath = relative,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = 0L,
            createdAt = now,
            updatedAt = now,
            externalUrl = url,
            prompt = prompt,
            description = description,
        )
        database.managedFileDao().insert(entity)
        enqueueManagedFilesBundleSync()
        enqueueCloudUpload(entity)
        entity
    }

    suspend fun createFromR2Ref(
        ref: R2Ref,
        displayName: String,
        mimeType: String,
        externalUrl: String? = null,
        prompt: String? = null,
        description: String? = null,
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        database.managedFileDao().getByR2Ref(ref.key, ref.acctId)?.takeIf { !it.deleted }?.let { return@withContext it }
        val now = System.currentTimeMillis()
        val entity = ManagedFileEntity(
            folder = folderForR2Key(ref.key),
            relativePath = "remote/${Uuid.random()}",
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = 0,
            createdAt = now,
            updatedAt = now,
            r2Key = ref.key,
            r2Acct = ref.acctId,
            externalUrl = externalUrl,
            prompt = prompt,
            description = description,
        )
        database.managedFileDao().insert(entity)
        enqueueManagedFilesBundleSync()
        entity
    }

    suspend fun partFromAsset(asset: ManagedFileEntity): UIMessagePart = when {
        asset.mimeType.startsWith("image/") || asset.mimeType == "image/url" -> UIMessagePart.Image(AssetUri.fromId(asset.id))
        asset.mimeType.startsWith("video/") -> UIMessagePart.Video(AssetUri.fromId(asset.id))
        asset.mimeType.startsWith("audio/") -> UIMessagePart.Audio(AssetUri.fromId(asset.id))
        else -> UIMessagePart.Document(AssetUri.fromId(asset.id), asset.displayName, asset.mimeType)
    }

    suspend fun indexPartForStorage(part: UIMessagePart): UIMessagePart? = when (part) {
        is UIMessagePart.Image -> {
            val detectedMime = part.metadata?.get("r2_mime")?.jsonPrimitive?.contentOrNull
                ?: runCatching { filesManager.getFileMimeType(part.url.toUri()) }.getOrNull()
                    ?.takeIf { it.startsWith("image/") }
                ?: "image/png"
            indexMediaUrl(
                url = part.url,
                displayName = "image${mimeToExt(detectedMime).ifBlank { ".png" }}",
                mimeType = detectedMime,
            )?.let { part.copy(url = AssetUri.fromId(it.id)) }
        }

        is UIMessagePart.Document -> indexMediaUrl(
            url = part.url,
            displayName = part.fileName,
            mimeType = part.mime,
        )?.let { part.copy(url = AssetUri.fromId(it.id)) }

        is UIMessagePart.Video -> indexMediaUrl(part.url, "video.mp4", "video/mp4")?.let { part.copy(url = AssetUri.fromId(it.id)) }

        is UIMessagePart.Audio -> indexMediaUrl(part.url, "audio.mp3", "audio/mpeg")?.let { part.copy(url = AssetUri.fromId(it.id)) }

        is UIMessagePart.Tool -> part.copy(output = part.output.mapNotNull { indexPartForStorage(it) })

        else -> part
    }

    private suspend fun indexMediaUrl(url: String, displayName: String, mimeType: String): ManagedFileEntity? {
        return when {
            AssetUri.isAsset(url) -> AssetUri.parse(url)?.let { database.managedFileDao().getById(it) }
            url.startsWith("data:image/", ignoreCase = true) -> {
                val header = url.substringBefore(',', missingDelimiterValue = "")
                val base64 = url.substringAfter(',', missingDelimiterValue = "")
                val mime = header.removePrefix("data:").substringBefore(';').takeIf { it.startsWith("image/") } ?: mimeType
                val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return null
                createFromBytes(bytes, displayName.ifBlank { "image${mimeToExt(mime)}" }, mime, FileFolders.UPLOAD)
            }
            url.startsWith("file://", ignoreCase = true) ->
                createFromLocalFileUri(url.toUri(), FileFolders.UPLOAD, displayName, mimeType)
            url.startsWith("content://", ignoreCase = true) ->
                createFromUri(url.toUri(), FileFolders.UPLOAD, displayName, mimeType)
            url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) ->
                createFromExternalUrl(url, displayName, mimeType)
            else -> R2Ref.parse(url)?.let { createFromR2Ref(it, displayName, mimeType) }
        }
    }

    suspend fun resolveForDisplay(url: String): String? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        val assetId = AssetUri.parse(url)
        if (assetId != null) {
            val asset = database.managedFileDao().getById(assetId)?.takeUnless { it.deleted }
            if (asset != null) {
                return@withContext localFile(asset)?.takeIf { it.isFile }?.toUri()?.toString()
                    ?: asset.r2Ref()?.let { r2MediaStore.presign(it).getOrNull() }
                    ?: asset.externalUrl
            }
        }

        if (url.startsWith("file://", ignoreCase = true)) {
            val file = runCatching { url.toUri().toFile() }.getOrNull()
            if (file != null && file.isFile) {
                runCatching { createFromLocalFileUri(url.toUri()) }
                return@withContext file.toUri().toString()
            }
        }

        if (url.startsWith("content://", ignoreCase = true)) {
            runCatching { createFromUri(url.toUri()) }
            return@withContext url
        }

        if (url.startsWith("data:", ignoreCase = true) ||
            url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
        ) {
            return@withContext url
        }

        if (url.startsWith("r2://", ignoreCase = true)) {
            val ref = R2Ref.parse(url)
            if (ref != null) {
                return@withContext r2MediaStore.presign(ref).getOrNull()
            }
        }

        val directFile = File(url)
        if (directFile.isFile) {
            return@withContext directFile.toUri().toString()
        }

        null
    }

    suspend fun getOcrText(assetId: String): String? = withContext(Dispatchers.IO) {
        database.managedFileDao().getOcrText(assetId)?.takeIf { it.isNotBlank() }
    }

    /**
     * 压缩/覆写资产物理内容：
     * 1. 保持 Asset UUID (id) 恒定不变，确保聊天记录与 Markdown 引用不破坏死链。
     * 2. 物理删除云端旧 R2 对象 (防止存储堆积)。
     * 3. 物理删除 App 私有私有私有目录下的旧未压缩文件 (不触碰系统相册/公共存储)。
     * 4. 替换保存新压缩字节文件，清空 externalUrl，重置 R2 状态并重新入列后台上传队列。
     */
    suspend fun compressAssetContent(
        assetId: String,
        newBytes: ByteArray,
        newMimeType: String = "image/jpeg",
    ): ManagedFileEntity? = withContext(Dispatchers.IO) {
        val existing = database.managedFileDao().getById(assetId)?.takeUnless { it.deleted }
            ?: return@withContext null

        // 1. 物理删除云端旧 R2 资产（防止垃圾文件长期堆积）
        existing.r2Ref()?.let { oldRef ->
            runCatching { r2MediaStore.delete(oldRef) }
        }

        // 2. 物理删除 App 私有沙箱私有目录中的旧文件（绝不影响系统相册）
        localFile(existing)?.let { oldFile ->
            if (oldFile.isFile && oldFile.exists()) {
                runCatching { oldFile.delete() }
            }
        }

        // 3. 写入新的压缩文件
        val folder = if (existing.folder.isBlank()) FileFolders.UPLOAD else existing.folder
        val ext = mimeToExt(newMimeType).ifBlank { ".jpg" }
        val relative = "$folder/${Uuid.random()}$ext"
        val newFile = File(context.filesDir, relative)
        newFile.parentFile?.mkdirs()
        newFile.writeBytes(newBytes)

        // 4. 更新 Asset 元数据：保持核心 id 绝对不变
        val updated = existing.copy(
            relativePath = relative,
            mimeType = newMimeType,
            sizeBytes = newBytes.size.toLong(),
            sha256 = sha256(newBytes),
            r2Key = null,
            r2Acct = null,
            externalUrl = null,
            updatedAt = System.currentTimeMillis(),
        )

        database.managedFileDao().update(updated)
        enqueueManagedFilesBundleSync()
        enqueueCloudUpload(updated)
        updated
    }

    suspend fun saveOcrText(assetId: String, text: String) = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext
        database.managedFileDao().updateOcrText(assetId, text, System.currentTimeMillis())
        enqueueManagedFilesBundleSync()
    }

    suspend fun resolveImagePartForOcr(part: UIMessagePart.Image, model: Model): UIMessagePart.Image? {
        val effectiveModel = model.copy(inputModalities = model.inputModalities - Modality.URL + Modality.IMAGE)
        return resolvePartForModel(part, effectiveModel) as? UIMessagePart.Image
    }

    suspend fun resolvePartForModel(part: UIMessagePart, model: Model): UIMessagePart? {
        val rawUrl = when (part) {
            is UIMessagePart.Image -> part.url
            is UIMessagePart.Document -> part.url
            is UIMessagePart.Video -> part.url
            is UIMessagePart.Audio -> part.url
            else -> return part
        }

        if (rawUrl.isBlank()) return null

        val assetId = AssetUri.parse(rawUrl)
        var asset = assetId?.let { database.managedFileDao().getById(it)?.takeUnless { a -> a.deleted } }

        if (asset == null) {
            val indexedPart = runCatching { indexPartForStorage(part) }.getOrNull()
            val newAssetId = when (indexedPart) {
                is UIMessagePart.Image -> AssetUri.parse(indexedPart.url)
                is UIMessagePart.Document -> AssetUri.parse(indexedPart.url)
                is UIMessagePart.Video -> AssetUri.parse(indexedPart.url)
                is UIMessagePart.Audio -> AssetUri.parse(indexedPart.url)
                else -> null
            }
            if (newAssetId != null) {
                asset = database.managedFileDao().getById(newAssetId)?.takeUnless { a -> a.deleted }
            }
        }

        if (asset != null) {
            val url = resolveAssetForModel(asset, model) ?: return null
            return when (part) {
                is UIMessagePart.Image -> part.copy(url = url)
                is UIMessagePart.Document -> part.copy(url = url, fileName = asset.displayName, mime = asset.mimeType)
                is UIMessagePart.Video -> part.copy(url = url)
                is UIMessagePart.Audio -> part.copy(url = url)
                else -> part
            }
        }

        val isUrlSupported = Modality.URL in model.inputModalities
        if (rawUrl.startsWith("data:", ignoreCase = true) ||
            rawUrl.startsWith("http://", ignoreCase = true) ||
            rawUrl.startsWith("https://", ignoreCase = true)
        ) {
            return part
        }

        if (rawUrl.startsWith("file://", ignoreCase = true)) {
            val file = runCatching { rawUrl.toUri().toFile() }.getOrNull()
            if (file != null && file.isFile) {
                return if (isUrlSupported) {
                    part
                } else {
                    val dataUri = file.absolutePath.toImageDataUriOrRemote()
                    when (part) {
                        is UIMessagePart.Image -> part.copy(url = dataUri)
                        else -> part
                    }
                }
            }
        }

        return null
    }

    suspend fun ensureLocal(asset: ManagedFileEntity): File? = withContext(Dispatchers.IO) {
        localFile(asset)?.takeIf { it.isFile }?.let { return@withContext it }
        val bytes = when {
            asset.r2Ref() != null -> r2MediaStore.downloadBytes(asset.r2Ref()!!).getOrNull()
            asset.externalUrl != null -> runCatching { URL(asset.externalUrl).openStream().use { it.readBytes() } }.getOrNull()
            else -> null
        } ?: return@withContext null
        val folder = if (asset.folder.isBlank()) FileFolders.UPLOAD else asset.folder
        val ext = asset.displayName.substringAfterLast('.', "").takeIf { it.length in 1..8 }?.let { ".$it" } ?: ""
        val relative = "$folder/${Uuid.random()}$ext"
        val file = File(context.filesDir, relative)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        database.managedFileDao().update(asset.copy(relativePath = relative, sizeBytes = bytes.size.toLong(), sha256 = sha256(bytes), updatedAt = System.currentTimeMillis()))
        enqueueManagedFilesBundleSync()
        file
    }

    private suspend fun enqueueManagedFilesBundleSync() {
        runCatching {
            val outbox = database.syncOutboxDao()
            outbox.deleteByRef(SyncOutboxEntity.KIND_BUNDLE, BUNDLE_MANAGED_FILES)
            outbox.insert(
                SyncOutboxEntity(
                    kind = SyncOutboxEntity.KIND_BUNDLE,
                    refKey = BUNDLE_MANAGED_FILES,
                    op = SyncOutboxEntity.OP_UPSERT,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    fun enqueueCloudUpload(asset: ManagedFileEntity) {
        if (asset.r2Key != null || asset.r2Acct != null || asset.deleted) return
        appScope.launch(Dispatchers.IO) {
            runCatching {
                database.mediaUploadOutboxDao().insert(MediaUploadOutboxEntity(assetId = asset.id))
                processCloudUploadOutbox()
            }
        }
    }

    suspend fun processCloudUploadOutbox() = withContext(Dispatchers.IO) {
        if (!uploadProcessorRunning.compareAndSet(false, true)) return@withContext
        try {
            if (!r2MediaStore.isConfigured()) return@withContext
            val dao = database.mediaUploadOutboxDao()
            while (true) {
                val dueItems = dao.due(
                    now = System.currentTimeMillis(),
                    limit = syncAdvancedConfigStore.current.mediaUploadBatchLimit,
                )
                if (dueItems.isEmpty()) break
                dueItems.forEach { item ->
                    runCatching { ensureCloud(item.assetId) }
                        .onSuccess { ref ->
                            if (ref != null) {
                                dao.delete(item.assetId)
                            } else {
                                markUploadFailed(item, "No local/external bytes available for upload")
                            }
                        }
                        .onFailure { e ->
                            markUploadFailed(item, e.message ?: e.javaClass.simpleName)
                        }
                }
            }
        } finally {
            uploadProcessorRunning.set(false)
        }
    }

    private suspend fun markUploadFailed(item: MediaUploadOutboxEntity, error: String) {
        val nextRetryCount = item.retryCount + 1
        val nextAttemptAt = if (nextRetryCount >= syncAdvancedConfigStore.current.mediaUploadMaxRetries) {
            Long.MAX_VALUE
        } else {
            nextRetryAt(nextRetryCount)
        }
        database.mediaUploadOutboxDao().markFailed(
            assetId = item.assetId,
            error = error,
            updatedAt = System.currentTimeMillis(),
            nextAttemptAt = nextAttemptAt,
        )
    }

    suspend fun ensureCloud(assetId: String): R2Ref? = withContext(Dispatchers.IO) {
        val asset = database.managedFileDao().getById(assetId)?.takeUnless { it.deleted } ?: return@withContext null
        asset.r2Ref()?.let { return@withContext it }
        val file = localFile(asset)?.takeIf { it.isFile } ?: ensureLocal(asset) ?: return@withContext null
        val ref = r2MediaStore.upload(file.readBytes(), asset.mimeType, prefixForFolder(asset.folder)).getOrNull() ?: return@withContext null
        database.managedFileDao().update(asset.copy(r2Key = ref.key, r2Acct = ref.acctId, updatedAt = System.currentTimeMillis()))
        enqueueManagedFilesBundleSync()
        ref
    }

    private suspend fun resolveAssetForModel(asset: ManagedFileEntity, model: Model): String? {
        val supportsUrl = Modality.URL in model.inputModalities
        if (supportsUrl) {
            asset.externalUrl?.let { return it }
            asset.r2Ref()?.let { return r2MediaStore.presign(it).getOrNull() }
            enqueueCloudUpload(asset)
        }
        return ensureLocal(asset)?.toUri()?.toString()
    }

    private fun localFile(asset: ManagedFileEntity): File? {
        if (asset.relativePath.isBlank() || asset.relativePath.startsWith("remote/")) return null
        val direct = File(asset.relativePath)
        if (direct.isAbsolute && direct.isFile) return direct
        val filesDirFile = File(context.filesDir, asset.relativePath)
        if (filesDirFile.isFile) return filesDirFile
        val cacheDirFile = File(context.cacheDir, asset.relativePath)
        if (cacheDirFile.isFile) return cacheDirFile
        return filesDirFile
    }

    private fun ManagedFileEntity.r2Ref(): R2Ref? =
        if (!r2Key.isNullOrBlank() && !r2Acct.isNullOrBlank()) R2Ref(r2Acct!!, r2Key!!) else null

    private fun isLegacyAttachment(part: UIMessagePart): Boolean = when (part) {
        is UIMessagePart.Image -> !AssetUri.isAsset(part.url)
        is UIMessagePart.Document -> !AssetUri.isAsset(part.url)
        is UIMessagePart.Video -> !AssetUri.isAsset(part.url)
        is UIMessagePart.Audio -> !AssetUri.isAsset(part.url)
        else -> false
    }

    private fun nextRetryAt(retryCount: Int): Long {
        val delayMinutes = min(
            syncAdvancedConfigStore.current.mediaUploadMaxBackoffMinutes,
            1 shl retryCount.coerceIn(0, 10),
        )
        return System.currentTimeMillis() + delayMinutes * 60_000L
    }

    private fun sha256(bytes: ByteArray?): String? = bytes?.let {
        MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) }
    }

    private fun prefixForFolder(folder: String): String = when (folder) {
        FileFolders.IMAGES, FileFolders.LLM_PREVIEWS -> R2MediaStore.PREFIX_GEN_IMAGES
        else -> R2MediaStore.PREFIX_CHAT_UPLOADS
    }

    private fun folderForR2Key(key: String): String = when {
        key.startsWith("${R2MediaStore.PREFIX_GEN_IMAGES}/") -> FileFolders.IMAGES
        key.startsWith("${R2MediaStore.PREFIX_GEN_PREVIEWS}/") -> FileFolders.LLM_PREVIEWS
        else -> FileFolders.UPLOAD
    }

    private fun mimeToExt(mime: String): String = when (mime.lowercase()) {
        "image/jpeg" -> ".jpg"
        "image/png" -> ".png"
        "image/gif" -> ".gif"
        "image/webp" -> ".webp"
        "video/mp4" -> ".mp4"
        "audio/mpeg" -> ".mp3"
        "audio/mp3" -> ".mp3"
        "audio/wav" -> ".wav"
        "audio/ogg" -> ".ogg"
        else -> ""
    }
}
