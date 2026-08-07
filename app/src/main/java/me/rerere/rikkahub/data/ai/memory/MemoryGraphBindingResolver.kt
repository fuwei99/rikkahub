package me.rerere.rikkahub.data.ai.memory

import me.rerere.common.android.MemoryGraphDebugLog
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MemoryGraphBinding
import me.rerere.rikkahub.data.model.MemoryGraphMeta
import me.rerere.rikkahub.data.model.MemoryOptions
import me.rerere.rikkahub.data.model.ResolvedGraphBinding
import me.rerere.rikkahub.data.repository.MemoryGraphRegistry

/**
 * 记忆图绑定解析器 —— **本轮记忆图配置的唯一运行时真源**（review §建议 1）。
 *
 * 注入、memory_tool 构建、自动提炼、溯源抽屉、自动保存门槛、UI 全部只读它的输出，
 * 不再各自判断 `enableAssistantMemoryGraph` / `allowEditGlobalGraph` 这些老布尔字段。
 *
 * 解析优先级：
 * 1. `allowConversationPromptInjection && conversation.memoryGraphBindings != null` → 会话绑定；
 * 2. `assistant.memoryGraphBindings` 非空 → 助手绑定；
 * 3. 都没有 → 从老字段推导（对老配置逐字等价，不迁移 prefs）。
 *
 * 注意 [Conversation.memoryGraphBindings] 是 nullable，语义严格区分：
 * - `null`  = 未设置，继承助手；
 * - `[]`    = 明确关闭所有图；
 * - 非空    = 显式绑定。
 * 这不是洁癖：lorebook 的既有语义是「对话覆盖开关一开，会话侧即唯一真源」，
 * 若用 emptyList 表示未设置，用户一打开开关绑定就瞬间全没 —— lorebook 消失只是人设淡了，
 * 记忆图消失是模型当场失忆（review2 §二.B）。
 */
class MemoryGraphBindingResolver(
    private val registry: MemoryGraphRegistry,
) {
    companion object {
        private const val TAG = "MemoryGraphBindingResolver"
    }

    /**
     * @param options 只提供「用户本轮意图」（[MemoryOptions.graphMuted] 运行时总闸），
     *   解析结果不回写 MemoryOptions —— 那是个内存态输入对象，既当输入又当输出会被
     *   8 个调用点各算一遍，其中还有 Composable 同步上下文（review2 §二.C）。
     * @param maxEnabledGraphs 本轮启用图硬上限，防止 10 张图导致每轮几十次全表扫。
     */
    suspend fun resolve(
        assistant: Assistant,
        conversation: Conversation?,
        options: MemoryOptions = MemoryOptions(),
        maxEnabledGraphs: Int = 8,
    ): List<ResolvedGraphBinding> {
        val assistantId = assistant.id.toString()
        // 内置图始终保证存在，否则老用户新建助手后一张图都解析不出来；
        // 顺手把助手名传下去，让内置助手图的名字跟着助手走（一排「助手记忆图」分不清谁是谁）
        runCatching { registry.ensureAssistantGraph(assistantId, assistant.name) }
        runCatching { registry.ensureGlobalGraph() }

        val useConversation = assistant.allowConversationPromptInjection &&
            conversation?.memoryGraphBindings != null
        val bindings: List<MemoryGraphBinding> = when {
            useConversation -> conversation!!.memoryGraphBindings!!
            assistant.memoryGraphBindings.isNotEmpty() -> assistant.memoryGraphBindings
            else -> deriveFromLegacyFields(assistant, options, assistantId)
        }
        if (bindings.isEmpty()) return emptyList()

        val metas = runCatching { registry.list() }.getOrDefault(emptyList()).associateBy { it.id }
        // 僵尸 binding（指向已删图）直接丢弃，否则每轮都要吃一次 null
        val resolved = bindings.mapNotNull { binding ->
            val meta = metas[binding.graphId] ?: return@mapNotNull null
            ResolvedGraphBinding(
                meta = meta,
                enabled = binding.enabled && !options.graphMuted,
                writable = binding.writable,
            )
        }.sortedWith(
            compareByDescending<ResolvedGraphBinding> { it.meta.sortOrder }
                .thenBy { it.meta.createdAt }
        )

        return capEnabled(resolved, maxEnabledGraphs)
    }

    /** 便捷读法：本轮参与注入的图。 */
    suspend fun enabledGraphs(
        assistant: Assistant,
        conversation: Conversation?,
        options: MemoryOptions = MemoryOptions(),
        maxEnabledGraphs: Int = 8,
    ): List<MemoryGraphMeta> =
        resolve(assistant, conversation, options, maxEnabledGraphs).filter { it.enabled }.map { it.meta }

    /**
     * 启用图数硬上限：`searchNodes` 会把整个 scope 的节点拉进内存做中文 n-gram 打分，
     * `getLinks` 每图一次全表扫，图一多每轮开销线性爆炸（review2 §一.5）。
     * 超限按 sortOrder 截断，并打点说明被砍了哪些。
     */
    private fun capEnabled(
        resolved: List<ResolvedGraphBinding>,
        maxEnabledGraphs: Int,
    ): List<ResolvedGraphBinding> {
        if (maxEnabledGraphs <= 0) return resolved.map { it.copy(enabled = false) }
        val enabledCount = resolved.count { it.enabled }
        if (enabledCount <= maxEnabledGraphs) return resolved
        var budget = maxEnabledGraphs
        val dropped = mutableListOf<String>()
        val capped = resolved.map { binding ->
            if (!binding.enabled) return@map binding
            if (budget > 0) {
                budget--
                binding
            } else {
                dropped += binding.meta.name
                binding.copy(enabled = false)
            }
        }
        MemoryGraphDebugLog.w(
            TAG,
            "resolve: enabled graphs $enabledCount > max $maxEnabledGraphs, dropped=${dropped.joinToString(",")}"
        )
        return capped
    }

    /**
     * 老字段推导（不迁移 prefs，与现有 `MemoryOptions.assistantGraphEnabled()` 同一套路）：
     *
     * | 老字段 | binding |
     * |---|---|
     * | enableAssistantMemoryGraph（或 legacy enableMemoryGraph） | 助手图 enabled |
     * | enableGlobalMemoryGraph（或 legacy enableMemoryGraph）    | 全局图 enabled |
     * | MemoryOptions.allowEditAssistantGraph                     | 助手图 writable |
     * | MemoryOptions.allowEditGlobalGraph                        | 全局图 writable |
     */
    private fun deriveFromLegacyFields(
        assistant: Assistant,
        options: MemoryOptions,
        assistantId: String,
    ): List<MemoryGraphBinding> {
        val assistantGraphOn = options.assistantGraphEnabled(assistant)
        val globalGraphOn = options.globalGraphEnabled(assistant)
        return buildList {
            if (assistantGraphOn) {
                add(
                    MemoryGraphBinding(
                        graphId = assistantId,
                        enabled = options.referenceAssistantGraph,
                        writable = options.allowEditAssistantGraph,
                    )
                )
            }
            if (globalGraphOn) {
                add(
                    MemoryGraphBinding(
                        graphId = me.rerere.rikkahub.data.repository.MemoryGraphRepository.GLOBAL_SCOPE,
                        enabled = options.referenceGlobalGraph,
                        writable = options.allowEditGlobalGraph,
                    )
                )
            }
        }
    }
}
