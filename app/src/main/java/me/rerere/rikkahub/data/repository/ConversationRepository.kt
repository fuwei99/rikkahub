package me.rerere.rikkahub.data.repository

import android.database.sqlite.SQLiteBlobTooBigException
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.schedule.ScheduleAction
import me.rerere.rikkahub.data.ai.schedule.ScheduleProtectionGuard
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.entity.SyncOutboxEntity
import me.rerere.rikkahub.data.sync.core.SyncApplyGate
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MemoryGraphBinding
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * 对话级能力覆盖列的通用解码：'' = 未设置（继承助手），解析失败也退回未设置。
 *
 * 脏数据（旧版本写入的格式、手改数据库、同步冲突）绝不能让整条会话读不出来 ——
 * 最坏结果只应是「这一项回退成继承助手默认」。
 */
private inline fun <reified T> String.decodeOverrideOrNull(): T? =
    takeIf { it.isNotEmpty() }
        ?.let { runCatching { JsonInstant.decodeFromString<T>(it) }.getOrNull() }

class ConversationRepository(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val favoriteDAO: FavoriteDAO,
    private val database: AppDatabase,
    private val filesManager: FilesManager,
    private val messageFtsManager: MessageFtsManager,
    /**
     * 定时任务会话保护（2026-08-21）：受保护会话（监督查岗）禁止被用户删除。
     *
     * 放在仓库层是**故意**的：删除入口有五个（抽屉、历史页、Web API、收藏页、同步墓碑），
     * 逐个补判断必然漏掉一个，漏一个就是一个后门。系统侧删除（同步墓碑、归档保留期清理）
     * 显式传 `force = true` 绕过。
     */
    private val scheduleProtectionGuard: ScheduleProtectionGuard,
) {
    companion object {
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40
    }

    /**
     * 云锚点同步写钩（P1）：本地变更入待推队列；同 ref 合并（只留最后一次操作），
     * 应用云端变更时由 [SyncApplyGate] 抑制。
     */
    private suspend fun enqueueSyncOutbox(refKey: String, op: String) {
        if (SyncApplyGate.applyingRemote) return
        val outbox = database.syncOutboxDao()
        outbox.deleteByRef(SyncOutboxEntity.KIND_CONVERSATION, refKey)
        outbox.insert(
            SyncOutboxEntity(
                kind = SyncOutboxEntity.KIND_CONVERSATION,
                refKey = refKey,
                op = op,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun getRecentConversations(assistantId: Uuid, limit: Int = 10): List<Conversation> {
        return conversationDAO.getRecentConversationsOfAssistant(
            assistantId = assistantId.toString(),
            limit = limit
        ).map { entity ->
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        }
    }

    /**
     * `chat_history` action=recent 的数据源（2026-08-11）。
     *
     * 与 [getRecentConversations] 的差别，条条都是之前工具的坑：
     * - **纯时间序**，置顶不霸榜；
     * - assistantId=null 表示跨全部助手；
     * - 可排除 agent 会话（含定时任务/查岗，靠 agent_session 判定，不靠标题猜）与调用者自己；
     * - 只按需加载末尾若干节点，而不是每个会话全量 loadMessageNodes
     *   （旧实现 limit=30 就要解析 30 个会话的全部消息 JSON，纯粹为了拿一个标题）。
     */
    suspend fun getRecentConversationSummaries(
        assistantId: Uuid?,
        limit: Int = 10,
        excludeAgents: Boolean = true,
        excludeConversationId: Uuid? = null,
        sinceMillis: Long? = null,
        tailMessages: Int = 1,
    ): List<RecentConversationSummary> {
        val rows = conversationDAO.getRecentConversationRows(
            assistantId = assistantId?.toString(),
            excludeAgents = excludeAgents,
            excludeId = excludeConversationId?.toString(),
            sinceMillis = sinceMillis,
            limit = limit,
        )
        return rows.map { row ->
            val tail = if (tailMessages > 0) loadTailMessages(row.id, tailMessages) else emptyList()
            RecentConversationSummary(
                id = row.id,
                assistantId = row.assistantId,
                title = row.title,
                isPinned = row.isPinned,
                createAt = Instant.ofEpochMilli(row.createAt),
                updateAt = Instant.ofEpochMilli(row.updateAt),
                folderId = row.folderId.ifBlank { null },
                agentTemplateId = row.agentTemplateId,
                agentStatus = row.agentStatus,
                messageCount = messageNodeDAO.countNodesOfConversation(row.id),
                tailMessages = tail,
            )
        }
    }

    suspend fun countMessageNodes(conversationId: String): Int =
        messageNodeDAO.countNodesOfConversation(conversationId)

    /** 末尾 [limit] 条「当前选中」消息，按时间正序返回 */
    suspend fun loadTailMessages(conversationId: String, limit: Int): List<UIMessage> {
        if (limit <= 0) return emptyList()
        val entities = runCatching {
            messageNodeDAO.getTailNodesOfConversation(conversationId, limit)
        }.getOrElse { return emptyList() }
        return entities
            .sortedBy { it.nodeIndex }
            .mapNotNull { entity ->
                val messages = runCatching {
                    JsonInstant.decodeFromString<List<UIMessage>>(entity.messages)
                }.getOrNull() ?: return@mapNotNull null
                messages.getOrNull(entity.selectIndex) ?: messages.lastOrNull()
            }
    }

    fun getConversationsOfAssistant(assistantId: Uuid): Flow<List<Conversation>> {
        return conversationDAO
            .getConversationsOfAssistant(assistantId.toString())
            .map { flow ->
                flow.map { entity ->
                    // 列表视图不需要完整的 nodes，使用空列表
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun getConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getUnfiledConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getConversationsOfFolderPaging(folderId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfFolderPaging(folderId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    suspend fun getConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.getConversationsOfAssistantPaging(assistantId.toString())
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun searchConversationsOfAssistantPage(
        assistantId: Uuid,
        titleKeyword: String,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.searchConversationsOfAssistantPaging(
            assistantId = assistantId.toString(),
            searchText = titleKeyword
        )
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun getUnfiledConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString()),
        offset,
        limit,
    )

    suspend fun getConversationsOfFolderPage(
        folderId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getConversationsOfFolderPaging(folderId.toString()),
        offset,
        limit,
    )

    private suspend fun loadConversationPage(
        pagingSource: PagingSource<Int, LightConversationEntity>,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    fun searchConversations(titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversations(titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsPaging(titleKeyword: String): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.searchConversationsPaging(titleKeyword) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun searchConversationsOfAssistant(assistantId: Uuid, titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversationsOfAssistant(assistantId.toString(), titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsOfAssistantPaging(assistantId: Uuid, titleKeyword: String): Flow<PagingData<Conversation>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = INITIAL_LOAD_SIZE,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                conversationDAO.searchConversationsOfAssistantPaging(
                    assistantId.toString(),
                    titleKeyword
                )
            }
        ).flow.map { pagingData ->
            pagingData.map { entity ->
                conversationSummaryToConversation(entity)
            }
        }

    suspend fun getConversationById(uuid: Uuid): Conversation? {
        val entity = conversationDAO.getConversationById(uuid.toString())
        return if (entity != null) {
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        } else null
    }

    suspend fun existsConversationById(uuid: Uuid): Boolean {
        return conversationDAO.existsById(uuid.toString())
    }

    suspend fun countConversations(): Int {
        return conversationDAO.countAll()
    }

    suspend fun getAllConversationIds(): List<String> = conversationDAO.getAllIds()

    suspend fun insertConversation(conversation: Conversation) {
        val stamped = stampLocalWrite(conversation)
        database.withTransaction {
            conversationDAO.insert(
                conversationToConversationEntity(stamped)
            )
            saveMessageNodes(stamped.id.toString(), stamped.messageNodes)
            enqueueSyncOutbox(stamped.id.toString(), SyncOutboxEntity.OP_UPSERT)
        }
        messageFtsManager.indexConversation(stamped)
    }

    suspend fun updateConversation(conversation: Conversation) {
        val stamped = stampLocalWrite(conversation)
        database.withTransaction {
            conversationDAO.update(
                conversationToConversationEntity(stamped)
            )
            // 增量同步节点，避免「先全删再全写」：一旦调用方持有空/幻影快照
            // （如会话回收后 getOrCreateSession 造的幻影），全删重写会把已有历史
            // 物理清空（2026-08-08 辩论赛历史丢失事故）。只删多余、插新增、更新存在的。
            upsertMessageNodes(stamped.id.toString(), stamped.messageNodes)
            enqueueSyncOutbox(stamped.id.toString(), SyncOutboxEntity.OP_UPSERT)
        }
        messageFtsManager.indexConversation(stamped)
    }

    private fun stampLocalWrite(conversation: Conversation): Conversation =
        if (SyncApplyGate.applyingRemote) conversation else conversation.copy(updateAt = Instant.now())

    suspend fun deleteConversation(conversation: Conversation, force: Boolean = false) {
        if (!force) {
            scheduleProtectionGuard
                .blockReason(conversation.id, ScheduleAction.DELETE_CONVERSATION)
                ?.let { reason -> throw IllegalStateException(reason) }
        }
        messageFtsManager.deleteConversation(conversation.id.toString())
        database.withTransaction {
            // message_node 会通过 CASCADE 自动删除
            conversationDAO.delete(
                conversationToConversationEntity(conversation)
            )
            // 邮件内核（收敛设计 §10）：目标对话删除时级联清空其收件箱
            database.agentInboxDao().deleteByTarget(conversation.id.toString())
            enqueueSyncOutbox(conversation.id.toString(), SyncOutboxEntity.OP_DELETE)
        }
        // 注意（P3 拍板 v1.1 #10）：附件与会话解耦，删会话不删任何文件/云资产——
        // 附件归『文件管理』注册行所有，清理只发生在文件管理页。
    }

    suspend fun searchMessages(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        conversationId: String? = null,
        fromMillis: Long? = null,
        toMillis: Long? = null,
        limit: Int = 50,
    ) = messageFtsManager.search(
        keyword = keyword,
        sort = sort,
        conversationId = conversationId,
        fromMillis = fromMillis,
        toMillis = toMillis,
        limit = limit,
    )

    suspend fun rebuildAllIndexes(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }) {
        messageFtsManager.deleteAll()
        val allIds = conversationDAO.getAllIds()
        val total = allIds.size
        allIds.forEachIndexed { index, id ->
            val entity = conversationDAO.getConversationById(id) ?: return@forEachIndexed
            val nodes = loadMessageNodes(entity.id)
            val conversation = conversationEntityToConversation(entity, nodes)
            messageFtsManager.indexConversation(conversation)
            onProgress(index + 1, total)
        }
    }

    suspend fun deleteConversationOfAssistant(assistantId: Uuid) {
        getConversationsOfAssistant(assistantId).first().forEach { conversation ->
            // 「清空该助手全部对话」不能顺手抹掉受保护的定时任务会话：
            // 受保护的跳过（不抛异常打断整批），其余照删。
            runCatching { deleteConversation(conversation) }
        }
    }

    fun conversationToConversationEntity(conversation: Conversation): ConversationEntity {
        require(conversation.messageNodes.none { it.messages.any { message -> message.hasBase64Part() } })
        return ConversationEntity(
            id = conversation.id.toString(),
            title = conversation.title,
            nodes = "[]",  // nodes 现在存储在单独的表中
            createAt = conversation.createAt.toEpochMilli(),
            updateAt = conversation.updateAt.toEpochMilli(),
            assistantId = conversation.assistantId.toString(),
            chatSuggestions = JsonInstant.encodeToString(conversation.chatSuggestions),
            isPinned = conversation.isPinned,
            customSystemPrompt = conversation.customSystemPrompt ?: "",
            modeInjectionIds = JsonInstant.encodeToString(conversation.modeInjectionIds),
            lorebookIds = JsonInstant.encodeToString(conversation.lorebookIds),
            // null（继承助手）落库为空串，[] 与非空数组照常序列化
            memoryGraphBindings = conversation.memoryGraphBindings
                ?.let { JsonInstant.encodeToString(it) } ?: "",
            workspaceCwd = conversation.workspaceCwd ?: "",
            // null（继承助手）落空串；WORKSPACE_ID_UNBOUND 哨兵落全零 Uuid 字符串（明确不挂）；
            // 其他 Uuid 正常落库。第三态不能再写成空串，否则「未绑定」会被读成「继承」。
            workspaceId = conversation.workspaceId?.toString() ?: "",
            folderId = conversation.folderId?.toString() ?: "",
            modelId = conversation.modelId?.toString() ?: "",
            // ---- 对话级能力覆盖：null（继承助手）落库为空串 ----
            // 集合的 "[]" 是有效值（明确全关），必须与 "" 区分，所以这里只对 null 兜空串。
            reasoningLevel = conversation.reasoningLevel?.let { JsonInstant.encodeToString(it) } ?: "",
            enableWebSearch = conversation.enableWebSearch?.let { if (it) "1" else "0" } ?: "",
            enabledSkills = conversation.enabledSkills?.let { JsonInstant.encodeToString(it) } ?: "",
            localTools = conversation.localTools?.let { JsonInstant.encodeToString(it) } ?: "",
            workspaceTools = conversation.workspaceTools?.let { JsonInstant.encodeToString(it) } ?: "",
            mcpTools = conversation.mcpTools?.let { JsonInstant.encodeToString(it) } ?: "",
            mcpServers = conversation.mcpServers?.let { JsonInstant.encodeToString(it) } ?: "",
            memoryOptions = conversation.memoryOptions?.let { JsonInstant.encodeToString(it) } ?: "",
            // 自动压缩覆盖（2026-08-21 补持久化）：null = 继承助手 autoCompress
            autoCompressOverride = conversation.autoCompressOverride
                ?.let { JsonInstant.encodeToString(it) } ?: "",
        )
    }

    fun conversationEntityToConversation(
        conversationEntity: ConversationEntity,
        messageNodes: List<MessageNode>
    ): Conversation {
        return Conversation(
            id = Uuid.parse(conversationEntity.id),
            title = conversationEntity.title,
            messageNodes = messageNodes.filter { it.messages.isNotEmpty() },
            createAt = Instant.ofEpochMilli(conversationEntity.createAt),
            updateAt = Instant.ofEpochMilli(conversationEntity.updateAt),
            assistantId = Uuid.parse(conversationEntity.assistantId),
            chatSuggestions = JsonInstant.decodeFromString(conversationEntity.chatSuggestions),
            isPinned = conversationEntity.isPinned,
            customSystemPrompt = conversationEntity.customSystemPrompt.ifEmpty { null },
            modeInjectionIds = JsonInstant.decodeFromString(conversationEntity.modeInjectionIds),
            lorebookIds = JsonInstant.decodeFromString(conversationEntity.lorebookIds),
            memoryGraphBindings = conversationEntity.memoryGraphBindings
                .takeIf { it.isNotEmpty() }
                ?.let { runCatching { JsonInstant.decodeFromString<List<MemoryGraphBinding>>(it) }.getOrNull() },
            workspaceCwd = conversationEntity.workspaceCwd.ifEmpty { null },
            workspaceId = conversationEntity.workspaceId.ifEmpty { null }?.let { Uuid.parse(it) },
            folderId = conversationEntity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
            modelId = conversationEntity.modelId.ifEmpty { null }?.let { Uuid.parse(it) },
            // 空串 = 继承助手；解析失败也退回继承（脏数据不该让整条会话读不出来）
            reasoningLevel = conversationEntity.reasoningLevel.decodeOverrideOrNull(),
            enableWebSearch = when (conversationEntity.enableWebSearch) {
                "1" -> true
                "0" -> false
                else -> null
            },
            enabledSkills = conversationEntity.enabledSkills.decodeOverrideOrNull(),
            localTools = conversationEntity.localTools.decodeOverrideOrNull(),
            workspaceTools = conversationEntity.workspaceTools.decodeOverrideOrNull(),
            mcpTools = conversationEntity.mcpTools.decodeOverrideOrNull(),
            mcpServers = conversationEntity.mcpServers.decodeOverrideOrNull(),
            memoryOptions = conversationEntity.memoryOptions.decodeOverrideOrNull(),
            autoCompressOverride = conversationEntity.autoCompressOverride.decodeOverrideOrNull(),
        )
    }

    fun getPinnedConversations(): Flow<List<Conversation>> {
        return conversationDAO
            .getPinnedConversations()
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    suspend fun togglePinStatus(conversationId: Uuid) {
        conversationDAO.updatePinStatus(
            id = conversationId.toString(),
            isPinned = !(getConversationById(conversationId)?.isPinned ?: false)
        )
    }

    /**
     * 单列更新会话的文件夹归属，folderId 为 null 表示移出文件夹（未归类）。
     */
    suspend fun updateConversationFolderId(conversationId: Uuid, folderId: Uuid?) {
        conversationDAO.updateFolderId(
            id = conversationId.toString(),
            folderId = folderId?.toString() ?: ""
        )
    }

    private fun conversationSummaryToConversation(entity: LightConversationEntity): Conversation {
        return Conversation(
            id = Uuid.parse(entity.id),
            assistantId = Uuid.parse(entity.assistantId),
            title = entity.title,
            isPinned = entity.isPinned,
            createAt = Instant.ofEpochMilli(entity.createAt),
            updateAt = Instant.ofEpochMilli(entity.updateAt),
            messageNodes = emptyList(),
            folderId = entity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
        )
    }

    private suspend fun loadMessageNodes(conversationId: String): List<MessageNode> {
        val favoriteNodeIds = favoriteDAO
            .getFavoriteNodeIdsOfConversation(conversationId)
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            .toSet()

        return database.withTransaction {
            val nodes = mutableListOf<MessageNode>()
            var offset = 0
            val pageSize = 64
            while (true) {
                val page = try {
                    messageNodeDAO.getNodesOfConversationPaged(conversationId, pageSize, offset)
                } catch (e: SQLiteBlobTooBigException) {
                    e.printStackTrace()
                    offset += pageSize
                    continue
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                    offset += pageSize
                    continue
                }
                if (page.isEmpty()) break
                page.forEach { entity ->
                    val messages = JsonInstant.decodeFromString<List<UIMessage>>(entity.messages)
                    val nodeId = Uuid.parse(entity.id)
                    nodes.add(
                        MessageNode(
                            id = nodeId,
                            messages = messages,
                            selectIndex = entity.selectIndex,
                            isFavorite = favoriteNodeIds.contains(nodeId)
                        )
                    )
                }
                offset += page.size
            }
            nodes
        }
    }

    private suspend fun saveMessageNodes(conversationId: String, nodes: List<MessageNode>) {
        val entities = nodes.mapIndexed { index, node ->
            MessageNodeEntity(
                id = node.id.toString(),
                conversationId = conversationId,
                nodeIndex = index,
                messages = JsonInstant.encodeToString(node.messages),
                selectIndex = node.selectIndex
            )
        }
        messageNodeDAO.insertAll(entities)
    }

    /**
     * 增量同步消息节点：只删除多余节点、插入新增节点、更新已存在节点。
     *
     * 与 [saveMessageNodes] 的全删重写不同，这里不做破坏性清空——
     * 调用方快照若意外缺失历史（如内存幻影），只会补上新节点，不会抹掉 DB 已有内容。
     */
    private suspend fun upsertMessageNodes(conversationId: String, nodes: List<MessageNode>) {
        val existing = messageNodeDAO.getNodesOfConversation(conversationId).associateBy { it.id }
        val incoming = nodes.mapIndexed { index, node ->
            MessageNodeEntity(
                id = node.id.toString(),
                conversationId = conversationId,
                nodeIndex = index,
                messages = JsonInstant.encodeToString(node.messages),
                selectIndex = node.selectIndex
            )
        }
        incoming.forEach { entity ->
            val old = existing[entity.id]
            when {
                old == null -> messageNodeDAO.insert(entity)
                old != entity -> messageNodeDAO.update(entity)
            }
        }
        val incomingIds = incoming.mapTo(mutableSetOf()) { it.id }
        existing.keys.filterTo(mutableListOf()) { it !in incomingIds }
            .forEach { id -> messageNodeDAO.deleteById(id) }
    }
}

/**
 * 轻量级的会话查询结果，不包含 nodes 和 suggestions 字段
 */
data class LightConversationEntity(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val folderId: String = "",
)

data class ConversationPageResult(
    val items: List<Conversation>,
    val nextOffset: Int?,
)

/**
 * `chat_history` action=recent 的一行结果：会话摘要 + agent 身份 + 末尾若干条消息。
 */
data class RecentConversationSummary(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Instant,
    val updateAt: Instant,
    val folderId: String?,
    val agentTemplateId: String?,
    val agentStatus: String?,
    val messageCount: Int,
    val tailMessages: List<UIMessage>,
) {
    val isAgent: Boolean get() = agentTemplateId != null
}
