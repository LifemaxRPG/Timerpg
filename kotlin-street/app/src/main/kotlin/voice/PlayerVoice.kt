package com.timerpg.street.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.timerpg.street.dialogue.ModelDownloads
import com.timerpg.street.llm.AiLane
import com.timerpg.street.llm.NativeAsr
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * The player's voice: hold the mic, say something, let go, and it comes back as text (Qwen3-ASR on the phone). Records
 * 16 kHz mono while held (at most [MAX_SECONDS]), then transcribes on its own thread with the town's names as hints.
 * The speech model loads on the first use and stays while conversations go on; [release] lets it go.
 */
class PlayerVoice(private val ctx: Context) {
    enum class State { IDLE, LOADING, LISTENING, THINKING }

    var state by mutableStateOf(State.IDLE)
        private set
    /** How loud the mic is now, 0..1 (for the button's meter). */
    var level by mutableFloatStateOf(0f)
        private set
    var problem by mutableStateOf<String?>(null)
        private set

    private val asr = NativeAsr()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "player-voice").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var recording = false
    private var recorder: Thread? = null
    private val samples = FloatArrayBuilder()

    val modelFile: File get() = File(ModelDownloads.dir(ctx), ASR_MODEL)
    val mmprojFile: File get() = File(ModelDownloads.dir(ctx), ASR_MMPROJ)
    val installed: Boolean get() = modelFile.isFile && mmprojFile.isFile

    fun hasPermission() = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Starts listening (the mic button pressed). The model starts loading now if it is not yet, while the player talks. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (recording || state == State.THINKING) return
        problem = null
        if (!installed) { problem = "Download the speech model in the AI panel first."; return }
        if (!hasPermission()) { problem = "The game needs the microphone to hear you."; return }
        main.removeCallbacks(idle)
        if (!asr.loaded) worker.execute { loadNow() }
        val rate = SAMPLE_RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(min, rate / 5 * 4))
        } catch (e: Exception) { problem = "The microphone could not be opened."; return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); problem = "The microphone could not be opened."; return }
        samples.clear()
        recording = true
        state = State.LISTENING
        recorder = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = FloatArray(rate / 20)
            try {
                rec.startRecording()
                while (recording && samples.size < rate * MAX_SECONDS) {
                    val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    if (n <= 0) continue
                    samples.add(buf, n)
                    var s = 0f
                    for (i in 0 until n) s += buf[i] * buf[i]
                    val rms = sqrt(s / n)
                    main.post { level = (rms * 8f).coerceIn(0f, 1f) }
                }
            } catch (e: Exception) {
                main.post { problem = "The microphone stopped: ${e.message}" }
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }, "player-mic").apply { start() }
    }

    /**
     * Stops listening (the button let go) and hands what was said to [onText] (main thread) once it is written down;
     * [context] is the names to expect. Nothing is sent for silence.
     */
    fun stop(context: String, onText: (String) -> Unit) {
        if (!recording) return
        recording = false
        level = 0f
        state = State.THINKING
        val mic = recorder
        worker.execute {
            mic?.join(1000)
            val pcm = samples.toArray()
            val text = when {
                pcm.size < SAMPLE_RATE / 4 -> ""                         // a tap, not a sentence
                !asr.loaded && !loadNow() -> null
                else -> AiLane.run("speech") { asr.transcribe(trimSilence(pcm), context) }
            }
            main.post {
                state = State.IDLE
                touch()
                when {
                    text == null -> problem = asr.error ?: "Could not hear that."
                    text.isBlank() -> problem = "Didn't catch that. Hold the button while you talk."
                    else -> onText(text)
                }
            }
        }
    }

    private val idle = Runnable { worker.execute { asr.close() } }
    private fun touch() { main.removeCallbacks(idle); main.postDelayed(idle, IDLE_MS) }

    /** Worker thread: the speech model, loaded in the lane (it takes the CPU while it reads its files). */
    private fun loadNow(): Boolean {
        if (asr.loaded) return true
        val ok = AiLane.run("speech") { asr.loaded || asr.load(modelFile, mmprojFile) }
        if (!ok) main.post { problem = asr.error }
        return ok
    }

    /** Someone to talk to is near: load the speech model now, so it is ready when the mic is held. */
    fun prewarm() {
        if (!installed) return
        touch()
        if (!asr.loaded) worker.execute { loadNow() }
    }

    /** Lets go of the speech model (the panel closed for a while, or memory is short). */
    fun release() {
        recording = false
        asr.cancel()
        worker.execute { asr.close() }
    }

    /** Leading and trailing quiet cut off (with a little margin), so the model reads only the speech. */
    private fun trimSilence(pcm: FloatArray): FloatArray {
        val win = SAMPLE_RATE / 50
        fun loud(at: Int): Boolean {
            var s = 0f; val e = minOf(pcm.size, at + win)
            for (i in at until e) s += pcm[i] * pcm[i]
            return sqrt(s / maxOf(1, e - at)) > .01f
        }
        var a = 0; while (a < pcm.size && !loud(a)) a += win
        var b = pcm.size; while (b > a && !loud(maxOf(0, b - win))) b -= win
        if (a >= b) return pcm
        val pad = SAMPLE_RATE / 5
        return pcm.copyOfRange(maxOf(0, a - pad), minOf(pcm.size, b + pad))
    }

    private class FloatArrayBuilder {
        private var a = FloatArray(16000 * 4)
        @Volatile var size = 0
            private set
        @Synchronized fun add(src: FloatArray, n: Int) {
            if (size + n > a.size) a = a.copyOf(maxOf(a.size * 2, size + n))
            System.arraycopy(src, 0, a, size, n); size += n
        }
        @Synchronized fun clear() { size = 0 }
        @Synchronized fun toArray(): FloatArray = a.copyOf(size)
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val MAX_SECONDS = 20
        /** The speech model is let go this long after it was last needed. */
        private const val IDLE_MS = 60_000L
        const val ASR_MODEL = "asr/Qwen3-ASR-1.7B-Q8_0.gguf"
        const val ASR_MMPROJ = "asr/mmproj-Qwen3-ASR-1.7B-Q8_0.gguf"
    }
}
