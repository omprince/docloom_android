package com.docloom_android

import com.docloom_android.core.model.CreateProjectRequest
import com.docloom_android.core.model.ProjectsResponse
import com.docloom_android.core.model.Revision
import com.docloom_android.core.model.RevisionChange
import com.docloom_android.core.model.UpdateProjectRequest
import com.docloom_android.core.network.ApiJson
import com.docloom_android.core.ui.summarize
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectLogicTest {
    private val obj = JsonObject(emptyMap())

    private fun revision(vararg changes: RevisionChange) =
        Revision(id = 1, version = 1, actorType = "user", changes = changes.toList(), createdAt = "2026-01-01T00:00:00Z")

    @Test
    fun summarizeMatchesWebsite() {
        val r = revision(
            RevisionChange("node", before = null, after = obj),
            RevisionChange("node", before = null, after = obj),
            RevisionChange("node", before = obj, after = obj),
            RevisionChange("edge", before = obj, after = null),
        )
        assertEquals("+2 nodes · 1 node changed · −1 connection", summarize(r))
        assertEquals("No changes", summarize(revision()))
    }

    @Test
    fun unlinkingRepoSendsExplicitNull() {
        // The backend treats a missing repoUrl as "unchanged" and null as "unlink".
        assertEquals(
            """{"name":"Shop","repoUrl":null}""",
            ApiJson.encodeToString(UpdateProjectRequest.serializer(), UpdateProjectRequest("Shop", null)),
        )
        assertEquals(
            """{"name":"Shop","repoUrl":null,"orgId":null}""",
            ApiJson.encodeToString(CreateProjectRequest.serializer(), CreateProjectRequest("Shop", null, null)),
        )
    }

    @Test
    fun parsesProjectListFromBackend() {
        val json = """{"projects":[{"id":"p1","name":"Shop","repoUrl":null,"graphVersion":3,
            "createdAt":"2026-01-01T00:00:00+00:00","nodeCount":4,"edgeCount":2,"tableCount":1,
            "openErrorCount":0,"openTaskCount":5,"sectionCount":2,"role":"owner","orgId":null,
            "orgName":null,"memberCount":1,"githubWebhookActive":false,"lastChangeAt":null,"newField":1}]}"""
        val projects = ApiJson.decodeFromString(ProjectsResponse.serializer(), json).projects
        assertEquals(1, projects.size)
        assertEquals(5, projects[0].openTaskCount)
    }
}
