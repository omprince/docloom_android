package com.docloom_android.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.docloom_android.BuildConfig
import com.docloom_android.core.auth.SessionRepository
import com.docloom_android.core.model.User
import com.docloom_android.core.network.ApiResult
import com.docloom_android.core.ui.MutedText
import com.docloom_android.core.ui.Notice
import com.docloom_android.core.ui.NoticeBanner
import com.docloom_android.core.ui.SectionCard
import kotlinx.coroutines.launch

private val THEMES = listOf("dark" to "Dark", "light" to "Light", "system" to "System")

/** Port of the website's AccountSettings (/dashboard/settings). */
@Composable
fun SettingsScreen(user: User, session: SessionRepository) {
    val theme by session.theme.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<Notice?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var deleteOpen by remember { mutableStateOf(false) }
    var deletePassword by remember { mutableStateOf("") }
    var deleteConfirm by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        notice?.let { NoticeBanner(it) }

        SectionCard("Profile") {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f)) {
                    MutedText("Username")
                    Text("@${user.username}", fontWeight = FontWeight.Medium)
                }
                Column(Modifier.weight(1f)) {
                    MutedText("Email")
                    Text(user.email, fontWeight = FontWeight.Medium)
                }
            }
        }

        SectionCard("Appearance") {
            MutedText("Saved to your account, so it follows you to other devices.")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                THEMES.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = theme == value,
                        onClick = {
                            scope.launch {
                                val r = session.setTheme(value)
                                notice = (r as? ApiResult.Err)?.let { Notice(false, it.message) }
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, THEMES.size),
                    ) { Text(label) }
                }
            }
        }

        SectionCard("Change password") {
            PasswordField(current, { current = it }, "Current password")
            PasswordField(next, { next = it }, "New password (8+ characters)")
            PasswordField(confirm, { confirm = it }, "Repeat new password")
            OutlinedButton(
                onClick = {
                    when {
                        next != confirm -> notice = Notice(false, "The new passwords don't match.")
                        next.length < 8 -> notice = Notice(false, "Password must be at least 8 characters.")
                        else -> scope.launch {
                            busy = "password"
                            notice = null
                            notice = when (val r = session.changePassword(current, next)) {
                                is ApiResult.Ok -> {
                                    current = ""; next = ""; confirm = ""
                                    Notice(true, "Password changed.")
                                }
                                is ApiResult.Err -> Notice(false, r.message)
                            }
                            busy = null
                        }
                    }
                },
                enabled = busy == null && current.isNotEmpty() && next.isNotEmpty(),
                shape = RoundedCornerShape(50),
            ) { Text(if (busy == "password") "Changing…" else "Change password") }
        }

        OutlinedButton(onClick = { session.logout() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(50)) {
            Text("Sign out")
        }

        SectionCard("Delete account") {
            MutedText("Permanently deletes your account, every project, map, history, API key and error report.")
            if (deleteOpen) {
                PasswordField(deletePassword, { deletePassword = it }, "Confirm with your password")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { deleteConfirm = true },
                        enabled = busy == null && deletePassword.isNotEmpty(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(50),
                    ) { Text(if (busy == "delete") "Deleting…" else "Delete my account") }
                    TextButton(onClick = { deleteOpen = false; deletePassword = "" }) { Text("Cancel") }
                }
            } else {
                OutlinedButton(
                    onClick = { deleteOpen = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(50),
                ) { Text("Delete account…") }
            }
        }

        MutedText("Server: ${BuildConfig.API_BASE_URL}")
    }

    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text("Delete your account?", fontWeight = FontWeight.SemiBold) },
            text = { Text("Your account and every project you own will be permanently deleted. This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteConfirm = false
                        scope.launch {
                            busy = "delete"
                            notice = null
                            val r = session.deleteAccount(deletePassword)
                            // On success the session ends and this screen goes away.
                            if (r is ApiResult.Err) notice = Notice(false, r.message)
                            busy = null
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}
