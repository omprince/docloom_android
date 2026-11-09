package com.docloom_android.core.network

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** JSON settings shared by every API call: tolerant of new server fields. */
val ApiJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/** Header that asks the backend to return the session JWT in the login body. */
const val CLIENT_HEADER = "X-DocLoom-Client"

/**
 * Talks to the DocLoom Rust API. The base URL comes from BuildConfig, set
 * per build type from local.properties (see app/build.gradle.kts).
 */
class ApiClient(
    baseUrl: String,
    private val tokenProvider: () -> String?,
    debug: Boolean,
) {
    private val _unauthorized = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when a request that carried a token got 401: the session ended. */
    val unauthorized: SharedFlow<Unit> = _unauthorized

    private val authInterceptor = Interceptor { chain ->
        val token = tokenProvider()
        val request = chain.request().newBuilder()
            .header(CLIENT_HEADER, "mobile")
            .header("Accept", "application/json")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()
        val response = chain.proceed(request)
        if (response.code == 401 && token != null) _unauthorized.tryEmit(Unit)
        response
    }

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .apply {
            if (debug) {
                // BASIC logs only method, URL and status; HEADERS would print the Bearer token.
                addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
            }
        }
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl.trimEnd('/') + "/")
        .client(okHttp)
        .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
        .build()

    fun <T> create(service: Class<T>): T = retrofit.create(service)
}
