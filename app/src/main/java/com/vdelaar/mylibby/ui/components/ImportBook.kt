package com.vdelaar.mylibby.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.ui.appContainer
import kotlinx.coroutines.launch

/** MIME types MyLibby can import and read (EPUB first). */
val ImportMimeTypes = arrayOf(
    "application/epub+zip",
    "application/x-mobipocket-ebook",
    "application/x-fictionbook+xml",
    "application/vnd.comicbook+zip",
    "application/octet-stream",
)

/** Toolbar button that opens the system file picker and imports the chosen book(s). */
@Composable
fun ImportBookButton(onImported: () -> Unit = {}) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            var last: String? = null
            for (uri in uris) {
                last = runCatching { c.localBooks.import(uri).title }
                    .fold({ com.vdelaar.mylibby.core.str(R.string.imported, it) }, { com.vdelaar.mylibby.core.str(R.string.import_failed, it.message ?: "?") })
            }
            last?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show() }
            onImported()
        }
    }
    IconButton(onClick = { launcher.launch(ImportMimeTypes) }) {
        Icon(Icons.Rounded.FileOpen, stringResource(R.string.import_book))
    }
}
