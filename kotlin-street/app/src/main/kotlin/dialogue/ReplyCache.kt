package com.timerpg.street.dialogue

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Lines already decided, keyed by a hash of their prompt: the model's validated reply, or "template" when its reply failed
 * the check (so it is not tried again and again). Kept in memory and in [file] (JSON), so lines survive restarts.
 * Reads are safe from any thread; [load] and [save] run on the dialogue worker.
 */
class ReplyCache(private val file: File) {
    /** [line] null: use the template. */
    class Entry(val line: String?)

    private val map = ConcurrentHashMap<String, Entry>()

    fun get(key: String): Entry? = map[key]
    fun putLine(key: String, line: String) { if (map.size < MAX) map[key] = Entry(line) }
    fun putTemplate(key: String) { if (map.size < MAX) map[key] = Entry(null) }

    /** Model lines, and lines that stay the template. */
    val lines: Int get() = map.values.count { it.line != null }
    val templates: Int get() = map.values.count { it.line == null }

    /** Forget the rejected ones (a new model gets its own try). */
    fun dropTemplates() { map.entries.removeIf { it.value.line == null } }

    /** Keep only the lines of these prompts (the dialogue file changed). */
    fun retain(keys: Set<String>) { map.keys.retainAll(keys) }

    fun load() {
        if (!file.isFile) return
        try {
            val j = JSONObject(file.readText())
            if (j.optInt("v") != VERSION) return
            val lines = j.optJSONObject("lines")
            if (lines != null) for (k in lines.keys()) lines.optString(k).takeIf { it.isNotEmpty() }?.let { map[k] = Entry(it) }
            val templates = j.optJSONArray("templates")
            if (templates != null) for (i in 0 until templates.length()) templates.optString(i).takeIf { it.isNotEmpty() }?.let { map[it] = Entry(null) }
        } catch (e: Exception) {
            // a damaged cache is only lost lines: start again
            Log.w(TAG, "reply cache unreadable, starting empty: $e")
            map.clear()
        }
    }

    /** Writes the cache (to a temporary file, then renamed over the old one, so a crash never leaves half a file). */
    fun save() {
        try {
            val lines = JSONObject()
            val templates = JSONArray()
            for ((k, e) in map) if (e.line != null) lines.put(k, e.line) else templates.put(k)
            val text = JSONObject().put("v", VERSION).put("lines", lines).put("templates", templates).toString()
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) tmp.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "could not save the reply cache: $e")
        }
    }

    companion object {
        private const val TAG = "ReplyCache"
        private const val VERSION = 1
        private const val MAX = 5000

        /** The cache key of a prompt (with the generation settings, so changing them starts afresh). */
        fun keyOf(prompt: String, settings: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest((settings + "\n" + prompt).toByteArray(Charsets.UTF_8))
            val hex = "0123456789abcdef"
            return buildString { for (b in d.take(16)) { val v = b.toInt() and 0xff; append(hex[v shr 4]).append(hex[v and 15]) } }
        }
    }
}
