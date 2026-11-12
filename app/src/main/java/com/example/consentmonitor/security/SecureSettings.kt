package com.example.consentmonitor.security

import android.content.Context
import java.security.SecureRandom
import java.util.UUID

data class MonitorSettings(
    val botToken: String,
    val chatId: String,
    val retentionDays: Int,
    val unmeteredOnly: Boolean,
    val consentTimestamp: Long,
)

class SecureSettings(context: Context, private val cipher: KeystoreCipher = KeystoreCipher()) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): MonitorSettings = MonitorSettings(
        botToken = decryptString(KEY_BOT_TOKEN),
        chatId = decryptString(KEY_CHAT_ID),
        retentionDays = preferences.getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS).coerceIn(1, 365),
        unmeteredOnly = preferences.getBoolean(KEY_UNMETERED_ONLY, true),
        consentTimestamp = preferences.getLong(KEY_CONSENT_TIMESTAMP, 0L),
    )

    fun save(
        botToken: String,
        chatId: String,
        retentionDays: Int,
        unmeteredOnly: Boolean,
        consentTimestamp: Long,
    ) {
        preferences.edit()
            .putString(KEY_BOT_TOKEN, cipher.encrypt(botToken.trim().toByteArray(Charsets.UTF_8)))
            .putString(KEY_CHAT_ID, cipher.encrypt(chatId.trim().toByteArray(Charsets.UTF_8)))
            .putInt(KEY_RETENTION_DAYS, retentionDays.coerceIn(1, 365))
            .putBoolean(KEY_UNMETERED_ONLY, unmeteredOnly)
            .putLong(KEY_CONSENT_TIMESTAMP, consentTimestamp)
            .apply()
    }

    @Synchronized
    fun getOrCreateDatabasePassphrase(): ByteArray {
        preferences.getString(KEY_DATABASE_PASSPHRASE, null)?.let { return cipher.decrypt(it) }
        val passphrase = ByteArray(32).also(SecureRandom()::nextBytes)
        check(preferences.edit().putString(KEY_DATABASE_PASSPHRASE, cipher.encrypt(passphrase)).commit()) {
            "Could not persist the database encryption key"
        }
        return passphrase
    }

    fun getOrCreateDeviceId(): String {
        preferences.getString(KEY_DEVICE_ID, null)?.let { return it }
        return UUID.randomUUID().toString().also { preferences.edit().putString(KEY_DEVICE_ID, it).apply() }
    }

    fun setVpnRunning(running: Boolean) {
        preferences.edit().putBoolean(KEY_VPN_RUNNING, running).apply()
    }

    fun isVpnRunning(): Boolean = preferences.getBoolean(KEY_VPN_RUNNING, false)

    fun recordConsent() {
        if (preferences.getLong(KEY_CONSENT_TIMESTAMP, 0L) == 0L) {
            preferences.edit().putLong(KEY_CONSENT_TIMESTAMP, System.currentTimeMillis()).apply()
        }
    }

    private fun decryptString(key: String): String = preferences.getString(key, null)?.let {
        cipher.decrypt(it).toString(Charsets.UTF_8)
    }.orEmpty()

    private companion object {
        const val PREFERENCES_NAME = "secure_monitor_settings"
        const val KEY_BOT_TOKEN = "bot_token"
        const val KEY_CHAT_ID = "chat_id"
        const val KEY_RETENTION_DAYS = "retention_days"
        const val KEY_UNMETERED_ONLY = "unmetered_only"
        const val KEY_CONSENT_TIMESTAMP = "consent_timestamp"
        const val KEY_DATABASE_PASSPHRASE = "database_passphrase"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_VPN_RUNNING = "vpn_running"
        const val DEFAULT_RETENTION_DAYS = 14
    }
}
