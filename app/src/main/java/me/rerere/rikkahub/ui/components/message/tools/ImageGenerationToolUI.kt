package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.AssetResolver
import me.rerere.rikkahub.data.files.AssetUri
import org.koin.compose.koinInject
import java.io.File

/**
 * 图像生成工具 UI 渲染器
 * 当大模型调用 image_generation 触发生图时，
 * 在工具卡片或对话流中展示渲染生成的图像。
 */
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
    override fun title(context: ToolUIContext): String {
        return "图像生成: " + (context.arguments.getStringContent("prompt") ?: "")
    }

    override fun hasSummary(context: ToolUIContext): Boolean = true

    @Composable
    override fun Summary(context: ToolUIContext) {
        val imageParts = context.tool.output.filterIsInstance<UIMessagePart.Image>()
        val filePaths = context.content.getStringContent("file_paths")
            ?: context.content.getStringContent("llm_preview")
        val urls = imageParts.map { it.url }.ifEmpty {
            filePaths?.split("\n")?.filter { it.isNotBlank() }.orEmpty()
        }
        if (urls.isNotEmpty()) {
            urls.forEach { path ->
                val model = rememberGeneratedImageModel(path)
                if (model != null) {
                    AsyncImage(
                        model = model,
                        contentDescription = "Generated Image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }
            }
        } else {
            Text(
                text = "正在生成中，请稍候...",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall
            )
        }
    }
}
