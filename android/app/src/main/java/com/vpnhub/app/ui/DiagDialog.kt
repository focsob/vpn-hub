package com.vpnhub.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.getSystemService
import com.vpnhub.app.BuildConfig
import com.vpnhub.app.data.Prefs
import com.vpnhub.app.vpn.Diagnostics
import kotlinx.coroutines.launch

/** Connection test + core log, with a copy button so the user can send it for troubleshooting. */
@Composable
fun DiagDialog(onDismiss: () -> Unit, onVerboseChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }
    var verbose by remember { mutableStateOf(Prefs.verboseLog) }

    fun runTest() {
        running = true
        scope.launch {
            result = Diagnostics.run()
            log = Diagnostics.logTail()
            running = false
        }
    }
    LaunchedEffect(Unit) { log = Diagnostics.logTail() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(Modifier.fillMaxSize().padding(12.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🩺 連線診斷", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = ::runTest, enabled = !running) { Text("測試連線") }
                    if (running) CircularProgressIndicator(Modifier.height(24.dp))
                }
                result?.let {
                    SelectionContainer { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("詳細記錄（debug）")
                        Text("查問題時先開，會重新連線；平時關咗慳電", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = verbose, onCheckedChange = {
                        verbose = it
                        Prefs.verboseLog = it
                        onVerboseChanged()
                    })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { log = Diagnostics.logTail() }) { Text("重新載入記錄") }
                    OutlinedButton(onClick = {
                        val text = "VPN Hub ${BuildConfig.VERSION_NAME}\n\n${result.orEmpty()}\n\n$log"
                        context.getSystemService<ClipboardManager>()
                            ?.setPrimaryClip(ClipData.newPlainText("VPN Hub log", text))
                        Toast.makeText(context, "已複製測試結果同記錄", Toast.LENGTH_SHORT).show()
                    }) { Text("複製全部") }
                }
                Card(Modifier.fillMaxWidth().weight(1f)) {
                    SelectionContainer {
                        Text(
                            log,
                            Modifier.padding(8.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            softWrap = false,
                        )
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("關閉") }
            }
        }
    }
}
