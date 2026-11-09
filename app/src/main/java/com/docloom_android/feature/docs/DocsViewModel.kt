package com.docloom_android.feature.docs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.docloom_android.core.model.CreateChapterRequest
import com.docloom_android.core.model.CreateSectionRequest
import com.docloom_android.core.model.DocChapter
import com.docloom_android.core.model.DocPerson
import com.docloom_android.core.model.DocRefs
import com.docloom_android.core.model.DocsGithub
import com.docloom_android.core.network.ApiResult
import com.docloom_android.core.network.apiCall
import com.docloom_android.core.ui.Notice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import retrofit2.HttpException

data class DocsState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val chapters: List<DocChapter> = emptyList(),
    val github: DocsGithub = DocsGithub(),
    /** null until loaded; what `{{kind:value}}` chips point at. */
    val refs: DocRefs? = null,
    /** null until first needed (the @ picker). */
    val people: List<DocPerson>? = null,
    /** The open section, or null for the table of contents. */
    val openSectionId: String? = null,
    /** "import", "generate", "blank", "chapter", "section" while running. */
    val busy: String? = null,
    val notice: Notice? = null,
)

/** The Docs tab: table of contents, shared refs and people. Port of DocsWorkspace. */
class DocsViewModel(
    private val api: DocsApi,
    private val projectId: String,
    private val onChanged: () -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(DocsState())
    val state: StateFlow<DocsState> = _state.asStateFlow()

    init {
        refreshToc()
        refreshRefs()
    }

    fun refreshToc() {
        viewModelScope.launch {
            when (val r = apiCall { api.toc(projectId) }) {
                is ApiResult.Ok -> _state.update { it.copy(loading = false, loadError = null, chapters = r.value.chapters, github = r.value.github) }
                is ApiResult.Err -> _state.update { it.copy(loading = false, loadError = r.message) }
            }
        }
    }

    fun refreshRefs() {
        viewModelScope.launch {
            val r = apiCall { api.refs(projectId) }
            if (r is ApiResult.Ok) _state.update { it.copy(refs = r.value) }
        }
    }

    fun loadPeopleIfNeeded() {
        if (_state.value.people != null) return
        _state.update { it.copy(people = emptyList()) }
        viewModelScope.launch {
            val r = apiCall { api.people(projectId) }
            _state.update { it.copy(people = (r as? ApiResult.Ok)?.value?.people.orEmpty()) }
            (r as? ApiResult.Ok)?.value?.githubError?.let { e ->
                _state.update { it.copy(notice = Notice(false, "Couldn't load GitHub collaborators: $e")) }
            }
        }
    }

    fun openSection(id: String?) = _state.update { it.copy(openSectionId = id) }
    fun dismissNotice() = _state.update { it.copy(notice = null) }

    private fun run(label: String, action: suspend () -> String?) {
        viewModelScope.launch {
            _state.update { it.copy(busy = label, notice = null) }
            val notice = try {
                action()?.let { Notice(true, it) }
            } catch (e: ActionFailed) {
                Notice(false, e.message ?: "Something went wrong.")
            }
            _state.update { it.copy(busy = null, notice = notice) }
        }
    }

    private class ActionFailed(message: String) : Exception(message)

    private suspend fun <T> must(block: suspend () -> T): T = when (val r = apiCall { block() }) {
        is ApiResult.Ok -> r.value
        is ApiResult.Err -> throw ActionFailed(r.message)
    }

    fun importGithub() = run("import") {
        val data = must { api.importGithub(projectId) }
        refreshToc()
        if (data.organization != null) "Mirrored ${data.chapters.size} repos of ${data.organization} as chapters."
        else "Chapter “${data.chapters.firstOrNull()?.title}” now mirrors ${data.chapters.firstOrNull()?.githubRepo ?: "the repo"}."
    }

    fun generate() = run("generate") {
        val data = must { api.generate(projectId) }
        refreshToc()
        refreshRefs()
        onChanged()
        data.sections.firstOrNull()?.let { openSection(it.id) }
        if (data.created > 0) "Wrote ${data.created} section${if (data.created == 1) "" else "s"} from the map, one per component."
        else "Every component on the map already has a section."
    }

    fun startBlank() = run("blank") {
        val chapter = must { api.createChapter(projectId, CreateChapterRequest("Overview", "What this system is and how it fits together.", null)) }.chapter
        val section = must { api.createSection(projectId, CreateSectionRequest(chapter.id, "Introduction", "")) }.section
        refreshToc()
        onChanged()
        openSection(section.id)
        null
    }

    /** [fromRepo]: [value] is `owner/name` or a GitHub URL the chapter mirrors. */
    fun createChapter(value: String, fromRepo: Boolean) = run("chapter") {
        val body = if (fromRepo) CreateChapterRequest(null, null, value.trim()) else CreateChapterRequest(value.trim(), null, null)
        val chapter = must { api.createChapter(projectId, body) }.chapter
        refreshToc()
        if (fromRepo) "Chapter “${chapter.title}” mirrors ${chapter.githubRepo}." else null
    }

    fun addSection(chapterId: String, title: String) = run("section") {
        val section = must { api.createSection(projectId, CreateSectionRequest(chapterId, title.trim(), "")) }.section
        refreshToc()
        refreshRefs()
        onChanged()
        openSection(section.id)
        null
    }

    fun renameChapter(id: String, title: String) = run("chapter") {
        must { api.updateChapter(projectId, id, buildJsonObject { put("title", JsonPrimitive(title.trim())) }) }
        refreshToc()
        null
    }

    fun deleteChapter(id: String) = run("chapter") {
        must {
            val response = api.deleteChapter(projectId, id)
            if (!response.isSuccessful) throw HttpException(response)
        }
        val removed = _state.value.chapters.firstOrNull { it.id == id }
        if (removed?.sections?.any { it.id == _state.value.openSectionId } == true) openSection(null)
        refreshToc()
        refreshRefs()
        onChanged()
        null
    }

    /** After the editor saved, created or deleted something. */
    fun onSectionChanged(tocChanged: Boolean, refsChanged: Boolean) {
        if (tocChanged) {
            refreshToc()
            onChanged()
        }
        if (refsChanged) refreshRefs()
    }
}
