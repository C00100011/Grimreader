package com.vdelaar.mylibby.core

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * App language: "system", "en" or "nl". Android 13+ uses per-app languages (also shown in the
 * system's app settings); Android 12 wraps the activity/app context with the chosen locale.
 */
object AppLanguage {
    const val SYSTEM = "system"
    val options = listOf(SYSTEM, "en", "nl")

    /** The language currently in effect for the app. */
    fun current(context: Context, stored: String): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            return if (locales.isEmpty) SYSTEM else locales[0].language
        }
        return stored
    }

    fun apply(activity: Activity?, app: Context, tag: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            app.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            AppStrings.context = app
        } else {
            AppStrings.context = wrap(app, tag)
            activity?.recreate()
        }
    }

    /** Pre-Android 13: a context whose resources use [tag]. */
    fun wrap(base: Context, tag: String): Context {
        if (Build.VERSION.SDK_INT >= 33 || tag == SYSTEM) return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag(tag)))
        return base.createConfigurationContext(config)
    }
}
