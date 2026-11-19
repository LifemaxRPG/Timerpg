package com.timerpg.street.dialogue

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What each person remembers of talking with the player: every exchange said out loud (the player's words and the
 * answer), with its vector (from the memory model, made once when it is stored). [recall] finds the ones that bear on what
 * is said now. Pure Kotlin (no Android), so the search is tested on the JVM; [MemoryService] runs the model and saves it.
 */
class NpcMemory {
    /**
     * One exchange. Also the player's conversation log (town-wide: [all]), which steers the overnight writer
     * ([Steer]): the [town] and [year] it was in, the [intent] (topic) answered, [how] the answer came ("ready": a line
     * written ahead; "live": the model wrote it then; "template"), and [q], the player's words alone as the memory model
     * reads a question (null without it). Stays on the phone (memories.json).
     */
    class Entry(val npc: String, val time: Long, val said: String, val answered: String, val vec: FloatArray,
                val town: String = "", val year: Int = 0, val intent: String = "", val how: String = "", val q: FloatArray? = null)

    private val entries = ArrayList<Entry>()
    val size: Int get() = synchronized(entries) { entries.size }
    fun countOf(npc: String): Int = synchronized(entries) { entries.count { it.npc == npc } }

    fun add(e: Entry) = synchronized(entries) {
        entries += e
        // a person keeps their most recent memories; the oldest go first (and the town's as a whole the same way)
        val mine = entries.filter { it.npc == e.npc }
        if (mine.size > PER_PERSON) entries.remove(mine.minByOrNull { it.time })
        if (entries.size > TOTAL) entries.remove(entries.minByOrNull { it.time })
    }

    /** Every exchange, every person (the town-wide log). */
    fun all(): List<Entry> = synchronized(entries) { entries.toList() }

    /**
     * What [npc] remembers that bears on [query] (a unit vector), best first: at most [k], each at least [minScore] alike;
     * the last exchange within [recentMs] of [now] always comes along (the conversation goes on from it).
     */
    fun recall(npc: String, query: FloatArray, now: Long, k: Int = 3, minScore: Float = MIN_SCORE, recentMs: Long = 10 * 60_000L): List<Entry> {
        val mine = synchronized(entries) { entries.filter { it.npc == npc } }
        if (mine.isEmpty()) return emptyList()
        val all = mine.map { it to dot(it.vec, query) }
        // with enough memories to compare, one that bears on the words stands out from the rest (measured with
        // Qwen3-Embedding-0.6B: real matches 2.0-3.0 standard deviations above the person's mean, small talk below 2);
        // with a few, a plain likeness floor
        val keep: (Float) -> Boolean = if (all.size >= 6) {
            val mean = all.sumOf { it.second.toDouble() } / all.size
            val sd = kotlin.math.sqrt(maxOf(1e-9, all.sumOf { (it.second - mean) * (it.second - mean) } / all.size))
            ({ s -> s >= minScore && (s - mean) / sd >= STANDS_OUT })
        } else ({ s -> s >= FEW_SCORE })
        val scored = all.filter { keep(it.second) }.sortedByDescending { it.second }.map { it.first }.take(k)
        val last = mine.maxByOrNull { it.time }?.takeIf { now - it.time <= recentMs }
        return if (last != null && last !in scored) (listOf(last) + scored).take(k) else scored
    }

    /** [npc]'s [k] memories most like [query], best first, with their likeness and how far (in standard deviations) each
     *  stands above the person's mean. */
    fun candidates(npc: String, query: FloatArray, k: Int = 4): List<Triple<Entry, Float, Float>> {
        val mine = synchronized(entries) { entries.filter { it.npc == npc } }
        if (mine.isEmpty()) return emptyList()
        val all = mine.map { it to dot(it.vec, query) }
        val mean = all.sumOf { it.second.toDouble() } / all.size
        val sd = kotlin.math.sqrt(maxOf(1e-9, all.sumOf { (it.second - mean) * (it.second - mean) } / all.size))
        return all.sortedByDescending { it.second }.take(k).map { Triple(it.first, it.second, ((it.second - mean) / sd).toFloat()) }
    }

    /** [npc]'s most recent exchange, if it was within [recentMs] of [now]. */
    fun last(npc: String, now: Long, recentMs: Long = 10 * 60_000L): Entry? =
        synchronized(entries) { entries.filter { it.npc == npc }.maxByOrNull { it.time } }?.takeIf { now - it.time <= recentMs }

    fun toJson(): String = synchronized(entries) {
        JSONArray(entries.map {
            JSONObject().put("npc", it.npc).put("t", it.time).put("said", it.said).put("answered", it.answered).put("v", pack(it.vec)).apply {
                if (it.town.isNotEmpty()) put("town", it.town)
                if (it.year != 0) put("year", it.year)
                if (it.intent.isNotEmpty()) put("intent", it.intent)
                if (it.how.isNotEmpty()) put("how", it.how)
                it.q?.let { q -> put("q", pack(q)) }
            }
        }).toString()
    }

    fun load(json: String) = synchronized(entries) {
        entries.clear()
        val a = JSONArray(json)
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            entries += Entry(o.getString("npc"), o.getLong("t"), o.getString("said"), o.getString("answered"), unpack(o.optString("v")),
                o.optString("town"), o.optInt("year"), o.optString("intent"), o.optString("how"), o.optString("q").takeIf { it.isNotEmpty() }?.let { unpack(it) })
        }
    }

    companion object {
        const val PER_PERSON = 200
        /** The whole town's exchanges kept (two 1024-float vectors each: about 20 MB of memories.json when full). */
        const val TOTAL = 2000
        const val MIN_SCORE = .3f
        const val STANDS_OUT = 2f
        const val FEW_SCORE = .45f

        fun dot(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]; return s }

        private fun pack(v: FloatArray): String {
            val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (x in v) bb.putFloat(x)
            return b64(bb.array())
        }

        private fun unpack(s: String): FloatArray {
            val bb = ByteBuffer.wrap(unb64(s)).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bb.remaining() / 4) { bb.float }
        }

        private const val A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        private fun b64(d: ByteArray): String = buildString {
            var i = 0
            while (i < d.size) {
                val n = minOf(3, d.size - i)
                val v = ((d[i].toInt() and 255) shl 16) or (if (n > 1) (d[i + 1].toInt() and 255) shl 8 else 0) or (if (n > 2) d[i + 2].toInt() and 255 else 0)
                for (j in 0 until 4) append(if (j <= n) A[(v shr (18 - 6 * j)) and 63] else '=')
                i += 3
            }
        }
        private fun unb64(s: String): ByteArray {
            val out = java.io.ByteArrayOutputStream(s.length * 3 / 4)
            var v = 0; var bits = 0
            for (c in s) {
                if (c == '=') break
                val x = A.indexOf(c); if (x < 0) continue
                v = (v shl 6) or x; bits += 6
                if (bits >= 8) { bits -= 8; out.write((v shr bits) and 255) }
            }
            return out.toByteArray()
        }

        /** The memories as lines for the fact sheet. */
        fun asFacts(m: List<Entry>): List<String> = m.map {
            "Earlier the player told you: \"${it.said}\" You answered: \"${it.answered}\""
        }
    }
}
