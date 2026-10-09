package com.vpnhub.app.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import de.blinkt.openvpn.api.IOpenVPNAPIService
import de.blinkt.openvpn.api.IOpenVPNStatusCallback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout

/**
 * OpenVPN nodes are run by the free "OpenVPN for Android" app (package de.blinkt.openvpn)
 * through its official remote-control API, because sing-box has no OpenVPN engine.
 */
object OpenVpnBridge {
    const val PACKAGE = "de.blinkt.openvpn"
    const val STORE_URL = "https://play.google.com/store/apps/details?id=de.blinkt.openvpn"
    const val FDROID_URL = "https://f-droid.org/packages/de.blinkt.openvpn/"

    private var service: IOpenVPNAPIService? = null
    private var connection: ServiceConnection? = null

    private val _status = MutableStateFlow("")
    /** Last state reported by OpenVPN for Android, e.g. CONNECTED / NOPROCESS. */
    val status: StateFlow<String> = _status

    private val callback = object : IOpenVPNStatusCallback.Stub() {
        override fun newStatus(uuid: String?, state: String?, message: String?, level: String?) {
            _status.value = state ?: ""
        }
    }

    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0); true }.getOrDefault(false)

    private suspend fun bind(context: Context): IOpenVPNAPIService {
        service?.let { if (it.asBinder().isBinderAlive) return it }
        val ready = CompletableDeferred<IOpenVPNAPIService>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val api = IOpenVPNAPIService.Stub.asInterface(binder)
                service = api
                runCatching { api.registerStatusCallback(callback) }
                ready.complete(api)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        val intent = Intent(IOpenVPNAPIService::class.java.name).setPackage(PACKAGE)
        if (!context.applicationContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)) {
            error("未能連接 OpenVPN for Android")
        }
        connection = conn
        return withTimeout(10_000) { ready.await() }
    }

    /**
     * Returns an Intent the activity must launch first (permission dialogs), or null when the
     * profile has been handed to OpenVPN for Android and is starting.
     */
    suspend fun connect(context: Context, ovpn: String): Intent? {
        val api = bind(context)
        api.prepare(context.packageName)?.let { return it }
        api.prepareVPNService()?.let { return it }
        api.startVPN(ovpn)
        return null
    }

    suspend fun disconnect(context: Context) {
        if (!isInstalled(context)) return
        runCatching { bind(context).disconnect() }
    }
}
