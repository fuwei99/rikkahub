package me.rerere.rikkahub

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.runtime.Composer
import androidx.compose.runtime.tooling.ComposeStackTraceMode
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.AppPaths
import me.rerere.rikkahub.data.files.LegacyDataMigrator
import java.io.File
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import androidx.lifecycle.ProcessLifecycleOwner
import me.rerere.common.android.appTempFolder
import com.whl.quickjs.android.QuickJSLoader
import me.rerere.rikkahub.di.appModule
import me.rerere.rikkahub.di.dataSourceModule
import me.rerere.rikkahub.di.repositoryModule
import me.rerere.rikkahub.di.viewModelModule
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.WebServerService
import me.rerere.rikkahub.utils.CrashHandler
import me.rerere.rikkahub.utils.DatabaseUtil
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.registry.WorkspaceRegistryMigrator
import me.rerere.rikkahub.data.sync.core.SyncEngine
import me.rerere.rikkahub.data.sync.core.SyncLifecycleObserver
import me.rerere.rikkahub.data.workspace.WorkspaceScheduledProcessManager
import me.rerere.rikkahub.data.screentime.ScreenTimeCollectWorker
import me.rerere.rikkahub.focus.FocusPolicyEngine
import me.rerere.workspace.WorkspaceManager
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin

private const val TAG = "RikkaHubApp"

const val CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID = "chat_completed"
const val CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID = "chat_live_update"
const val WEB_SERVER_NOTIFICATION_CHANNEL_ID = "web_server"
const val WORKSPACE_PROCESS_NOTIFICATION_CHANNEL_ID = "workspace_process"

class RikkaHubApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // 启动期崩溃诊断（2026-08-09）：把 CrashHandler 装到最前面，
        // 这样 LegacyDataMigrator / startKoin / DI 图构建阶段崩也能落盘
        // （之前 CrashHandler 在 startKoin 之后，Koin/DI 阶段崩溃什么都写不下来）。
        runCatching { CrashHandler.install(this) }
            .onFailure { android.util.Log.e("BOOT_DIAG", "CrashHandler.install failed", it) }
        bootStage("enter onCreate")

        // 数据根目录迁移（data/data -> Android/data）：必须在 Koin / Room / DataStore 初始化之前
        bootStage("before LegacyDataMigrator")
        LegacyDataMigrator.migrate(this)
        bootStage("after LegacyDataMigrator")

        bootStage("before startKoin")
        startKoin {
            androidLogger()
            androidContext(this@RikkaHubApp)
            workManagerFactory()
            modules(appModule, viewModelModule, dataSourceModule, repositoryModule)
        }
        bootStage("after startKoin")

        this.createNotificationChannel()

        // set cursor window size to 32MB
        DatabaseUtil.setCursorWindowSize(32 * 1024 * 1024)

        // init file logging
        bootStage("before initLogDir")
        me.rerere.common.android.Logging.initLogDir(AppPaths.filesDir(this))
        // AI 请求线路日志（实际发给 LLM 的 header/payload/response，文件在 filesDir/logs/ai_wire.log）
        me.rerere.common.android.AiWireLog.init(AppPaths.filesDir(this))
        // 记忆图链路专项调试日志（纯旁路，文件在 filesDir/logs/memory_graph_debug.log）
        me.rerere.common.android.MemoryGraphDebugLog.init(AppPaths.filesDir(this))
        // 工具调用链路专项调试日志（纯旁路，文件在 filesDir/logs/tool_call_debug.log）
        me.rerere.common.android.ToolCallDebugLog.init(AppPaths.filesDir(this))

        // Init QuickJS native library
        bootStage("before QuickJSLoader.init")
        QuickJSLoader.init()
        bootStage("after QuickJSLoader.init")

        // delete temp files
        deleteTempFiles()

        // cleanup stale tool output files
        cleanupToolOutputs()

        // cleanup workspace temp dirs (proot + rootfs /tmp)
        cleanupWorkspaceTempDirs()

        // migrate workspace registry from Room DB to json file
        bootStage("before migrateWorkspaceRegistry")
        migrateWorkspaceRegistry()
        bootStage("after migrateWorkspaceRegistry")

        // check workspace integrity (mark workspaces with missing files as broken after backup restore)
        checkWorkspaceIntegrity()

        // sync managed files to DB
        bootStage("before syncManagedFiles")
        syncManagedFiles()
        bootStage("after syncManagedFiles")

        // keep avatars in a dedicated folder, independent from chat uploads
        migrateAvatarFiles()

        // install built-in skills (no sync enqueue; users can enable them per assistant)
        installBuiltinSkills()

        // Start WebServer if enabled in settings
        bootStage("before startWebServerIfEnabled")
        startWebServerIfEnabled()
        bootStage("after startWebServerIfEnabled")

        // Increment launch count
        incrementLaunchCount()

        // 云锚点同步：注册前后台生命周期挂钩（P1）
        registerSyncLifecycleHook()

        // 跨设备屏幕时间（方案 2026-08-09）：启动采集链（立即采一发 + 每 10 分钟续发）
        bootStage("before startScreenTimeCollector")
        startScreenTimeCollector()
        bootStage("after startScreenTimeCollector")

        // 工作区计划进程：读取 workspace 内配置并按时间窗口拉起 shell 进程
        startWorkspaceScheduledProcesses()

        // 记忆图 P3：启动记忆自动提炼轮询器（60s tick，候选攒批后才调 LLM）
        startMemoryAutoSaveScheduler()

        // 记忆日志配置（开关/清理策略）随设置同步，不硬编码
        applyMemoryLogSettings()

        // Schedule Agent（定时任务）：确保默认查岗模板存在 + 重启后恢复闹钟
        bootStage("before startScheduleAgents")
        startScheduleAgents()
        startSupervisionWatcher()
        startFocusLockWatcher()
        bootStage("after startScheduleAgents")

        bootStage("onCreate complete")
        // Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.Auto)
    }

    /**
     * 启动期诊断：把当前阶段同时写进 logcat 和 files/boot_diag.log，
     * 这样崩溃时即使 logcat 缓冲被清掉也能从文件定位到挂在哪个阶段。
     */
    private fun bootStage(stage: String) {
        android.util.Log.i("BOOT_DIAG", stage)
        runCatching {
            val file = java.io.File(AppPaths.filesDir(this), "boot_diag.log")
            file.appendText(
                "${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                    .format(java.util.Date())}  $stage\n"
            )
        }
    }

    private fun applyMemoryLogSettings() {
        runCatching {
            get<AppScope>().launch(Dispatchers.IO) {
                // 常驻订阅：设置变更（含设置页修改、云端同步）即时生效
                get<SettingsStore>().settingsFlowRaw.collect { settings ->
                    val cfg = settings.memoryLog.sanitized()
                    me.rerere.common.android.MemoryGraphDebugLog.configure(
                        enabled = cfg.enabled,
                        maxAgeHours = cfg.maxAgeHours,
                        maxLines = cfg.maxLines,
                        keepBackups = cfg.keepBackups,
                    )
                    val reqCfg = settings.requestLog.sanitized()
                    me.rerere.common.android.AiWireLog.configure(
                        enabled = reqCfg.enabled,
                        maxAgeHours = reqCfg.maxAgeHours,
                        maxBodyChars = reqCfg.maxBodyChars,
                        includeResponseBody = reqCfg.includeResponseBody,
                    )
                    // 工具调用日志：总开关 + 每工具子开关（当前只有 ask_user 通道）
                    val toolCfg = settings.toolLog.sanitized()
                    me.rerere.common.android.ToolCallDebugLog.configure(
                        enabled = toolCfg.enabled,
                        channels = toolCfg.enabledChannels,
                        maxAgeHours = toolCfg.maxAgeHours,
                        maxLines = toolCfg.maxLines,
                        keepBackups = toolCfg.keepBackups,
                    )
                }
            }
        }.onFailure { Log.e(TAG, "applyMemoryLogSettings failed", it) }
    }

    private fun startMemoryAutoSaveScheduler() {
        runCatching {
            val scheduler = get<me.rerere.rikkahub.data.ai.memory.MemoryAutoSaveScheduler>()
            get<AppScope>().launch(Dispatchers.IO) {
                // 给数据库与 DataStore 初始化留时间
                delay(3000L)
                runCatching { scheduler.start() }
                    .onFailure { Log.e(TAG, "startMemoryAutoSaveScheduler failed", it) }
            }
        }.onFailure { Log.e(TAG, "startMemoryAutoSaveScheduler init failed", it) }
    }

    /**
     * Schedule Agent（定时任务）：启动时补默认模板 + 恢复 AlarmManager 闹钟
     * （进程被杀 / 重启后靠这里 + BootReceiver 恢复，PLAN_SCHEDULE_AGENTS §3.1/§6）。
     */
    private fun startScheduleAgents() {
        runCatching {
            get<AppScope>().launch(Dispatchers.IO) {
                delay(3000L)
                runCatching { get<me.rerere.rikkahub.data.ai.schedule.ScheduleAgentManager>().ensureDefault() }
                    .onFailure { Log.e(TAG, "schedule agent ensureDefault failed", it) }
                runCatching { get<me.rerere.rikkahub.data.ai.schedule.ScheduleAgentScheduler>().rescheduleAll() }
                    .onFailure { Log.e(TAG, "schedule agent rescheduleAll failed", it) }
            }
        }.onFailure { Log.e(TAG, "startScheduleAgents init failed", it) }
    }

    /**
     * 专注监督时段观察者：时段开始时自动把当前助手切回学习助手
     * （isActiveNow 是纯时间函数，没有事件源，只能采样，2026-08-18）。
     */
    private fun startSupervisionWatcher() {
        runCatching {
            get<AppScope>().launch(Dispatchers.IO) {
                delay(3000L)
                get<me.rerere.rikkahub.data.ai.schedule.SupervisionWatcher>().run()
            }
        }.onFailure { Log.e(TAG, "startSupervisionWatcher init failed", it) }
    }

    private fun startFocusLockWatcher() {
        runCatching {
            get<AppScope>().launch(Dispatchers.IO) {
                get<SettingsStore>().settingsFlow.collect { settings ->
                    FocusPolicyEngine.updateSettings(settings.focusLock)
                }
            }
        }.onFailure { Log.e(TAG, "startFocusLockWatcher init failed", it) }
    }

    private fun startScreenTimeCollector() {
        runCatching { ScreenTimeCollectWorker.start(this) }
            .onFailure { Log.e(TAG, "startScreenTimeCollector failed", it) }
    }

    private fun registerSyncLifecycleHook() {
        runCatching {
            val engine = get<SyncEngine>()
            // 分叉另存后通知 UI：在这里接线而非让 SyncEngine 直接依赖 ChatService，
            // 避免 Koin 循环依赖（ChatService 侧也持有同步相关组件）。
            engine.onConversationForked = { conversationId, branchTitle ->
                runCatching {
                    val uuid = kotlin.uuid.Uuid.parse(conversationId)
                    get<me.rerere.rikkahub.service.ChatService>().notifyMergeBranch(uuid, branchTitle)
                }.onFailure { Log.w(TAG, "notify merge branch failed", it) }
            }
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                SyncLifecycleObserver(
                    context = this,
                    engine = engine,
                    appScope = get(),
                    database = get(),
                    syncAdvancedConfigStore = get(),
                )
            )
        }.onFailure { Log.e(TAG, "registerSyncLifecycleHook failed", it) }
    }

    private fun startWorkspaceScheduledProcesses() {
        get<AppScope>().launch(Dispatchers.IO) {
            val manager = get<WorkspaceScheduledProcessManager>()
            // 给数据库与 Proot 存储留 2 秒初始化就绪时间，随后立即触发拉起
            delay(2000L)
            runCatching { manager.reconcileAll() }
                .onFailure { Log.e(TAG, "workspace scheduled process initial reconcile failed", it) }
            while (true) {
                delay(15_000L)
                runCatching { manager.reconcileAll() }
                    .onFailure { Log.e(TAG, "workspace scheduled process reconcile failed", it) }
            }
        }
    }

    private fun incrementLaunchCount() {
        get<AppScope>().launch {
            runCatching {
                val store = get<SettingsStore>()
                val current = store.settingsFlowRaw.first()
                store.update(current.copy(launchCount = current.launchCount + 1))
                Log.i(TAG, "incrementLaunchCount: ${store.settingsFlowRaw.first().launchCount}")
            }.onFailure {
                Log.e(TAG, "incrementLaunchCount failed", it)
            }
        }
    }

    private fun cleanupWorkspaceTempDirs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceManager>().cleanupAllTempDirs()
            }.onFailure {
                Log.e(TAG, "cleanupWorkspaceTempDirs failed", it)
            }
        }
    }

    private fun migrateWorkspaceRegistry() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceRegistryMigrator>().migrateIfNeeded()
            }.onFailure {
                Log.e(TAG, "migrateWorkspaceRegistry failed", it)
            }
        }
    }

    private fun checkWorkspaceIntegrity() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceRepository>().checkIntegrity()
            }.onFailure {
                Log.e(TAG, "checkWorkspaceIntegrity failed", it)
            }
        }
    }

    private fun deleteTempFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            val dir = appTempFolder
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }

    private fun cleanupToolOutputs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val dir = File(AppPaths.filesDir(this@RikkaHubApp), FileFolders.TOOL_OUTPUTS)
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            }
        }
    }

    private fun syncManagedFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val filesManager = get<FilesManager>()
                filesManager.syncFolder(FileFolders.UPLOAD)
                filesManager.syncFolder(FileFolders.AI_READ_IMAGES)
                filesManager.syncFolder(FileFolders.AVATARS)
                filesManager.syncFolder(FileFolders.IMAGES)
                filesManager.syncFolder(FileFolders.LLM_PREVIEWS)
            }.onFailure {
                Log.e(TAG, "syncManagedFiles failed", it)
            }
        }
    }

    private fun installBuiltinSkills() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching { get<SkillManager>().installBuiltinSkills() }
                .onFailure { Log.e(TAG, "installBuiltinSkills failed", it) }
        }
    }

    private fun migrateAvatarFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val store = get<SettingsStore>()
                val filesManager = get<FilesManager>()
                val current = store.settingsFlowRaw.first()
                val migrated = filesManager.migrateAvatarsToAvatarFolder(current)
                if (migrated != current) {
                    store.update(migrated)
                    filesManager.syncFolder(FileFolders.AVATARS)
                    Log.i(TAG, "migrateAvatarFiles: migrated avatars to dedicated folder")
                }
            }.onFailure {
                Log.e(TAG, "migrateAvatarFiles failed", it)
            }
        }
    }

    private fun startWebServerIfEnabled() {
        get<AppScope>().launch {
            runCatching {
                delay(500)
                val settings = get<SettingsStore>().settingsFlowRaw.first()
                if (settings.webServerEnabled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this@RikkaHubApp,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: notification permission not granted, skipping")
                        return@launch
                    }
                    if (Build.VERSION.SDK_INT >= 37 &&
                        !settings.webServerLocalhostOnly &&
                        ContextCompat.checkSelfPermission(
                            this@RikkaHubApp,
                            android.Manifest.permission.ACCESS_LOCAL_NETWORK
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: local network permission not granted, skipping")
                        return@launch
                    }
                    val intent = Intent(this@RikkaHubApp, WebServerService::class.java).apply {
                        action = WebServerService.ACTION_START
                        putExtra(WebServerService.EXTRA_PORT, settings.webServerPort)
                        putExtra(WebServerService.EXTRA_LOCALHOST_ONLY, settings.webServerLocalhostOnly)
                    }
                    startForegroundService(intent)
                }
            }.onFailure {
                Log.e(TAG, "startWebServerIfEnabled failed", it)
            }
        }
    }

    private fun createNotificationChannel() {
        val notificationManager = NotificationManagerCompat.from(this)
        val chatCompletedChannel = NotificationChannelCompat
            .Builder(
                CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
            .setName(getString(R.string.notification_channel_chat_completed))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(chatCompletedChannel)

        val chatLiveUpdateChannel = NotificationChannelCompat
            .Builder(
                CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            )
            .setName(getString(R.string.notification_channel_chat_live_update))
            .setVibrationEnabled(false)
            .build()
        notificationManager.createNotificationChannel(chatLiveUpdateChannel)

        val webServerChannel = NotificationChannelCompat
            .Builder(WEB_SERVER_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_web_server))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(webServerChannel)

        val workspaceProcessChannel = NotificationChannelCompat
            .Builder(WORKSPACE_PROCESS_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName("Workspace processes")
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(workspaceProcessChannel)
    }

    override fun onTerminate() {
        super.onTerminate()
        get<AppScope>().cancel()
        stopService(Intent(this, WebServerService::class.java))
    }
}

class AppScope : CoroutineScope by CoroutineScope(
    SupervisorJob()
        + Dispatchers.Main
        + CoroutineName("AppScope")
        + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "AppScope exception", e)
    }
)
