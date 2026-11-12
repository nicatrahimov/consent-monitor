package com.example.consentmonitor.monitor

import android.net.VpnService
import com.example.consentmonitor.data.LogRepository
import com.example.consentmonitor.data.local.NetworkLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class LocalSocks5Server(
    private val vpnService: VpnService,
    private val repository: LogRepository,
    private val serviceScope: CoroutineScope,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val acceptExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val sessionExecutor: ExecutorService = Executors.newFixedThreadPool(MAX_SESSION_THREADS)
    private val relayExecutor: ExecutorService = Executors.newFixedThreadPool(MAX_RELAY_THREADS)
    private val serverSocket = ServerSocket()
    private val openSockets = Collections.synchronizedSet(mutableSetOf<AutoCloseable>())
    private val domainCache = DomainCache()
    private val recentUdpFlows = ConcurrentHashMap<String, Long>()
    private val dnsQuestions = ConcurrentHashMap<String, DnsQuestion>()

    val port: Int
        get() = serverSocket.localPort

    fun start() {
        if (!running.compareAndSet(false, true)) return
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress(IPV4_LOOPBACK, 0))
        acceptExecutor.execute(::acceptLoop)
    }

    private fun acceptLoop() {
        while (running.get()) {
            try {
                val client = serverSocket.accept()
                openSockets += client
                sessionExecutor.execute { handleClient(client) }
            } catch (_: SocketException) {
                if (running.get()) close()
            } catch (_: Exception) {
                // A malformed or abruptly closed local session must not stop the VPN.
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            client.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            negotiateAuthentication(input, output)
            val request = readRequest(input)
            when (request.command) {
                COMMAND_CONNECT -> handleTcpConnect(client, input, output, request)
                COMMAND_UDP_ASSOCIATE -> handleUdpAssociate(client, input, output)
                else -> writeReply(output, REPLY_COMMAND_NOT_SUPPORTED, null)
            }
        } catch (_: Exception) {
            // The proxy reports connection failures through the SOCKS reply where possible.
        } finally {
            openSockets -= client
            runCatching { client.close() }
        }
    }

    private fun negotiateAuthentication(input: InputStream, output: OutputStream) {
        if (input.read() != SOCKS_VERSION) throw SocketException("Unsupported SOCKS version")
        val methodCount = input.read().requireByte()
        val methods = input.readExactly(methodCount)
        if (methods.none { it.toInt() and 0xff == AUTH_NONE }) {
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), AUTH_UNACCEPTABLE.toByte()))
            output.flush()
            throw SocketException("No supported SOCKS authentication method")
        }
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), AUTH_NONE.toByte()))
        output.flush()
    }

    private fun readRequest(input: InputStream): SocksRequest {
        if (input.read() != SOCKS_VERSION) throw SocketException("Unsupported SOCKS request")
        val command = input.read().requireByte()
        input.read().requireByte()
        val address = readAddress(input, input.read().requireByte())
        val port = readUnsignedShort(input)
        return SocksRequest(command, address, port)
    }

    private fun handleTcpConnect(client: Socket, clientInput: InputStream, clientOutput: OutputStream, request: SocksRequest) {
        val remote = Socket()
        openSockets += remote
        try {
            val destinationIp = if (request.address.domain.isBlank()) request.address.host else ""
            logNetworkEvent(
                domain = request.address.domain.ifBlank { domainCache.find(destinationIp) },
                destinationIp = destinationIp,
                destinationPort = request.port,
                protocol = "TCP",
                requestType = when (request.port) {
                    80 -> "HTTP_CONNECTION"
                    443 -> "HTTPS_CONNECTION"
                    else -> "TCP_CONNECTION"
                },
            )
            vpnService.protect(remote)
            remote.tcpNoDelay = true
            remote.connect(InetSocketAddress(request.address.host, request.port), CONNECT_TIMEOUT_MILLIS)
            writeReply(clientOutput, REPLY_SUCCEEDED, remote.localSocketAddress as? InetSocketAddress)
            client.soTimeout = 0
            remote.soTimeout = 0
            val outbound: Future<*> = relayExecutor.submit {
                runCatching { clientInput.copyTo(remote.getOutputStream()) }
                runCatching { remote.shutdownOutput() }
            }
            runCatching { remote.getInputStream().copyTo(clientOutput) }
            runCatching { client.shutdownOutput() }
            outbound.cancel(true)
        } catch (_: Exception) {
            runCatching { writeReply(clientOutput, REPLY_HOST_UNREACHABLE, null) }
        } finally {
            openSockets -= remote
            runCatching { remote.close() }
        }
    }

    private fun handleUdpAssociate(client: Socket, controlInput: InputStream, output: OutputStream) {
        val relay = DatagramSocket(null)
        openSockets += relay
        try {
            relay.reuseAddress = true
            relay.bind(InetSocketAddress(0))
            vpnService.protect(relay)
            relay.soTimeout = UDP_POLL_TIMEOUT_MILLIS
            writeReply(output, REPLY_SUCCEEDED, InetSocketAddress(IPV4_LOOPBACK, relay.localPort))
            val task = relayExecutor.submit { relayUdp(relay) }
            client.soTimeout = 0
            while (controlInput.read() >= 0 && running.get()) Unit
            task.cancel(true)
        } finally {
            openSockets -= relay
            relay.close()
        }
    }

    private fun relayUdp(relay: DatagramSocket) {
        val buffer = ByteArray(MAX_UDP_PACKET_SIZE)
        var clientEndpoint: InetSocketAddress? = null
        while (running.get() && !relay.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                relay.receive(packet)
                val sender = packet.socketAddress as InetSocketAddress
                val request = parseUdpRequest(packet.data, packet.offset, packet.length)
                if (request != null) {
                    clientEndpoint = sender
                    inspectAndLogUdp(request)
                    relay.send(DatagramPacket(request.payload, request.payload.size, InetSocketAddress(request.address.host, request.port)))
                } else {
                    val client = clientEndpoint ?: continue
                    val response = buildUdpResponse(sender, packet.data.copyOfRange(packet.offset, packet.offset + packet.length))
                    inspectDnsResponse(sender, packet.data, packet.offset, packet.length)
                    relay.send(DatagramPacket(response, response.size, client))
                }
            } catch (_: java.net.SocketTimeoutException) {
                pruneUdpFlows()
            } catch (_: SocketException) {
                return
            } catch (_: Exception) {
                // Drop one invalid datagram without terminating other flows.
            }
        }
    }

    private fun inspectAndLogUdp(request: UdpRequest) {
        val destinationIp = runCatching { InetAddress.getByName(request.address.host).hostAddress.orEmpty() }.getOrDefault(request.address.host)
        val dnsQuestion = if (request.port == DNS_PORT) DnsCodec.parseQuestion(request.payload) else null
        if (dnsQuestion != null) {
            dnsQuestions[dnsKey(destinationIp, request.payload)] = dnsQuestion
            logNetworkEvent(
                domain = dnsQuestion.domain,
                destinationIp = destinationIp,
                destinationPort = request.port,
                protocol = "UDP",
                requestType = "DNS_${dnsQuestion.typeName}",
                upstreamResolver = destinationIp,
            )
            return
        }
        val flowKey = "${request.address.host}:${request.port}"
        val now = System.currentTimeMillis()
        val previous = recentUdpFlows.put(flowKey, now)
        if (previous != null && now - previous < UDP_FLOW_WINDOW_MILLIS) return
        logNetworkEvent(
            domain = request.address.domain.ifBlank { domainCache.find(destinationIp) },
            destinationIp = destinationIp,
            destinationPort = request.port,
            protocol = "UDP",
            requestType = if (request.port == 443) "QUIC_CONNECTION" else "UDP_FLOW",
        )
    }

    private fun inspectDnsResponse(sender: InetSocketAddress, data: ByteArray, offset: Int, length: Int) {
        if (sender.port != DNS_PORT || length < 2) return
        val payload = data.copyOfRange(offset, offset + length)
        val question = dnsQuestions.remove(dnsKey(sender.address.hostAddress.orEmpty(), payload)) ?: return
        domainCache.remember(question.domain, DnsCodec.parseResolvedAddresses(payload))
    }

    private fun logNetworkEvent(
        domain: String,
        destinationIp: String,
        destinationPort: Int,
        protocol: String,
        requestType: String,
        upstreamResolver: String = "",
    ) {
        serviceScope.launch {
            repository.addNetwork(
                NetworkLogEntity(
                    domain = domain.take(MAX_DOMAIN_LENGTH),
                    resolvedIps = destinationIp.take(MAX_IP_LENGTH),
                    requestType = requestType,
                    upstreamResolver = upstreamResolver,
                    observedAt = System.currentTimeMillis(),
                    destinationIp = destinationIp.take(MAX_IP_LENGTH),
                    destinationPort = destinationPort,
                    protocol = protocol,
                ),
            )
        }
    }

    private fun parseUdpRequest(data: ByteArray, offset: Int, length: Int): UdpRequest? {
        if (length < 7 || data[offset].toInt() != 0 || data[offset + 1].toInt() != 0 || data[offset + 2].toInt() != 0) return null
        var cursor = offset + 4
        val address = when (data[offset + 3].toInt() and 0xff) {
            ADDRESS_IPV4 -> {
                if (cursor + 4 > offset + length) return null
                Address(InetAddress.getByAddress(data.copyOfRange(cursor, cursor + 4)).hostAddress.orEmpty(), "").also { cursor += 4 }
            }
            ADDRESS_DOMAIN -> {
                if (cursor >= offset + length) return null
                val domainLength = data[cursor++].toInt() and 0xff
                if (cursor + domainLength > offset + length) return null
                val domain = data.copyOfRange(cursor, cursor + domainLength).toString(Charsets.UTF_8)
                cursor += domainLength
                Address(domain, domain)
            }
            ADDRESS_IPV6 -> {
                if (cursor + 16 > offset + length) return null
                Address(InetAddress.getByAddress(data.copyOfRange(cursor, cursor + 16)).hostAddress.orEmpty(), "").also { cursor += 16 }
            }
            else -> return null
        }
        if (cursor + 2 > offset + length) return null
        val port = ((data[cursor].toInt() and 0xff) shl 8) or (data[cursor + 1].toInt() and 0xff)
        cursor += 2
        return UdpRequest(address, port, data.copyOfRange(cursor, offset + length))
    }

    private fun buildUdpResponse(sender: InetSocketAddress, payload: ByteArray): ByteArray {
        val address = sender.address.address
        val type = if (sender.address is Inet4Address) ADDRESS_IPV4 else ADDRESS_IPV6
        return ByteArray(4 + address.size + 2 + payload.size).also { result ->
            result[3] = type.toByte()
            address.copyInto(result, 4)
            val portOffset = 4 + address.size
            result[portOffset] = (sender.port ushr 8).toByte()
            result[portOffset + 1] = sender.port.toByte()
            payload.copyInto(result, portOffset + 2)
        }
    }

    private fun writeReply(output: OutputStream, reply: Int, bound: InetSocketAddress?) {
        val address = bound?.address ?: InetAddress.getByName("0.0.0.0")
        val rawAddress = address.address
        val addressType = if (address is Inet6Address) ADDRESS_IPV6 else ADDRESS_IPV4
        val port = bound?.port ?: 0
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), reply.toByte(), 0, addressType.toByte()))
        output.write(rawAddress)
        output.write(byteArrayOf((port ushr 8).toByte(), port.toByte()))
        output.flush()
    }

    private fun readAddress(input: InputStream, type: Int): Address = when (type) {
        ADDRESS_IPV4 -> Address(InetAddress.getByAddress(input.readExactly(4)).hostAddress.orEmpty(), "")
        ADDRESS_IPV6 -> Address(InetAddress.getByAddress(input.readExactly(16)).hostAddress.orEmpty(), "")
        ADDRESS_DOMAIN -> {
            val length = input.read().requireByte()
            val domain = input.readExactly(length).toString(Charsets.UTF_8)
            Address(domain, domain)
        }
        else -> throw SocketException("Unsupported SOCKS address type")
    }

    private fun readUnsignedShort(input: InputStream): Int = (input.read().requireByte() shl 8) or input.read().requireByte()

    private fun dnsKey(resolver: String, payload: ByteArray): String {
        val transaction = if (payload.size >= 2) ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff) else -1
        return "$resolver:$transaction"
    }

    private fun pruneUdpFlows(now: Long = System.currentTimeMillis()) {
        recentUdpFlows.entries.removeIf { now - it.value > UDP_FLOW_WINDOW_MILLIS }
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket.close() }
        synchronized(openSockets) { openSockets.toList().forEach { runCatching { it.close() } } }
        openSockets.clear()
        acceptExecutor.shutdownNow()
        sessionExecutor.shutdownNow()
        relayExecutor.shutdownNow()
    }

    private fun InputStream.readExactly(size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(result, offset, size - offset)
            if (count < 0) throw EOFException()
            offset += count
        }
        return result
    }

    private fun Int.requireByte(): Int = if (this < 0) throw EOFException() else this

    private data class SocksRequest(val command: Int, val address: Address, val port: Int)
    private data class UdpRequest(val address: Address, val port: Int, val payload: ByteArray)
    private data class Address(val host: String, val domain: String)

    private companion object {
        const val SOCKS_VERSION = 5
        const val AUTH_NONE = 0
        const val AUTH_UNACCEPTABLE = 0xff
        const val COMMAND_CONNECT = 1
        const val COMMAND_UDP_ASSOCIATE = 3
        const val ADDRESS_IPV4 = 1
        const val ADDRESS_DOMAIN = 3
        const val ADDRESS_IPV6 = 4
        const val REPLY_SUCCEEDED = 0
        const val REPLY_HOST_UNREACHABLE = 4
        const val REPLY_COMMAND_NOT_SUPPORTED = 7
        const val DNS_PORT = 53
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val HANDSHAKE_TIMEOUT_MILLIS = 10_000
        const val UDP_POLL_TIMEOUT_MILLIS = 1_000
        const val UDP_FLOW_WINDOW_MILLIS = 60_000L
        const val MAX_UDP_PACKET_SIZE = 65_535
        const val MAX_DOMAIN_LENGTH = 253
        const val MAX_IP_LENGTH = 64
        const val MAX_SESSION_THREADS = 64
        const val MAX_RELAY_THREADS = 64
        val IPV4_LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
    }
}
