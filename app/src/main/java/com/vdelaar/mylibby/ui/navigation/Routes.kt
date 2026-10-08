package com.vdelaar.mylibby.ui.navigation

import kotlinx.serialization.Serializable

/** [connectOnly] = just the sign-in step (from Settings → Integrations), not the whole welcome tour. */
@Serializable data class OnboardingRoute(val connectOnly: Boolean = false)
@Serializable data object MainRoute
@Serializable data class BookRoute(val id: Long)
@Serializable data class ReaderRoute(val id: Long)
@Serializable data class CollectionRoute(val kind: String, val name: String)
/** [page] = a SettingsPage name to open directly (e.g. "INTEGRATIONS"). */
@Serializable data class SettingsRoute(val page: String? = null)
@Serializable data object SwipeRoute
@Serializable data object WantedRoute
@Serializable data object DownloadsRoute
@Serializable data object SuggestionsRoute
@Serializable data class OpdsRoute(val url: String = "")
@Serializable data object LibrariesRoute

enum class Tab(@androidx.annotation.StringRes val label: Int) {
    HOME(com.vdelaar.mylibby.R.string.tab_home),
    BOOKS(com.vdelaar.mylibby.R.string.tab_books),
    DISCOVER(com.vdelaar.mylibby.R.string.tab_discover),
    ACTIVITY(com.vdelaar.mylibby.R.string.tab_activity),
    PROFILE(com.vdelaar.mylibby.R.string.tab_profile),
}
