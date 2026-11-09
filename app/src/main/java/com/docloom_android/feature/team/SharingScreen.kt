package com.docloom_android.feature.team

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.docloom_android.core.model.Project
import com.docloom_android.core.ui.GlassCard
import com.docloom_android.core.ui.MutedText
import com.docloom_android.feature.projects.ProjectsViewModel

/** Website settings/members: which projects you own and which are shared with you. */
@Composable
fun SharingScreen(projectsVm: ProjectsViewModel, onBack: () -> Unit, onOpenProject: (String) -> Unit) {
    val state by projectsVm.state.collectAsStateWithLifecycle()
    val owned = state.projects.filter { it.role == "owner" }
    val shared = state.projects.filter { it.role != "owner" }
    SubPage("Members", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MutedText(
                "Share a project from its Overview: add teammates by DocLoom username, or invite any email address. Editors change " +
                    "the map and tasks; viewers can only look; only the owner can rename, delete or reshare it. For a whole company, " +
                    "set up an organisation instead.",
            )
            Group("YOUR PROJECTS", owned, "You don't own any projects yet.", onOpenProject)
            Group("SHARED WITH YOU", shared, "Nobody has shared a project with you yet. Give a teammate your username to be added to theirs.", onOpenProject)
        }
    }
}

@Composable
private fun Group(title: String, projects: List<Project>, empty: String, onOpen: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    if (projects.isEmpty()) {
        GlassCard(Modifier.fillMaxWidth()) { MutedText(empty, Modifier.padding(16.dp)) }
        return
    }
    projects.forEach { p ->
        Surface(onClick = { onOpen(p.id) }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                MutedText(
                    listOfNotNull(p.orgName, if (p.role == "viewer") "view only" else null, "${p.memberCount} member${if (p.memberCount == 1) "" else "s"}")
                        .joinToString(" · ") + "  Manage →",
                )
            }
        }
    }
}
