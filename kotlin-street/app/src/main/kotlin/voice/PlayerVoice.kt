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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.timerpg.street.dialogue.ModelDownloads
import com.timerpg.street.llm.AiLane
import com.timerpg.street.llm.NativeAsr
import com.timerpg.street.llm.Ram
import com.timerpg.voice.SpeechSegmenter
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * The player's voice: hold the mic, say something, let go, and it comes back as text (Qwen3-ASR on the phone). Records
 * 16 kHz mono while held (at most [MAX_SECONDS]), then transcribes on its own thread with the town's names as hints.
 * The speech model loads on the first use and stays while conversations go on; [release] lets it go. When the phone has
 * no room for it (the dialogue model on the NPU holds 5.4 GB), the phone's own on-device speech recognition hears the
 * player instead ([usingPhone]), so holding the mic never takes the game down.
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

    /** How long writing down the last thing said took (ms, from letting go of the button), and by whom. */
    var lastHeard by mutableStateOf<String?>(null)
        private set
    private var letGo = 0L
    @Volatile private var loadedJustNow = false

    /** The phone's own recognizer is hearing the player (ours does not fit in memory beside the dialogue model now). */
    var usingPhone by mutableStateOf(false)
        private set

    /** Our speech model fits in the memory free now (with a margin), or is loaded already. */
    private fun ownFits(): Boolean = asr.loaded ||
        installed && Ram.freeMb(ctx) >= ((modelFile.length() + mmprojFile.length()) shr 20) + Ram.MARGIN_MB

    /** Starts listening (the mic button pressed). The model starts loading now if it is not yet, while the player talks. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (recording || listening || state == State.THINKING) return
        problem = null
        if (!hasPermission()) { problem = "The game needs the microphone to hear you."; return }
        if (!ownFits()) {
            if (SpeechRecognizer.isRecognitionAvailable(ctx)) { startPhone(); return }
            if (installed) { problem = "Not enough free memory for the speech model right now."; return }
        }
        if (!installed) { problem = "Download the speech model in the AI panel first."; return }
        usingPhone = false
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
        letGo = android.os.SystemClock.elapsedRealtime()
        if (phone != null) { stopPhone(onText); return }
        recording = false
        level = 0f
        state = State.THINKING
        val mic = recorder
        worker.execute {
            mic?.join(1000)
            val pcm = samples.toArray()
            loadedJustNow = !asr.loaded
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
                    else -> {
                        lastHeard = "heard in %.1f s (our speech model%s)".format((android.os.SystemClock.elapsedRealtime() - letGo) / 1000.0, if (loadedJustNow) ", loaded first" else "")
                        onText(text)
                    }
                }
            }
        }
    }

    // ---- the phone's own recognizer (main thread)
    private var phone: SpeechRecognizer? = null
    private var phoneResult: String? = null
    private var phoneDone = false
    private var phoneWant: ((String) -> Unit)? = null

    private fun startPhone() {
        usingPhone = true
        val r = try {
            if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
            else SpeechRecognizer.createSpeechRecognizer(ctx)
        } catch (e: Exception) { problem = "The phone's speech recognition could not start."; return }
        phone = r; phoneResult = null; phoneDone = false; phoneWant = null
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) { finishPhone(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull(), null) }
            override fun onError(error: Int) { finishPhone(null, if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "" else "The phone's speech recognition failed ($error).") }
            override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 20_000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4_000L)
        recording = true
        state = State.LISTENING
        try { r.startListening(i) } catch (e: Exception) { finishPhone(null, "The phone's speech recognition could not start.") }
    }

    private fun stopPhone(onText: (String) -> Unit) {
        recording = false
        level = 0f
        state = State.THINKING
        phoneWant = onText
        if (phoneDone) deliverPhone() else runCatching { phone?.stopListening() }
    }

    private var phoneProblem: String? = null
    private fun finishPhone(text: String?, why: String?) {
        phoneResult = text; phoneProblem = why; phoneDone = true
        if (phoneWant != null) deliverPhone()
    }

    private fun deliverPhone() {
        val want = phoneWant ?: return
        phoneWant = null
        runCatching { phone?.destroy() }; phone = null
        recording = false; level = 0f
        state = State.IDLE
        val t = phoneResult
        when {
            !t.isNullOrBlank() -> {
                lastHeard = "heard in %.1f s (the phone's recognizer)".format((android.os.SystemClock.elapsedRealtime() - letGo) / 1000.0)
                want(t)
            }
            phoneProblem.isNullOrEmpty() -> problem = "Didn't catch that. Hold the button while you talk."
            else -> problem = phoneProblem
        }
    }

    private val idle = Runnable { worker.execute { asr.close() } }
    // (never while listening all the time: the model is kept then)
    private fun touch() { main.removeCallbacks(idle); if (!listening) main.postDelayed(idle, IDLE_MS) }

    /** Worker thread: the speech model, loaded in the lane (it takes the CPU while it reads its files). */
    private fun loadNow(): Boolean {
        if (asr.loaded) return true
        // checked again here: the memory may have gone since the mic was pressed
        if (!ownFits()) { main.post { problem = "Not enough free memory for the speech model right now." }; return false }
        val ok = AiLane.run("speech") { asr.loaded || asr.load(modelFile, mmprojFile) }
        if (!ok) main.post { problem = asr.error }
        return ok
    }

    /** Someone to talk to is near: load the speech model now, so it is ready when the mic is held. */
    /** Memory is short ([com.timerpg.street.llm.MemoryWatch]): the speech model goes unless it is listening. */
    fun trimForMemory() { if (!listening) worker.execute { asr.close() } }

    fun prewarm() {
        if (!installed || !ownFits()) return
        touch()
        if (!asr.loaded) worker.execute { loadNow() }
    }

    // ------------------------------------------------------------------------------------------------ always listening

    /** Listening all the time (the settings' mic), between [listen] and [stopListening]. */
    var always by mutableStateOf(false)
        private set
    /** Someone is speaking into the mic now (an utterance is being cut). */
    var hearing by mutableStateOf(false)
        private set
    /** Utterances being written down now (or waiting for the speech model). */
    var writing by mutableIntStateOf(0)
        private set
    /** Not hearing for now (an NPC's voice is playing, so the game does not hear itself): what comes in is dropped. */
    @Volatile var muted = false
    /** The words to expect (the town's names), for the speech model. */
    @Volatile var expect = ""
    @Volatile private var listening = false
    private var listener: Thread? = null
    private val queued = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Listens from now until [stopListening]: the mic stays open, a cheap voice-activity detector ([SpeechSegmenter]) cuts
     * what is said into utterances, and only those go to the speech model (loaded now, kept while listening is on), each
     * written down and handed to [onText] (main thread). Ours only: the phone's recognizer cannot listen all the time.
     */
    @SuppressLint("MissingPermission")
    fun listen(onText: (String) -> Unit) {
        if (listening) return
        problem = null
        if (!hasPermission()) { problem = "The game needs the microphone to hear you."; return }
        if (!installed) { problem = "Download the speech model in the AI panel first."; return }
        if (recording) return
        // (the last listening's mic is closed first: it ends within one read)
        listener?.join(300)
        usingPhone = false
        main.removeCallbacks(idle)
        if (!asr.loaded) worker.execute { loadNow() }
        val rate = SAMPLE_RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(min, rate / 5 * 4))
        } catch (e: Exception) { problem = "The microphone could not be opened."; return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); problem = "The microphone could not be opened."; return }
        listening = true
        always = true
        listener = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val seg = SpeechSegmenter(rate) { pcm -> heard(pcm, onText) }
            val buf = FloatArray(rate * SpeechSegmenter.FRAME_MS / 1000 * 5)   // 100 ms a read: the thread wakes 10 times a second
            var shown = -1f; var wasHearing = false; var wasMuted = false
            try {
                rec.startRecording()
                while (listening) {
                    val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    if (n <= 0) continue
                    val m = muted
                    if (m) { if (!wasMuted) seg.reset() } else seg.feed(buf, n)
                    wasMuted = m
                    // the meter and the "hearing you" mark, only when they change
                    val lv = if (m) 0f else (seg.level * 8f).coerceIn(0f, 1f)
                    val h = seg.speaking
                    if (h != wasHearing || kotlin.math.abs(lv - shown) > .08f) {
                        shown = lv; wasHearing = h
                        main.post { if (listening) { level = lv; hearing = h } }
                    }
                }
            } catch (e: Exception) {
                main.post { problem = "The microphone stopped: ${e.message}" }
            } finally {
                runCatching { rec.stop() }
                rec.release()
                main.post { level = 0f; hearing = false }
            }
        }, "player-mic-always").apply { start() }
    }

    /** One utterance cut from the stream (the mic's thread): written down on the speech thread, at most two waiting. */
    private fun heard(pcm: FloatArray, onText: (String) -> Unit) {
        if (queued.get() >= 2) return   // the speech model is behind: this one is dropped rather than heard late
        queued.incrementAndGet()
        main.post { writing++ }
        val words = expect
        val at = android.os.SystemClock.elapsedRealtime()
        worker.execute {
            var text: String? = null
            var loadedFirst = false
            try {
                if (listening) {
                    loadedFirst = !asr.loaded
                    if (asr.loaded || loadNow()) text = AiLane.run("speech") { asr.transcribe(pcm, words) }
                }
            } finally {
                queued.decrementAndGet()
                val t = text?.trim()
                main.post {
                    writing = (writing - 1).coerceAtLeast(0)
                    if (t == null) { if (listening && asr.error != null) problem = asr.error }
                    // (a word at least: the speech model can write a lone "." or "Hmm" for a noise)
                    else if (always && t.count { it.isLetter() } >= 2) {
                        problem = null
                        lastHeard = "heard in %.1f s (our speech model%s)".format((android.os.SystemClock.elapsedRealtime() - at) / 1000.0, if (loadedFirst) ", loaded first" else "")
                        onText(t)
                    }
                }
            }
        }
    }

    /**
     * Stops listening. [release]: the speech model is let go at once (listening switched off); otherwise it goes after a
     * minute unused (the game paused), so coming straight back does not load it again.
     */
    fun stopListening(release: Boolean) {
        if (!listening && !always) return
        listening = false
        always = false
        level = 0f; hearing = false
        if (release) { asr.cancel(); worker.execute { asr.close() } } else touch()
    }

    /** Lets go of the speech model (the panel closed for a while, or memory is short). */
    fun release() {
        listening = false
        always = false
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
