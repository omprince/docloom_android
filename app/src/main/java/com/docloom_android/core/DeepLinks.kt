package com.docloom_android.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.net.URLDecoder

/** A website link the app can open. */
sealed interface DeepLink {
    /** `/invite/<token>`: a project or organisation invite. */
    data class Invite(val token: String) : DeepLink
    /** `/reset-password?token=<token>`: a password reset email. */
    data class ResetPassword(val token: String) : DeepLink
}

// Token shapes from the backend: team/invites.rs new_token() and
// auth/reset_token.rs generate() (32 random bytes, hex).
private val INVITE_TOKEN = Regex("[po]_[0-9a-f]{64}")
private val RESET_TOKEN = Regex("[0-9a-f]{64}")

/**
 * Reads a link to the DocLoom website. Links are attacker-controlled input:
 * only the website's own host, two exact paths and well-formed tokens are
 * accepted, and nothing happens without the user tapping to confirm.
 */
fun parseDeepLink(link: String?, websiteUrl: String): DeepLink? {
    if (link == null) return null
    val uri = runCatching { URI(link) }.getOrNull() ?: return null
    val site = runCatching { URI(websiteUrl) }.getOrNull() ?: return null
    if (!uri.host.equals(site.host, ignoreCase = true)) return null
    if (uri.scheme != site.scheme) return null
    val path = uri.path?.trimEnd('/') ?: return null
    if (path.startsWith("/invite/")) {
        val token = path.removePrefix("/invite/")
        return if (INVITE_TOKEN.matches(token)) DeepLink.Invite(token) else null
    }
    if (path == "/reset-password") {
        val token = uri.rawQuery.orEmpty().split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == "token" }
            ?.let { URLDecoder.decode(it[1], "UTF-8") }
        return if (token != null && RESET_TOKEN.matches(token)) DeepLink.ResetPassword(token) else null
    }
    return null
}

/** The link the app was opened with, until a screen takes it. */
class DeepLinkInbox {
    private val _pending = MutableStateFlow<DeepLink?>(null)
    val pending: StateFlow<DeepLink?> = _pending.asStateFlow()

    fun offer(link: DeepLink?) {
        if (link != null) _pending.value = link
    }

    fun consume(link: DeepLink) {
        if (_pending.value == link) _pending.value = null
    }
}
