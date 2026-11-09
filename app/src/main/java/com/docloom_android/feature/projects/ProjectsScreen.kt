package com.docloom_android.feature.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.docloom_android.core.model.Project
import com.docloom_android.core.model.User
import com.docloom_android.core.network.ApiResult
import com.docloom_android.core.network.apiCall
import com.docloom_android.core.ui.GlassCard
import com.docloom_android.core.ui.LoadingBox
import com.docloom_android.core.ui.MessageBox
import com.docloom_android.core.ui.MutedText
import com.docloom_android.core.ui.StatGrid
import com.docloom_android.core.ui.StatTile
import com.docloom_android.core.ui.timeAgo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProjectsState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val projects: List<Project> = emptyList(),
    val error: String? = null,
)

/** The user's projects; shared by Home and Projects, refreshed after any change. */
class ProjectsViewModel(private val api: ProjectsApi) : ViewModel() {
    private val _state = MutableStateFlow(ProjectsState())
    val state: StateFlow<ProjectsState> = _state.asStateFlow()

    init { load() }

    fun load(refresh: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(loading = !refresh && it.projects.isEmpty(), refreshing = refresh, error = null) }
            when (val r = apiCall { api.list() }) {
                is ApiResult.Ok -> _state.update { ProjectsState(loading = false, projects = r.value.projects) }
                is ApiResult.Err -> _state.update { it.copy(loading = false, refreshing = false, error = r.message) }
            }
        }
    }
}

/** Loading, error and pull-to-refresh around a project list body. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectsFrame(vm: ProjectsViewModel, body: LazyListScope.(ProjectsState) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    when {
        state.loading -> LoadingBox()
        state.error != null && state.projects.isEmpty() ->
            MessageBox("Couldn't load projects", state.error, "Try again", { vm.load() })
        else -> PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.load(refresh = true) },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) { body(state) }
        }
    }
}

/** Website /dashboard: welcome, totals, recently active projects. */
@Composable
fun HomeScreen(
    vm: ProjectsViewModel,
    user: User,
    onOpenProject: (String) -> Unit,
    onNewProject: () -> Unit,
    onAllProjects: () -> Unit,
    onMyTasks: () -> Unit,
) = ProjectsFrame(vm) { state ->
    val projects = state.projects
    val recent = projects.sortedByDescending { it.lastChangeAt ?: it.createdAt }.take(6)
    item {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Welcome back, ${user.username}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            MutedText("Your architecture maps, kept in sync with your code, your AI and your running apps.")
        }
    }
    item {
        Button(onClick = onNewProject, shape = RoundedCornerShape(50)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("  New project")
        }
    }
    item {
        val errors = projects.sumOf { it.openErrorCount }
        StatGrid(
            listOf(
                { m -> StatTile("Projects", "${projects.size}", m) },
                { m -> StatTile("Nodes mapped", "${projects.sumOf { it.nodeCount }}", m) },
                { m -> StatTile("Open tasks", "${projects.sumOf { it.openTaskCount }}", m, hint = "My tasks →", onClick = onMyTasks) },
                { m ->
                    StatTile(
                        "Open errors", "$errors", m,
                        hint = "Reported by your running apps",
                        valueColor = if (errors > 0) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
                    )
                },
            ),
        )
    }
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "RECENTLY ACTIVE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (projects.isNotEmpty()) TextButton(onClick = onAllProjects) { Text("All projects →") }
        }
    }
    if (recent.isEmpty()) {
        item { FirstProjectCard(onNewProject) }
    } else {
        items(recent, key = { it.id }) { ProjectCard(it) { onOpenProject(it.id) } }
    }
}

/** Website /dashboard/projects. */
@Composable
fun ProjectsScreen(vm: ProjectsViewModel, onOpenProject: (String) -> Unit, onNewProject: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        ProjectsFrame(vm) { state ->
            item { MutedText("Each project holds a live architecture map, its history, a board and the errors your apps report.") }
            if (state.projects.isEmpty()) {
                item { FirstProjectCard(onNewProject) }
            }
            items(state.projects, key = { it.id }) { ProjectCard(it) { onOpenProject(it.id) } }
        }
        ExtendedFloatingActionButton(
            onClick = onNewProject,
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("New project") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@Composable
private fun FirstProjectCard(onNewProject: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Start your first project", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Draw it yourself, scan a GitHub repo, paste a SQL schema, or let your AI build the map through DocLoom's MCP server.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(onClick = onNewProject, shape = RoundedCornerShape(50)) { Text("+ New project") }
        }
    }
}

private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

private fun repoName(url: String?) =
    url?.removePrefix("https://github.com/")?.removePrefix("http://github.com/")?.trimEnd('/')

@Composable
fun ProjectCard(project: Project, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (project.role != "owner") {
                    Text(
                        if (project.role == "viewer") "View only" else "Shared",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            val sub = listOfNotNull(project.orgName, repoName(project.repoUrl)).joinToString(" · ")
            if (sub.isNotEmpty()) MutedText(sub)
            MutedText(
                "${plural(project.nodeCount, "node")} · ${plural(project.sectionCount, "doc section")} · " +
                    "${project.openTaskCount} open tasks",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (project.openErrorCount > 0) {
                    Text(plural(project.openErrorCount, "open error"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                MutedText("Updated ${timeAgo(project.lastChangeAt ?: project.createdAt)}")
            }
        }
    }
}
