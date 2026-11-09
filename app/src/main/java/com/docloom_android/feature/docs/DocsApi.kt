package com.docloom_android.feature.docs

import com.docloom_android.core.model.ChapterResponse
import com.docloom_android.core.model.CreateChapterRequest
import com.docloom_android.core.model.CreateSectionRequest
import com.docloom_android.core.model.DocRefs
import com.docloom_android.core.model.DocsTocResponse
import com.docloom_android.core.model.ErrorsResponse
import com.docloom_android.core.model.GenerateDocsResponse
import com.docloom_android.core.model.ImportGithubResponse
import com.docloom_android.core.model.IngestKeyResponse
import com.docloom_android.core.model.OkResponse
import com.docloom_android.core.model.PeopleResponse
import com.docloom_android.core.model.ResolveErrorRequest
import com.docloom_android.core.model.RevertResponse
import com.docloom_android.core.model.RevisionsResponse
import com.docloom_android.core.model.SectionResponse
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Docs (docs/routes.rs), History (graph/routes.rs) and Errors (errors/routes.rs). */
interface DocsApi {
    @GET("api/projects/{pid}/docs")
    suspend fun toc(@Path("pid") projectId: String): DocsTocResponse

    @POST("api/projects/{pid}/docs/chapters")
    suspend fun createChapter(@Path("pid") projectId: String, @Body body: CreateChapterRequest): ChapterResponse

    @PATCH("api/projects/{pid}/docs/chapters/{id}")
    suspend fun updateChapter(@Path("pid") projectId: String, @Path("id") id: String, @Body patch: JsonObject): ChapterResponse

    @DELETE("api/projects/{pid}/docs/chapters/{id}")
    suspend fun deleteChapter(@Path("pid") projectId: String, @Path("id") id: String): Response<Unit>

    @POST("api/projects/{pid}/docs/chapters/import-github")
    suspend fun importGithub(@Path("pid") projectId: String): ImportGithubResponse

    @POST("api/projects/{pid}/docs/generate")
    suspend fun generate(@Path("pid") projectId: String): GenerateDocsResponse

    @POST("api/projects/{pid}/docs/sections")
    suspend fun createSection(@Path("pid") projectId: String, @Body body: CreateSectionRequest): SectionResponse

    @GET("api/projects/{pid}/docs/sections/{id}")
    suspend fun section(@Path("pid") projectId: String, @Path("id") id: String): SectionResponse

    /**
     * Partial update with `baseVersion`. Returned raw: a 409 is not a failure
     * here but "someone else saved first", with their version in the body.
     */
    @PATCH("api/projects/{pid}/docs/sections/{id}")
    suspend fun updateSection(@Path("pid") projectId: String, @Path("id") id: String, @Body patch: JsonObject): Response<SectionResponse>

    @DELETE("api/projects/{pid}/docs/sections/{id}")
    suspend fun deleteSection(@Path("pid") projectId: String, @Path("id") id: String): Response<Unit>

    @GET("api/projects/{pid}/docs/refs")
    suspend fun refs(@Path("pid") projectId: String): DocRefs

    @GET("api/projects/{pid}/docs/people")
    suspend fun people(@Path("pid") projectId: String): PeopleResponse

    // ---- History ----

    @GET("api/projects/{pid}/graph/revisions")
    suspend fun revisions(
        @Path("pid") projectId: String,
        @Query("limit") limit: Int,
        @Query("before") before: Long? = null,
    ): RevisionsResponse

    @POST("api/projects/{pid}/graph/revisions/{version}/revert")
    suspend fun revert(@Path("pid") projectId: String, @Path("version") version: Long): RevertResponse

    // ---- Errors ----

    @GET("api/projects/{pid}/errors")
    suspend fun errors(@Path("pid") projectId: String, @Query("resolved") resolved: Boolean): ErrorsResponse

    @PATCH("api/projects/{pid}/errors/{id}")
    suspend fun setResolved(@Path("pid") projectId: String, @Path("id") id: String, @Body body: ResolveErrorRequest): OkResponse

    @GET("api/projects/{pid}/errors/key")
    suspend fun ingestKey(@Path("pid") projectId: String): IngestKeyResponse

    @POST("api/projects/{pid}/errors/key/rotate")
    suspend fun rotateIngestKey(@Path("pid") projectId: String): IngestKeyResponse
}
