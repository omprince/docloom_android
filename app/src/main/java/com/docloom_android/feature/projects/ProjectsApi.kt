package com.docloom_android.feature.projects

import com.docloom_android.core.model.CreateProjectRequest
import com.docloom_android.core.model.GithubReposResponse
import com.docloom_android.core.model.GithubStatus
import com.docloom_android.core.model.OrganizationsResponse
import com.docloom_android.core.model.ProjectResponse
import com.docloom_android.core.model.ProjectsResponse
import com.docloom_android.core.model.RevisionsResponse
import com.docloom_android.core.model.ScanRepoRequest
import com.docloom_android.core.model.ScanResult
import com.docloom_android.core.model.ScanSqlRequest
import com.docloom_android.core.model.UpdateProjectRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Projects and what the project pages need. Backend: project/routes.rs,
 * scan/routes.rs, graph/routes.rs, github/routes.rs, team/orgs.rs.
 */
interface ProjectsApi {
    @GET("api/projects")
    suspend fun list(): ProjectsResponse

    @GET("api/projects/{id}")
    suspend fun get(@Path("id") id: String): ProjectResponse

    @POST("api/projects")
    suspend fun create(@Body body: CreateProjectRequest): ProjectResponse

    @PATCH("api/projects/{id}")
    suspend fun update(@Path("id") id: String, @Body body: UpdateProjectRequest): ProjectResponse

    /** 204 No Content on success. */
    @DELETE("api/projects/{id}")
    suspend fun delete(@Path("id") id: String): Response<Unit>

    @GET("api/projects/{id}/graph/revisions")
    suspend fun revisions(@Path("id") id: String, @Query("limit") limit: Int): RevisionsResponse

    @POST("api/projects/{id}/scan/repo")
    suspend fun scanRepo(@Path("id") id: String, @Body body: ScanRepoRequest): ScanResult

    @POST("api/projects/{id}/scan/sql")
    suspend fun scanSql(@Path("id") id: String, @Body body: ScanSqlRequest): ScanResult

    @GET("api/github/status")
    suspend fun githubStatus(): GithubStatus

    @GET("api/github/repos")
    suspend fun githubRepos(): GithubReposResponse

    @GET("api/orgs")
    suspend fun organizations(): OrganizationsResponse
}
