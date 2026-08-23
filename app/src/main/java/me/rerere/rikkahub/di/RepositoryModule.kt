package me.rerere.rikkahub.di

import me.rerere.rikkahub.data.files.AppPaths
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
import me.rerere.rikkahub.data.sync.core.SyncClock
import me.rerere.rikkahub.data.sync.r2.MediaResolver
import me.rerere.rikkahub.data.sync.r2.R2MediaStore
import me.rerere.rikkahub.data.workspace.WorkspaceScheduledProcessManager
import me.rerere.rikkahub.data.screentime.ScreenTimeCollectWorker
import org.koin.androidx.workmanager.dsl.worker
import me.rerere.rikkahub.data.repository.AssetLabelRepository
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.MemoryGraphRepository
import me.rerere.rikkahub.data.repository.MemoryGraphRegistry
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.ai.provider.providers.VectorProvider
import me.rerere.rikkahub.data.ai.memory.MemoryGraphExtractor
import me.rerere.rikkahub.data.ai.memory.MemoryAutoSaveScheduler
import me.rerere.rikkahub.data.ai.memory.MemorySemanticSearch
import me.rerere.rikkahub.data.ai.memory.MemoryGraphSelector
import me.rerere.rikkahub.data.ai.memory.MemoryGraphBindingResolver
import me.rerere.rikkahub.data.vector.GraphVectorStore
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get(), get(), get(), get())
    }

    single {
        FolderRepository(get(), get(), get())
    }

    single {
        MemoryRepository(get(), get(), get())
    }

    // 独立记忆图仓库（与 legacy 记忆完全解耦，见方案 2026-08-05）
    single {
        MemoryGraphRepository(
            nodeDAO = get(),
            linkDAO = get(),
            database = get(),
            graphVectorStore = get(),
        )
    }

    // 记忆图 P2 语义检索：HNSW 向量索引 + embedding 检索服务
    single { GraphVectorStore(get()) }
    single { VectorProvider(get()) }
    single { MemorySemanticSearch(get(), get(), get()) }

    // 注入选择器（方案 2026-08-06）：轻量 LLM 从整份目录挑 id，取代向量语义检索
    single { MemoryGraphSelector(providerManager = get(), graphRepo = get()) }

    // 记忆图注册表 + 绑定解析（方案 2026-08-07 多图体系阶段一）：
    // registry 管「有哪些图」，resolver 是本轮记忆图配置的唯一运行时真源。
    single {
        MemoryGraphRegistry(
            dao = get(),
            database = get(),
            graphRepo = get(),
            graphVectorStore = get(),
        )
    }
    single { MemoryGraphBindingResolver(registry = get()) }

    // 记忆图 P3：LLM 自动图谱抽取（复用 SubagentRunner 无 UI 跑一轮；只写独立图谱表）+ 轮询调度器
    single {
        MemoryGraphExtractor(
            graphRepo = get(),
            subagentRunner = get(),
            registry = get(),
            bindingResolver = get(),
        )
    }
    single {
        MemoryAutoSaveScheduler(
            scope = get(),
            settingsStore = get(),
            conversationRepo = get(),
            candidateDAO = get(),
            extractor = get(),
            bindingResolver = get(),
        )
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
            baseDir = AppPaths.workspacesDir(context),
            shellRunner = ProotShellRunner(
                nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
                extraBindMounts = listOf(
                    WorkspaceBindMount(
                        source = File(AppPaths.filesDir(context), FileFolders.SKILLS).apply { mkdirs() },
                        target = "/skills",
                    ),
                    WorkspaceBindMount(
                        source = File(AppPaths.filesDir(context), FileFolders.TOOL_OUTPUTS).apply { mkdirs() },
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
        WorkspaceRegistryStore(workspacesDir = AppPaths.workspacesDir(context))
    }

    single {
        val context: Context = get()
        WorkspaceRegistryMigrator(
            registryStore = get(),
            dao = get(),
            manager = get(),
            workspacesDir = AppPaths.workspacesDir(context),
        )
    }

    single {
        WorkspaceRepository(get(), get(), get(), get(), get(), androidContext())
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
            httpClient = get(named(SYNC_HTTP_CLIENT)),
            json = get(),
            r2MediaStore = get(),
            syncAdvancedConfigStore = get(),
            graphVectorStore = get(),
            memoryGraphRegistry = get(),
            syncClock = get(),
        )
    }

    // R2 媒体存取 + 发送链路媒体适配（P3）
    single {
        R2MediaStore(get(), get())
    }
    single {
        AssetResolver(get(), get(), get(), get(), get(), get(), get())
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
    worker {
        ScreenTimeCollectWorker(get(), get(), get())
    }
}
