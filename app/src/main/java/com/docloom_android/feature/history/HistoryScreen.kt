package com.docloom_android.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.docloom_android.core.model.Revision
import com.docloom_android.core.model.RevisionChange
import com.docloom_android.core.network.ApiResult
import com.docloom_android.core.network.apiCall
import com.docloom_android.core.ui.ActorChip
import com.docloom_android.core.ui.ErrorBanner
import com.docloom_android.core.ui.GlassCard
import com.docloom_android.core.ui.LoadingBox
import com.docloom_android.core.ui.MessageBox
import com.docloom_android.core.ui.MutedText
import com.docloom_android.core.ui.summarize
import com.docloom_android.core.ui.timeAgo
import com.docloom_android.feature.docs.DocsApi
import com.docloom_android.ui.theme.ErrorRed
import com.docloom_android.ui.theme.SuccessGreen
import com.docloom_android.ui.theme.WarningAmber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val PAGE = 30

data class HistoryState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val version: Long = 0,
    val revisions: List<Revision> = emptyList(),
    val hasMore: Boolean = false,
    /** "more" or "revert-<version>". */
    val busy: String? = null,
    val error: String? = null,
)

/** Port of the website's HistoryFeed. */
class HistoryViewModel(
    private val api: DocsApi,
    private val projectId: String,
    private val onChanged: () -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loadError = null) }
            when (val r = apiCall { api.revisions(projectId, PAGE) }) {
                is ApiResult.Ok -> _state.update {
                    it.copy(loading = false, version = r.value.version, revisions = r.value.revisions, hasMore = r.value.revisions.size == PAGE)
                }
                is ApiResult.Err -> _state.update { it.copy(loading = false, loadError = r.message) }
            }
        }
    }

    fun loadOlder() {
        val oldest = _state.value.revisions.lastOrNull()?.version ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = "more", error = null) }
            when (val r = apiCall { api.revisions(projectId, PAGE, before = oldest) }) {
                is ApiResult.Ok -> _state.update {
                    it.copy(busy = null, revisions = it.revisions + r.value.revisions, hasMore = r.value.revisions.size == PAGE)
                }
                is ApiResult.Err -> _state.update { it.copy(busy = null, error = r.message) }
            }
        }
    }

    /** Adds a new version that reverses [version]. */
    fun revert(version: Long) {
        viewModelScope.launch {
            _state.update { it.copy(busy = "revert-$version", error = null) }
            when (val r = apiCall { api.revert(projectId, version) }) {
                is ApiResult.Ok -> {
                    _state.update { s ->
                        s.copy(
                            busy = null,
                            version = r.value.version.takeIf { v -> v > 0 } ?: s.version,
                            revisions = listOfNotNull(r.value.revision) + s.revisions,
                        )
                    }
                    onChanged()
                }
                is ApiResult.Err -> _state.update { it.copy(busy = null, error = r.message) }
            }
        }
    }
}

/** Website lib/revisions.ts entityLabel. */
fun entityLabel(change: RevisionChange): String {
    val value = change.after ?: change.before
    val data = value?.get("data") as? JsonObject
    fun str(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
    val label = str(data, "label") ?: str(data, "title")
    if (change.entity == "edge") return if (label != null) "connection “$label”" else "connection"
    if (label != null) return label
    return if (str(value, "type") == "section") "untitled section" else "untitled node"
}

@Composable
fun HistoryScreen(vm: HistoryViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<Long?>(null) }
    var confirm by remember { mutableStateOf<Long?>(null) }

    when {
        state.loading -> LoadingBox()
        state.loadError != null -> MessageBox("Couldn't load the history", state.loadError, "Try again", { vm.load() })
        else -> LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                MutedText(
                    "Every change to the map, by you, a repo scan or your AI, with the reason given. Current version: ${state.version}.",
                )
            }
            state.error?.let { item { ErrorBanner(it) } }
            if (state.revisions.isEmpty()) {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        MutedText("No changes yet. Edit the map, scan a repo or connect your AI to start the history.", Modifier.padding(24.dp))
                    }
                }
            }
            items(state.revisions, key = { it.id }) { r ->
                RevisionCard(
                    r,
                    expanded = open == r.version,
                    reverting = state.busy == "revert-${r.version}",
                    busy = state.busy != null,
                    onToggle = { open = if (open == r.version) null else r.version },
                    onRevert = { confirm = r.version },
                )
            }
            if (state.hasMore) {
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        OutlinedButton(onClick = { vm.loadOlder() }, enabled = state.busy == null, shape = RoundedCornerShape(50)) {
                            Text(if (state.busy == "more") "Loading…" else "Load older changes")
                        }
                    }
                }
            }
        }
    }

    confirm?.let { version ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Undo version $version?", fontWeight = FontWeight.SemiBold) },
            text = { Text("This adds a new version that reverses it.") },
            confirmButton = { TextButton(onClick = { confirm = null; vm.revert(version) }) { Text("Undo") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RevisionCard(
    r: Revision,
    expanded: Boolean,
    reverting: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onRevert: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("v${r.version}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ActorChip(r.actorType)
                Text(r.actorLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                MutedText(timeAgo(r.createdAt))
            }
            Text(r.reason ?: "Edited on the canvas", fontWeight = FontWeight.Medium)
            MutedText(summarize(r))
            if (r.changes.isNotEmpty()) {
                Row {
                    TextButton(onClick = onToggle, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "Hide details" else "Show details") }
                    Box(Modifier.weight(1f))
                    TextButton(onClick = onRevert, enabled = !busy) { Text(if (reverting) "Undoing…" else "Undo this change") }
                }
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                r.changes.take(200).forEach { c ->
                    val (kind, color) = when {
                        c.before == null -> "added" to SuccessGreen
                        c.after == null -> "removed" to ErrorRed
                        else -> "changed" to WarningAmber
                    }
                    Row {
                        Text(kind, color = color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(72.dp))
                        Text(entityLabel(c), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (r.changes.size > 200) MutedText("…and ${r.changes.size - 200} more")
            }
        }
    }
}
