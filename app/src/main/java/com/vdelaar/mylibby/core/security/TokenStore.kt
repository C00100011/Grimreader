package com.vdelaar.mylibby.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps Grimmory JWTs and the Shelfmark password encrypted with the Android Keystore. */
@Suppress("DEPRECATION")
class TokenStore(context: Context) : com.vdelaar.mylibby.core.network.HardcoverKeyStore {

    private val prefs: SharedPreferences = try {
        create(context)
    } catch (_: Exception) {
        // Keystore corruption (e.g. after a backup restore): start clean.
        context.deleteSharedPreferences(FILE)
        create(context)
    }

    private val _loggedIn = MutableStateFlow(accessToken != null)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    val accessToken: String? get() = prefs.getString(KEY_ACCESS, null)
    val refreshToken: String? get() = prefs.getString(KEY_REFRESH, null)

    fun saveTokens(access: String, refresh: String) {
        prefs.edit { putString(KEY_ACCESS, access).putString(KEY_REFRESH, refresh) }
        _loggedIn.value = true
    }

    fun clearTokens() {
        prefs.edit { remove(KEY_ACCESS).remove(KEY_REFRESH) }
        _loggedIn.value = false
    }

    var shelfmarkUsername: String?
        get() = prefs.getString(KEY_SM_USER, null)
        set(value) = prefs.edit { putString(KEY_SM_USER, value) }

    var shelfmarkPassword: String?
        get() = prefs.getString(KEY_SM_PASS, null)
        set(value) = prefs.edit { putString(KEY_SM_PASS, value) }

    /** Shelfmark session cookies (JSON), e.g. from an OIDC login. */
    var shelfmarkCookies: String?
        get() = prefs.getString(KEY_SM_COOKIES, null)
        set(value) = prefs.edit { putString(KEY_SM_COOKIES, value) }

    /** Optional Shelfmark API key (sent as X-Api-Key). */
    var shelfmarkApiKey: String?
        get() = prefs.getString(KEY_SM_API_KEY, null)
        set(value) = prefs.edit { putString(KEY_SM_API_KEY, value) }

    /** Password of the OPDS catalog (the address and user name live in the app settings). */
    var opdsPassword: String?
        get() = prefs.getString(KEY_OPDS_PASS, null)
        set(value) = prefs.edit { if (value.isNullOrEmpty()) remove(KEY_OPDS_PASS) else putString(KEY_OPDS_PASS, value) }

    private val _hardcover = MutableStateFlow(prefs.getString(KEY_HARDCOVER, null) != null)

    /** True while a Hardcover API key is stored. */
    val hardcoverConnected: StateFlow<Boolean> = _hardcover.asStateFlow()

    /** The user's own Hardcover API key (only ever sent to Hardcover). */
    override var hardcoverKey: String?
        get() = prefs.getString(KEY_HARDCOVER, null)
        set(value) {
            prefs.edit { if (value.isNullOrBlank()) remove(KEY_HARDCOVER) else putString(KEY_HARDCOVER, value) }
            _hardcover.value = !value.isNullOrBlank()
        }

    /** An SSO sign-in that has been sent to the browser and is waiting for its redirect (JSON). */
    var oidcPending: String?
        get() = prefs.getString(KEY_OIDC_PENDING, null)
        set(value) = prefs.edit { if (value == null) remove(KEY_OIDC_PENDING) else putString(KEY_OIDC_PENDING, value) }

    private fun create(context: Context): SharedPreferences {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            context,
            FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private companion object {
        const val FILE = "secure_tokens"
        const val KEY_ACCESS = "access"
        const val KEY_REFRESH = "refresh"
        const val KEY_SM_USER = "sm_user"
        const val KEY_SM_PASS = "sm_pass"
        const val KEY_SM_COOKIES = "sm_cookies"
        const val KEY_HARDCOVER = "hardcover_key"
        const val KEY_OPDS_PASS = "opds_pass"
        const val KEY_SM_API_KEY = "sm_api_key"
        const val KEY_OIDC_PENDING = "oidc_pending"
    }
}
