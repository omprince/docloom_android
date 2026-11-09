import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Backend URL lives in local.properties (gitignored), never in source:
//   API_BASE_URL_DEBUG=http://10.0.2.2:3001          (emulator → your PC)
//   API_BASE_URL_RELEASE=https://api.your-domain.com (deployed Rust backend)
//   WEBSITE_URL_DEBUG / WEBSITE_URL_RELEASE          (the Next.js site: MCP address, GitHub connect)
// The database URL is NOT here — only the backend knows it (docloom_website_be/.env).
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun apiUrl(key: String, fallback: String): String =
    (localProps.getProperty(key) ?: fallback).trim().trimEnd('/')

/** A setting from local.properties, or else the environment (CI). */
fun setting(key: String): String? =
    localProps.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() } ?: System.getenv(key)?.takeIf { it.isNotEmpty() }

// Release signing (upload key). Never commit the keystore or its passwords:
//   DOCLOOM_KEYSTORE=C:/keys/docloom-upload.jks   (path to the .jks file)
//   DOCLOOM_KEYSTORE_PASSWORD=...
//   DOCLOOM_KEY_ALIAS=upload
//   DOCLOOM_KEY_PASSWORD=...
// in local.properties or as environment variables. Without them the release
// APK/AAB is built unsigned.
val releaseKeystore = setting("DOCLOOM_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.docloom_android"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.docloom_android"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = setting("DOCLOOM_KEYSTORE_PASSWORD")
                keyAlias = setting("DOCLOOM_KEY_ALIAS")
                keyPassword = setting("DOCLOOM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            optimization { enable = true } // TEMP-R8-SMOKE-TEST
            buildConfigField("String", "API_BASE_URL", "\"${apiUrl("API_BASE_URL_DEBUG", "http://10.0.2.2:3001")}\"")
            val websiteDebug = apiUrl("WEBSITE_URL_DEBUG", "http://10.0.2.2:3000")
            buildConfigField("String", "WEBSITE_URL", "\"$websiteDebug\"")
            URI(websiteDebug).let { site ->
                manifestPlaceholders["websiteScheme"] = site.scheme
                manifestPlaceholders["websiteHost"] = site.host
                manifestPlaceholders["websitePort"] = if (site.port > 0) site.port.toString() else if (site.scheme == "https") "443" else "80"
            }
        }
        release {
            val releaseUrl = apiUrl("API_BASE_URL_RELEASE", "https://api.example.com")
            val websiteUrl = apiUrl("WEBSITE_URL_RELEASE", "https://example.com")
            require(releaseUrl.startsWith("https://")) { "API_BASE_URL_RELEASE must use https://" }
            require(websiteUrl.startsWith("https://")) { "WEBSITE_URL_RELEASE must use https://" }
            buildConfigField("String", "API_BASE_URL", "\"$releaseUrl\"")
            buildConfigField("String", "WEBSITE_URL", "\"$websiteUrl\"")
            // Invite and password-reset links on the website open the app (src/release/AndroidManifest.xml).
            manifestPlaceholders["websiteHost"] = URI(websiteUrl).host
            // R8: shrink, obfuscate and optimise. Keep rules: src/main/keepRules/rules.keep.
            optimization {
                enable = true
            }
            isDebuggable = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.tink.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
