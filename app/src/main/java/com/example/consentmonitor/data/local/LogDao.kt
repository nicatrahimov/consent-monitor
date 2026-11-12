package com.example.consentmonitor.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {
    @Insert
    suspend fun insertNotification(log: NotificationLogEntity)

    @Insert
    suspend fun insertNetwork(log: NetworkLogEntity)

    @Query("SELECT * FROM notification_logs WHERE deliveredAt IS NULL ORDER BY postedAt ASC LIMIT :limit")
    suspend fun pendingNotifications(limit: Int): List<NotificationLogEntity>

    @Query("SELECT * FROM network_logs WHERE deliveredAt IS NULL ORDER BY observedAt ASC LIMIT :limit")
    suspend fun pendingNetworkLogs(limit: Int): List<NetworkLogEntity>

    @Query("UPDATE notification_logs SET deliveredAt = :deliveredAt WHERE id IN (:ids)")
    suspend fun markNotificationsDelivered(ids: List<Long>, deliveredAt: Long)

    @Query("UPDATE network_logs SET deliveredAt = :deliveredAt WHERE id IN (:ids)")
    suspend fun markNetworkLogsDelivered(ids: List<Long>, deliveredAt: Long)

    @Query("DELETE FROM notification_logs WHERE postedAt < :cutoff")
    suspend fun deleteNotificationsOlderThan(cutoff: Long): Int

    @Query("DELETE FROM network_logs WHERE observedAt < :cutoff")
    suspend fun deleteNetworkLogsOlderThan(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM notification_logs WHERE deliveredAt IS NULL")
    fun pendingNotificationCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM network_logs WHERE deliveredAt IS NULL")
    fun pendingNetworkCount(): Flow<Int>
}
