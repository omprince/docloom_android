package com.docloom_android.feature.studio

import android.graphics.Bitmap
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.docloom_android.core.network.ApiJson
import com.docloom_android.core.network.ApiResult
import com.docloom_android.core.network.apiCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import retrofit2.HttpException
import java.io.ByteArrayOutputStream

// ---------------- library ----------------

data class StudioState(
    val loading: Boolean = true,
    val error: String? = null,
    /** Every image in every project (the Studio home). */
    val all: List<AssetSummary> = emptyList(),
    /** Set when browsing one project's library. */
    val projectId: String? = null,
    val folderId: String? = null,
    val folders: List<AssetFolder> = emptyList(),
    val assets: List<AssetSummary> = emptyList(),
    val busy: Boolean = false,
    /** A new image to open. */
    val created: Pair<String, String>? = null,
)

/** Studio home and a project's library (website StudioHome). */
class StudioViewModel(private val api: StudioApi) : ViewModel() {
    private val _state = MutableStateFlow(StudioState())
    val state: StateFlow<StudioState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val all = apiCall { api.allAssets() }
            _state.update { it.copy(loading = false, all = (all as? ApiResult.Ok)?.value?.assets ?: it.all, error = (all as? ApiResult.Err)?.message) }
            _state.value.projectId?.let { loadLibrary(it) }
        }
    }

    fun openProject(projectId: String?) {
        _state.update { it.copy(projectId = projectId, folderId = null, folders = emptyList(), assets = emptyList()) }
        projectId?.let { viewModelScope.launch { loadLibrary(it) } }
    }

    fun openFolder(folderId: String?) = _state.update { it.copy(folderId = folderId) }

    private suspend fun loadLibrary(projectId: String) {
        when (val r = apiCall { api.library(projectId) }) {
            is ApiResult.Ok -> _state.update { if (it.projectId == projectId) it.copy(folders = r.value.folders, assets = r.value.assets, error = null) else it }
            is ApiResult.Err -> _state.update { it.copy(error = r.message) }
        }
    }

    private fun act(block: suspend (String) -> ApiResult<*>) {
        val pid = _state.value.projectId ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val r = block(pid)
            _state.update { it.copy(busy = false, error = (r as? ApiResult.Err)?.message) }
            loadLibrary(pid)
            (apiCall { api.allAssets() } as? ApiResult.Ok)?.let { all -> _state.update { it.copy(all = all.value.assets) } }
        }
    }

    private suspend fun noContent(block: suspend () -> retrofit2.Response<Unit>) = apiCall { block().also { if (!it.isSuccessful) throw HttpException(it) } }

    fun createFolder(name: String) = act { pid -> apiCall { api.createFolder(pid, CreateFolderRequest(name.trim(), _state.value.folderId)) } }
    fun renameFolder(id: String, name: String) = act { pid -> apiCall { api.updateFolder(pid, id, buildJsonObject { put("name", JsonPrimitive(name.trim())) }) } }
    fun deleteFolder(id: String) = act { pid ->
        if (_state.value.folderId == id) openFolder(_state.value.folders.firstOrNull { it.id == id }?.parentId)
        noContent { api.deleteFolder(pid, id) }
    }

    fun renameAsset(id: String, name: String) = act { pid -> apiCall { api.update(pid, id, buildJsonObject { put("name", JsonPrimitive(name.trim())) }).also { if (!it.isSuccessful) throw HttpException(it) } } }
    fun moveAsset(id: String, folderId: String?) = act { pid ->
        apiCall { api.update(pid, id, buildJsonObject { put("folderId", folderId?.let(::JsonPrimitive) ?: JsonNull) }).also { if (!it.isSuccessful) throw HttpException(it) } }
    }
    fun duplicate(id: String) = act { pid -> apiCall { api.duplicate(pid, id) } }
    fun deleteAsset(id: String) = act { pid -> noContent { api.delete(pid, id) } }

    fun create(projectId: String, request: CreateAssetRequest) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            when (val r = apiCall { api.create(projectId, request) }) {
                is ApiResult.Ok -> _state.update { it.copy(busy = false, created = projectId to r.value.asset.id) }
                is ApiResult.Err -> _state.update { it.copy(busy = false, error = r.message) }
            }
        }
    }

    fun createdHandled() = _state.update { it.copy(created = null) }
}

// ---------------- one image ----------------

data class AssetState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val name: String = "",
    val version: Int = 0,
    val raw: JsonObject? = null,
    val doc: StudioDocView? = null,
    /** Layer pixels by blob id. */
    val pixels: Map<String, Bitmap> = emptyMap(),
    val pixelsLoaded: Int = 0,
    val pixelsTotal: Int = 0,
    val selectedId: String? = null,
    /** Hidden in this view only (not saved). */
    val hidden: Set<String> = emptySet(),
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val conflict: AssetFull? = null,
    val savedNotice: Boolean = false,
)

/**
 * One Studio image: loads its document and layer pixels, applies shape
 * edits, and saves with `baseVersion` and a fresh library preview (website
 * lib/studio/persistence.ts). Raster pixels are never changed here, so
 * saving uploads no blobs.
 */
class AssetViewModel(
    private val api: StudioApi,
    private val images: StudioImages,
    val projectId: String,
    private val assetId: String,
    val readOnly: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(AssetState())
    val state: StateFlow<AssetState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            when (val r = apiCall { api.asset(projectId, assetId) }) {
                is ApiResult.Ok -> take(r.value.asset)
                is ApiResult.Err -> _state.update { it.copy(loading = false, loadError = r.message) }
            }
        }
    }

    private suspend fun take(asset: AssetFull) {
        val doc = parseDoc(asset.doc)
        _state.update {
            it.copy(
                loading = false, loadError = null, name = asset.name, version = asset.version, raw = asset.doc, doc = doc,
                dirty = false, conflict = null, error = null, pixelsTotal = doc.blobs.size, pixelsLoaded = 0,
                selectedId = it.selectedId?.takeIf { id -> doc.find(id) != null },
            )
        }
        coroutineLoadPixels(doc)
    }

    private suspend fun coroutineLoadPixels(doc: StudioDocView) {
        val have = _state.value.pixels
        val jobs = doc.blobs.distinct().filter { it !in have }.map { blob ->
            viewModelScope.async {
                val bmp = images.load(images.blobPath(projectId, assetId, blob))
                _state.update { s -> s.copy(pixelsLoaded = s.pixelsLoaded + 1, pixels = if (bmp != null) s.pixels + (blob to bmp) else s.pixels) }
            }
        }
        jobs.awaitAll()
        if (doc.blobs.any { it !in _state.value.pixels }) _state.update { it.copy(error = "Some layers' pixels couldn't be loaded.") }
    }

    fun select(id: String?) = _state.update { it.copy(selectedId = id) }

    fun toggleHidden(id: String) = _state.update { s -> s.copy(hidden = if (id in s.hidden) s.hidden - id else s.hidden + id) }

    private fun edit(change: (JsonObject) -> JsonObject) {
        if (readOnly) return
        val raw = _state.value.raw ?: return
        val next = change(raw)
        _state.update { it.copy(raw = next, doc = parseDoc(next), dirty = true, savedNotice = false) }
    }

    /** Adds a shape on top, centred, and selects it. */
    fun addShape(kind: String, color: String) {
        val doc = _state.value.doc ?: return
        val s = minOf(doc.width, doc.height) * 0.3f
        val x = doc.width / 2f - s / 2
        val y = doc.height / 2f - s / 2
        val (name, geom) = when (kind) {
            "ellipse" -> "Ellipse" to geomJson("ellipse", "x" to x, "y" to y, "w" to s, "h" to s)
            "line" -> "Line" to geomJson("line", "x1" to x, "y1" to y + s, "x2" to x + s, "y2" to y)
            "star" -> "Star" to geomJson("star", "x" to x, "y" to y, "w" to s, "h" to s, "points" to 5, "inner" to 0.5)
            "polygon" -> "Polygon" to geomJson("polygon", "x" to x, "y" to y, "w" to s, "h" to s, "sides" to 6)
            else -> "Rectangle" to geomJson("rect", "x" to x, "y" to y, "w" to s, "h" to s, "r" to 0)
        }
        val line = kind == "line"
        val node = shapeNode(name, geom, if (line) null else color, if (line) color else null, if (line) maxOf(2f, s / 40) else 0f)
        edit { it.withTopLayer(node) }
        select((node["id"] as JsonPrimitive).content)
    }

    fun addText(text: String, color: String) {
        val doc = _state.value.doc ?: return
        val size = maxOf(12f, minOf(doc.width, doc.height) / 10f)
        val geom = geomJson(
            "text", "x" to doc.width * 0.1f, "y" to doc.height / 2f - size / 2, "text" to text, "size" to size,
            "font" to "sans-serif", "weight" to 600, "italic" to false, "align" to "left", "lineHeight" to 1.2,
        )
        val node = shapeNode(text.lineSequence().first().take(30).ifBlank { "Text" }, geom, color, null, 0f)
        edit { it.withTopLayer(node) }
        select((node["id"] as JsonPrimitive).content)
    }

    /** A freehand stroke: [points] in document pixels become an open path. */
    fun addStroke(points: List<Pair<Float, Float>>, color: String, width: Float) {
        if (points.size < 2) return
        val anchors = kotlinx.serialization.json.JsonArray(points.map { (x, y) ->
            kotlinx.serialization.json.JsonArray(listOf(x, y, x, y, x, y).map { JsonPrimitive(Math.round(it * 10) / 10.0) })
        })
        val node = shapeNode("Stroke", geomJson("path", "closed" to false, "points" to anchors), null, color, width)
        edit { it.withTopLayer(node) }
    }

    fun moveSelected(dx: Float, dy: Float) {
        val id = _state.value.selectedId ?: return
        val layer = _state.value.doc?.find(id) as? Layer.Shape ?: return
        if (layer.locked) return
        edit { raw -> raw.mapLayer(id) { o -> o.with("shape" to moveGeom(o["shape"] as JsonObject, dx, dy)) } }
    }

    fun setColor(color: String) {
        val id = _state.value.selectedId ?: return
        val layer = _state.value.doc?.find(id) as? Layer.Shape ?: return
        // Lines and strokes are coloured by their stroke; filled shapes by their fill.
        val strokeOnly = layer.fill == null && layer.stroke != null
        edit { raw -> raw.mapLayer(id) { o -> o.with((if (strokeOnly) "stroke" else "fill") to JsonPrimitive(color)) } }
    }

    fun setOpacity(opacity: Float) {
        val id = _state.value.selectedId ?: return
        edit { raw -> raw.mapLayer(id) { o -> o.with("opacity" to JsonPrimitive(Math.round(opacity * 100) / 100.0)) } }
    }

    fun setVisible(id: String, visible: Boolean) = edit { raw -> raw.mapLayer(id) { o -> o.with("visible" to JsonPrimitive(visible)) } }

    fun setText(text: String) {
        val id = _state.value.selectedId ?: return
        edit { raw ->
            raw.mapLayer(id) { o ->
                val shape = o["shape"] as? JsonObject ?: return@mapLayer o
                o.with("shape" to shape.with("text" to JsonPrimitive(text)))
            }
        }
    }

    fun renameLayer(id: String, name: String) = edit { raw -> raw.mapLayer(id) { o -> o.with("name" to JsonPrimitive(name.trim().ifEmpty { "Layer" })) } }

    fun deleteSelected() {
        val id = _state.value.selectedId ?: return
        edit { raw -> raw.mapLayer(id) { null } }
        select(null)
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** [force] overwrites a newer version saved by someone else. */
    fun save(force: Boolean = false) {
        val s = _state.value
        val raw = s.raw ?: return
        val doc = s.doc ?: return
        if (readOnly || s.saving) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            val preview = withContext(Dispatchers.Default) {
                val bmp = renderPreview(doc, s.pixels)
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }
            val body = buildJsonObject {
                put("doc", raw)
                put("preview", JsonPrimitive(preview))
                if (!force) put("baseVersion", JsonPrimitive(s.version))
            }
            when (val r = apiCall { api.update(projectId, assetId, body) }) {
                is ApiResult.Err -> _state.update { it.copy(saving = false, error = r.message) }
                is ApiResult.Ok -> {
                    val response = r.value
                    val text = if (!response.isSuccessful) response.errorBody()?.string() else null
                    when {
                        response.code() == 409 -> {
                            val theirs = text?.let { runCatching { ApiJson.decodeFromString(AssetConflict.serializer(), it).asset }.getOrNull() }
                            _state.update { it.copy(saving = false, conflict = theirs) }
                        }
                        !response.isSuccessful -> {
                            val msg = text?.let { runCatching { ApiJson.decodeFromString(AssetConflict.serializer(), it).error }.getOrNull() }
                            _state.update { it.copy(saving = false, error = msg ?: "Couldn't save the image.") }
                        }
                        else -> {
                            val saved = response.body()?.asset
                            // Edits made while saving stay unsaved.
                            _state.update {
                                it.copy(
                                    saving = false,
                                    version = saved?.version ?: it.version,
                                    dirty = it.raw !== raw,
                                    savedNotice = it.raw === raw,
                                    conflict = null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** Conflict: drop local edits and load their version. */
    fun takeTheirs() {
        val theirs = _state.value.conflict ?: return
        viewModelScope.launch { take(theirs) }
    }

    /** Conflict: save this version over theirs. */
    fun keepMine() {
        _state.update { it.copy(conflict = null) }
        save(force = true)
    }
}
