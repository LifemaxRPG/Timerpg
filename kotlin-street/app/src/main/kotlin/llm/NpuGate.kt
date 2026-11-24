package com.timerpg.street.llm

import org.json.JSONArray
import org.json.JSONObject

/**
 * How much memory beyond Android's "available" count ([MemProbe]) a model's copy on the NPU (or the GPU) may count on
 * when the game is in front: what Android finds for a foreground app when it asks (cached background apps ended, memory
 * pushed out to compressed swap). The old gate counted none of it, so with the town loaded the light model's NPU copy
 * (about 3.6 GB with the margin) never loaded beside 2.0 to 3.6 GB "free" and the model ran on the CPU.
 *
 * Pure (no Android: unit tested in NpuGateTest). The figure, in order:
 * 1. none when the setting is off, or when a try of a copy as big or smaller, with about as much free memory or more, failed
 *    in the last week (the load failed, the safety valve stopped it, the game was killed during it, or memory went
 *    critical soon after);
 * 2. what was measured: the median (the lower one of an even count) of what Android found in the last [MEASURED_N] loads
 *    that measured it (the NPU fit test, or a try in play) and needed it (less free than they needed: a load with room
 *    to spare finds about nothing, which says nothing of what Android could find), capped at [CAP_MB]. Until 2026-11-24
 *    it took the least of the last three: 691 MB while two phone measurements said ~1.5 GB (MEMORY_AUDIT.md, M5). The
 *    median is cross-checked with the phone now ([crossCap]): never more than the kernel's own reclaimable count beyond
 *    Android's (MemAvailable less availMem) plus the free swap;
 * 3. else a first guess, [SWAP_SHARE] of the free swap, at most [GUESS_CAP_MB], until a load measures it.
 * Every try is guarded: the load is watched and stopped if free memory falls to Android's threshold plus [VALVE_MB],
 * and a failure falls back NPU, then GPU, then the CPU reading the mapped file.
 *
 * Also the device copy's margin ([marginAfterHeld], [marginAfterTrouble]): what must stay free beside the model once it
 * is in, learned per phone instead of the flat [Ram.MARGIN_MB] (MEMORY_AUDIT.md, M4).
 */
object NpuGate {
    /** A load of a device copy: with how much free (MB), what it needed, whether it held, what Android found beyond
     *  the free count (MB; -1 not measured), and why it failed. [measure]: the fit test's own load (not a try in play). */
    data class Try(val atMs: Long, val freeMb: Long, val needMb: Long, val ok: Boolean, val foundMb: Long = -1, val why: String = "",
                   val measure: Boolean = false)

    data class Verdict(val reclaimMb: Long, val why: String)

    const val SWAP_SHARE = 0.5
    const val GUESS_CAP_MB = 1536L
    const val CAP_MB = 4096L
    const val VALVE_MB = 250L
    /** After a failed try, the next is made only with this much more free memory than it had (no reload loop). */
    const val RETRY_MB = 300L
    const val WEEK_MS = 7L * 24 * 3600 * 1000
    /** A try whose model is let go for critical memory within this long after its load counts as failed; tries kept. */
    const val HOLD_MS = 5L * 60 * 1000
    const val KEEP = 16
    /** How many measured loads the reclaim's median is taken over. */
    const val MEASURED_N = 5

    /**
     * The most Android can plausibly find beyond its free count now (MB), to cross-check a measured median: what the
     * kernel counts as reclaimable beyond Android's own count ([memAvailableMb] less [availMb], never below 0) plus the
     * free swap (zram takes cached apps' and our own idle pages). Ending cached apps finds more than this, so it is a
     * loose cap: it bites only when the swap is nearly full. -1: not readable (no cap).
     */
    fun crossCap(memAvailableMb: Long, availMb: Long, swapFreeMb: Long): Long =
        if (memAvailableMb < 0 || swapFreeMb < 0 || availMb < 0) -1 else (memAvailableMb - availMb).coerceAtLeast(0) + swapFreeMb

    /** The median of [v] (the lower middle one of an even count: the cautious side). */
    fun lowerMedian(v: List<Long>): Long = v.sorted().let { it[(it.size - 1) / 2] }

    fun reclaim(freeMb: Long, needMb: Long, swapFreeMb: Long, tries: List<Try>, nowMs: Long, on: Boolean = true, capMb: Long = -1): Verdict {
        if (!on) return Verdict(0, "counting what Android frees on demand is off (AI panel)")
        val failed = tries.filter { !it.ok && nowMs - it.atMs < WEEK_MS && it.freeMb + RETRY_MB >= freeMb && it.needMb in 1..(needMb + 100) }.maxByOrNull { it.atMs }
        if (failed != null) return Verdict(0, "a try with ${failed.freeMb} MB free failed (${failed.why.ifEmpty { "no reason" }}), so not again with $freeMb MB free this week")
        // (only loads that leaned on the margin measure what Android finds: a load with more free than it needed can come
        // out of the free memory, its "found" about 0 however much Android could have found, and counting it would hold
        // back the light model's copy in play)
        val found = tries.filter { it.ok && it.foundMb >= 0 && it.freeMb < it.needMb }.sortedByDescending { it.atMs }.take(MEASURED_N)
        if (found.isNotEmpty()) {
            val m = lowerMedian(found.map { it.foundMb }).coerceAtMost(CAP_MB)
            val what = "the median of what Android found beyond its free count in the last ${found.size} measured load${if (found.size > 1) "s" else ""} " +
                "(${found.joinToString("/") { it.foundMb.toString() }} MB)"
            if (capMb in 0 until m) return Verdict(capMb, "$what is $m MB, but the phone now has at most $capMb MB to find (kernel reclaimable beyond Android's count plus free swap)")
            return Verdict(m, what)
        }
        if (swapFreeMb <= 0) return Verdict(0, "no swap free and no load measured yet")
        val g = (swapFreeMb * SWAP_SHARE).toLong().coerceAtMost(GUESS_CAP_MB)
        return Verdict(g, "a first guess (half the free swap, at most $GUESS_CAP_MB MB) until a load measures it")
    }

    /**
     * What Android found during a load (MB): the memory the copy took ([copiedMb], weights plus its own buffers) less what
     * the free count actually fell by. Positive: it ended apps or swapped to make room; about 0: the copy came out of
     * the free memory.
     */
    fun found(freeBeforeMb: Long, freeAfterMb: Long, copiedMb: Long): Long = (copiedMb - (freeBeforeMb - freeAfterMb)).coerceAtLeast(0)

    // ------------------------------------------------------------------------------------------------ the margin

    /**
     * The device copy's margin: the free memory the gate keeps beside the model once it is in (the need is the copy, its
     * own memory and this). It starts at [MARGIN_START_MB] (the old flat [Ram.MARGIN_MB]: Android's threshold 216 + the
     * critical line 500 + ~180 for the town's growth) and learns from the phone:
     * - after a load that held [HOLD_MS] with no trim, kill or critical memory, it shrinks toward what that load showed was
     *   enough: the lowest free memory seen from the load through the hold, less the critical line ([MemoryWatch.CRITICAL_MB]
     *   above Android's threshold), is slack the margin did not need. Half of it, at most [MARGIN_STEP_MB] a load, never
     *   below [MARGIN_MIN_MB] (the safety valve's level and a little more);
     * - after a failed try, a kill, critical memory soon after a load or a low-memory signal (onTrimMemory), it grows by
     *   [MARGIN_GROW_MB], at most [MARGIN_MAX_MB].
     * So a phone where loads keep holding with room to spare asks less, one where they come close asks more. The margin
     * is the free memory expected after the load beyond what Android finds, so a phone where Android finds more than the
     * median counted ends up with a smaller margin: the gate's arithmetic follows what the loads really did.
     */
    const val MARGIN_START_MB = 900L
    const val MARGIN_MIN_MB = 500L
    const val MARGIN_MAX_MB = 1500L
    const val MARGIN_STEP_MB = 100L
    const val MARGIN_GROW_MB = 200L

    /** The margin after a load that held: [lowestFreeMb] the least free seen from the load through the hold window. */
    fun marginAfterHeld(marginMb: Long, lowestFreeMb: Long, thresholdMb: Long): Long {
        if (lowestFreeMb < 0 || thresholdMb < 0) return marginMb
        val slack = lowestFreeMb - (thresholdMb + MemoryWatch.CRITICAL_MB)
        if (slack <= 0) return marginMb
        return (marginMb - minOf(slack / 2, MARGIN_STEP_MB)).coerceAtLeast(MARGIN_MIN_MB).coerceAtMost(marginMb)
    }

    /** The margin after a failed try, a kill, critical memory soon after a load, or a low-memory signal. */
    fun marginAfterTrouble(marginMb: Long): Long = (marginMb + MARGIN_GROW_MB).coerceIn(MARGIN_MIN_MB, MARGIN_MAX_MB)

    fun encode(tries: List<Try>): String = JSONArray().also { a ->
        for (t in tries.takeLast(KEEP)) a.put(JSONObject().put("at", t.atMs).put("free", t.freeMb).put("need", t.needMb).put("ok", t.ok)
            .put("found", t.foundMb).put("why", t.why).put("measure", t.measure))
    }.toString()

    fun decode(s: String?): List<Try> = runCatching {
        val a = JSONArray(s ?: "[]")
        List(a.length()) { i -> a.getJSONObject(i).let { o ->
            Try(o.getLong("at"), o.getLong("free"), o.getLong("need"), o.getBoolean("ok"), o.optLong("found", -1), o.optString("why", ""), o.optBoolean("measure", false)) } }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------------------------------------ on the phone

    private fun prefs(ctx: android.content.Context) = ctx.getSharedPreferences("npugate", android.content.Context.MODE_PRIVATE)

    fun enabled(ctx: android.content.Context) = prefs(ctx).getBoolean("on", true)
    fun setEnabled(ctx: android.content.Context, on: Boolean) = prefs(ctx).edit().putBoolean("on", on).apply()
    fun tries(ctx: android.content.Context) = decode(prefs(ctx).getString("tries", null))

    /** The device copy's margin on this phone now (MB). */
    fun margin(ctx: android.content.Context): Long = prefs(ctx).getLong("margin", MARGIN_START_MB).coerceIn(MARGIN_MIN_MB, MARGIN_MAX_MB)

    /** A load held its hold window untroubled: the margin shrinks toward what it showed was enough. */
    @Synchronized fun marginHeld(ctx: android.content.Context, lowestFreeMb: Long, thresholdMb: Long) {
        val m = margin(ctx); val n = marginAfterHeld(m, lowestFreeMb, thresholdMb)
        if (n != m) prefs(ctx).edit().putLong("margin", n).apply()
        com.timerpg.street.shot.Crumbs.add("npu gate: margin ${if (n < m) "$m -> $n" else "kept at $m"} MB (the load held ${HOLD_MS / 60000} min; " +
            "lowest free $lowestFreeMb MB, critical line ${thresholdMb + MemoryWatch.CRITICAL_MB} MB)")
    }

    /** Trouble with a device copy ([why]): the margin grows. */
    @Synchronized fun marginTrouble(ctx: android.content.Context, why: String) {
        val m = margin(ctx); val n = marginAfterTrouble(m)
        if (n != m) prefs(ctx).edit().putLong("margin", n).apply()
        com.timerpg.street.shot.Crumbs.add("npu gate: margin $m -> $n MB ($why)")
    }

    @Synchronized fun record(ctx: android.content.Context, t: Try) {
        prefs(ctx).edit().putString("tries", encode(tries(ctx) + t)).commit()
        com.timerpg.street.shot.Crumbs.add("npu gate: ${if (t.ok) "held" else "failed"} with ${t.freeMb} MB free, ${t.needMb} needed" +
            (if (t.foundMb >= 0) ", Android found ${t.foundMb} MB" else "") + (if (t.why.isNotEmpty()) " (${t.why})" else ""))
        if (!t.ok && !t.measure) marginTrouble(ctx, "a failed try: ${t.why.ifEmpty { "load failed" }}")
    }

    /** A try in play under way (written before its load, so a kill during it is known on the next start). */
    fun markRunning(ctx: android.content.Context, freeMb: Long, needMb: Long) =
        prefs(ctx).edit().putLong("runAt", System.currentTimeMillis()).putLong("runFree", freeMb).putLong("runNeed", needMb).commit()
    fun clearRunning(ctx: android.content.Context) = prefs(ctx).edit().remove("runAt").commit()

    /** At start: a try left marked running ended with the process (killed during the load or soon after). */
    fun checkKilled(ctx: android.content.Context) {
        val p = prefs(ctx)
        val at = p.getLong("runAt", 0L)
        if (at == 0L) return
        // (the writer's own process starting while the game's still runs: the game's try is not over, nor failed)
        val me = android.os.Process.myPid()
        val gameAlive = runCatching {
            ctx.getSystemService(android.app.ActivityManager::class.java)?.runningAppProcesses?.any { it.processName == ctx.packageName && it.pid != me }
        }.getOrNull() == true
        if (gameAlive) return
        clearRunning(ctx)
        record(ctx, Try(at, p.getLong("runFree", -1), p.getLong("runNeed", -1), ok = false, why = "the game ended during the load or soon after"))
    }

    /** The figure now, cross-checked with /proc/meminfo (MemAvailable against Android's availMem, and the free swap). */
    fun verdict(ctx: android.content.Context, freeMb: Long, needMb: Long, swapFreeMb: Long): Verdict {
        val mi = runCatching { MemProbe.parseMeminfo(java.io.File("/proc/meminfo").readText()) }.getOrNull()
        val avail = runCatching {
            android.app.ActivityManager.MemoryInfo().also { ctx.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }.availMem shr 20
        }.getOrDefault(-1L)
        val cap = crossCap(mi?.get("MemAvailable") ?: -1, avail, mi?.get("SwapFree") ?: -1)
        return reclaim(freeMb, needMb, swapFreeMb, tries(ctx), System.currentTimeMillis(), enabled(ctx), cap)
    }
}
