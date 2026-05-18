package com.manga.translate

import androidx.annotation.StringRes

enum class FolderReadingMode(
    val prefValue: String,
    @param:StringRes val labelRes: Int
) {
    STANDARD("standard", R.string.folder_reading_mode_standard),
    WEBTOON_SCROLL("webtoon_scroll", R.string.folder_reading_mode_webtoon_scroll);

    companion object {
        fun fromPref(value: String?): FolderReadingMode {
            return entries.firstOrNull { it.prefValue == value } ?: STANDARD
        }
    }
}
