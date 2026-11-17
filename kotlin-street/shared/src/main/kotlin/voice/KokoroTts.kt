package com.timerpg.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * A Kokoro voice: one 256-number style per phoneme count (510 of them). Voices blend by weighted average, so every NPC
 * can have a voice of their own from the built-in ones (af_heart, am_onyx, bf_alice, ...).
 */
class KokoroVoice(val styles: FloatArray) {
    val rows: Int get() = styles.size / DIM

    /** The style for a line of [n] phonemes (row n - 1, as the reference implementation picks it). */
    fun styleFor(n: Int): FloatArray {
        val r = (n.coerceIn(1, rows) - 1) * DIM
        return styles.copyOfRange(r, r + DIM)
    }

    companion object {
        const val DIM = 256

        /** A voice file (voices/af_heart.bin): raw little-endian float32, 510 x 1 x 256. */
        fun read(bytes: ByteArray): KokoroVoice {
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return KokoroVoice(FloatArray(fb.remaining()).also { fb.get(it) })
        }

        fun blend(parts: List<Pair<KokoroVoice, Float>>): KokoroVoice {
            require(parts.isNotEmpty())
            val total = parts.sumOf { it.second.toDouble() }.toFloat()
            val out = FloatArray(parts[0].first.styles.size)
            for ((v, w) in parts) { val k = w / total; for (i in out.indices) out[i] += v.styles[i] * k }
            return KokoroVoice(out)
        }
    }
}

/**
 * Kokoro-82M (ONNX) with a pronunciation lexicon instead of a phonemizer: the words come from Kokoro's own training
 * dictionaries plus every name and word our game can say (tools/make_kokoro_lexicon.py), so nothing GPL runs on the
 * phone. [phonemes] returns null for a line with a word it cannot pronounce; the game gives such a line to Supertonic.
 */
class KokoroTts(model: File, vocabJson: String, private val lexicon: Map<String, String>, threads: Int = 4) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val session = env.createSession(model.path, opts)
    private val tokenInput = if ("input_ids" in session.inputNames) "input_ids" else "tokens"
    private val vocab: Map<Char, Long> = org.json.JSONObject(vocabJson).let { o ->
        // tokenizer.json ({"model": {"vocab": ...}}) or a config with a top-level vocab
        val v = when { o.has("model") && o.getJSONObject("model").has("vocab") -> o.getJSONObject("model").getJSONObject("vocab"); o.has("vocab") -> o.getJSONObject("vocab"); else -> o }
        v.keys().asSequence().filter { it.length == 1 }.associate { it[0] to v.getLong(it) }
    }
    val sampleRate = 24000

    /** The line as Kokoro phonemes (misaki's alphabet), or null if any word is not in the lexicon. */
    fun phonemes(text: String): String? {
        val t = text.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"').replace("--", "—")
        val sb = StringBuilder()
        for (m in TOKEN.findAll(t)) {
            val tok = m.value
            if (tok[0].isLetter()) {
                val ps = word(tok) ?: return null
                if (sb.isNotEmpty() && !sb.endsWith(" ") && !sb.endsWith("\"") && !sb.endsWith("(")) sb.append(' ')
                sb.append(ps)
            } else if (tok[0].isDigit()) {
                for (w in numberWords(tok.toLongOrNull() ?: return null)) {
                    if (sb.isNotEmpty() && !sb.endsWith(" ")) sb.append(' ')
                    sb.append(word(w) ?: return null)
                }
            } else {
                // punctuation sits on the word before it, then a space
                while (sb.endsWith(" ")) sb.setLength(sb.length - 1)
                sb.append(tok)
                if (tok != "(" && tok != "\"") sb.append(' ')
            }
        }
        return sb.toString().trim()
    }

    /** One word's phonemes: the lexicon, or a word built from one in it ('s, plurals, -ed, -ing, compounds). */
    private fun word(w: String): String? {
        val k = w.lowercase()
        lexicon[k]?.let { return it }
        if (k.endsWith("'s")) return word(k.dropLast(2))?.let { it + sEnding(it) }
        if (k.endsWith("s'")) return word(k.dropLast(1))
        if (k.endsWith("s") && k.length > 3) word(k.dropLast(1))?.let { return it + sEnding(it) }
        if (k.endsWith("es") && k.length > 4) word(k.dropLast(2))?.let { return it + "ᵻz" }
        if (k.endsWith("ed") && k.length > 4) (word(k.dropLast(2)) ?: word(k.dropLast(1)))?.let { b -> return b + edEnding(b) }
        if (k.endsWith("ing") && k.length > 5) (word(k.dropLast(3)) ?: word(k.dropLast(3) + "e"))?.let { return it + "ɪŋ" }
        // a compound of two known words ("maplefield", "underhill")
        for (i in k.length - 3 downTo 3) {
            val a = lexicon[k.substring(0, i)] ?: continue
            val b = lexicon[k.substring(i)] ?: continue
            return a + b.replace("ˈ", "ˌ")
        }
        return null
    }

    private fun sEnding(ps: String): String {
        val last = ps.trimEnd('ˈ', 'ˌ').lastOrNull() ?: return "z"
        return when (last) { 's', 'z', 'ʃ', 'ʒ', 'ʧ', 'ʤ' -> "ᵻz"; 'p', 't', 'k', 'f', 'θ' -> "s"; else -> "z" }
    }

    private fun edEnding(ps: String): String {
        val last = ps.lastOrNull() ?: return "d"
        return when (last) { 't', 'd' -> "ᵻd"; 'p', 'k', 'f', 's', 'ʃ', 'ʧ', 'θ' -> "t"; else -> "d" }
    }

    /** Speech for [text] in [voice] (mono float at 24 kHz), or null if a word cannot be pronounced. */
    fun synthesize(text: String, voice: KokoroVoice, speed: Float = 1f): FloatArray? {
        val ps = phonemes(text) ?: return null
        val ids = ps.mapNotNull { vocab[it] }
        if (ids.isEmpty()) return null
        val n = ids.size.coerceAtMost(510)
        val input = LongArray(n + 2).also { a -> for (i in 0 until n) a[i + 1] = ids[i] }
        val tokens = OnnxTensor.createTensor(env, LongBuffer.wrap(input), longArrayOf(1, (n + 2).toLong()))
        val style = OnnxTensor.createTensor(env, FloatBuffer.wrap(voice.styleFor(n)), longArrayOf(1, KokoroVoice.DIM.toLong()))
        val sp = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1))
        try {
            session.run(mapOf(tokenInput to tokens, "style" to style, "speed" to sp)).use { r ->
                val fb = (r.get(0) as OnnxTensor).floatBuffer
                return FloatArray(fb.remaining()).also { fb.get(it) }
            }
        } finally { tokens.close(); style.close(); sp.close() }
    }

    override fun close() { session.close(); opts.close() }

    companion object {
        private val TOKEN = Regex("[A-Za-z]+(?:'[A-Za-z]+)*'?|\\d+|[.,!?;:—…\"()]")

        /** The lexicon file: one "word<TAB>phonemes" per line. */
        fun readLexicon(lines: Sequence<String>): Map<String, String> {
            val m = HashMap<String, String>(200_000)
            for (l in lines) { val t = l.indexOf('\t'); if (t > 0) m[l.substring(0, t)] = l.substring(t + 1) }
            return m
        }

        private val ONES = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
            "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
        private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

        /** A number as words ("1987" as a year: nineteen eighty-seven). */
        fun numberWords(n: Long): List<String> {
            fun under100(x: Int): List<String> = if (x < 20) listOf(ONES[x]) else listOfNotNull(TENS[x / 10], if (x % 10 != 0) ONES[x % 10] else null)
            fun under1000(x: Int): List<String> = (if (x >= 100) listOf(ONES[x / 100], "hundred") else emptyList()) + (if (x % 100 != 0 || x == 0) under100(x % 100) else emptyList())
            if (n in 1100..1999 || n in 2010..2099) {           // years read in pairs
                val hi = (n / 100).toInt(); val lo = (n % 100).toInt()
                return under100(hi) + if (lo == 0) listOf("hundred") else if (lo < 10) listOf("oh", ONES[lo]) else under100(lo)
            }
            if (n < 1000) return under1000(n.toInt())
            if (n < 1_000_000) return under1000((n / 1000).toInt()) + "thousand" + (if (n % 1000 != 0L) under1000((n % 1000).toInt()) else emptyList())
            return listOf("a", "lot")
        }
    }
}
