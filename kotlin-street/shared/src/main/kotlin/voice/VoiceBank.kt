package com.timerpg.voice

import java.io.File
import java.security.MessageDigest

/**
 * The NPC voices: both engines, loaded on first use from [models] (kokoro/{model.onnx, tokenizer.json, voices/NAME.bin} and
 * supertonic/{onnx/..., voice_styles/NAME.json}), every line cached in [cache] as a WAV named by its voice and text, so a
 * line is only ever made once. [lexicon] reads Kokoro's pronunciation lexicon (an app asset). Not thread-safe: the game
 * owns it from one worker thread.
 */
class VoiceBank(private val models: File, private val cache: File, private val lexicon: () -> Sequence<String>, private val threads: Int = 4) : AutoCloseable {
    companion object {
        /** Run the voice models on ONNX Runtime's XNNPACK kernels (set before a model loads). */
        @Volatile var xnnpack = false
        /** Which engines may speak: 0 both (each person gets one from who they are), 1 Kokoro only, 2 Supertonic only. */
        @Volatile var only = 0
    }

    private var kokoro: KokoroTts? = null
    private var supertonic: SupertonicTts? = null
    private val kokoroVoices = HashMap<String, KokoroVoice>()
    private val supertonicStyles = HashMap<String, SupertonicStyle>()

    val kokoroInstalled: Boolean get() = only != 2 && File(models, "kokoro/model.onnx").isFile && File(models, "kokoro/tokenizer.json").isFile
    val supertonicInstalled: Boolean get() = only != 1 && File(models, "supertonic/onnx/vector_estimator.onnx").isFile

    /** The cached file for [text] in [voice] (it may not exist yet). */
    fun cached(voice: NpcVoices.Voice, text: String): File = File(cache, digest(voice.key + "\u0000" + text) + ".wav")

    /**
     * [text] spoken in [voice]: the cached WAV, made now if it is not there yet (seconds of work: call it off the main
     * thread). Null if no engine can speak it. A Kokoro voice that cannot pronounce a word hands the line to Supertonic.
     */
    fun speak(voice: NpcVoices.Voice, text: String): File? {
        val out = cached(voice, text)
        if (out.isFile && out.length() > 44) return out
        val (samples, rate) = synth(voice, text) ?: return null
        cache.mkdirs()
        val tmp = File(out.path + ".part")
        tmp.writeBytes(wavBytes(samples, rate))
        if (!tmp.renameTo(out)) { tmp.delete(); return null }
        return out
    }

    private fun synth(voice: NpcVoices.Voice, text: String): Pair<FloatArray, Int>? {
        if (voice.engine == NpcVoices.Engine.KOKORO && kokoroInstalled) {
            val tts = kokoro ?: KokoroTts(File(models, "kokoro/model.onnx"), File(models, "kokoro/tokenizer.json").readText(), KokoroTts.readLexicon(lexicon()), threads).also { kokoro = it }
            val v = KokoroVoice.blend(voice.blend.mapNotNull { (n, w) -> kokoroVoice(n)?.let { it to w } }.ifEmpty { return null })
            tts.synthesize(text, v, voice.speed)?.let { return it to tts.sampleRate }
            // a word Kokoro cannot say: the same person in Supertonic's nearest voice
            val female = voice.blend.first().first.let { it.startsWith("af") || it.startsWith("bf") }
            val st = NpcVoices.Voice(NpcVoices.Engine.SUPERTONIC, listOf((if (female) "F2" else "M2") to 1f), voice.speed)
            return synth(st, text)
        }
        if (supertonicInstalled) {
            val tts = supertonic ?: SupertonicTts(File(models, "supertonic"), threads).also { supertonic = it }
            val names = if (voice.engine == NpcVoices.Engine.SUPERTONIC) voice.blend else listOf("M2" to 1f)
            val st = SupertonicStyle.blend(names.mapNotNull { (n, w) -> supertonicStyle(n)?.let { it to w } }.ifEmpty { return null })
            // the noise seed from the line, so it always comes out the same
            val seed = digest(voice.key + text).take(12).toLong(16)
            return tts.synthesize(text, st, speed = (voice.speed * 1.05f).coerceIn(.7f, 2f), seed = seed) to tts.sampleRate
        }
        return null
    }

    private fun kokoroVoice(name: String): KokoroVoice? {
        kokoroVoices[name]?.let { return it }
        val f = File(models, "kokoro/voices/$name.bin")
        if (!f.isFile) return null
        return KokoroVoice.read(f.readBytes()).also { kokoroVoices[name] = it }
    }

    private fun supertonicStyle(name: String): SupertonicStyle? {
        supertonicStyles[name]?.let { return it }
        val f = File(models, "supertonic/voice_styles/$name.json")
        if (!f.isFile) return null
        return SupertonicStyle.parse(f.readText()).also { supertonicStyles[name] = it }
    }

    /** Whether [engine]'s model is in memory now (else the next line in it loads it first). */
    fun isLoaded(engine: NpcVoices.Engine): Boolean = if (engine == NpcVoices.Engine.KOKORO) kokoro != null else supertonic != null

    /** Lets go of the engine [voice] does not use (memory is short: one engine at a time). */
    fun keepOnly(voice: NpcVoices.Voice) {
        if (voice.engine == NpcVoices.Engine.KOKORO) { supertonic?.close(); supertonic = null } else { kokoro?.close(); kokoro = null }
    }

    override fun close() { kokoro?.close(); supertonic?.close(); kokoro = null; supertonic = null }

    private fun digest(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
}
