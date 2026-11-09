package com.docloom_android.feature.map

import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.docloom_android.core.model.Revision
import com.docloom_android.core.network.ApiJson
import com.docloom_android.core.network.ApiResult
import com.docloom_android.core.network.apiCall
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

enum class SyncStatus(val text: String) {
    Loading("Loading…"),
    Saved("Saved"),
    Pending("Unsaved changes"),
    Saving("Saving…"),
    Offline("Offline: will retry"),
    Error("Reloaded"),
}

/** Someone else's change that just arrived, shown briefly. */
data class RemoteActivity(val key: Int, val actorType: String, val actorLabel: String, val reason: String?, val nodeIds: Set<String>)

data class MapState(
    val loadError: String? = null,
    val graph: MapGraph? = null,
    val status: SyncStatus = SyncStatus.Loading,
    val live: Boolean = false,
    val error: String? = null,
    val selectedId: String? = null,
    /** A node to bring into view (from a doc chip or an error), consumed by the canvas. */
    val focusId: String? = null,
    val activity: RemoteActivity? = null,
)

@Serializable
private data class ErrorBody(val error: String? = null)

private const val SAVE_DELAY_MS = 600L
private const val RETRY_MS = 4000L
private const val POLL_MS = 5000L
private const val POLL_LIVE_MS = 20000L
private const val STREAM_RETRY_MS = 5000L
private const val FEED_LIMIT = 200

/**
 * Keeps the phone's copy of the map in sync with the server, like the
 * website's useGraphSync: each local edit is queued and sent as a small
 * change batch; others' changes arrive through the live stream (polling as
 * a fallback) and are applied in place. Own revisions are recognised by
 * [clientId] and skipped. Per node/edge, the last write wins.
 */
class MapViewModel(
    private val api: MapApi,
    private val stream: MapLiveStream,
    private val projectId: String,
    val readOnly: Boolean,
    focusNodeId: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(MapState(focusId = focusNodeId, selectedId = focusNodeId))
    val state: StateFlow<MapState> = _state.asStateFlow()

    private val clientId = "android-" + UUID.randomUUID()
    private var version = 0L
    private val nodes = LinkedHashMap<String, JsonObject>()
    private val edges = LinkedHashMap<String, JsonObject>()

    /** Unsent changes by entity key ("n:id" / "e:id"); the newest per key wins. */
    private val pending = LinkedHashMap<String, JsonObject>()
    private val sendLock = Mutex()
    private var saveJob: Job? = null
    private var dragging = false
    private var activityKey = 0

    init { viewModelScope.launch { reload() } }

    // ---------------- loading ----------------

    private suspend fun reload() {
        when (val r = apiCall { api.graph(projectId) }) {
            is ApiResult.Ok -> {
                version = r.value.version
                nodes.clear()
                edges.clear()
                r.value.nodes.forEach { n -> (n["id"] as? JsonPrimitive)?.content?.let { nodes[it] = n.persistable() } }
                r.value.edges.forEach { e -> (e["id"] as? JsonPrimitive)?.content?.let { edges[it] = e.persistable() } }
                pending.clear()
                publish()
                _state.update { it.copy(loadError = null, status = SyncStatus.Saved) }
            }
            is ApiResult.Err -> _state.update { it.copy(loadError = r.message) }
        }
    }

    fun retryLoad() {
        viewModelScope.launch { reload() }
    }

    private fun publish() {
        val graph = MapGraph.from(nodes.values.toList(), edges.values.toList())
        _state.update { s ->
            s.copy(graph = graph, selectedId = s.selectedId?.takeIf { graph.byId.containsKey(it) })
        }
    }

    // ---------------- live updates ----------------

    /** Runs while the map is on screen (the caller cancels it when it isn't). */
    suspend fun runLive() = coroutineScope {
        launch {
            while (true) {
                stream.events(projectId).collect { event ->
                    when (event) {
                        is LiveEvent.Ready -> {
                            _state.update { it.copy(live = true) }
                            pull()
                        }
                        is LiveEvent.NewVersion -> if (event.version > version) pull()
                        LiveEvent.Resync -> pull()
                        LiveEvent.Down -> _state.update { it.copy(live = false) }
                    }
                }
                _state.update { it.copy(live = false) }
                delay(STREAM_RETRY_MS)
            }
        }
        launch {
            pull() // catch up straight away when coming back to the screen
            while (true) {
                delay(if (_state.value.live) POLL_LIVE_MS else POLL_MS)
                pull()
            }
        }
    }

    private val pullLock = Mutex()

    private suspend fun pull() {
        if (_state.value.graph == null) return
        pullLock.withLock {
            // Our own save going out first keeps the order simple.
            sendLock.withLock { }
            val feed = (apiCall { api.since(projectId, version, FEED_LIMIT) } as? ApiResult.Ok)?.value ?: return
            if (feed.revisions.size >= FEED_LIMIT) {
                reload()
                return
            }
            var changed = false
            for (r in feed.revisions.sortedBy { it.version }) {
                if (r.version <= version) continue
                if (r.clientId != clientId) {
                    applyRemote(r)
                    changed = true
                }
                version = r.version
            }
            if (changed) publish()
        }
    }

    private fun applyRemote(r: Revision) {
        val touched = mutableSetOf<String>()
        for (c in r.changes) {
            val key = (if (c.entity == "node") "n:" else "e:") + c.id
            if (pending.containsKey(key)) continue // our unsent edit wins and goes out next
            val map = if (c.entity == "node") nodes else edges
            if (c.after != null) {
                map[c.id] = c.after.persistable()
                if (c.entity == "node") touched += c.id
            } else {
                map.remove(c.id)
            }
        }
        val key = ++activityKey
        _state.update { it.copy(activity = RemoteActivity(key, r.actorType, r.actorLabel, r.reason, touched)) }
        viewModelScope.launch {
            delay(6000)
            _state.update { s -> if (s.activity?.key == key) s.copy(activity = null) else s }
        }
    }

    // ---------------- saving ----------------

    private fun queue(key: String, change: JsonObject) {
        if (readOnly) return
        pending[key] = change
        _state.update { it.copy(status = SyncStatus.Pending) }
        scheduleSave()
    }

    private fun scheduleSave(delayMs: Long = SAVE_DELAY_MS) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(delayMs)
            if (!dragging) flush()
        }
    }

    private suspend fun flush() = sendLock.withLock {
        if (pending.isEmpty()) return@withLock
        val batch = LinkedHashMap(pending)
        // Order like the website: node upserts parents-first, edge deletes, edge upserts, node deletes.
        val nodeUpserts = batch.filter { it.key.startsWith("n:") && it.value.op() == "upsert_node" }
        val ordered = parentsFirst(nodeUpserts.values.mapNotNull { (it["node"] as? JsonObject)?.let(::MapNode) })
            .mapNotNull { n -> nodeUpserts["n:${n.id}"] } +
            batch.values.filter { it.op() == "delete_edge" } +
            batch.values.filter { it.op() == "upsert_edge" } +
            batch.values.filter { it.op() == "delete_node" }
        _state.update { it.copy(status = SyncStatus.Saving) }
        when (val r = apiCall { api.changes(projectId, ChangesRequest(ordered, clientId)) }) {
            is ApiResult.Err -> {
                _state.update { it.copy(status = SyncStatus.Offline) }
                scheduleSave(RETRY_MS)
            }
            is ApiResult.Ok -> {
                val response = r.value
                if (response.code() in 400..499) {
                    val raw = response.errorBody()?.string()
                    val msg = raw?.let { runCatching { ApiJson.decodeFromString(ErrorBody.serializer(), it).error }.getOrNull() }
                    _state.update { it.copy(status = SyncStatus.Error, error = msg ?: "The map changed elsewhere and was reloaded.") }
                    reload()
                } else if (!response.isSuccessful) {
                    _state.update { it.copy(status = SyncStatus.Offline) }
                    scheduleSave(RETRY_MS)
                } else {
                    // Drop what was sent, unless it was edited again meanwhile.
                    for ((k, v) in batch) if (pending[k] === v) pending.remove(k)
                    _state.update { it.copy(status = if (pending.isEmpty()) SyncStatus.Saved else SyncStatus.Pending, error = null) }
                    if (pending.isNotEmpty()) scheduleSave()
                }
            }
        }
    }

    private fun JsonObject.op(): String? = (this["op"] as? JsonPrimitive)?.content

    private fun upsertNode(node: JsonObject) {
        val id = (node["id"] as JsonPrimitive).content
        nodes[id] = node
        queue("n:$id", buildJsonObject { put("op", JsonPrimitive("upsert_node")); put("node", node) })
    }

    private fun upsertEdge(edge: JsonObject) {
        val id = (edge["id"] as JsonPrimitive).content
        edges[id] = edge
        queue("e:$id", buildJsonObject { put("op", JsonPrimitive("upsert_edge")); put("edge", edge) })
    }

    private fun removeEdge(id: String) {
        edges.remove(id)
        queue("e:$id", buildJsonObject { put("op", JsonPrimitive("delete_edge")); put("id", JsonPrimitive(id)) })
    }

    // ---------------- selection ----------------

    fun select(id: String?) = _state.update { it.copy(selectedId = id) }
    fun focusHandled() = _state.update { it.copy(focusId = null) }
    fun focus(id: String) = _state.update { it.copy(focusId = id, selectedId = id) }
    fun dismissError() = _state.update { it.copy(error = null) }

    // ---------------- edits ----------------

    /** While dragging: moves the node on screen only. [delta] is in map units. */
    fun dragBy(id: String, delta: Offset) {
        if (readOnly) return
        val raw = nodes[id] ?: return
        val n = MapNode(raw)
        dragging = true
        nodes[id] = raw.withPosition(n.x + delta.x, n.y + delta.y)
        publish()
    }

    /** Drag finished: save the new position. */
    fun dragEnd(id: String) {
        dragging = false
        nodes[id]?.let(::upsertNode)
        publish()
    }

    fun addComponent(kind: NodeKind, label: String, at: Offset) {
        val node = newArchitectureNode(kind, label.trim().ifEmpty { kind.label }, at)
        upsertNode(node)
        publish()
        select((node["id"] as JsonPrimitive).content)
    }

    fun addNote(label: String, at: Offset) {
        val node = newNoteNode(label.trim().ifEmpty { "Note" }, at)
        upsertNode(node)
        publish()
        select((node["id"] as JsonPrimitive).content)
    }

    fun rename(id: String, label: String) {
        val raw = nodes[id] ?: return
        val clean = label.trim()
        if (clean.isEmpty()) return
        // ER tables and sections keep their name in "label" too (backend schema.rs / build.rs).
        upsertNode(raw.withData("label" to clean))
        publish()
    }

    fun setDescription(id: String, description: String) {
        val raw = nodes[id] ?: return
        upsertNode(raw.withData("description" to description.trim().ifEmpty { null }))
        publish()
    }

    fun setKind(id: String, kind: NodeKind) {
        val raw = nodes[id] ?: return
        upsertNode(raw.withData("kind" to kind.key))
        // Connections carry the colours of their ends; repaint them.
        val graph = MapGraph.from(nodes.values.toList(), edges.values.toList())
        val node = graph.byId[id] ?: return
        for (e in graph.connectionsOf(id)) {
            val data = (e.raw["data"] as? JsonObject)?.toMutableMap() ?: continue
            if (e.source == id) data["sourceColor"] = JsonPrimitive(hex(node.color))
            if (e.target == id) data["targetColor"] = JsonPrimitive(hex(node.color))
            upsertEdge(JsonObject(e.raw + ("data" to JsonObject(data))))
        }
        publish()
    }

    /** Null when it can be deleted, else why not. */
    fun whyNotDeletable(id: String): String? {
        val n = _state.value.graph?.byId?.get(id) ?: return null
        return when {
            !n.deletable || n.type == "task" -> "Task cards follow their task. Change the task's architecture area on the board instead."
            _state.value.graph?.nodes?.any { it.parentId == id } == true -> "Move or delete what's inside this group first."
            else -> null
        }
    }

    fun deleteNode(id: String) {
        if (whyNotDeletable(id) != null) return
        val graph = _state.value.graph ?: return
        graph.connectionsOf(id).forEach { removeEdge(it.id) }
        nodes.remove(id)
        queue("n:$id", buildJsonObject { put("op", JsonPrimitive("delete_node")); put("id", JsonPrimitive(id)) })
        select(null)
        publish()
    }

    fun connect(sourceId: String, targetId: String, label: String?) {
        if (sourceId == targetId) return
        val graph = _state.value.graph ?: return
        val s = graph.byId[sourceId] ?: return
        val t = graph.byId[targetId] ?: return
        upsertEdge(newFlowEdge(s, t, label))
        publish()
    }

    fun deleteEdge(id: String) {
        removeEdge(id)
        publish()
    }

    /** Flushes unsaved edits when leaving the map. */
    fun saveNow() {
        saveJob?.cancel()
        dragging = false
        viewModelScope.launch { flush() }
    }
}
