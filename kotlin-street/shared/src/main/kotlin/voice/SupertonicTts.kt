package com.timerpg.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import kotlin.math.ceil

/**
 * A voice style for Supertonic: the text-to-latent style (1x50x256) and the duration style (1x8x16). Styles blend by
 * weighted average, which is how every NPC gets a voice of their own from the ten built-in ones.
 */
class SupertonicStyle(val ttl: FloatArray, val ttlDims: LongArray, val dp: FloatArray, val dpDims: LongArray) {
    companion object {
        /** A voice style file (voice_styles/F1.json ...): {"style_ttl": {"dims", "data"}, "style_dp": {...}}. */
        fun parse(json: String): SupertonicStyle {
            val o = org.json.JSONObject(json)
            fun part(key: String): Pair<FloatArray, LongArray> {
                val p = o.getJSONObject(key)
                val dims = p.getJSONArray("dims").let { a -> LongArray(a.length()) { a.getLong(it) } }
                val out = FloatArray(dims.fold(1L) { a, b -> a * b }.toInt())
                var n = 0
                fun walk(v: Any) {
                    if (v is org.json.JSONArray) for (i in 0 until v.length()) walk(v.get(i))
                    else out[n++] = (v as Number).toFloat()
                }
                walk(p.get("data"))
                require(n == out.size) { "style $key has ${n} values, expected ${out.size}" }
                return out to dims
            }
            val (ttl, td) = part("style_ttl")
            val (dp, dd) = part("style_dp")
            return SupertonicStyle(ttl, td, dp, dd)
        }

        /** The weighted average of [parts] (weights need not sum to one). */
        fun blend(parts: List<Pair<SupertonicStyle, Float>>): SupertonicStyle {
            require(parts.isNotEmpty())
            val total = parts.sumOf { it.second.toDouble() }.toFloat()
            val first = parts[0].first
            val ttl = FloatArray(first.ttl.size); val dp = FloatArray(first.dp.size)
            for ((s, w) in parts) {
                val k = w / total
                for (i in ttl.indices) ttl[i] += s.ttl[i] * k
                for (i in dp.indices) dp[i] += s.dp[i] * k
            }
            return SupertonicStyle(ttl, first.ttlDims, dp, first.dpDims)
        }
    }
}

/**
 * Supertonic text-to-speech (Supertone, ONNX), run with ONNX Runtime: the same code on the phone and on a computer.
 * It reads raw text (no phonemizer): the text's characters go through an index table, a duration predictor sets the
 * length, and a few flow-matching steps turn seeded noise into the speech latent, which the vocoder turns into sound.
 * [dir] is the model folder (onnx/ with the four models, tts.json and unicode_indexer.json).
 */
class SupertonicTts(dir: File, threads: Int = 4) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val onnx = File(dir, "onnx").takeIf { it.isDirectory } ?: dir
    private val cfg = org.json.JSONObject(File(onnx, "tts.json").readText())
    val sampleRate: Int = cfg.getJSONObject("ae").getInt("sample_rate")
    private val baseChunk = cfg.getJSONObject("ae").getInt("base_chunk_size")
    private val ccf = cfg.getJSONObject("ttl").getInt("chunk_compress_factor")
    private val ldim = cfg.getJSONObject("ttl").getInt("latent_dim")
    private val indexer: IntArray = org.json.JSONArray(File(onnx, "unicode_indexer.json").readText()).let { a -> IntArray(a.length()) { a.getInt(it) } }
    private val opts = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val dp = env.createSession(File(onnx, "duration_predictor.onnx").path, opts)
    private val enc = env.createSession(File(onnx, "text_encoder.onnx").path, opts)
    private val est = env.createSession(File(onnx, "vector_estimator.onnx").path, opts)
    private val voc = env.createSession(File(onnx, "vocoder.onnx").path, opts)

    /** The engine's own text clean-up (as the reference implementation): normalised, tidied, ending in punctuation. */
    fun prepare(text: String, lang: String? = "en"): String {
        var t = Normalizer.normalize(text, Normalizer.Form.NFKD)
        for ((a, b) in SYMBOLS) t = t.replace(a, b)
        t = t.replace(Regex("[♥☆♡©\\\\]"), "")
        t = t.replace("@", " at ").replace("e.g.,", "for example, ").replace("i.e.,", "that is, ")
        for (p in listOf(",", ".", "!", "?", ";", ":", "'")) t = t.replace(" $p", p)
        t = t.replace(Regex("([\"'`])\\1+"), "$1")
        t = t.replace(Regex("\\s+"), " ").trim()
        if (!Regex("[.!?;:,'\"')\\]}…。」』】〉》›»]$").containsMatchIn(t)) t += "."
        return if (lang != null) "<$lang>$t</$lang>" else t
    }

    /**
     * Speech for [text] in [style]: mono float samples at [sampleRate]. [seed] fixes the noise, so the same line in the
     * same voice always comes out the same (and can be cached). [steps] trades quality for time (the default is 8).
     */
    fun synthesize(text: String, style: SupertonicStyle, speed: Float = 1.05f, steps: Int = 8, seed: Long = 1L, lang: String? = "en"): FloatArray {
        val prepared = prepare(text, lang)
        val cps = prepared.codePoints().toArray()
        val n = cps.size
        val ids = LongArray(n) { i -> val c = cps[i]; (if (c in indexer.indices) indexer[c] else -1).let { if (it < 0) 0 else it }.toLong() }
        val textIds = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, n.toLong()))
        val textMask = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(n) { 1f }), longArrayOf(1, 1, n.toLong()))
        val styleTtl = OnnxTensor.createTensor(env, FloatBuffer.wrap(style.ttl), style.ttlDims)
        val styleDp = OnnxTensor.createTensor(env, FloatBuffer.wrap(style.dp), style.dpDims)
        try {
            val duration = dp.run(mapOf("text_ids" to textIds, "style_dp" to styleDp, "text_mask" to textMask)).use { r ->
                (r.get(0) as OnnxTensor).floatBuffer.get(0) / speed
            }
            val textEmb = enc.run(mapOf("text_ids" to textIds, "style_ttl" to styleTtl, "text_mask" to textMask))
            val embTensor = textEmb.get(0) as OnnxTensor
            // the noisy latent: (1, latent_dim * compress, length), zero past the speech's own length
            val wavLen = (duration * sampleRate).toLong()
            val chunk = baseChunk * ccf
            val latentLen = ((wavLen + chunk - 1) / chunk).toInt().coerceAtLeast(1)
            val dim = ldim * ccf
            val rnd = java.util.Random(seed)
            var latent = FloatArray(dim * latentLen) { rnd.nextGaussian().toFloat() }
            val mask = FloatArray(latentLen) { 1f }
            val latentMask = OnnxTensor.createTensor(env, FloatBuffer.wrap(mask), longArrayOf(1, 1, latentLen.toLong()))
            val total = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(steps.toFloat())), longArrayOf(1))
            try {
                for (step in 0 until steps) {
                    val xt = OnnxTensor.createTensor(env, FloatBuffer.wrap(latent), longArrayOf(1, dim.toLong(), latentLen.toLong()))
                    val cur = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(step.toFloat())), longArrayOf(1))
                    est.run(mapOf("noisy_latent" to xt, "text_emb" to embTensor, "style_ttl" to styleTtl, "text_mask" to textMask,
                        "latent_mask" to latentMask, "current_step" to cur, "total_step" to total)).use { r ->
                        val fb = (r.get(0) as OnnxTensor).floatBuffer
                        latent = FloatArray(fb.remaining()).also { fb.get(it) }
                    }
                    xt.close(); cur.close()
                }
            } finally { latentMask.close(); total.close(); textEmb.close() }
            val lat = OnnxTensor.createTensor(env, FloatBuffer.wrap(latent), longArrayOf(1, dim.toLong(), latentLen.toLong()))
            return lat.use {
                voc.run(mapOf("latent" to lat)).use { r ->
                    val fb = (r.get(0) as OnnxTensor).floatBuffer
                    val all = FloatArray(fb.remaining()).also { fb.get(it) }
                    // the vocoder fills whole chunks: keep the speech's own length
                    if (wavLen in 1 until all.size) all.copyOf(wavLen.toInt()) else all
                }
            }
        } finally { textIds.close(); textMask.close(); styleTtl.close(); styleDp.close() }
    }

    override fun close() { dp.close(); enc.close(); est.close(); voc.close(); opts.close() }

    private companion object {
        val SYMBOLS = listOf(
            "–" to "-", "‑" to "-", "—" to "-", "¯" to " ", "_" to " ", "“" to "\"", "”" to "\"",
            "‘" to "'", "’" to "'", "´" to "'", "`" to "'", "[" to " ", "]" to " ", "|" to " ", "/" to " ",
            "#" to " ", "→" to " ", "←" to " ",
        )
    }
}

/** 16-bit PCM WAV bytes for mono [samples] at [rate] (for saving lines and for the cache). */
fun wavBytes(samples: FloatArray, rate: Int): ByteArray {
    val n = samples.size
    val b = java.nio.ByteBuffer.allocate(44 + n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    b.put("RIFF".toByteArray()); b.putInt(36 + n * 2); b.put("WAVE".toByteArray())
    b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
    b.put("data".toByteArray()); b.putInt(n * 2)
    for (s in samples) b.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
    return b.array()
}

private fun ceilDiv(a: Long, b: Long) = ceil(a.toDouble() / b).toLong()
