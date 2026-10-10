package com.vpnhub.app.vpn

import com.vpnhub.app.App
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/** In-app troubleshooting: test the live connection and read the core's log. */
object Diagnostics {
    private const val TRACE = "https://www.cloudflare.com/cdn-cgi/trace"

    val logFile: File get() = File(App.instance.filesDir, "box/box.log")

    @Volatile
    private var currentPort = 0

    /** Local test port of the running core (a free port picked at each start / reload). */
    fun port(): Int {
        currentPort = runCatching { Libbox.availablePort(29380) }.getOrDefault(29380)
        return currentPort
    }

    fun resetLog() {
        runCatching { logFile.parentFile?.mkdirs(); logFile.writeText("") }
    }

    data class Result(val ok: Boolean, val text: String)

    private fun trace(proxy: Proxy?): Result {
        val start = System.nanoTime()
        val conn = (if (proxy != null) URL(TRACE).openConnection(proxy) else URL(TRACE).openConnection()) as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        return try {
            val body = conn.inputStream.bufferedReader().readText()
            val ms = (System.nanoTime() - start) / 1_000_000
            val f = body.lines().filter { "=" in it }.associate { it.substringBefore("=") to it.substringAfter("=") }
            val warp = f["warp"] ?: "?"
            Result(
                true,
                "出口 IP：${f["ip"]}\n國家／地區：${f["loc"]}（Cloudflare 機房 ${f["colo"]}）\n" +
                    "WARP：${if (warp == "on" || warp == "plus") "✅ 開" else "❌ 冇（$warp）"}\n延遲：$ms ms",
            )
        } catch (e: Exception) {
            Result(false, "失敗：${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    /** Fetch Cloudflare's trace page through the running tunnel, and once without it for comparison. */
    suspend fun run(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.append("【經 VPN Hub 而家條線】\n")
        if (VpnState.status.value != VpnState.Status.Connected || currentPort == 0) {
            sb.append("未連線\n")
        } else {
            sb.append(VpnState.label.value).append('\n')
            sb.append(trace(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", currentPort))).text).append('\n')
        }
        sb.append("\n【唔經 VPN（你部手機本身嘅網絡）】\n")
        sb.append(trace(null).text).append('\n')
        sb.toString()
    }

    /** Last part of the core's log (the file can grow large at debug level). */
    fun logTail(maxBytes: Int = 48 * 1024): String {
        val f = logFile
        if (!f.exists() || f.length() == 0L) return "（未有記錄，請先連線）"
        return RandomAccessFile(f, "r").use { raf ->
            val start = (raf.length() - maxBytes).coerceAtLeast(0)
            raf.seek(start)
            val bytes = ByteArray((raf.length() - start).toInt())
            raf.readFully(bytes)
            String(bytes).replace(Regex("\u001B\\[[0-9;]*m"), "") // strip terminal colours
        }
    }
}
