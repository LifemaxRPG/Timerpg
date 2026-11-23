package com.timerpg.street.play

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

/**
 * One JSON object a line, built in a reused [StringBuilder] (no maps, no boxing): [begin], then fields, then [end].
 * Numbers are written with a fixed count of decimals by hand ([num]), so a line costs no garbage beyond the builder's
 * own growth. Keys are short on purpose: the timeline is written every second (PLAYTEST.md "What's built" lists them).
 */
class JsonLine(capacity: Int = 1024) {
    val sb = StringBuilder(capacity)
    private var first = true

    fun begin(): JsonLine { sb.setLength(0); sb.append('{'); first = true; return this }
    fun end(): StringBuilder { sb.append('}'); sb.append('\n'); return sb }

    private fun key(k: String) {
        if (!first) sb.append(',')
        first = false
        sb.append('"').append(k).append('"').append(':')
    }

    fun int(k: String, v: Int): JsonLine { key(k); sb.append(v); return this }
    fun long(k: String, v: Long): JsonLine { key(k); sb.append(v); return this }
    fun bool(k: String, v: Boolean): JsonLine { key(k); sb.append(if (v) "true" else "false"); return this }
    /** [v] with [decimals] places (NaN and infinities as null). */
    fun num(k: String, v: Double, decimals: Int = 1): JsonLine { key(k); fixed(sb, v, decimals); return this }
    fun num(k: String, v: Float, decimals: Int = 1): JsonLine = num(k, v.toDouble(), decimals)
    /** [v], or nothing at all when it is negative (unknown). */
    fun numIfKnown(k: String, v: Float, decimals: Int = 1): JsonLine { if (v >= 0f) num(k, v, decimals); return this }
    fun intIfKnown(k: String, v: Int): JsonLine { if (v >= 0) int(k, v); return this }
    fun str(k: String, v: String?): JsonLine { key(k); if (v == null) sb.append("null") else quote(sb, v); return this }
    fun strIfSet(k: String, v: String?): JsonLine { if (v != null) str(k, v); return this }
    /** [v] (p50, p95, p99, max) as an array, or nothing when unknown (-1). */
    fun pcts(k: String, v: FloatArray, decimals: Int = 1): JsonLine {
        if (v[0] < 0f) return this
        key(k); sb.append('[')
        for (i in v.indices) { if (i > 0) sb.append(','); fixed(sb, v[i].toDouble(), decimals) }
        sb.append(']'); return this
    }
    fun ints(k: String, v: IntArray, n: Int = v.size): JsonLine {
        key(k); sb.append('[')
        for (i in 0 until n) { if (i > 0) sb.append(','); sb.append(v[i]) }
        sb.append(']'); return this
    }
    /** A value already written as JSON. */
    fun raw(k: String, json: String): JsonLine { key(k); sb.append(json); return this }
    /** An object of [names] to [values] where the value is at least [min] (an empty one is left out). */
    fun named(k: String, names: Array<String>, values: FloatArray, min: Float, decimals: Int = 2): JsonLine {
        var any = false
        for (i in values.indices) {
            if (i >= names.size || values[i] < min) continue
            if (!any) { key(k); sb.append('{'); any = true } else sb.append(',')
            sb.append('"').append(names[i]).append('"').append(':'); fixed(sb, values[i].toDouble(), decimals)
        }
        if (any) sb.append('}')
        return this
    }
    fun named(k: String, names: Array<String>, values: IntArray): JsonLine {
        var any = false
        for (i in values.indices) {
            if (i >= names.size || values[i] == 0) continue
            if (!any) { key(k); sb.append('{'); any = true } else sb.append(',')
            sb.append('"').append(names[i]).append('"').append(':').append(values[i])
        }
        if (any) sb.append('}')
        return this
    }

    companion object {
        private val POW = longArrayOf(1, 10, 100, 1000, 10_000, 100_000, 1_000_000)

        fun fixed(sb: StringBuilder, v: Double, decimals: Int) {
            if (v.isNaN() || v.isInfinite()) { sb.append("null"); return }
            val d = decimals.coerceIn(0, 6)
            val scaled = Math.round(kotlin.math.abs(v) * POW[d])
            if (v < 0 && scaled != 0L) sb.append('-')
            sb.append(scaled / POW[d])
            if (d == 0) return
            var frac = scaled % POW[d]
            if (frac == 0L) return
            // trailing zeros dropped (1.50 -> 1.5)
            var digits = d
            while (frac % 10 == 0L) { frac /= 10; digits-- }
            sb.append('.')
            var p = POW[digits - 1]
            while (p > 0) { sb.append(('0' + (frac / p % 10).toInt())); p /= 10 }
        }

        fun quote(sb: StringBuilder, s: String) {
            sb.append('"')
            for (c in s) when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> { sb.append("\\u00"); sb.append(HEX[c.code shr 4]); sb.append(HEX[c.code and 15]) }
                else -> sb.append(c)
            }
            sb.append('"')
        }
        private const val HEX = "0123456789abcdef"
    }
}

/**
 * A JSONL file written as gzip members: lines gather in memory ([add]) and each [flush] appends one complete gzip
 * member to the file. Concatenated members are one valid gzip stream (`zcat`, Java's GZIPInputStream, Python's gzip
 * all read them), and a process killed between flushes loses only the lines since the last one, never the file.
 */
class GzipJsonl(val file: File, private val level: Int = Deflater.BEST_COMPRESSION) {
    private val pending = ByteArrayOutputStream(64 * 1024)
    private var bytes = ByteArray(4096)
    /** Bytes written to the file so far (compressed), and lines added. */
    var written = file.length(); private set
    var lines = 0L; private set

    /** Adds [line] (one JSON object and its newline), encoded as UTF-8 into a reused array. */
    @Synchronized
    fun add(line: CharSequence) {
        var n = 0
        for (i in 0 until line.length) {
            val c = line[i]
            if (n + 4 > bytes.size) bytes = bytes.copyOf(bytes.size * 2)
            val code = c.code
            when {
                code < 0x80 -> bytes[n++] = code.toByte()
                code < 0x800 -> { bytes[n++] = (0xC0 or (code shr 6)).toByte(); bytes[n++] = (0x80 or (code and 0x3F)).toByte() }
                Character.isHighSurrogate(c) && i + 1 < line.length && Character.isLowSurrogate(line[i + 1]) -> Unit
                Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(line[i - 1]) -> {
                    val cp = Character.toCodePoint(line[i - 1], c)
                    bytes[n++] = (0xF0 or (cp shr 18)).toByte(); bytes[n++] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
                    bytes[n++] = (0x80 or ((cp shr 6) and 0x3F)).toByte(); bytes[n++] = (0x80 or (cp and 0x3F)).toByte()
                }
                else -> {
                    val cc = if (Character.isSurrogate(c)) 0xFFFD else code
                    bytes[n++] = (0xE0 or (cc shr 12)).toByte(); bytes[n++] = (0x80 or ((cc shr 6) and 0x3F)).toByte(); bytes[n++] = (0x80 or (cc and 0x3F)).toByte()
                }
            }
        }
        pending.write(bytes, 0, n)
        lines++
    }

    /** Uncompressed bytes waiting for the next [flush]. */
    @Synchronized fun waiting(): Int = pending.size()

    /** Appends what is waiting as one gzip member (nothing when nothing waits). Returns the compressed bytes written. */
    @Synchronized
    fun flush(): Int {
        if (pending.size() == 0) return 0
        val before = file.length()
        FileOutputStream(file, true).use { fo ->
            object : GZIPOutputStream(fo, 16 * 1024) { init { def.setLevel(level) } }.use { gz -> pending.writeTo(gz) }
        }
        pending.reset()
        written = file.length()
        return (written - before).toInt()
    }
}
