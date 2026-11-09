package com.docloom_android.feature.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.docloom_android.feature.tasks.statusMeta
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.util.UUID

// The map is stored as React Flow nodes and edges (JSON). The app keeps each
// node's raw JSON and edits only the fields it understands, so fields it
// doesn't know (set by the website) survive a save.

data class NodeKind(val key: String, val label: String, val subtitle: String, val category: String, val color: Color)

/** Port of the website's components/canvas/nodeRegistry.ts. */
val NODE_KINDS: List<NodeKind> = listOf(
    NodeKind("frontend", "Frontend", "ui.render", "Core dev stack", Color(0xFF38BDF8)),
    NodeKind("backend", "Backend / API", "api.handler", "Core dev stack", Color(0xFFA78BFA)),
    NodeKind("database", "Database", "db.query", "Core dev stack", Color(0xFF2DD4BF)),
    NodeKind("auth", "Auth", "auth.verify", "Core dev stack", Color(0xFFFBBF24)),
    NodeKind("cache", "Cache / Queue", "cache.get", "Core dev stack", Color(0xFFF472B6)),
    NodeKind("uiFramework", "UI Framework", "ui.compose", "Frontend tools", Color(0xFF67E8F9)),
    NodeKind("stateManagement", "State Management", "store.dispatch", "Frontend tools", Color(0xFFC084FC)),
    NodeKind("routing", "Routing", "router.match", "Frontend tools", Color(0xFFFCD34D)),
    NodeKind("formValidation", "Forms & Validation", "form.validate", "Frontend tools", Color(0xFF86EFAC)),
    NodeKind("designSystem", "Design System / UI Kit", "ui.tokens", "Frontend tools", Color(0xFFF9A8D4)),
    NodeKind("orm", "ORM / Query Builder", "orm.query", "Backend tools", Color(0xFF7DD3FC)),
    NodeKind("messageQueue", "Message Queue", "queue.publish", "Backend tools", Color(0xFFFDBA74)),
    NodeKind("backgroundJobs", "Background Jobs", "job.schedule", "Backend tools", Color(0xFF93C5FD)),
    NodeKind("apiGateway", "API Gateway", "gateway.route", "Backend tools", Color(0xFFD8B4FE)),
    NodeKind("logging", "Logging / Monitoring", "log.emit", "Backend tools", Color(0xFFBEF264)),
    NodeKind("apiEndpoint", "API Endpoint", "http.route", "Backend tools", Color(0xFFC4B5FD)),
    NodeKind("table", "Table / Schema", "table.row", "Database", Color(0xFF5EEAD4)),
    NodeKind("migration", "Migration", "schema.migrate", "Database", Color(0xFFFDA4AF)),
    NodeKind("readReplica", "Read Replica", "db.replica", "Database", Color(0xFF99F6E4)),
    NodeKind("searchIndex", "Search Index", "index.search", "Database", Color(0xFFEAB308)),
    NodeKind("agent", "Agent", "agent.run", "AI & Agents", Color(0xFFFB7185)),
    NodeKind("orchestrator", "Orchestrator / Coordinator", "flow.route", "AI & Agents", Color(0xFFA5B4FC)),
    NodeKind("skill", "Skill", "skill.execute", "AI & Agents", Color(0xFFFACC15)),
    NodeKind("prompt", "Prompt", "prompt.render", "AI & Agents", Color(0xFF7C93FD)),
    NodeKind("toolCall", "Tool / Function Call", "tool.call", "AI & Agents", Color(0xFFF97316)),
    NodeKind("mcpServer", "MCP Server", "mcp.connect", "AI & Agents", Color(0xFF10B981)),
    NodeKind("llmModel", "LLM / Model", "model.infer", "AI & Agents", Color(0xFF8B5CF6)),
    NodeKind("vectorStore", "Vector Store / Memory", "vector.embed", "AI & Agents", Color(0xFF14B8A6)),
    NodeKind("unitTest", "Unit Tests", "test.unit", "Testing & CI/CD", Color(0xFF4ADE80)),
    NodeKind("integrationTest", "Integration Tests", "test.integration", "Testing & CI/CD", Color(0xFF34D399)),
    NodeKind("ciPipeline", "CI Pipeline", "ci.pipeline", "Testing & CI/CD", Color(0xFFFB923C)),
    NodeKind("deployment", "Deployment / Hosting", "deploy.release", "Testing & CI/CD", Color(0xFF60A5FA)),
    NodeKind("thirdPartyApi", "Third-Party API", "api.external", "External services", Color(0xFF818CF8)),
    NodeKind("webhook", "Webhook", "webhook.trigger", "External services", Color(0xFFE879F9)),
    NodeKind("payment", "Payment Provider", "payment.charge", "External services", Color(0xFFA3E635)),
    NodeKind("notification", "Email / Notifications", "notify.send", "External services", Color(0xFFF87171)),
)

private val KINDS_BY_KEY = NODE_KINDS.associateBy { it.key }

fun nodeKind(key: String?): NodeKind? = key?.let { KINDS_BY_KEY[it] }

val DEFAULT_NODE_COLOR = Color(0xFFA2A4FF)
val TABLE_COLOR = Color(0xFF5EEAD4)

// Sizes match the website's node components and the backend's graph/build.rs.
const val ARCH_WIDTH = 150f
const val ENDPOINT_WIDTH = 170f
const val ARCH_HEIGHT = 56f
const val ER_WIDTH = 240f
const val ER_HEADER = 40f
const val ER_ROW = 24f
const val NOTE_WIDTH = 160f
const val NOTE_HEIGHT = 44f
const val TASK_WIDTH = 190f
const val TASK_HEIGHT = 44f
const val TASKNOTE_WIDTH = 200f

data class Column(val id: String, val name: String, val type: String, val pk: Boolean, val fk: String?)

/** One node, read from its JSON. Positions are relative to the parent, as stored. */
data class MapNode(val raw: JsonObject) {
    val id: String = raw.str("id") ?: ""
    val type: String = raw.str("type") ?: "mindMap"
    val parentId: String? = raw.str("parentId")
    val x: Float = (raw.obj("position")?.num("x") ?: 0.0).toFloat()
    val y: Float = (raw.obj("position")?.num("y") ?: 0.0).toFloat()
    val data: JsonObject = raw.obj("data") ?: JsonObject(emptyMap())
    val kind: NodeKind? = nodeKind(data.str("kind"))
    val deletable: Boolean = raw["deletable"]?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: true

    val label: String = data.str("label")?.takeIf { it.isNotBlank() }
        ?: data.str("title")?.takeIf { it.isNotBlank() }
        ?: data.str("name")?.takeIf { it.isNotBlank() }
        ?: if (type == "section") "Group" else "Untitled"
    val description: String? = data.str("description")

    val columns: List<Column> = (data["columns"] as? JsonArray).orEmpty().mapNotNull { el ->
        val c = el as? JsonObject ?: return@mapNotNull null
        val fk = c.obj("fk")?.let { "${it.str("table")}.${it.str("column")}" }
        Column(c.str("id") ?: "", c.str("name") ?: "", c.str("type") ?: "", (c["pk"] as? JsonPrimitive)?.booleanOrNull == true, fk)
    }
    val collapsed: Boolean = (data["collapsed"] as? JsonPrimitive)?.booleanOrNull == true
    val taskItems: List<String> = (data["skills"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("text") }

    val width: Float = when (type) {
        "section" -> (raw.num("width") ?: raw.obj("style")?.num("width") ?: raw.obj("measured")?.num("width") ?: 400.0).toFloat()
        "architecture" -> if (kind?.key == "apiEndpoint") ENDPOINT_WIDTH else ARCH_WIDTH
        "erTable" -> ER_WIDTH
        "task" -> TASK_WIDTH
        "taskNote" -> TASKNOTE_WIDTH
        else -> NOTE_WIDTH
    }
    val height: Float = when (type) {
        "section" -> (raw.num("height") ?: raw.obj("style")?.num("height") ?: raw.obj("measured")?.num("height") ?: 300.0).toFloat()
        "architecture" -> ARCH_HEIGHT
        "erTable" -> if (collapsed) ER_HEADER else ER_HEADER + ER_ROW * maxOf(columns.size, 1)
        "task" -> TASK_HEIGHT
        "taskNote" -> ER_HEADER + ER_ROW * maxOf(taskItems.size, 1)
        else -> NOTE_HEIGHT
    }

    /** Colour of the node and of connections where they touch it (website colorForNode). */
    val color: Color = when (type) {
        "architecture" -> kind?.color ?: DEFAULT_NODE_COLOR
        "erTable" -> TABLE_COLOR
        "task" -> statusMeta(data.str("status")).color
        else -> DEFAULT_NODE_COLOR
    }

    val subtitle: String? = when (type) {
        "architecture" -> kind?.subtitle
        "erTable" -> "${columns.size} column${if (columns.size == 1) "" else "s"}"
        "task" -> statusMeta(data.str("status")).label + (data.str("assignee")?.let { " · @$it" } ?: "")
        else -> null
    }

    /** DocLoom task id for task nodes (type "task"). */
    val taskId: String? = data.str("taskId")
}

data class MapEdge(val raw: JsonObject) {
    val id: String = raw.str("id") ?: ""
    val source: String = raw.str("source") ?: ""
    val target: String = raw.str("target") ?: ""
    val sourceHandle: String? = raw.str("sourceHandle")
    val targetHandle: String? = raw.str("targetHandle")
    private val data = raw.obj("data")
    val label: String? = data?.str("label")
    val sourceColor: Color? = data?.str("sourceColor")?.let(::parseHex)
    val targetColor: Color? = data?.str("targetColor")?.let(::parseHex)
    /** Task links are drawn by the server from the task's area; they aren't edited here. */
    val managed: Boolean = id.startsWith("task-edge-")
}

/** The map: nodes parents-first, with absolute boxes for drawing and hit testing. */
class MapGraph(val nodes: List<MapNode>, val edges: List<MapEdge>) {
    val byId: Map<String, MapNode> = nodes.associateBy { it.id }

    /** Absolute boxes in map coordinates (parent offsets added). */
    val boxes: Map<String, Rect> = buildMap {
        fun origin(n: MapNode, depth: Int = 0): Offset {
            val parent = n.parentId?.let { byId[it] }
            val p = if (parent != null && depth < 20) origin(parent, depth + 1) else Offset.Zero
            return Offset(p.x + n.x, p.y + n.y)
        }
        for (n in nodes) {
            val o = origin(n)
            put(n.id, Rect(o.x, o.y, o.x + n.width, o.y + n.height))
        }
    }

    /** Groups first, then everything else, so groups draw underneath. */
    val drawOrder: List<MapNode> = nodes.filter { it.type == "section" } + nodes.filter { it.type != "section" }

    /** Bounding box of everything, for "fit to screen". */
    val bounds: Rect? = boxes.values.reduceOrNull { a, b ->
        Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
    }

    /** Topmost node under [point]: small nodes win over the groups around them. */
    fun hit(point: Offset): MapNode? =
        drawOrder.asReversed().firstOrNull { boxes[it.id]?.contains(point) == true }

    fun connectionsOf(id: String): List<MapEdge> = edges.filter { it.source == id || it.target == id }

    companion object {
        fun from(nodes: List<JsonObject>, edges: List<JsonObject>): MapGraph =
            MapGraph(parentsFirst(nodes.map(::MapNode)), edges.map(::MapEdge))
    }
}

/** Parents must come before children (React Flow and the server both expect it). */
fun parentsFirst(nodes: List<MapNode>): List<MapNode> {
    val byId = nodes.associateBy { it.id }
    fun depth(n: MapNode, seen: Int = 0): Int {
        val p = n.parentId?.let { byId[it] } ?: return 0
        return if (seen < 20) 1 + depth(p, seen + 1) else 0
    }
    return nodes.withIndex().sortedWith(compareBy({ depth(it.value) }, { it.index })).map { it.value }
}

/** Where an edge leaves its source and enters its target: right side to left side, or an ER column. */
fun edgeEnds(edge: MapEdge, graph: MapGraph): Pair<Offset, Offset>? {
    val s = graph.byId[edge.source] ?: return null
    val t = graph.byId[edge.target] ?: return null
    val sb = graph.boxes[s.id] ?: return null
    val tb = graph.boxes[t.id] ?: return null
    fun columnY(n: MapNode, box: Rect, handle: String?): Float? {
        if (n.type != "erTable" || n.collapsed || handle == null || !handle.startsWith("col:")) return null
        val name = handle.removePrefix("col:").substringBeforeLast(":")
        val i = n.columns.indexOfFirst { it.name.equals(name, true) || it.id == name }
        return if (i < 0) null else box.top + ER_HEADER + ER_ROW * i + ER_ROW / 2
    }
    val start = Offset(sb.right, columnY(s, sb, edge.sourceHandle) ?: sb.center.y)
    val end = Offset(tb.left, columnY(t, tb, edge.targetHandle) ?: tb.center.y)
    return start to end
}

// ---- building JSON for changes ----

fun newId(): String = UUID.randomUUID().toString()

/** A new architecture component (shape: backend graph/build.rs architecture_node). */
fun newArchitectureNode(kind: NodeKind, label: String, at: Offset): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(newId()))
    put("type", JsonPrimitive("architecture"))
    put("position", position(at))
    put("data", buildJsonObject {
        put("kind", JsonPrimitive(kind.key))
        put("label", JsonPrimitive(label))
    })
}

/** A free note (website "mindMap" node). */
fun newNoteNode(label: String, at: Offset): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(newId()))
    put("type", JsonPrimitive("mindMap"))
    put("position", position(at))
    put("data", buildJsonObject { put("label", JsonPrimitive(label)) })
}

/** A connection (shape: backend graph/build.rs flow_edge). */
fun newFlowEdge(source: MapNode, target: MapNode, label: String?): JsonObject = buildJsonObject {
    val sc = hex(source.color)
    val tc = hex(target.color)
    put("id", JsonPrimitive(newId()))
    put("source", JsonPrimitive(source.id))
    put("target", JsonPrimitive(target.id))
    put("type", JsonPrimitive("flow"))
    put("data", buildJsonObject {
        put("sourceColor", JsonPrimitive(sc))
        put("targetColor", JsonPrimitive(tc))
        if (!label.isNullOrBlank()) put("label", JsonPrimitive(label.trim()))
    })
    put("markerEnd", buildJsonObject {
        put("type", JsonPrimitive("arrowclosed"))
        put("color", JsonPrimitive(tc))
        put("width", JsonPrimitive(18))
        put("height", JsonPrimitive(18))
    })
    when (source.type) {
        "architecture" -> put("sourceHandle", JsonPrimitive("right-source"))
        "erTable" -> put("sourceHandle", JsonPrimitive("table:out"))
    }
    when (target.type) {
        "architecture" -> put("targetHandle", JsonPrimitive("left-target"))
        "erTable" -> put("targetHandle", JsonPrimitive("table:in"))
    }
}

/** Same node JSON with a new relative position. */
fun JsonObject.withPosition(x: Float, y: Float): JsonObject =
    JsonObject(this + ("position" to position(Offset(x, y))))

/** Same node JSON with data fields set (null removes the field). */
fun JsonObject.withData(vararg fields: Pair<String, String?>): JsonObject {
    val data = (this["data"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    for ((k, v) in fields) if (v == null) data.remove(k) else data[k] = JsonPrimitive(v)
    return JsonObject(this + ("data" to JsonObject(data)))
}

/** Fields React Flow keeps for itself, never saved (website persistable()). */
private val UI_ONLY = setOf("selected", "dragging", "measured", "resizing", "positionAbsolute", "internals")

fun JsonObject.persistable(): JsonObject = JsonObject(filterKeys { it !in UI_ONLY })

private fun position(at: Offset) = buildJsonObject {
    put("x", JsonPrimitive(Math.round(at.x).toDouble()))
    put("y", JsonPrimitive(Math.round(at.y).toDouble()))
}

fun hex(c: Color): String = "#%06x".format(c.toArgb() and 0xFFFFFF)

fun parseHex(s: String): Color? = runCatching {
    val h = s.removePrefix("#")
    when (h.length) {
        6 -> Color(0xFF000000 or h.toLong(16))
        8 -> Color(h.substring(6, 8).toLong(16).shl(24) or h.substring(0, 6).toLong(16))
        else -> null
    }
}.getOrNull()

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.intOrNull?.toDouble() }
