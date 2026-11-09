package com.docloom_android

import com.docloom_android.core.model.AddMemberRequest
import com.docloom_android.core.model.CreatedToken
import com.docloom_android.core.model.GithubMember
import com.docloom_android.core.model.InvitePreview
import com.docloom_android.core.model.OrgDetail
import com.docloom_android.core.model.OrgRequest
import com.docloom_android.core.network.ApiJson
import com.docloom_android.feature.team.EMAIL_RE
import com.docloom_android.feature.team.inviteToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamLogicTest {
    @Test
    fun inviteTokensComeFromLinksOrRawTokens() {
        assertEquals("dlp_abc123", inviteToken("https://docloom.app/invite/dlp_abc123"))
        assertEquals("dlo_xyz", inviteToken("  http://10.0.2.2:3000/invite/dlo_xyz?utm=mail "))
        assertEquals("dlp_raw", inviteToken("dlp_raw"))
    }

    @Test
    fun emailCheckMatchesWebsite() {
        assertTrue(EMAIL_RE.matches("jane@acme.com"))
        assertFalse(EMAIL_RE.matches("janedoe"))
        assertFalse(EMAIL_RE.matches("jane@acme"))
    }

    @Test
    fun requestBodiesMatchBackend() {
        // An empty domain is sent as null so the backend removes the limit.
        assertEquals("""{"name":"Acme","allowedDomain":null}""", ApiJson.encodeToString(OrgRequest.serializer(), OrgRequest("Acme", null)))
        assertEquals("""{"user":"jane","role":"viewer"}""", ApiJson.encodeToString(AddMemberRequest.serializer(), AddMemberRequest("jane", "viewer")))
    }

    @Test
    fun parsesTeamResponses() {
        val detail = ApiJson.decodeFromString(
            OrgDetail.serializer(),
            """{"organization":{"id":"o1","name":"Acme","slug":"acme","allowedDomain":null,"role":"admin","memberCount":2,"projectCount":1},
               "members":[{"userId":"u1","username":"jane","email":"jane@acme.com","role":"owner","addedAt":"2026-01-01T00:00:00Z"}],
               "invites":[],"projects":[{"id":"p1","name":"Shop"}]}""",
        )
        assertEquals("admin", detail.organization.role)
        assertEquals("Shop", detail.projects[0].name)
        val preview = ApiJson.decodeFromString(InvitePreview.serializer(), """{"kind":"project","name":"Shop","role":"editor","email":"a@b.co","inviter":null,"hasAccount":true}""")
        assertTrue(preview.hasAccount)
        val token = ApiJson.decodeFromString(CreatedToken.serializer(), """{"token":{"id":"t","name":"CI","prefix":"dl_ab","createdAt":"x","lastUsedAt":null},"secret":"dl_ab.secret"}""")
        assertEquals("dl_ab.secret", token.secret)
        assertEquals("https://a/u.png", ApiJson.decodeFromString(GithubMember.serializer(), """{"login":"x","avatar_url":"https://a/u.png"}""").avatarUrl)
    }
}
