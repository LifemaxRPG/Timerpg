package com.lifemax.rpg.feature.habits.presentation.scene

import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Heat and battery as scheduling (docs/HEAT.md): the frame rate and its even pacing, and the thermal governor's steps.
 * Platform-free (the app feeds it Android's thermal headroom and the display's refresh; the desktop tests it).
 */

/**
 * Frame pacing: the rate the player chose (Settings, "Frame rate": 60, the default for long play, or 120) and the thermal
 * governor's cap on it; how many display refreshes each frame is held for, so frames come evenly spaced (Swappy's rule:
 * never a 16/8/16/8 ms alternation, never more frames than the target).
 */
object FramePace {
    /** The player's choice (Hz). */
    @Volatile var chosen = 60
    /** The thermal governor's cap (Hz, 0: none). */
    @Volatile var heatCap = 0
    /** The rate aimed at now. */
    val target: Int get() = if (heatCap in 1 until chosen) heatCap else chosen

    /** Refreshes a frame is shown for at [displayHz] for [fps] frames a second (120 Hz at 60: 2), at least 1. */
    fun interval(displayHz: Float, fps: Int): Int = if (displayHz <= 0f || fps <= 0) 1 else max(1, (displayHz / fps).roundToInt())

    /**
     * The display refresh to ask for, of [rates] (Hz), for [fps]: the one that equals it (within 1 Hz), else the lowest
     * whole multiple of it (frames then held for an even number of refreshes), else the fastest; null if [rates] is empty.
     */
    fun pickRefresh(rates: List<Float>, fps: Int): Float? {
        if (rates.isEmpty()) return null
        rates.filter { kotlin.math.abs(it - fps) < 1f }.minByOrNull { kotlin.math.abs(it - fps) }?.let { return it }
        rates.filter { it > fps && kotlin.math.abs(it / fps - (it / fps).roundToInt()) < .02f }.minOrNull()?.let { return it }
        return rates.max()
    }

    /**
     * Whether a frame is due at the refresh [vsyncNs] when the last was drawn at [lastNs], [interval] refreshes of [periodNs]
     * apart (half a refresh of slack: the clock's jitter never skips a whole frame). Always due for the first.
     */
    fun due(vsyncNs: Long, lastNs: Long, periodNs: Long, interval: Int): Boolean =
        interval <= 1 || lastNs == 0L || vsyncNs - lastNs >= interval * periodNs - periodNs / 2
}

/**
 * The thermal governor's steps: fed Android's thermal headroom forecast (ADPF `getThermalHeadroom`, 1.0 = the phone
 * throttles severely; NaN where it cannot say) and its thermal status every few seconds, it holds a level from 0 (cool)
 * to [MAX], up one level after the headroom has stayed over that level's line for [UP_AFTER] s, down one after it has
 * stayed [DOWN_MARGIN] under it for [DOWN_AFTER] s: gradual, before the OS throttles, and back when cool. The status sets
 * a floor (a status of LIGHT is at least level 1, MODERATE 2, SEVERE or worse [MAX]) for phones that give no headroom.
 *
 * What each level does is [plan]: the frame rate first (120 -> 60 Hz, no loss of picture), then the far view's rebakes
 * and the workers' clock hint, then the resolution steps and the shadow cascades. It never touches the dialogue (on the
 * NPU, never on the CPU: CLAUDE.md), nor any picture's content.
 */
class HeatSteps {
    var level = 0; private set
    /** The smoothed headroom (NaN until one is known). */
    var headroom = Float.NaN; private set
    var status = 0; private set
    private var over = 0f
    private var under = 0f
    /** How many times the level changed (the breadcrumbs). */
    var changes = 0; private set

    /** One reading after [dt] s; true when [level] changed (apply [plan]). */
    fun feed(head: Float, thermalStatus: Int, dt: Float): Boolean {
        status = thermalStatus
        if (!head.isNaN() && head >= 0f) headroom = if (headroom.isNaN()) head else headroom + (head - headroom) * .5f
        val floor = statusFloor(thermalStatus)
        if (floor > level) { level = floor; over = 0f; under = 0f; changes++; return true }
        val h = headroom
        if (h.isNaN()) return false
        // up: the next level's line crossed for long enough
        if (level < MAX && h >= UP[level]) { over += dt; under = 0f } else over = 0f
        if (over >= UP_AFTER) { level++; over = 0f; changes++; return true }
        // down: well under this level's own line for long enough (never under the status's floor)
        if (level > floor && h < UP[level - 1] - DOWN_MARGIN) { under += dt } else under = 0f
        if (under >= DOWN_AFTER) { level--; under = 0f; changes++; return true }
        return false
    }

    fun reset() { level = 0; headroom = Float.NaN; status = 0; over = 0f; under = 0f }

    /** What a level sets. */
    class Plan(
        /** The frame rate's cap (Hz, 0: the player's choice). */
        val fpsCap: Int,
        /** The automatic resolution's least step ([ResolutionSteps.LEVELS] index): 0 none, 1 at most 0.86, 2 at most 0.72. */
        val resStep: Int,
        /** The sun's shadow cascades at most. */
        val cascades: Int,
        /** The far view's rebakes (the horizon ring) wait this many times as far. */
        val rebake: Float,
        /** The town workers' clock hint aims at this many times the frame (they may take longer: lower clocks). */
        val workerTarget: Float,
    )

    companion object {
        const val MAX = 4
        /** The headroom over which each level is entered (level 1 at 0.65 ... level 4 at 0.95). */
        val UP = floatArrayOf(.65f, .75f, .85f, .95f)
        const val UP_AFTER = 4f
        const val DOWN_MARGIN = .08f
        const val DOWN_AFTER = 20f

        /** Android's PowerManager.THERMAL_STATUS_* (NONE 0, LIGHT 1, MODERATE 2, SEVERE 3, CRITICAL 4, EMERGENCY 5, SHUTDOWN 6). */
        fun statusFloor(s: Int): Int = when { s >= 3 -> MAX; s == 2 -> 2; s == 1 -> 1; else -> 0 }

        private val PLANS = arrayOf(
            Plan(0, 0, 4, 1f, 1f),
            Plan(60, 0, 4, 2f, 1.5f),
            Plan(60, 1, 4, 3f, 2f),
            Plan(60, 2, 3, 4f, 2f),
            Plan(60, 2, 2, 4f, 3f),
        )
        fun plan(level: Int): Plan = PLANS[level.coerceIn(0, MAX)]
    }
}
