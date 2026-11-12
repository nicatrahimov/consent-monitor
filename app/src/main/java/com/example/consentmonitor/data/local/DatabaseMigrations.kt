package com.example.consentmonitor.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE network_logs ADD COLUMN destinationIp TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE network_logs ADD COLUMN destinationPort INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE network_logs ADD COLUMN protocol TEXT NOT NULL DEFAULT ''")
    }
}
