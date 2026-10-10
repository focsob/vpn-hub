package com.vpnhub.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.Socket

/**
 * After each list refresh, TCP-connect to each candidate's server from THIS phone and drop the
 * ones that don't answer. The cloud test uses a US runner, so a node alive there can still be
 * blocked or dead from the user's own network; this prunes those locally.
 */
object LocalPing {
    private val _dead = MutableStateFlow<Set<String>>(emptySet())

    /** Ids of nodes the phone could not reach in the last check. */
    val dead: StateFlow<Set<String>> = _dead

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking

    fun hostPort(node: Node): Pair<String, Int>? {
        val c = node.config ?: return null
        return try {
            when (c["type"]?.jsonPrimitive?.content) {
                "wireguard" -> {
                    val peer = c["peers"]!!.jsonArray[0].jsonObject
                    peer["address"]!!.jsonPrimitive.content to peer["port"]!!.jsonPrimitive.content.toInt()
                }
                "openvpn-client" -> {
                    val server = c["server"]?.jsonPrimitive?.content
                    if (server != null) {
                        server to c["server_port"]!!.jsonPrimitive.content.toInt()
                    } else {
                        val s = c["servers"]!!.jsonArray[0].jsonObject
                        s["server"]!!.jsonPrimitive.content to s["server_port"]!!.jsonPrimitive.content.toInt()
                    }
                }
                else -> c["server"]!!.jsonPrimitive.content to c["server_port"]!!.jsonPrimitive.content.toInt()
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun reachable(host: String, port: Int, timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
        withTimeoutOrNull(timeoutMs.toLong()) {
            runCatching {
                Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
            }.getOrDefault(false)
        } ?: false
    }

    /**
     * Check up to [cap] nodes (fastest first), [concurrency] at a time, and record which are unreachable.
     * Nodes not checked are left untouched so a quick pass never hides the rest of the list.
     */
    suspend fun prune(nodes: List<Node>, cap: Int = 600, concurrency: Int = 48, timeoutMs: Int = 2500) {
        if (nodes.isEmpty()) return
        _checking.value = true
        try {
            val targets = nodes.sortedBy { if (it.latency <= 0) Int.MAX_VALUE else it.latency }.take(cap)
            val gate = Semaphore(concurrency)
            val dead = HashSet<String>()
            coroutineScope {
                targets.map { node ->
                    async {
                        val hp = hostPort(node)
                        val ok = hp != null && reachable(hp.first, hp.second, timeoutMs)
                        if (!ok) synchronized(dead) { dead.add(node.id) }
                    }
                }.forEach { it.await() }
            }
            _dead.value = dead
        } finally {
            _checking.value = false
        }
    }

    fun clear() {
        _dead.value = emptySet()
    }
}
