package me.rerere.rikkahub.data.workspace

import android.content.Context
import me.rerere.rikkahub.data.files.AppPaths
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceExternalMount
import java.io.File

/**
 * 进程内固定 bind 的目录 —— proot 启动参数里写死、不属于工作区外挂配置的那几个。
 *
 * ## 为什么必须单独有一份
 *
 * proot 的 `-b` 只让 **shell** 看见这些目录；文件工具走的是另一条路径映射（用户外挂配置 +
 * 共享 rootfs 目录）。于是 `/skills` 出现过一个很坑的错位：
 *
 * ```
 * workspace_shell: cat /skills/device-bridge-api/SKILL.md   → 正常
 * workspace_read_file: /skills/device-bridge-api/SKILL.md   → File does not exist
 * ```
 *
 * 因为文件工具在挂载清单里找不到 `/skills`，就把它当成「工作区内的相对路径」去共享 rootfs 里
 * 找了个同名空目录（`_shared-rootfs/skills`），报的还是**去掉前导斜杠**的路径，误导性极强。
 *
 * 所以这里把 bind 的 (源目录, 挂载点) 定义成**唯一一份**，同时喂给两条通道：
 * - [WorkspaceBindMount] → ProotShellRunner 的 `-b`
 * - [WorkspaceExternalMount] → 文件工具路径解析 + `[Environment Context]` 的 mounts 行
 *
 * 两处各写各的，就会再次漂移出上面那个 bug。
 */
object WorkspaceBuiltinMounts {
    /** (源目录, proot 内挂载点)。顺序即提示词 mounts 行里的展示顺序。 */
    fun entries(context: Context): List<Pair<File, String>> = listOf(
        File(AppPaths.filesDir(context), FileFolders.SKILLS).apply { mkdirs() } to "/skills",
        File(AppPaths.filesDir(context), FileFolders.TOOL_OUTPUTS).apply { mkdirs() } to "/tool_outputs",
    )

    fun bindMounts(context: Context): List<WorkspaceBindMount> =
        entries(context).map { (source, target) -> WorkspaceBindMount(source = source, target = target) }

    /**
     * 文件工具视角的挂载配置。
     *
     * `writable = true` 与 proot 的 `-b` 一致（那两条 bind 没加 ro）；`autoApproveWrites = false`
     * —— 写 `/skills`、`/tool_outputs` 仍然走审批，它们不是工作区自己的地盘。
     */
    fun mountConfigs(context: Context): List<WorkspaceExternalMount> =
        entries(context).map { (source, target) ->
            WorkspaceExternalMount(
                name = target.trimStart('/'),
                description = DESCRIPTIONS[target].orEmpty(),
                sourcePath = source.absolutePath,
                targetPath = target,
                writable = true,
                autoApproveWrites = false,
            )
        }

    private val DESCRIPTIONS = mapOf(
        "/skills" to "RikkaHub skills directory — one directory per skill, each holding a SKILL.md",
        "/tool_outputs" to "artifacts produced by local tools (screenshots, exports, temp files)",
    )
}

/**
 * 合并「用户配置的外挂」与「进程内固定 bind」，得到文件工具/提示词唯一该用的挂载清单。
 *
 * 用户配置优先：同一个挂载点被显式配置过就以用户那份为准（源目录、可写位都听用户的），
 * 固定 bind 只是补上用户没配的。顺序稳定（用户配置在前），免得提示词前缀缓存被顺序抖动打掉。
 *
 * 单独抽成纯函数是为了能脱离 Android Context 单测 —— 挂载清单算错就等于路径解析错。
 */
internal fun mergeMountConfigs(
    configured: List<WorkspaceExternalMount>,
    builtin: List<WorkspaceExternalMount>,
): List<WorkspaceExternalMount> = (configured + builtin)
    .filter { it.isConfigured() }
    .distinctBy { it.normalizedTargetPath() }
