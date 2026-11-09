package com.docloom_android.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Docs, history and errors, as the Rust API returns them.
// Backend: docs/routes.rs + docs/model.rs, graph/routes.rs, errors/routes.rs.

@Serializable
data class DocSectionSummary(
    val id: String,
    val chapterId: String,
    val title: String,
    val position: Double = 0.0,
    val nodeId: String? = null,
    val version: Int = 0,
    val updatedByLabel: String? = null,
    val updatedAt: String = "",
)

@Serializable
data class DocSection(
    val id: String,
    val chapterId: String,
    val title: String,
    val body: String = "",
    val position: Double = 0.0,
    val nodeId: String? = null,
    val version: Int = 0,
    val updatedByLabel: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Serializable
data class DocChapter(
    val id: String,
    val title: String,
    val description: String? = null,
    val position: Double = 0.0,
    val githubRepo: String? = null,
    val githubUrl: String? = null,
    val updatedAt: String = "",
    val sections: List<DocSectionSummary> = emptyList(),
)

@Serializable
data class DocsGithub(val repoUrl: String? = null, val connected: Boolean = false, val login: String? = null)

@Serializable
data class DocsTocResponse(val chapters: List<DocChapter> = emptyList(), val github: DocsGithub = DocsGithub())

@Serializable
data class ChapterResponse(val chapter: DocChapter)

@Serializable
data class SectionResponse(val section: DocSection)

/** A 409 from a section save carries the newer version alongside the error. */
@Serializable
data class SectionConflict(val error: String? = null, val section: DocSection? = null)

@Serializable
data class ImportGithubResponse(val chapters: List<DocChapter> = emptyList(), val organization: String? = null)

@Serializable
data class GenerateDocsResponse(val created: Int = 0, val sections: List<DocSection> = emptyList())

/** Create body; the backend takes either a title or a GitHub repo to mirror. */
@Serializable
data class CreateChapterRequest(val title: String?, val description: String?, val repo: String?)

@Serializable
data class CreateSectionRequest(val chapterId: String, val title: String, val body: String)

@Serializable
data class RefTask(
    val id: String,
    val title: String,
    val status: String = "backlog",
    val number: Int? = null,
    val url: String? = null,
    val assignee: String? = null,
    val nodeId: String? = null,
)

@Serializable
data class RefNode(val id: String, val label: String = "", val type: String = "", val kind: JsonElement? = null)

@Serializable
data class RefSection(val id: String, val title: String, val chapterId: String = "", val nodeId: String? = null)

/** Everything a `{{kind:value}}` reference in a section can point at. */
@Serializable
data class DocRefs(
    val tasks: List<RefTask> = emptyList(),
    val nodes: List<RefNode> = emptyList(),
    val sections: List<RefSection> = emptyList(),
)

@Serializable
data class DocPerson(val login: String, val avatarUrl: String? = null, val source: String = "docloom", val name: String? = null)

@Serializable
data class PeopleResponse(val people: List<DocPerson> = emptyList(), val githubError: String? = null)

/** Revert result: `revision` is null when there was nothing to undo. */
@Serializable
data class RevertResponse(val version: Long = 0, val revision: Revision? = null)

@Serializable
data class ErrorEvent(
    val id: String,
    val nodeId: String? = null,
    val nodeLabel: String? = null,
    val message: String,
    val stack: String? = null,
    val method: String? = null,
    val path: String? = null,
    val status: Int? = null,
    val service: String? = null,
    val resolved: Boolean = false,
    val occurredAt: String = "",
)

@Serializable
data class ErrorsResponse(val errors: List<ErrorEvent> = emptyList())

@Serializable
data class ResolveErrorRequest(val resolved: Boolean)

@Serializable
data class IngestKeyResponse(val key: String)
