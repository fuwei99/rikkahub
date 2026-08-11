package me.rerere.rikkahub.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import me.rerere.ai.core.TokenUsage
import me.rerere.rikkahub.data.db.dao.AssetLabelDAO
import me.rerere.rikkahub.data.db.dao.AgentSessionDAO
import me.rerere.rikkahub.data.db.dao.AgentInboxDAO
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.FolderDAO
import me.rerere.rikkahub.data.db.dao.GenMediaDAO
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.dao.MediaUploadOutboxDAO
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.MemoryGraphDAO
import me.rerere.rikkahub.data.db.dao.MemoryGraphLinkDAO
import me.rerere.rikkahub.data.db.dao.MemoryGraphNodeDAO
import me.rerere.rikkahub.data.db.dao.MemoryLinkDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.MemoryAutoSaveCandidateDAO
import me.rerere.rikkahub.data.db.dao.ScreenTimeDayDAO
import me.rerere.rikkahub.data.db.dao.SyncOutboxDao
import me.rerere.rikkahub.data.db.dao.SyncStateDao
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.AssetLabelEntity
import me.rerere.rikkahub.data.db.entity.AgentSessionEntity
import me.rerere.rikkahub.data.db.entity.AgentInboxEntity
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.db.entity.FolderEntity
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.MediaUploadOutboxEntity
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MemoryGraphEntity
import me.rerere.rikkahub.data.db.entity.MemoryGraphLinkEntity
import me.rerere.rikkahub.data.db.entity.MemoryGraphNodeEntity
import me.rerere.rikkahub.data.db.entity.MemoryLinkEntity
import me.rerere.rikkahub.data.db.entity.MemoryAutoSaveCandidateEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.entity.ScreenTimeDayEntity
import me.rerere.rikkahub.data.db.entity.SyncOutboxEntity
import me.rerere.rikkahub.data.db.entity.SyncStateEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.db.migrations.Migration_16_17
import me.rerere.rikkahub.data.db.migrations.Migration_22_23
import me.rerere.rikkahub.data.db.migrations.Migration_34_35
import me.rerere.rikkahub.data.db.migrations.Migration_36_37
import me.rerere.rikkahub.data.db.migrations.Migration_41_42
import me.rerere.rikkahub.data.db.migrations.Migration_8_9
import me.rerere.rikkahub.utils.JsonInstant

@Database(
    entities = [
        ConversationEntity::class,
        MemoryEntity::class,
        MemoryLinkEntity::class,
        MemoryAutoSaveCandidateEntity::class,
        MemoryGraphEntity::class,
        MemoryGraphNodeEntity::class,
        MemoryGraphLinkEntity::class,
        GenMediaEntity::class,
        MessageNodeEntity::class,
        ManagedFileEntity::class,
        AssetLabelEntity::class,
        FavoriteEntity::class,
        WorkspaceEntity::class,
        FolderEntity::class,
        SyncOutboxEntity::class,
        SyncStateEntity::class,
        MediaUploadOutboxEntity::class,
        AgentSessionEntity::class,
        AgentInboxEntity::class,
        ScreenTimeDayEntity::class,
    ],
    version = 45,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9, spec = Migration_8_9::class),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 16, to = 17, spec = Migration_16_17::class),
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19),
        AutoMigration(from = 19, to = 20),
        AutoMigration(from = 20, to = 21),
        AutoMigration(from = 21, to = 22),
        AutoMigration(from = 22, to = 23, spec = Migration_22_23::class),
        AutoMigration(from = 23, to = 24),
        AutoMigration(from = 24, to = 25),
        // 注意：26 -> 27、27 -> 28 为手写迁移（仓库缺 26/27.json，无法 AutoMigration）
    ]
)
@TypeConverters(TokenUsageConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDAO

    abstract fun memoryDao(): MemoryDAO

    abstract fun memoryGraphDao(): MemoryGraphDAO

    abstract fun memoryGraphNodeDao(): MemoryGraphNodeDAO

    abstract fun memoryGraphLinkDao(): MemoryGraphLinkDAO

    abstract fun memoryLinkDao(): MemoryLinkDAO

    abstract fun memoryAutoSaveCandidateDao(): MemoryAutoSaveCandidateDAO

    abstract fun genMediaDao(): GenMediaDAO

    abstract fun messageNodeDao(): MessageNodeDAO

    abstract fun managedFileDao(): ManagedFileDAO

    abstract fun assetLabelDao(): AssetLabelDAO

    abstract fun favoriteDao(): FavoriteDAO

    abstract fun workspaceDao(): WorkspaceDAO

    abstract fun folderDao(): FolderDAO

    abstract fun syncOutboxDao(): SyncOutboxDao

    abstract fun mediaUploadOutboxDao(): MediaUploadOutboxDAO

    abstract fun syncStateDao(): SyncStateDao

    abstract fun agentSessionDao(): AgentSessionDAO

    abstract fun agentInboxDao(): AgentInboxDAO

    abstract fun screenTimeDayDao(): ScreenTimeDayDAO
}

object TokenUsageConverter {
    @TypeConverter
    fun fromTokenUsage(usage: TokenUsage?): String {
        return JsonInstant.encodeToString(usage)
    }

    @TypeConverter
    fun toTokenUsage(usage: String): TokenUsage? {
        return JsonInstant.decodeFromString(usage)
    }
}
