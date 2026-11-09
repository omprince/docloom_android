package com.docloom_android.feature.docs

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.docloom_android.core.model.DocRefs

/** A block of the rendered body. */
sealed interface DocBlock {
    data class Heading(val level: Int, val text: String) : DocBlock
    data class Bullet(val marker: String, val text: String) : DocBlock
    data class Quote(val text: String) : DocBlock
    data class Code(val text: String) : DocBlock
    data object Divider : DocBlock
    data class Paragraph(val text: String) : DocBlock
    data object Blank : DocBlock
}

/** Splits a body into blocks by its markdown-ish line prefixes (website editorDom styleLines). */
fun parseDocBlocks(body: String): List<DocBlock> {
    val out = mutableListOf<DocBlock>()
    val lines = body.split("\n")
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.startsWith("```") -> {
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].startsWith("```")) code += lines[i++]
                out += DocBlock.Code(code.joinToString("\n"))
            }
            line.trim() == "---" || line.trim() == "***" -> out += DocBlock.Divider
            line.startsWith("### ") -> out += DocBlock.Heading(3, line.removePrefix("### "))
            line.startsWith("## ") -> out += DocBlock.Heading(2, line.removePrefix("## "))
            line.startsWith("# ") -> out += DocBlock.Heading(1, line.removePrefix("# "))
            line.startsWith("- ") || line.startsWith("* ") -> out += DocBlock.Bullet("•", line.drop(2))
            Regex("""^\d+\. """).containsMatchIn(line) -> {
                val marker = line.substringBefore(". ") + "."
                out += DocBlock.Bullet(marker, line.substringAfter(". "))
            }
            line.startsWith("> ") -> out += DocBlock.Quote(line.removePrefix("> "))
            line.isBlank() -> out += DocBlock.Blank
            else -> out += DocBlock.Paragraph(line)
        }
        i++
    }
    return out
}

/** Read view of a section body: headings, lists, quotes, code and tappable chips. */
@Composable
fun DocBody(body: String, refs: DocRefs?, onChip: (kind: String, value: String) -> Unit) {
    val colors = chipColors()
    val base = MaterialTheme.typography.bodyLarge
    @Composable
    fun rich(text: String, style: TextStyle = base, modifier: Modifier = Modifier) {
        val annotated = buildAnnotatedString {
            appendWithChips(text, refs, colors) { kind, value, draw ->
                withLink(LinkAnnotation.Clickable("$kind:$value") { onChip(kind, value) }) { draw() }
            }
        }
        Text(annotated, style = style, modifier = modifier)
    }

    if (body.isBlank()) {
        Text("Nothing written yet. Tap Edit to start.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in parseDocBlocks(body)) {
            when (block) {
                is DocBlock.Heading -> rich(
                    block.text,
                    when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    }.copy(fontWeight = FontWeight.SemiBold),
                    Modifier.padding(top = 8.dp),
                )
                is DocBlock.Bullet -> Row {
                    Text(block.marker, style = base, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(24.dp))
                    rich(block.text)
                }
                is DocBlock.Quote -> Row {
                    Box(Modifier.width(3.dp).padding(vertical = 2.dp).background(MaterialTheme.colorScheme.primary))
                    rich(block.text, base.copy(fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant), Modifier.padding(start = 10.dp))
                }
                is DocBlock.Code -> Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        block.text,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        softWrap = false,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
                    )
                }
                DocBlock.Divider -> HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                DocBlock.Blank -> Box(Modifier.padding(2.dp))
                is DocBlock.Paragraph -> rich(block.text)
            }
        }
    }
}

@Composable
fun chipColors() = ChipColors(
    background = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
    text = MaterialTheme.colorScheme.secondary,
    missing = MaterialTheme.colorScheme.onSurfaceVariant,
)
