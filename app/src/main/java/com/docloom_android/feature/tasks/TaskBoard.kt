package com.docloom_android.feature.tasks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.docloom_android.core.model.Task
import com.docloom_android.core.ui.LoadingBox
import com.docloom_android.core.ui.MessageBox
import com.docloom_android.core.ui.MutedText
import com.docloom_android.core.ui.NoticeBanner
import com.docloom_android.ui.theme.SuccessGreen
import kotlinx.coroutines.delay

private const val POLL_MS = 10_000L

/**
 * The project's tasks as a board. On a phone the three columns are tabs, and
 * a card's menu moves it (the website drags instead).
 */
@Composable
fun TaskBoard(vm: TaskBoardViewModel, onOpenOverview: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var column by rememberSaveable { mutableIntStateOf(0) }
    var quickTitle by rememberSaveable { mutableStateOf("") }

    // Poll while the screen is visible, like the website does while its tab is visible.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(POLL_MS)
                vm.poll()
            }
        }
    }

    when {
        state.loading -> LoadingBox()
        state.loadError != null && state.tasks.isEmpty() -> MessageBox("Couldn't load tasks", state.loadError, "Try again", { vm.load() })
        else -> Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GithubBar(state, vm, onOpenOverview)
                state.notice?.let { notice ->
                    Box {
                        NoticeBanner(notice)
                        IconButton(onClick = { vm.dismissNotice() }, modifier = Modifier.align(Alignment.TopEnd).size(36.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = quickTitle,
                        onValueChange = { if (it.length <= 200) quickTitle = it },
                        placeholder = { Text("Quick add a task…") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { vm.quickAdd(quickTitle) { quickTitle = "" } }),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { vm.quickAdd(quickTitle) { quickTitle = "" } },
                        enabled = !state.adding && quickTitle.isNotBlank(),
                        shape = RoundedCornerShape(50),
                    ) { Text(if (state.adding) "Adding…" else "Add") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.github.login != null) {
                        FilterChip(selected = !state.mineOnly, onClick = { vm.setMineOnly(false) }, label = { Text("All") })
                        FilterChip(selected = state.mineOnly, onClick = { vm.setMineOnly(true) }, label = { Text("Assigned to me") })
                    }
                    Box(Modifier.weight(1f))
                    TextButton(onClick = { vm.openEditor(EditorTarget.New(TASK_STATUSES[column].status)) }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(" New task")
                    }
                }
            }

            val login = state.github.login
            val visible = if (state.mineOnly && login != null) {
                state.tasks.filter { it.assigneeLogin.equals(login, ignoreCase = true) }
            } else {
                state.tasks
            }
            val labels = remember(state.areas) { state.areas.orEmpty().associate { it.id to it.label } }

            PrimaryTabRow(selectedTabIndex = column) {
                TASK_STATUSES.forEachIndexed { i, meta ->
                    Tab(selected = column == i, onClick = { column = i }, text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(meta.color, CircleShape))
                            Text("  ${meta.label} ${columnTasks(visible, meta.status).size}", maxLines = 1)
                        }
                    })
                }
            }

            val status = TASK_STATUSES[column].status
            val items = columnTasks(visible, status)
            if (items.isEmpty()) {
                MessageBox(
                    if (state.mineOnly) "Nothing assigned to you here" else "No tasks here",
                    "Add one above, or move a task here from another column.",
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { it.id }) { task ->
                        val index = items.indexOf(task)
                        TaskCard(
                            task = task,
                            area = task.nodeId?.let { labels[it] ?: task.nodeLabel },
                            canMoveUp = index > 0,
                            canMoveDown = index < items.size - 1,
                            onOpen = { vm.openEditor(EditorTarget.Edit(task.id)) },
                            onMoveTo = { s -> vm.move(task.id, s, null) },
                            onNudge = { d -> vm.nudge(task.id, d) },
                        )
                    }
                }
            }
        }
    }

    state.editor?.let { target ->
        val task = (target as? EditorTarget.Edit)?.let { t -> state.tasks.firstOrNull { it.id == t.taskId } }
        // An opened link to a task that no longer exists just closes.
        if (target is EditorTarget.Edit && task == null) {
            if (!state.loading) LaunchedEffect(target) { vm.closeEditor() }
        } else {
            TaskEditorSheet(
                task = task,
                defaultStatus = (target as? EditorTarget.New)?.status ?: "backlog",
                state = state,
                vm = vm,
            )
        }
    }
}

@Composable
private fun GithubBar(state: TaskBoardState, vm: TaskBoardViewModel, onOpenOverview: () -> Unit) {
    val github = state.github
    val uri = LocalUriHandler.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                github.repoUrl == null -> {
                    MutedText("Tasks live here only. Link a GitHub repo on Overview to sync them with its issues.")
                    TextButton(onClick = onOpenOverview, contentPadding = PaddingValues(0.dp)) { Text("Link a GitHub repo") }
                }
                !github.connected -> MutedText(
                    "Linked to ${repoName(github.repoUrl)}. Connect your GitHub account on the website " +
                        "(Settings → Integrations) to sync issues and assign people.",
                )
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            repoName(github.repoUrl) ?: "",
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        MutedText("  as @${github.login}")
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.toggleWebhook() }, enabled = !state.hookBusy, shape = RoundedCornerShape(50)) {
                            Box(
                                Modifier.size(8.dp).background(
                                    if (github.webhook) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    CircleShape,
                                ),
                            )
                            Text(if (state.hookBusy) "  …" else if (github.webhook) "  Live sync on" else "  Turn on live sync")
                        }
                        OutlinedButton(onClick = { vm.syncIssues() }, enabled = !state.syncing, shape = RoundedCornerShape(50)) {
                            Text(if (state.syncing) "Syncing…" else "Sync issues")
                        }
                        if (isSafeGithubUrl(github.repoUrl)) {
                            TextButton(onClick = { uri.openUri(github.repoUrl!!) }) { Text("Open repo ↗") }
                        }
                    }
                    MutedText("Live sync installs a webhook on the repo and needs admin rights there.")
                }
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: Task,
    area: String?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onOpen: () -> Unit,
    onMoveTo: (String) -> Unit,
    onNudge: (Int) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val done = task.status == "done"
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    task.title,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                task.scenario?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    task.githubIssueNumber?.let { Pill("#$it") }
                    area?.let { Pill("◆ $it", accent = true) }
                    task.assigneeLogin?.let { Pill("@$it") }
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Move ${task.title}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    TASK_STATUSES.filter { it.status != task.status }.forEach { meta ->
                        DropdownMenuItem(text = { Text("Move to ${meta.label}") }, onClick = { menu = false; onMoveTo(meta.status) })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Move up") }, enabled = canMoveUp, onClick = { menu = false; onNudge(-1) })
                    DropdownMenuItem(text = { Text("Move down") }, enabled = canMoveDown, onClick = { menu = false; onNudge(1) })
                }
            }
        }
    }
}

@Composable
fun Pill(text: String, accent: Boolean = false) {
    val color = if (accent) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.12f)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp).padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
