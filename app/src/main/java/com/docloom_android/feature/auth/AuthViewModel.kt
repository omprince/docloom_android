package com.docloom_android.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.docloom_android.core.auth.SessionRepository
import com.docloom_android.core.network.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthFormState(
    val busy: Boolean = false,
    val error: String? = null,
    /** Shown after a successful forgot/reset request. */
    val notice: String? = null,
    /** Reset link a development backend returns instead of emailing it. */
    val devResetUrl: String? = null,
    val resetDone: Boolean = false,
)

/** Drives the login, signup, forgot-password and reset-password screens. */
class AuthViewModel(private val session: SessionRepository) : ViewModel() {
    private val _state = MutableStateFlow(AuthFormState())
    val state: StateFlow<AuthFormState> = _state.asStateFlow()

    fun clearMessages() = _state.update { AuthFormState() }

    fun login(email: String, password: String) {
        if (email.isBlank() || password.isEmpty()) {
            _state.update { it.copy(error = "Enter your email and password.") }
            return
        }
        run { session.login(email, password).errorOrNull() }
    }

    fun signup(username: String, email: String, password: String) {
        val problem = validateSignup(username, email, password)
        if (problem != null) {
            _state.update { it.copy(error = problem) }
            return
        }
        run { session.signup(username, email, password).errorOrNull() }
    }

    fun forgotPassword(email: String) {
        if (!EMAIL.matches(email.trim())) {
            _state.update { it.copy(error = "Enter a valid email address.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            when (val r = session.forgotPassword(email)) {
                is ApiResult.Ok -> _state.update {
                    it.copy(
                        busy = false,
                        notice = "If an account exists for that email, a reset link is on its way.",
                        devResetUrl = r.value,
                    )
                }
                is ApiResult.Err -> _state.update { it.copy(busy = false, error = r.message) }
            }
        }
    }

    /** [linkOrToken] may be the whole reset link from the email or just its token. */
    fun resetPassword(linkOrToken: String, password: String, confirm: String) {
        val token = extractResetToken(linkOrToken)
        val problem = when {
            token.isBlank() -> "Paste the reset link from your email."
            password.length < MIN_PASSWORD -> "Password must be at least $MIN_PASSWORD characters."
            password != confirm -> "Passwords don't match."
            else -> null
        }
        if (problem != null) {
            _state.update { it.copy(error = problem) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            when (val r = session.resetPassword(token, password)) {
                is ApiResult.Ok -> _state.update { it.copy(busy = false, resetDone = true) }
                is ApiResult.Err -> _state.update { it.copy(busy = false, error = r.message) }
            }
        }
    }

    private fun run(block: suspend () -> String?) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val error = block()
            _state.update { it.copy(busy = false, error = error) }
        }
    }

    private fun ApiResult<*>.errorOrNull(): String? = (this as? ApiResult.Err)?.message

    companion object {
        const val MIN_PASSWORD = 8
        // Same rules and messages as docloom_website_be/src/auth/validate.rs.
        private val EMAIL = Regex("""[^\s@]+@[^\s@]+\.[^\s@]+""")
        private val USERNAME = Regex("""[a-zA-Z0-9_-]{3,20}""")

        fun validateSignup(username: String, email: String, password: String): String? = when {
            !USERNAME.matches(username.trim()) -> "Username must be 3-20 characters (letters, numbers, _ or -)."
            !EMAIL.matches(email.trim()) -> "Enter a valid email address."
            password.length < MIN_PASSWORD -> "Password must be at least 8 characters."
            else -> null
        }

        /** Accepts `https://site/reset-password?token=abc` or a bare `abc`. */
        fun extractResetToken(input: String): String {
            val trimmed = input.trim()
            val match = Regex("""[?&]token=([^&#\s]+)""").find(trimmed)
            return match?.groupValues?.get(1) ?: trimmed
        }
    }
}
