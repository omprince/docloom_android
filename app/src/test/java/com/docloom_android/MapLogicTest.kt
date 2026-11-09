package com.docloom_android

import androidx.compose.ui.geometry.Offset
import com.docloom_android.core.model.Revision
import com.docloom_android.core.model.RevisionChange
import com.docloom_android.core.network.ApiJson
import com.docloom_android.feature.map.ChangesRequest
import com.docloom_android.feature.map.ChangesResponse
import com.docloom_android.feature.map.FullGraph
import com.docloom_android.feature.map.MapApi
import com.docloom_android.feature.map.MapEdge
import com.docloom_android.feature.map.MapGraph
import com.docloom_android.feature.map.MapLiveStream
import com.docloom_android.feature.map.MapNode
import com.docloom_android.feature.map.MapViewModel
import com.docloom_android.feature.map.NODE_KINDS
import com.docloom_android.feature.map.RevisionFeed
import com.docloom_android.feature.map.edgeEnds
import com.docloom_android.feature.map.hex
import com.docloom_android.feature.map.newFlowEdge
import com.docloom_android.feature.map.parseHex
import com.docloom_android.feature.map.persistable
import com.docloom_android.feature.map.withData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class MapLogicTest {
    private fun json(s: String) = ApiJson.parseToJsonElement(s).jsonObject

    private val section = json("""{"id":"s","type":"section","position":{"x":100,"y":100},"width":600,"height":400,"data":{"label":"Database"}}""")
    private val users = json(
        """{"id":"u","type":"erTable","position":{"x":20,"y":60},"parentId":"s","data":{"label":"users",
        "columns":[{"id":"c1","name":"id","type":"uuid","pk":true},{"id":"c2","name":"org_id","type":"uuid","fk":{"table":"orgs","column":"id"}}]}}""",
    )
    private val api = json("""{"id":"a","type":"architecture","position":{"x":-300,"y":150},"data":{"kind":"backend","label":"API"},"measured":{"width":150,"height":56},"selected":true}""")

    @Test
    fun boxesAddParentOffsetsAndTapsHitTheSmallestBox() {
        val graph = MapGraph.from(listOf(users, section, api), emptyList())
        assertEquals(listOf("s", "a", "u"), graph.nodes.map { it.id }) // by depth: top level, then children
        val box = graph.boxes["u"]!!
        assertEquals(120f, box.left)
        assertEquals(160f, box.top)
        assertEquals(40f + 24f * 2, box.height) // header + 2 rows
        assertEquals("u", graph.hit(Offset(130f, 170f))?.id) // table inside the group
        assertEquals("s", graph.hit(Offset(650f, 450f))?.id) // empty part of the group
        assertNull(graph.hit(Offset(-1000f, 0f)))
        assertEquals(-300f, graph.bounds!!.left)
    }

    @Test
    fun foreignKeyEdgesEndAtTheirColumns() {
        val graph = MapGraph.from(listOf(section, users, api), listOf(json("""{"id":"e","source":"a","target":"u","targetHandle":"col:org_id:in"}""")))
        val (start, end) = edgeEnds(graph.edges[0], graph)!!
        assertEquals(Offset(-150f, 178f), start) // right middle of the API box
        assertEquals(Offset(120f, 160f + 40f + 24f + 12f), end) // left side, second column row
    }

    @Test
    fun newEdgesMatchTheBackendShape() {
        val a = MapNode(api)
        val u = MapNode(users)
        val e = newFlowEdge(a, u, "reads")
        assertEquals("right-source", e["sourceHandle"]!!.jsonPrimitive.content)
        assertEquals("table:in", e["targetHandle"]!!.jsonPrimitive.content)
        assertEquals("#a78bfa", e["data"]!!.jsonObject["sourceColor"]!!.jsonPrimitive.content)
        assertEquals("#5eead4", e["data"]!!.jsonObject["targetColor"]!!.jsonPrimitive.content)
        assertEquals("reads", MapEdge(e).label)
    }

    @Test
    fun editsKeepUnknownFieldsAndDropUiOnes() {
        val renamed = api.withData("label" to "Gateway", "description" to null)
        assertEquals("Gateway", MapNode(renamed).label)
        assertEquals("backend", MapNode(renamed).kind?.key) // other data kept
        val saved = api.persistable()
        assertFalse(saved.containsKey("measured"))
        assertFalse(saved.containsKey("selected"))
        assertTrue(saved.containsKey("position"))
    }

    @Test
    fun colorsRoundTrip() {
        NODE_KINDS.forEach { assertEquals(it.color, parseHex(hex(it.color))) }
    }

    // ---------------- sync ----------------

    private class FakeMapApi : MapApi {
        var graph = FullGraph(version = 3)
        val sent = mutableListOf<ChangesRequest>()
        var feed = RevisionFeed()
        override suspend fun graph(projectId: String) = graph
        override suspend fun changes(projectId: String, body: ChangesRequest): Response<ChangesResponse> {
            sent += body
            return Response.success(ChangesResponse(version = 4))
        }
        override suspend fun since(projectId: String, since: Long, limit: Int) = feed
    }

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun editsGoOutAsOneOrderedBatch() = runTest(dispatcher) {
        val fake = FakeMapApi().apply { graph = FullGraph(3, listOf(section, api), emptyList()) }
        val vm = MapViewModel(fake, MapLiveStream("http://localhost", OkHttpClient()), "p", readOnly = false, focusNodeId = null)
        runCurrent()
        vm.addComponent(NODE_KINDS.first { it.key == "database" }, "Postgres", Offset(0f, 0f))
        val newId = vm.state.value.selectedId!!
        vm.connect("a", newId, null)
        vm.dragBy("a", Offset(10f, 0f))
        vm.dragEnd("a")
        advanceTimeBy(700)
        runCurrent()
        assertEquals(1, fake.sent.size)
        val ops = fake.sent[0].changes.map { it["op"]!!.jsonPrimitive.content }
        assertEquals(listOf("upsert_node", "upsert_node", "upsert_edge"), ops)
        val moved = fake.sent[0].changes.first { it["node"]?.jsonObject?.get("id")?.jsonPrimitive?.content == "a" }
        assertEquals(-290.0, moved["node"]!!.jsonObject["position"]!!.jsonObject["x"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(com.docloom_android.feature.map.SyncStatus.Saved, vm.state.value.status)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun viewersNeverSend() = runTest(dispatcher) {
        val fake = FakeMapApi().apply { graph = FullGraph(3, listOf(api), emptyList()) }
        val vm = MapViewModel(fake, MapLiveStream("http://localhost", OkHttpClient()), "p", readOnly = true, focusNodeId = null)
        runCurrent()
        vm.rename("a", "Nope")
        advanceTimeBy(2000)
        runCurrent()
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun revisionFeedParses() {
        val r = Revision(5, 5, "mcp", "Claude", "Add cache", listOf(RevisionChange("node", "n1", null, JsonObject(mapOf("id" to JsonPrimitive("n1"))))), "other", "2026-01-01T00:00:00Z")
        val text = ApiJson.encodeToString(RevisionFeed.serializer(), RevisionFeed(5, listOf(r)))
        assertEquals("Claude", ApiJson.decodeFromString(RevisionFeed.serializer(), text).revisions[0].actorLabel)
    }
}
