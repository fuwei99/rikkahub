package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.rikkahub.data.files.AssetResolver
import me.rerere.rikkahub.data.files.AssetUri
import org.koin.compose.koinInject
import java.io.File

private fun String.toImageModel(): Any = when {
    startsWith("http://", ignoreCase = true) ||
        startsWith("https://", ignoreCase = true) ||
        startsWith("r2://", ignoreCase = true) ||
        startsWith("data:", ignoreCase = true) ||
        startsWith("file://", ignoreCase = true) -> this
    else -> File(this)
}

@Composable
private fun rememberGeneratedImageModel(
    url: String,
    assetResolver: AssetResolver = koinInject(),
): Any? {
    val assetId = remember(url) { AssetUri.parse(url) }
    var resolved by remember(url) { mutableStateOf<String?>(null) }
    LaunchedEffect(url, assetId) {
        resolved = if (assetId != null) assetResolver.resolveForDisplay(assetId) else url
    }
    return resolved?.toImageModel()
}

object ImageGenerationToolUI : ToolUIRenderer {
    override val toolName: String = "image_generation"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.AiMagic

    @Composable
    override fun title(context: ToolUIContext): String =
        "图像生成: " + (context.arguments.getStringContent("prompt") ?: "")

    override fun hasSummary(context: ToolUIContext): Boolean = true

    @Composable
    override fun Summary(context: ToolUIContext) {
        val imageParts = context.tool.output.filterIsInstance<UIMessagePart.Image>()
        val originalUri = context.content.getStringContent("original_asset_uri")
        val previewUri = context.content.getStringContent("preview_asset_uri")
        val legacyPaths = context.content.getStringContent("file_paths")
            ?: context.content.getStringContent("llm_preview")
        val imageUris = buildList {
            originalUri?.takeIf { it.isNotBlank() }?.let { add(it) }
            previewUri?.takeIf { it.isNotBlank() && it != originalUri }?.let { add(it) }
            imageParts.map { it.url }.filter { it.isNotBlank() && it !in this }.forEach { add(it) }
            legacyPaths?.split("\n")?.filter { it.isNotBlank() && it !in this }?.forEach { add(it) }
        }
        if (imageUris.isEmpty()) {
            Text(
                text = "正在生成中，请稍候...",
                style = MaterialTheme.typography.labelSmall
            )
            return
        }

        var selected by remember(imageUris) { mutableStateOf(imageUris.first()) }
        val selectedModel = rememberGeneratedImageModel(selected)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selectedModel != null) {
                AsyncImage(
                    model = selectedModel,
                    contentDescription = "Generated Image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            }
            if (imageUris.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    imageUris.forEach { uri ->
                        val thumb = rememberGeneratedImageModel(uri)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            border = if (uri == selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier
                                .size(72.dp)
                                .clickable { selected = uri },
                        ) {
                            if (thumb != null) {
                                AsyncImage(
                                    model = thumb,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
