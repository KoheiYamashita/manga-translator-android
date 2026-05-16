package com.manga.translate

import android.graphics.RectF

enum class BubbleSource(val jsonValue: String) {
    BUBBLE_DETECTOR("bubble_detector"),
    TEXT_DETECTOR("text_detector"),
    MANUAL("manual"),
    UNKNOWN("unknown");

    companion object {
        fun fromJson(value: String?): BubbleSource {
            return entries.firstOrNull { it.jsonValue.equals(value, ignoreCase = true) } ?: UNKNOWN
        }
    }
}

enum class PageTranslationStatus(val jsonValue: String) {
    UNKNOWN(""),
    SUCCESS("success"),
    PARTIAL("partial"),
    SKIPPED("skipped"),
    FAILED("failed");

    companion object {
        fun fromJson(value: String?): PageTranslationStatus {
            if (value.isNullOrBlank()) return UNKNOWN
            return entries.firstOrNull { it.jsonValue.equals(value, ignoreCase = true) } ?: UNKNOWN
        }
    }
}

data class TranslationMetadata(
    val sourceLastModified: Long = 0L,
    val sourceFileSize: Long = 0L,
    val mode: String = "",
    val language: String = "",
    val promptAsset: String = "",
    val modelName: String = "",
    val providerId: String = "",
    val apiFormat: String = "",
    val ocrCacheMode: String = "",
    val version: Int = CURRENT_VERSION,
    val status: PageTranslationStatus = PageTranslationStatus.UNKNOWN
) {
    fun isEmpty(): Boolean {
        return sourceLastModified <= 0L &&
            sourceFileSize <= 0L &&
            mode.isBlank() &&
            language.isBlank() &&
            promptAsset.isBlank() &&
            modelName.isBlank() &&
            providerId.isBlank() &&
            apiFormat.isBlank() &&
            ocrCacheMode.isBlank()
    }

    fun isManual(): Boolean {
        return mode == MODE_MANUAL
    }

    fun matchesSource(imageFile: java.io.File): Boolean {
        return sourceLastModified == imageFile.lastModified() &&
            sourceFileSize == imageFile.length()
    }

    fun withSourceFingerprint(imageFile: java.io.File): TranslationMetadata {
        return copy(
            sourceLastModified = imageFile.lastModified(),
            sourceFileSize = imageFile.length()
        )
    }

    companion object {
        const val CURRENT_VERSION = 2
        const val MODE_STANDARD = "standard"
        const val MODE_FULL_PAGE = "full_page"
        const val MODE_VL_DIRECT = "vl_direct"
        const val MODE_MANUAL = "manual"
    }
}

data class OcrMetadata(
    val sourceLastModified: Long = 0L,
    val sourceFileSize: Long = 0L,
    val cacheMode: String = "",
    val language: String = "",
    val engineModel: String = "",
    val version: Int = CURRENT_VERSION
) {
    fun matchesSource(imageFile: java.io.File): Boolean {
        return sourceLastModified == imageFile.lastModified() &&
            sourceFileSize == imageFile.length()
    }

    fun matches(expected: OcrMetadata): Boolean {
        return version == expected.version &&
            cacheMode == expected.cacheMode &&
            language == expected.language &&
            engineModel == expected.engineModel
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

enum class BubbleTranslationState(val jsonValue: String) {
    PENDING("pending"),
    TRANSLATED("translated");

    companion object {
        fun fromJson(value: String?): BubbleTranslationState {
            return entries.firstOrNull { it.jsonValue.equals(value, ignoreCase = true) } ?: PENDING
        }
    }
}

data class BubbleTranslation(
    val id: Int,
    val rect: RectF,
    val originalText: String = "",
    val translatedText: String = "",
    val translationState: BubbleTranslationState = if (translatedText.isNotBlank()) {
        BubbleTranslationState.TRANSLATED
    } else {
        BubbleTranslationState.PENDING
    },
    val source: BubbleSource = BubbleSource.UNKNOWN,
    val maskContour: FloatArray? = null
) {
    val text: String
        get() = when {
            translationState == BubbleTranslationState.TRANSLATED && translatedText.isNotBlank() -> translatedText
            originalText.isNotBlank() -> originalText
            else -> translatedText
        }

    val sourceText: String
        get() = originalText.ifBlank {
            if (translationState == BubbleTranslationState.PENDING) {
                translatedText
            } else {
                ""
            }
        }

    fun hasDisplayText(): Boolean = text.isNotBlank()

    fun needsTranslationRetry(): Boolean = translationState == BubbleTranslationState.PENDING

    fun withTranslationResult(value: String): BubbleTranslation {
        val normalized = value.trim()
        return if (normalized.isNotBlank()) {
            copy(
                translatedText = normalized,
                translationState = BubbleTranslationState.TRANSLATED
            )
        } else {
            copy(
                translatedText = "",
                translationState = BubbleTranslationState.PENDING
            )
        }
    }

    fun withRecognizedOriginalText(value: String): BubbleTranslation {
        return copy(
            originalText = value.trim(),
            translatedText = "",
            translationState = BubbleTranslationState.PENDING
        )
    }

    fun withManualText(value: String): BubbleTranslation {
        return if (value.isBlank()) {
            copy(
                originalText = "",
                translatedText = "",
                translationState = BubbleTranslationState.PENDING
            )
        } else {
            copy(
                translatedText = value,
                translationState = BubbleTranslationState.TRANSLATED
            )
        }
    }

    fun withContentFrom(other: BubbleTranslation): BubbleTranslation {
        return copy(
            originalText = other.originalText,
            translatedText = other.translatedText,
            translationState = other.translationState
        )
    }

    companion object {
        fun pending(
            id: Int,
            rect: RectF,
            originalText: String = "",
            source: BubbleSource = BubbleSource.UNKNOWN,
            maskContour: FloatArray? = null
        ): BubbleTranslation {
            return BubbleTranslation(
                id = id,
                rect = rect,
                originalText = originalText,
                translatedText = "",
                translationState = BubbleTranslationState.PENDING,
                source = source,
                maskContour = maskContour
            )
        }

        fun translated(
            id: Int,
            rect: RectF,
            translatedText: String,
            source: BubbleSource = BubbleSource.UNKNOWN,
            maskContour: FloatArray? = null,
            originalText: String = ""
        ): BubbleTranslation {
            return BubbleTranslation(
                id = id,
                rect = rect,
                originalText = originalText,
                translatedText = translatedText,
                translationState = if (translatedText.isNotBlank()) {
                    BubbleTranslationState.TRANSLATED
                } else {
                    BubbleTranslationState.PENDING
                },
                source = source,
                maskContour = maskContour
            )
        }
    }
}

data class TranslationResult(
    val imageName: String,
    val width: Int,
    val height: Int,
    val bubbles: List<BubbleTranslation>,
    val metadata: TranslationMetadata = TranslationMetadata()
)

fun TranslationResult.deriveStatus(): PageTranslationStatus {
    if (bubbles.isEmpty()) return PageTranslationStatus.SUCCESS
    val translated = bubbles.count {
        it.translationState == BubbleTranslationState.TRANSLATED && it.translatedText.isNotBlank()
    }
    return if (translated == bubbles.size) {
        PageTranslationStatus.SUCCESS
    } else {
        PageTranslationStatus.PARTIAL
    }
}
