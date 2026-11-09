package com.docloom_android.feature.team

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.docloom_android.BuildConfig
import com.docloom_android.core.model.ApiToken
import com.docloom_android.core.model.CreateTokenRequest
import com.docloom_android.core.model.GithubStatus
import com.docloom_android.core.model.InviteAccepted
import com.docloom_android.core.model.InvitePreview
import com.docloom_android.core.model.OAuthGrant
import com.docloom_android.core.model.User
import com.docloom_android.core.network.ApiResult
import com.docloom_android.core.network.apiCall
import com.docloom_android.core.ui.CopyableCode
import com.docloom_android.core.ui.ErrorBanner
import com.docloom_android.core.ui.LoadingBox
import com.docloom_android.core.ui.MutedText
import com.docloom_android.core.ui.SectionCard
import com.docloom_android.core.ui.timeAgo
import com.docloom_android.ui.theme.SuccessGreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

/** Keeps screenshots and the recent-apps preview from capturing a secret while it's shown. */
@Composable
fun SecureWhileShown() {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

// ---------------- API keys + connected apps ----------------

data class KeysState(
    val loading: Boolean = true,
    val tokens: List<ApiToken> = emptyList(),
    val grants: List<OAuthGrant> = emptyList(),
    /** The new key's secret, shown once. */
    val secret: String? = null,
    val busy: String? = null,
    val error: String? = null,
)

class KeysViewModel(private val api: TeamApi) : ViewModel() {
    private val _state = MutableStateFlow(KeysState())
    val state: StateFlow<KeysState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val tokens = apiCall { api.tokens() }
            val grants = apiCall { api.grants() }
            _state.update {
                it.copy(
                    loading = false,
                    tokens = (tokens as? ApiResult.Ok)?.value?.tokens.orEmpty(),
                    grants = (grants as? ApiResult.Ok)?.value?.grants.orEmpty(),
                    error = (tokens as? ApiResult.Err)?.message,
                )
            }
        }
    }

    fun create(name: String, onDone: () -> Unit) = run("create") {
        when (val r = apiCall { api.createToken(CreateTokenRequest(name.trim())) }) {
            is ApiResult.Ok -> {
                _state.update { it.copy(tokens = listOf(r.value.token) + it.tokens, secret = r.value.secret) }
                onDone()
            }
            is ApiResult.Err -> _state.update { it.copy(error = r.message) }
        }
    }

    fun hideSecret() = _state.update { it.copy(secret = null) }

    fun revoke(token: ApiToken) = run(token.id) {
        val r = apiCall { api.revokeToken(token.id).also { if (!it.isSuccessful) throw HttpException(it) } }
        if (r is ApiResult.Err) _state.update { it.copy(error = r.message) }
        else _state.update { s -> s.copy(tokens = s.tokens.filter { it.id != token.id }) }
    }

    fun disconnect(grant: OAuthGrant) = run(grant.id) {
        val r = apiCall { api.revokeGrant(grant.id).also { if (!it.isSuccessful) throw HttpException(it) } }
        if (r is ApiResult.Err) _state.update { it.copy(error = r.message) }
        else _state.update { s -> s.copy(grants = s.grants.filter { it.id != grant.id }) }
    }

    private fun run(key: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = key, error = null) }
            block()
            _state.update { it.copy(busy = null) }
        }
    }
}

/** Website settings/api-keys: MCP setup, connected apps, API keys. */
@Composable
fun ApiKeysScreen(vm: KeysViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    SubPage("AI & API keys", onBack) {
        if (state.loading) {
            LoadingBox()
            return@SubPage
        }
        state.secret?.let { SecureWhileShown() }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.error?.let { ErrorBanner(it) }
            SectionCard("Connect an AI over MCP") {
                MutedText(
                    "Tools available to the AI: list and create projects, read a map, add, connect, update and delete nodes, " +
                        "apply a database schema, read history and open errors.",
                )
                val url = BuildConfig.WEBSITE_URL + "/mcp"
                val secret = state.secret
                CopyableCode("MCP server address", url)
                CopyableCode(
                    "Claude Code",
                    "claude mcp add --transport http docloom $url" + (secret?.let { " --header \"Authorization: Bearer $it\"" } ?: ""),
                    sensitive = secret != null,
                )
                MutedText(
                    if (secret != null) "These snippets include your new key, so keep them private."
                    else "The first time it connects, your AI client opens DocLoom in the browser. Sign in and tap Allow; no key needed.",
                )
            }

            SectionCard("Connected apps") {
                if (state.grants.isEmpty()) MutedText("No apps have signed in to DocLoom yet.")
                state.grants.forEach { g ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(g.clientName, fontWeight = FontWeight.Medium)
                            MutedText("Connected ${timeAgo(g.createdAt)} · " + (g.lastUsedAt?.let { "last used ${timeAgo(it)}" } ?: "never used"))
                        }
                        TextButton(onClick = { confirm = "Disconnect “${g.clientName}”? It loses access immediately." to { vm.disconnect(g) } }, enabled = state.busy == null) {
                            Text(if (state.busy == g.id) "…" else "Disconnect")
                        }
                    }
                }
            }

            SectionCard("API keys") {
                MutedText(
                    "Only needed for clients that can't sign in with a browser (scripts, older MCP clients). A key acts as you; " +
                        "each change it makes is labelled with the key's name in History.",
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 60) name = it },
                    placeholder = { Text("Key name, e.g. Claude Desktop") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { vm.create(name) { name = "" } }, enabled = state.busy == null && name.isNotBlank(), shape = RoundedCornerShape(50)) {
                    Text(if (state.busy == "create") "Creating…" else "Create key")
                }
                state.secret?.let { secret ->
                    Surface(shape = RoundedCornerShape(10.dp), color = SuccessGreen.copy(alpha = 0.10f), border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.3f))) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Copy this key now. It won't be shown again.", color = SuccessGreen, fontWeight = FontWeight.Medium)
                            CopyableCode("New key", secret, sensitive = true)
                            TextButton(onClick = vm::hideSecret) { Text("I've copied it") }
                        }
                    }
                }
                if (state.tokens.isEmpty()) MutedText("No keys yet.")
                state.tokens.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name, fontWeight = FontWeight.Medium)
                            MutedText("${t.prefix}… · created ${timeAgo(t.createdAt)} · " + (t.lastUsedAt?.let { "last used ${timeAgo(it)}" } ?: "never used"))
                        }
                        TextButton(onClick = { confirm = "Revoke “${t.name}”? Anything using it stops working immediately." to { vm.revoke(t) } }, enabled = state.busy == null) {
                            Text(if (state.busy == t.id) "Revoking…" else "Revoke", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
    confirm?.let { (text, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text("Yes", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

// ---------------- GitHub ----------------

data class GithubState(val loading: Boolean = true, val status: GithubStatus = GithubStatus(), val busy: Boolean = false, val error: String? = null)

class GithubViewModel(private val api: TeamApi) : ViewModel() {
    private val _state = MutableStateFlow(GithubState())
    val state: StateFlow<GithubState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            when (val r = apiCall { api.githubStatus() }) {
                is ApiResult.Ok -> _state.update { it.copy(loading = false, status = r.value, error = null) }
                is ApiResult.Err -> _state.update { it.copy(loading = false, error = r.message) }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            when (val r = apiCall { api.githubDisconnect() }) {
                is ApiResult.Ok -> _state.update { it.copy(busy = false, status = it.status.copy(connected = false, username = null)) }
                is ApiResult.Err -> _state.update { it.copy(busy = false, error = r.message) }
            }
        }
    }
}

/**
 * Website settings/integrations, GitHub part. Connecting goes through
 * GitHub's consent page on the website (it needs the website's sign-in in
 * the browser), so it opens there; the status refreshes on return.
 */
@Composable
fun IntegrationsScreen(vm: GithubViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.refresh() } }
    var confirm by remember { mutableStateOf(false) }

    SubPage("Integrations", onBack) {
        if (state.loading) {
            LoadingBox()
            return@SubPage
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            state.error?.let { ErrorBanner(it) }
            SectionCard("GitHub") {
                MutedText("Sync issues with project tasks, assign people, scan private repos and mirror repos as doc chapters.")
                val s = state.status
                when {
                    !s.configured -> MutedText("GitHub isn't set up on this server yet (missing GitHub OAuth settings in the backend's .env).")
                    s.connected -> {
                        Text("Connected as @${s.username}", color = SuccessGreen, fontWeight = FontWeight.Medium)
                        OutlinedButton(onClick = { confirm = true }, enabled = !state.busy, shape = RoundedCornerShape(50)) {
                            Text(if (state.busy) "Disconnecting…" else "Disconnect")
                        }
                    }
                    else -> {
                        Button(onClick = { uri.openUri(BuildConfig.WEBSITE_URL + "/api/github/connect") }, shape = RoundedCornerShape(50)) {
                            Text("Connect GitHub in the browser")
                        }
                        MutedText("Opens DocLoom's website. Sign in there with this same account if it asks, approve on GitHub, then come back here.")
                    }
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            text = { Text("Disconnect GitHub? Issue sync and private repo scans stop until you connect again.") },
            confirmButton = { TextButton(onClick = { confirm = false; vm.disconnect() }) { Text("Disconnect", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

// ---------------- invite links ----------------

/** The token from an invite link (`…/invite/<token>`) or the token itself. */
fun inviteToken(input: String): String {
    val t = input.trim()
    val fromLink = Regex("""/invite/([^/?#\s]+)""").find(t)?.groupValues?.get(1)
    return (fromLink ?: t).trim()
}

data class InviteState(
    val token: String? = null,
    val preview: InvitePreview? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val joined: InviteAccepted? = null,
)

class InviteViewModel(private val api: TeamApi) : ViewModel() {
    private val _state = MutableStateFlow(InviteState())
    val state: StateFlow<InviteState> = _state.asStateFlow()

    fun open(input: String) {
        val token = inviteToken(input)
        if (token.isEmpty()) return
        viewModelScope.launch {
            _state.update { InviteState(token = token, busy = true) }
            when (val r = apiCall { api.invitePreview(token) }) {
                is ApiResult.Ok -> _state.update { it.copy(busy = false, preview = r.value) }
                is ApiResult.Err -> _state.update { it.copy(busy = false, error = r.message) }
            }
        }
    }

    fun accept() {
        val token = _state.value.token ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            when (val r = apiCall { api.acceptInvite(token) }) {
                is ApiResult.Ok -> _state.update { it.copy(busy = false, joined = r.value) }
                is ApiResult.Err -> _state.update { it.copy(busy = false, error = r.message) }
            }
        }
    }
}

private val ROLE_TEXT = mapOf(
    "editor" to "an editor (can change the map, tasks and docs)",
    "viewer" to "a viewer (can look, not change)",
    "admin" to "an admin (can invite people and manage the organisation)",
    "member" to "a member",
)

/** Paste an invite link to see what it's for and accept it (website /invite/[token]). */
@Composable
fun InviteScreen(vm: InviteViewModel, user: User, onBack: () -> Unit, onJoined: (InviteAccepted) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var link by remember { mutableStateOf("") }
    LaunchedEffect(state.joined) { state.joined?.let(onJoined) }

    SubPage("Open an invite", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MutedText("Got an invite email? Paste its link here to join from the app.")
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                placeholder = { Text("https://…/invite/…", fontFamily = FontFamily.Monospace) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { vm.open(link) }, enabled = !state.busy && link.isNotBlank(), shape = RoundedCornerShape(50)) {
                Text(if (state.busy && state.preview == null) "Checking…" else "Check invite")
            }
            state.error?.let { ErrorBanner(it) }
            state.preview?.let { p ->
                val what = if (p.kind == "project") "the project “${p.name}”" else "the organisation “${p.name}”"
                val matches = user.email.trim().equals(p.email, ignoreCase = true)
                SectionCard("${p.inviter ?: "Someone"} invited you to join $what") {
                    MutedText("You'll be ${ROLE_TEXT[p.role] ?: p.role}. The invite is for ${p.email}.")
                    if (matches) {
                        Button(onClick = vm::accept, enabled = !state.busy, shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.busy) "Joining…" else "Accept invitation")
                        }
                    } else {
                        ErrorBanner(
                            "You're signed in as ${user.email}, but this invite is for ${p.email}. Sign out and " +
                                (if (p.hasAccount) "sign in" else "create an account") + " with ${p.email} to accept it.",
                        )
                    }
                }
            }
        }
    }
}
