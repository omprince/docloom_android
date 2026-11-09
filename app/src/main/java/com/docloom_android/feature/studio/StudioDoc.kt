package com.docloom_android.feature.studio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// The Studio document (website lib/studio/types.ts), read for drawing. Edits
// work on the raw JSON so fields the app doesn't know are kept on save.

sealed interface Geom {
    data class Rect(val x: Float, val y: Float, val w: Float, val h: Float, val r: Float) : Geom
    data class Ellipse(val x: Float, val y: Float, val w: Float, val h: Float) : Geom
    data class Polygon(val x: Float, val y: Float, val w: Float, val h: Float, val sides: Int) : Geom
    data class Star(val x: Float, val y: Float, val w: Float, val h: Float, val points: Int, val inner: Float) : Geom
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : Geom
    /** Anchors: x, y, inX, inY, outX, outY (absolute). */
    data class PathG(val closed: Boolean, val points: List<FloatArray>) : Geom
    data class Text(
        val x: Float, val y: Float, val text: String, val size: Float, val font: String,
        val weight: Int, val italic: Boolean, val align: String, val lineHeight: Float,
    ) : Geom
}

sealed interface Layer {
    val id: String
    val name: String
    val visible: Boolean
    val locked: Boolean
    val opacity: Float
    val blend: String

    data class Group(
        override val id: String, override val name: String, override val visible: Boolean, override val locked: Boolean,
        override val opacity: Float, override val blend: String, val children: List<Layer>, val frame: Int?,
    ) : Layer

    data class Raster(
        override val id: String, override val name: String, override val visible: Boolean, override val locked: Boolean,
        override val opacity: Float, override val blend: String,
        val x: Float, val y: Float, val w: Float, val h: Float, val blob: String?, val maskBlob: String?, val maskEnabled: Boolean,
    ) : Layer

    data class Shape(
        override val id: String, override val name: String, override val visible: Boolean, override val locked: Boolean,
        override val opacity: Float, override val blend: String,
        val geom: Geom?, val fill: String?, val stroke: String?, val strokeWidth: Float, val rotation: Float,
    ) : Layer
}

data class StudioDocView(
    val width: Int,
    val height: Int,
    val background: String?,
    val grid: GridSpec?,
    val layers: List<Layer>,
) {
    /** Every blob id a raster layer or mask points at. */
    val blobs: List<String> = buildList {
        fun walk(ls: List<Layer>) {
            for (l in ls) when (l) {
                is Layer.Group -> walk(l.children)
                is Layer.Raster -> { l.blob?.let(::add); l.maskBlob?.let(::add) }
                is Layer.Shape -> Unit
            }
        }
        walk(layers)
    }

    /** Layers flattened top to bottom (as a layer panel lists them), with depth. */
    val panel: List<Pair<Layer, Int>> = buildList {
        fun walk(ls: List<Layer>, depth: Int) {
            for (l in ls.asReversed()) {
                add(l to depth)
                if (l is Layer.Group) walk(l.children, depth + 1)
            }
        }
        walk(layers, 0)
    }

    fun find(id: String): Layer? = panel.firstOrNull { it.first.id == id }?.first
}

// ---------------- parsing ----------------

private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.f(k: String, d: Float = 0f): Float = (this[k] as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: d
private fun JsonObject.b(k: String, d: Boolean): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull ?: d

fun parseDoc(doc: JsonObject): StudioDocView {
    val grid = (doc["grid"] as? JsonObject)?.let {
        GridSpec(it.f("cols").toInt(), it.f("rows").toInt(), it.f("cellW").toInt(), it.f("cellH").toInt(), it.f("gap").toInt(), it.f("padding").toInt())
    }
    return StudioDocView(
        width = doc.f("width", 1f).toInt(),
        height = doc.f("height", 1f).toInt(),
        background = doc.s("background"),
        grid = grid,
        layers = parseLayers(doc["nodes"]),
    )
}

private fun parseLayers(el: JsonElement?): List<Layer> = (el as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseLayer) }

private fun parseLayer(o: JsonObject): Layer? {
    val id = o.s("id") ?: return null
    val name = o.s("name") ?: ""
    val visible = o.b("visible", true)
    val locked = o.b("locked", false)
    val opacity = o.f("opacity", 1f)
    val blend = o.s("blend") ?: "normal"
    return when (o.s("type")) {
        "group" -> Layer.Group(id, name, visible, locked, opacity, blend, parseLayers(o["children"]), (o["frame"] as? JsonPrimitive)?.doubleOrNull?.toInt())
        "raster" -> {
            val mask = o["mask"] as? JsonObject
            Layer.Raster(id, name, visible, locked, opacity, blend, o.f("x"), o.f("y"), o.f("w", 1f), o.f("h", 1f), o.s("blob"), mask?.s("blob"), mask?.b("enabled", true) == true)
        }
        "shape" -> Layer.Shape(
            id, name, visible, locked, opacity, blend,
            (o["shape"] as? JsonObject)?.let(::parseGeom), o.s("fill"), o.s("stroke"), o.f("strokeWidth"), o.f("rotation"),
        )
        else -> null
    }
}

private fun parseGeom(g: JsonObject): Geom? = when (g.s("kind")) {
    "rect" -> Geom.Rect(g.f("x"), g.f("y"), g.f("w"), g.f("h"), g.f("r"))
    "ellipse" -> Geom.Ellipse(g.f("x"), g.f("y"), g.f("w"), g.f("h"))
    "polygon" -> Geom.Polygon(g.f("x"), g.f("y"), g.f("w"), g.f("h"), g.f("sides", 6f).toInt())
    "star" -> Geom.Star(g.f("x"), g.f("y"), g.f("w"), g.f("h"), g.f("points", 5f).toInt(), g.f("inner", 0.5f))
    "line" -> Geom.Line(g.f("x1"), g.f("y1"), g.f("x2"), g.f("y2"))
    "path" -> Geom.PathG(
        g.b("closed", false),
        (g["points"] as? JsonArray).orEmpty().mapNotNull { p ->
            (p as? JsonArray)?.takeIf { it.size >= 6 }?.map { (it as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: 0f }?.toFloatArray()
        },
    )
    "text" -> Geom.Text(
        g.f("x"), g.f("y"), g.s("text") ?: "", g.f("size", 32f), g.s("font") ?: "sans-serif",
        g.f("weight", 400f).toInt(), g.b("italic", false), g.s("align") ?: "left", g.f("lineHeight", 1.2f),
    )
    else -> null
}

// ---------------- CSS colours ----------------

/** "#rgb", "#rrggbb", "#rrggbbaa", "rgb()/rgba()", "transparent" → ARGB. Null if unreadable. */
fun cssColor(s: String?): Int? {
    if (s == null) return null
    val t = s.trim().lowercase()
    if (t == "transparent" || t == "none") return 0
    if (t.startsWith("#")) {
        val h = t.drop(1)
        val full = when (h.length) {
            3, 4 -> h.map { "$it$it" }.joinToString("")
            else -> h
        }
        return runCatching {
            when (full.length) {
                6 -> (0xFF000000 or full.toLong(16)).toInt()
                8 -> ((full.substring(6, 8).toLong(16) shl 24) or full.substring(0, 6).toLong(16)).toInt()
                else -> null
            }
        }.getOrNull()
    }
    Regex("""rgba?\(([^)]+)\)""").matchEntire(t)?.let { m ->
        val parts = m.groupValues[1].split(',', ' ', '/').filter { it.isNotBlank() }.map { it.trim() }
        if (parts.size < 3) return null
        val (r, g, b) = parts.take(3).map { it.removeSuffix("%").toFloatOrNull()?.roundToInt()?.coerceIn(0, 255) ?: return null }
        val a = parts.getOrNull(3)?.let { if (it.endsWith("%")) it.removeSuffix("%").toFloatOrNull()?.div(100f) else it.toFloatOrNull() } ?: 1f
        return ((a.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or (r shl 16) or (g shl 8) or b
    }
    return when (t) {
        "white" -> 0xFFFFFFFF.toInt()
        "black" -> 0xFF000000.toInt()
        "red" -> 0xFFFF0000.toInt()
        else -> null
    }
}

// ---------------- drawing ----------------

private fun xfer(blend: String): PorterDuffXfermode? = when (blend) {
    "multiply" -> PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    "screen" -> PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    "overlay" -> PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
    "darken" -> PorterDuffXfermode(PorterDuff.Mode.DARKEN)
    "lighten" -> PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
    // Other CSS blend modes have no PorterDuff equivalent before API 29; drawn as normal.
    else -> null
}

/**
 * Draws [doc] onto [canvas] in document pixels (scale the canvas first).
 * [pixels] maps blob ids to bitmaps; [hidden] are layers hidden in this view only.
 */
fun drawDoc(canvas: Canvas, doc: StudioDocView, pixels: Map<String, Bitmap>, hidden: Set<String> = emptySet(), withBackground: Boolean = true) {
    canvas.save()
    canvas.clipRect(0f, 0f, doc.width.toFloat(), doc.height.toFloat())
    if (withBackground) cssColor(doc.background)?.let { canvas.drawColor(it) }
    drawLayers(canvas, doc.layers, pixels, hidden)
    canvas.restore()
}

private fun drawLayers(canvas: Canvas, layers: List<Layer>, pixels: Map<String, Bitmap>, hidden: Set<String>) {
    for (l in layers) {
        if (!l.visible || l.id in hidden || l.opacity <= 0f) continue
        val needsLayer = l.opacity < 1f || xfer(l.blend) != null
        val save = if (needsLayer) {
            canvas.saveLayer(null, Paint().apply {
                alpha = (l.opacity.coerceIn(0f, 1f) * 255).roundToInt()
                xfermode = xfer(l.blend)
            })
        } else {
            canvas.save()
        }
        when (l) {
            is Layer.Group -> drawLayers(canvas, l.children, pixels, hidden)
            is Layer.Raster -> drawRaster(canvas, l, pixels)
            is Layer.Shape -> drawShape(canvas, l)
        }
        canvas.restoreToCount(save)
    }
}

private fun drawRaster(canvas: Canvas, l: Layer.Raster, pixels: Map<String, Bitmap>) {
    val bmp = l.blob?.let { pixels[it] } ?: return
    val dst = RectF(l.x, l.y, l.x + l.w, l.y + l.h)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    val mask = l.maskBlob?.takeIf { l.maskEnabled }?.let { pixels[it] }
    if (mask == null) {
        canvas.drawBitmap(bmp, null, dst, paint)
        return
    }
    // Opaque mask shows, transparent hides.
    val count = canvas.saveLayer(dst, null)
    canvas.drawBitmap(bmp, null, dst, paint)
    canvas.drawBitmap(mask, null, dst, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
    canvas.restoreToCount(count)
}

/** Box of the shape's own geometry before rotation (website geomBounds). */
fun geomBounds(g: Geom): RectF = when (g) {
    is Geom.Rect -> norm(g.x, g.y, g.w, g.h)
    is Geom.Ellipse -> norm(g.x, g.y, g.w, g.h)
    is Geom.Polygon -> norm(g.x, g.y, g.w, g.h)
    is Geom.Star -> norm(g.x, g.y, g.w, g.h)
    is Geom.Line -> RectF(min(g.x1, g.x2), min(g.y1, g.y2), max(g.x1, g.x2), max(g.y1, g.y2))
    is Geom.PathG -> if (g.points.isEmpty()) RectF() else RectF(
        g.points.minOf { it[0] }, g.points.minOf { it[1] }, g.points.maxOf { it[0] }, g.points.maxOf { it[1] },
    )
    is Geom.Text -> {
        val p = textPaint(g)
        val lines = g.text.split("\n")
        val w = max(1f, lines.maxOf { p.measureText(it.ifEmpty { " " }) })
        RectF(g.x, g.y, g.x + w, g.y + lines.size * g.size * g.lineHeight)
    }
}

private fun norm(x: Float, y: Float, w: Float, h: Float) = RectF(min(x, x + w), min(y, y + h), max(x, x + w), max(y, y + h))

private fun textPaint(g: Geom.Text) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = g.size
    val family = when {
        g.font.contains("mono", true) -> Typeface.MONOSPACE
        g.font.contains("serif", true) && !g.font.contains("sans", true) -> Typeface.SERIF
        else -> Typeface.SANS_SERIF
    }
    val style = when {
        g.weight >= 600 && g.italic -> Typeface.BOLD_ITALIC
        g.weight >= 600 -> Typeface.BOLD
        g.italic -> Typeface.ITALIC
        else -> Typeface.NORMAL
    }
    typeface = Typeface.create(family, style)
}

fun shapePath(g: Geom): Path = Path().apply {
    when (g) {
        is Geom.Rect -> {
            val b = geomBounds(g)
            addRoundRect(b, g.r, g.r, Path.Direction.CW)
        }
        is Geom.Ellipse -> addOval(geomBounds(g), Path.Direction.CW)
        is Geom.Polygon, is Geom.Star -> {
            val pts = polygonPoints(g)
            pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x, y) else lineTo(x, y) }
            close()
        }
        is Geom.Line -> { moveTo(g.x1, g.y1); lineTo(g.x2, g.y2) }
        is Geom.PathG -> {
            val p = g.points
            if (p.isEmpty()) return@apply
            moveTo(p[0][0], p[0][1])
            for (i in 1 until p.size) cubicTo(p[i - 1][4], p[i - 1][5], p[i][2], p[i][3], p[i][0], p[i][1])
            if (g.closed && p.size > 1) {
                cubicTo(p.last()[4], p.last()[5], p[0][2], p[0][3], p[0][0], p[0][1])
                close()
            }
        }
        is Geom.Text -> addRect(geomBounds(g), Path.Direction.CW)
    }
}

/** Corner points of a regular polygon or star inside its box (website polygonPoints). */
fun polygonPoints(g: Geom): List<Pair<Float, Float>> {
    val (x, y, w, h) = when (g) {
        is Geom.Polygon -> listOf(g.x, g.y, g.w, g.h)
        is Geom.Star -> listOf(g.x, g.y, g.w, g.h)
        else -> return emptyList()
    }
    val cx = x + w / 2
    val cy = y + h / 2
    val rx = w / 2
    val ry = h / 2
    return if (g is Geom.Polygon) {
        val n = max(3, g.sides)
        (0 until n).map { i ->
            val a = -PI / 2 + i * 2 * PI / n
            (cx + rx * cos(a)).toFloat() to (cy + ry * sin(a)).toFloat()
        }
    } else {
        g as Geom.Star
        val n = max(3, g.points)
        (0 until n * 2).map { i ->
            val a = -PI / 2 + i * PI / n
            val k = if (i % 2 == 0) 1f else g.inner.coerceIn(0.05f, 1f)
            (cx + rx * k * cos(a)).toFloat() to (cy + ry * k * sin(a)).toFloat()
        }
    }
}

private fun drawShape(canvas: Canvas, l: Layer.Shape) {
    val g = l.geom ?: return
    canvas.save()
    if (l.rotation != 0f) {
        val b = geomBounds(g)
        canvas.rotate(l.rotation, b.centerX(), b.centerY())
    }
    val fill = cssColor(l.fill)
    val stroke = cssColor(l.stroke)
    if (g is Geom.Text) {
        val p = textPaint(g)
        val lines = g.text.split("\n")
        val widths = lines.map { p.measureText(it.ifEmpty { " " }) }
        val width = max(1f, widths.max())
        val lineH = g.size * g.lineHeight
        lines.forEachIndexed { i, line ->
            val dx = when (g.align) {
                "center" -> (width - widths[i]) / 2
                "right" -> width - widths[i]
                else -> 0f
            }
            // Canvas "top" baseline: the ascent sits on y.
            val top = g.y + i * lineH + (lineH - g.size) / 2
            val baseline = top - p.fontMetrics.ascent
            if (fill != null) canvas.drawText(line, g.x + dx, baseline, Paint(p).apply { color = fill })
            if (stroke != null && l.strokeWidth > 0) {
                canvas.drawText(line, g.x + dx, baseline, Paint(p).apply {
                    color = stroke
                    style = Paint.Style.STROKE
                    strokeWidth = l.strokeWidth
                    strokeJoin = Paint.Join.ROUND
                })
            }
        }
    } else {
        val path = shapePath(g)
        val closed = g !is Geom.Line && !(g is Geom.PathG && !g.closed)
        if (fill != null && closed) canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
        if (stroke != null && l.strokeWidth > 0) {
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = stroke
                style = Paint.Style.STROKE
                strokeWidth = l.strokeWidth
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            })
        }
    }
    canvas.restore()
}

/** Renders the whole image at most [max] px on its long side (the library preview). */
fun renderPreview(doc: StudioDocView, pixels: Map<String, Bitmap>, max: Int = 320): Bitmap {
    val k = min(1f, max.toFloat() / maxOf(doc.width, doc.height))
    val w = max(1, (doc.width * k).roundToInt())
    val h = max(1, (doc.height * k).roundToInt())
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.scale(k, k)
    drawDoc(c, doc, pixels)
    return bmp
}

// ---------------- editing the JSON ----------------

/** Ids like the website's tree.newId(). */
fun newLayerId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

fun shapeNode(name: String, geom: JsonObject, fill: String?, stroke: String?, strokeWidth: Float): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(newLayerId()))
    put("type", JsonPrimitive("shape"))
    put("name", JsonPrimitive(name))
    put("visible", JsonPrimitive(true))
    put("locked", JsonPrimitive(false))
    put("opacity", JsonPrimitive(1))
    put("blend", JsonPrimitive("normal"))
    put("shape", geom)
    put("fill", fill?.let(::JsonPrimitive) ?: JsonNull)
    put("stroke", stroke?.let(::JsonPrimitive) ?: JsonNull)
    put("strokeWidth", JsonPrimitive(strokeWidth))
    put("rotation", JsonPrimitive(0))
}

fun geomJson(kind: String, vararg fields: Pair<String, Any>): JsonObject = buildJsonObject {
    put("kind", JsonPrimitive(kind))
    for ((k, v) in fields) put(k, when (v) {
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is JsonElement -> v
        else -> JsonPrimitive(v.toString())
    })
}

/** Adds [node] on top of everything (children are stored bottom to top). */
fun JsonObject.withTopLayer(node: JsonObject): JsonObject {
    val nodes = (this["nodes"] as? JsonArray).orEmpty()
    return JsonObject(this + ("nodes" to JsonArray(nodes + node)))
}

/** Replaces the layer [id] anywhere in the tree with [transform] of it (null removes it). */
fun JsonObject.mapLayer(id: String, transform: (JsonObject) -> JsonObject?): JsonObject {
    fun walk(list: JsonArray): JsonArray = JsonArray(list.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull el
        if ((o["id"] as? JsonPrimitive)?.content == id) return@mapNotNull transform(o)
        val children = o["children"] as? JsonArray
        if (children != null) JsonObject(o + ("children" to walk(children))) else o
    })
    val nodes = this["nodes"] as? JsonArray ?: return this
    return JsonObject(this + ("nodes" to walk(nodes)))
}

/** Same layer JSON with top-level fields set. */
fun JsonObject.with(vararg fields: Pair<String, JsonElement>): JsonObject = JsonObject(this + fields)

/** Moves a shape's geometry by (dx, dy) document pixels. */
fun moveGeom(shape: JsonObject, dx: Float, dy: Float): JsonObject {
    fun add(o: JsonObject, k: String, d: Float) = (o[k] as? JsonPrimitive)?.doubleOrNull?.let { k to JsonPrimitive(it + d) }
    val pairs = mutableListOf<Pair<String, JsonElement>>()
    for (k in listOf("x", "x1", "x2")) add(shape, k, dx)?.let(pairs::add)
    for (k in listOf("y", "y1", "y2")) add(shape, k, dy)?.let(pairs::add)
    (shape["points"] as? JsonArray)?.let { pts ->
        pairs += "points" to JsonArray(pts.map { p ->
            val a = (p as? JsonArray) ?: return@map p
            JsonArray(a.mapIndexed { i, v ->
                val n = (v as? JsonPrimitive)?.doubleOrNull ?: return@mapIndexed v
                JsonPrimitive(n + if (i % 2 == 0) dx else dy)
            })
        })
    }
    return JsonObject(shape + pairs)
}
