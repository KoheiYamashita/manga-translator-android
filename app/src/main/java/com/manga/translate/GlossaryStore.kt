package com.manga.translate

import org.json.JSONObject
import java.io.File

class GlossaryStore {
    fun load(folder: File): MutableMap<String, String> {
        val file = glossaryFileFor(folder)
        if (!file.exists()) return mutableMapOf()
        return try {
            val json = JSONObject(file.readText())
            val map = mutableMapOf<String, String>()
            for (key in json.keys()) {
                val value = json.optString(key).trim()
                if (key.isNotBlank() && value.isNotBlank()) {
                    map[key] = value
                }
            }
            map
        } catch (e: Exception) {
            AppLogger.log("GlossaryStore", "Failed to load glossary for ${folder.name}", e)
            mutableMapOf()
        }
    }

    fun save(folder: File, glossary: Map<String, String>) {
        val json = JSONObject()
        for ((key, value) in glossary) {
            json.put(key, value)
        }
        val file = glossaryFileFor(folder)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun glossaryFileFor(folder: File): File {
        return File(folder, "glossary.json")
    }
}
