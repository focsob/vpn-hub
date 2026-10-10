package com.vpnhub.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.Locale

val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class NodeList(
    val version: Int = 1,
    val updated: String = "",
    val count: Int = 0,
    val countries: Map<String, CountryInfo> = emptyMap(),
    val nodes: List<Node> = emptyList(),
)

@Serializable
data class CountryInfo(
    val count: Int = 0,
    val best: Int = 0,
    val protocols: Map<String, Int> = emptyMap(),
    @SerialName("ip_types") val ipTypes: Map<String, Int> = emptyMap(),
)

@Serializable
data class Node(
    val id: String,
    val name: String,
    val protocol: String,
    val country: String,
    val latency: Int = 0,
    val kind: String,
    val config: JsonObject? = null,
    val link: String? = null,
    val ovpn: String? = null,
    @SerialName("ip_type") val ipType: String = "unknown",
    val isp: String = "",
) {
}

/** What the user picked on the main screen. */
sealed class Selection {
    data object Fastest : Selection()
    data class Country(val code: String) : Selection()
    data class Single(val nodeId: String) : Selection()

    fun encode(): String = when (this) {
        Fastest -> "auto"
        is Country -> "country:$code"
        is Single -> "node:$nodeId"
    }

    companion object {
        fun decode(raw: String?): Selection = when {
            raw == null || raw == "auto" -> Fastest
            raw.startsWith("country:") -> Country(raw.removePrefix("country:"))
            raw.startsWith("node:") -> Single(raw.removePrefix("node:"))
            else -> Fastest
        }
    }
}

object Protocols {
    val all = listOf(
        "vless", "vmess", "trojan", "shadowsocks", "hysteria2", "hysteria", "tuic", "anytls",
        "wireguard", "openvpn", "naive", "snell", "ssh", "socks", "http",
    )

    /** New protocols the app does not know yet are always shown. */
    fun matches(node: Node, enabled: Set<String>): Boolean = node.protocol !in all || node.protocol in enabled

    fun label(p: String) = when (p) {
        "vless" -> "VLESS"
        "vmess" -> "VMess"
        "trojan" -> "Trojan"
        "hysteria2" -> "Hysteria2"
        "wireguard" -> "WireGuard"
        "openvpn" -> "OpenVPN"
        "shadowsocks" -> "Shadowsocks"
        "hysteria" -> "Hysteria"
        "tuic" -> "TUIC"
        "anytls" -> "AnyTLS"
        "naive" -> "NaiveProxy"
        "snell" -> "Snell"
        "ssh" -> "SSH"
        "socks" -> "SOCKS5"
        "http" -> "HTTP"
        else -> p
    }
}

/** Kind of network the exit IP belongs to. */
object IpTypes {
    val all = listOf("dc", "residential", "isp", "mobile")

    fun label(t: String) = when (t) {
        "dc" -> "Data Centre"
        "residential" -> "Residential"
        "isp" -> "ISP"
        "mobile" -> "Mobile"
        else -> "未知"
    }

    fun short(t: String) = when (t) {
        "dc" -> "DC"
        "residential" -> "住宅"
        "isp" -> "ISP"
        "mobile" -> "流動"
        else -> "?"
    }

    /** Nodes with an unknown type are only shown while no IP type filter is active. */
    fun matches(node: Node, selected: Set<String>): Boolean =
        if (selected.containsAll(all)) true else node.ipType in selected
}

object Countries {
    private val zh = Locale.forLanguageTag("zh-Hant-HK")

    fun name(code: String): String = when (code) {
        "WARP" -> "Cloudflare WARP（就近出口）"
        "ZZ", "XX" -> "未知地區"
        "T1" -> "Tor 網絡"
        else -> runCatching { Locale.Builder().setRegion(code).build().getDisplayCountry(zh) }
            .getOrNull()?.ifBlank { null } ?: code
    }

    fun flag(code: String): String {
        if (code == "WARP") return "☁️"
        if (code.length != 2 || !code.all { it in 'A'..'Z' } || code == "ZZ" || code == "XX" || code == "T1") return "🌐"
        val base = 0x1F1E6 - 'A'.code
        return String(Character.toChars(base + code[0].code)) + String(Character.toChars(base + code[1].code))
    }
}

/** Per-app routing: apps in [packages] use nodes in [target] country (or go out directly). */
@Serializable
data class SplitRule(
    val id: String,
    val target: String,
    val packages: List<String> = emptyList(),
    val enabled: Boolean = true,
) {
    companion object {
        const val DIRECT = "DIRECT"

        fun targetLabel(target: String) =
            if (target == DIRECT) "🏠 直連（唔經 VPN）" else "${Countries.flag(target)} ${Countries.name(target)}"
    }
}
