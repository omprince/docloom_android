package com.docloom_android

import com.docloom_android.core.network.ApiJson
import com.docloom_android.feature.studio.CreateAssetRequest
import com.docloom_android.feature.studio.Geom
import com.docloom_android.feature.studio.GridSpec
import com.docloom_android.feature.studio.Layer
import com.docloom_android.feature.studio.cssColor
import com.docloom_android.feature.studio.geomJson
import com.docloom_android.feature.studio.mapLayer
import com.docloom_android.feature.studio.moveGeom
import com.docloom_android.feature.studio.parseDoc
import com.docloom_android.feature.studio.polygonPoints
import com.docloom_android.feature.studio.shapeNode
import com.docloom_android.feature.studio.withTopLayer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioLogicTest {
    // Same shape as the backend's blank_doc for a 2-frame sprite sheet, plus a shape.
    private val raw = ApiJson.parseToJsonElement(
        """{"v":1,"width":64,"height":32,"background":null,"custom":"kept",
        "grid":{"cols":2,"rows":1,"cellW":32,"cellH":32,"gap":0,"padding":0},
        "nodes":[
          {"id":"g1","type":"group","name":"Frame 1","visible":true,"locked":false,"opacity":1,"blend":"normal","frame":0,
           "children":[{"id":"r1","type":"raster","name":"Frame 1 art","visible":true,"locked":false,"opacity":1,"blend":"normal",
             "x":0,"y":0,"w":32,"h":32,"blob":"11111111-1111-1111-1111-111111111111","mask":{"blob":"22222222-2222-2222-2222-222222222222","enabled":true}}]},
          {"id":"s1","type":"shape","name":"Box","visible":true,"locked":false,"opacity":0.5,"blend":"multiply",
           "shape":{"kind":"rect","x":4,"y":4,"w":10,"h":10,"r":2},"fill":"#ff0000","stroke":null,"strokeWidth":0,"rotation":0}
        ]}""",
    ).jsonObject

    @Test
    fun parsesTheDocumentTree() {
        val doc = parseDoc(raw)
        assertEquals(64, doc.width)
        assertEquals(GridSpec(2, 1, 32, 32, 0, 0), doc.grid)
        assertEquals(listOf("s1", "g1", "r1"), doc.panel.map { it.first.id }) // top first
        assertEquals(listOf(0, 0, 1), doc.panel.map { it.second })
        assertEquals(2, doc.blobs.size) // layer pixels and mask
        val shape = doc.find("s1") as Layer.Shape
        assertEquals(Geom.Rect(4f, 4f, 10f, 10f, 2f), shape.geom)
        assertEquals("multiply", shape.blend)
        assertEquals(0, (doc.find("g1") as Layer.Group).frame)
    }

    @Test
    fun cssColours() {
        assertEquals(0xFFFF0000.toInt(), cssColor("#ff0000"))
        assertEquals(0xFF112233.toInt(), cssColor("#123"))
        assertEquals(0x80FF0000.toInt(), cssColor("#ff000080")) // CSS puts alpha last
        assertEquals(0x80000000.toInt() or 0x0A141E, cssColor("rgba(10, 20, 30, 0.5)"))
        assertEquals(0, cssColor("transparent"))
        assertNull(cssColor("not a colour"))
    }

    @Test
    fun polygonAndStarPointsMatchWebsite() {
        val hex = polygonPoints(Geom.Polygon(0f, 0f, 100f, 100f, 6))
        assertEquals(6, hex.size)
        assertEquals(50f, hex[0].first, 0.001f) // first point at the top centre
        assertEquals(0f, hex[0].second, 0.001f)
        val star = polygonPoints(Geom.Star(0f, 0f, 100f, 100f, 5, 0.5f))
        assertEquals(10, star.size)
        assertEquals(25f, star[5].second - 50f, 0.001f) // inner point at half the radius, straight down
    }

    @Test
    fun editsKeepUnknownFields() {
        val added = raw.withTopLayer(shapeNode("Dot", geomJson("ellipse", "x" to 1, "y" to 1, "w" to 4, "h" to 4), "#000000", null, 0f))
        assertEquals(3, (added["nodes"] as JsonArray).size)
        assertEquals("kept", added["custom"]!!.jsonPrimitive.content)
        val removed = added.mapLayer("r1") { null }
        assertTrue(parseDoc(removed).find("r1") == null)
        assertEquals(0, parseDoc(removed).blobs.size) // its pixels and mask went with it
        val renamed = raw.mapLayer("s1") { JsonObject(it + ("name" to kotlinx.serialization.json.JsonPrimitive("Renamed"))) }
        assertEquals("Renamed", parseDoc(renamed).find("s1")!!.name)
    }

    @Test
    fun movingShapesShiftsEveryCoordinate() {
        val moved = moveGeom(geomJson("line", "x1" to 0, "y1" to 0, "x2" to 10, "y2" to 5), 3f, -1f)
        assertEquals(3.0, moved["x1"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(4.0, moved["y2"]!!.jsonPrimitive.content.toDouble(), 0.0)
        val path = ApiJson.parseToJsonElement("""{"kind":"path","closed":false,"points":[[1,2,1,2,1,2]]}""").jsonObject
        val p = moveGeom(path, 10f, 20f)["points"]!!.jsonArray[0].jsonArray.map { it.jsonPrimitive.content.toDouble() }
        assertEquals(listOf(11.0, 22.0, 11.0, 22.0, 11.0, 22.0), p)
    }

    @Test
    fun spriteSheetRequestMatchesBackend() {
        val body = ApiJson.encodeToString(
            CreateAssetRequest.serializer(),
            CreateAssetRequest("Hero", 128, 32, null, null, GridSpec(4, 1, 32, 32, 0, 0), true),
        )
        // gap and padding of 0 are defaults and left out; the backend defaults them to 0 too.
        assertTrue(body.contains(""""grid":{"cols":4,"rows":1,"cellW":32,"cellH":32}"""))
        assertTrue(body.contains(""""frameGroups":true"""))
    }
}
