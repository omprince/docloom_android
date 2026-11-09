package com.docloom_android.feature.errors

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.docloom_android.BuildConfig
import com.docloom_android.core.model.ErrorEvent
import com.docloom_android.core.model.ResolveErrorRequest
import com.docloom_android.core.network.ApiResult
import com.docloom_android.core.network.apiCall
import com.docloom_android.core.ui.CopyableCode
import com.docloom_android.core.ui.ErrorBanner
import com.docloom_android.core.ui.GlassCard
import com.docloom_android.core.ui.LoadingBox
import com.docloom_android.core.ui.MessageBox
import com.docloom_android.core.ui.MutedText
import com.docloom_android.core.ui.SectionCard
import com.docloom_android.core.ui.timeAgo
import com.docloom_android.feature.docs.DocsApi
import com.docloom_android.feature.tasks.Pill
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ErrorsState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val errors: List<ErrorEvent> = emptyList(),
    val showResolved: Boolean = false,
    val key: String? = null,
    /** An error id, or "key". */
    val busy: String? = null,
    val message: String? = null,
)

/** Port of the website's ErrorsPanel. */
class ErrorsViewModel(
    private val api: DocsApi,
    private val projectId: String,
    private val onChanged: () -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(ErrorsState())
    val state: StateFlow<ErrorsState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loadError = null) }
            when (val r = apiCall { api.errors(projectId, _state.value.showResolved) }) {
                is ApiResult.Ok -> _state.update { it.copy(loading = false, errors = r.value.errors) }
                is ApiResult.Err -> _state.update { it.copy(loading = false, loadError = r.message) }
            }
        }
    }

    fun setShowResolved(show: Boolean) {
        _state.update { it.copy(showResolved = show) }
        load()
    }

    fun setResolved(id: String, resolved: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(busy = id, message = null) }
            when (val r = apiCall { api.setResolved(projectId, id, ResolveErrorRequest(resolved)) }) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(busy = null) }
                    load()
                    onChanged()
                }
                is ApiResult.Err -> _state.update { it.copy(busy = null, message = r.message) }
            }
        }
    }

    fun loadKey(rotate: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(busy = "key", message = null) }
            val r = apiCall { if (rotate) api.rotateIngestKey(projectId) else api.ingestKey(projectId) }
            when (r) {
                is ApiResult.Ok -> _state.update { it.copy(busy = null, key = r.value.key) }
                is ApiResult.Err -> _state.update { it.copy(busy = null, message = r.message) }
            }
        }
    }
}

@Composable
fun ErrorsScreen(vm: ErrorsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf<String?>(null) }
    var confirmRotate by remember { mutableStateOf(false) }

    when {
        state.loading -> LoadingBox()
        state.loadError != null -> MessageBox("Couldn't load errors", state.loadError, "Try again", { vm.load() })
        else -> LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { MutedText("Errors your running apps report, each placed on the part of the architecture where it happened.") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !state.showResolved, onClick = { vm.setShowResolved(false) }, label = { Text("Open") })
                    FilterChip(selected = state.showResolved, onClick = { vm.setShowResolved(true) }, label = { Text("Include resolved") })
                }
            }
            state.message?.let { item { ErrorBanner(it) } }
            if (state.errors.isEmpty()) {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        MutedText(
                            "No ${if (state.showResolved) "" else "open "}errors. Send one with the snippet below to see it mapped.",
                            Modifier.padding(24.dp),
                        )
                    }
                }
            }
            items(state.errors, key = { it.id }) { e ->
                ErrorCard(
                    e,
                    expanded = expanded == e.id,
                    busy = state.busy == e.id,
                    onToggleStack = { expanded = if (expanded == e.id) null else e.id },
                    onResolve = { vm.setResolved(e.id, !e.resolved) },
                )
            }
            item { SendErrorsCard(state, onShowKey = { vm.loadKey(false) }, onRotate = { confirmRotate = true }) }
        }
    }

    if (confirmRotate) {
        AlertDialog(
            onDismissRequest = { confirmRotate = false },
            title = { Text("Rotate the key?", fontWeight = FontWeight.SemiBold) },
            text = { Text("Apps using the old key will stop reporting until they're updated.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmRotate = false; vm.loadKey(true) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Rotate") }
            },
            dismissButton = { TextButton(onClick = { confirmRotate = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ErrorCard(e: ErrorEvent, expanded: Boolean, busy: Boolean, onToggleStack: () -> Unit, onResolve: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().alpha(if (e.resolved) 0.6f else 1f)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                e.status?.let {
                    Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)) {
                        Text("$it", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
                if (e.method != null && e.path != null) {
                    Text("${e.method} ${e.path}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                e.service?.let { MutedText("· $it") }
                MutedText(timeAgo(e.occurredAt))
            }
            Text(e.message, fontWeight = FontWeight.Medium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (e.nodeId != null) Pill("At: ${e.nodeLabel ?: "a node"}", accent = true) else MutedText("Not matched to a node yet")
            }
            Row {
                if (e.stack != null) {
                    TextButton(onClick = onToggleStack, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "Hide stack" else "Stack trace") }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                TextButton(onClick = onResolve, enabled = !busy) { Text(if (e.resolved) "Reopen" else "Mark resolved") }
            }
            if (expanded && e.stack != null) {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.background) {
                    Text(
                        e.stack,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        softWrap = false,
                        modifier = Modifier
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SendErrorsCard(state: ErrorsState, onShowKey: () -> Unit, onRotate: () -> Unit) {
    val key = state.key
    val curl = """curl -X POST ${BuildConfig.WEBSITE_URL}/api/ingest/errors \
  -H "Content-Type: application/json" \
  -H "X-DocLoom-Key: ${key ?: "<project key>"}" \
  -d '{"message":"Order lookup failed","method":"GET","path":"/api/orders/42","status":500}'"""
    SectionCard("Send errors from your app") {
        MutedText(
            "POST errors with this project's key. DocLoom matches the request's method and path to a scanned endpoint, " +
                "or a table named in the message, and marks that node on the map.",
        )
        if (key == null) {
            OutlinedButton(onClick = onShowKey, enabled = state.busy != "key", shape = RoundedCornerShape(50)) { Text("Show project key") }
        } else {
            CopyableCode("Project key", key, sensitive = true)
        }
        CopyableCode("Example", curl, sensitive = key != null)
        if (key != null) {
            TextButton(onClick = onRotate, enabled = state.busy != "key") { Text("Rotate key") }
        }
    }
}
