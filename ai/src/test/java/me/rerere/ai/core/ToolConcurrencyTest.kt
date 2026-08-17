package me.rerere.ai.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolConcurrencyTest {

    private fun dummyTool(
        name: String = "t",
        schema: InputSchema? = null,
    ): Tool = Tool(
        name = name,
        description = "d",
        parameters = { schema },
        execute = { listOf(UIMessagePart.Text("ok")) },
    )

    @Test
    fun `null schema is promoted so the model has somewhere to put the flag`() {
        val schema = (null as InputSchema?).withConcurrentField()
        val obj = schema as InputSchema.Obj
        assertTrue(obj.properties.containsKey(TOOL_CONCURRENT_FIELD))
    }

    @Test
    fun `injected field is a bare boolean and never required`() {
        val original = InputSchema.Obj(
            properties = buildJsonObject { put("path", buildJsonObject { put("type", "string") }) },
            required = listOf("path"),
        )
        val patched = original.withConcurrentField() as InputSchema.Obj

        // 原有参数一个不少
        assertTrue(patched.properties.containsKey("path"))
        // required 不变：concurrent 必须是可选的
        assertEquals(listOf("path"), patched.required)

        val field = patched.properties[TOOL_CONCURRENT_FIELD]!!.jsonObject
        assertEquals(1, field.size) // 只有 type，刻意不带 description（省 token）
        assertTrue(field.containsKey("type"))
    }

    @Test
    fun `tool declaring its own concurrent param is left alone`() {
        val own = InputSchema.Obj(
            properties = buildJsonObject {
                put(TOOL_CONCURRENT_FIELD, buildJsonObject { put("type", "string") })
            }
        )
        val wrapped = listOf(dummyTool(schema = own)).withConcurrentSupport().single()
        val field = (wrapped.parameters() as InputSchema.Obj)
            .properties[TOOL_CONCURRENT_FIELD]!!.jsonObject
        assertEquals("string", field["type"]!!.toString().trim('"'))
    }

    @Test
    fun `flag parsing defaults to serial`() {
        assertFalse(null.wantsConcurrentExecution())
        assertFalse(JsonObject(emptyMap()).wantsConcurrentExecution())
        assertFalse(buildJsonObject { put(TOOL_CONCURRENT_FIELD, false) }.wantsConcurrentExecution())
        assertTrue(buildJsonObject { put(TOOL_CONCURRENT_FIELD, true) }.wantsConcurrentExecution())
        // 小模型偶尔把布尔写成字符串
        assertTrue(buildJsonObject { put(TOOL_CONCURRENT_FIELD, "true") }.wantsConcurrentExecution())
        assertFalse(buildJsonObject { put(TOOL_CONCURRENT_FIELD, "nonsense") }.wantsConcurrentExecution())
    }

    @Test
    fun `protocol field never reaches execute`() = runBlocking {
        var seen: JsonObject? = null
        val tool = Tool(
            name = "spy",
            description = "d",
            parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
            execute = { args ->
                seen = args as JsonObject
                listOf(UIMessagePart.Text("ok"))
            },
        )
        val wrapped = listOf(tool).withConcurrentSupport().single()
        wrapped.execute(
            buildJsonObject {
                put("path", "/tmp/x")
                put(TOOL_CONCURRENT_FIELD, true)
            }
        )
        // MCP server 会对未声明字段做严格校验，协议字段必须在这之前剥掉
        assertNull(seen!![TOOL_CONCURRENT_FIELD])
        assertEquals("/tmp/x", seen!!["path"]!!.toString().trim('"'))
    }
}
