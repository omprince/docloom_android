package com.docloom_android.feature.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.docloom_android.core.ui.ErrorBanner
import com.docloom_android.core.ui.LoadingBox
import com.docloom_android.core.ui.MessageBox
import com.docloom_android.core.ui.MutedText
import com.docloom_android.ui.theme.SuccessGreen
import com.docloom_android.ui.theme.WarningAmber
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

private enum class Tool { Move, Draw }

private val SWATCHES = listOf("#6d6ff8", "#f87171", "#fbbf24", "#34d399", "#38bdf8", "#f472b6", "#ffffff", "#14162b")

/**
 * A Studio image: view it (pinch to zoom, play sprite frames, hide layers)
 * and, for editors, add and change shapes, text and freehand strokes.
 * Painting pixels on raster layers stays on the website.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetScreen(vm: AssetViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tool by remember { mutableStateOf(Tool.Move) }
    var color by remember { mutableStateOf(SWATCHES[0]) }
    var layersOpen by remember { mutableStateOf(false) }
    var shapesOpen by remember { mutableStateOf(false) }
    var textDialog by remember { mutableStateOf<String?>(null) }
    var leaveDialog by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var frame by remember { mutableIntStateOf(0) }

    BackHandler(enabled = state.dirty) { leaveDialog = true }
    val back = { if (state.dirty) leaveDialog = true else onBack() }

    val doc = state.doc
    val grid = doc?.grid
    LaunchedEffect(playing, grid) {
        if (!playing || grid == null) return@LaunchedEffect
        while (true) {
            delay(125) // 8 frames a second
            frame = (frame + 1) % (grid.cols * grid.rows)
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(state.name.ifEmpty { "Image" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    doc?.let {
                        MutedText(
                            "${it.width}×${it.height} · " + when {
                                state.saving -> "Saving…"
                                state.dirty -> "Unsaved changes"
                                state.savedNotice -> "Saved"
                                else -> "v${state.version}"
                            },
                        )
                    }
                }
            },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                if (grid != null) IconButton(onClick = { playing = !playing }) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play frames") }
                IconButton(onClick = { layersOpen = true }) { Icon(Icons.Filled.Layers, "Layers") }
                if (!vm.readOnly) TextButton(onClick = { vm.save() }, enabled = state.dirty && !state.saving) { Text("Save") }
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> LoadingBox()
                doc == null -> MessageBox("Couldn't open this image", state.loadError, "Try again", vm::load)
                else -> {
                    if (state.pixelsLoaded < state.pixelsTotal) {
                        LinearProgressIndicator(progress = { state.pixelsLoaded / max(1f, state.pixelsTotal.toFloat()) }, modifier = Modifier.fillMaxWidth())
                    }
                    state.conflict?.let { theirs ->
                        Surface(color = WarningAmber.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("${theirs.updatedByLabel ?: "Someone"} changed this image while you were editing.", color = WarningAmber)
                                Row {
                                    TextButton(onClick = vm::takeTheirs) { Text("Use their version") }
                                    TextButton(onClick = vm::keepMine) { Text("Keep mine") }
                                }
                            }
                        }
                    }
                    state.error?.let { msg ->
                        Box(Modifier.padding(8.dp)) { ErrorBanner(msg) }
                    }
                    ImageCanvas(
                        state = state,
                        tool = if (vm.readOnly || playing) Tool.Move else tool,
                        color = color,
                        frame = if (playing && grid != null) frame else null,
                        editable = !vm.readOnly && !playing,
                        onSelect = vm::select,
                        onMove = vm::moveSelected,
                        onStroke = { pts -> vm.addStroke(pts, color, max(2f, min(doc.width, doc.height) / 120f)) },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    if (!vm.readOnly && !playing) {
                        EditBar(
                            state = state, tool = tool, color = color,
                            onTool = { tool = it },
                            onColor = { color = it; if (state.selectedId != null) vm.setColor(it) },
                            onShapes = { shapesOpen = true },
                            onText = { textDialog = "" },
                            vm = vm,
                            onEditText = { textDialog = it },
                        )
                    } else if (vm.readOnly) {
                        MutedText("View only.", Modifier.padding(12.dp))
                    }
                }
            }
        }
    }

    if (layersOpen && doc != null) {
        ModalBottomSheet(onDismissRequest = { layersOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                Text("Layers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                MutedText("Top of the list is drawn on top. The eye hides a layer on this phone only; the switch in the menu hides it for everyone.")
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(doc.panel, key = { it.first.id }) { (layer, depth) ->
                        LayerRow(layer, depth, selected = state.selectedId == layer.id, hidden = layer.id in state.hidden, editable = !vm.readOnly,
                            onSelect = { if (layer is Layer.Shape) vm.select(layer.id); layersOpen = false },
                            onToggleLocal = { vm.toggleHidden(layer.id) },
                            onToggleSaved = { vm.setVisible(layer.id, !layer.visible) })
                    }
                }
            }
        }
    }
    if (shapesOpen) {
        AlertDialog(
            onDismissRequest = { shapesOpen = false },
            title = { Text("Add a shape") },
            text = {
                Column {
                    listOf("rect" to "Rectangle", "ellipse" to "Ellipse", "polygon" to "Hexagon", "star" to "Star", "line" to "Line").forEach { (k, label) ->
                        TextButton(onClick = { vm.addShape(k, color); shapesOpen = false; tool = Tool.Move }) { Text(label, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { shapesOpen = false }) { Text("Cancel") } },
        )
    }
    textDialog?.let { initial ->
        var text by remember(initial) { mutableStateOf(initial) }
        val editing = initial.isNotEmpty()
        AlertDialog(
            onDismissRequest = { textDialog = null },
            title = { Text(if (editing) "Edit text" else "Add text") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 2, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    if (editing) vm.setText(text) else vm.addText(text, color)
                    textDialog = null
                    tool = Tool.Move
                }, enabled = text.isNotBlank()) { Text(if (editing) "Save" else "Add") }
            },
            dismissButton = { TextButton(onClick = { textDialog = null }) { Text("Cancel") } },
        )
    }
    if (leaveDialog) {
        AlertDialog(
            onDismissRequest = { leaveDialog = false },
            title = { Text("Save your changes?") },
            text = { Text("This image has changes that aren't saved yet.") },
            confirmButton = { TextButton(onClick = { leaveDialog = false; vm.save() }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { leaveDialog = false; onBack() }) { Text("Discard", color = MaterialTheme.colorScheme.error) } },
        )
    }
}

@Composable
private fun ImageCanvas(
    state: AssetState,
    tool: Tool,
    color: String,
    frame: Int?,
    editable: Boolean,
    onSelect: (String?) -> Unit,
    onMove: (Float, Float) -> Unit,
    onStroke: (List<Pair<Float, Float>>) -> Unit,
    modifier: Modifier,
) {
    val doc = state.doc ?: return
    var size by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val stroke = remember { mutableStateListOf<Pair<Float, Float>>() }

    // The image fitted to the view, then the user's pan and zoom on top.
    val fit = if (size.width == 0) 1f else min(size.width * 0.92f / doc.width, size.height * 0.92f / doc.height)
    val scale = fit * zoom
    val origin = Offset(size.width / 2f - doc.width * scale / 2f, size.height / 2f - doc.height * scale / 2f) + pan
    fun toDoc(p: Offset) = (p - origin) / scale

    val currentState by rememberUpdatedState(state)
    val currentTool by rememberUpdatedState(tool)
    val canEdit by rememberUpdatedState(editable)
    val currentScale by rememberUpdatedState(scale)
    val currentOrigin by rememberUpdatedState(origin)

    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, p, z, _ ->
                    val newZoom = (zoom * z).coerceIn(0.2f, 40f)
                    val f = newZoom / zoom
                    // Keep the point under the fingers still while zooming.
                    val centre = Offset(size.width / 2f, size.height / 2f)
                    pan = (pan + centre - centroid) * f - centre + centroid + p
                    zoom = newZoom
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val d = (pos - currentOrigin) / currentScale
                    onSelect(hitShape(currentState, d.x, d.y))
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!canEdit) return@awaitEachGesture
                    val start = (down.position - currentOrigin) / currentScale
                    val drawing = currentTool == Tool.Draw
                    val moving = currentTool == Tool.Move && currentState.selectedId != null &&
                        hitShape(currentState, start.x, start.y) == currentState.selectedId
                    if (!drawing && !moving) return@awaitEachGesture
                    if (drawing) { stroke.clear(); stroke += start.x to start.y }
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1) { stroke.clear(); break }
                        val c = event.changes.first()
                        if (!c.pressed) break
                        val delta = c.positionChange()
                        if (delta == Offset.Zero) continue
                        c.consume()
                        if (drawing) {
                            val p = (c.position - currentOrigin) / currentScale
                            val last = stroke.last()
                            // Skip points closer than ~2 screen px: keeps paths small.
                            if (kotlin.math.hypot(p.x - last.first, p.y - last.second) * currentScale > 2f) stroke += p.x to p.y
                        } else {
                            onMove(delta.x / currentScale, delta.y / currentScale)
                        }
                    }
                    if (drawing && stroke.size > 1) onStroke(stroke.toList())
                    stroke.clear()
                }
            },
    ) {
        val checker = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
        val accent = MaterialTheme.colorScheme.primary
        val strokeColor = cssColor(color)?.let { Color(it) } ?: accent
        Canvas(Modifier.fillMaxSize()) {
            withTransform({ translate(origin.x, origin.y); scale(scale, scale, Offset.Zero) }) {
                // Checkerboard shows transparency.
                val cell = max(4f, 16f / scale)
                drawRect(Color.White.copy(alpha = 0.02f), size = androidx.compose.ui.geometry.Size(doc.width.toFloat(), doc.height.toFloat()))
                var y = 0f
                var row = 0
                while (y < doc.height) {
                    var x = if (row % 2 == 0) 0f else cell
                    while (x < doc.width) {
                        drawRect(checker, Offset(x, y), androidx.compose.ui.geometry.Size(min(cell, doc.width - x), min(cell, doc.height - y)))
                        x += cell * 2
                    }
                    y += cell
                    row++
                }
                drawIntoCanvas { c ->
                    val native = c.nativeCanvas
                    val g = doc.grid
                    if (frame != null && g != null) {
                        // Sprite playback: draw one cell, scaled to fill the image area.
                        val col = frame % g.cols
                        val r = frame / g.cols
                        val cx = g.padding + col * (g.cellW + g.gap).toFloat()
                        val cy = g.padding + r * (g.cellH + g.gap).toFloat()
                        val k = min(doc.width / g.cellW.toFloat(), doc.height / g.cellH.toFloat())
                        native.save()
                        native.translate((doc.width - g.cellW * k) / 2, (doc.height - g.cellH * k) / 2)
                        native.scale(k, k)
                        native.clipRect(0f, 0f, g.cellW.toFloat(), g.cellH.toFloat())
                        native.translate(-cx, -cy)
                        drawDoc(native, doc, state.pixels, state.hidden)
                        native.restore()
                    } else {
                        drawDoc(native, doc, state.pixels, state.hidden)
                    }
                }
                // Selection box.
                (state.selectedId?.let { doc.find(it) } as? Layer.Shape)?.geom?.let { g ->
                    val b = geomBounds(g)
                    drawRect(accent, Offset(b.left, b.top), androidx.compose.ui.geometry.Size(b.width(), b.height()), style = Stroke(width = 2f / scale))
                }
                // The stroke being drawn.
                for (i in 1 until stroke.size) {
                    drawLine(strokeColor, Offset(stroke[i - 1].first, stroke[i - 1].second), Offset(stroke[i].first, stroke[i].second), strokeWidth = max(2f, min(doc.width, doc.height) / 120f))
                }
            }
        }
    }
}

/** Topmost visible shape whose box holds the point (document pixels). */
private fun hitShape(state: AssetState, x: Float, y: Float): String? {
    val doc = state.doc ?: return null
    return doc.panel.firstOrNull { (l, _) ->
        l is Layer.Shape && l.visible && !l.locked && l.id !in state.hidden &&
            l.geom?.let { g -> geomBounds(g).apply { inset(-6f, -6f) }.contains(x, y) } == true
    }?.first?.id
}

@Composable
private fun EditBar(
    state: AssetState,
    tool: Tool,
    color: String,
    onTool: (Tool) -> Unit,
    onColor: (String) -> Unit,
    onShapes: () -> Unit,
    onText: () -> Unit,
    vm: AssetViewModel,
    onEditText: (String) -> Unit,
) {
    val selected = state.selectedId?.let { state.doc?.find(it) } as? Layer.Shape
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(selected = tool == Tool.Move, onClick = { onTool(Tool.Move) }, label = { Text("Move") }, leadingIcon = { Icon(Icons.Filled.OpenWith, null, Modifier.size(16.dp)) })
                FilterChip(selected = tool == Tool.Draw, onClick = { onTool(Tool.Draw); vm.select(null) }, label = { Text("Draw") }, leadingIcon = { Icon(Icons.Filled.Brush, null, Modifier.size(16.dp)) })
                IconButton(onClick = onShapes) { Icon(Icons.Outlined.Category, "Add a shape") }
                IconButton(onClick = onText) { Icon(Icons.Filled.TextFields, "Add text") }
                Box(Modifier.weight(1f))
                if (selected != null) IconButton(onClick = vm::deleteSelected) { Icon(Icons.Filled.Delete, "Delete layer", tint = MaterialTheme.colorScheme.error) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SWATCHES.forEach { hex ->
                    val c = cssColor(hex)?.let { Color(it) } ?: Color.Gray
                    Box(
                        Modifier.size(28.dp).background(c, CircleShape)
                            .border(BorderStroke(if (hex == color) 3.dp else 1.dp, if (hex == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline), CircleShape)
                            .pointerInput(hex) { detectTapGestures { onColor(hex) } },
                    )
                }
            }
            if (selected != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(selected.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(0.4f))
                    Slider(value = selected.opacity, onValueChange = { vm.setOpacity(it) }, valueRange = 0f..1f, modifier = Modifier.weight(0.6f))
                }
                (selected.geom as? Geom.Text)?.let { g -> TextButton(onClick = { onEditText(g.text) }) { Text("Edit text") } }
                MutedText("Drag the selected shape to move it. Colour taps recolour it.")
            } else if (tool == Tool.Draw) {
                MutedText("Draw with one finger; two fingers pan and zoom.")
            } else {
                MutedText("Tap a shape to select it. Pinch to zoom.")
            }
        }
    }
}

@Composable
private fun LayerRow(
    layer: Layer,
    depth: Int,
    selected: Boolean,
    hidden: Boolean,
    editable: Boolean,
    onSelect: () -> Unit,
    onToggleLocal: () -> Unit,
    onToggleSaved: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        onClick = onSelect,
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = (8 + depth * 16).dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (layer) { is Layer.Group -> "▤"; is Layer.Raster -> "▦"; is Layer.Shape -> "◆" },
                color = MaterialTheme.colorScheme.secondary,
            )
            Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(layer.name.ifEmpty { "Layer" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                MutedText(
                    listOfNotNull(
                        when (layer) { is Layer.Group -> "Group"; is Layer.Raster -> "Pixels"; is Layer.Shape -> "Shape" },
                        if (!layer.visible) "hidden" else null,
                        if (layer.locked) "locked" else null,
                        if (layer.opacity < 1f) "${(layer.opacity * 100).toInt()}%" else null,
                    ).joinToString(" · "),
                )
            }
            IconButton(onClick = onToggleLocal) {
                Icon(if (hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Hide here", tint = if (hidden) MaterialTheme.colorScheme.onSurfaceVariant else SuccessGreen)
            }
            if (editable) Box {
                TextButton(onClick = { menu = true }) { Text("⋮") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(if (layer.visible) "Hide for everyone" else "Show for everyone") }, onClick = { menu = false; onToggleSaved() })
                }
            }
        }
    }
}
