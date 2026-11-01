package com.vdelaar.mylibby.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.data.dict.InstalledPack
import com.vdelaar.mylibby.data.dict.InstalledWikt
import com.vdelaar.mylibby.data.dict.WiktPackInfo
import com.vdelaar.mylibby.data.dict.PackInfo
import com.vdelaar.mylibby.data.dict.PackProgress
import com.vdelaar.mylibby.data.dict.packLabel
import com.vdelaar.mylibby.data.dict.packSize

/** One dictionary to download (or already installed): its name and size, and the button, progress or error that goes with it. */
@Composable
fun PackRow(
    info: PackInfo,
    installed: InstalledPack?,
    progress: PackProgress?,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) = PackRowLayout(
    packLabel(info.source, info.target),
    stringResource(R.string.dict_pack_meta, "%,d".format(info.headwords), packSize(info.sizeBytes)),
    installed != null, progress, onDownload, onRemove,
)

/** The same for a Wiktionary pack: the language, the language its definitions are in, and what it costs to download. */
@Composable
fun WiktPackRow(
    info: WiktPackInfo,
    installed: InstalledWikt?,
    progress: PackProgress?,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    PackRowLayout(
        wiktTitle(info),
        stringResource(R.string.dict_wikt_meta, "≈ " + packSize(info.downloadBytes)),
        installed != null, progress, onDownload, onRemove,
    )
}

/** "Dutch (definitions in English)". */
@Composable
fun wiktTitle(info: WiktPackInfo): String = com.vdelaar.mylibby.data.dict.wiktLabel(LocalContext.current, info)

@Composable
private fun PackRowLayout(
    title: String,
    meta: String,
    installed: Boolean,
    progress: PackProgress?,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                progress is PackProgress.Downloading || progress is PackProgress.Preparing -> {}
                installed -> TextButton(onClick = onRemove) { Text(stringResource(R.string.dict_remove)) }
                else -> OutlinedButton(onClick = onDownload) { Text(stringResource(if (progress is PackProgress.Failed) R.string.action_try_again else R.string.dict_download)) }
            }
        }
        when (progress) {
            is PackProgress.Downloading -> {
                if (progress.fraction != null) LinearProgressIndicator(progress = { progress.fraction }, Modifier.fillMaxWidth().padding(top = 6.dp))
                else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                Text(stringResource(R.string.dict_downloading), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is PackProgress.Preparing -> {
                if (progress.fraction != null) LinearProgressIndicator(progress = { progress.fraction }, Modifier.fillMaxWidth().padding(top = 6.dp))
                else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                Text(stringResource(R.string.dict_preparing), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is PackProgress.Failed -> Text(progress.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            null -> {}
        }
    }
}

/** "Look up words offline": the dictionaries worth downloading for the language of this book. */
@Composable
fun PackOffer(
    languageName: String,
    offers: Offers,
    installed: List<InstalledPack>,
    progress: Map<String, PackProgress>,
    onDownload: (PackInfo) -> Unit,
    installedWikt: List<InstalledWikt>,
    wiktProgress: Map<String, PackProgress>,
    onDownloadWikt: (WiktPackInfo) -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.dict_offer_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.dict_offer_body, languageName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (offers) {
            Offers.Idle, Offers.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
            Offers.Unavailable -> {
                Text(stringResource(R.string.dict_catalog_unavailable), Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
            is Offers.Ready -> if (offers.packs.isEmpty() && offers.wikt.isEmpty()) {
                Text(stringResource(R.string.dict_none_for_language, languageName), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            } else {
                if (offers.wikt.isNotEmpty()) {
                    Text(stringResource(R.string.dict_wikt_heading), Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelLarge)
                    offers.wikt.forEach { p ->
                        WiktPackRow(p, installedWikt.firstOrNull { it.id == p.id }, wiktProgress[p.id], onDownload = { onDownloadWikt(p) }, onRemove = {})
                    }
                }
                if (offers.packs.isNotEmpty()) {
                    Text(stringResource(R.string.dict_translations_heading), Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelLarge)
                    offers.packs.forEach { p ->
                        PackRow(p, installed.firstOrNull { it.name == p.name }, progress[p.name], onDownload = { onDownload(p) }, onRemove = {})
                    }
                }
            }
        }
    }
}
