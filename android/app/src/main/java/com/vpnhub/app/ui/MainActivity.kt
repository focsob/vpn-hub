package com.vpnhub.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.vpnhub.app.data.NodeRepository
import com.vpnhub.app.vpn.VpnController
import com.vpnhub.app.vpn.VpnState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            VpnController.start(this)
        } else {
            toast("未授予 VPN 權限")
        }
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
                    onSettingsSaved = ::applySettings,
                    onSelectionChanged = { VpnController.reload(this) },
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
        val intent = if (VpnController.needsVpnPermission()) VpnService.prepare(this) else null
        if (intent != null) vpnPermission.launch(intent) else VpnController.start(this)
    }

    /** Called after settings change; restarts the service when the mode switched while running. */
    private fun applySettings(modeChanged: Boolean, urlChanged: Boolean) {
        if (urlChanged) refresh()
        val running = VpnState.status.value == VpnState.Status.Connected
        if (modeChanged && running) {
            VpnController.stop(this)
            lifecycleScope.launch {
                VpnState.status.first { it == VpnState.Status.Stopped }
                connect()
            }
        } else if (running) {
            VpnController.reload(this)
        }
    }


    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
