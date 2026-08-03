package me.rerere.rikkahub.ui.pages.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.transformers.OcrRateLimiter
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.files.AssetUri
import me.rerere.rikkahub.data.files.AssetResolver
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.ImageTag
import me.rerere.rikkahub.data.repository.AssetLabelRepository
import kotlin.uuid.Uuid

/** 「全部」这个伪分类：跨 upload / ai_images / avatars 聚合 */
const val GALLERY_FOLDER_ALL = "__all__"

/** 相册收录的分类，顺序即 UI 顺序 */
val GALLERY_FOLDERS = listOf(
    GALLERY_FOLDER_ALL,
    FileFolders.UPLOAD,
    FileFolders.IMAGES,
    FileFolders.AVATARS,
)

data class GalleryBatchOcrProgress(
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
)

/**
 * 批量 OCR 的全局进度单例。
 *
 * 任务跑在 appScope 而不是 ViewModel 里 —— 离开相册页时 ViewModel 被销毁，
 * 但 OCR 不该跟着取消（用户很可能切出去等一会儿再回来看结果）；
 * 重进页面从单例恢复进度显示，做完的识别结果已落库。
 */
object OcrBatchState {
    val progress = MutableStateFlow(GalleryBatchOcrProgress())

    fun reset() {
        if (!progress.value.running) progress.value = GalleryBatchOcrProgress()
    }
}

class GalleryVM(
    private val filesManager: FilesManager,
    private val labelRepository: AssetLabelRepository,
    private val settingsStore: SettingsStore,
    private val assetResolver: AssetResolver,
    private val appScope: AppScope,
) : ViewModel() {

    val settings: MutableStateFlow<Settings> = MutableStateFlow(settingsStore.settingsFlow.value)

    /** assetId -> tagId 集合 */
    val tagMap = labelRepository.observeTagMap()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** assetId -> 附加分类集合（物理 folder 之外还应出现在哪） */
    val extraFolderMap = labelRepository.observeFolderMap()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * 相册的全部图片（未过滤）。
     *
     * 一次性订阅三个目录再在内存里合并，而不是按当前选中分类去查：
     * 「全部」和附加分类都需要跨目录数据，按需查会导致切分类时闪一下空列表。
     */
    val allImages = combine(
        filesManager.observe(FileFolders.UPLOAD),
        filesManager.observe(FileFolders.IMAGES),
        filesManager.observe(FileFolders.AVATARS),
    ) { upload, images, avatars ->
        (upload + images + avatars)
            .asSequence()
            .filter { it.isGalleryImage() }
            .distinctBy { it.id }
            .sortedByDescending { it.createdAt }
            .toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            settingsStore.settingsFlow.collect { settings.value = it }
        }
    }

    fun syncFolders() {
        viewModelScope.launch {
            listOf(FileFolders.UPLOAD, FileFolders.IMAGES, FileFolders.AVATARS).forEach {
                runCatching { filesManager.syncFolder(it) }
            }
        }
    }

    // ---------------- 标签 ----------------

    /** 某分类下可用的标签：全局标签 + 该分类专属标签 */
    fun tagsForFolder(folder: String): List<ImageTag> {
        val tags = ImageTag.withBuiltins(settings.value.imageTags)
        // 「全部」是伪分类，没有专属标签，只给全局的
        if (folder == GALLERY_FOLDER_ALL) return tags.filter { it.scope == null }
        return tags.filter { it.scope == null || it.scope == folder }
    }

    /** 敏感标签的 id 集合。用于「从全部里排除」和「默认打码」 */
    fun sensitiveTagIds(): Set<String> =
        ImageTag.withBuiltins(settings.value.imageTags)
            .filter { it.sensitive }
            .mapTo(mutableSetOf()) { it.id.toString() }

    fun toggleTag(assetId: String, tagId: Uuid, attached: Boolean) {
        viewModelScope.launch {
            if (attached) {
                labelRepository.removeTag(assetId, tagId.toString())
            } else {
                labelRepository.addTag(assetId, tagId.toString())
            }
            syncMetadataToFile(assetId)
        }
    }

    fun addTagToAll(assetIds: Collection<String>, tagId: Uuid) {
        viewModelScope.launch {
            labelRepository.addTagToAll(assetIds, tagId.toString())
            assetIds.forEach { syncMetadataToFile(it) }
        }
    }

    fun removeTagFromAll(assetIds: Collection<String>, tagId: Uuid) {
        viewModelScope.launch {
            labelRepository.removeTagFromAll(assetIds, tagId.toString())
            assetIds.forEach { syncMetadataToFile(it) }
        }
    }

    fun rename(assetId: String, nameZh: String?) {
        viewModelScope.launch {
            filesManager.rename(assetId, nameZh)
            syncMetadataToFile(assetId)
        }
    }

    /**
     * 把 DB 里的名字与标签同步进图片字节。
     *
     * 用户改完名/打完标签就把文件里的元数据一起更新，
     * 这样导出到系统相册或拷到电脑上仍然带着信息，不依赖本 App 的数据库。
     * 失败不上报：元数据是附加价值，写不进去不该打断 UI 操作。
     */
    private suspend fun syncMetadataToFile(assetId: String) {
        runCatching {
            val asset = filesManager.get(assetId) ?: return
            val tagIds = labelRepository.getTags(assetId)
            val names = ImageTag.withBuiltins(settings.value.imageTags)
                .filter { it.id.toString() in tagIds }
                .map { it.name }
            assetResolver.writeMetadataToFile(
                assetId = assetId,
                description = asset.description ?: asset.ocrText,
                nameZh = asset.nameZh,
                nameEn = asset.nameEn,
                tagNames = names,
            )
        }
    }

    // ---------------- 批量 OCR ----------------

    /**
     * 批量 OCR。
     *
     * 进度是「已完成数」而非「已发起数」—— 限流下发起和完成能差出很远，
     * 报发起数会让进度条冲到 100% 然后卡住不动。
     *
     * 任务跑在 appScope：离开相册页（ViewModel 销毁）不会取消任务，
     * 重进页面从 [OcrBatchState] 恢复进度。
     */
    fun batchOcr(assets: List<ManagedFileEntity>) {
        if (assets.isEmpty() || OcrBatchState.progress.value.running) return
        val current = settings.value
        val limiter = OcrRateLimiter(
            maxConcurrency = current.ocrMaxConcurrency,
            ratePerMinute = current.ocrRatePerMinute,
        )
        OcrBatchState.progress.value = GalleryBatchOcrProgress(running = true, total = assets.size)
        appScope.launch(Dispatchers.IO) {
            runCatching {
                coroutineScope {
                    assets.map { asset ->
                        async {
                            val ok = limiter.withPermit {
                                runCatching {
                                    val result = OcrTransformer.performOcr(
                                        UIMessagePart.Image(AssetUri.fromId(asset.id))
                                    )
                                    // performOcr 内部把异常吞成了字符串，只能看内容判断
                                    !result.startsWith("[ERROR")
                                }.getOrDefault(false)
                            }
                            OcrBatchState.progress.value = OcrBatchState.progress.value.let {
                                if (ok) it.copy(done = it.done + 1)
                                else it.copy(done = it.done + 1, failed = it.failed + 1)
                            }
                        }
                    }.awaitAll()
                }
            }
            OcrBatchState.progress.value = OcrBatchState.progress.value.copy(running = false)
        }
    }

    fun clearBatchOcr() = OcrBatchState.reset()

    // ---------------- 设置页：标签维护 ----------------

    fun upsertTag(tag: ImageTag) {
        viewModelScope.launch {
            val existing = settings.value.imageTags
            val next = if (existing.any { it.id == tag.id }) {
                existing.map { if (it.id == tag.id) tag else it }
            } else {
                existing + tag
            }
            settingsStore.update(settings.value.copy(imageTags = next))
        }
    }

    fun deleteTag(tag: ImageTag) {
        // 内置标签不允许删除：删掉 NSFW 之后敏感图会突然全部裸奔到「全部」里
        if (tag.builtin) return
        viewModelScope.launch {
            settingsStore.update(
                settings.value.copy(imageTags = settings.value.imageTags.filterNot { it.id == tag.id })
            )
            // 顺手清掉图上的残留引用，否则会留下永远筛不出来的孤儿标签
            labelRepository.purgeTag(tag.id.toString())
        }
    }

    /**
     * 一键合并默认标签：把 SEED_TAGS 里当前缺失的种子标签追加进来。
     * 按 id 判断 —— 用户已有的（含改过名/改过作用域的）一律保留不动，
     * 老用户不会因为更新被重置标签表。
     *
     * @return 实际追加的标签数（0 = 全部已存在）
     */
    fun mergeDefaultTags(onDone: (added: Int) -> Unit = {}) {
        viewModelScope.launch {
            val existing = settings.value.imageTags
            val existingIds = existing.mapTo(mutableSetOf()) { it.id }
            val missing = ImageTag.SEED_TAGS.filter { it.id !in existingIds }
            if (missing.isNotEmpty()) {
                settingsStore.update(settings.value.copy(imageTags = existing + missing))
            }
            onDone(missing.size)
        }
    }
}

/**
 * 相册收录判定：mimeType 是 image，且排除喂给模型的压缩预览图。
 */
fun ManagedFileEntity.isGalleryImage(): Boolean {
    if (!mimeType.startsWith("image/")) return false
    if (relativePath.endsWith("_llm_preview.jpg", ignoreCase = true)) return false
    if (folder == FileFolders.LLM_PREVIEWS) return false
    return true
}
