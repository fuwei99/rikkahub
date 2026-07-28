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
    "workspace_list_backups" to false,
    "workspace_restore_backup" to false,
    "workspace_shell" to true,
    "workspace_grep" to false,
    "workspace_shell_background" to true,
)

val WorkspaceToolDefaultEnabled: Map<String, Boolean> = mapOf(
    "workspace_read_file" to true,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_apply_patch" to false,
    "workspace_list_backups" to false,
    "workspace_restore_backup" to false,
    "workspace_shell" to false,
    "workspace_grep" to true,
    "workspace_shell_background" to false,
)

val WorkspaceToolNames: List<String> = WorkspaceToolDefaultApprovals.keys.toList()

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

fun resolveWorkspaceToolDefaultEnabled(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultEnabled[name] ?: false

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
    val selectedTools = enabledTools ?: enabledByDefault
    val externalMounts = workspace?.externalMountConfigs().orEmpty()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    val shellCwd = cwd?.removePrefix("/workspace/")?.removePrefix("/workspace")

    return listOf(
        createReadFileTool(workspaceId, ::needsApproval, workspaceRepository, externalMounts),
        createWriteFileTool(workspaceId, ::needsApproval, workspaceRepository, externalMounts),
        createEditFileTool(workspaceId, ::needsApproval, workspaceRepository, externalMounts),
        createApplyPatchTool(workspaceId, ::needsApproval, workspaceRepository, externalMounts),
        createListBackupsTool(workspaceId, ::needsApproval, workspaceRepository),
        createRestoreBackupTool(workspaceId, ::needsApproval, workspaceRepository, externalMounts),
        createShellTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd, externalMounts),
        createGrepTool(workspaceId, ::needsApproval, workspaceRepository),
        createShellBackgroundTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd),
    ).filter { it.name in selectedTools }
}

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")

private fun StringBuilder.appendExternalMounts(
    externalMounts: List<me.rerere.workspace.WorkspaceExternalMount>,
) {
    if (externalMounts.isEmpty()) return
    append("External mounts: ")
    append(externalMounts.joinToString { mount ->
        "${mount.normalizedTargetPath()} (${if (mount.writable) "read/write" else "read-only"})"
    })
    append(". ")
}

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createReadFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    externalMounts: List<me.rerere.workspace.WorkspaceExternalMount>,
) = Tool(
    name = "workspace_read_file",
    description = buildString {
        append("Read a file using the assistant's bound workspace runtime. Paths must be absolute inside the runtime view. ")
        append("Use /workspace for the workspace files area. Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp). ")
        append("For text files, returns numbered lines. Use start_line, line_count and max_chars to read safely in chunks. Limits are configured in /workspace/$WORKSPACE_TOOL_CONFIG_PATH. ")
        append("To read several text files at once, pass paths=[...] (up to 8; start_line/line_count apply to each, per-file char budget is shared). ")
        appendExternalMounts(externalMounts)
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = false)
                put("paths", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put(
                        "description",
                        "Batch mode: absolute paths of text files to read in one call (max 8). Mutually exclusive with path."
                    )
                })
                put("start_line", buildJsonObject {
                    put("type", "integer")
                    put("description", "1-based line number to start reading from. Defaults to 1.")
                })
                put("line_count", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum lines to return. Defaults to 400, hard max 2000. Read large files in chunks.")
                })
                put("max_chars", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum characters to return. Defaults to 20000, hard max 60000.")
                })
            },
            required = emptyList(),
        )
    },
    needsApproval = { needsApproval("workspace_read_file") },
    execute = { input ->
        val params = input.jsonObject
        val config = workspaceRepository.getToolConfig(workspaceId).readFile
        val startLine = (params["start_line"]?.jsonPrimitive?.intOrNull ?: config.defaultStartLine)
            .coerceAtLeast(1)
        val lineCount = (params["line_count"]?.jsonPrimitive?.intOrNull ?: config.defaultLineCount)
            .coerceIn(1, config.maxLineCount.coerceAtLeast(1))
        val maxChars = (params["max_chars"]?.jsonPrimitive?.intOrNull ?: config.defaultMaxChars)
            .coerceIn(1_000, config.hardMaxChars.coerceAtLeast(1_000))

        suspend fun readOne(path: String, charBudget: Int): kotlinx.serialization.json.JsonObject {
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
                put("start_line", result.startLine)
                put("end_line", result.endLine)
                put("line_count", result.returnedLines)
                put("total_lines", result.totalLines)
                result.nextStartLine?.let { next -> put("next_start_line", next) }
                put("truncated", result.truncated)
                put("text", result.text)
            }
        }

        val batchPaths = params["paths"]?.jsonArrayOrNull()
        if (batchPaths != null) {
            require(batchPaths.isNotEmpty()) { "paths must not be empty" }
            require(batchPaths.size <= 8) { "paths supports at most 8 files per call" }
            val resolved = batchPaths.map { el ->
                val raw = el.jsonPrimitive.contentOrNull ?: error("paths entries must be strings")
                buildJsonObject { put("path", raw) }.absolutePath("path")
            }
            require(resolved.none { it.isImagePath() }) { "Batch mode supports text files only" }
            // 每个文件均分字符预算, 避免批量读取撑爆上下文
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
            val path = params.absolutePath("path")
            if (path.isImagePath()) {
                workspaceRepository.readImageInRootfs(workspaceId, path)
            } else {
                listOf(UIMessagePart.Text(readOne(path, maxChars).toString()))
            }
        }
    },
)

private fun createWriteFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    externalMounts: List<me.rerere.workspace.WorkspaceExternalMount>,
) = Tool(
    name = "workspace_write_file",
    description = buildString {
        append("Write a UTF-8 text file using the assistant's bound workspace runtime. Paths must be absolute inside the runtime view. ")
        append("Use /workspace for the workspace files area. ")
        appendExternalMounts(externalMounts)
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    needsApproval = { needsApproval("workspace_write_file") || it.pathOutsideWritableRoots("path", externalMounts) },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
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
    externalMounts: List<me.rerere.workspace.WorkspaceExternalMount>,
) = Tool(
    name = "workspace_edit_file",
    description = buildString {
        append("Edit a UTF-8 text file using the assistant's bound workspace runtime. Paths must be absolute inside the runtime view. Use /workspace for the workspace files area. ")
        append("[REQUIRED PAYLOAD] You MUST supply the edit content in exactly ONE of the following two mutually-exclusive modes, in addition to `path`:\n")
        append("  (A) Single-edit mode: provide BOTH `old_text` AND `new_text` at the top level. Optionally set `replace_all=true` to replace every occurrence (default: replace exactly one occurrence).\n")
        append("  (B) Multi-edit mode: provide an `edits` array of {old_text, new_text, replace_all?} objects, applied in order and atomically. Mutually exclusive with top-level old_text/new_text.\n")
        append("Calling this tool with ONLY `path` and no edit payload will fail — you must include either (old_text + new_text) or `edits`. ")
        append("If no exact match is found, whitespace-tolerant line matching is attempted automatically. ")
        appendExternalMounts(externalMounts)
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "[Single-edit mode] Exact text to replace. REQUIRED together with `new_text` unless you use `edits` (multi-edit mode) instead.")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "[Single-edit mode] Replacement text. REQUIRED together with `old_text` unless you use `edits` (multi-edit mode) instead.")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "[Single-edit mode] Whether to replace every occurrence. Defaults to false.")
                })
                put("edits", buildJsonObject {
                    put("type", "array")
                    put(
                        "description",
                        "[Multi-edit mode] Non-empty list of {old_text, new_text, replace_all?} applied sequentially and atomically. Mutually exclusive with top-level old_text/new_text — use this OR (old_text + new_text), never neither."
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
    needsApproval = { needsApproval("workspace_edit_file") || it.pathOutsideWritableRoots("path", externalMounts) },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")

        // 前置载荷校验: 必须提供 (old_text + new_text) 或 edits, 提前给出人类可读错误
        val hasSingle = params["old_text"] != null || params["new_text"] != null
        val hasMulti = params["edits"] != null
        require(hasSingle || hasMulti) {
            "workspace_edit_file requires either (old_text + new_text) for single-edit mode, " +
                    "or an `edits` array for multi-edit mode. Only `path` was provided — nothing to edit."
        }
        require(!(hasSingle && hasMulti)) {
            "workspace_edit_file: `edits` is mutually exclusive with top-level `old_text`/`new_text`. " +
                    "Provide one mode, not both."
        }

        // 统一成编辑列表: 单编辑模式 (old_text/new_text) 或多编辑模式 (edits 数组)
        data class EditOp(val oldText: String, val newText: String, val replaceAll: Boolean)
        val editsJson = params["edits"]?.jsonArrayOrNull()
        val ops: List<EditOp> = if (editsJson != null) {
            require(editsJson.isNotEmpty()) { "edits must not be empty" }
            editsJson.map { el ->
                val obj = el.jsonObject
                EditOp(
                    oldText = obj.string("old_text") ?: error("edits[].old_text is required"),
                    newText = obj.string("new_text") ?: error("edits[].new_text is required"),
                    replaceAll = obj["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                )
            }
        } else {
            listOf(
                EditOp(
                    oldText = params.string("old_text") ?: error("old_text is required (or pass edits array)"),
                    newText = params.string("new_text") ?: error("new_text is required (or pass edits array)"),
                    replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                )
            )
        }
        ops.forEach { op -> require(op.oldText.isNotEmpty()) { "old_text must not be empty" } }

        val original = workspaceRepository.readTextInRootfs(workspaceId, path)
        // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt
        // 全部编辑在内存中按顺序应用, 任何一个失败则整体失败, 不落盘
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
                }.toString(),
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)


private fun createApplyPatchTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    externalMounts: List<me.rerere.workspace.WorkspaceExternalMount>,
) = Tool(
    name = "workspace_apply_patch",
    description = buildString {
        append("Apply a Git-style unified diff patch in the assistant's bound workspace runtime. ")
        append("The patch may modify, create, delete, or rename text files. Paths may be relative to /workspace (a/foo.kt, b/foo.kt, foo.kt) or absolute /workspace paths. ")
        append("Before non-dry-run writes, the tool automatically creates a restorable backup. If a hunk fails and rollback_on_failure is false, already applied hunks/files are kept and backup_id is returned for one-click restore. ")
        append("Use workspace_restore_backup to undo. Use shell for complex commands outside text patching. ")
        appendExternalMounts(externalMounts)
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("patch", buildJsonObject {
                    put("type", "string")
                    put("description", "Git-style unified diff text")
                })
                put("dry_run", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Preview diff without writing. Defaults to workspace config patch.dryRunDefault.")
                })
                put("rollback_on_failure", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, restore backup automatically when an apply hunk fails. Defaults to workspace config patch.rollbackOnFailure.")
                })
            },
            required = listOf("patch"),
        )
    },
    needsApproval = {
        needsApproval("workspace_apply_patch") || runCatching {
            val patch = it.jsonObject.string("patch") ?: return@runCatching true
            parseUnifiedDiff(patch).touchedPaths().any { path -> path.isOutsideWritableRoots(externalMounts) }
        }.getOrDefault(true)
    },
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
        val patches = parseUnifiedDiff(patchText)
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
                            "Already applied changes were kept. Read the failed file and apply a smaller patch for remaining changes, or call workspace_restore_backup with backup_id to undo."
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
                }.toString(),
                metadata = diffBeforeAfter.toString().takeIf { diff -> diff.isNotBlank() }
                    ?.let { diff -> DiffMetadata(diff = diff).toMetadata() },
            )
        )
    },
)

private fun createListBackupsTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_list_backups",
    description = "List restorable workspace backups created before write/edit/apply_patch/restore operations.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum backups to return. Defaults to 20.")
                })
            },
        )
    },
    needsApproval = { needsApproval("workspace_list_backups") },
    execute = {
        val limit = it.jsonObject["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 100) ?: 20
        val backups = workspaceRepository.listWorkspaceBackups(workspaceId, limit)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("backups", backups.toJsonObjectArray())
                }.toString()
            )
        )
    },
)

private fun createRestoreBackupTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    externalMounts: List<me.rerere.workspace.WorkspaceExternalMount>,
) = Tool(
    name = "workspace_restore_backup",
    description = "Restore files from a workspace backup. Restore creates another backup first by default, so undo/redo remains possible.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("backup_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Backup id returned by write/edit/apply_patch or workspace_list_backups")
                })
                put("files", buildJsonObject {
                    put("type", "array")
                    put("description", "Optional list of absolute /workspace paths to restore from this backup. Omit/null to restore all entries.")
                    put("items", buildJsonObject { put("type", "string") })
                })
            },
            required = listOf("backup_id"),
        )
    },
    needsApproval = {
        needsApproval("workspace_restore_backup") || runCatching {
            val files = it.jsonObject["files"]?.jsonArrayOrNull()?.mapNotNull { item -> item.jsonPrimitive.contentOrNull }
                ?: return@runCatching false
            files.any { path -> path.isOutsideWritableRoots(externalMounts) }
        }.getOrDefault(true)
    },
    execute = {
        val params = it.jsonObject
        val backupId = params.string("backup_id") ?: error("backup_id is required")
        val files = params["files"]?.jsonArrayOrNull()?.mapNotNull { item -> item.jsonPrimitive.contentOrNull }
        val result = workspaceRepository.restoreWorkspaceBackup(workspaceId, backupId, files)
        listOf(UIMessagePart.Text(result.toString()))
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
    description = buildString {
        append("Run a shell command in the assistant's bound workspace runtime. The workspace files area is available as /workspace; on external SSH runtimes it is mapped to the configured remote workspace directory. ")
        append("Use cwd for a path relative to the workspace files root. Prefer relative paths or /workspace paths. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        append("Requires Rootfs to be installed and ready. Timeout and output defaults are configured in /workspace/$WORKSPACE_TOOL_CONFIG_PATH. ")
        if (externalMounts.isNotEmpty()) {
            append("External mounts available in shell: ")
            append(externalMounts.joinToString { mount ->
                "${mount.normalizedTargetPath()} -> ${mount.sourcePath} (${if (mount.writable) "read/write" else "read-only"})"
            })
            append(".")
        }
    },
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
                            "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to the workspace files root. Defaults to root."
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
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val cwd = (params.string("cwd") ?: defaultCwd.orEmpty())
            .removePrefix("/workspace/").removePrefix("/workspace")
        val shellConfig = workspaceRepository.getToolConfig(workspaceId).shell
        val timeoutMillis = params.string("timeout")?.toLongOrNull()
            ?.coerceIn(1L, shellConfig.maxTimeoutSeconds.coerceAtLeast(1L))
            ?.times(1_000L)
            ?: shellConfig.defaultTimeoutSeconds.coerceIn(1L, shellConfig.maxTimeoutSeconds.coerceAtLeast(1L)).times(1_000L)
        val result = workspaceRepository.executeCommand(workspaceId, command, cwd, timeoutMillis, shellConfig.outputMaxChars.coerceIn(1_000, 512 * 1024))
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout.collapseCarriageReturns())
                    put("stderr", result.stderr.collapseCarriageReturns())
                    put("timedOut", result.timedOut)
                    if (result.truncated) put("truncated", true)
                }.toString()
            )
        )
    },
)

private fun createGrepTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_grep",
    description = buildString {
        append("Search file contents in the workspace files area (/workspace). ")
        append("Returns structured matches {path, line, text}. Automatically skips binary and oversized files. ")
        append("Use include_glob (e.g. **/*.kt) to filter files, regex=true for regular expressions. ")
        append("Only searches /workspace files; for rootfs or SSH paths use workspace_shell with grep.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Text or regex pattern to search for")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Directory relative to /workspace to search in. Defaults to the whole files area.")
                })
                put("regex", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Treat query as a regular expression. Defaults to false (literal).")
                })
                put("ignore_case", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Case-insensitive matching. Defaults to true.")
                })
                put("include_glob", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional glob filter for file paths, e.g. **/*.kt or src/**/*.json")
                })
                put("max_results", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum matches to return. Defaults to 100, max 500.")
                })
            },
            required = listOf("query"),
        )
    },
    needsApproval = { needsApproval("workspace_grep") },
    execute = { input ->
        val params = input.jsonObject
        val query = params.string("query") ?: error("query is required")
        val path = (params.string("path") ?: "").removePrefix("/workspace/").removePrefix("/workspace")
        val regex = params["regex"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
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
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("total", matches.size)
                    if (matches.size > maxResults) put("truncated", true)
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

private fun createShellBackgroundTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_shell_background",
    description = buildString {
        append("Manage long-running background shell processes in the workspace runtime (dev servers, watchers, long builds). ")
        append("Actions: start (launch process, returns process_id), output (read current stdout/stderr snapshot; ")
        append("pass wait_seconds to wait for exit first), kill (terminate), list (show all). ")
        append("Processes outlive a single tool call but are killed after the configured max lifetime or when the app exits. ")
        append("Not available on SSH runtimes.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", kotlinx.serialization.json.JsonArray(
                        listOf("start", "output", "kill", "list").map { kotlinx.serialization.json.JsonPrimitive(it) }
                    ))
                    put("description", "Operation to perform")
                })
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run (start only)")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put("description", "Working directory relative to the workspace files root (start only)")
                })
                put("process_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Process id returned by start (required for output/kill)")
                })
                put("wait_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "For output: wait up to this many seconds for the process to exit before reading")
                })
            },
            required = listOf("action"),
        )
    },
    needsApproval = { needsApproval("workspace_shell_background") },
    execute = { input ->
        val params = input.jsonObject
        val action = params.string("action") ?: error("action is required")
        val shellConfig = workspaceRepository.getToolConfig(workspaceId).shell
        require(shellConfig.backgroundEnabled) { "Background processes are disabled in workspace config" }

        fun me.rerere.workspace.WorkspaceBackgroundProcess.statusJson(includeOutput: Boolean) = buildJsonObject {
            put("process_id", id)
            put("command", command.take(200))
            put("running", isAlive)
            exitCode()?.let { put("exitCode", it) }
            put("started_at", startedAt)
            if (includeOutput) {
                put("stdout", stdoutText().collapseCarriageReturns())
                put("stderr", stderrText().collapseCarriageReturns())
                if (truncated()) put("truncated", true)
            }
        }

        val resultJson = when (action) {
            "start" -> {
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

            "output" -> {
                val processId = params.string("process_id") ?: error("process_id is required for output")
                val process = workspaceRepository.getBackgroundProcess(workspaceId, processId)
                    ?: error("No such background process: $processId (it may have been reaped)")
                val waitSeconds = params["wait_seconds"]?.jsonPrimitive?.intOrNull
                    ?.coerceIn(0, shellConfig.backgroundMaxWaitSeconds.toInt())
                if (waitSeconds != null && waitSeconds > 0 && process.isAlive) {
                    kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                        process.waitFor(waitSeconds * 1_000L)
                    }
                }
                // 进程已结束: 读走输出后移除记录, 避免注册表积灰
                if (!process.isAlive) workspaceRepository.removeBackgroundProcess(processId)
                process.statusJson(includeOutput = true)
            }

            "kill" -> {
                val processId = params.string("process_id") ?: error("process_id is required for kill")
                val process = workspaceRepository.getBackgroundProcess(workspaceId, processId)
                    ?: error("No such background process: $processId")
                kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                    process.kill()
                }
                workspaceRepository.removeBackgroundProcess(processId)
                process.statusJson(includeOutput = true)
            }

            "list" -> buildJsonObject {
                put("processes", kotlinx.serialization.json.JsonArray(
                    workspaceRepository.listBackgroundProcesses(workspaceId)
                        .map { it.statusJson(includeOutput = false) }
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
)

private data class PatchHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<PatchLine>,
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

private fun parseUnifiedDiff(text: String): List<PatchFile> {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines()
    val result = mutableListOf<PatchFile>()
    var i = 0
    fun parseGitPathPair(line: String): Pair<String?, String?> {
        val rest = line.removePrefix("diff --git ").trim()
        val parts = rest.split(Regex("\\s+"), limit = 2)
        return normalizeDiffPath(parts.getOrNull(0)) to normalizeDiffPath(parts.getOrNull(1))
    }
    while (i < lines.size) {
        if (lines[i].isBlank()) { i++; continue }
        var oldPath: String? = null
        var newPath: String? = null
        var isCreate = false
        var isDelete = false
        var isRename = false
        if (lines[i].startsWith("diff --git ")) {
            val pair = parseGitPathPair(lines[i])
            oldPath = pair.first
            newPath = pair.second
            i++
        } else if (lines[i].startsWith("--- ")) {
            oldPath = normalizeDiffPath(lines[i].removePrefix("--- ").substringBefore('\t').trim())
        } else {
            error("Unsupported patch line: ${lines[i]}")
        }
        val hunks = mutableListOf<PatchHunk>()
        while (i < lines.size && !lines[i].startsWith("diff --git ")) {
            val line = lines[i]
            when {
                line.startsWith("new file mode ") -> isCreate = true
                line.startsWith("deleted file mode ") -> isDelete = true
                line.startsWith("rename from ") -> {
                    isRename = true
                    oldPath = normalizeDiffPath(line.removePrefix("rename from ").trim())
                }
                line.startsWith("rename to ") -> {
                    isRename = true
                    newPath = normalizeDiffPath(line.removePrefix("rename to ").trim())
                }
                line.startsWith("--- ") -> oldPath = normalizeDiffPath(line.removePrefix("--- ").substringBefore('\t').trim())
                line.startsWith("+++ ") -> newPath = normalizeDiffPath(line.removePrefix("+++ ").substringBefore('\t').trim())
                line.startsWith("@@ ") -> {
                    val parsed = parseHunkHeader(line)
                    i++
                    val hunkLines = mutableListOf<PatchLine>()
                    var oldSeen = 0
                    var newSeen = 0
                    while (i < lines.size && !lines[i].startsWith("@@ ") && !lines[i].startsWith("diff --git ")) {
                        val hunkLine = lines[i]
                        if (hunkLine.startsWith("\\ No newline at end of file")) {
                            i++
                            continue
                        }
                        val countsSatisfied = oldSeen >= parsed.oldCount && newSeen >= parsed.newCount
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
                        oldSeen > parsed.oldCount && newSeen > parsed.newCount
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
        )
    }
    return result
}

private fun parseHunkHeader(line: String): PatchHunk {
    val match = Regex("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*").matchEntire(line)
        ?: error("Invalid hunk header: $line")
    return PatchHunk(
        oldStart = match.groupValues[1].toInt(),
        oldCount = match.groupValues[2].ifBlank { "1" }.toInt(),
        newStart = match.groupValues[3].toInt(),
        newCount = match.groupValues[4].ifBlank { "1" }.toInt(),
        lines = emptyList(),
    )
}

private fun normalizeDiffPath(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val cleaned = raw.trim().removeSurrounding("\"")
    if (cleaned == "/dev/null") return "/dev/null"
    val noPrefix = cleaned.removePrefix("a/").removePrefix("b/").removePrefix("./")
    val workspacePath = if (noPrefix.startsWith("/")) noPrefix else "/workspace/$noPrefix"
    require(!workspacePath.contains('\u0000') && workspacePath.split('/').none { it == ".." }) {
        "Patch path escapes workspace: $raw"
    }
    require(workspacePath.startsWith("/")) { "Patch path must be absolute or relative to /workspace: $raw" }
    return workspacePath.replace("//", "/")
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
    require(filePatch.isCreate || original != null) { "File does not exist: $sourcePath" }
    val targetOriginal = if (filePatch.isRename || filePatch.isCreate) originals[targetPath] else null
    require(!filePatch.isRename || targetOriginal == null) { "Rename target already exists: $targetPath" }
    require(!filePatch.isCreate || targetOriginal == null) { "Create target already exists: $targetPath" }
    val newText = when {
        filePatch.isDelete -> null
        filePatch.hunks.isEmpty() -> original.orEmpty()
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

private fun kotlinx.serialization.json.JsonElement.jsonArrayOrNull(): kotlinx.serialization.json.JsonArray? =
    this as? kotlinx.serialization.json.JsonArray

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

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

    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
    return listOf(
        UIMessagePart.Image(url = uris.first().toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", path)
                put("description", "Image file read successfully")
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

private fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    return path
}

// 免强制审批的可写安全区: 工作区文件目录, 以及临时目录 /tmp
private val WRITABLE_ROOT_PREFIXES = listOf("/workspace", "/tmp")

private fun kotlinx.serialization.json.JsonElement.pathOutsideWritableRoots(
    name: String,
    externalMounts: List<me.rerere.workspace.WorkspaceExternalMount> = emptyList(),
): Boolean = runCatching {
    jsonObject.absolutePath(name).isOutsideWritableRoots(externalMounts)
}.getOrDefault(true)

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
        put(
            "description",
            if (required) {
                "Absolute path inside the workspace runtime. Use /workspace for the workspace files area."
            } else {
                "Optional absolute path inside the workspace runtime. Use /workspace for the workspace files area."
            }
        )
    })
}

private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}
