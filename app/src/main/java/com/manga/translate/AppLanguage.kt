package com.manga.translate

import androidx.annotation.StringRes
import androidx.core.os.LocaleListCompat

enum class AppLanguage(
    val prefValue: String,
    @param:StringRes val labelRes: Int,
    private val languageTags: String?
) {
    FOLLOW_SYSTEM("follow_system", R.string.language_follow_system, null),
    SIMPLIFIED_CHINESE("zh_hans", R.string.language_simplified_chinese, "zh-Hans"),
    TRADITIONAL_CHINESE("zh_hant", R.string.language_traditional_chinese, "zh-Hant");

    fun toLocales(): LocaleListCompat {
        return if (languageTags.isNullOrBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(languageTags)
        }
    }

    companion object {
        fun fromPref(value: String?): AppLanguage {
            return entries.firstOrNull { it.prefValue == value } ?: FOLLOW_SYSTEM
        }
    }
}
