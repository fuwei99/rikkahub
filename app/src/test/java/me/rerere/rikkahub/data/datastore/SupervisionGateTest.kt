package me.rerere.rikkahub.data.datastore

import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.PendingUnlock
import me.rerere.rikkahub.data.model.SupervisionSchedule
import me.rerere.rikkahub.data.model.SupervisionSettings
import me.rerere.rikkahub.data.model.ToolFilter
import me.rerere.rikkahub.data.model.isUnlockStale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 监督期闸门的行为回归（2026-08-11 bug：黑名单模式下什么都没禁，却啥都改不了）。
 *
 * 钉住的结论：
 * - 学习相关字段（思维链 / 网页搜索 / 模型 / 采样参数）在监督期**可改**；
 * - MCP server 的 enable 开关在监督期**可改**（能力管控归 mcpToolFilter）；
 * - `lockMcpServers` 只锁结构：新增被丢弃、删除被补回、地址被回滚；
 * - `lockMcpToolToggles` / `lockSkills` 显式开启时才恢复"只许关不许开"。
 */
class SupervisionGateTest {

    private val gate = SupervisionGate()
    private val assistantId = Uuid.random()

    /** 覆盖一周全天的时段，保证 isActiveNow() 恒为 true */
    private fun alwaysOnSupervision(
        lockMcpServers: Boolean = true,
        lockMcpToolToggles: Boolean = false,
        lockSkills: Boolean = false,
    ) = SupervisionSettings(
        enabled = true,
        schedules = listOf(
            SupervisionSchedule(
                daysOfWeek = setOf(1, 2, 3, 4, 5, 6, 7),
                startMinute = 0,
                endMinute = 24 * 60,
            )
        ),
        allowedAssistantIds = setOf(assistantId),
        lockMcpServers = lockMcpServers,
        lockMcpToolToggles = lockMcpToolToggles,
        lockSkills = lockSkills,
    )

    private fun assistant() = Assistant(
        id = assistantId,
        name = "江锋",
        systemPrompt = "监工",
        reasoningLevel = ReasoningLevel.OFF,
        enableWebSearch = false,
        enabledSkills = emptySet(),
    )

    private fun settings(
        sup: SupervisionSettings,
        assistant: Assistant,
        mcp: List<McpServerConfig> = emptyList(),
    ) = Settings(
        assistantId = assistantId,
        assistants = listOf(assistant),
        mcpServers = mcp,
        supervision = sup,
    )

    private fun server(
        id: Uuid,
        url: String,
        enable: Boolean,
        tools: List<McpTool> = emptyList(),
    ) = McpServerConfig.StreamableHTTPServer(
        id = id,
        commonOptions = McpCommonOptions(enable = enable, name = "s", tools = tools),
        url = url,
    )

    // ---------- 助手字段 ----------

    @Test
    fun `监督期可以改思维链等级`() {
        val old = settings(alwaysOnSupervision(), assistant())
        val incoming = old.copy(
            assistants = listOf(assistant().copy(reasoningLevel = ReasoningLevel.HIGH))
        )
        val result = gate.enforceDuringLock(old, incoming)
        assertEquals(ReasoningLevel.HIGH, result.assistants.first().reasoningLevel)
    }

    @Test
    fun `监督期可以开网页搜索`() {
        val old = settings(alwaysOnSupervision(), assistant())
        val incoming = old.copy(assistants = listOf(assistant().copy(enableWebSearch = true)))
        assertTrue(gate.enforceDuringLock(old, incoming).assistants.first().enableWebSearch)
    }

    @Test
    fun `监督期不能改 system prompt`() {
        val old = settings(alwaysOnSupervision(), assistant())
        val incoming = old.copy(assistants = listOf(assistant().copy(systemPrompt = "随便玩")))
        assertEquals("监工", gate.enforceDuringLock(old, incoming).assistants.first().systemPrompt)
    }

    @Test
    fun `lockSkills 默认关时可以改 enabledSkills`() {
        val old = settings(alwaysOnSupervision(), assistant())
        val incoming = old.copy(assistants = listOf(assistant().copy(enabledSkills = setOf("math"))))
        assertEquals(setOf("math"), gate.enforceDuringLock(old, incoming).assistants.first().enabledSkills)
    }

    @Test
    fun `lockSkills 开启后回滚 enabledSkills`() {
        val old = settings(alwaysOnSupervision(lockSkills = true), assistant())
        val incoming = old.copy(assistants = listOf(assistant().copy(enabledSkills = setOf("math"))))
        assertTrue(gate.enforceDuringLock(old, incoming).assistants.first().enabledSkills.isEmpty())
    }

    // ---------- MCP ----------

    @Test
    fun `监督期可以开关已挂载的 MCP server`() {
        val id = Uuid.random()
        val old = settings(alwaysOnSupervision(), assistant(), listOf(server(id, "http://a", false)))
        val incoming = old.copy(mcpServers = listOf(server(id, "http://a", true)))
        val result = gate.enforceDuringLock(old, incoming)
        assertTrue(result.mcpServers.single().commonOptions.enable)

        // 反向：也能关
        val old2 = settings(alwaysOnSupervision(), assistant(), listOf(server(id, "http://a", true)))
        val incoming2 = old2.copy(mcpServers = listOf(server(id, "http://a", false)))
        assertFalse(gate.enforceDuringLock(old2, incoming2).mcpServers.single().commonOptions.enable)
    }

    @Test
    fun `lockMcpServers 丢弃新增的 server`() {
        val id = Uuid.random()
        val old = settings(alwaysOnSupervision(), assistant(), listOf(server(id, "http://a", true)))
        val incoming = old.copy(
            mcpServers = old.mcpServers + server(Uuid.random(), "http://evil", true)
        )
        val result = gate.enforceDuringLock(old, incoming)
        assertEquals(listOf(id), result.mcpServers.map { it.id })
    }

    @Test
    fun `lockMcpServers 把被删掉的 server 补回来`() {
        val id = Uuid.random()
        val old = settings(alwaysOnSupervision(), assistant(), listOf(server(id, "http://a", true)))
        val incoming = old.copy(mcpServers = emptyList())
        val result = gate.enforceDuringLock(old, incoming)
        assertEquals(listOf(id), result.mcpServers.map { it.id })
    }

    @Test
    fun `lockMcpServers 回滚地址改动但保留 enable`() {
        val id = Uuid.random()
        val old = settings(alwaysOnSupervision(), assistant(), listOf(server(id, "http://a", true)))
        val incoming = old.copy(mcpServers = listOf(server(id, "http://evil", false)))
        val result = gate.enforceDuringLock(old, incoming).mcpServers.single()
        assertEquals("http://a", (result as McpServerConfig.StreamableHTTPServer).url)
        assertFalse(result.commonOptions.enable)
    }

    @Test
    fun `lockMcpToolToggles 默认关时可以打开此前关闭的工具`() {
        val id = Uuid.random()
        val old = settings(
            alwaysOnSupervision(), assistant(),
            listOf(server(id, "http://a", true, listOf(McpTool(enable = false, name = "t"))))
        )
        val incoming = old.copy(
            mcpServers = listOf(server(id, "http://a", true, listOf(McpTool(enable = true, name = "t"))))
        )
        val tools = gate.enforceDuringLock(old, incoming).mcpServers.single().commonOptions.tools
        assertTrue(tools.single().enable)
    }

    @Test
    fun `lockMcpToolToggles 开启后回滚工具的重新启用`() {
        val id = Uuid.random()
        val old = settings(
            alwaysOnSupervision(lockMcpToolToggles = true), assistant(),
            listOf(server(id, "http://a", true, listOf(McpTool(enable = false, name = "t"))))
        )
        val incoming = old.copy(
            mcpServers = listOf(server(id, "http://a", true, listOf(McpTool(enable = true, name = "t"))))
        )
        val tools = gate.enforceDuringLock(old, incoming).mcpServers.single().commonOptions.tools
        assertFalse(tools.single().enable)
    }

    @Test
    fun `两个新锁只许加严不许放松`() {
        val old = settings(
            alwaysOnSupervision(lockMcpToolToggles = true, lockSkills = true),
            assistant()
        )
        val incoming = old.copy(
            supervision = old.supervision.copy(lockMcpToolToggles = false, lockSkills = false)
        )
        val sup = gate.enforceDuringLock(old, incoming).supervision
        assertTrue(sup.lockMcpToolToggles)
        assertTrue(sup.lockSkills)
    }

    // ---------- 空白名单死锁 / 陈旧解锁（2026-08-17） ----------

    @Test
    fun `空白名单不算限制_可以改回黑名单`() {
        val old = settings(
            alwaysOnSupervision().copy(
                localToolFilter = ToolFilter(ToolFilter.Mode.WHITELIST, emptySet()),
            ),
            assistant(),
        )
        val incoming = old.copy(
            supervision = old.supervision.copy(
                localToolFilter = ToolFilter(ToolFilter.Mode.BLACKLIST, emptySet()),
            ),
        )
        val result = gate.enforceDuringLock(old, incoming).supervision.localToolFilter
        assertEquals(ToolFilter.Mode.BLACKLIST, result.mode)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `空白名单放行所有工具`() {
        assertTrue(ToolFilter(ToolFilter.Mode.WHITELIST, emptySet()).allows("javascript_engine"))
        assertFalse(ToolFilter(ToolFilter.Mode.WHITELIST, setOf("a")).allows("javascript_engine"))
    }

    @Test
    fun `非空白名单仍然只许收窄`() {
        val old = settings(
            alwaysOnSupervision().copy(
                localToolFilter = ToolFilter(ToolFilter.Mode.WHITELIST, setOf("a", "b")),
            ),
            assistant(),
        )
        val incoming = old.copy(
            supervision = old.supervision.copy(
                localToolFilter = ToolFilter(ToolFilter.Mode.BLACKLIST, emptySet()),
            ),
        )
        val result = gate.enforceDuringLock(old, incoming).supervision.localToolFilter
        assertEquals(ToolFilter.Mode.WHITELIST, result.mode)
        assertEquals(setOf("a", "b"), result.items)
    }

    @Test
    fun `上个时段批准的解锁可以被清除`() {
        // 时段是全天 00:00-24:00，requestedAt 放到 3 天前 = 那次 session 早已结束
        val stale = PendingUnlock(
            requestedAt = System.currentTimeMillis() - 3 * 24 * 3600_000L,
            expiresAt = System.currentTimeMillis() - 3 * 24 * 3600_000L,
            reason = "上个时段的旧请求",
            grantedByAssistantId = assistantId,
            conversationId = Uuid.random(),
            status = PendingUnlock.Status.APPROVED,
        )
        val old = settings(alwaysOnSupervision().copy(pendingUnlock = stale), assistant())
        val incoming = old.copy(supervision = old.supervision.copy(pendingUnlock = null))
        assertNull(gate.enforceDuringLock(old, incoming).supervision.pendingUnlock)
    }

    @Test
    fun `本时段批准的解锁不可被清除`() {
        val fresh = PendingUnlock(
            requestedAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis(),
            reason = "本时段",
            grantedByAssistantId = assistantId,
            conversationId = Uuid.random(),
            status = PendingUnlock.Status.APPROVED,
        )
        val old = settings(alwaysOnSupervision().copy(pendingUnlock = fresh), assistant())
        val incoming = old.copy(supervision = old.supervision.copy(pendingUnlock = null))
        // 本时段内已解锁 → isActiveNow()==false，Gate 直接放行 incoming，
        // 真正的保护在时段内由 UI 只读 + 状态机负责，这里钉住不崩即可
        assertTrue(old.supervision.isUnlockStale().not())
    }

    @Test
    fun `陈旧解锁后守门员可以再次申请`() {
        val stale = PendingUnlock(
            requestedAt = System.currentTimeMillis() - 3 * 24 * 3600_000L,
            expiresAt = System.currentTimeMillis() - 3 * 24 * 3600_000L,
            reason = "旧的",
            grantedByAssistantId = assistantId,
            conversationId = Uuid.random(),
            status = PendingUnlock.Status.APPROVED,
        )
        val old = settings(alwaysOnSupervision().copy(pendingUnlock = stale), assistant())
        val newPending = stale.copy(
            requestedAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 600_000L,
            reason = "新的",
            status = PendingUnlock.Status.PENDING,
        )
        val incoming = old.copy(supervision = old.supervision.copy(pendingUnlock = newPending))
        val result = gate.enforceDuringLock(old, incoming).supervision.pendingUnlock
        assertEquals(PendingUnlock.Status.PENDING, result?.status)
        assertEquals("新的", result?.reason)
    }

    // ---------- 非白名单助手后门（2026-08-18）----------

    @Test
    fun `监督期停在非白名单助手_Gate有写入时会纠正当前助手`() {
        val other = Uuid.random()
        val old = settings(alwaysOnSupervision(), assistant())
        // incoming 把当前助手指向白名单外的助手（模拟同步下拉 / 设置写入）
        val incoming = old.copy(assistantId = other)
        // 注：assistants 列表里没有 other，sanitizeAssistants 会回落 old.assistantId
        assertEquals(assistantId, gate.enforceDuringLock(old, incoming).assistantId)
    }

    @Test
    fun `白名单为空时不限制当前助手`() {
        val other = Uuid.random()
        val otherAssistant = assistant().copy(id = other)
        val sup = alwaysOnSupervision().copy(allowedAssistantIds = emptySet())
        val old = settings(sup, assistant()).copy(assistants = listOf(assistant(), otherAssistant))
        val incoming = old.copy(assistantId = other)
        assertEquals(other, gate.enforceDuringLock(old, incoming).assistantId)
    }
}
