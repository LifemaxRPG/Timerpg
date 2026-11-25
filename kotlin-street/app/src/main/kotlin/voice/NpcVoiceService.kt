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
        // the memory breakdown; let go (unless one speaks) for the dialogue model's copy when it is short of room
        com.timerpg.street.llm.ModelRoom.register(com.timerpg.street.llm.ModelRoom.Resident("voices",
            mb = { residentMb }, drop = { if (speaking) 0L else residentMb.also { if (it > 0) workerExecute { bank.close() } } }, note = { residentWhich }))
        val p = ctx.getSharedPreferences("voices", Context.MODE_PRIVATE)
        com.timerpg.voice.VoiceBank.xnnpack = p.getBoolean("xnnpack", false)
        com.timerpg.voice.VoiceBank.only = p.getInt("only", 0)
    }
    /**
     * One thread for the voices, its work by priority: what someone is about to say ([LIVE]) before their fillers
     * ([FILLER]) before lines made ahead ([AHEAD]); first come first served within each.
     */
    private class Job(val prio: Int, val seq: Long, val block: () -> Unit) : Runnable, Comparable<Job> {
        override fun run() = block()
        override fun compareTo(other: Job) = compareValuesBy(this, other, { it.prio }, { it.seq })
    }
    private val pool = java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.PriorityBlockingQueue<Runnable>()) { r -> Thread(r, "npc-voices").apply { isDaemon = true } }
    private val seq = java.util.concurrent.atomic.AtomicLong()
    private fun run(prio: Int, block: () -> Unit) = pool.execute(Job(prio, seq.incrementAndGet(), block))
    private fun workerExecute(block: () -> Unit) = run(LIVE, block)
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    @Volatile private var generation = 0
    // the voice engines (loaded whole into RAM) are let go when nothing has been spoken or made ahead for ten minutes
    private val idle = Runnable { workerExecute { bank.close() } }
    private fun touch() {
        if (!enabled) return
        main.removeCallbacks(idle); main.postDelayed(idle, 600_000L)
    }

    /** Told when an NPC starts and stops speaking (the street's sound dips under the voice). */
    var onSpeaking: ((Boolean) -> Unit)? = null

    /** The voice models on ONNX Runtime's XNNPACK kernels instead of its own CPU kernels (compare with the timing). */
    var xnnpack: Boolean
        get() = ctx.getSharedPreferences("voices", Context.MODE_PRIVATE).getBoolean("xnnpack", false)
        set(on) {
            ctx.getSharedPreferences("voices", Context.MODE_PRIVATE).edit().putBoolean("xnnpack", on).apply()
            com.timerpg.voice.VoiceBank.xnnpack = on
            workerExecute { bank.close() }   // loaded again the new way on the next line
        }

    /** Which voice engines speak: 0 both, 1 Kokoro only, 2 Supertonic only (for comparing them). */
    var only: Int
        get() = ctx.getSharedPreferences("voices", Context.MODE_PRIVATE).getInt("only", 0)
        set(v) {
            ctx.getSharedPreferences("voices", Context.MODE_PRIVATE).edit().putInt("only", v).apply()
            com.timerpg.voice.VoiceBank.only = v
            stop()
            workerExecute { bank.close() }
        }

    /**
     * Voices on or off (the player's choice, kept; off until switched on). Off, nothing of the voices runs or stays in
     * memory: no line is voiced, ahead or live, the work queued is dropped and both voice engines are let go.
     */
    var enabled by mutableStateOf(ctx.getSharedPreferences("voices", Context.MODE_PRIVATE).getBoolean("enabled", false))
        private set

    /** Switches the voices on or off (kept for the next start); off frees them at once. */
    fun switchTo(on: Boolean) {
        if (on == enabled) return
        enabled = on
        ctx.getSharedPreferences("voices", Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply()
        if (!on) unload()
    }

    /** Off: whatever plays stops, queued work (lines made ahead, fillers) is dropped, and the engines are closed. */
    private fun unload() {
        stop()
        fillerPrep++
        main.removeCallbacks(idle)
        pool.queue.clear()
        workerExecute { bank.close() }
    }

    /**
     * One line in [v]'s voice (the voices' thread): from the cache, else made, with only [v]'s engine in memory (in play
     * the other one is let go first: the two voice engines are never both resident beside the dialogue model and speech).
     */
    private fun speakOne(v: NpcVoices.Voice, text: String): File? {
        if (!bank.cached(v, text).isFile && !bank.isLoaded(v.engine)) bank.keepOnly(v)
        return bank.speak(v, text)
    }

    /** The voice engines in memory now and about how much they hold (MB), for the memory breakdown. */
    val residentMb: Long get() =
        (if (bank.isLoaded(NpcVoices.Engine.KOKORO)) File(models, "kokoro/model.onnx").length() shr 20 else 0L) +
        (if (bank.isLoaded(NpcVoices.Engine.SUPERTONIC)) listOf("duration_predictor", "text_encoder", "vector_estimator", "vocoder")
            .sumOf { File(models, "supertonic/onnx/$it.onnx").length() } shr 20 else 0L)
    val residentWhich: String get() = listOfNotNull(if (bank.isLoaded(NpcVoices.Engine.KOKORO)) "Kokoro" else null,
        if (bank.isLoaded(NpcVoices.Engine.SUPERTONIC)) "Supertonic" else null).joinToString("+").ifEmpty { "none" }

    /** Memory is short ([com.timerpg.street.llm.MemoryWatch]): the voice engines go unless one speaks (they load again for the next line). */
    fun trimForMemory() { if (!speaking) workerExecute { bank.close() } }

    /** A voice is playing now (the player's mic does not listen to it). */
    var speaking by mutableStateOf(false)
        private set
    /** Downloading the models: 0..1, or -1 when not downloading. */
    var downloading by mutableFloatStateOf(-1f)
    var downloadNote by mutableStateOf("")
    @Volatile private var cancelDownload = false

    val installed: Boolean get() = bank.kokoroInstalled || bank.supertonicInstalled
    val missingMb: Long get() = ModelDownloads.missing(ctx).sumOf { if (it.bytes > 0) it.bytes else 300_000L } shr 20
    /** The optional extra model versions still to fetch (MB), and what of them is on the device now (MB). */
    val extrasMissingMb: Long get() = ModelDownloads.missing(ctx, ModelDownloads.EXTRAS).sumOf { it.bytes } shr 20
    val extrasOnDeviceMb: Long get() = ModelDownloads.extrasOnDevice(ctx).sumOf { it.length() } shr 20

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
        // a filler is playing for them: the line follows it rather than cutting it off
        val behindFiller = fillerFor == npc.id
        val e = early
        early = null
        if (e != null && e.first == npc.id && text.trim().startsWith(e.second) && text.trim().length > e.second.length) {
            // the first sentence is already being spoken: the rest after it, in the same turn
            continueWith(npc, text.trim().substring(e.second.length).trim())
            return
        }
        if (e != null && e.first == npc.id && text.trim() == e.second) return
        if (behindFiller) fillerFor = null else stop()
        if (!enabled || text.isBlank()) return
        touch()
        val g = generation
        val t0 = android.os.SystemClock.elapsedRealtime()
        workerExecute {
            if (g != generation || !enabled) return@workerExecute
            val v = NpcVoices.assign(npc.id, npc.female, npc.age, npc.warmth, bank.kokoroInstalled, bank.supertonicInstalled) ?: return@workerExecute
            // memory short (the dialogue model on the NPU): one voice engine at a time
            val wasLoaded = bank.isLoaded(v.engine)
            var first = -1L; var made = 0L; var audio = 0.0
            for (part in sentences(text)) {
                if (g != generation) return@workerExecute
                val a = android.os.SystemClock.elapsedRealtime()
                val f = try { com.timerpg.street.llm.AiLane.voice { speakOne(v, part) } } catch (e: Throwable) { null } ?: continue
                made += android.os.SystemClock.elapsedRealtime() - a
                audio += (f.length() - 44).coerceAtLeast(0) / 2.0 / wavRate(f)
                if (first < 0) first = android.os.SystemClock.elapsedRealtime() - t0
                main.post { if (g == generation) enqueue(f) }
            }
            val note = "%s: first sound after %.1f s%s · %.1f s of speech made in %.1f s".format(
                v.engine.name.lowercase().replaceFirstChar { it.uppercase() }, first / 1000.0,
                if (wasLoaded) "" else " (the voice model loaded first)", audio, made / 1000.0)
            main.post { lastTiming = note }
            com.timerpg.street.play.PlayLog.tts(npc.id, v.engine.name.lowercase(), first, audio, made, !wasLoaded)
        }
    }

    // ------------------------------------------------------------------------------------------------ fillers

    /** Whose filler is playing (their line queues behind it). */
    private var fillerFor: String? = null
    private var fillerPrep = 0

    /**
     * Voices [lines] (the person's fillers, see Fillers) ahead of time, one at a time so a real line asked meanwhile
     * waits for one short clip at most. Only when the voice is loaded or there is room for it.
     */
    fun prepareFillers(npc: Npc, lines: List<String>) {
        if (!enabled || !installed) return
        val mine = ++fillerPrep
        fun next(i: Int) {
            if (i >= lines.size) return
            run(FILLER) {
                if (mine != fillerPrep || !enabled) return@run
                val v = NpcVoices.assign(npc.id, npc.female, npc.age, npc.warmth, bank.kokoroInstalled, bank.supertonicInstalled) ?: return@run
                if (!bank.isLoaded(v.engine) && com.timerpg.street.llm.Ram.freeMb(ctx) < 1300) return@run
                if (!bank.cached(v, lines[i]).isFile) try { com.timerpg.street.llm.AiLane.voice { speakOne(v, lines[i]) } } catch (e: Throwable) { }
                next(i + 1)
            }
        }
        touch()
        next(0)
    }

    /**
     * Plays the first of [candidates] already voiced for [npc] at once (replacing whatever is playing): what they say
     * while they think. False if none is ready (then nothing is said: a filler is never worth waiting for).
     */
    fun sayFiller(npc: Npc, candidates: List<String>): Boolean {
        if (!enabled || !installed) return false
        val v = NpcVoices.assign(npc.id, npc.female, npc.age, npc.warmth, bank.kokoroInstalled, bank.supertonicInstalled) ?: return false
        val f = candidates.asSequence().map { bank.cached(v, it) }.firstOrNull { it.isFile && it.length() > 44 } ?: return false
        stop()
        fillerFor = npc.id
        touch()
        play(f)
        return true
    }

    /**
     * Says [echo] (the player's question said back, see Fillers.echo) as soon as it is voiced, which starts now ahead of
     * anything else; [npc]'s answer then follows it.
     */
    fun sayEcho(npc: Npc, echo: String) {
        if (!enabled || !installed || echo.isBlank()) return
        stop()
        fillerFor = npc.id
        touch()
        val g = generation
        run(LIVE) {
            if (g != generation) return@run
            val v = NpcVoices.assign(npc.id, npc.female, npc.age, npc.warmth, bank.kokoroInstalled, bank.supertonicInstalled) ?: return@run
            val f = try { com.timerpg.street.llm.AiLane.voice { speakOne(v, echo) } } catch (e: Throwable) { null } ?: return@run
            main.post { if (g == generation) enqueue(f) }
        }
    }

    /** The rest of a line whose first sentence is playing: made and queued behind it. */
    private fun continueWith(npc: Npc, rest: String) {
        if (!enabled || rest.isBlank()) return
        val g = generation
        workerExecute {
            if (g != generation) return@workerExecute
            val v = NpcVoices.assign(npc.id, npc.female, npc.age, npc.warmth, bank.kokoroInstalled, bank.supertonicInstalled) ?: return@workerExecute
            for (part in sentences(rest)) {
                if (g != generation) return@workerExecute
                val f = try { com.timerpg.street.llm.AiLane.voice { speakOne(v, part) } } catch (e: Throwable) { null } ?: continue
                main.post { if (g == generation) enqueue(f) }
            }
        }
    }

    /** Make [text] in [npc]'s voice now (no playback), so it is ready when asked: the voices' own pre-generation. */
    fun prepare(npc: Npc, text: String) {
        if (!enabled || text.isBlank() || !installed) return
        touch()
        run(AHEAD) {
            if (!enabled) return@run
            val v = NpcVoices.assign(npc.id, npc.female, npc.age, npc.warmth, bank.kokoroInstalled, bank.supertonicInstalled) ?: return@run
            // made ahead when the voice is loaded, or there is room to load it
            if (!bank.isLoaded(v.engine) && com.timerpg.street.llm.Ram.freeMb(ctx) < 1300) return@run
            // a sentence per job, so what someone is about to say waits for one sentence at most
            for (part in sentences(text)) run(AHEAD) { if (enabled) try { com.timerpg.street.llm.AiLane.voice { speakOne(v, part) } } catch (e: Throwable) { } }
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

    private companion object {
        const val LIVE = 0
        const val FILLER = 1
        const val AHEAD = 2
    }

    private fun enqueue(f: File) { if (!enabled) return; if (player == null) play(f) else queue.addLast(f) }

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
                        if (next != null) play(next) else { speaking = false; onSpeaking?.invoke(false) }
                        if (next == null && queue.isEmpty()) fillerFor = null
                    }
                }
                prepare()
                start()
                speaking = true
                onSpeaking?.invoke(true)
            }
        } catch (e: Exception) { null }
    }

    fun stop() {
        early = null
        fillerFor = null
        if (player != null) onSpeaking?.invoke(false)
        speaking = false
        generation++
        queue.clear()
        player?.let { try { it.stop() } catch (e: Exception) { }; it.release() }
        player = null
    }

    /** Fetch every model (dialogue, voices, speech, memory), resuming a stopped download. */
    fun download(onDone: () -> Unit) = download(ModelDownloads.ITEMS, onDone)

    /** Fetch the light dialogue model alone (replies in play), when it is what is missing. */
    fun downloadLive(onDone: () -> Unit) = download(ModelDownloads.ITEMS.filter { it.path == com.timerpg.street.dialogue.ModelFiles.LIVE_MTP }, onDone)

    /** Fetch the optional extra versions of the dialogue model (NPU Q4_0, MTP), for trying them out. */
    fun downloadExtras(onDone: () -> Unit) = download(ModelDownloads.EXTRAS, onDone)

    private fun download(items: List<ModelDownloads.Item>, onDone: () -> Unit) {
        if (downloading >= 0f) return
        cancelDownload = false
        downloading = 0f; downloadNote = "Starting…"
        Thread({
            val r = ModelDownloads.download(ctx, { cancelDownload }, items, onItem = { item ->
                // the light model in place: it loads and is prepared now, while the rest downloads
                if (item.prepared != null) main.post { com.timerpg.street.dialogue.DialogueEngine.shared(ctx).reload() }
            }) { done, total ->
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

    fun close() { stop(); workerExecute { bank.close() }; pool.shutdown() }
}
