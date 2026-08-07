package me.rerere.rikkahub.di

import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import kotlinx.serialization.json.Json
import me.rerere.highlight.Highlighter
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.agent.AgentBridge
import me.rerere.rikkahub.data.ai.agent.AgentInboxStore
import me.rerere.rikkahub.data.ai.subagent.SubagentJobManager
import me.rerere.rikkahub.data.ai.subagent.SubagentRunner
import me.rerere.rikkahub.data.ai.subagent.SubagentTemplateManager
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.service.ChatNotificationManager
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.SoundEffectPlayer
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.web.WebServerManager
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single {
        Highlighter(get())
    }

    single {
        AppEventBus()
    }

    single {
        LocalTools(get(), get(), get(), get(), get(), get(), get())
    }

    single {
        UpdateChecker(get())
    }

    single {
        AppScope()
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    single {
        Firebase.crashlytics
    }

    single {
        Firebase.analytics
    }

    single {
        SoundEffectPlayer(get())
    }

    // 生成通知与业务解耦：ChatService 只发事件，通知由这里消费；
    // createdAtStart 保证进程启动即订阅，否则后台生成的事件会因无订阅者而丢失
    single(createdAtStart = true) {
        ChatNotificationManager(
            context = get(),
            appScope = get(),
            eventBus = get(),
            settingsStore = get(),
        )
    }

    single {
        SubagentRunner(generationHandler = get())
    }

    single {
        SubagentTemplateManager(context = get(), json = get())
    }

    single {
        SubagentJobManager(runner = get())
    }

    // 收件箱存储门面：通信内核的唯一真相源（方案 2026-08-07「多 Agent 通信内核」Step 2）
    single {
        AgentInboxStore(dao = get())
    }

    // 「对话即 Agent」编排核心：只依赖仓库/DAO，不依赖 ChatService
    // （ChatService 在 init 里 attach 窄接口回来，避免 Koin 循环依赖）
    single {
        AgentBridge(
            conversationRepo = get(),
            folderRepo = get(),
            agentSessionDao = get(),
            inboxStore = get(),
            templateManager = get(),
            settingsStore = get(),
            appScope = get(),
            appEventBus = get(),
        )
    }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            memoryRepository = get(),
            generationHandler = get(),
            templateTransformer = get(),
            providerManager = get(),
            localTools = get(),
            mcpManager = get(),
            filesManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
            folderRepository = get(),
            subagentRunner = get(),
            subagentJobManager = get(),
            subagentTemplateManager = get(),
            agentBridge = get(),
            agentSessionDao = get(),
            agentInboxStore = get(),
            syncLockManager = get(),
            mediaResolver = get(),
            candidateDAO = get(),
            memoryGraphBindingResolver = get(),
        )
    }

    single {
        WebServerManager(
            context = get(),
            appScope = get(),
            chatService = get(),
            conversationRepo = get(),
            folderRepo = get(),
            settingsStore = get(),
            filesManager = get(),
            agentBridge = get()
        )
    }
}
