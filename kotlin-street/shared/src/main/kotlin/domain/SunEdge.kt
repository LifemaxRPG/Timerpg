package com.lifemax.rpg.feature.habits.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * The sun at a building's edge, with no drawing in it. When the sun stands partly behind a building
 * the bright sliver that still shows flares: a hot point hugging the edge, a bloom that spills over
 * the dark silhouette, and the starburst of the lens's blades. This works out, each frame, how much
 * of the sun's disc shows past the buildings in front of it and where that sliver is (the "peek"),
 * how near the open sky is when it is hidden (for the light round the edge that hides it), and how
 * strong each part of the glare is for that.
 *
 * The buildings are given as boxes on the screen (pixels, y down), each pushed back along the
 * shown side and up by the roof's depth: a building's front, cornice and side face are one box
 * swept along that offset ([box]), what stands on the roof a box with no sweep. Allocation-free:
 * the boxes and the results live in arrays and fields written in place.
 */
class SunEdge {
    /** The boxes: left, top, right, bottom (px, y down), and the sweep (px; dy upwards). */
    val left = FloatArray(MAX)
    val top = FloatArray(MAX)
    val right = FloatArray(MAX)
    val bottom = FloatArray(MAX)
    val sweepX = FloatArray(MAX)
    val sweepY = FloatArray(MAX)
    var count = 0
        private set

    /** Above this y (px) nothing hides the sun: the buildings are cut off or faded out there. */
    var ceiling = Float.NEGATIVE_INFINITY

    /** How much of the sun's disc shows: 0 hidden to 1 in the open. */
    var visible = 1f
        private set

    /** The middle of the part of the disc that shows (px): where the glare is centred. */
    var peekX = 0f
        private set

    var peekY = 0f
        private set

    /** How much of a ring round the sun, out to about three of its radii, is open sky. */
    var near = 1f
        private set

    /** The middle of that open part of the ring (px): towards the edge the hidden sun is behind. */
    var nearX = 0f
        private set

    var nearY = 0f
        private set

    fun clear() {
        count = 0
    }

    /**
     * A box from [l] to [r] and [t] to [b] (px, y down), swept by [sx] across and [sy] up: every
     * point of the box moved by (t·[sx], -t·[sy]) for t from 0 to 1 is inside too.
     */
    fun box(l: Float, t: Float, r: Float, b: Float, sx: Float = 0f, sy: Float = 0f) {
        if (count >= MAX || r <= l || b <= t) return
        left[count] = l
        top[count] = t
        right[count] = r
        bottom[count] = b
        sweepX[count] = sx
        sweepY[count] = sy
        count++
    }

    /** Whether the point ([x], [y]) (px) is behind any of the boxes. */
    fun inside(x: Float, y: Float): Boolean {
        for (i in 0 until count) if (insideBox(i, x, y)) return true
        return false
    }

    /** Whether ([x], [y]) is inside box [i], its sweep included. */
    fun insideBox(i: Int, x: Float, y: Float): Boolean {
        if (y < ceiling) return false
        val sx = sweepX[i]
        val sy = sweepY[i]
        // the sweep's share t for which the point, moved back by it, falls in the box: from the
        // box's height and from its width, the two ranges meeting within 0..1
        var lo = 0f
        var hi = 1f
        if (abs(sy) < EPS) {
            if (y < top[i] || y > bottom[i]) return false
        } else {
            val a = (top[i] - y) / sy
            val b = (bottom[i] - y) / sy
            lo = max(lo, min(a, b))
            hi = min(hi, max(a, b))
        }
        if (abs(sx) < EPS) {
            if (x < left[i] || x > right[i]) return false
        } else {
            val a = (x - right[i]) / sx
            val b = (x - left[i]) / sx
            lo = max(lo, min(a, b))
            hi = min(hi, max(a, b))
        }
        return lo <= hi
    }

    /**
     * How far ([x], [y]) (px) is from the nearest box, its sweep included (its front and its swept
     * copy tried as rectangles), 0 behind one, and infinite with no box: for how near the buildings
     * the sun stands ([Backlit.near]). Continuous as the boxes move.
     */
    fun gap(x: Float, y: Float): Float {
        var best = Float.POSITIVE_INFINITY
        for (i in 0 until count) {
            if (insideBox(i, x, y)) return 0f
            val sx = sweepX[i]
            val sy = sweepY[i]
            best = min(best, rectGap(x, y, left[i], top[i], right[i], bottom[i]))
            best =
                min(best, rectGap(x, y, left[i] + sx, top[i] - sy, right[i] + sx, bottom[i] - sy))
        }
        return best
    }

    private fun rectGap(x: Float, y: Float, l: Float, t: Float, r: Float, b: Float): Float {
        // the part above the ceiling is not drawn
        val tt = max(t, ceiling)
        if (b <= tt) return Float.POSITIVE_INFINITY
        val dx = max(max(l - x, x - r), 0f)
        val dy = max(max(tt - y, y - b), 0f)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * Looks at a sun at ([x], [y]) of radius [r] (px): how much of its disc shows ([visible]) and
     * where ([peekX], [peekY]), and how open the sky round it is ([near], [nearX], [nearY]).
     */
    fun measure(x: Float, y: Float, r: Float) {
        // only the boxes that come near the sun are tried
        var shown = 0
        var sumX = 0f
        var sumY = 0f
        val n = DISC_X.size
        val reach = r * RING_OUT
        var any = false
        for (i in 0 until count) if (nearBox(i, x, y, reach)) any = true
        if (!any) {
            visible = 1f
            peekX = x
            peekY = y
            near = 1f
            nearX = x
            nearY = y
            return
        }
        for (k in 0 until n) {
            val px = x + DISC_X[k] * r
            val py = y + DISC_Y[k] * r
            if (!insideNear(px, py, x, y, reach)) {
                shown++
                sumX += px
                sumY += py
            }
        }
        visible = shown.toFloat() / n
        if (shown > 0) {
            peekX = sumX / shown
            peekY = sumY / shown
        } else {
            peekX = x
            peekY = y
        }
        var open = 0
        var ox = 0f
        var oy = 0f
        for (k in 0 until RING) {
            val a = k * (2f * PI.toFloat() / RING)
            val c = cos(a)
            val s = sin(a)
            for (j in 0 until 2) {
                val rr = r * (if (j == 0) RING_IN else RING_OUT)
                val px = x + c * rr
                val py = y + s * rr
                if (!insideNear(px, py, x, y, reach)) {
                    open++
                    ox += px
                    oy += py
                }
            }
        }
        near = open / (RING * 2f)
        if (open > 0) {
            nearX = ox / open
            nearY = oy / open
        } else {
            nearX = x
            nearY = y
        }
    }

    private fun insideNear(px: Float, py: Float, x: Float, y: Float, reach: Float): Boolean {
        for (i in 0 until count) if (nearBox(i, x, y, reach) && insideBox(i, px, py)) return true
        return false
    }

    /** Whether box [i] (its sweep included) comes within [reach] of ([x], [y]). */
    fun nearBox(i: Int, x: Float, y: Float, reach: Float): Boolean {
        val l = min(left[i], left[i] + sweepX[i])
        val r = max(right[i], right[i] + sweepX[i])
        val t = min(top[i], top[i] - sweepY[i])
        val b = max(bottom[i], bottom[i] - sweepY[i])
        return x + reach >= l && x - reach <= r && y + reach >= t && y - reach <= b
    }

    companion object {
        const val MAX = 32
        private const val EPS = 1e-4f

        /** The open-sky ring's two radii, in the sun's radii, and how many points round each. */
        const val RING_IN = 1.7f
        const val RING_OUT = 2.8f
        const val RING = 16

        /** The points the disc is tried at: a grid over the unit disc, 1 radius out. */
        private val DISC_X: FloatArray
        private val DISC_Y: FloatArray

        init {
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            val g = 17
            for (j in 0 until g) for (i in 0 until g) {
                val u = (i + .5f) / g * 2f - 1f
                val v = (j + .5f) / g * 2f - 1f
                if (u * u + v * v <= 1f) {
                    xs += u
                    ys += v
                }
            }
            DISC_X = xs.toFloatArray()
            DISC_Y = ys.toFloatArray()
        }

        /** How many points the disc is tried at. */
        val samples: Int
            get() = DISC_X.size

        /**
         * The starburst and the hot point, for a disc this [visible]: none while it is hidden, full
         * as soon as a sliver shows and while it is a small part, fading as more of the sun comes
         * out, gone in the open (where the lens flare takes over).
         */
        fun spikes(visible: Float): Float =
            smooth(0f, .03f, visible) * (1f - smooth(.06f, 1f, visible))

        /** The bloom over the edge: as the starburst, but it lasts longer as the sun comes out. */
        fun bloom(visible: Float): Float =
            smooth(0f, .05f, visible) * (1f - smooth(.55f, 1f, visible))

        /**
         * The thin light along the edges near the sun (the backlit rim): strongest with the sun
         * just behind the edge ([visible] small, the sky [near] it partly open), weaker as it comes
         * out, none when it is deep behind a building (no open sky near it).
         */
        fun rim(visible: Float, near: Float): Float =
            (1f - .7f * clamp(visible)) * smooth(0f, .25f, near)

        /**
         * How far the rays' fan is turned (degrees) for a sliver at ([x], [y]) on a picture [w] by
         * [h]: a little, as the sun's place on the screen moves.
         */
        fun turn(x: Float, y: Float, w: Float, h: Float): Float =
            TURN0 + 28f * (x / max(1f, w)) + 14f * (y / max(1f, h))

        private const val TURN0 = 9f

        /**
         * The starburst's rays: each one's angle (degrees, before [turn]), its length (a share of
         * the longest) and its width (a share of the main rays'). Eight main rays of uneven length,
         * the blades of the lens, and finer ones between them.
         */
        val RAY_ANGLE =
            floatArrayOf(
                0f,
                45f,
                90f,
                135f,
                180f,
                225f,
                270f,
                315f,
                22f,
                67f,
                112f,
                158f,
                203f,
                248f,
                293f,
                338f,
            )
        val RAY_LENGTH =
            floatArrayOf(
                1f,
                .58f,
                .82f,
                .5f,
                .92f,
                .62f,
                .74f,
                .54f,
                .36f,
                .28f,
                .4f,
                .25f,
                .33f,
                .3f,
                .38f,
                .26f,
            )
        val RAY_WIDTH =
            floatArrayOf(
                1f,
                .8f,
                .9f,
                .8f,
                1f,
                .8f,
                .9f,
                .8f,
                .55f,
                .5f,
                .55f,
                .5f,
                .55f,
                .5f,
                .55f,
                .5f,
            )
        val RAY_ALPHA =
            floatArrayOf(
                1f,
                .8f,
                .9f,
                .75f,
                1f,
                .8f,
                .9f,
                .75f,
                .5f,
                .45f,
                .5f,
                .45f,
                .5f,
                .45f,
                .5f,
                .45f,
            )

        private fun clamp(v: Float): Float = v.coerceIn(0f, 1f)

        private fun smooth(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
