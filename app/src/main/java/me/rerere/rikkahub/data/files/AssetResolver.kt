package me.rerere.rikkahub.data.files

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.room.withTransaction
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
import me.rerere.rikkahub.data.db.entity.AssetLabelEntity
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.MediaUploadOutboxEntity
import me.rerere.rikkahub.data.db.entity.SyncOutboxEntity
import me.rerere.rikkahub.data.sync.core.BUNDLE_ASSET_LABELS
import me.rerere.rikkahub.data.sync.core.BUNDLE_MANAGED_FILES
import me.rerere.rikkahub.data.sync.core.SyncAdvancedConfigStore
import me.rerere.rikkahub.data.sync.r2.R2MediaStore
import me.rerere.rikkahub.data.sync.r2.R2Ref
import me.rerere.rikkahub.data.repository.GenMediaRepository
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
    private val genMediaRepository: GenMediaRepository,
) {
    private val uploadProcessorRunning = AtomicBoolean(false)

    init {
        appScope.launch(Dispatchers.IO) { processCloudUploadOutbox() }
        appScope.launch(Dispatchers.IO) { backfillContentSha256() }
    }

    /**
     * 给存量图片补 content_sha256。
     *
     * 这一列是 Migration 33->34 新加的，老数据全是 NULL；不补的话去重会一直退化成
     * 只比整字节 sha256，写过元数据的老图仍然会被重复落库。
     * 分批 + 每批之间让出调度，避免开机瞬间抢满 IO。
     */
    suspend fun backfillContentSha256(batchSize: Int = 40) = withContext(Dispatchers.IO) {
        runCatching {
            val dao = database.managedFileDao()
            while (true) {
                val batch = dao.listMissingContentSha256(batchSize)
                if (batch.isEmpty()) break
                batch.forEach { asset ->
                    val file = localFile(asset)?.takeIf { it.isFile }
                    // 文件不在本地就写个空串占位，否则这行会被反复查出来变成死循环
                    val sha = file?.let { AssetMetadataWriter.normalizedSha256(it) } ?: ""
                    dao.updateContentSha256(asset.id, sha)
                }
                if (batch.size < batchSize) break
            }
        }.onFailure {
            android.util.Log.w("AssetResolver", "backfillContentSha256 failed", it)
        }
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
        val bytes = file.takeIf { it.isFile }?.readBytes()
        val updated = entity.copy(
            sha256 = sha256(bytes),
            contentSha256 = AssetMetadataWriter.normalizedSha256(bytes),
            prompt = prompt,
            description = description,
        )
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
            contentSha256 = AssetMetadataWriter.normalizedSha256(bytes),
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
        val contentSha = AssetMetadataWriter.normalizedSha256(bytes)
        // 先按整字节命中，再按「剥掉元数据」的内容摘要命中。
        // 后者是关键：一张图被写过 OCR 元数据之后整字节摘要就变了,
        // 只查 sha256 会把同一张图当成新文件反复落盘 + 反复上传 R2。
        val duplicate = database.managedFileDao().getBySha256(sha)?.takeIf { !it.deleted }
            ?: contentSha?.let { database.managedFileDao().getByContentSha256(it) }?.takeIf { !it.deleted }
        duplicate?.let { existing ->
            val updated = if (externalUrl != null && existing.externalUrl != externalUrl) {
                existing.copy(externalUrl = externalUrl).also { database.managedFileDao().update(it) }
            } else existing
            enqueueCloudUpload(updated)
            return@withContext updated
        }
        val entity = filesManager.saveManagedFromBytes(folder, bytes, displayName, mimeType)
        val updated = entity.copy(
            sha256 = sha,
            contentSha256 = contentSha,
            prompt = prompt,
            description = description,
            externalUrl = externalUrl,
        )
        database.managedFileDao().update(updated)
        enqueueManagedFilesBundleSync()
        enqueueCloudUpload(updated)
        updated
    }

    suspend fun createFromExternalUrl(
        url: String,
        folder: String = FileFolders.UPLOAD,
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
            folder = folder,
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

    /**
     * 按统一寻址取本地文件: asset id(uri / 裸 uuid / metadata hint) > file:// > 绝对路径。
     * 资产只在云端时会先下载落地(见 [ensureLocal])。
     */
    suspend fun localFileFor(url: String, assetIdHint: String? = null): File? = withContext(Dispatchers.IO) {
        val assetId = assetIdHint?.let { AssetReferences.assetId(it) } ?: AssetReferences.assetId(url)
        if (assetId != null) {
            val asset = database.managedFileDao().getById(assetId)?.takeUnless { it.deleted }
            if (asset != null) return@withContext ensureLocal(asset)
        }
        if (url.startsWith("file://", ignoreCase = true)) {
            return@withContext runCatching { url.toUri().toFile() }.getOrNull()?.takeIf { it.isFile }
        }
        File(url).takeIf { it.isAbsolute && it.isFile }
    }

    /**
     * 读取附件字节。先走 [localFileFor], 再兜底 content:// / http(s) / data:。
     * 任何需要"附件原始内容"的地方(文档解析、OCR、上传)都应该用这个, 不要自己 toFile()。
     */
    suspend fun readBytes(url: String, assetIdHint: String? = null): ByteArray? = withContext(Dispatchers.IO) {
        localFileFor(url, assetIdHint)?.let { return@withContext runCatching { it.readBytes() }.getOrNull() }
        when {
            url.startsWith("content://", ignoreCase = true) -> runCatching {
                context.contentResolver.openInputStream(url.toUri())?.use { it.readBytes() }
            }.getOrNull()

            url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) ->
                runCatching { URL(url).openStream().use { it.readBytes() } }.getOrNull()

            url.startsWith("data:", ignoreCase = true) -> runCatching {
                Base64.decode(url.substringAfter(',', missingDelimiterValue = ""), Base64.DEFAULT)
            }.getOrNull()

            else -> R2Ref.parse(url)?.let { r2MediaStore.downloadBytes(it).getOrNull() }
        }
    }

    /** 给需要"可公网访问 URL"的外部解析服务(MinerU 等)用 */
    suspend fun presignedUrlFor(assetId: String): String? = withContext(Dispatchers.IO) {
        val asset = database.managedFileDao().getById(assetId)?.takeUnless { it.deleted } ?: return@withContext null
        asset.r2Ref()?.let { r2MediaStore.presign(it).getOrNull() }?.let { return@withContext it }
        asset.externalUrl
    }

    suspend fun getOcrText(assetId: String): String? = withContext(Dispatchers.IO) {
        database.managedFileDao().getOcrText(assetId)?.takeIf { it.isNotBlank() }
    }

    /**
     * 把本地文件路径(file:// 或绝对路径)反查回托管资产。
     *
     * 对话里发送的附件 URL 是 file:// 临时路径，AssetUri 解析不出 id，
     * 于是 OCR 缓存被整个绕过，同一张图每次进对话都要重跑视觉模型。
     * 附件其实都落在 filesDir 下并被 trackManagedFile 登记过，
     * 按 relativePath 反查就能命中已有 asset（含已保存的 ocrText）。
     */
    suspend fun findAssetByLocalPath(url: String): ManagedFileEntity? = withContext(Dispatchers.IO) {
        val path = when {
            url.startsWith("file://", ignoreCase = true) ->
                runCatching { url.toUri().toFile() }.getOrNull()?.absolutePath
            else -> url
        } ?: return@withContext null
        val file = File(path).takeIf { it.isFile } ?: return@withContext null
        val filesDir = context.filesDir.absolutePath + File.separator
        val relative = file.absolutePath.takeIf { it.startsWith(filesDir) }?.removePrefix(filesDir)
            ?: return@withContext null
        database.managedFileDao().getByPath(relative)?.takeUnless { it.deleted }
    }

    /** 用户手动编辑图片描述：只改 description 字段，其余不动；同步写进图片文件 */
    suspend fun updateDescription(assetId: String, description: String?) = withContext(Dispatchers.IO) {
        val asset = database.managedFileDao().getById(assetId)?.takeUnless { it.deleted } ?: return@withContext
        val updated = asset.copy(
            description = description?.takeIf { it.isNotBlank() },
            updatedAt = System.currentTimeMillis(),
        )
        database.managedFileDao().update(updated)
        enqueueManagedFilesBundleSync()
        runCatching { writeMetadataToFile(assetId, updated.description, null, null, emptyList()) }
            .onFailure { android.util.Log.w("AssetResolver", "updateDescription: write metadata failed", it) }
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
            contentSha256 = AssetMetadataWriter.normalizedSha256(newBytes),
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

    suspend fun getAsset(assetId: String): ManagedFileEntity? = withContext(Dispatchers.IO) {
        database.managedFileDao().getById(assetId)?.takeUnless { it.deleted }
    }

    /**
     * OCR 结构化结果落库：描述 / 中英文名 / 标签一次写完。
     *
     * 标签走 asset_label_ref，与 managed_files 是两张表两个同步 bundle，
     * 所以这里包一层事务 —— 否则名字写进去了标签没写，
     * 用户会看到一张"有名字但筛不出来"的图。
     */
    suspend fun saveOcrResult(
        assetId: String,
        ocrText: String,
        description: String?,
        nameZh: String?,
        nameEn: String?,
        tagIds: List<String>,
        tagNames: List<String> = emptyList(),
    ) = withContext(Dispatchers.IO) {
        database.withTransaction {
            database.managedFileDao().updateOcrResult(
                id = assetId,
                ocrText = ocrText.takeIf { it.isNotBlank() },
                description = description?.takeIf { it.isNotBlank() },
                nameZh = nameZh?.takeIf { it.isNotBlank() },
                nameEn = nameEn?.takeIf { it.isNotBlank() },
                updatedAt = System.currentTimeMillis(),
            )
            if (tagIds.isNotEmpty()) {
                // 追加而不是覆盖：用户手动打的标签优先级高于模型，不能被 OCR 冲掉
                database.assetLabelDao().insertAll(
                    tagIds.distinct().map {
                        AssetLabelEntity(
                            assetId = assetId,
                            kind = AssetLabelEntity.KIND_TAG,
                            value = it,
                        )
                    }
                )
            }
        }
        enqueueManagedFilesBundleSync()
        if (tagIds.isNotEmpty()) enqueueAssetLabelsBundleSync()
        // 元数据物理写进图片：导出到系统相册 / 拷到电脑后信息还在，不依赖本 App 的库。
        // 放在 DB 之后做，且失败只记日志 —— 写文件失败不该让 OCR 结果丢掉。
        runCatching { writeMetadataToFile(assetId, description, nameZh, nameEn, tagNames) }
            .onFailure { android.util.Log.w("AssetResolver", "saveOcrResult: write metadata failed", it) }
        // LLM preview 不参与相册，但要带上和原图一致的 prompt/描述/命名/标签，
        // 否则聊天里看图时预览图是一张没有任何元数据的孤儿。
        syncLlmPreviewMetadata(
            assetId = assetId,
            ocrText = ocrText.takeIf { it.isNotBlank() },
            description = description?.takeIf { it.isNotBlank() },
            nameZh = nameZh?.takeIf { it.isNotBlank() },
            nameEn = nameEn?.takeIf { it.isNotBlank() },
            tagIds = tagIds,
        )
    }

    /**
     * 把元数据写进图片字节，并同步 content_sha256 / size_bytes。
     *
     * 顺序很重要：写文件 → 重算 content_sha256（对元数据免疫，理论上不变，
     * 但首次写入前该列可能为空，正好补上）→ 回写 size_bytes。
     * 整字节 sha256 故意不更新：它是 R2 对象的寻址键，改了会让已上传的对象失联。
     */
    suspend fun writeMetadataToFile(
        assetId: String,
        description: String?,
        nameZh: String?,
        nameEn: String?,
        tagNames: List<String>,
    ) = withContext(Dispatchers.IO) {
        val asset = database.managedFileDao().getById(assetId)?.takeUnless { it.deleted } ?: return@withContext
        if (!asset.mimeType.startsWith("image/")) return@withContext
        val file = localFile(asset)?.takeIf { it.isFile } ?: return@withContext
        val metadata = AssetMetadataWriter.Metadata(
            description = description ?: asset.description ?: asset.ocrText,
            nameZh = nameZh ?: asset.nameZh,
            nameEn = nameEn ?: asset.nameEn,
            tags = tagNames,
        )
        if (metadata.isEmpty) return@withContext
        val written = AssetMetadataWriter.write(file, metadata)
        val contentSha = AssetMetadataWriter.normalizedSha256(file)
        if (!written && contentSha == asset.contentSha256) return@withContext
        database.managedFileDao().update(
            asset.copy(
                contentSha256 = contentSha ?: asset.contentSha256,
                sizeBytes = file.length(),
                updatedAt = System.currentTimeMillis(),
            )
        )
        enqueueManagedFilesBundleSync()
    }

    /**
     * 把原图的 OCR/命名/标签同步到它的 LLM preview 资产。
     *
     * preview 不参与相册筛选，但聊天链路里展示的是 preview —— 不带元数据的话
     * 预览图就是一张没有描述/名字/标签的孤儿。名字统一加 _llmpreview 后缀以示区分。
     * 失败只记日志，绝不让 OCR 主流程因此中断。
     */
    private suspend fun syncLlmPreviewMetadata(
        assetId: String,
        ocrText: String?,
        description: String?,
        nameZh: String?,
        nameEn: String?,
        tagIds: List<String>,
    ) {
        runCatching {
            val previewId = genMediaRepository.getAllMediaList()
                .firstOrNull { it.originalAssetId == assetId }
                ?.previewAssetId
                ?: return@runCatching
            if (previewId == assetId) return@runCatching // 预览即原图（未压缩），无需复制
            val dao = database.managedFileDao()
            val preview = dao.getById(previewId)?.takeUnless { it.deleted } ?: return@runCatching
            dao.update(
                preview.copy(
                    ocrText = ocrText?.takeIf { it.isNotBlank() } ?: preview.ocrText,
                    description = description?.takeIf { it.isNotBlank() } ?: preview.description,
                    nameZh = nameZh?.takeIf { it.isNotBlank() }?.plus("_llmpreview") ?: preview.nameZh,
                    nameEn = nameEn?.takeIf { it.isNotBlank() }?.plus("_llmpreview") ?: preview.nameEn,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            if (tagIds.isNotEmpty()) {
                database.assetLabelDao().insertAll(
                    tagIds.distinct().map {
                        AssetLabelEntity(assetId = previewId, kind = AssetLabelEntity.KIND_TAG, value = it)
                    }
                )
            }
            enqueueManagedFilesBundleSync()
            enqueueAssetLabelsBundleSync()
        }.onFailure {
            android.util.Log.w("AssetResolver", "syncLlmPreviewMetadata failed", it)
        }
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
            // 文档一律优先本地: 各家 provider 的 fileData/fileUri 只认自家托管地址,
            // 丢一个 R2 预签名 URL 过去必被拒, 而且会让下游 transformer 拿不到内容。
            val url = resolveAssetForModel(asset, model, preferLocal = part is UIMessagePart.Document)
                ?: return null
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
        database.managedFileDao().update(
            asset.copy(
                relativePath = relative,
                sizeBytes = bytes.size.toLong(),
                sha256 = sha256(bytes),
                contentSha256 = AssetMetadataWriter.normalizedSha256(bytes),
                updatedAt = System.currentTimeMillis(),
            )
        )
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

    private suspend fun enqueueAssetLabelsBundleSync() {
        runCatching {
            val outbox = database.syncOutboxDao()
            outbox.deleteByRef(SyncOutboxEntity.KIND_BUNDLE, BUNDLE_ASSET_LABELS)
            outbox.insert(
                SyncOutboxEntity(
                    kind = SyncOutboxEntity.KIND_BUNDLE,
                    refKey = BUNDLE_ASSET_LABELS,
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

    private suspend fun resolveAssetForModel(
        asset: ManagedFileEntity,
        model: Model,
        preferLocal: Boolean = false,
    ): String? {
        val supportsUrl = Modality.URL in model.inputModalities && !preferLocal
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

    companion object {
        /** 传输层 metadata key: 附件被解析成 http/file/data 之后, 原始 asset id 存在这里 */
        const val METADATA_ASSET_ID = "asset_id"
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
