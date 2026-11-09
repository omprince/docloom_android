package com.docloom_android.feature.team

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.docloom_android.core.model.AddMemberRequest
import com.docloom_android.core.model.InviteRequest
import com.docloom_android.core.model.PendingInvite
import com.docloom_android.core.model.ProjectMember
import com.docloom_android.core.model.RoleRequest
import com.docloom_android.core.network.ApiResult
import com.docloom_android.core.network.apiCall
import com.docloom_android.core.ui.ErrorBanner
import com.docloom_android.core.ui.MutedText
import com.docloom_android.core.ui.SectionCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MembersState(
    val members: List<ProjectMember>? = null,
    val invites: List<PendingInvite> = emptyList(),
    /** "add", a user id or an invite id while working. */
    val busy: String? = null,
    val error: String? = null,
    val sent: SentInvite? = null,
    /** True once the current user has left the project. */
    val left: Boolean = false,
)

/** Port of the website's MembersPanel logic. */
class MembersViewModel(
    private val api: TeamApi,
    private val projectId: String,
    private val isOwner: Boolean,
    private val currentUserId: String,
    private val onChanged: () -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(MembersState())
    val state: StateFlow<MembersState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            when (val r = apiCall { api.members(projectId) }) {
                is ApiResult.Ok -> _state.update { it.copy(members = r.value.members) }
                is ApiResult.Err -> _state.update { it.copy(error = r.message) }
            }
            if (isOwner) (apiCall { api.projectInvites(projectId) } as? ApiResult.Ok)?.let { r -> _state.update { it.copy(invites = r.value.invites) } }
        }
    }

    fun dismissSent() = _state.update { it.copy(sent = null) }

    /** A username or email; an email with no account becomes an invite. */
    fun add(who: String, role: String, onDone: () -> Unit) {
        val target = who.trim()
        if (target.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = "add", error = null, sent = null) }
            when (val r = apiCall { api.addMember(projectId, AddMemberRequest(target, role)) }) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(busy = null, members = r.value.members) }
                    onDone()
                    onChanged()
                }
                is ApiResult.Err -> {
                    if (EMAIL_RE.matches(target) && r.message.contains("invite them by email", ignoreCase = true)) {
                        when (val inv = apiCall { api.inviteToProject(projectId, InviteRequest(target, role)) }) {
                            is ApiResult.Ok -> {
                                val invites = (apiCall { api.projectInvites(projectId) } as? ApiResult.Ok)?.value?.invites ?: _state.value.invites
                                _state.update { it.copy(busy = null, sent = SentInvite(target, inv.value.inviteUrl, inv.value.emailSent), invites = invites) }
                                onDone()
                            }
                            is ApiResult.Err -> _state.update { it.copy(busy = null, error = inv.message) }
                        }
                    } else {
                        _state.update { it.copy(busy = null, error = r.message) }
                    }
                }
            }
        }
    }

    fun changeRole(member: ProjectMember, role: String) = run(member.userId) {
        when (val r = apiCall { api.changeMemberRole(projectId, member.userId, RoleRequest(role)) }) {
            is ApiResult.Ok -> _state.update { it.copy(members = r.value.members) }
            is ApiResult.Err -> _state.update { it.copy(error = r.message) }
        }
    }

    fun remove(member: ProjectMember) = run(member.userId) {
        when (val r = apiCall { api.removeMember(projectId, member.userId) }) {
            is ApiResult.Ok -> {
                if (member.userId == currentUserId) _state.update { it.copy(left = true) }
                else _state.update { it.copy(members = r.value.members) }
                onChanged()
            }
            is ApiResult.Err -> _state.update { it.copy(error = r.message) }
        }
    }

    fun resend(invite: PendingInvite) = run(invite.id) {
        when (val r = apiCall { api.resendProjectInvite(projectId, invite.id) }) {
            is ApiResult.Ok -> _state.update {
                it.copy(sent = SentInvite(invite.email, r.value.inviteUrl, r.value.emailSent), invites = r.value.invites ?: it.invites)
            }
            is ApiResult.Err -> _state.update { it.copy(error = r.message) }
        }
    }

    fun revoke(invite: PendingInvite) = run(invite.id) {
        when (val r = apiCall { api.revokeProjectInvite(projectId, invite.id) }) {
            is ApiResult.Ok -> _state.update { it.copy(invites = r.value.invites) }
            is ApiResult.Err -> _state.update { it.copy(error = r.message) }
        }
    }

    private fun run(key: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = key, error = null) }
            block()
            _state.update { it.copy(busy = null) }
        }
    }
}

private val ROLE_HELP = mapOf(
    "owner" to "Share the map and its tasks. Editors change everything except the project's name, repo and members; viewers can only look.",
    "editor" to "You're an editor on this project: you can change the map, tasks and docs, but not its name, repo or members.",
    "viewer" to "You have view-only access to this project.",
)

/** Who can work on the project; owners add people or invite an email. */
@Composable
fun MembersPanel(vm: MembersViewModel, role: String, currentUserId: String, onLeft: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val isOwner = role == "owner"
    var who by remember { mutableStateOf("") }
    var newRole by remember { mutableStateOf("editor") }
    var confirmLeave by remember { mutableStateOf<ProjectMember?>(null) }
    if (state.left) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onLeft() }
    }

    SectionCard("Members") {
        MutedText(ROLE_HELP[role] ?: "")
        when {
            state.members == null && state.error == null -> MutedText("Loading…")
            state.members != null -> state.members!!.forEach { m ->
                val explicit = !m.viaOrg
                val canRemove = explicit && m.role != "owner" && (isOwner || m.userId == currentUserId)
                val canChange = isOwner && explicit && m.role != "owner"
                PersonRow(
                    name = m.username,
                    sub = when {
                        m.viaOrg -> "Through the organisation"
                        m.githubLogin != null -> "GitHub @${m.githubLogin}"
                        else -> "GitHub not connected"
                    },
                    you = m.userId == currentUserId,
                ) {
                    RolePicker(m.role, listOf("editor", "viewer"), enabled = canChange && state.busy == null) { vm.changeRole(m, it) }
                    if (canRemove) {
                        TextButton(onClick = { if (m.userId == currentUserId) confirmLeave = m else vm.remove(m) }, enabled = state.busy == null) {
                            Text(if (state.busy == m.userId) "…" else if (m.userId == currentUserId) "Leave" else "Remove")
                        }
                    }
                }
            }
        }

        if (isOwner) {
            OutlinedTextField(
                value = who,
                onValueChange = { who = it },
                placeholder = { Text("Username, or an email to invite") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = newRole == "editor", onClick = { newRole = "editor" }, label = { Text("Editor") })
                FilterChip(selected = newRole == "viewer", onClick = { newRole = "viewer" }, label = { Text("Viewer") })
                OutlinedButton(
                    onClick = { vm.add(who, newRole) { who = "" } },
                    enabled = state.busy == null && who.isNotBlank(),
                    shape = RoundedCornerShape(50),
                ) { Text(if (state.busy == "add") "Adding…" else if (EMAIL_RE.matches(who.trim())) "Add or invite" else "Add") }
            }
            MutedText("An email that isn't a DocLoom account yet gets an invite link; they can create an account when they open it.")
            state.sent?.let { InviteLinkNotice(it, vm::dismissSent) }
            PendingInvitesList(state.invites, state.busy?.takeIf { id -> state.invites.any { it.id == id } }, vm::resend, vm::revoke)
        }
        state.error?.let { ErrorBanner(it) }
    }

    confirmLeave?.let { me ->
        AlertDialog(
            onDismissRequest = { confirmLeave = null },
            title = { Text("Leave this project?") },
            text = { Text("You lose access until the owner adds you again.") },
            confirmButton = { TextButton(onClick = { confirmLeave = null; vm.remove(me) }) { Text("Leave", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmLeave = null }) { Text("Cancel") } },
        )
    }
}
