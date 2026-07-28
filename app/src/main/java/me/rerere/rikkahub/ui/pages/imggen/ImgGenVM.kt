package me.rerere.rikkahub.ui.pages.imggen

import android.app.Application
import android.util.Log
import me.rerere.rikkahub.data.sync.r2.R2MediaStore
import me.rerere.rikkahub.data.sync.r2.R2Ref
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.defaultImageParameterBodies
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findImageProvider
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.utils.sanitizeFileName
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

@Serializable
data class GeneratedImage(
    val id: Int,
    val prompt: String,
    val filePath: String,
    val timestamp: Long,
    val model: String
)

private fun GenMediaEntity.toGeneratedImage(filesManager: FilesManager): GeneratedImage {
    // r2:// 引用原样透出，由 Coil 的 R2ImageFetcher 预签名加载
    val fullPath = if (path.startsWith("http://") || path.startsWith("https://") || path.startsWith("r2://")) {
        path
    } else {
        File(filesManager.getImagesDir(), path.removePrefix("images/")).absolutePath
    }

    return GeneratedImage(
        id = this.id,
        prompt = this.prompt,
        filePath = fullPath,
        timestamp = this.createAt,
        model = this.modelId
    )
}

class ImgGenVM(
    context: Application,
    val settingsStore: SettingsStore,
    val providerManager: ProviderManager,
    val genMediaRepository: GenMediaRepository,
    private val filesManager: FilesManager,
    private val r2MediaStore: R2MediaStore,
) : AndroidViewModel(context) {
    private val _prompt = MutableStateFlow("")
    val prompt: StateFlow<String> = _prompt

    private val _numberOfImages = MutableStateFlow(1)
    val numberOfImages: StateFlow<Int> = _numberOfImages

    private val _size = MutableStateFlow(ImageGenSize.AUTO.value)
    val size: StateFlow<String> = _size

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating
    private var cancelJob: Job? = null

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _currentGeneratedImages = MutableStateFlow<List<GeneratedImage>>(emptyList())
    val currentGeneratedImages: StateFlow<List<GeneratedImage>> = _currentGeneratedImages

    private val _referenceImages = MutableStateFlow<List<String>>(emptyList())
    val referenceImages: StateFlow<List<String>> = _referenceImages

    val pager = Pager(
        config = PagingConfig(pageSize = 20, enablePlaceholders = false),
        pagingSourceFactory = { genMediaRepository.getAllMedia() }
    )
    val generatedImages: Flow<PagingData<GeneratedImage>> = pager.flow
        .map { pagingData ->
            pagingData.map { entity -> entity.toGeneratedImage(filesManager) }
        }
        .cachedIn(viewModelScope)

    fun updatePrompt(prompt: String) {
        _prompt.value = prompt
    }

    fun updateNumberOfImages(count: Int) {
        _numberOfImages.value = count.coerceIn(1, 4)
    }

    fun updateSize(size: String) {
        _size.value = size
    }

    fun addReferenceImages(paths: List<String>) {
        _referenceImages.value = (_referenceImages.value + paths).distinct().take(MAX_REFERENCE_IMAGES)
    }

    fun removeReferenceImage(path: String) {
        _referenceImages.value = _referenceImages.value.filterNot { it == path }
        deleteReferenceFiles(listOf(path))
    }

    fun clearReferenceImages() {
        deleteReferenceFiles(_referenceImages.value)
        _referenceImages.value = emptyList()
    }

    fun clearError() {
        _error.value = null
    }

    fun startNewSession() {
        cancelJob?.cancel()
        clearReferenceImages()
        _prompt.value = ""
        _currentGeneratedImages.value = emptyList()
        _error.value = null
        _isGenerating.value = false
    }

    fun generateImage() {
        if (prompt.value.isBlank()) return
        cancelJob?.cancel()
        cancelJob = viewModelScope.launch {
            try {
                _isGenerating.value = true
                _error.value = null
                _currentGeneratedImages.value = emptyList()

                val settings = settingsStore.settingsFlow.first()
                val model = settings.findModelById(settings.imageGenerationModelId)
                    ?: throw IllegalStateException("No model selected")

                val provider = model.findImageProvider(settings.imageProviders)
                    ?: throw IllegalStateException("Provider not found")

                val providerSetting = settings.imageProviders.find { it.id == provider.id }
                    ?: throw IllegalStateException("Provider setting not found")

                val requestPrompt = _prompt.value
                val params = ImageGenerationParams(
                    model = model,
                    prompt = requestPrompt,
                    numOfImages = _numberOfImages.value,
                    size = _size.value,
                    customHeaders = model.customHeaders,
                    customBody = model.defaultImageParameterBodies()
                        .filterNot { it.key == "size" && _size.value != ImageGenSize.AUTO.value } + model.customBodies
                )

                val images = providerManager.getImageProviderByType(providerSetting)
                    .generateImage(providerSetting, params)

                collectImageGeneration(
                    images = images,
                    prompt = requestPrompt,
                    modelName = model.displayName,
                )
            } catch (e: Exception) {
                if(e is CancellationException) return@launch
                Log.e(TAG, "Failed to generate image", e)
                _error.value = e.message ?: "Unknown error occurred"
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun editImage() {
        if (prompt.value.isBlank() || referenceImages.value.isEmpty()) return
        cancelJob?.cancel()
        cancelJob = viewModelScope.launch {
            try {
                _isGenerating.value = true
                _error.value = null
                _currentGeneratedImages.value = emptyList()

                val settings = settingsStore.settingsFlow.first()
                val model = settings.findModelById(settings.imageGenerationModelId)
                    ?: throw IllegalStateException("No model selected")

                val provider = model.findImageProvider(settings.imageProviders)
                    ?: throw IllegalStateException("Image Provider not found")

                val providerSetting = settings.imageProviders.find { it.id == provider.id }
                    ?: throw IllegalStateException("Image Provider setting not found")

                val requestPrompt = _prompt.value
                val sourceImages = _referenceImages.value
                val params = ImageEditParams(
                    model = model,
                    prompt = requestPrompt,
                    images = sourceImages,
                    numOfImages = _numberOfImages.value,
                    size = _size.value,
                    customHeaders = model.customHeaders,
                    customBody = model.defaultImageParameterBodies()
                        .filterNot { it.key == "size" && _size.value != ImageGenSize.AUTO.value } + model.customBodies
                )

                val images = providerManager.getImageProviderByType(providerSetting)
                    .editImage(providerSetting, params)

                collectImageGeneration(
                    images = images,
                    prompt = requestPrompt,
                    modelName = model.displayName,
                    type = GenMediaEntity.TYPE_IMAGE_EDIT,
                    sourcePaths = sourceImages.joinToString("\n"),
                )
            } catch (e: Exception) {
                if (e is CancellationException) return@launch
                Log.e(TAG, "Failed to edit image", e)
                _error.value = e.message ?: "Unknown error occurred"
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun cancelGeneration() {
        cancelJob?.cancel()
    }

    private suspend fun collectImageGeneration(
        images: Flow<ImageGenerationItem>,
        prompt: String,
        modelName: String,
        type: String = GenMediaEntity.TYPE_IMAGE_GENERATION,
        sourcePaths: String? = null,
    ) {
        val finalImages = mutableListOf<GeneratedImage>()
        var previewFile: File? = null
        var finalIndex = 0

        images.collect { item ->
            if (item.partial) {
                previewFile?.delete()
                val imageFile = saveImagePreview(
                    item = item,
                    modelName = modelName,
                    index = item.partialImageIndex ?: finalIndex,
                )
                previewFile = imageFile
                _currentGeneratedImages.value = finalImages + GeneratedImage(
                    id = 0,
                    prompt = prompt,
                    filePath = imageFile.absolutePath,
                    timestamp = System.currentTimeMillis(),
                    model = modelName
                )
            } else {
                previewFile?.delete()
                previewFile = null
                val imagePath = saveImageToStorage(
                    item = item,
                    prompt = prompt,
                    modelName = modelName,
                    index = finalIndex,
                    type = type,
                    sourcePaths = sourcePaths,
                )
                finalImages.add(
                    GeneratedImage(
                        id = 0, // Will be updated after database insertion
                        prompt = prompt,
                        filePath = imagePath,
                        timestamp = System.currentTimeMillis(),
                        model = modelName
                    )
                )
                finalIndex++
                _currentGeneratedImages.value = finalImages.toList()
            }
        }
    }

    private fun saveImagePreview(
        item: ImageGenerationItem,
        modelName: String,
        index: Int,
    ): File {
        val timestamp = System.currentTimeMillis()
        val imageFile = File(
            getApplication<Application>().appTempFolder,
            "imggen_${timestamp}_${modelName.sanitizeFileName()}_$index.png",
        )
        return filesManager.createImageFileFromBase64(item.data, imageFile.absolutePath)
    }

    private suspend fun saveImageToStorage(
        item: ImageGenerationItem,
        prompt: String,
        modelName: String,
        index: Int,
        type: String = GenMediaEntity.TYPE_IMAGE_GENERATION,
        sourcePaths: String? = null,
    ): String {
        val timestamp = System.currentTimeMillis()
        // P3 云资产（v1.1）：URL 型立即镜像防过期，base64 型直接上传原字节；
        // R2 未配置/失败则回退原有"URL 原样 / 本地文件"行为
        val mirrored: Pair<R2Ref, String>? = if (r2MediaStore.isConfigured()) {
            if (item.url != null) {
                r2MediaStore.mirror(item.url, R2MediaStore.PREFIX_GEN_IMAGES).getOrNull()
            } else if (item.data.isNotBlank()) {
                runCatching {
                    val bytes = android.util.Base64.decode(
                        item.data.substringAfter("base64,"),
                        android.util.Base64.DEFAULT
                    )
                    r2MediaStore.upload(bytes, item.mimeType, R2MediaStore.PREFIX_GEN_IMAGES)
                        .getOrNull()
                        ?.let { it to item.mimeType }
                }.getOrNull()
            } else {
                null
            }
        } else {
            null
        }

        val path = mirrored?.first?.toString() ?: (item.url ?: run {
            val filename = "${timestamp}_${modelName.sanitizeFileName()}_$index.png"
            val imageFile = File(filesManager.getImagesDir(), filename)
            filesManager.createImageFileFromBase64(item.data, imageFile.absolutePath)
            "images/${imageFile.name}"
        })

        val entity = GenMediaEntity(
            path = path,
            modelId = modelName,
            prompt = prompt,
            createAt = timestamp,
            type = type,
            sourcePaths = sourcePaths,
            r2Key = mirrored?.first?.key,
            r2Acct = mirrored?.first?.acctId,
            originalUrl = item.url,
        )
        genMediaRepository.insertMedia(entity)

        return if (mirrored != null || item.url != null) {
            path
        } else {
            File(filesManager.getImagesDir(), path.removePrefix("images/")).absolutePath
        }
    }

    fun deleteImage(image: GeneratedImage) {
        viewModelScope.launch {
            try {
                // Delete from database first
                genMediaRepository.deleteMedia(image.id)

                // P3 云资产：r2:// 引用 → 联动删除 R2 对象（资产的唯一主人在此）
                R2Ref.parse(image.filePath)?.let { ref ->
                    r2MediaStore.delete(ref).onFailure {
                        Log.w(TAG, "delete R2 object failed for $ref", it)
                    }
                }

                // Remote URLs are not owned by this device; only delete local files.
                if (!image.filePath.startsWith("http://") && !image.filePath.startsWith("https://") && !image.filePath.startsWith("r2://")) {
                    val file = File(image.filePath)
                    if (file.exists()) {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete image", e)
                _error.value = "Failed to delete image"
            }
        }
    }

    private fun deleteReferenceFiles(paths: List<String>) {
        viewModelScope.launch {
            paths.forEach { path ->
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            }
        }
    }

    companion object {
        private const val TAG = "ImgGenVM"
        private const val MAX_REFERENCE_IMAGES = 16
    }
}
