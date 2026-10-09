package com.vpnhub.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vpnhub.app.data.Prefs
import com.vpnhub.app.vpn.OpenVpnBridge

@Composable
fun SettingsDialog(onDismiss: () -> Unit, onSaved: () -> Unit, onInstallOpenVpn: () -> Unit) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(Prefs.nodesUrl) }
    var auto by remember { mutableStateOf(Prefs.autoUpdate) }
    var size by remember { mutableStateOf(Prefs.groupSize.toString()) }
    val openVpnInstalled = remember { OpenVpnBridge.isInstalled(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("設定") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("節點清單網址（nodes.json）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = size,
                    onValueChange = { size = it.filter(Char::isDigit).take(3) },
                    label = { Text("自動模式用最快幾多個節點") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("每小時自動更新節點", Modifier.weight(1f))
                    Switch(checked = auto, onCheckedChange = { auto = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (openVpnInstalled) "OpenVPN for Android：已安裝" else "OpenVPN for Android：未安裝",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!openVpnInstalled) TextButton(onClick = onInstallOpenVpn) { Text("安裝") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val changed = url.trim() != Prefs.nodesUrl
                Prefs.nodesUrl = url
                Prefs.autoUpdate = auto
                size.toIntOrNull()?.let { Prefs.groupSize = it }
                onDismiss()
                if (changed) onSaved()
            }) { Text("儲存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
