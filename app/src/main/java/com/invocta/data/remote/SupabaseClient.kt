package com.invocta.data.remote

import android.util.Log
import com.invocta.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.compose.auth.ComposeAuth
import io.github.jan.supabase.compose.auth.googleNativeLogin
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "SupabaseClient"

/**
 * Singleton Supabase client for authentication and database operations.
 * 
 * Production-grade auth handling:
 * - Caches tokens to avoid SDK StateFlow issues during background operations
 * - JWT tokens have 1 hour lifetime, cache is valid for 55 minutes (5 min safety margin)
 * - SDK handles refresh internally; we just need reliable token access
 * 
 * Known issue: Supabase SDK's sessionStatus StateFlow can get stuck at "Initializing"
 * when the app is in background state (e.g., overlay minimized to bubble).
 * Our caching strategy works around this.
 */
object SupabaseClientProvider {

    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            install(Auth) {
                flowType = FlowType.PKCE
                scheme = "invocta"
                host = "auth-callback"
            }
            install(Postgrest)
            install(ComposeAuth) {
                googleNativeLogin(serverClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID)
            }
        }
    }

    // Token cache to avoid SDK StateFlow issues during background operations
    private data class CachedToken(
        val token: String,
        val expiresAt: Long // Unix timestamp in milliseconds
    )
    
    @Volatile
    private var cachedToken: CachedToken? = null
    
    // Mutex for initialization wait
    private val initMutex = Mutex()
    
    // Track if we've completed initial load from storage
    @Volatile 
    private var initialLoadComplete = false

    /**
     * Get auth token with caching for reliable access during tool execution.
     * 
     * Strategy:
     * 1. Check cache - if valid (>5 min until expiry), return immediately
     * 2. Try SDK directly - if available, cache and return
     * 3. If SDK stuck at Initializing, wait for it (first load only)
     * 4. Return cached token as fallback if SDK is flaky but cache still valid
     */
    suspend fun getAuthToken(): String? {
        // Fast path 1: Check cache first (avoids SDK StateFlow issues)
        val cached = cachedToken
        val now = System.currentTimeMillis()
        val fiveMinutes = 5 * 60 * 1000L
        
        if (cached != null && cached.expiresAt > now + fiveMinutes) {
            // Cache is valid with good margin - use it
            return cached.token
        }
        
        // Fast path 2: Try SDK directly
        val sdkToken = client.auth.currentAccessTokenOrNull()
        if (sdkToken != null) {
            // Got token from SDK - cache it
            cacheTokenFromSession()
            return sdkToken
        }
        
        // SDK returned null - check why
        val status = client.auth.sessionStatus.value
        
        when (status) {
            is SessionStatus.Authenticated -> {
                // Authenticated but no token yet - session might be refreshing
                // Return cached if still valid (even with shorter margin)
                if (cached != null && cached.expiresAt > now) {
                    Log.d(TAG, "Using cached token while SDK refreshes")
                    return cached.token
                }
                // No valid cache, try once more after brief wait
                kotlinx.coroutines.delay(100)
                val retryToken = client.auth.currentAccessTokenOrNull()
                if (retryToken != null) {
                    cacheTokenFromSession()
                    return retryToken
                }
                Log.w(TAG, "Authenticated status but no token available")
                return null
            }
            
            is SessionStatus.NotAuthenticated -> {
                Log.d(TAG, "Not authenticated")
                clearCache()
                return null
            }
            
            is SessionStatus.RefreshFailure -> {
                Log.w(TAG, "Session refresh failed: ${status.cause}")
                // Return cached if still valid - user might still be able to work
                if (cached != null && cached.expiresAt > now) {
                    Log.d(TAG, "Using cached token after refresh failure")
                    return cached.token
                }
                clearCache()
                return null
            }
            
            SessionStatus.Initializing -> {
                // SDK is initializing - need special handling
                return handleInitializing(cached, now)
            }
        }
    }
    
    /**
     * Handle Initializing state - wait for SDK or use cache.
     */
    private suspend fun handleInitializing(cached: CachedToken?, now: Long): String? {
        // If we have valid cache, don't wait for flaky SDK
        if (cached != null && cached.expiresAt > now) {
            Log.d(TAG, "SDK initializing - using cached token")
            return cached.token
        }
        
        // No cache - must wait for initialization (first app launch)
        return waitForInitAndGetToken()
    }
    
    /**
     * Wait for SDK initialization. Only called when we have no cached token.
     */
    private suspend fun waitForInitAndGetToken(): String? {
        // Prevent concurrent waits
        return initMutex.withLock {
            // Double-check after acquiring lock
            val token = client.auth.currentAccessTokenOrNull()
            if (token != null) {
                cacheTokenFromSession()
                initialLoadComplete = true
                return@withLock token
            }
            
            val status = client.auth.sessionStatus.value
            if (status !is SessionStatus.Initializing) {
                initialLoadComplete = true
                return@withLock if (status is SessionStatus.Authenticated) {
                    client.auth.currentAccessTokenOrNull()?.also { cacheTokenFromSession() }
                } else {
                    null
                }
            }
            
            // Wait for initialization (max 3 seconds for first load)
            Log.d(TAG, "Waiting for auth initialization...")
            val startTime = System.currentTimeMillis()
            
            val finalStatus = withTimeoutOrNull(3000L) {
                client.auth.sessionStatus.first { it !is SessionStatus.Initializing }
            }
            
            val elapsed = System.currentTimeMillis() - startTime
            initialLoadComplete = true
            
            when (finalStatus) {
                is SessionStatus.Authenticated -> {
                    val resultToken = client.auth.currentAccessTokenOrNull()
                    if (resultToken != null) {
                        Log.d(TAG, "Auth initialized in ${elapsed}ms - authenticated")
                        cacheTokenFromSession()
                    } else {
                        Log.w(TAG, "Auth initialized but no token")
                    }
                    resultToken
                }
                is SessionStatus.NotAuthenticated -> {
                    Log.d(TAG, "Auth initialized in ${elapsed}ms - not authenticated")
                    null
                }
                is SessionStatus.RefreshFailure -> {
                    Log.w(TAG, "Auth initialized with refresh failure in ${elapsed}ms")
                    null
                }
                null -> {
                    // Timeout - check if SDK has session anyway
                    Log.w(TAG, "Auth init timeout after ${elapsed}ms, checking session...")
                    val fallbackToken = client.auth.currentAccessTokenOrNull()
                    if (fallbackToken != null) {
                        Log.d(TAG, "Found token despite timeout")
                        cacheTokenFromSession()
                    }
                    fallbackToken
                }
                else -> {
                    Log.w(TAG, "Unexpected status: $finalStatus")
                    null
                }
            }
        }
    }
    
    /**
     * Cache token from current session with expiry.
     */
    private fun cacheTokenFromSession() {
        try {
            val session = client.auth.currentSessionOrNull() ?: return
            val token = session.accessToken
            // expiresAt is Instant, convert to epoch millis
            val expiresAt = session.expiresAt.toEpochMilliseconds()
            cachedToken = CachedToken(token, expiresAt)
            Log.d(TAG, "Token cached, expires in ${(expiresAt - System.currentTimeMillis()) / 1000}s")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache token", e)
        }
    }
    
    /**
     * Clear cached token (call on logout).
     */
    private fun clearCache() {
        cachedToken = null
        initialLoadComplete = false
    }
    
    /**
     * Invalidate token cache - call this on logout or when token is known to be invalid.
     * This forces the next getAuthToken() call to fetch fresh from SDK.
     */
    fun invalidateTokenCache() {
        Log.d(TAG, "Token cache invalidated")
        clearCache()
    }

    /**
     * Check if user is authenticated.
     */
    suspend fun isAuthenticated(): Boolean {
        return getAuthToken() != null
    }
    
    /**
     * Wait for auth to be ready and return authentication state.
     */
    suspend fun waitForAuthReady(): Boolean {
        return isAuthenticated()
    }
}

