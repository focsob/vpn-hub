package com.vpnhub.app.vpn

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Proxy mode: no VPN, only a SOCKS5/HTTP port other apps (e.g. AdGuard) can point at. */
class BoxProxyService : Service() {
    private val core by lazy { BoxCore(this, null) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = core.onStartCommand(intent)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        core.onDestroy()
        super.onDestroy()
    }
}
