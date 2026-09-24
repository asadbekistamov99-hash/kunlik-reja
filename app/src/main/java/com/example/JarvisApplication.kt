package com.example

import android.app.Application
import com.jarvis.AppContainer
import com.example.notification.NotificationHelper

open class JarvisApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
        container.start()
        NotificationHelper.createNotificationChannels(this)
    }

    /** Overridden in tests to use an in-memory database and a fake secret store. */
    protected open fun createContainer(): AppContainer = AppContainer(this)
}
