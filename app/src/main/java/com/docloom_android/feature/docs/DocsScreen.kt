package com.docloom_android.feature.docs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.docloom_android.core.model.DocChapter
import com.docloom_android.core.ui.GlassCard
import com.docloom_android.core.ui.LoadingBox
import com.docloom_android.core.ui.MessageBox
import com.docloom_android.core.ui.MutedText
import com.docloom_android.core.ui.NoticeBanner
import com.docloom_android.core.ui.timeAgo
import com.docloom_android.feature.tasks.repoName

/**
 * The Docs tab: table of contents, or the open section. [section] builds
 * the open section's screen (its ViewModel is keyed by section id).
 */
@Composable
fun DocsScreen(vm: DocsViewModel, section: @Composable (sectionId: String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val open = state.openSectionId
    when {
        open != null -> section(open)
        state.loading -> LoadingBox()
        state.loadError != null -> MessageBox("Couldn't load the docs", state.loadError, "Try again", { vm.refreshToc() })
        else -> Contents(state, vm)
    }
}

private sealed interface ChapterDialog {
    data object New : ChapterDialog
    data class Rename(val chapter: DocChapter) : ChapterDialog
    data class AddSection(val chapter: DocChapter) : ChapterDialog
    data class Delete(val chapter: DocChapter) : ChapterDialog
}

@Composable
private fun Contents(state: DocsState, vm: DocsViewModel) {
    var dialog by remember { mutableStateOf<ChapterDialog?>(null) }
    val busy = state.busy != null
    val canImport = state.github.repoUrl != null && state.github.connected

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { MutedText("Living documentation next to the map: sections link to components, tasks and each other.") }
        state.notice?.let { notice ->
            item {
                Box {
                    NoticeBanner(notice)
                    IconButton(onClick = { vm.dismissNotice() }, modifier = Modifier.align(Alignment.TopEnd)) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss")
                    }
                }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { dialog = ChapterDialog.New }, enabled = !busy, shape = RoundedCornerShape(50)) { Text("New chapter") }
                OutlinedButton(onClick = { vm.generate() }, enabled = !busy, shape = RoundedCornerShape(50)) {
                    Text(if (state.busy == "generate") "Writing…" else "Generate from the map")
                }
                if (canImport) {
                    OutlinedButton(onClick = { vm.importGithub() }, enabled = !busy, shape = RoundedCornerShape(50)) {
                        Text(if (state.busy == "import") "Reading GitHub…" else "Import from ${repoName(state.github.repoUrl)}")
                    }
                }
            }
        }

        if (state.chapters.isEmpty()) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Start the docs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        MutedText("Start from a blank page, write one section per component on the map, or mirror your GitHub repo.")
                        Button(onClick = { vm.startBlank() }, enabled = !busy, shape = RoundedCornerShape(50)) {
                            Text(if (state.busy == "blank") "Creating…" else "Start with a blank page")
                        }
                    }
                }
            }
        }

        items(state.chapters, key = { it.id }) { chapter ->
            ChapterCard(
                chapter,
                onOpen = { vm.openSection(it) },
                onAddSection = { dialog = ChapterDialog.AddSection(chapter) },
                onRename = { dialog = ChapterDialog.Rename(chapter) },
                onDelete = { dialog = ChapterDialog.Delete(chapter) },
            )
        }
    }

    when (val d = dialog) {
        null -> Unit
        ChapterDialog.New -> NewChapterDialog(canRepo = state.github.connected, onDismiss = { dialog = null }) { value, fromRepo ->
            vm.createChapter(value, fromRepo)
            dialog = null
        }
        is ChapterDialog.Rename -> TextDialog("Rename chapter", d.chapter.title, "Rename", { dialog = null }) {
            vm.renameChapter(d.chapter.id, it)
            dialog = null
        }
        is ChapterDialog.AddSection -> TextDialog("New section in ${d.chapter.title}", "", "Create", { dialog = null }) {
            vm.addSection(d.chapter.id, it)
            dialog = null
        }
        is ChapterDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete “${d.chapter.title}”?", fontWeight = FontWeight.SemiBold) },
            text = { Text("The chapter and its ${d.chapter.sections.size} section(s) are permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = { vm.deleteChapter(d.chapter.id); dialog = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ChapterCard(
    chapter: DocChapter,
    onOpen: (String) -> Unit,
    onAddSection: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(chapter.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    chapter.description?.let { MutedText(it) }
                    chapter.githubRepo?.let { MutedText("⌂ Mirrors $it") }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Chapter options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("New section") }, onClick = { menu = false; onAddSection() })
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text("Delete chapter") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            if (chapter.sections.isEmpty()) {
                TextButton(onClick = onAddSection, modifier = Modifier.padding(horizontal = 8.dp)) { Text("+ Add the first section") }
            }
            chapter.sections.sortedBy { it.position }.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Surface(onClick = { onOpen(s.id) }, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("§  ", color = MaterialTheme.colorScheme.secondary)
                        Column(Modifier.weight(1f)) {
                            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            MutedText("Updated ${timeAgo(s.updatedAt)}" + (s.updatedByLabel?.let { " by $it" } ?: "") + if (s.nodeId != null) " · ◆ on the map" else "")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewChapterDialog(canRepo: Boolean, onDismiss: () -> Unit, onSubmit: (String, Boolean) -> Unit) {
    var fromRepo by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New chapter") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canRepo) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !fromRepo, onClick = { fromRepo = false }, label = { Text("Blank") })
                        FilterChip(selected = fromRepo, onClick = { fromRepo = true }, label = { Text("Mirror a GitHub repo") })
                    }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = { Text(if (fromRepo) "owner/repo or GitHub URL" else "Chapter title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(value, fromRepo) }, enabled = value.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TextDialog(title: String, initial: String, action: String, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onSubmit(value) }, enabled = value.isNotBlank()) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
