package com.docloom_android.feature.studio

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.docloom_android.core.model.Project
import com.docloom_android.core.ui.ErrorBanner
import com.docloom_android.core.ui.LoadingBox
import com.docloom_android.core.ui.MutedText
import com.docloom_android.core.ui.timeAgo

/** Website /dashboard/studio: every image, or one project's library with folders. */
@Composable
fun StudioScreen(
    vm: StudioViewModel,
    images: StudioImages,
    projects: List<Project>,
    onOpenAsset: (projectId: String, assetId: String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var newImage by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }
    var moving by remember { mutableStateOf<AssetSummary?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(state.created) {
        state.created?.let { (pid, aid) ->
            vm.createdHandled()
            newImage = false
            onOpenAsset(pid, aid)
        }
    }

    val pid = state.projectId
    val editable = pid != null && projects.firstOrNull { it.id == pid }?.role != "viewer"
    Box(Modifier.fillMaxSize()) {
        if (state.loading) {
            LoadingBox()
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MutedText("Images and sprite sheets for your projects. Open one to view it, play its frames, or add shapes and text.")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = pid == null, onClick = { vm.openProject(null) }, label = { Text("All projects") })
                            projects.forEach { p -> FilterChip(selected = pid == p.id, onClick = { vm.openProject(p.id) }, label = { Text(p.name) }) }
                        }
                        state.error?.let { ErrorBanner(it) }
                        if (pid != null) Breadcrumb(state, vm, editable) { title, submit -> dialog = title to submit }
                    }
                }
                if (pid == null) {
                    if (state.all.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { MutedText("No images yet. Tap New image to start one.") }
                    items(state.all, key = { it.id }) { a ->
                        AssetTile(a, a.projectId ?: "", images, subtitle = a.projectName) { onOpenAsset(a.projectId ?: return@AssetTile, a.id) }
                    }
                } else {
                    val folders = state.folders.filter { it.parentId == state.folderId }
                    items(folders, key = { "f-" + it.id }) { f ->
                        FolderTile(
                            f, editable,
                            onOpen = { vm.openFolder(f.id) },
                            onRename = { dialog = "Rename folder" to { name -> vm.renameFolder(f.id, name) } },
                            onDelete = { confirm = "Delete the folder “${f.name}” and every image in it?" to { vm.deleteFolder(f.id) } },
                        )
                    }
                    val assets = state.assets.filter { it.folderId == state.folderId }
                    if (folders.isEmpty() && assets.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { MutedText("This folder is empty.") }
                    items(assets, key = { it.id }) { a ->
                        AssetTile(
                            a, pid, images, subtitle = null,
                            menu = if (!editable) null else listOf(
                                "Rename" to { dialog = "Rename image" to { name -> vm.renameAsset(a.id, name) } },
                                "Move to folder" to { moving = a },
                                "Duplicate" to { vm.duplicate(a.id) },
                                "Delete" to { confirm = "Delete “${a.name}”? This can't be undone." to { vm.deleteAsset(a.id) } },
                            ),
                        ) { onOpenAsset(pid, a.id) }
                    }
                }
            }
        }
        if (projects.any { it.role != "viewer" }) {
            ExtendedFloatingActionButton(
                onClick = { newImage = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("New image") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }

    if (newImage) {
        NewImageDialog(
            projects = projects.filter { it.role != "viewer" },
            initialProject = pid,
            busy = state.busy,
            error = state.error,
            onDismiss = { newImage = false },
        ) { projectId, request -> vm.create(projectId, request.copy(folderId = if (projectId == pid) state.folderId else null)) }
    }
    dialog?.let { (title, submit) ->
        var value by remember(title) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(title) },
            text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { submit(value); dialog = null }, enabled = value.isNotBlank()) { Text("OK") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
    }
    confirm?.let { (text, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
    moving?.let { asset ->
        AlertDialog(
            onDismissRequest = { moving = null },
            title = { Text("Move “${asset.name}”") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(onClick = { vm.moveAsset(asset.id, null); moving = null }) { Text("Top level", modifier = Modifier.fillMaxWidth()) }
                    state.folders.forEach { f -> TextButton(onClick = { vm.moveAsset(asset.id, f.id); moving = null }) { Text(folderPath(state.folders, f.id), modifier = Modifier.fillMaxWidth()) } }
                }
            },
            confirmButton = { TextButton(onClick = { moving = null }) { Text("Cancel") } },
        )
    }
}

private fun folderPath(folders: List<AssetFolder>, id: String?): String {
    val names = mutableListOf<String>()
    var cur = id
    var guard = 0
    while (cur != null && guard++ < 20) {
        val f = folders.firstOrNull { it.id == cur } ?: break
        names += f.name
        cur = f.parentId
    }
    return names.asReversed().joinToString(" / ")
}

@Composable
private fun Breadcrumb(state: StudioState, vm: StudioViewModel, editable: Boolean, onDialog: (String, (String) -> Unit) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { vm.openFolder(null) }) { Text("Library") }
        if (state.folderId != null) {
            val parent = state.folders.firstOrNull { it.id == state.folderId }?.parentId
            Text("/ ${folderPath(state.folders, state.folderId)}", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.openFolder(parent) }) { Text("Up") }
        } else {
            Box(Modifier.weight(1f))
        }
        if (editable) TextButton(onClick = { onDialog("New folder") { vm.createFolder(it) } }) { Text("+ Folder") }
    }
}

@Composable
private fun FolderTile(f: AssetFolder, editable: Boolean, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(onClick = onOpen, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.secondary)
            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
            if (editable) Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Folder options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Delete with contents") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun AssetTile(
    a: AssetSummary,
    projectId: String,
    images: StudioImages,
    subtitle: String?,
    menu: List<Pair<String, () -> Unit>>? = null,
    onOpen: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val path = images.previewPath(projectId, a)
    val bitmap by produceState<Bitmap?>(images.cached(path), path, a.hasPreview) {
        if (a.hasPreview && value == null) value = images.load(path)
    }
    Surface(onClick = onOpen, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp), contentAlignment = Alignment.Center) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bmp.asImageBitmap(), contentDescription = a.name, contentScale = ContentScale.Fit,
                        // Pixel art stays crisp.
                        filterQuality = if (maxOf(a.width, a.height) <= 256) FilterQuality.None else FilterQuality.Medium,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    MutedText(if (a.hasPreview) "…" else "No preview yet")
                }
            }
            Row(Modifier.padding(start = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(a.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    MutedText(
                        (subtitle?.let { "$it · " } ?: "") + "${a.width}×${a.height}" + (if (a.isSpriteSheet) " · sprites" else "") +
                            " · ${timeAgo(a.updatedAt)}",
                    )
                }
                if (menu != null) Box {
                    IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "Image options") }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        menu.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() }) }
                    }
                }
            }
        }
    }
}

private val SIZE_PRESETS = listOf(
    Triple("Icon", 256, 256), Triple("Square", 1024, 1024), Triple("Pixel art", 64, 64),
    Triple("HD", 1920, 1080), Triple("Portrait", 1080, 1920), Triple("Texture", 2048, 2048),
)
private val FRAME_PRESETS = listOf(16, 32, 64, 128, 256)
private val BACKGROUNDS = listOf(null to "Transparent", "#ffffff" to "White", "#000000" to "Black")

/** Website NewImageForm: an image or a sprite sheet in a project. */
@Composable
private fun NewImageDialog(
    projects: List<Project>,
    initialProject: String?,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onCreate: (String, CreateAssetRequest) -> Unit,
) {
    var projectId by remember { mutableStateOf(initialProject?.takeIf { id -> projects.any { it.id == id } } ?: projects.firstOrNull()?.id) }
    var name by remember { mutableStateOf("") }
    var sprite by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(SIZE_PRESETS[0]) }
    var cell by remember { mutableStateOf(32) }
    var cols by remember { mutableStateOf("4") }
    var rows by remember { mutableStateOf("1") }
    var background by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New image") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (projects.isEmpty()) MutedText("Create a project first: images live in projects.")
                Text("Project", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    projects.forEach { p -> FilterChip(selected = projectId == p.id, onClick = { projectId = p.id }, label = { Text(p.name) }) }
                }
                OutlinedTextField(value = name, onValueChange = { if (it.length <= 100) name = it }, label = { Text("Name") }, placeholder = { Text(if (sprite) "Hero walk" else "Logo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sprite sheet", modifier = Modifier.weight(1f))
                    Switch(checked = sprite, onCheckedChange = { sprite = it })
                }
                if (sprite) {
                    Text("Frame size", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FRAME_PRESETS.forEach { s -> FilterChip(selected = cell == s, onClick = { cell = s }, label = { Text("${s}px") }) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = cols, onValueChange = { cols = it.filter(Char::isDigit).take(3) }, label = { Text("Columns") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = rows, onValueChange = { rows = it.filter(Char::isDigit).take(3) }, label = { Text("Rows") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                } else {
                    Text("Size", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SIZE_PRESETS.forEach { p -> FilterChip(selected = size == p, onClick = { size = p }, label = { Text("${p.first} ${p.second}×${p.third}") }) }
                    }
                }
                Text("Background", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BACKGROUNDS.forEach { (value, label) -> FilterChip(selected = background == value, onClick = { background = value }, label = { Text(label) }) }
                }
                error?.let { ErrorBanner(it) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val pid = projectId ?: return@TextButton
                    val c = cols.toIntOrNull()?.coerceIn(1, 256) ?: 1
                    val r = rows.toIntOrNull()?.coerceIn(1, 256) ?: 1
                    val grid = if (sprite) GridSpec(c, r, cell, cell, 0, 0) else null
                    onCreate(
                        pid,
                        CreateAssetRequest(
                            name = name.trim().ifEmpty { if (sprite) "Sprite sheet" else "Untitled" },
                            width = if (sprite) c * cell else size.second,
                            height = if (sprite) r * cell else size.third,
                            folderId = null,
                            background = background,
                            grid = grid,
                            frameGroups = sprite,
                        ),
                    )
                },
                enabled = !busy && projectId != null,
            ) { Text(if (busy) "Creating…" else "Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
