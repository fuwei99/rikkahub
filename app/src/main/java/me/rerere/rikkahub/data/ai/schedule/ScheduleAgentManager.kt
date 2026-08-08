package me.rerere.rikkahub.data.ai.schedule

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.AppPaths
import java.io.File

private const val TAG = "ScheduleAgentManager"

/**
 * Schedule Agent 模板文件读写（PLAN_SCHEDULE_AGENTS §2/§7）。
 *
 * 与 SubagentTemplateManager 同款模式：JSON 文件存 `filesDir/schedule-agents/`
 * 下的 .json 文件，AI 可直接改文件，设置页只做开关列表；每次列取时补齐缺失的默认模板。
 */
class ScheduleAgentManager(
    private val context: Context,
    private val json: Json,
    private val settingsStore: SettingsStore,
) {
    fun getScheduleAgentsDir(): File {
        val dir = File(AppPaths.filesDir(context), "schedule-agents")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /** 模板目录绝对路径（设置页提示用）。 */
    fun templatesDirPath(): String = getScheduleAgentsDir().absolutePath

    fun listTemplates(includeDisabled: Boolean = false): List<ScheduleAgentTemplate> {
        // 每次都补齐缺失的默认模板（防误删 / 升级后一片空白）
        ensureDefault()
        val dir = getScheduleAgentsDir()
        val files = dir.listFiles { _, name -> name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { file ->
            runCatching {
                json.decodeFromString<ScheduleAgentTemplate>(file.readText())
            }.onFailure {
                Log.e(TAG, "Failed to parse schedule agent template JSON: ${file.name}", it)
            }.getOrNull()
        }.filter { includeDisabled || it.enabled }
    }

    fun getTemplate(id: String): ScheduleAgentTemplate? =
        listTemplates(includeDisabled = true).firstOrNull { it.id == id }

    /** 设置页开关：改 JSON 的 enabled 字段。 */
    fun setTemplateEnabled(id: String, enabled: Boolean): Boolean =
        updateTemplate(id) { it.copy(enabled = enabled, updatedAt = System.currentTimeMillis()) }

    /** 就地改写某个模板文件（按 id 找文件，文件名与 id 不要求一致）。 */
    fun updateTemplate(
        id: String,
        transform: (ScheduleAgentTemplate) -> ScheduleAgentTemplate,
    ): Boolean {
        val dir = getScheduleAgentsDir()
        val file = dir.listFiles { _, name -> name.endsWith(".json") }
            ?.firstOrNull { f ->
                runCatching { json.decodeFromString<ScheduleAgentTemplate>(f.readText()).id == id }
                    .getOrDefault(false)
            } ?: return false
        return runCatching {
            val current = json.decodeFromString<ScheduleAgentTemplate>(file.readText())
            file.writeText(json.encodeToString(ScheduleAgentTemplate.serializer(), transform(current)))
            Log.i(TAG, "updated template: $id")
        }.isSuccess
    }

    /**
     * 首次运行补默认模板：`check-in.json`（监督查岗）。
     * assistantId：若已设监督白名单则取第一个白名单学习助手，否则留空。
     */
    fun ensureDefault() {
        val dir = getScheduleAgentsDir()
        val existingIds = dir.listFiles { _, name -> name.endsWith(".json") }
            ?.mapNotNull { f ->
                runCatching {
                    (json.parseToJsonElement(f.readText()) as? kotlinx.serialization.json.JsonObject)
                        ?.get("id")?.jsonPrimitive?.content
                }.getOrNull()
            }?.toSet() ?: emptySet()

        if ("supervision_checkin" in existingIds) return
        val assistantId = runCatching {
            // settingsFlow 是 StateFlow，直接读当前值（ensureDefault 非 suspend，不能 first()）
            val settings = settingsStore.settingsFlow.value
            settings.supervision.allowedAssistantIds.firstOrNull { id ->
                settings.assistants.any { it.id == id }
            }
        }.getOrNull()
        runCatching {
            File(dir, "supervision_checkin.json").writeText(
                json.encodeToString(ScheduleAgentTemplate.serializer(), defaultCheckInTemplate(assistantId))
            )
            Log.i(TAG, "restored default schedule agent template: supervision_checkin")
        }.onFailure {
            Log.e(TAG, "Failed to write default schedule agent template", it)
        }
    }
}
