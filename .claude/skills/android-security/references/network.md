# Network security

## Contents
1. HTTPS and cleartext (debug vs release)
2. HTTP client setup
3. Certificate pinning (optional hardening)
4. Handling responses safely

## 1. HTTPS and cleartext

Since Android 9, cleartext HTTP is blocked by default, which is good. Don't put `android:usesCleartextTraffic="true"` in the main manifest. Allow plain HTTP only for development hosts, and only in debug builds.

`app/src/debug/res/xml/network_security_config.xml` (debug source set only):
```xml
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">10.0.2.2</domain>
        <domain includeSubdomains="false">localhost</domain>
        <!-- add your LAN IP here if testing on a real phone -->
    </domain-config>
</network-security-config>
```

`app/src/main/res/xml/network_security_config.xml` (release and default):
```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system"/>
        </trust-anchors>
    </base-config>
</network-security-config>
```

Manifest `<application>`: `android:networkSecurityConfig="@xml/network_security_config"`. The debug source set overrides the file automatically, so release never ships the cleartext exception.

Never trust user-installed CAs in release (`src="user"`). That's how traffic-interception tools work. If debugging needs a proxy, use a `<debug-overrides>` block, which only applies to debuggable builds.

## 2. HTTP client setup (OkHttp + Retrofit example)

```kotlin
fun buildClient(tokenStore: SecureTokenStore): OkHttpClient =
    OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(tokenStore))     // adds "Authorization: Bearer ..."
        .authenticator(TokenRefreshAuthenticator(...))  // handles 401 -> refresh or logout
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                    else HttpLoggingInterceptor.Level.NONE
            redactHeader("Authorization")
            redactHeader("Cookie")
        })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
```

Rules:
- Even in debug, don't use `Level.BODY` on auth endpoints, because login bodies contain passwords.
- Only attach the Bearer token to requests for **your API host**. Check `request.url.host` against `BuildConfig.API_BASE_URL`'s host, so the token never leaks to third-party URLs (image CDNs, GitHub, and so on).
- Never write a custom `X509TrustManager` that accepts everything, a `HostnameVerifier` that returns `true`, or `sslSocketFactory` hacks. If a dev certificate fails, fix the certificate or use the debug network config.

## 3. Certificate pinning (optional hardening)

Pinning stops interception even if a CA is compromised or a user CA is installed. It's useful for an app handling private company docs, but a wrong pin bricks the app's networking, so:
- Pin the **public key (SPKI) of the intermediate or root CA**, or of your own key, not the leaf certificate, which rotates often.
- Always include **at least one backup pin**.
- Set an expiration so old app versions fail open instead of breaking forever.

```xml
<domain-config>
    <domain includeSubdomains="true">api.docloom.example</domain>
    <pin-set expiration="2027-12-31">
        <pin digest="SHA-256">PRIMARY_BASE64_SPKI_HASH=</pin>
        <pin digest="SHA-256">BACKUP_BASE64_SPKI_HASH=</pin>
    </pin-set>
</domain-config>
```

Recommend pinning only after the production domain and hosting (CDN or proxy) are settled.

## 4. Handling responses safely

- Parse with a strict schema (kotlinx-serialization data classes). On a parse failure, show an error. Don't continue with partial data.
- Treat all server data as untrusted when rendering. Never feed it into a WebView as HTML without sanitizing it, and never use it to build intents or file paths.
- On a `401`, try one refresh. If that fails, clear the session and go to login. On a `403`, show "no access" and don't retry.
- Don't put tokens, emails or other personal data in URLs or query strings, because they end up in server logs and proxies. Use headers or bodies.
