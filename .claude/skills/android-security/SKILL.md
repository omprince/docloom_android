---
name: android-security
description: Security rules and audit workflow for the DocLoom Android app (Kotlin + Jetpack Compose, talking to the DocLoom Rust backend API). Use this skill whenever writing, changing or reviewing Android code in this project that touches login, tokens, sessions, networking, API calls, storage, DataStore/SharedPreferences, the AndroidManifest, intents, deep links, OAuth/GitHub sign-in, WebViews, permissions, Gradle build config, signing, R8/ProGuard, logging or release builds, and whenever the user asks for a security review, audit, hardening, "is this secure", OWASP/MASVS checks or pre-release checks, even if they don't say the word "security".
---

# Android security for DocLoom

This skill keeps the DocLoom Android app secure while it is built from scratch. Use it in two ways:

- **While writing code:** follow the rules below, so new code is secure by default instead of being fixed later.
- **During a review or audit:** run the workflow in "Auditing", and report findings in the format given there.

## The architecture this skill protects

```
Android app  --HTTPS-->  DocLoom Rust API (/api/...)  -->  Neon Postgres
```

- **The app never talks to the database.** `DATABASE_URL`, `JWT_SECRET`, `GITAPP_CLIENT_SECRET` and every other server secret stay on the backend only. Anything shipped in an APK can be extracted with free tools in minutes, so a secret in the app is a published secret. If a feature seems to need a secret in the app, the right fix is a new backend endpoint.
- **The app's only config is non-secret.** The API base URL comes from `BuildConfig.API_BASE_URL`, set per build type in `app/build.gradle.kts`. Debug uses `http://10.0.2.2:3001` (emulator) or a LAN or tunnel URL, and release must be `https://`.
- **The backend is the security boundary.** Client-side checks are for user experience only. Every permission (project, team or document access) must be enforced by the API, because an attacker can call the API directly without the app.
- **Auth:** the web uses a JWT in the `docloom_session` cookie. Mobile sends `Authorization: Bearer <token>` instead. If the backend still reads the cookie only, flag that `auth/current_user.rs` needs to accept the Bearer header, and that login needs to return the token in the response body.

## Core rules (apply these while writing any code)

Each rule has a reason, and the reason helps with cases the rule doesn't name. Details and code samples are in the reference files listed in the next section.

1. **No secrets in source, resources, BuildConfig or Git.** API keys, client secrets, DB URLs, signing passwords and keystores all count. Local values go in `local.properties` (gitignored), and CI values go in CI secrets. *Why: decompiling an APK is trivial.*
2. **Tokens live in Keystore-backed encrypted storage, never in plain SharedPreferences or files.** Encrypt them with an AES-GCM key from the Android Keystore, for example Tink with DataStore. *Why: rooted devices, backups and malware can read plain prefs.* Don't add `androidx.security:security-crypto` (EncryptedSharedPreferences) to new code, because it is deprecated.
3. **HTTPS only in release.** Allow cleartext only in a **debug-only** network security config scoped to dev hosts. Never write a trust-all `TrustManager` or `HostnameVerifier`, even "temporarily".
4. **Least exposure in the manifest.** Set `exported="false"` unless a component must be public. Request minimal permissions, and verify App Links with `autoVerify`. Back up nothing sensitive: set `allowBackup="false"`, or make backup and data-extraction rules exclude the token store.
5. **OAuth (GitHub sign-in) uses Custom Tabs with PKCE and `state`.** Never use a WebView for it. The redirect returns through a verified App Link (or a custom scheme as a fallback) and the `state` value is checked. The client secret stays on the backend.
6. **Never log sensitive data.** That covers tokens, passwords, emails, auth headers and full request or response bodies. Use a logging setup that is silent in release, and set OkHttp's logging interceptor to `NONE` in release. *Why: logcat and crash reports leak.*
7. **Validate everything that enters the app.** That covers deep link parameters, intent extras, API responses (fail closed on parse errors) and clipboard data. Use explicit intents, and `PendingIntent.FLAG_IMMUTABLE` unless mutability is required.
8. **WebViews (only if unavoidable):** keep JavaScript off unless needed, set `allowFileAccess=false`, never call `addJavascriptInterface` with untrusted content, and load only an allowlist of HTTPS hosts.
9. **Protect sensitive screens.** Use `FLAG_SECURE` on screens showing tokens or private docs, set `filterTouchesWhenObscured` on critical buttons (to block tapjacking), and mark copied secrets as sensitive on the clipboard.
10. **Release builds are hardened.** Turn on R8 shrinking and obfuscation (in this project: `optimization { enable = true }` in `release`), keep `debuggable` off, use Play App Signing, keep keystores out of the repo, and pin dependency versions in `libs.versions.toml`.
11. **Sessions are short and revocable.** Use short-lived access tokens with refresh-token rotation. Logout clears local tokens **and** calls the backend to revoke them. On a 401, clear the session and return to login.
12. **Don't build security on client-side tricks.** Root, emulator and debugger detection only add friction. For real device or app integrity, use the Play Integrity API, verified **on the backend**.

## Reference files (read the one that matches the task)

| Task touches… | Read |
|---|---|
| Tokens, DataStore, prefs, files, backups, secrets in Gradle | `references/storage-and-secrets.md` |
| Retrofit/OkHttp/Ktor, HTTPS, cleartext, certificate pinning | `references/network.md` |
| Login, Bearer tokens, refresh, logout, GitHub OAuth, deep links | `references/auth-and-session.md` |
| Manifest, intents, PendingIntent, WebView, clipboard, screens | `references/platform-components.md` |
| Gradle release config, R8, signing, dependencies, CI, release checklist | `references/build-and-release.md` |

## Auditing (when asked to review, audit or harden)

1. **Run the quick scan** for fast, deterministic hits:
   ```bash
   bash .claude/skills/android-security/scripts/quick_scan.sh <path-to-android-project>
   ```
   It greps for known-bad patterns, so treat each hit as a lead to confirm, not a final verdict. It misses logic issues.
2. **Read the code** that the scan can't judge: auth flow, token storage, network client setup, manifest, `build.gradle.kts`, deep link handlers and any WebView. Check each area against the core rules and the matching reference file.
3. **Check the backend contract** where the app depends on it. Flag any place where the app assumes the server enforces something, such as permissions, token expiry or rate limits, so the user can verify it in the Rust code.
4. **Report** in this format, sorted by severity:

```
## Android security review: <scope>

| # | Severity | Issue | Location | Fix |
|---|----------|-------|----------|-----|
| 1 | High | Auth token stored in plain SharedPreferences | app/src/main/java/.../TokenStore.kt:22 | Encrypt with a Keystore AES-GCM key (Tink + DataStore) |

**Summary:** <1–3 sentences: overall state, most urgent fix>
**Not checked:** <anything out of scope or not verifiable, e.g. backend enforcement, dynamic testing>
```

Severity guide:
- **Critical:** a secret or DB credential is in the app, a trust-all TLS check exists, or auth can be bypassed.
- **High:** tokens are stored in plaintext, cleartext traffic is allowed in release, an exported component lacks protection, or a token is logged.
- **Medium:** backup includes sensitive data, R8 is off in release, there's no PKCE or `state` check, or a PendingIntent is mutable.
- **Low / Info:** hardening opportunities, such as pinning, FLAG_SECURE, or Play Integrity.

Then offer to fix the issues, starting with the highest severity. When fixing, change the code and also explain the reason in one line, so the user learns the pattern.

## When writing new features

Before you finish any feature that touches the areas in the description, do a short self-check against the core rules. Mention in your reply which rules applied, for example: "Token stored via `SecureTokenStore` (rule 2); no logging of response body (rule 6)." That keeps security visible without long lectures.
