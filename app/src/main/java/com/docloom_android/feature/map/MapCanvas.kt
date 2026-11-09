package com.docloom_android.feature.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Pan and zoom of the map. Screen point = map point × scale + offset. */
@Stable
class MapViewport {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var size by mutableStateOf(IntSize.Zero)

    fun toMap(screen: Offset) = (screen - offset) / scale

    /** The map point at the middle of the screen (where new boxes go). */
    fun center(): Offset = toMap(Offset(size.width / 2f, size.height / 2f))

    fun zoomBy(factor: Float, pivot: Offset = Offset(size.width / 2f, size.height / 2f)) {
        val newScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val f = newScale / scale
        offset = pivot - (pivot - offset) * f
        scale = newScale
    }

    fun fit(bounds: Rect?) {
        if (bounds == null || size.width == 0) return
        val pad = 48f
        val s = min((size.width - pad * 2) / max(bounds.width, 1f), (size.height - pad * 2) / max(bounds.height, 1f))
            .coerceIn(MIN_SCALE, 1.5f)
        scale = s
        offset = Offset(size.width / 2f, size.height / 2f) - bounds.center * s
    }

    fun centerOn(box: Rect) {
        if (scale < 0.8f) scale = 1f
        offset = Offset(size.width / 2f, size.height / 2f) - box.center * scale
    }

    companion object {
        const val MIN_SCALE = 0.15f
        const val MAX_SCALE = 3f
    }
}

data class MapColors(
    val canvas: Color,
    val dot: Color,
    val card: Color,
    val cardBorder: Color,
    val text: Color,
    val muted: Color,
    val selected: Color,
    val activity: Color,
    val section: Color,
)

@Composable
fun mapColors() = MapColors(
    canvas = MaterialTheme.colorScheme.background,
    dot = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    card = MaterialTheme.colorScheme.surfaceContainer,
    cardBorder = MaterialTheme.colorScheme.outline,
    text = MaterialTheme.colorScheme.onSurface,
    muted = MaterialTheme.colorScheme.onSurfaceVariant,
    selected = MaterialTheme.colorScheme.primary,
    activity = Color(0xFF34D399),
    section = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
)

/**
 * Draws the map and handles touch: one or two fingers pan and pinch-zoom,
 * a tap selects, and dragging the selected box moves it (when editable).
 */
@Composable
fun MapCanvas(
    graph: MapGraph,
    viewport: MapViewport,
    selectedId: String?,
    highlighted: Set<String>,
    editable: Boolean,
    onTap: (String?) -> Unit,
    onDrag: (String, Offset) -> Unit,
    onDragEnd: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = mapColors()
    val measurer = rememberTextMeasurer(cacheSize = 256)
    val currentGraph by rememberUpdatedState(graph)
    val currentSelected by rememberUpdatedState(selectedId)
    val canDrag by rememberUpdatedState(editable)

    // Text is laid out once per graph change, not on every frame.
    val texts = remember(graph, colors) { layoutTexts(graph, colors, measurer) }

    Canvas(
        modifier
            .onSizeChanged { viewport.size = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    viewport.offset += pan
                    if (zoom != 1f) viewport.zoomBy(zoom, centroid)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { pos -> onTap(currentGraph.hit(viewport.toMap(pos))?.id) }
            }
            // Inner modifiers see touches first: a drag that starts on the
            // selected box moves it, and the pan/zoom above never sees it.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val id = currentSelected ?: return@awaitEachGesture
                    if (!canDrag) return@awaitEachGesture
                    val node = currentGraph.byId[id] ?: return@awaitEachGesture
                    if (node.type == "task") return@awaitEachGesture
                    val box = currentGraph.boxes[id] ?: return@awaitEachGesture
                    if (!box.contains(viewport.toMap(down.position))) return@awaitEachGesture
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1) break // a second finger: let pinch-zoom take over
                        val change = event.changes.first()
                        if (!change.pressed) break
                        val delta = change.positionChange()
                        if (delta != Offset.Zero) {
                            moved = true
                            change.consume()
                            onDrag(id, delta / viewport.scale)
                        }
                    }
                    if (moved) onDragEnd(id)
                }
            },
    ) {
        drawRect(colors.canvas)
        drawGrid(viewport, colors)
        withTransform({
            translate(viewport.offset.x, viewport.offset.y)
            scale(viewport.scale, viewport.scale, Offset.Zero)
        }) {
            val showText = viewport.scale >= 0.3f
            for (n in graph.drawOrder) if (n.type == "section") drawSection(n, graph.boxes[n.id]!!, colors, texts, showText, n.id == selectedId)
            for (e in graph.edges) drawEdge(e, graph, colors, texts, showText)
            for (n in graph.drawOrder) {
                if (n.type == "section") continue
                drawNode(n, graph.boxes[n.id]!!, colors, texts, showText, n.id == selectedId, n.id in highlighted)
            }
        }
    }
}

private fun DrawScope.drawGrid(viewport: MapViewport, colors: MapColors) {
    val step = 24f * viewport.scale
    if (step < 8f) return
    val startX = viewport.offset.x % step
    val startY = viewport.offset.y % step
    var x = startX
    while (x < size.width) {
        var y = startY
        while (y < size.height) {
            drawCircle(colors.dot, radius = 1.2f, center = Offset(x, y))
            y += step
        }
        x += step
    }
}

/** Pre-measured text for every node and edge label. */
private class MapTexts(val byKey: Map<String, TextLayoutResult>)

private fun layoutTexts(graph: MapGraph, colors: MapColors, measurer: androidx.compose.ui.text.TextMeasurer): MapTexts {
    val out = HashMap<String, TextLayoutResult>()
    fun measure(key: String, text: String, width: Float, style: TextStyle, lines: Int = 1) {
        out[key] = measurer.measure(
            text,
            style,
            overflow = TextOverflow.Ellipsis,
            maxLines = lines,
            constraints = Constraints(maxWidth = max(1, width.toInt())),
        )
    }
    val title = TextStyle(color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val small = TextStyle(color = colors.muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    val row = TextStyle(color = colors.text, fontSize = 11.sp)
    for (n in graph.nodes) {
        when (n.type) {
            "section" -> measure("${n.id}:title", n.label, n.width - 24, title.copy(color = colors.muted))
            "architecture" -> {
                measure("${n.id}:title", n.label, n.width - 22, title)
                n.subtitle?.let { measure("${n.id}:sub", it, n.width - 22, small) }
            }
            "erTable" -> {
                measure("${n.id}:title", n.label, n.width - 24, title)
                if (!n.collapsed) n.columns.forEachIndexed { i, c ->
                    measure("${n.id}:c$i", (if (c.pk) "🔑 " else "") + c.name, n.width * 0.58f, row)
                    measure("${n.id}:t$i", c.type, n.width * 0.36f, small)
                }
            }
            "taskNote" -> {
                measure("${n.id}:title", n.label, n.width - 20, title)
                n.taskItems.forEachIndexed { i, t -> measure("${n.id}:i$i", "☐ $t", n.width - 20, row) }
            }
            "task" -> {
                val number = (n.data["number"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it != "null" }
                measure("${n.id}:title", (number?.let { "#$it " } ?: "") + n.label, n.width - 28, row.copy(fontWeight = FontWeight.Medium))
            }
            else -> measure("${n.id}:title", n.label, n.width - 20, title, lines = 2)
        }
    }
    for (e in graph.edges) e.label?.let { measure("edge:${e.id}", it, 160f, small.copy(color = colors.text)) }
    return MapTexts(out)
}

private fun DrawScope.text(texts: MapTexts, key: String, at: Offset) {
    texts.byKey[key]?.let { drawText(it, topLeft = at) }
}

private fun DrawScope.drawSection(n: MapNode, box: Rect, colors: MapColors, texts: MapTexts, showText: Boolean, selected: Boolean) {
    val r = CornerRadius(16f)
    drawRoundRect(colors.section, box.topLeft, box.size, r)
    drawRoundRect(
        if (selected) colors.selected else colors.cardBorder.copy(alpha = 0.5f),
        box.topLeft, box.size, r,
        style = Stroke(width = if (selected) 3f else 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
    )
    if (showText) text(texts, "${n.id}:title", box.topLeft + Offset(12f, 10f))
}

private fun DrawScope.drawEdge(e: MapEdge, graph: MapGraph, colors: MapColors, texts: MapTexts, showText: Boolean) {
    val (start, end) = edgeEnds(e, graph) ?: return
    val sColor = e.sourceColor ?: graph.byId[e.source]?.color ?: colors.muted
    val tColor = e.targetColor ?: graph.byId[e.target]?.color ?: colors.muted
    val dx = max(40f, abs(end.x - start.x) / 2f)
    val path = Path().apply {
        moveTo(start.x, start.y)
        cubicTo(start.x + dx, start.y, end.x - dx, end.y, end.x, end.y)
    }
    val brush = Brush.linearGradient(listOf(sColor, tColor), start, end)
    drawPath(path, brush, style = Stroke(width = 2f, pathEffect = if (e.managed) PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) else null))
    // Arrowhead: the curve always enters the target's left side horizontally.
    val size = 9f
    val head = Path().apply {
        moveTo(end.x, end.y)
        lineTo(end.x - size, end.y - size * 0.55f)
        lineTo(end.x - size, end.y + size * 0.55f)
        close()
    }
    drawPath(head, tColor)
    if (showText) texts.byKey["edge:${e.id}"]?.let { layout ->
        val mid = Offset((start.x + end.x) / 2, (start.y + end.y) / 2)
        val w = layout.size.width.toFloat()
        val h = layout.size.height.toFloat()
        drawRoundRect(colors.card, mid - Offset(w / 2 + 6, h / 2 + 3), Size(w + 12, h + 6), CornerRadius(8f))
        drawText(layout, topLeft = mid - Offset(w / 2, h / 2))
    }
}

private fun DrawScope.drawNode(
    n: MapNode,
    box: Rect,
    colors: MapColors,
    texts: MapTexts,
    showText: Boolean,
    selected: Boolean,
    highlighted: Boolean,
) {
    val r = CornerRadius(10f)
    if (highlighted) drawRoundRect(colors.activity.copy(alpha = 0.25f), box.topLeft - Offset(6f, 6f), Size(box.width + 12, box.height + 12), CornerRadius(14f))
    drawRoundRect(colors.card, box.topLeft, box.size, r)
    val border = when {
        selected -> colors.selected
        n.type == "architecture" || n.type == "erTable" -> n.color.copy(alpha = 0.6f)
        else -> colors.cardBorder.copy(alpha = 0.6f)
    }
    drawRoundRect(border, box.topLeft, box.size, r, style = Stroke(width = if (selected) 3f else 1.5f))

    when (n.type) {
        "architecture" -> {
            drawRoundRect(n.color, box.topLeft + Offset(6f, 8f), Size(4f, box.height - 16f), CornerRadius(2f))
            if (showText) {
                text(texts, "${n.id}:title", box.topLeft + Offset(16f, 10f))
                text(texts, "${n.id}:sub", box.topLeft + Offset(16f, 32f))
            }
        }
        "erTable" -> {
            drawRoundRect(TABLE_COLOR.copy(alpha = 0.18f), box.topLeft, Size(box.width, ER_HEADER), r)
            if (showText) {
                text(texts, "${n.id}:title", box.topLeft + Offset(12f, 11f))
                if (!n.collapsed) n.columns.forEachIndexed { i, _ ->
                    val y = box.top + ER_HEADER + ER_ROW * i
                    if (i > 0) drawLine(colors.cardBorder.copy(alpha = 0.25f), Offset(box.left, y), Offset(box.right, y))
                    text(texts, "${n.id}:c$i", Offset(box.left + 10f, y + 5f))
                    text(texts, "${n.id}:t$i", Offset(box.left + box.width * 0.62f, y + 6f))
                }
            }
        }
        "taskNote" -> if (showText) {
            text(texts, "${n.id}:title", box.topLeft + Offset(10f, 11f))
            n.taskItems.forEachIndexed { i, _ -> text(texts, "${n.id}:i$i", Offset(box.left + 10f, box.top + ER_HEADER + ER_ROW * i + 4f)) }
        }
        "task" -> {
            drawCircle(n.color, radius = 5f, center = Offset(box.left + 14f, box.center.y))
            if (showText) text(texts, "${n.id}:title", Offset(box.left + 26f, box.top + 13f))
        }
        else -> if (showText) text(texts, "${n.id}:title", box.topLeft + Offset(10f, 8f))
    }
}
