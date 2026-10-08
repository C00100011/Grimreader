package com.vdelaar.mylibby

import android.os.Bundle
import android.view.KeyEvent
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.vdelaar.mylibby.core.datastore.ThemeMode
import com.vdelaar.mylibby.ui.navigation.AppNavHost
import com.vdelaar.mylibby.ui.theme.MyLibbyTheme

/** A book opened from outside the app ("Open with MyLibby"); the nav host opens it in the reader. */
object OpenRequests {
    val pending = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
}

/** Screens that must not follow the device theme (onboarding is always light). */
object ThemeOverride {
    val forceLight = kotlinx.coroutines.flow.MutableStateFlow(false)
}

/** Hardware volume keys can turn pages; the reader registers a handler here. TODO: doesnt seem to be working */
object VolumeKeys {
    var handler: ((up: Boolean) -> Boolean)? = null
}

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        val app = newBase.applicationContext as? MyLibbyApp
        val tag = runCatching { app?.container?.settings?.app?.value?.language }.getOrNull() ?: "system"
        super.attachBaseContext(com.vdelaar.mylibby.core.AppLanguage.wrap(newBase, tag))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as MyLibbyApp).container
        handleOpenIntent(intent)
        setContent {
            val app by container.settings.app.collectAsStateWithLifecycle()
            val forceLight by ThemeOverride.forceLight.collectAsStateWithLifecycle()
            val dark = when {
                forceLight || !app.onboardingDone -> false
                app.themeMode == ThemeMode.SYSTEM -> isSystemInDarkTheme()
                app.themeMode == ThemeMode.LIGHT -> false
                else -> true
            }
            androidx.compose.runtime.DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
                onDispose { }
            }
            MyLibbyTheme(darkTheme = dark, dynamicColor = app.dynamicColor) {
                AppNavHost()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleOpenIntent(intent)
    }

    /** Imports a book file handed to us by another app and asks the UI to open it. */
    private fun handleOpenIntent(intent: android.content.Intent?) {
        // Single sign-on redirect from the browser.
        if (intent?.action == android.content.Intent.ACTION_VIEW && intent.data?.scheme == "grimmory") {
            val redirect = intent.data!!
            intent.action = null
            val container = (application as MyLibbyApp).container
            lifecycleScope.launch { container.auth.completeOidc(redirect) }
            return
        }
        val uri: android.net.Uri = when (intent?.action) {
            android.content.Intent.ACTION_VIEW -> intent.data
            android.content.Intent.ACTION_SEND -> androidx.core.content.IntentCompat.getParcelableExtra(intent, android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
            else -> null
        } ?: return
        intent?.action = null // don't import again on configuration changes
        val container = (application as MyLibbyApp).container
        lifecycleScope.launch {
            runCatching { container.localBooks.import(uri) }
                .onSuccess { OpenRequests.pending.value = it.id }
                .onFailure {
                    android.widget.Toast.makeText(this@MainActivity, getString(R.string.import_failed, it.message ?: "?"), android.widget.Toast.LENGTH_LONG).show()
                }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val h = VolumeKeys.handler
        if (h != null && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (h(keyCode == KeyEvent.KEYCODE_VOLUME_UP)) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (VolumeKeys.handler != null && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        return super.onKeyUp(keyCode, event)
    }
}
