package com.vdelaar.mylibby.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.data.WordEntry
import com.vdelaar.mylibby.data.WordMeaning

/**
 * The dictionary popover: meanings, the synonyms and antonyms that go with them, and the sentence the word was in.
 * A bottom sheet that stays at a readable width, so it is a card on a tablet or an unfolded foldable and a sheet on a phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionarySheet(
    lookup: LookupUi,
    offers: Offers,
    installed: List<com.vdelaar.mylibby.data.dict.InstalledPack>,
    progress: Map<String, com.vdelaar.mylibby.data.dict.PackProgress>,
    installedWikt: List<com.vdelaar.mylibby.data.dict.InstalledWikt>,
    wiktProgress: Map<String, com.vdelaar.mylibby.data.dict.PackProgress>,
    onLoadOffers: () -> Unit,
    onDownload: (com.vdelaar.mylibby.data.dict.PackInfo) -> Unit,
    onDownloadWikt: (com.vdelaar.mylibby.data.dict.WiktPackInfo) -> Unit,
    choice: String,
    onChoose: (String) -> Unit,
    synonymChoice: String,
    onChooseSynonyms: (String) -> Unit,
    onWord: (String) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        sheetMaxWidth = 640.dp,
    ) {
        Column(Modifier.sheetBodyHeight(.62f).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (lookup.trail.isNotEmpty()) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) }
                Column(Modifier.weight(1f)) {
                    Text(lookup.word, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    (lookup.result as? LookupResult.Found)?.entry?.let { e -> (e.packs.chosen(choice) ?: e.packs.first()).phonetic ?: e.phonetic }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.action_close)) }
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (lookup.context.isNotBlank() && lookup.trail.isEmpty()) {
                    Text(stringResource(R.string.dict_in_context), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("“${lookup.context}”", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                }
                when (val r = lookup.result) {
                    LookupResult.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    LookupResult.NotFound -> Message(stringResource(R.string.dict_not_found, lookup.word))
                    LookupResult.NoDictionary -> Message(stringResource(R.string.dict_no_dictionary))
                    is LookupResult.Found -> EntryBody(r.entry, lookup.word, choice, onChoose, synonymChoice, onChooseSynonyms, onWord)
                }
                // Nothing for this language on the device yet: say how to get it .
                val offerFor = lookup.offerFor
                if (offerFor != null && lookup.result !is LookupResult.Loading) {
                    LaunchedEffect(offerFor) { onLoadOffers() }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    PackOffer(
                        languageName = java.util.Locale.forLanguageTag(offerFor).getDisplayLanguage(androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).ifBlank { offerFor },
                        offers = offers, installed = installed, progress = progress, onDownload = onDownload,
                        installedWikt = installedWikt, wiktProgress = wiktProgress, onDownloadWikt = onDownloadWikt, onRetry = onLoadOffers,
                    )
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The dictionary the reader chose, if this word is in it. */
private fun List<com.vdelaar.mylibby.data.PackEntry>.chosen(id: String) = firstOrNull { it.id == id }

/** The name of a dictionary; with several to choose from, a small menu that switches between them. */
@Composable
private fun PackMenu(packs: List<com.vdelaar.mylibby.data.PackEntry>, selected: com.vdelaar.mylibby.data.PackEntry, onChoose: (String) -> Unit) {
    if (packs.size < 2) {
        Text(selected.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(MaterialTheme.shapes.small).clickable { open = true }.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(selected.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Icon(Icons.Rounded.ArrowDropDown, stringResource(R.string.dict_choose), tint = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            packs.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.label) },
                    leadingIcon = { if (p.id == selected.id) Icon(Icons.Rounded.Check, null) },
                    onClick = { open = false; onChoose(p.id) },
                )
            }
        }
    }
}

/** What the chosen dictionary says, then the synonyms of the dictionary chosen for those (they can differ). */
@Composable
private fun EntryBody(
    entry: WordEntry, word: String, choice: String, onChoose: (String) -> Unit,
    synonymChoice: String, onChooseSynonyms: (String) -> Unit, onWord: (String) -> Unit,
) {
    val pack = entry.packs.chosen(choice) ?: entry.packs.first()
    Row(verticalAlignment = Alignment.CenterVertically) {
        PackMenu(entry.packs, pack, onChoose)
        if (!pack.matched.equals(word, true)) Text("  " + stringResource(R.string.dict_from_form, pack.matched), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(6.dp))
    if (pack.translations) Translations(pack) else pack.meanings.forEach { Meaning(it); Spacer(Modifier.height(14.dp)) }
    pack.source?.let {
        Text(
            stringResource(R.string.dict_source, it),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
    }
    val withSynonyms = entry.packs.filter { p -> p.meanings.any { it.synonyms.isNotEmpty() || it.antonyms.isNotEmpty() } }
    if (withSynonyms.isNotEmpty()) {
        val from = withSynonyms.chosen(synonymChoice) ?: withSynonyms.first()
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(stringResource(R.string.dict_synonyms), style = MaterialTheme.typography.labelLarge)
        PackMenu(withSynonyms, from, onChooseSynonyms)
        from.meanings.filter { it.synonyms.isNotEmpty() || it.antonyms.isNotEmpty() }.forEach { m ->
            Text(m.partOfSpeech, style = MaterialTheme.typography.titleSmall, fontStyle = FontStyle.Italic, modifier = Modifier.padding(top = 8.dp))
            // The heading above says "Synonyms"; the labels are only needed to tell the two lists apart.
            val both = m.synonyms.isNotEmpty() && m.antonyms.isNotEmpty()
            if (m.synonyms.isNotEmpty()) { if (both) Label(stringResource(R.string.dict_synonyms)); Chips(m.synonyms, onWord) }
            if (m.antonyms.isNotEmpty()) { Label(stringResource(R.string.dict_antonyms)); Chips(m.antonyms, onWord) }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** The translations of a translation dictionary, grouped by part of speech. */
@Composable
private fun Translations(p: com.vdelaar.mylibby.data.PackEntry) {
    p.meanings.forEach { m ->
        if (m.partOfSpeech.isNotBlank()) Text(m.partOfSpeech, style = MaterialTheme.typography.labelLarge, fontStyle = FontStyle.Italic, modifier = Modifier.padding(top = 8.dp))
        m.senses.forEachIndexed { i, s ->
            Row(Modifier.padding(top = 4.dp)) {
                if (m.senses.size > 1) Text("${i + 1}.", Modifier.widthIn(min = 24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column {
                    Text(s.text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    s.gloss?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))

@Composable
private fun Meaning(m: WordMeaning) {
    Text(m.partOfSpeech, style = MaterialTheme.typography.titleSmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.primary)
    m.senses.forEachIndexed { i, s ->
        Row(Modifier.padding(top = 6.dp)) {
            Text("${i + 1}.", Modifier.widthIn(min = 24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column {
                Text(s.text, style = MaterialTheme.typography.bodyMedium)
                s.gloss?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                s.example?.let { Text("“$it”", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(words: List<String>, onWord: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        words.forEach { w -> AssistChip(onClick = { onWord(w) }, label = { Text(w) }) }
    }
}
