package com.invocta

import android.app.Application
import com.invocta.di.appModule
import com.invocta.ui.screens.chat.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.getKoin
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class InvoctaApplication : Application() {

    // Application-scoped coroutine scope for background initialization
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidLogger()
            androidContext(this@InvoctaApplication)
            modules(appModule)
        }

        // Eagerly initialize critical singletons in background to avoid blocking
        // assistant gesture launch
        applicationScope.launch {
            try {
                // Warm up the ChatViewModel and its dependencies
                // This will initialize: Database, ToolRegistry, ChatSessionRepository
                getKoin().get<ChatViewModel>()
            } catch (_: Exception) {
                // Ignore initialization errors - they'll be handled when actually used
            }
        }
    }
}
