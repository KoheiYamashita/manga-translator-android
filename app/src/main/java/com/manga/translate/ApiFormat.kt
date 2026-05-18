package com.manga.translate

import androidx.annotation.StringRes

enum class ApiFormat(
    val prefValue: String,
    @param:StringRes val labelRes: Int
) {
    OPENAI_COMPATIBLE("openai_compatible", R.string.api_format_openai_compatible),
    GEMINI("gemini", R.string.api_format_gemini);

    companion object {
        fun fromPref(value: String?): ApiFormat {
            return entries.firstOrNull { it.prefValue == value } ?: OPENAI_COMPATIBLE
        }
    }
}
