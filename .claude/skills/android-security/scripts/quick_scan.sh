#!/usr/bin/env bash
# Quick static security scan for an Android (Kotlin/Java) project.
# Greps for known-risky patterns. Every hit is a LEAD to confirm by reading the code,
# not a final verdict, and logic flaws are not detected.
#
# Usage: bash quick_scan.sh [path-to-android-project]   (default: current directory)

ROOT="${1:-.}"
if [ ! -d "$ROOT" ]; then echo "Not a directory: $ROOT" >&2; exit 2; fi

EXCLUDES=(--exclude-dir=build --exclude-dir=.gradle --exclude-dir=.idea --exclude-dir=.git
          --exclude-dir=.claude --exclude-dir=.kotlin --exclude-dir=node_modules)
CODE=(--include=*.kt --include=*.java --include=*.kts --include=*.gradle --include=*.xml
      --include=*.properties --include=*.json --include=*.toml --include=*.pro --include=*.keep)

hits=0
declare -A counts=([CRITICAL]=0 [HIGH]=0 [MEDIUM]=0 [LOW]=0)

# check SEVERITY "title" "regex" [extra grep args...]
check() {
  local sev="$1" title="$2" pattern="$3"; shift 3
  local out
  # drop XML namespace URIs (xmlns="http://schemas.android.com/..."), which are never fetched
  out=$(grep -rnIE "${EXCLUDES[@]}" "${CODE[@]}" "$@" -e "$pattern" "$ROOT" 2>/dev/null \
        | grep -v 'http://schemas\.android\.com')
  if [ -n "$out" ]; then
    echo "[$sev] $title"
    echo "$out" | head -20 | sed 's/^/    /'
    local n; n=$(echo "$out" | wc -l | tr -d ' ')
    [ "$n" -gt 20 ] && echo "    ... ($n hits total)"
    echo
    hits=$((hits+1)); counts[$sev]=$((counts[$sev]+1))
  fi
}

echo "== Android quick security scan: $ROOT =="
echo

# --- Secrets ------------------------------------------------------------------
check CRITICAL "Server secret / DB credential referenced in app code" \
  '(DATABASE_URL|DIRECT_URL|JWT_SECRET|GITAPP_CLIENT_SECRET|postgres(ql)?://|BEGIN (RSA |EC )?PRIVATE KEY)' \
  --exclude=local.properties
check HIGH "Possible hardcoded secret / key / password" \
  '(api[_-]?key|client[_-]?secret|secret[_-]?key|password|passwd|auth[_-]?token)[[:space:]]*[:=][[:space:]]*"[^"$]{6,}"' -i

# --- TLS / network ------------------------------------------------------------
check CRITICAL "Trust-all TLS: custom TrustManager / HostnameVerifier / SSL error proceed" \
  '(X509TrustManager|checkServerTrusted[^{]*\{[[:space:]]*\}|HostnameVerifier[^)]*true|ALLOW_ALL_HOSTNAME_VERIFIER|handler\.proceed\(\))'
check HIGH "Cleartext traffic enabled in manifest" \
  'usesCleartextTraffic="true"' --include=AndroidManifest.xml
check HIGH "Network security config permits cleartext (ok only under src/debug)" \
  'cleartextTrafficPermitted="true"'
check MEDIUM "Trusts user-installed CAs (ok only in debug-overrides)" \
  'certificates src="user"'
check MEDIUM "Plain http:// URL in source (ok only for debug dev hosts)" \
  '"http://[^"]+' --include=*.kt --include=*.java --include=*.kts
check MEDIUM "HTTP body logging (may leak tokens/passwords)" \
  'Level\.BODY'

# --- Storage ------------------------------------------------------------------
check HIGH "World-readable/writeable file mode" \
  'MODE_WORLD_(READABLE|WRITEABLE)'
check HIGH "Token/password possibly stored in plain SharedPreferences" \
  'put(String)?\([^)]*(token|password|secret|jwt)' --include=*.kt --include=*.java
check MEDIUM "Deprecated EncryptedSharedPreferences / security-crypto (prefer Tink + DataStore)" \
  '(EncryptedSharedPreferences|security-crypto|security\.crypto)'
check MEDIUM "allowBackup enabled (check backup rules exclude session data)" \
  'allowBackup="true"' --include=AndroidManifest.xml
check MEDIUM "External storage used (check nothing private is written there)" \
  '(getExternalStorageDirectory|getExternalStoragePublicDirectory|WRITE_EXTERNAL_STORAGE)'

# --- Logging ------------------------------------------------------------------
check HIGH "Sensitive value possibly logged" \
  '(Log\.[vdiwe]|println|Timber\.[vdiwe])\([^)]*(token|password|secret|jwt|authorization|cookie)' -i

# --- Components / intents -----------------------------------------------------
check LOW "Exported components (confirm each one must be public)" \
  'android:exported="true"' --include=AndroidManifest.xml
check HIGH "App marked debuggable in manifest" \
  'android:debuggable="true"'
check MEDIUM "PendingIntent created: confirm FLAG_IMMUTABLE is set" \
  'PendingIntent\.get(Activity|Service|Broadcast|ForegroundService)\(' --include=*.kt --include=*.java
check MEDIUM "Incoming intent possibly forwarded (intent redirection)" \
  'startActivity\([^)]*getParcelableExtra|startActivity\(intent\.get' --include=*.kt --include=*.java

# --- WebView ------------------------------------------------------------------
check HIGH "WebView JavaScript bridge (addJavascriptInterface)" \
  'addJavascriptInterface'
check MEDIUM "WebView JavaScript / file access enabled" \
  '(javaScriptEnabled[[:space:]]*=[[:space:]]*true|setJavaScriptEnabled\(true\)|allowFileAccess[[:space:]]*=[[:space:]]*true|setAllowFileAccess\(true\)|setAllowUniversalAccessFromFileURLs\(true\))'

# --- Build / release ----------------------------------------------------------
check MEDIUM "R8 / minify disabled (check it is not the release build type)" \
  '(optimization[[:space:]]*\{[[:space:]]*enable[[:space:]]*=[[:space:]]*false|^[[:space:]]*enable[[:space:]]*=[[:space:]]*false|isMinifyEnabled[[:space:]]*=[[:space:]]*false|minifyEnabled[[:space:]]+false)' \
  --include=*.kts --include=*.gradle
check MEDIUM "Release build marked debuggable" \
  '(isDebuggable[[:space:]]*=[[:space:]]*true|debuggable[[:space:]]+true)' --include=*.kts --include=*.gradle
check MEDIUM "Dynamic dependency version (+ / latest)" \
  '(:[0-9.]*\+"|latest\.(release|integration)|version[[:space:]]*=[[:space:]]*"\+")' --include=*.kts --include=*.gradle --include=*.toml
check LOW "Non-standard Maven repository (check trust)" \
  '(jitpack\.io|maven[[:space:]]*\{[[:space:]]*url|maven\(")' --include=*.kts --include=*.gradle

# --- Committed keystores --------------------------------------------------------
ks=$(find "$ROOT" \( -name build -o -name .gradle -o -name .git \) -prune -o \
       \( -name '*.jks' -o -name '*.keystore' -o -name 'keystore.properties' \) -print 2>/dev/null)
if [ -n "$ks" ]; then
  echo "[CRITICAL] Keystore / signing file inside project (must not be committed)"
  echo "$ks" | sed 's/^/    /'; echo
  hits=$((hits+1)); counts[CRITICAL]=$((counts[CRITICAL]+1))
fi

# --- Missing protections (absence checks) ---------------------------------------
manifest=$(find "$ROOT" -path '*/src/main/AndroidManifest.xml' -not -path '*/build/*' 2>/dev/null | head -1)
if [ -n "$manifest" ]; then
  grep -q 'android.permission.INTERNET' "$manifest" || \
    { echo "[LOW] INTERNET permission not declared (needed before any API call)"; echo "    $manifest"; echo; counts[LOW]=$((counts[LOW]+1)); hits=$((hits+1)); }
  grep -q 'networkSecurityConfig' "$manifest" || \
    { echo "[LOW] No networkSecurityConfig set (add one: release no cleartext, debug-only dev hosts)"; echo "    $manifest"; echo; counts[LOW]=$((counts[LOW]+1)); hits=$((hits+1)); }
fi

echo "== Summary: ${counts[CRITICAL]} critical, ${counts[HIGH]} high, ${counts[MEDIUM]} medium, ${counts[LOW]} low pattern groups =="
[ "$hits" -eq 0 ] && echo "No risky patterns found. Still review auth, storage and manifest logic by hand."
exit 0
