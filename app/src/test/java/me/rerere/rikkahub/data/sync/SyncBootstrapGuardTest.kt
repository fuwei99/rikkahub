package me.rerere.rikkahub.data.sync

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.dao.SyncStateDao
import me.rerere.rikkahub.data.db.entity.SyncStateEntity
import me.rerere.rikkahub.data.sync.core.SyncBootstrapGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * bootstrap 安全阀（大统一重构 v2 §2.5）。
 *
 * 这是整个方案里最高危的一处：未 bootstrap 就 push，会把本机默认值刷上云。
 */
class SyncBootstrapGuardTest {

    private class FakeSyncStateDao : SyncStateDao {
        val rows = mutableMapOf<String, SyncStateEntity>()
        override suspend fun get(key: String) = rows[key]
        override suspend fun put(item: SyncStateEntity) { rows[item.key] = item }
        override suspend fun delete(key: String) { rows.remove(key) }
        override suspend fun getAll() = rows.values.toList()
    }

    @Test
    fun `fresh install cannot push settings`() = runBlocking {
        val guard = SyncBootstrapGuard(FakeSyncStateDao())
        assertFalse(
            "未 bootstrap 前只 pull 不 push。否则升级后本地 84 个 unknown 字段" +
                "会把云端真实配置写空 —— 这就是「切换那天两台设备设置全变默认」的剧本",
            guard.canPushSettings()
        )
    }

    @Test
    fun `push opens after a successful pull`() = runBlocking {
        val dao = FakeSyncStateDao()
        val guard = SyncBootstrapGuard(dao)
        guard.markBootstrapped(SyncBootstrapGuard.REASON_PULLED)
        assertTrue(guard.canPushSettings())
    }

    @Test
    fun `empty cloud also opens the gate`() = runBlocking {
        val dao = FakeSyncStateDao()
        val guard = SyncBootstrapGuard(dao)
        guard.markEmptyCloud()
        assertTrue(
            "云端确认为空（首次启用同步）也必须放行，否则这台设备的配置一辈子上不了云",
            guard.canPushSettings()
        )
        assertEquals(
            "来源要能区分：「云端本来是空的」和「拉到了云端数据」在排查时完全不同",
            SyncBootstrapGuard.REASON_EMPTY_CLOUD,
            dao.rows.values.first().value,
        )
    }

    @Test
    fun `marking twice keeps the first reason`() = runBlocking {
        val dao = FakeSyncStateDao()
        val guard = SyncBootstrapGuard(dao)
        guard.markBootstrapped(SyncBootstrapGuard.REASON_PULLED)
        val firstAt = dao.rows.values.first().updatedAt
        guard.markEmptyCloud()
        assertEquals(
            "重复标记不得覆盖首次来源与时间，审计要看的是「第一次什么时候开闸的」",
            SyncBootstrapGuard.REASON_PULLED,
            dao.rows.values.first().value,
        )
        assertEquals(firstAt, dao.rows.values.first().updatedAt)
    }

    @Test
    fun `reset after restore closes the gate again`() = runBlocking {
        val dao = FakeSyncStateDao()
        val guard = SyncBootstrapGuard(dao)
        guard.markBootstrapped(SyncBootstrapGuard.REASON_PULLED)
        assertTrue(guard.canPushSettings())

        guard.resetAfterRestore()

        assertFalse(
            "恢复备份后必须先 pull 一轮认清云端现状，否则会把备份里的旧配置当新事实推上云",
            guard.canPushSettings()
        )
    }

    @Test
    fun `dao failure is treated as not bootstrapped`() = runBlocking {
        // 读不出标记时必须按「未 bootstrap」处理（保守）：
        // 判断错方向会直接把默认值推上云，这个代价远大于「少同步一轮」
        val failing = object : SyncStateDao {
            override suspend fun get(key: String): SyncStateEntity? = error("db unavailable")
            override suspend fun put(item: SyncStateEntity) {}
            override suspend fun delete(key: String) {}
            override suspend fun getAll(): List<SyncStateEntity> = emptyList()
        }
        assertFalse(SyncBootstrapGuard(failing).canPushSettings())
    }

    @Test
    fun `key lives under the sync namespace`() = runBlocking {
        val dao = FakeSyncStateDao()
        SyncBootstrapGuard(dao).markEmptyCloud()
        val key = dao.rows.keys.first()
        assertTrue(
            "与 sync_state 里既有的 conv: / bundle: 前缀风格保持一致，实得 $key",
            key.startsWith("sync:")
        )
    }
}
