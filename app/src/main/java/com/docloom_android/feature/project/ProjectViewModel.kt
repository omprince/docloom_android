package com.docloom_android.feature.project

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.docloom_android.core.model.GithubStatus
import com.docloom_android.core.model.Project
import com.docloom_android.core.model.Revision
import com.docloom_android.core.model.ScanRepoRequest
import com.docloom_android.core.model.ScanSqlRequest
import com.docloom_android.core.model.UpdateProjectRequest
import com.docloom_android.core.network.ApiResult
import com.docloom_android.core.network.apiCall
import com.docloom_android.core.ui.Notice
import com.docloom_android.feature.projects.ProjectsApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class ProjectState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val project: Project? = null,
    val recent: List<Revision> = emptyList(),
    val github: GithubStatus = GithubStatus(),
    /** "save", "scan", "sql" or "delete" while that action runs. */
    val busy: String? = null,
    val notice: Notice? = null,
    val deleted: Boolean = false,
)

/**
 * One project and the actions on its overview page. [onChanged] lets the
 * projects list refresh after a rename, scan or delete.
 */
class ProjectViewModel(
    private val api: ProjectsApi,
    val projectId: String,
    private val onChanged: () -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(ProjectState())
    val state: StateFlow<ProjectState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.project == null, loadError = null) }
            val project = async { apiCall { api.get(projectId) } }
            val revisions = async { apiCall { api.revisions(projectId, limit = 5) } }
            val github = async { apiCall { api.githubStatus() } }
            when (val p = project.await()) {
                is ApiResult.Err -> _state.update { it.copy(loading = false, loadError = p.message) }
                is ApiResult.Ok -> _state.update {
                    it.copy(
                        loading = false,
                        project = p.value.project,
                        recent = (revisions.await() as? ApiResult.Ok)?.value?.revisions.orEmpty(),
                        github = (github.await() as? ApiResult.Ok)?.value ?: GithubStatus(),
                    )
                }
            }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun saveDetails(name: String, repoUrl: String) = run("save") {
        val body = UpdateProjectRequest(name.trim(), repoUrl.trim().ifEmpty { null })
        when (val r = apiCall { api.update(projectId, body) }) {
            is ApiResult.Ok -> {
                _state.update { it.copy(project = r.value.project) }
                Notice(true, "Project details saved.")
            }
            is ApiResult.Err -> Notice(false, r.message)
        }
    }

    fun scanRepo() = run("scan") {
        when (val r = apiCall { api.scanRepo(projectId, ScanRepoRequest(null)) }) {
            is ApiResult.Ok -> {
                val v = r.value
                if (v.revision == null || v.revision is kotlinx.serialization.json.JsonNull) {
                    Notice(true, "Scan finished. The map was already up to date.")
                } else {
                    Notice(true, "Scan finished: ${v.stats.tables} tables and ${v.stats.endpoints} endpoints mapped.")
                }
            }
            is ApiResult.Err -> Notice(false, r.message)
        }
    }

    fun importSql(sql: String) = run("sql") {
        when (val r = apiCall { api.scanSql(projectId, ScanSqlRequest(sql)) }) {
            is ApiResult.Ok -> Notice(true, "Imported ${r.value.stats.tables} tables into the ER diagram.")
            is ApiResult.Err -> Notice(false, r.message)
        }
    }

    fun delete() {
        viewModelScope.launch {
            _state.update { it.copy(busy = "delete", notice = null) }
            val r = apiCall {
                val response = api.delete(projectId)
                if (!response.isSuccessful) throw HttpException(response)
            }
            when (r) {
                is ApiResult.Ok -> {
                    onChanged()
                    _state.update { it.copy(busy = null, deleted = true) }
                }
                is ApiResult.Err -> _state.update { it.copy(busy = null, notice = Notice(false, r.message)) }
            }
        }
    }

    /** Runs an action, shows its notice, then reloads the project so the counts update. */
    private fun run(label: String, action: suspend () -> Notice) {
        viewModelScope.launch {
            _state.update { it.copy(busy = label, notice = null) }
            val notice = action()
            _state.update { it.copy(busy = null, notice = notice) }
            if (notice.ok) {
                load()
                onChanged()
            }
        }
    }
}
