package com.vdelaar.mylibby.ui

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Things one screen asks another to do. "Request this book" from a list of wanted books: the text to look up goes
 * here, the main screen switches to Discover, and Discover starts the search and clears it.
 */
object UiRequests {
    val discoverQuery = MutableStateFlow<String?>(null)
}
