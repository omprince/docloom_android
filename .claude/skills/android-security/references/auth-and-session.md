# Authentication and sessions

## Contents
1. DocLoom backend contract for mobile
2. Email/password login
3. Token refresh and logout
4. GitHub OAuth (Custom Tabs + PKCE)
5. Deep links and App Links
6. Biometrics and re-authentication

## 1. DocLoom backend contract for mobile

The Rust backend (`Docloom_website/docloom_website_be/src/auth/`) currently:
- issues a JWT in the `docloom_session` cookie (`cookie.rs`), and
- reads the identity from that cookie (`current_user.rs`).

For mobile, the backend should:
1. **Accept `Authorization: Bearer <jwt>`** in `current_user.rs`, alongside the cookie.
2. **Return the token(s) in the JSON body** of `/api/auth/login` when the client asks for it (for example with a header `X-Client: android` or a separate `/api/auth/mobile/login`), because native apps shouldn't rely on cookie jars.
3. Ideally issue a **short-lived access token** (around 15 minutes) and a **refresh token** that rotates on every use and can be revoked server-side. Logout should revoke it.
4. Keep the existing **rate limiting** (`auth/rate_limit.rs`) on login, forgot-password and token refresh for mobile clients too.

When reviewing app code, flag any assumption that depends on these and isn't yet implemented on the backend.

## 2. Email/password login

- Send credentials only over HTTPS in a POST body. Never put them in a query string or log them.
- Don't store the password, even encrypted. Store only tokens (see `storage-and-secrets.md`).
- Clear password fields from state once login completes. In Compose, don't keep the password in `rememberSaveable`, because it would be saved to the instance state Bundle.
- Use `KeyboardType.Password` and turn off autocorrect and suggestions on password fields. Support password managers (autofill hints `ContentType.Password`, `ContentType.EmailAddress`). Autofill is good for security because users pick stronger passwords.
- Show generic error messages ("Email or password is incorrect") so attackers can't tell which accounts exist.

## 3. Token refresh and logout

```kotlin
class TokenRefreshAuthenticator(...) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) { sessionManager.logout(); return null } // avoid loops
        val newToken = runBlocking { sessionManager.refreshOrNull() } ?: run {
            sessionManager.logout(); return null
        }
        return response.request.newBuilder()
            .header("Authorization", "Bearer $newToken").build()
    }
}
```

- Serialize refreshes with a mutex, so ten parallel 401s cause one refresh, not ten. If refresh tokens rotate, parallel refreshes would invalidate each other.
- **Logout** has three steps: call the backend revoke endpoint (best effort), clear the token store, then clear cached private data (Room or files) and in-memory state. Then navigate to login and clear the back stack.
- Don't consider a session valid only because a token exists locally. Validate it with `/api/auth/me` at app start or on the first API call.

## 4. GitHub OAuth (Custom Tabs + PKCE)

DocLoom uses a GitHub App (`/api/github/connect`, `/api/github/callback`). On mobile:

- Open the auth URL in **Custom Tabs** (`androidx.browser`), never a WebView. A WebView lets the app read the user's GitHub password, and GitHub may block it.
- Use **PKCE** (`code_verifier` + `code_challenge`, S256) and a random **`state`**. Generate both with `SecureRandom`, and keep them in memory or encrypted storage only for the flow's duration.
- The **client secret stays on the backend**. Ideally the backend performs the code-for-token exchange, and the app only receives a DocLoom session token.
- The redirect should come back through a **verified App Link** (`https://docloom.example/auth/callback`) so no other app can hijack it. If you use a custom scheme (`docloom://auth/callback`), PKCE and the `state` check are what protect it, so they're mandatory.
- On the callback, reject if `state` doesn't match, if there's an `error` parameter, or if the code is missing. Then clear the stored verifier and state.
- Register the exact redirect URI in the GitHub App settings. Don't use wildcards.

## 5. Deep links and App Links

```xml
<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW"/>
    <category android:name="android.intent.category.DEFAULT"/>
    <category android:name="android.intent.category.BROWSABLE"/>
    <data android:scheme="https" android:host="docloom.example" android:pathPrefix="/auth/callback"/>
</intent-filter>
```

- Host `/.well-known/assetlinks.json` on the domain with the release signing certificate's SHA-256 (from Play App Signing).
- Treat every deep link parameter as attacker-controlled input. Validate IDs (UUID format), never navigate to arbitrary URLs from a parameter, and never perform an action (delete, share, accept invite) without user confirmation and an authenticated API call.
- For invite links (the backend has `invite` and `team` routes), the app should show what is being accepted and let the backend validate the invite token.

## 6. Biometrics and re-authentication

- For sensitive actions (deleting a project, rotating an API key, viewing tokens), require recent auth: `BiometricPrompt` with a `CryptoObject` tied to a Keystore key, or re-entering the password.
- Use `BIOMETRIC_STRONG` for anything that unlocks keys.
- A biometric check that only returns a boolean is UX, not security. Make it unlock a key whenever possible.
