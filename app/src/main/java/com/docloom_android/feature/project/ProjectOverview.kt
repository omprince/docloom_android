package com.docloom_android.feature.project

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docloom_android.BuildConfig
import com.docloom_android.core.model.Project
import com.docloom_android.core.ui.ActorChip
import com.docloom_android.core.ui.CopyableCode
import com.docloom_android.core.ui.MutedText
import com.docloom_android.core.ui.NoticeBanner
import com.docloom_android.core.ui.SectionCard
import com.docloom_android.core.ui.StatGrid
import com.docloom_android.core.ui.StatTile
import com.docloom_android.core.ui.summarize
import com.docloom_android.core.ui.timeAgo
import com.docloom_android.ui.theme.SuccessGreen

/** Port of the website's ProjectOverview. [onOpenTab] takes a ProjectTab name. */
@Composable
fun ProjectOverview(state: ProjectState, vm: ProjectViewModel, onOpenTab: (String) -> Unit) {
    val project = state.project ?: return
    val isOwner = project.role == "owner"
    val busy = state.busy != null

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MutedText(
            "Version ${project.graphVersion} · " +
                (project.lastChangeAt?.let { "changed ${timeAgo(it)}" } ?: "no changes yet"),
        )

        state.notice?.let { NoticeBanner(it) }

        StatGrid(
            listOf(
                { m -> StatTile("Nodes", "${project.nodeCount}", m, hint = "${project.edgeCount} connections · ${project.tableCount} tables") },
                { m ->
                    StatTile(
                        "Open tasks", "${project.openTaskCount}", m,
                        hint = when {
                            project.githubWebhookActive -> "Live-synced with GitHub issues"
                            project.repoUrl != null -> "Synced with GitHub issues"
                            else -> null
                        },
                        onClick = { onOpenTab("Tasks") },
                    )
                },
                { m -> StatTile("Members", "${project.memberCount}", m, hint = if (isOwner) "You own this project" else "Shared with you") },
                { m ->
                    StatTile(
                        "Open errors", "${project.openErrorCount}", m,
                        valueColor = if (project.openErrorCount > 0) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
                        onClick = { onOpenTab("Errors") },
                    )
                },
            ),
        )

        MapYourCodebase(project, state, vm, busy)
        AiSetup(project.id)
        Details(project, isOwner, state.busy, vm)

        SectionCard("Documentation", actionLabel = "Open docs", onAction = { onOpenTab("Docs") }) {
            MutedText(
                if (project.sectionCount > 0) {
                    "${project.sectionCount} section${if (project.sectionCount == 1) "" else "s"}, linked to the map and the board."
                } else {
                    "Write the docs next to the map: start from your repo, from the map, or from a blank page."
                },
            )
        }

        SectionCard("GitHub issues", actionLabel = "Open tasks", onAction = { onOpenTab("Tasks") }) {
            if (project.repoUrl == null) {
                MutedText("Link a repo in Details and its issues become tasks, on the board and pinned to the map.")
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(8.dp).background(
                            if (project.githubWebhookActive) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            CircleShape,
                        ),
                    )
                    MutedText(
                        if (project.githubWebhookActive) "  Live sync is on: issue changes arrive by themselves."
                        else "  Live sync is off: use Sync issues on the Tasks tab.",
                    )
                }
                MutedText("Edits on DocLoom tasks are written back to their issues: title, description, assignee and open/closed.")
                if (!state.github.connected) MutedText("Connect GitHub in Settings → Integrations on the website to sync.")
            }
        }

        SectionCard("Recent changes", actionLabel = "Full history", onAction = { onOpenTab("History") }) {
            if (state.recent.isEmpty()) {
                MutedText("Nothing yet. Changes from you, scans and your AI show up here.")
            } else {
                state.recent.forEach { r ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActorChip(r.actorType)
                            Text(
                                r.actorLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            MutedText(timeAgo(r.createdAt))
                        }
                        Text(r.reason ?: summarize(r), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        if (isOwner) DangerZone(project, state.busy == "delete", vm)
    }
}

@Composable
private fun MapYourCodebase(project: Project, state: ProjectState, vm: ProjectViewModel, busy: Boolean) {
    var sqlOpen by rememberSaveable { mutableStateOf(false) }
    var sql by rememberSaveable { mutableStateOf("") }
    SectionCard("Map your codebase") {
        MutedText(
            "Read the linked repo's SQL migrations and route definitions into an ER diagram and an API map. " +
                "Re-scanning updates the same nodes and keeps your layout.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.scanRepo() }, enabled = !busy && project.repoUrl != null, shape = RoundedCornerShape(50)) {
                Text(if (state.busy == "scan") "Scanning…" else "Scan repo")
            }
            OutlinedButton(onClick = { sqlOpen = !sqlOpen }, enabled = !busy, shape = RoundedCornerShape(50)) {
                Text("Import SQL schema")
            }
        }
        if (project.repoUrl == null) MutedText("Link a GitHub repo in Details to enable scanning.")
        else if (!state.github.connected) MutedText("Public repos scan without GitHub. For private ones, connect GitHub on the website.")
        if (sqlOpen) {
            OutlinedTextField(
                value = sql,
                onValueChange = { sql = it },
                placeholder = { Text("CREATE TABLE users (id UUID PRIMARY KEY, ...);", fontFamily = FontFamily.Monospace) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                minLines = 6,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    vm.importSql(sql)
                    sql = ""
                    sqlOpen = false
                },
                enabled = !busy && sql.isNotBlank(),
                shape = RoundedCornerShape(50),
            ) { Text(if (state.busy == "sql") "Importing…" else "Import") }
        }
    }
}

@Composable
private fun AiSetup(projectId: String) {
    val url = BuildConfig.WEBSITE_URL + "/mcp"
    val claudeCode = "claude mcp add --transport http docloom $url"
    val json = """{
  "mcpServers": {
    "docloom": {
      "type": "http",
      "url": "$url"
    }
  }
}"""
    SectionCard("Let your AI build the map") {
        MutedText(
            "DocLoom is an MCP server. Connect Claude (or any MCP client) and it can read this map, add components, " +
                "connect them and apply schemas. Each step lands live on the canvas and in History.",
        )
        CopyableCode("MCP server address", url)
        CopyableCode("Claude Code", claudeCode)
        CopyableCode("Other MCP clients (JSON config)", json)
        MutedText(
            "The first time it connects, your AI client opens DocLoom in the browser. Sign in and tap Allow; no key needed. " +
                "For clients that can't sign in, use an API key from Settings on the website.",
        )
        MutedText("Then ask your AI, for example: \"Read my design doc and map its architecture into DocLoom project $projectId.\"")
    }
}

@Composable
private fun Details(project: Project, isOwner: Boolean, busy: String?, vm: ProjectViewModel) {
    // Keyed on the saved values so a successful save resets the "changed" state.
    var name by remember(project.name) { mutableStateOf(project.name) }
    var repoUrl by remember(project.repoUrl) { mutableStateOf(project.repoUrl ?: "") }
    val changed = name.trim() != project.name || (repoUrl.trim().ifEmpty { null }) != project.repoUrl

    SectionCard("Details") {
        OutlinedTextField(
            value = name,
            onValueChange = { if (it.length <= 100) name = it },
            label = { Text("Name") },
            singleLine = true,
            enabled = isOwner,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = repoUrl,
            onValueChange = { repoUrl = it },
            label = { Text("GitHub repo") },
            placeholder = { Text("https://github.com/owner/repo") },
            singleLine = true,
            enabled = isOwner,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        if (isOwner) {
            OutlinedButton(
                onClick = { vm.saveDetails(name, repoUrl) },
                enabled = busy == null && changed && name.isNotBlank(),
                shape = RoundedCornerShape(50),
            ) { Text(if (busy == "save") "Saving…" else "Save") }
        } else {
            MutedText("Only the owner can rename the project or change its repo.")
        }
    }
}

@Composable
private fun DangerZone(project: Project, deleting: Boolean, vm: ProjectViewModel) {
    var confirm by remember { mutableStateOf(false) }
    SectionCard("Delete project") {
        MutedText("Permanently deletes \"${project.name}\", its map, history, tasks and errors.")
        OutlinedButton(
            onClick = { confirm = true },
            enabled = !deleting,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text(if (deleting) "Deleting…" else "Delete project…") }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete ${project.name}?", fontWeight = FontWeight.SemiBold) },
            text = { Text("Its map, history, tasks and errors will be permanently deleted. This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        vm.delete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
