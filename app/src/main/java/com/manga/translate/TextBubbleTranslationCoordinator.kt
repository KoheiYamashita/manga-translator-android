package com.manga.translate

internal class TextBubbleTranslationCoordinator(
    private val llmClient: LlmClient
) {

    suspend fun translateBubbles(
        bubbles: List<BubbleTranslation>,
        glossary: Map<String, String>,
        promptAsset: String,
        requestTimeoutMs: Int? = null,
        retryCount: Int = 3,
        apiSettings: ApiSettings? = null,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        logTag: String,
        translationMode: String
    ): TextBubbleTranslationBatchResult? {
        if (bubbles.isEmpty()) {
            return TextBubbleTranslationBatchResult(bubbles = bubbles, glossaryUsed = emptyMap())
        }
        val translatable = bubbles.filter { it.sourceText.isNotBlank() }
        if (translatable.isEmpty()) {
            AppLogger.log(logTag, "Skip translate: no translatable text")
            return TextBubbleTranslationBatchResult(bubbles = bubbles, glossaryUsed = emptyMap())
        }

        val resolvedApiSettings = apiSettings
        if (!llmClient.isConfigured(resolvedApiSettings)) {
            AppLogger.log(logTag, "Skip translate: LLM client not configured")
            return null
        }

        val translatedMap = HashMap<Int, String>(translatable.size)
        val cacheMisses = ArrayList<BubbleTranslation>(translatable.size)
        cacheMisses.addAll(translatable)

        fun merge(): List<BubbleTranslation> {
            return bubbles.map { bubble ->
                translatedMap[bubble.id]?.let { translated ->
                    bubble.withTranslationResult(translated)
                } ?: bubble
            }
        }

        if (cacheMisses.isEmpty()) {
            return TextBubbleTranslationBatchResult(
                bubbles = merge(),
                glossaryUsed = emptyMap()
            )
        }

        AppLogger.log(logTag, "Translate request segments=${cacheMisses.size}")
        val requestItems = cacheMisses.map {
            LlmBubbleTranslationRequestItem(
                id = it.id,
                text = normalizeOcrText(it.sourceText, language)
            )
        }
        val translated = llmClient.translateBubbleItems(
            items = requestItems,
            glossary = glossary,
            promptAsset = promptAsset,
            requestTimeoutMs = requestTimeoutMs,
            retryCount = retryCount,
            apiSettings = resolvedApiSettings
        ) ?: return null

        val translationById = LinkedHashMap<Int, String>(translated.items.size)
        val duplicateIds = LinkedHashSet<Int>()
        for (item in translated.items) {
            val normalizedTranslation = item.translation.trim()
            if (translationById.putIfAbsent(item.id, normalizedTranslation) != null) {
                duplicateIds.add(item.id)
            }
        }
        val requestedIds = requestItems.map { it.id }
        val requestedIdSet = requestedIds.toSet()
        val unexpectedIds = translationById.keys.filter { it !in requestedIdSet }
        val missingIds = requestedIds.filter { translationById[it].isNullOrBlank() }
        if (duplicateIds.isNotEmpty() || unexpectedIds.isNotEmpty() || missingIds.isNotEmpty()) {
            AppLogger.log(
                logTag,
                buildStructuredTranslationErrorLog(
                    mode = translationMode,
                    requestedIds = requestedIds,
                    duplicateIds = duplicateIds.toList(),
                    unexpectedIds = unexpectedIds,
                    missingIds = missingIds
                )
            )
        }

        for (source in cacheMisses) {
            val translatedText = translationById[source.id].orEmpty()
            translatedMap[source.id] = translatedText
        }

        return TextBubbleTranslationBatchResult(
            bubbles = merge(),
            glossaryUsed = translated.glossaryUsed
        )
    }
}

internal data class TextBubbleTranslationBatchResult(
    val bubbles: List<BubbleTranslation>,
    val glossaryUsed: Map<String, String>
)

private fun buildStructuredTranslationErrorLog(
    mode: String,
    requestedIds: List<Int>,
    duplicateIds: List<Int>,
    unexpectedIds: List<Int>,
    missingIds: List<Int>
): String {
    return buildString {
        append("Structured translation partial in ")
        append(mode)
        append(": requested=")
        append(summarizeIdsForLog(requestedIds))
        if (duplicateIds.isNotEmpty()) {
            append(", duplicate=")
            append(summarizeIdsForLog(duplicateIds))
        }
        if (unexpectedIds.isNotEmpty()) {
            append(", unexpected=")
            append(summarizeIdsForLog(unexpectedIds))
        }
        if (missingIds.isNotEmpty()) {
            append(", missing=")
            append(summarizeIdsForLog(missingIds))
        }
    }
}

private fun summarizeIdsForLog(ids: List<Int>, limit: Int = 12): String {
    if (ids.isEmpty()) return "[]"
    val normalized = ids.distinct()
    val shown = normalized.take(limit).joinToString(prefix = "[", postfix = "]")
    return if (normalized.size <= limit) shown else "$shown...(${normalized.size} total)"
}
