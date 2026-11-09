package com.docloom_android.feature.team

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.docloom_android.core.model.InviteRequest
import com.docloom_android.core.model.OrgDetail
import com.docloom_android.core.model.OrgMember
import com.docloom_android.core.model.OrgRequest
import com.docloom_android.core.model.Organization
import com.docloom_android.core.model.PendingInvite
import com.docloom_android.core.model.RoleRequest
import com.docloom_android.core.network.ApiResult
import com.docloom_android.core.network.apiCall
import com.docloom_android.core.ui.ErrorBanner
import com.docloom_android.core.ui.LoadingBox
import com.docloom_android.core.ui.MessageBox
import com.docloom_android.core.ui.MutedText
import com.docloom_android.core.ui.Notice
import com.docloom_android.core.ui.NoticeBanner
import com.docloom_android.core.ui.SectionCard
import com.docloom_android.feature.tasks.Pill
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

/** A screen with a back arrow, for the pages under Settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title, fontWeight = FontWeight.SemiBold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding -> Column(Modifier.padding(padding).fillMaxSize()) { content() } }
}

// ---------------- list ----------------

data class OrgListState(val loading: Boolean = true, val orgs: List<Organization> = emptyList(), val error: String? = null, val busy: Boolean = false, val created: String? = null)

class OrgListViewModel(private val api: TeamApi) : ViewModel() {
    private val _state = MutableStateFlow(OrgListState())
    val state: StateFlow<OrgListState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            when (val r = apiCall { api.organizations() }) {
                is ApiResult.Ok -> _state.update { it.copy(loading = false, orgs = r.value.organizations, error = null) }
                is ApiResult.Err -> _state.update { it.copy(loading = false, error = r.message) }
            }
        }
    }

    fun create(name: String, domain: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            when (val r = apiCall { api.createOrganization(OrgRequest(name.trim(), domain.trim().ifEmpty { null })) }) {
                is ApiResult.Ok -> _state.update { it.copy(busy = false, created = r.value.organization.id) }
                is ApiResult.Err -> _state.update { it.copy(busy = false, error = r.message) }
            }
        }
    }

    fun createdHandled() = _state.update { it.copy(created = null) }
}

/** Website settings/organizations: your organisations and a form to start one. */
@Composable
fun OrganizationsScreen(vm: OrgListViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(state.created) {
        state.created?.let {
            vm.createdHandled()
            name = ""
            domain = ""
            onOpen(it)
        }
    }

    SubPage("Organisations", onBack) {
        if (state.loading) {
            LoadingBox()
            return@SubPage
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MutedText(
                "A company or team. As its admin you invite people by email, and everyone in it reaches the projects you create there, " +
                    "with no sharing one project at a time.",
            )
            state.orgs.forEach { o ->
                Surface(onClick = { onOpen(o.id) }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Initials(o.name)
                        Column(Modifier.weight(1f)) {
                            Text(o.name, fontWeight = FontWeight.Medium)
                            MutedText(plural(o.memberCount, "person") + " · " + plural(o.projectCount, "project") + (o.allowedDomain?.let { " · @$it" } ?: ""))
                        }
                        Pill(o.role)
                    }
                }
            }
            SectionCard(if (state.orgs.isEmpty()) "Start your organisation" else "Start another organisation") {
                OutlinedTextField(value = name, onValueChange = { if (it.length <= 100) name = it }, label = { Text("Name") }, placeholder = { Text("Acme Inc.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = domain, onValueChange = { domain = it }, label = { Text("Only invite people from this email domain (optional)") }, placeholder = { Text("acme.com") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                state.error?.let { ErrorBanner(it) }
                Button(onClick = { vm.create(name, domain) }, enabled = !state.busy && name.isNotBlank(), shape = RoundedCornerShape(50)) {
                    Text(if (state.busy) "Creating…" else "Create organisation")
                }
            }
        }
    }
}

// ---------------- one organisation ----------------

data class OrgState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val detail: OrgDetail? = null,
    val busy: String? = null,
    val error: String? = null,
    val notice: Notice? = null,
    val sent: SentInvite? = null,
    /** Left or deleted: go back. */
    val gone: Boolean = false,
)

/** Port of the website's OrgManager. */
class OrgViewModel(private val api: TeamApi, private val orgId: String, private val currentUserId: String) : ViewModel() {
    private val _state = MutableStateFlow(OrgState())
    val state: StateFlow<OrgState> = _state.asStateFlow()

    init { viewModelScope.launch { reload() } }

    private suspend fun reload() {
        when (val r = apiCall { api.organization(orgId) }) {
            is ApiResult.Ok -> _state.update { it.copy(loading = false, loadError = null, detail = r.value) }
            is ApiResult.Err -> _state.update { it.copy(loading = false, loadError = r.message) }
        }
    }

    fun retry() { viewModelScope.launch { reload() } }
    fun dismissSent() = _state.update { it.copy(sent = null) }

    private fun run(key: String, block: suspend () -> String?) {
        viewModelScope.launch {
            _state.update { it.copy(busy = key, error = null, notice = null) }
            val error = block()
            _state.update { it.copy(busy = null, error = error) }
        }
    }

    private fun <T> ApiResult<T>.errorOr(onOk: (T) -> Unit): String? = when (this) {
        is ApiResult.Ok -> { onOk(value); null }
        is ApiResult.Err -> message
    }

    fun save(name: String, domain: String) = run("settings") {
        apiCall { api.updateOrganization(orgId, OrgRequest(name.trim(), domain.trim().ifEmpty { null })) }.errorOr {
            _state.update { s -> s.copy(notice = Notice(true, "Saved.")) }
        }.also { if (it == null) reload() }
    }

    fun invite(email: String, role: String, onDone: () -> Unit) = run("invite") {
        apiCall { api.inviteToOrg(orgId, InviteRequest(email.trim(), role)) }.errorOr { r ->
            _state.update { s -> s.copy(sent = SentInvite(email.trim().lowercase(), r.inviteUrl, r.emailSent)) }
            onDone()
        }.also { if (it == null) reload() }
    }

    fun changeRole(m: OrgMember, role: String) = run(m.userId) {
        apiCall { api.changeOrgRole(orgId, m.userId, RoleRequest(role)) }.errorOr { }.also { if (it == null) reload() }
    }

    fun remove(m: OrgMember) = run(m.userId) {
        apiCall { api.removeOrgMember(orgId, m.userId) }.errorOr {
            if (m.userId == currentUserId) _state.update { s -> s.copy(gone = true) }
        }.also { if (it == null && m.userId != currentUserId) reload() }
    }

    fun resend(invite: PendingInvite) = run(invite.id) {
        apiCall { api.resendOrgInvite(orgId, invite.id) }.errorOr { r ->
            _state.update { s ->
                s.copy(sent = SentInvite(invite.email, r.inviteUrl, r.emailSent), detail = s.detail?.copy(invites = r.invites ?: s.detail.invites))
            }
        }
    }

    fun revoke(invite: PendingInvite) = run(invite.id) {
        apiCall { api.revokeOrgInvite(orgId, invite.id) }.errorOr { r ->
            _state.update { s -> s.copy(detail = s.detail?.copy(invites = r.invites)) }
        }
    }

    fun delete() = run("delete") {
        apiCall { api.deleteOrganization(orgId) }.errorOr { _state.update { s -> s.copy(gone = true) } }
    }
}

@Composable
fun OrganizationScreen(
    vm: OrgViewModel,
    currentUserId: String,
    onBack: () -> Unit,
    onOpenProject: (String) -> Unit,
    onNewProject: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.gone) { if (state.gone) onBack() }
    val detail = state.detail
    SubPage(detail?.organization?.name ?: "Organisation", onBack) {
        when {
            state.loading -> LoadingBox()
            detail == null -> MessageBox("Couldn't open this organisation", state.loadError, "Try again", vm::retry)
            else -> OrgContent(detail, state, vm, currentUserId, onOpenProject, onNewProject)
        }
    }
}

@Composable
private fun OrgContent(
    detail: OrgDetail,
    state: OrgState,
    vm: OrgViewModel,
    currentUserId: String,
    onOpenProject: (String) -> Unit,
    onNewProject: () -> Unit,
) {
    val org = detail.organization
    val isOwner = org.role == "owner"
    val isAdmin = org.role != "member"
    var email by remember { mutableStateOf("") }
    var inviteRole by remember { mutableStateOf("member") }
    var name by remember(org.name) { mutableStateOf(org.name) }
    var domain by remember(org.allowedDomain) { mutableStateOf(org.allowedDomain ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MutedText(
            "You're ${if (org.role == "admin") "an admin" else if (isOwner) "an owner" else "a member"}. People here reach every project created " +
                "in this organisation: owners and admins as project owners, members as editors.",
        )
        state.error?.let { ErrorBanner(it) }
        state.notice?.let { NoticeBanner(it) }

        SectionCard("People") {
            detail.members.forEach { m ->
                val canChange = isAdmin && (m.role != "owner" || isOwner)
                val canRemove = m.userId == currentUserId || (isAdmin && (m.role != "owner" || isOwner))
                PersonRow(m.username, m.email, m.userId == currentUserId) {
                    RolePicker(m.role, if (isOwner) listOf("owner", "admin", "member") else listOf("admin", "member"), canChange && state.busy == null) { vm.changeRole(m, it) }
                    if (canRemove) TextButton(onClick = { vm.remove(m) }, enabled = state.busy == null) {
                        Text(if (state.busy == m.userId) "…" else if (m.userId == currentUserId) "Leave" else "Remove")
                    }
                }
            }
            if (isAdmin) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    placeholder = { Text(org.allowedDomain?.let { "name@$it" } ?: "name@company.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = inviteRole == "member", onClick = { inviteRole = "member" }, label = { Text("Member") })
                    if (isOwner) FilterChip(selected = inviteRole == "admin", onClick = { inviteRole = "admin" }, label = { Text("Admin") })
                    OutlinedButton(onClick = { vm.invite(email, inviteRole) { email = "" } }, enabled = state.busy == null && EMAIL_RE.matches(email.trim()), shape = RoundedCornerShape(50)) {
                        Text(if (state.busy == "invite") "Inviting…" else "Invite")
                    }
                }
                MutedText("They get an email with a link. Without a DocLoom account, they create one with that address and join straight away.")
                state.sent?.let { InviteLinkNotice(it, vm::dismissSent) }
                PendingInvitesList(detail.invites, state.busy?.takeIf { id -> detail.invites.any { it.id == id } }, vm::resend, vm::revoke)
            }
        }

        if (isAdmin) {
            SectionCard("Settings") {
                OutlinedTextField(value = name, onValueChange = { if (it.length <= 100) name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = domain, onValueChange = { domain = it }, label = { Text("Only invite people from this email domain") }, placeholder = { Text("acme.com, or empty for no limit") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                MutedText("Applies to new invitations; people already here stay.")
                Button(onClick = { vm.save(name, domain) }, enabled = state.busy == null && name.isNotBlank(), shape = RoundedCornerShape(50)) {
                    Text(if (state.busy == "settings") "Saving…" else "Save")
                }
            }
        }

        SectionCard("Projects", actionLabel = "+ New project here", onAction = onNewProject) {
            if (detail.projects.isEmpty()) MutedText("No projects yet. Projects created here are open to everyone in the organisation.")
            detail.projects.forEach { p -> TextButton(onClick = { onOpenProject(p.id) }) { Text(p.name, modifier = Modifier.fillMaxWidth()) } }
        }

        if (isOwner) {
            SectionCard("Delete organisation") {
                MutedText("Its projects stay with the people added to them directly; everyone else loses access through the organisation.")
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    enabled = state.busy == null,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(50),
                ) { Text(if (state.busy == "delete") "Deleting…" else "Delete…") }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${org.name}?") },
            text = { Text("This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Yes, delete it", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
