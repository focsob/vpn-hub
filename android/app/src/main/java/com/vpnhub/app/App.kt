package com.vpnhub.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import androidx.core.content.getSystemService
import com.vpnhub.app.data.NodeRepository
import com.vpnhub.app.data.UpdateWorker
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        val working = File(filesDir, "box").apply { mkdirs() }
        Libbox.setup(
            SetupOptions().also {
                it.basePath = filesDir.path
                it.workingPath = working.path
                it.tempPath = cacheDir.path
                it.fixAndroidStack = true
                it.logMaxLines = 500
                it.debug = BuildConfig.DEBUG
            },
        )

        getSystemService<NotificationManager>()!!.createNotificationChannel(
            NotificationChannel(CHANNEL_VPN, "VPN 連線狀態", NotificationManager.IMPORTANCE_LOW),
        )

        NodeRepository.loadCache(this)
        UpdateWorker.schedule(this)
    }

    companion object {
        const val CHANNEL_VPN = "vpn"
        lateinit var instance: App
            private set

        val connectivity: ConnectivityManager by lazy { instance.getSystemService<ConnectivityManager>()!! }
        val wifi: WifiManager by lazy { instance.getSystemService<WifiManager>()!! }
    }
}
