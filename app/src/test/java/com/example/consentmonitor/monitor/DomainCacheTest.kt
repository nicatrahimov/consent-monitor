package com.example.consentmonitor.monitor

import org.junit.Assert.assertEquals
import org.junit.Test

class DomainCacheTest {
    @Test
    fun mapsResolvedAddressToNormalizedDomain() {
        val cache = DomainCache()

        cache.remember("Example.COM.", listOf("93.184.216.34"), now = 1_000L)

        assertEquals("example.com", cache.find("93.184.216.34", now = 2_000L))
    }

    @Test
    fun expiresOldMappings() {
        val cache = DomainCache()

        cache.remember("example.com", listOf("93.184.216.34"), now = 1_000L)

        assertEquals("", cache.find("93.184.216.34", now = 31 * 60 * 1_000L))
    }
}
