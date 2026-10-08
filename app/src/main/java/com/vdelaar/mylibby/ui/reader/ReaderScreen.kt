package com.vdelaar.mylibby.ui.reader

import android.app.Activity
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.core.datastore.ProgressScope
import androidx.compose.ui.res.stringResource
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vdelaar.mylibby.VolumeKeys
import com.vdelaar.mylibby.core.database.AnnotationEntity
import com.vdelaar.mylibby.data.formatMinutes
import com.vdelaar.mylibby.tts.TtsStatus
import com.vdelaar.mylibby.ui.adaptive.rememberDeviceLayout
import com.vdelaar.mylibby.ui.appViewModel
import com.vdelaar.mylibby.ui.components.BookCover
import kotlinx.serialization.encodeToString

@Composable
fun ReaderScreen(bookId: Long, onBack: () -> Unit) {
    val vm = appViewModel(key = "reader-$bookId") { ReaderViewModel(it, bookId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val tts by vm.ttsState.collectAsStateWithLifecycle()
    val annotations by vm.annotations.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val speed by vm.speed.collectAsStateWithLifecycle()
    val speedRead by vm.speedRead.collectAsStateWithLifecycle()
    val speedSettings by vm.speedSettings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val layout = rememberDeviceLayout()
    val systemDark = isSystemInDarkTheme()
    val theme = settings.effectiveTheme(systemDark)
    val bg = Color(theme.bg)
    val fg = Color(theme.fg)

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showContents by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var noteFor by remember { mutableStateOf<AnnotationEntity?>(null) }
    var noteForSelection by rememberSaveable { mutableStateOf(false) }

    // WebView instance lives as long as this screen.
    val webView = remember {
        ReaderWebView(
            context,
            onEvent = { t, j -> runCatching { vm.onBridgeEvent(t, j) } },
            onSelectionAction = { action ->
                val sel = vm.state.value.selection
                when (action) {
                    SelectionAction.HIGHLIGHT -> vm.highlightSelection()
                    SelectionAction.NOTE -> noteForSelection = true
                    SelectionAction.COPY -> sel?.let { copy(context, it.text); vm.clearSelection() }
                    SelectionAction.SHARE -> sel?.let { shareQuote(context, it.text, vm.state.value.book?.title, vm.state.value.book?.authorLine); vm.clearSelection() }
                    SelectionAction.SPEAK -> vm.startTts(fromSelection = true)
                }
            },
        ).apply { loadUrl("https://appassets.androidplatform.net/assets/reader/reader.html") }
    }
    DisposableEffect(webView) {
        vm.js = { webView.run(it) }
        onDispose {
            vm.js = null
            webView.destroy()
        }
    }

    val jsSettings = remember(settings, systemDark, layout.readerColumns, layout.readerPortraitColumns) {
        BridgeJson.encodeToString(settings.toJs(systemDark, layout.readerColumns, layout.readerPortraitColumns))
    }
    LaunchedEffect(state.bridgeReady, state.fileUrl) { vm.openIfReady(jsSettings) }
    LaunchedEffect(jsSettings) { vm.applySettings(jsSettings) }
    LaunchedEffect(annotations, state.ready) {
        if (state.ready != null) webView.run("reader.setAnnotations(${vm.annotationsJson()})")
    }

    // Lifecycle: close the reading session when leaving, resume on return.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> { vm.pauseSpeed(); vm.flushSession() }
                Lifecycle.Event.ON_START -> vm.onResumeReading()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Immersive reading: hide system bars while the reader chrome is hidden.
    val activity = context as? Activity
    LaunchedEffect(state.chromeVisible, state.ready) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (state.chromeVisible || state.ready == null) controller.show(WindowInsetsCompat.Type.systemBars())
        else controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.isAppearanceLightStatusBars = !theme.dark
        controller.isAppearanceLightNavigationBars = !theme.dark
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.window?.let { w ->
                val ctl = WindowCompat.getInsetsController(w, w.decorView)
                ctl.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    LaunchedEffect(settings.keepScreenOn) { webView.keepScreenOn = settings.keepScreenOn }

    // Volume keys turn pages (not while reading aloud).
    DisposableEffect(settings.volumeKeysTurn, tts.status) {
        VolumeKeys.handler = if (settings.volumeKeysTurn && tts.status == TtsStatus.IDLE) { up ->
            if (up) vm.prev() else vm.next(); true
        } else null
        onDispose { VolumeKeys.handler = null }
    }

    BackHandler(enabled = state.selection != null || state.activeAnnotation != null) {
        vm.clearSelection(); vm.dismissAnnotation()
    }

    LaunchedEffect(state.message) {
        val m = state.message ?: return@LaunchedEffect
        if (m.startsWith("link:")) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(m.removePrefix("link:")))) }
            vm.consumeMessage()
        } else {
            kotlinx.coroutines.delay(2500)
            vm.consumeMessage()
        }
    }

    val chapterMinutes = state.location?.let { it.remainingSectionBytes / speed.bytesPerMinute }
    val bookMinutes = state.location?.let { it.remainingTotalBytes / speed.bytesPerMinute }
    val (clock, battery) = rememberClockAndBattery(settings.usesClockOrBattery)
    val infoData = InfoData(
        bookTitle = state.book?.title ?: state.ready?.title.orEmpty(),
        author = state.book?.authorLine ?: state.ready?.author.orEmpty(),
        location = state.location,
        chapterMinutes = chapterMinutes,
        bookMinutes = bookMinutes,
        clock = clock,
        battery = battery,
    )

    Box(Modifier.fillMaxSize().background(bg)) {
        val tabletop = layout.isTabletop && state.ready != null
        Column(Modifier.fillMaxSize()) {
            // Page area (top half in tabletop posture)
            Box(
                Modifier.fillMaxWidth().weight(1f).windowInsetsPadding(WindowInsets.displayCutout)
            ) {
                AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
                if (state.ready != null && !state.chromeVisible) {
                    AmbientInfo(settings, infoData, fg, Modifier.fillMaxSize())
                }
            }
            if (tabletop) {
                TabletopPanel(vm, state, tts.status, chapterMinutes, bookMinutes)
            }
        }

        // Loading / error overlay
        if (state.ready == null) {
            LoadingOverlay(state, bg, fg, onBack, vm::retry)
        }

        // Reader chrome
        AnimatedVisibility(state.chromeVisible && state.ready != null, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
            TopChrome(
                title = state.book?.title ?: state.ready?.title.orEmpty(),
                chapter = state.location?.tocLabel.orEmpty(),
                bookmarked = bookmarks.isNotEmpty() && vm.currentBookmark != null,
                onBack = onBack,
                onBookmark = vm::toggleBookmark,
                onSearch = { showSearch = true },
            )
        }
        AnimatedVisibility(
            state.chromeVisible && state.ready != null && !tabletop,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it },
        ) {
            BottomChrome(
                location = state.location,
                chapterMinutes = chapterMinutes,
                bookMinutes = bookMinutes,
                ttsStatus = tts.status,
                scope = settings.progressScope,
                onSeek = { f ->
                    if (settings.progressScope == ProgressScope.CHAPTER && ReaderInfoMath.chapterSliderFraction(state.location) != null) {
                        vm.goToChapterFraction(f)
                    } else vm.goToFraction(f)
                },
                onContents = { showContents = true },
                onSettings = { showSettings = true },
                onListen = vm::toggleTts,
                onSpeedRead = vm::startSpeedRead,
            )
        }

        // Read-aloud mini player
        AnimatedVisibility(
            tts.status != TtsStatus.IDLE && !(state.chromeVisible) && !tabletop,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it },
        ) {
            TtsBar(vm, tts.status, tts.sleepTimerEndsAt)
        }

        // Speed reading
        if (speedRead.active) {
            SpeedReadOverlay(
                state = speedRead,
                settings = speedSettings,
                bg = bg, fg = fg, accent = Color(theme.link),
                title = state.book?.title ?: state.ready?.title.orEmpty(),
                chapter = state.location?.tocLabel.orEmpty(),
                bookFraction = (state.location?.fraction ?: 0.0).toFloat(),
                onToggle = vm::toggleSpeed,
                onBack = vm::speedBackSentence,
                onWpm = { vm.setSpeedWpm(it) },
                onWpmFinished = vm::saveSpeedWpm,
                onSettings = vm::updateSpeedSettings,
                onClose = vm::exitSpeedRead,
            )
        }

        // Highlight editor
        state.activeAnnotation?.let { a ->
            AnnotationBar(
                a,
                modifier = Modifier.align(Alignment.BottomCenter),
                onColor = { vm.updateAnnotation(a, color = it) },
                onStyle = { vm.updateAnnotation(a, style = it) },
                onNote = { noteFor = a },
                onDelete = { vm.deleteAnnotation(a) },
                onClose = vm::dismissAnnotation,
            )
        }

        // Messages
        state.serverPosition?.let { position ->
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
                action = { TextButton(onClick = vm::jumpToServerPosition) { Text(stringResource(R.string.jump)) } },
                dismissAction = { IconButton(onClick = vm::dismissServerPosition) { Icon(Icons.Rounded.Close, stringResource(R.string.dismiss)) } },
            ) { Text(stringResource(if (position.other) R.string.further_other_reader else R.string.further_elsewhere, position.percent.toInt())) }
        }
        state.message?.takeIf { !it.startsWith("link:") }?.let { m ->
            Snackbar(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp)) { Text(m) }
        }
    }

    if (showSettings) ReaderSettingsSheet(settings, vm::updateSettings, onDismiss = { showSettings = false })
    if (showContents) ContentsSheet(
        toc = state.ready?.toc.orEmpty(),
        currentHref = state.location?.href,
        annotations = annotations,
        bookmarks = bookmarks,
        onGo = { target -> vm.goTo(target); showContents = false; vm.hideChrome() },
        onDeleteBookmark = { vm.deleteBookmark(it) },
        onDismiss = { showContents = false },
    )
    if (showSearch) SearchSheet(
        results = state.searchResults,
        searching = state.searching,
        onSearch = vm::search,
        onGo = { cfi -> vm.goTo(cfi); showSearch = false; vm.hideChrome() },
        onDismiss = { showSearch = false; vm.clearSearch() },
    )
    if (noteForSelection) {
        NoteDialog(initial = "", quote = state.selection?.text, onDismiss = { noteForSelection = false }) { text ->
            vm.highlightSelection(note = text)
            noteForSelection = false
        }
    }
    noteFor?.let { a ->
        NoteDialog(initial = a.note.orEmpty(), quote = a.text, onDismiss = { noteFor = null }) { text ->
            vm.updateAnnotation(a, note = text.ifBlank { null })
            noteFor = null
        }
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.quote), text))
}

private fun shareQuote(context: Context, text: String, title: String?, author: String?) {
    val body = "“${text.trim()}”" + (title?.let { "\n— $it" + (author?.let { a -> ", $a" } ?: "") } ?: "")
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body), context.getString(R.string.share_quote)))
}

@Composable
private fun LoadingOverlay(state: ReaderUiState, bg: Color, fg: Color, onBack: () -> Unit, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp).widthIn(max = 360.dp)) {
            state.book?.let { BookCover(it, Modifier.width(140.dp), elevation = 16.dp) }
            Spacer(Modifier.height(24.dp))
            if (state.error != null) {
                Text(state.error, color = fg, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                    Button(onClick = onRetry) { Text(stringResource(R.string.action_try_again)) }
                }
            } else {
                Text(state.loadingMessage ?: stringResource(R.string.opening), color = fg.copy(alpha = .8f))
                Spacer(Modifier.height(12.dp))
                val p = state.downloadProgress
                if (p != null && p > 0f) LinearProgressIndicator(progress = { p }, modifier = Modifier.width(200.dp))
                else LinearProgressIndicator(Modifier.width(200.dp))
            }
        }
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), tint = fg)
        }
    }
}

@Composable
private fun TopChrome(title: String, chapter: String, bookmarked: Boolean, onBack: () -> Unit, onBookmark: () -> Unit, onSearch: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.close_book)) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (chapter.isNotBlank()) Text(chapter, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onSearch) { Icon(Icons.Rounded.Search, stringResource(R.string.search_in_book)) }
            IconButton(onClick = onBookmark) {
                Icon(if (bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, stringResource(if (bookmarked) R.string.remove_bookmark else R.string.bookmark_page))
            }
        }
    }
}

@Composable
private fun BottomChrome(
    location: RelocateEvent?,
    chapterMinutes: Double?,
    bookMinutes: Double?,
    ttsStatus: TtsStatus,
    scope: ProgressScope,
    onSeek: (Float) -> Unit,
    onContents: () -> Unit,
    onSettings: () -> Unit,
    onListen: () -> Unit,
    onSpeedRead: () -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    // Chapter scope needs the page count of the chapter; until it is known the slider covers the book.
    val chapterSlider = if (scope == ProgressScope.CHAPTER) ReaderInfoMath.chapterSliderFraction(location) else null
    val fraction = if (dragging) dragValue else chapterSlider ?: (location?.fraction ?: 0.0).toFloat()
    val pages = location?.pagesInSection ?: 0
    val shownPage = if (chapterSlider != null) (if (dragging) ReaderInfoMath.pageAt(dragValue, pages) else location!!.pageInSection) else 0
    val percent = if (chapterSlider != null) {
        if (dragging) (100f * shownPage / pages).toInt() else ReaderInfoMath.chapterPercent(location) ?: 0
    } else (fraction * 100).toInt()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (chapterSlider != null) {
                        listOfNotNull(
                            stringResource(R.string.info_page_of, shownPage, pages),
                            chapterMinutes?.let { stringResource(R.string.left_in_chapter, formatMinutes(it)) },
                        ).joinToString(" · ")
                    } else {
                        listOfNotNull(
                            chapterMinutes?.let { stringResource(R.string.left_in_chapter, formatMinutes(it)) },
                            bookMinutes?.let { stringResource(R.string.left_in_book, formatMinutes(it)) },
                        ).joinToString(" · ")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("$percent%", style = MaterialTheme.typography.labelLarge)
            }
            Slider(
                value = fraction,
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = { onSeek(dragValue); dragging = false },
                colors = SliderDefaults.colors(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ChromeButton(Icons.AutoMirrored.Rounded.FormatListBulleted, stringResource(R.string.contents), onContents)
                ChromeButton(Icons.Rounded.FormatSize, stringResource(R.string.display), onSettings)
                ChromeButton(if (ttsStatus == TtsStatus.PLAYING) Icons.Rounded.Pause else Icons.Rounded.Headphones, stringResource(if (ttsStatus == TtsStatus.IDLE) R.string.listen else if (ttsStatus == TtsStatus.PLAYING) R.string.pause else R.string.resume), onListen)
                ChromeButton(Icons.Rounded.Bolt, stringResource(R.string.speed_read), onSpeedRead)
            }
        }
    }
}

@Composable
private fun ChromeButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    // One touch target (≥48dp) with a merged label for TalkBack.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .widthIn(min = 72.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(icon, null, Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TtsBar(vm: ReaderViewModel, status: TtsStatus, sleepEndsAt: Long?) {
    var sleepMenu by remember { mutableStateOf(false) }
    var langMenu by remember { mutableStateOf(false) }
    var showVoices by remember { mutableStateOf(false) }
    val c = com.vdelaar.mylibby.ui.appContainer()
    val tts = c.settings.tts.collectAsStateWithLifecycle().value
    val ttsState = c.tts.state.collectAsStateWithLifecycle().value
    val override = tts.bookLanguages[vm.bookId]
    val current = override ?: vm.autoTtsLanguage
    fun langName(code: String) = when (code) { "nl" -> "Nederlands"; "en" -> "English"; else -> java.util.Locale.forLanguageTag(code).displayLanguage }
    fun flag(code: String) = when (code) { "nl" -> "🇳🇱"; "en" -> "🇬🇧"; else -> "🌐" }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = 6.dp,
        modifier = Modifier.navigationBarsPadding().padding(16.dp).widthIn(max = 560.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            ttsState.error?.let { err ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = { c.tts.installVoiceData() }) { Text(stringResource(R.string.action_install)) }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                IconButton(onClick = vm::onPreviousRequested) { Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.prev_paragraph)) }
                FilledIconButton(onClick = vm::toggleTts, modifier = Modifier.padding(horizontal = 8.dp).size(52.dp)) {
                    Icon(if (status == TtsStatus.PLAYING) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(if (status == TtsStatus.PLAYING) R.string.pause else R.string.play))
                }
                IconButton(onClick = vm::onNextRequested) { Icon(Icons.Rounded.SkipNext, stringResource(R.string.next_paragraph)) }
                IconButton(onClick = vm::stopTts) { Icon(Icons.Rounded.Stop, stringResource(R.string.stop_reading_aloud)) }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    androidx.compose.material3.AssistChip(
                        onClick = { langMenu = true },
                        label = { Text("${flag(current)} ${langName(current)}" + if (override == null) stringResource(R.string.auto_suffix) else "") },
                        trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(18.dp)) },
                    )
                    DropdownMenu(expanded = langMenu, onDismissRequest = { langMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.automatic_lang, langName(vm.autoTtsLanguage))) },
                            onClick = { vm.setTtsLanguage(null); langMenu = false },
                            trailingIcon = { if (override == null) Icon(Icons.Rounded.Check, null) },
                        )
                        listOf("nl", "en").forEach { code ->
                            DropdownMenuItem(
                                text = { Text("${flag(code)}  ${langName(code)}") },
                                onClick = { vm.setTtsLanguage(code); langMenu = false },
                                trailingIcon = { if (override == code) Icon(Icons.Rounded.Check, null) },
                            )
                        }
                        androidx.compose.material3.HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.choose_voice)) },
                            leadingIcon = { Icon(Icons.Rounded.RecordVoiceOver, null) },
                            onClick = { langMenu = false; showVoices = true },
                        )
                    }
                }
                androidx.compose.material3.AssistChip(
                    onClick = {
                        val rates = listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.8f, 2.0f)
                        val next = rates.firstOrNull { it > tts.speechRate + 0.01f } ?: rates.first()
                        vm.setSpeechRate(next)
                    },
                    label = { Text("${"%.1f".format(tts.speechRate)}×") },
                )
                Box {
                    androidx.compose.material3.AssistChip(
                        onClick = { sleepMenu = true },
                        label = { Text(stringResource(if (sleepEndsAt != null) R.string.sleep_on else R.string.sleep)) },
                        leadingIcon = {
                            Icon(Icons.Rounded.Timer, null, Modifier.size(18.dp), tint = if (sleepEndsAt != null) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                        },
                    )
                    DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                        listOf(0, 10, 15, 30, 45, 60).forEach { m ->
                            DropdownMenuItem(text = { Text(if (m == 0) stringResource(R.string.off) else stringResource(R.string.n_minutes, m)) }, onClick = { vm.setSleepTimer(m); sleepMenu = false })
                        }
                    }
                }
            }
        }
    }

    if (showVoices) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showVoices = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
                Text(stringResource(R.string.voices), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                com.vdelaar.mylibby.ui.settings.VoicePickerSection()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Tabletop posture: controls live on the bottom half of a half-folded device. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.TabletopPanel(vm: ReaderViewModel, state: ReaderUiState, status: TtsStatus, chapterMinutes: Double?, bookMinutes: Double?) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().weight(1f)) {
        Column(Modifier.fillMaxSize().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.location?.tocLabel.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(
                listOfNotNull(chapterMinutes?.let { stringResource(R.string.left_in_chapter, formatMinutes(it)) }, bookMinutes?.let { stringResource(R.string.left_in_book, formatMinutes(it)) }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconButton(onClick = vm::prev) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.prev_page)) }
                FilledIconButton(onClick = vm::toggleTts, modifier = Modifier.size(64.dp)) {
                    Icon(if (status == TtsStatus.PLAYING) Icons.Rounded.Pause else Icons.Rounded.Headphones, stringResource(R.string.read_aloud))
                }
                IconButton(onClick = vm::next) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.next_page)) }
            }
            if (status != TtsStatus.IDLE) TextButton(onClick = vm::stopTts) { Text(stringResource(R.string.stop_reading_aloud)) }
        }
    }
}

@Composable
private fun AnnotationBar(
    a: AnnotationEntity,
    modifier: Modifier,
    onColor: (String) -> Unit,
    onStyle: (String) -> Unit,
    onNote: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
        modifier = modifier.navigationBarsPadding().padding(16.dp).widthIn(max = 560.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            if (!a.note.isNullOrBlank()) {
                Text("📝 ${a.note}", style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                HighlightColors.forEach { hex ->
                    val color = Color(android.graphics.Color.parseColor(hex))
                    Box(
                        Modifier.padding(4.dp).size(32.dp).clip(RoundedCornerShape(16.dp)).background(color)
                            .then(if (a.color.equals(hex, true)) Modifier.padding(2.dp) else Modifier)
                    ) {
                        IconButton(onClick = { onColor(hex) }, modifier = Modifier.fillMaxSize()) {
                            if (a.color.equals(hex, true)) Text("✓", color = Color.Black.copy(alpha = .7f))
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onNote) { Icon(Icons.Rounded.EditNote, stringResource(R.string.add_note)) }
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, stringResource(R.string.delete_highlight)) }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.action_close)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                listOf("highlight" to R.string.style_highlight, "underline" to R.string.style_underline, "squiggly" to R.string.style_squiggly, "strikethrough" to R.string.style_strike).forEach { (s, label) ->
                    androidx.compose.material3.FilterChip(selected = a.style == s, onClick = { onStyle(s) }, label = { Text(stringResource(label)) })
                }
            }
        }
    }
}

@Composable
private fun NoteDialog(initial: String, quote: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.note)) },
        text = {
            Column {
                quote?.let {
                    Text("“${it.take(240)}”", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(text, { text = it }, placeholder = { Text(stringResource(R.string.your_thoughts)) }, minLines = 3, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
