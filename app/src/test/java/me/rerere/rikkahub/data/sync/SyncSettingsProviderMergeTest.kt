package me.rerere.rikkahub.data.sync

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.sync.core.SyncSettingsFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 回归锁：providers 曾经不参与 mergeRemote，remote 整包落地把本机刚加/删/改的模型抹平。
 * 这批用例保证逐项 LWW 与删除墓碑的语义不被改回去。
 */
class SyncSettingsProviderMergeTest {

    private val providerId = Uuid.parse("a8d2d463-e8c0-41f2-b89e-f5eb8e716cce")

    private fun provider(
        id: Uuid = providerId,
        name: String = "P",
        models: List<Model> = emptyList(),
        updatedAt: Long = 0L,
        builtIn: Boolean = false,
    ) = ProviderSetting.OpenAI(
        id = id,
        name = name,
        models = models,
        updatedAt = updatedAt,
        builtIn = builtIn,
    )

    private fun model(name: String) = Model(modelId = name, displayName = name)

    private fun settings(
        providers: List<ProviderSetting>,
        tombstones: Map<String, Long> = emptyMap(),
    ) = Settings(providers = providers, providerTombstones = tombstones)

    @Test
    fun `本地更新的渠道不会被较旧的云端覆盖`() {
        val local = settings(listOf(provider(models = listOf(model("gpt-5")), updatedAt = 2000L)))
        val remote = settings(listOf(provider(models = emptyList(), updatedAt = 1000L)))

        val merged = SyncSettingsFilter.mergeRemote(local, remote)

        val kept = merged.providers.single { it.id == providerId }
        assertEquals(listOf("gpt-5"), kept.models.map { it.modelId })
        assertEquals(2000L, kept.updatedAt)
    }

    @Test
    fun `云端更新的渠道会覆盖较旧的本地`() {
        val local = settings(listOf(provider(models = emptyList(), updatedAt = 1000L)))
        val remote = settings(listOf(provider(models = listOf(model("claude")), updatedAt = 3000L)))

        val merged = SyncSettingsFilter.mergeRemote(local, remote)

        val won = merged.providers.single { it.id == providerId }
        assertEquals(listOf("claude"), won.models.map { it.modelId })
    }

    @Test
    fun `云端赢时保留本地的内置标记等运行时字段`() {
        val local = settings(listOf(provider(updatedAt = 1000L, builtIn = true)))
        val remote = settings(listOf(provider(updatedAt = 5000L, builtIn = false)))

        val merged = SyncSettingsFilter.mergeRemote(local, remote)

        assertTrue(merged.providers.single { it.id == providerId }.builtIn)
    }

    @Test
    fun `时间戳相同时保留本地避免UI闪动`() {
        val local = settings(listOf(provider(name = "local", updatedAt = 1000L)))
        val remote = settings(listOf(provider(name = "remote", updatedAt = 1000L)))

        val merged = SyncSettingsFilter.mergeRemote(local, remote)

        assertEquals("local", merged.providers.single { it.id == providerId }.name)
    }

    @Test
    fun `墓碑较新时渠道被真正删除而不是复活`() {
        val local = settings(emptyList(), tombstones = mapOf(providerId.toString() to 9000L))
        val remote = settings(listOf(provider(updatedAt = 1000L)))

        val merged = SyncSettingsFilter.mergeRemote(local, remote)

        assertNull(merged.providers.find { it.id == providerId })
        assertEquals(9000L, merged.providerTombstones[providerId.toString()])
    }

    @Test
    fun `删除后又被另一端更新则编辑胜出`() {
        val local = settings(emptyList(), tombstones = mapOf(providerId.toString() to 1000L))
        val remote = settings(listOf(provider(updatedAt = 5000L)))

        val merged = SyncSettingsFilter.mergeRemote(local, remote)

        assertEquals(1, merged.providers.count { it.id == providerId })
    }

    @Test
    fun `仅存在于本地的新增渠道不会被云端抹掉`() {
        val newId = Uuid.random()
        val local = settings(listOf(provider(updatedAt = 1000L), provider(id = newId, name = "new", updatedAt = 2000L)))
        val remote = settings(listOf(provider(updatedAt = 1000L)))

        val merged = SyncSettingsFilter.mergeRemote(local, remote)

        assertEquals("new", merged.providers.single { it.id == newId }.name)
    }
}
