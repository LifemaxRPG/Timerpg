package com.lifemax.rpg.feature.habits.presentation.scene

import kotlin.math.hypot

/**
 * Someone who could hear the player: a person standing at their door ([sprite], the building's) or one walking by in the
 * street ([who], the passer's seed: the loop takes it for one of the town's people, LoopTown.passerId), where they are
 * now (street units).
 */
class Hearer(val sprite: String?, val who: Int, val x: Float, val z: Float) {
    /** The same person from one moment to the next: the building for someone at a door, the seed for a passer. */
    val key: String get() = sprite ?: "who:$who"
    val walking: Boolean get() = sprite == null
}

/**
 * Who hears what the player says in the open town (always-listening voice): anyone within [TalkRange.distance] who is a
 * person, at their door or walking past, not only the people at their doors. Of those, the one the player faces and is
 * nearest: each is scored by distance times 1 + (1 - facing) / 2 ([facing]: the cosine between the way the player faces
 * and the way to them), so someone straight ahead counts at their distance, someone beside at 1.5 times it and someone
 * behind at twice it. The one already listening keeps the turn while still in earshot unless another is clearly
 * better ([STICK] units of score), so the choice does not flicker between two people walking side by side.
 */
object Earshot {
    /** How much better (score, street units) another must be to take over from the one already listening. */
    const val STICK = 20f

    /** The cosine between the player's facing ([hx], [hz]) and the way from ([px], [pz]) to ([x], [z]); 1 when they stand on the player. */
    fun facing(px: Float, pz: Float, hx: Float, hz: Float, x: Float, z: Float): Float {
        val dx = x - px; val dz = z - pz
        val d = hypot(dx, dz); val h = hypot(hx, hz)
        if (d < 1e-3f || h < 1e-6f) return 1f
        return ((dx * hx + dz * hz) / (d * h)).coerceIn(-1f, 1f)
    }

    /** [h]'s score for the player at ([px], [pz]) facing ([hx], [hz]) (lower is better); [Float.MAX_VALUE] beyond [range]. */
    fun score(px: Float, pz: Float, hx: Float, hz: Float, h: Hearer, range: Float = TalkRange.distance): Float {
        val d = hypot(h.x - px, h.z - pz)
        if (d > range) return Float.MAX_VALUE
        return d * (1f + (1f - facing(px, pz, hx, hz, h.x, h.z)) / 2f)
    }

    /**
     * The listener among [hearers] for the player at ([px], [pz]) facing ([hx], [hz]): the best [score] within [range]; the
     * one called [current] (a [Hearer.key]) stays while in range unless another scores [STICK] better. Null: nobody in earshot.
     */
    fun choose(px: Float, pz: Float, hx: Float, hz: Float, hearers: List<Hearer>, range: Float = TalkRange.distance, current: String? = null): Hearer? {
        var best: Hearer? = null
        var bestS = Float.MAX_VALUE
        var cur: Hearer? = null
        var curS = Float.MAX_VALUE
        for (h in hearers) {
            val s = score(px, pz, hx, hz, h, range)
            if (s == Float.MAX_VALUE) continue
            if (s < bestS) { best = h; bestS = s }
            if (current != null && h.key == current && s < curS) { cur = h; curS = s }
        }
        if (cur != null && bestS + STICK >= curS) return cur
        return best
    }

    /** The nearest of [hearers] to ([px], [pz]) and how far, any distance (for the breadcrumb when nobody is in earshot). */
    fun nearest(px: Float, pz: Float, hearers: List<Hearer>): Pair<Hearer, Float>? =
        hearers.map { it to hypot(it.x - px, it.z - pz) }.minByOrNull { it.second }
}
