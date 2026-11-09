package com.docloom_android.feature.tasks

import com.docloom_android.core.model.CreateTaskRequest
import com.docloom_android.core.model.CreateTodoRequest
import com.docloom_android.core.model.GraphResponse
import com.docloom_android.core.model.MembersResponse
import com.docloom_android.core.model.MyTasksResponse
import com.docloom_android.core.model.SyncIssuesResponse
import com.docloom_android.core.model.TaskOutcome
import com.docloom_android.core.model.TaskResponse
import com.docloom_android.core.model.TasksResponse
import com.docloom_android.core.model.TodoResponse
import com.docloom_android.core.model.TodosResponse
import com.docloom_android.core.model.UpdateTodoRequest
import com.docloom_android.core.model.WebhookResponse
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/** Project tasks (tasks/routes.rs), My tasks, personal todos (todo/routes.rs). */
interface TasksApi {
    /** The first visit to a project with a linked repo also pulls its issues. */
    @GET("api/projects/{pid}/tasks")
    suspend fun list(@Path("pid") projectId: String): TasksResponse

    @POST("api/projects/{pid}/tasks")
    suspend fun create(@Path("pid") projectId: String, @Body body: CreateTaskRequest): TaskOutcome

    /** Create with extra fields (doc tasks send sectionId and labels). */
    @POST("api/projects/{pid}/tasks")
    suspend fun createRaw(@Path("pid") projectId: String, @Body body: JsonObject): TaskOutcome

    /**
     * Partial update. A field left out is kept; a field sent as `null` is
     * cleared, so the body is built field by field (see [taskPatch]).
     */
    @PATCH("api/projects/{pid}/tasks/{id}")
    suspend fun update(@Path("pid") projectId: String, @Path("id") id: String, @Body patch: JsonObject): TaskOutcome

    /** 204 No Content on success. */
    @DELETE("api/projects/{pid}/tasks/{id}")
    suspend fun delete(@Path("pid") projectId: String, @Path("id") id: String): Response<Unit>

    @POST("api/projects/{pid}/tasks/{id}/issue")
    suspend fun openIssue(@Path("pid") projectId: String, @Path("id") id: String): TaskResponse

    @POST("api/projects/{pid}/tasks/sync")
    suspend fun syncIssues(@Path("pid") projectId: String): SyncIssuesResponse

    @GET("api/projects/{pid}/tasks/assignees")
    suspend fun assignees(@Path("pid") projectId: String): MembersResponse

    @POST("api/projects/{pid}/tasks/webhook")
    suspend fun enableWebhook(@Path("pid") projectId: String): WebhookResponse

    @DELETE("api/projects/{pid}/tasks/webhook")
    suspend fun disableWebhook(@Path("pid") projectId: String): WebhookResponse

    /** Map nodes, for the "architecture area" picker. */
    @GET("api/projects/{pid}/graph")
    suspend fun graph(@Path("pid") projectId: String): GraphResponse

    @GET("api/tasks/mine")
    suspend fun mine(): MyTasksResponse

    @GET("api/todos")
    suspend fun todos(): TodosResponse

    @POST("api/todos")
    suspend fun createTodo(@Body body: CreateTodoRequest): TodoResponse

    @PATCH("api/todos/{id}")
    suspend fun updateTodo(@Path("id") id: String, @Body body: UpdateTodoRequest): TodoResponse

    @DELETE("api/todos/{id}")
    suspend fun deleteTodo(@Path("id") id: String): Response<Unit>
}
