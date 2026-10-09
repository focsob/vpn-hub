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

    internal val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun clearError() {
        _error.value = null
    }
}

object VpnController {
    fun start(context: Context) {
        Prefs.wantRunning = true
        context.startForegroundService(Intent(context, BoxVpnService::class.java).setAction(BoxVpnService.ACTION_START))
    }

    /** Rebuild the config (new selection or new node list) without dropping the tunnel. */
    fun reload(context: Context) {
        if (VpnState.status.value != VpnState.Status.Connected) return
        context.startService(Intent(context, BoxVpnService::class.java).setAction(BoxVpnService.ACTION_RELOAD))
    }

    fun stop(context: Context) {
        Prefs.wantRunning = false
        if (VpnState.status.value == VpnState.Status.Stopped) return
        context.startService(Intent(context, BoxVpnService::class.java).setAction(BoxVpnService.ACTION_STOP))
    }
}
