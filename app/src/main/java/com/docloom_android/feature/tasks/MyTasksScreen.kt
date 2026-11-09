package com.docloom_android.feature.tasks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.docloom_android.core.model.MyTask
import com.docloom_android.core.network.ApiResult
import com.docloom_android.core.network.apiCall
import com.docloom_android.core.ui.GlassCard
import com.docloom_android.core.ui.LoadingBox
import com.docloom_android.core.ui.MessageBox
import com.docloom_android.core.ui.MutedText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MyTasksState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val tasks: List<MyTask> = emptyList(),
    val githubLogin: String? = null,
    val error: String? = null,
)

class MyTasksViewModel(private val api: TasksApi) : ViewModel() {
    private val _state = MutableStateFlow(MyTasksState())
    val state: StateFlow<MyTasksState> = _state.asStateFlow()

    /** Called each time the tab is shown, so it never goes stale. */
    fun load(refresh: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(refreshing = refresh, error = null) }
            when (val r = apiCall { api.mine() }) {
                is ApiResult.Ok -> _state.update { MyTasksState(loading = false, tasks = r.value.tasks, githubLogin = r.value.githubLogin) }
                is ApiResult.Err -> _state.update { it.copy(loading = false, refreshing = false, error = r.message) }
            }
        }
    }
}

/** Website /dashboard/tasks: your tasks across projects, grouped by status. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyTasksScreen(vm: MyTasksViewModel, onOpenTask: (projectId: String, taskId: String) -> Unit, onProjects: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    when {
        state.loading -> LoadingBox()
        state.error != null && state.tasks.isEmpty() -> MessageBox("Couldn't load your tasks", state.error, "Try again", { vm.load() })
        else -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { vm.load(refresh = true) }, modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    MutedText(
                        state.githubLogin?.let { "Assigned to @$it or created by you, across every project you're in." }
                            ?: "Tasks you created, across every project you're in. Connect GitHub on the website to also see issues assigned to you.",
                    )
                }
                if (state.tasks.isEmpty()) {
                    item {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Nothing on your plate", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                MutedText("Tasks you create, and GitHub issues assigned to you in your projects, show up here. Open a project's Tasks tab to plan work.")
                                OutlinedButton(onClick = onProjects, shape = RoundedCornerShape(50)) { Text("Go to projects") }
                            }
                        }
                    }
                } else {
                    val open = state.tasks.count { it.status != "done" }
                    item { MutedText("$open open · ${state.tasks.size - open} finished in the last two weeks") }
                    TASK_STATUSES.forEach { meta ->
                        val group = state.tasks.filter { it.status == meta.status }
                        if (group.isEmpty()) return@forEach
                        item(key = "h-${meta.status}") {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                                Box(Modifier.size(8.dp).background(meta.color, CircleShape))
                                Text(
                                    "  ${meta.label.uppercase()}  ${group.size}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(group, key = { it.id }) { task -> MyTaskRow(task) { onOpenTask(task.projectId, task.id) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun MyTaskRow(task: MyTask, onClick: () -> Unit) {
    val done = task.status == "done"
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(statusMeta(task.status).color, CircleShape))
                Text(
                    "  ${task.title}",
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(task.projectName)
                task.githubIssueNumber?.let { Pill("#$it") }
                task.assigneeLogin?.let { Pill("@$it") }
            }
        }
    }
}
