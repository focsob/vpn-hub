package com.vpnhub.app.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.vpnhub.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Quick-settings tile to toggle the tunnel from the notification shade. */
class QuickTile : TileService() {
    private var job: Job? = null

    override fun onStartListening() {
        job = CoroutineScope(Dispatchers.Main).launch {
            VpnState.status.collect { refresh(it) }
        }
    }

    override fun onStopListening() {
        job?.cancel()
        job = null
    }

    override fun onClick() {
        when (VpnState.status.value) {
            VpnState.Status.Connected, VpnState.Status.Starting -> VpnController.stop(this)
            else -> {
                if (VpnService.prepare(this) == null) {
                    VpnController.start(this)
                } else {
                    val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivityAndCollapse(
                        PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE),
                    )
                }
            }
        }
    }

    private fun refresh(status: VpnState.Status) {
        val tile = qsTile ?: return
        tile.state = when (status) {
            VpnState.Status.Connected -> Tile.STATE_ACTIVE
            VpnState.Status.Stopped -> Tile.STATE_INACTIVE
            else -> Tile.STATE_UNAVAILABLE
        }
        tile.subtitle = when (status) {
            VpnState.Status.Connected -> "已連線"
            VpnState.Status.Starting -> "連線中"
            VpnState.Status.Stopping -> "斷開中"
            VpnState.Status.Stopped -> "未連線"
        }
        tile.updateTile()
    }
}
