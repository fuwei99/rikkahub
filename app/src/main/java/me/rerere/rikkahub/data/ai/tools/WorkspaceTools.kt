package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.files.AssetResolver
import me.rerere.rikkahub.data.files.AssetUri
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.WORKSPACE_TOOL_CONFIG_PATH
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceRuntimeType
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_apply_patch" to false,
    "workspace_backup" to false,
    "workspace_shell" to true,
    "workspace_grep" to false,
    "workspace_shell_session" to true,
)

val WorkspaceToolDefaultEnabled: Map<String, Boolean> = mapOf(
    "workspace_read_file" to true,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_apply_patch" to false,
    "workspace_backup" to false,
    "workspace_shell" to false,
    "workspace_grep" to true,
    "workspace_shell_session" to false,
)

/**
 * 旧工具名 → 新工具名。
 *
 * 工具合并后, 用户此前在工作区里保存的 approval/enabled 覆盖项以及助手已选的工具集合
 * 里仍是旧名。这里做一次映射, 避免用户开过的开关静默失效。
 * 多个旧名映射到同一新名时按 **OR** 合并(任一为 true 即生效)。
 */
val WorkspaceToolNameAliases: Map<String, String> = mapOf(
    "workspace_shell_background" to "workspace_shell_session",
    "workspace_list_backups" to "workspace_backup",
    "workspace_restore_backup" to "workspace_backup",
)

val WorkspaceToolNames: List<String> = WorkspaceToolDefaultApprovals.keys.toList()

/** 把可能含旧名的工具名集合归一到新名 */
fun normalizeWorkspaceToolNames(names: Set<String>): Set<String> =
    names.mapTo(mutableSetOf()) { WorkspaceToolNameAliases[it] ?: it }

/** 读取覆盖项时兼容旧名: 新名优先, 否则取所有映射到它的旧名的 OR */
private fun resolveOverride(name: String, overrides: Map<String, Boolean>): Boolean? {
    overrides[name]?.let { return it }
    val legacyValues = WorkspaceToolNameAliases
        .filterValues { it == name }
        .keys
        .mapNotNull { overrides[it] }
    return if (legacyValues.isEmpty()) null else legacyValues.any { it }
}

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    resolveOverride(name, overrides) ?: WorkspaceToolDefaultApprovals[name] ?: false

fun resolveWorkspaceToolDefaultEnabled(name: String, overrides: Map<String, Boolean>): Boolean =
    resolveOverride(name, overrides) ?: WorkspaceToolDefaultEnabled[name] ?: false

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
    enabledTools: Set<String>? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val workspace = workspaceRepository.getById(workspaceId)
    val approvalOverrides = workspace?.toolApprovalOverrides().orEmpty()
    val enabledOverrides = workspace?.toolDefaultEnabledOverrides().orEmpty()
    val enabledByDefault = WorkspaceToolNames
        .filter { resolveWorkspaceToolDefaultEnabled(it, enabledOverrides) }
        .toSet()
    val selectedTools = normalizeWorkspaceToolNames(enabledTools ?: enabledByDefault)
    val externalMounts = workspace?.externalMountConfigs().orEmpty()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    // shell / shell_session 用的是「进程真实工作目录」, 与文件工具的相对路径基准是两件事:
    // 前者要 /workspace 下的相对片段, 后者要一个绝对基准目录。
    val shellCwd = cwd?.removePrefix("/workspace/")?.removePrefix("/workspace")

    // 相对路径基准, 全体文件工具共用同一个值(单一事实来源):
    //   会话 cwd(用户在 UI 里选的) > workspace_config.jsonc 的 paths.relativeBase > /workspace
    val configuredBase = runCatching { workspaceRepository.getToolConfig(workspaceId).paths }
        .getOrNull()
        ?.relativeBase
        ?.takeIf { base -> base.isNotBlank() && base.isAllowedPatchPath(externalMounts) }
    val pathBase = cwd?.takeIf { it.isNotBlank() }
        ?: configuredBase
        ?: "/workspace"

    return listOf(
        createReadFileTool(workspaceId, ::needsApproval, workspaceRepository, pathBase, externalMounts),
        createWriteFileTool(workspaceId, ::needsApproval, workspaceRepository, pathBase, externalMounts),
        createEditFileTool(workspaceId, ::needsApproval, workspaceRepository, pathBase, externalMounts),
        createApplyPatchTool(workspaceId, ::needsApproval, workspaceRepository, pathBase, externalMounts),
        createBackupTool(workspaceId, ::needsApproval, workspaceRepository, externalMounts),
        createShellTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd, externalMounts),
        createGrepTool(workspaceId, ::needsApproval, workspaceRepository, pathBase, externalMounts),
        createShellSessionTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd),
    ).filter { it.name in selectedTools }
}

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createReadFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    pathBase: String = "/workspace",
    externalMounts: List<me.rerere.workspace.WorkspaceExternalMount> = emptyList(),
) = Tool(
    name = "workspace_read_file",
    description = "Read file contents as UTF-8 text (returns numbered lines) or as an image preview " +
        "(PNG/JPG/WEBP). Use `path` for one file, or `paths` for up to 8 text files at once; " +
        "passing both is fine (they are merged, and an empty `paths` is ignored).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = false)
                put("paths", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put(
                        "description",
                        "Read up to 8 text files in one call. May be combined with `path`; " +
                            "an empty array is ignored."
                    )
                })
                put("start_line", buildJsonObject {
                    put("type", "integer")
                    put("description", "1-based line to start from. Defaults to 1.")
                })
                put("line_count", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max lines returned. Defaults to 400, hard max 2000. Read large files in chunks.")
                })
                put("max_chars", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max chars returned. Defaults to 20000, hard max 60000.")
                })
                put("uncompressed", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Images only: return the original instead of a compressed preview. Defaults to false.")
                })
            },
            required = emptyList(),
        )
    },
    needsApproval = { needsApproval("workspace_read_file") },
    execute = { input ->
        val params = input.jsonObject
        val fullConfig = workspaceRepository.getToolConfig(workspaceId)
        val config = fullConfig.readFile
        val pathsConfig = fullConfig.paths
        val startLine = (params["start_line"]?.jsonPrimitive?.intOrNull ?: config.defaultStartLine)
            .coerceAtLeast(1)
        val lineCount = (params["line_count"]?.jsonPrimitive?.intOrNull ?: config.defaultLineCount)
            .coerceIn(1, config.maxLineCount.coerceAtLeast(1))
        val maxChars = (params["max_chars"]?.jsonPrimitive?.intOrNull ?: config.defaultMaxChars)
            .coerceIn(1_000, config.hardMaxChars.coerceAtLeast(1_000))

        suspend fun readOne(rawPath: String, charBudget: Int): kotlinx.serialization.json.JsonObject {
            val (path, fellBackFrom) = workspaceRepository.resolveExistingPath(
                workspaceId = workspaceId,
                path = rawPath,
                pathBase = pathBase,
                enabled = pathsConfig.fallbackToWorkspaceRoot,
            )
            val result = workspaceRepository.readTextRangeInRootfs(
                workspaceId = workspaceId,
                path = path,
                startLine = startLine,
                lineCount = lineCount,
                maxChars = charBudget,
                maxFileBytes = config.maxFileBytes.coerceAtLeast(1),
                includeLineNumbers = config.includeLineNumbers,
            )
            return buildJsonObject {
                put("path", path)
                if (fellBackFrom != null) {
                    put(
                        "note",
                        "Relative paths resolve against \"$pathBase\"; \"$fellBackFrom\" did not exist, " +
                            "so \"$path\" was read instead. Use absolute paths to be explicit."
                    )
                }
                put("start_line", result.startLine)
                put("end_line", result.endLine)
                put("line_count", result.returnedLines)
                put("total_lines", result.totalLines)
                result.nextStartLine?.let { next -> put("next_start_line", next) }
                put("truncated", result.truncated)
                put("text", result.text)
            }
        }

        val uncompressedImage = params["uncompressed"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

        // `path` 与 `paths` 不再互斥: 合并成同一个待读列表。
        // 这样模型常犯的 { path: "a.kt", paths: [] } 也能正常工作。
        val singlePaths = params["path"]?.takeIf { raw -> raw !is kotlinx.serialization.json.JsonNull }
            ?.let { raw ->
                when (raw) {
                    // path 误传成数组也容忍; 但不做逗号拆分(文件名可能含逗号)
                    is kotlinx.serialization.json.JsonArray -> raw.mapNotNull { el ->
                        (el as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim()
                    }
                    is kotlinx.serialization.json.JsonPrimitive -> listOf(raw.content.trim())
                    else -> emptyList()
                }
            }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val batchPaths = params["paths"]?.takeIf { raw -> raw !is kotlinx.serialization.json.JsonNull }
            ?.stringListOrNull()
            .orEmpty()
        val requested = (singlePaths + batchPaths).distinct()
        require(requested.isNotEmpty()) {
            "workspace_read_file requires a non-empty 'path' (single file) or 'paths' (array of up to 8 files)."
        }
        require(requested.size <= 8) { "paths supports at most 8 files per call" }

        val resolved = requested.map { raw ->
            buildJsonObject { put("path", raw) }.resolveAbsolutePath("path", pathBase, externalMounts)
        }.distinct()

        if (resolved.size > 1) {
            require(resolved.none { it.isImagePath() }) { "Batch mode supports text files only" }
            val perFileBudget = (maxChars / resolved.size).coerceAtLeast(1_000)
            val files = resolved.map { path ->
                runCatching { readOne(path, perFileBudget) }.getOrElse { e ->
                    buildJsonObject {
                        put("path", path)
                        put("error", e.message ?: "read failed")
                    }
                }
            }
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("files", kotlinx.serialization.json.JsonArray(files))
                    }.toString()
                )
            )
        } else {
            val path = resolved.single()
            if (path.isImagePath()) {
                runCatching { workspaceRepository.readImageInRootfs(workspaceId, path, uncompressedImage) }
                    .getOrElse { e ->
                        listOf(
                            UIMessagePart.Text(
                                buildJsonObject {
                                    put("path", path)
                                    put("error", e.message ?: "read failed")
                                }.toString()
                            )
                        )
                    }
            } else {
                val payload = runCatching { readOne(path, maxChars) }
                    .getOrElse { e ->
                        buildJsonObject {
                            put("path", path)
                            put("error", e.message ?: "read failed")
                        }
                    }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        }
    },
)

private fun createWriteFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    pathBase: String = "/workspace",
    externalMounts: List<me.rerere.workspace.WorkspaceExternalMount> = emptyList(),
) = Tool(
    name = "workspace_write_file",
    description = "Create or overwrite a UTF-8 text file. Creates a restorable backup before writing.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content.")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Overwrite if the file exists. Defaults to true.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    needsApproval = { needsApproval("workspace_write_file") },
    execute = {
        val params = it.jsonObject
        val path = params.resolveAbsolutePath("path", pathBase, externalMounts)
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val backupId = workspaceRepository.createWorkspaceBackup(
            workspaceId = workspaceId,
            paths = listOf(path),
            reason = "workspace_write_file",
        )
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, text, overwrite)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("path", entry.path)
                    put("name", entry.name)
                    put("isDirectory", entry.isDirectory)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                    backupId?.let { id -> put("backup_id", id) }
                }.toString()
            )
        )
    },
)

private fun createEditFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    pathBase: String = "/workspace",
    externalMounts: List<me.rerere.workspace.WorkspaceExternalMount> = emptyList(),
) = Tool(
    name = "workspace_edit_file",
    description = "Edit a UTF-8 text file. Pass exactly one of: old_text + new_text (single edit), " +
            "or an `edits` array (multiple edits, applied in order). If both are sent, `edits` wins. " +
            "old_text is matched exactly first, then with whitespace-tolerant fallbacks.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Text to replace. Must match a unique location unless replace_all=true.")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text.")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Replace every occurrence. Defaults to false (errors on multiple matches).")
                })
                put("edits", buildJsonObject {
                    put("type", "array")
                    put(
                        "description",
                        "Array of {old_text, new_text, replace_all?} objects, applied in order."
                    )
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("old_text", buildJsonObject { put("type", "string") })
                            put("new_text", buildJsonObject { put("type", "string") })
                            put("replace_all", buildJsonObject { put("type", "boolean") })
                        })
                        put("required", kotlinx.serialization.json.buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive("old_text"))
                            add(kotlinx.serialization.json.JsonPrimitive("new_text"))
                        })
                    })
                })
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_edit_file") },
    execute = {
        val params = it.jsonObject
        val path = params.resolveAbsolutePath("path", pathBase, externalMounts)

        // 统一成编辑列表: 单编辑模式 (old_text/new_text) 或多编辑模式 (edits 数组)
        data class EditOp(val oldText: String, val newText: String, val replaceAll: Boolean)

        val warnings = mutableListOf<String>()

        // 宽容解析 edits: 容忍字符串化数组 / 单对象未包数组 (见 jsonArrayOrNull)
        val editsRaw = params["edits"]?.takeIf { raw -> raw !is kotlinx.serialization.json.JsonNull }
        val editsJson = editsRaw?.jsonArrayOrNull()
        if (editsRaw != null && editsJson == null) {
            error(
                "workspace_edit_file: `edits` must be an ARRAY of {old_text, new_text, replace_all?} " +
                        "(a JSON string containing such an array is also accepted), but got: " +
                        editsRaw.toString().take(200)
            )
        }
        if (editsRaw != null && editsRaw !is kotlinx.serialization.json.JsonArray) {
            warnings += "`edits` was not a JSON array; coerced into ${editsJson!!.size} op(s). " +
                    "Pass a real array next time."
        }
        val hasMulti = editsJson != null && editsJson.isNotEmpty()

        // 单编辑模式判定: 只认「非空的 old_text」, 空串 / null 一律不算,
        // 避免模型多带一个占位 key 就把整个调用毙掉
        val singleOld = params.string("old_text")
        val hasSingle = !singleOld.isNullOrEmpty()

        require(hasSingle || hasMulti) {
            "workspace_edit_file requires either (old_text + new_text) for single-edit mode, " +
                    "or a non-empty `edits` array for multi-edit mode. Received keys: " +
                    params.keys.joinToString(", ").ifEmpty { "(none)" } +
                    " — nothing to edit."
        }

        // 两种模式同时给出时不再硬失败: edits 信息量更完整, 优先采用并明确告知取舍
        val ops: List<EditOp> = if (hasMulti) {
            if (hasSingle) {
                warnings += "both `edits` and top-level `old_text`/`new_text` were provided; " +
                        "applied `edits` (${editsJson!!.size} op(s)) and ignored the top-level pair. " +
                        "These two modes are mutually exclusive — pick one next time."
            }
            editsJson!!.mapIndexed { idx, el ->
                val obj = el as? kotlinx.serialization.json.JsonObject
                    ?: error("edits[$idx] must be an object with old_text/new_text, got: ${el.toString().take(120)}")
                EditOp(
                    oldText = obj.string("old_text")
                        ?: error("edits[$idx].old_text is required"),
                    newText = obj.string("new_text")
                        ?: error("edits[$idx].new_text is required"),
                    replaceAll = obj["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                )
            }
        } else {
            listOf(
                EditOp(
                    oldText = singleOld!!,
                    newText = params.string("new_text")
                        ?: error("new_text is required in single-edit mode (or pass an `edits` array)"),
                    replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                )
            )
        }
        ops.forEachIndexed { idx, op ->
            require(op.oldText.isNotEmpty()) {
                if (ops.size > 1) "edits[$idx].old_text must not be empty" else "old_text must not be empty"
            }
        }

        val original = workspaceRepository.readTextInRootfs(workspaceId, path)
        var current = original
        var totalReplacements = 0
        val strategies = mutableSetOf<String>()
        for ((index, op) in ops.withIndex()) {
            val result = try {
                replaceText(current, op.oldText, op.newText, op.replaceAll)
            } catch (e: IllegalArgumentException) {
                error("edit ${index + 1}/${ops.size}: ${e.message} (path: $path)")
            }
            current = result.updated
            totalReplacements += result.replacements
            if (result.strategy != ExactReplacer.name) strategies += result.strategy
        }
        val backupId = workspaceRepository.createWorkspaceBackup(
            workspaceId = workspaceId,
            paths = listOf(path),
            reason = "workspace_edit_file",
        )
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, current, overwrite = true)
        val diff = generateUnifiedDiff(original, current, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("path", entry.path)
                    put("replacements", totalReplacements)
                    if (ops.size > 1) put("edits", ops.size)
                    if (strategies.isNotEmpty()) put("matchStrategy", strategies.joinToString(","))
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                    backupId?.let { id -> put("backup_id", id) }
                    if (warnings.isNotEmpty()) {
                        put("warnings", kotlinx.serialization.json.JsonArray(
                            warnings.map { w -> kotlinx.serialization.json.JsonPrimitive(w) }
                        ))
                    }
                }.toString(),
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)


private fun createApplyPatchTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    pathBase: String = "/workspace",
    externalMounts: List<me.rerere.workspace.WorkspaceExternalMount>,
) = Tool(
    name = "workspace_apply_patch",
    description = "Apply a Git-style unified diff patch to modify, create, delete, or rename text files. " +
        "Relative paths in `--- a/` / `+++ b/` headers resolve against the same base directory as " +
        "workspace_read_file, so a path that read_file accepted can be reused verbatim.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("patch", buildJsonObject {
                    put("type", "string")
                    put("description", "Unified diff text.")
                })
                put("dry_run", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Preview only, no writes. Defaults to false.")
                })
                put("rollback_on_failure", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Auto-restore the backup if a hunk fails to apply.")
                })
            },
            required = listOf("patch"),
        )
    },
    needsApproval = { needsApproval("workspace_apply_patch") },
    execute = {
        val params = it.jsonObject
        val patchText = params.string("patch") ?: error("patch is required")
        val config = workspaceRepository.getToolConfig(workspaceId)
        require(config.patch.enabled) { "workspace_apply_patch is disabled by workspace config" }
        require(patchText.length <= config.patch.maxPatchChars.coerceAtLeast(1)) {
            "Patch is too large (${patchText.length} chars, max ${config.patch.maxPatchChars})"
        }
        val dryRun = params["dry_run"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: config.patch.dryRunDefault
        val rollbackOnFailure = params["rollback_on_failure"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: config.patch.rollbackOnFailure
        val patches = parseUnifiedDiff(patchText, pathBase, externalMounts)
        require(patches.isNotEmpty()) { "Patch contains no file changes" }
        require(patches.size <= config.patch.maxFilesPerPatch.coerceAtLeast(1)) {
            "Patch touches too many files (${patches.size}, max ${config.patch.maxFilesPerPatch})"
        }
        if (!config.patch.allowGitExtendedDiff) {
            require(patches.none { fp -> fp.isCreate || fp.isDelete || fp.isRename }) {
                "Git extended diff is disabled by workspace config"
            }
        }
        val touchedPaths = patches.touchedPaths().distinct()
        val originals = touchedPaths.associateWith { path -> workspaceRepository.readOptionalTextInRootfs(workspaceId, path) }
        val diffBeforeAfter = StringBuilder()
        val summaries = mutableListOf<PatchFileSummary>()

        for (filePatch in patches) {
            val result = applyFilePatchToSnapshot(filePatch, originals)
            summaries += result.summary
            if (result.oldText != result.newText) {
                val d = generateUnifiedDiff(result.oldText.orEmpty(), result.newText.orEmpty(), result.summary.path)
                if (!d.isNullOrBlank()) {
                    if (diffBeforeAfter.isNotEmpty()) diffBeforeAfter.append('\n')
                    diffBeforeAfter.append(d)
                }
            }
        }

        if (dryRun) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("applied", false)
                        put("dry_run", true)
                        put("files", summaries.toPatchSummaryJsonArray())
                    }.toString(),
                    metadata = diffBeforeAfter.toString().takeIf { diff -> diff.isNotBlank() }
                        ?.let { diff -> DiffMetadata(diff = diff).toMetadata() },
                )
            )
        }

        val backupId = workspaceRepository.createWorkspaceBackup(
            workspaceId = workspaceId,
            paths = touchedPaths,
            reason = "workspace_apply_patch",
        )
        val applied = mutableListOf<PatchFileSummary>()
        try {
            for (filePatch in patches) {
                val result = applyAndWriteFilePatch(workspaceRepository, workspaceId, filePatch)
                applied += result.summary
            }
        } catch (e: PatchApplyException) {
            if (rollbackOnFailure && backupId != null) {
                workspaceRepository.restoreWorkspaceBackup(workspaceId, backupId, files = null, createPreRestoreBackup = false)
            }
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("applied", false)
                        put("partial_applied", applied.isNotEmpty() && !rollbackOnFailure)
                        put("rollback_on_failure", rollbackOnFailure)
                        backupId?.let { id -> put("backup_id", id) }
                        put("applied_files", applied.toPatchSummaryJsonArray())
                        put("failed_path", e.path)
                        put("failed_hunk", e.hunkIndex)
                        put("reason", e.message ?: "Patch hunk failed")
                        put("hint", if (rollbackOnFailure) {
                            "The backup was restored. Read the file again and regenerate a smaller patch."
                        } else {
                            "Already applied changes were kept. Read the failed file and apply a smaller patch for remaining changes, or call workspace_backup with action=restore and backup_id to undo."
                        })
                    }.toString()
                )
            )
        }

        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("applied", true)
                    put("dry_run", false)
                    backupId?.let { id -> put("backup_id", id) }
                    put("files", applied.toPatchSummaryJsonArray())
                    // A patch that parses but produces no diff is almost always a malformed patch
                    // rather than an intentional no-op. Say so instead of reporting a clean success.
                    if (applied.isNotEmpty() &&
                        applied.none { s -> s.status == "created" || s.status == "deleted" || s.status == "renamed" } &&
                        diffBeforeAfter.isBlank()
                    ) {
                        put(
                            "warning",
                            "Patch applied but no content changed. Verify the hunks actually matched " +
                                "the target file before assuming the edit landed."
                        )
                    }
                }.toString(),
                metadata = diffBeforeAfter.toString().takeIf { diff -> diff.isNotBlank() }
                    ?.let { diff -> DiffMetadata(diff = diff).toMetadata() },
            )
        )
    },
)

private fun createBackupTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    externalMounts: List<me.rerere.workspace.WorkspaceExternalMount>,
) = Tool(
    name = "workspace_backup",
    description = "Inspect or restore backups created automatically by file-changing tools.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", kotlinx.serialization.json.JsonArray(
                        listOf("list", "restore").map { kotlinx.serialization.json.JsonPrimitive(it) }
                    ))
                    put(
                        "description",
                        "list: show restorable backups, newest first. restore: roll files back to a backup id."
                    )
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max backups returned (list only). Defaults to 20.")
                })
                put("backup_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Backup id from a file-changing tool or action=list. Required for restore.")
                })
                put("files", buildJsonObject {
                    put("type", "array")
                    put("description", "Paths to restore. Omit to restore every entry in the backup.")
                    put("items", buildJsonObject { put("type", "string") })
                })
            },
            required = listOf("action"),
        )
    },
    needsApproval = { needsApproval("workspace_backup") },
    execute = { input ->
        val params = input.jsonObject
        val action = params.string("action") ?: error("action is required")
        val resultJson = when (action) {
            "list" -> {
                val limit = params["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 100) ?: 20
                val backups = workspaceRepository.listWorkspaceBackups(workspaceId, limit)
                buildJsonObject {
                    put("backups", backups.toJsonObjectArray())
                }
            }

            "restore" -> {
                val backupId = params.string("backup_id")
                    ?: error("backup_id is required for action=restore")
                val files = params["files"]?.takeIf { raw -> raw !is kotlinx.serialization.json.JsonNull }
                    ?.stringListOrNull()?.takeIf { list -> list.isNotEmpty() }
                workspaceRepository.restoreWorkspaceBackup(workspaceId, backupId, files)
            }

            else -> error("Unknown action: $action")
        }
        listOf(UIMessagePart.Text(resultJson.toString()))
    },
)

private fun createShellTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
    externalMounts: List<me.rerere.workspace.WorkspaceExternalMount> = emptyList(),
) = Tool(
    name = "workspace_shell",
    description = "Run a bash command in the workspace. Pass session_id to run inside a persistent " +
        "session (see workspace_shell_session).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "Working directory relative to workspace root. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to workspace root. Defaults to root."
                        }
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS."
                    )
                })
                put("session_id", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Session id from workspace_shell_session. In a session, `cwd` is ignored (use `cd`) " +
                            "and a timeout does NOT kill the command: you get partial output with " +
                            "still_running=true, then keep reading via workspace_shell_session action=read."
                    )
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val sessionId = params.string("session_id")?.takeIf { raw -> raw.isNotBlank() }
        val shellConfig = workspaceRepository.getToolConfig(workspaceId).shell

        val resultJson = if (sessionId != null) {
            // 会话模式: 状态持久, 超时不杀命令
            require(shellConfig.sessionEnabled) { "Interactive sessions are disabled in workspace config" }
            val maxSeconds = shellConfig.sessionMaxTimeoutSeconds.coerceAtLeast(1L)
            val timeoutMillis = (params.string("timeout")?.toLongOrNull()
                ?: shellConfig.sessionDefaultTimeoutSeconds)
                .coerceIn(1L, maxSeconds) * 1_000L
            val result = workspaceRepository.execInSession(
                id = workspaceId,
                sessionId = sessionId,
                command = command,
                timeoutMillis = timeoutMillis,
            )
            buildJsonObject {
                // exitCode 未知(命令仍在跑)时用 -1 占位, 由 still_running 说明真实状态
                put("exitCode", result.exitCode ?: -1)
                put("stdout", result.stdout.collapseCarriageReturns())
                // 会话模式下 stderr 已合并进 stdout(交错顺序才有意义)
                put("stderr", "")
                put("timedOut", result.stillRunning)
                put("session_id", sessionId)
                if (result.stillRunning) {
                    put("still_running", true)
                    put(
                        "hint",
                        "Command is still running; it was NOT killed. " +
                            "Use workspace_shell_session action=read to continue, " +
                            "or action=interrupt to abort it."
                    )
                }
                if (result.exitCode == null && !result.stillRunning) {
                    put("session_dead", true)
                }
                if (result.droppedChars > 0) put("dropped_chars", result.droppedChars)
            }
        } else {
            val cwd = (params.string("cwd") ?: defaultCwd.orEmpty())
                .removePrefix("/workspace/").removePrefix("/workspace")
            val timeoutMillis = params.string("timeout")?.toLongOrNull()
                ?.coerceIn(1L, shellConfig.maxTimeoutSeconds.coerceAtLeast(1L))
                ?.times(1_000L)
                ?: shellConfig.defaultTimeoutSeconds.coerceIn(1L, shellConfig.maxTimeoutSeconds.coerceAtLeast(1L)).times(1_000L)
            val result = workspaceRepository.executeCommand(workspaceId, command, cwd, timeoutMillis, shellConfig.outputMaxChars.coerceIn(1_000, 512 * 1024))
            buildJsonObject {
                put("exitCode", result.exitCode)
                put("stdout", result.stdout.collapseCarriageReturns())
                put("stderr", result.stderr.collapseCarriageReturns())
                put("timedOut", result.timedOut)
                if (result.truncated) put("truncated", true)
            }
        }
        listOf(UIMessagePart.Text(resultJson.toString()))
    },
)

private fun detectRegexSyntaxHint(query: String): String? {
    val triggers = listOf(
        "|" to "'|' (OR operator)",
        "\\b" to "'\\b' (word boundary)",
        "^" to "'^' (start of line)",
        "$" to "'$' (end of line)",
        ".*" to "'.*' (wildcard)",
        ".+" to "'.+' (wildcard)",
        "\\d" to "'\\d' (digit class)",
        "\\w" to "'\\w' (word class)",
        "\\s" to "'\\s' (whitespace class)",
        "(?" to "'(?...' (grouping/lookaround)",
    )
    val matched = triggers.filter { (pattern, _) -> query.contains(pattern) }
    if (matched.isNotEmpty()) {
        val features = matched.joinToString(", ") { it.second }
        return "0 matches found. Query contains regex syntax ($features) while regex=false. To search for multiple terms or use regex syntax, set 'regex': true."
    }
    return null
}

private fun createGrepTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    pathBase: String = "/workspace",
    externalMounts: List<me.rerere.workspace.WorkspaceExternalMount> = emptyList(),
) = Tool(
    name = "workspace_grep",
    description = "Search file contents in the workspace or mounts. Skips .git, node_modules, build.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Search pattern.")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Directory to search. Defaults to /workspace.")
                })
                put("regex", buildJsonObject {
                    put("type", "boolean")
                    put("description", "True for regex or OR search ('a|b'); false for literal.")
                })
                put("ignore_case", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Case-insensitive. Defaults to true.")
                })
                put("include_glob", buildJsonObject {
                    put("type", "string")
                    put("description", "Glob filter (e.g. *.kt). Auto-prepends **/ if missing.")
                })
                put("max_results", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max matches (1-500). Defaults to 100.")
                })
            },
            required = listOf("query", "regex"),
        )
    },
    needsApproval = { needsApproval("workspace_grep") },
    execute = { input ->
        val params = input.jsonObject
        val query = params.string("query") ?: error("query is required")
        val rawPath = params.string("path")
        val path = if (!rawPath.isNullOrBlank()) {
            params.resolveAbsolutePath("path", pathBase, externalMounts)
        } else ""
        val regex = params["regex"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val ignoreCase = params["ignore_case"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: true
        val maxResults = (params["max_results"]?.jsonPrimitive?.intOrNull ?: 100).coerceIn(1, 500)
        val matches = workspaceRepository.grepFiles(
            id = workspaceId,
            query = query,
            path = path,
            regex = regex,
            ignoreCase = ignoreCase,
            includeGlob = params.string("include_glob"),
        )
        val hint = if (matches.isEmpty() && !regex) detectRegexSyntaxHint(query) else null
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("total", matches.size)
                    if (matches.size > maxResults) put("truncated", true)
                    hint?.let { put("hint", it) }
                    put("matches", kotlinx.serialization.json.JsonArray(
                        matches.take(maxResults).map { match ->
                            buildJsonObject {
                                put("path", match.path)
                                put("line", match.line)
                                put("text", match.text.take(500))
                            }
                        }
                    ))
                }.toString()
            )
        )
    },
)

private fun createShellSessionTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_shell_session",
    description = "Manage persistent shell sessions (long-lived bash: cwd, env and functions survive; " +
        "open here, then run commands via workspace_shell + session_id) and detached background tasks.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", kotlinx.serialization.json.JsonArray(
                        listOf("open", "close", "read", "write", "interrupt", "start", "kill", "list")
                            .map { kotlinx.serialization.json.JsonPrimitive(it) }
                    ))
                    put(
                        "description",
                        "open: create a session, returns session_id. " +
                            "close: terminate a session. " +
                            "read: keep reading output of a still-running command, or drain pending output. " +
                            "write: send raw text to stdin, include the trailing \\n. " +
                            "interrupt: SIGINT the foreground command (Ctrl-C). " +
                            "start: launch a detached background task. " +
                            "kill: terminate a background task or session by id. " +
                            "list: show all sessions and background tasks."
                    )
                })
                put("session_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Required for close/read/write/interrupt.")
                })
                put("data", buildJsonObject {
                    put("type", "string")
                    put("description", "Raw text for action=write. Include the trailing newline.")
                })
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Command to run (action=start).")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put("description", "Working directory relative to workspace root (action=open/start).")
                })
                put("process_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Task id from start, required for kill. A session_id also works.")
                })
                put("wait_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "For read: seconds to wait for completion before returning what is available.")
                })
            },
            required = listOf("action"),
        )
    },
    needsApproval = { needsApproval("workspace_shell_session") },
    execute = { input ->
        val params = input.jsonObject
        val action = params.string("action") ?: error("action is required")
        val shellConfig = workspaceRepository.getToolConfig(workspaceId).shell

        fun me.rerere.workspace.WorkspaceBackgroundProcess.statusJson(includeOutput: Boolean) = buildJsonObject {
            put(if (pinned) "session_id" else "process_id", id)
            put("kind", if (pinned) "session" else "task")
            put("command", command.take(200))
            put("running", isAlive)
            exitCode()?.let { put("exitCode", it) }
            put("started_at", startedAt)
            if (pinned) {
                workspaceRepository.sessionState(id)?.let { state ->
                    state.pendingCommand?.let { put("pending_command", it) }
                    if (state.pendingNonce != null) put("command_running", true)
                }
            }
            if (includeOutput) {
                put("stdout", stdoutText().collapseCarriageReturns())
                put("stderr", stderrText().collapseCarriageReturns())
                if (truncated()) put("truncated", true)
            }
        }

        fun requireSessionId(): String = params.string("session_id")?.takeIf { it.isNotBlank() }
            ?: error("session_id is required for action=$action")

        val resultJson = when (action) {
            "open" -> {
                require(shellConfig.sessionEnabled) { "Interactive sessions are disabled in workspace config" }
                val cwd = (params.string("cwd") ?: defaultCwd.orEmpty())
                    .removePrefix("/workspace/").removePrefix("/workspace")
                val (channel, state) = workspaceRepository.openSession(workspaceId, cwd)
                buildJsonObject {
                    put("session_id", state.id)
                    put("running", channel.isAlive)
                    state.sessionPid?.let { put("shell_pid", it) }
                    // 告知前端/模型本会话的中断能力: pty 才有真 Ctrl-C
                    put("pty", state.usesPty)
                    put(
                        "hint",
                        "Run commands with workspace_shell using session_id=\"${state.id}\". " +
                            "cwd/env/functions persist. Close it when done." +
                            if (state.usesPty) ""
                            else " Note: this is a pipe session (pty unavailable); action=interrupt " +
                                "cannot stop bash's own loops such as `while true`, use action=close instead."
                    )
                }
            }

            "close" -> {
                val sessionId = requireSessionId()
                workspaceRepository.closeSession(workspaceId, sessionId)
                buildJsonObject {
                    put("session_id", sessionId)
                    put("closed", true)
                }
            }

            "read" -> {
                val sessionId = requireSessionId()
                val waitSeconds = params["wait_seconds"]?.jsonPrimitive?.intOrNull
                    ?.coerceIn(0, shellConfig.sessionMaxTimeoutSeconds.toInt().coerceAtLeast(1))
                    ?: 0
                val result = workspaceRepository.readSession(
                    id = workspaceId,
                    sessionId = sessionId,
                    timeoutMillis = waitSeconds * 1_000L,
                )
                buildJsonObject {
                    put("session_id", sessionId)
                    put("stdout", result.stdout.collapseCarriageReturns())
                    result.exitCode?.let { put("exitCode", it) }
                    if (result.stillRunning) {
                        put("still_running", true)
                        put("hint", "Command is still running. Call read again, or interrupt to abort it.")
                    }
                    if (result.droppedChars > 0) put("dropped_chars", result.droppedChars)
                }
            }

            "write" -> {
                val sessionId = requireSessionId()
                val data = params.string("data") ?: error("data is required for action=write")
                workspaceRepository.writeSession(workspaceId, sessionId, data)
                buildJsonObject {
                    put("session_id", sessionId)
                    put("written", data.length)
                    put("hint", "Use action=read to collect the output.")
                }
            }

            "interrupt" -> {
                val sessionId = requireSessionId()
                val detail = workspaceRepository.interruptSession(workspaceId, sessionId)
                buildJsonObject {
                    put("session_id", sessionId)
                    put("interrupted", true)
                    put("detail", detail)
                    put("hint", "Use action=read to collect any remaining output.")
                }
            }

            "start" -> {
                require(shellConfig.backgroundEnabled) { "Background processes are disabled in workspace config" }
                val command = params.string("command") ?: error("command is required for start")
                val cwd = (params.string("cwd") ?: defaultCwd.orEmpty())
                    .removePrefix("/workspace/").removePrefix("/workspace")
                val process = workspaceRepository.startBackgroundCommand(
                    id = workspaceId,
                    processId = java.util.UUID.randomUUID().toString().take(8),
                    command = command,
                    cwd = cwd,
                    maxOutputChars = shellConfig.outputMaxChars.coerceIn(1_000, 512 * 1024),
                )
                process.statusJson(includeOutput = false)
            }

            "kill" -> {
                val processId = params.string("process_id")?.takeIf { it.isNotBlank() }
                    ?: params.string("session_id")?.takeIf { it.isNotBlank() }
                    ?: error("process_id is required for kill")
                val process = workspaceRepository.getBackgroundProcess(workspaceId, processId)
                    ?: error("No such background process or session: $processId")
                kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                    process.kill()
                }
                workspaceRepository.removeBackgroundProcess(processId)
                process.statusJson(includeOutput = true)
            }

            "list" -> buildJsonObject {
                put("sessions", kotlinx.serialization.json.JsonArray(
                    workspaceRepository.listSessions(workspaceId).map { info ->
                        buildJsonObject {
                            put("session_id", info.id)
                            put("kind", "session")
                            put("running", info.running)
                            // pty=false 的会话 interrupt 能力受限, 明确告知模型
                            put("pty", info.usesPty)
                            info.shellPid?.let { put("shell_pid", it) }
                            info.pendingCommand?.let { put("pending_command", it) }
                            if (info.commandRunning) put("command_running", true)
                            if (info.truncated) put("truncated", true)
                        }
                    }
                ))
                put("tasks", kotlinx.serialization.json.JsonArray(
                    workspaceRepository.listBackgroundTasks(workspaceId).map { it.statusJson(includeOutput = false) }
                ))
            }

            else -> error("Unknown action: $action")
        }
        listOf(UIMessagePart.Text(resultJson.toString()))
    },
)

/**
 * 折叠 \r 进度条输出: 终端里 "\r" 会把光标拉回行首覆盖重绘 (git clone/pip/gradle 的进度条),
 * 原样返回给 LLM 会产生几十帧重复文本。这里模拟覆盖行为, 每行只保留最后一帧。
 */
private fun String.collapseCarriageReturns(): String {
    if ('\r' !in this) return this
    // 注意: 不能用 lines()/lineSequence(), 它们把 \r 也视为行分隔符
    return split('\n').joinToString("\n") { line ->
        val trimmed = line.removeSuffix("\r") // \r\n 行尾
        if ('\r' !in trimmed) return@joinToString trimmed
        // 模拟终端覆盖: 每个 \r 后的帧从行首开始覆盖前面的内容
        trimmed.split('\r').fold("") { acc, frame ->
            if (frame.length >= acc.length) frame else frame + acc.substring(frame.length)
        }
    }
}


@Serializable
private data class WorkspaceBackupManifest(
    val backupId: String,
    val createdAt: Long,
    val reason: String,
    val entries: List<WorkspaceBackupEntry>,
)

@Serializable
private data class WorkspaceBackupEntry(
    val path: String,
    val existed: Boolean,
    val backupPath: String? = null,
    val sizeBytes: Long = 0,
)

private data class PatchFile(
    val oldPath: String?,
    val newPath: String?,
    val isCreate: Boolean,
    val isDelete: Boolean,
    val isRename: Boolean,
    val hunks: List<PatchHunk>,
    /** 补丁头里的原始(可能是相对的)路径, 仅用于报错时解释路径是怎么解析出来的。 */
    val rawSourcePath: String? = null,
)

private data class PatchHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<PatchLine>,
    /**
     * True when the header omitted line ranges ("@@" with no numbers). Such a hunk is located by
     * context matching alone, and its declared counts must not be used to decide where it ends.
     */
    val rangesOmitted: Boolean = false,
)

private data class PatchLine(
    val type: Char,
    val text: String,
)

private data class PatchFileSummary(
    val path: String,
    val status: String,
    val oldSize: Int,
    val newSize: Int,
)

private data class PatchApplyResult(
    val summary: PatchFileSummary,
    val oldText: String?,
    val newText: String?,
)

private class PatchApplyException(
    val path: String,
    val hunkIndex: Int,
    message: String,
    val partialText: String? = null,
) : IllegalArgumentException(message)

private fun parseUnifiedDiff(
    text: String,
    pathBase: String = "/workspace",
    externalMounts: List<me.rerere.workspace.WorkspaceExternalMount> = emptyList(),
): List<PatchFile> {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines()
    val result = mutableListOf<PatchFile>()
    var i = 0
    fun parseGitPathPair(line: String): Pair<String?, String?> {
        val rest = line.removePrefix("diff --git ").trim()
        val parts = rest.split(Regex("\\s+"), limit = 2)
        return normalizeDiffPath(parts.getOrNull(0), pathBase, externalMounts) to
            normalizeDiffPath(parts.getOrNull(1), pathBase, externalMounts)
    }
    while (i < lines.size) {
        if (lines[i].isBlank()) { i++; continue }
        var oldPath: String? = null
        var newPath: String? = null
        var isCreate = false
        var isDelete = false
        var isRename = false
        var rawSourcePath: String? = null
        if (lines[i].startsWith("diff --git ")) {
            val pair = parseGitPathPair(lines[i])
            oldPath = pair.first
            newPath = pair.second
            rawSourcePath = lines[i].removePrefix("diff --git ").trim()
                .split(Regex("\\s+"), limit = 2).getOrNull(0)
            i++
        } else if (lines[i].startsWith("--- ")) {
            rawSourcePath = lines[i].removePrefix("--- ").substringBefore('\t').trim()
            oldPath = normalizeDiffPath(lines[i].removePrefix("--- ").substringBefore('\t').trim(), pathBase, externalMounts)
        } else {
            error("Unsupported patch line: ${lines[i]}")
        }
        val hunks = mutableListOf<PatchHunk>()
        while (i < lines.size && !lines[i].startsWith("diff --git ")) {
            val line = lines[i]
            // A "--- "/"+++ " pair appearing after this file's hunks starts the next file entry.
            // Without this, a multi-file patch that omits "diff --git" headers collapses every
            // file into one PatchFile, and all but the last path silently loses its hunks.
            if (line.startsWith("--- ") && hunks.isNotEmpty() &&
                lines.getOrNull(i + 1)?.startsWith("+++ ") == true
            ) break
            when {
                line.startsWith("new file mode ") -> isCreate = true
                line.startsWith("deleted file mode ") -> isDelete = true
                line.startsWith("rename from ") -> {
                    isRename = true
                    oldPath = normalizeDiffPath(line.removePrefix("rename from ").trim(), pathBase, externalMounts)
                }
                line.startsWith("rename to ") -> {
                    isRename = true
                    newPath = normalizeDiffPath(line.removePrefix("rename to ").trim(), pathBase, externalMounts)
                }
                line.startsWith("--- ") -> oldPath = normalizeDiffPath(line.removePrefix("--- ").substringBefore('\t').trim(), pathBase, externalMounts)
                line.startsWith("+++ ") -> newPath = normalizeDiffPath(line.removePrefix("+++ ").substringBefore('\t').trim(), pathBase, externalMounts)
                line.startsWith("@@") -> {
                    val parsed = parseHunkHeader(line)
                    i++
                    val hunkLines = mutableListOf<PatchLine>()
                    var oldSeen = 0
                    var newSeen = 0
                    while (i < lines.size && !lines[i].startsWith("@@") && !lines[i].startsWith("diff --git ")) {
                        val hunkLine = lines[i]
                        if (hunkLine.startsWith("\\ No newline at end of file")) {
                            i++
                            continue
                        }
                        // 无行号的 "@@" 头没有可信计数, 只能靠 hunk 行本身的形态判断边界
                        val countsSatisfied = !parsed.rangesOmitted &&
                            oldSeen >= parsed.oldCount && newSeen >= parsed.newCount
                        // 空行视为上下文空行: unified diff 的上下文空行是 " "(单个空格),
                        // 但编辑器/传输层常会 trim 行尾空格使其变成完全空行, git apply 同样容忍。
                        // 声明行数已读满后遇到的空行视为 hunk 结束(补丁末尾/段落间的空行)。
                        if (hunkLine.isEmpty()) {
                            if (countsSatisfied) break
                            hunkLines += PatchLine(' ', "")
                            oldSeen++; newSeen++
                            i++
                            continue
                        }
                        // 行数读满后, 下一个文件的 "--- /+++ " 头不能被误当作删除/新增行
                        if (countsSatisfied && (hunkLine.startsWith("--- ") || hunkLine.startsWith("+++ "))) break
                        // 无行号 hunk 同样要防止吃掉下一个文件头: "--- "/"+++ " 紧跟 "@@" 才是文件头,
                        // 真正的删除/新增行不会出现这种成对形态
                        if (parsed.rangesOmitted &&
                            (hunkLine.startsWith("--- ") || hunkLine.startsWith("+++ ")) &&
                            lines.getOrNull(i + 1)?.startsWith("+++ ") == true
                        ) break
                        val type = hunkLine[0]
                        // 行数声明常由 LLM 生成、可能偏小, 只要仍是合法 hunk 行就继续读;
                        // 读满后遇到非 hunk 行则视为 hunk 结束而不是报错
                        if (type != ' ' && type != '+' && type != '-') {
                            if (countsSatisfied) break
                            error("Invalid hunk line prefix: $hunkLine")
                        }
                        when (type) {
                            ' ' -> { oldSeen++; newSeen++ }
                            '-' -> oldSeen++
                            '+' -> newSeen++
                        }
                        hunkLines += PatchLine(type, hunkLine.drop(1))
                        i++
                    }
                    // 去掉按空行补进来的尾部空上下文(超出声明行数的部分, 多为补丁末尾空行)
                    while (hunkLines.isNotEmpty() && hunkLines.last().let { it.type == ' ' && it.text.isEmpty() } &&
                        (parsed.rangesOmitted || (oldSeen > parsed.oldCount && newSeen > parsed.newCount))
                    ) {
                        hunkLines.removeAt(hunkLines.lastIndex)
                        oldSeen--; newSeen--
                    }
                    hunks += parsed.copy(lines = hunkLines)
                    continue
                }
            }
            i++
        }
        if (oldPath == null && newPath == null) continue
        if (oldPath == null || oldPath == "/dev/null") isCreate = true
        if (newPath == null || newPath == "/dev/null") isDelete = true
        result += PatchFile(
            oldPath = oldPath?.takeUnless { it == "/dev/null" },
            newPath = newPath?.takeUnless { it == "/dev/null" },
            isCreate = isCreate,
            isDelete = isDelete,
            isRename = isRename,
            hunks = hunks,
            rawSourcePath = rawSourcePath,
        )
    }
    return result
}

private fun parseHunkHeader(line: String): PatchHunk {
    // Line numbers may be omitted entirely ("@@"), in which case the hunk is located
    // purely by context matching. `git apply` accepts this, and LLM-authored patches
    // use it constantly, so treat the ranges as optional rather than rejecting the hunk.
    val match = Regex("@@(?: -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*)?").matchEntire(line)
        ?: error("Invalid hunk header: $line")
    val hasRanges = match.groupValues[1].isNotBlank()
    return PatchHunk(
        oldStart = if (hasRanges) match.groupValues[1].toInt() else 0,
        oldCount = if (hasRanges) match.groupValues[2].ifBlank { "1" }.toInt() else 0,
        newStart = if (hasRanges) match.groupValues[3].toInt() else 0,
        newCount = if (hasRanges) match.groupValues[4].ifBlank { "1" }.toInt() else 0,
        lines = emptyList(),
        rangesOmitted = !hasRanges,
    )
}

private fun normalizeDiffPath(
    raw: String?,
    pathBase: String = "/workspace",
    externalMounts: List<me.rerere.workspace.WorkspaceExternalMount> = emptyList(),
): String? {
    if (raw.isNullOrBlank()) return null
    val cleaned = raw.trim().removeSurrounding("\"")
    if (cleaned == "/dev/null") return "/dev/null"
    val noPrefix = cleaned.removePrefix("a/").removePrefix("b/").removePrefix("./")
    val path = if (noPrefix.startsWith("/")) {
        noPrefix.replace(Regex("/+"), "/").trimEnd('/').ifBlank { "/" }
    } else {
        val externalTargets = externalMounts.map { it.normalizedTargetPath().removePrefix("/").trimEnd('/') }
        val isExternal = externalTargets.any { target ->
            target.isNotEmpty() && (noPrefix == target || noPrefix.startsWith("$target/"))
        }
        if (isExternal) {
            "/$noPrefix".replace(Regex("/+"), "/").trimEnd('/').ifBlank { "/" }
        } else {
            // 与 resolveAbsolutePath 共用同一份拼接逻辑
            joinPathBase(pathBase, noPrefix)
        }
    }
    require(!path.contains('\u0000') && path.split('/').none { it == ".." }) {
        "Patch path escapes workspace: $raw"
    }
    require(path.isAllowedPatchPath(externalMounts)) {
        "Patch path is outside /workspace and configured external mounts: $raw"
    }
    return path
}

private fun String.isAllowedPatchPath(
    externalMounts: List<me.rerere.workspace.WorkspaceExternalMount>,
): Boolean {
    if (this == "/workspace" || startsWith("/workspace/")) return true
    return externalMounts.any { mount ->
        val target = mount.normalizedTargetPath().trimEnd('/').ifBlank { "/" }
        this == target || startsWith("$target/")
    }
}

private fun List<PatchFile>.touchedPaths(): List<String> = flatMap { file ->
    listOfNotNull(file.oldPath, file.newPath).filter { it != "/dev/null" }
}.distinct()

private suspend fun applyFilePatchToSnapshot(
    filePatch: PatchFile,
    originals: Map<String, String?>,
): PatchApplyResult {
    val sourcePath = filePatch.oldPath ?: filePatch.newPath ?: error("Patch file path missing")
    val targetPath = filePatch.newPath ?: filePatch.oldPath ?: error("Patch file path missing")
    val original = if (filePatch.isCreate) null else originals[sourcePath]
    require(filePatch.isCreate || original != null) {
        "File does not exist: $sourcePath" +
            (filePatch.rawSourcePath?.takeIf { it != sourcePath }?.let { raw ->
                " (resolved from the relative patch header '$raw'; " +
                    "relative paths resolve against the shell working directory, " +
                    "so pass an absolute path like '--- a$sourcePath' if that base is wrong)"
            } ?: "") +
            ". Add 'new file mode 100644' if you meant to create it."
    }
    val targetOriginal = if (filePatch.isRename || filePatch.isCreate) originals[targetPath] else null
    require(!filePatch.isRename || targetOriginal == null) { "Rename target already exists: $targetPath" }
    require(!filePatch.isCreate || targetOriginal == null) { "Create target already exists: $targetPath" }
    val newText = when {
        filePatch.isDelete -> null
        // An empty hunk list is only legitimate for pure rename / mode-change entries. Anywhere
        // else it means the hunks failed to parse, and silently returning the original text would
        // report applied=true for a zero-byte write.
        filePatch.hunks.isEmpty() -> {
            require(filePatch.isRename) {
                "Patch for $targetPath contains no usable hunks. " +
                    "Check the hunk headers: each hunk must start with '@@'."
            }
            original.orEmpty()
        }
        else -> applyHunksToText(original.orEmpty(), filePatch.hunks, targetPath).first
    }
    val status = when {
        filePatch.isCreate -> "created"
        filePatch.isDelete -> "deleted"
        filePatch.isRename -> if (filePatch.hunks.isEmpty()) "renamed" else "renamed_modified"
        else -> "modified"
    }
    return PatchApplyResult(
        summary = PatchFileSummary(
            path = targetPath,
            status = status,
            oldSize = original?.length ?: 0,
            newSize = newText?.length ?: 0,
        ),
        oldText = original,
        newText = newText,
    )
}

private suspend fun applyAndWriteFilePatch(
    workspaceRepository: WorkspaceRepository,
    workspaceId: String,
    filePatch: PatchFile,
): PatchApplyResult {
    val sourcePath = filePatch.oldPath ?: filePatch.newPath ?: error("Patch file path missing")
    val targetPath = filePatch.newPath ?: filePatch.oldPath ?: error("Patch file path missing")
    val original = if (filePatch.isCreate) null else workspaceRepository.readTextInRootfs(workspaceId, sourcePath)
    if (filePatch.isRename || filePatch.isCreate) {
        val existing = workspaceRepository.readOptionalTextInRootfs(workspaceId, targetPath)
        require(!filePatch.isRename || existing == null) { "Rename target already exists: $targetPath" }
        require(!filePatch.isCreate || existing == null) { "Create target already exists: $targetPath" }
    }
    // Mirrors the guard in applyFilePatchToSnapshot: only a pure rename may carry zero hunks.
    require(filePatch.hunks.isNotEmpty() || filePatch.isRename || filePatch.isDelete || filePatch.isCreate) {
        "Patch for $targetPath contains no usable hunks. " +
            "Check the hunk headers: each hunk must start with '@@'."
    }
    var current = original.orEmpty()
    try {
        current = applyHunksToText(current, filePatch.hunks, targetPath).first
    } catch (e: PatchApplyException) {
        val partial = e.partialText
        if (partial != null && !filePatch.isDelete && !filePatch.isRename) {
            workspaceRepository.writeTextInRootfs(workspaceId, targetPath, partial, overwrite = true)
        }
        throw e
    }
    when {
        filePatch.isDelete -> workspaceRepository.deletePathInRootfs(workspaceId, sourcePath)
        filePatch.isCreate -> workspaceRepository.writeTextInRootfs(workspaceId, targetPath, current, overwrite = false)
        filePatch.isRename -> {
            workspaceRepository.writeTextInRootfs(workspaceId, targetPath, current, overwrite = false)
            workspaceRepository.deletePathInRootfs(workspaceId, sourcePath)
        }
        else -> workspaceRepository.writeTextInRootfs(workspaceId, targetPath, current, overwrite = true)
    }
    val status = when {
        filePatch.isCreate -> "created"
        filePatch.isDelete -> "deleted"
        filePatch.isRename -> if (filePatch.hunks.isEmpty()) "renamed" else "renamed_modified"
        else -> "modified"
    }
    return PatchApplyResult(PatchFileSummary(targetPath, status, original?.length ?: 0, current.length), original, current)
}

private fun applyHunksToText(text: String, hunks: List<PatchHunk>, path: String): Pair<String, Int> {
    val trailingNewline = text.endsWith('\n')
    val lines = if (text.isEmpty()) mutableListOf() else text.removeSuffix("\n").split('\n').toMutableList()
    var offset = 0
    var applied = 0
    for ((index, hunk) in hunks.withIndex()) {
        val oldSegment = hunk.lines.filter { it.type == ' ' || it.type == '-' }.map { it.text }
        val newSegment = hunk.lines.filter { it.type == ' ' || it.type == '+' }.map { it.text }
        val expected = (hunk.oldStart - 1 + offset).coerceIn(0, lines.size)
        val position = findHunkPosition(lines, oldSegment, expected)
            ?: throw PatchApplyException(
                path = path,
                hunkIndex = index + 1,
                message = "Hunk context not found at -${hunk.oldStart},${hunk.oldCount}",
                partialText = if (applied > 0) lines.joinToString("\n") + (if (trailingNewline) "\n" else "") else null,
            )
        repeat(oldSegment.size) { lines.removeAt(position) }
        lines.addAll(position, newSegment)
        offset += newSegment.size - oldSegment.size
        applied++
    }
    val updated = if (lines.isEmpty()) "" else lines.joinToString("\n") + if (trailingNewline) "\n" else ""
    return updated to applied
}

private fun findHunkPosition(lines: List<String>, oldSegment: List<String>, expected: Int): Int? {
    if (oldSegment.isEmpty()) return expected.coerceIn(0, lines.size)
    fun matchesAt(pos: Int): Boolean =
        pos >= 0 && pos + oldSegment.size <= lines.size && lines.subList(pos, pos + oldSegment.size) == oldSegment
    if (matchesAt(expected)) return expected
    val start = (expected - 40).coerceAtLeast(0)
    val end = (expected + 40).coerceAtMost(lines.size)
    for (pos in start..end) if (matchesAt(pos)) return pos
    for (pos in 0..(lines.size - oldSegment.size).coerceAtLeast(0)) if (matchesAt(pos)) return pos
    return null
}

private suspend fun WorkspaceRepository.createWorkspaceBackup(
    workspaceId: String,
    paths: List<String>,
    reason: String,
): String? {
    val config = getToolConfig(workspaceId).backup
    if (!config.enabled) return null
    val id = "${System.currentTimeMillis()}-${(1000..9999).random()}"
    val root = "/workspace/.rikkahub/backups/$id"
    val entries = paths.distinct().mapIndexed { index, path ->
        when (pathStateInRootfs(workspaceId, path)) {
            "missing" -> WorkspaceBackupEntry(path = path, existed = false)
            "file" -> {
                val text = readTextInRootfs(workspaceId, path)
                val backupPath = "$root/files/$index.txt"
                writeTextInRootfs(workspaceId, backupPath, text, overwrite = true)
                WorkspaceBackupEntry(path = path, existed = true, backupPath = backupPath, sizeBytes = text.toByteArray().size.toLong())
            }
            else -> error("Cannot backup non-file path: $path")
        }
    }
    val manifest = WorkspaceBackupManifest(id, System.currentTimeMillis(), reason, entries)
    writeTextInRootfs(workspaceId, "$root/manifest.json", JsonInstantPretty.encodeToString(WorkspaceBackupManifest.serializer(), manifest), overwrite = true)
    if (config.autoCleanup) cleanupWorkspaceBackups(workspaceId)
    return id
}

private suspend fun WorkspaceRepository.listWorkspaceBackups(
    workspaceId: String,
    limit: Int,
): List<kotlinx.serialization.json.JsonObject> {
    cleanupWorkspaceBackups(workspaceId)
    val entries = runCatching { listFiles(workspaceId, WorkspaceStorageArea.FILES, ".rikkahub/backups") }.getOrDefault(emptyList())
    return entries.filter { it.isDirectory }
        .mapNotNull { entry -> readBackupManifest(workspaceId, entry.name) }
        .sortedByDescending { it.createdAt }
        .take(limit)
        .map { manifest ->
            buildJsonObject {
                put("backup_id", manifest.backupId)
                put("created_at", manifest.createdAt)
                put("reason", manifest.reason)
                put("files", manifest.entries.size)
                put("size_bytes", manifest.entries.sumOf { it.sizeBytes })
            }
        }
}

private suspend fun WorkspaceRepository.restoreWorkspaceBackup(
    workspaceId: String,
    backupId: String,
    files: List<String>? = null,
    createPreRestoreBackup: Boolean = true,
): kotlinx.serialization.json.JsonObject {
    val manifest = readBackupManifest(workspaceId, backupId) ?: error("Backup not found: $backupId")
    val selected = files?.toSet()
    val entries = manifest.entries.filter { selected == null || it.path in selected }
    require(entries.isNotEmpty()) { "No matching files in backup: $backupId" }
    val backupConfig = getToolConfig(workspaceId).backup
    val preRestoreBackupId = if (createPreRestoreBackup && backupConfig.enabled && backupConfig.backupBeforeRestore) {
        createWorkspaceBackup(workspaceId, entries.map { it.path }, "workspace_restore_backup")
    } else null
    for (entry in entries.asReversed()) {
        if (entry.existed) {
            val backupPath = entry.backupPath ?: error("Backup entry missing content: ${entry.path}")
            val text = readTextInRootfs(workspaceId, backupPath)
            writeTextInRootfs(workspaceId, entry.path, text, overwrite = true)
        } else {
            if (pathStateInRootfs(workspaceId, entry.path) != "missing") {
                deletePathInRootfs(workspaceId, entry.path)
            }
        }
    }
    return buildJsonObject {
        put("restored", true)
        put("backup_id", backupId)
        preRestoreBackupId?.let { put("pre_restore_backup_id", it) }
        put("files", entries.map { it.path }.joinToString(","))
    }
}

private suspend fun WorkspaceRepository.readBackupManifest(workspaceId: String, backupId: String): WorkspaceBackupManifest? =
    runCatching {
        val text = readText(workspaceId, ".rikkahub/backups/$backupId/manifest.json")
        JsonInstant.decodeFromString(WorkspaceBackupManifest.serializer(), text)
    }.getOrNull()

private suspend fun WorkspaceRepository.cleanupWorkspaceBackups(workspaceId: String) {
    val config = getToolConfig(workspaceId).backup
    val entries = runCatching { listFiles(workspaceId, WorkspaceStorageArea.FILES, ".rikkahub/backups") }.getOrDefault(emptyList())
    val manifests = entries.filter { it.isDirectory }.mapNotNull { entry -> readBackupManifest(workspaceId, entry.name) }
    val now = System.currentTimeMillis()
    val retentionMillis = config.retentionDays.coerceAtLeast(1).toLong() * 24L * 60L * 60L * 1000L
    val toDelete = mutableSetOf<String>()
    manifests.filter { now - it.createdAt > retentionMillis }.forEach { toDelete += it.backupId }
    manifests.sortedByDescending { it.createdAt }.drop(config.maxBackups.coerceAtLeast(1)).forEach { toDelete += it.backupId }
    var kept = manifests.filter { it.backupId !in toDelete }.sortedBy { it.createdAt }
    var total = kept.sumOf { it.entries.sumOf { entry -> entry.sizeBytes } }
    while (total > config.maxTotalBytes.coerceAtLeast(1) && kept.isNotEmpty()) {
        val oldest = kept.first()
        toDelete += oldest.backupId
        total -= oldest.entries.sumOf { it.sizeBytes }
        kept = kept.drop(1)
    }
    for (id in toDelete) {
        runCatching { deleteFile(workspaceId, WorkspaceStorageArea.FILES, ".rikkahub/backups/$id", recursive = true) }
    }
}

private suspend fun WorkspaceRepository.readOptionalTextInRootfs(workspaceId: String, path: String): String? =
    when (pathStateInRootfs(workspaceId, path)) {
        "missing" -> null
        "file" -> readTextInRootfs(workspaceId, path)
        else -> error("Path is not a text file: $path")
    }

private suspend fun WorkspaceRepository.pathStateInRootfs(workspaceId: String, path: String): String {
    externalMountedFile(workspaceId, path)?.let { (_, file) ->
        return when {
            !file.exists() -> "missing"
            file.isFile -> "file"
            file.isDirectory -> "directory"
            else -> "other"
        }
    }
    val result = runRootfsCommand(
        workspaceId = workspaceId,
        action = "Stat path",
        command = """
            if [ -e ${path.shellQuote()} ]; then
              if [ -f ${path.shellQuote()} ]; then printf file; elif [ -d ${path.shellQuote()} ]; then printf directory; else printf other; fi
            else
              printf missing
            fi
        """.trimIndent(),
    )
    return result.stdout.trim()
}

private suspend fun WorkspaceRepository.deletePathInRootfs(workspaceId: String, path: String) {
    externalMountedFile(workspaceId, path)?.let { (mount, file) ->
        require(mount.writable) { "External mount is read-only: ${mount.normalizedTargetPath()}" }
        if (file.exists()) require(if (file.isDirectory) file.deleteRecursively() else file.delete()) { "Failed to delete: $path" }
        return
    }
    val (area, relativePath) = rootfsPathToAreaAndRelative(path)
    if (area == WorkspaceStorageArea.FILES) {
        deleteFile(workspaceId, area, relativePath, recursive = true)
    } else {
        runRootfsCommand(
            workspaceId = workspaceId,
            action = "Delete path",
            command = "rm -rf -- ${path.shellQuote()}",
        )
    }
}

private fun List<PatchFileSummary>.toPatchSummaryJsonArray(): kotlinx.serialization.json.JsonArray =
    kotlinx.serialization.json.JsonArray(map { summary ->
        buildJsonObject {
            put("path", summary.path)
            put("status", summary.status)
            put("old_size", summary.oldSize)
            put("new_size", summary.newSize)
        }
    })

private fun List<kotlinx.serialization.json.JsonObject>.toJsonObjectArray(): kotlinx.serialization.json.JsonArray =
    kotlinx.serialization.json.JsonArray(this)

/**
 * 宽容地把任意 JsonElement 解读成 JsonArray。
 *
 * LLM 生成的 tool call 参数天生是脏的，常见三种偏差都在这里兜住:
 *  1. 标准数组              -> 直接返回
 *  2. 被当成字符串的数组     -> "[{...},{...}]" 二次 parse (高频, 内容含 \n 和引号时尤其容易发生)
 *  3. 单个对象未包成数组     -> {...} 自动升维成 [{...}]
 * 解析失败一律返回 null, 交由调用方走原有的缺参报错路径。
 */
private fun kotlinx.serialization.json.JsonElement.jsonArrayOrNull(): kotlinx.serialization.json.JsonArray? =
    when (this) {
        is kotlinx.serialization.json.JsonArray -> this
        is kotlinx.serialization.json.JsonObject -> kotlinx.serialization.json.JsonArray(listOf(this))
        is kotlinx.serialization.json.JsonPrimitive -> {
            if (!isString) null
            else runCatching {
                when (val parsed = JsonInstant.parseToJsonElement(content.trim())) {
                    is kotlinx.serialization.json.JsonArray -> parsed
                    is kotlinx.serialization.json.JsonObject ->
                        kotlinx.serialization.json.JsonArray(listOf(parsed))
                    else -> null
                }
            }.getOrNull()
        }
        else -> null
    }

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

/**
 * 宽容地把参数解读成「字符串列表」(用于 paths / files 这类纯字符串数组)。
 * 除 jsonArrayOrNull 的三种兜底外, 额外容忍:
 *  4. 单个裸字符串        -> "a.txt"        升维成 ["a.txt"]
 *  5. 逗号分隔的字符串    -> "a.txt,b.txt"  拆成 ["a.txt", "b.txt"]
 * 数组元素中的非字符串项会被丢弃。
 */
private fun kotlinx.serialization.json.JsonElement.stringListOrNull(): List<String>? {
    jsonArrayOrNull()?.let { arr ->
        return arr.mapNotNull { el ->
            (el as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        }
    }
    val prim = this as? kotlinx.serialization.json.JsonPrimitive ?: return null
    if (!prim.isString) return null
    val raw = prim.content.trim()
    if (raw.isEmpty()) return null
    return raw.split(',').map { s -> s.trim() }.filter { s -> s.isNotEmpty() }
}

private data class WorkspaceReadTextResult(
    val text: String,
    val startLine: Int,
    val endLine: Int,
    val returnedLines: Int,
    val totalLines: Int,
    val nextStartLine: Int?,
    val truncated: Boolean,
)

private suspend fun WorkspaceRepository.readTextRangeInRootfs(
    workspaceId: String,
    path: String,
    startLine: Int,
    lineCount: Int,
    maxChars: Int,
    maxFileBytes: Long,
    includeLineNumbers: Boolean,
): WorkspaceReadTextResult {
    val fullText = readTextInRootfs(workspaceId, path, maxFileBytes)
    val lines = fullText.lines()
    val startIndex = (startLine - 1).coerceIn(0, lines.size.coerceAtLeast(1) - 1)
    val endExclusive = (startIndex + lineCount).coerceAtMost(lines.size)
    val builder = StringBuilder()
    var endLine = startLine - 1
    var returnedLines = 0
    var charTruncated = false
    for (index in startIndex until endExclusive) {
        val numberedLine = if (includeLineNumbers) "${index + 1}: ${lines[index]}" else lines[index]
        val additional = numberedLine.length + if (builder.isEmpty()) 0 else 1
        if (builder.length + additional > maxChars) {
            charTruncated = true
            break
        }
        if (builder.isNotEmpty()) builder.append("\n")
        builder.append(numberedLine)
        endLine = index + 1
        returnedLines += 1
    }
    val lineTruncated = endExclusive < lines.size || charTruncated
    val next = if (lineTruncated && endLine < lines.size) endLine + 1 else null
    return WorkspaceReadTextResult(
        text = builder.toString(),
        startLine = startIndex + 1,
        endLine = endLine.coerceAtLeast(startIndex + 1),
        returnedLines = returnedLines,
        totalLines = lines.size,
        nextStartLine = next,
        truncated = lineTruncated,
    )
}

private suspend fun WorkspaceRepository.readTextInRootfs(
    workspaceId: String,
    path: String,
    maxFileBytes: Long = MAX_READ_FILE_BYTES,
): String {
    externalMountedFile(workspaceId, path)?.let { (_, file) ->
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        require(file.length() <= maxFileBytes) {
            "File is too large to read: $path (${file.length() / 1024 / 1024}MB, max ${maxFileBytes / 1024 / 1024}MB). Use ranged reads or shell commands like head, tail, or grep to read parts of it."
        }
        return file.readText(Charsets.UTF_8)
    }

    val (area, relativePath) = rootfsPathToAreaAndRelative(path)
    val size = fileSize(workspaceId, area, relativePath)
    require(size <= maxFileBytes) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${maxFileBytes / 1024 / 1024}MB). Use ranged reads or shell commands like head, tail, or grep to read parts of it."
    }
    val buffer = ByteArrayOutputStream(size.toInt())
    exportFile(workspaceId, area, relativePath, buffer)
    return buffer.toString(Charsets.UTF_8.name())
}

private suspend fun WorkspaceRepository.externalMountedFile(
    workspaceId: String,
    path: String,
): Pair<me.rerere.workspace.WorkspaceExternalMount, java.io.File>? {
    val workspace = getById(workspaceId) ?: return null
    if (workspace.runtimeTypeValue() != WorkspaceRuntimeType.BUILTIN_PROOT) return null
    return resolveExternalMountFile(workspace, path)
}

private fun rootfsPathToAreaAndRelative(path: String): Pair<WorkspaceStorageArea, String> {
    val trimmed = path.trimEnd('/')
    return if (trimmed == "/workspace" || trimmed.startsWith("/workspace/")) {
        WorkspaceStorageArea.FILES to trimmed.removePrefix("/workspace").trimStart('/')
    } else {
        WorkspaceStorageArea.LINUX to trimmed.trimStart('/')
    }
}

private suspend fun WorkspaceRepository.readImageInRootfs(
    workspaceId: String,
    path: String,
    uncompressed: Boolean = false,
): List<UIMessagePart> {
    val bytes = externalMountedFile(workspaceId, path)?.let { (_, file) ->
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        file.readBytes()
    } ?: run {
        val (area, relativePath) = rootfsPathToAreaAndRelative(path)
        val buffer = ByteArrayOutputStream()
        exportFile(workspaceId, area, relativePath, buffer)
        buffer.toByteArray()
    }
    return readImageInRootfs(workspaceId, path, bytes, uncompressed)
}

private suspend fun WorkspaceRepository.readImageInRootfs(
    workspaceId: String,
    path: String,
    bytes: ByteArray,
    uncompressed: Boolean,
): List<UIMessagePart> {
    val filesManager = getKoin().get<FilesManager>()
    val assetResolver = getKoin().get<AssetResolver>()

    val originalDisplayName = path.substringAfterLast('/').ifBlank { "workspace_image.png" }
    val detectedMime = runCatching {
        filesManager.getFileMimeType(android.net.Uri.parse("file:///$originalDisplayName"))
    }.getOrNull() ?: "image/png"

    // 1. 保存原图 Asset (asset_uri)
    val originalAsset = assetResolver.createFromBytes(
        bytes = bytes,
        displayName = originalDisplayName,
        mimeType = detectedMime,
        folder = FileFolders.UPLOAD,
        description = "Workspace read_file original image: $path",
    )
    val originalAssetUri = AssetUri.fromId(originalAsset.id)

    // 2. 生成/保存低 Token 的 Preview 压缩图 Asset (preview_asset_uri)
    val previewBytes = run {
        val temp = kotlin.io.path.createTempFile(prefix = "workspace_read_preview_", suffix = ".img").toFile()
        try {
            temp.writeBytes(bytes)
            filesManager.createLlmPreviewImageBytes(temp) ?: bytes
        } finally {
            runCatching { temp.delete() }
        }
    }

    val previewAsset = assetResolver.createFromBytes(
        bytes = previewBytes,
        displayName = "preview_${originalAsset.id}.jpg",
        mimeType = "image/jpeg",
        folder = FileFolders.LLM_PREVIEWS,
        description = "Workspace read_file preview for asset ${originalAsset.id}",
    )
    val previewAssetUri = AssetUri.fromId(previewAsset.id)

    return listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("status", "ok")
                put("path", path)
                put("asset_uri", originalAssetUri)
                put("preview_asset_uri", previewAssetUri)
                put("mime", detectedMime)
                put("uncompressed", uncompressed)
                put("description", if (uncompressed) "Original image file read successfully" else "Compressed image preview read successfully")
                put("transport", "asset")
            }.toString()
        ),
    )
}

private suspend fun WorkspaceRepository.writeTextInRootfs(
    workspaceId: String,
    path: String,
    text: String,
    overwrite: Boolean,
): WorkspaceFileEntry {
    externalMountedFile(workspaceId, path)?.let { (mount, file) ->
        require(mount.writable) { "External mount is read-only: ${mount.normalizedTargetPath()}" }
        if (file.exists() && !overwrite) error("File already exists: $path")
        if (file.exists() && !file.isFile) error("Path is not a file: $path")
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
        return WorkspaceFileEntry(
            path = path,
            name = file.name,
            isDirectory = false,
            sizeBytes = file.length(),
            updatedAt = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
        )
    }

    val (area, relativePath) = rootfsPathToAreaAndRelative(path)
    if (area == WorkspaceStorageArea.FILES) {
        return writeText(workspaceId, relativePath, text, overwrite)
    }

    val pathArg = path.shellQuote()
    val result = runRootfsCommand(
        workspaceId = workspaceId,
        action = "Write file",
        command = """
            if [ -e $pathArg ] && [ ${(!overwrite).shellFlag()} = 1 ]; then
              printf '%s\n' ${"File already exists: $path".shellQuote()} >&2
              exit 1
            fi
            if [ -e $pathArg ] && [ ! -f $pathArg ]; then
              printf '%s\n' ${"Path is not a file: $path".shellQuote()} >&2
              exit 1
            fi
            parent=${'$'}(dirname -- $pathArg) || exit 1
            mkdir -p -- "${'$'}parent" || exit 1
            cat > $pathArg || exit 1
            ${statEntryCommand(path)}
        """.trimIndent(),
        stdin = text.toByteArray(Charsets.UTF_8),
    )
    return result.stdout.parseRootfsEntry()
}

private suspend fun WorkspaceRepository.runRootfsCommand(
    workspaceId: String,
    action: String,
    command: String,
    stdin: ByteArray? = null,
): WorkspaceCommandResult {
    val result = executeCommand(
        id = workspaceId,
        command = command,
        timeoutMillis = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin = stdin,
    )
    if (result.timedOut) {
        error("$action timed out")
    }
    if (result.exitCode != 0) {
        val message = result.stderr.ifBlank { result.stdout }.trim()
        error(if (message.isBlank()) "$action failed with exit code ${result.exitCode}" else message)
    }
    if (result.truncated) {
        error("$action output is too large")
    }
    return result
}

private fun statEntryCommand(path: String): String {
    val pathArg = path.shellQuote()
    return """
        if [ -d $pathArg ]; then entry_type=d; else entry_type=f; fi
        entry_size=${'$'}(stat -c '%s' -- $pathArg) || exit 1
        entry_mtime=${'$'}(stat -c '%Y' -- $pathArg) || exit 1
        printf '%s\0%s\0%s\0%s\0' "${'$'}entry_type" "${'$'}entry_size" "${'$'}entry_mtime" $pathArg
    """.trimIndent()
}

private fun String.parseRootfsEntry(): WorkspaceFileEntry =
    parseRootfsEntries().singleOrNull() ?: error("Invalid file metadata output")

private fun String.parseRootfsEntries(): List<WorkspaceFileEntry> {
    val fields = split('\u0000').dropLastWhile { it.isEmpty() }
    require(fields.size % 4 == 0) { "Invalid file metadata output" }
    return fields.chunked(4).map { chunk ->
        val type = chunk[0]
        val size = chunk[1].toLongOrNull() ?: error("Invalid file size: ${chunk[1]}")
        val updatedAt = (chunk[2].toLongOrNull() ?: error("Invalid file mtime: ${chunk[2]}")) * 1_000L
        val path = chunk[3]
        WorkspaceFileEntry(
            path = path,
            name = path.rootfsName(),
            isDirectory = type == "d",
            sizeBytes = size,
            updatedAt = updatedAt,
        )
    }
}

private fun kotlinx.serialization.json.JsonObject.resolveAbsolutePath(
    name: String,
    pathBase: String = "/workspace",
    externalMounts: List<me.rerere.workspace.WorkspaceExternalMount> = emptyList(),
): String {
    val rawPath = string(name)?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(rawPath.isNotBlank()) { "$name is required" }
    require(!rawPath.contains('\u0000')) { "$name contains invalid character" }

    if (rawPath.startsWith("/")) {
        return rawPath.replace(Regex("/+"), "/").trimEnd('/').ifBlank { "/" }
    }

    val cleaned = rawPath.removePrefix("./")
    val externalTargets = externalMounts.map { it.normalizedTargetPath().removePrefix("/").trimEnd('/') }
    val isExternalRelative = externalTargets.any { target ->
        target.isNotEmpty() && (cleaned == target || cleaned.startsWith("$target/"))
    }
    if (isExternalRelative) {
        return "/$cleaned".replace(Regex("/+"), "/").trimEnd('/').ifBlank { "/" }
    }

    return joinPathBase(pathBase, cleaned)
}

/**
 * 相对路径拼接的唯一入口。resolveAbsolutePath(文件工具) 与 normalizeDiffPath(patch)
 * 必须走同一份逻辑, 否则同一个相对路径会在不同工具里指向不同文件。
 */
private fun joinPathBase(pathBase: String, cleanedRelative: String): String {
    val base = pathBase.takeIf { it.isNotBlank() }?.let {
        if (it.startsWith("/")) it else "/workspace/$it"
    }?.trimEnd('/') ?: "/workspace"
    return "$base/$cleanedRelative".replace(Regex("/+"), "/").trimEnd('/').ifBlank { "/" }
}

/**
 * 相对路径在基准目录下不存在、但在 /workspace 下存在时, 回退到后者。
 *
 * 这是对「基准目录判断错误」的容错: 不靠谁背住规则, 而是工具自己找回来。
 * 只对已存在的文件生效 —— 新建文件不该被情境影响, 否则写入位置会难以预测。
 * @return 实际使用的路径, 以及当发生回退时的原路径(用于向模型告知)。
 */
private suspend fun WorkspaceRepository.resolveExistingPath(
    workspaceId: String,
    path: String,
    pathBase: String,
    enabled: Boolean,
): Pair<String, String?> {
    val base = pathBase.trimEnd('/')
    // 基准就是工作区根时无处可退
    if (!enabled || base.isEmpty() || base == "/workspace") return path to null
    // 只处理「由相对路径拼到基准目录下」的情形; 模型显式给绝对路径时不猜
    if (!path.startsWith("$base/")) return path to null
    val stateAtBase = runCatching { pathStateInRootfs(workspaceId, path) }.getOrNull()
    if (stateAtBase == null || stateAtBase != "missing") return path to null
    val alt = "/workspace/${path.removePrefix("$base/")}"
        .replace(Regex("/+"), "/").trimEnd('/')
    if (alt == path) return path to null
    val stateAtRoot = runCatching { pathStateInRootfs(workspaceId, alt) }.getOrNull()
    return if (stateAtRoot == "file" || stateAtRoot == "directory") alt to path else path to null
}

private fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String =
    resolveAbsolutePath(name)

private fun kotlinx.serialization.json.JsonElement.pathOutsideWritableRoots(
    name: String,
    externalMounts: List<me.rerere.workspace.WorkspaceExternalMount> = emptyList(),
): Boolean = runCatching {
    jsonObject.resolveAbsolutePath(name).isOutsideWritableRoots(externalMounts)
}.getOrDefault(true)

// 免强制审批的可写安全区: 工作区文件目录, 以及临时目录 /tmp
private val WRITABLE_ROOT_PREFIXES = listOf("/workspace", "/tmp")

private fun String.isOutsideWritableRoots(
    externalMounts: List<me.rerere.workspace.WorkspaceExternalMount> = emptyList(),
): Boolean {
    val normalized = trimEnd('/').ifBlank { "/" }
    val matchedExternal = externalMounts
        .sortedByDescending { it.normalizedTargetPath().length }
        .firstOrNull { mount ->
            val target = mount.normalizedTargetPath()
            normalized == target || normalized.startsWith("$target/")
        }
    if (matchedExternal != null) {
        return !(matchedExternal.writable && matchedExternal.autoApproveWrites)
    }
    val builtInWritable = WRITABLE_ROOT_PREFIXES.any { prefix ->
        normalized == prefix || normalized.startsWith("$prefix/")
    }
    return !builtInWritable
}

private fun String.rootfsName(): String =
    trimEnd('/').substringAfterLast('/').ifBlank { "/" }

private fun String.shellQuote(): String =
    "'" + replace("'", "'\"'\"'") + "'"

private fun Boolean.shellFlag(): Int = if (this) 1 else 0

private fun JsonObjectBuilder.putPathProperty(required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        // 路径规则(绝对/相对均可)统一由 <workspace> 系统提示说明, 主对话与 subagent 都会注入,
        // 故不在每个工具的参数描述里重复一遍
        put("description", if (required) "File path." else "File path. Omit when using `paths`.")
    })
}

private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}
