package com.example.consentmonitor.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.consentmonitor.AppContainer
import com.example.consentmonitor.data.local.LogCounts
import com.example.consentmonitor.telegram.ChatLookupResult
import com.example.consentmonitor.telegram.TelegramChat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SavedConfiguration(
    val hasBotToken: Boolean,
    val chatId: String,
    val retentionDays: Int,
    val unmeteredOnly: Boolean,
    val consented: Boolean,
)

class MainViewModel(private val container: AppContainer) : ViewModel() {
    private val _counts = MutableLiveData(LogCounts(0, 0))
    val counts: LiveData<LogCounts> = _counts

    private val _message = MutableLiveData<String?>()
    val message: LiveData<String?> = _message

    private val _detectedChat = MutableLiveData<TelegramChat?>()
    val detectedChat: LiveData<TelegramChat?> = _detectedChat

    init {
        viewModelScope.launch { container.repository.pendingCounts.collect(_counts::postValue) }
    }

    fun configuration(): SavedConfiguration {
        val current = container.settings.load()
        return SavedConfiguration(
            hasBotToken = current.botToken.isNotBlank(),
            chatId = current.chatId,
            retentionDays = current.retentionDays,
            unmeteredOnly = current.unmeteredOnly,
            consented = current.consentTimestamp > 0,
        )
    }

    fun save(
        botTokenInput: String,
        chatIdInput: String,
        retentionDaysInput: String,
        unmeteredOnly: Boolean,
        consented: Boolean,
    ): Boolean {
        if (!consented) {
            _message.value = "Confirm informed consent before enabling collection or delivery."
            return false
        }
        val existing = container.settings.load()
        val botToken = botTokenInput.trim().ifBlank { existing.botToken }
        val chatId = chatIdInput.trim()
        val retentionDays = retentionDaysInput.toIntOrNull()?.coerceIn(1, 365) ?: 14
        if (!BOT_TOKEN_PATTERN.matches(botToken)) {
            _message.value = "Enter a valid Telegram bot token."
            return false
        }
        if (chatId.isBlank()) {
            _message.value = "Enter or detect the Telegram chat ID."
            return false
        }
        container.settings.save(
            botToken = botToken,
            chatId = chatId,
            retentionDays = retentionDays,
            unmeteredOnly = unmeteredOnly,
            consentTimestamp = existing.consentTimestamp.takeIf { it > 0 } ?: System.currentTimeMillis(),
        )
        _message.value = "Encrypted settings saved. The first periodic upload is eligible in about four hours."
        return true
    }

    fun detectLatestTelegramChat(botTokenInput: String) {
        val token = botTokenInput.trim().ifBlank { container.settings.load().botToken }
        viewModelScope.launch {
            when (val result = withContext(Dispatchers.IO) { container.telegramClient.findLatestChat(token) }) {
                is ChatLookupResult.Found -> {
                    _detectedChat.value = result.chat
                    _message.value = "Detected Telegram chat: ${result.chat.label} (${result.chat.id})"
                }
                is ChatLookupResult.Error -> _message.value = result.message
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    companion object {
        private val BOT_TOKEN_PATTERN = Regex("^[0-9]{5,20}:[A-Za-z0-9_-]{20,100}$")
    }
}

class MainViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = MainViewModel(container) as T
}
