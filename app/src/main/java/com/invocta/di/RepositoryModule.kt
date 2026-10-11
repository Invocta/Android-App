package com.invocta.di

import com.invocta.data.local.InvoctaDatabase
import com.invocta.data.remote.AIClient
import com.invocta.data.remote.ServerProxiedAzureAIClient
import com.invocta.data.remote.api.InvoctaApiClient
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
    // Database (kept for potential local caching in future)
    single { InvoctaDatabase.getInstance(androidContext()) }
    single { get<InvoctaDatabase>().chatSessionDao() }
    single { get<InvoctaDatabase>().chatMessageDao() }

    // API Client for server communication
    single { InvoctaApiClient() }

    // AI Client (proxied through server - no API keys needed on client)
    single<AIClient> { ServerProxiedAzureAIClient(get()) }

    // Tool Registry with all available tools
    single<ToolRegistry> { ToolProvider.createToolRegistry(androidContext()) }

    // Repositories
    single<AuthRepository> { AuthRepositoryImpl() }
    single<ChatSessionRepository> { ChatSessionRepositoryImpl(androidContext(), get(), get(), get()) }
    single<PreferencesRepository> { PreferencesRepositoryImpl(androidContext()) }

    // ViewModels
    // ChatViewModel is a singleton to preserve state across activity recreations
    // (especially for AssistantOverlayActivity after tool execution)
    single { ChatViewModel(get(), get()) }
    viewModel { OnboardingViewModel(get(), get()) }
    viewModel { SettingsViewModel(androidContext() as android.app.Application, get(), get(), get()) }
}
