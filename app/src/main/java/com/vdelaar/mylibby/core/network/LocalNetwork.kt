package com.vdelaar.mylibby.core.network

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import okhttp3.HttpUrl
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Android 17 (API 37) protects the home network: an app that targets it may only reach devices on the local network
 * (192.168.x.x, 10.x.x.x, *.local, ...) after the user allowed "Nearby devices" (`ACCESS_LOCAL_NETWORK`). Without it
 * every connection to a home server fails, so the permission is asked right before the first request to such an
 * address, and that request waits for the answer.
 */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** The permission only exists from Android 17; earlier versions never restrict the local network. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 37

    /** The UI asks for the permission when this emits (see `LocalNetworkPrompt`). */
    val asks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** True while an activity is in front, so there is somebody to answer the dialog. */
    @Volatile var uiVisible = false

    @Volatile private var pending: CountDownLatch? = null
    @Volatile private var refused = false
    private val localHosts = ConcurrentHashMap<String, Pair<Long, Boolean>>()

    fun granted(context: Context): Boolean =
        !supported || ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Called by the UI with the user's answer. */
    fun answered(granted: Boolean) {
        refused = !granted
        pending?.countDown()
    }

    /** Runs on a network thread before a request: asks for the permission if the address needs it and waits for the answer. */
    fun beforeRequest(context: Context, url: HttpUrl) {
        if (!supported || granted(context)) return
        val waiting = pending
        if (waiting != null) { waiting.await(WAIT_SECONDS, TimeUnit.SECONDS); return }
        if (refused || !uiVisible || !isLocal(url.host)) return
        val latch = synchronized(this) { pending ?: CountDownLatch(1).also { pending = it } }
        asks.tryEmit(Unit)
        try { latch.await(WAIT_SECONDS, TimeUnit.SECONDS) } finally { synchronized(this) { if (pending === latch) pending = null } }
    }

    /** True when a request to [url] fails because the local network is not allowed. */
    fun blocks(context: Context, url: HttpUrl): Boolean = supported && !granted(context) && isLocal(url.host)

    /** For every client that talks to a server the user typed in: asks first, and explains a refusal. */
    val interceptor = okhttp3.Interceptor { chain ->
        val context = com.vdelaar.mylibby.core.AppStrings.context
        val url = chain.request().url
        beforeRequest(context, url)
        try {
            chain.proceed(chain.request())
        } catch (e: java.io.IOException) {
            if (blocks(context, url)) throw java.io.IOException(com.vdelaar.mylibby.core.str(com.vdelaar.mylibby.R.string.err_local_network), e)
            throw e
        }
    }

    fun isLocal(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h == "localhost") return false
        if (h.endsWith(".local")) return true
        val now = System.currentTimeMillis()
        localHosts[h]?.let { (at, local) -> if (now - at < CACHE_MS) return local }
        val local = try {
            InetAddress.getAllByName(h).any { it.isLocalNetwork() }
        } catch (e: Exception) { false }
        localHosts[h] = now to local
        return local
    }

    private fun InetAddress.isLocalNetwork(): Boolean = when {
        isLoopbackAddress || isAnyLocalAddress -> false
        isSiteLocalAddress || isLinkLocalAddress -> true
        this is Inet6Address -> (address[0].toInt() and 0xFE) == 0xFC
        else -> false
    }

    private const val WAIT_SECONDS = 120L
    private const val CACHE_MS = 5 * 60 * 1000L
}
