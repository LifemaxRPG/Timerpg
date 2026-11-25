package com.lifemax.rpg.feature.habits.presentation.scene

/**
 * The open world's automatic drawing resolution, in steps (TownQuality.RES_AUTO). Filament's own dynamic resolution moves
 * the scale a little every frame while the GPU is over its budget; each new scale lays the screen's pixels over the
 * town's pixel art a little differently, so a fine nearest-sampled picture (the Great Meridian's coffered arch vault, brickwork,
 * fences) crawls and flickers while the player walks: the owner's "glitchyness here when walking" (S25, 164212: GPU over
 * 16.7 ms in half the frames, so the scale never settled). Here the scale is one of a few [LEVELS], held: it steps down
 * only after the GPU has been over budget for [DOWN_AFTER] s, up only when the next step up would fit with room to spare
 * for [UP_AFTER] s, and never sooner than [HOLD] s after the last change, so a walk is drawn at one scale throughout.
 * Pure logic (fed the GPU's frame times), so a test can drive it; allocates nothing.
 */
class ResolutionSteps(
    /** The GPU's budget a frame (ms): 60 frames a second (the app sets it from the frame rate aimed at: 8.3 at 120). */
    @Volatile var budgetMs: Float = 16.7f,
) {
    /** The scale now (one of [LEVELS]): its own step, or the thermal governor's [heatStep] where that is lower. */
    var scale = LEVELS[0]; private set
    /**
     * The thermal governor's least step ([HeatSteps.Plan.resStep]: 0 none): the scale is held at most at that step's while
     * the phone is warm, before it throttles; true from [setHeatStep] when the scale changed (apply it).
     */
    var heatStep = 0; private set
    fun setHeatStep(step: Int): Boolean {
        val s = step.coerceIn(0, LEVELS.size - 1)
        if (s == heatStep) return false
        heatStep = s
        val k = LEVELS[maxOf(level, heatStep)]
        if (k == scale) return false
        scale = k; since = 0f; over = 0f; under = 0f; changes++
        return true
    }
    private var level = 0
    private var avg = -1f
    private var over = 0f
    private var under = 0f
    private var since = HOLD
    /** How many times it changed (for the breadcrumbs). */
    var changes = 0; private set

    /**
     * One frame's GPU time [gpuMs] (negative: not known, skipped) after [dt] s; true when the scale changed (apply it).
     */
    fun feed(gpuMs: Float, dt: Float): Boolean {
        if (dt <= 0f) return false
        since += dt
        if (gpuMs < 0f) return false
        avg = if (avg < 0f) gpuMs else avg + (gpuMs - avg) * (1f - kotlin.math.exp(-dt / SMOOTH))
        over = if (avg > budgetMs * OVER) over + dt else 0f
        val up = if (level > 0) LEVELS[level - 1] else scale
        // (what the next step up would cost: the GPU's time grows about as the pixels, the scale squared)
        val atUp = avg * (up * up) / (scale * scale)
        under = if (level > 0 && atUp < budgetMs * UNDER) under + dt else 0f
        if (since < HOLD) return false
        if (over > DOWN_AFTER && level < LEVELS.size - 1) { level++; return changed() }
        if (under > UP_AFTER && level > 0) { level--; return changed() }
        return false
    }

    private fun changed(): Boolean {
        val was = scale
        scale = LEVELS[maxOf(level, heatStep)]; since = 0f; over = 0f; under = 0f; changes++
        return scale != was
    }

    fun reset() { level = 0; heatStep = 0; scale = LEVELS[0]; avg = -1f; over = 0f; under = 0f; since = HOLD }

    companion object {
        /** The scales it draws at: the full screen, then two steps down to the least the automatic resolution allows. */
        val LEVELS = floatArrayOf(1f, .86f, .72f)
        /** The GPU's time is smoothed over this (s). */
        const val SMOOTH = .5f
        /** Over budget by this share for [DOWN_AFTER] s: a step down. */
        const val OVER = 1.05f
        const val DOWN_AFTER = 1.5f
        /** The step up must fit in this share of the budget for [UP_AFTER] s. */
        const val UNDER = .8f
        const val UP_AFTER = 6f
        /** No change within this long of the last (s). */
        const val HOLD = 4f
    }
}
