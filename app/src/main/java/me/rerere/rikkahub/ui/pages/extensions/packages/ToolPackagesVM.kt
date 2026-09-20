package me.rerere.rikkahub.ui.pages.extensions.packages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.tools.WORKSPACE_TOOL_NAMES
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.ToolPackage
import me.rerere.rikkahub.data.ai.tools.local.ToolPackagesStore
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.SkillManager

/** Package Manager 里可选的一个工具条目（id = 模型调用名，group = 分组标签）。 */
data class ToolOption(
    val id: String,
    val label: String,
    val group: String,
)

/**
 * Package Manager 的 VM。
 *
 * 只干两件事：把 [ToolPackagesStore] 的 StateFlow 透给 UI；把编辑结果落盘。
 * 工具候选列表（[availableTools]）是**从当前设置现算的**，不走 tool_manage 那套 catalog ——
 * 那套要会话上下文，UI 层拿不到，也没必要拿。
 */
class ToolPackagesVM(
    private val store: ToolPackagesStore,
    private val settingsStore: SettingsStore,
    private val skillManager: SkillManager,
) : ViewModel() {

    val packages: StateFlow<List<ToolPackage>> = store.packages

    /** 供 UI 勾选的候选工具：workspace + local + 已配置 MCP + 技能。 */
    fun availableTools(): List<ToolOption> = buildList {
        WORKSPACE_TOOL_NAMES.forEach { add(ToolOption(it, it, "Workspace")) }
        LocalToolOption.ALL_SERIAL_NAMES.forEach { add(ToolOption(it, it, "Local")) }
        runCatching {
            settingsStore.settingsFlow.value.mcpServers.forEach { server ->
                val sname = server.commonOptions.name.ifBlank { server.id.toString() }
                server.commonOptions.tools.forEach { t ->
                    add(ToolOption("mcp__${sname}__${t.name}", t.name, "MCP · $sname"))
                }
            }
        }
        runCatching {
            skillManager.listSkills().forEach { add(ToolOption(it.name, it.name, "Skill")) }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        persist(store.packages.value.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun upsert(pkg: ToolPackage) {
        val cur = store.packages.value
        val next = if (cur.any { it.id == pkg.id }) {
            cur.map { if (it.id == pkg.id) pkg else it }
        } else {
            cur + pkg
        }
        persist(next)
    }

    fun delete(id: String) {
        persist(store.packages.value.filterNot { it.id == id })
    }

    private fun persist(list: List<ToolPackage>) {
        viewModelScope.launch(Dispatchers.IO) { store.save(list) }
    }
}
