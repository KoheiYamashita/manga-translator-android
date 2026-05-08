package com.manga.translate

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

const val PRIMARY_PROVIDER_ID = "primary"
const val OCR_PROVIDER_ID = "ocr"

data class ApiSettings(
    val apiUrl: String,
    val apiKey: String,
    val modelName: String,
    val apiFormat: ApiFormat = ApiFormat.OPENAI_COMPATIBLE,
    val providerId: String = PRIMARY_PROVIDER_ID
) {
    fun isValid(): Boolean {
        return apiUrl.isNotBlank() && apiKey.isNotBlank() && modelName.isNotBlank()
    }
}

data class OcrApiSettings(
    val useLocalOcr: Boolean,
    val japaneseLocalOcrEngine: JapaneseLocalOcrEngine,
    val apiUrl: String,
    val apiKey: String,
    val modelName: String,
    val timeoutSeconds: Int
) {
    fun isValid(): Boolean {
        return useLocalOcr || (apiUrl.isNotBlank() && apiKey.isNotBlank() && modelName.isNotBlank())
    }
}

enum class JapaneseLocalOcrEngine(
    val prefValue: String
) {
    MANGA_OCR_MOBILE("manga_ocr_mobile");

    companion object {
        fun fromPref(value: String?): JapaneseLocalOcrEngine {
            return entries.firstOrNull { it.prefValue == value } ?: MANGA_OCR_MOBILE
        }
    }
}

data class FloatingTranslateApiSettings(
    val apiUrl: String,
    val apiKey: String,
    val modelName: String,
    val language: TranslationLanguage,
    val timeoutSeconds: Int,
    val useVlDirectTranslate: Boolean,
    val vlTranslateConcurrency: Int,
    val proofreadingModeEnabled: Boolean,
    val autoCloseOnScreenChangeEnabled: Boolean,
    val singleTapAction: FloatingBallGestureAction,
    val doubleTapAction: FloatingBallGestureAction,
    val longPressAction: FloatingBallGestureAction,
    val tripleTapAction: FloatingBallGestureAction
)

data class NormalBubbleRenderSettings(
    val shrinkPercent: Int,
    val opacityPercent: Int,
    val freeBubbleShrinkPercent: Int,
    val freeBubbleOpacityPercent: Int,
    val minAreaPerCharSp: Float,
    val useHorizontalText: Boolean
)

enum class FloatingBubbleShape(val prefValue: String, val labelRes: Int) {
    RECTANGLE("rectangle", R.string.floating_bubble_shape_rectangle),
    INSCRIBED_ELLIPSE("inscribed_ellipse", R.string.floating_bubble_shape_inscribed_ellipse);

    companion object {
        fun fromPref(value: String?): FloatingBubbleShape {
            return entries.firstOrNull { it.prefValue == value } ?: RECTANGLE
        }
    }
}

data class FloatingBubbleRenderSettings(
    val sizeAdjustPercent: Int,
    val opacityPercent: Int,
    val shape: FloatingBubbleShape,
    val useHorizontalText: Boolean,
    val minAreaPerCharSp: Float
)

data class CustomRequestParameter(
    val key: String,
    val value: String,
    val enabled: Boolean = true,
    val targetProviderId: String = PRIMARY_PROVIDER_ID
)

data class AiProviderProfile(
    val name: String,
    val mainSettings: ApiSettings,
    val apiTimeoutSeconds: Int,
    val ocrSettings: OcrApiSettings,
    val floatingTranslateSettings: FloatingTranslateApiSettings,
    val llmParameters: LlmParameterSettings,
    val customRequestParameters: List<CustomRequestParameter>,
    val additionalTranslationProviders: List<AdditionalTranslationProvider>
)

data class AiProviderProfilesState(
    val activeProfileName: String?,
    val profiles: List<AiProviderProfile>
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val aiProviderProfilesFile = File(context.filesDir, AI_PROVIDER_PROFILES_FILE_NAME)

    fun load(): ApiSettings {
        val url = prefs.getString(KEY_API_URL, DEFAULT_API_URL) ?: DEFAULT_API_URL
        val key = prefs.getString(KEY_API_KEY, "") ?: ""
        val model = prefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL) ?: DEFAULT_MODEL
        val apiFormat = ApiFormat.fromPref(prefs.getString(KEY_API_FORMAT, null))
        return ApiSettings(url, key, model, apiFormat, PRIMARY_PROVIDER_ID)
    }

    fun save(settings: ApiSettings) {
        prefs.edit() {
                putString(KEY_API_URL, settings.apiUrl)
                .putString(KEY_API_KEY, settings.apiKey)
                .putString(KEY_MODEL_NAME, settings.modelName)
                .putString(KEY_API_FORMAT, settings.apiFormat.prefValue)
            }
    }

    fun loadFloatingTranslateApiSettings(): FloatingTranslateApiSettings {
        return FloatingTranslateApiSettings(
            apiUrl = prefs.getString(KEY_FLOATING_API_URL, "") ?: "",
            apiKey = prefs.getString(KEY_FLOATING_API_KEY, "") ?: "",
            modelName = prefs.getString(KEY_FLOATING_MODEL_NAME, "") ?: "",
            language = TranslationLanguage.fromString(
                prefs.getString(KEY_FLOATING_LANGUAGE, TranslationLanguage.JA_TO_ZH.name)
            ),
            timeoutSeconds = prefs.getInt(
                KEY_FLOATING_TIMEOUT_SECONDS,
                DEFAULT_FLOATING_API_TIMEOUT_SECONDS
            ).coerceIn(
                MIN_FLOATING_API_TIMEOUT_SECONDS,
                MAX_FLOATING_API_TIMEOUT_SECONDS
            ),
            useVlDirectTranslate = prefs.getBoolean(KEY_FLOATING_USE_VL_DIRECT_TRANSLATE, false),
            vlTranslateConcurrency = prefs.getInt(
                KEY_FLOATING_VL_TRANSLATE_CONCURRENCY,
                DEFAULT_FLOATING_VL_TRANSLATE_CONCURRENCY
            ).coerceIn(
                MIN_FLOATING_VL_TRANSLATE_CONCURRENCY,
                MAX_FLOATING_VL_TRANSLATE_CONCURRENCY
            ),
            proofreadingModeEnabled = prefs.getBoolean(KEY_FLOATING_PROOFREADING_MODE_ENABLED, false),
            autoCloseOnScreenChangeEnabled = prefs.getBoolean(
                KEY_FLOATING_AUTO_CLOSE_ON_SCREEN_CHANGE_ENABLED,
                false
            ),
            singleTapAction = FloatingBallGestureAction.fromPref(
                prefs.getString(KEY_FLOATING_SINGLE_TAP_ACTION, null),
                DEFAULT_FLOATING_SINGLE_TAP_ACTION
            ),
            doubleTapAction = FloatingBallGestureAction.fromPref(
                prefs.getString(KEY_FLOATING_DOUBLE_TAP_ACTION, null),
                DEFAULT_FLOATING_DOUBLE_TAP_ACTION
            ),
            longPressAction = FloatingBallGestureAction.fromPref(
                prefs.getString(KEY_FLOATING_LONG_PRESS_ACTION, null),
                DEFAULT_FLOATING_LONG_PRESS_ACTION
            ),
            tripleTapAction = FloatingBallGestureAction.fromPref(
                prefs.getString(KEY_FLOATING_TRIPLE_TAP_ACTION, null),
                DEFAULT_FLOATING_TRIPLE_TAP_ACTION
            )
        )
    }

    fun loadResolvedFloatingTranslateApiSettings(): ApiSettings {
        val floating = loadFloatingTranslateApiSettings()
        val main = load()
        return ApiSettings(
            apiUrl = floating.apiUrl.ifBlank { main.apiUrl },
            apiKey = floating.apiKey.ifBlank { main.apiKey },
            modelName = floating.modelName.ifBlank { main.modelName },
            apiFormat = main.apiFormat,
            providerId = PRIMARY_PROVIDER_ID
        )
    }

    fun saveFloatingTranslateApiSettings(settings: FloatingTranslateApiSettings) {
        val normalizedConcurrency = settings.vlTranslateConcurrency.coerceIn(
            MIN_FLOATING_VL_TRANSLATE_CONCURRENCY,
            MAX_FLOATING_VL_TRANSLATE_CONCURRENCY
        )
        val normalizedTimeout = settings.timeoutSeconds.coerceIn(
            MIN_FLOATING_API_TIMEOUT_SECONDS,
            MAX_FLOATING_API_TIMEOUT_SECONDS
        )
        prefs.edit() {
                putString(KEY_FLOATING_API_URL, settings.apiUrl)
                .putString(KEY_FLOATING_API_KEY, settings.apiKey)
                .putString(KEY_FLOATING_MODEL_NAME, settings.modelName)
                .putString(KEY_FLOATING_LANGUAGE, settings.language.name)
                .putInt(KEY_FLOATING_TIMEOUT_SECONDS, normalizedTimeout)
                .putBoolean(KEY_FLOATING_USE_VL_DIRECT_TRANSLATE, settings.useVlDirectTranslate)
                .putInt(KEY_FLOATING_VL_TRANSLATE_CONCURRENCY, normalizedConcurrency)
                .putBoolean(
                    KEY_FLOATING_PROOFREADING_MODE_ENABLED,
                    settings.proofreadingModeEnabled
                )
                .putBoolean(
                    KEY_FLOATING_AUTO_CLOSE_ON_SCREEN_CHANGE_ENABLED,
                    settings.autoCloseOnScreenChangeEnabled
                )
                .putString(KEY_FLOATING_SINGLE_TAP_ACTION, settings.singleTapAction.prefValue)
                .putString(KEY_FLOATING_DOUBLE_TAP_ACTION, settings.doubleTapAction.prefValue)
                .putString(KEY_FLOATING_LONG_PRESS_ACTION, settings.longPressAction.prefValue)
                .putString(KEY_FLOATING_TRIPLE_TAP_ACTION, settings.tripleTapAction.prefValue)
            }
    }

    fun loadOcrApiSettings(): OcrApiSettings {
        val useLocal = prefs.getBoolean(KEY_OCR_USE_LOCAL, true)
        val url = prefs.getString(KEY_OCR_API_URL, DEFAULT_OCR_API_URL) ?: DEFAULT_OCR_API_URL
        val key = prefs.getString(KEY_OCR_API_KEY, "") ?: ""
        val model = prefs.getString(KEY_OCR_MODEL_NAME, DEFAULT_OCR_MODEL_NAME) ?: DEFAULT_OCR_MODEL_NAME
        val timeoutSeconds = prefs.getInt(KEY_OCR_API_TIMEOUT_SECONDS, DEFAULT_OCR_API_TIMEOUT_SECONDS)
            .coerceIn(MIN_OCR_API_TIMEOUT_SECONDS, MAX_OCR_API_TIMEOUT_SECONDS)
        return OcrApiSettings(
            useLocalOcr = useLocal,
            japaneseLocalOcrEngine = JapaneseLocalOcrEngine.fromPref(
                prefs.getString(KEY_JAPANESE_LOCAL_OCR_ENGINE, null)
            ),
            apiUrl = url,
            apiKey = key,
            modelName = model,
            timeoutSeconds = timeoutSeconds
        )
    }

    fun saveOcrApiSettings(settings: OcrApiSettings) {
        val normalizedTimeout = settings.timeoutSeconds
            .coerceIn(MIN_OCR_API_TIMEOUT_SECONDS, MAX_OCR_API_TIMEOUT_SECONDS)
        prefs.edit() {
                putBoolean(KEY_OCR_USE_LOCAL, settings.useLocalOcr)
                .putString(
                    KEY_JAPANESE_LOCAL_OCR_ENGINE,
                    settings.japaneseLocalOcrEngine.prefValue
                )
                .putString(KEY_OCR_API_URL, settings.apiUrl)
                .putString(KEY_OCR_API_KEY, settings.apiKey)
                .putString(KEY_OCR_MODEL_NAME, settings.modelName)
                .putInt(KEY_OCR_API_TIMEOUT_SECONDS, normalizedTimeout)
            }
    }

    fun loadUseHorizontalText(): Boolean {
        return prefs.getBoolean(KEY_HORIZONTAL_TEXT, true)
    }

    fun saveUseHorizontalText(enabled: Boolean) {
        prefs.edit() {
                putBoolean(KEY_HORIZONTAL_TEXT, enabled)
            }
    }

    fun loadNormalBubbleRenderSettings(): NormalBubbleRenderSettings {
        return NormalBubbleRenderSettings(
            shrinkPercent = prefs.getInt(
                KEY_NORMAL_BUBBLE_SHRINK_PERCENT,
                DEFAULT_NORMAL_BUBBLE_SHRINK_PERCENT
            ).coerceIn(
                MIN_NORMAL_BUBBLE_SHRINK_PERCENT,
                MAX_NORMAL_BUBBLE_SHRINK_PERCENT
            ),
            opacityPercent = loadTranslationBubbleOpacityPercent(),
            freeBubbleShrinkPercent = prefs.getInt(
                KEY_NORMAL_FREE_BUBBLE_SHRINK_PERCENT,
                DEFAULT_NORMAL_FREE_BUBBLE_SHRINK_PERCENT
            ).coerceIn(
                MIN_NORMAL_BUBBLE_SHRINK_PERCENT,
                MAX_NORMAL_BUBBLE_SHRINK_PERCENT
            ),
            freeBubbleOpacityPercent = prefs.getInt(
                KEY_NORMAL_FREE_BUBBLE_OPACITY_PERCENT,
                DEFAULT_NORMAL_FREE_BUBBLE_OPACITY_PERCENT
            ).coerceIn(
                MIN_TRANSLATION_BUBBLE_OPACITY_PERCENT,
                MAX_TRANSLATION_BUBBLE_OPACITY_PERCENT
            ),
            minAreaPerCharSp = prefs.getFloat(
                KEY_NORMAL_BUBBLE_MIN_AREA_PER_CHAR_SP,
                DEFAULT_NORMAL_MIN_AREA_PER_CHAR_SP
            ).coerceIn(
                MIN_NORMAL_MIN_AREA_PER_CHAR_SP,
                MAX_NORMAL_MIN_AREA_PER_CHAR_SP
            ),
            useHorizontalText = loadUseHorizontalText()
        )
    }

    fun saveNormalBubbleRenderSettings(settings: NormalBubbleRenderSettings) {
        prefs.edit() {
                putInt(
                    KEY_NORMAL_BUBBLE_SHRINK_PERCENT,
                    settings.shrinkPercent.coerceIn(
                        MIN_NORMAL_BUBBLE_SHRINK_PERCENT,
                        MAX_NORMAL_BUBBLE_SHRINK_PERCENT
                    )
                )
                .putInt(
                    KEY_TRANSLATION_BUBBLE_OPACITY_PERCENT,
                    settings.opacityPercent.coerceIn(
                        MIN_TRANSLATION_BUBBLE_OPACITY_PERCENT,
                        MAX_TRANSLATION_BUBBLE_OPACITY_PERCENT
                    )
                )
                .putFloat(
                    KEY_NORMAL_BUBBLE_MIN_AREA_PER_CHAR_SP,
                    settings.minAreaPerCharSp.coerceIn(
                        MIN_NORMAL_MIN_AREA_PER_CHAR_SP,
                        MAX_NORMAL_MIN_AREA_PER_CHAR_SP
                    )
                )
                .putInt(
                    KEY_NORMAL_FREE_BUBBLE_SHRINK_PERCENT,
                    settings.freeBubbleShrinkPercent.coerceIn(
                        MIN_NORMAL_BUBBLE_SHRINK_PERCENT,
                        MAX_NORMAL_BUBBLE_SHRINK_PERCENT
                    )
                )
                .putInt(
                    KEY_NORMAL_FREE_BUBBLE_OPACITY_PERCENT,
                    settings.freeBubbleOpacityPercent.coerceIn(
                        MIN_TRANSLATION_BUBBLE_OPACITY_PERCENT,
                        MAX_TRANSLATION_BUBBLE_OPACITY_PERCENT
                    )
                )
                .putBoolean(KEY_HORIZONTAL_TEXT, settings.useHorizontalText)
            }
    }

    fun loadFloatingBubbleRenderSettings(): FloatingBubbleRenderSettings {
        return FloatingBubbleRenderSettings(
            sizeAdjustPercent = prefs.getInt(
                KEY_FLOATING_BUBBLE_SIZE_ADJUST_PERCENT,
                DEFAULT_FLOATING_BUBBLE_SIZE_ADJUST_PERCENT
            ).coerceIn(
                MIN_FLOATING_BUBBLE_SIZE_ADJUST_PERCENT,
                MAX_FLOATING_BUBBLE_SIZE_ADJUST_PERCENT
            ),
            opacityPercent = prefs.getInt(
                KEY_FLOATING_BUBBLE_OPACITY_PERCENT,
                loadTranslationBubbleOpacityPercent()
            ).coerceIn(
                MIN_TRANSLATION_BUBBLE_OPACITY_PERCENT,
                MAX_TRANSLATION_BUBBLE_OPACITY_PERCENT
            ),
            shape = FloatingBubbleShape.fromPref(
                prefs.getString(KEY_FLOATING_BUBBLE_SHAPE, FloatingBubbleShape.RECTANGLE.prefValue)
            ),
            useHorizontalText = prefs.getBoolean(KEY_FLOATING_BUBBLE_HORIZONTAL_TEXT, true),
            minAreaPerCharSp = prefs.getFloat(
                KEY_FLOATING_BUBBLE_MIN_AREA_PER_CHAR_SP,
                DEFAULT_FLOATING_MIN_AREA_PER_CHAR_SP
            ).coerceIn(
                MIN_FLOATING_MIN_AREA_PER_CHAR_SP,
                MAX_FLOATING_MIN_AREA_PER_CHAR_SP
            )
        )
    }

    fun saveFloatingBubbleRenderSettings(settings: FloatingBubbleRenderSettings) {
        prefs.edit() {
                putInt(
                    KEY_FLOATING_BUBBLE_SIZE_ADJUST_PERCENT,
                    settings.sizeAdjustPercent.coerceIn(
                        MIN_FLOATING_BUBBLE_SIZE_ADJUST_PERCENT,
                        MAX_FLOATING_BUBBLE_SIZE_ADJUST_PERCENT
                    )
                )
                .putInt(
                    KEY_FLOATING_BUBBLE_OPACITY_PERCENT,
                    settings.opacityPercent.coerceIn(
                        MIN_TRANSLATION_BUBBLE_OPACITY_PERCENT,
                        MAX_TRANSLATION_BUBBLE_OPACITY_PERCENT
                    )
                )
                .putString(KEY_FLOATING_BUBBLE_SHAPE, settings.shape.prefValue)
                .putBoolean(KEY_FLOATING_BUBBLE_HORIZONTAL_TEXT, settings.useHorizontalText)
                .putFloat(
                    KEY_FLOATING_BUBBLE_MIN_AREA_PER_CHAR_SP,
                    settings.minAreaPerCharSp.coerceIn(
                        MIN_FLOATING_MIN_AREA_PER_CHAR_SP,
                        MAX_FLOATING_MIN_AREA_PER_CHAR_SP
                    )
                )
            }
    }

    fun loadModelIoLogging(): Boolean {
        return prefs.getBoolean(KEY_MODEL_IO_LOGGING, false)
    }

    fun saveModelIoLogging(enabled: Boolean) {
        prefs.edit() {
                putBoolean(KEY_MODEL_IO_LOGGING, enabled)
            }
    }

    fun loadApiRetryCount(): Int {
        val saved = prefs.getInt(KEY_API_RETRY_COUNT, DEFAULT_API_RETRY_COUNT)
        return saved.coerceIn(MIN_API_RETRY_COUNT, MAX_API_RETRY_COUNT)
    }

    fun saveApiRetryCount(value: Int) {
        val normalized = value.coerceIn(MIN_API_RETRY_COUNT, MAX_API_RETRY_COUNT)
        prefs.edit() {
                putInt(KEY_API_RETRY_COUNT, normalized)
            }
    }

    fun loadMaxConcurrency(): Int {
        val saved = prefs.getInt(KEY_MAX_CONCURRENCY, DEFAULT_MAX_CONCURRENCY)
        return saved.coerceIn(MIN_MAX_CONCURRENCY, MAX_MAX_CONCURRENCY)
    }

    fun saveMaxConcurrency(value: Int) {
        val normalized = value.coerceIn(MIN_MAX_CONCURRENCY, MAX_MAX_CONCURRENCY)
        prefs.edit() {
                putInt(KEY_MAX_CONCURRENCY, normalized)
            }
    }

    fun loadApiTimeoutSeconds(): Int {
        val saved = prefs.getInt(KEY_API_TIMEOUT_SECONDS, DEFAULT_API_TIMEOUT_SECONDS)
        return saved.coerceIn(MIN_API_TIMEOUT_SECONDS, MAX_API_TIMEOUT_SECONDS)
    }

    fun loadApiTimeoutMs(): Int {
        return loadApiTimeoutSeconds() * 1000
    }

    fun saveApiTimeoutSeconds(value: Int) {
        val normalized = value.coerceIn(MIN_API_TIMEOUT_SECONDS, MAX_API_TIMEOUT_SECONDS)
        prefs.edit() {
                putInt(KEY_API_TIMEOUT_SECONDS, normalized)
            }
    }

    fun loadThemeMode(): ThemeMode {
        val saved = prefs.getString(KEY_THEME_MODE, ThemeMode.FOLLOW_SYSTEM.prefValue)
        return ThemeMode.fromPref(saved)
    }

    fun loadAppLanguage(): AppLanguage {
        val saved = prefs.getString(KEY_APP_LANGUAGE, AppLanguage.FOLLOW_SYSTEM.prefValue)
        return AppLanguage.fromPref(saved)
    }

    fun saveAppLanguage(language: AppLanguage) {
        prefs.edit() {
                putString(KEY_APP_LANGUAGE, language.prefValue)
            }
    }

    fun saveThemeMode(mode: ThemeMode) {
        prefs.edit() {
                putString(KEY_THEME_MODE, mode.prefValue)
            }
    }

    fun loadReadingDisplayMode(): ReadingDisplayMode {
        val saved = prefs.getString(KEY_READING_DISPLAY_MODE, ReadingDisplayMode.FIT_WIDTH.prefValue)
        return ReadingDisplayMode.fromPref(saved)
    }

    fun saveReadingDisplayMode(mode: ReadingDisplayMode) {
        prefs.edit() {
                putString(KEY_READING_DISPLAY_MODE, mode.prefValue)
            }
    }

    fun loadReadingPageAnimationMode(): ReadingPageAnimationMode {
        val saved = prefs.getString(
            KEY_READING_PAGE_ANIMATION_MODE,
            ReadingPageAnimationMode.HORIZONTAL_SLIDE.prefValue
        )
        return ReadingPageAnimationMode.fromPref(saved)
    }

    fun saveReadingPageAnimationMode(mode: ReadingPageAnimationMode) {
        prefs.edit() {
                putString(KEY_READING_PAGE_ANIMATION_MODE, mode.prefValue)
            }
    }

    fun loadBubbleConfThresholdPercent(): Int {
        val saved = prefs.getInt(
            KEY_BUBBLE_CONF_THRESHOLD_PERCENT,
            DEFAULT_BUBBLE_CONF_THRESHOLD_PERCENT
        )
        return saved.coerceIn(
            MIN_BUBBLE_CONF_THRESHOLD_PERCENT,
            MAX_BUBBLE_CONF_THRESHOLD_PERCENT
        )
    }

    fun saveBubbleConfThresholdPercent(value: Int) {
        val normalized = value.coerceIn(
            MIN_BUBBLE_CONF_THRESHOLD_PERCENT,
            MAX_BUBBLE_CONF_THRESHOLD_PERCENT
        )
        prefs.edit() {
                putInt(KEY_BUBBLE_CONF_THRESHOLD_PERCENT, normalized)
            }
    }

    fun loadTranslationBubbleOpacityPercent(): Int {
        val saved = prefs.getInt(
            KEY_TRANSLATION_BUBBLE_OPACITY_PERCENT,
            DEFAULT_TRANSLATION_BUBBLE_OPACITY_PERCENT
        )
        return saved.coerceIn(
            MIN_TRANSLATION_BUBBLE_OPACITY_PERCENT,
            MAX_TRANSLATION_BUBBLE_OPACITY_PERCENT
        )
    }

    fun loadTranslationBubbleOpacity(): Float {
        return loadTranslationBubbleOpacityPercent() / 100f
    }

    fun saveTranslationBubbleOpacityPercent(value: Int) {
        val normalized = value.coerceIn(
            MIN_TRANSLATION_BUBBLE_OPACITY_PERCENT,
            MAX_TRANSLATION_BUBBLE_OPACITY_PERCENT
        )
        prefs.edit() {
                putInt(KEY_TRANSLATION_BUBBLE_OPACITY_PERCENT, normalized)
            }
    }

    fun loadLinkSource(): LinkSource {
        val saved = prefs.getString(KEY_LINK_SOURCE, LinkSource.GITHUB.prefValue)
        return LinkSource.fromPref(saved)
    }

    fun saveLinkSource(source: LinkSource) {
        prefs.edit() {
                putString(KEY_LINK_SOURCE, source.prefValue)
            }
    }

    fun loadLlmParameters(): LlmParameterSettings {
        return LlmParameterSettings(
            temperature = readDoubleWithDefault(KEY_LLM_TEMPERATURE, DEFAULT_LLM_TEMPERATURE),
            topP = readDoubleWithDefault(KEY_LLM_TOP_P, DEFAULT_LLM_TOP_P),
            topK = readIntOptional(KEY_LLM_TOP_K),
            maxOutputTokens = readIntOptional(KEY_LLM_MAX_OUTPUT_TOKENS),
            enableThinking = prefs.getBoolean(KEY_LLM_ENABLE_THINKING, DEFAULT_LLM_ENABLE_THINKING),
            thinkingBudget = readIntOptional(KEY_LLM_THINKING_BUDGET),
            frequencyPenalty = readDoubleOptional(KEY_LLM_FREQUENCY_PENALTY),
            presencePenalty = readDoubleOptional(KEY_LLM_PRESENCE_PENALTY)
        )
    }

    fun saveLlmParameters(settings: LlmParameterSettings) {
        prefs.edit() {
                putOptionalString(KEY_LLM_TEMPERATURE, settings.temperature)
                .putOptionalString(KEY_LLM_TOP_P, settings.topP)
                .putOptionalString(KEY_LLM_TOP_K, settings.topK)
                .putOptionalString(KEY_LLM_MAX_OUTPUT_TOKENS, settings.maxOutputTokens)
                .putBoolean(KEY_LLM_ENABLE_THINKING, settings.enableThinking)
                .putOptionalString(KEY_LLM_THINKING_BUDGET, settings.thinkingBudget)
                .putOptionalString(KEY_LLM_FREQUENCY_PENALTY, settings.frequencyPenalty)
                .putOptionalString(KEY_LLM_PRESENCE_PENALTY, settings.presencePenalty)
            }
    }

    fun loadCustomRequestParameters(): List<CustomRequestParameter> {
        val raw = prefs.getString(KEY_CUSTOM_REQUEST_PARAMETERS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val key = item.optString("key").trim()
                    val value = item.optString("value")
                    val enabled = item.optBoolean("enabled", true)
                    val targetProviderId = item.optString("targetProviderId")
                        .trim()
                        .ifBlank { PRIMARY_PROVIDER_ID }
                    if (key.isBlank() && value.isBlank()) continue
                    add(
                        CustomRequestParameter(
                            key = key,
                            value = value,
                            enabled = enabled,
                            targetProviderId = targetProviderId
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveCustomRequestParameters(parameters: List<CustomRequestParameter>) {
        val array = JSONArray()
        parameters.forEach { parameter ->
            val key = parameter.key.trim()
            val value = parameter.value
            if (key.isBlank() && value.isBlank()) return@forEach
            array.put(
                JSONObject()
                    .put("key", key)
                    .put("value", value)
                    .put("enabled", parameter.enabled)
                    .put(
                        "targetProviderId",
                        parameter.targetProviderId.trim().ifBlank { PRIMARY_PROVIDER_ID }
                    )
            )
        }
        prefs.edit() {
            putString(KEY_CUSTOM_REQUEST_PARAMETERS, array.toString())
        }
    }

    fun loadAdditionalTranslationProviders(): List<AdditionalTranslationProvider> {
        val raw = prefs.getString(KEY_ADDITIONAL_TRANSLATION_PROVIDERS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val provider = parseAdditionalTranslationProvider(item, index)
                    if (
                        provider.apiUrl.isBlank() &&
                        provider.apiKey.isBlank() &&
                        provider.modelName.isBlank()
                    ) {
                        continue
                    }
                    add(provider)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveAdditionalTranslationProviders(providers: List<AdditionalTranslationProvider>) {
        val array = JSONArray()
        providers.forEachIndexed { index, provider ->
            val apiUrl = provider.apiUrl.trim()
            val apiKey = provider.apiKey.trim()
            val modelName = provider.modelName.trim()
            if (apiUrl.isBlank() && apiKey.isBlank() && modelName.isBlank()) return@forEachIndexed
            array.put(
                JSONObject()
                    .put("name", provider.name.ifBlank { defaultAdditionalProviderName(index) })
                    .put("apiUrl", apiUrl)
                    .put("apiKey", apiKey)
                    .put("modelName", modelName)
                    .put("weight", provider.weight.coerceAtLeast(1))
                    .put("enabled", provider.enabled)
            )
        }
        prefs.edit() {
            putString(KEY_ADDITIONAL_TRANSLATION_PROVIDERS, array.toString())
        }
    }

    fun loadMainTranslationProviderPool(): List<WeightedProviderCandidate> {
        val main = load()
        val candidates = ArrayList<WeightedProviderCandidate>()
        if (main.isValid()) {
            candidates += WeightedProviderCandidate(
                providerId = PRIMARY_PROVIDER_ID,
                displayName = PRIMARY_PROVIDER_DISPLAY_NAME,
                settings = main,
                weight = PRIMARY_PROVIDER_WEIGHT,
                isPrimary = true
            )
        }
        loadAdditionalTranslationProviders().forEachIndexed { index, provider ->
            if (!provider.enabled || !provider.isConfigured()) return@forEachIndexed
            candidates += WeightedProviderCandidate(
                providerId = "additional_${index + 1}",
                displayName = provider.name.ifBlank { defaultAdditionalProviderName(index) },
                settings = ApiSettings(
                    apiUrl = provider.apiUrl.trim(),
                    apiKey = provider.apiKey.trim(),
                    modelName = provider.modelName.trim(),
                    apiFormat = main.apiFormat,
                    providerId = "additional_${index + 1}"
                ),
                weight = provider.weight.coerceAtLeast(1),
                isPrimary = false
            )
        }
        return candidates
    }

    fun loadAiProviderProfilesState(): AiProviderProfilesState {
        val raw = runCatching {
            if (aiProviderProfilesFile.exists()) aiProviderProfilesFile.readText() else ""
        }.getOrDefault("")
        if (raw.isBlank()) return AiProviderProfilesState(activeProfileName = null, profiles = emptyList())
        return runCatching {
            val root = JSONObject(raw)
            val profilesJson = root.optJSONArray("profiles") ?: JSONArray()
            val profiles = buildList {
                for (index in 0 until profilesJson.length()) {
                    val item = profilesJson.optJSONObject(index) ?: continue
                    parseAiProviderProfile(item)?.let(::add)
                }
            }
            val activeProfileName = root.optString("activeProfileName").trim().ifBlank { null }
            val normalizedActive = activeProfileName?.takeIf { active ->
                profiles.any { it.name == active }
            }
            AiProviderProfilesState(
                activeProfileName = normalizedActive,
                profiles = profiles.sortedBy { it.name.lowercase() }
            )
        }.getOrDefault(AiProviderProfilesState(activeProfileName = null, profiles = emptyList()))
    }

    fun saveCurrentAsAiProviderProfile(name: String): Boolean {
        val normalizedName = name.trim()
        if (normalizedName.isBlank()) return false
        val currentState = loadAiProviderProfilesState()
        if (currentState.profiles.any { it.name == normalizedName }) return false
        val updatedProfiles = currentState.profiles + captureCurrentAiProviderProfile(normalizedName)
        writeAiProviderProfilesState(
            AiProviderProfilesState(
                activeProfileName = normalizedName,
                profiles = updatedProfiles
            )
        )
        return true
    }

    fun overwriteActiveAiProviderProfile(): Boolean {
        val currentState = loadAiProviderProfilesState()
        val activeProfileName = currentState.activeProfileName ?: return false
        val updatedProfiles = currentState.profiles.map { profile ->
            if (profile.name == activeProfileName) {
                captureCurrentAiProviderProfile(activeProfileName)
            } else {
                profile
            }
        }
        writeAiProviderProfilesState(
            currentState.copy(
                profiles = updatedProfiles
            )
        )
        return true
    }

    fun applyAiProviderProfile(name: String): Boolean {
        val currentState = loadAiProviderProfilesState()
        val profile = currentState.profiles.firstOrNull { it.name == name } ?: return false
        save(profile.mainSettings)
        saveApiTimeoutSeconds(profile.apiTimeoutSeconds)
        saveOcrApiSettings(profile.ocrSettings)
        saveFloatingTranslateApiSettings(profile.floatingTranslateSettings)
        saveLlmParameters(profile.llmParameters)
        saveCustomRequestParameters(profile.customRequestParameters)
        saveAdditionalTranslationProviders(profile.additionalTranslationProviders)
        writeAiProviderProfilesState(
            currentState.copy(
                activeProfileName = profile.name
            )
        )
        return true
    }

    fun deleteAiProviderProfile(name: String): Boolean {
        val currentState = loadAiProviderProfilesState()
        val updatedProfiles = currentState.profiles.filterNot { it.name == name }
        if (updatedProfiles.size == currentState.profiles.size) return false
        val updatedActive = currentState.activeProfileName?.takeIf { it != name }
        writeAiProviderProfilesState(
            AiProviderProfilesState(
                activeProfileName = updatedActive,
                profiles = updatedProfiles
            )
        )
        return true
    }

    private fun captureCurrentAiProviderProfile(name: String): AiProviderProfile {
        return AiProviderProfile(
            name = name,
            mainSettings = load(),
            apiTimeoutSeconds = loadApiTimeoutSeconds(),
            ocrSettings = loadOcrApiSettings(),
            floatingTranslateSettings = loadFloatingTranslateApiSettings(),
            llmParameters = loadLlmParameters(),
            customRequestParameters = loadCustomRequestParameters(),
            additionalTranslationProviders = loadAdditionalTranslationProviders()
        )
    }

    private fun writeAiProviderProfilesState(state: AiProviderProfilesState) {
        val root = JSONObject()
        root.put("activeProfileName", state.activeProfileName.orEmpty())
        val profilesArray = JSONArray()
        state.profiles
            .sortedBy { it.name.lowercase() }
            .forEach { profile ->
                profilesArray.put(serializeAiProviderProfile(profile))
            }
        root.put("profiles", profilesArray)
        aiProviderProfilesFile.writeText(root.toString())
    }

    private fun serializeAiProviderProfile(profile: AiProviderProfile): JSONObject {
        return JSONObject()
            .put("name", profile.name)
            .put(
                "mainSettings",
                JSONObject()
                    .put("apiUrl", profile.mainSettings.apiUrl)
                    .put("apiKey", profile.mainSettings.apiKey)
                    .put("modelName", profile.mainSettings.modelName)
                    .put("apiFormat", profile.mainSettings.apiFormat.prefValue)
                    .put("apiTimeoutSeconds", profile.apiTimeoutSeconds)
            )
            .put(
                "ocrSettings",
                JSONObject()
                    .put("useLocalOcr", profile.ocrSettings.useLocalOcr)
                    .put(
                        "japaneseLocalOcrEngine",
                        profile.ocrSettings.japaneseLocalOcrEngine.prefValue
                    )
                    .put("apiUrl", profile.ocrSettings.apiUrl)
                    .put("apiKey", profile.ocrSettings.apiKey)
                    .put("modelName", profile.ocrSettings.modelName)
                    .put("timeoutSeconds", profile.ocrSettings.timeoutSeconds)
            )
            .put(
                "floatingTranslateSettings",
                JSONObject()
                    .put("apiUrl", profile.floatingTranslateSettings.apiUrl)
                    .put("apiKey", profile.floatingTranslateSettings.apiKey)
                    .put("modelName", profile.floatingTranslateSettings.modelName)
                    .put("language", profile.floatingTranslateSettings.language.name)
                    .put("timeoutSeconds", profile.floatingTranslateSettings.timeoutSeconds)
                    .put(
                        "useVlDirectTranslate",
                        profile.floatingTranslateSettings.useVlDirectTranslate
                    )
                    .put(
                        "vlTranslateConcurrency",
                        profile.floatingTranslateSettings.vlTranslateConcurrency
                    )
                    .put(
                        "proofreadingModeEnabled",
                        profile.floatingTranslateSettings.proofreadingModeEnabled
                    )
                    .put(
                        "autoCloseOnScreenChangeEnabled",
                        profile.floatingTranslateSettings.autoCloseOnScreenChangeEnabled
                    )
                    .put(
                        "singleTapAction",
                        profile.floatingTranslateSettings.singleTapAction.prefValue
                    )
                    .put(
                        "doubleTapAction",
                        profile.floatingTranslateSettings.doubleTapAction.prefValue
                    )
                    .put(
                        "longPressAction",
                        profile.floatingTranslateSettings.longPressAction.prefValue
                    )
                    .put(
                        "tripleTapAction",
                        profile.floatingTranslateSettings.tripleTapAction.prefValue
                    )
            )
            .put(
                "llmParameters",
                JSONObject()
                    .put("temperature", profile.llmParameters.temperature)
                    .put("topP", profile.llmParameters.topP)
                    .put("topK", profile.llmParameters.topK)
                    .put("maxOutputTokens", profile.llmParameters.maxOutputTokens)
                    .put("enableThinking", profile.llmParameters.enableThinking)
                    .put("thinkingBudget", profile.llmParameters.thinkingBudget)
                    .put("frequencyPenalty", profile.llmParameters.frequencyPenalty)
                    .put("presencePenalty", profile.llmParameters.presencePenalty)
            )
            .put(
                "customRequestParameters",
                JSONArray().apply {
                    profile.customRequestParameters.forEach { parameter ->
                        put(
                            JSONObject()
                                .put("key", parameter.key)
                                .put("value", parameter.value)
                                .put("enabled", parameter.enabled)
                                .put(
                                    "targetProviderId",
                                    parameter.targetProviderId.ifBlank { PRIMARY_PROVIDER_ID }
                                )
                        )
                    }
                }
            )
            .put(
                "additionalTranslationProviders",
                JSONArray().apply {
                    profile.additionalTranslationProviders.forEachIndexed { index, provider ->
                        put(
                            JSONObject()
                                .put("name", provider.name.ifBlank { defaultAdditionalProviderName(index) })
                                .put("apiUrl", provider.apiUrl)
                                .put("apiKey", provider.apiKey)
                                .put("modelName", provider.modelName)
                                .put("weight", provider.weight)
                                .put("enabled", provider.enabled)
                        )
                    }
                }
            )
    }

    private fun parseAiProviderProfile(item: JSONObject): AiProviderProfile? {
        val name = item.optString("name").trim()
        if (name.isBlank()) return null
        val mainJson = item.optJSONObject("mainSettings") ?: JSONObject()
        val ocrJson = item.optJSONObject("ocrSettings") ?: JSONObject()
        val floatingJson = item.optJSONObject("floatingTranslateSettings") ?: JSONObject()
        val llmJson = item.optJSONObject("llmParameters") ?: JSONObject()
        val customParams = item.optJSONArray("customRequestParameters") ?: JSONArray()
        val additionalProviders = item.optJSONArray("additionalTranslationProviders") ?: JSONArray()
        return AiProviderProfile(
            name = name,
            mainSettings = ApiSettings(
                apiUrl = mainJson.optString("apiUrl", DEFAULT_API_URL),
                apiKey = mainJson.optString("apiKey"),
                modelName = mainJson.optString("modelName", DEFAULT_MODEL),
                apiFormat = ApiFormat.fromPref(mainJson.optStringOrNull("apiFormat")),
                providerId = PRIMARY_PROVIDER_ID
            ),
            apiTimeoutSeconds = mainJson.optInt(
                "apiTimeoutSeconds",
                DEFAULT_API_TIMEOUT_SECONDS
            ).coerceIn(MIN_API_TIMEOUT_SECONDS, MAX_API_TIMEOUT_SECONDS),
            ocrSettings = OcrApiSettings(
                useLocalOcr = ocrJson.optBoolean("useLocalOcr", true),
                japaneseLocalOcrEngine = JapaneseLocalOcrEngine.fromPref(
                    ocrJson.optStringOrNull("japaneseLocalOcrEngine")
                ),
                apiUrl = ocrJson.optString("apiUrl", DEFAULT_OCR_API_URL),
                apiKey = ocrJson.optString("apiKey"),
                modelName = ocrJson.optString("modelName", DEFAULT_OCR_MODEL_NAME),
                timeoutSeconds = ocrJson.optInt(
                    "timeoutSeconds",
                    DEFAULT_OCR_API_TIMEOUT_SECONDS
                ).coerceIn(MIN_OCR_API_TIMEOUT_SECONDS, MAX_OCR_API_TIMEOUT_SECONDS)
            ),
            floatingTranslateSettings = FloatingTranslateApiSettings(
                apiUrl = floatingJson.optString("apiUrl"),
                apiKey = floatingJson.optString("apiKey"),
                modelName = floatingJson.optString("modelName"),
                language = TranslationLanguage.fromString(
                    floatingJson.optString("language", TranslationLanguage.JA_TO_ZH.name)
                ),
                timeoutSeconds = floatingJson.optInt(
                    "timeoutSeconds",
                    DEFAULT_FLOATING_API_TIMEOUT_SECONDS
                ).coerceIn(
                    MIN_FLOATING_API_TIMEOUT_SECONDS,
                    MAX_FLOATING_API_TIMEOUT_SECONDS
                ),
                useVlDirectTranslate = floatingJson.optBoolean("useVlDirectTranslate", false),
                vlTranslateConcurrency = floatingJson.optInt(
                    "vlTranslateConcurrency",
                    DEFAULT_FLOATING_VL_TRANSLATE_CONCURRENCY
                ).coerceIn(
                    MIN_FLOATING_VL_TRANSLATE_CONCURRENCY,
                    MAX_FLOATING_VL_TRANSLATE_CONCURRENCY
                ),
                proofreadingModeEnabled = floatingJson.optBoolean(
                    "proofreadingModeEnabled",
                    false
                ),
                autoCloseOnScreenChangeEnabled = floatingJson.optBoolean(
                    "autoCloseOnScreenChangeEnabled",
                    false
                ),
                singleTapAction = FloatingBallGestureAction.fromPref(
                    floatingJson.optStringOrNull("singleTapAction"),
                    DEFAULT_FLOATING_SINGLE_TAP_ACTION
                ),
                doubleTapAction = FloatingBallGestureAction.fromPref(
                    floatingJson.optStringOrNull("doubleTapAction"),
                    DEFAULT_FLOATING_DOUBLE_TAP_ACTION
                ),
                longPressAction = FloatingBallGestureAction.fromPref(
                    floatingJson.optStringOrNull("longPressAction"),
                    DEFAULT_FLOATING_LONG_PRESS_ACTION
                ),
                tripleTapAction = FloatingBallGestureAction.fromPref(
                    floatingJson.optStringOrNull("tripleTapAction"),
                    DEFAULT_FLOATING_TRIPLE_TAP_ACTION
                )
            ),
            llmParameters = LlmParameterSettings(
                temperature = llmJson.optOptionalDouble("temperature"),
                topP = llmJson.optOptionalDouble("topP"),
                topK = llmJson.optOptionalInt("topK"),
                maxOutputTokens = llmJson.optOptionalInt("maxOutputTokens"),
                enableThinking = llmJson.optBoolean("enableThinking", DEFAULT_LLM_ENABLE_THINKING),
                thinkingBudget = llmJson.optOptionalInt("thinkingBudget"),
                frequencyPenalty = llmJson.optOptionalDouble("frequencyPenalty"),
                presencePenalty = llmJson.optOptionalDouble("presencePenalty")
            ),
            customRequestParameters = buildList {
                for (index in 0 until customParams.length()) {
                    val param = customParams.optJSONObject(index) ?: continue
                    val key = param.optString("key").trim()
                    val value = param.optString("value")
                    if (key.isBlank() && value.isBlank()) continue
                    add(
                        CustomRequestParameter(
                            key = key,
                            value = value,
                            enabled = param.optBoolean("enabled", true),
                            targetProviderId = param.optString("targetProviderId")
                                .trim()
                                .ifBlank { PRIMARY_PROVIDER_ID }
                        )
                    )
                }
            },
            additionalTranslationProviders = buildList {
                for (index in 0 until additionalProviders.length()) {
                    val providerJson = additionalProviders.optJSONObject(index) ?: continue
                    add(parseAdditionalTranslationProvider(providerJson, index))
                }
            }
        )
    }

    private fun parseAdditionalTranslationProvider(
        item: JSONObject,
        index: Int
    ): AdditionalTranslationProvider {
        return AdditionalTranslationProvider(
            name = item.optString("name").trim().ifBlank { defaultAdditionalProviderName(index) },
            apiUrl = item.optString("apiUrl"),
            apiKey = item.optString("apiKey"),
            modelName = item.optString("modelName"),
            weight = item.optInt("weight", 1).coerceAtLeast(1),
            enabled = item.optBoolean("enabled", true)
        )
    }

    fun defaultAdditionalProviderName(index: Int): String {
        return "供应商 ${index + 1}"
    }

    private fun readDoubleWithDefault(key: String, defaultValue: Double): Double? {
        if (!prefs.contains(key)) return defaultValue
        val value = prefs.getString(key, null)
        if (value.isNullOrBlank()) return null
        return value.toDoubleOrNull()
    }

    private fun readDoubleOptional(key: String): Double? {
        if (!prefs.contains(key)) return null
        val value = prefs.getString(key, null)
        if (value.isNullOrBlank()) return null
        return value.toDoubleOrNull()
    }

    private fun readIntOptional(key: String): Int? {
        if (!prefs.contains(key)) return null
        val value = prefs.getString(key, null)
        if (value.isNullOrBlank()) return null
        return value.toIntOrNull()
    }

    companion object {
        private const val PREFS_NAME = "manga_translate_settings"
        private const val AI_PROVIDER_PROFILES_FILE_NAME = "ai_provider_profiles.json"
        private const val KEY_API_URL = "api_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL_NAME = "model_name"
        private const val KEY_API_FORMAT = "api_format"
        private const val KEY_OCR_USE_LOCAL = "ocr_use_local"
        private const val KEY_JAPANESE_LOCAL_OCR_ENGINE = "japanese_local_ocr_engine"
        private const val KEY_OCR_API_URL = "ocr_api_url"
        private const val KEY_OCR_API_KEY = "ocr_api_key"
        private const val KEY_OCR_MODEL_NAME = "ocr_model_name"
        private const val KEY_FLOATING_API_URL = "floating_api_url"
        private const val KEY_FLOATING_API_KEY = "floating_api_key"
        private const val KEY_FLOATING_MODEL_NAME = "floating_model_name"
        private const val KEY_FLOATING_LANGUAGE = "floating_language"
        private const val KEY_FLOATING_TIMEOUT_SECONDS = "floating_timeout_seconds"
        private const val KEY_FLOATING_USE_VL_DIRECT_TRANSLATE = "floating_use_vl_direct_translate"
        private const val KEY_FLOATING_VL_TRANSLATE_CONCURRENCY = "floating_vl_translate_concurrency"
        private const val KEY_FLOATING_PROOFREADING_MODE_ENABLED =
            "floating_proofreading_mode_enabled"
        private const val KEY_FLOATING_AUTO_CLOSE_ON_SCREEN_CHANGE_ENABLED =
            "floating_auto_close_on_screen_change_enabled"
        private const val KEY_FLOATING_SINGLE_TAP_ACTION = "floating_single_tap_action"
        private const val KEY_FLOATING_DOUBLE_TAP_ACTION = "floating_double_tap_action"
        private const val KEY_FLOATING_LONG_PRESS_ACTION = "floating_long_press_action"
        private const val KEY_FLOATING_TRIPLE_TAP_ACTION = "floating_triple_tap_action"
        private const val KEY_FLOATING_BUBBLE_SIZE_ADJUST_PERCENT =
            "floating_bubble_size_adjust_percent"
        private const val KEY_FLOATING_BUBBLE_OPACITY_PERCENT = "floating_bubble_opacity_percent"
        private const val KEY_FLOATING_BUBBLE_SHAPE = "floating_bubble_shape"
        private const val KEY_FLOATING_BUBBLE_HORIZONTAL_TEXT = "floating_bubble_horizontal_text"
        private const val KEY_FLOATING_BUBBLE_MIN_AREA_PER_CHAR_SP =
            "floating_bubble_min_area_per_char_sp"
        private const val KEY_OCR_API_TIMEOUT_SECONDS = "ocr_api_timeout_seconds"
        private const val KEY_HORIZONTAL_TEXT = "horizontal_text_layout"
        private const val KEY_NORMAL_BUBBLE_SHRINK_PERCENT = "normal_bubble_shrink_percent"
        private const val KEY_NORMAL_BUBBLE_MIN_AREA_PER_CHAR_SP = "normal_bubble_min_area_per_char_sp"
        private const val KEY_NORMAL_FREE_BUBBLE_SHRINK_PERCENT =
            "normal_free_bubble_shrink_percent"
        private const val KEY_NORMAL_FREE_BUBBLE_OPACITY_PERCENT =
            "normal_free_bubble_opacity_percent"
        private const val KEY_MODEL_IO_LOGGING = "model_io_logging"
        private const val KEY_API_RETRY_COUNT = "api_retry_count"
        private const val KEY_MAX_CONCURRENCY = "max_concurrency"
        private const val KEY_API_TIMEOUT_SECONDS = "api_timeout_seconds"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_READING_DISPLAY_MODE = "reading_display_mode"
        private const val KEY_READING_PAGE_ANIMATION_MODE = "reading_page_animation_mode"
        private const val KEY_TRANSLATION_BUBBLE_OPACITY_PERCENT = "translation_bubble_opacity_percent"
        private const val KEY_BUBBLE_CONF_THRESHOLD_PERCENT = "bubble_conf_threshold_percent"
        private const val KEY_LINK_SOURCE = "link_source"
        private const val KEY_LLM_TEMPERATURE = "llm_temperature"
        private const val KEY_LLM_TOP_P = "llm_top_p"
        private const val KEY_LLM_TOP_K = "llm_top_k"
        private const val KEY_LLM_MAX_OUTPUT_TOKENS = "llm_max_output_tokens"
        private const val KEY_ADDITIONAL_TRANSLATION_PROVIDERS = "additional_translation_providers"
        private const val KEY_LLM_ENABLE_THINKING = "llm_enable_thinking"
        private const val KEY_LLM_THINKING_BUDGET = "llm_thinking_budget"
        private const val KEY_LLM_FREQUENCY_PENALTY = "llm_frequency_penalty"
        private const val KEY_LLM_PRESENCE_PENALTY = "llm_presence_penalty"
        private const val KEY_CUSTOM_REQUEST_PARAMETERS = "custom_request_parameters"
        const val PRIMARY_PROVIDER_WEIGHT = 10
        const val PRIMARY_PROVIDER_DISPLAY_NAME = "主供应商"
        private const val DEFAULT_LLM_TEMPERATURE = 0.8
        private const val DEFAULT_LLM_TOP_P = 1.0
        private const val DEFAULT_LLM_ENABLE_THINKING = false
        private const val DEFAULT_API_URL = "https://api.siliconflow.cn/v1"
        private const val DEFAULT_MODEL = "Qwen/Qwen3.5-35B-A3B"
        private const val DEFAULT_OCR_API_URL = "https://api.siliconflow.cn/v1"
        private const val DEFAULT_OCR_MODEL_NAME = "Qwen/Qwen3-VL-8B-Instruct"
        private const val DEFAULT_OCR_API_TIMEOUT_SECONDS = 300
        private const val MIN_OCR_API_TIMEOUT_SECONDS = 30
        private const val MAX_OCR_API_TIMEOUT_SECONDS = 1200
        private const val DEFAULT_FLOATING_VL_TRANSLATE_CONCURRENCY = 1
        private const val MIN_FLOATING_VL_TRANSLATE_CONCURRENCY = 1
        private const val MAX_FLOATING_VL_TRANSLATE_CONCURRENCY = 16
        private val DEFAULT_FLOATING_SINGLE_TAP_ACTION = FloatingBallGestureAction.START_TRANSLATE
        private val DEFAULT_FLOATING_DOUBLE_TAP_ACTION = FloatingBallGestureAction.CLEAR_SCREEN
        private val DEFAULT_FLOATING_LONG_PRESS_ACTION = FloatingBallGestureAction.OPEN_MENU
        private val DEFAULT_FLOATING_TRIPLE_TAP_ACTION = FloatingBallGestureAction.NONE
        private const val DEFAULT_FLOATING_API_TIMEOUT_SECONDS = 300
        private const val MIN_FLOATING_API_TIMEOUT_SECONDS = 30
        private const val MAX_FLOATING_API_TIMEOUT_SECONDS = 1200
        private const val DEFAULT_FLOATING_BUBBLE_SIZE_ADJUST_PERCENT = 0
        private const val MIN_FLOATING_BUBBLE_SIZE_ADJUST_PERCENT = -30
        private const val MAX_FLOATING_BUBBLE_SIZE_ADJUST_PERCENT = 30
        private const val DEFAULT_FLOATING_MIN_AREA_PER_CHAR_SP = 48f
        private const val MIN_FLOATING_MIN_AREA_PER_CHAR_SP = 16f
        private const val MAX_FLOATING_MIN_AREA_PER_CHAR_SP = 256f
        private const val DEFAULT_NORMAL_BUBBLE_SHRINK_PERCENT = 10
        private const val MIN_NORMAL_BUBBLE_SHRINK_PERCENT = 0
        private const val MAX_NORMAL_BUBBLE_SHRINK_PERCENT = 30
        private const val DEFAULT_NORMAL_FREE_BUBBLE_SHRINK_PERCENT = 10
        private const val DEFAULT_NORMAL_FREE_BUBBLE_OPACITY_PERCENT = 90
        private const val DEFAULT_NORMAL_MIN_AREA_PER_CHAR_SP = 48f
        private const val MIN_NORMAL_MIN_AREA_PER_CHAR_SP = 16f
        private const val MAX_NORMAL_MIN_AREA_PER_CHAR_SP = 256f
        private const val DEFAULT_MAX_CONCURRENCY = 3
        private const val MIN_MAX_CONCURRENCY = 1
        private const val MAX_MAX_CONCURRENCY = 200
        private const val DEFAULT_API_RETRY_COUNT = 3
        private const val MIN_API_RETRY_COUNT = 1
        private const val MAX_API_RETRY_COUNT = 50
        private const val DEFAULT_API_TIMEOUT_SECONDS = 300
        private const val MIN_API_TIMEOUT_SECONDS = 30
        private const val MAX_API_TIMEOUT_SECONDS = 1200
        private const val DEFAULT_TRANSLATION_BUBBLE_OPACITY_PERCENT = 100
        private const val MIN_TRANSLATION_BUBBLE_OPACITY_PERCENT = 0
        private const val MAX_TRANSLATION_BUBBLE_OPACITY_PERCENT = 100
        private const val DEFAULT_BUBBLE_CONF_THRESHOLD_PERCENT = 20
        private const val MIN_BUBBLE_CONF_THRESHOLD_PERCENT = 1
        private const val MAX_BUBBLE_CONF_THRESHOLD_PERCENT = 95
    }
}

data class LlmParameterSettings(
    val temperature: Double?,
    val topP: Double?,
    val topK: Int?,
    val maxOutputTokens: Int?,
    val enableThinking: Boolean,
    val thinkingBudget: Int?,
    val frequencyPenalty: Double?,
    val presencePenalty: Double?
)

private fun android.content.SharedPreferences.Editor.putOptionalString(
    key: String,
    value: Number?
): android.content.SharedPreferences.Editor {
    putString(key, value?.toString().orEmpty())
    return this
}

private fun JSONObject.optOptionalInt(key: String): Int? {
    if (isNull(key)) return null
    return when (val raw = opt(key)) {
        is Number -> raw.toInt()
        is String -> raw.toIntOrNull()
        else -> null
    }
}

private fun JSONObject.optOptionalDouble(key: String): Double? {
    if (isNull(key)) return null
    return when (val raw = opt(key)) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull()
        else -> null
    }
}

private fun JSONObject.optStringOrNull(key: String): String? {
    if (isNull(key)) return null
    return optString(key).takeIf { it.isNotBlank() }
}
