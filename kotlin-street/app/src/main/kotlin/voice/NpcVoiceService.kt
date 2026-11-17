package com.timerpg.street.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.timerpg.street.dialogue.ModelDownloads
import com.timerpg.street.dialogue.Npc
import com.timerpg.voice.NpcVoices
import com.timerpg.voice.VoiceBank
import java.io.File
import java.util.concurrent.Executors

/**
 * NPC voices on the phone: each person's voice (NpcVoices, from who they are), every line made once by Kokoro or Supertonic
 * (VoiceBank, its own worker thread) and cached as a WAV, played with the system's media player. Also fetches the models
 * (ModelDownloads) the first time. All heavy work is off the main thread; the Compose state here is set on the main thread.
 */
class NpcVoiceService(private val ctx: Context) {
    private val models = File(ctx.filesDir, "models")
    private val bank = VoiceBank(models, File(ctx.filesDir, "voice-cache"),
        { ctx.assets.open("voices/kokoro_lexicon.tsv").bufferedReader().lineSequence() }, threads = 6)  // the dialogue model is on the NPU: the CPU's big cores are the voices'
    init {
        val p = ctx.getSharedPreferences("voices", Context.MODE_PRIVATE)
        com.timerpg.voice.VoiceBank.xnnpack = p.getBoolean("xnnpack", false)
        com.timerpg.voice.VoiceBank.only = p.getInt("only", 0)
    }
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "npc-voices").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    @Volatile private var generation = 0
    // the voice engines (loaded whole into RAM) are let go when nothing has been spoken or made ahead for ten minutes
    private val idle = Runnable { worker.execute { bank.close() } }
    private fun touch() { main.removeCallbacks(idle); main.postDelayed(idle, 600_000L) }

    /** Told when an NPC starts and stops speaking (the street's sound dips under the voice). */
    var onSpeaking: ((Boolean) -> Unit)? = null

    /** The voice models on ONNX Runtime's XNNPACK kernels instead of its own CPU kernels (compare with the timing). */
    var xnnpack: Boolean
        get() = ctx.getSharedPreferences("voices", Context.MODE_PRIVATE).getBoolean("xnnpack", false)
        set(on) {
            ctx.getSharedPreferences("voices", Context.MODE_PRIVATE).edit().putBoolean("xnnpack", on).apply()
            com.timerpg.voice.VoiceBank.xnnpack = on
            worker.execute { bank.close() }   // loaded again the new way on the next line
        }

    /** Which voice engines speak: 0 both, 1 Kokoro only, 2 Supertonic only (for comparing them). */
    var only: Int
        get() = ctx.getSharedPreferences("voices", Context.MODE_PRIVATE).getInt("only", 0)
        set(v) {
            ctx.getSharedPreferences("voices", Context.MODE_PRIVATE).edit().putInt("only", v).apply()
            com.timerpg.voice.VoiceBank.only = v
            stop()
            worker.execute { bank.close() }
        }

    /** Voices on or off (the player's choice). */
    var enabled by mutableStateOf(true)
    /** Downloading the models: 0..1, or -1 when not downloading. */
    var downloading by mutableFloatStateOf(-1f)
    var downloadNote by mutableStateOf("")
    @Volatile private var cancelDownload = false

    val installed: Boolean get() = bank.kokoroInstalled || bank.supertonicInstalled
    val missingMb: Long get() = ModelDownloads.missing(ctx).sumOf { if (it.bytes > 0) it.bytes else 300_000L } shr 20

    /** The last line's timing, for the AI panel: how long its first sound took and how fast the voice was made. */
    var lastTiming by mutableStateOf<String?>(null)
        private set

    // a line's first sentence spoken before the line is finished (see [sayFirst])
    private var early: Pair<String, String>? = null   // npc id, the sentence

    /**
     * The model's first sentence of a line still being written, already checked: spoken now, so the person starts talking
     * before the rest is written. [say] with the finished line then goes on after it (or starts over if it changed).
     */
    fun sayFirst(npc: Npc, sentence: String) {
        say(npc, sentence)
        early = npc.id to sentence.trim()
    }

    /**
     * Speak [text] as [npc] (replacing whatever is playing). Quietly nothing if voices are off or not installed. Made a
     * sentence at a time: the first plays as soon as it is ready while the next ones are made, so the person starts
     * talking after one sentence's work, not the whole line's.
     */
    fun say(npc: Npc, text: String) {
        val e = early
        early = null
        if (e != null && e.first == npc.id && text.trim().startsWith(e.second) && text.trim().length > e.second.length) {
            // the first sentence is already being spoken: the rest after it, in the same turn
            continueWith(npc, text.trim().substring(e.second.length).trim())
            return
        }
        if (e != null && e.first == npc.id && text.trim() == e.second) return
        stop()
        if (!enabled || text.isBlank()) return
        touch()
        val g = generation
        val t0 = android.os.SystemClock.elapsedRealtime()
        worker.execute {
            if (g != generation) return@execute
            val v = NpcVoices.assign(npc.id, npc.female, npc.age, npc.warmth, bank.kokoroInstalled, bank.supertonicInstalled) ?: return@execute
            // memory short (the dialogue model on the NPU): one voice engine at a time
            if (com.timerpg.street.llm.Ram.freeMb(ctx) < 1100) bank.keepOnly(v)
            val wasLoaded = bank.isLoaded(v.engine)
            var first = -1L; var made = 0L; var audio = 0.0
            for (part in sentences(text)) {
                if (g != generation) return@execute
                val a = android.os.SystemClock.elapsedRealtime()
                val f = try { com.timerpg.street.llm.AiLane.run("voice") { bank.speak(v, part) } } catch (e: Throwable) { null } ?: continue
                made += android.os.SystemClock.elapsedRealtime() - a
                audio += (f.length() - 44).coerceAtLeast(0) / 2.0 / wavRate(f)
                if (first < 0) first = android.os.SystemClock.elapsedRealtime() - t0
                main.post { if (g == generation) enqueue(f) }
            }
            val note = "%s: first sound after %.1f s%s · %.1f s of speech made in %.1f s".format(
                v.engine.name.lowercase().replaceFirstChar { it.uppercase() }, first / 1000.0,
                if (wasLoaded) "" else " (the voice model loaded first)", audio, made / 1000.0)
            main.post { lastTiming = note }
        }
    }

    /** The rest of a line whose first sentence is playing: made and queued behind it. */
    private fun continueWith(npc: Npc, rest: String) {
        if (!enabled || rest.isBlank()) return
        val g = generation
        worker.execute {
            if (g != generation) return@execute
            val v = NpcVoices.assign(npc.id, npc.female, npc.age, npc.warmth, bank.kokoroInstalled, bank.supertonicInstalled) ?: return@execute
            for (part in sentences(rest)) {
                if (g != generation) return@execute
                val f = try { com.timerpg.street.llm.AiLane.run("voice") { bank.speak(v, part) } } catch (e: Throwable) { null } ?: continue
                main.post { if (g == generation) enqueue(f) }
            }
        }
    }

    /** Make [text] in [npc]'s voice now (no playback), so it is ready when asked: the voices' own pre-generation. */
    fun prepare(npc: Npc, text: String) {
        if (!enabled || text.isBlank() || !installed) return
        touch()
        worker.execute {
            val v = NpcVoices.assign(npc.id, npc.female, npc.age, npc.warmth, bank.kokoroInstalled, bank.supertonicInstalled) ?: return@execute
            // made ahead when the voice is loaded, or there is room to load it
            if (!bank.isLoaded(v.engine) && com.timerpg.street.llm.Ram.freeMb(ctx) < 1300) return@execute
            for (part in sentences(text)) try { com.timerpg.street.llm.AiLane.run("voice") { bank.speak(v, part) } } catch (e: Throwable) { }
        }
    }

    /**
     * A line in the pieces it is spoken in: its sentences, a short one joined to the next, and the first cut at a comma
     * when it is long (the first piece is what the player waits for).
     */
    private fun sentences(text: String): List<String> {
        val raw = Regex("""(?<=[.!?…])["')\]]?\s+""").split(text.trim()).map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        // the first sentence stays on its own (it is what the player waits for); a very short later one joins the one before
        for (s in raw) if (out.size >= 2 && s.length < 16) out[out.size - 1] = out.last() + " " + s else out += s
        if (out.isNotEmpty() && out[0].length > 90) {
            val c = out[0].indexOf(", ", 30)
            if (c in 30..80) { val head = out[0].substring(0, c + 1); val rest = out[0].substring(c + 2); out[0] = head; out.add(1, rest) }
        }
        return out
    }

    /** A WAV file's sample rate (its header). */
    private fun wavRate(f: File): Double = try {
        java.io.RandomAccessFile(f, "r").use { r -> r.seek(24); val b = ByteArray(4); r.readFully(b)
            ((b[0].toInt() and 255) or ((b[1].toInt() and 255) shl 8) or ((b[2].toInt() and 255) shl 16) or ((b[3].toInt() and 255) shl 24)).toDouble() }
    } catch (e: Exception) { 24000.0 }

    private val queue = ArrayDeque<File>()

    private fun enqueue(f: File) { if (player == null) play(f) else queue.addLast(f) }

    private fun play(f: File) {
        player?.release()
        player = try {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(f.path)
                setOnCompletionListener {
                    it.release()
                    if (player === it) {
                        player = null
                        val next = queue.removeFirstOrNull()
                        if (next != null) play(next) else onSpeaking?.invoke(false)
                    }
                }
                prepare()
                start()
                onSpeaking?.invoke(true)
            }
        } catch (e: Exception) { null }
    }

    fun stop() {
        early = null
        if (player != null) onSpeaking?.invoke(false)
        generation++
        queue.clear()
        player?.let { try { it.stop() } catch (e: Exception) { }; it.release() }
        player = null
    }

    /** Fetch every model (dialogue, voices, speech, memory), resuming a stopped download. */
    fun download(onDone: () -> Unit) {
        if (downloading >= 0f) return
        cancelDownload = false
        downloading = 0f; downloadNote = "Starting…"
        Thread({
            val r = ModelDownloads.download(ctx, { cancelDownload }) { done, total ->
                main.post { downloading = done.toFloat() / total; downloadNote = "${done shr 20} of ${total shr 20} MB" }
            }
            main.post {
                downloading = -1f
                downloadNote = when (r) { is ModelDownloads.Result.Done -> "Everything is installed."; is ModelDownloads.Result.Failed -> r.reason }
                if (r is ModelDownloads.Result.Done) onDone()
            }
        }, "model-download").start()
    }

    fun cancelDownload() { cancelDownload = true }

    fun close() { stop(); worker.execute { bank.close() }; worker.shutdown() }
}
