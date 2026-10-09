package com.vpnhub.app.vpn

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
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
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
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
import io.nekohasekai.libbox.Notification as BoxNotification
import io.nekohasekai.libbox.NetworkInterface as BoxInterface

/**
 * Hosts the sing-box core (libbox) inside an Android service.
 * [vpn] is the VpnService in VPN mode, or null in proxy mode (SOCKS5/HTTP port only, no TUN).
 */
class BoxCore(private val service: Service, private val vpn: VpnService?) : PlatformInterface, CommandServerHandler {

    companion object {
        const val ACTION_START = "com.vpnhub.app.START"
        const val ACTION_RELOAD = "com.vpnhub.app.RELOAD"
        const val ACTION_STOP = "com.vpnhub.app.STOP"
        private const val TAG = "BoxCore"
        private const val NOTIFICATION_ID = 1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var commandServer: CommandServer? = null
    private var tunFd: ParcelFileDescriptor? = null
    private val proxyMode get() = vpn == null

    fun onStartCommand(intent: Intent?): Int {
        when (intent?.action) {
            ACTION_STOP -> scope.launch { stop() }
            ACTION_RELOAD -> scope.launch { reload() }
            else -> {
                service.startForeground(
                    NOTIFICATION_ID,
                    buildNotification("連線中…"),
                    if (proxyMode) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                    },
                )
                scope.launch { start() }
            }
        }
        return Service.START_NOT_STICKY
    }

    fun onRevoke() {
        scope.launch { stop() }
    }

    fun onDestroy() {
        scope.launch { stop() }.invokeOnCompletion { scope.cancel() }
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
        val inbound = if (proxyMode) {
            ConfigBuilder.Inbound.Proxy(Prefs.proxyPort, Prefs.proxyAllowLan)
        } else {
            ConfigBuilder.Inbound.Tun
        }
        return ConfigBuilder.build(plan, inbound) to plan.label
    }

    private fun overrideOptions() = OverrideOptions().apply {
        // keep this app's own traffic (hourly list download) off the tunnel
        if (!proxyMode) excludePackage = StringArray(listOf(service.packageName))
    }

    private fun describe(label: String) = if (proxyMode) {
        val host = if (Prefs.proxyAllowLan) (localIpv4() ?: "0.0.0.0") else "127.0.0.1"
        "$label\n代理：socks5://$host:${Prefs.proxyPort}"
    } else {
        label
    }

    private suspend fun start() = lock.withLock {
        if (commandServer != null) return@withLock
        VpnState._status.value = VpnState.Status.Starting
        VpnState._error.value = null
        VpnState._mode.value = if (proxyMode) Prefs.MODE_PROXY else Prefs.MODE_VPN
        try {
            if (NodeRepository.list.value == null) NodeRepository.refresh()
            val (config, label) = currentConfig()
                ?: error("冇可用節點，請先更新節點清單或調整篩選")
            DefaultNetworkMonitor.start()
            val server = CommandServer(this, this)
            server.start()
            commandServer = server
            server.startOrReloadService(config, overrideOptions())
            VpnState._label.value = describe(label)
            VpnState._status.value = VpnState.Status.Connected
            updateNotification("已連線：${describe(label)}")
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            VpnState._error.value = "連線失敗：${e.message}"
            teardown()
        }
    }

    private suspend fun reload() = lock.withLock {
        val server = commandServer ?: return@withLock
        try {
            val (config, label) = currentConfig() ?: error("冇可用節點")
            server.startOrReloadService(config, overrideOptions())
            VpnState._label.value = describe(label)
            updateNotification("已連線：${describe(label)}")
        } catch (e: Exception) {
            Log.e(TAG, "reload failed", e)
            VpnState._error.value = "切換失敗：${e.message}"
        }
    }

    private suspend fun stop() = lock.withLock {
        if (VpnState.status.value == VpnState.Status.Stopped && commandServer == null) {
            stopService()
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
        stopService()
    }

    private fun stopService() {
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        service.stopSelf()
    }

    // ------------------------------------------------------------------ notification

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            service, 0, Intent(service, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            service, 1, Intent(service, service.javaClass).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(service, App.CHANNEL_VPN)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(service.getString(R.string.app_name) + if (proxyMode) "（代理模式）" else "")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "斷開", stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        service.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun localIpv4(): String? = runCatching {
        val network = DefaultNetworkMonitor.defaultNetwork ?: App.connectivity.activeNetwork
        App.connectivity.getLinkProperties(network)?.linkAddresses
            ?.firstOrNull { it.address is java.net.Inet4Address }?.address?.hostAddress
    }.getOrNull()

    // ------------------------------------------------------------------ CommandServerHandler

    override fun serviceStop() {
        scope.launch { stop() }
    }

    override fun serviceReload() {
        scope.launch { reload() }
    }

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
        available = false
        enabled = false
    }

    override fun setSystemProxyEnabled(isEnabled: Boolean) {}

    override fun writeDebugMessage(message: String?) {
        Log.d(TAG, message ?: "")
    }

    override fun triggerNativeCrash() {}

    override fun connectSSHAgent(): Int = -1

    // ------------------------------------------------------------------ PlatformInterface

    override fun localDNSTransport(): LocalDNSTransport = LocalResolver

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        vpn?.protect(fd)
    }

    override fun openTun(options: TunOptions): Int {
        val vpn = vpn ?: error("代理模式唔會開 TUN")
        if (VpnService.prepare(service) != null) error("缺少 VPN 權限")
        val builder = vpn.Builder()
            .setSession(service.getString(R.string.app_name))
            .setMtu(options.mtu)
            .setMetered(false)

        val v4 = options.inet4Address
        while (v4.hasNext()) v4.next().let { builder.addAddress(it.address(), it.prefix()) }
        val v6 = options.inet6Address
        while (v6.hasNext()) v6.next().let { builder.addAddress(it.address(), it.prefix()) }

        if (options.autoRoute) {
            val dns = options.dnsServerAddress
            while (dns.hasNext()) builder.addDnsServer(dns.next())

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
        val packages = service.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
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

    override fun clearDNSCache() {}

    override fun sendNotification(notification: BoxNotification?) {}

    override fun cancelNotification(identifier: String?, typeID: Int) {}

    // Features of the core this app does not use (LAN neighbours, SSH shell, Tailscale, bridges)
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) {}

    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {}

    override fun registerMyInterface(name: String?) {}

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() {
        error("not supported")
    }

    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int,
    ): ShellSession = error("not supported")

    override fun lookupUser(username: String?): PlatformUser = error("not supported")

    override fun lookupSFTPServer(): String = error("not supported")

    override fun readSystemSSHHostKey(): String = error("not supported")

    override fun tailscaleHostname(): String = "vpnhub"

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions?): BridgeSession = error("not supported")
}
