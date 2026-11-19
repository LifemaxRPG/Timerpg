package com.timerpg.street.night

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.timerpg.street.dialogue.DialogueEngine
import com.timerpg.street.dialogue.ModelState
import com.timerpg.street.shot.Crumbs
import java.util.TimeZone
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One night's writing (on its own thread): every [TICK_MS] it reads the phone ([NightInputs]) and does what
 * [NightRules] says: lets the dialogue engine write ([DialogueEngine.beginNight]; a partial wake lock held only while
 * it does), holds it (bursts, cooling down, a hot pause), or stops. The model is loaded once for the session, with the
 * game's own memory checks ([com.timerpg.street.llm.MemoryWatch], the engine's load gate), on the device the AI panel
 * or the NPU tuner chose, and let go when it stops. The progress goes to the notification, the start, stop and speed
 * to the breadcrumbs, and the summary to the AI panel and a report ([NightReport]).
 */
class NightSession(private val app: Context, val how: String, private val onEnd: (NightStep.Stop) -> Unit) {
    private val wakeups = LinkedBlockingQueue<String>()
    @Volatile private var stopWith: String? = null
    private val thread = Thread({ runCatching { run() }.onFailure { Crumbs.add("overnight writer failed: $it") ; finish(NightStep.Stop("failed: ${it.javaClass.simpleName}", true), null, null) } }, "night-writer")
    @Volatile private var finished = false

    fun start() = thread.start()

    private val stopped = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    /** [l] runs (on the session's thread) once it has stopped. */
    fun onStopped(l: () -> Unit) { if (finished) l() else stopped += l }

    /** Stops the session (from any thread) for [reason]; it ends within a moment. */
    fun stop(reason: String) { if (stopWith == null) stopWith = reason; wakeups.offer(reason) }

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
    private fun headroom(): Float = if (Build.VERSION.SDK_INT >= 30) runCatching { pm.getThermalHeadroom(10) }.getOrDefault(Float.NaN) else Float.NaN

    private fun run() {
        val t0 = System.currentTimeMillis()
        val rules = NightRules()
        val battery0 = NightWriter.battery(app)
        fun inputs(left: Int, gaveUp: String?) = NightInputs(
            enabled = NightWriter.enabled(app), plugged = NightWriter.plugged(app), battery = NightWriter.battery(app),
            gameOpen = NightWriter.gameOpen, tuning = com.timerpg.street.NpuTune.active(app), thermal = thermal(), headroom = headroom(),
            left = left, modelGaveUp = gaveUp,
        )
        // nothing loads when it would stop at once (unplugged meanwhile, the game open, tuning)
        (rules.step(inputs(-1, null), SystemClock.elapsedRealtime()) as? NightStep.Stop)?.let { return finish(it, null, null) }

        com.timerpg.street.AppInit.basics(app)
        com.timerpg.street.AppInit.native(app)
        Crumbs.add("overnight writer: start ($how), battery $battery0%, thermal ${thermal()}, ${com.timerpg.street.llm.Ram.freeMb(app)} MB free")
        if (!DialogueEngine.sharedExists) DialogueEngine.startDir = NightWriter.townDir(app)
        val engine = DialogueEngine.shared(app)
        // the game's memory care: the watch's levels and Android's trim calls go to the engine as they do in the game
        com.timerpg.street.llm.MemoryWatch.start(app)
        val onMemory: (Int) -> Unit = { engine.onMemoryLevel(it) }
        com.timerpg.street.llm.MemoryWatch.listeners += onMemory
        val trim = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) { Crumbs.add("overnight writer: trim memory $level"); engine.onTrimMemory(level) }
            override fun onConfigurationChanged(newConfig: Configuration) {}
            @Deprecated("") override fun onLowMemory() {}
        }
        app.registerComponentCallbacks(trim)
        engine.beginNight(gameVisible = NightWriter.gameOpen)

        var last = SystemClock.elapsedRealtime()
        val spent = LongArray(NightStep.Kind.values().size)
        var writingMs = 0L
        var lastNotify = 0L
        var lastCrumb = last
        var reloads = 0
        var lastReload = 0L
        var gaveUp: String? = null
        var stop: NightStep.Stop
        var notLoadedSince = -1L
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val st = engine.status.value
            // the model: loaded once; after a memory unload or a load put off, tried again now and then, then given up
            when (st.state) {
                ModelState.NO_MODEL, ModelState.NO_ENGINE -> gaveUp = "no dialogue model on the phone"
                ModelState.READY -> notLoadedSince = -1L
                else -> {
                    if (notLoadedSince < 0) notLoadedSince = now
                    if ((st.state == ModelState.UNLOADED || st.state == ModelState.ERROR) && now - lastReload > RELOAD_MS) {
                        if (++reloads > MAX_RELOADS) gaveUp = "the model would not load (${st.detail.take(80)})"
                        else { lastReload = now; engine.nightReload() }
                    }
                    if (now - notLoadedSince > LOAD_GIVE_UP_MS) gaveUp = "the model was not ready for ${LOAD_GIVE_UP_MS / 60_000} minutes (${st.detail.take(80)})"
                }
            }
            val step = rules.step(inputs(engine.nightLeft(), gaveUp), now)
            val dt = now - last; last = now
            val asked = stopWith
            if (asked != null) { stop = NightStep.Stop(asked, again = asked.startsWith("the system")); break }
            when (step) {
                is NightStep.Stop -> { stop = step; break }
                is NightStep.Rest -> {
                    spent[step.kind.ordinal] += dt
                    engine.pause(REST, stopRunning = step.kind == NightStep.Kind.HOT)
                    hold(false)
                }
                NightStep.Write -> {
                    writingMs += dt
                    engine.resume(REST)
                    hold(true)
                }
            }
            if (now - lastNotify > NOTIFY_MS) {
                lastNotify = now
                val c = engine.nightCount()
                val p = engine.status.value.night
                val why = (step as? NightStep.Rest)?.reason ?: if (st.state != ModelState.READY) st.detail.take(60) else null
                NightWriter.notify(app, p?.label ?: "Writing dialogue", listOfNotNull("%.1f tok/s".format(c.genPerSec).takeIf { c.genMs > 0 }, why).joinToString(" · ").ifEmpty { null })
            }
            if (now - lastCrumb > CRUMB_MS) {
                lastCrumb = now
                val c = engine.nightCount()
                Crumbs.add("overnight writer: ${c.lines} lines + ${c.variants} variants, %.1f tok/s, thermal ${thermal()}, battery ${NightWriter.battery(app)}%%".format(c.genPerSec))
            }
            wakeups.poll(TICK_MS, TimeUnit.MILLISECONDS)
        }
        // the end: the engine back to the game's rules, the model let go if this session loaded it
        engine.resume(REST)
        hold(false)
        val c = engine.nightCount()
        val bank = engine.bank()
        engine.endNight(release = true)
        com.timerpg.street.llm.MemoryWatch.listeners -= onMemory
        runCatching { app.unregisterComponentCallbacks(trim) }
        val r = NightReport.Run(
            start = t0, end = System.currentTimeMillis(), how = how, stopReason = stop.reason,
            lines = c.lines, variants = c.variants, rejected = c.rejected, genTokens = c.genTokens, genMs = c.genMs,
            prefillTokens = c.prefillTokens, prefillMs = c.prefillMs, writingMs = writingMs,
            coolMs = spent[NightStep.Kind.COOL.ordinal], hotMs = spent[NightStep.Kind.HOT.ordinal],
            burstRestMs = spent[NightStep.Kind.BURST.ordinal], batteryWaitMs = spent[NightStep.Kind.BATTERY.ordinal],
            coolDowns = rules.coolDowns, hotPauses = rules.hotPauses, maxThermal = rules.maxThermal,
            batteryStart = battery0, batteryEnd = NightWriter.battery(app),
            runsOn = listOf("CPU", "GPU", "NPU").getOrElse(engine.status.value.runsOn) { "?" },
            bank = bank, themes = engine.steer.summary(withText = NightWriter.shareWords(app)), answers = engine.answerDays(),
        )
        finish(stop, r, engine)
    }

    private fun finish(stop: NightStep.Stop, r: NightReport.Run?, engine: DialogueEngine?) {
        if (finished) return
        finished = true
        hold(false)
        NightWriter.clearNotification(app)
        Crumbs.add("overnight writer: stop ($how): ${stop.reason}")
        if (r != null && (r.written > 0 || r.end - r.start > 60_000L)) {
            val tz = TimeZone.getDefault()
            val json = NightReport.json(r, com.timerpg.street.shot.DeviceFacts.app(), tz)
            NightWriter.saveLast(app, json, NightReport.summary(r, tz))
            runCatching { com.timerpg.street.shot.Reports.writeNight(app, NightReport.path(r.start, tz), json) }
            if (com.timerpg.street.shot.ShotSettings.token(app) != null) com.timerpg.street.shot.ShotUploader.sendNight(app)
        }
        onEnd(stop)
        for (l in stopped) runCatching { l() }
    }

    companion object {
        const val REST = "night-rest"
        const val TICK_MS = 5_000L
        const val NOTIFY_MS = 15_000L
        const val CRUMB_MS = 10 * 60_000L
        const val RELOAD_MS = 3 * 60_000L
        const val MAX_RELOADS = 5
        const val LOAD_GIVE_UP_MS = 20 * 60_000L
    }
}
