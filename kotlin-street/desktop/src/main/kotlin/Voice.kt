import com.timerpg.voice.KokoroTts
import com.timerpg.voice.KokoroVoice
import com.timerpg.voice.SupertonicStyle
import com.timerpg.voice.SupertonicTts
import com.timerpg.voice.wavBytes
import java.io.File

/** Speaks a few NPC lines with the Kotlin voice engines, to WAV files, and reports how fast (desktop check of the phone code). */
fun renderVoices(out: String) {
    val kdir = File(System.getProperty("kokoro") ?: "/tmp/claude-0/-home-user-Timerpg/f2eefd9e-16c9-5542-9daa-dcab8571e817/scratchpad/kokoro-dl")
    File(out).mkdirs()
    if (File(kdir, "onnx/model.onnx").exists()) {
        val t0 = System.nanoTime()
        val lex = KokoroTts.readLexicon(File("../app/src/main/assets/voices/kokoro_lexicon.tsv").bufferedReader().lineSequence())
        KokoroTts(File(kdir, "onnx/model.onnx"), File(kdir, "tokenizer.json").readText(), lex, threads = 4).use { tts ->
            println("kokoro loaded in ${(System.nanoTime() - t0) / 1_000_000} ms (lexicon ${lex.size} words)")
            fun v(n: String) = KokoroVoice.read(File(kdir, "voices/$n.bin").readBytes())
            val voices = listOf(
                "old-gruff-man" to KokoroVoice.blend(listOf(v("am_onyx") to .55f, v("bm_george") to .45f)),
                "young-warm-woman" to KokoroVoice.blend(listOf(v("af_heart") to .7f, v("af_bella") to .3f)),
                "shopkeeper" to KokoroVoice.blend(listOf(v("am_eric") to .6f, v("bm_daniel") to .4f)),
            )
            val lines = listOf("Hello there. Haven't seen you around.", "I run Holloway's Bakery. Business is good, and the bread sells out by noon.",
                "Keep this quiet, but Ray Vance owes money to half of Maplefield since 1987.", "The flibbertigibbetzz said hello.")
            for (l in lines) println("  phonemes: ${tts.phonemes(l) ?: "(cannot pronounce: goes to Supertonic)"}")
            for ((name, voice) in voices) for ((i, line) in lines.take(3).withIndex()) {
                tts.synthesize(line, voice)
                val s = System.nanoTime()
                val wav = tts.synthesize(line, voice) ?: continue
                val secs = (System.nanoTime() - s) / 1e9
                val audio = wav.size.toDouble() / tts.sampleRate
                File(out, "kokoro-$name-$i.wav").writeBytes(wavBytes(wav, tts.sampleRate))
                println("kokoro $name line$i: %.2f s audio in %.2f s (RTF %.2f)".format(audio, secs, secs / audio))
            }
        }
    }
    val dir = File(System.getProperty("supertonic") ?: "/tmp/tts-supertonic-models/supertonic3")
    File(out).mkdirs()
    val t0 = System.nanoTime()
    SupertonicTts(dir, threads = 4).use { tts ->
        println("supertonic loaded in ${(System.nanoTime() - t0) / 1_000_000} ms, ${tts.sampleRate} Hz")
        fun style(n: String) = SupertonicStyle.parse(File(dir, "voice_styles/$n.json").readText())
        val voices = listOf(
            "old-gruff-man" to SupertonicStyle.blend(listOf(style("M3") to .6f, style("M5") to .4f)),
            "young-warm-woman" to SupertonicStyle.blend(listOf(style("F1") to .7f, style("F3") to .3f)),
            "shopkeeper" to SupertonicStyle.blend(listOf(style("M2") to .5f, style("M1") to .5f)),
        )
        val lines = listOf("Hello there. Haven't seen you around.", "I run Holloway's Bakery. Business is good, and the bread sells out by noon.")
        for ((name, st) in voices) for ((i, line) in lines.withIndex()) {
            tts.synthesize(line, st, seed = 7)   // warm-up
            val s = System.nanoTime()
            val wav = tts.synthesize(line, st, seed = 7)
            val secs = (System.nanoTime() - s) / 1e9
            val audio = wav.size.toDouble() / tts.sampleRate
            File(out, "supertonic-$name-$i.wav").writeBytes(wavBytes(wav, tts.sampleRate))
            println("supertonic $name line$i: %.2f s audio in %.2f s (RTF %.2f)".format(audio, secs, secs / audio))
        }
    }
}
