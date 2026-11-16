package com.lifemax.rpg.feature.habits.domain

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/*
 * Who is visible, when. A lit window is a furnished room whether or not anyone is in it; people take
 * turns in them. Time runs in blocks of a minute. In each block a building "staffs" only a few of its
 * lit floors (rotating, so every floor gets its turn), a staffed floor puts one or two people in the
 * windows of one checker colour (so two neighbours, side by side or one above the other, are never
 * both occupied), and each of them is there for 18 to 50 seconds of that minute: a shift. Everything
 * is a function of the ids and the clock, so the street is the same wherever it is looked at; a still
 * frame (0 seconds) shows one such arrangement with everyone in place. Pure arithmetic.
 */
object Presence {
    /** The length of a block, in seconds. */
    const val BLOCK = 60f

    /** The most people one floor shows (three on a big floor of a building of up to four). */
    const val FLOOR_CAP = 2
    const val BIG_FLOOR_CAP = 3

    /** The most floors a building staffs at once, so at most 8 people in a tall building. */
    const val MAX_STAFFED = 4

    /** How long a person takes to appear or go, in seconds. */
    const val FADE = 1.3f

    /** The reward after a floor is laid: this long, up to [CHEER_MAX] people appear one by one. */
    const val CHEER_SECONDS = 6f
    const val CHEER_MAX = 3

    /** How many of [lit] floors a building staffs in a block. */
    fun staffedFloors(litFloors: Int): Int =
        if (litFloors <= 0) 0 else ceil(litFloors / 2f).toInt().coerceIn(1, MAX_STAFFED)

    /**
     * The most people on a floor of [lit] lit windows in a building with [litFloors] lit floors.
     */
    fun floorCap(lit: Int, litFloors: Int): Int =
        if (lit >= 9 && litFloors <= 4) BIG_FLOOR_CAP else FLOOR_CAP

    /** How many people a staffed floor of [lit] lit windows shows: about one window in four. */
    fun peoplePerFloor(lit: Int, litFloors: Int): Int =
        floor(lit * .28f + .45f).toInt().coerceIn(1, floorCap(lit, litFloors)).coerceAtMost(lit)

    /** The block a building is in at [seconds]: its own offset, so buildings are out of step. */
    fun block(seconds: Float, seed: Int): Int =
        if (seconds <= 0f) (Ambient.hash(seed, 0, 101) * 40f).toInt()
        else floor((seconds + Ambient.hash(seed, 0, 102) * BLOCK * 12f) / BLOCK).toInt()

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val t = x % y
            x = y
            y = t
        }
        return x
    }

    /**
     * The visits floor number [ordinal] (of [litFloors], counting the lit floors from the bottom)
     * has had before [block], and whether it is staffed in it: the floors are scrambled once and
     * [staffedFloors] consecutive ones in that order are staffed in each block, moving on in the
     * next, so every floor is staffed about as often as any other. Returns the number of earlier
     * visits if staffed, else -1.
     */
    fun visits(seed: Int, ordinal: Int, litFloors: Int, block: Int): Int {
        if (litFloors <= 0) return -1
        val s = staffedFloors(litFloors)
        if (litFloors == 1) return block.coerceAtLeast(0)
        var stride = 1 + (Ambient.hash(seed, 0, 103) * (litFloors - 1)).toInt()
        while (gcd(stride, litFloors) != 1) stride = stride % (litFloors - 1) + 1
        val shift = (Ambient.hash(seed, 0, 104) * litFloors).toInt()
        val slot = (ordinal * stride) % litFloors
        val d = ((slot - shift) % litFloors + litFloors) % litFloors
        val b = max(0, block)
        val before = max(0, ceil((b * s - d) / litFloors.toFloat()).toInt())
        val upTo = max(0, ceil(((b + 1) * s - d) / litFloors.toFloat()).toInt())
        return if (upTo > before) before else -1
    }

    /**
     * Writes into [out] (the first `cast.size` places) how much of a person is in each lit window
     * of a floor at [seconds]: 0 for nobody, 1 for settled in, between as they arrive or leave. The
     * floor is number [ordinal] of [litFloors] lit floors of a building with [seed]; [floorSeed] is
     * the floor's own; [cheer] is the seconds since the floor was laid (negative for not just
     * laid).
     */
    fun floorPresence(
        out: FloatArray,
        cast: Array<WindowCast>,
        seed: Int,
        floorSeed: Int,
        ordinal: Int,
        litFloors: Int,
        seconds: Float,
        cheer: Float = -1f,
    ) {
        val lit = cast.size
        for (k in 0 until lit) out[k] = 0f
        if (lit == 0) return
        val b = block(seconds, seed)
        val q = visits(seed, ordinal, litFloors, b)
        if (q >= 0) {
            var m0 = 0
            var m1 = 0
            for (c in cast) if (c.group == 0) m0++ else m1++
            var cls = q and 1
            if ((if (cls == 0) m0 else m1) == 0) cls = 1 - cls
            val m = if (cls == 0) m0 else m1
            val n = peoplePerFloor(lit, litFloors).coerceAtMost(m)
            val start = ((q shr 1) * n) % m
            val local = seconds + Ambient.hash(seed, 0, 102) * BLOCK * 12f - b * BLOCK
            for (k in 0 until lit) {
                val c = cast[k]
                if (c.group != cls || ((c.rank - start) % m + m) % m >= n) continue
                out[k] =
                    if (seconds <= 0f) 1f
                    else {
                        val len = 18f + Ambient.hash(floorSeed, k, b) * 32f
                        val from = 2f + Ambient.hash(floorSeed, k, b + 7919) * (54f - len)
                        (((local - from) / FADE).coerceIn(0f, 1f) *
                            ((from + len - local) / FADE).coerceIn(0f, 1f))
                    }
            }
        }
        if (cheer >= 0f && cheer < CHEER_SECONDS) {
            var m0 = 0
            for (c in cast) if (c.group == 0) m0++
            val cls = if (m0 * 2 >= lit) 0 else 1
            for (k in 0 until lit) {
                val c = cast[k]
                if (c.group != cls || c.rank >= CHEER_MAX) continue
                val at = .3f + c.rank * .9f
                val v =
                    ((cheer - at) / .5f).coerceIn(0f, 1f) *
                        ((CHEER_SECONDS - cheer) / 1f).coerceIn(0f, 1f)
                if (v > out[k]) out[k] = v
            }
        }
    }
}
