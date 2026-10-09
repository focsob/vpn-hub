package com.vpnhub.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.vpnhub.app.data.Node
import com.vpnhub.app.data.NodeRepository
import com.vpnhub.app.vpn.OpenVpnBridge
import com.vpnhub.app.vpn.VpnController
import com.vpnhub.app.vpn.VpnState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            VpnController.start(this)
        } else {
            toast("未授予 VPN 權限")
        }
    }

    private var pendingOpenVpn: Node? = null
    private val openVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val node = pendingOpenVpn
        pendingOpenVpn = null
        if (it.resultCode == RESULT_OK && node != null) connectOpenVpn(node) else toast("OpenVPN 授權已取消")
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            AppTheme {
                MainScreen(
                    onConnect = ::connect,
                    onDisconnect = { VpnController.stop(this) },
                    onRefresh = ::refresh,
                    onOpenVpn = ::connectOpenVpn,
                    onSelectionChanged = { VpnController.reload(this) },
                    onInstallOpenVpn = ::installOpenVpn,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val stale = System.currentTimeMillis() - NodeRepository.fetchedAt > 60 * 60 * 1000L
        if (NodeRepository.list.value == null || stale) refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            NodeRepository.refresh().onSuccess {
                if (VpnState.status.value == VpnState.Status.Connected) VpnController.reload(this@MainActivity)
            }
        }
    }

    private fun connect() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else VpnController.start(this)
    }

    private fun connectOpenVpn(node: Node) {
        val config = node.ovpn ?: return
        if (!OpenVpnBridge.isInstalled(this)) {
            installOpenVpn()
            return
        }
        VpnController.stop(this) // only one VPN can run at a time
        lifecycleScope.launch {
            runCatching { OpenVpnBridge.connect(this@MainActivity, config) }
                .onSuccess { intent ->
                    if (intent != null) {
                        pendingOpenVpn = node
                        openVpnPermission.launch(intent)
                    } else {
                        toast("已交由 OpenVPN for Android 連線：${node.name}")
                    }
                }
                .onFailure { toast("OpenVPN 啟動失敗：${it.message}") }
        }
    }

    private fun installOpenVpn() {
        toast("OpenVPN 節點需要先安裝免費嘅「OpenVPN for Android」")
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${OpenVpnBridge.PACKAGE}"))
        runCatching { startActivity(market) }.onFailure {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OpenVpnBridge.FDROID_URL)))
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
