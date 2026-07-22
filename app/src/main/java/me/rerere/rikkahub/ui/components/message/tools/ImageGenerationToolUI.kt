package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.rikkahub.R
import java.io.File

/**
 * 图像生成工具 UI 渲染器
 * 当大模型调用 image_generation 触发生图时，
 * 在工具卡片或对话流中展示渲染生成的图像。
 */
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
        val filePaths = context.content.getStringContent("file_paths")
        if (!filePaths.isNullOrBlank()) {
            val list = filePaths.split("\n").filter { it.isNotBlank() }
            list.forEach { path ->
                AsyncImage(
                    model = File(path),
                    contentDescription = "Generated Image",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                )
            }
        } else {
            Text(
                text = "正在生成中，请稍候...",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall
            )
        }
    }
}
