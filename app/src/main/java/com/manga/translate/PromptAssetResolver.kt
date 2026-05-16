package com.manga.translate

import android.content.Context
import java.util.Locale

object PromptAssetResolver {
    fun resolve(context: Context, assetName: String): String {
        if (!isTraditionalChinese(context)) return assetName
        val candidate = assetName.toTraditionalVariantName()
        if (candidate == assetName) return assetName
        return if (assetExists(context, candidate)) candidate else assetName
    }

    private fun assetExists(context: Context, assetName: String): Boolean {
        return try {
            context.assets.open(assetName).close()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun isTraditionalChinese(context: Context): Boolean {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        if (!locale.language.equals(Locale.CHINESE.language, ignoreCase = true)) return false
        val script = locale.script.orEmpty()
        if (script.equals("Hant", ignoreCase = true)) return true
        return locale.country.uppercase(Locale.US) in TRADITIONAL_CHINESE_REGIONS
    }

    private fun String.toTraditionalVariantName(): String {
        val dotIndex = lastIndexOf('.')
        if (dotIndex <= 0) return this
        return substring(0, dotIndex) + HANT_SUFFIX + substring(dotIndex)
    }

    private const val HANT_SUFFIX = "_hant"
    private val TRADITIONAL_CHINESE_REGIONS = setOf("TW", "HK", "MO")
}
