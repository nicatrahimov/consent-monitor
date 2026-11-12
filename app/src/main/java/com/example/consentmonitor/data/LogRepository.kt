package com.example.consentmonitor.data

import androidx.room.withTransaction
import com.example.consentmonitor.data.local.AppDatabase
import com.example.consentmonitor.data.local.LogCounts
import com.example.consentmonitor.data.local.NetworkLogEntity
import com.example.consentmonitor.data.local.NotificationLogEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class PendingLogs(
    val notifications: List<NotificationLogEntity>,
    val network: List<NetworkLogEntity>,
) {
    val isEmpty: Boolean get() = notifications.isEmpty() && network.isEmpty()
}

class LogRepository(private val database: AppDatabase) {
    private val dao = database.logDao()

    val pendingCounts: Flow<LogCounts> = combine(dao.pendingNotificationCount(), dao.pendingNetworkCount(), ::LogCounts)

    suspend fun addNotification(log: NotificationLogEntity) = dao.insertNotification(log)

    suspend fun addNetwork(log: NetworkLogEntity) = dao.insertNetwork(log)

    suspend fun pending(limitPerType: Int = 5_000): PendingLogs = PendingLogs(
        notifications = dao.pendingNotifications(limitPerType),
        network = dao.pendingNetworkLogs(limitPerType),
    )

    suspend fun markDelivered(logs: PendingLogs, deliveredAt: Long = System.currentTimeMillis()) = database.withTransaction {
        val notificationIds = logs.notifications.map(NotificationLogEntity::id)
        val networkIds = logs.network.map(NetworkLogEntity::id)
        if (notificationIds.isNotEmpty()) dao.markNotificationsDelivered(notificationIds, deliveredAt)
        if (networkIds.isNotEmpty()) dao.markNetworkLogsDelivered(networkIds, deliveredAt)
    }

    suspend fun rotate(retentionDays: Int): Int {
        val cutoff = System.currentTimeMillis() - retentionDays.coerceIn(1, 365) * MILLIS_PER_DAY
        return database.withTransaction {
            dao.deleteNotificationsOlderThan(cutoff) + dao.deleteNetworkLogsOlderThan(cutoff)
        }
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
