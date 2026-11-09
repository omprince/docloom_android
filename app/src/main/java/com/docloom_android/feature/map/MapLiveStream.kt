package com.docloom_android.feature.map

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/** What the map's server-sent event stream says (graph/routes.rs stream_revisions). */
sealed interface LiveEvent {
    /** Connected; [version] is the server's current version. */
    data class Ready(val version: Long) : LiveEvent
    /** A new version landed. */
    data class NewVersion(val version: Long) : LiveEvent
    /** This stream fell behind; pull everything since our version. */
    data object Resync : LiveEvent
    /** The stream dropped or was refused; polling covers it until it's back. */
    data object Down : LiveEvent
}

/**
 * Opens `/api/projects/{id}/graph/stream` with the app's authenticated
 * client (Bearer header included). The flow ends when the stream does.
 */
class MapLiveStream(baseUrl: String, client: OkHttpClient) {
    private val base = baseUrl.trimEnd('/')

    // A stream stays open: no read timeout.
    private val client = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    fun events(projectId: String): Flow<LiveEvent> = callbackFlow {
        val request = Request.Builder()
            .url("$base/api/projects/$projectId/graph/stream")
            .header("Accept", "text/event-stream")
            .build()
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val event = when (type) {
                    "ready" -> LiveEvent.Ready(data.trim().toLongOrNull() ?: 0)
                    "revision" -> LiveEvent.NewVersion(data.trim().toLongOrNull() ?: 0)
                    "resync" -> LiveEvent.Resync
                    else -> null
                }
                if (event != null) trySend(event)
            }

            override fun onClosed(eventSource: EventSource) {
                trySend(LiveEvent.Down)
                channel.close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                trySend(LiveEvent.Down)
                channel.close()
            }
        }
        val source = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { source.cancel() }
    }
}
