package com.docloom_android

import com.docloom_android.core.model.GraphNode
import com.docloom_android.core.model.MyTasksResponse
import com.docloom_android.core.model.Task
import com.docloom_android.core.model.UpdateTodoRequest
import com.docloom_android.core.network.ApiJson
import com.docloom_android.feature.tasks.TaskDraft
import com.docloom_android.feature.tasks.areasFromGraph
import com.docloom_android.feature.tasks.isSafeGithubUrl
import com.docloom_android.feature.tasks.movePatch
import com.docloom_android.feature.tasks.positionFor
import com.docloom_android.feature.tasks.repoName
import com.docloom_android.feature.tasks.taskPatch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskLogicTest {
    private fun task(id: String, status: String, position: Double, vararg extra: Pair<String, String?>): Task {
        val m = extra.toMap()
        return Task(
            id = id, projectId = "p", title = m["title"] ?: id, status = status, position = position,
            body = m["body"], scenario = m["scenario"], nodeId = m["nodeId"], assigneeLogin = m["assignee"],
        )
    }

    private val board = listOf(
        task("a", "backlog", 1.0),
        task("b", "backlog", 2.0),
        task("c", "backlog", 3.0),
        task("d", "done", 5.0),
    )

    @Test
    fun positionsMatchWebsiteMoves() {
        assertEquals(6.0, positionFor(board, "a", "done", null)!!, 0.0) // end of another column
        assertEquals(4.0, positionFor(board, "a", "done", "d")!!, 0.0) // before the first card
        assertEquals(2.5, positionFor(board, "a", "backlog", "c")!!, 0.0) // between b and c
        assertEquals(0.0, positionFor(board, "a", "in_progress", null)!!, 0.0) // empty column
        assertNull(positionFor(board, "a", "backlog", "b")) // already there
        assertNull(positionFor(board, "a", "backlog", "a"))
    }

    @Test
    fun moveSendsStatusOnlyWhenItChanges() {
        assertEquals(buildJsonObject { put("position", JsonPrimitive(2.5)) }, movePatch(board[0], "backlog", 2.5))
        assertEquals(
            buildJsonObject { put("position", JsonPrimitive(6.0)); put("status", JsonPrimitive("done")) },
            movePatch(board[0], "done", 6.0),
        )
    }

    @Test
    fun patchHasOnlyChangedFieldsAndNullsForCleared() {
        val t = task("a", "backlog", 1.0, "title" to "Fix login", "body" to "Old", "nodeId" to "n1", "assignee" to "jane")
        val same = TaskDraft("Fix login", "backlog", "jane", "n1", "", "Old")
        assertTrue(taskPatch(t, same) { null }.isEmpty())

        val changed = TaskDraft("Fix login ", "in_progress", "", "n1", "Scenario", "  ")
        val patch = taskPatch(t, changed) { null }
        assertEquals(setOf("status", "body", "scenario", "assigneeLogin"), patch.keys)
        assertEquals("null", patch["body"].toString())
        assertEquals("null", patch["assigneeLogin"].toString())

        val reassigned = taskPatch(t, same.copy(assignee = "bob")) { "https://avatars/bob" }
        assertEquals("\"https://avatars/bob\"", reassigned["assigneeAvatarUrl"].toString())
    }

    @Test
    fun areasSkipSectionsAndTasks() {
        val nodes = listOf(
            GraphNode("1", "service", buildJsonObject { put("label", JsonPrimitive("Payments")) }),
            GraphNode("2", "section", buildJsonObject { put("label", JsonPrimitive("Group")) }),
            GraphNode("3", "task", null),
            GraphNode("4", "erTable", buildJsonObject { put("name", JsonPrimitive("users")) }),
            GraphNode("5", "note", null),
        )
        assertEquals(listOf("Payments", "Untitled", "users"), areasFromGraph(nodes).map { it.label })
    }

    @Test
    fun helpers() {
        assertEquals("acme/shop", repoName("https://github.com/acme/shop.git"))
        assertTrue(isSafeGithubUrl("https://github.com/acme/shop/issues/3"))
        assertFalse(isSafeGithubUrl("javascript:alert(1)"))
        assertFalse(isSafeGithubUrl("https://github.com.evil.io/x"))
        assertEquals("""{"is_done":true}""", ApiJson.encodeToString(UpdateTodoRequest.serializer(), UpdateTodoRequest(true)))
    }

    @Test
    fun parsesMyTasksWithFlattenedProject() {
        val json = """{"tasks":[{"id":"t1","projectId":"p1","title":"Do it","status":"in_progress","position":1.5,
            "source":"manual","createdAt":"x","updatedAt":"y","projectName":"Shop","githubIssueNumber":7}],"githubLogin":"jane"}"""
        val r = ApiJson.decodeFromString(MyTasksResponse.serializer(), json)
        assertEquals("Shop", r.tasks[0].projectName)
        assertEquals(7, r.tasks[0].githubIssueNumber)
        assertEquals("jane", r.githubLogin)
    }
}
