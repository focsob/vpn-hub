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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import com.vpnhub.app.data.LocalPing
import com.vpnhub.app.data.Node
import com.vpnhub.app.data.NodeRepository
import com.vpnhub.app.data.Prefs
import com.vpnhub.app.data.Protocols
import com.vpnhub.app.data.Selection
import com.vpnhub.app.vpn.SpeedMeter
import com.vpnhub.app.vpn.VpnState
import com.vpnhub.app.data.SplitRule
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
    onSettingsSaved: (modeChanged: Boolean, urlChanged: Boolean) -> Unit,
    onSelectionChanged: () -> Unit,
) {
    val list by NodeRepository.list.collectAsStateWithLifecycle()
    val refreshing by NodeRepository.refreshing.collectAsStateWithLifecycle()
    val repoError by NodeRepository.lastError.collectAsStateWithLifecycle()
    val status by VpnState.status.collectAsStateWithLifecycle()
    val label by VpnState.label.collectAsStateWithLifecycle()
    val vpnError by VpnState.error.collectAsStateWithLifecycle()
    val splitWarning by VpnState.splitWarning.collectAsStateWithLifecycle()
    val speed by SpeedMeter.speed.collectAsStateWithLifecycle()
    val splitRules by Prefs.splitRules.collectAsStateWithLifecycle()
    var showSplit by remember { mutableStateOf(false) }
    var showDiag by remember { mutableStateOf(false) }
    val selection by Prefs.selection.collectAsStateWithLifecycle()
    val protocols by Prefs.protocols.collectAsStateWithLifecycle()
    val ipTypes by Prefs.ipTypes.collectAsStateWithLifecycle()

    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var showSettings by remember { mutableStateOf(false) }
    var countryQuery by rememberSaveable { mutableStateOf("") }

    val warpMode by Prefs.warpMode.collectAsStateWithLifecycle()
    val dead by LocalPing.dead.collectAsStateWithLifecycle()
    val pinging by LocalPing.checking.collectAsStateWithLifecycle()
    val filtered = list?.nodes.orEmpty()
        .filter { it.id !in dead && Protocols.matches(it, protocols) && IpTypes.matches(it, ipTypes) }
    // WARP mode lists the nodes that can carry WARP, grouped by the country WARP then exits in
    val visible = if (warpMode) filtered.filter { it.warpCc != null } else filtered
    val byCountry = visible.groupBy { if (warpMode) it.warpCc!! else it.country }.entries.sortedWith(
        compareBy<Map.Entry<String, List<Node>>> { it.key == "WARP" || it.key == "ZZ" }
            .thenByDescending { it.value.size },
    )
    val shownCountries = byCountry.filter { Countries.matches(it.key, countryQuery) }

    fun select(s: Selection) {
        Prefs.select(s)
        onSelectionChanged()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fatbo VPNhub") },
                actions = {
                    if (refreshing) {
                        CircularProgressIndicator(Modifier.padding(12.dp).width(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "更新節點") }
                    }
                    IconButton(onClick = { showDiag = true }) {
                        Icon(Icons.Filled.Info, contentDescription = "連線診斷")
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
                    warning = splitWarning,
                    speed = speed,
                    updated = list?.updated?.let(::formatUpdated),
                    total = visible.size,
                    countries = byCountry.size,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                )
            }
            if (pinging || dead.isNotEmpty()) {
                item {
                    Text(
                        if (pinging) "本機測試節點連通性中…" else "本機測試後移除咗 ${dead.size} 個連唔到嘅節點",
                        Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                val active = splitRules.filter { it.enabled && it.packages.isNotEmpty() }
                SelectRow(
                    title = "🔀 分流（${active.size} 條規則）",
                    subtitle = if (active.isEmpty()) {
                        "指定某啲 App 用某個國家，例如 LINE 用日本"
                    } else {
                        active.joinToString("、") { "${it.packages.size} 個 App → ${SplitRule.targetLabel(it.target)}" }
                    },
                    selected = false,
                    onClick = { showSplit = true },
                )
                HorizontalDivider()
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
                Row(
                    Modifier.fillMaxWidth().clickable {
                        Prefs.setWarpMode(!warpMode)
                        onSelectionChanged()
                    }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("☁️ 經 WARP 出口", fontWeight = FontWeight.Bold)
                        Text(
                            if (warpMode) {
                                "手機 → 所選國家嘅節點 → Cloudflare WARP，WARP 喺嗰個國家出口。下面只列出雲端實測接得通 WARP 嘅節點，按 WARP 出口國家分類。"
                            } else {
                                "開咗之後，揀國家就會變成「經嗰個國家嘅節點再接 WARP」，用 WARP 喺嗰個國家出口。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = warpMode, onCheckedChange = {
                        Prefs.setWarpMode(it)
                        onSelectionChanged()
                    })
                }
                HorizontalDivider()
            }
            item {
                SelectRow(
                    title = if (warpMode) "☁️ WARP · 全部最快（自動）" else "⚡ 全部最快（自動）",
                    subtitle = if (warpMode) {
                        "經最快嘅接得通 WARP 嘅節點，WARP 出口國家會跟住變"
                    } else {
                        "喺最快嘅 ${Prefs.groupSize} 個節點之間自動揀、斷咗自動換"
                    },
                    selected = selection == Selection.Fastest,
                    onClick = { select(Selection.Fastest) },
                )
                HorizontalDivider()
                SelectRow(
                    title = "☁️ Cloudflare WARP（直接・就近出口）",
                    subtitle = "手機直接連 WARP，唔經其他節點；出口喺你附近。用你部手機自己嘅 WARP 帳戶。",
                    selected = selection == Selection.Country("WARP"),
                    onClick = { select(Selection.Country("WARP")) },
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
            if (byCountry.isNotEmpty()) {
                item {
                    OutlinedTextField(
                        value = countryQuery,
                        onValueChange = { countryQuery = it },
                        label = { Text("搜尋國家 / 地區") },
                        placeholder = { Text("例如 菲律賓、PH、Japan") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (countryQuery.isNotEmpty()) {
                                IconButton(onClick = { countryQuery = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "清除")
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                if (shownCountries.isEmpty()) {
                    item {
                        Text(
                            "搵唔到符合「$countryQuery」嘅國家／地區",
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            shownCountries.forEach { (cc, nodes) ->
                val sorted = nodes.sortedBy { if (it.latency <= 0) Int.MAX_VALUE else it.latency }
                val isOpen = cc in expanded
                item(key = "c-$cc") {
                    CountryRow(
                        code = cc,
                        nodes = sorted,
                        selected = selection == Selection.Country(cc),
                        expanded = isOpen,
                        onClick = { select(Selection.Country(cc)) },
                        onToggle = { expanded = if (isOpen) expanded - cc else expanded + cc },
                    )
                }
                if (isOpen) {
                    items(sorted, key = { "n-${it.id}" }) { node ->
                        NodeRow(
                            node = node,
                            warpMode = warpMode,
                            selected = selection == Selection.Single(node.id),
                            onClick = { select(Selection.Single(node.id)) },
                        )
                    }
                }
            }
        }
    }

    if (showDiag) {
        DiagDialog(onDismiss = { showDiag = false }, onVerboseChanged = onSelectionChanged)
    }

    if (showSplit) {
        SplitScreen(onDismiss = { showSplit = false }, onChanged = onSelectionChanged)
    }

    if (showSettings) {
        SettingsDialog(onDismiss = { showSettings = false }, onSaved = onSettingsSaved)
    }
}

@Composable
private fun StatusCard(
    status: VpnState.Status,
    label: String,
    error: String?,
    warning: String?,
    speed: SpeedMeter.Speed,
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
            if (connected) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("↑ 上傳", style = MaterialTheme.typography.labelSmall)
                        Text(SpeedMeter.rate(speed.up), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("共 ${SpeedMeter.size(speed.totalUp)}", style = MaterialTheme.typography.labelSmall)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("↓ 下載", style = MaterialTheme.typography.labelSmall)
                        Text(SpeedMeter.rate(speed.down), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("共 ${SpeedMeter.size(speed.totalDown)}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (warning != null) {
                Text(warning, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
            }
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
private fun NodeRow(node: Node, selected: Boolean, onClick: () -> Unit, warpMode: Boolean = false) {
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
            if (warpMode) {
                Text(
                    "經 ${Countries.flag(node.country)} ${Countries.name(node.country)} 節點 → WARP" +
                        if (node.warpMs > 0) " · ${node.warpMs} ms" else "",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
