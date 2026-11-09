package com.docloom_android.core.model

import kotlinx.serialization.Serializable

// Kotlin copies of the shapes the Rust API returns (camelCase JSON).
// Source of truth: docloom_website_FE/src/lib/types.ts.

@Serializable
data class User(val id: String, val email: String, val username: String)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class SignupRequest(val username: String, val email: String, val password: String)

@Serializable
data class ForgotPasswordRequest(val email: String)

@Serializable
data class ResetPasswordRequest(val token: String, val password: String)

/** `token` is present only because the app sends `X-DocLoom-Client: mobile`. */
@Serializable
data class AuthResponse(val user: User, val token: String? = null)

@Serializable
data class MeResponse(val user: User? = null)

@Serializable
data class OkResponse(val ok: Boolean = true)

/** `resetUrl` is returned only by a development backend. */
@Serializable
data class ForgotPasswordResponse(val ok: Boolean = true, val resetUrl: String? = null)

@Serializable
data class ThemePreference(val theme: String)

@Serializable
data class Project(
    val id: String,
    val name: String,
    val repoUrl: String? = null,
    val graphVersion: Long = 0,
    val createdAt: String = "",
    val nodeCount: Int = 0,
    val edgeCount: Int = 0,
    val tableCount: Int = 0,
    val openErrorCount: Int = 0,
    val openTaskCount: Int = 0,
    val sectionCount: Int = 0,
    val role: String = "viewer",
    val orgId: String? = null,
    val orgName: String? = null,
    val memberCount: Int = 0,
    val githubWebhookActive: Boolean = false,
    val lastChangeAt: String? = null,
)

@Serializable
data class ProjectsResponse(val projects: List<Project> = emptyList())

@Serializable
data class ProjectResponse(val project: Project)

// Request bodies have no default values on purpose: kotlinx.serialization
// skips defaults, and the backend treats an explicit `null` differently from
// a missing field (PATCH `repoUrl: null` unlinks the repo).
@Serializable
data class CreateProjectRequest(val name: String, val repoUrl: String?, val orgId: String?)

@Serializable
data class UpdateProjectRequest(val name: String, val repoUrl: String?)

@Serializable
data class ScanSqlRequest(val sql: String)

@Serializable
data class ScanRepoRequest(val repoUrl: String?)

@Serializable
data class ScanStats(val tables: Int = 0, val endpoints: Int = 0)

/** `revision` is null when the scan found nothing new. */
@Serializable
data class ScanResult(
    val stats: ScanStats = ScanStats(),
    val revision: kotlinx.serialization.json.JsonElement? = null,
)

@Serializable
data class RevisionChange(
    val entity: String,
    val id: String = "",
    val before: kotlinx.serialization.json.JsonObject? = null,
    val after: kotlinx.serialization.json.JsonObject? = null,
)

@Serializable
data class Revision(
    val id: Long,
    val version: Long,
    val actorType: String,
    val actorLabel: String = "",
    val reason: String? = null,
    val changes: List<RevisionChange> = emptyList(),
    val clientId: String? = null,
    val createdAt: String,
)

@Serializable
data class RevisionsResponse(val version: Long = 0, val revisions: List<Revision> = emptyList())

@Serializable
data class GithubStatus(val connected: Boolean = false, val username: String? = null, val configured: Boolean = true)

@Serializable
data class GithubRepo(
    val id: Long,
    val name: String,
    val fullName: String,
    val private: Boolean = false,
    val htmlUrl: String,
)

@Serializable
data class GithubReposResponse(val repos: List<GithubRepo> = emptyList())

@Serializable
data class Organization(
    val id: String,
    val name: String,
    val slug: String = "",
    val allowedDomain: String? = null,
    val role: String = "member",
    val memberCount: Int = 0,
    val projectCount: Int = 0,
)

@Serializable
data class OrganizationsResponse(val organizations: List<Organization> = emptyList())

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class DeleteAccountRequest(val password: String)
