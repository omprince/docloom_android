package com.docloom_android.feature.tasks

import androidx.compose.ui.graphics.Color
import com.docloom_android.core.model.GraphNode
import com.docloom_android.core.model.Task
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

// Port of the website's lib/tasks.ts, plus the board's move/patch rules.

data class TaskStatusMeta(val status: String, val label: String, val color: Color)

/** Board columns, in order; colours match the task nodes on the map. */
val TASK_STATUSES = listOf(
    TaskStatusMeta("backlog", "Backlog", Color(0xFFA8ADBF)),
    TaskStatusMeta("in_progress", "In progress", Color(0xFFFBBF24)),
    TaskStatusMeta("done", "Done", Color(0xFF34D399)),
)

fun statusMeta(status: String?): TaskStatusMeta = TASK_STATUSES.firstOrNull { it.status == status } ?: TASK_STATUSES[0]

/** `owner/repo` from a GitHub URL, for display. */
fun repoName(url: String?): String? {
    if (url == null) return null
    val match = Regex("""github\.com[/:]([^/]+)/([^/#?]+?)(?:\.git)?/?$""", RegexOption.IGNORE_CASE).find(url)
    return match?.let { "${it.groupValues[1]}/${it.groupValues[2]}" } ?: url
}

/** A map node a task can be about: anything but sections and task nodes. */
data class TaskArea(val id: String, val label: String, val type: String)

fun areasFromGraph(nodes: List<GraphNode>): List<TaskArea> = nodes
    .filter { it.type != "section" && it.type != "task" }
    .map { node ->
        val label = listOf("label", "title", "name")
            .firstNotNullOfOrNull { key ->
                (node.data?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
            }
        TaskArea(node.id, label ?: "Untitled", node.type ?: "")
    }
    .sortedBy { it.label.lowercase() }

/** Tasks of one column, in board order. */
fun columnTasks(tasks: List<Task>, status: String): List<Task> =
    tasks.filter { it.status == status }.sortedBy { it.position }

/**
 * Position for [taskId] placed in [status] before [beforeId] (null = at the
 * end), halfway between its new neighbours. Null when nothing would change.
 */
fun positionFor(tasks: List<Task>, taskId: String, status: String, beforeId: String?): Double? {
    val task = tasks.firstOrNull { it.id == taskId } ?: return null
    if (beforeId == taskId) return null
    val column = columnTasks(tasks, status).filter { it.id != taskId }
    val index = if (beforeId == null) column.size else column.indexOfFirst { it.id == beforeId }
    val at = if (index < 0) column.size else index
    val prev = column.getOrNull(at - 1)?.position
    val next = column.getOrNull(at)?.position
    val position = when {
        prev == null && next == null -> 0.0
        prev == null -> next!! - 1
        next == null -> prev + 1
        else -> (prev + next) / 2
    }
    return if (task.status == status && task.position == position) null else position
}

/** The editor's values; blank text means "none". */
data class TaskDraft(
    val title: String,
    val status: String,
    val assignee: String,
    val area: String,
    val scenario: String,
    val body: String,
)

private fun String.orNull() = trim().ifEmpty { null }

private fun jsonOf(value: String?) = if (value == null) JsonNull else JsonPrimitive(value)

/**
 * PATCH body with only the fields that changed (website TaskDrawer rules).
 * Cleared fields are sent as `null`. Empty when nothing changed.
 */
fun taskPatch(task: Task, draft: TaskDraft, avatarFor: (String) -> String?): JsonObject = buildJsonObject {
    if (draft.title.trim() != task.title) put("title", JsonPrimitive(draft.title.trim()))
    if (draft.status != task.status) put("status", JsonPrimitive(draft.status))
    if (draft.body.orNull() != task.body?.orNull()) put("body", jsonOf(draft.body.orNull()))
    if (draft.scenario.orNull() != task.scenario?.orNull()) put("scenario", jsonOf(draft.scenario.orNull()))
    if (draft.area.orNull() != task.nodeId) put("nodeId", jsonOf(draft.area.orNull()))
    if (draft.assignee.orNull() != task.assigneeLogin) {
        put("assigneeLogin", jsonOf(draft.assignee.orNull()))
        draft.assignee.orNull()?.let(avatarFor)?.let { put("assigneeAvatarUrl", JsonPrimitive(it)) }
    }
}

/** Body for a move on the board. */
fun movePatch(task: Task, status: String, position: Double): JsonObject = buildJsonObject {
    put("position", JsonPrimitive(position))
    if (task.status != status) put("status", JsonPrimitive(status))
}

/** Opens links only to GitHub over HTTPS (the URLs come from the server). */
fun isSafeGithubUrl(url: String?): Boolean =
    url != null && (url.startsWith("https://github.com/") || url.startsWith("https://www.github.com/"))
