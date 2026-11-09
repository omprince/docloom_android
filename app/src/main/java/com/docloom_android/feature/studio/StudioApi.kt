package com.docloom_android.feature.studio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

// Studio images: backend assets/routes.rs. An asset's document is a tree of
// layers (website lib/studio/types.ts); raster pixels are PNG blobs.

@Serializable
data class AssetSummary(
    val id: String,
    val folderId: String? = null,
    val name: String,
    val width: Int = 0,
    val height: Int = 0,
    val version: Int = 0,
    val layerCount: Int = 0,
    val isSpriteSheet: Boolean = false,
    val hasPreview: Boolean = false,
    val updatedByLabel: String? = null,
    val updatedAt: String = "",
    /** Only in the cross-project Studio list. */
    val projectId: String? = null,
    val projectName: String? = null,
)

@Serializable
data class AssetFolder(val id: String, val parentId: String? = null, val name: String)

@Serializable
data class StudioAssetsResponse(val assets: List<AssetSummary> = emptyList())

@Serializable
data class LibraryResponse(val folders: List<AssetFolder> = emptyList(), val assets: List<AssetSummary> = emptyList())

/** A full asset: summary fields plus the document. */
@Serializable
data class AssetFull(
    val id: String,
    val folderId: String? = null,
    val name: String,
    val width: Int = 0,
    val height: Int = 0,
    val version: Int = 0,
    val updatedByLabel: String? = null,
    val doc: JsonObject,
)

@Serializable
data class AssetFullResponse(val asset: AssetFull)

@Serializable
data class AssetSummaryResponse(val asset: AssetSummary)

/** A 409 save: someone else's newer version comes back. */
@Serializable
data class AssetConflict(val error: String? = null, val asset: AssetFull? = null)

@Serializable
data class FolderResponse(val folder: AssetFolder)

@Serializable
data class GridSpec(val cols: Int, val rows: Int, val cellW: Int, val cellH: Int, val gap: Int = 0, val padding: Int = 0)

/** Every field sent: the backend reads a sprite sheet from `grid`. */
@Serializable
data class CreateAssetRequest(
    val name: String,
    val width: Int,
    val height: Int,
    val folderId: String?,
    val background: String?,
    val grid: GridSpec?,
    val frameGroups: Boolean,
)

@Serializable
data class CreateFolderRequest(val name: String, val parentId: String?)

interface StudioApi {
    @GET("api/studio/assets")
    suspend fun allAssets(): StudioAssetsResponse

    @GET("api/projects/{pid}/assets")
    suspend fun library(@Path("pid") projectId: String): LibraryResponse

    @POST("api/projects/{pid}/assets")
    suspend fun create(@Path("pid") projectId: String, @Body body: CreateAssetRequest): AssetFullResponse

    @GET("api/projects/{pid}/assets/{id}")
    suspend fun asset(@Path("pid") projectId: String, @Path("id") id: String): AssetFullResponse

    /**
     * Rename, move (`folderId`, null = top level) or save `doc` with
     * `baseVersion` and a `preview`. Raw, because 409 means "someone saved first".
     */
    @PATCH("api/projects/{pid}/assets/{id}")
    suspend fun update(@Path("pid") projectId: String, @Path("id") id: String, @Body body: JsonObject): Response<AssetSummaryResponse>

    @DELETE("api/projects/{pid}/assets/{id}")
    suspend fun delete(@Path("pid") projectId: String, @Path("id") id: String): Response<Unit>

    @POST("api/projects/{pid}/assets/{id}/duplicate")
    suspend fun duplicate(@Path("pid") projectId: String, @Path("id") id: String): AssetSummaryResponse

    @POST("api/projects/{pid}/assets/folders")
    suspend fun createFolder(@Path("pid") projectId: String, @Body body: CreateFolderRequest): FolderResponse

    @PATCH("api/projects/{pid}/assets/folders/{fid}")
    suspend fun updateFolder(@Path("pid") projectId: String, @Path("fid") folderId: String, @Body body: JsonObject): FolderResponse

    /** Deletes the folder and everything inside it. */
    @DELETE("api/projects/{pid}/assets/folders/{fid}")
    suspend fun deleteFolder(@Path("pid") projectId: String, @Path("fid") folderId: String): Response<Unit>
}

/**
 * Loads PNGs (library previews, layer pixels) with the signed-in client.
 * Blobs never change, so they're cached in memory.
 */
class StudioImages(baseUrl: String, private val client: OkHttpClient) {
    private val base = baseUrl.trimEnd('/')
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun previewPath(projectId: String, asset: AssetSummary) = "/api/projects/$projectId/assets/${asset.id}/preview?v=${asset.version}"
    fun blobPath(projectId: String, assetId: String, blobId: String) = "/api/projects/$projectId/assets/$assetId/blobs/$blobId"

    fun cached(path: String): Bitmap? = cache.get(path)

    /** Null when missing or unreadable. */
    suspend fun load(path: String): Bitmap? {
        cache.get(path)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(base + path).build()).execute().use { res ->
                    if (!res.isSuccessful) return@use null
                    res.body.bytes().let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
            }.getOrNull()?.also { cache.put(path, it) }
        }
    }
}
