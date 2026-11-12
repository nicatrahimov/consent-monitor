package com.example.consentmonitor

import android.app.Application
import android.content.Context
import com.example.consentmonitor.work.UploadScheduler

class MonitorApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        if (container.settings.load().consentTimestamp > 0L) UploadScheduler.schedulePeriodic(this, container.settings.load().unmeteredOnly)
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as MonitorApplication).container
