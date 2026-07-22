package me.rerere.ai.provider

import kotlinx.coroutines.flow.Flow
import me.rerere.ai.ui.ImageGenerationItem

interface ImageProvider<T : ImageProviderSetting> {
    suspend fun generateImage(
        providerSetting: T,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem>

    suspend fun editImage(
        providerSetting: T,
        params: ImageEditParams,
    ): Flow<ImageGenerationItem> {
        error("Image edit is not supported by this provider")
    }
}
