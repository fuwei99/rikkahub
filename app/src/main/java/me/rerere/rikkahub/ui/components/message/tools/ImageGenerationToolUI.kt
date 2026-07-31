package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.hugeicons.stroke.Download01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.AssetResolver
import me.rerere.rikkahub.data.files.AssetUri
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.utils.JsonInstantPretty
import org.koin.compose.koinInject

@Composable
private fun rememberGeneratedImageModel(
    url: String,
    assetResolver: AssetResolver = koinInject(),
): String? {
    val assetId = remember(url) { AssetUri.parse(url) }
    var resolved by remember(url) { mutableStateOf<String?>(null) }
    LaunchedEffect(url, assetId) {
        resolved = if (assetId != null) assetResolver.resolveForDisplay(assetId) else url
    }
    return resolved
}

private fun imageUris(context: ToolUIContext): List<String> {
    val imageParts = context.tool.output.filterIsInstance<UIMessagePart.Image>()
    val assetUri = context.content.getStringContent("asset_uri")
    val originalUri = context.content.getStringContent("original_asset_uri")
    val previewUri = context.content.getStringContent("preview_asset_uri")
    val legacyPaths = context.content.getStringContent("file_paths")
        ?: context.content.getStringContent("llm_preview")
    return buildList {
        assetUri?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (isEmpty()) originalUri?.takeIf { it.isNotBlank() }?.let { add(it) }
        previewUri?.takeIf { it.isNotBlank() && it !in this }?.let { add(it) }
        imageParts.map { it.url }.filter { it.isNotBlank() && it !in this }.forEach { add(it) }
        legacyPaths?.split("\n")?.filter { it.isNotBlank() && it !in this }?.forEach { add(it) }
    }
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
        val imageUris = imageUris(context)
        if (imageUris.isEmpty()) {
            Text(
                text = "正在生成中，请稍候...",
                style = MaterialTheme.typography.labelSmall
            )
            return
        }

        var selected by remember(imageUris) { mutableStateOf(imageUris.first()) }
        val selectedModel = rememberGeneratedImageModel(selected)
        val filesManager = koinInject<FilesManager>()
        val androidContext = LocalContext.current
        val scope = rememberCoroutineScope()
        var showPreview by remember { mutableStateOf(false) }

        if (showPreview && selectedModel != null) {
            ImagePreviewDialog(
                images = listOf(selectedModel),
                onDismissRequest = { showPreview = false }
            )
        }

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
                        .clickable { showPreview = true }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                FilledTonalButton(
                    onClick = {
                        val urlToSave = selectedModel ?: return@FilledTonalButton
                        scope.launch {
                            val success = runCatching {
                                filesManager.saveMessageImage(androidContext, urlToSave)
                                true
                            }.getOrElse { false }
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    androidContext,
                                    if (success) androidContext.getString(R.string.imggen_page_image_saved_success)
                                    else androidContext.getString(R.string.imggen_page_save_failed, ""),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    enabled = selectedModel != null,
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

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val filesManager = koinInject<FilesManager>()
        val assetResolver = koinInject<AssetResolver>()
        val androidContext = LocalContext.current
        val scope = rememberCoroutineScope()
        val imageUris = remember(context) { imageUris(context) }

        if (imageUris.isEmpty()) {
            DefaultToolPreview(context = context)
            return
        }

        var selectedUri by remember(imageUris) { mutableStateOf(imageUris.first()) }
        var resolvedUrl by remember(selectedUri) { mutableStateOf<String?>(null) }
        var isSaving by remember { mutableStateOf(false) }

        LaunchedEffect(selectedUri) {
            val assetId = AssetUri.parse(selectedUri)
            resolvedUrl = if (assetId != null) assetResolver.resolveForDisplay(assetId) else selectedUri
        }

        Column(
            modifier = Modifier
                .fillMaxHeight(0.88f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("工具调用", style = MaterialTheme.typography.headlineSmall)
            Text("调用工具 ${context.tool.toolName}", style = MaterialTheme.typography.titleMedium)
            HighlightCodeBlock(
                code = JsonInstantPretty.encodeToString(context.arguments),
                language = "json",
                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp),
            )

            Text("调用结果", style = MaterialTheme.typography.titleMedium)
            val selectedModel = rememberGeneratedImageModel(selectedUri)
            if (selectedModel != null) {
                ZoomableAsyncImage(
                    model = selectedModel,
                    contentDescription = context.arguments.getStringContent("prompt") ?: "Generated Image",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
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

            context.content?.let { content ->
                HighlightCodeBlock(
                    code = JsonInstantPretty.encodeToString(content),
                    language = "json",
                    style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp),
                )
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
                                Toast.makeText(
                                    androidContext,
                                    if (success) androidContext.getString(R.string.imggen_page_image_saved_success)
                                    else androidContext.getString(R.string.imggen_page_save_failed, ""),
                                    Toast.LENGTH_SHORT,
                                ).show()
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
