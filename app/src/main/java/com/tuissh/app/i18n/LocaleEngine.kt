package com.tuissh.app.i18n

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Forces the app language independently of the device language.
 *
 * Why this exists
 * ---------------
 * The app ships Vietnamese as its primary language. Android resolves resources by
 * locale specificity, so an English device would pick `values-en/` even though
 * `values/` is Vietnamese. There is no manifest flag that says "ignore the device
 * language"; the only correct way is to apply the chosen locale to the context.
 *
 * Strategy:
 *   - the user's stored preference wins (see [NeoLocale])
 *   - otherwise the app defaults to Vietnamese, not to the system language
 *
 * On API 33+ this uses `LocaleManager`, which is the platform-supported path and
 * survives process death. Below 33 it wraps the base context, which is applied in
 * `NeoActivity.attachBaseContext`.
 */
object LocaleEngine {

    /** Language the app ships as its default. */
    const val DEFAULT_LANGUAGE = "vi"

    fun resolveTag(preference: String?, systemTag: String): String =
        NeoLocale.resolve(preference ?: DEFAULT_LANGUAGE, systemTag)

    /** Build a localised configuration for `attachBaseContext` (API < 33 path). */
    fun wrap(base: Context, tag: String): Context {
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    /**
     * Apply the language for the current activity.
     *
     * API 33+ delegates to the platform so the choice is persisted per app
     * (`cmd locale get-app-locales` reflects it); older versions rely on
     * [wrap] having run before `onCreate`.
     */
    fun apply(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val manager = activity.getSystemService(android.app.LocaleManager::class.java)
                manager?.applicationLocales = LocaleList.forLanguageTags(tag)
            }
        }
    }

    /** True when the device language is already one the app ships. */
    fun deviceIsSupported(systemTag: String): Boolean =
        NeoLocale.normalise(systemTag).let { tag ->
            NeoLocale.SUPPORTED.any { it.equals(tag, ignoreCase = true) }
        }
}
