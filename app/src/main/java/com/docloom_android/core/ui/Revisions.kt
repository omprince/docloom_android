package com.docloom_android.core.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.docloom_android.core.model.Revision

// Port of the website's lib/revisions.ts.

/** "+3 nodes · 2 nodes changed · −1 connection" */
fun summarize(revision: Revision): String {
    val counts = mutableMapOf<String, IntArray>() // entity -> [added, changed, removed]
    for (c in revision.changes) {
        val kind = when {
            c.before == null -> 0
            c.after == null -> 2
            else -> 1
        }
        counts.getOrPut(c.entity) { IntArray(3) }[kind] += 1
    }
    fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
    val parts = mutableListOf<String>()
    for ((entity, word) in listOf("node" to "node", "edge" to "connection")) {
        val (added, changed, removed) = counts[entity] ?: continue
        if (added > 0) parts += "+${plural(added, word)}"
        if (changed > 0) parts += "${plural(changed, word)} changed"
        if (removed > 0) parts += "−${plural(removed, word)}"
    }
    return parts.joinToString(" · ").ifEmpty { "No changes" }
}

private operator fun IntArray.component1() = this[0]
private operator fun IntArray.component2() = this[1]
private operator fun IntArray.component3() = this[2]

private data class ActorStyle(val label: String, val color: Color?)

private fun actorStyle(actorType: String): ActorStyle = when (actorType) {
    "user" -> ActorStyle("You", null)
    "mcp" -> ActorStyle("AI · MCP", Color(0xFF34D399))
    "scan" -> ActorStyle("Scan", Color(0xFF38BDF8))
    "github" -> ActorStyle("GitHub", Color(0xFF9CA3AF))
    else -> ActorStyle("System", Color(0xFF9CA3AF))
}

/** Small coloured pill naming who made a change. */
@Composable
fun ActorChip(actorType: String) {
    val style = actorStyle(actorType)
    val color = style.color ?: MaterialTheme.colorScheme.secondary
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.15f)) {
        Text(
            style.label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
