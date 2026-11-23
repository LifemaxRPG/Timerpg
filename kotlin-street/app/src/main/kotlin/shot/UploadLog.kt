package com.timerpg.street.shot

import android.content.Context

/**
 * What the uploads did last (both processes: the game's and the writer's), so a report that never reaches the
 * repository says why: the last success and the last failure with its path, the HTTP code and GitHub's words, or the
 * reason nothing was tried (no token, offline, not on Wi-Fi or charging). Shown in the screenshot JSON. Before this
 * every report upload failed quietly (sendNight, the playtest recorder's files and crash reports broke off at the first
 * failure with no trace).
 */
object UploadLog {
    @Suppress("DEPRECATION")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("uploads", Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS)

    /** One upload's outcome ([code] the HTTP status, -1 no connection, 0 not tried). */
    data class Entry(val at: Long, val kind: String, val path: String, val code: Int, val what: String) {
        fun json(): String = org.json.JSONObject().put("at", at).put("kind", kind).put("path", path).put("code", code).put("what", what).toString()
        fun map(now: Long): Map<String, Any?> = linkedMapOf("kind" to kind, "path" to path, "code" to code, "what" to what,
            "time" to ShotRequests.isoTime(at, java.util.TimeZone.getDefault()), "minutesAgo" to (now - at) / 60_000)

        companion object {
            fun parse(t: String?): Entry? = t?.let { s -> runCatching { org.json.JSONObject(s).let { o ->
                Entry(o.getLong("at"), o.optString("kind"), o.optString("path"), o.optInt("code"), o.optString("what")) } }.getOrNull() }
        }
    }

    /** The kind of a report path ("night/2026-11-23/…" is "night"). */
    fun kindOf(path: String): String = path.substringBefore('/').ifEmpty { "?" }

    fun ok(ctx: Context, path: String, code: Int) = Entry(System.currentTimeMillis(), kindOf(path), path, code, "sent").let { put(ctx, "lastOk", it); put(ctx, "last." + it.kind, it) }

    fun failed(ctx: Context, path: String, code: Int, body: String) {
        val what = ShotRequests.explain(code, body)
        Crumbs.add("upload of $path failed: $what ($code)")
        Entry(System.currentTimeMillis(), kindOf(path), path, code, what).let { put(ctx, "lastError", it); put(ctx, "last." + it.kind, it) }
    }

    /** Nothing was tried for [kind] ([why]: no token, offline, not allowed now). Kept apart from real failures. */
    fun skipped(ctx: Context, kind: String, why: String) = Entry(System.currentTimeMillis(), kind, "", 0, why).let { put(ctx, "lastSkip", it); put(ctx, "last." + kind, it) }

    /** The kinds kept apart in [summary]'s lastByKind. */
    val KINDS = listOf("play", "talk", "trace", "crashes", "night", "npu")

    private fun put(ctx: Context, key: String, e: Entry) { runCatching { prefs(ctx).edit().putString(key, e.json()).apply() } }

    fun entry(ctx: Context, key: String): Entry? = Entry.parse(runCatching { prefs(ctx).getString(key, null) }.getOrNull())

    /** For the screenshot JSON. */
    fun summary(ctx: Context): Map<String, Any?> {
        val now = System.currentTimeMillis()
        return linkedMapOf("lastError" to entry(ctx, "lastError")?.map(now), "lastOk" to entry(ctx, "lastOk")?.map(now),
            "lastSkip" to entry(ctx, "lastSkip")?.map(now),
            // the last attempt of each kind (sent, failed or skipped, with why): the playtest recorder's above all
            "lastByKind" to linkedMapOf(*KINDS.map { k -> k to entry(ctx, "last.$k")?.map(now) }.toTypedArray()))
    }
}
