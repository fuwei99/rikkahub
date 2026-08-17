package me.rerere.ai.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 统一的「并行执行」开关字段名。
 *
 * 设计取舍（省 token 优先）：
 * - schema 里只注入 `{"type":"boolean"}`，**不写 description**，也**不进 required**。
 *   单个工具只多约 8 token；语义统一由 system prompt 说明一次，
 *   避免 N 个工具重复 N 段废话。
 * - 字段可选，缺省 = false = 串行。老对话历史里没有这个字段，行为完全不变。
 * - 该字段是「传输层协议」而非业务参数，执行前必须剥掉，绝不能透给 execute /
 *   MCP server（多传未声明字段会被做严格校验的 server 直接打回）。
 */
const val TOOL_CONCURRENT_FIELD = "concurrent"

/** 注入到 schema 里的最小字段定义，刻意不带 description。 */
private val concurrentFieldSchema = buildJsonObject { put("type", "boolean") }

/**
 * 给 schema 补上 concurrent 字段。
 *
 * - null schema（工具没声明参数）也会被提升成 Obj，否则模型无处可填。
 * - 工具自己已有同名参数时原样返回：业务语义优先，不覆盖。
 */
fun InputSchema?.withConcurrentField(): InputSchema? = when (this) {
    null -> InputSchema.Obj(
        properties = buildJsonObject { put(TOOL_CONCURRENT_FIELD, concurrentFieldSchema) }
    )

    is InputSchema.Obj -> if (properties.containsKey(TOOL_CONCURRENT_FIELD)) {
        this
    } else {
        copy(
            properties = buildJsonObject {
                properties.forEach { (key, value) -> put(key, value) }
                put(TOOL_CONCURRENT_FIELD, concurrentFieldSchema)
            }
        )
    }
}

/**
 * 批量包装：schema 注入 concurrent，同时保证 execute / needsApproval
 * 收到的参数已剥离该字段。
 */
fun List<Tool>.withConcurrentSupport(): List<Tool> = map { tool ->
    val declaredByTool = (tool.parameters() as? InputSchema.Obj)
        ?.properties
        ?.containsKey(TOOL_CONCURRENT_FIELD) == true
    if (declaredByTool) return@map tool

    val originalParameters = tool.parameters
    val originalExecute = tool.execute
    val originalNeedsApproval = tool.needsApproval
    tool.copy(
        parameters = { originalParameters().withConcurrentField() },
        needsApproval = { args -> originalNeedsApproval(args.stripConcurrentField()) },
        execute = { args -> originalExecute(args.stripConcurrentField()) },
    )
}

/**
 * 读取模型填的 concurrent 值。
 * 非 object、字段缺失、类型不认识一律当 false（串行是安全默认值）。
 * 顺手容忍字符串形式的 `"true"`——小模型偶尔这么写。
 */
fun JsonElement?.wantsConcurrentExecution(): Boolean {
    val raw = (this as? JsonObject)?.get(TOOL_CONCURRENT_FIELD) as? JsonPrimitive ?: return false
    raw.booleanOrNull?.let { return it }
    return raw.isString && raw.content.equals("true", ignoreCase = true)
}

/** 剥掉协议字段，业务侧看到的参数与未启用该特性时完全一致。 */
fun JsonElement.stripConcurrentField(): JsonElement {
    val obj = this as? JsonObject ?: return this
    if (!obj.containsKey(TOOL_CONCURRENT_FIELD)) return this
    return buildJsonObject {
        obj.forEach { (key, value) -> if (key != TOOL_CONCURRENT_FIELD) put(key, value) }
    }
}
