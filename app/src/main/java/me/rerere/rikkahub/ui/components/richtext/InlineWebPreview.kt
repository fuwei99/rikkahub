package me.rerere.rikkahub.ui.components.richtext

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Eye
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.files.AppPaths
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.WebViewContentCache
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.modifier.onClick
import java.io.File

/**
 * 聊天里的「本地 HTML / SVG 文件」内联预览。
 *
 * 和 ```html / ```svg 代码块预览共用同一套东西：[InlineWebPreview] 渲染、
 * [buildWebPreviewHtml] 包装、[WebViewContentCache] + `Screen.WebView` 跳全屏。
 * 区别只有一个: 代码块的源码在消息里, 这里的源码在磁盘上, 所以要自己读文件。
 */

/** 能当网页直接跑的本地文件后缀 */
internal val WEB_PREVIEW_EXTENSIONS = setOf("html", "htm", "xhtml", "svg")

/** 折叠态高度: HTML 可能是一整页, 全铺开能把整轮对话顶飞 */
internal val WEB_PREVIEW_COLLAPSED_HEIGHT = 280.dp

/** 展开态占屏高比例与下限 */
private const val WEB_PREVIEW_EXPANDED_SCREEN_FRACTION = 0.7f
private val WEB_PREVIEW_EXPANDED_MIN_HEIGHT = 320.dp

/** 超过这个体积不读进内存 —— 把几十兆的 HTML 塞进 WebView 是自杀 */
private const val WEB_PREVIEW_MAX_BYTES = 2L * 1024 * 1024

/** 从路径/URI 里认出可预览的语言; 认不出返回 null */
internal fun webPreviewLanguageOf(path: String): String? {
    val clean = path.substringBefore('?').substringBefore('#').trim().lowercase()
    return clean.substringAfterLast('.', "").takeIf { it in WEB_PREVIEW_EXTENSIONS }
}

/**
 * 与代码块预览同一套包装: 裸 SVG 丢进居中 body, HTML 原样返回。
 */
internal fun buildWebPreviewHtml(code: String, language: String): String = if (language == "svg") {
    """<!DOCTYPE html><html><body style="margin:0;display:flex;justify-content:center;align-items:center;min-height:100vh;">$code</body></html>"""
} else {
    code
}

/**
 * 内联 WebView。baseUrl 传 `file://<目录>/` 时, 页面里的兄弟资源(图/CSS/JS)才找得到。
 *
 * 注意 targetSdk 30+ 之后 WebView 默认禁止 file:// 访问, 不显式打开的话本地页面里的
 * 相对图片会一片白。
 */
@Composable
internal fun InlineWebPreview(
    html: String,
    baseUrl: String? = null,
    modifier: Modifier = Modifier,
) {
    val localFileBase = baseUrl?.startsWith("file://") == true
    val state = rememberWebViewState(
        data = html,
        baseUrl = baseUrl,
        mimeType = "text/html",
        settings = {
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            if (localFileBase) {
                allowFileAccess = true
                allowContentAccess = true
            }
        },
    )

    WebView(
        state = state,
        modifier = modifier.clip(RoundedCornerShape(8.dp)),
    )
}

/**
 * 展开图标：上下两个尖角朝外（^ ∨）。
 * 原来挂在 HighlightCodeBlock 里, 现在两边共用, 所以挪到这儿。
 */
internal val ExpandPreviewIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ExpandPreview",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(8f, 9f); lineTo(12f, 5f); lineTo(16f, 9f)
            moveTo(8f, 15f); lineTo(12f, 19f); lineTo(16f, 15f)
        }
    }.build()
}

/** 折叠图标：两个对着的实心三角，尖头相对（fold） */
internal val FoldPreviewIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "FoldPreview",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(8f, 5f); lineTo(16f, 5f); lineTo(12f, 10f); close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(8f, 19f); lineTo(16f, 19f); lineTo(12f, 14f); close()
        }
    }.build()
}

/**
 * Markdown 图片位(`![alt](path)`)的统一出口:
 * - 目标是本地 html/svg 文件 → 内联网页预览（可展开、可跳全屏）
 * - 其它 → 老样子走 Coil 图片渲染, 一行代码没改
 */
@Composable
internal fun MarkdownMediaBlock(
    src: String,
    alt: String = "",
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val workspaceId = LocalMarkdownWorkspaceId.current
    val path = remember(src) { localPathOf(src) }
    // 远程 / content / data 一律不当网页预渲染, 老老实实交给 Coil
    val language = remember(src, path) {
        if (isRemoteOrInlineSource(src)) null else webPreviewLanguageOf(path)
    }
    val previewFile = remember(path, workspaceId, context) {
        language?.let { resolveAppLocalFile(context, path, workspaceId) }
    }

    when {
        language == null -> MarkdownImageBlock(src = src, alt = alt, modifier = modifier)
        previewFile == null -> MissingPreviewHint(path = path, modifier = modifier)
        else -> LocalWebPreviewBlock(
            file = previewFile,
            language = language,
            alt = alt,
            modifier = modifier,
        )
    }
}

/** 老路径: 图片走 Coil / asset, 一字未动 */
@Composable
private fun MarkdownImageBlock(
    src: String,
    alt: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val workspaceId = LocalMarkdownWorkspaceId.current
    val imageReferences = LocalImageReferences.current
    val imageModel = rememberMarkdownImageModel(context, src, workspaceId, imageReferences)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (imageModel == null) {
            MarkdownImageLoadingPlaceholder()
        } else {
            ZoomableAsyncImage(
                model = imageModel,
                contentDescription = alt.takeIf { it.isNotEmpty() },
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .widthIn(min = 120.dp)
                    .heightIn(min = 120.dp),
            )
        }
    }
}

private sealed interface WebPreviewLoad {
    data object Loading : WebPreviewLoad
    data class Ready(val html: String) : WebPreviewLoad
    data class TooLarge(val bytes: Long) : WebPreviewLoad
    data class Failed(val message: String) : WebPreviewLoad
}

@Composable
private fun LocalWebPreviewBlock(
    file: File,
    language: String,
    alt: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    // 兄弟资源靠它: 本地页面的相对 img/css/script 都从这里解
    val baseUrl = remember(file) { "file://${file.parentFile?.absolutePath.orEmpty()}/" }

    val loaded by produceState<WebPreviewLoad>(
        initialValue = WebPreviewLoad.Loading,
        key1 = file.absolutePath,
        key2 = language,
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    !file.isFile -> WebPreviewLoad.Failed("")
                    file.length() > WEB_PREVIEW_MAX_BYTES -> WebPreviewLoad.TooLarge(file.length())
                    else -> WebPreviewLoad.Ready(
                        buildWebPreviewHtml(code = file.readText(), language = language)
                    )
                }
            }.getOrElse { WebPreviewLoad.Failed(it.message.orEmpty()) }
        }
    }

    // SVG 默认铺开, HTML 默认收着 —— 与代码块预览同一个约定
    var expanded by remember(file.absolutePath) { mutableStateOf(language == "svg") }
    val expandedHeight = with(LocalConfiguration.current) {
        (screenHeightDp * WEB_PREVIEW_EXPANDED_SCREEN_FRACTION).dp
    }.coerceAtLeast(WEB_PREVIEW_EXPANDED_MIN_HEIGHT)

    Box(modifier = modifier.fillMaxWidth()) {
        when (val result = loaded) {
            is WebPreviewLoad.Ready -> {
                InlineWebPreview(
                    html = result.html,
                    baseUrl = baseUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (expanded) expandedHeight else WEB_PREVIEW_COLLAPSED_HEIGHT),
                )

                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PreviewActionIcon(
                        imageVector = if (expanded) FoldPreviewIcon else ExpandPreviewIcon,
                        contentDescription = if (expanded) "折叠" else "展开",
                    ) {
                        expanded = !expanded
                    }

                    PreviewActionIcon(
                        imageVector = HugeIcons.Eye,
                        contentDescription = "全屏预览",
                    ) {
                        val contentId = WebViewContentCache.store(context.cacheDir, result.html)
                        navController.navigate(Screen.WebView(contentId = contentId, baseUrl = baseUrl))
                    }
                }
            }

            WebPreviewLoad.Loading -> PreviewStatusBox(
                text = "加载 ${file.name}…",
                modifier = Modifier.height(WEB_PREVIEW_COLLAPSED_HEIGHT),
            )

            is WebPreviewLoad.TooLarge -> PreviewStatusBox(
                text = "文件太大（${result.bytes / 1024 / 1024} MB），不内联预览: ${file.name}",
                modifier = Modifier.height(WEB_PREVIEW_COLLAPSED_HEIGHT),
            )

            is WebPreviewLoad.Failed -> PreviewStatusBox(
                text = if (alt.isNotEmpty()) alt else "无法预览: ${file.name}",
                modifier = Modifier.height(WEB_PREVIEW_COLLAPSED_HEIGHT),
            )
        }
    }
}

@Composable
private fun PreviewStatusBox(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun MissingPreviewHint(path: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(
            text = "找不到文件: $path",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun PreviewActionIcon(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f))
            .onClick(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** 把 `file://` / URL 编码 / 尖括号包裹 的写法统一还原成裸路径 */
private fun localPathOf(raw: String): String {
    val value = raw.trim().trim('<', '>').removeSurrounding("\"").removeSurrounding("'")
    val decoded = Uri.decode(value)
    val uri = runCatching { decoded.toUri() }.getOrNull()
    return if (uri?.scheme.equals("file", ignoreCase = true)) uri?.path.orEmpty() else decoded
}

private fun isRemoteOrInlineSource(raw: String): Boolean {
    val value = raw.trim().lowercase()
    return value.startsWith("http://") ||
        value.startsWith("https://") ||
        value.startsWith("content://") ||
        value.startsWith("data:")
}

/**
 * 与 Markdown 图片同一套本地路径映射（workspace / obsidian / BaiduNetdisk / app filesDir）。
 * 原来叫 mapAppLocalImage, 现在多一个调用方, 顺手改成名副其实的名字并挪到本文件。
 */
internal fun resolveAppLocalFile(context: Context, path: String, workspaceId: String?): File? {
    val normalized = path.replace('\\', '/').trim()
    if (normalized.isBlank()) return null

    fun fileIfExists(file: File): File? = file.takeIf { it.isFile }

    // 优先映射外部挂载目录（Obsidian / BaiduNetdisk 等多端挂载路径）
    val externalMountMappings = listOf(
        listOf("/mnt/obsidian/", "obsidian/", "/storage/emulated/0/obsidian/", "/sdcard/obsidian/") to listOf(
            File("/mnt/obsidian"),
            File("/storage/emulated/0/obsidian"),
        ),
        listOf("/mnt/BaiduNetdisk/", "BaiduNetdisk/", "/storage/emulated/0/Download/BaiduNetdisk/", "/sdcard/Download/BaiduNetdisk/") to listOf(
            File("/mnt/BaiduNetdisk"),
            File("/storage/emulated/0/Download/BaiduNetdisk"),
        ),
    )

    for ((prefixes, targetDirs) in externalMountMappings) {
        val matchedPrefix = prefixes.find { normalized.startsWith(it, ignoreCase = true) }
        if (matchedPrefix != null) {
            val relative = normalized.substring(matchedPrefix.length)
            if (relative.isNotBlank() && !relative.contains("../")) {
                for (dir in targetDirs) {
                    fileIfExists(File(dir, relative))?.let { return it }
                }
            }
        }
    }

    if (workspaceId != null) {
        val workspacePrefix = "/workspace"
        val isExplicitWorkspace = normalized == workspacePrefix || normalized.startsWith("$workspacePrefix/")
        val isRelativePath = !normalized.startsWith("/") && externalMountMappings.none { (prefixes, _) ->
            prefixes.any { normalized.startsWith(it, ignoreCase = true) }
        }

        if (isExplicitWorkspace || isRelativePath) {
            val relative = if (isExplicitWorkspace) {
                if (normalized == workspacePrefix) "" else normalized.removePrefix("$workspacePrefix/")
            } else {
                normalized
            }
            if (relative.isNotBlank() && !relative.contains("../")) {
                fileIfExists(File(AppPaths.workspacesDir(context), "$workspaceId/files/$relative"))?.let { return it }
            }
        }
    }

    val appRelative = normalized.trimStart('/')
    listOf(
        appRelative,
        "upload/$appRelative",
        "images/$appRelative",
        "avatars/$appRelative",
        "tool_outputs/$appRelative",
    ).distinct().forEach { relative ->
        fileIfExists(File(AppPaths.filesDir(context), relative))?.let { return it }
    }

    if (normalized.startsWith("/")) {
        fileIfExists(File(normalized))?.let { return it }
    }
    return null
}