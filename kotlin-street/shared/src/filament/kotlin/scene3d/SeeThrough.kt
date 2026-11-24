package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.google.android.filament.MaterialInstance

/**
 * The open world's see-through (docs/TOWN.md, "See-through"): whatever stands between the camera and the player is
 * thinned to a ghost by the lit material itself (street_lit.mat's `seeA`, `seeB`, `seeK`, `seeCrowns`), a screen door in
 * whole art pixels, so it works on merged meshes the box cutaway ([FilamentTown.occlude]) cannot take apart:
 *  - a tree's crown across the player's lines to the eye fades whole ([crowns]: its volume, its trunk left), eased in and
 *    out on its own, its rim soft;
 *  - anything else in the way (a forest's merged trunks, a landmark's gazebo, a building's corner) thins in a cone from
 *    the eye to the player that eases from the ghost at its middle to whole at its edge: no hard ring round the player
 *    (the owner's 100817, 0.1.848: "the circle cut out looks bad").
 * Never the ground (its layer is left out, and nothing under 1.5 up is touched), never the people, never past the
 * player. Set once a frame by [TownRun.orbit]; the layers hand it to each drawn material instance with its `look`.
 */
internal object SeeThrough {
    /** The eye (x, y, z) and how far along the line the cut ends (w: just short of the player). */
    @JvmField val a = FloatArray(4)
    /** The player's middle (x, y, z) and the cone's radius there (w; 0: off). */
    @JvmField val b = FloatArray(4)

    /** Above the feet, the cone aims here: the figure's middle. */
    const val MIDDLE = 26f
    /** The cone's radius at the player (40 with a hard dithered rim until 0.1.848; soft from half way out now, so wider). */
    const val RADIUS = 70f
    /** The share of a thinned thing's pixels left (its ghost: enough to tell it is there, the player plain through it). */
    const val GHOST = .22f
    /** Most crowns faded at once. */
    const val CROWNS = 4
    /** How long the cone takes to open once the player is hidden, and to close once they are not (s). */
    const val EASE_SECONDS = .3f
    /** How open the cone is (0 shut: nothing cut, the material skips it; 1 open), eased toward [aim]'s hidden. */
    var open = 0f; private set
    /** (Measuring) the cone open whether or not the player is hidden, as before 0.1.390. */
    @JvmField var always = false
    /** False: the cone opens and shuts at once (a still picture). */
    @JvmField var ease = true
    /** The cut stops this short of the player: a trunk right at their back stays. */
    const val SHORT = 8f

    /**
     * Cut what stands between the eye ([ex], [ey], [ez]) and the player at ([px], [pz]) on the ground, only while
     * something really hides them ([hidden]: [FilamentTown.occlude]'s lines from their chest and head to the eye), the
     * cone widening from nothing over [EASE_SECONDS] and closing again over as long once they are clear ([dt] the
     * frame's step). 0.1.385 had it always on: every tree and post walked past dithered away (054003).
     */
    fun aim(px: Float, pz: Float, ex: Float, ey: Float, ez: Float, hidden: Boolean = true, dt: Float = 0f, feet: Float = 0f) {
        val goal = if (hidden || always) 1f else 0f
        open = if (!ease || dt <= 0f) goal else if (open < goal) kotlin.math.min(goal, open + dt / EASE_SECONDS) else kotlin.math.max(goal, open - dt / EASE_SECONDS)
        val py = MIDDLE + feet
        val len = kotlin.math.sqrt((px - ex) * (px - ex) + (py - ey) * (py - ey) + (pz - ez) * (pz - ez))
        a[0] = ex; a[1] = ey; a[2] = ez; a[3] = if (len <= SHORT + 1f) 0f else 1f - SHORT / len
        // (eased smoothly: the ghost thinning in)
        val s = open * open * (3f - 2f * open)
        b[0] = px; b[1] = py; b[2] = pz; b[3] = if (enabled && s > .01f) RADIUS else 0f
        stepCrowns(dt)
        var any = if (b[3] > 0f) s else 0f
        for (i in 0 until CROWNS) if (crowns[i * 4 + 3] > 0f) { val c = crownK[i]; k[i] = c * c * (3f - 2f * c); any = kotlin.math.max(any, k[i]) } else k[i] = 0f
        see[0] = if (b[3] > 0f) s else 0f; see[1] = GHOST; see[2] = if (any > .01f) 1f else 0f; see[3] = 0f
    }
    /** seeK: the cone's openness, the ghost, anything on. */
    private val see = FloatArray(4)

    // the crowns faded whole: centre x, y, z and radius each ([CROWNS]), how far each is faded, and whether it is in the
    // way this frame ([crown] gathers them, [aim] eases them)
    @JvmField val crowns = FloatArray(CROWNS * 4)
    @JvmField val crownK = FloatArray(CROWNS)
    private val crownHit = BooleanArray(CROWNS)
    private val k = FloatArray(4)

    /** Starts gathering this frame's crowns ([crown]); [FilamentTown.occlude] calls it before it looks. */
    fun beginCrowns() { crownHit.fill(false) }

    /**
     * A tree's crown in the way this frame (centre ([x], [y], [z]), radius [r]): faded whole while it is, eased out after.
     * Allocates nothing; past [CROWNS] at once the rest stay whole (the cone still thins them).
     */
    fun crown(x: Float, y: Float, z: Float, r: Float) {
        var free = -1
        for (i in 0 until CROWNS) {
            val o = i * 4
            if (crowns[o + 3] > 0f && kotlin.math.abs(crowns[o] - x) < 1f && kotlin.math.abs(crowns[o + 2] - z) < 1f) { crownHit[i] = true; return }
            if (free < 0 && crowns[o + 3] <= 0f) free = i
        }
        if (free < 0) return
        val o = free * 4
        crowns[o] = x; crowns[o + 1] = y; crowns[o + 2] = z; crowns[o + 3] = r
        crownK[free] = 0f; crownHit[free] = true
    }

    private fun stepCrowns(dt: Float) {
        for (i in 0 until CROWNS) {
            if (crowns[i * 4 + 3] <= 0f) continue
            val goal = if (crownHit[i] && enabled) 1f else 0f
            crownK[i] = if (!ease || dt <= 0f) goal else if (crownK[i] < goal) kotlin.math.min(goal, crownK[i] + dt / EASE_SECONDS) else kotlin.math.max(goal, crownK[i] - dt / EASE_SECONDS)
            if (crownK[i] <= 0f && goal == 0f) { crowns[i * 4 + 3] = 0f; crownK[i] = 0f }
        }
    }

    /** Off for a comparison (the desktop preview's -Dsee=0). */
    @JvmField var enabled = System.getProperty("see") != "0"

    /** Nothing is cut (the street view, a room). */
    fun off() { a.fill(0f); b.fill(0f); see.fill(0f); crowns.fill(0f); crownK.fill(0f); k.fill(0f) }

    fun apply(mi: MaterialInstance) {
        mi.setParameter("seeA", a[0], a[1], a[2], a[3])
        mi.setParameter("seeB", b[0], b[1], b[2], b[3])
        mi.setParameter("seeK", see[0], see[1], see[2], see[3])
        if (see[2] > 0f) {
            mi.setParameter("seeCrowns", MaterialInstance.FloatElement.FLOAT4, crowns, 0, CROWNS)
            mi.setParameter("seeCrownK", k[0], k[1], k[2], k[3])
        }
    }

    /**
     * The cutaway of a building the player is inside, for its own layer ([PropLayer.cutaway]): [out] the plane through
     * the player facing the eye (normal x, z towards the eye, offset, on) and the floor and ceiling's underside, from the
     * player at ([px], [pz]) with feet [feet], the eye at ([ex], [ez]), the ceiling at [ceil]. Allocates nothing.
     */
    fun cutaway(px: Float, pz: Float, feet: Float, ex: Float, ez: Float, ceil: Float, out: FloatArray) {
        val dx = ex - px; val dz = ez - pz
        val l = kotlin.math.hypot(dx, dz).coerceAtLeast(1e-3f)
        val nx = dx / l; val nz = dz / l
        // (the plane a little in front of the player towards the eye: the wall at their back, from the camera, stays)
        out[0] = nx; out[1] = nz; out[2] = px * nx + pz * nz + CUT_AHEAD; out[3] = 1f
        out[4] = feet; out[5] = ceil; out[6] = 0f; out[7] = 0f
    }
    /** The cutaway's plane stands this far in front of the player towards the eye (street units: about 0.6 m). */
    const val CUT_AHEAD = 12f
}
