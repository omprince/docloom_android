package com.docloom_android.core.auth

import com.docloom_android.core.model.AuthResponse
import com.docloom_android.core.model.ChangePasswordRequest
import com.docloom_android.core.model.DeleteAccountRequest
import com.docloom_android.core.model.ForgotPasswordRequest
import com.docloom_android.core.model.LoginRequest
import com.docloom_android.core.model.ResetPasswordRequest
import com.docloom_android.core.model.SignupRequest
import com.docloom_android.core.model.ThemePreference
import com.docloom_android.core.model.User
import com.docloom_android.core.network.ApiResult
import com.docloom_android.core.network.apiCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SessionState {
    data object Loading : SessionState
    data object SignedOut : SessionState
    data class SignedIn(val user: User) : SessionState
}

/** Single source of truth for "who is signed in". */
class SessionRepository(
    private val authApi: AuthApi,
    private val tokenStore: TokenStore,
    unauthorized: Flow<Unit>,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _theme = MutableStateFlow("dark")

    /** "dark", "light" or "system": the same values the website saves. */
    val theme: StateFlow<String> = _theme.asStateFlow()

    init {
        scope.launch { unauthorized.collect { endSession() } }
        scope.launch { restore() }
    }

    private suspend fun restore() {
        val stored = tokenStore.load()
        if (stored == null) {
            _state.value = SessionState.SignedOut
            return
        }
        // Show the cached user straight away (works offline), then confirm with the server.
        _state.value = SessionState.SignedIn(stored.user)
        when (val me = apiCall { authApi.me() }) {
            is ApiResult.Ok -> me.value.user?.let { _state.value = SessionState.SignedIn(it) }
            is ApiResult.Err -> if (me.status == 401) endSession()
        }
        refreshTheme()
    }

    suspend fun login(email: String, password: String): ApiResult<User> =
        startSession { authApi.login(LoginRequest(email.trim(), password)) }

    suspend fun signup(username: String, email: String, password: String): ApiResult<User> =
        startSession { authApi.signup(SignupRequest(username.trim(), email.trim(), password)) }

    private suspend fun startSession(call: suspend () -> AuthResponse): ApiResult<User> =
        when (val result = apiCall { call() }) {
            is ApiResult.Err -> result
            is ApiResult.Ok -> {
                val token = result.value.token
                if (token == null) {
                    ApiResult.Err("The server didn't return a session token. Update the backend to the version with mobile sign-in.")
                } else {
                    val user = result.value.user
                    tokenStore.save(StoredSession(token, user))
                    _state.value = SessionState.SignedIn(user)
                    refreshTheme()
                    ApiResult.Ok(user)
                }
            }
        }

    /** Returns the reset link when the backend is in development mode (no email is sent then). */
    suspend fun forgotPassword(email: String): ApiResult<String?> =
        when (val r = apiCall { authApi.forgotPassword(ForgotPasswordRequest(email.trim())) }) {
            is ApiResult.Ok -> ApiResult.Ok(r.value.resetUrl)
            is ApiResult.Err -> r
        }

    suspend fun resetPassword(token: String, password: String): ApiResult<Unit> =
        when (val r = apiCall { authApi.resetPassword(ResetPasswordRequest(token.trim(), password)) }) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            is ApiResult.Err -> r
        }

    suspend fun setTheme(theme: String): ApiResult<Unit> {
        val previous = _theme.value
        _theme.value = theme
        return when (val r = apiCall { authApi.updatePreferences(ThemePreference(theme)) }) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            is ApiResult.Err -> {
                _theme.value = previous
                r
            }
        }
    }

    private suspend fun refreshTheme() {
        val result = apiCall { authApi.preferences() }
        if (result is ApiResult.Ok) _theme.value = result.value.theme
    }

    suspend fun changePassword(current: String, new: String): ApiResult<Unit> =
        when (val r = apiCall { authApi.changePassword(ChangePasswordRequest(current, new)) }) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            is ApiResult.Err -> r
        }

    /** Permanently deletes the account on the server, then signs out here. */
    suspend fun deleteAccount(password: String): ApiResult<Unit> =
        when (val r = apiCall { authApi.deleteAccount(DeleteAccountRequest(password)) }) {
            is ApiResult.Ok -> {
                endSession()
                ApiResult.Ok(Unit)
            }
            is ApiResult.Err -> r
        }

    /** Tells the backend, then forgets the session locally. */
    fun logout() {
        scope.launch {
            apiCall { authApi.logout() }
            endSession()
        }
    }

    private suspend fun endSession() {
        tokenStore.clear()
        _state.value = SessionState.SignedOut
    }
}
