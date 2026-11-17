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
        { ctx.assets.open("voices/kokoro_lexicon.tsv").bufferedReader().lineSequence() }, threads = 4)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "npc-voices").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    @Volatile private var generation = 0
    // the voice engines (loaded whole into RAM) are let go when nothing has been spoken for a while
    private val idle = Runnable { worker.execute { bank.close() } }
    private fun touch() { main.removeCallbacks(idle); main.postDelayed(idle, 30_000L) }

    /** Voices on or off (the player's choice). */
    var enabled by mutableStateOf(true)
    /** Downloading the models: 0..1, or -1 when not downloading. */
    var downloading by mutableFloatStateOf(-1f)
    var downloadNote by mutableStateOf("")
    @Volatile private var cancelDownload = false

    val installed: Boolean get() = bank.kokoroInstalled || bank.supertonicInstalled
    val missingMb: Long get() = ModelDownloads.missing(ctx).sumOf { if (it.bytes > 0) it.bytes else 300_000L } shr 20

    /** Speak [text] as [npc] (replacing whatever is playing). Quietly nothing if voices are off or not installed. */
    fun say(npc: Npc, text: String) {
        stop()
        if (!enabled || text.isBlank()) return
        touch()
        val g = generation
        worker.execute {
            if (g != generation) return@execute
            val v = NpcVoices.assign(npc.id, npc.female, npc.age, npc.warmth, bank.kokoroInstalled, bank.supertonicInstalled) ?: return@execute
            val f = try { com.timerpg.street.llm.AiLane.run("voice") { bank.speak(v, text) } } catch (e: Throwable) { null } ?: return@execute
            main.post { if (g == generation) play(f) }
        }
    }

    /** Make [text] in [npc]'s voice now (no playback), so it is ready when asked: the voices' own pre-generation. */
    fun prepare(npc: Npc, text: String) {
        if (!enabled || text.isBlank() || !installed) return
        touch()
        worker.execute {
            val v = NpcVoices.assign(npc.id, npc.female, npc.age, npc.warmth, bank.kokoroInstalled, bank.supertonicInstalled) ?: return@execute
            try { com.timerpg.street.llm.AiLane.run("voice") { bank.speak(v, text) } } catch (e: Throwable) { }
        }
    }

    private fun play(f: File) {
        player?.release()
        player = try {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(f.path)
                setOnCompletionListener { it.release(); if (player === it) player = null }
                prepare()
                start()
            }
        } catch (e: Exception) { null }
    }

    fun stop() {
        generation++
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
