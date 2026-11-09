package com.docloom_android.core.auth

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.docloom_android.core.model.User
import com.docloom_android.core.network.ApiJson
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

private val Context.sessionDataStore by preferencesDataStore(name = "session")

/** What is persisted between launches. Stored only encrypted. */
@Serializable
data class StoredSession(val token: String, val user: User)

/**
 * Keeps the session JWT encrypted at rest: AES-256-GCM through Tink, with
 * the key wrapped by an Android Keystore master key. The decrypted token is
 * kept in memory so the network interceptor can read it synchronously.
 */
class TokenStore(private val context: Context) {
    @Volatile
    private var cached: StoredSession? = null

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    fun currentToken(): String? = cached?.token

    suspend fun load(): StoredSession? = withContext(Dispatchers.IO) {
        val encoded = context.sessionDataStore.data.first()[SESSION_KEY] ?: return@withContext null
        val session = runCatching {
            val plain = aead.decrypt(Base64.decode(encoded, Base64.NO_WRAP), ASSOCIATED_DATA)
            ApiJson.decodeFromString(StoredSession.serializer(), plain.decodeToString())
        }.getOrNull()
        if (session == null) clear() // unreadable (e.g. key lost): start signed out
        cached = session
        session
    }

    suspend fun save(session: StoredSession) = withContext(Dispatchers.IO) {
        val plain = ApiJson.encodeToString(StoredSession.serializer(), session).encodeToByteArray()
        val encoded = Base64.encodeToString(aead.encrypt(plain, ASSOCIATED_DATA), Base64.NO_WRAP)
        context.sessionDataStore.edit { it[SESSION_KEY] = encoded }
        cached = session
    }

    suspend fun clear() {
        cached = null
        withContext(Dispatchers.IO) {
            context.sessionDataStore.edit { it.remove(SESSION_KEY) }
        }
    }

    private companion object {
        val SESSION_KEY = stringPreferencesKey("session")
        const val KEYSET_NAME = "docloom_session_keyset"
        const val KEYSET_PREFS = "docloom_session_keyset_prefs"
        const val MASTER_KEY_URI = "android-keystore://docloom_session_master_key"
        val ASSOCIATED_DATA = "docloom-session".encodeToByteArray()
    }
}
