package com.docloom_android.feature.map

import com.docloom_android.core.model.Revision
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** The whole map, nodes and edges as stored (React Flow JSON). */
@Serializable
data class FullGraph(val version: Long = 0, val nodes: List<JsonObject> = emptyList(), val edges: List<JsonObject> = emptyList())

@Serializable
data class ChangesRequest(val changes: List<JsonObject>, val clientId: String)

@Serializable
data class ChangesResponse(val version: Long = 0)

@Serializable
data class RevisionFeed(val version: Long = 0, val revisions: List<Revision> = emptyList())

/** The map: graph/routes.rs. The live stream (`/graph/stream`) is read by [MapLiveStream]. */
interface MapApi {
    @GET("api/projects/{pid}/graph")
    suspend fun graph(@Path("pid") projectId: String): FullGraph

    /** Returned raw: a 4xx means the batch was refused and the map should reload. */
    @POST("api/projects/{pid}/graph/changes")
    suspend fun changes(@Path("pid") projectId: String, @Body body: ChangesRequest): Response<ChangesResponse>

    @GET("api/projects/{pid}/graph/revisions")
    suspend fun since(@Path("pid") projectId: String, @Query("since") since: Long, @Query("limit") limit: Int): RevisionFeed
}
