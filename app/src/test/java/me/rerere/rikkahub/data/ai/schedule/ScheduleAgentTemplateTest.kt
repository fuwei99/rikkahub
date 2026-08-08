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
        assertNull(t.dailyAt)
        assertNull(t.assistantId)
        assertTrue(t.inheritMemory)
        assertTrue(t.inheritMemoryGraph)
        assertFalse(t.inheritRecentChats)
        assertTrue(t.onlyDuringSupervision)
        assertEquals("监督", t.folderName)
        assertEquals("reuse", t.conversationMode)
        assertTrue(t.reuseConversation)
        assertEquals(listOf("screen_time", "ask_user", "time_info", "inbox", "send"), t.allowedLocalTools)
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
            """{"id":"t1","name":"T1","futureField":123,"dailyAt":"09:00"}"""
        )
        assertEquals("09:00", decoded.dailyAt)
    }
}
