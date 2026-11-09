package com.docloom_android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Tasks, todos and graph nodes, as the Rust API returns them.
// Backend: tasks/model.rs, tasks/routes.rs, todo/routes.rs, graph/routes.rs.

@Serializable
data class Task(
    val id: String,
    val projectId: String,
    val title: String,
    val body: String? = null,
    val scenario: String? = null,
    /** "backlog", "in_progress" or "done". */
    val status: String = "backlog",
    val position: Double = 0.0,
    val nodeId: String? = null,
    /** Label of the linked map node (list endpoint only). */
    val nodeLabel: String? = null,
    val assigneeLogin: String? = null,
    val assigneeAvatarUrl: String? = null,
    /** "manual" or "github". */
    val source: String = "manual",
    val githubIssueNumber: Int? = null,
    val githubHtmlUrl: String? = null,
    val githubState: String? = null,
    val sectionId: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Serializable
data class TaskGithub(
    val repoUrl: String? = null,
    val connected: Boolean = false,
    val login: String? = null,
    val webhook: Boolean = false,
)

@Serializable
data class TasksResponse(
    val tasks: List<Task> = emptyList(),
    val github: TaskGithub = TaskGithub(),
    val syncError: String? = null,
)

/** Create/update result: saved in DocLoom, with the GitHub write's error if that part failed. */
@Serializable
data class TaskOutcome(val task: Task, val githubError: String? = null)

@Serializable
data class TaskResponse(val task: Task)

/** No defaults: every field is sent, matching what the website sends. */
@Serializable
data class CreateTaskRequest(
    val title: String,
    val status: String,
    val body: String?,
    val scenario: String?,
    val nodeId: String?,
    val assigneeLogin: String?,
    val createIssue: Boolean,
)

@Serializable
data class SyncIssuesResponse(val synced: Int = 0, val tasks: List<Task> = emptyList())

@Serializable
data class WebhookResponse(val webhook: Boolean = false)

@Serializable
data class GithubMember(
    val login: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
data class MembersResponse(val members: List<GithubMember> = emptyList())

/** A task in "My tasks": the task's fields plus its project's name. */
@Serializable
data class MyTask(
    val id: String,
    val projectId: String,
    val projectName: String = "",
    val title: String,
    val status: String = "backlog",
    val assigneeLogin: String? = null,
    val githubIssueNumber: Int? = null,
    val githubHtmlUrl: String? = null,
    val updatedAt: String = "",
)

@Serializable
data class MyTasksResponse(val tasks: List<MyTask> = emptyList(), val githubLogin: String? = null)

@Serializable
data class Todo(val id: String, val title: String, val isDone: Boolean = false)

@Serializable
data class TodosResponse(val todos: List<Todo> = emptyList())

@Serializable
data class TodoResponse(val todo: Todo)

@Serializable
data class CreateTodoRequest(val title: String)

/** The backend reads this one field in snake_case. */
@Serializable
data class UpdateTodoRequest(@SerialName("is_done") val isDone: Boolean)

@Serializable
data class GraphNode(val id: String, val type: String? = null, val data: JsonObject? = null)

@Serializable
data class GraphResponse(val version: Long = 0, val nodes: List<GraphNode> = emptyList())
