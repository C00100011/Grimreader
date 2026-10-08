package com.vdelaar.mylibby.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vdelaar.mylibby.MyLibbyApp
import com.vdelaar.mylibby.di.AppContainer

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as MyLibbyApp).container

/** Creates a ViewModel with access to the app's dependency container. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = appContainer()
    return viewModel(key = key) { create(container) }
}
