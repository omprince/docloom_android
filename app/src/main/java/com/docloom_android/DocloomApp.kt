package com.docloom_android

import android.app.Application
import com.docloom_android.core.AppContainer

/** Holds the app-wide [AppContainer] (manual dependency injection). */
class DocloomApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
