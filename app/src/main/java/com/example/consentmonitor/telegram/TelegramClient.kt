package com.example.consentmonitor.telegram

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import com.example.consentmonitor.delivery.DeliveryResult
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class TelegramChat(val id: String, val label: String)

sealed interface ChatLookupResult {
    data class Found(val chat: TelegramChat) : ChatLookupResult
    data class Error(val message: String) : ChatLookupResult
}

class TelegramClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build(),
) {
    fun sendDocument(botToken: String, chatId: String, file: File, caption: String): DeliveryResult {
        if (!TOKEN_PATTERN.matches(botToken)) return DeliveryResult.PermanentFailure("The bot token format is invalid")
        if (chatId.isBlank()) return DeliveryResult.PermanentFailure("The chat ID is empty")

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("caption", caption.take(1_024))
            .addFormDataPart("document", file.name, file.asRequestBody(JSON_MEDIA_TYPE))
            .build()
        val request = Request.Builder()
            .url("https://api.telegram.org/bot$botToken/sendDocument")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                val responseJson = runCatching { JSONObject(responseText) }.getOrNull()
                val apiOk = responseJson?.optBoolean("ok", false) == true
                val apiErrorCode = responseJson?.optInt("error_code", 0) ?: 0
                when {
                    response.isSuccessful && apiOk -> DeliveryResult.Success
                    response.code == 429 || apiErrorCode == 429 || response.code >= 500 -> DeliveryResult.Retryable("Telegram returned HTTP ${response.code}")
                    else -> DeliveryResult.PermanentFailure("Telegram rejected the upload with HTTP ${response.code}")
                }
            }
        } catch (exception: Exception) {
            DeliveryResult.Retryable(exception.javaClass.simpleName)
        }
    }

    fun findLatestChat(botToken: String): ChatLookupResult {
        if (!TOKEN_PATTERN.matches(botToken)) return ChatLookupResult.Error("Enter a valid bot token first.")
        val request = Request.Builder()
            .url("https://api.telegram.org/bot$botToken/getUpdates?limit=100&timeout=0")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val root = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
                    ?: return ChatLookupResult.Error("Telegram returned an unreadable response.")
                if (!response.isSuccessful || !root.optBoolean("ok", false)) {
                    return ChatLookupResult.Error(root.optString("description", "Telegram rejected the request."))
                }
                val updates = root.optJSONArray("result")
                for (index in (updates?.length() ?: 0) - 1 downTo 0) {
                    val update = updates?.optJSONObject(index) ?: continue
                    val message = update.optJSONObject("message") ?: update.optJSONObject("channel_post") ?: continue
                    val chat = message.optJSONObject("chat") ?: continue
                    val id = chat.optLong("id").toString()
                    val label = listOf(chat.optString("title"), chat.optString("username"), chat.optString("first_name"))
                        .firstOrNull(String::isNotBlank).orEmpty().ifBlank { id }
                    return ChatLookupResult.Found(TelegramChat(id, label))
                }
                ChatLookupResult.Error("No recent chat was found. Send /start to the bot, then try again.")
            }
        } catch (exception: Exception) {
            ChatLookupResult.Error("Telegram lookup failed: ${exception.javaClass.simpleName}")
        }
    }

    private companion object {
        val TOKEN_PATTERN = Regex("^[0-9]{5,20}:[A-Za-z0-9_-]{20,100}$")
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
