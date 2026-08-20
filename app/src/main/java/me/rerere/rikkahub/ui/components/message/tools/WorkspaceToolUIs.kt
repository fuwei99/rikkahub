package me.rerere.rikkahub.ui.components.message.tools

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.metadataAs
import me.rerere.common.http.jsonArrayOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.highlight.HighlightText
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.FileAdd
import me.rerere.hugeicons.stroke.FileEdit
import me.rerere.hugeicons.stroke.FileView
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.AssetResolver
import me.rerere.rikkahub.data.files.AssetUri
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.richtext.DiffAddedColor
import me.rerere.rikkahub.ui.components.richtext.DiffRemovedColor
import me.rerere.rikkahub.ui.components.richtext.DiffView
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.richtext.parseDiffStats
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import org.koin.compose.koinInject

@Composable
private fun rememberAssetImageModel(
    url: String,
    assetResolver: AssetResolver = koinInject(),
): Any? {
    val assetId = remember(url) { AssetUri.parse(url) }
    var resolved by remember(url) { mutableStateOf<String?>(null) }
    LaunchedEffect(url, assetId) {
        resolved = if (assetId != null) assetResolver.resolveForDisplay(url) else url
    }
    return resolved
}

/**
 * 外部 MCP server 放 unified diff 文本的候选键名。
 *
 * 它们没有 metadata 通道（只能回 JSON 字符串），且各家 server 自己起名:
 * termux 侧叫 `diff_preview`，别的实现常见 `diff` / `unified_diff`。
 * 全试一遍比逼每个 server 改名现实。
 */
private val MCP_DIFF_KEYS = listOf("diff_preview", "diff", "unified_diff")

/**
 * 由 edit 工具的**入参**合成预览 diff, 单编辑与批量 `edits` 数组都覆盖; 无法合成时返回 null。
 *
 * 抽成顶层 internal 函数是为了能单测: 这里的分支（批量/单编辑/字段缺失）正是
 * 2026-08-21「批量编辑气泡空白」的事故点，必须有回归钉子钉住。
 *
 * @param arguments 工具入参 JSON
 */
internal fun buildEditPreviewDiff(arguments: JsonElement): String? {
    val path = arguments.getStringContent("path") ?: "(unknown path)"
    val singleOld = arguments.getStringContent("old_text")
    val singleNew = arguments.getStringContent("new_text")
    if (singleOld != null && singleNew != null) {
        return generateUnifiedDiff(singleOld, singleNew, path)
    }
    val edits = arguments.jsonObjectOrNull?.get("edits")?.jsonArrayOrNull ?: return null
    if (edits.isEmpty()) return null
    val chunks = edits.mapIndexedNotNull { index, element ->
        val old = element.getStringContent("old_text") ?: return@mapIndexedNotNull null
        val new = element.getStringContent("new_text") ?: return@mapIndexedNotNull null
        // 每条编辑单独生成一段, 文件头带上 i/n: 批量改同一个文件时, 光看 hunk 分不清是第几处。
        generateUnifiedDiff(old, new, "$path (edit ${index + 1}/${edits.size})")
    }
    return chunks.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

/** 入参声明的编辑处数, 非批量 `edits` 模式返回 null */
internal fun editOpCount(arguments: JsonElement): Int? =
    arguments.jsonObjectOrNull?.get("edits")?.jsonArrayOrNull?.size?.takeIf { it > 0 }

/**
 * 工作空间编辑文件: 摘要显示增删统计与精简 diff, 详情为完整 diff view
 */
object EditFileToolUI : ToolUIRenderer {
    private const val SUMMARY_MAX_LINES = 10

    /**
     * 批量 `edits` 摘要的行数上限。
     *
     * 每段自带 2 行文件头 + 1 行 @@，10 行只够看第一处编辑；批量的重点恰是「几处分别改了什么」，
     * 所以按处数放宽，再由 ChatMessageTools 的 220dp 封顶 + 内部滚动兜住高度。
     */
    private const val SUMMARY_MAX_LINES_PER_EDIT = 8
    private const val SUMMARY_BATCH_MAX_LINES = 40

    override val toolName: String = "workspace_edit_file"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.FileEdit

    @Composable
    override fun title(context: ToolUIContext): String {
        val path = context.arguments.getStringContent("path")
        return if (path != null) stringResource(R.string.tool_ui_edit_file, path) else stringResource(R.string.tool_ui_edit_file_default)
    }

    /**
     * 取本次编辑的 diff, 四级 fallback:
     *
     * 1. 输出部件 metadata 里的全文件 diff（本地工具执行成功的正路）;
     * 2. 输出 JSON 里的 diff 文本（外部 MCP server 没有 metadata 通道, 只能给字符串;
     *    键名各家不一, 见 [MCP_DIFF_KEYS]）;
     * 3. 入参顶层 old_text/new_text（单编辑模式的预览）;
     * 4. 入参 `edits` 数组逐条合成预览（批量编辑模式）。
     *
     * 第 4 条是 2026-08-21 补的：`edits` 批量模式下顶层**没有** old_text/new_text，
     * 老实现在第 3 条直接 return null → hasSummary=false → 卡片整块空白。
     * 后果不只是丑：等待审批的批量编辑也看不到改动，等于闭眼点同意。
     * 所以预览 diff 必须与「执行成功与否」「有无 metadata」解耦。
     */
    private fun diffOf(context: ToolUIContext): String? {
        if (context.tool.isExecuted) {
            context.tool.output.firstOrNull()?.metadataAs<DiffMetadata>()?.diff
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
            MCP_DIFF_KEYS.forEach { key ->
                context.content.getStringContent(key)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return previewDiffOf(context)
    }

    /**
     * 仅由入参合成的预览 diff（不看输出）, 单编辑与批量 `edits` 都覆盖。
     * 实现见 [buildEditPreviewDiff]（抽到顶层是为了可单测）。
     */
    private fun previewDiffOf(context: ToolUIContext): String? =
        buildEditPreviewDiff(context.arguments)

    /** 入参声明的编辑处数, 非批量模式返回 null */
    private fun editCountOf(context: ToolUIContext): Int? = editOpCount(context.arguments)

    override fun hasSummary(context: ToolUIContext): Boolean = diffOf(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val diff = remember(context) { diffOf(context) } ?: return
        val stats = remember(diff) { parseDiffStats(diff) }
        val editCount = remember(context) { editCountOf(context) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 批量模式先标出处数：+/- 汇总看不出"一次动了几处"，而这正是批量编辑最该先确认的规模。
            if (editCount != null) {
                Text(
                    text = "×$editCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "+${stats.additions}",
                style = MaterialTheme.typography.labelSmall,
                color = DiffAddedColor,
            )
            Text(
                text = "-${stats.deletions}",
                style = MaterialTheme.typography.labelSmall,
                color = DiffRemovedColor,
            )
        }
        DiffView(
            diff = diff,
            modifier = Modifier.fillMaxWidth(),
            maxLines = if (editCount != null) {
                (editCount * SUMMARY_MAX_LINES_PER_EDIT).coerceAtMost(SUMMARY_BATCH_MAX_LINES)
            } else {
                SUMMARY_MAX_LINES
            },
            // 批量模式保留文件头：多段拼接时那行 `--- a/<path> (edit i/n)` 就是分隔标签，
            // 砍掉它第一段会和第二段糊在一起，反而看不出边界。单编辑仍然砍掉省两行。
            showFileHeader = editCount != null,
        )
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val diff = remember(context) { diffOf(context) }
        if (diff == null) {
            DefaultToolPreview(context = context)
            return
        }
        val stats = remember(diff) { parseDiffStats(diff) }
        val editCount = remember(context) { editCountOf(context) }
        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = context.arguments.getStringContent("path") ?: toolName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (editCount != null) {
                    Text(
                        text = "×$editCount",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "+${stats.additions}",
                    style = MaterialTheme.typography.labelMedium,
                    color = DiffAddedColor,
                )
                Text(
                    text = "-${stats.deletions}",
                    style = MaterialTheme.typography.labelMedium,
                    color = DiffRemovedColor,
                )
            }
            DiffView(
                diff = diff,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 工作空间读取文件: 摘要显示内容首部预览, 详情为带语法高亮的完整内容
 */
object ReadFileToolUI : ToolUIRenderer {
    override val toolName: String = "workspace_read_file"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.FileView

    @Composable
    override fun title(context: ToolUIContext): String {
        val path = context.arguments.getStringContent("path")
        if (path != null) return stringResource(R.string.tool_ui_read_file, path)
        // 批量模式: 显示 "首个路径 +N"
        val paths = context.arguments.jsonObjectOrNull?.get("paths")?.jsonArrayOrNull
            ?.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
            .orEmpty()
        if (paths.isNotEmpty()) {
            val label = if (paths.size == 1) paths[0] else "${paths[0]} +${paths.size - 1}"
            return stringResource(R.string.tool_ui_read_file, label)
        }
        return stringResource(R.string.tool_ui_read_file_default)
    }

    private fun assetUriOf(context: ToolUIContext): String? =
        context.content.getStringContent("asset_uri")

    override fun hasSummary(context: ToolUIContext): Boolean =
        assetUriOf(context) != null || readFileEntriesOf(context).any { it.text != null }

    @Composable
    override fun Summary(context: ToolUIContext) {
        val assetUri = remember(context) { assetUriOf(context) }
        if (assetUri != null) {
            val model = rememberAssetImageModel(assetUri)
            val filesManager = koinInject<FilesManager>()
            val androidContext = LocalContext.current
            val scope = rememberCoroutineScope()
            var showPreview by remember { mutableStateOf(false) }
            if (showPreview && model != null) {
                ImagePreviewDialog(
                    images = listOf(model.toString()),
                    onDismissRequest = { showPreview = false }
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (model != null) {
                    AsyncImage(
                        model = model,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { showPreview = true }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    FilledTonalButton(
                        onClick = {
                            val urlToSave = model?.toString() ?: return@FilledTonalButton
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
                        enabled = model != null,
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
            return
        }
        val entries = remember(context) { readFileEntriesOf(context) }
        val single = entries.singleOrNull()
        if (single != null) {
            val text = single.text ?: return
            FileContentSummary(
                text = text,
                path = single.path ?: context.arguments.getStringContent("path"),
                loading = context.loading,
            )
            return
        }
        // 批量读取: 每个文件一小段, 带路径标题
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            entries.forEach { entry ->
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entry.error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                entry.text?.let { text ->
                    FileContentSummary(
                        text = text,
                        path = entry.path,
                        loading = context.loading,
                        maxLines = BATCH_SUMMARY_MAX_LINES,
                    )
                }
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val assetUri = remember(context) { assetUriOf(context) }
        // 图片读取: 保持原有 JSON + 图片预览布局不变
        if (assetUri != null) {
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
                val model = rememberAssetImageModel(assetUri)
                if (model != null) {
                    ZoomableAsyncImage(
                        model = model.toString(),
                        contentDescription = context.arguments.getStringContent("path"),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .clip(MaterialTheme.shapes.medium),
                    )
                }

                context.content?.let { content ->
                    HighlightCodeBlock(
                        code = JsonInstantPretty.encodeToString(content),
                        language = "json",
                        style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp),
                    )
                }
            }
            return
        }

        // 文本读取: 直接展示文件内容; 读取失败或没读到东西才退回原始 JSON
        val entries = remember(context) { readFileEntriesOf(context) }
        if (entries.none { it.text != null }) {
            DefaultToolPreview(context = context)
            return
        }
        Column(
            modifier = Modifier
                .fillMaxHeight(0.88f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            entries.forEach { entry ->
                ReadFileSection(entry = entry)
            }
        }
    }
}

/** 一次 workspace_read_file 输出中的单个文件条目 (单文件与 paths 批量统一模型) */
private data class ReadFileEntry(
    val path: String?,
    val text: String?,
    val error: String?,
    val startLine: Int?,
    val endLine: Int?,
    val totalLines: Int?,
    val truncated: Boolean,
) {
    /** 标题展示: 路径 (读取失败时附带错误) */
    val displayName: String
        get() = when {
            error != null -> "${path ?: "?"} — $error"
            else -> path ?: "?"
        }

    /** 行号范围提示, 如 "150-219 / 1923" */
    val rangeLabel: String?
        get() {
            if (startLine == null || endLine == null) return null
            val total = totalLines?.let { " / $it" }.orEmpty()
            return "$startLine-$endLine$total" + if (truncated) " …" else ""
        }
}

private fun JsonElement?.toReadFileEntry(fallbackPath: String?): ReadFileEntry {
    val start = int("start_line")
    // 外部 MCP server 的字段名不同: 正文叫 content, 只给 returned_lines 不给 end_line
    val text = getStringContent("text")
        ?: getStringContent("content")
        ?: this?.jsonObjectOrNull?.get("entries")?.jsonArrayOrNull
            ?.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
            ?.joinToString("\n")
    val returned = int("returned_lines")
    return ReadFileEntry(
        path = getStringContent("path") ?: fallbackPath,
        text = text,
        error = getStringContent("error"),
        startLine = start,
        endLine = int("end_line")
            ?: if (start != null && returned != null) start + returned - 1 else null,
        totalLines = int("total_lines"),
        truncated = boolean("truncated") ?: false,
    )
}

/** 解析读取输出: 兼容单文件 {text:…} 与批量 {files:[…]} 两种形状 */
private fun readFileEntriesOf(context: ToolUIContext): List<ReadFileEntry> {
    val content = context.content ?: return emptyList()
    val batch = content.jsonObjectOrNull?.get("files")?.jsonArrayOrNull
    if (batch != null) {
        return batch.map { it.toReadFileEntry(null) }
    }
    val single = content.toReadFileEntry(context.arguments.getStringContent("path"))
    return if (single.text != null || single.error != null) listOf(single) else emptyList()
}

/** BottomSheet 内的单文件区块: 路径 + 行号范围 + 高亮全文 (失败则显示错误) */
@Composable
private fun ReadFileSection(entry: ReadFileEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = entry.path ?: stringResource(R.string.tool_ui_file),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            entry.rangeLabel?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                )
            }
        }
        val error = entry.error
        if (error != null && entry.text == null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            return@Column
        }
        HighlightCodeBlock(
            code = entry.text.orEmpty(),
            language = languageOf(entry.path),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
/**
 * 工作空间写入文件: 内容取自入参 (未执行也可预览), 摘要为内容首部, 详情为完整内容
 */
object WriteFileToolUI : ToolUIRenderer {
    override val toolName: String = "workspace_write_file"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.FileAdd

    @Composable
    override fun title(context: ToolUIContext): String {
        val path = context.arguments.getStringContent("path")
        return if (path != null) stringResource(R.string.tool_ui_write_file, path) else stringResource(R.string.tool_ui_write_file_default)
    }

    private fun textOf(context: ToolUIContext): String? =
        context.arguments.getStringContent("text")

    override fun hasSummary(context: ToolUIContext): Boolean = textOf(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val text = remember(context) { textOf(context) } ?: return
        FileContentSummary(
            text = text,
            path = context.arguments.getStringContent("path"),
            loading = context.loading,
        )
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val text = remember(context) { textOf(context) }
        if (text == null) {
            DefaultToolPreview(context = context)
            return
        }
        FileContentPreview(path = context.arguments.getStringContent("path"), code = text)
    }
}

/**
 * 补丁工具 (workspace_apply_patch / workspace_codex_patch / MCP apply_patch)。
 *
 * 补丁此前没有专属渲染器, 只能退化成 JSON。这里的 diff 来源三档:
 * 1. 已执行: 输出 metadata 里的 before/after 全量 diff (内置工具);
 * 2. 已执行但无 metadata: 输出 JSON 的 diff_preview / stdout (外部 MCP);
 * 3. 未执行(等待审批): 直接把入参里的 patch 文本当 diff 渲染 —— 它本来就是 unified diff。
 */
object PatchToolUI : BasePatchToolUI() {
    override val toolName: String = "workspace_apply_patch"
}

/** Codex 文件式补丁: 与 unified diff 同一套渲染, 只是 diff 文本要先翻译一遍 */
object CodexPatchToolUI : BasePatchToolUI() {
    override val toolName: String = "workspace_codex_patch"
}

/** 补丁摘要里最多渲染的 diff 行数 */
private const val SUMMARY_PATCH_MAX_LINES = 12

abstract class BasePatchToolUI : ToolUIRenderer {
    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.FileEdit

    @Composable
    override fun title(context: ToolUIContext): String {
        val files = patchedFilesOf(context)
        val label = when {
            files.isEmpty() -> null
            files.size == 1 -> files[0].substringAfterLast('/')
            else -> "${files[0].substringAfterLast('/')} +${files.size - 1}"
        }
        val isCodex = context.tool.toolName.contains("codex")
        return when {
            label != null && isCodex -> stringResource(R.string.tool_ui_codex_patch, label)
            label != null -> stringResource(R.string.tool_ui_apply_patch, label)
            isCodex -> stringResource(R.string.tool_ui_codex_patch_default)
            else -> stringResource(R.string.tool_ui_apply_patch_default)
        }
    }

    /** 受影响文件: 优先输出 JSON 的 files, 否则从 patch 文本的 diff 头里扒 */
    private fun patchedFilesOf(context: ToolUIContext): List<String> {
        val fromOutput = context.content?.jsonObjectOrNull?.get("files")?.jsonArrayOrNull
            ?.mapNotNull { el ->
                el.jsonPrimitiveOrNull?.contentOrNull ?: el.getStringContent("path")
            }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (fromOutput.isNotEmpty()) return fromOutput.distinct()
        val patch = context.arguments.getStringContent("patch") ?: return emptyList()
        return patch.lineSequence()
            .map { it.trim() }
            .mapNotNull { line ->
                when {
                    line.startsWith("+++ ") -> line.removePrefix("+++ ").trim()
                    // Move to 优先于 Update File: 重命名后展示目标路径更贴近结果
                    line.startsWith("*** Move to:") -> line.removePrefix("*** Move to:").trim()
                    line.startsWith("*** Update File:") -> line.removePrefix("*** Update File:").trim()
                    line.startsWith("*** Add File:") -> line.removePrefix("*** Add File:").trim()
                    line.startsWith("*** Delete File:") -> line.removePrefix("*** Delete File:").trim()
                    else -> null
                }
            }
            .filterNot { it == "/dev/null" || it.isBlank() }
            .map { it.removePrefix("b/").substringBefore('\t') }
            .distinct()
            .toList()
    }

    private fun diffOf(context: ToolUIContext): String? {
        if (context.tool.isExecuted) {
            context.tool.output.firstOrNull()?.metadataAs<DiffMetadata>()?.diff
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
            MCP_DIFF_KEYS.forEach { key ->
                context.content.getStringContent(key)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        // 审批阶段只有入参: unified diff 可直接渲染, codex 格式需先转成 unified 形状
        val patch = context.arguments.getStringContent("patch")?.takeIf { it.isNotBlank() } ?: return null
        return if (patch.isCodexPatch()) codexPatchToUnifiedDiff(patch) else patch
    }

    override fun hasSummary(context: ToolUIContext): Boolean = diffOf(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val diff = remember(context) { diffOf(context) } ?: return
        val stats = remember(diff) { parseDiffStats(diff) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PatchStatusLabel(context, MaterialTheme.typography.labelSmall)
                Text(
                    text = "+${stats.additions}",
                    style = MaterialTheme.typography.labelSmall,
                    color = DiffAddedColor,
                )
                Text(
                    text = "-${stats.deletions}",
                    style = MaterialTheme.typography.labelSmall,
                    color = DiffRemovedColor,
                )
            }
            DiffView(
                diff = diff,
                modifier = Modifier.fillMaxWidth(),
                maxLines = SUMMARY_PATCH_MAX_LINES,
            )
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val diff = remember(context) { diffOf(context) }
        if (diff == null) {
            DefaultToolPreview(context = context)
            return
        }
        val stats = remember(diff) { parseDiffStats(diff) }
        val files = remember(context) { patchedFilesOf(context) }
        val reason = context.content.getStringContent("reason")
        Column(
            modifier = Modifier
                .fillMaxHeight(0.85f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (context.tool.toolName.contains("codex")) {
                        stringResource(R.string.tool_ui_codex_patch_default)
                    } else {
                        stringResource(R.string.tool_ui_apply_patch_default)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                PatchStatusLabel(context, MaterialTheme.typography.labelMedium)
                Text(
                    text = "+${stats.additions}",
                    style = MaterialTheme.typography.labelMedium,
                    color = DiffAddedColor,
                )
                Text(
                    text = "-${stats.deletions}",
                    style = MaterialTheme.typography.labelMedium,
                    color = DiffRemovedColor,
                )
            }
            if (files.isNotEmpty()) {
                Text(
                    text = files.joinToString("\n"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (!reason.isNullOrBlank()) {
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            DiffView(
                diff = diff,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Codex 补丁 → unified diff 形状的文本, 仅用于渲染。
 *
 * Codex 格式的 `*** Begin Patch` / `*** Update File:` 这些标记行 [DiffView] 不认,
 * 会当普通上下文行渲染成一片灰; 而 hunk 体本身的 ` `/`+`/`-` 前缀与 unified diff 完全一致。
 * 所以只需把文件标记翻译成 `--- a/x` / `+++ b/x` 头、把范围省略的 hunk 头写成 `@@`,
 * 内容行原样透传, 就能复用同一个着色器。**这不是解析器**: 不校验、不落盘,
 * 渲染失真远好过审批时看一坨没颜色的文本(真正的解析在 WorkspaceTools.parseCodexPatch)。
 */
private fun String.isCodexPatch(): Boolean =
    lineSequence().firstOrNull { it.isNotBlank() }?.trim() == "*** Begin Patch"

private fun codexPatchToUnifiedDiff(patch: String): String = buildString {
    var pendingOldPath: String? = null
    patch.replace("\r\n", "\n").lineSequence().forEach { raw ->
        val line = raw.trim()
        when {
            line == "*** Begin Patch" || line == "*** End Patch" -> {}

            line.startsWith("*** Add File:") -> {
                val path = line.removePrefix("*** Add File:").trim()
                appendLine("--- /dev/null")
                appendLine("+++ b/$path")
                appendLine("@@")
                pendingOldPath = null
            }

            line.startsWith("*** Delete File:") -> {
                val path = line.removePrefix("*** Delete File:").trim()
                appendLine("--- a/$path")
                appendLine("+++ /dev/null")
                pendingOldPath = null
            }

            // Update 后面可能紧跟 `*** Move to:`，所以先记下旧路径，等下一行决定新路径
            line.startsWith("*** Update File:") -> {
                pendingOldPath = line.removePrefix("*** Update File:").trim()
            }

            line.startsWith("*** Move to:") -> {
                val newPath = line.removePrefix("*** Move to:").trim()
                appendLine("--- a/${pendingOldPath ?: newPath}")
                appendLine("+++ b/$newPath")
                pendingOldPath = null
            }

            line.startsWith("@@") -> {
                // 到 hunk 头才说明前面的 Update 没有 Move to, 此时补出同路径的文件头
                pendingOldPath?.let { path ->
                    appendLine("--- a/$path")
                    appendLine("+++ b/$path")
                    pendingOldPath = null
                }
                // codex 的 @@ 后面跟的是上下文锚点(函数名等), 保留它比丢掉更有信息量
                appendLine(line)
            }

            line == "*** End of File" -> {}

            else -> {
                pendingOldPath?.let { path ->
                    appendLine("--- a/$path")
                    appendLine("+++ b/$path")
                    appendLine("@@")
                    pendingOldPath = null
                }
                appendLine(raw)
            }
        }
    }
}.trimEnd('\n')

/** 补丁结果标签: 成功/失败/试运行; 未执行时不显示 */@Composable
private fun PatchStatusLabel(context: ToolUIContext, style: TextStyle) {
    val content = context.content ?: return
    val applied = content.boolean("applied") ?: content.boolean("ok")
    val dryRun = content.boolean("dry_run") ?: false
    Text(
        text = when {
            dryRun -> stringResource(R.string.tool_ui_patch_dry_run)
            applied == true -> stringResource(R.string.tool_ui_patch_applied)
            applied == false -> stringResource(R.string.tool_ui_patch_failed)
            else -> return
        },
        style = style,
        color = if (applied == false) MaterialTheme.colorScheme.error else DiffAddedColor,
    )
}

/** 内联摘要: 按扩展名语法高亮展示文件内容首部若干行 */
@Composable
private fun FileContentSummary(
    text: String,
    path: String?,
    loading: Boolean,
    maxLines: Int = FILE_SUMMARY_MAX_LINES,
) {
    val preview = remember(text, maxLines) {
        text.lineSequence().take(maxLines).joinToString("\n")
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .shimmer(isLoading = loading),
    ) {
        HighlightText(
            code = preview,
            language = languageOf(path),
            fontSize = 11.sp,
            lineHeight = 14.sp,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** BottomSheet 详情: 文件路径 + 按扩展名语法高亮的完整内容 */
@Composable
private fun FileContentPreview(path: String?, code: String) {
    Column(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = path ?: stringResource(R.string.tool_ui_file),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        HighlightCodeBlock(
            code = code,
            language = languageOf(path),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 工作空间执行 Shell: 摘要显示退出状态与输出首部, 详情为命令 + stdout/stderr
 */
object ShellToolUI : ToolUIRenderer {
    private const val TITLE_MAX_CHARS = 40
    private const val SUMMARY_MAX_LINES = 8

    override val toolName: String = "workspace_shell"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.ComputerTerminal01

    @Composable
    override fun title(context: ToolUIContext): String {
        val command = context.arguments.getStringContent("command") ?: return stringResource(R.string.tool_ui_shell_default)
        val preview = command.replace("\n", " ").trim()
        val truncated = if (preview.length > TITLE_MAX_CHARS) preview.take(TITLE_MAX_CHARS) + "…" else preview
        return stringResource(R.string.tool_ui_shell, truncated)
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val content = context.content ?: return
        val combined = remember(content) {
            // 外部 MCP 的 shell_session 只给 output 一个流
            listOf(
                content.getStringContent("stdout") ?: content.getStringContent("output"),
                content.getStringContent("stderr"),
            )
                .filterNot { it.isNullOrBlank() }
                .joinToString("\n")
                .trim()
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ShellExitStatus(content, MaterialTheme.typography.labelSmall)
            if (combined.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .shimmer(isLoading = context.loading),
                ) {
                    Text(
                        text = combined.lineSequence().take(SUMMARY_MAX_LINES).joinToString("\n"),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = SUMMARY_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        if (content == null) {
            DefaultToolPreview(context = context)
            return
        }
        val command = context.arguments.getStringContent("command")
            ?: context.arguments.getStringContent("data")
            ?: context.arguments.getStringContent("action").orEmpty()
        val cwd = context.arguments.getStringContent("cwd")
        val stdout = (content.getStringContent("stdout") ?: content.getStringContent("output")).orEmpty()
        val stderr = content.getStringContent("stderr").orEmpty()
        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.tool_ui_shell_default),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                ShellExitStatus(content, MaterialTheme.typography.labelMedium)
            }
            HighlightCodeBlock(
                code = if (cwd.isNullOrBlank()) command else "# cwd: $cwd\n$command",
                language = "bash",
                modifier = Modifier.fillMaxWidth(),
            )
            if (stdout.isNotEmpty()) {
                Text(text = "stdout", style = MaterialTheme.typography.labelMedium)
                HighlightCodeBlock(
                    code = stdout,
                    language = "plaintext",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (stderr.isNotEmpty()) {
                Text(
                    text = "stderr",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                HighlightCodeBlock(
                    code = stderr,
                    language = "plaintext",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Shell 退出状态文本: exit code 为 0 显示绿色, 超时或非零显示错误色 */
@Composable
private fun ShellExitStatus(content: JsonElement, style: androidx.compose.ui.text.TextStyle) {
    val exitCode = content.int("exitCode") ?: content.int("exit_code")
    val timedOut = content.boolean("timedOut") ?: content.boolean("timed_out") ?: false
    val alive = content.boolean("alive")
    val ok = !timedOut && (exitCode == 0 || (exitCode == null && alive == true))
    Text(
        text = when {
            timedOut -> stringResource(R.string.tool_ui_shell_timeout)
            // 会话/后台任务没有 exit code, 只有存活状态
            exitCode == null && alive != null -> stringResource(
                if (alive) R.string.tool_ui_shell_alive else R.string.tool_ui_shell_exited
            )
            else -> stringResource(R.string.tool_ui_shell_exit, exitCode?.toString() ?: "?")
        },
        style = style,
        color = if (ok) DiffAddedColor else MaterialTheme.colorScheme.error,
    )
}

/** 从工具输出 JSON 读取布尔字段 */
private fun JsonElement?.boolean(key: String): Boolean? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.booleanOrNull

/** 从工具输出 JSON 读取整型字段 */
private fun JsonElement?.int(key: String): Int? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.intOrNull

/** 从工具输出 JSON 读取长整型字段 */
private fun JsonElement?.long(key: String): Long? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.longOrNull

private const val FILE_SUMMARY_MAX_LINES = 10

/** 批量读取时每个文件在摘要里的最大行数 */
private const val BATCH_SUMMARY_MAX_LINES = 6

/** 由文件扩展名推断语法高亮语言 */
private fun languageOf(path: String?): String = when (
    path?.substringAfterLast('.', "")?.lowercase().orEmpty()
) {
    "kt", "kts" -> "kotlin"
    "java" -> "java"
    "js", "mjs", "cjs" -> "javascript"
    "ts" -> "typescript"
    "tsx" -> "tsx"
    "jsx" -> "jsx"
    "py" -> "python"
    "rb" -> "ruby"
    "go" -> "go"
    "rs" -> "rust"
    "c", "h" -> "c"
    "cpp", "cc", "cxx", "hpp", "hxx" -> "cpp"
    "cs" -> "csharp"
    "swift" -> "swift"
    "php" -> "php"
    "sh", "bash", "zsh" -> "bash"
    "json" -> "json"
    "xml" -> "xml"
    "html", "htm" -> "html"
    "css" -> "css"
    "scss" -> "scss"
    "yaml", "yml" -> "yaml"
    "toml" -> "toml"
    "md", "markdown" -> "markdown"
    "sql" -> "sql"
    "gradle" -> "groovy"
    else -> "plaintext"
}
