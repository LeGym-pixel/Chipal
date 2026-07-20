package com.svpn.app.util

import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Switches app language at runtime for ru / kk / en using the
 * AndroidX per-app language API (works on API 24+ via AppCompat backport).
 */
object LocaleHelper {

    val SUPPORTED = listOf("ru", "kk", "en", "ky", "tk")

    fun apply(code: String) {
        val locales = LocaleListCompat.forLanguageTags(code)
        AppCompatDelegate.setApplicationLocales(locales)
    }

    fun current(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        return if (!locales.isEmpty) locales[0]?.language ?: "ru" else "ru"
    }
}
