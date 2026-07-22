package me.rerere.ai.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import me.rerere.ai.ui.ImageGenerationItem

interface ImageProvider<T : ImageProviderSetting> {
    suspend fun generateImage(
        providerSetting: T,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem>
}
