package com.vpnhub.app.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import android.util.Log
import com.vpnhub.app.App
import com.vpnhub.app.R
import com.vpnhub.app.data.ConfigBuilder
import com.vpnhub.app.data.NodeRepository
import com.vpnhub.app.data.Prefs
import com.vpnhub.app.ui.MainActivity
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.security.KeyStore
import java.util.Base64
import io.nekohasekai.libbox.Notification as BoxNotification
import io.nekohasekai.libbox.NetworkInterface as BoxInterface

/**
 * Android VpnService that hosts the sing-box core (libbox).
 * It implements libbox's PlatformInterface so the core can open the TUN device and
 * bind sockets to the physical network.
 */
class BoxVpnService : VpnService(), PlatformInterface, CommandServerHandler {

    companion object {
        const val ACTION_START = "com.vpnhub.app.START"
        const val ACTION_RELOAD = "com.vpnhub.app.RELOAD"
        const val ACTION_STOP = "com.vpnhub.app.STOP"
        private const val TAG = "BoxVpnService"
        private const val NOTIFICATION_ID = 1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var commandServer: CommandServer? = null
    private var tunFd: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> scope.launch { stopTunnel() }
            ACTION_RELOAD -> scope.launch { reloadTunnel() }
            else -> {
                // ACTION_START, or the system restarting an always-on VPN
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("連線中…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
                )
                scope.launch { startTunnel() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // another VPN took over (e.g. OpenVPN for Android)
        scope.launch { stopTunnel() }
    }

    override fun onDestroy() {
        scope.launch { stopTunnel() }.invokeOnCompletion { scope.cancel() }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ lifecycle

    private fun currentConfig(): Pair<String, String>? {
        val plan = ConfigBuilder.plan(
            NodeRepository.list.value,
            Prefs.selection.value,
            Prefs.protocols.value,
            Prefs.ipTypes.value,
            Prefs.groupSize,
        ) ?: return null
        return ConfigBuilder.build(plan) to plan.label
    }

    private fun overrideOptions() = OverrideOptions().apply {
        // keep this app's own traffic (hourly list download) off the tunnel
        excludePackage = StringArray(listOf(packageName))
    }

    private suspend fun startTunnel() = lock.withLock {
        if (commandServer != null) return@withLock
        VpnState._status.value = VpnState.Status.Starting
        VpnState._error.value = null
        try {
            if (NodeRepository.list.value == null) NodeRepository.refresh()
            val (config, label) = currentConfig()
                ?: error("冇可用節點，請先更新節點清單或調整協定篩選")
            DefaultNetworkMonitor.start()
            val server = CommandServer(this, this)
            server.start()
            commandServer = server
            server.startOrReloadService(config, overrideOptions())
            VpnState._label.value = label
            VpnState._status.value = VpnState.Status.Connected
            updateNotification("已連線：$label")
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            VpnState._error.value = "連線失敗：${e.message}"
            teardown()
        }
    }

    private suspend fun reloadTunnel() = lock.withLock {
        val server = commandServer ?: return@withLock
        try {
            val (config, label) = currentConfig() ?: error("冇可用節點")
            server.startOrReloadService(config, overrideOptions())
            VpnState._label.value = label
            updateNotification("已連線：$label")
        } catch (e: Exception) {
            Log.e(TAG, "reload failed", e)
            VpnState._error.value = "切換失敗：${e.message}"
        }
    }

    private suspend fun stopTunnel() = lock.withLock {
        if (VpnState.status.value == VpnState.Status.Stopped && commandServer == null) {
            stopSelfCompat()
            return@withLock
        }
        VpnState._status.value = VpnState.Status.Stopping
        teardown()
    }

    private fun teardown() {
        commandServer?.let { server ->
            runCatching { server.closeService() }
            runCatching { server.close() }
        }
        commandServer = null
        runCatching { tunFd?.close() }
        tunFd = null
        DefaultNetworkMonitor.stop()
        VpnState._label.value = ""
        VpnState._status.value = VpnState.Status.Stopped
        stopSelfCompat()
    }

    private fun stopSelfCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------ notification

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BoxVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, App.CHANNEL_VPN)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "斷開", stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(android.app.NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ------------------------------------------------------------------ CommandServerHandler

    override fun serviceStop() {
        scope.launch { stopTunnel() }
    }

    override fun serviceReload() {
        scope.launch { reloadTunnel() }
    }

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
        available = false
        enabled = false
    }

    override fun setSystemProxyEnabled(isEnabled: Boolean) {}

    override fun writeDebugMessage(message: String?) {
        Log.d(TAG, message ?: "")
    }

    // ------------------------------------------------------------------ PlatformInterface

    override fun localDNSTransport(): LocalDNSTransport = LocalResolver

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("缺少 VPN 權限")
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(options.mtu)
            .setMetered(false)

        val v4 = options.inet4Address
        while (v4.hasNext()) v4.next().let { builder.addAddress(it.address(), it.prefix()) }
        val v6 = options.inet6Address
        while (v6.hasNext()) v6.next().let { builder.addAddress(it.address(), it.prefix()) }

        if (options.autoRoute) {
            builder.addDnsServer(options.dnsServerAddress.value)

            val r4 = options.inet4RouteAddress
            if (r4.hasNext()) {
                while (r4.hasNext()) builder.addRoute(r4.next().toIpPrefix())
            } else if (options.inet4Address.hasNext()) {
                builder.addRoute("0.0.0.0", 0)
            }
            val r6 = options.inet6RouteAddress
            if (r6.hasNext()) {
                while (r6.hasNext()) builder.addRoute(r6.next().toIpPrefix())
            } else if (options.inet6Address.hasNext()) {
                builder.addRoute("::", 0)
            }
            val x4 = options.inet4RouteExcludeAddress
            while (x4.hasNext()) builder.excludeRoute(x4.next().toIpPrefix())
            val x6 = options.inet6RouteExcludeAddress
            while (x6.hasNext()) builder.excludeRoute(x6.next().toIpPrefix())

            val include = options.includePackage
            while (include.hasNext()) {
                runCatching { builder.addAllowedApplication(include.next()) }
            }
            val exclude = options.excludePackage
            while (exclude.hasNext()) {
                runCatching { builder.addDisallowedApplication(exclude.next()) }
            }
        }

        val pfd = builder.establish() ?: error("VPN 權限被撤回")
        tunFd = pfd
        return pfd.fd
    }

    override fun useProcFS(): Boolean = false

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        val uid = App.connectivity.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort),
        )
        if (uid == Process.INVALID_UID) error("connection owner not found")
        val packages = packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        return ConnectionOwner().apply {
            userId = uid
            userName = packages.firstOrNull() ?: ""
            setAndroidPackageNames(StringArray(packages))
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        DefaultNetworkMonitor.setListener(listener)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        DefaultNetworkMonitor.setListener(null)
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val javaInterfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val result = mutableListOf<BoxInterface>()
        @Suppress("DEPRECATION")
        for (network in App.connectivity.allNetworks) {
            val props = App.connectivity.getLinkProperties(network) ?: continue
            val caps = App.connectivity.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val ni = javaInterfaces.find { it.name == props.interfaceName } ?: continue
            result += BoxInterface().apply {
                name = props.interfaceName
                index = ni.index
                runCatching { mtu = ni.mtu }
                dnsServer = StringArray(props.dnsServers.mapNotNull { it.hostAddress })
                type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                addresses = StringArray(
                    ni.interfaceAddresses.map {
                        val host = if (it.address is Inet6Address) {
                            Inet6Address.getByAddress(it.address.address).hostAddress
                        } else {
                            it.address.hostAddress
                        }
                        "$host/${it.networkPrefixLength}"
                    },
                )
                var f = 0
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    f = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                }
                if (ni.isLoopback) f = f or OsConstants.IFF_LOOPBACK
                if (ni.isPointToPoint) f = f or OsConstants.IFF_POINTOPOINT
                if (ni.supportsMulticast()) f = f or OsConstants.IFF_MULTICAST
                flags = f
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }
        return InterfaceArray(result.iterator())
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun readWIFIState(): WIFIState? = null

    override fun systemCertificates(): StringIterator {
        val certs = mutableListOf<String>()
        runCatching {
            val ks = KeyStore.getInstance("AndroidCAStore")
            ks.load(null, null)
            val aliases = ks.aliases()
            val enc = Base64.getMimeEncoder(64, "\n".toByteArray())
            while (aliases.hasMoreElements()) {
                val cert = ks.getCertificate(aliases.nextElement()) ?: continue
                certs += "-----BEGIN CERTIFICATE-----\n" + enc.encodeToString(cert.encoded) +
                    "\n-----END CERTIFICATE-----"
            }
        }
        return StringArray(certs)
    }

    override fun clearDNSCache() {}

    override fun sendNotification(notification: BoxNotification) {
        // core notifications (e.g. rule-set updates) are not used by this app
    }

    @Suppress("unused")
    private fun hasPermission(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
}
