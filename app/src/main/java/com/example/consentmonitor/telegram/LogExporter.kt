package com.example.consentmonitor.telegram

import android.content.Context
import android.util.JsonWriter
import com.example.consentmonitor.data.PendingLogs
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

object LogExporter {
    fun write(context: Context, logs: PendingLogs, deviceId: String): File {
        val output = File.createTempFile("consent-monitor-", ".json", context.cacheDir)
        JsonWriter(OutputStreamWriter(FileOutputStream(output), Charsets.UTF_8)).use { writer ->
            writer.setIndent("  ")
            writer.beginObject()
            writer.name("schema_version").value(2)
            writer.name("device_id").value(deviceId)
            writer.name("generated_at_epoch_ms").value(System.currentTimeMillis())
            writer.name("notifications").beginArray()
            logs.notifications.forEach { log ->
                writer.beginObject()
                writer.name("id").value(log.id)
                writer.name("package_name").value(log.packageName)
                writer.name("app_name").value(log.appName)
                writer.name("title").value(log.title)
                writer.name("content").value(log.content)
                writer.name("posted_at_epoch_ms").value(log.postedAt)
                writer.endObject()
            }
            writer.endArray()
            writer.name("network_events").beginArray()
            logs.network.forEach { log ->
                writer.beginObject()
                writer.name("id").value(log.id)
                writer.name("domain").value(log.domain)
                writer.name("resolved_ips").beginArray()
                log.resolvedIps.split(',').map(String::trim).filter(String::isNotEmpty).forEach { writer.value(it) }
                writer.endArray()
                writer.name("request_type").value(log.requestType)
                writer.name("protocol").value(log.protocol)
                writer.name("destination_ip").value(log.destinationIp.ifBlank { log.resolvedIps })
                writer.name("destination_port").value(log.destinationPort.toLong())
                writer.name("upstream_resolver").value(log.upstreamResolver)
                writer.name("observed_at_epoch_ms").value(log.observedAt)
                writer.endObject()
            }
            writer.endArray()
            writer.endObject()
        }
        return output
    }
}
