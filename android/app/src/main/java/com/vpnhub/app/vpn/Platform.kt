package com.vpnhub.app.vpn

import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.system.ErrnoException
import com.vpnhub.app.App
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.RoutePrefix
import io.nekohasekai.libbox.StringIterator
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import io.nekohasekai.libbox.NetworkInterface as BoxInterface

class StringArray(private val items: List<String>) : StringIterator {
    private var index = 0
    override fun len(): Int = items.size
    override fun hasNext(): Boolean = index < items.size
    override fun next(): String = items[index++]
}

class InterfaceArray(private val iterator: Iterator<BoxInterface>) : NetworkInterfaceIterator {
    override fun hasNext(): Boolean = iterator.hasNext()
    override fun next(): BoxInterface = iterator.next()
}

fun RoutePrefix.toIpPrefix(): IpPrefix = IpPrefix(InetAddress.getByName(address()), prefix())

fun StringIterator.toList(): List<String> {
    val out = mutableListOf<String>()
    while (hasNext()) out += next()
    return out
}

/** Tracks the real (non-VPN) default network so sing-box can bind its outgoing sockets to it. */
object DefaultNetworkMonitor {
    @Volatile
    var defaultNetwork: Network? = null
        private set

    private var listener: InterfaceUpdateListener? = null
    private var registered = false
    private val handler = Handler(Looper.getMainLooper())

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            defaultNetwork = network
            notifyListener()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (network == defaultNetwork) notifyListener()
        }

        override fun onLost(network: Network) {
            if (network == defaultNetwork) {
                defaultNetwork = null
                notifyListener()
            }
        }
    }

    @Synchronized
    fun start() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        App.connectivity.registerBestMatchingNetworkCallback(request, callback, handler)
        registered = true
        if (defaultNetwork == null) defaultNetwork = App.connectivity.activeNetwork
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        runCatching { App.connectivity.unregisterNetworkCallback(callback) }
        registered = false
        defaultNetwork = null
    }

    fun setListener(l: InterfaceUpdateListener?) {
        listener = l
        notifyListener()
    }

    private fun notifyListener() {
        val l = listener ?: return
        val network = defaultNetwork
        if (network == null) {
            l.updateDefaultInterface("", -1, false, false)
            return
        }
        repeat(10) {
            val props = App.connectivity.getLinkProperties(network)
            val name = props?.interfaceName
            val index = name?.let { runCatching { java.net.NetworkInterface.getByName(it)?.index }.getOrNull() }
            if (name != null && index != null) {
                val caps = App.connectivity.getNetworkCapabilities(network)
                val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
                l.updateDefaultInterface(name, index, metered, false)
                return
            }
            Thread.sleep(100)
        }
    }
}

/** DNS for resolving proxy server names through the underlying network (Android 10+ API). */
object LocalResolver : LocalDNSTransport {
    private const val RCODE_NXDOMAIN = 3
    private val executor = Executors.newCachedThreadPool()

    override fun raw(): Boolean = true

    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        val network = DefaultNetworkMonitor.defaultNetwork ?: error("missing default interface")
        val latch = CountDownLatch(1)
        val signal = CancellationSignal()
        var failure: Exception? = null
        ctx.onCancel {
            signal.cancel()
            latch.countDown()
        }
        DnsResolver.getInstance().rawQuery(
            network, message, DnsResolver.FLAG_NO_RETRY, executor, signal,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int) {
                    if (rcode == 0) ctx.rawSuccess(answer) else ctx.errorCode(rcode)
                    latch.countDown()
                }

                override fun onError(error: DnsResolver.DnsException) {
                    val cause = error.cause
                    if (cause is ErrnoException) ctx.errnoCode(cause.errno) else failure = error
                    latch.countDown()
                }
            },
        )
        if (!latch.await(15, TimeUnit.SECONDS)) {
            signal.cancel()
            error("dns timeout")
        }
        failure?.let { throw it }
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        val net = DefaultNetworkMonitor.defaultNetwork ?: error("missing default interface")
        val latch = CountDownLatch(1)
        val signal = CancellationSignal()
        var failure: Exception? = null
        ctx.onCancel {
            signal.cancel()
            latch.countDown()
        }
        val callback = object : DnsResolver.Callback<List<InetAddress>> {
            override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                if (rcode == 0) {
                    ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
                } else {
                    ctx.errorCode(rcode)
                }
                latch.countDown()
            }

            override fun onError(error: DnsResolver.DnsException) {
                val cause = error.cause
                if (cause is ErrnoException) ctx.errnoCode(cause.errno) else failure = error
                latch.countDown()
            }
        }
        val type = when {
            network.endsWith("4") -> DnsResolver.TYPE_A
            network.endsWith("6") -> DnsResolver.TYPE_AAAA
            else -> null
        }
        if (type != null) {
            DnsResolver.getInstance().query(net, domain, type, DnsResolver.FLAG_NO_RETRY, executor, signal, callback)
        } else {
            DnsResolver.getInstance().query(net, domain, DnsResolver.FLAG_NO_RETRY, executor, signal, callback)
        }
        if (!latch.await(15, TimeUnit.SECONDS)) {
            signal.cancel()
            ctx.errorCode(RCODE_NXDOMAIN)
            return
        }
        failure?.let { throw it }
    }
}
