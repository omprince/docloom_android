# Build, dependencies and release

## Contents
1. Release build type
2. Logging in release
3. Signing
4. Dependencies and supply chain
5. App integrity (Play Integrity)
6. Pre-release checklist

## 1. Release build type

The current `app/build.gradle.kts` has:
```kotlin
release { optimization { enable = false } }
```
Turn R8 shrinking and obfuscation on for release. It removes unused code (smaller attack surface) and makes reverse engineering harder:
```kotlin
release {
    optimization { enable = true }   // R8: shrink + obfuscate + optimize
    // older AGP DSL equivalent: isMinifyEnabled = true; isShrinkResources = true
}
```
- Keep rules go in the project's keep-rules file (`app/src/main/keepRules/rules.keep` in this project). Keep only what reflection or serialization needs, such as kotlinx-serialization `@Serializable` classes if not handled by the library rules.
- Test the **release** build on a device before shipping, because R8 can break reflection-based code.
- Never set `isDebuggable = true` on release.
- Obfuscation isn't encryption. It slows attackers but doesn't protect secrets (see `storage-and-secrets.md`).

## 2. Logging in release

- Route all logging through one wrapper (or Timber) that logs nothing in release builds.
- As a safety net, strip `android.util.Log` debug, verbose and info calls in release with R8:
  ```
  -assumenosideeffects class android.util.Log {
      public static int v(...);
      public static int d(...);
      public static int i(...);
  }
  ```
- Crash reporting (if added): scrub tokens, emails and request bodies before sending, and disclose it in the privacy policy.

## 3. Signing

- Use **Play App Signing**. Google holds the app signing key, and you keep only an upload key.
- The upload keystore (`*.jks` / `*.keystore`) and its passwords **never go into Git**. Store them in a password manager and in CI secrets.
- Read signing config from environment variables or `local.properties`, never hardcoded:
  ```kotlin
  signingConfigs {
      create("release") {
          storeFile = System.getenv("DOCLOOM_KEYSTORE")?.let { file(it) }
          storePassword = System.getenv("DOCLOOM_KEYSTORE_PASSWORD")
          keyAlias = System.getenv("DOCLOOM_KEY_ALIAS")
          keyPassword = System.getenv("DOCLOOM_KEY_PASSWORD")
      }
  }
  ```
- Add `*.jks`, `*.keystore` and `keystore.properties` to `.gitignore`.

## 4. Dependencies and supply chain

- Declare all versions in `gradle/libs.versions.toml`. Don't use dynamic versions (`+`, `latest.release`).
- Only use `google()` and `mavenCentral()` repositories. Avoid JitPack or random Maven repos for anything security-relevant.
- Turn on **Gradle dependency verification** (`./gradlew --write-verification-metadata sha256 help`) so tampered artifacts fail the build.
- Scan regularly: run `./gradlew dependencyUpdates` (versions plugin) for outdated libraries, and use OWASP Dependency-Check or GitHub Dependabot for known CVEs.
- Before adding a library, check that it's maintained and what permissions it merges into the manifest.
- Keep `compileSdk` and `targetSdk` current (this project targets 37), since new targets enable new platform protections.

## 5. App integrity (Play Integrity)

- Root, emulator and Frida detection in the app can be bypassed and mostly annoys legitimate users. Don't rely on them.
- For server-side assurance that requests come from a genuine app on a genuine device, use the **Play Integrity API**:
  1. The app requests an integrity token with a nonce or request hash from the backend.
  2. The **backend** decrypts and verifies the verdict with Google, and decides (for example, allow login, step up, or block).
- Use it for high-value actions (login, account changes), not every request.

## 6. Pre-release checklist

- [ ] Run `quick_scan.sh` with no Critical or High hits left unexplained
- [ ] `./gradlew lint` passes, with the security lint checks reviewed
- [ ] R8 enabled, release build tested on a real device
- [ ] `API_BASE_URL` for release is `https://` and the production host
- [ ] No cleartext in the main network config; the debug config is only in `src/debug`
- [ ] `allowBackup` is false, or session stores are excluded
- [ ] All components have explicit `exported`, and only the necessary ones are `true`
- [ ] Logging is silent in release, with the OkHttp logging level `NONE`
- [ ] No secrets in the repo (`git grep -nE "secret|password|DATABASE_URL|JWT_SECRET|BEGIN PRIVATE KEY"`)
- [ ] Keystore isn't in the repo, and Play App Signing is on
- [ ] Dependencies pinned, verification metadata committed, no known-vulnerable versions
- [ ] Backend accepts the Bearer token, enforces permissions and rate limits, and revokes refresh tokens on logout
- [ ] `assetlinks.json` is deployed if App Links are used
- [ ] Optional: MobSF static scan of the release APK, and a dynamic test against staging
