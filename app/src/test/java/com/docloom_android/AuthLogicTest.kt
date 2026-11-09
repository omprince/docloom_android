package com.docloom_android

import com.docloom_android.core.ui.parseIso
import com.docloom_android.core.ui.timeAgo
import com.docloom_android.feature.auth.AuthViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AuthLogicTest {
    @Test
    fun extractsTokenFromResetLink() {
        assertEquals("abc123", AuthViewModel.extractResetToken("http://localhost:3000/reset-password?token=abc123"))
        assertEquals("abc123", AuthViewModel.extractResetToken("https://x.dev/reset-password?a=1&token=abc123#top"))
        assertEquals("abc123", AuthViewModel.extractResetToken("  abc123 "))
    }

    @Test
    fun signupValidationMatchesBackendRules() {
        assertNull(AuthViewModel.validateSignup("jane_doe", "jane@example.com", "supersecret"))
        assertNotNull(AuthViewModel.validateSignup("ab", "jane@example.com", "supersecret"))
        assertNotNull(AuthViewModel.validateSignup("a".repeat(21), "jane@example.com", "supersecret"))
        assertNotNull(AuthViewModel.validateSignup("jane", "not-an-email", "supersecret"))
        assertNotNull(AuthViewModel.validateSignup("jane", "jane@example.com", "short"))
    }

    @Test
    fun parsesBackendTimestamps() {
        assertEquals(0L, parseIso("1970-01-01T00:00:00Z"))
        assertEquals(1_500L, parseIso("1970-01-01T00:00:01.500123Z"))
        assertEquals(0L, parseIso("1970-01-01T05:30:00+05:30"))
        assertNull(parseIso("yesterday"))
    }

    @Test
    fun timeAgoMatchesWebsite() {
        val now = 10_000_000_000L
        assertEquals("never", timeAgo(null, now))
        assertEquals("just now", timeAgo("1970-04-26T17:46:30Z", now))
        val oneHourAgo = "1970-04-26T16:46:40Z"
        assertEquals("1 hour ago", timeAgo(oneHourAgo, now))
    }
}
