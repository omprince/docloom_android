package com.docloom_android.feature.docs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.docloom_android.core.model.DocChapter
import com.docloom_android.core.model.DocPerson
import com.docloom_android.core.ui.ErrorBanner
import com.docloom_android.core.ui.LoadingBox
import com.docloom_android.core.ui.MessageBox
import com.docloom_android.core.ui.MutedText
import com.docloom_android.core.ui.NoticeBanner
import com.docloom_android.core.ui.timeAgo
import com.docloom_android.feature.tasks.statusMeta
import com.docloom_android.ui.theme.ErrorRed
import com.docloom_android.ui.theme.SuccessGreen
import com.docloom_android.ui.theme.WarningAmber
import kotlinx.coroutines.delay

/** An item in the "/", "@" or "#" picker. */
private data class PickItem(
    val id: String,
    val group: String,
    val label: String,
    val hint: String? = null,
    val color: Color? = null,
    val glyph: String? = null,
    val onPick: () -> Unit,
)

private enum class Picker(val title: String) { Create("Create"), Mention("Assign or mention a person"), Link("Link something that exists") }

/** A section: read it, or edit it with the website's "/ @ #" tools as buttons. */
@Composable
fun SectionScreen(
    vm: SectionEditorViewModel,
    docs: DocsState,
    docsVm: DocsViewModel,
    onBack: () -> Unit,
    onChip: (kind: String, value: String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val body by vm.body.collectAsStateWithLifecycle()
    val title by vm.title.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var nodePicker by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    var prompt by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }
    val refs = docs.refs
    val canGithub = docs.github.repoUrl != null && docs.github.connected

    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    LaunchedEffect(state.toast) {
        if (state.toast != null) {
            delay(4000)
            vm.dismissToast()
        }
    }
    val leave = {
        vm.flushBody()
        onBack()
    }

    when {
        state.loading -> LoadingBox()
        state.section == null -> MessageBox("Couldn't open this section", state.loadError, "Back to contents", leave)
        else -> Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Contents") }
                val chapter = docs.chapters.firstOrNull { it.id == state.section?.chapterId }
                Text(chapter?.title ?: "Contents", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                SaveDot(state.saveState)
                TextButton(onClick = {
                    if (editing) vm.flushBody()
                    editing = !editing
                }) { Text(if (editing) "Done" else "Edit") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Section options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Link to a map component") }, onClick = { menu = false; nodePicker = true })
                        DropdownMenuItem(text = { Text("Delete section") }, onClick = { menu = false; confirmDelete = true })
                    }
                }
            }

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.conflict?.let { theirs ->
                    Surface(shape = RoundedCornerShape(10.dp), color = WarningAmber.copy(alpha = 0.12f)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${theirs.updatedByLabel ?: "Someone"} changed this section while you were editing.", color = WarningAmber)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { vm.takeTheirs() }, shape = RoundedCornerShape(50)) { Text("Use their version") }
                                TextButton(onClick = { vm.keepMine() }) { Text("Keep mine") }
                            }
                        }
                    }
                }
                state.toast?.let { NoticeBanner(it) }
                if (state.error != null && state.conflict == null) ErrorBanner(state.error!!)

                val section = state.section!!
                val linked = section.nodeId?.let { id -> refs?.nodes?.firstOrNull { it.id == id } }
                if (editing) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = vm::onTitleChange,
                        label = { Text("Title") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleLarge,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToolButton("/ Create") { picker = Picker.Create }
                        ToolButton("@ Mention") {
                            docsVm.loadPeopleIfNeeded()
                            picker = Picker.Mention
                        }
                        ToolButton("# Link") { picker = Picker.Link }
                    }
                    OutlinedTextField(
                        value = body,
                        onValueChange = vm::onBodyChange,
                        placeholder = { Text("Write here. Use the buttons above to add tasks, people and links. Lines starting with #, - or > become headings, lists and quotes.") },
                        visualTransformation = TokenVisualTransformation(refs, chipColors()),
                        textStyle = MaterialTheme.typography.bodyLarge,
                        minLines = 12,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(section.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    MutedText(
                        listOfNotNull(
                            linked?.let { "◆ On the map: ${it.label}" },
                            "Updated ${timeAgo(section.updatedAt)}" + (section.updatedByLabel?.let { " by $it" } ?: ""),
                        ).joinToString(" · "),
                    )
                    DocBody(body.text, refs, onChip)
                }
            }
        }
    }

    picker?.let { p ->
        PickerSheet(
            title = p.title,
            items = when (p) {
                Picker.Create -> createItems(vm, canGithub, docs, onPrompt = { ph, submit -> prompt = ph to submit })
                Picker.Mention -> mentionItems(vm, docs.people, refs?.tasks?.associate { it.id to it.title } ?: emptyMap())
                Picker.Link -> linkItems(vm, docs, state.section?.id)
            },
            loading = p == Picker.Mention && docs.people.isNullOrEmpty(),
            onDismiss = { picker = null },
        )
    }

    prompt?.let { (placeholder, submit) ->
        var value by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { prompt = null },
            title = { Text(placeholder) },
            text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { submit(value); prompt = null }, enabled = value.isNotBlank()) { Text("Create") } },
            dismissButton = { TextButton(onClick = { prompt = null }) { Text("Cancel") } },
        )
    }

    if (nodePicker) {
        PickerSheet(
            title = "Link this section to a map component",
            items = listOf(PickItem("none", "", "Not on the map", onPick = { vm.setNode(null) })) +
                refs?.nodes.orEmpty().filter { it.type != "section" }.sortedBy { it.label.lowercase() }.map { n ->
                    PickItem(n.id, "Map components", n.label.ifEmpty { "Untitled" }, hint = if (n.type == "erTable") "Table" else null, glyph = "◆") { vm.setNode(n.id) }
                },
            loading = refs == null,
            onDismiss = { nodePicker = false },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${state.section?.title}”?", fontWeight = FontWeight.SemiBold) },
            text = { Text("The section and its text are permanently deleted. Tasks made from it stay on the board.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SaveDot(state: SaveState) {
    val color = when (state) {
        SaveState.Saved -> SuccessGreen
        SaveState.Conflict, SaveState.Error -> ErrorRed
        else -> WarningAmber
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(" ${state.text}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToolButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, shape = RoundedCornerShape(50)) { Text(text, fontFamily = FontFamily.Monospace) }
}

private fun createItems(
    vm: SectionEditorViewModel,
    canGithub: Boolean,
    docs: DocsState,
    onPrompt: (String, (String) -> Unit) -> Unit,
): List<PickItem> {
    fun task(kind: String) = {
        if (vm.currentLineTitle().isEmpty()) onPrompt("Task title") { vm.createTask(kind, it, canGithub) }
        else vm.createTask(kind, null, canGithub)
    }
    val repo = com.docloom_android.feature.tasks.repoName(docs.github.repoUrl)
    return buildList {
        add(PickItem("task", "Create", "Task", "This line becomes a task on the board", glyph = "☐", onPick = task("task")))
        if (canGithub) {
            add(PickItem("issue", "Create", "GitHub issue", "A task opened as an issue in $repo", glyph = "◎", onPick = task("issue")))
            add(PickItem("bug", "Create", "Bug", "An issue labelled bug", glyph = "✱", onPick = task("bug")))
        } else {
            add(PickItem("bug", "Create", "Bug", "A task marked as a bug", glyph = "✱", onPick = task("bug")))
        }
        add(PickItem("section", "Create", "Section", "A new section in this chapter, linked here", glyph = "§") { onPrompt("New section title") { vm.createSection(it) } })
        add(PickItem("h1", "Structure", "Heading", glyph = "H1") { vm.applyPrefix("# ") })
        add(PickItem("h2", "Structure", "Subheading", glyph = "H2") { vm.applyPrefix("## ") })
        add(PickItem("h3", "Structure", "Small heading", glyph = "H3") { vm.applyPrefix("### ") })
        add(PickItem("ul", "Structure", "Bulleted list", glyph = "•") { vm.applyPrefix("- ") })
        add(PickItem("ol", "Structure", "Numbered list", glyph = "1.") { vm.applyPrefix("1. ") })
        add(PickItem("quote", "Structure", "Quote", glyph = "❝") { vm.applyPrefix("> ") })
        add(PickItem("text", "Structure", "Plain text", glyph = "¶") { vm.applyPrefix("") })
        add(PickItem("code", "Structure", "Code block", glyph = "{}") { vm.insertBlock("```\n\n```") })
        add(PickItem("hr", "Structure", "Divider", glyph = "—") { vm.insertBlock("---") })
    }
}

private fun mentionItems(vm: SectionEditorViewModel, people: List<DocPerson>?, taskTitles: Map<String, String>): List<PickItem> =
    people.orEmpty().map { p ->
        PickItem(
            id = "user:${p.login}",
            group = if (p.source == "github") "Repo collaborators" else "Project members",
            label = "@${p.login}",
            hint = p.name?.takeIf { it != p.login },
            glyph = "@",
        ) { vm.mention(p) { taskTitles[it] } }
    }

private fun linkItems(vm: SectionEditorViewModel, docs: DocsState, currentSectionId: String?): List<PickItem> {
    val refs = docs.refs ?: return emptyList()
    return buildList {
        refs.tasks.forEach { t ->
            add(
                PickItem(
                    "task:${t.id}", "Tasks & issues", "${t.number?.let { "#$it " } ?: ""}${t.title}",
                    hint = statusMeta(t.status).label + (t.assignee?.let { " · @$it" } ?: ""),
                    color = statusMeta(t.status).color,
                ) { vm.insertToken("task", t.id) },
            )
        }
        refs.nodes.filter { it.type != "section" }.forEach { n ->
            add(PickItem("node:${n.id}", "Map components", n.label.ifEmpty { "Untitled" }, hint = if (n.type == "erTable") "Table" else null, glyph = "◆") { vm.insertToken("node", n.id) })
        }
        refs.sections.filter { it.id != currentSectionId }.forEach { s ->
            add(PickItem("section:${s.id}", "Doc sections", s.title, hint = docs.chapters.firstOrNull { c: DocChapter -> c.id == s.chapterId }?.title, glyph = "§") { vm.insertToken("section", s.id) })
        }
    }
}

/** Searchable list in a bottom sheet; the phone version of the website's SymbolMenu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerSheet(title: String, items: List<PickItem>, loading: Boolean, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val shown = items.filter { q.isEmpty() || it.label.lowercase().contains(q) || (it.hint?.lowercase()?.contains(q) == true) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            when {
                loading -> MutedText("Loading…")
                shown.isEmpty() -> MutedText("Nothing matches.")
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                var lastGroup: String? = null
                shown.forEach { item ->
                    if (item.group.isNotEmpty() && item.group != lastGroup) {
                        val group = item.group
                        item(key = "g-$group") {
                            Text(group.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                        }
                    }
                    lastGroup = item.group
                    item(key = item.id) {
                        Surface(onClick = { item.onPick(); onDismiss() }, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                                    if (item.color != null) Box(Modifier.size(10.dp).background(item.color, CircleShape))
                                    else Text(item.glyph ?: "", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.secondary)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    item.hint?.let { MutedText(it) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
