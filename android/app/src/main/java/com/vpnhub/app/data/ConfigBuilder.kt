package com.vpnhub.app.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Turns the user's choice into a sing-box configuration. */
object ConfigBuilder {

    /** Apps in [packages] go through [nodes] (fastest auto-picked), or straight out when [direct]. */
    data class RouteGroup(val target: String, val packages: List<String>, val nodes: List<Node>, val direct: Boolean)

    data class Plan(
        val nodes: List<Node>,
        val label: String,
        val routes: List<RouteGroup> = emptyList(),
        /** Split rules whose country currently has no working node (their apps use the main choice). */
        val unavailable: List<String> = emptyList(),
    )

    sealed class Inbound {
        /** VPN mode: capture all traffic through a TUN interface. */
        data object Tun : Inbound()

        /** Proxy mode: SOCKS5 + HTTP on one port. */
        data class Proxy(val port: Int, val allowLan: Boolean) : Inbound()
    }

    private fun usableNodes(list: NodeList?, protocols: Set<String>, ipTypes: Set<String>) = list?.nodes.orEmpty()
        .filter { it.config != null && Protocols.matches(it, protocols) && IpTypes.matches(it, ipTypes) }
        .sortedBy { if (it.latency <= 0) Int.MAX_VALUE else it.latency }

    fun plan(
        list: NodeList?,
        selection: Selection,
        protocols: Set<String>,
        ipTypes: Set<String>,
        groupSize: Int,
        splitRules: List<SplitRule> = emptyList(),
    ): Plan? {
        val usable = usableNodes(list, protocols, ipTypes)
        if (usable.isEmpty()) return null
        val main = when (selection) {
            is Selection.Single -> {
                val node = usable.firstOrNull { it.id == selection.nodeId }
                if (node != null) {
                    Plan(listOf(node), "${Countries.flag(node.country)} ${node.name} · ${Protocols.label(node.protocol)}")
                } else {
                    // node vanished in a refresh: fall back to the fastest in the same country
                    val old = list?.nodes?.firstOrNull { it.id == selection.nodeId }
                    val sameCountry = usable.filter { it.country == old?.country }.take(groupSize)
                    if (sameCountry.isNotEmpty()) {
                        Plan(sameCountry, "${Countries.flag(old!!.country)} ${Countries.name(old.country)}（自動）")
                    } else {
                        Plan(usable.take(groupSize), "⚡ 全部最快（自動）")
                    }
                }
            }
            is Selection.Country -> {
                val nodes = usable.filter { it.country == selection.code }.take(groupSize)
                if (nodes.isEmpty()) {
                    return null
                } else {
                    Plan(nodes, "${Countries.flag(selection.code)} ${Countries.name(selection.code)}（自動揀最快）")
                }
            }
            Selection.Fastest -> Plan(
                usable.filter { it.country != "WARP" }.ifEmpty { usable }.take(groupSize),
                "⚡ 全部最快（自動）",
            )
        }

        val routes = mutableListOf<RouteGroup>()
        val unavailable = mutableListOf<String>()
        for (rule in splitRules.filter { it.enabled && it.packages.isNotEmpty() }) {
            if (rule.target == SplitRule.DIRECT) {
                routes += RouteGroup(rule.target, rule.packages, emptyList(), direct = true)
                continue
            }
            val nodes = usable.filter { it.country == rule.target }.take(groupSize)
            if (nodes.isEmpty()) {
                unavailable += rule.target
            } else {
                routes += RouteGroup(rule.target, rule.packages, nodes, direct = false)
            }
        }
        return main.copy(routes = routes, unavailable = unavailable)
    }

    fun build(plan: Plan, inbound: Inbound = Inbound.Tun): String {
        val outbounds = mutableListOf<JsonObject>()
        val endpoints = mutableListOf<JsonObject>()
        // each node is defined once even when several groups use it
        val tagOf = LinkedHashMap<String, String>()
        val used = HashSet<String>()

        fun tagsFor(nodes: List<Node>): List<String> = nodes.map { node ->
            tagOf.getOrPut(node.id) {
                var tag = node.name
                var i = 2
                while (!used.add(tag)) tag = "${node.name}-${i++}"
                val obj = JsonObject(node.config!! + ("tag" to JsonPrimitive(tag)))
                if (node.kind == "endpoint") endpoints += obj else outbounds += obj
                tag
            }
        }

        fun urltest(tag: String, members: List<String>) = buildJsonObject {
            put("type", "urltest")
            put("tag", tag)
            putJsonArray("outbounds") { members.forEach { add(it) } }
            put("url", "https://www.gstatic.com/generate_204")
            put("interval", "5m")
            put("tolerance", 100)
            put("idle_timeout", "30m")
        }

        val groups = mutableListOf(urltest("proxy", tagsFor(plan.nodes)))
        // per-app split routing needs the TUN (Android only reports app owners to the active VPN app)
        val routes = if (inbound is Inbound.Tun) plan.routes else emptyList()
        val routeRules = mutableListOf<JsonObject>()
        for (r in routes) {
            val outbound = if (r.direct) {
                "direct"
            } else {
                val tag = "split-${r.target}"
                if (groups.none { it["tag"] == JsonPrimitive(tag) }) groups += urltest(tag, tagsFor(r.nodes))
                tag
            }
            routeRules += buildJsonObject {
                putJsonArray("package_name") { r.packages.forEach { add(it) } }
                put("outbound", outbound)
            }
        }

        val root = buildJsonObject {
            putJsonObject("log") { put("level", "warn") }
            putJsonObject("dns") {
                putJsonArray("servers") {
                    addJsonObject {
                        put("type", "https")
                        put("tag", "remote")
                        put("server", "1.1.1.1")
                        put("detour", "proxy")
                    }
                    addJsonObject {
                        put("type", "local")
                        put("tag", "local")
                    }
                }
                put("final", "remote")
                put("strategy", "prefer_ipv4")
            }
            putJsonArray("inbounds") {
                when (inbound) {
                    Inbound.Tun -> addJsonObject {
                        put("type", "tun")
                        put("tag", "tun-in")
                        putJsonArray("address") {
                            add("172.19.0.1/30")
                            add("fdfe:dcba:9876::1/126")
                        }
                        put("mtu", 9000)
                        put("auto_route", true)
                        put("strict_route", true)
                        put("stack", "mixed")
                    }
                    is Inbound.Proxy -> addJsonObject {
                        // "mixed" answers both SOCKS5 and HTTP proxy requests on the same port
                        put("type", "mixed")
                        put("tag", "mixed-in")
                        put("listen", if (inbound.allowLan) "0.0.0.0" else "127.0.0.1")
                        put("listen_port", inbound.port)
                    }
                }
            }
            put(
                "outbounds",
                JsonArray(
                    outbounds + groups + buildJsonObject {
                        put("type", "direct")
                        put("tag", "direct")
                    },
                ),
            )
            if (endpoints.isNotEmpty()) put("endpoints", JsonArray(endpoints))
            putJsonObject("route") {
                putJsonArray("rules") {
                    addJsonObject { put("action", "sniff") }
                    addJsonObject {
                        put("protocol", "dns")
                        put("action", "hijack-dns")
                    }
                    addJsonObject {
                        put("ip_is_private", true)
                        put("outbound", "direct")
                    }
                    routeRules.forEach { add(it) }
                }
                put("final", "proxy")
                put("auto_detect_interface", true)
                put("default_domain_resolver", "local")
            }
        }
        return root.toString()
    }
}
