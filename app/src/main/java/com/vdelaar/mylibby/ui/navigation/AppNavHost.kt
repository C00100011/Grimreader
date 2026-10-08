package com.vdelaar.mylibby.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import com.vdelaar.mylibby.ui.adaptive.rememberDeviceLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.vdelaar.mylibby.ui.activity.ActivityScreen
import com.vdelaar.mylibby.ui.appContainer
import com.vdelaar.mylibby.ui.bookdetail.BookDetailScreen
import com.vdelaar.mylibby.ui.collection.CollectionScreen
import com.vdelaar.mylibby.ui.discover.DiscoverScreen
import com.vdelaar.mylibby.ui.home.HomeScreen
import kotlinx.coroutines.launch
import com.vdelaar.mylibby.ui.library.LibraryScreen
import com.vdelaar.mylibby.ui.onboarding.OnboardingScreen
import com.vdelaar.mylibby.ui.profile.ProfileScreen
import com.vdelaar.mylibby.ui.reader.ReaderScreen
import com.vdelaar.mylibby.ui.settings.DownloadsScreen
import com.vdelaar.mylibby.ui.suggestions.SuggestionsScreen
import com.vdelaar.mylibby.ui.settings.LibrariesScreen
import com.vdelaar.mylibby.ui.settings.SettingsScreen

/** Navigation callbacks shared by all screens. */
class AppNavigator(private val nav: NavHostController) {
    fun openBook(id: Long) = nav.navigate(BookRoute(id)) { launchSingleTop = true }
    fun read(id: Long) = nav.navigate(ReaderRoute(id)) { launchSingleTop = true }
    fun series(name: String) = nav.navigate(CollectionRoute("series", name))
    fun author(name: String) = nav.navigate(CollectionRoute("author", name))
    fun settings() = nav.navigate(SettingsRoute()) { launchSingleTop = true }
    fun integrations() = nav.navigate(SettingsRoute("INTEGRATIONS")) { launchSingleTop = true }
    /** Settings opened straight on a page ([page] = a SettingsPage name, e.g. "READING"). */
    fun settingsPage(page: String) = nav.navigate(SettingsRoute(page)) { launchSingleTop = true }
    fun suggestions() = nav.navigate(SuggestionsRoute) { launchSingleTop = true }
    fun libraries() = nav.navigate(LibrariesRoute) { launchSingleTop = true }
    fun opds(url: String = "") = nav.navigate(OpdsRoute(url))
    fun downloads() = nav.navigate(DownloadsRoute) { launchSingleTop = true }
    fun swipe() = nav.navigate(SwipeRoute) { launchSingleTop = true }
    fun wanted() = nav.navigate(WantedRoute) { launchSingleTop = true }
    fun back() { nav.popBackStack() }
    fun toOnboarding() = nav.navigate(OnboardingRoute()) { popUpTo(0) { inclusive = true } }
    fun connectLibrary() = nav.navigate(OnboardingRoute(connectOnly = true)) { launchSingleTop = true }
    fun toMain() = nav.navigate(MainRoute) { popUpTo(0) { inclusive = true } }
}

@Composable
fun AppNavHost() {
    val c = appContainer()
    val nav = rememberNavController()
    val navigator = androidx.compose.runtime.remember(nav) { AppNavigator(nav) }
    val loggedIn by c.auth.loggedIn.collectAsStateWithLifecycle()
    val app by c.settings.app.collectAsStateWithLifecycle()
    // The start destination is decided once; later changes navigate explicitly.
    val start: Any = androidx.compose.runtime.remember { if ((loggedIn || app.noServer) && app.onboardingDone) MainRoute else OnboardingRoute() }

    // A book opened from another app: go straight to the reader (works without a Grimmory login).
    val pendingOpen by com.vdelaar.mylibby.OpenRequests.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pendingOpen) {
        pendingOpen?.let { id ->
            com.vdelaar.mylibby.OpenRequests.pending.value = null
            navigator.read(id)
        }
    }

    // Session expired (refresh token rejected) -> back to sign-in.
    LaunchedEffect(loggedIn) {
        if (!loggedIn && !c.settings.app.value.noServer && nav.currentDestination?.route?.contains("OnboardingRoute") == false) navigator.toOnboarding()
    }

    NavHost(
        navController = nav,
        startDestination = start,
        enterTransition = { slideInHorizontally { it / 4 } + fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutHorizontally { it / 4 } + fadeOut() },
    ) {
        composable<OnboardingRoute> { entry -> OnboardingScreen(connectOnly = entry.toRoute<OnboardingRoute>().connectOnly, onFinished = navigator::toMain, onCancel = navigator::back) }
        composable<MainRoute> { MainScaffold(navigator) }
        composable<BookRoute> { entry ->
            BookDetailScreen(entry.toRoute<BookRoute>().id, navigator, onBack = navigator::back)
        }
        composable<ReaderRoute>(
            enterTransition = { scaleIn(initialScale = .92f) + fadeIn() },
            popExitTransition = { scaleOut(targetScale = .92f) + fadeOut() },
        ) { entry ->
            ReaderScreen(entry.toRoute<ReaderRoute>().id, onBack = navigator::back)
        }
        composable<CollectionRoute> { entry ->
            val r = entry.toRoute<CollectionRoute>()
            CollectionScreen(r.kind, r.name, navigator)
        }
        composable<SettingsRoute> { entry -> SettingsScreen(navigator, entry.toRoute<SettingsRoute>().page) }
        composable<SuggestionsRoute> { SuggestionsScreen(navigator) }
        composable<LibrariesRoute> { LibrariesScreen(navigator) }
        composable<OpdsRoute> { entry -> com.vdelaar.mylibby.ui.opds.OpdsScreen(entry.toRoute<OpdsRoute>().url, navigator) }
        composable<DownloadsRoute> { DownloadsScreen(navigator) }
        composable<SwipeRoute> { com.vdelaar.mylibby.ui.newbooks.SwipeScreen(navigator) }
        composable<WantedRoute> { com.vdelaar.mylibby.ui.newbooks.WantedScreen(navigator) }
    }

    val untrusted by c.api.untrustedCert.collectAsStateWithLifecycle()
    val trustScope = androidx.compose.runtime.rememberCoroutineScope()
    untrusted?.let { cert ->
        com.vdelaar.mylibby.ui.components.TrustCertificateDialog(cert, onTrust = { trustScope.launch { c.api.trust(cert) } }, onDismiss = c.api::dismissUntrusted)
    }
}

private data class TabItem(val tab: Tab, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    TabItem(Tab.HOME, Icons.Outlined.Home, Icons.Rounded.Home),
    TabItem(Tab.BOOKS, Icons.AutoMirrored.Outlined.LibraryBooks, Icons.AutoMirrored.Rounded.LibraryBooks),
    TabItem(Tab.DISCOVER, Icons.Outlined.Explore, Icons.Rounded.Explore),
    TabItem(Tab.ACTIVITY, Icons.Outlined.Insights, Icons.Rounded.Insights),
    TabItem(Tab.PROFILE, Icons.Outlined.AccountCircle, Icons.Rounded.AccountCircle),
)

/** Bottom bar on phones, navigation rail on foldables and tablets (Material adaptive). */
@Composable
private fun MainScaffold(navigator: AppNavigator) {
    var current by rememberSaveable { mutableStateOf(Tab.HOME) }
    val appSettings by appContainer().settings.app.collectAsStateWithLifecycle()
    val demo = appSettings.demo
    androidx.compose.runtime.LaunchedEffect(appSettings.showDiscover) { if (!appSettings.showDiscover && current == Tab.DISCOVER) current = Tab.HOME }
    // "Request this book" from another screen: show Discover, which starts the search.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.vdelaar.mylibby.ui.UiRequests.discoverQuery.collect { q -> if (q != null && appSettings.showDiscover) current = Tab.DISCOVER }
    }
    // Bottom bar below 720 dp (a rail would eat into the content); the adaptive default (rail) from there on.
    val adaptiveInfo = currentWindowAdaptiveInfo()
    val navType = if (rememberDeviceLayout().isWide) NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptiveInfo) else NavigationSuiteType.NavigationBar
    NavigationSuiteScaffold(
        layoutType = navType,
        navigationSuiteItems = {
            tabs.filter { it.tab != Tab.DISCOVER || appSettings.showDiscover }.forEach { item ->
                item(
                    selected = current == item.tab,
                    onClick = { current = item.tab },
                    icon = { Icon(if (current == item.tab) item.selectedIcon else item.icon, null) },
                    label = { Text(stringResource(item.tab.label)) },
                )
            }
        },
    ) {
        when (current) {
            Tab.HOME -> HomeScreen(navigator, onOpenLibrary = { current = Tab.BOOKS }, onOpenActivity = { current = Tab.ACTIVITY }, onOpenProfile = { current = Tab.PROFILE })
            Tab.BOOKS -> LibraryScreen(navigator)
            Tab.DISCOVER -> if (demo) DemoDiscover(navigator) else DiscoverScreen(navigator)
            Tab.ACTIVITY -> ActivityScreen(navigator)
            Tab.PROFILE -> ProfileScreen(navigator, onOpenActivity = { current = Tab.ACTIVITY })
        }
    }
}

/** The Discover tab needs a Shelfmark server; in demo mode it explains that instead. */
@Composable
private fun DemoDiscover(navigator: AppNavigator) {
    com.vdelaar.mylibby.ui.components.EmptyState(
        emoji = "🧭",
        title = stringResource(com.vdelaar.mylibby.R.string.demo_discover_title),
        message = stringResource(com.vdelaar.mylibby.R.string.demo_discover_body),
        modifier = androidx.compose.ui.Modifier.fillMaxSize().androidx_statusBars(),
    )
}

private fun androidx.compose.ui.Modifier.androidx_statusBars() = this.then(androidx.compose.ui.Modifier.statusBarsPadding())
