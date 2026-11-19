package com.timerpg.street

import com.lifemax.rpg.feature.habits.presentation.scene.FrameWork

/**
 * The frame-spike profiler (0.1.385's "it feels like it's stuttering at times"): every frame of the town's drawn on the
 * main thread is looked at as it ends ([frame], from the Filament host), and one that came more than [LATE_MS] after the
 * last while frames were coming at the display's rate is a spike, kept with what ran in it: the game's own parts
 * ([FrameWork]: the step, the build, the cutaway, the far boxes, the buildings, the night light, the yards, the greenery and
 * its uploads, the stores, the people, Filament's own CPU time), the uploads (tree cells, far cells, buildings, people's
 * sheets, ground jobs, bytes), the GPU's time for the frames Filament last heard about, garbage collections since the last
 * frame (ART's counts), the HUD's recompositions ([recomposed]), and which of the AI's models was running ([aiNow]).
 * The GPU's own frames over a refresh are counted too (a TextureView shows the last finished frame again when the GPU is
 * late: a stutter the main thread never sees).
 *
 * [window] sums the last 10 s for the breadcrumbs: spikes by cause, and the worst frames in full. Allocates nothing per
 * frame except ART's GC counters' strings.
 */
object FrameSpikes {
    /** A frame later than this after the one before it is a spike (ms): a 60 Hz refresh missed. */
    const val LATE_MS = 20f
    /** A part of the frame this long (ms) is named as a cause of a spike. */
    const val PART_MS = 3f
    /** The GPU over this (ms) misses a 60 Hz refresh. */
    const val GPU_BUDGET_MS = 16.7f

    /** The last [window]'s line (the screenshot's state JSON carries it). */
    @Volatile var last: String? = null
    /** The HUD's recompositions, counted by its composables (a SideEffect): read and cleared each frame. */
    @Volatile var recomposed = 0
    /** What the AI's lane runs now ("dialogue", "voice", "speech", "memory") or null: [com.timerpg.street.llm.AiLane.current]. */
    private fun aiNow(): String? = com.timerpg.street.llm.AiLane.current

    private var lastAt = 0L
    private var lastInterval = 0f
    private var gcCount = -1L
    private var gcBlocking = -1L
    private var gcTimeMs = -1L
    private var lastGpuId = -1
    private val gpuRecent = FloatArray(3)

    // the window
    private var frames = 0
    private var moving = 0
    private var spikes = 0
    private var gpuOver = 0
    private var gpuFrames = 0
    private var gpuWorst = 0f
    private var gcs = 0
    private var gcMs = 0L
    private val causes = IntArray(FrameWork.NAMES.size + EXTRA.size)
    private val worst = Array(3) { Spike() }

    private class Spike {
        var ms = 0f
        var line = ""
    }

    private val EXTRA = arrayOf("gpu", "gc", "gc (blocking)", "AI", "HUD", "uploads", "unexplained")
    private const val X_GPU = 0; private const val X_GC = 1; private const val X_GC_BLOCK = 2; private const val X_AI = 3
    private const val X_HUD = 4; private const val X_UP = 5; private const val X_NONE = 6

    private fun stat(name: String): Long = try { android.os.Debug.getRuntimeStat(name)?.toLongOrNull() ?: -1L } catch (t: Throwable) { -1L }

    /**
     * The frame just drawn ([FrameWork] holds its parts; cleared here): [inMotion] whether frames should be coming at the
     * display's rate (the player or the camera moving), [history] Filament's frame infos newest first ([got] of them).
     */
    fun frame(inMotion: Boolean, history: Array<com.google.android.filament.Renderer.FrameInfo>, got: Int) {
        val now = System.nanoTime()
        val interval = if (lastAt == 0L) 0f else (now - lastAt) / 1e6f
        lastAt = now
        // the GPU's frames, each once
        for (k in (got - 1) downTo 0) {
            val fi = history[k]
            if (fi.frameId <= lastGpuId || fi.gpuFrameDuration <= 0) continue
            lastGpuId = fi.frameId
            val g = fi.gpuFrameDuration / 1e6f
            gpuRecent[2] = gpuRecent[1]; gpuRecent[1] = gpuRecent[0]; gpuRecent[0] = g
            gpuFrames++
            if (g > GPU_BUDGET_MS) gpuOver++
            if (g > gpuWorst) gpuWorst = g
        }
        // the collections since the last frame (ART's counters; -1 where it does not tell)
        val c = stat("art.gc.gc-count"); val cb = stat("art.gc.blocking-gc-count"); val ct = stat("art.gc.gc-time")
        val gcNow = if (gcCount >= 0 && c > gcCount) (c - gcCount).toInt() else 0
        val gcBlockNow = if (gcBlocking >= 0 && cb > gcBlocking) (cb - gcBlocking).toInt() else 0
        val gcMsNow = if (gcTimeMs >= 0 && ct > gcTimeMs) ct - gcTimeMs else 0L
        gcCount = c; gcBlocking = cb; gcTimeMs = ct
        gcs += gcNow; gcMs += gcMsNow
        val hud = recomposed; recomposed = 0
        val ai = aiNow()
        frames++
        if (inMotion) moving++
        val late = interval > LATE_MS && (inMotion || lastInterval in 1f..LATE_MS)
        lastInterval = interval
        if (late) {
            spikes++
            var named = false
            for (p in FrameWork.NAMES.indices) if (FrameWork.ns[p] / 1e6f > PART_MS) { causes[p]++; named = true }
            val gpu = maxOf(gpuRecent[0], gpuRecent[1], gpuRecent[2])
            if (gpu > GPU_BUDGET_MS) { causes[FrameWork.NAMES.size + X_GPU]++; named = true }
            if (gcBlockNow > 0) { causes[FrameWork.NAMES.size + X_GC_BLOCK]++; named = true }
            else if (gcNow > 0) { causes[FrameWork.NAMES.size + X_GC]++; named = true }
            if (ai != null) { causes[FrameWork.NAMES.size + X_AI]++; named = true }
            if (hud > 0) causes[FrameWork.NAMES.size + X_HUD]++
            val ups = FrameWork.uploads.sum()
            if (ups > 0) causes[FrameWork.NAMES.size + X_UP]++
            if (!named) causes[FrameWork.NAMES.size + X_NONE]++
            // the worst three in full
            val slot = worst.minByOrNull { it.ms }!!
            if (interval > slot.ms) {
                slot.ms = interval
                slot.line = describe(interval, gpu, gcNow, gcBlockNow, gcMsNow, hud, ai)
            }
        }
        FrameWork.reset()
    }

    private fun describe(interval: Float, gpu: Float, gc: Int, gcBlock: Int, gcMs: Long, hud: Int, ai: String?): String {
        val sb = StringBuilder()
        sb.append("%.0f ms: ours %.1f".format(java.util.Locale.US, interval, FrameWork.totalMs()))
        val parts = FrameWork.NAMES.indices.filter { FrameWork.ns[it] > 300_000L }.sortedByDescending { FrameWork.ns[it] }
        if (parts.isNotEmpty()) sb.append(" (").append(parts.joinToString { "%s %.1f".format(java.util.Locale.US, FrameWork.NAMES[it], FrameWork.ns[it] / 1e6f) }).append(")")
        if (gpu > 0f) sb.append(", gpu %.1f".format(java.util.Locale.US, gpu))
        val ups = FrameWork.UP_NAMES.indices.filter { FrameWork.uploads[it] > 0 }
        if (ups.isNotEmpty() || FrameWork.bytes > 0) sb.append(", up ").append(ups.joinToString { "${FrameWork.uploads[it]} ${FrameWork.UP_NAMES[it]}" })
            .append(" %.1f MB".format(java.util.Locale.US, FrameWork.bytes / 1048576f))
        if (gc > 0) sb.append(", gc $gc${if (gcBlock > 0) " ($gcBlock blocking)" else ""} ${gcMs} ms")
        if (hud > 0) sb.append(", HUD x$hud")
        if (ai != null) sb.append(", AI $ai")
        return sb.toString()
    }

    /** The last window (10 s) for the breadcrumbs, then a new one. */
    fun window(): String {
        val sb = StringBuilder()
        sb.append("spikes: $spikes of $frames frames (moving $moving) over %.0f ms".format(java.util.Locale.US, LATE_MS))
        val named = (0 until causes.size).filter { causes[it] > 0 }.sortedByDescending { causes[it] }
        if (named.isNotEmpty()) sb.append(" by ").append(named.joinToString { (if (it < FrameWork.NAMES.size) FrameWork.NAMES[it] else EXTRA[it - FrameWork.NAMES.size]) + " " + causes[it] })
        sb.append("; GPU over %.1f ms in %d of %d frames (worst %.1f)".format(java.util.Locale.US, GPU_BUDGET_MS, gpuOver, gpuFrames, gpuWorst))
        sb.append("; gc %d (%d ms)".format(java.util.Locale.US, gcs, gcMs))
        val w = worst.filter { it.ms > 0f }.sortedByDescending { it.ms }
        if (w.isNotEmpty()) sb.append("; worst: ").append(w.joinToString(" | ") { it.line })
        last = sb.toString()
        frames = 0; moving = 0; spikes = 0; gpuOver = 0; gpuFrames = 0; gpuWorst = 0f; gcs = 0; gcMs = 0L
        causes.fill(0); for (s in worst) { s.ms = 0f; s.line = "" }
        return sb.toString()
    }
}
