package com.example.consentmonitor

import android.content.Context
import androidx.room.Room
import com.example.consentmonitor.data.LogRepository
import com.example.consentmonitor.data.local.AppDatabase
import com.example.consentmonitor.data.local.MIGRATION_1_2
import com.example.consentmonitor.security.SecureSettings
import com.example.consentmonitor.telegram.TelegramClient
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val settings = SecureSettings(appContext)

    private val database: AppDatabase by lazy {
        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory(settings.getOrCreateDatabasePassphrase())
        Room.databaseBuilder(appContext, AppDatabase::class.java, appContext.getDatabasePath("monitor.db").absolutePath)
            .openHelperFactory(factory)
            .addMigrations(MIGRATION_1_2)
            .build()
    }

    val repository: LogRepository by lazy { LogRepository(database) }
    val telegramClient: TelegramClient by lazy { TelegramClient() }
}
