package com.docloom_android.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.docloom_android.core.auth.SessionState
import com.docloom_android.core.model.User
import com.docloom_android.core.ui.LoadingBox
import com.docloom_android.core.ui.MessageBox
import com.docloom_android.core.ui.appContainer
import com.docloom_android.feature.auth.AuthViewModel
import com.docloom_android.feature.auth.ForgotPasswordScreen
import com.docloom_android.feature.auth.LoginScreen
import com.docloom_android.feature.auth.ResetPasswordScreen
import com.docloom_android.feature.auth.SignupScreen
import com.docloom_android.feature.project.ProjectScreen
import com.docloom_android.feature.project.ProjectViewModel
import com.docloom_android.feature.projects.HomeScreen
import com.docloom_android.feature.projects.NewProjectScreen
import com.docloom_android.feature.projects.NewProjectViewModel
import com.docloom_android.feature.projects.ProjectsScreen
import com.docloom_android.feature.projects.ProjectsViewModel
import com.docloom_android.feature.settings.SettingsScreen
import com.docloom_android.feature.docs.DocsScreen
import com.docloom_android.feature.docs.DocsViewModel
import com.docloom_android.feature.docs.SectionEditorViewModel
import com.docloom_android.feature.docs.SectionScreen
import com.docloom_android.feature.errors.ErrorsScreen
import com.docloom_android.feature.errors.ErrorsViewModel
import com.docloom_android.feature.history.HistoryScreen
import com.docloom_android.feature.map.MapScreen
import com.docloom_android.feature.team.ApiKeysScreen
import com.docloom_android.feature.team.GithubViewModel
import com.docloom_android.feature.team.IntegrationsScreen
import com.docloom_android.feature.team.InviteScreen
import com.docloom_android.feature.team.InviteViewModel
import com.docloom_android.feature.team.KeysViewModel
import com.docloom_android.feature.team.MembersPanel
import com.docloom_android.feature.team.MembersViewModel
import com.docloom_android.feature.team.OrgListViewModel
import com.docloom_android.feature.team.OrgViewModel
import com.docloom_android.feature.team.OrganizationScreen
import com.docloom_android.feature.team.OrganizationsScreen
import com.docloom_android.feature.team.SharingScreen
import com.docloom_android.feature.map.MapViewModel
import com.docloom_android.feature.history.HistoryViewModel
import com.docloom_android.feature.tasks.EditorTarget
import com.docloom_android.feature.tasks.repoName
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import com.docloom_android.feature.tasks.MyTasksScreen
import com.docloom_android.feature.tasks.MyTasksViewModel
import com.docloom_android.feature.tasks.TaskBoard
import com.docloom_android.feature.tasks.TaskBoardViewModel
import com.docloom_android.feature.todos.TodosScreen
import com.docloom_android.feature.todos.TodosViewModel
import com.docloom_android.ui.theme.DocloomTheme
import kotlinx.serialization.Serializable

// ---- Routes (type-safe navigation) ----
@Serializable data object LoginRoute
@Serializable data object SignupRoute
@Serializable data object ForgotPasswordRoute
@Serializable data class ResetPasswordRoute(val token: String? = null)

@Serializable data object HomeRoute
@Serializable data object ProjectsRoute
@Serializable data object MyTasksRoute
@Serializable data object TodosRoute
@Serializable data object SettingsRoute
/** [orgId]: create the project inside that organisation. */
@Serializable data class NewProjectRoute(val orgId: String? = null)
@Serializable data object OrganizationsRoute
@Serializable data class OrganizationRoute(val id: String)
@Serializable data object SharingRoute
@Serializable data object ApiKeysRoute
@Serializable data object IntegrationsRoute
@Serializable data object InviteRoute
/** [tab] opens a project tab by name (e.g. "Tasks"); [taskId] opens that task on the board. */
@Serializable data class ProjectRoute(val id: String, val tab: String? = null, val taskId: String? = null)

/** Top of the UI: picks the auth flow or the signed-in app from the session. */
@Composable
fun DocloomRoot() {
    val container = appContainer()
    val session by container.sessionRepository.state.collectAsStateWithLifecycle()
    val theme by container.sessionRepository.theme.collectAsStateWithLifecycle()

    DocloomTheme(theme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val s = session) {
                SessionState.Loading -> LoadingBox()
                SessionState.SignedOut -> AuthNavHost()
                is SessionState.SignedIn -> MainShell(s.user)
            }
        }
    }
}

@Composable
private fun AuthNavHost() {
    val container = appContainer()
    val vm: AuthViewModel = viewModel { AuthViewModel(container.sessionRepository) }
    val nav = rememberNavController()

    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        NavHost(nav, startDestination = LoginRoute) {
            composable<LoginRoute> {
                LoginScreen(vm, onSignup = { nav.navigate(SignupRoute) }, onForgot = { nav.navigate(ForgotPasswordRoute) })
            }
            composable<SignupRoute> {
                SignupScreen(vm, onLogin = { nav.popBackStack(LoginRoute, inclusive = false) })
            }
            composable<ForgotPasswordRoute> {
                ForgotPasswordScreen(
                    vm,
                    onBack = { nav.popBackStack(LoginRoute, inclusive = false) },
                    onHaveLink = { nav.navigate(ResetPasswordRoute()) },
                )
            }
            composable<ResetPasswordRoute> { entry ->
                ResetPasswordScreen(
                    vm,
                    initialToken = entry.toRoute<ResetPasswordRoute>().token,
                    onDone = { nav.popBackStack(LoginRoute, inclusive = false) },
                )
            }
        }
    }
}

private data class Tab(val route: Any, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(HomeRoute, "Home", Icons.Outlined.Home),
    Tab(ProjectsRoute, "Projects", Icons.Outlined.GridView),
    Tab(MyTasksRoute, "My tasks", Icons.AutoMirrored.Outlined.Assignment),
    Tab(TodosRoute, "Todos", Icons.Outlined.Checklist),
    Tab(SettingsRoute, "Settings", Icons.Outlined.Settings),
)

/**
 * Signed-in app: the website's dashboard sidebar becomes a bottom bar.
 * Project and New project pages are full screen with their own top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(user: User) {
    val container = appContainer()
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination
    val currentTab = TABS.firstOrNull { t -> current?.hasRoute(t.route::class) == true }
    val projectsVm: ProjectsViewModel = viewModel { ProjectsViewModel(container.projectsApi) }
    val openProject: (String) -> Unit = { id -> nav.navigate(ProjectRoute(id)) }
    val newProject: () -> Unit = { nav.navigate(NewProjectRoute()) }

    Scaffold(
        topBar = {
            if (currentTab != null) {
                TopAppBar(title = {
                    Text(if (currentTab.route == HomeRoute) "DocLoom" else currentTab.label, fontWeight = FontWeight.SemiBold)
                })
            }
        },
        bottomBar = {
            if (currentTab != null) {
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = { navigateToTab(nav, tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label, maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = HomeRoute, modifier = Modifier.padding(padding)) {
            composable<HomeRoute> {
                HomeScreen(
                    projectsVm, user,
                    onOpenProject = openProject,
                    onNewProject = newProject,
                    onAllProjects = { navigateToTab(nav, ProjectsRoute) },
                    onMyTasks = { navigateToTab(nav, MyTasksRoute) },
                )
            }
            composable<ProjectsRoute> { ProjectsScreen(projectsVm, openProject, newProject) }
            composable<MyTasksRoute> {
                val vm: MyTasksViewModel = viewModel { MyTasksViewModel(container.tasksApi) }
                MyTasksScreen(
                    vm,
                    onOpenTask = { pid, tid -> nav.navigate(ProjectRoute(pid, tab = "Tasks", taskId = tid)) },
                    onProjects = { navigateToTab(nav, ProjectsRoute) },
                )
            }
            composable<TodosRoute> {
                val vm: TodosViewModel = viewModel { TodosViewModel(container.tasksApi) }
                TodosScreen(vm)
            }
            composable<SettingsRoute> {
                SettingsScreen(user, container.sessionRepository) { page ->
                    when (page) {
                        "organizations" -> nav.navigate(OrganizationsRoute)
                        "sharing" -> nav.navigate(SharingRoute)
                        "keys" -> nav.navigate(ApiKeysRoute)
                        "integrations" -> nav.navigate(IntegrationsRoute)
                        "invite" -> nav.navigate(InviteRoute)
                    }
                }
            }
            composable<OrganizationsRoute> {
                val vm: OrgListViewModel = viewModel { OrgListViewModel(container.teamApi) }
                OrganizationsScreen(vm, onBack = { nav.popBackStack() }, onOpen = { nav.navigate(OrganizationRoute(it)) })
            }
            composable<OrganizationRoute> { entry ->
                val id = entry.toRoute<OrganizationRoute>().id
                val vm: OrgViewModel = viewModel { OrgViewModel(container.teamApi, id, user.id) }
                OrganizationScreen(
                    vm, user.id,
                    onBack = {
                        projectsVm.load(refresh = true)
                        nav.popBackStack()
                    },
                    onOpenProject = openProject,
                    onNewProject = { nav.navigate(NewProjectRoute(orgId = id)) },
                )
            }
            composable<SharingRoute> { SharingScreen(projectsVm, onBack = { nav.popBackStack() }, onOpenProject = openProject) }
            composable<ApiKeysRoute> {
                val vm: KeysViewModel = viewModel { KeysViewModel(container.teamApi) }
                ApiKeysScreen(vm, onBack = { nav.popBackStack() })
            }
            composable<IntegrationsRoute> {
                val vm: GithubViewModel = viewModel { GithubViewModel(container.teamApi) }
                IntegrationsScreen(vm, onBack = { nav.popBackStack() })
            }
            composable<InviteRoute> {
                val vm: InviteViewModel = viewModel { InviteViewModel(container.teamApi) }
                InviteScreen(vm, user, onBack = { nav.popBackStack() }) { joined ->
                    projectsVm.load(refresh = true)
                    val target: Any = if (joined.kind == "project") ProjectRoute(joined.id) else OrganizationRoute(joined.id)
                    nav.navigate(target) { popUpTo<InviteRoute> { inclusive = true } }
                }
            }

            composable<NewProjectRoute> { entry ->
                val orgId = entry.toRoute<NewProjectRoute>().orgId
                val vm: NewProjectViewModel = viewModel { NewProjectViewModel(container.projectsApi) }
                NewProjectScreen(
                    vm,
                    onBack = { nav.popBackStack() },
                    onOpenProject = { id ->
                        projectsVm.load(refresh = true)
                        nav.navigate(ProjectRoute(id)) { popUpTo<NewProjectRoute> { inclusive = true } }
                    },
                    initialOrgId = orgId,
                )
            }
            composable<ProjectRoute> { entry ->
                val route = entry.toRoute<ProjectRoute>()
                // Scoped to this back-stack entry, so each opened project gets its own.
                val vm: ProjectViewModel = viewModel {
                    ProjectViewModel(container.projectsApi, route.id, onChanged = { projectsVm.load(refresh = true) })
                }
                val refreshProject = {
                    vm.load()
                    projectsVm.load(refresh = true)
                }
                // A task to open on the board: from My tasks, or a task chip in the docs.
                var pendingTask by rememberSaveable { mutableStateOf(route.taskId) }
                // A map node to bring into view: from a doc chip or an error.
                var pendingFocus by rememberSaveable { mutableStateOf<String?>(null) }
                val uri = LocalUriHandler.current
                val projectState by vm.state.collectAsStateWithLifecycle()
                ProjectScreen(
                    vm,
                    onBack = { nav.popBackStack() },
                    initialTab = route.tab,
                    membersPanel = {
                        val role = projectState.project?.role ?: "viewer"
                        val members: MembersViewModel = viewModel(key = "members-$role") {
                            MembersViewModel(container.teamApi, route.id, role == "owner", user.id, refreshProject)
                        }
                        MembersPanel(members, role, user.id) {
                            projectsVm.load(refresh = true)
                            nav.popBackStack()
                        }
                    },
                ) { tab, openTab ->
                    // Each tab's ViewModel is created the first time it's shown and kept while the project is open.
                    when (tab) {
                        "Tasks" -> {
                            val board: TaskBoardViewModel = viewModel { TaskBoardViewModel(container.tasksApi, route.id, null, refreshProject) }
                            LaunchedEffect(pendingTask) {
                                pendingTask?.let { board.openEditor(EditorTarget.Edit(it)) }
                                pendingTask = null
                            }
                            TaskBoard(board, onOpenOverview = { openTab("Overview") })
                        }
                        "Docs" -> {
                            val docs: DocsViewModel = viewModel { DocsViewModel(container.docsApi, route.id, refreshProject) }
                            val docsState by docs.state.collectAsStateWithLifecycle()
                            DocsScreen(docs) { sectionId ->
                                val editor: SectionEditorViewModel = viewModel(key = "section-$sectionId") {
                                    SectionEditorViewModel(container.docsApi, container.tasksApi, route.id, sectionId, docs::onSectionChanged, container.appScope)
                                }
                                LaunchedEffect(sectionId) { editor.refreshIfClean() }
                                SectionScreen(editor, docsState, docs, onBack = { docs.openSection(null) }) { kind, value ->
                                    when (kind) {
                                        "task" -> {
                                            pendingTask = value
                                            openTab("Tasks")
                                        }
                                        "section" -> docs.openSection(value)
                                        "node" -> {
                                            pendingFocus = value
                                            openTab("Map")
                                        }
                                        "repo" -> uri.openUri("https://github.com/$value")
                                        "branch" -> docsState.github.repoUrl
                                            ?.let { repoName(it) }
                                            ?.let { uri.openUri("https://github.com/$it/tree/$value") }
                                    }
                                }
                            }
                        }
                        "Map" -> {
                            val viewer = vm.state.value.project?.role == "viewer"
                            val map: MapViewModel = viewModel {
                                MapViewModel(container.mapApi, container.mapLiveStream, route.id, viewer, pendingFocus)
                            }
                            LaunchedEffect(pendingFocus) {
                                pendingFocus?.let { map.focus(it) }
                                pendingFocus = null
                            }
                            MapScreen(map) { taskId ->
                                pendingTask = taskId
                                openTab("Tasks")
                            }
                        }
                        "History" -> {
                            val history: HistoryViewModel = viewModel { HistoryViewModel(container.docsApi, route.id, refreshProject) }
                            HistoryScreen(history)
                        }
                        "Errors" -> {
                            val errors: ErrorsViewModel = viewModel { ErrorsViewModel(container.docsApi, route.id, refreshProject) }
                            ErrorsScreen(errors) { nodeId ->
                                pendingFocus = nodeId
                                openTab("Map")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun navigateToTab(nav: NavHostController, route: Any) {
    nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
