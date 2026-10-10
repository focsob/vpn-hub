package com.vpnhub.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

object NodeRepository {
    private val _list = MutableStateFlow<NodeList?>(null)
    val list: StateFlow<NodeList?> = _list

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val lock = Mutex()
    private lateinit var cacheFile: File

    /** Time (ms) the cached list was downloaded. */
    val fetchedAt: Long get() = if (::cacheFile.isInitialized && cacheFile.exists()) cacheFile.lastModified() else 0L

    fun loadCache(context: Context) {
        cacheFile = File(context.filesDir, "nodes.json")
        if (cacheFile.exists()) {
            runCatching { _list.value = json.decodeFromString(NodeList.serializer(), cacheFile.readText()) }
        }
    }

    fun find(id: String): Node? = _list.value?.nodes?.firstOrNull { it.id == id }

    /** Download the hourly list published by the GitHub Actions job. */
    suspend fun refresh(): Result<NodeList> = lock.withLock {
        withContext(Dispatchers.IO) {
            val url = Prefs.nodesUrl
            if (url.isBlank()) {
                val e = IllegalStateException("未設定節點清單網址（設定 → 節點清單網址）")
                _lastError.value = e.message
                return@withContext Result.failure(e)
            }
            _refreshing.value = true
            try {
                // prefer the gzip copy published next to nodes.json; fall back to the plain file
                val text = (if (url.endsWith(".json")) runCatching { download("$url.gz", gzipped = true) }.getOrNull() else null)
                    ?: download(url)
                val parsed = json.decodeFromString(NodeList.serializer(), text)
                if (parsed.nodes.isEmpty()) error("清單係空嘅，保留舊資料")
                val tmp = File(cacheFile.path + ".tmp")
                tmp.writeText(text)
                tmp.renameTo(cacheFile)
                _list.value = parsed
                _lastError.value = null
                Result.success(parsed)
            } catch (e: Exception) {
                _lastError.value = "更新失敗：${e.message}"
                Result.failure(e)
            } finally {
                _refreshing.value = false
            }
        }
    }

    private fun download(url: String, gzipped: Boolean = false): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Accept-Encoding", "gzip")
        conn.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val stream = if (gzipped || conn.contentEncoding == "gzip") GZIPInputStream(conn.inputStream) else conn.inputStream
            return stream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
