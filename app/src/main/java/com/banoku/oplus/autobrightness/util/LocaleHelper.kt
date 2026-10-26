package com.banoku.oplus.autobrightness.util

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object LocaleHelper {

    fun setAppLocale(context: Context, languageTag: String) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().putString("app_language", languageTag).apply()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val localeManager = context.getSystemService(LocaleManager::class.java)
                if (languageTag.isEmpty() || languageTag == "system") {
                    localeManager?.applicationLocales = LocaleList.getEmptyLocaleList()
                } else {
                    localeManager?.applicationLocales = LocaleList.forLanguageTags(languageTag)
                }
            } else {
                val locale = if (languageTag.isEmpty() || languageTag == "system") {
                    Locale.getDefault()
                } else {
                    Locale.forLanguageTag(languageTag)
                }
                Locale.setDefault(locale)
                val config = Configuration(context.resources.configuration)
                config.setLocale(locale)
                context.resources.updateConfiguration(config, context.resources.displayMetrics)
            }
        } catch (ignored: Throwable) {
        }
    }

    fun getSelectedLanguage(context: Context): String {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return prefs.getString("app_language", "system") ?: "system"
    }
}
