package com.example.consentmonitor.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class DnsCodecTest {
    @Test
    fun parsesQuestion() {
        val question = DnsCodec.parseQuestion(query("example.com", 1))

        assertNotNull(question)
        assertEquals("example.com", question?.domain)
        assertEquals("A", question?.typeName)
    }

    @Test
    fun parsesAAndAaaaAnswers() {
        val response = responseWithAddresses()

        assertEquals(listOf("93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946"), DnsCodec.parseResolvedAddresses(response))
    }

    private fun query(domain: String, type: Int): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeShort(0x1234)
            output.writeShort(0x0100)
            output.writeShort(1)
            output.writeShort(0)
            output.writeShort(0)
            output.writeShort(0)
            writeName(output, domain)
            output.writeShort(type)
            output.writeShort(1)
        }
    }.toByteArray()

    private fun responseWithAddresses(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeShort(0x1234)
            output.writeShort(0x8180)
            output.writeShort(1)
            output.writeShort(2)
            output.writeShort(0)
            output.writeShort(0)
            writeName(output, "example.com")
            output.writeShort(1)
            output.writeShort(1)
            output.writeShort(0xC00C)
            output.writeShort(1)
            output.writeShort(1)
            output.writeInt(300)
            output.writeShort(4)
            output.write(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
            output.writeShort(0xC00C)
            output.writeShort(28)
            output.writeShort(1)
            output.writeInt(300)
            output.writeShort(16)
            output.write(byteArrayOf(0x26, 0x06, 0x28, 0x00, 0x02, 0x20, 0x00, 0x01, 0x02, 0x48, 0x18, 0x93.toByte(), 0x25, 0xC8.toByte(), 0x19, 0x46))
        }
    }.toByteArray()

    private fun writeName(output: DataOutputStream, domain: String) {
        domain.split('.').forEach { label ->
            output.writeByte(label.length)
            output.write(label.toByteArray(Charsets.US_ASCII))
        }
        output.writeByte(0)
    }
}
