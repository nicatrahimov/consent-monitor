package com.example.consentmonitor.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.consentmonitor.appContainer
import com.example.consentmonitor.delivery.DeliveryResult
import com.example.consentmonitor.telegram.LogExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class UploadWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = uploadMutex.withLock {
        val container = applicationContext.appContainer
        val settings = container.settings.load()
        if (settings.consentTimestamp == 0L) return@withLock Result.success()
        container.repository.rotate(settings.retentionDays)
        if (settings.botToken.isBlank() || settings.chatId.isBlank()) return@withLock Result.success()

        repeat(MAX_BATCHES_PER_RUN) {
            val logs = container.repository.pending(limitPerType = RECORDS_PER_TYPE_PER_BATCH)
            if (logs.isEmpty) return@withLock Result.success()
            val exportFile = withContext(Dispatchers.IO) {
                LogExporter.write(applicationContext, logs, container.settings.getOrCreateDeviceId())
            }
            try {
                val delivery = withContext(Dispatchers.IO) {
                    container.telegramClient.sendDocument(
                        botToken = settings.botToken,
                        chatId = settings.chatId,
                        file = exportFile,
                        caption = "Consent Monitor: ${logs.notifications.size} notifications, ${logs.network.size} network events",
                    )
                }
                when (delivery) {
                    DeliveryResult.Success -> container.repository.markDelivered(logs)
                    is DeliveryResult.Retryable -> return@withLock Result.retry()
                    is DeliveryResult.PermanentFailure -> return@withLock Result.failure()
                }
            } finally {
                withContext(Dispatchers.IO) { exportFile.delete() }
            }
        }
        Result.retry()
    }

    private companion object {
        val uploadMutex = Mutex()
        const val RECORDS_PER_TYPE_PER_BATCH = 2_000
        const val MAX_BATCHES_PER_RUN = 10
    }
}
