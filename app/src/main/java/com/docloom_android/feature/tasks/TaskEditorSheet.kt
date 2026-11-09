package com.docloom_android.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.docloom_android.core.model.Task
import com.docloom_android.core.ui.ErrorBanner
import com.docloom_android.core.ui.MutedText
import com.docloom_android.ui.theme.WarningAmber

/** Create or edit one task; port of the website's TaskDrawer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorSheet(task: Task?, defaultStatus: String, state: TaskBoardState, vm: TaskBoardViewModel) {
    val isNew = task == null
    val key = task?.id ?: "new-$defaultStatus"
    var title by rememberSaveable(key) { mutableStateOf(task?.title ?: "") }
    var status by rememberSaveable(key) { mutableStateOf(task?.status ?: defaultStatus) }
    var assignee by rememberSaveable(key) { mutableStateOf(task?.assigneeLogin ?: "") }
    var area by rememberSaveable(key) { mutableStateOf(task?.nodeId ?: "") }
    var scenario by rememberSaveable(key) { mutableStateOf(task?.scenario ?: "") }
    var body by rememberSaveable(key) { mutableStateOf(task?.body ?: "") }
    var createIssue by rememberSaveable(key) { mutableStateOf(false) }
    var confirmDelete by remember(key) { mutableStateOf(false) }

    val github = state.github
    val canUseGithub = github.repoUrl != null && github.connected
    val busy = state.editorBusy != null
    val uri = LocalUriHandler.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // The current assignee may have lost repo access; keep them selectable.
    val members = (state.members as? MembersState.Ready)?.list.orEmpty().map { it.login }
    val memberOptions = if (assignee.isNotEmpty() && members.none { it.equals(assignee, true) }) listOf(assignee) + members else members

    ModalBottomSheet(onDismissRequest = { vm.closeEditor() }, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).background(statusMeta(status).color, CircleShape))
                Text(if (isNew) "New task" else "Task", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (task?.githubIssueNumber != null && isSafeGithubUrl(task.githubHtmlUrl)) {
                    TextButton(onClick = { uri.openUri(task.githubHtmlUrl!!) }) { Text("#${task.githubIssueNumber} ↗") }
                }
                if (task?.source == "github") Pill("from GitHub")
            }

            OutlinedTextField(
                value = title,
                onValueChange = { if (it.length <= 200) title = it },
                label = { Text("Title") },
                placeholder = { Text("Add rate limiting to the login endpoint") },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Picker(
                label = "Status",
                selected = status,
                options = TASK_STATUSES.map { it.status to it.label },
                onSelect = { status = it },
            )

            Picker(
                label = "Assignee",
                selected = assignee,
                options = listOf("" to "Unassigned") + memberOptions.map { it to "@$it" },
                enabled = canUseGithub || assignee.isNotEmpty(),
                onSelect = { assignee = it },
            )
            when {
                !canUseGithub -> MutedText(
                    "Assignees are the linked repo's collaborators. " +
                        if (github.repoUrl != null) "Connect GitHub on the website to pick one." else "Link a repo on Overview to pick one.",
                )
                state.members is MembersState.Loading -> MutedText("Loading the repo's collaborators…")
                state.members is MembersState.Failed -> Text(
                    "Couldn't load collaborators: ${state.members.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = WarningAmber,
                )
                else -> Unit
            }

            val areas = state.areas
            val areaMissing = area.isNotEmpty() && areas != null && areas.none { it.id == area }
            Picker(
                label = "Architecture area",
                selected = area,
                options = buildList {
                    add("" to if (areas == null) "Loading the map…" else "Not on the map")
                    if (areaMissing) add(area to (task?.nodeLabel ?: "Removed from the map"))
                    areas?.forEach { add(it.id to it.label + if (it.type == "erTable") " (table)" else "") }
                },
                enabled = areas != null,
                onSelect = { area = it },
            )
            MutedText("The task appears on the map next to this component, coloured by its status.")

            if (task?.sectionId != null) MutedText("§ Created from a doc section.")

            OutlinedTextField(
                value = scenario,
                onValueChange = { if (it.length <= 2000) scenario = it },
                label = { Text("Scenario") },
                placeholder = { Text("When a user signs in with a wrong password 5 times, they are locked out for 15 minutes.") },
                minLines = 2,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = body,
                onValueChange = { if (it.length <= 20000) body = it },
                label = { Text("Description") },
                placeholder = { Text("Details, acceptance criteria, links… (Markdown, same as the GitHub issue)") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                minLines = 5,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            if (canUseGithub && isNew) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = createIssue, onCheckedChange = { createIssue = it })
                    Column {
                        Text("Also open a GitHub issue")
                        MutedText("Title, description, assignee and status stay in sync both ways.")
                    }
                }
            }
            if (canUseGithub && task != null && task.githubIssueNumber == null) {
                OutlinedButton(onClick = { vm.openIssue(task) }, enabled = !busy, shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.editorBusy == "issue") "Opening issue…" else "Open a GitHub issue for this task")
                }
            }

            state.editorError?.let { ErrorBanner(it) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (task != null) {
                    if (confirmDelete) {
                        TextButton(
                            onClick = { vm.delete(task) },
                            enabled = !busy,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text(if (state.editorBusy == "delete") "Deleting…" else "Delete") }
                        TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                    } else {
                        TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
                    }
                }
                Box(Modifier.weight(1f))
                Button(
                    onClick = { vm.save(task, TaskDraft(title, status, assignee, area, scenario, body), createIssue) },
                    enabled = !busy && title.isNotBlank(),
                    shape = RoundedCornerShape(50),
                ) { Text(if (state.editorBusy == "save") "Saving…" else if (isNew) "Create task" else "Save") }
            }
            if (task?.githubIssueNumber != null) {
                MutedText(
                    "Deleting only removes it from DocLoom. The issue stays on GitHub and comes back on the next sync " +
                        "while it's open. Move it to Done to close the issue instead.",
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Picker(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (enabled) open = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second ?: selected,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); open = false })
            }
        }
    }
}
