package com.vpnhub.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vpnhub.app.data.Countries
import com.vpnhub.app.data.NodeRepository
import com.vpnhub.app.data.Prefs
import com.vpnhub.app.data.SplitRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

data class AppEntry(val pkg: String, val label: String, val icon: ImageBitmap?)

private suspend fun loadApps(pm: PackageManager, self: String): List<AppEntry> = withContext(Dispatchers.IO) {
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    pm.queryIntentActivities(launcher, 0)
        .map { it.activityInfo.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != self }
        .map { info ->
            AppEntry(
                pkg = info.packageName,
                label = pm.getApplicationLabel(info).toString(),
                icon = runCatching { pm.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap() }.getOrNull(),
            )
        }
        .sortedBy { it.label.lowercase() }
}

/** Full-screen editor for per-app routing rules. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitScreen(onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val rules by Prefs.splitRules.collectAsStateWithLifecycle()
    val list by NodeRepository.list.collectAsStateWithLifecycle()
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var editing by remember { mutableStateOf<SplitRule?>(null) }
    var pickingCountry by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { apps = loadApps(context.packageManager, context.packageName) }

    fun save(newRules: List<SplitRule>) {
        Prefs.setSplitRules(newRules)
        onChanged()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("分流（按 App 揀國家）") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { pickingCountry = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("新增規則") },
                )
            },
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item {
                    Text(
                        "每條規則指定一個國家，選中嘅 App 會用嗰個國家最快嘅節點；其他 App 照用主頁嘅選擇。" +
                            "只喺 VPN 模式生效。某國家暫時冇節點時，嗰啲 App 會改用主頁嘅選擇。",
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (rules.isEmpty()) {
                    item { Text("未有規則。撳右下角「新增規則」。", Modifier.padding(16.dp)) }
                }
                items(rules, key = { it.id }) { rule ->
                    val count = list?.countries?.get(rule.target)?.count ?: 0
                    val names = rule.packages.map { p -> apps?.firstOrNull { it.pkg == p }?.label ?: p }
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(SplitRule.targetLabel(rule.target), fontWeight = FontWeight.Bold)
                                    if (rule.target != SplitRule.DIRECT) {
                                        Text(
                                            if (count > 0) "而家有 $count 個節點" else "而家冇可用節點",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (count > 0) {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            } else {
                                                MaterialTheme.colorScheme.error
                                            },
                                        )
                                    }
                                }
                                Switch(
                                    checked = rule.enabled,
                                    onCheckedChange = { on -> save(rules.map { if (it.id == rule.id) it.copy(enabled = on) else it }) },
                                )
                                IconButton(onClick = { save(rules.filterNot { it.id == rule.id }) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "刪除")
                                }
                            }
                            Text(
                                if (names.isEmpty()) "未揀 App" else names.joinToString("、"),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.padding(4.dp))
                            OutlinedButton(onClick = { editing = rule }) { Text("揀 App（${rule.packages.size}）") }
                        }
                    }
                }
                item { Spacer(Modifier.padding(40.dp)) }
            }
        }
    }

    if (pickingCountry) {
        CountryPicker(
            available = list?.countries?.mapValues { it.value.count }.orEmpty(),
            onDismiss = { pickingCountry = false },
            onPick = { code ->
                pickingCountry = false
                val rule = SplitRule(id = UUID.randomUUID().toString(), target = code)
                save(rules + rule)
                editing = rule
            },
        )
    }

    editing?.let { rule ->
        AppPicker(
            apps = apps,
            initial = rule.packages.toSet(),
            takenElsewhere = rules.filter { it.id != rule.id }.flatMap { it.packages }.toSet(),
            title = SplitRule.targetLabel(rule.target),
            onDismiss = { editing = null },
            onDone = { chosen ->
                editing = null
                save(rules.map { if (it.id == rule.id) it.copy(packages = chosen.sorted()) else it })
            },
        )
    }
}

@Composable
private fun CountryPicker(available: Map<String, Int>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    // countries with nodes first, then every other ISO country so a rule can wait for nodes to appear
    val all = remember(available) {
        val iso = java.util.Locale.getISOCountries().toList()
        (available.keys.sortedByDescending { available[it] } + iso.filter { it !in available }).distinct()
    }
    val shown = all.filter {
        query.isBlank() || Countries.name(it).contains(query, true) || it.contains(query, true)
    }
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("揀目的地", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜尋國家") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                LazyColumn(Modifier.size(width = 320.dp, height = 420.dp)) {
                    item {
                        Text(
                            SplitRule.targetLabel(SplitRule.DIRECT),
                            Modifier.fillMaxWidth().clickable { onPick(SplitRule.DIRECT) }.padding(10.dp),
                        )
                        HorizontalDivider()
                    }
                    items(shown, key = { it }) { code ->
                        val n = available[code] ?: 0
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(code) }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${Countries.flag(code)} ${Countries.name(code)}", Modifier.weight(1f))
                            Text(
                                if (n > 0) "$n 個" else "暫時冇",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (n > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("取消") }
            }
        }
    }
}

@Composable
private fun AppPicker(
    apps: List<AppEntry>?,
    initial: Set<String>,
    takenElsewhere: Set<String>,
    title: String,
    onDismiss: () -> Unit,
    onDone: (Set<String>) -> Unit,
) {
    var chosen by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(Modifier.fillMaxSize().padding(12.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("$title：揀 App（已揀 ${chosen.size}）", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜尋 App") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                Box(Modifier.weight(1f)) {
                    if (apps == null) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    } else {
                        val shown = apps
                            .filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
                            .sortedByDescending { it.pkg in chosen }
                        LazyColumn {
                            items(shown, key = { it.pkg }) { app ->
                                val checked = app.pkg in chosen
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable { chosen = if (checked) chosen - app.pkg else chosen + app.pkg }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (app.icon != null) {
                                        Image(app.icon, contentDescription = null, modifier = Modifier.size(36.dp))
                                    } else {
                                        Spacer(Modifier.size(36.dp))
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            if (app.pkg in takenElsewhere) "${app.pkg} · 已喺其他規則" else app.pkg,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Checkbox(checked = checked, onCheckedChange = null)
                                }
                            }
                        }
                    }
                }
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(onClick = { onDone(chosen) }) { Text("完成") }
                }
            }
        }
    }
}
