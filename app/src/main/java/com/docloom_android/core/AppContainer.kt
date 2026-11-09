package com.docloom_android.core

import android.content.Context
import com.docloom_android.BuildConfig
import com.docloom_android.core.auth.AuthApi
import com.docloom_android.core.auth.SessionRepository
import com.docloom_android.core.auth.TokenStore
import com.docloom_android.core.network.ApiClient
import com.docloom_android.feature.docs.DocsApi
import com.docloom_android.feature.map.MapApi
import com.docloom_android.feature.map.MapLiveStream
import com.docloom_android.feature.projects.ProjectsApi
import com.docloom_android.feature.tasks.TasksApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Builds the app's long-lived objects once. Every feature gets its Retrofit
 * API from [api], which already points at `BuildConfig.API_BASE_URL` and
 * attaches the session token.
 */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val tokenStore = TokenStore(context.applicationContext)

    val api = ApiClient(
        baseUrl = BuildConfig.API_BASE_URL,
        tokenProvider = { tokenStore.currentToken() },
        debug = BuildConfig.DEBUG,
    )

    val sessionRepository = SessionRepository(
        authApi = api.create(AuthApi::class.java),
        tokenStore = tokenStore,
        unauthorized = api.unauthorized,
        scope = appScope,
    )

    val projectsApi: ProjectsApi = api.create(ProjectsApi::class.java)
    val tasksApi: TasksApi = api.create(TasksApi::class.java)
    val docsApi: DocsApi = api.create(DocsApi::class.java)
    val mapApi: MapApi = api.create(MapApi::class.java)
    val mapLiveStream = MapLiveStream(BuildConfig.API_BASE_URL, api.okHttp)
}
