package com.docloom_android.feature.docs

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.docloom_android.core.model.CreateSectionRequest
import com.docloom_android.core.model.DocPerson
import com.docloom_android.core.model.DocSection
import com.docloom_android.core.model.SectionConflict
import com.docloom_android.core.model.TaskOutcome
import com.docloom_android.core.network.ApiJson
import com.docloom_android.core.network.ApiResult
import com.docloom_android.core.network.apiCall
import com.docloom_android.core.ui.Notice
import com.docloom_android.feature.tasks.TasksApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import retrofit2.HttpException

enum class SaveState(val text: String) {
    Saved("Saved"),
    Dirty("Unsaved changes"),
    Saving("Saving…"),
    Conflict("Not saved: conflict"),
    Error("Not saved"),
}

data class SectionEditorState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val section: DocSection? = null,
    val saveState: SaveState = SaveState.Saved,
    /** The newer version someone else saved, while there's a conflict. */
    val conflict: DocSection? = null,
    val error: String? = null,
    val toast: Notice? = null,
    val deleted: Boolean = false,
)

/** Task created from a doc line; carries the section it came from. */
@Serializable
private data class DocTaskRequest(
    val title: String,
    val sectionId: String,
    val nodeId: String?,
    val createIssue: Boolean,
    val labels: List<String>,
)

private const val SAVE_DELAY_MS = 800L
private const val TITLE_DELAY_MS = 600L

/**
 * One section being read or edited. Saves on a short delay after each edit,
 * one save at a time, each based on the version the last one produced; a 409
 * means someone else saved first (website SectionEditor).
 */
class SectionEditorViewModel(
    private val api: DocsApi,
    private val tasksApi: TasksApi,
    private val projectId: String,
    private val sectionId: String,
    /** (tocChanged, refsChanged) */
    private val onChanged: (Boolean, Boolean) -> Unit,
    /** Outlives this screen, for the last save when it closes. */
    private val appScope: CoroutineScope,
) : ViewModel() {
    private val _state = MutableStateFlow(SectionEditorState())
    val state: StateFlow<SectionEditorState> = _state.asStateFlow()

    private val _body = MutableStateFlow(TextFieldValue(""))
    val body: StateFlow<TextFieldValue> = _body.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private var version = 0
    private val saveLock = Mutex()
    private var bodyJob: Job? = null
    private var titleJob: Job? = null
    private var bodyDirty = false

    init { load() }

    fun load() {
        viewModelScope.launch {
            when (val r = apiCall { api.section(projectId, sectionId) }) {
                is ApiResult.Ok -> take(r.value.section)
                is ApiResult.Err -> _state.update { it.copy(loading = false, loadError = r.message) }
            }
        }
    }

    /** Reopened: pick up changes made elsewhere, unless there are local edits. */
    fun refreshIfClean() {
        if (!_state.value.loading && !bodyDirty && _state.value.conflict == null) load()
    }

    private fun take(section: DocSection) {
        version = section.version
        bodyDirty = false
        _body.value = TextFieldValue(section.body)
        _title.value = section.title
        _state.update { it.copy(loading = false, loadError = null, section = section, conflict = null, saveState = SaveState.Saved, error = null) }
    }

    fun dismissToast() = _state.update { it.copy(toast = null) }

    // ---- editing ----

    fun onBodyChange(value: TextFieldValue) {
        val snapped = snapTokenEdit(_body.value, value)
        val changed = snapped.text != _body.value.text
        _body.value = snapped
        if (changed) bodyEdited()
    }

    private fun bodyEdited() {
        if (_state.value.conflict != null) return
        bodyDirty = true
        _state.update { it.copy(saveState = SaveState.Dirty) }
        bodyJob?.cancel()
        bodyJob = viewModelScope.launch {
            delay(SAVE_DELAY_MS)
            flushBody()
        }
    }

    /** Saves pending body edits now (also called when leaving the editor). */
    fun flushBody() {
        bodyJob?.cancel()
        if (!bodyDirty || _state.value.conflict != null) return
        bodyDirty = false
        val text = _body.value.text
        viewModelScope.launch { save(buildJsonObject { put("body", JsonPrimitive(text)) }) }
    }

    fun onTitleChange(value: String) {
        if (value.length > 200) return
        _title.value = value
        titleJob?.cancel()
        if (value.isBlank() || _state.value.conflict != null) return
        titleJob = viewModelScope.launch {
            delay(TITLE_DELAY_MS)
            if (save(buildJsonObject { put("title", JsonPrimitive(value.trim())) })) onChanged(true, true)
        }
    }

    fun setNode(nodeId: String?) {
        viewModelScope.launch {
            if (save(buildJsonObject { put("nodeId", if (nodeId == null) JsonNull else JsonPrimitive(nodeId)) })) onChanged(true, true)
        }
    }

    /** Saves fields in order; [force] drops `baseVersion` to overwrite a newer version. */
    private suspend fun save(fields: JsonObject, force: Boolean = false): Boolean = saveLock.withLock {
        _state.update { it.copy(saveState = SaveState.Saving) }
        val body = if (force) fields else JsonObject(fields + ("baseVersion" to JsonPrimitive(version)))
        val result = apiCall { api.updateSection(projectId, sectionId, body) }
        when (result) {
            is ApiResult.Err -> {
                bodyDirty = true
                _state.update { it.copy(saveState = SaveState.Error, error = "You're offline or the server didn't answer. Changes save when you edit again.") }
                false
            }
            is ApiResult.Ok -> {
                val response = result.value
                when {
                    response.code() == 409 -> {
                        val raw = response.errorBody()?.string()
                        val conflict = raw?.let { runCatching { ApiJson.decodeFromString(SectionConflict.serializer(), it) }.getOrNull() }
                        _state.update { it.copy(saveState = SaveState.Conflict, conflict = conflict?.section) }
                        false
                    }
                    !response.isSuccessful -> {
                        val raw = response.errorBody()?.string()
                        val msg = raw?.let { runCatching { ApiJson.decodeFromString(SectionConflict.serializer(), it).error }.getOrNull() }
                        _state.update { it.copy(saveState = if (bodyDirty) SaveState.Dirty else SaveState.Error, error = msg ?: "Couldn't save.") }
                        false
                    }
                    else -> {
                        val saved = response.body()?.section
                        if (saved != null) version = saved.version
                        _state.update {
                            it.copy(
                                section = saved ?: it.section,
                                error = null,
                                saveState = if (bodyDirty) SaveState.Dirty else SaveState.Saved,
                            )
                        }
                        true
                    }
                }
            }
        }
    }

    /** Conflict: drop local edits and show the other person's version. */
    fun takeTheirs() {
        val theirs = _state.value.conflict ?: return
        take(theirs)
    }

    /** Conflict: overwrite their version with this one. */
    fun keepMine() {
        val theirs = _state.value.conflict ?: return
        version = theirs.version
        _state.update { it.copy(conflict = null) }
        val fields = buildJsonObject {
            put("title", JsonPrimitive(_title.value.trim().ifEmpty { theirs.title }))
            put("body", JsonPrimitive(_body.value.text))
        }
        viewModelScope.launch {
            bodyDirty = false
            save(fields, force = true)
        }
    }

    // ---- "/" create, "@" mention, "#" link ----

    fun insertToken(kind: String, value: String) {
        _body.value = _body.value.insertAtCursor(tokenText(kind, value))
        bodyEdited()
    }

    fun applyPrefix(prefix: String) {
        _body.value = _body.value.withLinePrefix(prefix)
        bodyEdited()
    }

    fun insertBlock(block: String) {
        val v = _body.value
        val atLineStart = v.selection.min == 0 || v.text.getOrNull(v.selection.min - 1) == '\n'
        _body.value = v.insertAtCursor((if (atLineStart) "" else "\n") + block + "\n")
        bodyEdited()
    }

    /** What a new task from the current line would be called ("" = ask for a title). */
    fun currentLineTitle(): String = _body.value.currentLinePlainText()

    /**
     * Creates a task from the current line (or [typedTitle]) and puts its chip
     * there. [kind]: "task", "issue" or "bug" (website "/" menu).
     */
    fun createTask(kind: String, typedTitle: String?, canGithub: Boolean) {
        val fromLine = typedTitle == null
        val name = (typedTitle ?: currentLineTitle()).trim()
        if (name.isEmpty()) return
        val withIssue = kind != "task" && canGithub
        val section = _state.value.section ?: return
        val lineNode = _body.value.currentLineTokens("node").firstOrNull()
        val request = DocTaskRequest(
            title = if (kind == "bug" && !withIssue) "Bug: $name" else name,
            sectionId = section.id,
            nodeId = lineNode ?: section.nodeId,
            createIssue = withIssue,
            labels = if (kind == "bug" && withIssue) listOf("bug") else emptyList(),
        )
        viewModelScope.launch {
            val r = apiCall { tasksApi.createRaw(projectId, ApiJson.encodeToJsonElement(DocTaskRequest.serializer(), request) as JsonObject) }
            when (r) {
                is ApiResult.Err -> _state.update { it.copy(toast = Notice(false, r.message)) }
                is ApiResult.Ok -> {
                    val task = r.value.task
                    val token = tokenText("task", task.id)
                    _body.value = if (fromLine) _body.value.replaceCurrentLineContent(token) else _body.value.insertAtCursor(token)
                    bodyEdited()
                    onChanged(false, true)
                    val where = if (request.nodeId != null) " and the map" else ""
                    val msg = r.value.githubError?.let { Notice(false, "Task created, but GitHub said: $it") }
                        ?: Notice(true, task.githubIssueNumber?.let { "Opened issue #$it, on the board$where" } ?: "Task added to the board$where")
                    _state.update { it.copy(toast = msg) }
                }
            }
        }
    }

    fun createSection(title: String) {
        val chapterId = _state.value.section?.chapterId ?: return
        viewModelScope.launch {
            when (val r = apiCall { api.createSection(projectId, CreateSectionRequest(chapterId, title.trim(), "")) }) {
                is ApiResult.Err -> _state.update { it.copy(toast = Notice(false, r.message)) }
                is ApiResult.Ok -> {
                    insertToken("section", r.value.section.id)
                    onChanged(true, true)
                    _state.update { it.copy(toast = Notice(true, "Section “${r.value.section.title}” added")) }
                }
            }
        }
    }

    /** Inserts `@login`; if the line holds a task, also assigns it (website mention()). */
    fun mention(person: DocPerson, taskTitle: (String) -> String?) {
        val taskId = _body.value.currentLineTokens("task").firstOrNull()
        insertToken("user", person.login)
        if (taskId == null) return
        val patch = buildJsonObject {
            put("assigneeLogin", JsonPrimitive(person.login))
            person.avatarUrl?.let { put("assigneeAvatarUrl", JsonPrimitive(it)) }
        }
        viewModelScope.launch {
            val r = apiCall<TaskOutcome> { tasksApi.update(projectId, taskId, patch) }
            val toast = when (r) {
                is ApiResult.Err -> Notice(false, r.message)
                is ApiResult.Ok -> r.value.githubError?.let { Notice(false, "Assigned here, but GitHub said: $it") }
                    ?: Notice(true, "Assigned “${taskTitle(taskId) ?: "task"}” to @${person.login}")
            }
            _state.update { it.copy(toast = toast) }
            onChanged(false, true)
        }
    }

    fun delete() {
        bodyJob?.cancel()
        titleJob?.cancel()
        viewModelScope.launch {
            val r = apiCall {
                val response = api.deleteSection(projectId, sectionId)
                if (!response.isSuccessful) throw HttpException(response)
            }
            when (r) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(deleted = true) }
                    onChanged(true, true)
                }
                is ApiResult.Err -> _state.update { it.copy(toast = Notice(false, r.message)) }
            }
        }
    }

    override fun onCleared() {
        // Last edits within the save delay would otherwise be lost.
        if (bodyDirty && _state.value.conflict == null) {
            val text = _body.value.text
            val base = version
            appScope.launch {
                apiCall { api.updateSection(projectId, sectionId, buildJsonObject { put("body", JsonPrimitive(text)); put("baseVersion", JsonPrimitive(base)) }) }
            }
        }
    }
}
