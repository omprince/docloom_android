package com.docloom_android.feature.docs

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.docloom_android.core.model.DocRefs
import com.docloom_android.feature.tasks.statusMeta

// Section bodies are plain text with inline references stored as
// `{{kind:value}}` (website components/docs/editorDom.ts). They show as
// named chips so they read well but survive renames.

val TOKEN_KINDS = listOf("task", "node", "section", "user", "branch", "repo")

val TOKEN_RE = Regex("""\{\{(task|node|section|user|branch|repo):([^{}\n]+?)\}\}""")

fun tokenText(kind: String, value: String) = "{{$kind:$value}}"

data class ChipView(val label: String, val color: Color?, val glyph: String?, val missing: Boolean)

/** How a reference reads; port of chipView() in editorDom.ts. */
fun chipView(kind: String, value: String, refs: DocRefs?): ChipView = when (kind) {
    "task" -> {
        val t = refs?.tasks?.firstOrNull { it.id == value }
        if (t == null) {
            ChipView(if (refs != null) "deleted task" else "task", null, "☐", refs != null)
        } else {
            ChipView("${t.number?.let { "#$it " } ?: ""}${t.title}", statusMeta(t.status).color, null, false)
        }
    }
    "node" -> {
        val n = refs?.nodes?.firstOrNull { it.id == value }
        if (n == null) ChipView(if (refs != null) "removed component" else value, null, "◆", refs != null)
        else ChipView(n.label.ifEmpty { "Untitled" }, null, "◆", false)
    }
    "section" -> {
        val s = refs?.sections?.firstOrNull { it.id == value }
        if (s == null) ChipView(if (refs != null) "deleted section" else "section", null, "§", refs != null)
        else ChipView(s.title, null, "§", false)
    }
    "user" -> ChipView("@$value", null, null, false)
    "branch" -> ChipView(value, null, "⑂", false)
    "repo" -> ChipView(value, null, "⌂", false)
    else -> ChipView(value, null, null, false)
}

/** The text a chip shows in place of its token. */
fun chipDisplay(view: ChipView): String = when {
    view.color != null -> "● ${view.label}"
    view.glyph != null -> "${view.glyph} ${view.label}"
    else -> view.label
}

data class ChipColors(val background: Color, val text: Color, val missing: Color)

/** Appends [line] with its tokens drawn as chips. [onToken] can wrap each chip (e.g. in a link). */
fun AnnotatedString.Builder.appendWithChips(
    line: String,
    refs: DocRefs?,
    colors: ChipColors,
    onToken: (AnnotatedString.Builder.(kind: String, value: String, draw: AnnotatedString.Builder.() -> Unit) -> Unit)? = null,
) {
    var last = 0
    for (m in TOKEN_RE.findAll(line)) {
        if (m.range.first > last) append(line.substring(last, m.range.first))
        val kind = m.groupValues[1]
        val value = m.groupValues[2]
        val draw: AnnotatedString.Builder.() -> Unit = { appendChip(chipView(kind, value, refs), colors) }
        if (onToken != null) onToken(kind, value, draw) else draw()
        last = m.range.last + 1
    }
    if (last < line.length) append(line.substring(last))
}

private fun AnnotatedString.Builder.appendChip(view: ChipView, colors: ChipColors) {
    withStyle(SpanStyle(background = colors.background, color = if (view.missing) colors.missing else colors.text, fontWeight = FontWeight.Medium)) {
        if (view.color != null) {
            withStyle(SpanStyle(color = view.color)) { append("●") }
            append(" ${view.label}")
        } else {
            append(chipDisplay(view))
        }
    }
}

/**
 * Shows tokens as chips inside the editor's text field while the stored
 * text keeps `{{kind:value}}`. The caret never lands inside a chip.
 */
class TokenVisualTransformation(private val refs: DocRefs?, private val colors: ChipColors) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val original = text.text
        // (origStart, origEnd, transStart, transEnd) for each token.
        val spans = mutableListOf<IntArray>()
        val out = buildAnnotatedString {
            var last = 0
            for (m in TOKEN_RE.findAll(original)) {
                if (m.range.first > last) append(original.substring(last, m.range.first))
                val start = length
                appendChip(chipView(m.groupValues[1], m.groupValues[2], refs), colors)
                spans += intArrayOf(m.range.first, m.range.last + 1, start, length)
                last = m.range.last + 1
            }
            if (last < original.length) append(original.substring(last))
        }
        return TransformedText(out, TokenOffsetMapping(spans, original.length, out.length))
    }
}

internal class TokenOffsetMapping(
    private val spans: List<IntArray>,
    private val originalLength: Int,
    private val transformedLength: Int,
) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int {
        var delta = 0
        for ((os, oe, ts, te) in spans.map { it.toList() }) {
            if (offset <= os) break
            if (offset < oe) return te // inside a token: after the chip
            delta = te - oe
        }
        return (offset + delta).coerceIn(0, transformedLength)
    }

    override fun transformedToOriginal(offset: Int): Int {
        var delta = 0
        for ((os, oe, ts, te) in spans.map { it.toList() }) {
            if (offset <= ts) break
            if (offset < te) return oe // inside a chip: after the token
            delta = oe - te
        }
        return (offset + delta).coerceIn(0, originalLength)
    }
}

/**
 * When an edit deletes part of a token (e.g. backspace after a chip), deletes
 * the whole token instead, so no half `{{task:…` is left behind.
 */
fun snapTokenEdit(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    val a = old.text
    val b = new.text
    if (b.length >= a.length) return new
    var start = 0
    while (start < b.length && a[start] == b[start]) start++
    var endA = a.length
    var endB = b.length
    while (endB > start && a[endA - 1] == b[endB - 1]) { endA--; endB-- }
    if (endB != start) return new // a replacement, not a pure deletion
    // Deleted range in the old text: [start, endA). Grow it to whole tokens it touches.
    var from = start
    var to = endA
    for (m in TOKEN_RE.findAll(a)) {
        val ts = m.range.first
        val te = m.range.last + 1
        if (ts < to && te > from) {
            from = minOf(from, ts)
            to = maxOf(to, te)
        }
    }
    if (from == start && to == endA) return new
    val text = a.removeRange(from, to)
    return TextFieldValue(text, TextRange(from))
}

/** Inserts [insert] at the selection, replacing any selected text. */
fun TextFieldValue.insertAtCursor(insert: String): TextFieldValue {
    val s = selection.min
    val e = selection.max
    val t = text.substring(0, s) + insert + text.substring(e)
    return TextFieldValue(t, TextRange(s + insert.length))
}

/** Start and end of the line holding the cursor. */
fun TextFieldValue.currentLineRange(): IntRange {
    val c = selection.min.coerceIn(0, text.length)
    val start = text.lastIndexOf('\n', c - 1).let { if (it < 0) 0 else it + 1 }
    val end = text.indexOf('\n', c).let { if (it < 0) text.length else it }
    return start until end
}

/** Markdown-ish line prefixes the editor understands (editorDom LINE_PREFIXES + numbered). */
private val PREFIX_RE = Regex("""^(### |## |# |- |\* |> |\d+\. )""")

fun linePrefix(line: String): String = PREFIX_RE.find(line)?.value ?: ""

/** Puts [prefix] on the current line, replacing the one it had. */
fun TextFieldValue.withLinePrefix(prefix: String): TextFieldValue {
    val range = currentLineRange()
    val line = text.substring(range.first, range.last + 1)
    val rest = line.removePrefix(linePrefix(line))
    val newLine = prefix + rest
    val t = text.substring(0, range.first) + newLine + text.substring(range.last + 1)
    return TextFieldValue(t, TextRange(range.first + newLine.length))
}

/** Text of the current line without its prefix and tokens, for a task title. */
fun TextFieldValue.currentLinePlainText(): String {
    val range = currentLineRange()
    val line = text.substring(range.first, range.last + 1)
    return TOKEN_RE.replace(line.removePrefix(linePrefix(line)), "").trim()
}

/** Ids of tokens of [kind] on the current line. */
fun TextFieldValue.currentLineTokens(kind: String): List<String> {
    val range = currentLineRange()
    val line = text.substring(range.first, range.last + 1)
    return TOKEN_RE.findAll(line).filter { it.groupValues[1] == kind }.map { it.groupValues[2] }.toList()
}

/** Replaces the current line's text (after its prefix) with [content]. */
fun TextFieldValue.replaceCurrentLineContent(content: String): TextFieldValue {
    val range = currentLineRange()
    val line = text.substring(range.first, range.last + 1)
    val newLine = linePrefix(line) + content
    val t = text.substring(0, range.first) + newLine + text.substring(range.last + 1)
    return TextFieldValue(t, TextRange(range.first + newLine.length))
}
