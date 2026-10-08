package com.vdelaar.mylibby.ui.newbooks

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.core.database.SwipeCardEntity
import com.vdelaar.mylibby.data.Swipe
import com.vdelaar.mylibby.data.toIdea
import com.vdelaar.mylibby.di.AppContainer
import com.vdelaar.mylibby.ui.appViewModel
import coil3.compose.SubcomposeAsyncImage
import com.vdelaar.mylibby.ui.components.EmptyState
import com.vdelaar.mylibby.ui.components.GeneratedCover
import com.vdelaar.mylibby.ui.navigation.AppNavigator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

enum class DeckStatus { LOADING, READY, NO_CONNECTION }

class SwipeViewModel(private val c: AppContainer) : ViewModel() {
    val deck: StateFlow<List<SwipeCardEntity>> = c.swipe.deck().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _status = MutableStateFlow(DeckStatus.LOADING)
    val status: StateFlow<DeckStatus> = _status.asStateFlow()

    init { prepare() }

    fun prepare() {
        viewModelScope.launch {
            _status.update { DeckStatus.LOADING }
            val ok = runCatching { c.swipe.ensureDeck() }.getOrDefault(false)
            _status.update { if (ok) DeckStatus.READY else DeckStatus.NO_CONNECTION }
        }
    }

    fun decide(card: SwipeCardEntity, swipe: Swipe) { viewModelScope.launch { c.swipe.decide(card, swipe) } }
    fun undo() { viewModelScope.launch { c.swipe.undo() } }
}

/** This week's picks: swipe right for "want to read", up for "love it", left for "no". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeScreen(navigator: AppNavigator) {
    val vm = appViewModel { SwipeViewModel(it) }
    val deck by vm.deck.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val remaining = deck.filter { it.decision == null }
    var open by remember { mutableStateOf<SwipeCardEntity?>(null) }
    var command by remember { mutableStateOf<Swipe?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.swipe_title))
                        if (deck.isNotEmpty()) Text(stringResource(R.string.swipe_progress, deck.size - remaining.size, deck.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) } },
                actions = { TextButton(onClick = navigator::wanted) { Text(stringResource(R.string.swipe_my_list)) } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                deck.isEmpty() && status == DeckStatus.LOADING -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                deck.isEmpty() -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState("📡", stringResource(R.string.swipe_no_deck), stringResource(R.string.swipe_no_deck_body))
                    Button(onClick = vm::prepare) { Text(stringResource(R.string.action_try_again)) }
                }
                remaining.isEmpty() -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState("🎉", stringResource(R.string.swipe_done_title), stringResource(R.string.swipe_done_body))
                    Button(onClick = navigator::wanted) { Text(stringResource(R.string.swipe_my_list)) }
                    if (deck.any { it.decision != null }) TextButton(onClick = vm::undo) { Text(stringResource(R.string.swipe_undo)) }
                }
                else -> Column(Modifier.fillMaxSize().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.swipe_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                        remaining.getOrNull(1)?.let { next -> SwipeCardFace(next, Modifier.graphicsLayer { scaleX = .94f; scaleY = .94f }.offset(y = 14.dp).alpha(.85f)) }
                        val top = remaining.first()
                        androidx.compose.runtime.key(top.key) {
                            TopCard(top, command, onOpen = { open = top }, onSwiped = { s -> command = null; vm.decide(top, s) })
                        }
                    }
                    Row(Modifier.padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(onClick = vm::undo, enabled = deck.any { it.decision != null }) { Icon(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.swipe_undo)) }
                        FilledIconButton(
                            onClick = { command = Swipe.NO }, modifier = Modifier.size(64.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                        ) { Icon(Icons.Rounded.Close, stringResource(R.string.swipe_no), Modifier.size(32.dp)) }
                        FilledIconButton(
                            onClick = { command = Swipe.LOVE }, modifier = Modifier.size(64.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer),
                        ) { Icon(Icons.Rounded.Favorite, stringResource(R.string.swipe_love), Modifier.size(30.dp)) }
                        FilledIconButton(
                            onClick = { command = Swipe.YES }, modifier = Modifier.size(64.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                        ) { Icon(Icons.Rounded.Check, stringResource(R.string.swipe_yes), Modifier.size(32.dp)) }
                    }
                }
            }
        }
    }

    open?.let { card ->
        IdeaSheet(card.toIdea(), onOpenLibrary = { open = null; navigator.openBook(it.id) }, onDismiss = { open = null }, onRequested = navigator::back)
    }
}

/** The card on top: drag it (or press a button) and it flies off the way it was sent. */
@Composable
private fun TopCard(card: SwipeCardEntity, command: Swipe?, onOpen: () -> Unit, onSwiped: (Swipe) -> Unit) {
    val x = remember { Animatable(0f) }
    val y = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val threshold = with(density) { 96.dp.toPx() }
    val far = with(density) { (config.screenWidthDp.dp * 1.5f).toPx() }
    val up = with(density) { (config.screenHeightDp.dp * 1.2f).toPx() }

    suspend fun fling(s: Swipe) {
        when (s) {
            Swipe.YES -> x.animateTo(far, tween(240))
            Swipe.NO -> x.animateTo(-far, tween(240))
            Swipe.LOVE -> y.animateTo(-up, tween(260))
        }
        onSwiped(s)
    }
    LaunchedEffect(command) { command?.let { fling(it) } }

    val yes = (x.value / threshold).coerceIn(0f, 1f)
    val no = (-x.value / threshold).coerceIn(0f, 1f)
    val love = (-y.value / threshold).coerceIn(0f, 1f).let { if (abs(y.value) > abs(x.value)) it else 0f }

    Box(
        Modifier
            .offset { IntOffset(x.value.roundToInt(), y.value.roundToInt()) }
            .graphicsLayer { rotationZ = x.value / 40f }
            .pointerInput(card.key) {
                detectDragGestures(
                    onDragEnd = {
                        val dx = x.value
                        val dy = y.value
                        scope.launch {
                            when {
                                dy < -threshold && abs(dy) > abs(dx) -> fling(Swipe.LOVE)
                                dx > threshold -> fling(Swipe.YES)
                                dx < -threshold -> fling(Swipe.NO)
                                else -> {
                                    launch { x.animateTo(0f, spring(Spring.DampingRatioMediumBouncy)) }
                                    y.animateTo(0f, spring(Spring.DampingRatioMediumBouncy))
                                }
                            }
                        }
                    },
                    onDragCancel = { scope.launch { x.animateTo(0f); y.animateTo(0f) } },
                ) { change, drag ->
                    change.consume()
                    scope.launch { x.snapTo(x.value + drag.x); y.snapTo(y.value + drag.y) }
                }
            },
    ) {
        SwipeCardFace(card, Modifier.clickable(onClick = onOpen))
        Stamp(stringResource(R.string.swipe_stamp_yes), yes, MaterialTheme.colorScheme.primary, Alignment.TopStart)
        Stamp(stringResource(R.string.swipe_stamp_no), no, MaterialTheme.colorScheme.error, Alignment.TopEnd)
        Stamp(stringResource(R.string.swipe_stamp_love), love, MaterialTheme.colorScheme.tertiary, Alignment.TopCenter)
    }
}

@Composable
private fun BoxScope.Stamp(text: String, amount: Float, color: androidx.compose.ui.graphics.Color, align: Alignment) {
    if (amount <= 0.02f) return
    // matchParentSize: the stamp follows the size of the card. fillMaxSize would stretch the card's box to the whole
    // screen as soon as a stamp shows, throwing the card off-centre and the stamp into a corner on wide screens.
    Box(Modifier.matchParentSize().padding(24.dp), contentAlignment = align) {
        Surface(
            // A soft backing keeps the word readable on any cover.
            shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .8f),
            border = androidx.compose.foundation.BorderStroke(3.dp, color), modifier = Modifier.alpha(amount),
        ) { Text(text, color = color, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
    }
}

@Composable
private fun SwipeCardFace(card: SwipeCardEntity, modifier: Modifier = Modifier) {
    val idea = card.toIdea()
    Surface(
        modifier.widthIn(max = CARD_MAX_WIDTH).fillMaxWidth().heightIn(max = CARD_MAX_HEIGHT).fillMaxHeight(),
        shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            // The cover fills the space the text leaves, without growing past it (the plain cover insists on its own shape).
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                val url = idea.coverUrl('L')
                if (url == null) GeneratedCover(idea.title, idea.authorLine, Modifier.fillMaxSize())
                else SubcomposeAsyncImage(
                    model = url, contentDescription = idea.title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                    loading = { GeneratedCover(idea.title, idea.authorLine, Modifier.fillMaxSize()) },
                    error = { GeneratedCover(idea.title, idea.authorLine, Modifier.fillMaxSize()) },
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(idea.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(idea.authorLine, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val facts = listOfNotNull(idea.year?.toString(), idea.subjects.firstOrNull()).joinToString(" · ")
            if (facts.isNotEmpty()) Text(facts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val CARD_MAX_HEIGHT = 560.dp
private val CARD_MAX_WIDTH = 440.dp
