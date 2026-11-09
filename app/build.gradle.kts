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

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"${apiUrl("API_BASE_URL_DEBUG", "http://10.0.2.2:3001")}\"")
            buildConfigField("String", "WEBSITE_URL", "\"${apiUrl("WEBSITE_URL_DEBUG", "http://10.0.2.2:3000")}\"")
        }
        release {
            val releaseUrl = apiUrl("API_BASE_URL_RELEASE", "https://api.example.com")
            require(releaseUrl.startsWith("https://")) { "API_BASE_URL_RELEASE must use https://" }
            buildConfigField("String", "API_BASE_URL", "\"$releaseUrl\"")
            buildConfigField("String", "WEBSITE_URL", "\"${apiUrl("WEBSITE_URL_RELEASE", "https://example.com")}\"")
            optimization {
                enable = false
            }
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
