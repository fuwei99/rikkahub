package me.rerere.rikkahub.data.sync.core

import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.sync.d1.D1Config
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.ai.provider.ProviderSetting
import kotlin.uuid.Uuid

/**
 * settings 上推/下拉的双向清洗规则（见 docs/cloud-sync-plan.md §5.2）：
 * - displaySetting：观感字段，走独立 bundle "settings.display"（受设备开关控制）
 * - d1Config / s3Config：设备本地锚点/旧备份密钥，不存 D1
 * - r2Accounts：必须完整同步（含 secretAccessKey），否则其他设备无法为 r2:// 对象签名读取
 * - webServer*：本机服务入口/密码，设备本地
 * - launchCount / sponsorAlertDismissedAt：volatile 噪音字段
 * - assistantId（当前选中的助手）：设备本地 UI 状态，不存 D1。否则一台设备切助手会把
 *   另一台设备正在用的"当前助手"静默换掉（用户报告的核心 bug）；对话内容本身照常同步。
 */
object SyncSettingsFilter {

    /** 生成完成/Live Update 通知是设备本地偏好，不随显示设置同步。 */
    fun displayForUpload(display: DisplaySetting): DisplaySetting = display.copy(
        enableNotificationOnMessageGeneration = false,
        enableLiveUpdateNotification = false,
    )

    fun mergeRemoteDisplay(local: DisplaySetting, remote: DisplaySetting): DisplaySetting = remote.copy(
        enableNotificationOnMessageGeneration = local.enableNotificationOnMessageGeneration,
        enableLiveUpdateNotification = local.enableLiveUpdateNotification,
    )

    /** 上推前：剥离设备本地字段；R2 读取密钥必须保留以支持跨设备媒体访问 */
    fun forUpload(settings: Settings): Settings = settings.copy(
        displaySetting = DisplaySetting(),
        d1Config = D1Config(),
        s3Config = S3Config(),
        r2Accounts = settings.r2Accounts,
        webServerEnabled = false,
        webServerPort = 8080,
        webServerJwtEnabled = false,
        webServerAccessPassword = "",
        externalDeliveryToken = "",
        webServerLocalhostOnly = false,
        launchCount = 0,
        sponsorAlertDismissedAt = 0,
        // 当前助手是设备状态：上云只存固定哨兵，各端 pull 时用本地值兜底（见 mergeRemote）
        assistantId = DEFAULT_ASSISTANT_ID,
    )

    /** 下拉后：保留本机锚点配置；R2 账户以云端为准，旧云端空 secret 时兼容保留本机 secret */
    fun mergeRemote(local: Settings, remote: Settings): Settings {
        val localSecretMap = local.r2Accounts.associate { it.id to it.secretAccessKey }
        val mergedR2 = remote.r2Accounts.map { acct ->
            if (acct.secretAccessKey.isBlank()) {
                acct.copy(secretAccessKey = localSecretMap[acct.id] ?: "")
            } else acct
        }
        val mergedAssistants = mergeAssistantsByUpdatedAt(local, remote)
        val mergedMcpServers = mergeMcpServersByUpdatedAt(local, remote)
        val mergedTombstones = mergeTombstones(local.providerTombstones, remote.providerTombstones)
        val mergedProviders = mergeProvidersByUpdatedAt(local, remote, mergedTombstones)

        // 四个列表类设置走外挂版本表的通用 LWW，不再整包采纳云端
        val (mergedImageProviders, mergedImageMeta) = mergeListByVersion(
            local = local.imageProviders,
            remote = remote.imageProviders,
            localMeta = local.imageProvidersSyncMeta,
            remoteMeta = remote.imageProvidersSyncMeta,
        ) { it.id.toString() }
        val (mergedTtsProviders, mergedTtsMeta) = mergeListByVersion(
            local = local.ttsProviders,
            remote = remote.ttsProviders,
            localMeta = local.ttsProvidersSyncMeta,
            remoteMeta = remote.ttsProvidersSyncMeta,
        ) { it.id.toString() }
        val (mergedAsrProviders, mergedAsrMeta) = mergeListByVersion(
            local = local.asrProviders,
            remote = remote.asrProviders,
            localMeta = local.asrProvidersSyncMeta,
            remoteMeta = remote.asrProvidersSyncMeta,
        ) { it.id.toString() }
        val (mergedSearchServices, mergedSearchMeta) = mergeListByVersion(
            local = local.searchServices,
            remote = remote.searchServices,
            localMeta = local.searchServicesSyncMeta,
            remoteMeta = remote.searchServicesSyncMeta,
        ) { it.id.toString() }
        val (mergedVectorProviders, mergedVectorMeta) = mergeListByVersion(
            local = local.vectorProviders,
            remote = remote.vectorProviders,
            localMeta = local.vectorProvidersSyncMeta,
            remoteMeta = remote.vectorProvidersSyncMeta,
        ) { it.id.toString() }

        // 内置生图渠道的 @Transient 属性（builtIn/description）云端拉下来是空的，从本地同 id 项揃回
        val localImageById = local.imageProviders.associateBy { it.id }
        val restoredImageProviders = mergedImageProviders.map { provider ->
            val twin = localImageById[provider.id]
            if (twin != null && provider !== twin) {
                provider.copyProvider(
                    builtIn = twin.builtIn,
                    description = twin.description,
                    shortDescription = twin.shortDescription,
                )
            } else provider
        }

        // 内置向量渠道的 @Transient 属性（builtIn/description）云端拉下来是空的，从本地同 id 项揃回
        val localVectorById = local.vectorProviders.associateBy { it.id }
        val restoredVectorProviders = mergedVectorProviders.map { provider ->
            val twin = localVectorById[provider.id]
            if (twin != null && provider !== twin) {
                provider.copyProvider(
                    builtIn = twin.builtIn,
                    description = twin.description,
                    shortDescription = twin.shortDescription,
                )
            } else provider
        }

        // 选中的服务被合并删掉时把指针拉回合法范围，否则 UI 会指向不存在的项
        val safeSearchSelected = remote.searchServiceSelected
            .coerceIn(0, (mergedSearchServices.size - 1).coerceAtLeast(0))
        val safeSelectedTts = mergedTtsProviders
            .firstOrNull { it.id == remote.selectedTTSProviderId }?.id
            ?: mergedTtsProviders.firstOrNull()?.id
            ?: remote.selectedTTSProviderId
        val safeSelectedAsr = mergedAsrProviders
            .firstOrNull { it.id == remote.selectedASRProviderId }?.id
            ?: mergedAsrProviders.firstOrNull()?.id

        return remote.copy(
            displaySetting = local.displaySetting,
            d1Config = local.d1Config,
            s3Config = local.s3Config,
            r2Accounts = mergedR2,
            providers = mergedProviders,
            providerTombstones = mergedTombstones,
            imageProviders = restoredImageProviders,
            imageProvidersSyncMeta = mergedImageMeta,
            vectorProviders = restoredVectorProviders,
            vectorProvidersSyncMeta = mergedVectorMeta,
            ttsProviders = mergedTtsProviders,
            ttsProvidersSyncMeta = mergedTtsMeta,
            selectedTTSProviderId = safeSelectedTts,
            asrProviders = mergedAsrProviders,
            asrProvidersSyncMeta = mergedAsrMeta,
            selectedASRProviderId = safeSelectedAsr,
            searchServices = mergedSearchServices,
            searchServicesSyncMeta = mergedSearchMeta,
            searchServiceSelected = safeSearchSelected,
            assistants = mergedAssistants,
            mcpServers = mergedMcpServers,
            // 当前助手是设备本地状态：无论云端 settings 里是什么，一律保留本机选择
            assistantId = local.assistantId,
            webServerEnabled = local.webServerEnabled,
            webServerPort = local.webServerPort,
            webServerJwtEnabled = local.webServerJwtEnabled,
            webServerAccessPassword = local.webServerAccessPassword,
            externalDeliveryToken = local.externalDeliveryToken,
            webServerLocalhostOnly = local.webServerLocalhostOnly,
            launchCount = local.launchCount,
            sponsorAlertDismissedAt = local.sponsorAlertDismissedAt,
        )
    }


    /** 墓碑并集，同 id 取较晚的删除时间 */
    private fun mergeTombstones(local: Map<String, Long>, remote: Map<String, Long>): Map<String, Long> =
        buildMap {
            putAll(remote)
            local.forEach { (id, ts) ->
                val existing = this[id]
                if (existing == null || ts > existing) this[id] = ts
            }
        }

    /**
     * 渠道逐项 LWW。之前这里根本没合并，remote.providers 直接整包落地，
     * 导致本机刚加/删/改的模型被 pull 一次就抹平。
     *
     * 规则：
     * - 只存于一边 → 直接采纳（但要过墓碑过滤）
     * - 两边都有 → updatedAt 大的赢；相等时保本地，避免无意义的 UI 闪动
     * - builtIn/description 是 @Transient 运行时属性，云端拉下来永远是 false/空，
     *   赢家是远程时必须从本地同 id 项把这三个属性揃回来
     * - 墓碑时间 >= 渠道 updatedAt → 认为删除更新，丢弃该渠道
     */
    private fun mergeProvidersByUpdatedAt(
        local: Settings,
        remote: Settings,
        tombstones: Map<String, Long>,
    ): List<ProviderSetting> {
        val localById = local.providers.associateBy { it.id }
        val remoteById = remote.providers.associateBy { it.id }
        val ids = LinkedHashSet<Uuid>().apply {
            addAll(remote.providers.map { it.id })
            addAll(local.providers.map { it.id })
        }
        return buildList {
            ids.forEach { id ->
                val l = localById[id]
                val r = remoteById[id]
                val winner = when {
                    l == null -> r
                    r == null -> l
                    r.updatedAt > l.updatedAt -> r
                    else -> l
                } ?: return@forEach
                // 删除与编辑竞争：墓碑不旧于胜出的版本就真删
                val tombstonedAt = tombstones[id.toString()]
                if (tombstonedAt != null && tombstonedAt >= winner.updatedAt) return@forEach
                add(if (winner === r && l != null) winner.restoreRuntimeFieldsFrom(l) else winner)
            }
        }
    }

    /** 云端 payload 丢失的 @Transient 运行时属性（内置标识/说明文案）从本地同 id 项恢复 */
    private fun ProviderSetting.restoreRuntimeFieldsFrom(local: ProviderSetting): ProviderSetting =
        copyProvider(
            builtIn = local.builtIn,
            description = local.description,
            shortDescription = local.shortDescription,
        )

    private fun mergeMcpServersByUpdatedAt(local: Settings, remote: Settings) = buildList {
        val localById = local.mcpServers.associateBy { it.id }
        val remoteById = remote.mcpServers.associateBy { it.id }
        val ids = LinkedHashSet<kotlin.uuid.Uuid>().apply {
            addAll(remote.mcpServers.map { it.id })
            addAll(local.mcpServers.map { it.id })
        }
        ids.forEach { id ->
            val l = localById[id]
            val r = remoteById[id]
            add(
                when {
                    l == null -> r
                    r == null -> l
                    l.commonOptions.updatedAt > r.commonOptions.updatedAt -> l
                    else -> r
                } ?: return@forEach
            )
        }
    }
    private fun mergeAssistantsByUpdatedAt(local: Settings, remote: Settings) = buildList {
        val localById = local.assistants.associateBy { it.id }
        val remoteById = remote.assistants.associateBy { it.id }
        val ids = LinkedHashSet<kotlin.uuid.Uuid>().apply {
            addAll(remote.assistants.map { it.id })
            addAll(local.assistants.map { it.id })
        }
        ids.forEach { id ->
            val l = localById[id]
            val r = remoteById[id]
            add(
                when {
                    l == null -> r
                    r == null -> l
                    l.updatedAt > r.updatedAt -> l
                    else -> r
                } ?: return@forEach
            )
        }
    }
}
