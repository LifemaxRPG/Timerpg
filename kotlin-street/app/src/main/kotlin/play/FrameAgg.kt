package com.timerpg.street.play

/**
 * The frames of one second, gathered on the render thread and summed up once a second by the recorder's own thread
 * ([PlayLog]): fixed arrays sized for a 240 Hz display, filled in place, so the per-frame path ([frame]) allocates
 * nothing. [close] sorts a copy (in a scratch array kept for it) for the percentiles, fills a [Second] and starts the
 * next second. Both sides take this object's lock: uncontended on the render thread (the close runs once a second).
 */
class FrameAgg(val capacity: Int = 512) {
    private val interval = FloatArray(capacity)
    private val cpu = FloatArray(capacity)
    private val gpu = FloatArray(capacity)
    private val scratch = FloatArray(capacity)
    private var nInterval = 0
    private var nCpu = 0
    private var nGpu = 0
    private var frames = 0
    private var moving = 0
    private var spikes = 0
    private var lastAt = 0L
    private var lastInterval = 0f
    private var lastGpuId = -1L
    private var drawsSum = 0L
    private var trisSum = 0L
    private var trisMax = 0
    private var visibleSum = 0L
    private var statFrames = 0
    private val partNs = LongArray(PARTS)
    private val uploads = IntArray(UPLOADS)
    private var upBytes = 0L
    // touch-down to the next frame begun after it (sampled)
    @Volatile private var touchAt = 0L
    private var touchLastSample = 0L
    private var touchMs = -1f

    /**
     * One frame drawn, as it ends: [now] its end (nanoTime), [startedAt] when its work began, [cpuMs] its CPU time on
     * the render thread, [gpuMs] the GPU's time of frame [gpuFrameId] (-1 when the driver says nothing; each GPU frame
     * counted once), [inMotion] whether frames should come at the display's rate, and what the town drew (-1: unknown).
     * [parts] and [ups] are FrameWork's per-part nanoseconds and upload counts of this frame (read, not kept). Returns the
     * interval since the last frame (ms; 0 for the first).
     */
    @Synchronized
    fun frame(now: Long, startedAt: Long, cpuMs: Float, gpuMs: Float, gpuFrameId: Long, inMotion: Boolean,
              draws: Int, triangles: Int, visible: Int, parts: LongArray?, ups: IntArray?, bytes: Long): Float {
        val iv = if (lastAt == 0L) 0f else (now - lastAt) / 1e6f
        lastAt = now
        frames++
        if (inMotion) moving++
        // (the interval counts only while frames flow: a scene standing still draws nothing, and its gaps are not frames)
        if (iv > 0f && (inMotion || lastInterval in 0.5f..FLOW_MS)) {
            if (nInterval < capacity) interval[nInterval++] = iv
            if (iv > SPIKE_MS) spikes++
        }
        lastInterval = iv
        if (nCpu < capacity) cpu[nCpu++] = cpuMs
        if (gpuMs > 0f && gpuFrameId > lastGpuId) {
            lastGpuId = gpuFrameId
            if (nGpu < capacity) gpu[nGpu++] = gpuMs
        }
        if (draws >= 0) {
            statFrames++
            drawsSum += draws; trisSum += triangles; visibleSum += visible
            if (triangles > trisMax) trisMax = triangles
        }
        if (parts != null) for (i in 0 until minOf(PARTS, parts.size)) partNs[i] += parts[i]
        if (ups != null) for (i in 0 until minOf(UPLOADS, ups.size)) uploads[i] += ups[i]
        upBytes += bytes
        val t = touchAt
        if (t != 0L && startedAt >= t) {
            touchMs = (now - t) / 1e6f
            touchAt = 0L
        }
        return iv
    }

    /** A touch went down at [now] (nanoTime): the next frame begun after it is timed, at most one each [TOUCH_EVERY_NS]. */
    fun touch(now: Long) {
        if (touchAt != 0L || now - touchLastSample < TOUCH_EVERY_NS) return
        touchLastSample = now
        touchAt = now
    }

    /** The second so far into [out], and a new second begun. */
    @Synchronized
    fun close(out: Second) {
        out.frames = frames
        out.moving = moving
        out.spikes = spikes
        pct(interval, nInterval, out.frame)
        pct(cpu, nCpu, out.cpu)
        pct(gpu, nGpu, out.gpu)
        out.flowing = nInterval
        out.gpuFrames = nGpu
        if (statFrames > 0) {
            out.draws = (drawsSum / statFrames).toInt(); out.triangles = (trisSum / statFrames).toInt()
            out.trianglesMax = trisMax; out.visible = (visibleSum / statFrames).toInt()
        } else { out.draws = -1; out.triangles = -1; out.trianglesMax = -1; out.visible = -1 }
        for (i in 0 until PARTS) out.partMs[i] = if (frames > 0) partNs[i] / 1e6f / frames else 0f
        System.arraycopy(uploads, 0, out.uploads, 0, UPLOADS)
        out.uploadBytes = upBytes
        out.touchMs = touchMs
        nInterval = 0; nCpu = 0; nGpu = 0; frames = 0; moving = 0; spikes = 0
        drawsSum = 0; trisSum = 0; trisMax = 0; visibleSum = 0; statFrames = 0
        java.util.Arrays.fill(partNs, 0L); java.util.Arrays.fill(uploads, 0); upBytes = 0L
        touchMs = -1f
    }

    /** p50, p95, p99 and the worst of the first [n] of [v] into [out] (all -1 with none), sorted in the scratch array. */
    private fun pct(v: FloatArray, n: Int, out: FloatArray) {
        if (n == 0) { out.fill(-1f); return }
        System.arraycopy(v, 0, scratch, 0, n)
        java.util.Arrays.sort(scratch, 0, n)
        out[0] = Pct.at(scratch, n, 50); out[1] = Pct.at(scratch, n, 95); out[2] = Pct.at(scratch, n, 99); out[3] = scratch[n - 1]
    }

    companion object {
        /** FrameWork's parts and upload kinds (its NAMES and UP_NAMES; more are ignored). */
        const val PARTS = 12
        const val UPLOADS = 5
        /** A frame interval this long (ms) while frames flow is a spike (FrameSpikes' 60 Hz refresh missed). */
        const val SPIKE_MS = 20f
        /** An interval up to this (ms) after a frame means frames are flowing (two 60 Hz refreshes). */
        const val FLOW_MS = 34f
        const val TOUCH_EVERY_NS = 2_000_000_000L
    }
}

/** One second's numbers ([FrameAgg.close]): reused, never allocated per second. Percentile arrays: p50, p95, p99, max. */
class Second {
    var frames = 0
    var moving = 0
    var spikes = 0
    /** Intervals counted in [frame] (frames flowing). */
    var flowing = 0
    var gpuFrames = 0
    val frame = FloatArray(4)
    val cpu = FloatArray(4)
    val gpu = FloatArray(4)
    var draws = -1
    var triangles = -1
    var trianglesMax = -1
    var visible = -1
    val partMs = FloatArray(FrameAgg.PARTS)
    val uploads = IntArray(FrameAgg.UPLOADS)
    var uploadBytes = 0L
    var touchMs = -1f
}

/** Percentiles of a sorted array (nearest rank). */
object Pct {
    /** The [p]th percentile of the first [n] of sorted [s]. */
    fun at(s: FloatArray, n: Int, p: Int): Float {
        if (n <= 0) return -1f
        val rank = ((p * n + 99) / 100).coerceIn(1, n)
        return s[rank - 1]
    }
}
