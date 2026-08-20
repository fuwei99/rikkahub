package me.rerere.rikkahub.data.ai.schedule

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleAgentTemplateTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `default check-in template fields`() {
        val t = defaultCheckInTemplate()
        assertEquals("supervision_checkin", t.id)
        assertEquals("监督查岗", t.name)
        assertTrue(t.enabled)
        assertEquals(10, t.intervalMinutes)
        assertNull(t.assistantId)
        assertTrue(t.inheritMemory)
        assertTrue(t.inheritMemoryGraph)
        assertFalse(t.inheritRecentChats)
        assertTrue(t.usesWindowSchedule)
        assertEquals(5, t.windows.size)
        assertEquals(2, t.dailyTimes.size)
        assertEquals("监督", t.folderName)
        assertEquals("reuse", t.conversationMode)
        assertTrue(t.reuseConversation)
        assertEquals(listOf("screen_time", "ask_user", "time_info", "inbox"), t.allowedLocalTools)
        assertTrue(t.notifyOnReport)
    }

    @Test
    fun `check-in template with bound assistant id survives json round trip`() {
        val t = defaultCheckInTemplate(assistantId = kotlin.uuid.Uuid.parse("11111111-1111-1111-1111-111111111111"))
        val encoded = json.encodeToString(ScheduleAgentTemplate.serializer(), t)
        val decoded = json.decodeFromString<ScheduleAgentTemplate>(encoded)
        assertEquals(t, decoded)
        assertNotNull(decoded.assistantId)
    }

    @Test
    fun `conversation mode normalization`() {
        assertEquals("reuse", ScheduleAgentTemplate.normalizeConversationMode(null))
        assertEquals("reuse", ScheduleAgentTemplate.normalizeConversationMode("whatever"))
        assertEquals("reuse", ScheduleAgentTemplate.normalizeConversationMode("reuse"))
        assertEquals("fresh", ScheduleAgentTemplate.normalizeConversationMode("fresh"))
        assertFalse(ScheduleAgentTemplate("id", "x", conversationMode = "fresh").reuseConversation)
    }

    @Test
    fun `old schema json without new fields decodes with defaults`() {
        // 兼容：手写的精简 JSON（只含必填 id/name）也能解析，其余字段取默认值
        val decoded = json.decodeFromString<ScheduleAgentTemplate>("""{"id":"my_task","name":"我的任务"}""")
        assertEquals("my_task", decoded.id)
        assertEquals("我的任务", decoded.name)
        assertTrue(decoded.enabled)
        assertEquals(10, decoded.intervalMinutes)
        assertTrue(decoded.inheritMemory)
        assertTrue(decoded.reuseConversation)
    }

    @Test
    fun `ignore unknown keys`() {
        // 未知字段（如后续版本新增）不影响解析
        val decoded = json.decodeFromString<ScheduleAgentTemplate>(
            """{"id":"t1","name":"T1","futureField":123,"dailyAt":"09:00","onlyDuringSupervision":true}"""
        )
        // dailyAt / onlyDuringSupervision 已废弃：老 JSON 里残留也不该炸，走 ignoreUnknownKeys
        assertEquals("t1", decoded.id)
        assertFalse(decoded.usesWindowSchedule)
    }

    @Test
    fun `model override defaults to null and survives json round trip`() {
        // 缺省 = null → spawnSchedule 回落助手 chatModelId（原行为不变）
        assertNull(defaultCheckInTemplate().modelId)
        assertNull(json.decodeFromString<ScheduleAgentTemplate>("""{"id":"t","name":"T"}""").modelId)

        // 显式指定便宜模型：手写 JSON 能解析，且编解码往返不丢
        val modelId = kotlin.uuid.Uuid.parse("9007d93d-9c44-41eb-8406-f53f54c9eb10")
        val decoded = json.decodeFromString<ScheduleAgentTemplate>(
            """{"id":"t","name":"T","modelId":"9007d93d-9c44-41eb-8406-f53f54c9eb10"}"""
        )
        assertEquals(modelId, decoded.modelId)

        val t = defaultCheckInTemplate().copy(modelId = modelId)
        val roundTrip = json.decodeFromString<ScheduleAgentTemplate>(
            json.encodeToString(ScheduleAgentTemplate.serializer(), t)
        )
        assertEquals(t, roundTrip)
        assertEquals(modelId, roundTrip.modelId)
    }

    @Test
    fun `fallback model ids decode and round trip`() {
        // 备用模型链（1 主 + 至多 3 备）：手写 JSON 能解析，编解码往返不丢
        val fallback = listOf(
            kotlin.uuid.Uuid.parse("11111111-2222-3333-4444-555555555555"),
            kotlin.uuid.Uuid.parse("66666666-7777-8888-9999-000000000000"),
        )
        val decoded = json.decodeFromString<ScheduleAgentTemplate>(
            """{"id":"t","name":"T","modelId":"9007d93d-9c44-41eb-8406-f53f54c9eb10","fallbackModelIds":["11111111-2222-3333-4444-555555555555","66666666-7777-8888-9999-000000000000"]}"""
        )
        assertEquals(fallback, decoded.fallbackModelIds)

        val t = defaultCheckInTemplate().copy(fallbackModelIds = fallback)
        val roundTrip = json.decodeFromString<ScheduleAgentTemplate>(
            json.encodeToString(ScheduleAgentTemplate.serializer(), t)
        )
        assertEquals(t, roundTrip)
        assertEquals(fallback, roundTrip.fallbackModelIds)
    }
}
