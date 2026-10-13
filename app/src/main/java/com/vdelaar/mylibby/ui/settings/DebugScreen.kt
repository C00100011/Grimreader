package com.vdelaar.mylibby.ui.settings

import android.content.ClipData
import android.content.Intent
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.core.debug.ConnectionProbe
import com.vdelaar.mylibby.core.debug.DiagnosticsReport
import com.vdelaar.mylibby.core.debug.DiagnosticsSnapshots
import com.vdelaar.mylibby.core.debug.NetEntry
import com.vdelaar.mylibby.core.debug.NetworkLog
import com.vdelaar.mylibby.ui.adaptive.rememberDeviceLayout
import com.vdelaar.mylibby.ui.appContainer
import com.vdelaar.mylibby.ui.navigation.AppNavigator
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Connection diagnostics: how the app reaches each server, the last 100 requests, and a report without secrets to send
 * to the developer. Reachable from the settings and from the onboarding, where connecting is most likely to go wrong.
 * Technical lines (steps, errors) are English on purpose: they end up in bug reports.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(navigator: AppNavigator) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layout = rememberDeviceLayout()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val container = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val shareTitle = stringResource(R.string.debug_share_title)
    val shareChooser = stringResource(R.string.debug_share)
    val snackbar = remember { SnackbarHostState() }
    val entries by NetworkLog.entries.collectAsStateWithLifecycle()
    val images by NetworkLog.images.collectAsStateWithLifecycle()
    val probes by NetworkLog.probes.collectAsStateWithLifecycle()
    var testing by remember { mutableStateOf(false) }
    var hideAddresses by rememberSaveable { mutableStateOf(true) }
    var onlyFailed by rememberSaveable { mutableStateOf(false) }

    val window = "${layout.formFactor.name.lowercase()}, ${with(density) { container.width.toDp().value.toInt() }}x${with(density) { container.height.toDp().value.toInt() }} dp" +
        (if (layout.isWide) ", wide layout" else "") + (if (layout.hasVerticalHinge) ", vertical hinge" else "") + (if (layout.isTabletop) ", tabletop" else "")
    val snapshot = remember(window, probes.size, entries.size) { DiagnosticsSnapshots.capture(context, c, window) }

    fun report() = DiagnosticsReport.render(snapshot, probes, entries, images, hideAddresses)

    fun runTests() = scope.launch {
        testing = true
        val probe = ConnectionProbe(c)
        NetworkLog.probes.value = probe.targets().map { t -> async { probe.run(t) } }.awaitAll()
        testing = false
    }

    fun share() {
        val file = File(context.cacheDir, "share").apply { mkdirs() }.resolve("grimreader-connection-report.txt")
        file.writeText(report())
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, shareTitle)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, shareChooser))
    }

    val copiedMsg = stringResource(R.string.debug_copied)
    fun copy() {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Grimreader report", report()))
        scope.launch { snackbar.showSnackbar(copiedMsg) }
    }

    val left: @Composable () -> Unit = {
        Text(stringResource(R.string.debug_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        SettingsGroup(stringResource(R.string.debug_this_phone)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val lines = listOf("${snapshot.device}, ${snapshot.android}", "App ${snapshot.appVersion} (${snapshot.build})", snapshot.screen, "Network: ${snapshot.network}") + snapshot.setup
                lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        SettingsGroup(stringResource(R.string.debug_test_title)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.debug_test_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { runTests() }, enabled = !testing) {
                    if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (testing) R.string.debug_testing else R.string.debug_test_all))
                }
                if (probes.isEmpty() && !testing) Text(stringResource(R.string.debug_test_none), style = MaterialTheme.typography.bodySmall)
                probes.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Icon(if (p.ok) Icons.Rounded.CheckCircle else Icons.Rounded.Error, null, tint = if (p.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(p.target + (p.host?.let { "  ·  $it" } ?: ""), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    p.steps.forEach { s ->
                        Text(
                            (when (s.ok) { true -> "✓ "; false -> "✗ "; null -> "– " }) + "${s.name}: ${s.detail}" + if (s.millis > 0) "  (${s.millis} ms)" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (s.ok == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 28.dp),
                        )
                    }
                }
            }
        }
        SettingsGroup(stringResource(R.string.debug_export_title)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.debug_export_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().clickable { hideAddresses = !hideAddresses }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.debug_hide_addresses), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.debug_hide_addresses_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = hideAddresses, onCheckedChange = { hideAddresses = it })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { share() }) { Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.debug_share)) }
                    OutlinedButton(onClick = { copy() }) { Text(stringResource(R.string.debug_copy)) }
                }
            }
        }
    }

    val shown = if (onlyFailed) entries.filter { !it.ok } else entries
    val requests: @Composable () -> Unit = {
        SettingsGroup(stringResource(R.string.debug_requests_title)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.debug_requests_count, entries.size, NetworkLog.CAPACITY), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = !onlyFailed, onClick = { onlyFailed = false }, label = { Text(stringResource(R.string.debug_filter_all)) })
                    FilterChip(selected = onlyFailed, onClick = { onlyFailed = true }, label = { Text(stringResource(R.string.debug_filter_failed)) })
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { NetworkLog.clear() }, enabled = entries.isNotEmpty() || probes.isNotEmpty()) { Text(stringResource(R.string.debug_clear)) }
                }
                if (shown.isEmpty()) Text(stringResource(if (onlyFailed && entries.isNotEmpty()) R.string.debug_empty_failed else R.string.debug_empty), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
                shown.forEach { RequestRow(it) }
                Text(stringResource(R.string.debug_images, images.ok, images.failed) + (images.lastError?.let { "\n$it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debug_title)) },
                navigationIcon = { IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) } },
                actions = { IconButton(onClick = { share() }) { Icon(Icons.Rounded.Share, stringResource(R.string.debug_share)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (layout.isWide) {
            // Tablets and unfolded foldables: tests and export on the left, the request list on the right, each scrolling on its own.
            Row(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { left() }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { requests() }
            }
        } else {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { left(); requests() }
            }
        }
    }
}

private val TIME = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

@Composable
private fun RequestRow(e: NetEntry) {
    var open by rememberSaveable(e.id) { mutableStateOf(false) }
    val color = if (e.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 4.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.code?.toString() ?: "ERR", style = MaterialTheme.typography.labelLarge, color = color, modifier = Modifier.width(40.dp))
            Text("${e.method} ${e.path}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = if (open) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Text("${TIME.format(e.time)} · ${e.source} · ${e.millis} ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 40.dp))
        if (open) {
            val port = if (e.port == (if (e.scheme == "https") 443 else 80)) "" else ":${e.port}"
            Text(
                listOfNotNull("${e.scheme}://${e.host}$port", e.protocol, e.tls, if (e.retried) "retried after sign-in" else null, e.error).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall, color = if (e.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 40.dp, top = 2.dp),
            )
        }
    }
}
