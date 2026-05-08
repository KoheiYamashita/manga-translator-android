package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF

class OcrEngineRegistry(
    context: Context,
    private val settingsStore: SettingsStore = SettingsStore(context.applicationContext)
) {
    private val appContext = context.applicationContext
    private var mangaOcr: MangaOcr? = null
    private var mangaOcrMobile: MangaOcrMobile? = null
    private var mangaOcrMobileInitFailed = false
    private var englishOcr: EnglishOcr? = null
    private var koreanOcr: KoreanOcr? = null
    private var englishLineDetector: EnglishLineDetector? = null

    fun getMangaOcr(logTag: String): MangaOcr? {
        if (mangaOcr != null) return mangaOcr
        return try {
            MangaOcr(appContext, settingsStore = settingsStore).also { mangaOcr = it }
        } catch (e: Exception) {
            AppLogger.log(logTag, "Failed to init OCR", e)
            null
        }
    }

    fun getMangaOcrMobile(logTag: String): MangaOcrMobile? {
        if (mangaOcrMobile != null) return mangaOcrMobile
        if (mangaOcrMobileInitFailed) return null
        return try {
            MangaOcrMobile(appContext, settingsStore = settingsStore).also {
                mangaOcrMobile = it
            }
        } catch (e: Exception) {
            mangaOcrMobileInitFailed = true
            AppLogger.log(logTag, "Failed to init mobile OCR", e)
            null
        }
    }

    fun getEnglishOcr(logTag: String): EnglishOcr? {
        if (englishOcr != null) return englishOcr
        return try {
            EnglishOcr(appContext, settingsStore = settingsStore).also { englishOcr = it }
        } catch (e: Exception) {
            AppLogger.log(logTag, "Failed to init English OCR", e)
            null
        }
    }

    fun getKoreanOcr(logTag: String): KoreanOcr? {
        if (koreanOcr != null) return koreanOcr
        return try {
            KoreanOcr(appContext, settingsStore = settingsStore).also { koreanOcr = it }
        } catch (e: Exception) {
            AppLogger.log(logTag, "Failed to init Korean OCR", e)
            null
        }
    }

    fun getEnglishLineDetector(logTag: String): EnglishLineDetector? {
        if (englishLineDetector != null) return englishLineDetector
        return try {
            EnglishLineDetector(appContext, settingsStore = settingsStore).also {
                englishLineDetector = it
            }
        } catch (e: Exception) {
            AppLogger.log(logTag, "Failed to init English line detector", e)
            null
        }
    }
}

class BubbleTextRecognizer(
    private val llmClient: LlmClient,
    private val engineRegistry: OcrEngineRegistry,
    private val settingsStore: SettingsStore
) {
    fun getLocalOcrEngine(
        language: TranslationLanguage,
        logTag: String
    ): OcrEngine? {
        return when (language) {
            TranslationLanguage.JA_TO_ZH -> when (
                settingsStore.loadOcrApiSettings().japaneseLocalOcrEngine
            ) {
                JapaneseLocalOcrEngine.MANGA_OCR_MOBILE -> engineRegistry.getMangaOcrMobile(logTag)
            }
            TranslationLanguage.EN_TO_ZH -> engineRegistry.getEnglishOcr(logTag)
            TranslationLanguage.KO_TO_ZH -> engineRegistry.getKoreanOcr(logTag)
        }
    }

    fun detectRecognizedLines(
        source: Bitmap,
        language: TranslationLanguage,
        logTag: String
    ): List<EnglishLine> {
        val lineDetector = engineRegistry.getEnglishLineDetector(logTag) ?: return emptyList()
        val lineRects = lineDetector.detectLines(source)
        return when (language) {
            TranslationLanguage.EN_TO_ZH -> {
                val engine = engineRegistry.getEnglishOcr(logTag) ?: return emptyList()
                recognizeEnglishLines(source, lineRects, engine)
            }

            TranslationLanguage.KO_TO_ZH -> {
                val engine = engineRegistry.getKoreanOcr(logTag) ?: return emptyList()
                recognizeKoreanLines(source, lineRects, engine)
            }

            TranslationLanguage.JA_TO_ZH -> emptyList()
        }
    }

    suspend fun recognizeRegion(
        source: Bitmap,
        rect: RectF,
        language: TranslationLanguage,
        useLocalOcr: Boolean,
        logTag: String
    ): String {
        val crop = cropBitmap(source, rect) ?: return ""
        return try {
            recognizeCrop(crop, language, useLocalOcr, logTag)
        } finally {
            crop.recycleSafely()
        }
    }

    suspend fun recognizeCrop(
        crop: Bitmap,
        language: TranslationLanguage,
        useLocalOcr: Boolean,
        logTag: String
    ): String {
        if (!useLocalOcr) {
            return try {
                llmClient.recognizeImageText(crop)?.trim().orEmpty()
            } catch (e: Exception) {
                AppLogger.log(logTag, "API OCR failed", e)
                ""
            }
        }
        return when (language) {
            TranslationLanguage.JA_TO_ZH -> {
                when (settingsStore.loadOcrApiSettings().japaneseLocalOcrEngine) {
                    JapaneseLocalOcrEngine.MANGA_OCR_MOBILE -> {
                        val engine = engineRegistry.getMangaOcrMobile(logTag) ?: return ""
                        engine.recognize(crop).trim()
                    }
                }
            }

            TranslationLanguage.EN_TO_ZH -> {
                val engine = engineRegistry.getEnglishOcr(logTag) ?: return ""
                val lineDetector = engineRegistry.getEnglishLineDetector(logTag)
                val lineRects = lineDetector?.detectLines(crop).orEmpty()
                val lines = recognizeEnglishLines(crop, lineRects, engine)
                if (lines.isEmpty()) {
                    engine.recognize(crop).trim()
                } else {
                    lines.joinToString("\n") { it.text }
                }
            }

            TranslationLanguage.KO_TO_ZH -> {
                val engine = engineRegistry.getKoreanOcr(logTag) ?: return ""
                val lineDetector = engineRegistry.getEnglishLineDetector(logTag)
                val lineRects = lineDetector?.detectLines(crop).orEmpty()
                val lines = recognizeKoreanLines(crop, lineRects, engine)
                if (lines.isEmpty()) {
                    engine.recognize(crop).trim()
                } else {
                    lines.joinToString("\n") { it.text }
                }
            }
        }
    }
}

const val DEFAULT_EN_MIN_LINE_SCORE = 0.5f

data class EnglishLine(
    val rect: RectF,
    val text: String
)

fun normalizeOcrText(text: String, language: TranslationLanguage): String {
    if (language != TranslationLanguage.EN_TO_ZH && language != TranslationLanguage.KO_TO_ZH) return text
    return text.replace('\r', ' ')
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
}

fun cropBitmap(source: Bitmap, rect: RectF): Bitmap? {
    val left = rect.left.toInt().coerceIn(0, source.width - 1)
    val top = rect.top.toInt().coerceIn(0, source.height - 1)
    val right = rect.right.toInt().coerceIn(1, source.width)
    val bottom = rect.bottom.toInt().coerceIn(1, source.height)
    val width = right - left
    val height = bottom - top
    if (width <= 0 || height <= 0) return null
    return Bitmap.createBitmap(source, left, top, width, height)
}

fun Bitmap?.recycleSafely() {
    if (this != null && !isRecycled) {
        recycle()
    }
}

inline fun <T> withBitmapCrop(
    source: Bitmap,
    rect: RectF,
    block: (Bitmap) -> T
): T? {
    val crop = cropBitmap(source, rect) ?: return null
    return try {
        block(crop)
    } finally {
        crop.recycleSafely()
    }
}

fun recognizeEnglishLines(
    source: Bitmap,
    lineRects: List<RectF>,
    ocrEngine: EnglishOcr,
    minLineScore: Float = DEFAULT_EN_MIN_LINE_SCORE
): List<EnglishLine> {
    if (lineRects.isEmpty()) return emptyList()
    val results = ArrayList<EnglishLine>(lineRects.size)
    for (rect in lineRects) {
        withBitmapCrop(source, rect) { crop ->
            val decoded = ocrEngine.recognizeWithScore(crop)
            val text = decoded.text.trim()
            if (decoded.score >= minLineScore && text.isNotBlank()) {
                results.add(EnglishLine(rect, text))
            }
        }
    }
    return results
}

fun recognizeKoreanLines(
    source: Bitmap,
    lineRects: List<RectF>,
    ocrEngine: KoreanOcr,
    minLineScore: Float = DEFAULT_EN_MIN_LINE_SCORE
): List<EnglishLine> {
    if (lineRects.isEmpty()) return emptyList()
    val results = ArrayList<EnglishLine>(lineRects.size)
    for (rect in lineRects) {
        withBitmapCrop(source, rect) { crop ->
            val decoded = ocrEngine.recognizeWithScore(crop)
            val text = decoded.text.trim()
            if (decoded.score >= minLineScore && text.isNotBlank()) {
                results.add(EnglishLine(rect, text))
            }
        }
    }
    return results
}
