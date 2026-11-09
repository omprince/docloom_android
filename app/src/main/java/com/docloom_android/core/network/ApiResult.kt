package com.docloom_android.core.network

import kotlinx.serialization.Serializable
import retrofit2.HttpException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** Outcome of an API call, with the backend's `{ "error": "..." }` text on failure. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Err(val message: String, val status: Int? = null) : ApiResult<Nothing>
}

@Serializable
private data class ErrorBody(val error: String? = null)

/** Runs [block], turning HTTP and network failures into [ApiResult.Err]. */
suspend fun <T> apiCall(block: suspend () -> T): ApiResult<T> = try {
    ApiResult.Ok(block())
} catch (e: CancellationException) {
    throw e
} catch (e: HttpException) {
    val raw = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
    val message = raw?.let { runCatching { ApiJson.decodeFromString<ErrorBody>(it).error }.getOrNull() }
    ApiResult.Err(message ?: "Request failed (${e.code()}).", e.code())
} catch (e: IOException) {
    ApiResult.Err("Can't reach the DocLoom server. Check your connection.")
} catch (e: Exception) {
    ApiResult.Err("Something went wrong. Try again.")
}
