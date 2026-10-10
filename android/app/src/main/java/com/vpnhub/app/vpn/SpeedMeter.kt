package com.vpnhub.app.vpn

import android.net.TrafficStats
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Live upload / download speed of the tunnel.
 * All proxied traffic leaves the phone from this app's own sockets, so the app's UID counters
 * measure exactly what goes through the VPN or proxy (plus a little protocol overhead).
 */
object SpeedMeter {
    data class Speed(val up: Long = 0, val down: Long = 0, val totalUp: Long = 0, val totalDown: Long = 0)

    private val _speed = MutableStateFlow(Speed())
    val speed: StateFlow<Speed> = _speed

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            val uid = Process.myUid()
            val startTx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
            val startRx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
            var lastTx = startTx
            var lastRx = startRx
            var lastTime = System.nanoTime()
            while (isActive) {
                delay(1000)
                val tx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
                val rx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
                val now = System.nanoTime()
                val secs = ((now - lastTime) / 1e9).coerceAtLeast(0.1)
                _speed.value = Speed(
                    up = ((tx - lastTx).coerceAtLeast(0) / secs).toLong(),
                    down = ((rx - lastRx).coerceAtLeast(0) / secs).toLong(),
                    totalUp = (tx - startTx).coerceAtLeast(0),
                    totalDown = (rx - startRx).coerceAtLeast(0),
                )
                lastTx = tx
                lastRx = rx
                lastTime = now
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _speed.value = Speed()
    }

    fun rate(bytesPerSecond: Long): String = size(bytesPerSecond) + "/s"

    fun size(bytes: Long): String {
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        return if (i == 0) "${bytes} B" else String.format(Locale.US, "%.1f %s", v, units[i])
    }
}
