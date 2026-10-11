package com.invocta.data.remote

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class OpenAiKeyStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "openai_api_key",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun get(): String? = preferences.getString("api_key", null)
    fun save(key: String) { preferences.edit().putString("api_key", key.trim()).apply() }
    fun clear() { preferences.edit().remove("api_key").apply() }
}
