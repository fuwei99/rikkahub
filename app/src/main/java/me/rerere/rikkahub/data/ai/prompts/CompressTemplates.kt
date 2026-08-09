package me.rerere.rikkahub.data.ai.prompts

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.DEFAULT_AUTO_MODEL_ID
import kotlin.uuid.Uuid

/**
 * 压缩提示词模板（方案 2026-08-08 对话压缩重构）。
 *
 * 一个模板 = 名称 + 场景 + 压缩模型 + 思考强度 + 提示词。
 * 内置模板 id 固定（[DEFAULT_COMPRESS_TEMPLATE_ID] 等），保证默认绑定稳定；用户模板随机 id。
 */
@Serializable
data class CompressTemplate(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    /** 场景标记：general / roleplay / search / coding / custom */
    val scene: String = "custom",
    /** 压缩模型；null = 跟随对话当前模型/助手压缩模型 */
    val modelId: Uuid? = null,
    /** 思考强度：off/on/auto/low/medium/high/max（模型不支持则忽略） */
    val reasoningEffort: String? = null,
    val prompt: String = "",
    /** 内置模板不可删除，可复制修改 */
    val builtin: Boolean = false,
    /** 云同步合并用：模板最后修改时间（epoch millis） */
    val updatedAt: Long = 0L,
)

/**
 * 助手级自动压缩设置（方案 2026-08-08 §5）。
 *
 * 这是**助手默认值**；对话可用 [AutoCompressOverride] 覆盖。
 * token 限制与条数限制可单独开、可同时开；同时开时 OR 触发、保留量取交集（保守）。
 */
@Serializable
data class AutoCompressSetting(
    /** 总开关（默认关） */
    val enabled: Boolean = false,
    // —— token 限制（可单独开）——
    val tokenLimitEnabled: Boolean = false,
    /** 达到多少 token 触发自动压缩 */
    val tokenThreshold: Int = 100_000,
    /** 压缩后保留多少 token 的上下文 */
    val tokenKeep: Int = 30_000,
    // —— 条数限制（可单独开）——
    val countLimitEnabled: Boolean = false,
    /** 达到多少条消息触发自动压缩 */
    val countThreshold: Int = 500,
    /** 压缩后保留多少条消息 */
    val countKeep: Int = 150,
    /** 自动压缩用哪个模板；null = 助手默认模板（再兜底全局默认模板） */
    val templateId: Uuid? = null,
)

/**
 * 对话级自动压缩覆盖（方案 2026-08-08 §5.1）。
 * null 字段 = 继承助手配置；对话页（聊天输入面板 → 自动压缩）可逐项覆盖，
 * 清空覆盖（[isEmpty] 为真时整个 override 置 null）= 恢复继承。
 *
 * 助手上的 [AutoCompressSetting] 只是**默认值**：开关、阈值、保留量、模板每一项都能被单个对话改掉。
 */
@Serializable
data class AutoCompressOverride(
    val enabled: Boolean? = null,
    val templateId: Uuid? = null,
    val tokenLimitEnabled: Boolean? = null,
    val tokenThreshold: Int? = null,
    val tokenKeep: Int? = null,
    val countLimitEnabled: Boolean? = null,
    val countThreshold: Int? = null,
    val countKeep: Int? = null,
) {
    /** 所有字段都未设置 = 完全继承助手，调用方应把 override 直接置 null 保持数据干净 */
    val isEmpty: Boolean
        get() = enabled == null && templateId == null &&
            tokenLimitEnabled == null && tokenThreshold == null && tokenKeep == null &&
            countLimitEnabled == null && countThreshold == null && countKeep == null
}

/**
 * 合并助手默认值与对话覆盖，得到本对话**实际生效**的自动压缩配置。
 *
 * 语义：override 的每个字段 null = 继承助手同名字段；非 null = 本对话说了算。
 */
fun AutoCompressSetting.mergeOverride(override: AutoCompressOverride?): AutoCompressSetting {
    if (override == null) return this
    return copy(
        enabled = override.enabled ?: enabled,
        templateId = override.templateId ?: templateId,
        tokenLimitEnabled = override.tokenLimitEnabled ?: tokenLimitEnabled,
        tokenThreshold = override.tokenThreshold ?: tokenThreshold,
        tokenKeep = override.tokenKeep ?: tokenKeep,
        countLimitEnabled = override.countLimitEnabled ?: countLimitEnabled,
        countThreshold = override.countThreshold ?: countThreshold,
        countKeep = override.countKeep ?: countKeep,
    )
}

/**
 * 归一化对话覆盖：与助手默认**相同**的字段一律回落成 null（= 继承），
 * 全部相同则返回 null（调用方据此把 `autoCompressOverride` 清成 null）。
 *
 * 意义：用户把开关拨回助手默认值时，不该留下一条「本对话自定义」的死覆盖 ——
 * 否则之后改助手默认，这个对话会莫名不跟随。
 */
fun AutoCompressOverride.normalizedAgainst(base: AutoCompressSetting): AutoCompressOverride? {
    val next = AutoCompressOverride(
        enabled = enabled?.takeIf { it != base.enabled },
        templateId = templateId?.takeIf { it != base.templateId },
        tokenLimitEnabled = tokenLimitEnabled?.takeIf { it != base.tokenLimitEnabled },
        tokenThreshold = tokenThreshold?.takeIf { it != base.tokenThreshold },
        tokenKeep = tokenKeep?.takeIf { it != base.tokenKeep },
        countLimitEnabled = countLimitEnabled?.takeIf { it != base.countLimitEnabled },
        countThreshold = countThreshold?.takeIf { it != base.countThreshold },
        countKeep = countKeep?.takeIf { it != base.countKeep },
    )
    return next.takeUnless { it.isEmpty }
}

// ---- 内置模板（固定 id，保证跨设备/云同步稳定） ----

private val GENERAL_ID = Uuid.parse("00000000-0000-0000-0000-000000000001")
private val ROLEPLAY_ID = Uuid.parse("00000000-0000-0000-0000-000000000002")
private val SEARCH_ID = Uuid.parse("00000000-0000-0000-0000-000000000003")
private val CODING_ID = Uuid.parse("00000000-0000-0000-0000-000000000004")

/** 全局默认压缩模板 id（内置「通用对话」） */
val DEFAULT_COMPRESS_TEMPLATE_ID: Uuid = GENERAL_ID

private val GENERAL_PROMPT = """
    You are a conversation compression assistant. Compress the following conversation into a concise summary.

    Requirements:
    1. Preserve key facts, decisions, and important context that would be needed to continue the conversation
    2. Keep the summary in the same language as the original conversation
    3. Target approximately {target_tokens} tokens
    4. Output the summary directly without any explanations or meta-commentary
    5. Format the summary as context information that can be used to continue the conversation
    6. Use {locale} language
    7. Output format: first line is a short title (<= 20 chars), following lines are the summary content
    8. Start the summary content with a clear indicator that this is a summary (e.g., "[Summary of previous conversation]" or equivalent in the target language)

    {additional_context}

    <previous_summary>
    {previous_summary}
    </previous_summary>

    <conversation>
    {content}
    </conversation>
""".trimIndent()

private val ROLEPLAY_PROMPT = """
    You are a roleplay conversation compressor. Compress the roleplay session into a concise summary.

    Requirements:
    1. Preserve: character identities & personality state, relationship progress, emotional tone, current plot position, unresolved plot threads
    2. Keep the summary in the same language as the original conversation
    3. Target approximately {target_tokens} tokens
    4. Do NOT include trivial action descriptions or small talk unless they change the state
    5. Output the summary directly, formatted as context usable to continue the roleplay
    6. Use {locale} language
    7. Output format: first line is a short title (<= 20 chars), following lines are the summary content

    {additional_context}

    <previous_summary>
    {previous_summary}
    </previous_summary>

    <conversation>
    {content}
    </conversation>
""".trimIndent()

private val SEARCH_PROMPT = """
    You are a research assistant that compresses search/research conversations into a knowledge digest.

    Requirements:
    1. Preserve: confirmed facts (with sources if mentioned), keywords, unanswered questions, dead ends eliminated
    2. Aggressive compression: drop procedural chat, keep only the knowledge gained
    3. Keep the digest in the same language as the original conversation
    4. Target approximately {target_tokens} tokens
    5. Output the digest directly, formatted as standalone reference notes
    6. Use {locale} language
    7. Output format: first line is a short title (<= 20 chars), following lines are the digest content

    {additional_context}

    <previous_summary>
    {previous_summary}
    </previous_summary>

    <conversation>
    {content}
    </conversation>
""".trimIndent()

private val CODING_PROMPT = """
    You are a coding session compressor. Compress the coding session into a technical summary.

    Requirements:
    1. Preserve: goal, architecture decisions, file/module structure, what's done vs not done, errors & fixes, key code snippets (keep them short)
    2. Keep the summary in the same language as the original conversation
    3. Target approximately {target_tokens} tokens
    4. Drop exploratory dead ends unless they contain a lesson; keep TODO items
    5. Output the summary directly, formatted as a handoff note for continuing development
    6. Use {locale} language
    7. Output format: first line is a short title (<= 20 chars), following lines are the summary content

    {additional_context}

    <previous_summary>
    {previous_summary}
    </previous_summary>

    <conversation>
    {content}
    </conversation>
""".trimIndent()

/** 内置压缩模板（用户可复制修改，不可删） */
val DEFAULT_COMPRESS_TEMPLATES: List<CompressTemplate> = listOf(
    CompressTemplate(
        id = GENERAL_ID,
        name = "通用对话",
        scene = "general",
        prompt = GENERAL_PROMPT,
        builtin = true,
    ),
    CompressTemplate(
        id = ROLEPLAY_ID,
        name = "Roleplay 总结",
        scene = "roleplay",
        prompt = ROLEPLAY_PROMPT,
        builtin = true,
    ),
    CompressTemplate(
        id = SEARCH_ID,
        name = "搜索/资料",
        scene = "search",
        prompt = SEARCH_PROMPT,
        builtin = true,
    ),
    CompressTemplate(
        id = CODING_ID,
        name = "编码会话",
        scene = "coding",
        prompt = CODING_PROMPT,
        builtin = true,
    ),
)

/** 兼容旧字段：老全局 compressPrompt/compressModelId 若被改过，迁移为自定义模板（不改内置） */
fun legacyCompressTemplateIfCustom(
    compressPrompt: String,
    compressModelId: Uuid?,
): CompressTemplate? {
    val promptChanged = compressPrompt.isNotBlank() && compressPrompt != DEFAULT_COMPRESS_PROMPT
    val modelChanged = compressModelId != null && compressModelId != DEFAULT_AUTO_MODEL_ID
    if (!promptChanged && !modelChanged) return null
    return CompressTemplate(
        name = "我的模板",
        scene = "custom",
        modelId = compressModelId?.takeIf { it != DEFAULT_AUTO_MODEL_ID },
        prompt = compressPrompt.ifBlank { DEFAULT_COMPRESS_PROMPT },
        updatedAt = System.currentTimeMillis(),
    )
}
