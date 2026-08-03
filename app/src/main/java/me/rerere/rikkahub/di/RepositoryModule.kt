package me.rerere.rikkahub.di

import android.content.Context
import me.rerere.rikkahub.data.files.AssetResolver
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.registry.WorkspaceRegistryMigrator
import me.rerere.rikkahub.data.registry.WorkspaceRegistryStore
import me.rerere.rikkahub.data.sync.core.AutoSyncWorker
import me.rerere.rikkahub.data.sync.core.SnapshotWorker
import me.rerere.rikkahub.data.sync.core.SyncEngine
import me.rerere.rikkahub.data.sync.core.SyncLockManager
import me.rerere.rikkahub.data.sync.r2.MediaResolver
import me.rerere.rikkahub.data.sync.r2.R2MediaStore
import me.rerere.rikkahub.data.workspace.WorkspaceScheduledProcessManager
import org.koin.androidx.workmanager.dsl.worker
import me.rerere.rikkahub.data.repository.AssetLabelRepository
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.io.File

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get(), get(), get())
    }

    single {
        FolderRepository(get(), get(), get())
    }

    single {
        MemoryRepository(get(), get())
    }

    single {
        GenMediaRepository(get(), get())
    }

    single {
        FilesRepository(get(), get())
    }

    single {
        AssetLabelRepository(get(), get())
    }

    single {
        FavoriteRepository(get(), get())
    }

    single {
        val context: Context = get()
        WorkspaceManager(
            baseDir = File(context.filesDir, "workspaces"),
            shellRunner = ProotShellRunner(
                nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
                extraBindMounts = listOf(
                    WorkspaceBindMount(
                        source = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() },
                        target = "/skills",
                    ),
                    WorkspaceBindMount(
                        source = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() },
                        target = "/tool_outputs",
                    ),
                ),
            )
        )
    }

    single {
        RootfsInstaller(get())
    }

    single {
        val context: Context = get()
        WorkspaceRegistryStore(workspacesDir = File(context.filesDir, "workspaces"))
    }

    single {
        val context: Context = get()
        WorkspaceRegistryMigrator(
            registryStore = get(),
            dao = get(),
            manager = get(),
            workspacesDir = File(context.filesDir, "workspaces"),
        )
    }

    single {
        WorkspaceRepository(get(), get(), get(), get(), androidContext())
    }

    single {
        WorkspaceScheduledProcessManager(get(), get())
    }

    single {
        FilesManager(get(), get(), get(), get())
    }

    single {
        SkillManager(get(), get())
    }

    // 云锚点同步引擎（P1）
    single {
        SyncEngine(
            context = get(),
            settingsStore = get(),
            conversationRepository = get(),
            database = get(),
            httpClient = get(),
            json = get(),
            r2MediaStore = get(),
            syncAdvancedConfigStore = get(),
        )
    }

    // 会话互斥锁（P2）
    single {
        SyncLockManager(get(), get(), get())
    }

    // R2 媒体存取 + 发送链路媒体适配（P3）
    single {
        R2MediaStore(get(), get())
    }
    single {
        AssetResolver(get(), get(), get(), get(), get(), get())
    }
    single {
        MediaResolver(get())
    }

    worker {
        AutoSyncWorker(get(), get(), get())
    }
    worker {
        SnapshotWorker(get(), get(), get(), get())
    }
}
