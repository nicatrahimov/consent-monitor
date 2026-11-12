package com.example.consentmonitor.monitor

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DnsQuestion(val domain: String, val type: Int) {
    val typeName: String
        get() = when (type) {
            1 -> "A"
            2 -> "NS"
            5 -> "CNAME"
            12 -> "PTR"
            15 -> "MX"
            16 -> "TXT"
            28 -> "AAAA"
            33 -> "SRV"
            65 -> "HTTPS"
            else -> "TYPE$type"
        }
}

data class UdpDnsPacket(val clientAddress: ByteArray, val clientPort: Int, val dnsPayload: ByteArray)

object DnsCodec {
    fun parseUdpDnsPacket(packet: ByteArray, length: Int, expectedDnsAddress: ByteArray): UdpDnsPacket? {
        if (length < 28 || (packet[0].toInt() ushr 4) != 4) return null
        val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
        if (ipHeaderLength < 20 || length < ipHeaderLength + 8 || (packet[9].toInt() and 0xFF) != UDP_PROTOCOL) return null
        val destinationAddress = packet.copyOfRange(16, 20)
        if (!destinationAddress.contentEquals(expectedDnsAddress)) return null
        val destinationPort = unsignedShort(packet, ipHeaderLength + 2)
        if (destinationPort != DNS_PORT) return null
        val udpLength = unsignedShort(packet, ipHeaderLength + 4)
        if (udpLength < 8 || ipHeaderLength + udpLength > length) return null
        return UdpDnsPacket(
            clientAddress = packet.copyOfRange(12, 16),
            clientPort = unsignedShort(packet, ipHeaderLength),
            dnsPayload = packet.copyOfRange(ipHeaderLength + 8, ipHeaderLength + udpLength),
        )
    }

    fun parseQuestion(message: ByteArray): DnsQuestion? {
        if (message.size < DNS_HEADER_LENGTH || unsignedShort(message, 4) < 1) return null
        val name = readName(message, DNS_HEADER_LENGTH) ?: return null
        if (name.nextOffset + 4 > message.size) return null
        return DnsQuestion(name.value, unsignedShort(message, name.nextOffset))
    }

    fun parseResolvedAddresses(message: ByteArray): List<String> {
        if (message.size < DNS_HEADER_LENGTH) return emptyList()
        var offset = DNS_HEADER_LENGTH
        repeat(unsignedShort(message, 4)) {
            val name = readName(message, offset) ?: return emptyList()
            offset = name.nextOffset + 4
            if (offset > message.size) return emptyList()
        }
        val addresses = mutableListOf<String>()
        repeat(unsignedShort(message, 6)) {
            val name = readName(message, offset) ?: return addresses
            offset = name.nextOffset
            if (offset + 10 > message.size) return addresses
            val type = unsignedShort(message, offset)
            val dataLength = unsignedShort(message, offset + 8)
            offset += 10
            if (offset + dataLength > message.size) return addresses
            if ((type == 1 && dataLength == 4) || (type == 28 && dataLength == 16)) {
                runCatching { InetAddress.getByAddress(message.copyOfRange(offset, offset + dataLength)).hostAddress }.getOrNull()?.let(addresses::add)
            }
            offset += dataLength
        }
        return addresses.distinct()
    }

    fun buildUdpIpv4Response(request: UdpDnsPacket, dnsResponse: ByteArray, dnsAddress: ByteArray): ByteArray {
        val totalLength = IPV4_HEADER_LENGTH + UDP_HEADER_LENGTH + dnsResponse.size
        val result = ByteArray(totalLength)
        val buffer = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN)
        buffer.put(0x45.toByte())
        buffer.put(0.toByte())
        buffer.putShort(totalLength.toShort())
        buffer.putShort((System.nanoTime() and 0xFFFF).toShort())
        buffer.putShort(0.toShort())
        buffer.put(64.toByte())
        buffer.put(UDP_PROTOCOL.toByte())
        buffer.putShort(0.toShort())
        buffer.put(dnsAddress)
        buffer.put(request.clientAddress)
        buffer.putShort(DNS_PORT.toShort())
        buffer.putShort(request.clientPort.toShort())
        buffer.putShort((UDP_HEADER_LENGTH + dnsResponse.size).toShort())
        buffer.putShort(0.toShort())
        buffer.put(dnsResponse)

        putUnsignedShort(result, 10, checksum(result, 0, IPV4_HEADER_LENGTH))
        val udpChecksum = udpChecksum(result, IPV4_HEADER_LENGTH, UDP_HEADER_LENGTH + dnsResponse.size, dnsAddress, request.clientAddress)
        putUnsignedShort(result, IPV4_HEADER_LENGTH + 6, if (udpChecksum == 0) 0xFFFF else udpChecksum)
        return result
    }

    private data class ParsedName(val value: String, val nextOffset: Int)

    private fun readName(message: ByteArray, startOffset: Int): ParsedName? {
        if (startOffset !in message.indices) return null
        val labels = mutableListOf<String>()
        var offset = startOffset
        var nextOffset = -1
        var jumps = 0
        while (offset < message.size && jumps < MAX_POINTER_JUMPS) {
            val length = message[offset].toInt() and 0xFF
            when {
                length == 0 -> return ParsedName(labels.joinToString("."), if (nextOffset >= 0) nextOffset else offset + 1)
                (length and 0xC0) == 0xC0 -> {
                    if (offset + 1 >= message.size) return null
                    val pointer = ((length and 0x3F) shl 8) or (message[offset + 1].toInt() and 0xFF)
                    if (pointer !in message.indices) return null
                    if (nextOffset < 0) nextOffset = offset + 2
                    offset = pointer
                    jumps++
                }
                length > 63 || offset + 1 + length > message.size -> return null
                else -> {
                    labels += message.copyOfRange(offset + 1, offset + 1 + length).toString(Charsets.US_ASCII)
                    offset += 1 + length
                }
            }
        }
        return null
    }

    private fun checksum(bytes: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            sum += ((bytes[index].toInt() and 0xFF) shl 8) or (bytes[index + 1].toInt() and 0xFF)
            index += 2
        }
        if (index < end) sum += (bytes[index].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv().toInt() and 0xFFFF
    }

    private fun udpChecksum(packet: ByteArray, udpOffset: Int, udpLength: Int, source: ByteArray, destination: ByteArray): Int {
        val pseudoHeader = ByteBuffer.allocate(12 + udpLength + (udpLength and 1)).order(ByteOrder.BIG_ENDIAN)
        pseudoHeader.put(source)
        pseudoHeader.put(destination)
        pseudoHeader.put(0.toByte())
        pseudoHeader.put(UDP_PROTOCOL.toByte())
        pseudoHeader.putShort(udpLength.toShort())
        pseudoHeader.put(packet, udpOffset, udpLength)
        return checksum(pseudoHeader.array(), 0, pseudoHeader.position())
    }

    private fun unsignedShort(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 1 >= bytes.size) return -1
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun putUnsignedShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private const val DNS_HEADER_LENGTH = 12
    private const val IPV4_HEADER_LENGTH = 20
    private const val UDP_HEADER_LENGTH = 8
    private const val UDP_PROTOCOL = 17
    private const val DNS_PORT = 53
    private const val MAX_POINTER_JUMPS = 32
}
