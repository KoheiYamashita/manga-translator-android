package com.manga.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val changelog: String,
    val history: List<UpdateHistoryEntry>,
    val releaseChannel: ReleaseChannel,
    val qqGroup: String?
)

data class UpdateHistoryEntry(
    val versionCode: Int,
    val versionName: String,
    val releasedAt: String,
    val changelog: String,
    val apkUrl: String,
    val releaseChannel: ReleaseChannel
)

enum class ReleaseChannel {
    STABLE,
    PREVIEW
}

object UpdateChecker {
    private const val UPDATE_URL_GITHUB =
        "https://raw.githubusercontent.com/jedzqer/manga-translator/main/update.json"
    private const val UPDATE_URL_GITEE =
        "https://gitee.com/jedzqer/manga-translator/raw/main/update.json"
    private val updateUrls = listOf(UPDATE_URL_GITHUB, UPDATE_URL_GITEE)
    private const val DEFAULT_TIMEOUT_MS = 15_000

    suspend fun fetchUpdateInfo(timeoutMs: Int = DEFAULT_TIMEOUT_MS): UpdateInfo? =
        fetchUpdateInfo(timeoutMs, includePreview = true)

    suspend fun fetchUpdateInfo(
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        includePreview: Boolean
    ): UpdateInfo? =
        withContext(Dispatchers.IO) {
            val coroutineContext = currentCoroutineContext()
            val job = coroutineContext[Job]
            for ((index, url) in updateUrls.withIndex()) {
                coroutineContext.ensureActive()
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                }
                val cancelHandle = job?.invokeOnCompletion {
                    connection.disconnect()
                }
                try {
                    val code = connection.responseCode
                    val stream = if (code in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }
                    val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                    if (code !in 200..299) {
                        AppLogger.log("UpdateChecker", "[$index] $url HTTP $code: $body")
                        continue
                    }
                    val parsed = parseUpdateInfo(body, includePreview)
                    if (parsed != null) {
                        AppLogger.log("UpdateChecker", "Loaded update info from $url")
                        return@withContext parsed
                    }
                    AppLogger.log("UpdateChecker", "[$index] $url returned invalid update json")
                } catch (e: Exception) {
                    AppLogger.log("UpdateChecker", "[$index] Update request failed: $url", e)
                } finally {
                    cancelHandle?.dispose()
                    connection.disconnect()
                }
            }
            null
        }

    private fun parseUpdateInfo(body: String, includePreview: Boolean): UpdateInfo? {
        return try {
            val normalizedBody = normalizeUpdateJson(body)
            if (normalizedBody != body) {
                AppLogger.log("UpdateChecker", "Normalized update json before parsing")
            }
            val json = JSONObject(normalizedBody)
            val history = buildHistory(json)
            val latest = buildLatest(json) ?: return null
            val selected = selectUpdateInfo(latest, history, includePreview) ?: latest
            if (selected.versionName.isBlank() || selected.apkUrl.isBlank()) {
                AppLogger.log("UpdateChecker", "Invalid update json: $body")
                null
            } else {
                val remainingHistory = buildSelectedHistory(selected, latest, history)
                selected.copy(history = remainingHistory)
            }
        } catch (e: Exception) {
            AppLogger.log("UpdateChecker", "Parse update json failed", e)
            null
        }
    }

    private fun normalizeUpdateJson(body: String): String {
        if (body.isBlank()) return body
        val result = StringBuilder(body.length)
        var inString = false
        var escaping = false
        var index = 0
        while (index < body.length) {
            val current = body[index]
            if (inString) {
                result.append(current)
                if (escaping) {
                    escaping = false
                } else if (current == '\\') {
                    escaping = true
                } else if (current == '"') {
                    inString = false
                }
                index++
                continue
            }

            when (current) {
                '"' -> {
                    inString = true
                    result.append(current)
                }
                ',' -> {
                    var lookAhead = index + 1
                    while (lookAhead < body.length && body[lookAhead].isWhitespace()) {
                        lookAhead++
                    }
                    if (lookAhead < body.length && (body[lookAhead] == '}' || body[lookAhead] == ']')) {
                        index++
                        continue
                    }
                    result.append(current)
                }
                else -> result.append(current)
            }
            index++
        }
        return result.toString()
    }

    private fun buildLatest(json: JSONObject): UpdateInfo? {
        val versionCode = json.optInt("versionCode", -1)
        val versionName = json.optString("versionName").trim()
        val apkUrl = json.optString("apkUrl").trim()
        val changelog = json.optString("changelog").trim()
        val releaseChannel = parseReleaseChannel(json.optString("releaseChannel"))
        val qqGroup = json.optString("qqGroup").trim().ifBlank { null }
        if (versionName.isBlank() || apkUrl.isBlank()) return null
        return UpdateInfo(
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = apkUrl,
            changelog = changelog,
            history = emptyList(),
            releaseChannel = releaseChannel,
            qqGroup = qqGroup
        )
    }

    private fun buildHistory(json: JSONObject): List<UpdateHistoryEntry> {
        val historyArray = json.optJSONArray("history") ?: return emptyList()
        val items = ArrayList<UpdateHistoryEntry>(historyArray.length())
        for (i in 0 until historyArray.length()) {
            val entry = historyArray.optJSONObject(i) ?: continue
            val versionCode = entry.optInt("versionCode", -1)
            val versionName = entry.optString("versionName").trim()
            val releasedAt = entry.optString("releasedAt").trim()
            val changelog = entry.optString("changelog").trim()
            val apkUrl = entry.optString("apkUrl").trim()
            val releaseChannel = parseReleaseChannel(entry.optString("releaseChannel"))
            if (versionName.isBlank() || changelog.isBlank()) continue
            items.add(
                UpdateHistoryEntry(
                    versionCode = versionCode,
                    versionName = versionName,
                    releasedAt = releasedAt,
                    changelog = changelog,
                    apkUrl = apkUrl,
                    releaseChannel = releaseChannel
                )
            )
        }
        return items
    }

    private fun parseReleaseChannel(rawValue: String?): ReleaseChannel {
        return when (rawValue?.trim()?.lowercase()) {
            "preview" -> ReleaseChannel.PREVIEW
            else -> ReleaseChannel.STABLE
        }
    }

    private fun selectUpdateInfo(
        latest: UpdateInfo,
        history: List<UpdateHistoryEntry>,
        includePreview: Boolean
    ): UpdateInfo? {
        if (includePreview || latest.releaseChannel == ReleaseChannel.STABLE) {
            return latest
        }
        val latestStable = history.firstOrNull {
            it.releaseChannel == ReleaseChannel.STABLE &&
                it.versionCode > 0 &&
                it.apkUrl.isNotBlank()
        } ?: return null
        return UpdateInfo(
            versionCode = latestStable.versionCode,
            versionName = latestStable.versionName,
            apkUrl = latestStable.apkUrl,
            changelog = latestStable.changelog,
            history = emptyList(),
            releaseChannel = latestStable.releaseChannel,
            qqGroup = latest.qqGroup
        )
    }

    private fun buildSelectedHistory(
        selected: UpdateInfo,
        latest: UpdateInfo,
        history: List<UpdateHistoryEntry>
    ): List<UpdateHistoryEntry> {
        val items = ArrayList<UpdateHistoryEntry>(history.size + 1)
        if (selected.versionName != latest.versionName || selected.versionCode != latest.versionCode) {
            items.add(
                UpdateHistoryEntry(
                    versionCode = latest.versionCode,
                    versionName = latest.versionName,
                    releasedAt = "",
                    changelog = latest.changelog,
                    apkUrl = latest.apkUrl,
                    releaseChannel = latest.releaseChannel
                )
            )
        }
        items.addAll(history)
        return items.filterNot {
            it.versionName.equals(selected.versionName, ignoreCase = true) &&
                (selected.versionCode <= 0 || it.versionCode <= 0 || it.versionCode == selected.versionCode)
        }
    }
}
