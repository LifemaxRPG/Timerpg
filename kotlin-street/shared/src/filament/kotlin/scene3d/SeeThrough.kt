package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.google.android.filament.MaterialInstance

/**
 * The open world's see-through cone (docs/TOWN.md, "See-through"): whatever stands between the camera and the player
 * (a forest's merged trees, a building's corner, a yard's shed) is cut in a cone from the eye that widens to the player,
 * its edge dithered in whole art pixels, by the lit material itself (street_lit.mat's `seeA`/`seeB`), so it works on
 * merged meshes the box cutaway ([FilamentTown.occlude]) cannot take apart. Never the ground (its layer is left out, and
 * nothing under 1.5 up is cut), never the people, never past the player. Set once a frame by [TownRun.orbit]; the
 * layers hand it to each drawn material instance with its `look`.
 */
internal object SeeThrough {
    /** The eye (x, y, z) and how far along the line the cut ends (w: just short of the player). */
    @JvmField val a = FloatArray(4)
    /** The player's middle (x, y, z) and the cone's radius there (w; 0: off). */
    @JvmField val b = FloatArray(4)

    /** Above the feet, the cone aims here: the figure's middle. */
    const val MIDDLE = 26f
    /** The cone's radius at the player: the whole figure and a little round it. */
    const val RADIUS = 55f
    /** The cut stops this short of the player: a trunk right at their back stays. */
    const val SHORT = 8f

    /** Cut what stands between the eye ([ex], [ey], [ez]) and the player at ([px], [pz]) on the ground. */
    fun aim(px: Float, pz: Float, ex: Float, ey: Float, ez: Float) {
        val py = MIDDLE
        val len = kotlin.math.sqrt((px - ex) * (px - ex) + (py - ey) * (py - ey) + (pz - ez) * (pz - ez))
        a[0] = ex; a[1] = ey; a[2] = ez; a[3] = if (len <= SHORT + 1f) 0f else 1f - SHORT / len
        b[0] = px; b[1] = py; b[2] = pz; b[3] = if (enabled) RADIUS else 0f
    }

    /** Off for a comparison (the desktop preview's -Dsee=0). */
    @JvmField var enabled = System.getProperty("see") != "0"

    /** Nothing is cut (the street view, a room). */
    fun off() { a.fill(0f); b.fill(0f) }

    fun apply(mi: MaterialInstance) {
        mi.setParameter("seeA", a[0], a[1], a[2], a[3])
        mi.setParameter("seeB", b[0], b[1], b[2], b[3])
    }
}
