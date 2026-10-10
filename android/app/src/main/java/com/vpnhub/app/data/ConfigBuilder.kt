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
        /** Finish through Cloudflare WARP: via [nodes] when not empty, otherwise straight from the phone. */
        val warp: Boolean = false,
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
        warpMode: Boolean = false,
    ): Plan? {
        val usable = usableNodes(list, protocols, ipTypes)
        val main = when {
            // "WARP" row: connect to WARP straight from the phone (exit near you)
            selection == Selection.Country(WARP) -> Plan(emptyList(), "☁️ Cloudflare WARP（直接・就近出口）", warp = true)
            warpMode -> warpPlan(usable, list, selection, groupSize) ?: return null
            usable.isEmpty() -> return null
            else -> normalPlan(usable, list, selection, groupSize) ?: return null
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

    const val WARP = "WARP"

    /**
     * WARP chain: the phone reaches WARP through nodes that the cloud verified can carry it, grouped by the
     * country WARP then exits in. If the node in use dies the group switches to another one of the same
     * group; if every node of that country is down, traffic stops instead of leaking out somewhere else.
     */
    private fun warpPlan(usable: List<Node>, list: NodeList?, selection: Selection, groupSize: Int): Plan? {
        val capable = usable.filter { it.warpCc != null }.sortedBy { if (it.warpMs <= 0) Int.MAX_VALUE else it.warpMs }
        if (capable.isEmpty()) return null
        fun country(cc: String, auto: Boolean = true) = capable.filter { it.warpCc == cc }.take(groupSize)
            .takeIf { it.isNotEmpty() }
            ?.let { Plan(it, "☁️ WARP ${Countries.flag(cc)} ${Countries.name(cc)}" + if (auto) "（經 ${it.size} 個節點，自動切換）" else "", warp = true) }
        return when (selection) {
            is Selection.Single -> {
                val node = capable.firstOrNull { it.id == selection.nodeId }
                if (node != null) {
                    Plan(listOf(node), "☁️ WARP ${Countries.flag(node.warpCc!!)} ${Countries.name(node.warpCc)} · 經 ${node.name}", warp = true)
                } else {
                    val old = list?.nodes?.firstOrNull { it.id == selection.nodeId }
                    (old?.warpCc ?: old?.country)?.let { country(it) }
                        ?: Plan(capable.take(groupSize), "☁️ WARP（自動）", warp = true)
                }
            }
            is Selection.Country -> country(selection.code)
            Selection.Fastest -> Plan(capable.take(groupSize), "☁️ WARP · 全部最快（自動）", warp = true)
        }
    }

    private fun normalPlan(usable: List<Node>, list: NodeList?, selection: Selection, groupSize: Int): Plan? {
        return when (selection) {
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
                    null
                } else {
                    Plan(nodes, "${Countries.flag(selection.code)} ${Countries.name(selection.code)}（自動揀最快）")
                }
            }
            Selection.Fastest -> Plan(
                usable.filter { it.country != "WARP" }.ifEmpty { usable }.take(groupSize),
                "⚡ 全部最快（自動）",
            )
        }
    }

    /** Logging and the local test port used by the in-app diagnostics. */
    data class Debug(val logPath: String? = null, val verbose: Boolean = false, val diagPort: Int = 0)

    fun build(
        plan: Plan,
        inbound: Inbound = Inbound.Tun,
        warpAccount: WarpAccount? = null,
        debug: Debug = Debug(),
    ): String {
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

        // interval: how soon a dead node is noticed and replaced
        fun urltest(tag: String, members: List<String>, interval: String = "3m") = buildJsonObject {
            put("type", "urltest")
            put("tag", tag)
            putJsonArray("outbounds") { members.forEach { add(it) } }
            put("url", "https://www.gstatic.com/generate_204")
            put("interval", interval)
            put("tolerance", 100)
            put("idle_timeout", "30m")
        }

        val groups = mutableListOf<JsonObject>()
        // where everything not matched by a split rule goes
        val finalTag = if (plan.warp) {
            requireNotNull(warpAccount) { "WARP account missing" }
            val detour = if (plan.nodes.isEmpty()) {
                // straight from the phone, but through the "direct" outbound: the same proven socket path as
                // the node chain, instead of sing-box 1.14's per-interface WireGuard sockets
                "direct"
            } else {
                // checked every minute: when the node in use dies, the group moves to another node of the
                // same WARP country and sing-box re-dials WARP's WireGuard session through it
                groups += urltest("warp-up", tagsFor(plan.nodes), "1m")
                "warp-up"
            }
            endpoints += Warp.endpoint(warpAccount, "warp", detour)
            "warp"
        } else {
            groups += urltest("proxy", tagsFor(plan.nodes))
            "proxy"
        }
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
            putJsonObject("log") {
                put("level", if (debug.verbose) "debug" else "info")
                put("timestamp", true)
                debug.logPath?.let { put("output", it) }
            }
            putJsonObject("dns") {
                putJsonArray("servers") {
                    addJsonObject {
                        put("type", "https")
                        put("tag", "remote")
                        put("server", "1.1.1.1")
                        put("detour", finalTag)
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
                if (debug.diagPort > 0) {
                    // local-only port the diagnostics screen uses to test the live connection
                    addJsonObject {
                        put("type", "mixed")
                        put("tag", "diag-in")
                        put("listen", "127.0.0.1")
                        put("listen_port", debug.diagPort)
                    }
                }
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
                put("final", finalTag)
                put("auto_detect_interface", true)
                put("default_domain_resolver", "local")
            }
        }
        return root.toString()
    }
}
