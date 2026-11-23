package com.timerpg.street.night

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.timerpg.street.dialogue.BankPlan
import com.timerpg.street.dialogue.DialogueEngine
import com.timerpg.street.dialogue.ModelState
import com.timerpg.street.dialogue.NightCount
import com.timerpg.street.llm.MemoryWatch
import com.timerpg.street.llm.Ram
import com.timerpg.street.shot.Crumbs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** What Writer mode's screen shows (and the notification): a session's state, refreshed every tick. */
data class WriterView(
    val running: Boolean = false,
    /** "Loading the model…", "Writing", "Resting: …", "Stopped: …". */
    val phase: String = "",
    val model: String = "",
    val modelDetail: String = "",
    val working: String? = null,
    val progress: String? = null,
    val count: NightCount = NightCount(),
    val bankReady: Int = 0,
    val bankPrompts: Int = 0,
    val bankLines: Int = 0,
    val bankLight: Int = 0,
    val left: Int = -1,
    val etaMs: Long? = null,
    val elapsedMs: Long = 0,
    val writingMs: Long = 0,
    val linesPerHour: Double = 0.0,
    val queue: BankPlan.Queue? = null,
    val recent: List<NightReport.Written> = emptyList(),
    val thermal: Int = -1,
    val headroom: Float = Float.NaN,
    val maxThermal: Int = -1,
    val battery: Int = -1,
    val plugged: Boolean = false,
    val charging: Boolean = false,
    val freeMb: Long = -1,
    val thresholdMb: Long = -1,
    val memory: NightReport.Memory = NightReport.Memory(),
    val paused: Boolean = false,
    /** Once stopped: the run in plain words ([NightReport.explain]). */
    val ended: String? = null,
    /** Why it is or is not writing now, in plain words ([WriterWhy]): shown under the phase while it is not writing. */
    val why: WriterWhy? = null,
)

/**
 * One writing session (on its own thread), the overnight writer's or Writer mode's ([how] "service", "job" or
 * "writer"): it takes [WriterLock], closes the game's process when it sits out of sight (Writer mode at once, the
 * overnight writer after [NightWriter.GAME_GRACE_MS]), lets the dialogue engine write ([DialogueEngine.beginNight]: the
 * main model's copy on the NPU, else the light one's there, never the CPU while there is an NPU) and every tick runs
 * [WriterLoop]: [NightRules] with the phone's state (charger, battery, heat), holding the engine (bursts, warm spells,
 * hot pauses, Writer mode's Pause) or stopping. A partial wake lock is held only while it writes. The model is loaded
 * once with the game's memory checks and let go when it stops; the bank is saved before [WriterLock] goes, so the game
 * reads every line. The progress goes to [view] (Writer mode's screen), the notification and the breadcrumbs, the
 * summary to the AI panel and a report ([NightReport]).
 */
class NightSession(private val app: Context, how: String, private val onEnd: (NightStep.Stop) -> Unit) {
    @Volatile var how: String = how
        private set
    val writer: Boolean get() = how == "writer"
    private val wakeups = LinkedBlockingQueue<String>()
    @Volatile private var stopWith: String? = null
    private val thread = Thread({ runCatching { run() }.onFailure { Crumbs.add("writer failed: $it"); finish(NightStep.Stop("failed: ${it.javaClass.simpleName}", true), null) } }, "night-writer")
    @Volatile private var finished = false
    /** It has stopped (its end is being handed to the service). */
    val done: Boolean get() = finished
    private val _view = MutableStateFlow(WriterView(running = true, phase = "Starting…"))
    val view: StateFlow<WriterView> = _view

    /** Writer mode's Pause: nothing new is written, the model stays loaded. */
    @Volatile var paused = false
        private set

    fun start() = thread.start()

    private val stopped = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    /** [l] runs (on the session's thread) once it has stopped. */
    fun onStopped(l: () -> Unit) { if (finished) l() else stopped += l }

    /** Stops the session (from any thread) for [reason]; it ends within a moment. */
    fun stop(reason: String) { if (stopWith == null) stopWith = reason; wakeups.offer(reason) }

    fun setPaused(on: Boolean) { paused = on; wakeups.offer("pause") }

    /** An overnight session taken over by Writer mode (its screen opened while it ran): Writer mode's rules from now on. */
    fun becomeWriter() { how = "writer"; wakeups.offer("writer") }

    private val pm = app.getSystemService(PowerManager::class.java)
    private var wake: PowerManager.WakeLock? = null

    private fun hold(on: Boolean) {
        try {
            if (on) {
                val w = wake ?: pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "timerpg:night-writer")?.apply { setReferenceCounted(false) }.also { wake = it }
                // (renewed every tick: a stuck session cannot hold the phone awake for long)
                w?.acquire(5 * 60_000L)
            } else wake?.takeIf { it.isHeld }?.release()
        } catch (t: Throwable) { }
    }

    private fun thermal(): Int = if (Build.VERSION.SDK_INT >= 29) runCatching { pm.currentThermalStatus }.getOrDefault(-1) else -1
    @Volatile private var lastHeadroom = Float.NaN
    private var headroomAt = 0L
    // (Android answers about once a second at most; asked more often it says NaN: the last answer is kept)
    private fun headroom(): Float {
        if (Build.VERSION.SDK_INT < 30) return Float.NaN
        val now = SystemClock.elapsedRealtime()
        if (now - headroomAt >= 1500) { headroomAt = now; runCatching { pm.getThermalHeadroom(10) }.getOrNull()?.takeIf { !it.isNaN() }?.let { lastHeadroom = it } }
        return lastHeadroom
    }

    private fun inputs(): NightInputs = NightInputs(
        enabled = writer || NightWriter.enabled(app), plugged = NightWriter.plugged(app), charging = NightWriter.charging(app),
        battery = NightWriter.battery(app), gameOpen = NightWriter.gameVisible(app), tuning = com.timerpg.street.NpuTune.active(app),
        thermal = thermal(), headroom = headroom(), onBattery = writer && NightWriter.writerOnBattery(app), paused = writer && paused,
    )

    private fun tickMs() = if (writer) 2_000L else 5_000L

    private fun run() {
        val t0 = System.currentTimeMillis()
        val e0 = SystemClock.elapsedRealtime()
        com.timerpg.street.AppInit.basics(app)
        if (!WriterLock.hold(app)) return finish(NightStep.Stop("another writer is already writing", false), null)
        val battery0 = NightWriter.battery(app)
        val plugged0 = NightWriter.plugged(app)
        // Writer mode is opened from the game, and the overnight service is started as the game goes out of sight: either
        // way the game's screen is still leaving the front for a moment, and that is not "the game was opened" (it
        // stopped at once, with no report, when the game's process still read as visible)
        run {
            val until = SystemClock.elapsedRealtime() + GAME_LEAVING_MS
            while (stopWith == null && NightWriter.gameVisible(app) && SystemClock.elapsedRealtime() < until) {
                _view.value = _view.value.copy(phase = "Waiting for the game to close…")
                wakeups.poll(250, TimeUnit.MILLISECONDS)
            }
        }
        // nothing loads when it would stop at once (unplugged meanwhile, the game open, tuning)
        (NightRules().step(inputs(), SystemClock.elapsedRealtime()) as? NightStep.Stop)?.let { return finish(it, null) }

        // the game's process tells this one when it opens (its broadcast; its process coming to the front is also seen)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    NightWriter.ACTION_GAME_OPENED -> stop(NightWriter.GAME_OPENED)
                    NightWriter.ACTION_SWITCHED_OFF -> if (!writer) stop("the overnight writer was switched off in the AI panel")
                }
            }
        }
        val filter = IntentFilter().apply { addAction(NightWriter.ACTION_GAME_OPENED); addAction(NightWriter.ACTION_SWITCHED_OFF) }
        runCatching { if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(receiver, filter) }

        com.timerpg.street.AppInit.native(app)
        MemoryWatch.start(app)
        val free0 = Ram.freeMb(app)
        Crumbs.add("writer: start ($how), battery $battery0%${if (plugged0) " plugged in" else ""}, thermal ${thermal()}, $free0 MB free")

        // the game's process out of sight holds its town, renderer and voices: closed for the room (Writer mode at once;
        // overnight after a grace, as the player may come back)
        var gameWaitMs = 0L
        var freed = -1L
        while (stopWith == null) {
            val g = NightWriter.gameProcess(app)
            if (g == null || g.pid == android.os.Process.myPid()) break
            val waited = SystemClock.elapsedRealtime() - e0
            // (room for the main model's copy beside it already: the game is left alone)
            val mainFile = com.timerpg.street.dialogue.ModelFiles.file(app).length()
            if (!writer && mainFile > 0 && Ram.freeMb(app) >= com.timerpg.street.dialogue.ModelRoles.needBytes(mainFile, true) shr 20) break
            if (writer || waited >= NightWriter.GAME_GRACE_MS) {
                // the playtest recorder sends the game's timeline as it goes out of sight: a moment for that upload first
                // (a killed process loses it until the game's next start)
                // (its part is closed on the game's recorder thread as it goes out of sight: a moment for that too)
                val w0 = SystemClock.elapsedRealtime()
                val until = w0 + UPLOAD_WAIT_MS
                while (stopWith == null && SystemClock.elapsedRealtime() < until &&
                    (SystemClock.elapsedRealtime() - w0 < 1500 || com.timerpg.street.play.PlayUploads.waiting(app) > 0)) {
                    _view.value = _view.value.copy(phase = "Letting the game send its playtest timeline…")
                    wakeups.poll(500, TimeUnit.MILLISECONDS)
                }
                freed = NightWriter.closeGame(app); break
            }
            (NightRules().step(inputs(), SystemClock.elapsedRealtime()) as? NightStep.Stop)?.let { stop(it.reason) }
            _view.value = _view.value.copy(phase = "The game was just closed: its memory is freed in ${WriterLoop.duration(NightWriter.GAME_GRACE_MS - waited)} (you may come back)")
            wakeups.poll(tickMs(), TimeUnit.MILLISECONDS)
            gameWaitMs = SystemClock.elapsedRealtime() - e0
        }
        stopWith?.let { s ->
            runCatching { app.unregisterReceiver(receiver) }
            return finish(NightStep.Stop(s, s.startsWith("the system")), null)
        }

        if (!DialogueEngine.sharedExists) DialogueEngine.startDir = NightWriter.townDir(app)
        val engine = DialogueEngine.shared(app)
        // the game's memory care: the watch's levels and Android's trim calls go to the engine as they do in the game
        val onMemory: (Int) -> Unit = { engine.onMemoryLevel(it) }
        MemoryWatch.listeners += onMemory
        val trim = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) { Crumbs.add("writer: trim memory $level"); engine.onTrimMemory(level) }
            override fun onConfigurationChanged(newConfig: Configuration) {}
            @Deprecated("") override fun onLowMemory() {}
        }
        app.registerComponentCallbacks(trim)
        val freeBefore = Ram.freeMb(app)
        val (mainNeed, lightNeed) = runCatching { engine.copyNeedsMb() }.getOrDefault(-1L to -1L)
        var memory = NightReport.Memory(freeAtStartMb = free0, freedByGameMb = freed, freeBeforeLoadMb = freeBefore,
            thresholdMb = MemoryWatch.thresholdMb, mainNeedMb = mainNeed, lightNeedMb = lightNeed)
        Crumbs.add("writer: $freeBefore MB free before the load (Android's threshold ${MemoryWatch.thresholdMb} MB); the main model's NPU copy needs $mainNeed MB, the light one's $lightNeed MB")
        engine.beginNight(gameVisible = false)

        val engineSide = object : WriterEngine {
            override val state get() = engine.status.value.state
            override val detail get() = engine.status.value.detail
            override fun left() = engine.nightLeft()
            override fun count() = engine.nightCount()
            override fun hold(on: Boolean, stopRunning: Boolean) {
                if (on) engine.pause(if (stopRunning) HOT else REST, stopRunning)
                else { engine.resume(REST); engine.resume(HOT) }
            }
            override fun reload() = engine.nightReload()
            override fun held() = engine.nightHeld()
        }
        val loop = WriterLoop(NightRules(), engineSide, start = SystemClock.elapsedRealtime())
        var lastNotify = 0L
        var lastCrumb = SystemClock.elapsedRealtime()
        var lastQueue = 0L
        var queue: BankPlan.Queue? = null
        var loaded = false
        var lastWhy: WriterWhy? = null
        var lastStatus = 0L
        var stop: NightStep.Stop
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val i = inputs()
            val step = loop.tick(i, now, stopWith)
            if (step is NightStep.Stop) { stop = step; break }
            hold(step == NightStep.Write)
            val st = engine.status.value
            if (!loaded && st.state == ModelState.READY) {
                loaded = true
                memory = memory.copy(freeAfterLoadMb = Ram.freeMb(app))
                Crumbs.add("writer: ${engine.modelNow()} loaded; ${memory.freeAfterLoadMb} MB free after (${memory.freeBeforeLoadMb} before)")
            }
            if (now - lastQueue > 30_000L || queue == null) { lastQueue = now; queue = runCatching { engine.nightQueue() }.getOrNull() }
            val c = engine.nightCount()
            val bank = engine.bank()
            val elapsed = now - loop.start
            val why = loop.why(st.rejected)
            val phase = when {
                step is NightStep.Rest -> if (step.kind == NightStep.Kind.USER) "Paused" else "Resting: ${step.reason}"
                st.state == ModelState.READY -> engine.nightHeld()?.let { "Held by the engine: $it" }
                    ?: if (why.stuck) "Not writing: ${why.reason}" else "Writing"
                else -> st.detail.ifEmpty { "Loading the model…" }
            }
            if (why.state != lastWhy?.state || why.reason != lastWhy?.reason || now - lastStatus > STATUS_MS) {
                if (why.state != lastWhy?.state) Crumbs.add("writer: ${why.state}: ${why.reason}")
                lastWhy = why; lastStatus = now
                status(true, why, c, engine.modelNow())
            }
            _view.value = WriterView(
                running = true, phase = phase, model = engine.modelNow(), modelDetail = st.detail, working = st.working, progress = st.night?.label,
                count = c, bankReady = bank.ready, bankPrompts = bank.prompts, bankLines = bank.variants, bankLight = bank.light,
                left = engine.nightLeft(), etaMs = loop.etaMs(now), elapsedMs = elapsed, writingMs = loop.writingMs,
                linesPerHour = if (elapsed > 0) (c.lines + c.variants) * 3_600_000.0 / elapsed else 0.0,
                queue = queue, recent = engine.nightRecent(), thermal = i.thermal, headroom = i.headroom, maxThermal = loop.rules.maxThermal,
                battery = i.battery, plugged = i.plugged, charging = i.charging, freeMb = MemoryWatch.freeMb.takeIf { it >= 0 } ?: Ram.freeMb(app),
                thresholdMb = MemoryWatch.thresholdMb, memory = memory, paused = paused, why = why,
            )
            if (now - lastNotify > NOTIFY_MS) {
                lastNotify = now
                val title = (if (writer) "Writer mode: " else "") + (st.night?.label ?: "Writing dialogue")
                NightWriter.notify(app, title, listOfNotNull(engine.modelNow().takeIf { it.isNotEmpty() },
                    "%.1f tok/s".format(c.genPerSec).takeIf { c.genMs > 0 }, phase.takeIf { it != "Writing" }?.take(80)).joinToString(" · ").ifEmpty { null })
            }
            if (now - lastCrumb > CRUMB_MS) {
                lastCrumb = now
                Crumbs.add("writer: ${c.lines} lines + ${c.variants} variants, %.1f tok/s, thermal ${i.thermal}, battery ${i.battery}%%, ${engine.modelNow()}".format(c.genPerSec))
            }
            wakeups.poll(tickMs(), TimeUnit.MILLISECONDS)
        }
        // the end: the line in progress stopped, the bank saved and the model let go (before the lock goes: the game reads
        // the bank after), the engine back to the game's rules
        _view.value = _view.value.copy(phase = "Stopping: ${stop.reason}…")
        hold(false)
        val c = engine.nightCount()
        val bank = engine.bank()
        val model = engine.modelNow().ifEmpty { if (loaded) _view.value.model else "" }
        val runsOn = listOf("CPU", "GPU", "NPU").getOrElse(engine.status.value.runsOn) { "?" }
        val recent = engine.nightRecent()
        val left = engine.nightLeft()
        val eta = loop.etaMs(SystemClock.elapsedRealtime())
        val q = runCatching { engine.nightQueue() }.getOrNull()
        val atEnd = engine.status.value.let { "${it.state.name.lowercase()}${it.detail.takeIf { d -> d.isNotEmpty() }?.let { d -> ": " + d.take(160) } ?: ""}" +
            (engine.nightHeld()?.let { h -> " (held by: $h)" } ?: "") }
        val lastRejected = engine.status.value.rejected?.take(200)
        val saved = CountDownLatch(1)
        engine.resume(REST); engine.resume(HOT)
        engine.endNight(release = true) { saved.countDown() }
        if (!saved.await(30, TimeUnit.SECONDS)) Crumbs.add("writer: the bank's last save took more than 30 s")
        MemoryWatch.listeners -= onMemory
        runCatching { app.unregisterComponentCallbacks(trim) }
        runCatching { app.unregisterReceiver(receiver) }
        val r = NightReport.Run(
            start = t0, end = System.currentTimeMillis(), how = how, stopReason = stop.reason,
            lines = c.lines, variants = c.variants, rejected = c.rejected, timedOut = c.timedOut, failed = c.failed, lastFailure = c.lastFailure,
            genTokens = c.genTokens, genMs = c.genMs,
            prefillTokens = c.prefillTokens, prefillMs = c.prefillMs, writingMs = loop.writingMs,
            coolMs = loop.spent(NightStep.Kind.COOL), hotMs = loop.spent(NightStep.Kind.HOT),
            burstRestMs = loop.spent(NightStep.Kind.BURST), batteryWaitMs = loop.spent(NightStep.Kind.BATTERY),
            coolDowns = loop.rules.coolDowns, hotPauses = loop.rules.hotPauses, maxThermal = loop.rules.maxThermal,
            batteryStart = battery0, batteryEnd = NightWriter.battery(app),
            runsOn = if (model.isEmpty()) "" else runsOn,
            bank = bank, themes = engine.steer.summary(withText = NightWriter.shareWords(app)), answers = engine.answerDays(),
            userPausedMs = loop.spent(NightStep.Kind.USER), gameWaitMs = gameWaitMs, loadingMs = loop.loadingMs,
            heldMs = loop.heldMs, heldBy = loop.heldBy, engineAtEnd = atEnd, lastRejected = lastRejected,
            model = model, modelProblem = if (loaded) null else loop.modelProblem ?: engine.status.value.detail.takeIf { it.isNotEmpty() },
            memory = memory, pluggedStart = plugged0, pluggedEnd = NightWriter.plugged(app),
            left = left, etaMs = eta, queue = q, recent = recent,
        )
        finish(stop, r)
    }

    private fun finish(stop: NightStep.Stop, r: NightReport.Run?) {
        if (finished) return
        finished = true
        hold(false)
        NightWriter.clearNotification(app)
        Crumbs.add("writer: stop ($how): ${stop.reason}")
        val explain = r?.let { NightReport.explain(it) } ?: "It stopped before writing: ${stop.reason}."
        _view.value = _view.value.copy(running = false, phase = "Stopped: ${stop.reason}", ended = explain, working = null,
            why = WriterWhy("stopped", stop.reason))
        NightWriter.lastView = _view.value
        status(false, WriterWhy(if (r == null) "not-started" else "stopped", if (r == null) stop.reason else explain), r?.let {
            com.timerpg.street.dialogue.NightCount(lines = it.lines, variants = it.variants, rejected = it.rejected, timedOut = it.timedOut, failed = it.failed)
        } ?: com.timerpg.street.dialogue.NightCount(), r?.model ?: "")
        // every run that got as far as the model has its report (a short one too: "0 lines" with its reason is what
        // tells why), written before the lock goes: the game, opened, reads the bank once the lock is free and sends the
        // reports waiting then ([com.timerpg.street.shot.ShotUploader.onLaunch]); this process is cached (and frozen by
        // Android) moments after, so its own upload may not finish
        if (r != null) {
            val tz = TimeZone.getDefault()
            val json = NightReport.json(r, com.timerpg.street.shot.DeviceFacts.app(), tz)
            NightWriter.saveLast(app, json, NightReport.summary(r, tz))
            Crumbs.add("writer: $explain")
            runCatching { com.timerpg.street.shot.Reports.writeNight(app, NightReport.path(r.start, tz), json) }
                .onFailure { Crumbs.add("writer: its report could not be written: $it") }
        }
        WriterLock.release()
        if (r != null) com.timerpg.street.shot.ShotUploader.sendNight(app)
        onEnd(stop)
        for (l in stopped) runCatching { l() }
    }

    /** The writer's state for the game's process (the AI panel, the screenshot JSON): [NightWriter.saveStatus]. */
    private fun status(running: Boolean, why: WriterWhy, c: com.timerpg.street.dialogue.NightCount, model: String) =
        NightWriter.saveStatus(app, WriterStatus(System.currentTimeMillis(), how, running, why.state, why.reason,
            c.lines, c.variants, c.rejected, c.timedOut, c.failed, model))

    companion object {
        /** Writer mode: how long the game's screen may take to leave the front before it counts as open. */
        const val GAME_LEAVING_MS = 10_000L
        /** How long Writer mode lets the game's last playtest upload run before it closes the game's process. */
        const val UPLOAD_WAIT_MS = 10_000L
        /** The writer's state is saved for the game's process on every change and at least this often. */
        const val STATUS_MS = 60_000L
        const val REST = "night-rest"
        const val HOT = "night-hot"
        const val NOTIFY_MS = 15_000L
        const val CRUMB_MS = 10 * 60_000L
    }
}
