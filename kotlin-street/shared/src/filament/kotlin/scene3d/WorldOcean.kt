package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.lifemax.rpg.feature.habits.domain.Planet
import com.lifemax.rpg.feature.habits.domain.RenderOrigin
import com.lifemax.rpg.feature.habits.domain.WorldMemory
import com.lifemax.rpg.feature.habits.domain.WorldSkeleton
import kotlin.math.floor

/**
 * The near ocean (docs/WORLD_STREAM.md): Jeff's pixel-art Gerstner ocean ([OceanSurface], G002) drawn round the eye
 * wherever the sea is within [NEAR_M], beside the world's far view ([WorldFar]), whose flat sea it takes over from: while
 * the waves are drawn the far view's sea is sunk under their troughs ([WorldFar.oceanDropM]), so the two never fight;
 * past the waves' 24 km skirt the flat sea goes on to the horizon.
 *
 * The ocean works in metres (its mesh, waves and palette are the prototype's); the town draws in street units from the
 * render origin. Its look is the town's times the metres' scale, its grid snapped round the eye in metres from the
 * render origin (the waves are a function of that position: they stay put as the eye moves), its sea level the far view's
 * (m above the town's ground height). Whether the sea is near is asked of the skeleton's land cells once the eye has
 * moved [RECHECK_M]; the mesh is made once (it is one static draw) and only shown while the sea is near.
 */
internal class WorldOcean(private val kit: TownExtra.Kit, private val far: WorldFar, private val origin: RenderOrigin) : TownExtra {
    private var surface: OceanSurface? = null
    private val u = origin.unitsPerMetre.toFloat()
    private var checkedX = Double.NaN
    private var checkedZ = Double.NaN
    /** Whether the sea is within [NEAR_M] of the eye (the last check). */
    var near = false; private set
    private val look = FloatArray(16)
    private val worldOf = FloatArray(16)
    /** Leaves the waves out (a comparison: the far view's flat sea alone). */
    var off = false
    /** The sea's state (calm by default: the weather's hook). */
    var spec: OceanSpec = OceanWaves.CALM

    override val pending: Boolean get() = false
    override val report: String get() = "world near ocean: ${if (surface == null) "not made" else "${surface!!.triangleCount} triangles, ${if (near) "drawn (the sea within %.0f km)".format(java.util.Locale.US, NEAR_M / 1000) else "hidden (no sea near)"}"}"

    override fun step() {}

    override fun frame(f: TownExtra.Frame, out: IntArray): IntArray {
        out[0] = 0; out[1] = 0; out[2] = 0
        val plan = far.plan ?: return out
        val ex = f.pose.ex / u; val ez = f.pose.ez / u
        val w = origin.origin.plus(ex.toDouble(), ez.toDouble())
        if (checkedX.isNaN() || kotlin.math.hypot(w.x - checkedX, w.z - checkedZ) > RECHECK_M) {
            checkedX = w.x; checkedZ = w.z
            near = seaNear(plan.sk, w.x / Planet.CELL_M, w.z / Planet.CELL_M)
        }
        val ey = f.pose.ey / u
        val on = near && !off && !far.hidden && ey - plan.seaLevel < HIGH_M
        if (on && surface == null) {
            surface = OceanSurface(kit.engine, kit.scene, kit.material("ocean"), seaLevelY = plan.seaLevel)
            WorldMemory.oceanBuffers = surface!!.bytes
        }
        val s = surface ?: run { far.oceanDropM = 0f; WorldMemory.oceanOn = false; return out }
        s.setVisible(on)
        WorldMemory.oceanOn = on
        far.oceanDropM = if (on) spec.maxAmplitude + DROP_MARGIN_M else 0f
        if (!on) return out
        // (the town's look takes street units; the ocean's vertices are metres from an anchor near the eye (floats stay
        // small: 94 km out the waves' phases and noise lost their detail in the capital's frame): look x scale(u) x
        // translate(anchor))
        val ax = Math.round(ex / ANCHOR_M) * ANCHOR_M; val az = Math.round(ez / ANCHOR_M) * ANCHOR_M
        for (k in 0 until 12) look[k] = f.look[k] * u
        for (r in 0 until 4) look[12 + r] = f.look[r] * u * ax + f.look[8 + r] * u * az + f.look[12 + r]
        // (its world position, for the fog: the same scale and anchor without the camera)
        worldOf[0] = u; worldOf[5] = u; worldOf[10] = u; worldOf[15] = 1f; worldOf[12] = u * ax; worldOf[14] = u * az
        s.setWorldOf(worldOf)
        s.update(look, ex - ax, ey, ez - az, f.seconds.toFloat(), spec, OceanPalette.forHour(f.hour))
        out[0] = 1; out[1] = s.triangleCount
        return out
    }

    override fun destroy() { surface?.destroy(); surface = null; WorldMemory.oceanBuffers = 0; WorldMemory.oceanOn = false }

    companion object {
        /** The waves are drawn when the sea is this near the eye (m), and the eye less than [HIGH_M] over it. */
        const val NEAR_M = 14_000.0
        const val HIGH_M = 3_000f
        /**
         * The waves' anchor lattice (m): the ocean's coordinates are from the lattice point nearest the eye. A multiple of
         * every calm and rough wavelength would hide the step when it moves; at 4 km the step is seen at most once a
         * few minutes at sea, far off (to do with the ships, step 3).
         */
        const val ANCHOR_M = 4096f
        /** Checked again once the eye has moved this far (m). */
        const val RECHECK_M = 300.0
        /** The far view's flat sea sinks this far under the waves' lowest trough (m). */
        const val DROP_MARGIN_M = .6f

        /** Whether a sea cell (not land, not a lake) lies within [NEAR_M] of cell ([cx], [cz]): every second cell looked at. */
        fun seaNear(sk: WorldSkeleton, cx: Double, cz: Double): Boolean {
            val r = (NEAR_M / Planet.CELL_M).toInt()
            val i0 = floor(cx).toInt(); val j0 = floor(cz).toInt()
            for (dj in -r..r step 2) for (di in -r..r step 2) {
                if (di * di + dj * dj > r * r) continue
                val j = (j0 + dj).coerceIn(0, WorldSkeleton.H - 1)
                if (!sk.land(i0 + di, j) && sk.biome(i0 + di, j) == 0) return true
            }
            return false
        }

        fun attach(town: FilamentTown, far: WorldFar, origin: RenderOrigin): WorldOcean = town.addExtra { WorldOcean(it, far, origin) }
    }
}
