package com.vpnhub.app.data

import android.content.Context
import android.content.SharedPreferences
import com.vpnhub.app.App
import com.vpnhub.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object Prefs {
    private val sp: SharedPreferences by lazy { App.instance.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private val _selection = MutableStateFlow(Selection.decode(sp.getString("selection", null)))
    val selection: StateFlow<Selection> = _selection

    private val _protocols = MutableStateFlow(sp.getStringSet("protocols", null)?.toSet() ?: Protocols.all.toSet())

    /** Protocols the user wants to see / use. */
    val protocols: StateFlow<Set<String>> = _protocols

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

    /** Wanted on/off state, so the hourly job knows whether to reload the tunnel. */
    var wantRunning: Boolean
        get() = sp.getBoolean("want_running", false)
        set(v) = sp.edit().putBoolean("want_running", v).apply()

    fun select(s: Selection) {
        _selection.value = s
        sp.edit().putString("selection", s.encode()).apply()
    }

    fun setProtocols(set: Set<String>) {
        val v = set.ifEmpty { Protocols.all.toSet() }
        _protocols.value = v
        sp.edit().putStringSet("protocols", v).apply()
    }
}
