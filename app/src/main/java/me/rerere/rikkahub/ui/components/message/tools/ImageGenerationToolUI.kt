package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.widget.Toast
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Download01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.AssetResolver
import me.rerere.rikkahub.data.files.AssetUri
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
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
    LaunchedEffect(url) {
        resolved = assetResolver.resolveForDisplay(url) ?: url
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
        val assetUri = context.content.getStringContent("asset_uri")
        val originalUri = context.content.getStringContent("original_asset_uri")
        val previewUri = context.content.getStringContent("preview_asset_uri")
        val legacyPaths = context.content.getStringContent("file_paths")
            ?: context.content.getStringContent("llm_preview")
        val imageUris = buildList {
            // 新协议只暴露 asset_uri（原图）。旧消息回退 original_asset_uri，最后才回退 preview。
            assetUri?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (isEmpty()) originalUri?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (isEmpty()) previewUri?.takeIf { it.isNotBlank() }?.let { add(it) }
            // 旧消息 / 未来真正的多图返回：tool output 里可能带 Image 部分。
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
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val filesManager = koinInject<FilesManager>()
        val assetResolver = koinInject<AssetResolver>()
        val androidContext = LocalContext.current
        val scope = rememberCoroutineScope()

        val imageParts = context.tool.output.filterIsInstance<UIMessagePart.Image>()
        val assetUri = context.content.getStringContent("asset_uri")
        val originalUri = context.content.getStringContent("original_asset_uri")
        val previewUri = context.content.getStringContent("preview_asset_uri")
        val legacyPaths = context.content.getStringContent("file_paths")
            ?: context.content.getStringContent("llm_preview")

        val imageUris = remember(context) {
            buildList {
                assetUri?.takeIf { it.isNotBlank() }?.let { add(it) }
                if (isEmpty()) originalUri?.takeIf { it.isNotBlank() }?.let { add(it) }
                if (isEmpty()) previewUri?.takeIf { it.isNotBlank() }?.let { add(it) }
                imageParts.map { it.url }.filter { it.isNotBlank() && it !in this }.forEach { add(it) }
                legacyPaths?.split("\n")?.filter { it.isNotBlank() && it !in this }?.forEach { add(it) }
            }
        }

        if (imageUris.isEmpty()) {
            DefaultToolPreview(context = context)
            return
        }

        var selectedUri by remember(imageUris) { mutableStateOf(imageUris.first()) }
        var resolvedUrl by remember(selectedUri) { mutableStateOf<String?>(null) }
        var isSaving by remember { mutableStateOf(false) }

        LaunchedEffect(selectedUri) {
            resolvedUrl = assetResolver.resolveForDisplay(selectedUri) ?: selectedUri
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val prompt = context.arguments.getStringContent("prompt")
            if (!prompt.isNullOrBlank()) {
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val selectedModel = rememberGeneratedImageModel(selectedUri)
            if (selectedModel != null) {
                ZoomableAsyncImage(
                    model = selectedModel,
                    contentDescription = prompt ?: "Generated Image",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )
            }

            if (imageUris.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    imageUris.forEach { uri ->
                        val thumb = rememberGeneratedImageModel(uri)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            border = if (uri == selectedUri) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier
                                .size(64.dp)
                                .clickable { selectedUri = uri },
                        ) {
                            if (thumb != null) {
                                AsyncImage(
                                    model = thumb,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = {
                        val urlToSave = resolvedUrl ?: return@FilledTonalButton
                        scope.launch {
                            isSaving = true
                            val success = runCatching {
                                filesManager.saveMessageImage(androidContext, urlToSave)
                                true
                            }.getOrElse { false }
                            isSaving = false
                            withContext(Dispatchers.Main) {
                                if (success) {
                                    Toast.makeText(
                                        androidContext,
                                        androidContext.getString(R.string.imggen_page_image_saved_success),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        androidContext,
                                        androidContext.getString(R.string.imggen_page_save_failed, ""),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    },
                    enabled = !isSaving && resolvedUrl != null,
                ) {
                    Icon(
                        imageVector = HugeIcons.Download01,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.imggen_page_save))
                }
            }
        }
    }
}
