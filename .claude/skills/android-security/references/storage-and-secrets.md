# Storage and secrets

## Contents
1. What may and may not ship in the app
2. Build config without secrets
3. Encrypted token storage (Keystore + Tink + DataStore)
4. Backups and data extraction
5. Files, cache and databases

## 1. What may and may not ship in the app

| May ship in the APK | Must stay on the backend |
|---|---|
| API base URL | `DATABASE_URL`, `DIRECT_URL` |
| Public OAuth client ID | `JWT_SECRET` |
| Feature flags, version info | `GITAPP_CLIENT_SECRET` and any client secret |
| Public keys / certificate pins | Third-party API keys that cost money or grant access |

Obfuscating a secret, splitting it, putting it in NDK code or "encrypting" it with a key that is also in the app does **not** protect it. If the app needs to call a paid or privileged service, add a backend endpoint that calls it.

## 2. Build config without secrets

`app/build.gradle.kts`:

```kotlin
import java.util.Properties

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    buildFeatures { buildConfig = true }
    buildTypes {
        debug {
            buildConfigField(
                "String", "API_BASE_URL",
                "\"${localProps.getProperty("docloom.apiBaseUrl", "http://10.0.2.2:3001")}\""
            )
        }
        release {
            buildConfigField("String", "API_BASE_URL", "\"https://api.docloom.example\"")
        }
    }
}
```

- `local.properties` is already gitignored, so each developer can set their own LAN or tunnel URL there.
- CI injects values through environment variables or Gradle properties, never committed files.

## 3. Encrypted token storage

Don't use plain `SharedPreferences` or files for tokens. `androidx.security:security-crypto` (EncryptedSharedPreferences) is deprecated, so new code should use Tink with a Keystore-protected key and store the ciphertext in DataStore.

Dependencies (add to `libs.versions.toml` with pinned versions):
- `com.google.crypto.tink:tink-android`
- `androidx.datastore:datastore-preferences`

```kotlin
class SecureTokenStore(context: Context) {
    private val aead: Aead = run {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, "docloom_keyset", "docloom_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://docloom_master_key")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }
    private val ds = context.tokenDataStore            // preferencesDataStore("session")
    private val KEY = stringPreferencesKey("access_token")
    private val AD = "docloom-session".toByteArray()    // associated data binds ciphertext to its purpose

    suspend fun save(token: String) {
        val ct = aead.encrypt(token.toByteArray(), AD)
        ds.edit { it[KEY] = Base64.encodeToString(ct, Base64.NO_WRAP) }
    }

    suspend fun read(): String? = ds.data.first()[KEY]?.let {
        runCatching { String(aead.decrypt(Base64.decode(it, Base64.NO_WRAP), AD)) }
            .getOrNull()                                // corrupted or key lost: treat as logged out
    }

    suspend fun clear() = ds.edit { it.remove(KEY) }
}
```

Notes:
- The master key never leaves the Keystore (hardware-backed where available).
- If decryption fails (the app was restored to a new device, or the key was wiped), treat it as logged out. Don't crash.
- Keep tokens in memory only as long as needed. Don't put them in `ViewModel` saved state or `Bundle`s, because those can be written to disk.
- For extra protection on sensitive actions, gate the key with `BiometricPrompt` + `CryptoObject`. Don't use a boolean "biometric passed" flag, because it can be patched out.

## 4. Backups and data extraction

The template ships with `android:allowBackup="true"` and empty rule files, which means the token store **is backed up**. Choose one:

- **Simplest:** `android:allowBackup="false"` in `AndroidManifest.xml`.
- **Keep backup for non-sensitive data:** exclude the session stores in **both** files.

`res/xml/data_extraction_rules.xml` (Android 12+):
```xml
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="sharedpref" path="docloom_keyset_prefs.xml"/>
        <exclude domain="file" path="datastore/session.preferences_pb"/>
    </cloud-backup>
    <device-transfer>
        <exclude domain="sharedpref" path="docloom_keyset_prefs.xml"/>
        <exclude domain="file" path="datastore/session.preferences_pb"/>
    </device-transfer>
</data-extraction-rules>
```
`res/xml/backup_rules.xml` (Android 11 and lower) gets the same excludes inside `<full-backup-content>`.

A restored ciphertext can't be decrypted anyway (the Keystore key doesn't transfer), but excluding the stores avoids confusing half-restored sessions.

## 5. Files, cache and databases

- Use app-private storage (`filesDir`, `cacheDir`). Never use `MODE_WORLD_READABLE` or `MODE_WORLD_WRITEABLE`, or write private data to shared external storage.
- If cached docs or project data are sensitive, consider encrypting a Room database (for example with SQLCipher) and clearing it on logout.
- Share files only through `FileProvider` with specific paths and temporary URI grants. Never expose raw file paths.
- Clear caches of private documents on logout.
