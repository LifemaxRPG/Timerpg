package com.timerpg.street.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * The player's voice, written down on the phone (the player's words never leave it).
 *
 * Two hearers ([Hearer], the AI panel's "Speech" switch):
 * - [Hearer.PHONE] (the default where the phone has it): Android's on-device speech recognition
 *   ([SpeechRecognizer.createOnDeviceSpeechRecognizer], API 31+; never the cloud one), with the words shown while the
 *   player talks (partial results). Always listening restarts it after every result. Our own speech model is then never
 *   loaded, so its memory goes to the dialogue model.
 * - [Hearer.GAME]: our own speech model on the CPU (mapped from its file), the small one by default ([AsrSize]); a cheap
 *   voice-activity detector ([SpeechSegmenter]) cuts the mic's stream into utterances, each written down whole.
 * Without on-device recognition (older Android, or its language pack missing) the game's model hears the player.
 *
 * Hold to talk ([start]/[stop]) and always listening ([listen]/[stopListening]) both go through the chosen hearer.
 * Each utterance's timing is kept in [lastHeardInfo] (speech end to text, the model's load) for the breadcrumbs.
 */
class PlayerVoice(private val ctx: Context) {
    enum class State { IDLE, LOADING, LISTENING, THINKING }
    enum class Hearer { PHONE, GAME }
    /** Which of our own speech models: the small one (about a third of the memory) or the large one (more accurate). */
    enum class AsrSize { SMALL, LARGE }

    /** What one utterance took: [by] who wrote it down, [afterEndMs] from the end of speech to the text, [loadMs] the
     *  speech model's load first (0: it was loaded), [audioMs] how long the player spoke (0: unknown). */
    data class Heard(val by: String, val afterEndMs: Long, val loadMs: Long = 0, val audioMs: Long = 0, val confidence: Float = Float.NaN) {
        fun label(): String = "heard %.1f s after you stopped (%s%s)".format(java.util.Locale.US, afterEndMs / 1000.0, by,
            if (loadMs > 0) ", loaded first in %.1f s".format(java.util.Locale.US, loadMs / 1000.0) else "")
    }

    var state by mutableStateOf(State.IDLE)
        private set
    /** How loud the mic is now, 0..1 (for the button's meter). */
    var level by mutableFloatStateOf(0f)
        private set
    var problem by mutableStateOf<String?>(null)
        private set

    private val prefs = ctx.getSharedPreferences("mic", Context.MODE_PRIVATE)
    private val asr = NativeAsr()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "player-voice").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var recording = false
    private var recorder: Thread? = null
    private val samples = FloatArrayBuilder()

    // ------------------------------------------------------------------------------------------------ which hearer

    /** The phone can recognize speech on the device itself (API 31+; never a cloud recognizer). */
    val phoneAvailable: Boolean by lazy {
        Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) }.getOrDefault(false)
    }
    /** Why the phone's recognizer cannot be used after all (its language pack missing, it kept failing), or null. */
    var phoneProblem by mutableStateOf<String?>(null)
        private set

    /** The hearer chosen in the AI panel (kept). */
    var hearerWanted by mutableStateOf(if (prefs.getString("speech", "phone") == "game") Hearer.GAME else Hearer.PHONE)
        private set
    /** The hearer that hears now: the phone's when chosen and it works here, else ours. */
    val hearer: Hearer get() = if (hearerWanted == Hearer.PHONE && phoneAvailable && phoneProblem == null) Hearer.PHONE else Hearer.GAME

    /** Our speech model's size (kept); the small one by default. */
    var asrSize by mutableStateOf(if (prefs.getString("asr", "small") == "large") AsrSize.LARGE else AsrSize.SMALL)
        private set

    fun chooseHearer(h: Hearer) {
        if (h == hearerWanted) return
        hearerWanted = h
        prefs.edit().putString("speech", if (h == Hearer.GAME) "game" else "phone").apply()
        phoneProblem = null
        restartListening()
        // the phone's: ours is let go now (its memory is the dialogue model's)
        if (hearer == Hearer.PHONE) { asr.cancel(); worker.execute { asr.close() } }
    }

    fun chooseAsrSize(s: AsrSize) {
        if (s == asrSize) return
        asrSize = s
        prefs.edit().putString("asr", if (s == AsrSize.LARGE) "large" else "small").apply()
        asr.cancel()
        worker.execute { asr.close(); loadedFile = null }
        if (listening && hearer == Hearer.GAME) worker.execute { loadNow() }
    }

    private fun files(size: AsrSize) = if (size == AsrSize.SMALL) File(ModelDownloads.dir(ctx), ASR_SMALL_MODEL) to File(ModelDownloads.dir(ctx), ASR_SMALL_MMPROJ)
        else File(ModelDownloads.dir(ctx), ASR_MODEL) to File(ModelDownloads.dir(ctx), ASR_MMPROJ)
    private fun installed(size: AsrSize) = files(size).let { (m, p) -> m.isFile && p.isFile }
    private fun other(s: AsrSize) = if (s == AsrSize.SMALL) AsrSize.LARGE else AsrSize.SMALL
    /** The size that loads: the one chosen, else the other when only that one is on the phone. */
    private val sizeNow: AsrSize get() = if (!installed(asrSize) && installed(other(asrSize))) other(asrSize) else asrSize
    val modelFile: File get() = files(sizeNow).first
    val mmprojFile: File get() = files(sizeNow).second
    val installed: Boolean get() = installed(AsrSize.SMALL) || installed(AsrSize.LARGE)
    @Volatile private var loadedFile: File? = null

    fun hasPermission() = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** The last utterance's timing in words, and in numbers. */
    var lastHeard by mutableStateOf<String?>(null)
        private set
    @Volatile var lastHeardInfo: Heard? = null
        private set
    private var letGo = 0L
    @Volatile private var loadedJustNow = 0L

    /** The phone's own recognizer is hearing the player. */
    val usingPhone: Boolean get() = phoneSession != null || phoneLoop

    /** Our speech model fits in the memory free now (with a margin, beside its mapped weights' half), or is loaded already. */
    private fun ownFits(): Boolean = asr.loaded ||
        installed && Ram.freeMb(ctx) >= ((modelFile.length() / 2 + mmprojFile.length()) shr 20) + ASR_WORK_MB + Ram.MARGIN_MB

    init {
        // the memory breakdown, and what the dialogue model may have back: our speech model, unless it is hearing now
        com.timerpg.street.llm.ModelRoom.register(com.timerpg.street.llm.ModelRoom.Resident("speech",
            mb = { residentMb() },
            drop = { if (listening && hearer == Hearer.GAME) 0L else residentMb().also { if (it > 0) { asr.cancel(); asr.close() } } },
            note = { "${if (loadedFile?.name?.contains("0.6B") == true) "small" else "large"}, model mapped, encoder ${mmprojFile.length() shr 20} MB, work ${asr.workMb} MB" }))
    }

    private fun residentMb(): Long = if (!asr.loaded) 0L else ((loadedFile?.length() ?: 0L) + mmprojFile.length()) / 1048576L + asr.workMb

    // ------------------------------------------------------------------------------------------------ hold to talk

    /** Starts listening (the mic button pressed). Our model starts loading now if it is not yet, while the player talks. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (recording || listening || state == State.THINKING) return
        problem = null
        if (!hasPermission()) { problem = "The game needs the microphone to hear you."; return }
        if (hearer == Hearer.PHONE) { startPhone(); return }
        if (!ownFits()) {
            // (on the device only: never a cloud recognizer)
            if (phoneAvailable && phoneProblem == null) { startPhone(); return }
            if (installed) { problem = "Not enough free memory for the speech model right now."; return }
        }
        if (!installed) { problem = "Download the speech model in the AI panel first."; return }
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
        letGo = SystemClock.elapsedRealtime()
        if (phoneSession != null) { stopPhone(onText); return }
        recording = false
        level = 0f
        state = State.THINKING
        val mic = recorder
        worker.execute {
            mic?.join(1000)
            val pcm = samples.toArray()
            loadedJustNow = 0L
            val text = when {
                pcm.size < SAMPLE_RATE / 4 -> ""                         // a tap, not a sentence
                !(asr.loaded && loadedFile == modelFile) && !loadNow() -> null
                else -> AiLane.run("speech") { asr.transcribe(trimSilence(pcm), context) }
            }
            main.post {
                state = State.IDLE
                touch()
                when {
                    text == null -> problem = asr.error ?: "Could not hear that."
                    text.isBlank() -> problem = "Didn't catch that. Hold the button while you talk."
                    else -> {
                        heardBy(Heard(ourName(), SystemClock.elapsedRealtime() - letGo, loadedJustNow, pcm.size * 1000L / SAMPLE_RATE))
                        onText(text)
                    }
                }
            }
        }
    }

    private fun ourName() = "our speech model, ${if (sizeNow == AsrSize.SMALL) "small" else "large"}"

    private fun heardBy(h0: Heard) {
        // (the phone's recognizer gives a confidence with its results; ours does not yet)
        val h = if (h0.confidence.isNaN() && h0.by.startsWith("the phone") && !phoneConfidence.isNaN()) h0.copy(confidence = phoneConfidence) else h0
        phoneConfidence = Float.NaN
        lastHeardInfo = h
        lastHeard = h.label()
        com.timerpg.street.play.PlayLog.asr(h.by, h.afterEndMs, h.audioMs, h.confidence)
    }
    /** The phone recognizer's confidence in its last result (NaN: none given). */
    private var phoneConfidence = Float.NaN

    // ---- the phone's own recognizer, hold to talk (main thread)
    private var phoneSession: SpeechRecognizer? = null
    private var phoneResult: String? = null
    private var phoneDone = false
    private var phoneWant: ((String) -> Unit)? = null
    private var phoneWhy: String? = null

    /** The on-device recognizer (API 31+), or null where there is none (then ours hears). */
    private fun onDevice(): SpeechRecognizer? =
        if (Build.VERSION.SDK_INT < 31 || !phoneAvailable) null else runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) }.getOrNull()

    private fun intent(partial: Boolean, silenceMs: Long) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partial)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)

    private fun startPhone() {
        val r = onDevice() ?: run { problem = "The phone's on-device speech recognition could not start."; return }
        phoneSession = r; phoneResult = null; phoneDone = false; phoneWant = null
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) { phoneConfidence = b?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull()?.takeIf { it >= 0f } ?: Float.NaN; finishPhone(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull(), null) }
            override fun onError(error: Int) { finishPhone(null, if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "" else "The phone's speech recognition failed (${errorName(error)}).") }
            override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        recording = true
        state = State.LISTENING
        try { r.startListening(intent(partial = false, silenceMs = 4_000L).putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 20_000L)) }
        catch (e: Exception) { finishPhone(null, "The phone's speech recognition could not start.") }
    }

    private fun stopPhone(onText: (String) -> Unit) {
        recording = false
        level = 0f
        state = State.THINKING
        phoneWant = onText
        if (phoneDone) deliverPhone() else runCatching { phoneSession?.stopListening() }
    }

    private fun finishPhone(text: String?, why: String?) {
        phoneResult = text; phoneWhy = why; phoneDone = true
        if (phoneWant != null) deliverPhone()
    }

    private fun deliverPhone() {
        val want = phoneWant ?: return
        phoneWant = null
        runCatching { phoneSession?.destroy() }; phoneSession = null
        recording = false; level = 0f
        state = State.IDLE
        val t = phoneResult
        when {
            !t.isNullOrBlank() -> { heardBy(Heard("the phone's recognizer", SystemClock.elapsedRealtime() - letGo)); want(t) }
            phoneWhy.isNullOrEmpty() -> problem = "Didn't catch that. Hold the button while you talk."
            else -> problem = phoneWhy
        }
    }

    private val idle = Runnable { worker.execute { asr.close() } }
    // (never while listening all the time with our model: it is kept then)
    private fun touch() { main.removeCallbacks(idle); if (!(listening && hearer == Hearer.GAME)) main.postDelayed(idle, IDLE_MS) }

    /** Worker thread: our speech model, loaded in the lane (it takes the CPU while it reads its files). */
    private fun loadNow(): Boolean {
        val want = modelFile
        if (asr.loaded && loadedFile == want) return true
        if (asr.loaded) asr.close()
        // checked again here: the memory may have gone since the mic was pressed
        if (!ownFits()) { main.post { problem = "Not enough free memory for the speech model right now${if (phoneAvailable) " (the phone's recognizer can hear you: AI panel, Speech)" else ""}." }; return false }
        val t0 = SystemClock.elapsedRealtime()
        val ok = AiLane.run("speech") { asr.loaded || asr.load(want, mmprojFile) }
        val ms = SystemClock.elapsedRealtime() - t0
        if (ok) {
            loadedFile = want
            loadedJustNow = ms
            val m = asr.loadMem
            com.timerpg.street.shot.Crumbs.add("speech model: ${ourName()} loaded in $ms ms (weights %.0f MB mapped, encoder ${mmprojFile.length() shr 20} MB, KV %.0f, compute %.0f; ${Ram.freeMb(ctx)} MB free after)"
                .format(java.util.Locale.US, m[0], m[2], m[4]))
        } else main.post { problem = asr.error }
        return ok
    }

    /** Memory is short ([com.timerpg.street.llm.MemoryWatch]): our speech model goes unless it is hearing. */
    fun trimForMemory() { if (!(listening && hearer == Hearer.GAME)) worker.execute { asr.close() } }

    /** Someone to talk to is near: load our speech model now, so it is ready when the mic is held (not when the phone's hears). */
    fun prewarm() {
        if (hearer == Hearer.PHONE || !installed || !ownFits()) return
        touch()
        if (!asr.loaded) worker.execute { loadNow() }
    }

    // ------------------------------------------------------------------------------------------------ always listening

    /** Listening all the time (the settings' mic), between [listen] and [stopListening]. */
    var always by mutableStateOf(false)
        private set
    /** Someone is speaking into the mic now (an utterance is being cut, or the phone's recognizer hears speech). */
    var hearing by mutableStateOf(false)
        private set
    /** Utterances being written down now (or waiting for the speech model). */
    var writing by mutableIntStateOf(0)
        private set
    /**
     * Not hearing for now (an NPC's voice is playing, so the game does not hear itself): what comes in is dropped. The
     * phone's recognizer is stopped meanwhile and started again after.
     */
    @Volatile var muted = false
        set(v) {
            if (v == field) return
            field = v
            main.post { if (phoneLoop) { if (v) phoneLoopPause() else phoneLoopNext(150) } }
        }
    /** The words to expect (the town's names), for our speech model. */
    @Volatile var expect = ""
    @Volatile private var listening = false
    private var listener: Thread? = null
    private val queued = java.util.concurrent.atomic.AtomicInteger(0)
    private var onTextNow: ((String) -> Unit)? = null
    private var onPartialNow: ((String) -> Unit)? = null

    /**
     * Listens from now until [stopListening], each thing said handed to [onText] (main thread) once written down; with the
     * phone's recognizer, the words so far go to [onPartial] while the player talks ("" when they are dropped). Our model:
     * the mic stays open, a cheap voice-activity detector cuts what is said into utterances, and only those go to the
     * model (loaded now, kept while listening is on).
     */
    @SuppressLint("MissingPermission")
    fun listen(onText: (String) -> Unit, onPartial: (String) -> Unit = {}) {
        if (listening) return
        problem = null
        if (!hasPermission()) { problem = "The game needs the microphone to hear you."; return }
        if (recording) return
        onTextNow = onText; onPartialNow = onPartial
        if (hearer == Hearer.PHONE) {
            listening = true
            always = true
            phoneLoopStart()
            return
        }
        if (!installed) { problem = "Download the speech model in the AI panel first."; return }
        // (the last listening's mic is closed first: it ends within one read)
        listener?.join(300)
        main.removeCallbacks(idle)
        if (!asr.loaded || loadedFile != modelFile) worker.execute { loadNow() }
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
            val seg = SpeechSegmenter(rate, hangoverMs = TAIL_MS) { pcm -> heard(pcm, onText) }
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
        // the player stopped speaking the detector's tail ago
        val ended = SystemClock.elapsedRealtime() - TAIL_MS
        worker.execute {
            var text: String? = null
            var loadMs = 0L
            try {
                if (listening) {
                    loadedJustNow = 0L
                    if ((asr.loaded && loadedFile == modelFile) || loadNow()) { loadMs = loadedJustNow; text = AiLane.run("speech") { asr.transcribe(pcm, words) } }
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
                        heardBy(Heard(ourName(), SystemClock.elapsedRealtime() - ended, loadMs, pcm.size * 1000L / SAMPLE_RATE))
                        onText(t)
                    }
                }
            }
        }
    }

    // ---- the phone's recognizer, always listening (main thread): started again after every result, error and mute
    private var phoneLoop = false
    private var loopRec: SpeechRecognizer? = null
    private var loopActive = false
    private var loopErrors = 0
    private var speechEndAt = 0L
    private var speechStartAt = 0L
    private var lastPartial = ""
    private var downloadAsked = false
    private val loopNext = Runnable { phoneLoopListen() }

    /**
     * Each recognition the player spoke into (0.1.909: "NPCs couldn't hear me", the lamp at "didn't catch that", and the
     * screenshot's last 40 breadcrumbs, crowded out by the frame lines, held none of why): when it began, the words shown
     * while they talked, how it ended (the result's length, or the error) and what was done with it. The quiet ones (no
     * speech, no words: the loop's normal "no match") are only counted. Read by the screenshot ([attemptLines]) and
     * written as breadcrumbs. Main thread.
     */
    private class Attempt(val at: Long) {
        var spoke = false; var partials = 0; var partialWords = 0
        var ended = ""
    }
    private var attempt: Attempt? = null
    private val attempts = ArrayDeque<String>()
    private var quietAttempts = 0
    /** Who heard the last thing said, and why that one (the game screen tells: [noteListener]). */
    @Volatile var lastListener: String? = null
        private set

    private fun attemptBegin() { attempt = Attempt(SystemClock.elapsedRealtime()) }
    private fun attemptEnd(how: String) {
        val a = attempt ?: return
        attempt = null
        if (!a.spoke && a.partials == 0) { quietAttempts++; return }
        val line = "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} " +
            "${(SystemClock.elapsedRealtime() - a.at) / 100 / 10.0} s, ${if (a.spoke) "speech" else "no speech mark"}, " +
            "${a.partials} partials (${a.partialWords} words shown): $how (${quietAttempts} quiet tries before)"
        quietAttempts = 0
        synchronized(attempts) { attempts.addLast(line); while (attempts.size > ATTEMPTS_KEPT) attempts.removeFirst() }
        crumb("attempt: $line")
    }

    /** The game screen: who was chosen to hear [words] words, and why (or why nobody). */
    fun noteListener(words: Int, who: String) {
        lastListener = who
        val line = "  -> $words words to $who"
        synchronized(attempts) { attempts.addLast(line); while (attempts.size > ATTEMPTS_KEPT) attempts.removeFirst() }
    }

    /** The recent recognitions spoken into, newest last (the screenshot's mic section). */
    fun attemptLines(): List<String> = synchronized(attempts) { attempts.toList() }

    /**
     * "Listening" that hears nothing (the owner's S25, 0.1.642: the lamp said listening, no words came, nothing said why).
     * The loop had no way out of a recognizer that stops calling back (no ready, no level, no result, no error: then
     * [loopActive] stays set and every restart is skipped), nor of one that answers every start with ERROR_CLIENT (retried
     * every 300 ms for ever, on the same instance), and it logged none of it. Now a watch checks the loop every
     * [WATCH_MS]: a recognizer silent for [STALL_MS], or with no result [RESULT_TIMEOUT_MS] after the player stopped
     * talking, is thrown away and a new one started; a loop idle for no reason is started again; repeated client errors
     * get a new recognizer. When words were lost (no result after speech) or the recognizer keeps stalling, the lamp says
     * "didn't catch that: tap to retry" ([needsRetry], [retry]). Every step is a breadcrumb.
     */
    private var lastEventAt = 0L
    private var clientErrors = 0
    private var stalls = 0
    private var mutedSince = 0L
    private var mutedNoted = false
    private val loopWatch = object : Runnable {
        override fun run() {
            if (!phoneLoop || !listening) return
            val now = SystemClock.elapsedRealtime()
            when {
                muted -> {
                    if (mutedSince == 0L) mutedSince = now
                    if (!mutedNoted && now - mutedSince > MUTED_LONG_MS) { mutedNoted = true; crumb("not hearing for ${(now - mutedSince) / 1000} s: an NPC's voice is playing (muted)") }
                }
                loopActive && speechEndAt > 0 && writing > 0 && now - speechEndAt > RESULT_TIMEOUT_MS -> {
                    crumb("no result ${(now - speechEndAt) / 1000} s after you stopped talking: the recognizer is replaced")
                    // (the words it showed while they talked are what they said: those are heard, not lost)
                    if (!deliverShown("no result ${(now - speechEndAt) / 1000} s after the speech ended")) { attemptEnd("no result ${(now - speechEndAt) / 1000} s after the speech ended: missed"); missed() }
                    resetRecognizer()
                }
                loopActive && now - lastEventAt > STALL_MS -> {
                    stalls++
                    attemptEnd("the recognizer went silent for ${(now - lastEventAt) / 1000} s (stall $stalls)")
                    crumb("the recognizer has been silent for ${(now - lastEventAt) / 1000} s (stall $stalls): replaced")
                    if (stalls >= 2) missed()
                    resetRecognizer()
                }
                !loopActive && !restartPending && now - lastEventAt > STALL_MS -> {
                    crumb("the loop was idle for ${(now - lastEventAt) / 1000} s with nothing to wait for: started again")
                    phoneLoopListen()
                }
            }
            if (!muted) { mutedSince = 0L; mutedNoted = false }
            main.postDelayed(this, WATCH_MS)
        }
    }

    /**
     * The recognizer gave no final words (an error, an empty result, or none at all) after showing some while the player
     * talked: those shown words are what was said, so they are heard (handed on as the result) rather than dropped with
     * "didn't catch that". False when there were none to hand on (or the game is not hearing now).
     */
    private fun deliverShown(why: String): Boolean {
        val had = lastPartial
        if (muted || !always || had.count { it.isLetter() } < 2) return false
        lastPartial = ""
        problem = null; needsRetry = false
        val now = SystemClock.elapsedRealtime()
        heardBy(Heard("the phone's recognizer (its words shown)", if (speechEndAt > 0) now - speechEndAt else 0L, 0L,
            if (speechStartAt > 0 && speechEndAt > speechStartAt) speechEndAt - speechStartAt else 0L))
        attemptEnd("$why: the ${had.length} characters shown were heard")
        onTextNow?.invoke(had)
        return true
    }

    /** The player's words were lost, or the recognizer keeps failing: the lamp offers a retry. */
    private fun missed() {
        needsRetry = true
        problem = "Didn't catch that: tap the mic to retry."
        if (lastPartial.isNotEmpty()) { lastPartial = ""; onPartialNow?.invoke("") }
    }

    /** Throws the recognizer away and starts a new one shortly (a recognizer that stalled stays stalled). */
    private fun resetRecognizer(delayMs: Long = 300) {
        loopActive = false
        runCatching { loopRec?.cancel() }
        runCatching { loopRec?.destroy() }
        loopRec = null
        writing = 0; hearing = false; level = 0f
        speechEndAt = 0L
        lastEventAt = SystemClock.elapsedRealtime()
        phoneLoopNext(delayMs)
    }

    /** The lamp's "tap to retry": listening starts again from scratch (a new recognizer, or our model's mic again). */
    fun retry() {
        crumb("retry tapped (${hearer.name.lowercase()})")
        needsRetry = false
        problem = null
        stalls = 0; clientErrors = 0; loopErrors = 0
        if (phoneLoop) { resetRecognizer(0); return }
        val t = onTextNow ?: return
        val p = onPartialNow
        stopListening(release = false)
        listen(t, p ?: {})
    }

    /** The lamp offers "tap to retry" (words were lost, or the recognizer kept stalling). */
    var needsRetry by mutableStateOf(false)
        private set

    private fun crumb(s: String) = com.timerpg.street.shot.Crumbs.add("mic: $s")

    /** The always-listening loop in a line, for the screenshot's diagnostics ("state" there is hold to talk's). */
    fun loopStatus(): String = if (!listening) "not listening" else if (!phoneLoop) "our speech model (${if (asr.loaded) "loaded" else "not loaded"}), writing $writing" else
        "phone recognizer ${if (loopActive) "listening" else if (restartPending) "restarting" else "idle"}, last sign of life " +
            "${(SystemClock.elapsedRealtime() - lastEventAt) / 1000} s ago, errors $loopErrors, client errors $clientErrors, stalls $stalls" +
            "${if (muted) ", muted (an NPC's voice)" else ""}${if (needsRetry) ", asking to retry" else ""}"

    private fun phoneLoopStart() {
        phoneLoop = true
        loopErrors = 0; clientErrors = 0; stalls = 0
        lastEventAt = SystemClock.elapsedRealtime()
        // (our model is not needed: its memory is the dialogue model's)
        if (asr.loaded) { asr.cancel(); worker.execute { asr.close() } }
        crumb("the phone's on-device recognizer listens")
        main.removeCallbacks(loopWatch)
        main.postDelayed(loopWatch, WATCH_MS)
        phoneLoopListen()
    }

    private var restartPending = false

    private fun phoneLoopNext(delayMs: Long) {
        main.removeCallbacks(loopNext)
        restartPending = phoneLoop && listening
        if (restartPending) main.postDelayed(loopNext, delayMs)
    }

    private fun phoneLoopPause() {
        main.removeCallbacks(loopNext)
        restartPending = false
        if (loopActive) { loopActive = false; runCatching { loopRec?.cancel() } }
        hearing = false; level = 0f; writing = 0; speechEndAt = 0L
        if (lastPartial.isNotEmpty()) { lastPartial = ""; onPartialNow?.invoke("") }
    }

    private fun phoneLoopListen() {
        restartPending = false
        if (!phoneLoop || !listening || loopActive) return
        if (muted) return   // started again when the voice stops
        val r = loopRec ?: onDevice()?.also { r ->
            loopRec = r
            r.setRecognitionListener(loopListener)
        } ?: return phoneLoopFail("The phone's on-device speech recognition could not start.")
        loopActive = true
        lastPartial = ""
        attemptBegin()
        // (the watch counts silence from the start)
        lastEventAt = SystemClock.elapsedRealtime()
        try { r.startListening(intent(partial = true, silenceMs = PHONE_SILENCE_MS)) }
        catch (e: Exception) { loopActive = false; crumb("could not start the recognizer: ${e.javaClass.simpleName} ${e.message ?: ""}"); phoneLoopError(-1) }
    }

    private fun alive() { lastEventAt = SystemClock.elapsedRealtime() }

    private val loopListener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) { alive(); clientErrors = 0 }
        override fun onBeginningOfSpeech() { alive(); speechStartAt = SystemClock.elapsedRealtime(); speechEndAt = 0L; if (!muted) hearing = true; attempt?.spoke = true }
        override fun onRmsChanged(rmsdB: Float) { alive(); if (!muted) level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(b: ByteArray?) { alive() }
        override fun onEndOfSpeech() { alive(); speechEndAt = SystemClock.elapsedRealtime(); hearing = false; level = 0f; writing = 1 }
        override fun onPartialResults(p: Bundle?) {
            alive()
            if (muted || !phoneLoop) return
            val t = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (t.isNotEmpty() && t != lastPartial) {
                lastPartial = t; onPartialNow?.invoke(t)
                attempt?.let { it.partials++; it.partialWords = t.split(' ').size }
            }
        }
        override fun onEvent(t: Int, p: Bundle?) { alive() }
        override fun onResults(b: Bundle?) {
            alive()
            loopActive = false; writing = 0; hearing = false
            val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            phoneConfidence = b?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull()?.takeIf { it >= 0f } ?: Float.NaN
            loopErrors = 0; clientErrors = 0; stalls = 0
            // (the speech's end kept until the result is timed: it was cleared first, so every "heard after" read 0 s)
            val ended = speechEndAt
            val had = lastPartial
            if (!muted && always && t.count { it.isLetter() } >= 2) {
                speechEndAt = 0L
                lastPartial = ""
                problem = null; needsRetry = false
                val now = SystemClock.elapsedRealtime()
                heardBy(Heard("the phone's recognizer", if (ended > 0) now - ended else 0L, 0L,
                    if (speechStartAt > 0 && ended > speechStartAt) ended - speechStartAt else 0L))
                attemptEnd("a result of ${t.length} characters, heard")
                onTextNow?.invoke(t)
            } else {
                // (an empty result after words were shown: the shown words are what was said)
                val delivered = t.isEmpty() && had.isNotEmpty() && deliverShown("an empty result")
                speechEndAt = 0L
                if (!delivered) {
                    lastPartial = ""
                    if (had.isNotEmpty()) { crumb("a result of ${t.length} characters after ${had.split(' ').size} words were shown: dropped"); onPartialNow?.invoke("") }
                    attemptEnd(if (muted) "a result of ${t.length} characters while an NPC spoke: not heard" else "a result of ${t.length} characters: too short to answer")
                }
            }
            phoneLoopNext(RESTART_MS)
        }
        override fun onError(error: Int) {
            alive()
            loopActive = false; writing = 0; hearing = false; level = 0f
            // words shown, then an error (the on-device recognizer can end that way): the words shown are what was said and
            // are heard; before, they were dropped and the lamp said "didn't catch that"
            if (lastPartial.isNotEmpty()) {
                val words = lastPartial.split(' ').size
                if (!deliverShown("error ${errorName(error)}")) {
                    crumb("${errorName(error)} after $words words were shown: lost")
                    attemptEnd("error ${errorName(error)} after $words words were shown: lost")
                    if (error != SpeechRecognizer.ERROR_CLIENT) missed()
                    lastPartial = ""; onPartialNow?.invoke("")
                }
            } else attemptEnd("error ${errorName(error)}")
            speechEndAt = 0L
            phoneLoopError(error)
        }
    }

    private fun phoneLoopError(error: Int) {
        if (!phoneLoop) return
        // (no match and timeouts are the loop's normal quiet: not logged)
        if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            crumb("recognizer error: ${errorName(error)} (${loopErrors + 1} errors, ${clientErrors + if (error == SpeechRecognizer.ERROR_CLIENT) 1 else 0} client errors in a row)")
        when (error) {
            // nothing said, or said nothing it knew: listen again
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> { loopErrors = 0; clientErrors = 0; stalls = 0; phoneLoopNext(RESTART_MS) }
            // (a cancel of ours, or a start too soon after the last; again and again: the instance is broken, a new one)
            SpeechRecognizer.ERROR_CLIENT -> {
                if (++clientErrors >= CLIENT_ERRORS_MAX) {
                    crumb("$clientErrors client errors in a row: a new recognizer")
                    clientErrors = 0
                    if (++stalls >= 3) missed()
                    resetRecognizer(600)
                } else phoneLoopNext(300)
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> { runCatching { loopRec?.destroy() }; loopRec = null; phoneLoopNext(600) }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { problem = "The game needs the microphone to hear you."; stopPhoneLoop() }
            // its language pack is not on the phone: asked for (API 33+), and ours hears meanwhile
            12, 13 -> {
                if (Build.VERSION.SDK_INT >= 33 && !downloadAsked) {
                    downloadAsked = true
                    runCatching { loopRec?.triggerModelDownload(intent(partial = true, silenceMs = PHONE_SILENCE_MS)) }
                }
                phoneLoopFail("The phone's on-device speech recognition has no English pack yet (asked to download it); the game's speech model hears you meanwhile.")
            }
            else -> {
                if (++loopErrors >= 5) phoneLoopFail("The phone's on-device speech recognition keeps failing (${errorName(error)}); the game's speech model hears you instead.")
                // (an audio or server error can leave the instance unusable: the next try gets a new one)
                else { runCatching { loopRec?.destroy() }; loopRec = null; phoneLoopNext(400L * loopErrors) }
            }
        }
    }

    /** The phone's recognizer cannot hear here: ours does, from now (if it is on the phone). */
    private fun phoneLoopFail(why: String) {
        com.timerpg.street.shot.Crumbs.add("mic: $why")
        phoneProblem = why
        stopPhoneLoop()
        val t = onTextNow; val p = onPartialNow
        listening = false; always = false
        if (t != null) listen(t, p ?: {})
    }

    private fun stopPhoneLoop() {
        phoneLoop = false
        main.removeCallbacks(loopNext)
        main.removeCallbacks(loopWatch)
        restartPending = false
        loopActive = false
        runCatching { loopRec?.cancel() }
        runCatching { loopRec?.destroy() }
        loopRec = null
        hearing = false; level = 0f; writing = 0
        if (lastPartial.isNotEmpty()) { lastPartial = ""; onPartialNow?.invoke("") }
    }

    /** Listening goes on with the hearer chosen now (the panel's switch changed). */
    private fun restartListening() {
        if (!listening) return
        val t = onTextNow ?: return
        val p = onPartialNow
        stopListening(release = false)
        listen(t, p ?: {})
    }

    /**
     * Stops listening. [release]: our speech model is let go at once (listening switched off); otherwise it goes after a
     * minute unused (the game paused), so coming straight back does not load it again.
     */
    fun stopListening(release: Boolean) {
        if (!listening && !always) return
        listening = false
        always = false
        needsRetry = false
        level = 0f; hearing = false
        if (phoneLoop) stopPhoneLoop()
        if (release) { asr.cancel(); worker.execute { asr.close() } } else touch()
    }

    /** Lets go of our speech model and the phone's recognizer (the game screen goes, or memory is short). */
    fun release() {
        listening = false
        always = false
        recording = false
        if (phoneLoop) stopPhoneLoop()
        runCatching { phoneSession?.destroy() }; phoneSession = null
        asr.cancel()
        worker.execute { asr.close() }
    }

    private fun errorName(e: Int): String = when (e) {
        SpeechRecognizer.ERROR_AUDIO -> "audio"
        SpeechRecognizer.ERROR_CLIENT -> "client"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission"
        SpeechRecognizer.ERROR_NETWORK -> "network"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "no match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
        SpeechRecognizer.ERROR_SERVER -> "server"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech timeout"
        10 -> "too many requests"
        11 -> "server disconnected"
        12 -> "language not supported"
        13 -> "language unavailable"
        else -> "error $e"
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
        /** Our speech model is let go this long after it was last needed. */
        private const val IDLE_MS = 60_000L
        /** Quiet this long ends an utterance for our model (it was 700 ms: the wait before anything happens). */
        const val TAIL_MS = 500
        /** The phone's recognizer: the quiet that ends an utterance (a hint; the recognizer may keep its own). */
        const val PHONE_SILENCE_MS = 700L
        /** The phone's recognizer starts listening again this soon after a result. */
        private const val RESTART_MS = 80L
        /** The always-listening loop's watch: how often it looks, how long a recognizer may say nothing at all (it sends
         *  its level several times a second while it listens), how long a result may take after the player stops, and
         *  client errors in a row before the recognizer is replaced. Guesses, to be checked against the breadcrumbs. */
        private const val WATCH_MS = 2_000L
        const val STALL_MS = 10_000L
        const val RESULT_TIMEOUT_MS = 6_000L
        /** Recognitions spoken into kept for the screenshot ([attemptLines]). */
        private const val ATTEMPTS_KEPT = 16
        private const val CLIENT_ERRORS_MAX = 4
        /** Muted (an NPC's voice playing) this long is logged once: the mic hears nothing meanwhile. */
        private const val MUTED_LONG_MS = 30_000L
        /** Our speech model's own buffers beside its weights (MB): its cache in 8 bits and one output row. */
        private const val ASR_WORK_MB = 150L
        const val ASR_MODEL = "asr/Qwen3-ASR-1.7B-Q8_0.gguf"
        const val ASR_MMPROJ = "asr/mmproj-Qwen3-ASR-1.7B-Q8_0.gguf"
        const val ASR_SMALL_MODEL = "asr/Qwen3-ASR-0.6B-Q8_0.gguf"
        const val ASR_SMALL_MMPROJ = "asr/mmproj-Qwen3-ASR-0.6B-Q8_0.gguf"
    }
}
