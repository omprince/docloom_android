package com.docloom_android.core.model

import kotlinx.serialization.Serializable

// Members, invites, organisations, API keys and connected apps.
// Backend: project/members.rs, team/invites.rs, team/orgs.rs, tokens/routes.rs, oauth/routes.rs.

@Serializable
data class ProjectMember(
    val userId: String,
    val username: String,
    val email: String = "",
    /** "owner", "editor" or "viewer". */
    val role: String,
    val githubLogin: String? = null,
    /** Reaches the project through its organisation rather than being added to it. */
    val viaOrg: Boolean = false,
)

@Serializable
data class MembersListResponse(val members: List<ProjectMember> = emptyList())

@Serializable
data class AddMemberRequest(val user: String, val role: String)

@Serializable
data class RoleRequest(val role: String)

@Serializable
data class PendingInvite(
    val id: String,
    val email: String,
    val role: String,
    val createdAt: String = "",
    val expiresAt: String = "",
    val expired: Boolean = false,
)

@Serializable
data class InvitesResponse(val invites: List<PendingInvite> = emptyList())

@Serializable
data class InviteRequest(val email: String, val role: String)

/** After an invite is created or resent: the link, and whether it was emailed. */
@Serializable
data class InviteResult(val inviteUrl: String, val emailSent: Boolean = false, val invites: List<PendingInvite>? = null)

/** What an invite link is for, readable before accepting. */
@Serializable
data class InvitePreview(
    /** "project" or "organization". */
    val kind: String,
    val name: String,
    val role: String,
    val email: String,
    val inviter: String? = null,
    val hasAccount: Boolean = false,
)

@Serializable
data class InviteAccepted(val kind: String, val id: String, val name: String = "")

@Serializable
data class OrgMember(val userId: String, val username: String, val email: String = "", val role: String, val addedAt: String = "")

@Serializable
data class OrgProject(val id: String, val name: String)

@Serializable
data class OrgDetail(
    val organization: Organization,
    val members: List<OrgMember> = emptyList(),
    val invites: List<PendingInvite> = emptyList(),
    val projects: List<OrgProject> = emptyList(),
)

@Serializable
data class OrganizationResponse(val organization: Organization)

@Serializable
data class OrgMembersResponse(val members: List<OrgMember> = emptyList())

/** Both fields always sent; `allowedDomain: null` removes the limit. */
@Serializable
data class OrgRequest(val name: String, val allowedDomain: String?)

@Serializable
data class ApiToken(val id: String, val name: String, val prefix: String = "", val createdAt: String = "", val lastUsedAt: String? = null)

@Serializable
data class TokensResponse(val tokens: List<ApiToken> = emptyList())

@Serializable
data class CreateTokenRequest(val name: String)

/** `secret` is shown once; the server keeps only a hash. */
@Serializable
data class CreatedToken(val token: ApiToken, val secret: String)

@Serializable
data class OAuthGrant(val id: String, val clientName: String, val createdAt: String = "", val lastUsedAt: String? = null)

@Serializable
data class GrantsResponse(val grants: List<OAuthGrant> = emptyList())
