package com.example.consentmonitor.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.consentmonitor.R
import com.example.consentmonitor.appContainer
import com.example.consentmonitor.ui.MainActivity
import hev.htproxy.TProxyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.net.Inet6Address
import java.util.concurrent.atomic.AtomicBoolean

class TrafficMonitorVpnService : VpnService() {
    private val running = AtomicBoolean(false)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var socksServer: LocalSocks5Server? = null
    private var tunnelConfig: File? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            return Service.START_NOT_STICKY
        }
        startInForeground()
        if (appContainer.settings.load().consentTimestamp == 0L) {
            stopMonitoring()
            return Service.START_NOT_STICKY
        }
        if (running.compareAndSet(false, true)) startVpn()
        return Service.START_STICKY
    }

    override fun onRevoke() {
        stopMonitoring()
        super.onRevoke()
    }

    override fun onDestroy() {
        closeResources()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startVpn() {
        try {
            val localProxy = LocalSocks5Server(this, appContainer.repository, serviceScope).also { it.start() }
            socksServer = localProxy
            val enableIpv6 = hasUsableUnderlyingIpv6()
            vpnInterface = createInterface(enableIpv6)
            val descriptor = vpnInterface ?: error("Android did not establish the VPN interface")
            tunnelConfig = writeTunnelConfig(localProxy.port, enableIpv6)
            check(TProxyService.TProxyStartService(tunnelConfig!!.absolutePath, descriptor.fd)) { "tun2socks did not start" }
            Log.i(TAG, "Full tunnel started: socksPort=${localProxy.port}, nativeRunning=${TProxyService.TProxyIsRunning()}")
            appContainer.settings.setVpnRunning(true)
        } catch (exception: Exception) {
            Log.e(TAG, "Could not start full traffic VPN", exception)
            stopMonitoring()
        }
    }

    private fun createInterface(enableIpv6: Boolean): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(MTU)
            .addAddress(CLIENT_IPV4_ADDRESS, CLIENT_IPV4_PREFIX)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(DNS_IPV4_ADDRESS)
            .setMetered(isCurrentNetworkMetered())
            .setBlocking(false)
        if (enableIpv6) {
            builder.addAddress(CLIENT_IPV6_ADDRESS, CLIENT_IPV6_PREFIX)
                .addRoute("::", 0)
                .addDnsServer(DNS_IPV6_ADDRESS)
        }
        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            // The package is always installed when this service runs; continue defensively.
        }
        return builder.establish()
    }

    private fun writeTunnelConfig(proxyPort: Int, enableIpv6: Boolean): File {
        val config = File(cacheDir, "traffic-monitor-tun2socks.yml")
        val ipv6Line = if (enableIpv6) "  ipv6: '$CLIENT_IPV6_ADDRESS'\n" else ""
        config.writeText(
            """
            tunnel:
              mtu: $MTU
              ipv4: $CLIENT_IPV4_ADDRESS
            $ipv6Line  icmp: 'reply'
            socks5:
              address: '127.0.0.1'
              port: $proxyPort
              udp: 'udp'
            misc:
              task-stack-size: 49152
              tcp-buffer-size: 16384
              max-session-count: 256
              connect-timeout: 15000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 60000
              log-file: null
              log-level: warn
            """.trimIndent(),
        )
        return config
    }

    private fun startInForeground() {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TrafficMonitorVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.monitoring_notification_title))
            .setContentText(getString(R.string.monitoring_notification_text))
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.stop), stopIntent)
            .build()
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.monitoring_channel_name), NotificationManager.IMPORTANCE_LOW)
            channel.description = getString(R.string.monitoring_notification_text)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun isCurrentNetworkMetered(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
    }

    private fun hasUsableUnderlyingIpv6(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val linkProperties = connectivityManager.getLinkProperties(connectivityManager.activeNetwork) ?: return false
        return linkProperties.linkAddresses.any { linkAddress ->
            val address = linkAddress.address
            address is Inet6Address && !address.isAnyLocalAddress && !address.isLinkLocalAddress && !address.isLoopbackAddress && !address.isSiteLocalAddress
        }
    }

    private fun stopMonitoring() {
        closeResources()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeResources() {
        appContainer.settings.setVpnRunning(false)
        if (!running.getAndSet(false) && vpnInterface == null && socksServer == null) return
        runCatching { TProxyService.TProxyStopService() }
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        runCatching { socksServer?.close() }
        socksServer = null
        runCatching { tunnelConfig?.delete() }
        tunnelConfig = null
    }

    companion object {
        const val ACTION_STOP = "com.example.consentmonitor.action.STOP_VPN"
        private const val CHANNEL_ID = "traffic_monitoring"
        private const val NOTIFICATION_ID = 1001
        private const val CLIENT_IPV4_ADDRESS = "10.23.42.2"
        private const val CLIENT_IPV4_PREFIX = 24
        private const val CLIENT_IPV6_ADDRESS = "fd42:23:42::2"
        private const val CLIENT_IPV6_PREFIX = 64
        private const val DNS_IPV4_ADDRESS = "1.1.1.1"
        private const val DNS_IPV6_ADDRESS = "2606:4700:4700::1111"
        private const val MTU = 1_500
        private const val TAG = "ConsentTrafficVpn"
    }
}
