package com.docloom_android

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.docloom_android.core.model.DocRefs
import com.docloom_android.core.model.RefTask
import com.docloom_android.core.model.RevisionChange
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.docloom_android.feature.docs.ChipColors
import com.docloom_android.feature.docs.DocBlock
import com.docloom_android.feature.docs.TokenVisualTransformation
import com.docloom_android.feature.docs.chipView
import com.docloom_android.feature.docs.currentLinePlainText
import com.docloom_android.feature.docs.currentLineTokens
import com.docloom_android.feature.docs.insertAtCursor
import com.docloom_android.feature.docs.parseDocBlocks
import com.docloom_android.feature.docs.replaceCurrentLineContent
import com.docloom_android.feature.docs.snapTokenEdit
import com.docloom_android.feature.docs.withLinePrefix
import com.docloom_android.feature.history.entityLabel
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocLogicTest {
    private val token = "{{task:t1}}"

    @Test
    fun backspaceAfterChipDeletesWholeToken() {
        val text = "Fix $token now"
        val old = TextFieldValue(text, TextRange(4 + token.length))
        // Backspace removes the last "}" of the token.
        val broken = TextFieldValue(text.removeRange(4 + token.length - 1, 4 + token.length), TextRange(4 + token.length - 1))
        val fixed = snapTokenEdit(old, broken)
        assertEquals("Fix  now", fixed.text)
        assertEquals(4, fixed.selection.start)
        // Ordinary typing is untouched.
        val typed = TextFieldValue("Fix $token now!", TextRange(text.length + 1))
        assertEquals(typed, snapTokenEdit(old, typed))
    }

    @Test
    fun lineHelpers() {
        val v = TextFieldValue("Intro\n- Add login $token\nEnd", TextRange(10))
        assertEquals("Add login", v.currentLinePlainText())
        assertEquals(listOf("t1"), v.currentLineTokens("task"))
        assertEquals("Intro\n## Add login $token\nEnd", v.withLinePrefix("## ").text)
        assertEquals("Intro\n- {{task:t2}}\nEnd", v.replaceCurrentLineContent("{{task:t2}}").text)
        val inserted = TextFieldValue("ab", TextRange(1)).insertAtCursor("{{user:jane}}")
        assertEquals("a{{user:jane}}b", inserted.text)
        assertEquals(14, inserted.selection.start)
    }

    @Test
    fun parsesBodyLikeTheWebsite() {
        val blocks = parseDocBlocks("# Title\n- one\n1. first\n> note\n```\ncode\n```\n---\n\nplain")
        assertEquals(
            listOf(
                DocBlock.Heading(1, "Title"),
                DocBlock.Bullet("•", "one"),
                DocBlock.Bullet("1.", "first"),
                DocBlock.Quote("note"),
                DocBlock.Code("code"),
                DocBlock.Divider,
                DocBlock.Blank,
                DocBlock.Paragraph("plain"),
            ),
            blocks,
        )
    }

    @Test
    fun chipsReadAsNames() {
        val refs = DocRefs(tasks = listOf(RefTask("t1", "Add login", status = "done", number = 12)))
        assertEquals("#12 Add login", chipView("task", "t1", refs).label)
        assertTrue(chipView("task", "gone", refs).missing)
        assertEquals("@jane", chipView("user", "jane", null).label)
    }

    @Test
    fun caretNeverLandsInsideAChip() {
        val colors = ChipColors(Color.Gray, Color.White, Color.DarkGray)
        val text = "a $token b"
        val t = TokenVisualTransformation(null, colors).filter(AnnotatedString(text))
        val map = t.offsetMapping
        // Every original offset maps into the shown text, and back into the original.
        for (o in 0..text.length) {
            val shown = map.originalToTransformed(o)
            assertTrue(shown in 0..t.text.length)
            assertTrue(map.transformedToOriginal(shown) in 0..text.length)
        }
        for (s in 0..t.text.length) {
            val back = map.transformedToOriginal(s)
            // Never strictly inside the token.
            assertTrue(back <= 2 || back >= 2 + token.length)
        }
        assertEquals(text.length, map.transformedToOriginal(t.text.length))
    }

    @Test
    fun historyLabels() {
        val node = buildJsonObject { put("data", buildJsonObject { put("label", JsonPrimitive("Payments")) }) }
        assertEquals("Payments", entityLabel(RevisionChange("node", before = null, after = node)))
        assertEquals("connection", entityLabel(RevisionChange("edge", before = buildJsonObject {}, after = null)))
    }
}
