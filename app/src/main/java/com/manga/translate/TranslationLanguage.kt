package com.manga.translate

enum class TranslationLanguage(val displayNameResId: Int) {
    JA_TO_ZH(R.string.folder_language_ja_to_zh),
    EN_TO_ZH(R.string.folder_language_en_to_zh),
    EN_TO_JA(R.string.folder_language_en_to_ja),
    KO_TO_ZH(R.string.folder_language_ko_to_zh);

    fun resolvePromptAsset(assetName: String): String {
        return when (this) {
            EN_TO_JA -> assetName.withPromptSuffix("_ja")
            else -> assetName
        }
    }

    private fun String.withPromptSuffix(suffix: String): String {
        val dotIndex = lastIndexOf('.')
        if (dotIndex <= 0) return this + suffix
        return substring(0, dotIndex) + suffix + substring(dotIndex)
    }

    companion object {
        fun fromString(value: String?): TranslationLanguage {
            return when (value) {
                "EN_TO_ZH" -> EN_TO_ZH
                "EN_TO_JA" -> EN_TO_JA
                "KO_TO_ZH" -> KO_TO_ZH
                else -> JA_TO_ZH
            }
        }
    }
}
