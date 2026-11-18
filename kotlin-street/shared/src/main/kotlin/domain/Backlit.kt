package com.lifemax.rpg.feature.habits.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Shooting into a low sun ("contre-jour"), with no drawing in it. When the sun is low and in the
 * picture, near the buildings, a camera exposed for the sky sees the street as a photograph does:
 * the buildings, the furniture, the people and the cars in front of the bright sky go to warm, dark
 * silhouettes (their lit windows and signs still glowing), the sun overpowers the sky round it,
 * the whole frame is drenched in a warm haze, and soft shafts of light fan out from the sun
 * through the gaps between the buildings and over their roofs, dust catching the light in them.
 *
 * [factor] is how far the camera looks into the sun, 0 to 1: high with the sun low (sunrise, the
 * golden hour, sunset), in the picture and near the buildings; 0 with the sun high, out of the
 * picture or at night; less under the overcast, a cloud across the sun or the morning mist. It
 * follows the hour and the sun's place on the screen smoothly, so nothing pops while the street
 * scrolls. The rest is the arithmetic the drawing shares with its tests: how dark the exposure
 * takes a point at a distance from the sun ([shade]), how open each shaft is ([shafts]) and where
 * the motes of dust are ([moteX]).
 */
object Backlit {
    /**
     * How low the sun stands, from how high it is in its arc ([up], 0 at the horizon to 1 at the
     * top of it, [orbAt]): about 1 from sunrise to 7:00 and from 19:00 to sunset, a third at 8:00
     * and 18:00, nothing from 8:30 to 17:30.
     */
    fun low(up: Float): Float = 1f - smooth(LOW_FROM, LOW_TO, up)

    /**
     * How near the buildings the sun is, from its [gap] to the nearest of them in its radii (0 when
     * it touches one or is behind one): 1 at an edge, falling to [FAR] in open sky well clear of
     * them.
     */
    fun near(gap: Float): Float = FAR + (1f - FAR) * (1f - smooth(1.5f, 10f, gap))

    /**
     * How much of the sun is in the picture, for its middle at ([x], [y]) on a picture [w] by [h]
     * (px), [r] its radius: 1 well inside it, 0 once its glow is out of it, or once it is [reach]
     * past the picture's edge when that is given (a camera swinging past the sun: its glow fades
     * over the swing instead of in the few frames the sun takes to cross three radii).
     */
    fun inView(x: Float, y: Float, r: Float, w: Float, h: Float, reach: Float = r * 3f): Float {
        val m = max(reach, r * 3f)
        val dx = max(max(-x, x - w), 0f)
        val dy = max(max(-y, y - h), 0f)
        return 1f - smooth(0f, m, sqrt(dx * dx + dy * dy))
    }

    /**
     * How far the camera looks into the sun: [up] how high the sun is in its arc, [shown] how much
     * of it is in the sky (0 at night, fading after sunset: the scene's SunLook.up), [cloud] the
     * overcast's share, [cover] how far a cloud is across it, [mist] the morning fog's share, [gap]
     * its distance to the nearest building in its radii, [zoom] how far the street is zoomed out (0
     * to 1) and [view] how much of it is in the picture ([inView]).
     */
    fun factor(
        up: Float,
        shown: Float,
        cloud: Float,
        cover: Float,
        mist: Float,
        gap: Float,
        zoom: Float,
        view: Float,
    ): Float {
        val c = clamp(cloud)
        val weather = (1f - .9f * c) * (1f - .65f * clamp(cover)) * (1f - .5f * clamp(mist))
        return clamp(
            low(up) * clamp(shown) * weather * near(gap) * (1f - .35f * clamp(zoom)) * clamp(view)
        )
    }

    // ---- the exposure: the street multiplied by a warm dark, darkest round the sun

    /**
     * How far from the sun (a share of the picture's diagonal) the exposure's dark reaches its
     * outer value.
     */
    const val REACH = .95f

    /**
     * The exposure's stops: at these shares of [REACH] from the sun, the street is multiplied by
     * these colours (red, green, blue, 0 to 1) at full strength. Warm and dark near the sun, less
     * so out at the picture's far side, never black: a little detail stays in the shadows.
     */
    val STOPS = floatArrayOf(0f, .32f, 1f)
    val INK_R = floatArrayOf(.24f, .32f, .52f)
    val INK_G = floatArrayOf(.15f, .21f, .42f)
    val INK_B = floatArrayOf(.13f, .18f, .46f)

    /**
     * The exposure's multiplier for channel [c] (0 red, 1 green, 2 blue) at [t], a share of [REACH]
     * from the sun, at strength [k]: 1 (untouched) at 0, the stops' colours at 1.
     */
    fun ink(c: Int, t: Float, k: Float): Float {
        val stops =
            when (c) {
                0 -> INK_R
                1 -> INK_G
                else -> INK_B
            }
        val x = clamp(t)
        var v = stops[stops.size - 1]
        for (i in 1 until STOPS.size) {
            if (x <= STOPS[i]) {
                val u = (x - STOPS[i - 1]) / (STOPS[i] - STOPS[i - 1])
                v = stops[i - 1] + (stops[i] - stops[i - 1]) * u
                break
            }
        }
        return 1f - clamp(k) * (1f - v)
    }

    /**
     * How bright the exposure leaves a point [t] of [REACH] from the sun at strength [k], as the
     * eye weighs the three channels: what a lit window has lost there, to give back.
     */
    fun shade(t: Float, k: Float): Float =
        .2126f * ink(0, t, k) + .7152f * ink(1, t, k) + .0722f * ink(2, t, k)

    // ---- the shafts of light

    /** How many shafts fan out round the sun, evenly, turned a little ([shaftTurn]). */
    const val RAYS = 28

    /**
     * Where along each shaft its openness is tried, in the sun's radii, and how much each counts.
     */
    val PROBE = floatArrayOf(1.4f, 2.2f, 3.2f, 4.6f, 6.5f, 9f, 12.5f, 17f)
    val PROBE_W = floatArrayOf(1f, .9f, .8f, .7f, .6f, .5f, .4f, .3f)

    /** The angle (radians, 0 to the right, y down) of shaft [i], turned by [turn] (radians). */
    fun shaftAngle(i: Int, turn: Float): Float = i * (2f * PI.toFloat() / RAYS) + turn

    /**
     * How open shaft [i] is, from [open] (whether each of its [PROBE] points is in the open sky) as
     * a share, the nearer points counting more: the light that reaches the air along it.
     */
    fun openness(open: BooleanArray): Float {
        var sum = 0f
        var all = 0f
        for (j in PROBE.indices) {
            all += PROBE_W[j]
            if (open[j]) sum += PROBE_W[j]
        }
        return if (all > 0f) sum / all else 0f
    }

    /**
     * Fills [into] with how open each shaft round a sun at ([x], [y]) of radius [r] (px) is, past
     * the buildings in [edge], the fan turned by [turn] (radians). Allocation-free: [probe] is the
     * scratch for one shaft's points ([PROBE] long).
     */
    fun shafts(
        edge: SunEdge,
        x: Float,
        y: Float,
        r: Float,
        turn: Float,
        into: FloatArray,
        probe: BooleanArray,
    ) {
        for (i in 0 until RAYS) {
            val a = shaftAngle(i, turn)
            val c = cos(a)
            val s = sin(a)
            for (j in PROBE.indices) {
                val d = PROBE[j] * r
                probe[j] = !edge.inside(x + c * d, y + s * d)
            }
            into[i] = openness(probe)
        }
    }

    /**
     * How strong shaft [i] is drawn, for its [open]ness and how open the shafts round it are on
     * average ([mean]): a shaft shows against the shade of the ones beside it, so where every one
     * is open (the sun in the clear sky) they are faint and only their own unevenness shows; where
     * the buildings cut some off, the ones through the gaps and over the roofs stand out.
     */
    fun shaftStrength(i: Int, open: Float, mean: Float): Float {
        val own = .55f + .45f * hash(i, 17)
        val contrast = .35f + .65f * clamp((open - mean * .6f) / .5f)
        return clamp(open) * own * contrast
    }

    /** How long the shafts are, as a share of the picture's diagonal. */
    const val SHAFT_LENGTH = .9f

    /** The fan's turn (radians): slowly, so the shafts drift, and with the sun's place. */
    fun shaftTurn(seconds: Float, x: Float, w: Float): Float =
        .11f + seconds * .004f + .2f * (x / max(1f, w))

    // ---- the motes of dust drifting in the light

    /** How many motes there are, and how far from the sun they drift (dp). */
    const val MOTES = 34
    const val MOTE_REACH = 210f

    /** Mote [i]'s place across and down from the sun (dp) at [seconds]. */
    fun moteX(i: Int, seconds: Float): Float {
        val a = hash(i, 1) * 2f * PI.toFloat()
        val d = MOTE_REACH * sqrt(hash(i, 2))
        return cos(a) * d + 9f * sin(seconds * (.07f + .05f * hash(i, 3)) + 6f * hash(i, 4))
    }

    fun moteY(i: Int, seconds: Float): Float {
        val a = hash(i, 1) * 2f * PI.toFloat()
        val d = MOTE_REACH * sqrt(hash(i, 2))
        // drifting slowly down, coming round again from above
        val fall = (seconds * (1.2f + 1.5f * hash(i, 5)) + 40f * hash(i, 6)) % 40f - 20f
        return sin(a) * d * .7f + fall + 5f * cos(seconds * (.09f + .05f * hash(i, 7)))
    }

    /** Mote [i]'s size (dp) and how much it catches the light at [seconds] (0 to 1). */
    fun moteSize(i: Int): Float = .6f + 1.1f * hash(i, 8)

    fun moteGlint(i: Int, seconds: Float): Float =
        .45f + .55f * (.5f + .5f * sin(seconds * (.8f + .9f * hash(i, 9)) + 6.3f * hash(i, 10)))

    // ----

    /** The same number from 0 to 1 for the same [i] and [k], every time. */
    fun hash(i: Int, k: Int): Float {
        var n = i * 374761393 + k * 668265263
        n = (n xor (n ushr 13)) * 1274126177
        n = n xor (n ushr 16)
        return (n and 0xFFFF) / 65535f
    }

    private const val LOW_FROM = .25f
    private const val LOW_TO = .65f

    /** How much of the effect is left with the sun in open sky far from every building. */
    const val FAR = .62f

    private fun clamp(v: Float): Float = v.coerceIn(0f, 1f)

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
