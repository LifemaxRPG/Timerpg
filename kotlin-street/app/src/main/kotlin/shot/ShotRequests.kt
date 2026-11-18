package com.timerpg.street.shot

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What the screenshots and crash reports send to GitHub, built without any Android types so it is tested on the JVM:
 * the file paths, the request URLs, headers and bodies (base64 content, branch refs), the JSON of the game state and of
 * a crash report, and the user-facing reading of an HTTP status. [ShotUploader] does the network calls with these.
 */
object ShotRequests {
    const val OWNER = "lifemaxrpg"
    const val REPO = "timerpg"
    const val API = "https://api.github.com"
    const val SHOT_BRANCH = "screenshots"
    const val REPORT_BRANCH = "reports"
    /** A screenshot larger than this is sent as lossless WebP, or scaled down (see [ShotUploader]). */
    const val MAX_IMAGE_BYTES = 3_000_000

    // ---- paths

    /** A short file-name-safe slug of [text]: lower case ASCII letters and digits, dashes between, at most [max] long. */
    fun slug(text: String?, fallback: String = "shot", max: Int = 32): String {
        val sb = StringBuilder()
        var dash = false
        for (c in (text ?: "").lowercase(Locale.US)) {
            if ((c in 'a'..'z') || (c in '0'..'9')) {
                if (dash && sb.isNotEmpty()) sb.append('-')
                dash = false
                sb.append(c)
                if (sb.length >= max) break
            } else dash = true
        }
        return sb.toString().trimEnd('-').ifEmpty { fallback }
    }

    private fun fmt(pattern: String, ms: Long, tz: TimeZone) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = tz }.format(Date(ms))

    /** The time as ISO 8601 with its offset (2026-11-18T14:05:09.120+0100). */
    fun isoTime(ms: Long, tz: TimeZone): String = fmt("yyyy-MM-dd'T'HH:mm:ss.SSSZ", ms, tz)

    /**
     * screenshots/<yyyy-MM-dd>/<HHmmss>-<slug>.<ext>, in the phone's time zone; [attempt] > 0 adds -2, -3 ... (a name
     * already taken: two shots in the same second with the same note).
     */
    fun shotPath(ms: Long, tz: TimeZone, note: String?, ext: String = "png", attempt: Int = 0): String =
        "screenshots/${fmt("yyyy-MM-dd", ms, tz)}/${fmt("HHmmss", ms, tz)}-${slug(note)}${if (attempt > 0) "-${attempt + 1}" else ""}.$ext"

    /** The game-state file beside an image: the same name, .json. */
    fun siblingJson(path: String): String = path.substringBeforeLast('.') + ".json"

    /** crashes/<yyyy-MM-dd>/<HHmmss>-<kind>.json. */
    fun crashPath(ms: Long, tz: TimeZone, kind: String): String =
        "crashes/${fmt("yyyy-MM-dd", ms, tz)}/${fmt("HHmmss", ms, tz)}-${slug(kind, "crash")}.json"

    // ---- requests

    fun repoUrl() = "$API/repos/$OWNER/$REPO"
    fun contentsUrl(path: String) = "${repoUrl()}/contents/" + path.split('/').joinToString("/") { encodePathPart(it) }
    fun refUrl(branch: String) = "${repoUrl()}/git/ref/heads/${encodePathPart(branch)}"
    fun refsUrl() = "${repoUrl()}/git/refs"

    private fun encodePathPart(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** The headers of every call (the token is only ever put here, never logged or written anywhere). */
    fun headers(token: String): Map<String, String> = linkedMapOf(
        "Authorization" to "Bearer $token",
        "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to "2022-11-28",
    )

    /** The body of PUT /contents: {message, content (base64), branch}, as UTF-8 bytes (built without a second copy as a String of the image). */
    fun putBody(message: String, content: ByteArray, branch: String): ByteArray {
        val sb = StringBuilder(content.size * 4 / 3 + 256)
        sb.append("{\"message\":").append(Json.quote(message)).append(",\"branch\":").append(Json.quote(branch)).append(",\"content\":\"")
        base64(content, sb)
        sb.append("\"}")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** The body of POST /git/refs that makes [branch] at [sha]. */
    fun createRefBody(branch: String, sha: String): String = Json.write(linkedMapOf("ref" to "refs/heads/$branch", "sha" to sha))

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Standard base64 with padding (java.util.Base64 is API 26+; the game runs from API 24). */
    fun base64(bytes: ByteArray, out: StringBuilder = StringBuilder(bytes.size * 4 / 3 + 4)): StringBuilder {
        var i = 0
        val n = bytes.size
        while (i + 2 < n) {
            val v = ((bytes[i].toInt() and 0xff) shl 16) or ((bytes[i + 1].toInt() and 0xff) shl 8) or (bytes[i + 2].toInt() and 0xff)
            out.append(B64[v ushr 18]).append(B64[(v ushr 12) and 63]).append(B64[(v ushr 6) and 63]).append(B64[v and 63])
            i += 3
        }
        val left = n - i
        if (left == 1) {
            val v = (bytes[i].toInt() and 0xff) shl 16
            out.append(B64[v ushr 18]).append(B64[(v ushr 12) and 63]).append("==")
        } else if (left == 2) {
            val v = ((bytes[i].toInt() and 0xff) shl 16) or ((bytes[i + 1].toInt() and 0xff) shl 8)
            out.append(B64[v ushr 18]).append(B64[(v ushr 12) and 63]).append(B64[(v ushr 6) and 63]).append('=')
        }
        return out
    }

    /** The commit message of a screenshot. */
    fun shotMessage(note: String?, path: String): String =
        "Screenshot from the phone" + (note?.trim()?.takeIf { it.isNotEmpty() }?.let { ": " + it.take(72) } ?: ": " + path.substringAfterLast('/'))

    // ---- payloads

    /**
     * The JSON beside a screenshot: kind, time, the note, the app, the image (how it was sent), then each section the
     * caller gathered (screen, device, game ...), in order.
     */
    fun shotPayload(ms: Long, tz: TimeZone, note: String?, app: Map<String, Any?>, image: Map<String, Any?>,
                    sections: Map<String, Any?>): String {
        val m = linkedMapOf<String, Any?>(
            "kind" to "screenshot",
            "time" to isoTime(ms, tz),
            "epochMs" to ms,
            "note" to note?.trim()?.takeIf { it.isNotEmpty() },
            "app" to app,
            "image" to image,
        )
        m.putAll(sections)
        return Json.write(m, pretty = true)
    }

    /**
     * A crash report: [kind] (jvm, native, anr, low-memory ...), its time, the app, [fields] (the thread, or Android's
     * exit record), the [stack] (or the tombstone / ANR trace), the game [state] then, the [crumbs] (time, event) before
     * it, and the app's own [logcat] lines when there are.
     */
    fun crashPayload(kind: String, ms: Long, tz: TimeZone, app: Map<String, Any?>, fields: Map<String, Any?>, stack: String?,
                     state: Map<String, Any?>?, crumbs: List<Pair<Long, String>>, logcat: List<String>?): String {
        val m = linkedMapOf<String, Any?>(
            "kind" to kind,
            "time" to isoTime(ms, tz),
            "epochMs" to ms,
            "app" to app,
        )
        m.putAll(fields)
        m["stack"] = stack
        m["state"] = state
        m["breadcrumbs"] = crumbs.map { (t, what) -> "${fmt("HH:mm:ss.SSS", t, tz)} $what" }
        m["logcat"] = logcat
        return Json.write(m, pretty = true)
    }

    // ---- answers

    /** What a failed call means for the player (code -1: no connection at all). */
    fun explain(code: Int, body: String = ""): String = when {
        code == -1 -> "No network"
        code == 401 -> "Token rejected: wrong or expired (make a new one)"
        code == 403 && body.contains("rate limit", ignoreCase = true) -> "GitHub's rate limit: try again later"
        code == 403 -> "Token can't write to the repo"
        code == 404 -> "Token can't see $OWNER/$REPO"
        code == 409 -> "GitHub conflict: try again"
        code == 422 -> "GitHub refused the file" + messageOf(body)?.let { ": $it" }.orEmpty()
        code >= 500 -> "GitHub is having trouble ($code)"
        else -> "GitHub error $code" + messageOf(body)?.let { ": $it" }.orEmpty()
    }

    /** The "message" of GitHub's error JSON, if there is one. */
    fun messageOf(body: String): String? = Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)?.groupValues?.get(1)?.take(120)
}

/** A small JSON writer: maps (in order), lists, arrays, strings, numbers (NaN and infinities as null), booleans, null. */
object Json {
    /** Text that is already JSON, written as it is. */
    class Raw(val text: String)

    fun write(v: Any?, pretty: Boolean = false): String = StringBuilder().also { write(v, it, if (pretty) 0 else -1) }.toString()

    private fun write(v: Any?, sb: StringBuilder, indent: Int) {
        when (v) {
            null -> sb.append("null")
            is String -> sb.append(quote(v))
            is Raw -> sb.append(v.text.trim())
            is Boolean -> sb.append(v)
            is Float -> if (v.isNaN() || v.isInfinite()) sb.append("null") else sb.append(v.toDouble().let { if (it == Math.rint(it) && kotlin.math.abs(it) < 1e15) it.toLong().toString() else v.toString() })
            is Double -> if (v.isNaN() || v.isInfinite()) sb.append("null") else sb.append(if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString())
            is Number -> sb.append(v.toString())
            is Map<*, *> -> block(sb, indent, '{', '}', v.entries.toList()) { e, i -> sb.append(quote(e.key.toString())).append(if (indent >= 0) ": " else ":"); write(e.value, sb, i) }
            is Iterable<*> -> block(sb, indent, '[', ']', v.toList()) { e, i -> write(e, sb, i) }
            is Array<*> -> block(sb, indent, '[', ']', v.toList()) { e, i -> write(e, sb, i) }
            is FloatArray -> write(v.toList(), sb, indent)
            is IntArray -> write(v.toList(), sb, indent)
            else -> sb.append(quote(v.toString()))
        }
    }

    private fun <T> block(sb: StringBuilder, indent: Int, open: Char, close: Char, items: List<T>, each: (T, Int) -> Unit) {
        sb.append(open)
        if (items.isEmpty()) { sb.append(close); return }
        val inner = if (indent >= 0) indent + 2 else -1
        items.forEachIndexed { k, e ->
            if (k > 0) sb.append(',')
            if (inner >= 0) sb.append('\n').append(" ".repeat(inner))
            each(e, inner)
        }
        if (indent >= 0) sb.append('\n').append(" ".repeat(indent))
        sb.append(close)
    }

    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000c' -> sb.append("\\f")
            else -> if (c < ' ' || c == ' ' || c == ' ') sb.append(String.format(Locale.US, "\\u%04x", c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }
}

/**
 * The last [capacity] game events (time, what), for crash reports: a fixed ring, written from any thread. Adding keeps
 * no copy of anything but the string given (events are rare: room changes, the corner turn, a renderer switch ...).
 */
class Breadcrumbs(val capacity: Int = 100) {
    private val times = LongArray(capacity)
    private val whats = arrayOfNulls<String>(capacity)
    private var at = 0
    private var n = 0

    @Synchronized fun add(timeMs: Long, what: String) {
        times[at] = timeMs; whats[at] = what
        at = (at + 1) % capacity
        if (n < capacity) n++
    }

    /** Oldest first. */
    @Synchronized fun snapshot(): List<Pair<Long, String>> = List(n) { k ->
        val i = (at - n + k + capacity) % capacity
        times[i] to (whats[i] ?: "")
    }

    @Synchronized fun size() = n

    /** One line per event (time ms, a tab, the event), to keep on disk between runs. */
    fun serialize(): String = snapshot().joinToString("\n") { (t, w) -> "$t\t" + w.replace('\n', ' ') }

    companion object {
        fun parse(text: String): List<Pair<Long, String>> = text.lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else line.substring(0, tab).toLongOrNull()?.let { it to line.substring(tab + 1) }
        }.toList()
    }
}
