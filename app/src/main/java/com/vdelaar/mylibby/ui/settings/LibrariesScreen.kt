package com.vdelaar.mylibby.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.ui.navigation.AppNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One piece of third-party work: who made it, under which licence, and the full licence text (an asset). */
private data class Lib(val name: String, val version: String?, val license: String, val what: Int, val owner: String, val asset: String)

private data class LibGroup(val title: Int, val libs: List<Lib>)

private const val APACHE = "Apache 2.0"
private const val AOSP = "The Android Open Source Project"
private const val JB = "JetBrains s.r.o. and Kotlin Programming Language contributors"
private const val A = "apache-2.0.txt"

private val groups = listOf(
    LibGroup(R.string.libs_group_reader, listOf(
        Lib("foliate-js", null, "MIT", R.string.lib_foliate, "John Factotum", "mit-foliate.txt"),
        Lib("zip.js", null, "BSD-3-Clause", R.string.lib_zipjs, "Gildas Lormeau", "bsd3-zipjs.txt"),
        Lib("fflate", null, "MIT", R.string.lib_fflate, "Arjun Barrett", "mit-fflate.txt"),
    )),
    LibGroup(R.string.libs_group_ui, listOf(
        Lib("Jetpack Compose", "BOM 2026.09.00", APACHE, R.string.lib_compose, AOSP, A),
        Lib("Material 3 + Material Icons Extended", "1.7.8", APACHE, R.string.lib_material, AOSP, A),
        Lib("Material 3 Adaptive & Navigation Suite", "1.3.0 / 1.4.0", APACHE, R.string.lib_adaptive, AOSP, A),
        Lib("Navigation Compose", "2.10.2", APACHE, R.string.lib_navigation, AOSP, A),
        Lib("Glance (widget)", "1.2.0", APACHE, R.string.lib_glance, AOSP, A),
        Lib("Coil", "3.6.3", APACHE, R.string.lib_coil, "Coil Contributors", A),
        Lib("AndroidX Palette", "1.0.0", APACHE, R.string.lib_palette, AOSP, A),
        Lib("Core SplashScreen", "1.2.0", APACHE, R.string.lib_splash, AOSP, A),
    )),
    LibGroup(R.string.libs_group_android, listOf(
        Lib("AndroidX Core, Activity, Lifecycle", "1.19.1 / 1.13.0 / 2.11.0", APACHE, R.string.lib_androidx, AOSP, A),
        Lib("Jetpack Window", "1.5.1", APACHE, R.string.lib_window, AOSP, A),
        Lib("WebKit", "1.17.1", APACHE, R.string.lib_webkit, AOSP, A),
        Lib("Media3", "1.11.1", APACHE, R.string.lib_media3, AOSP, A),
        Lib("WorkManager", "2.12.0", APACHE, R.string.lib_work, AOSP, A),
        Lib("Room", "2.8.5", APACHE, R.string.lib_room, AOSP, A),
        Lib("DataStore", "1.2.1", APACHE, R.string.lib_datastore, AOSP, A),
        Lib("Security Crypto", "1.1.0", APACHE, R.string.lib_crypto, AOSP, A),
    )),
    LibGroup(R.string.libs_group_network, listOf(
        Lib("Retrofit", "3.0.0", APACHE, R.string.lib_retrofit, "Square, Inc.", A),
        Lib("OkHttp", "5.5.0", APACHE, R.string.lib_okhttp, "Square, Inc.", A),
        Lib("kotlinx.serialization", "1.11.0", APACHE, R.string.lib_serialization, JB, A),
        Lib("kotlinx.coroutines", "1.11.0", APACHE, R.string.lib_coroutines, JB, A),
        Lib("Kotlin", "2.4.20", APACHE, R.string.lib_kotlin, JB, A),
        Lib("ONNX Runtime", "1.29.0", "MIT", R.string.lib_onnx, "Microsoft Corporation", "mit-onnxruntime.txt"),
    )),
    LibGroup(R.string.libs_group_voice, listOf(
        Lib("Supertonic 3 (model)", null, "OpenRAIL-M", R.string.lib_supertonic, "Supertone Inc.", "openrail-m-supertonic.txt"),
        Lib("Supertonic (sample code, ported to Kotlin)", null, "MIT", R.string.lib_supertonic_code, "Supertone Inc.", "mit-supertonic-code.txt"),
    )),
    LibGroup(R.string.libs_group_fonts, listOf(
        Lib("Literata", null, "SIL OFL 1.1", R.string.lib_font_one, "The Literata Project Authors", "ofl-literata.txt"),
        Lib("Lora", null, "SIL OFL 1.1", R.string.lib_font_one, "The Lora Project Authors", "ofl-lora.txt"),
        Lib("Merriweather", null, "SIL OFL 1.1", R.string.lib_font_one, "The Merriweather Project Authors", "ofl-merriweather.txt"),
        Lib("Atkinson Hyperlegible", null, "SIL OFL 1.1", R.string.lib_font_one, "Braille Institute of America, Inc.", "ofl-atkinson.txt"),
        Lib("OpenDyslexic", null, "SIL OFL 1.1", R.string.lib_font_one, "Abbie Gonzalez", "ofl-opendyslexic.txt"),
    )),
    LibGroup(R.string.libs_group_content, listOf(
        Lib("Standard Ebooks", null, "CC0 1.0", R.string.lib_standard_ebooks, "Standard Ebooks", "cc0-1.0.txt"),
    )),
)

/** Overview of the open-source libraries and content Grimreader is built with; tap one for the full licence. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrariesScreen(navigator: AppNavigator) {
    var open by remember { mutableStateOf<Lib?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.libraries)) },
                navigationIcon = { IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
            item {
                Text(
                    stringResource(R.string.libraries_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            groups.forEach { g ->
                item(key = "h${g.title}") {
                    Text(
                        stringResource(g.title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
                    )
                }
                items(g.libs, key = { it.name }) { lib ->
                    Column(
                        Modifier.fillMaxWidth().clickable { open = lib }.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(lib.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOfNotNull(lib.version, lib.license).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(stringResource(lib.what), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    open?.let { lib -> LicenseSheet(lib) { open = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicenseSheet(lib: Lib, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember(lib) { mutableStateOf<String?>(null) }
    LaunchedEffect(lib) {
        text = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("licenses/${lib.asset}").bufferedReader().use { it.readText() } }.getOrNull()
        } ?: ""
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(lib.name, style = MaterialTheme.typography.titleLarge)
            Text(listOfNotNull(lib.version, lib.license).joinToString(" · "), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.license_by, lib.owner), style = MaterialTheme.typography.bodyMedium)
            if (lib.asset == "apache-2.0.txt") {
                Text(stringResource(R.string.license_apache_note, lib.owner), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SelectionContainer {
                Text(
                    text ?: stringResource(R.string.loading_voices),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
