# Platform components: manifest, intents, WebView, UI

## Contents
1. Manifest hardening
2. Intents and PendingIntents
3. Content providers and file sharing
4. WebView
5. Sensitive UI (screenshots, tapjacking, clipboard)
6. Permissions

## 1. Manifest hardening

Checklist for `app/src/main/AndroidManifest.xml`:
- `android:allowBackup="false"`, or backup rules that exclude session data (see `storage-and-secrets.md`). **The current template has `allowBackup="true"` with empty rules.**
- No `android:debuggable` attribute. The build type controls it.
- No `android:usesCleartextTraffic="true"`. Use `networkSecurityConfig` instead (see `network.md`).
- `<uses-permission android:name="android.permission.INTERNET"/>` is needed. Add other permissions only when a feature needs them.
- Every `<activity>`, `<service>`, `<receiver>` and `<provider>` has an explicit `android:exported`. Only the launcher activity and verified deep link activities should be `true`.
- An exported component that must accept calls from specific apps should be protected with a `signature`-level custom permission.

## 2. Intents and PendingIntents

- Use **explicit intents** (`Intent(context, Target::class.java)`) for in-app navigation and services.
- `PendingIntent`: always set `FLAG_IMMUTABLE` unless the receiver must fill in data (rare, such as inline reply). A mutable PendingIntent with an implicit base intent can be hijacked.
- Validate all incoming `Intent` extras and `data` URIs. Never take a URL, file path, class name or component from an extra and use it directly.
- Avoid forwarding an incoming intent to `startActivity` (the "intent redirection" vulnerability).
- Don't broadcast private data with implicit broadcasts. Use in-app mechanisms instead (Flows, ViewModels).

## 3. Content providers and file sharing

- Don't add a `ContentProvider` unless it's needed. If one exists: set `exported="false"`, or protect it with permissions and use `grantUriPermissions` narrowly.
- Share files (exported docs, Studio images) via `FileProvider` with a `file_paths.xml` that lists only the specific share directory, never `.` or the root.
- Use parameterized queries in Room or SQLite. Never concatenate user input into SQL.

## 4. WebView

Prefer native Compose UI. If a WebView is unavoidable (for example rendering doc HTML):

```kotlin
webView.settings.apply {
    javaScriptEnabled = false            // enable only if strictly needed
    allowFileAccess = false
    allowContentAccess = false
    domStorageEnabled = false
}
webView.webViewClient = object : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
        val allowed = req.url.scheme == "https" && req.url.host in ALLOWED_HOSTS
        if (!allowed) openExternally(req.url)   // or block
        return !allowed
    }
}
```

- Never call `addJavascriptInterface` on a WebView that loads remote or user content.
- Sanitize server- or user-provided HTML before `loadDataWithBaseURL`, and use a null or `about:blank` base URL.
- Never use a WebView for login or OAuth (see `auth-and-session.md`).
- Don't call `handler.proceed()` in `onReceivedSslError`. Cancel instead.

## 5. Sensitive UI

- **Screenshots and recents:** on screens showing tokens, API keys (the backend has `tokens` and `errors/key` routes) or private docs, set `window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)`, and clear the flag when leaving the screen.
- **Tapjacking:** for critical buttons (confirm delete, grant access), block input when another window overlays the screen. In Compose, check `MotionEvent.FLAG_WINDOW_IS_OBSCURED` / `FLAG_WINDOW_IS_PARTIALLY_OBSCURED` in a `pointerInteropFilter`, or use `filterTouchesWhenObscured` on the hosting view. On Android 12+, `setHideOverlayWindows(true)` (permission `HIDE_OVERLAY_WINDOWS`) hides overlays on sensitive activities.
- **Clipboard:** when copying API keys or tokens, mark them sensitive:
  ```kotlin
  val clip = ClipData.newPlainText("API key", key).apply {
      description.extras = PersistableBundle().apply {
          putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
      }
  }
  ```
- **Notifications:** don't show private doc content on the lock screen. Use `VISIBILITY_PRIVATE` with a public version.
- **Error messages:** don't show stack traces or raw server errors to users.

## 6. Permissions

- Request permissions at the moment of use, with an explanation, and handle denial gracefully.
- For images, prefer the Photo Picker (`PickVisualMedia`), which needs no storage permission, over `READ_MEDIA_IMAGES`.
- Review new dependencies for permissions they merge into the manifest. Check the merged manifest in `build/intermediates/merged_manifests/`.
