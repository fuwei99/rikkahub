package me.rerere.rikkahub.di

import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import kotlinx.serialization.json.Json
import me.rerere.highlight.Highlighter
import me.rerere.rikkahub.AppScope
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
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val appModule = module {
    singleOf(::AppScope)
    singleOf(::AppEventBus)
    singleOf(::TTSManager)

    single {
        ChatNotificationManager(
            context = get(),
            conversationRepo = get()
        )
    }

    single {
        UpdateChecker(
            context = get(),
            okHttpClient = get()
        )
    }

    single {
        Highlighter(
            context = get()
        )
    }

    single {
        EmojiUtils(
            emojiData = EmojiData(
                context = get(),
                json = get()
            )
        )
    }

    single {
        SoundEffectPlayer(
            context = get(),
            settingsStore = get()
        )
    }

    single {
        LocalTools(
            context = get(),
            appScope = get(),
            eventBus = get(),
            settingsStore = get(),
        )
    }

    single<SubagentRunner> {
        SubagentRunner(generationHandler = get())
    }

    single<SubagentTemplateManager> {
        SubagentTemplateManager(context = get(), json = JsonInstant)
    }

    single<SubagentJobManager> {
        SubagentJobManager(runner = get())
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
            subagentTemplateManager = get()
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
            filesManager = get()
        )
    }
}
