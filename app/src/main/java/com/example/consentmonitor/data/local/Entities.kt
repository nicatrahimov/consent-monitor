package com.example.consentmonitor.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "notification_logs", indices = [Index("postedAt"), Index("deliveredAt")])
data class NotificationLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val title: String,
    val content: String,
    val postedAt: Long,
    val deliveredAt: Long? = null,
)

@Entity(tableName = "network_logs", indices = [Index("observedAt"), Index("deliveredAt")])
data class NetworkLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val domain: String,
    val resolvedIps: String,
    val requestType: String,
    val upstreamResolver: String,
    val observedAt: Long,
    val deliveredAt: Long? = null,
    val destinationIp: String = "",
    val destinationPort: Int = 0,
    val protocol: String = "",
)

data class LogCounts(
    val notificationCount: Int,
    val networkCount: Int,
)
