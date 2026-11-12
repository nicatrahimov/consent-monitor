package com.example.consentmonitor.monitor

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.consentmonitor.appContainer
import com.example.consentmonitor.data.local.NotificationLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NotificationCaptureService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(statusBarNotification: StatusBarNotification?) {
        val notification = statusBarNotification ?: return
        if (notification.packageName == packageName || appContainer.settings.load().consentTimestamp == 0L) return

        val extras = notification.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(MAX_TITLE_LENGTH)
        val content = listOf(
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
        ).mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }.distinct().joinToString("\n").take(MAX_CONTENT_LENGTH)
        val appName = runCatching {
            val applicationInfo = packageManager.getApplicationInfo(notification.packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        }.getOrDefault(notification.packageName).take(MAX_APP_NAME_LENGTH)

        serviceScope.launch {
            appContainer.repository.addNotification(
                NotificationLogEntity(
                    packageName = notification.packageName.take(MAX_PACKAGE_LENGTH),
                    appName = appName,
                    title = title,
                    content = content,
                    postedAt = notification.postTime,
                ),
            )
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val MAX_PACKAGE_LENGTH = 255
        const val MAX_APP_NAME_LENGTH = 255
        const val MAX_TITLE_LENGTH = 1_000
        const val MAX_CONTENT_LENGTH = 8_000
    }
}
