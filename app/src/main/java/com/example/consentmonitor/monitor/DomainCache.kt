package com.example.consentmonitor.monitor

import java.util.concurrent.ConcurrentHashMap

class DomainCache {
    private val entries = ConcurrentHashMap<String, Entry>()

    fun remember(domain: String, addresses: List<String>, now: Long = System.currentTimeMillis()) {
        val cleanDomain = domain.trim().trimEnd('.').lowercase().take(MAX_DOMAIN_LENGTH)
        if (cleanDomain.isBlank()) return
        val expiresAt = now + ENTRY_LIFETIME_MILLIS
        addresses.filter(String::isNotBlank).forEach { address -> entries[address] = Entry(cleanDomain, expiresAt) }
    }

    fun find(address: String, now: Long = System.currentTimeMillis()): String {
        val entry = entries[address] ?: return ""
        if (entry.expiresAt < now) {
            entries.remove(address, entry)
            return ""
        }
        return entry.domain
    }

    private data class Entry(val domain: String, val expiresAt: Long)

    private companion object {
        const val ENTRY_LIFETIME_MILLIS = 30 * 60 * 1_000L
        const val MAX_DOMAIN_LENGTH = 253
    }
}
