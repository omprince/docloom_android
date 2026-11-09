package com.docloom_android.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.docloom_android.core.model.CreateTaskRequest
import com.docloom_android.core.model.GithubMember
import com.docloom_android.core.model.Task
import com.docloom_android.core.model.TaskGithub
import com.docloom_android.core.network.ApiResult
import com.docloom_android.core.network.apiCall
import com.docloom_android.core.ui.Notice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.util.concurrent.atomic.AtomicInteger

sealed interface MembersState {
    data object Idle : MembersState
    data object Loading : MembersState
    data class Ready(val list: List<GithubMember>) : MembersState
    data class Failed(val message: String) : MembersState
}

/** What the editor sheet shows: an existing task, or a new one in a column. */
sealed interface EditorTarget {
    data class Edit(val taskId: String) : EditorTarget
    data class New(val status: String) : EditorTarget
}

data class TaskBoardState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val tasks: List<Task> = emptyList(),
    val github: TaskGithub = TaskGithub(),
    val notice: Notice? = null,
    /** null while the map is loading. */
    val areas: List<TaskArea>? = null,
    val members: MembersState = MembersState.Idle,
    val adding: Boolean = false,
    val syncing: Boolean = false,
    val hookBusy: Boolean = false,
    val mineOnly: Boolean = false,
    val editor: EditorTarget? = null,
    /** "save", "issue" or "delete" while the editor works. */
    val editorBusy: String? = null,
    val editorError: String? = null,
)

/** Port of the website's TaskBoard + TaskDrawer logic. */
class TaskBoardViewModel(
    private val api: TasksApi,
    private val projectId: String,
    openTaskId: String?,
    private val onChanged: () -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(TaskBoardState(editor = openTaskId?.let { EditorTarget.Edit(it) }))
    val state: StateFlow<TaskBoardState> = _state.asStateFlow()

    /** Local writes in flight; polling holds off so it can't undo them. */
    private val pending = AtomicInteger(0)

    init {
        load()
        viewModelScope.launch {
            val r = apiCall { api.graph(projectId) }
            _state.update { it.copy(areas = (r as? ApiResult.Ok)?.value?.nodes?.let(::areasFromGraph) ?: emptyList()) }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loadError = null) }
            when (val r = apiCall { api.list(projectId) }) {
                is ApiResult.Ok -> _state.update {
                    it.copy(
                        loading = false,
                        tasks = r.value.tasks,
                        github = r.value.github,
                        notice = r.value.syncError?.let { e -> Notice(false, "Couldn't pull GitHub issues: $e") } ?: it.notice,
                    )
                }.also {
                    // Opened straight onto a task (from My tasks): load its assignee choices too.
                    val s = _state.value
                    if (s.editor != null && s.members is MembersState.Idle && s.github.repoUrl != null && s.github.connected) loadMembers()
                }
                is ApiResult.Err -> _state.update { it.copy(loading = false, loadError = r.message) }
            }
        }
    }

    /** Quiet background refresh (the website polls every 10 seconds). */
    fun poll() {
        if (pending.get() > 0 || _state.value.loading) return
        viewModelScope.launch {
            val r = apiCall { api.list(projectId) }
            if (r is ApiResult.Ok && pending.get() == 0) {
                _state.update { it.copy(tasks = r.value.tasks, github = r.value.github) }
            }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }
    fun setMineOnly(mine: Boolean) = _state.update { it.copy(mineOnly = mine) }

    private suspend fun <T> tracked(block: suspend () -> T): T {
        pending.incrementAndGet()
        try {
            return block()
        } finally {
            pending.decrementAndGet()
        }
    }

    private fun upsert(task: Task) = _state.update { s ->
        val exists = s.tasks.any { it.id == task.id }
        s.copy(tasks = if (exists) s.tasks.map { if (it.id == task.id) task else it } else s.tasks + task)
    }

    private fun githubWarning(error: String?) {
        if (error != null) _state.update { it.copy(notice = Notice(false, "Saved in DocLoom, but GitHub wasn't updated: $error")) }
    }

    fun quickAdd(title: String, onDone: () -> Unit) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(adding = true) }
            val r = tracked { apiCall { api.create(projectId, CreateTaskRequest(clean, "backlog", null, null, null, null, false)) } }
            _state.update { it.copy(adding = false) }
            when (r) {
                is ApiResult.Ok -> {
                    upsert(r.value.task)
                    onChanged()
                    onDone()
                }
                is ApiResult.Err -> _state.update { it.copy(notice = Notice(false, r.message)) }
            }
        }
    }

    /** Moves a task to [status], before [beforeId] (null = end of the column). */
    fun move(taskId: String, status: String, beforeId: String?) {
        val tasks = _state.value.tasks
        val task = tasks.firstOrNull { it.id == taskId } ?: return
        val position = positionFor(tasks, taskId, status, beforeId) ?: return
        val previous = tasks
        _state.update { s -> s.copy(tasks = s.tasks.map { if (it.id == taskId) it.copy(status = status, position = position) else it }) }
        viewModelScope.launch {
            when (val r = tracked { apiCall { api.update(projectId, taskId, movePatch(task, status, position)) } }) {
                is ApiResult.Ok -> {
                    upsert(r.value.task)
                    githubWarning(r.value.githubError)
                    if (task.status != status) onChanged()
                }
                is ApiResult.Err -> _state.update { it.copy(tasks = previous, notice = Notice(false, r.message)) }
            }
        }
    }

    /** Moves a task one place up (-1) or down (+1) inside its column. */
    fun nudge(taskId: String, direction: Int) {
        val task = _state.value.tasks.firstOrNull { it.id == taskId } ?: return
        val column = columnTasks(_state.value.tasks, task.status)
        val index = column.indexOfFirst { it.id == taskId }
        val before = when {
            direction < 0 && index > 0 -> column[index - 1].id
            direction > 0 && index < column.size - 1 -> column.getOrNull(index + 2)?.id
            else -> return
        }
        move(taskId, task.status, before)
    }

    fun syncIssues() {
        viewModelScope.launch {
            _state.update { it.copy(syncing = true, notice = null) }
            val r = tracked { apiCall { api.syncIssues(projectId) } }
            when (r) {
                is ApiResult.Ok -> {
                    val n = r.value.synced
                    _state.update {
                        it.copy(syncing = false, tasks = r.value.tasks, notice = Notice(true, "Synced $n issue${if (n == 1) "" else "s"} from GitHub."))
                    }
                    onChanged()
                }
                is ApiResult.Err -> _state.update { it.copy(syncing = false, notice = Notice(false, r.message)) }
            }
        }
    }

    fun toggleWebhook() {
        val on = _state.value.github.webhook
        viewModelScope.launch {
            _state.update { it.copy(hookBusy = true, notice = null) }
            val r = apiCall { if (on) api.disableWebhook(projectId) else api.enableWebhook(projectId) }
            when (r) {
                is ApiResult.Ok -> _state.update {
                    it.copy(
                        hookBusy = false,
                        github = it.github.copy(webhook = r.value.webhook),
                        notice = if (r.value.webhook) Notice(true, "Live sync is on: issue changes on GitHub now show up here by themselves.") else null,
                    )
                }
                is ApiResult.Err -> _state.update { it.copy(hookBusy = false, notice = Notice(false, r.message)) }
            }
        }
    }

    // ---- Editor sheet ----

    fun openEditor(target: EditorTarget) {
        _state.update { it.copy(editor = target, editorError = null, editorBusy = null) }
        val s = _state.value
        if (s.github.repoUrl != null && s.github.connected && (s.members is MembersState.Idle || s.members is MembersState.Failed)) {
            loadMembers()
        }
    }

    fun closeEditor() = _state.update { it.copy(editor = null, editorError = null, editorBusy = null) }

    private fun loadMembers() {
        _state.update { it.copy(members = MembersState.Loading) }
        viewModelScope.launch {
            val r = apiCall { api.assignees(projectId) }
            _state.update {
                it.copy(members = if (r is ApiResult.Ok) MembersState.Ready(r.value.members) else MembersState.Failed((r as ApiResult.Err).message))
            }
        }
    }

    fun save(task: Task?, draft: TaskDraft, createIssue: Boolean) {
        if (draft.title.isBlank()) {
            _state.update { it.copy(editorError = "Give the task a title.") }
            return
        }
        val members = (_state.value.members as? MembersState.Ready)?.list.orEmpty()
        viewModelScope.launch {
            _state.update { it.copy(editorBusy = "save", editorError = null) }
            val r = tracked {
                if (task == null) {
                    val gh = _state.value.github
                    apiCall {
                        api.create(
                            projectId,
                            CreateTaskRequest(
                                title = draft.title.trim(),
                                status = draft.status,
                                body = draft.body.trim().ifEmpty { null },
                                scenario = draft.scenario.trim().ifEmpty { null },
                                nodeId = draft.area.ifEmpty { null },
                                assigneeLogin = draft.assignee.ifEmpty { null },
                                createIssue = gh.repoUrl != null && gh.connected && createIssue,
                            ),
                        )
                    }
                } else {
                    val patch = taskPatch(task, draft) { login -> members.firstOrNull { it.login == login }?.avatarUrl }
                    if (patch.isEmpty()) {
                        _state.update { it.copy(editorBusy = null) }
                        closeEditor()
                        return@tracked null
                    }
                    apiCall { api.update(projectId, task.id, patch) }
                }
            } ?: return@launch
            when (r) {
                is ApiResult.Ok -> {
                    upsert(r.value.task)
                    githubWarning(r.value.githubError)
                    closeEditor()
                    onChanged()
                }
                is ApiResult.Err -> _state.update { it.copy(editorBusy = null, editorError = r.message) }
            }
        }
    }

    fun openIssue(task: Task) {
        viewModelScope.launch {
            _state.update { it.copy(editorBusy = "issue", editorError = null) }
            when (val r = tracked { apiCall { api.openIssue(projectId, task.id) } }) {
                is ApiResult.Ok -> {
                    upsert(r.value.task)
                    _state.update { it.copy(editorBusy = null) }
                }
                is ApiResult.Err -> _state.update { it.copy(editorBusy = null, editorError = r.message) }
            }
        }
    }

    fun delete(task: Task) {
        viewModelScope.launch {
            _state.update { it.copy(editorBusy = "delete", editorError = null) }
            val r = tracked {
                apiCall {
                    val response = api.delete(projectId, task.id)
                    if (!response.isSuccessful) throw HttpException(response)
                }
            }
            when (r) {
                is ApiResult.Ok -> {
                    _state.update { s -> s.copy(tasks = s.tasks.filter { it.id != task.id }) }
                    closeEditor()
                    onChanged()
                }
                is ApiResult.Err -> _state.update { it.copy(editorBusy = null, editorError = r.message) }
            }
        }
    }
}
