package com.vdelaar.mylibby.ui.home

import androidx.compose.foundation.background
import com.vdelaar.mylibby.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.vdelaar.mylibby.core.datastore.GoalType
import com.vdelaar.mylibby.core.model.Book
import com.vdelaar.mylibby.data.ReadingSpeed
import com.vdelaar.mylibby.data.StreakInfo
import com.vdelaar.mylibby.ui.adaptive.rememberDeviceLayout
import com.vdelaar.mylibby.ui.appViewModel
import com.vdelaar.mylibby.ui.components.BookCover
import com.vdelaar.mylibby.ui.components.EmptyState
import com.vdelaar.mylibby.ui.components.OfflineBanner
import com.vdelaar.mylibby.ui.components.ProfileAvatar
import com.vdelaar.mylibby.ui.components.ProgressRing
import com.vdelaar.mylibby.ui.components.SectionHeader
import com.vdelaar.mylibby.ui.components.StreakFlame
import com.vdelaar.mylibby.ui.components.coverUrl
import com.vdelaar.mylibby.ui.navigation.AppNavigator
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navigator: AppNavigator, onOpenLibrary: () -> Unit, onOpenActivity: () -> Unit, onOpenProfile: () -> Unit) {
    val vm = appViewModel { HomeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val streak by vm.streak.collectAsStateWithLifecycle()
    val speed by vm.speed.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val favourites by vm.favourites.collectAsStateWithLifecycle()
    val onDevice by vm.onDevice.collectAsStateWithLifecycle()
    val ownBooks by vm.ownBooks.collectAsStateWithLifecycle()
    val favIds by vm.favouriteIds.collectAsStateWithLifecycle()
    val dlIds by vm.downloadedIds.collectAsStateWithLifecycle()
    val demo by vm.demo.collectAsStateWithLifecycle()
    val localOnly by vm.localOnly.collectAsStateWithLifecycle()
    val layout = rememberDeviceLayout()
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        vm.onResume()
        onPauseOrDispose { }
    }
    val coverWidth = if (layout.isWide) 132.dp else 112.dp

    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { vm.load(refresh = true) }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(greeting()), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(profile.name.ifBlank { stringResource(R.string.reader_fallback_name) }, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    ProfileAvatar(profile, 40.dp, Modifier.clickable(onClick = onOpenProfile))
                    IconButton(onClick = navigator::settings) { Icon(Icons.Rounded.Settings, stringResource(R.string.settings)) }
                }
            }
            if (demo) item { DemoBanner(onConnect = { vm.connectServer(navigator::toOnboarding) }) }
            if (state.offline && !demo) item { OfflineBanner() }

            val hero = state.continueReading.firstOrNull()
            item {
                if (layout.isWide) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // Medium widths (720-840 dp) get a more even split so the goal card keeps room for its text.
                        Box(Modifier.weight(if (layout.isExpanded) 1.4f else 1.05f).fillMaxHeight()) {
                            if (hero != null) HeroCard(hero, speed, onRead = { navigator.read(hero.id) }, onOpen = { navigator.openBook(hero.id) })
                            else if (!state.loading && !(localOnly && ownBooks.isNotEmpty())) StartCard(onOpenLibrary, localOnly)
                        }
                        Box(Modifier.weight(1f).fillMaxHeight()) { GoalCard(streak, onOpenActivity, Modifier.fillMaxHeight()) }
                    }
                } else {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (hero != null) HeroCard(hero, speed, onRead = { navigator.read(hero.id) }, onOpen = { navigator.openBook(hero.id) })
                        else if (!state.loading && !(localOnly && ownBooks.isNotEmpty())) StartCard(onOpenLibrary, localOnly)
                        GoalCard(streak, onOpenActivity)
                    }
                }
            }

            if (state.loading) {
                item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            }

            val others = state.continueReading.drop(1)
            if (others.isNotEmpty()) item {
                BookRow(stringResource(R.string.home_continue_reading), others, coverWidth, favIds, dlIds, navigator, showProgress = true)
            }
            if (favourites.isNotEmpty()) item {
                BookRow(stringResource(R.string.favorites), favourites, coverWidth, favIds, dlIds, navigator)
            }
            if (state.recentlyAdded.isNotEmpty()) item {
                BookRow(stringResource(R.string.home_recently_added), state.recentlyAdded, coverWidth, favIds, dlIds, navigator, action = stringResource(R.string.action_see_all), onAction = onOpenLibrary)
            }
            if (ownBooks.isNotEmpty()) item {
                BookRow(stringResource(R.string.home_own_books), ownBooks, coverWidth, favIds, dlIds, navigator)
            }
            if (onDevice.isNotEmpty()) item {
                BookRow(stringResource(R.string.home_on_device), onDevice, coverWidth, favIds, dlIds, navigator, action = stringResource(R.string.action_manage), onAction = navigator::downloads)
            }
            if (!state.loading && state.continueReading.isEmpty() && state.recentlyAdded.isEmpty() && state.error != null) {
                item { EmptyState("📡", stringResource(R.string.home_unreachable), state.error ?: "", action = stringResource(R.string.action_retry), onAction = { vm.load() }) }
            }
        }
    }
}

private fun greeting(): Int = when (LocalTime.now().hour) {
    in 5..11 -> R.string.greeting_morning
    in 12..17 -> R.string.greeting_afternoon
    in 18..22 -> R.string.greeting_evening
    else -> R.string.greeting_night
}

@Composable
private fun HeroCard(book: Book, speed: ReadingSpeed?, onRead: () -> Unit, onOpen: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).clickable(onClick = onOpen),
    ) {
        Box {
            AsyncImage(
                model = coverUrl(book, thumbnail = true),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().blur(40.dp),
                alpha = .45f,
            )
            Box(
                Modifier.matchParentSize().background(
                    Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .3f), MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .9f)))
                )
            )
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                BookCover(book, Modifier.width(104.dp), elevation = 12.dp)
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_continue_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(book.title, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(book.authorLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Spacer(Modifier.height(10.dp))
                    val p = (book.progress ?: 0f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressRing(p / 100f, Modifier.size(28.dp), stroke = 4.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.percent_read, p.toInt()), style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRead) {
                        Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_read))
                    }
                }
            }
        }
    }
}

@Composable
private fun StartCard(onOpenLibrary: () -> Unit, local: Boolean = false) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp)) {
            Text(stringResource(if (local) R.string.home_local_title else R.string.home_pick_next), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(if (local) R.string.home_local_body else R.string.home_pick_next_body), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(14.dp))
            Button(onClick = onOpenLibrary) { Text(stringResource(R.string.home_browse)) }
        }
    }
}

@Composable
fun GoalCard(streak: StreakInfo, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val unit = stringResource(if (streak.goalType == GoalType.MINUTES) R.string.unit_min else R.string.unit_pages)
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(
                streak.todayFraction,
                Modifier.size(84.dp),
                stroke = 9.dp,
                color = MaterialTheme.colorScheme.primary,
                track = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .12f),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${streak.todayValue}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("/${streak.target} $unit", fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.goal_today), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    when {
                        streak.todayMet -> stringResource(R.string.goal_reached)
                        streak.todayValue > 0 -> stringResource(R.string.goal_to_go, streak.target - streak.todayValue, unit)
                        else -> stringResource(R.string.goal_lets_read)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                StreakFlame(active = streak.current > 0, modifier = Modifier.size(36.dp, 44.dp))
                Text("${streak.current}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(stringResource(R.string.day_streak), fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
fun BookRow(
    title: String,
    books: List<Book>,
    coverWidth: androidx.compose.ui.unit.Dp,
    favourites: Set<Long>,
    downloaded: Set<Long>,
    navigator: AppNavigator,
    showProgress: Boolean = false,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    subtitle: String? = null,
) {
    Column(Modifier.padding(top = 12.dp)) {
        SectionHeader(title, action = action, onAction = onAction)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(books, key = { it.id }) { book ->
                Column(Modifier.width(coverWidth).clip(RoundedCornerShape(8.dp)).clickable { navigator.openBook(book.id) }) {
                    BookCover(book, Modifier.fillMaxWidth(), showProgress = showProgress || (book.progress ?: 0f) > 0f, downloaded = book.id in downloaded, favourite = book.id in favourites)
                    Spacer(Modifier.height(8.dp))
                    Text(book.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(book.authorLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun DemoBanner(onConnect: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(18.dp)) {
            Text(stringResource(R.string.demo_banner_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.demo_banner_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onConnect) { Text(stringResource(R.string.demo_connect)) }
        }
    }
}
