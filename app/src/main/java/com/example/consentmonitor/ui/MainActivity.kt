package com.example.consentmonitor.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.consentmonitor.appContainer
import com.example.consentmonitor.databinding.ActivityMainBinding
import com.example.consentmonitor.monitor.TrafficMonitorVpnService
import com.example.consentmonitor.monitor.NotificationCaptureService
import com.example.consentmonitor.work.UploadScheduler

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels { MainViewModelFactory(appContainer) }

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) startDnsVpn() else toast("VPN permission was not granted.")
    }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) toast("Foreground VPN status may be hidden from the notification drawer, but Android still shows the active VPN indicator.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        populateConfiguration()
        bindActions()
        observeModel()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun populateConfiguration() {
        val configuration = viewModel.configuration()
        binding.chatIdEditText.setText(configuration.chatId)
        binding.retentionDaysEditText.setText(configuration.retentionDays.toString())
        binding.unmeteredOnlyCheckBox.isChecked = configuration.unmeteredOnly
        binding.consentCheckBox.isChecked = configuration.consented
        if (configuration.hasBotToken) binding.botTokenEditText.hint = "Bot token (stored; leave blank to keep it)"
    }

    private fun bindActions() {
        binding.notificationAccessButton.setOnClickListener {
            if (!requireConsent()) return@setOnClickListener
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        binding.vpnButton.setOnClickListener {
            if (appContainer.settings.isVpnRunning()) {
                startService(Intent(this, TrafficMonitorVpnService::class.java).setAction(TrafficMonitorVpnService.ACTION_STOP))
                binding.root.postDelayed(::refreshStatus, 500)
            } else {
                if (!requireConsent()) return@setOnClickListener
                val permissionIntent = VpnService.prepare(this)
                if (permissionIntent != null) vpnPermissionLauncher.launch(permissionIntent) else startDnsVpn()
            }
        }
        binding.detectChatButton.setOnClickListener {
            viewModel.detectLatestTelegramChat(binding.botTokenEditText.text.toString())
        }
        binding.saveButton.setOnClickListener {
            val saved = viewModel.save(
                botTokenInput = binding.botTokenEditText.text.toString(),
                chatIdInput = binding.chatIdEditText.text.toString(),
                retentionDaysInput = binding.retentionDaysEditText.text.toString(),
                unmeteredOnly = binding.unmeteredOnlyCheckBox.isChecked,
                consented = binding.consentCheckBox.isChecked,
            )
            if (saved) {
                UploadScheduler.schedulePeriodic(this, binding.unmeteredOnlyCheckBox.isChecked)
                binding.botTokenEditText.text?.clear()
                binding.botTokenEditText.hint = "Bot token (stored; leave blank to keep it)"
                refreshStatus()
            }
        }
        binding.sendNowButton.setOnClickListener {
            if (!requireConsent()) return@setOnClickListener
            UploadScheduler.enqueueNow(this)
            toast("Upload queued. WorkManager will run it when a network is available.")
        }
    }

    private fun observeModel() {
        viewModel.counts.observe(this) { refreshStatus(it.notificationCount, it.networkCount) }
        viewModel.message.observe(this) { message ->
            message?.let {
                toast(it)
                viewModel.clearMessage()
            }
        }
        viewModel.detectedChat.observe(this) { chat -> chat?.let { binding.chatIdEditText.setText(it.id) } }
    }

    private fun startDnsVpn() {
        requestNotificationPermissionIfNeeded()
        ContextCompat.startForegroundService(this, Intent(this, TrafficMonitorVpnService::class.java))
        binding.root.postDelayed(::refreshStatus, 500)
    }

    private fun refreshStatus(notificationCount: Int? = null, networkCount: Int? = null) {
        val listenerComponent = ComponentName(this, NotificationCaptureService::class.java)
        val listenerGranted = getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listenerComponent)
        val vpnRunning = appContainer.settings.isVpnRunning()
        val currentCounts = viewModel.counts.value
        binding.statusText.text = buildString {
            append("Notification access: ${if (listenerGranted) "granted" else "not granted"}\n")
            append("Traffic VPN: ${if (vpnRunning) "running" else "stopped"}\n")
            append("Delivery: Telegram\n")
            append("Pending logs: ${notificationCount ?: currentCounts?.notificationCount ?: 0} notifications, ")
            append("${networkCount ?: currentCounts?.networkCount ?: 0} network events")
        }
        binding.vpnButton.text = if (vpnRunning) "Stop traffic monitoring VPN" else "Start traffic monitoring VPN"
    }

    private fun requireConsent(): Boolean {
        if (binding.consentCheckBox.isChecked) {
            appContainer.settings.recordConsent()
            UploadScheduler.schedulePeriodic(this, appContainer.settings.load().unmeteredOnly)
            return true
        }
        toast("Confirm informed consent first.")
        return false
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
