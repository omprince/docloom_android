package com.docloom_android.feature.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.docloom_android.core.ui.ActorChip
import com.docloom_android.core.ui.ErrorBanner
import com.docloom_android.core.ui.LoadingBox
import com.docloom_android.core.ui.MessageBox
import com.docloom_android.core.ui.MutedText
import com.docloom_android.ui.theme.ErrorRed
import com.docloom_android.ui.theme.SuccessGreen
import com.docloom_android.ui.theme.WarningAmber

/** The Map tab: the architecture map, live, with phone-sized editing. */
@Composable
fun MapScreen(vm: MapViewModel, onOpenTask: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val viewport = remember { MapViewport() }
    var fitted by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }

    // Live updates only while the map is on screen; unsaved edits go out when it isn't.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                vm.runLive()
            } finally {
                vm.saveNow()
            }
        }
    }

    val graph = state.graph
    when {
        graph == null && state.loadError != null -> {
            MessageBox("Couldn't load the map", state.loadError, "Try again", { vm.retryLoad() })
            return
        }
        graph == null -> {
            LoadingBox()
            return
        }
    }
    graph!!

    // First look: everything on screen. A focus request centres one node.
    LaunchedEffect(graph, viewport.size) {
        if (!fitted && viewport.size.width > 0) {
            viewport.fit(graph.bounds)
            fitted = true
        }
    }
    LaunchedEffect(state.focusId, viewport.size, fitted) {
        val id = state.focusId ?: return@LaunchedEffect
        if (!fitted) return@LaunchedEffect
        graph.boxes[id]?.let { viewport.centerOn(it) }
        vm.focusHandled()
    }

    Box(Modifier.fillMaxSize()) {
        MapCanvas(
            graph = graph,
            viewport = viewport,
            selectedId = state.selectedId,
            highlighted = state.activity?.nodeIds.orEmpty(),
            editable = !vm.readOnly,
            onTap = vm::select,
            onDrag = vm::dragBy,
            onDragEnd = vm::dragEnd,
            modifier = Modifier.fillMaxSize(),
        )

        // Status and view controls.
        Column(Modifier.align(Alignment.TopStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(state, vm.readOnly)
            state.error?.let { msg ->
                Box(Modifier.fillMaxWidth(0.9f)) {
                    ErrorBanner(msg)
                    IconButton(onClick = vm::dismissError, modifier = Modifier.align(Alignment.TopEnd)) { Icon(Icons.Filled.Close, "Dismiss") }
                }
            }
        }
        Column(Modifier.align(Alignment.TopEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallFloatingActionButton(onClick = { viewport.zoomBy(1.25f) }) { Icon(Icons.Outlined.ZoomIn, "Zoom in") }
            SmallFloatingActionButton(onClick = { viewport.zoomBy(0.8f) }) { Icon(Icons.Filled.Remove, "Zoom out") }
            SmallFloatingActionButton(onClick = { viewport.fit(graph.bounds) }) { Icon(Icons.Filled.FitScreen, "Fit to screen") }
            if (!vm.readOnly) SmallFloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Add a box") }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.activity?.let { a ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 4.dp) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActorChip(a.actorType)
                        Text(
                            a.actorLabel + (a.reason?.let { ": $it" } ?: " changed the map"),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            val selected = state.selectedId?.let { graph.byId[it] }
            if (selected != null) {
                NodePanel(
                    node = selected,
                    graph = graph,
                    readOnly = vm.readOnly,
                    onClose = { vm.select(null) },
                    onEdit = { editing = selected.id },
                    onConnect = { connecting = selected.id },
                    onDelete = { deleting = selected.id },
                    onDeleteEdge = vm::deleteEdge,
                    onSelect = vm::focus,
                    onOpenTask = onOpenTask,
                )
            } else if (graph.nodes.isEmpty()) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    MutedText(
                        if (vm.readOnly) "This map is empty." else "The map is empty. Tap + to add a component, scan the repo from Overview, or let your AI build it.",
                        Modifier.padding(14.dp),
                    )
                }
            }
        }
    }

    if (adding) {
        AddNodeSheet(onDismiss = { adding = false }) { kind, label ->
            val at = viewport.center()
            if (kind == null) vm.addNote(label, at) else vm.addComponent(kind, label, at)
            adding = false
        }
    }
    editing?.let { id ->
        graph.byId[id]?.let { node ->
            EditNodeDialog(node, onDismiss = { editing = null }) { label, description, kind ->
                if (label != node.label) vm.rename(id, label)
                if (node.type == "architecture" && description != (node.description ?: "")) vm.setDescription(id, description)
                if (kind != null && kind != node.kind) vm.setKind(id, kind)
                editing = null
            }
        }
    }
    connecting?.let { id ->
        ConnectSheet(source = id, graph = graph, onDismiss = { connecting = null }) { target, label ->
            vm.connect(id, target, label)
            connecting = null
        }
    }
    deleting?.let { id ->
        val reason = vm.whyNotDeletable(id)
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(if (reason == null) "Delete “${graph.byId[id]?.label}”?" else "Can't delete this", fontWeight = FontWeight.SemiBold) },
            text = { Text(reason ?: "It and its connections are removed from the map. You can undo it from History.") },
            confirmButton = {
                if (reason == null) {
                    TextButton(
                        onClick = { vm.deleteNode(id); deleting = null },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Delete") }
                } else {
                    TextButton(onClick = { deleting = null }) { Text("OK") }
                }
            },
            dismissButton = { if (reason == null) TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatusPill(state: MapState, readOnly: Boolean) {
    val color = when (state.status) {
        SyncStatus.Saved -> SuccessGreen
        SyncStatus.Offline, SyncStatus.Error -> ErrorRed
        else -> WarningAmber
    }
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 2.dp) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Text(
                "  " + (if (readOnly) "View only" else state.status.text) + if (state.live) " · Live" else "",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun NodePanel(
    node: MapNode,
    graph: MapGraph,
    readOnly: Boolean,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onConnect: () -> Unit,
    onDelete: () -> Unit,
    onDeleteEdge: (String) -> Unit,
    onSelect: (String) -> Unit,
    onOpenTask: (String) -> Unit,
) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(node.color, CircleShape))
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(node.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    MutedText(
                        when (node.type) {
                            "architecture" -> node.kind?.label ?: "Component"
                            "erTable" -> "Database table · ${node.columns.size} columns"
                            "section" -> "Group"
                            "task" -> "Task · ${node.subtitle}"
                            "taskNote" -> "Checklist"
                            else -> "Note"
                        },
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") }
            }
            node.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (node.type == "erTable" && node.columns.isNotEmpty()) {
                node.columns.take(12).forEach { c ->
                    MutedText((if (c.pk) "🔑 " else "• ") + "${c.name}  ${c.type}" + (c.fk?.let { "  → $it" } ?: ""))
                }
                if (node.columns.size > 12) MutedText("…and ${node.columns.size - 12} more")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (node.type == "task" && node.taskId != null) {
                    OutlinedButton(onClick = { onOpenTask(node.taskId) }, shape = RoundedCornerShape(50)) { Text("Open task") }
                }
                if (!readOnly && node.type != "task") {
                    OutlinedButton(onClick = onEdit, shape = RoundedCornerShape(50)) { Text("Edit") }
                    OutlinedButton(onClick = onConnect, shape = RoundedCornerShape(50)) { Text("Connect") }
                    TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
                }
            }
            if (!readOnly && node.type != "task") MutedText("Hold and drag the selected box to move it.")

            val connections = graph.connectionsOf(node.id)
            if (connections.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Connections", style = MaterialTheme.typography.labelLarge)
                connections.forEach { e ->
                    val outgoing = e.source == node.id
                    val other = graph.byId[if (outgoing) e.target else e.source]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { other?.let { onSelect(it.id) } }, modifier = Modifier.weight(1f)) {
                            Text(
                                (if (outgoing) "→ " else "← ") + (other?.label ?: "?") + (e.label?.let { "  ($it)" } ?: ""),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (!readOnly && !e.managed) {
                            IconButton(onClick = { onDeleteEdge(e.id) }) { Icon(Icons.Filled.Close, "Remove connection") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddNodeSheet(onDismiss: () -> Unit, onAdd: (NodeKind?, String) -> Unit) {
    var label by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<NodeKind?>(null) }
    var note by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Add to the map", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = label,
                onValueChange = { if (it.length <= 100) label = it },
                label = { Text("Name") },
                placeholder = { Text(picked?.label ?: if (note) "Note" else "Checkout service") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !note, onClick = { note = false }, label = { Text("Component") })
                FilterChip(selected = note, onClick = { note = true; picked = null }, label = { Text("Note") })
            }
            if (!note) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    NODE_KINDS.groupBy { it.category }.forEach { (category, kinds) ->
                        item(key = category) {
                            Text(category.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                        }
                        kinds.forEach { kind ->
                            item(key = kind.key) {
                                Surface(
                                    onClick = { picked = kind },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (picked == kind) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(10.dp).background(kind.color, CircleShape))
                                        Text("  ${kind.label}", modifier = Modifier.weight(1f))
                                        MutedText(kind.subtitle)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { onAdd(if (note) null else picked, label) },
                enabled = note || picked != null,
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add") }
        }
    }
}

@Composable
private fun EditNodeDialog(node: MapNode, onDismiss: () -> Unit, onSave: (String, String, NodeKind?) -> Unit) {
    var label by remember { mutableStateOf(node.label) }
    var description by remember { mutableStateOf(node.description ?: "") }
    var kind by remember { mutableStateOf(node.kind) }
    var kindsOpen by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = label, onValueChange = { if (it.length <= 100) label = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (node.type == "architecture") {
                    OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { kindsOpen = !kindsOpen }, shape = RoundedCornerShape(50)) {
                        Box(Modifier.size(8.dp).background(kind?.color ?: DEFAULT_NODE_COLOR, CircleShape))
                        Text("  Type: ${kind?.label ?: "Component"}")
                    }
                    if (kindsOpen) {
                        NODE_KINDS.forEach { k ->
                            TextButton(onClick = { kind = k; kindsOpen = false }) {
                                Box(Modifier.size(8.dp).background(k.color, CircleShape))
                                Text("  ${k.label}", modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(label, description, kind) }, enabled = label.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectSheet(source: String, graph: MapGraph, onDismiss: () -> Unit, onConnect: (String, String?) -> Unit) {
    var query by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val targets = graph.nodes
        .filter { it.id != source && it.type != "section" && it.type != "task" }
        .filter { q.isEmpty() || it.label.lowercase().contains(q) }
        .sortedBy { it.label.lowercase() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connect “${graph.byId[source]?.label}” to…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label (optional)") }, placeholder = { Text("reads from, calls…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("Search the map") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (targets.isEmpty()) MutedText("Nothing to connect to yet.")
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                targets.forEach { n ->
                    item(key = n.id) {
                        Surface(onClick = { onConnect(n.id, label.ifBlank { null }) }, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).background(n.color, CircleShape))
                                Text("  ${n.label}", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                MutedText(n.kind?.label ?: if (n.type == "erTable") "Table" else "Note")
                            }
                        }
                    }
                }
            }
        }
    }
}
