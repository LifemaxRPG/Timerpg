package com.timerpg.street

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.lifemax.rpg.feature.habits.presentation.scene.FramePace
import com.lifemax.rpg.feature.habits.presentation.scene.HeatSteps
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownJobs
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownQuality
import java.io.File

/**
 * Heat and battery as scheduling (docs/HEAT.md), the Android half: ADPF's performance hints for the frame's threads and
 * the town's workers, and the thermal governor that reads ADPF's thermal headroom and steps the quality settings down
 * gradually before the phone throttles, and back up when it cools ([HeatSteps] holds the rules, the desktop tests them).
 *
 * Never touches the dialogue's path or its rules (the NPU only, never the CPU): the governor changes the frame rate, the
 * drawing resolution's steps, the sun's shadow cascades and how often the far view bakes again, nothing else.
 */
object Heat {
    private var pm: PowerManager? = null
    private var hints: Any? = null
    /** The frame's session (the main thread, which runs the game and submits Filament's frame, and Filament's own threads). */
    private var frameSession: Any? = null
    /** The town workers' session (their busy time a frame, against a looser target: background work, lower clocks). */
    private var workerSession: Any? = null
    private var frameTids = IntArray(0)
    private var workerTids = IntArray(0)
    private var targetNs = 0L
    private var workerTargetNs = 0L
    private var lastBusy = -1L
    private var lastTidScanMs = 0L
    @Volatile private var running = false
    private var thread: Thread? = null

    /** The governor (its level, smoothed headroom and status). */
    val steps = HeatSteps()
    /** The plan in force ([HeatSteps.plan] of [steps]' level). */
    @Volatile var plan: HeatSteps.Plan = HeatSteps.plan(0); private set
    /** Called (on the governor's thread) when the frame rate aimed at changes: the activity asks the display for it. */
    @Volatile var onTarget: ((Int) -> Unit)? = null
    /** Frames reported to the hint sessions, and why they are off if they are. */
    @Volatile var hinted = 0L; private set
    @Volatile var hintState = "off"; private set

    /** Starts the governor (once; idempotent): a thread reading the headroom every [PERIOD_MS], and the status listener. */
    fun start(ctx: Context) {
        if (running) return
        running = true
        pm = ctx.getSystemService(PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= 29) runCatching {
            pm?.addThermalStatusListener(ctx.mainExecutor) { s -> synchronized(this) { if (steps.feed(Float.NaN, s, 0f)) applied("status $s") } }
        }
        thread = Thread({
            var last = android.os.SystemClock.elapsedRealtime()
            while (running) {
                try { Thread.sleep(PERIOD_MS) } catch (_: InterruptedException) { break }
                val now = android.os.SystemClock.elapsedRealtime()
                val dt = (now - last) / 1000f; last = now
                val p = pm ?: continue
                val head = if (Build.VERSION.SDK_INT >= 30) runCatching { p.getThermalHeadroom(FORECAST_S) }.getOrDefault(Float.NaN) else Float.NaN
                val status = runCatching { p.currentThermalStatus }.getOrDefault(0)
                synchronized(this) { if (steps.feed(head, status, dt)) applied("headroom %.2f, status %d".format(java.util.Locale.US, steps.headroom, status)) }
            }
        }, "heat").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    fun stop() { running = false; thread?.interrupt(); thread = null }

    /** The level changed: its plan into the settings the renderer reads at its next frame. */
    private fun applied(why: String) {
        val p = HeatSteps.plan(steps.level)
        val was = FramePace.target
        plan = p
        FramePace.heatCap = p.fpsCap
        TownQuality.heatCascades = p.cascades
        TownQuality.rebakeStretch = p.rebake
        if (TownQuality.autoSteps.setHeatStep(p.resStep)) resolutionChanged = true
        workerTargetNs = (targetNs * p.workerTarget).toLong()
        com.timerpg.street.shot.Crumbs.add("heat: level ${steps.level} ($why): fps cap ${p.fpsCap}, resolution step ${p.resStep}, cascades ${p.cascades}, rebake x${p.rebake}")
        com.timerpg.street.play.PlayLog.phase("heat.${steps.level}")
        if (FramePace.target != was) onTarget?.invoke(FramePace.target)
    }
    /** The resolution's step changed by the governor: the renderer applies it at its next frame (and clears this). */
    @Volatile var resolutionChanged = false

    /**
     * The frame's work is done ([workNs] on the main thread, start to Filament's endFrame) at the frame rate aimed at
     * ([fps]): reported to the frame's session, and the workers' busy time since the last frame to theirs (API 31+; the
     * sessions are made at the first call and their threads found again every few seconds as the town's workers start).
     */
    fun frameDone(workNs: Long, fps: Int) {
        if (Build.VERSION.SDK_INT < 31) { hintState = "needs Android 12"; return }
        try {
            val t = 1_000_000_000L / fps.coerceAtLeast(1)
            val now = android.os.SystemClock.elapsedRealtime()
            if (hints == null) hints = (ContextHolder.ctx ?: return).getSystemService(android.os.PerformanceHintManager::class.java) ?: run { hintState = "no hint manager"; return }
            val hm = hints as android.os.PerformanceHintManager
            if (now - lastTidScanMs > TID_SCAN_MS) {
                lastTidScanMs = now
                val (f, w) = threads()
                if (!f.contentEquals(frameTids)) { frameTids = f; (frameSession as? android.os.PerformanceHintManager.Session)?.close(); frameSession = null }
                if (!w.contentEquals(workerTids)) { workerTids = w; (workerSession as? android.os.PerformanceHintManager.Session)?.close(); workerSession = null }
            }
            if (t != targetNs) {
                targetNs = t; workerTargetNs = (t * plan.workerTarget).toLong()
                (frameSession as? android.os.PerformanceHintManager.Session)?.updateTargetWorkDuration(t)
            }
            if (frameSession == null && frameTids.isNotEmpty()) frameSession = hm.createHintSession(frameTids, targetNs)
            if (workerSession == null && workerTids.isNotEmpty()) workerSession = hm.createHintSession(workerTids, workerTargetNs)
            val fs = frameSession as? android.os.PerformanceHintManager.Session
            if (fs == null) { hintState = "no session"; return }
            fs.reportActualWorkDuration(workNs.coerceAtLeast(1000L))
            // the workers: their busy time since the last frame, a worker's share of it (against the looser target)
            val ws = workerSession as? android.os.PerformanceHintManager.Session
            val busy = TownJobs.busyNs()
            if (ws != null && lastBusy >= 0L) {
                val d = (busy - lastBusy) / workerTids.size.coerceAtLeast(1)
                if (d > 0L) { ws.updateTargetWorkDuration(workerTargetNs.coerceAtLeast(1_000_000L)); ws.reportActualWorkDuration(d) }
            }
            lastBusy = busy
            hinted++
            hintState = "frame ${frameTids.size} threads, workers ${workerTids.size}"
        } catch (t: Throwable) {
            hintState = "failed: ${t.javaClass.simpleName}"
        }
    }

    /**
     * The process's threads by name (/proc/self/task/<tid>/comm): the frame's (the main thread, Filament's driver thread
     * "FEngine::loop" and its job threads "JobSystem::loo...") and the town's workers ("town-build").
     */
    private fun threads(): Pair<IntArray, IntArray> {
        val frame = ArrayList<Int>(); val work = ArrayList<Int>()
        frame += android.os.Process.myPid()
        for (d in File("/proc/self/task").listFiles() ?: emptyArray()) {
            val tid = d.name.toIntOrNull() ?: continue
            val name = runCatching { File(d, "comm").readText().trim() }.getOrNull() ?: continue
            when {
                name.startsWith("FEngine") || name.startsWith("JobSystem") -> if (tid !in frame) frame += tid
                name.startsWith("town-build") -> work += tid
            }
        }
        return frame.toIntArray() to work.toIntArray()
    }

    /** The governor's state for the play log's lines and the panel: level, headroom, status, what it holds. */
    fun line(): String = "level %d, headroom %s, status %d, fps %d (chosen %d), resolution %.2f, cascades %d, rebake x%.0f; hints: %s".format(
        java.util.Locale.US, steps.level, if (steps.headroom.isNaN()) "n/a" else "%.2f".format(java.util.Locale.US, steps.headroom), steps.status,
        FramePace.target, FramePace.chosen, TownQuality.autoSteps.scale, TownQuality.heatCascades, TownQuality.rebakeStretch, hintState)

    const val PERIOD_MS = 2000L
    /** ADPF's forecast: how far ahead the headroom is asked for (s). */
    const val FORECAST_S = 10
    const val TID_SCAN_MS = 5000L
}

/** The application's context for code with none to hand (set by the activity). */
object ContextHolder { @Volatile var ctx: Context? = null }
