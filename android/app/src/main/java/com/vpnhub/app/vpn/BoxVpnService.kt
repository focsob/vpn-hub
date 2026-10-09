package com.vpnhub.app.vpn

import android.content.Intent
import android.net.VpnService

/** VPN mode: every app goes through the tunnel. */
class BoxVpnService : VpnService() {
    private val core by lazy { BoxCore(this, this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = core.onStartCommand(intent)

    override fun onRevoke() = core.onRevoke()

    override fun onDestroy() {
        core.onDestroy()
        super.onDestroy()
    }
}
