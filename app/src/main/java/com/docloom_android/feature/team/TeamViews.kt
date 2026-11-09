package com.docloom_android.feature.team

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docloom_android.core.model.PendingInvite
import com.docloom_android.core.ui.CopyableCode
import com.docloom_android.core.ui.MutedText
import com.docloom_android.core.ui.timeAgo
import com.docloom_android.feature.tasks.Pill

/** An invite that was just created or resent: the link to share. */
data class SentInvite(val email: String, val url: String, val emailSent: Boolean)

val EMAIL_RE = Regex("""[^\s@]+@[^\s@]+\.[^\s@]+""")

/** Initials in a circle, the website's member avatar. */
@Composable
fun Initials(name: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), modifier = Modifier.size(32.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(name.take(2).uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

/** A person row: initials, name, a line under it, then trailing controls. */
@Composable
fun PersonRow(name: String, sub: String?, you: Boolean, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Initials(name)
        Column(Modifier.weight(1f)) {
            Text("@$name" + if (you) " (you)" else "", maxLines = 1, overflow = TextOverflow.Ellipsis)
            sub?.let { MutedText(it) }
        }
        trailing()
    }
}

/** A role chip that opens a menu when [options] has more than one choice. */
@Composable
fun RolePicker(role: String, options: List<String>, enabled: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    if (options.size <= 1 || !enabled) {
        Pill(role, accent = role == "owner")
        return
    }
    Box {
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Row(Modifier.padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(role, style = MaterialTheme.typography.labelMedium)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Change role", modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { r -> DropdownMenuItem(text = { Text(r) }, onClick = { open = false; if (r != role) onPick(r) }) }
        }
    }
}

/** Shown after an invite is created or resent (website InviteLinkNotice). */
@Composable
fun InviteLinkNotice(sent: SentInvite, onClose: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(shape = RoundedCornerShape(10.dp), color = accent.copy(alpha = 0.10f), border = BorderStroke(1.dp, accent.copy(alpha = 0.3f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Text(
                    if (sent.emailSent) "Invite emailed to ${sent.email}."
                    else "Invite created for ${sent.email}. Email isn't set up on this server, so send them this link yourself:",
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) { Icon(Icons.Filled.Close, "Dismiss") }
            }
            CopyableCode("Invite link", sent.url, sensitive = true)
            MutedText("Works once, for ${sent.email}, for 7 days.")
        }
    }
}

/** Invites that haven't been accepted yet, with resend and revoke. */
@Composable
fun PendingInvitesList(invites: List<PendingInvite>, busyId: String?, onResend: (PendingInvite) -> Unit, onRevoke: (PendingInvite) -> Unit) {
    if (invites.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("WAITING TO JOIN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        invites.forEach { invite ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(invite.email, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    MutedText(if (invite.expired) "Expired: resend to give a new link" else "Invited ${timeAgo(invite.createdAt)} · ${invite.role}")
                }
                TextButton(onClick = { onResend(invite) }, enabled = busyId == null) { Text(if (busyId == invite.id) "…" else "Resend") }
                TextButton(onClick = { onRevoke(invite) }, enabled = busyId == null) { Text("Revoke") }
            }
        }
    }
}
