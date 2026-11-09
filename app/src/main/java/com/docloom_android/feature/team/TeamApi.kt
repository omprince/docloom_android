package com.docloom_android.feature.team

import com.docloom_android.core.model.AddMemberRequest
import com.docloom_android.core.model.CreateTokenRequest
import com.docloom_android.core.model.CreatedToken
import com.docloom_android.core.model.GithubStatus
import com.docloom_android.core.model.GrantsResponse
import com.docloom_android.core.model.InviteAccepted
import com.docloom_android.core.model.InvitePreview
import com.docloom_android.core.model.InviteRequest
import com.docloom_android.core.model.InviteResult
import com.docloom_android.core.model.InvitesResponse
import com.docloom_android.core.model.MembersListResponse
import com.docloom_android.core.model.OkResponse
import com.docloom_android.core.model.OrgDetail
import com.docloom_android.core.model.OrgMembersResponse
import com.docloom_android.core.model.OrgRequest
import com.docloom_android.core.model.OrganizationResponse
import com.docloom_android.core.model.OrganizationsResponse
import com.docloom_android.core.model.RoleRequest
import com.docloom_android.core.model.TokensResponse
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/** People and access: project members and invites, organisations, API keys, connected apps, GitHub. */
interface TeamApi {
    // ---- project members (project/members.rs) ----
    @GET("api/projects/{pid}/members")
    suspend fun members(@Path("pid") projectId: String): MembersListResponse

    /** [AddMemberRequest.user] is a DocLoom username or email. */
    @POST("api/projects/{pid}/members")
    suspend fun addMember(@Path("pid") projectId: String, @Body body: AddMemberRequest): MembersListResponse

    @PATCH("api/projects/{pid}/members/{uid}")
    suspend fun changeMemberRole(@Path("pid") projectId: String, @Path("uid") userId: String, @Body body: RoleRequest): MembersListResponse

    @DELETE("api/projects/{pid}/members/{uid}")
    suspend fun removeMember(@Path("pid") projectId: String, @Path("uid") userId: String): MembersListResponse

    // ---- project invites (team/invites.rs) ----
    @GET("api/projects/{pid}/invites")
    suspend fun projectInvites(@Path("pid") projectId: String): InvitesResponse

    @POST("api/projects/{pid}/invites")
    suspend fun inviteToProject(@Path("pid") projectId: String, @Body body: InviteRequest): InviteResult

    @POST("api/projects/{pid}/invites/{iid}/resend")
    suspend fun resendProjectInvite(@Path("pid") projectId: String, @Path("iid") inviteId: String, @Body empty: JsonObject = JsonObject(emptyMap())): InviteResult

    @DELETE("api/projects/{pid}/invites/{iid}")
    suspend fun revokeProjectInvite(@Path("pid") projectId: String, @Path("iid") inviteId: String): InvitesResponse

    // ---- invite links ----
    @GET("api/invites/{token}")
    suspend fun invitePreview(@Path("token") token: String): InvitePreview

    @POST("api/invites/{token}/accept")
    suspend fun acceptInvite(@Path("token") token: String, @Body empty: JsonObject = JsonObject(emptyMap())): InviteAccepted

    // ---- organisations (team/orgs.rs) ----
    @GET("api/orgs")
    suspend fun organizations(): OrganizationsResponse

    @POST("api/orgs")
    suspend fun createOrganization(@Body body: OrgRequest): OrganizationResponse

    @GET("api/orgs/{id}")
    suspend fun organization(@Path("id") id: String): OrgDetail

    @PATCH("api/orgs/{id}")
    suspend fun updateOrganization(@Path("id") id: String, @Body body: OrgRequest): OrganizationResponse

    @DELETE("api/orgs/{id}")
    suspend fun deleteOrganization(@Path("id") id: String): OkResponse

    @PATCH("api/orgs/{id}/members/{uid}")
    suspend fun changeOrgRole(@Path("id") id: String, @Path("uid") userId: String, @Body body: RoleRequest): OrgMembersResponse

    @DELETE("api/orgs/{id}/members/{uid}")
    suspend fun removeOrgMember(@Path("id") id: String, @Path("uid") userId: String): OrgMembersResponse

    @POST("api/orgs/{id}/invites")
    suspend fun inviteToOrg(@Path("id") id: String, @Body body: InviteRequest): InviteResult

    @POST("api/orgs/{id}/invites/{iid}/resend")
    suspend fun resendOrgInvite(@Path("id") id: String, @Path("iid") inviteId: String, @Body empty: JsonObject = JsonObject(emptyMap())): InviteResult

    @DELETE("api/orgs/{id}/invites/{iid}")
    suspend fun revokeOrgInvite(@Path("id") id: String, @Path("iid") inviteId: String): InvitesResponse

    // ---- API keys and connected apps ----
    @GET("api/tokens")
    suspend fun tokens(): TokensResponse

    @POST("api/tokens")
    suspend fun createToken(@Body body: CreateTokenRequest): CreatedToken

    @DELETE("api/tokens/{id}")
    suspend fun revokeToken(@Path("id") id: String): Response<Unit>

    @GET("api/oauth/grants")
    suspend fun grants(): GrantsResponse

    @DELETE("api/oauth/grants/{id}")
    suspend fun revokeGrant(@Path("id") id: String): Response<Unit>

    // ---- GitHub (github/routes.rs) ----
    @GET("api/github/status")
    suspend fun githubStatus(): GithubStatus

    @POST("api/github/disconnect")
    suspend fun githubDisconnect(): OkResponse
}
