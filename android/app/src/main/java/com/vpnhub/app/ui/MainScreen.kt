package com.vpnhub.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vpnhub.app.data.Countries
import com.vpnhub.app.data.IpTypes
import com.vpnhub.app.data.Node
import com.vpnhub.app.data.NodeRepository
import com.vpnhub.app.data.Prefs
import com.vpnhub.app.data.Protocols
import com.vpnhub.app.data.Selection
import com.vpnhub.app.vpn.OpenVpnBridge
import com.vpnhub.app.vpn.VpnState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatUpdated(iso: String): String =
    runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).format(timeFormat) }.getOrDefault(iso)

@Composable
private fun latencyColor(ms: Int): Color = when {
    ms <= 0 -> MaterialTheme.colorScheme.onSurfaceVariant
    ms < 400 -> Color(0xFF2E7D32)
    ms < 1000 -> Color(0xFFF9A825)
    else -> Color(0xFFC62828)
}

@Composable
private fun ipTypeColor(t: String): Color = when (t) {
    "residential" -> Color(0xFF2E7D32)
    "mobile" -> Color(0xFF1565C0)
    "isp" -> Color(0xFF6A1B9A)
    "dc" -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.outline
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
    onOpenVpn: (Node) -> Unit,
    onSelectionChanged: () -> Unit,
    onInstallOpenVpn: () -> Unit,
) {
    val list by NodeRepository.list.collectAsStateWithLifecycle()
    val refreshing by NodeRepository.refreshing.collectAsStateWithLifecycle()
    val repoError by NodeRepository.lastError.collectAsStateWithLifecycle()
    val status by VpnState.status.collectAsStateWithLifecycle()
    val label by VpnState.label.collectAsStateWithLifecycle()
    val vpnError by VpnState.error.collectAsStateWithLifecycle()
    val selection by Prefs.selection.collectAsStateWithLifecycle()
    val protocols by Prefs.protocols.collectAsStateWithLifecycle()
    val ipTypes by Prefs.ipTypes.collectAsStateWithLifecycle()
    val ovpnStatus by OpenVpnBridge.status.collectAsStateWithLifecycle()

    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var showSettings by remember { mutableStateOf(false) }

    val visible = list?.nodes.orEmpty().filter { it.protocol in protocols && IpTypes.matches(it, ipTypes) }
    val byCountry = visible.groupBy { it.country }.entries.sortedWith(
        compareBy<Map.Entry<String, List<Node>>> { it.key == "WARP" || it.key == "ZZ" }
            .thenByDescending { it.value.size },
    )

    fun select(s: Selection) {
        Prefs.select(s)
        onSelectionChanged()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VPN Hub") },
                actions = {
                    if (refreshing) {
                        CircularProgressIndicator(Modifier.padding(12.dp).width(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "更新節點") }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "設定")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item {
                StatusCard(
                    status = status,
                    label = label,
                    error = vpnError ?: repoError,
                    updated = list?.updated?.let(::formatUpdated),
                    total = visible.size,
                    countries = byCountry.size,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                )
            }
            if (ovpnStatus.isNotBlank() && ovpnStatus != "NOPROCESS") {
                item {
                    Text(
                        "OpenVPN for Android 狀態：$ovpnStatus",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Protocols.all.forEach { p ->
                        FilterChip(
                            selected = p in protocols,
                            onClick = {
                                Prefs.setProtocols(if (p in protocols) protocols - p else protocols + p)
                                onSelectionChanged()
                            },
                            label = { Text(Protocols.label(p)) },
                        )
                    }
                }
            }
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("IP 類型", style = MaterialTheme.typography.labelMedium)
                    IpTypes.all.forEach { t ->
                        FilterChip(
                            selected = t in ipTypes,
                            onClick = {
                                Prefs.setIpTypes(if (t in ipTypes) ipTypes - t else ipTypes + t)
                                onSelectionChanged()
                            },
                            label = { Text(IpTypes.label(t)) },
                        )
                    }
                }
            }
            item {
                SelectRow(
                    title = "⚡ 全部最快（自動）",
                    subtitle = "喺最快嘅 ${Prefs.groupSize} 個節點之間自動揀、斷咗自動換",
                    selected = selection == Selection.Fastest,
                    onClick = { select(Selection.Fastest) },
                )
                HorizontalDivider()
            }
            if (list == null) {
                item {
                    Text(
                        if (refreshing) "下載節點清單中…" else "未有節點清單，請撳右上角更新，或喺設定填節點清單網址。",
                        Modifier.padding(16.dp),
                    )
                }
            }
            byCountry.forEach { (cc, nodes) ->
                val sorted = nodes.sortedBy { if (it.latency <= 0) Int.MAX_VALUE else it.latency }
                val isOpen = cc in expanded
                item(key = "c-$cc") {
                    CountryRow(
                        code = cc,
                        nodes = sorted,
                        selected = selection == Selection.Country(cc),
                        expanded = isOpen,
                        onClick = {
                            if (sorted.all { it.isOpenVpn }) {
                                expanded = if (isOpen) expanded - cc else expanded + cc
                            } else {
                                select(Selection.Country(cc))
                            }
                        },
                        onToggle = { expanded = if (isOpen) expanded - cc else expanded + cc },
                    )
                }
                if (isOpen) {
                    items(sorted, key = { "n-${it.id}" }) { node ->
                        NodeRow(
                            node = node,
                            selected = selection == Selection.Single(node.id),
                            onClick = {
                                if (node.isOpenVpn) onOpenVpn(node) else select(Selection.Single(node.id))
                            },
                        )
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(onDismiss = { showSettings = false }, onSaved = onRefresh, onInstallOpenVpn = onInstallOpenVpn)
    }
}

@Composable
private fun StatusCard(
    status: VpnState.Status,
    label: String,
    error: String?,
    updated: String?,
    total: Int,
    countries: Int,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val connected = status == VpnState.Status.Connected
    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (connected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                when (status) {
                    VpnState.Status.Connected -> "已連線"
                    VpnState.Status.Starting -> "連線中…"
                    VpnState.Status.Stopping -> "斷開中…"
                    VpnState.Status.Stopped -> "未連線"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            if (label.isNotBlank()) Text(label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "節點：$total 個 · $countries 個地區" + (updated?.let { " · 更新：$it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            val busy = status == VpnState.Status.Starting || status == VpnState.Status.Stopping
            Button(
                onClick = if (connected || status == VpnState.Status.Starting) onDisconnect else onConnect,
                enabled = status != VpnState.Status.Stopping,
                modifier = Modifier.fillMaxWidth(),
                colors = if (connected) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(if (connected || busy) "斷開" else "連線")
            }
        }
    }
}

@Composable
private fun SelectRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        if (selected) Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CountryRow(
    code: String,
    nodes: List<Node>,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
) {
    val best = nodes.firstOrNull { it.latency > 0 }?.latency ?: 0
    val protoSummary = nodes.groupingBy { it.protocol }.eachCount().entries
        .sortedByDescending { it.value }
        .joinToString(" · ") { "${Protocols.label(it.key)} ${it.value}" }
    val typeSummary = nodes.groupingBy { it.ipType }.eachCount().entries
        .sortedByDescending { it.value }
        .joinToString(" · ") { "${IpTypes.short(it.key)} ${it.value}" }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(Countries.flag(code), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${Countries.name(code)}  (${nodes.size})",
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(protoSummary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(typeSummary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Text("✓ ", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        if (best > 0) Text("$best ms", color = latencyColor(best), style = MaterialTheme.typography.bodySmall)
        IconButton(onClick = onToggle) {
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展開",
            )
        }
    }
}

@Composable
private fun NodeRow(node: Node, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 56.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                node.name,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                IpTypes.label(node.ipType) + if (node.isp.isNotBlank()) " · ${node.isp}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = ipTypeColor(node.ipType),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AssistChip(onClick = onClick, label = { Text(Protocols.label(node.protocol)) })
        Spacer(Modifier.width(8.dp))
        Text(
            if (node.latency > 0) "${node.latency} ms" else "—",
            color = latencyColor(node.latency),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(64.dp),
        )
        if (selected) Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
}
