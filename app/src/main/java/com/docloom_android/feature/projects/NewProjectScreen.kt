package com.docloom_android.feature.projects

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.docloom_android.core.model.CreateProjectRequest
import com.docloom_android.core.model.GithubRepo
import com.docloom_android.core.model.GithubStatus
import com.docloom_android.core.model.Organization
import com.docloom_android.core.model.ScanRepoRequest
import com.docloom_android.core.model.ScanSqlRequest
import com.docloom_android.core.network.ApiResult
import com.docloom_android.core.network.apiCall
import com.docloom_android.core.ui.ErrorBanner
import com.docloom_android.core.ui.MutedText
import com.docloom_android.core.ui.PrimaryButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class StartKind(val title: String, val body: String) {
    Blank("Blank map", "Draw the architecture yourself on the canvas."),
    Repo("From a GitHub repo", "Scan SQL migrations and routes into an ER diagram and API map."),
    Sql("From a SQL schema", "Paste CREATE TABLE statements to get an ER diagram."),
    Ai("From a document, with your AI", "Connect Claude (or any MCP client) and let it build the map from your doc."),
}

const val SAMPLE_SQL = """CREATE TABLE users (
  id UUID PRIMARY KEY,
  email TEXT NOT NULL UNIQUE
);

CREATE TABLE orders (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users (id),
  total_cents INT NOT NULL
);"""

data class NewProjectState(
    val github: GithubStatus = GithubStatus(),
    val orgs: List<Organization> = emptyList(),
    /** null until loaded (only loaded for the GitHub start when connected). */
    val repos: List<GithubRepo>? = null,
    val reposError: String? = null,
    val reposRequested: Boolean = false,
    val busy: String? = null,
    val error: String? = null,
    /** Set once the project exists, so a retry after a failed import reuses it. */
    val createdId: String? = null,
    /** Set when everything finished: open this project. */
    val openProjectId: String? = null,
)

/** Same flow as the website's NewProjectWizard. */
class NewProjectViewModel(private val api: ProjectsApi) : ViewModel() {
    private val _state = MutableStateFlow(NewProjectState())
    val state: StateFlow<NewProjectState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val github = apiCall { api.githubStatus() }
            val orgs = apiCall { api.organizations() }
            _state.update {
                it.copy(
                    github = (github as? ApiResult.Ok)?.value ?: GithubStatus(),
                    orgs = (orgs as? ApiResult.Ok)?.value?.organizations.orEmpty(),
                )
            }
        }
    }

    fun loadReposIfNeeded() {
        val s = _state.value
        if (!s.github.connected || s.reposRequested) return
        _state.update { it.copy(reposRequested = true) }
        viewModelScope.launch {
            when (val r = apiCall { api.githubRepos() }) {
                is ApiResult.Ok -> _state.update { it.copy(repos = r.value.repos, reposError = null) }
                is ApiResult.Err -> _state.update { it.copy(repos = emptyList(), reposError = r.message) }
            }
        }
    }

    fun create(name: String, orgId: String?, start: StartKind, repoUrl: String, sql: String) {
        val problem = when {
            name.isBlank() -> "Give the project a name."
            start == StartKind.Repo && repoUrl.isBlank() -> "Pick or paste a GitHub repo."
            start == StartKind.Sql && sql.isBlank() -> "Paste a SQL schema first."
            else -> null
        }
        if (problem != null) {
            _state.update { it.copy(error = problem) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(error = null) }
            var id = _state.value.createdId
            if (id == null) {
                _state.update { it.copy(busy = "Creating project…") }
                val request = CreateProjectRequest(
                    name = name.trim(),
                    repoUrl = if (start == StartKind.Repo) repoUrl.trim() else null,
                    orgId = orgId,
                )
                when (val r = apiCall { api.create(request) }) {
                    is ApiResult.Ok -> {
                        id = r.value.project.id
                        _state.update { it.copy(createdId = id) }
                    }
                    is ApiResult.Err -> {
                        _state.update { it.copy(busy = null, error = r.message) }
                        return@launch
                    }
                }
            }
            val projectId = id!!
            val scan: ApiResult<*>? = when (start) {
                StartKind.Repo -> {
                    _state.update { it.copy(busy = "Scanning the repo…") }
                    apiCall { api.scanRepo(projectId, ScanRepoRequest(null)) }
                }
                StartKind.Sql -> {
                    _state.update { it.copy(busy = "Building the ER diagram…") }
                    apiCall { api.scanSql(projectId, ScanSqlRequest(sql)) }
                }
                else -> null
            }
            if (scan is ApiResult.Err) {
                _state.update { it.copy(busy = null, error = scan.message) }
            } else {
                _state.update { it.copy(busy = null, openProjectId = projectId) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewProjectScreen(
    vm: NewProjectViewModel,
    onBack: () -> Unit,
    onOpenProject: (String) -> Unit,
    /** Start inside this organisation ("New project here"). */
    initialOrgId: String? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var orgId by remember { mutableStateOf(initialOrgId) }
    var start by remember { mutableStateOf(StartKind.Blank) }
    var repoUrl by remember { mutableStateOf("") }
    var sql by remember { mutableStateOf("") }
    val locked = state.createdId != null
    val busy = state.busy != null

    // Also covers GitHub status arriving after the repo option was picked.
    LaunchedEffect(start, state.github.connected) {
        if (start == StartKind.Repo) vm.loadReposIfNeeded()
    }

    state.openProjectId?.let { id ->
        LaunchedEffect(id) { onOpenProject(id) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New project", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MutedText("Pick how you want to start. You can mix all of them later; they all edit the same map.")

            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 100) name = it },
                label = { Text("Project name") },
                placeholder = { Text("Checkout service") },
                singleLine = true,
                enabled = !locked,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.orgs.isNotEmpty()) OrgPicker(state.orgs, orgId, enabled = !locked) { orgId = it }

            Text("How do you want to start?", style = MaterialTheme.typography.labelLarge)
            StartKind.entries.forEach { kind ->
                StartOption(kind, selected = start == kind, enabled = !locked) { start = kind }
            }

            when (start) {
                StartKind.Repo -> RepoPicker(state, repoUrl, enabled = !locked) { repoUrl = it }
                StartKind.Sql -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = sql,
                        onValueChange = { sql = it },
                        placeholder = { Text("CREATE TABLE users (…);", fontFamily = FontFamily.Monospace) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        minLines = 8,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { sql = SAMPLE_SQL }) { Text("Use an example") }
                }
                StartKind.Ai -> MutedText(
                    "After the project is created you'll see the MCP address and a one-line setup for Claude Code " +
                        "or Claude Desktop on its overview page.",
                )
                StartKind.Blank -> Unit
            }

            state.error?.let { ErrorBanner(it + if (locked) " The project was created; you can open it anyway." else "") }
            if (locked && state.error != null) {
                TextButton(onClick = { onOpenProject(state.createdId!!) }) { Text("Open the project anyway") }
            }

            PrimaryButton(
                text = if (locked) "Try again" else "Create project",
                onClick = { vm.create(name, orgId, start, repoUrl, sql) },
                busy = busy,
                busyText = state.busy ?: "",
                enabled = name.isNotBlank(),
            )
        }
    }
}

@Composable
private fun StartOption(kind: StartKind, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) accent.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, if (selected) accent else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(kind.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            MutedText(kind.body)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrgPicker(orgs: List<Organization>, selected: String?, enabled: Boolean, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = orgs.firstOrNull { it.id == selected }?.let { "${it.name}: everyone in the organisation" }
        ?: "Just me (share it later)"
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (enabled) open = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Where should it live?") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Just me (share it later)") }, onClick = { onSelect(null); open = false })
            orgs.forEach { org ->
                DropdownMenuItem(text = { Text(org.name) }, onClick = { onSelect(org.id); open = false })
            }
        }
    }
}

@Composable
private fun RepoPicker(state: NewProjectState, repoUrl: String, enabled: Boolean, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            !state.github.connected -> MutedText(
                "Public repos can be scanned without connecting. To pick from your repos, including private ones, " +
                    "connect GitHub in Settings → Integrations on the website.",
            )
            state.reposError != null -> ErrorBanner("Couldn't load your repos: ${state.reposError}")
            state.repos == null -> MutedText("Loading your repos…")
            state.repos.isEmpty() -> MutedText("No repos found on your GitHub account.")
            else -> Surface(
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    state.repos.forEach { repo ->
                        val picked = repo.htmlUrl == repoUrl
                        Surface(
                            onClick = { onChange(repo.htmlUrl) },
                            enabled = enabled,
                            color = if (picked) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                                Text(
                                    repo.fullName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (picked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                if (repo.private) MutedText("Private")
                            }
                        }
                    }
                }
            }
        }
        OutlinedTextField(
            value = repoUrl,
            onValueChange = onChange,
            placeholder = { Text("https://github.com/owner/repo") },
            singleLine = true,
            enabled = enabled,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
