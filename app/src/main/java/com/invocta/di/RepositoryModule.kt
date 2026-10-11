package com.invocta.di

import com.invocta.data.local.InvoctaDatabase
import com.invocta.data.local.ai.LocalGemmaModelManager
import com.invocta.data.remote.AIClient
import com.invocta.data.remote.GoogleAiStudioAIClient
import com.invocta.data.remote.GoogleAiStudioKeyStore
import com.invocta.data.remote.OpenAiAIClient
import com.invocta.data.remote.OpenAiKeyStore
import com.invocta.data.remote.SelectedAIClient
import com.invocta.data.local.ai.LocalGemmaAIClient
import com.invocta.data.repository.AuthRepositoryImpl
import com.invocta.data.repository.ChatSessionRepositoryImpl
import com.invocta.data.repository.PreferencesRepositoryImpl
import com.invocta.domain.repository.AuthRepository
import com.invocta.domain.repository.ChatSessionRepository
import com.invocta.domain.repository.PreferencesRepository
import com.invocta.domain.tools.ToolProvider
import com.invocta.domain.tools.ToolRegistry
import com.invocta.ui.screens.chat.ChatViewModel
import com.invocta.ui.screens.onboarding.OnboardingViewModel
import com.invocta.ui.screens.settings.SettingsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single { LocalGemmaModelManager(androidContext()) }

    // Local-mode chat storage.
    single { InvoctaDatabase.getInstance(androidContext()) }
    single { get<InvoctaDatabase>().chatSessionDao() }
    single { get<InvoctaDatabase>().chatMessageDao() }

    single { GoogleAiStudioKeyStore(androidContext()) }
    single { OpenAiKeyStore(androidContext()) }
    single<AIClient> { SelectedAIClient(get(), LocalGemmaAIClient(get()), GoogleAiStudioAIClient(get()), OpenAiAIClient(get())) }

    // Tool Registry with all available tools
    single<ToolRegistry> { ToolProvider.createToolRegistry(androidContext()) }

    // Repositories
    single<AuthRepository> { AuthRepositoryImpl() }
    single<PreferencesRepository> { PreferencesRepositoryImpl(androidContext()) }
    single<ChatSessionRepository> { ChatSessionRepositoryImpl(androidContext(), get(), get(), get(), get(), get()) }

    // ViewModels
    // ChatViewModel is a singleton to preserve state across activity recreations
    // (especially for AssistantOverlayActivity after tool execution)
    single { ChatViewModel(get(), get(), get()) }
    viewModel { OnboardingViewModel(get(), get(), get()) }
    viewModel { SettingsViewModel(androidContext() as android.app.Application, get(), get(), get(), get(), get(), get()) }
}
