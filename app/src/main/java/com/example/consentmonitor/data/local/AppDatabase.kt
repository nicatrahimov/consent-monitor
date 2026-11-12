package com.example.consentmonitor.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [NotificationLogEntity::class, NetworkLogEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao
}
