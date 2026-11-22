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
 * 2. what was measured: the least that Android found in the last loads that measured it (the NPU fit test, or a try in
 *    play), capped at [CAP_MB];
 * 3. else a first guess, [SWAP_SHARE] of the free swap, at most [GUESS_CAP_MB], until a load measures it.
 * Every try is guarded: the load is watched and stopped if free memory falls to Android's threshold plus [VALVE_MB],
 * and a failure falls back NPU, then GPU, then the CPU reading the mapped file.
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

    fun reclaim(freeMb: Long, needMb: Long, swapFreeMb: Long, tries: List<Try>, nowMs: Long, on: Boolean = true): Verdict {
        if (!on) return Verdict(0, "counting what Android frees on demand is off (AI panel)")
        val failed = tries.filter { !it.ok && nowMs - it.atMs < WEEK_MS && it.freeMb + RETRY_MB >= freeMb && it.needMb in 1..(needMb + 100) }.maxByOrNull { it.atMs }
        if (failed != null) return Verdict(0, "a try with ${failed.freeMb} MB free failed (${failed.why.ifEmpty { "no reason" }}), so not again with $freeMb MB free this week")
        val found = tries.filter { it.ok && it.foundMb >= 0 }.sortedByDescending { it.atMs }.take(3)
        if (found.isNotEmpty()) {
            val f = found.minOf { it.foundMb }.coerceAtMost(CAP_MB)
            return Verdict(f, "Android found $f MB beyond its free count in the last ${found.size} measured load${if (found.size > 1) "s" else ""}")
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

    @Synchronized fun record(ctx: android.content.Context, t: Try) {
        prefs(ctx).edit().putString("tries", encode(tries(ctx) + t)).commit()
        com.timerpg.street.shot.Crumbs.add("npu gate: ${if (t.ok) "held" else "failed"} with ${t.freeMb} MB free, ${t.needMb} needed" +
            (if (t.foundMb >= 0) ", Android found ${t.foundMb} MB" else "") + (if (t.why.isNotEmpty()) " (${t.why})" else ""))
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
        clearRunning(ctx)
        record(ctx, Try(at, p.getLong("runFree", -1), p.getLong("runNeed", -1), ok = false, why = "the game ended during the load or soon after"))
    }

    fun verdict(ctx: android.content.Context, freeMb: Long, needMb: Long, swapFreeMb: Long) =
        reclaim(freeMb, needMb, swapFreeMb, tries(ctx), System.currentTimeMillis(), enabled(ctx))
}
