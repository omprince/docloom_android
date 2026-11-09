package com.docloom_android.feature.project

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.docloom_android.core.ui.LoadingBox
import com.docloom_android.core.ui.MessageBox
import com.docloom_android.ui.theme.WarningAmber

private enum class ProjectTab(val label: String, val comingIn: String?) {
    Overview("Overview", null),
    Map("Map", null),
    Docs("Docs", null),
    History("History", null),
    Errors("Errors", null),
    Tasks("Tasks", null),
}

/** A project, with the website's project sidebar turned into tabs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    vm: ProjectViewModel,
    onBack: () -> Unit,
    initialTab: String? = null,
    /** The Members card on Overview. */
    membersPanel: @Composable () -> Unit = {},
    /** Content of every tab but Overview, by tab name ("Docs", "Tasks"…); [openTab] switches tabs. */
    tabContent: @Composable (tab: String, openTab: (String) -> Unit) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable {
        mutableIntStateOf(ProjectTab.entries.indexOfFirst { it.name == initialTab }.coerceAtLeast(0))
    }
    val project = state.project

    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f, fill = false)) {
                                Text(project?.name ?: "Project", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                project?.orgName?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (project?.role == "viewer") {
                                Spacer(Modifier.width(8.dp))
                                Surface(shape = RoundedCornerShape(50), color = WarningAmber.copy(alpha = 0.15f)) {
                                    Text(
                                        "View only",
                                        color = WarningAmber,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "All projects") }
                    },
                )
                PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                    ProjectTab.entries.forEachIndexed { index, t ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            text = {
                                val count = when (t) {
                                    ProjectTab.Errors -> project?.openErrorCount ?: 0
                                    ProjectTab.Tasks -> project?.openTaskCount ?: 0
                                    else -> 0
                                }
                                if (count > 0) {
                                    BadgedBox(badge = {
                                        Badge(
                                            containerColor = if (t == ProjectTab.Errors) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceContainerHigh,
                                        ) { Text("$count") }
                                    }) { Text(t.label, modifier = Modifier.padding(end = 6.dp)) }
                                } else {
                                    Text(t.label)
                                }
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> LoadingBox()
                project == null -> MessageBox("Couldn't open this project", state.loadError, "Try again", { vm.load() })
                else -> {
                    val current = ProjectTab.entries[tab]
                    when (current) {
                        ProjectTab.Overview -> ProjectOverview(state, vm, onOpenTab = { name -> tab = ProjectTab.valueOf(name).ordinal }, membersPanel = membersPanel)
                        else -> if (current.comingIn != null) {
                            MessageBox(current.label, current.comingIn)
                        } else {
                            tabContent(current.name) { name -> tab = ProjectTab.valueOf(name).ordinal }
                        }
                    }
                }
            }
        }
    }
}
