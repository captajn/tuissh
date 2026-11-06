package com.tuissh.app.i18n

import java.util.Locale

/**
 * UI language handling.
 *
 * The original app ships 12 localisations via `.lproj` bundles and follows the
 * system language. Android does the same through `values-<lang>/strings.xml`, but
 * two things differ and are handled here:
 *
 *  1. **Priority order.** Vietnamese is the first non-English language in the
 *     supported list, so a device set to `vi-VN` resolves to Vietnamese even when
 *     English resources are also present (Android's default resource fallback can
 *     otherwise pick `values/` for partially translated keys).
 *  2. **In-app override.** `AppSetting` allowed choosing a language independently
 *     of the system. [resolve] exposes that mapping without pulling in AppCompat.
 */
object NeoLocale {

    /** Languages with a `.lproj` bundle in the original app, in priority order. */
    val SUPPORTED = listOf(
        "vi",      // Tiếng Việt — first-class, ships complete
        "en",
        "zh-Hans",
        "zh-Hant",
        "ja",
        "ko",
        "ru",
        "de",
        "fr",
        "es",
        "pt-BR",
        "tr",
    )

    /**
     * Human-readable native names, matching what the language picker shows.
     * Not translated on purpose: a language is always listed in itself.
     */
    val NATIVE_NAMES = mapOf(
        "vi" to "Tiếng Việt",
        "en" to "English",
        "zh-Hans" to "简体中文",
        "zh-Hant" to "繁體中文",
        "ja" to "日本語",
        "ko" to "한국어",
        "ru" to "Русский",
        "de" to "Deutsch",
        "fr" to "Français",
        "es" to "Español",
        "pt-BR" to "Português (Brasil)",
        "tr" to "Türkçe",
    )

    /** Sentinel meaning "follow the system language". */
    const val FOLLOW_SYSTEM = "system"

    /**
     * Resolve the effective language tag.
     *
     * @param preference a value from [SUPPORTED] or [FOLLOW_SYSTEM]
     * @param systemTag the current system locale tag, e.g. `vi-VN`
     */
    fun resolve(preference: String, systemTag: String = Locale.getDefault().toLanguageTag()): String {
        if (preference != FOLLOW_SYSTEM && preference in SUPPORTED) return preference

        val normalised = normalise(systemTag)
        // Exact match first, then base language.
        SUPPORTED.firstOrNull { it.equals(normalised, ignoreCase = true) }?.let { return it }
        val base = normalised.substringBefore('-')
        SUPPORTED.firstOrNull { it.equals(base, ignoreCase = true) }?.let { return it }
        SUPPORTED.firstOrNull { it.substringBefore('-').equals(base, ignoreCase = true) }?.let { return it }
        return "en"
    }

    /** Normalise a BCP-47 tag into the casing used by [SUPPORTED]. */
    fun normalise(tag: String): String {
        val cleaned = tag.replace('_', '-')
        val parts = cleaned.split('-')
        if (parts.isEmpty()) return "en"
        val lang = parts[0].lowercase(Locale.US)
        if (parts.size == 1) return lang
        val region = parts[1].uppercase(Locale.US)
        // Script subtags are four letters; regions are two letters or three digits.
        return if (region.length == 4) {
            "$lang-${region[0].uppercase()}${region.substring(1).lowercase()}"
        } else {
            "$lang-$region"
        }
    }

    /** Convert a supported tag into an Android resource qualifier (`pt-BR` → `pt-rBR`). */
    fun resourceQualifier(tag: String): String {
        val parts = tag.split('-')
        return when (parts.size) {
            1 -> parts[0]
            2 -> if (parts[1].length == 4) {
                "${parts[0]}-r${parts[1].uppercase(Locale.US)}"
            } else {
                "${parts[0]}-r${parts[1].uppercase(Locale.US)}"
            }
            else -> parts[0]
        }
    }

    fun displayName(tag: String): String = NATIVE_NAMES[tag] ?: tag
}
