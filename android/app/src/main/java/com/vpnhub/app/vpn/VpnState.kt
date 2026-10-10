package com.vpnhub.app.vpn

import android.content.Context
import android.content.Intent
import com.vpnhub.app.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object VpnState {
    enum class Status { Stopped, Starting, Connected, Stopping }

    internal val _status = MutableStateFlow(Status.Stopped)
    val status: StateFlow<Status> = _status

    internal val _label = MutableStateFlow("")
    /** Human readable description of what the tunnel is using. */
    val label: StateFlow<String> = _label

    internal val _mode = MutableStateFlow(Prefs.MODE_VPN)
    /** Mode of the running service (VPN or proxy). */
    val mode: StateFlow<String> = _mode

    internal val _splitWarning = MutableStateFlow<String?>(null)
    /** Shown when some split-routing rules cannot be applied right now. */
    val splitWarning: StateFlow<String?> = _splitWarning

    internal val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun clearError() {
        _error.value = null
    }
}

object VpnController {
    private fun serviceFor(mode: String): Class<*> =
        if (mode == Prefs.MODE_PROXY) BoxProxyService::class.java else BoxVpnService::class.java

    /** Whether starting in the chosen mode needs the system VPN permission dialog. */
    fun needsVpnPermission(): Boolean = Prefs.mode != Prefs.MODE_PROXY

    fun start(context: Context) {
        Prefs.wantRunning = true
        context.startForegroundService(Intent(context, serviceFor(Prefs.mode)).setAction(BoxCore.ACTION_START))
    }

    /** Rebuild the config (new selection or new node list) without dropping the tunnel. */
    fun reload(context: Context) {
        if (VpnState.status.value != VpnState.Status.Connected) return
        runCatching {
            context.startService(Intent(context, serviceFor(VpnState.mode.value)).setAction(BoxCore.ACTION_RELOAD))
        }
    }

    fun stop(context: Context) {
        Prefs.wantRunning = false
        if (VpnState.status.value == VpnState.Status.Stopped) return
        runCatching {
            context.startService(Intent(context, serviceFor(VpnState.mode.value)).setAction(BoxCore.ACTION_STOP))
        }
    }
}
