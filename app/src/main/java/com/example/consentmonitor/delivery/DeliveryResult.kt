package com.example.consentmonitor.delivery

sealed interface DeliveryResult {
    data object Success : DeliveryResult
    data class Retryable(val reason: String) : DeliveryResult
    data class PermanentFailure(val reason: String) : DeliveryResult
}
