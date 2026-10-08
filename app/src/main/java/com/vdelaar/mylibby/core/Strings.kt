package com.vdelaar.mylibby.core

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/** Localized strings for code outside composables (ViewModels, repositories, workers). */
object AppStrings {
    lateinit var context: Context
    val isReady: Boolean get() = ::context.isInitialized
}

fun str(@StringRes id: Int, vararg args: Any): String = AppStrings.context.getString(id, *args)

/** Like [str], but works without an Android context (unit tests) using the English [fallback]. */
fun strOr(@StringRes id: Int, fallback: String, vararg args: Any): String =
    if (AppStrings.isReady) AppStrings.context.getString(id, *args) else String.format(java.util.Locale.ROOT, fallback, *args)

fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    AppStrings.context.resources.getQuantityString(id, count, *args)
