package com.vpnhub.app.data

import android.content.Context
import android.content.SharedPreferences
import com.vpnhub.app.App
import com.vpnhub.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object Prefs {
    const val MODE_VPN = "vpn"
    const val MODE_PROXY = "proxy"

    private val sp: SharedPreferences by lazy { App.instance.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private val _selection = MutableStateFlow(Selection.decode(sp.getString("selection", null)))
    val selection: StateFlow<Selection> = _selection

    // Stored as the switched-off set, so protocols added in later versions start switched on
    private val _protocols = MutableStateFlow(Protocols.all.toSet() - (sp.getStringSet("protocols_off", null) ?: emptySet()))

    /** Protocols the user wants to see / use. */
    val protocols: StateFlow<Set<String>> = _protocols

    private val _ipTypes = MutableStateFlow(sp.getStringSet("ip_types", null)?.toSet() ?: IpTypes.all.toSet())

    /** Exit IP types (Data Centre / Residential / ISP / Mobile) the user wants. */
    val ipTypes: StateFlow<Set<String>> = _ipTypes

    var nodesUrl: String
        get() = sp.getString("nodes_url", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.NODES_URL
        set(v) = sp.edit().putString("nodes_url", v.trim()).apply()

    var autoUpdate: Boolean
        get() = sp.getBoolean("auto_update", true)
        set(v) = sp.edit().putBoolean("auto_update", v).apply()

    /** How many of the fastest nodes go into an automatic (urltest) group. */
    var groupSize: Int
        get() = sp.getInt("group_size", 20)
        set(v) = sp.edit().putInt("group_size", v.coerceIn(1, 100)).apply()

    /** VPN mode (whole phone through a TUN) or proxy mode (SOCKS5/HTTP port only). */
    var mode: String
        get() = sp.getString("mode", MODE_VPN) ?: MODE_VPN
        set(v) = sp.edit().putString("mode", v).apply()

    var proxyPort: Int
        get() = sp.getInt("proxy_port", 10808)
        set(v) = sp.edit().putInt("proxy_port", v.coerceIn(1024, 65535)).apply()

    /** Listen on all interfaces so other devices on the same Wi-Fi / hotspot can use the proxy. */
    var proxyAllowLan: Boolean
        get() = sp.getBoolean("proxy_allow_lan", false)
        set(v) = sp.edit().putBoolean("proxy_allow_lan", v).apply()

    /** Wanted on/off state, so the hourly job knows whether to reload the tunnel. */
    var wantRunning: Boolean
        get() = sp.getBoolean("want_running", false)
        set(v) = sp.edit().putBoolean("want_running", v).apply()

    fun select(s: Selection) {
        _selection.value = s
        sp.edit().putString("selection", s.encode()).apply()
    }

    fun setIpTypes(set: Set<String>) {
        val v = set.ifEmpty { IpTypes.all.toSet() }
        _ipTypes.value = v
        sp.edit().putStringSet("ip_types", v).apply()
    }

    fun setProtocols(set: Set<String>) {
        val v = set.ifEmpty { Protocols.all.toSet() }
        _protocols.value = v
        sp.edit().putStringSet("protocols_off", Protocols.all.toSet() - v).apply()
    }
}
