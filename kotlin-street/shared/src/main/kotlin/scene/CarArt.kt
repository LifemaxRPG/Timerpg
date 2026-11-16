package com.lifemax.rpg.feature.habits.presentation.scene

import com.lifemax.rpg.feature.habits.domain.StreetLife
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The cars on the front road as pre-rendered pictures, side on, at street scale (about 14 units to
 * the metre): six bodies (a saloon, a hatchback, an SUV, a city car, an estate and a people
 * carrier) drawn from smooth outlines (centripetal Catmull-Rom through hand-set points) as signed
 * distances, so every edge is anti-aliased. A body is baked once in grey (its paint's shading: the
 * lit shoulder, the shadowed flank under the crease, the roll of its edges) that the painter
 * multiplies by any paint, and once more in colour for what is not paint (the glass with the sky
 * in it, the pillars and seals, the shut lines and handles, the lamps, the wheel wells, the
 * reflections along the side and the shadow under it), by day and by night; its lamps' bloom is
 * a third picture. The wheels are their own pictures (four rim designs) so they turn. Pure
 * Kotlin: pixels only; [SceneSprites] turns them into images the first time each is drawn.
 */

/**
 * A car, nose to the right, in pixels of its pictures: [paint] (grey, the whole silhouette) is
 * drawn multiplied by the paint's colour, [day] or [night] over it, [lights] added at night. The
 * wheels' centres and radius ([wheelR] in street units), the ground at [groundY], the lamps' rows,
 * the roof's flat run (for a rack or a taxi's sign) and the rack's picture.
 */
internal class CarSprite(
    val w: Int,
    val h: Int,
    val ppu: Float,
    val paint: IntArray,
    val day: IntArray,
    val night: IntArray,
    val lights: IntArray,
    val wheelX0: Float,
    val wheelX1: Float,
    val wheelY: Float,
    val wheelR: Float,
    val rim: Int,
    val groundY: Float,
    val frontX: Float,
    val backX: Float,
    val lampY: Float,
    val tailY: Float,
    /** The car's length, bumper to bumper, in street units. */
    val length: Float,
    val roofX0: Float,
    val roofX1: Float,
    val roofY: Float,
    val rack: IntArray,
    val rackW: Int,
    val rackH: Int,
    val rackX: Float,
    val rackY: Float,
)

internal object CarArt {
    /** Pixels to a street unit. */
    const val PPU = 3f

    /** Pixels left round the car for its lamps' bloom. */
    private const val PAD = 22

    /**
     * The rim designs: five twin spokes, ten fine spokes, a steel wheel's trim, five broad spokes.
     */
    const val RIMS = 4

    /**
     * The paints ([StreetLife.CAR_PAINTS]): white, black, silver, gunmetal, navy, red, racing
     * green, champagne, blue, burgundy, taxi yellow.
     */
    val PAINTS =
        intArrayOf(
            0xE9EBED,
            0x18191D,
            0xA9AEB4,
            0x565C64,
            0x1B2C48,
            0x9A1A1E,
            0x29422F,
            0xB3A486,
            0x2A64A6,
            0x531722,
            0xEDB111,
        )

    /** A paint as the night has it: mostly the night's own blue. */
    fun nightPaint(rgb: Int): Int = mix(rgb, 0x0B0E18, .7f)

    // ---- the bodies, in street units: x from the tail, y up from the road

    private class Body(
        val length: Float,
        val rearAxle: Float,
        val frontAxle: Float,
        val sill: Float,
        val crease: Float,
        val belt: Float,
        val outline: FloatArray,
        /** The side glass (the daylight opening), its outline. */
        val dlo: FloatArray,
        /** The pillars over the glass: each one's foot x, head x and width. */
        val pillars: FloatArray,
        /** The shut lines: polylines, each a count of points then its points. */
        val seams: FloatArray,
        /** The door handles' middles. */
        val handles: FloatArray,
        val head: FloatArray,
        val tail: FloatArray,
        val mirrorX: Float,
        val mirrorY: Float,
        val roof0: Float,
        val roof1: Float,
        val roofY: Float,
        /** Black plastic round the arches and along the sills (an SUV). */
        val clad: Boolean,
        /** A bright trim along the window line. */
        val chrome: Boolean,
        val rim: Int,
        /** The roof's aerial fin, or a negative for none. */
        val fin: Float,
        val index: Int,
    ) {
        val wheelR = StreetLife.carWheel(index)
    }

    /** Numbers written out in a string, apart by spaces (point after point: x then y). */
    private fun f(s: String): FloatArray =
        s.trim().split(' ', '\n').filter { it.isNotBlank() }.map { it.toFloat() }.toFloatArray()

    /*
     * The six bodies. Each: length, rear and front axles, sill, crease and belt heights; the
     * outline from the tail's foot round over the roof and down the nose; the side glass; the
     * pillars (foot x, head x, width each); the shut lines (each a count of points, then its
     * points); the handles; the head and tail lamps; the mirror's foot; the roof's flat run and
     * height; cladding, a bright window trim, the rim design, the aerial fin's x, the index.
     */
    private val BODIES =
        arrayOf(
            // the saloon
            Body(
                length = 66f,
                rearAxle = 13.2f,
                frontAxle = 53.6f,
                sill = 3.2f,
                crease = 11.4f,
                belt = 14.9f,
                outline =
                    f(
                        """
                    1.6 3.5 .4 4.6 0 7.5 .2 11.2 .9 12.8 3 13.7 11.8 14.3 14.5 15.2 17.5 17 21.5
                    19.6 25.5 20.9 30 21.4 36 21.4 40.5 20.8 43.5 19.5 47.5 16.8 50.5 15.2 54 14.5
                    59.5 13.7 63.5 12.7 65.3 11.4 65.9 9.4 66 7 65.6 4.9 64.6 3.7 62.8 3.2 54 3 13
                    3 3.5 3.2
                    """
                    ),
                dlo =
                    f(
                        """
                    19.2 15.45 20.2 16.3 24.6 19.4 30 20.35 36 20.4 40.3 19.9 46.6 16.2 48.4 15.2
                    47.4 15.15 34 15.3 20.2 15.45
                    """
                    ),
                pillars = f("33.6 33 1.4"),
                seams =
                    f(
                        """
                    2 47.9 15 47.3 3.4 2 33.6 15 33.4 3.3 2 19.6 15.4 19.1 3.4 3 51.2 14.6 58
                    13.75 63.2 12.5 2 2.2 13.3 11.8 13.85 3 61.4 12.3 60 8 59.4 6.2 3 3 12.6 5.2
                    8.6 7.6 6.6
                    """
                    ),
                handles = f("43.2 13.4 29.2 13.5"),
                head = f("58.6 13.3 63.4 12.45 65.2 11.3 65.4 10.6 62.2 10.7 58.4 12.4"),
                tail = f(".25 11 .9 12.7 3.2 13.6 6.4 13.75 6.2 12.6 1.6 11.4"),
                mirrorX = 46.4f,
                mirrorY = 15.2f,
                roof0 = 28f,
                roof1 = 39f,
                roofY = 21.4f,
                clad = false,
                chrome = true,
                rim = 0,
                fin = 26.5f,
                index = 0,
            ),
            // the hatchback
            Body(
                length = 60f,
                rearAxle = 11.6f,
                frontAxle = 48.6f,
                sill = 3.1f,
                crease = 11.6f,
                belt = 15.2f,
                outline =
                    f(
                        """
                    1.4 3.4 .3 4.6 0 7.5 .15 11.5 .6 14.2 1.5 16.6 3 18.9 5 20.4 8.5 21.2 14 21.55
                    24 21.6 30.5 21.2 35 19.9 38.5 17.8 42.3 15.8 45.5 14.7 49.5 14.1 54.5 13.3 58
                    12.2 59.5 10.8 60 8.6 59.8 6 59.1 4.3 57.8 3.3 49 3 12 3 3.4 3.15
                    """
                    ),
                dlo =
                    f(
                        """
                    10.4 15.6 11.6 18 13.4 19.9 15.5 20.45 24 20.6 30.2 20.3 34.3 19.3 39.8 16.2
                    41.6 15.3 40.6 15.3 25 15.4 11.4 15.55
                    """
                    ),
                pillars = f("28 27.4 1.4 16.2 16.8 1.1"),
                seams =
                    f(
                        """
                    2 42.3 15.4 42 3.3 2 28.1 15.3 27.9 3.2 3 16.4 15.5 17 10.5 17.3 3.3 3 45.6
                    14.5 52 13.8 57.6 12.1 3 55.8 12.4 54.6 8.2 53.9 6 3 3 18.6 1.4 15.2 .9 11.8 4
                    .9 11.8 3.4 10.9 5.4 8.4 6.6 6.4
                    """
                    ),
                handles = f("37 13.8 24 13.9"),
                head = f("53.2 13.6 57.6 12.4 59.3 11 59.4 10.4 56.2 10.6 53 12.6"),
                tail = f(".5 13 1.2 15.6 2.2 16.4 4.6 16.2 4.4 14.6 1.6 13.2"),
                mirrorX = 40.4f,
                mirrorY = 15.5f,
                roof0 = 10f,
                roof1 = 28f,
                roofY = 21.6f,
                clad = false,
                chrome = false,
                rim = 1,
                fin = 8f,
                index = 1,
            ),
            // the SUV
            Body(
                length = 67f,
                rearAxle = 13.4f,
                frontAxle = 54.2f,
                sill = 4.4f,
                crease = 13.6f,
                belt = 17.6f,
                outline =
                    f(
                        """
                    1.6 5 .4 6.4 0 9.5 .1 14.5 .45 18.5 1.2 21.6 2.4 23.7 4.5 24.6 10 24.9 24 25
                    34 24.9 39.5 24.4 42.5 23 47 19.9 50 18.3 54 17.6 59.5 16.9 64 16 66.2 14.6
                    66.9 12.4 67 9.6 66.6 7 65.6 5.3 64 4.6 55 4.3 13 4.3 3.6 4.5
                    """
                    ),
                dlo =
                    f(
                        """
                    6.2 17.9 6.6 21.2 7.6 23.1 9.6 23.8 20 23.95 34 23.95 39 23.5 45.6 19.4 47.4
                    18.3 46.4 18.2 35 18 20 17.85 7.2 17.85
                    """
                    ),
                pillars = f("32.2 31.8 1.5 16 16.4 1.3"),
                seams =
                    f(
                        """
                    2 47.4 18.2 46.6 4.6 2 32.3 17.7 32 4.5 3 16.3 17.9 19.6 11.6 20.8 4.6 3 50
                    17.9 57 17.15 63.6 15.8 3 62.2 15.6 61 10.6 60.4 8.6 3 2.6 23 1 17 .6 13 3 .4
                    10.6 5.4 10.2 7 8.6
                    """
                    ),
                handles = f("42.8 16.3 27.8 16.4"),
                head = f("59.2 16.6 64 15.6 66 14.3 66.3 13.3 62.6 13.5 59 15.6"),
                tail = f(".3 15.4 .6 19.2 1.6 19.8 4.4 19.4 4.2 17.8 1.3 15.6"),
                mirrorX = 45f,
                mirrorY = 18f,
                roof0 = 10f,
                roof1 = 36f,
                roofY = 25f,
                clad = true,
                chrome = false,
                rim = 3,
                fin = 9f,
                index = 2,
            ),
            // the city car
            Body(
                length = 52f,
                rearAxle = 9.4f,
                frontAxle = 42.8f,
                sill = 3f,
                crease = 11f,
                belt = 14.6f,
                outline =
                    f(
                        """
                    1.3 3.3 .3 4.5 0 7.4 .15 11.5 .6 15 1.5 18.2 3 20.3 5.4 21.4 10 21.8 20 21.85
                    25.5 21.4 29 20 32.5 17.4 36 15.2 39.5 14.4 44 13.8 48.6 12.9 51.2 11.5 52 9
                    51.8 6.2 51 4.3 49.6 3.2 43 2.9 10 2.9 3 3.05
                    """
                    ),
                dlo =
                    f(
                        """
                    4.6 15 5.2 17.8 6.4 19.9 8.4 20.8 14 20.95 20 20.95 24.9 20.5 28.6 19 33.6
                    15.6 35 14.95 34 14.9 25 14.85 14 14.85 5.4 14.95
                    """
                    ),
                pillars = f("18.4 18.8 1.5"),
                seams =
                    f(
                        """
                    2 35.6 14.9 36.4 3.1 2 19.4 14.9 19.2 3.1 3 37 15 44 13.6 48.4 12.6 3 49.6 12
                    48.6 8.3 47.9 6.6 3 2.6 19.6 1 15.4 .6 12 3 .6 12 3.2 11 4.6 7.2
                    """
                    ),
                handles = f("31.5 13.3"),
                head = f("45.8 13.6 49.2 12.6 51 11.4 51.3 10.2 48.3 10.6 45.6 12.6"),
                tail = f(".4 12.4 .8 16 1.8 16.6 3.4 16.2 3.2 14 1.4 12.6"),
                mirrorX = 32.8f,
                mirrorY = 15f,
                roof0 = 8f,
                roof1 = 22f,
                roofY = 21.85f,
                clad = false,
                chrome = false,
                rim = 2,
                fin = -1f,
                index = 3,
            ),
            // the estate
            Body(
                length = 70f,
                rearAxle = 14f,
                frontAxle = 56.4f,
                sill = 3.2f,
                crease = 11.5f,
                belt = 15f,
                outline =
                    f(
                        """
                    1.6 3.4 .4 4.6 0 7.5 .15 11.5 .5 14.6 1.2 17.6 2.3 19.7 4 20.8 8 21.25 20 21.4
                    32 21.45 38 21.2 42.5 20.4 45.6 19 50 16.4 53.2 15 57 14.4 62.5 13.7 67 12.6
                    69.2 11.3 69.9 9.3 70 7 69.6 4.9 68.6 3.7 66.8 3.2 57 3 14 3 3.6 3.2
                    """
                    ),
                dlo =
                    f(
                        """
                    4.8 15.3 5.3 18 6.4 19.8 8.2 20.4 20 20.5 32 20.5 38 20.3 42.3 19.7 49 16 51
                    15.15 50 15.1 40 15.1 25 15.15 12 15.25 5.6 15.3
                    """
                    ),
                pillars = f("35.2 34.6 1.4 16.8 17.2 1.3"),
                seams =
                    f(
                        """
                    2 50.4 15.1 49.8 3.3 2 35.2 15 35 3.2 3 17.4 15.2 19.6 10.6 19.9 3.3 3 53.4
                    14.8 61 13.8 66.6 12.5 3 64.8 12.4 63.6 8.2 62.2 6.2 3 2.6 19.6 1 15.4 .6 12.2
                    4 .6 12.2 4 11.2 6 8.2 8 6.4
                    """
                    ),
                handles = f("45.4 13.6 30.6 13.7"),
                head = f("62.4 13.6 67.2 12.6 69.1 11.3 69.3 10.5 66 10.7 62.2 12.6"),
                tail = f(".4 13 .8 16.6 1.8 17.4 4.2 17 4 15 1.4 13.2"),
                mirrorX = 48.2f,
                mirrorY = 15.3f,
                roof0 = 10f,
                roof1 = 38f,
                roofY = 21.4f,
                clad = false,
                chrome = true,
                rim = 1,
                fin = 9f,
                index = 4,
            ),
            // the people carrier
            Body(
                length = 72f,
                rearAxle = 13.6f,
                frontAxle = 58.2f,
                sill = 3.8f,
                crease = 13.6f,
                belt = 18.4f,
                outline =
                    f(
                        """
                    1.4 4.1 .3 5.3 0 8.5 0 14 .05 20 .25 25.4 .9 27.4 2.4 28.3 6 28.5 20 28.55 36
                    28.55 45 28.4 48.6 27.8 51 26.4 54.5 22.8 57.6 19.8 60.4 18.2 64 17.4 68.4
                    16.3 71 14.8 71.9 12.4 72 9.6 71.6 7 70.6 5.2 69 4.2 59 3.8 14 3.8 3.6 3.95
                    """
                    ),
                dlo =
                    f(
                        """
                    3 18.6 3.1 23 3.6 26.2 5.2 27.2 20 27.3 36 27.3 46 27.2 49.2 26.4 51 25 54
                    21.6 56.2 19.3 57 18.7 56 18.6 45 18.5 30 18.5 15 18.55 3.6 18.6
                    """
                    ),
                pillars = f("46.4 46.2 1.6 28.8 28.8 1.4"),
                seams =
                    f(
                        """
                    4 56.8 18.6 54.4 13 52.2 10.8 51.6 4 2 46.2 18.4 46 3.9 2 29 18.4 28.8 3.9 2 4
                    17.2 28.8 17.2 2 1.2 27 .6 9 3 .4 9 4.6 8.6 6.4 7.4 3 67.6 16 66 10.4 64.6 7.6
                    3 58.4 19.4 64 17.2 68.4 16.1
                    """
                    ),
                handles = f("52 16.2 43.6 16.4"),
                head = f("64.4 17.1 68.6 16 70.8 14.6 71.2 13.4 67.4 13.8 64.2 16"),
                tail = f(".05 13.5 .1 20.5 1 20.8 2.4 20.4 2.2 13.8 .9 13.3"),
                mirrorX = 54f,
                mirrorY = 18.9f,
                roof0 = 8f,
                roof1 = 44f,
                roofY = 28.55f,
                clad = false,
                chrome = false,
                rim = 2,
                fin = -1f,
                index = 5,
            ),
        )

    // ---- baking

    /** Car [body] ([StreetLife.CAR_BODIES]): its grey paint, day, night and lights. */
    fun car(body: Int): CarSprite = Baker(BODIES[body]).bake()

    private class Baker(val b: Body) {
        val k = PPU
        val L = b.length
        val top = run {
            var t = 0f
            for (i in 1 until b.outline.size step 2) t = max(t, b.outline[i])
            t
        }
        val w = ceil(L * k).toInt() + 2 * PAD
        val h = ceil((top + 1.5f) * k).toInt() + 10 + 8
        val G = h - 8f

        fun X(u: Float) = PAD + u * k

        fun Y(v: Float) = G - v * k

        fun U(x: Float) = (x - PAD) / k

        fun V(y: Float) = (G - y) / k

        val body = Grid(w, h, poly(b.outline))
        val glass = poly(b.dlo)
        val arches =
            Or(
                Circle(X(b.rearAxle), Y(b.wheelR), (b.wheelR + .9f) * k),
                Circle(X(b.frontAxle), Y(b.wheelR), (b.wheelR + .9f) * k),
            )

        /** The painted shell: the body less its arches, and the mirror. */
        val shell = Minus(body, arches)
        val mirror =
            poly(
                floatArrayOf(
                    b.mirrorX - .4f,
                    b.mirrorY + .5f,
                    b.mirrorX + 1.6f,
                    b.mirrorY + .9f,
                    b.mirrorX + 2.7f,
                    b.mirrorY + 1.6f,
                    b.mirrorX + 2.5f,
                    b.mirrorY + 2.6f,
                    b.mirrorX + .6f,
                    b.mirrorY + 2.9f,
                    b.mirrorX - .5f,
                    b.mirrorY + 2.2f,
                ),
                smooth = true,
            )
        val lampY = run {
            var s = 0f
            for (i in 1 until b.head.size step 2) s += b.head[i]
            s / (b.head.size / 2)
        }
        val tailY = run {
            var s = 0f
            for (i in 1 until b.tail.size step 2) s += b.tail[i]
            s / (b.tail.size / 2)
        }

        fun poly(pts: FloatArray, smooth: Boolean = true): Sd {
            val (us, vs) =
                if (smooth) spline(pts)
                else
                    Pair(
                        FloatArray(pts.size / 2) { pts[it * 2] },
                        FloatArray(pts.size / 2) { pts[it * 2 + 1] },
                    )
            return Poly(FloatArray(us.size) { X(us[it]) }, FloatArray(vs.size) { Y(vs[it]) })
        }

        fun seg(u0: Float, v0: Float, u1: Float, v1: Float, r: Float): Sd =
            Seg(X(u0), Y(v0), X(u1), Y(v1), r * k)

        fun box(u0: Float, v0: Float, u1: Float, v1: Float, r: Float): Sd =
            Box(X(u0), Y(v1), X(u1), Y(v0), r * k)

        fun bake(): CarSprite {
            val paint = paintLayer()
            val day = detail(false)
            val night = detail(true)
            val lights = lights()
            val rackL = rack()
            return CarSprite(
                w,
                h,
                k,
                paint.argb(),
                day.argb(),
                night.argb(),
                lights.argb(),
                X(b.rearAxle),
                X(b.frontAxle),
                Y(b.wheelR),
                b.wheelR,
                b.rim,
                G,
                X(L),
                X(0f),
                Y(lampY),
                Y(tailY),
                L,
                X(b.roof0),
                X(b.roof1),
                Y(b.roofY),
                rackL.argb(),
                rackL.w,
                rackL.h,
                X(b.roof0 - 1f),
                Y(b.roofY + 2.2f),
            )
        }

        /** The paint's shading, grey: lit shoulder, the crease, the flank turning under. */
        private fun paintLayer(): Layer {
            val c = Layer(w, h)
            val g = body.g
            c.fill(shell, 0xFFFFFF) { x, y ->
                val u = U(x)
                val v = V(y)
                var s = flank(v)
                // the ends roll away from us
                val e = min(u, L - u)
                s *= 1f - .14f * (1f - Px.smooth(0f, 5f, e))
                // the outline's own roll: top edges catch the sky, lower ones turn into shade
                val xi = x.toInt().coerceIn(1, w - 2)
                val yi = y.toInt().coerceIn(1, h - 2)
                val d = g[yi * w + xi]
                val gx = g[yi * w + xi + 1] - g[yi * w + xi - 1]
                val gy = g[(yi + 1) * w + xi] - g[(yi - 1) * w + xi]
                val len = sqrt(gx * gx + gy * gy)
                if (len > 1e-4f) {
                    val up = -gy / len
                    val rim = 1f - Px.smooth(0f, 1.6f * k, -d)
                    s *= 1f + rim * (.06f * max(up, 0f) - .3f * max(-up, 0f) - .1f * (1f - abs(up)))
                }
                s
            }
            // the mirror's housing, lit from above
            c.fill(mirror, 0xFFFFFF) { _, y ->
                val t = ((y - Y(b.mirrorY + 2.9f)) / (2.4f * k)).coerceIn(0f, 1f)
                1f - .35f * t
            }
            return c
        }

        /**
         * The paint's brightness at height [v]: the shoulder bright, the flank under the crease
         * darker.
         */
        private fun flank(v: Float): Float {
            val cr = b.crease
            val upper =
                if (v >= b.belt) .9f + .04f * Px.smooth(b.belt, b.belt + 3f, v)
                else .95f + .05f * ((v - cr) / (b.belt - cr)).coerceIn(0f, 1f)
            val t = ((cr - v) / (cr - b.sill)).coerceIn(0f, 1f)
            val lower = .8f - .24f * t * t
            return lower + (upper - lower) * Px.smooth(cr - .25f, cr + .12f, v)
        }

        /** Everything not paint, by day or by night. */
        private fun detail(night: Boolean): Layer {
            val c = Layer(w, h)
            val nk = if (night) .4f else 1f
            val r = b.wheelR
            // the shadow under the car: a soft wide pool and the tyres' dark contact
            c.field(0x050608, X(-2f), Y(1.5f), X(L + 2f), Y(-2.5f)) { x, y ->
                val ex = (U(x) - L / 2f) / (L * .52f)
                val ey = V(y) / 1.5f
                val f = (1f - sqrt(ex * ex + ey * ey)).coerceIn(0f, 1f)
                val under = if (V(y) > 0f) Px.smooth(b.sill, 0f, V(y)) else 1f
                f * .78f * under
            }
            for (ax in floatArrayOf(b.rearAxle, b.frontAxle)) {
                c.field(0x020203, X(ax - r), Y(1f), X(ax + r), Y(-1f)) { x, y ->
                    val ex = (U(x) - ax) / (r * .95f)
                    val ey = V(y) / .7f
                    (1f - sqrt(ex * ex + ey * ey)).coerceIn(0f, 1f) * .9f
                }
            }
            // the dark under the sills, between the wheels
            c.fill(box(b.rearAxle, 0f, b.frontAxle, b.sill + .2f, 0f), 0x08090B, .85f) { _, y ->
                1f
            }
            // the wheel wells: dark inside the arches, over the sills
            for (ax in floatArrayOf(b.rearAxle, b.frontAxle)) {
                val well = Circle(X(ax), Y(r), (r + .9f) * k)
                c.fill(And(well, box(ax - r - 2f, b.sill - .4f, ax + r + 2f, r + 3f, 0f)), 0x0A0B0D)
                // the arch's turned lip
                c.fill(And(Stroke(well, .22f * k), body), 0x000000, .45f * nk)
                if (b.clad) {
                    c.fill(And(Minus(Circle(X(ax), Y(r), (r + 2f) * k), well), body), 0x202226) {
                        _,
                        y ->
                        .9f + .25f * ((Y(r) - y) / (r * k)).coerceIn(-1f, 1f)
                    }
                }
            }
            if (b.clad)
                c.fill(And(box(0f, 0f, L, b.sill + 2f, 0f), shell), 0x1F2125) { _, y ->
                    .85f + .3f * ((G - y) / (k * (b.sill + 2f)))
                }
            // the light along the side: the sky in the shoulder, the horizon's line under the
            // crease, the road's grey low down
            val side = Minus(shell, glass)
            c.field(0xFFFFFF, X(0f), Y(b.belt + .5f), X(L), Y(b.sill), side) { x, y ->
                val v = V(y)
                val e = Px.smooth(0f, 6f, min(U(x), L - U(x)))
                val sky = if (v > b.crease) .08f + .16f * Px.smooth(b.crease, b.belt, v) else 0f
                val ground = .05f * Px.smooth(b.sill + 3f, b.sill, v)
                (sky + ground) * e * nk
            }
            c.field(0x000000, X(0f), Y(b.crease + .2f), X(L), Y(b.sill), side) { x, y ->
                val v = V(y)
                val e = Px.smooth(0f, 4f, min(U(x), L - U(x)))
                val t = (b.crease - v) / 3f
                if (t < 0f) 0f else .26f * (1f - t).coerceIn(0f, 1f) * e
            }
            // the crease's bright edge, the reflection line down the side
            c.field(0xFFFFFF, X(0f), Y(b.crease + 1f), X(L), Y(b.crease - .5f), side) { x, y ->
                val d = abs(V(y) - (b.crease + .3f))
                val e = Px.smooth(1f, 9f, min(U(x), L - U(x)))
                (1f - d / .3f).coerceIn(0f, 1f) * .55f * e * (if (night) .25f else 1f)
            }
            if (night) {
                // a street lamp's warm streak in the shoulder
                c.field(0xFFC98A, X(0f), Y(b.belt), X(L), Y(b.crease), side) { x, y ->
                    val d = abs(V(y) - (b.crease + 1.4f))
                    val along = sin((U(x) / L) * PI.toFloat())
                    (1f - d / .7f).coerceIn(0f, 1f) * .14f * along * along
                }
            }
            // the glass: the sky's reflection over the dark of the cabin
            val gTop = Y(b.roofY)
            val gBot = Y(b.belt)
            c.fill(glass, if (night) 0x07090D else 0x1A2028) { x, y ->
                val t = ((y - gTop) / (gBot - gTop)).coerceIn(0f, 1f)
                if (night) 1.3f - .5f * t else 2.6f - 1.5f * t
            }
            if (!night) {
                // the headrests seen through the glass
                for (i in 0 until b.handles.size / 2) {
                    val hx = b.handles[i * 2] - 3.2f
                    c.fill(
                        And(box(hx - 1.3f, b.belt + 2.4f, hx + .9f, b.belt + 4.9f, .8f), glass),
                        0x0A0C10,
                        .55f,
                    )
                }
            }
            // the sky's streaks across the glass
            c.field(0xFFFFFF, X(0f), gTop - 2f, X(L), gBot + 2f, glass) { x, y ->
                val s = (U(x) + V(y) * .9f)
                val band = abs(((s / 13f) % 1f + 1f) % 1f - .45f)
                val fine = abs(((s / 13f + .27f) % 1f + 1f) % 1f - .5f)
                val upper = Px.smooth(b.belt, b.roofY, V(y))
                ((1f - band / .12f).coerceIn(0f, 1f) * .13f +
                    (1f - fine / .03f).coerceIn(0f, 1f) * .08f +
                    .12f * upper * upper) * (if (night) .25f else 1f)
            }
            if (night) {
                // the dashboard's glow low in the front glass
                c.field(0x5A6E96, X(b.mirrorX - 9f), gTop, X(b.mirrorX + 1f), gBot, glass) { x, y ->
                    val dx = (U(x) - (b.mirrorX - 3.5f)) / 5f
                    val dy = (V(y) - (b.belt + .6f)) / 2.4f
                    (1f - sqrt(dx * dx + dy * dy)).coerceIn(0f, 1f) * .4f
                }
            }
            // the pillars between the panes: gloss black, with a sheen
            val p = b.pillars
            for (i in 0 until p.size / 3) {
                val wd = p[i * 3 + 2] / 2f
                val pillar =
                    Poly(
                        floatArrayOf(
                            X(p[i * 3] - wd),
                            X(p[i * 3] + wd),
                            X(p[i * 3 + 1] + wd),
                            X(p[i * 3 + 1] - wd),
                        ),
                        floatArrayOf(
                            Y(b.belt - 1f),
                            Y(b.belt - 1f),
                            Y(b.roofY + 1f),
                            Y(b.roofY + 1f),
                        ),
                    )
                c.fill(And(pillar, Grow(glass, .25f * k)), 0x0C0E11) { x, _ ->
                    val t = (x - X(p[i * 3 + 1] - wd)) / (2f * wd * k)
                    1f + (if (night) .3f else 1.6f) * max(0f, 1f - abs(t - .3f) / .18f)
                }
            }
            // the seals round the glass, and a bright trim along its foot
            c.fill(Stroke(glass, .2f * k), 0x0A0B0D)
            if (b.chrome) {
                val dl = b.dlo
                var x0 = 1e9f
                var x1 = -1e9f
                for (i in 0 until dl.size step 2) {
                    x0 = min(x0, dl[i])
                    x1 = max(x1, dl[i])
                }
                c.fill(seg(x0 + .6f, b.belt + .05f, x1 - 1.2f, b.belt - .02f, .13f), 0xD4D8DD, nk)
            }
            // the shut lines, each with the light catching the edge beside it
            val s = b.seams
            var i = 0
            while (i < s.size) {
                val n = s[i].toInt()
                for (j in 0 until n - 1) {
                    val a = i + 1 + j * 2
                    c.fill(And(seg(s[a], s[a + 1], s[a + 2], s[a + 3], .07f), shell), 0x050607, .7f)
                    c.fill(
                        And(seg(s[a] - .16f, s[a + 1], s[a + 2] - .16f, s[a + 3], .05f), shell),
                        0xFFFFFF,
                        .16f * nk,
                    )
                }
                i += 1 + n * 2
            }
            // the handles: a lit lip over a dark recess
            for (j in 0 until b.handles.size / 2) {
                val hx = b.handles[j * 2]
                val hy = b.handles[j * 2 + 1]
                c.fill(box(hx - 1.2f, hy - .35f, hx + 1.2f, hy + .1f, .2f), 0x000000, .5f)
                c.fill(box(hx - 1.1f, hy + .05f, hx + 1.1f, hy + .28f, .12f), 0xFFFFFF, .45f * nk)
            }
            // the fuel flap on the rear quarter
            val fx = b.rearAxle - .5f
            val fy = b.crease + .8f
            c.fill(
                And(Stroke(box(fx - 1.1f, fy - 1f, fx + 1.1f, fy + 1f, .4f), .06f * k), shell),
                0x000000,
                .45f,
            )
            // the mirror stands proud of the glass: the painted housing shows through
            c.cut(mirror)
            // the mirror's black foot, and its lit top
            c.fill(
                poly(
                    floatArrayOf(
                        b.mirrorX - .6f,
                        b.mirrorY,
                        b.mirrorX + 1.4f,
                        b.mirrorY + .2f,
                        b.mirrorX + 1.2f,
                        b.mirrorY + 1.1f,
                        b.mirrorX - .5f,
                        b.mirrorY + 1f,
                    ),
                    smooth = false,
                ),
                0x0C0D10,
            )
            c.fill(
                And(
                    And(Stroke(mirror, .12f * k), mirror),
                    box(b.mirrorX - 1f, b.mirrorY + 2.2f, b.mirrorX + 3f, b.mirrorY + 3.2f, 0f),
                ),
                0xFFFFFF,
                .35f * nk,
            )
            // the bumpers' black lower lips and the front intake's corner
            c.fill(And(box(L - 6f, b.sill - .5f, L + 1f, b.sill + 1.4f, 0f), shell), 0x141518)
            c.fill(And(box(L - 2.4f, b.sill + 1.4f, L + 1f, b.sill + 3.6f, .6f), shell), 0x0E0F12)
            c.fill(And(box(-1f, b.sill - .5f, 5f, b.sill + 1.2f, 0f), shell), 0x141518)
            // the side repeater on the front wing
            val rx = b.frontAxle - r - .6f
            c.fill(
                box(rx - .7f, b.crease - .9f, rx + .3f, b.crease - .45f, .2f),
                if (night) 0x6A3A10 else 0xD98A26,
                .9f,
            )
            // the aerial fin
            if (b.fin > 0f) {
                val ry = b.roofY - .15f
                c.fill(
                    poly(
                        floatArrayOf(
                            b.fin - 1.3f,
                            ry,
                            b.fin + 1.4f,
                            ry,
                            b.fin - .5f,
                            ry + .85f,
                            b.fin - .9f,
                            ry + .8f,
                        ),
                        smooth = false,
                    ),
                    0x111215,
                )
            }
            headLamp(c, night)
            tailLamp(c, night)
            return c
        }

        private fun headLamp(c: Layer, night: Boolean) {
            val lens = poly(b.head, smooth = false)
            val round = Grow(Erode(lens, .25f * k), .25f * k)
            var x0 = 1e9f
            var x1 = -1e9f
            for (i in 0 until b.head.size step 2) {
                x0 = min(x0, b.head[i])
                x1 = max(x1, b.head[i])
            }
            if (night) {
                c.fill(round, 0xFFF4D8)
                c.fill(Circle(X(x1 - 2.4f), Y(lampY), .9f * k), 0xFFFFFF)
            } else {
                // the clear cover over a dark housing, the projector's lens and its chrome
                c.fill(round, 0x2A2F37)
                c.fill(Circle(X(x1 - 2.4f), Y(lampY - .1f), .8f * k), 0xE6ECF2)
                c.fill(Stroke(Circle(X(x1 - 2.4f), Y(lampY - .1f), .8f * k), .14f * k), 0x8E959E)
                c.field(0xFFFFFF, X(x0), Y(lampY + 2f), X(x1 + 1f), Y(lampY - 2f), round) { _, y ->
                    .12f + .3f * Px.smooth(Y(lampY), Y(lampY + 1.2f), y)
                }
                c.fill(And(Stroke(round, .1f * k), shell), 0x9AA1AA, .8f)
            }
            // the running light, a fine bright line along its top
            c.fill(
                And(Stroke(round, .16f * k), box(x0, lampY + .2f, x1 + 1f, lampY + 3f, 0f)),
                if (night) 0xFFFFFF else 0xF2F6FA,
            )
        }

        private fun tailLamp(c: Layer, night: Boolean) {
            val lens = Grow(Erode(poly(b.tail, smooth = false), .2f * k), .2f * k)
            c.fill(lens, if (night) 0xFF3020 else 0x7C0F12) { _, y ->
                val t = ((y - Y(tailY + 1.5f)) / (3f * k)).coerceIn(0f, 1f)
                if (night) 1f else 1.25f - .45f * t
            }
            c.fill(
                And(Grow(seg(0f, tailY + .2f, 6f, tailY + .25f, .15f), 0f), lens),
                if (night) 0xFFB09A else 0xC92A26,
            )
            if (!night) c.fill(And(Stroke(lens, .08f * k), shell), 0x2A0506, .7f)
        }

        /** The lamps' bloom at night, added over the car. */
        private fun lights(): Layer {
            val c = Layer(w, h)
            val hx = X(L - 1.4f)
            val hy = Y(lampY)
            c.field(0xFFE9B8, hx - 9f * k, hy - 5f * k, hx + 9f * k, hy + 5f * k) { x, y ->
                val dx = (x - hx) / (7f * k)
                val dy = (y - hy) / (3.6f * k)
                val f = (1f - sqrt(dx * dx + dy * dy)).coerceIn(0f, 1f)
                val core = (1f - sqrt(dx * dx * 9f + dy * dy * 9f)).coerceIn(0f, 1f)
                f * f * .75f + core * .6f
            }
            // a faint horizontal streak through the lamp
            c.field(0xFFF2D8, hx - 7f * k, hy - 1f * k, hx + 7f * k, hy + 1f * k) { x, y ->
                val dx = abs(x - hx) / (6.5f * k)
                val dy = abs(y - hy) / (.35f * k)
                (1f - dx).coerceIn(0f, 1f) * (1f - dy).coerceIn(0f, 1f) * .35f
            }
            val tx = X(1f)
            val ty = Y(tailY)
            c.field(0xFF3A26, tx - 6f * k, ty - 4f * k, tx + 6f * k, ty + 4f * k) { x, y ->
                val dx = (x - tx) / (4.6f * k)
                val dy = (y - ty) / (3f * k)
                val f = (1f - sqrt(dx * dx + dy * dy)).coerceIn(0f, 1f)
                f * f * .8f
            }
            return c
        }

        /** Roof bars along the roof's flat run: rails on feet, the cross bars' ends. */
        private fun rack(): Layer {
            val rl = b.roof1 - b.roof0 + 2f
            val rw = ceil(rl * k).toInt() + 4
            val rh = ceil(2.6f * k).toInt() + 4
            val c = Layer(rw, rh)
            fun rx(u: Float) = 2f + (u + 1f) * k

            fun ry(v: Float) = rh - 2f - (v + .4f) * k
            // the rail
            c.fill(Box(rx(0f), ry(2f), rx(rl - 2f), ry(1.3f), .3f * k), 0x1A1C20) { _, y ->
                if (y < ry(1.75f)) 1.9f else 1f
            }
            // the feet and the bars' ends
            for (u in floatArrayOf(.6f, rl - 3.6f)) {
                c.fill(Box(rx(u), ry(1.4f), rx(u + 1.4f), ry(-.4f), .2f * k), 0x141518)
                c.fill(Box(rx(u + .1f), ry(2.3f), rx(u + 1.3f), ry(1.2f), .2f * k), 0x26292E) { _, y
                    ->
                    if (y < ry(2f)) 1.8f else 1f
                }
            }
            return c
        }
    }

    // ---- the wheels

    /**
     * Wheel design [rim] ([RIMS]) at [StreetLife.CAR_WHEEL]'s radius, centred: tyre and sidewall,
     * the brake disc behind, the spokes, the rim's lip, the nuts and the centre cap. Its shading is
     * round, so it can turn; [wheelShade] lays the arch's shadow over it.
     */
    fun wheel(rim: Int): Sprite {
        val k = PPU
        val R = StreetLife.CAR_WHEEL * k
        val n = ceil(R * 2f).toInt() + 4
        val c = Layer(n, n)
        val cx = n / 2f
        val cy = n / 2f
        fun dist(x: Float, y: Float) = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
        val Rr = R * .7f
        // the tyre, its sidewall's soft bulge
        c.fill(Circle(cx, cy, R), 0x121315)
        c.field(0x3B3D42, 0f, 0f, n.toFloat(), n.toFloat(), Circle(cx, cy, R)) { x, y ->
            val t = ((dist(x, y) - Rr) / (R - Rr)).coerceIn(0f, 1f)
            val s = sin(t * PI.toFloat())
            s * s * .55f
        }
        c.fill(Stroke(Circle(cx, cy, R - .25f * k), .08f * k), 0x000000, .6f)
        // behind the spokes: the dark of the wheel, the disc and its hat
        c.fill(Circle(cx, cy, Rr), 0x17181B)
        c.fill(Circle(cx, cy, Rr * .86f), 0x5B6067) { x, y ->
            val t = dist(x, y) / (Rr * .86f)
            .75f + .35f * t
        }
        c.fill(Circle(cx, cy, Rr * .42f), 0x2A2C31)
        val face: Int
        val lip: Int
        when (rim) {
            0 -> {
                face = 0xC6CBD1
                lip = 0xD9DDE2
                for (i in 0 until 5) {
                    val a = i / 5f * 2f * PI.toFloat()
                    for (sgn in floatArrayOf(-1f, 1f)) {
                        val b = a + sgn * .17f
                        c.fill(
                            Seg(
                                cx + cos(a) * Rr * .28f,
                                cy + sin(a) * Rr * .28f,
                                cx + cos(b) * Rr * .93f,
                                cy + sin(b) * Rr * .93f,
                                .2f * k,
                            ),
                            face,
                        ) { x, y ->
                            1.08f - .25f * dist(x, y) / Rr
                        }
                    }
                }
            }
            1 -> {
                face = 0x6C727A
                lip = 0xB9BEC5
                for (i in 0 until 10) {
                    val a = i / 10f * 2f * PI.toFloat()
                    c.fill(
                        Seg(
                            cx + cos(a) * Rr * .3f,
                            cy + sin(a) * Rr * .3f,
                            cx + cos(a + .1f) * Rr * .94f,
                            cy + sin(a + .1f) * Rr * .94f,
                            .15f * k,
                        ),
                        face,
                    ) { x, y ->
                        1.25f - .35f * dist(x, y) / Rr
                    }
                }
            }
            2 -> {
                // a plastic trim over a steel wheel, its vents
                face = 0xB4B9C0
                lip = 0x9EA3AA
                c.fill(Circle(cx, cy, Rr * .97f), face) { x, y -> 1.06f - .2f * dist(x, y) / Rr }
                for (i in 0 until 8) {
                    val a = i / 8f * 2f * PI.toFloat()
                    c.fill(
                        Seg(
                            cx + cos(a) * Rr * .5f,
                            cy + sin(a) * Rr * .5f,
                            cx + cos(a) * Rr * .78f,
                            cy + sin(a) * Rr * .78f,
                            .22f * k,
                        ),
                        0x26282C,
                    )
                }
                c.fill(Stroke(Circle(cx, cy, Rr * .9f), .05f * k), 0x000000, .4f)
            }
            else -> {
                face = 0x25282D
                lip = 0x2E3136
                for (i in 0 until 5) {
                    val a = i / 5f * 2f * PI.toFloat()
                    val sd =
                        Seg(
                            cx + cos(a) * Rr * .3f,
                            cy + sin(a) * Rr * .3f,
                            cx + cos(a) * Rr * .93f,
                            cy + sin(a) * Rr * .93f,
                            .5f * k,
                        )
                    c.fill(sd, face)
                    // the machined face down each spoke
                    c.fill(
                        Seg(
                            cx + cos(a) * Rr * .34f,
                            cy + sin(a) * Rr * .34f,
                            cx + cos(a) * Rr * .9f,
                            cy + sin(a) * Rr * .9f,
                            .17f * k,
                        ),
                        0x9AA1AA,
                    )
                }
            }
        }
        // the rim's lip
        c.fill(Minus(Circle(cx, cy, Rr), Circle(cx, cy, Rr - .38f * k)), lip) { x, y ->
            .9f + .2f * (dist(x, y) - Rr + .38f * k) / (.38f * k)
        }
        // the hub, its nuts and the centre cap
        if (rim != 2) {
            c.fill(Circle(cx, cy, Rr * .27f), face) { _, _ -> 1.05f }
            for (i in 0 until 5) {
                val a = i / 5f * 2f * PI.toFloat() + .63f
                c.fill(Circle(cx + cos(a) * Rr * .17f, cy + sin(a) * Rr * .17f, .17f * k), 0x8E949B)
            }
        }
        c.fill(Circle(cx, cy, Rr * .11f), 0x2B2E33)
        c.fill(Circle(cx, cy, Rr * .045f), 0xC9CED4)
        return Sprite(n, n, k, cx, cy, c.argb())
    }

    /**
     * The light over a wheel, laid on it unturned: the arch's shadow over its top, the road's
     * bounce under it.
     */
    fun wheelShade(): Sprite {
        val k = PPU
        val R = StreetLife.CAR_WHEEL * k
        val n = ceil(R * 2f).toInt() + 4
        val c = Layer(n, n)
        val cx = n / 2f
        val cy = n / 2f
        c.field(0x000000, 0f, 0f, n.toFloat(), n.toFloat(), Circle(cx, cy, R)) { _, y ->
            val t = (y - cy) / R
            .55f * Px.smooth(.1f, -1f, t) + .12f * Px.smooth(.5f, 1f, t)
        }
        return Sprite(n, n, k, cx, cy, c.argb())
    }

    /**
     * A taxi's roof sign, its middle foot at ([Sprite.ox], [Sprite.oy]): by day pale plastic with
     * its lettering, by night lit from inside.
     */
    fun sign(night: Boolean): Sprite {
        val k = PPU
        val sw = 8f
        val sh = 2.6f
        val pad = 8
        val w = ceil(sw * k).toInt() + 2 * pad
        val h = ceil(sh * k).toInt() + 2 * pad
        val c = Layer(w, h)
        val x0 = pad.toFloat()
        val yb = h - pad.toFloat()
        fun X(u: Float) = x0 + u * k

        fun Y(v: Float) = yb - v * k
        if (night) {
            c.field(0xFFE6A0, 0f, 0f, w.toFloat(), h.toFloat()) { x, y ->
                val dx = (x - X(sw / 2f)) / ((sw / 2f + 2f) * k)
                val dy = (y - Y(1.4f)) / (3f * k)
                val f = (1f - sqrt(dx * dx + dy * dy)).coerceIn(0f, 1f)
                f * f * .7f
            }
        }
        c.fill(Box(X(.3f), Y(.5f), X(sw - .3f), Y(0f), .1f * k), 0x101114)
        c.fill(Box(X(0f), Y(sh), X(sw), Y(.45f), .7f * k), if (night) 0xFFF1C4 else 0xF2EEDF) { _, y
            ->
            val t = ((y - Y(sh)) / (sh * k)).coerceIn(0f, 1f)
            if (night) 1f else 1.05f - .2f * t
        }
        // T A X I
        val ink = if (night) 0x5A3A10 else 0x1C1C1E
        val l = .13f * k
        val y0 = 1f
        val y1 = 2.05f
        fun s(a: Float, b: Float, c2: Float, d: Float) =
            c.fill(Seg(X(a), Y(b), X(c2), Y(d), l), ink)
        s(1.5f, y1, 2.5f, y1)
        s(2f, y1, 2f, y0)
        s(3f, y0, 3.45f, y1)
        s(3.45f, y1, 3.9f, y0)
        s(3.2f, 1.4f, 3.7f, 1.4f)
        s(4.4f, y0, 5.2f, y1)
        s(4.4f, y1, 5.2f, y0)
        s(5.9f, y0, 5.9f, y1)
        return Sprite(w, h, k, X(sw / 2f), yb, c.argb())
    }

    /** Car [body]'s length and its head lamp's height, in street units. */
    fun length(body: Int): Float = BODIES[body].length

    fun lampUp(body: Int): Float {
        val h = BODIES[body].head
        var s = 0f
        for (i in 1 until h.size step 2) s += h[i]
        return s / (h.size / 2)
    }

    /**
     * Car [body] in [rgb] (by night if [night]), its wheels on, made small for the far road: [ppu]
     * pixels a unit, its middle at [cx] and the road at [roadY] of a [w] by [h] picture (each pixel
     * the average of the full-size picture under it).
     */
    fun small(
        body: Int,
        rgb: Int,
        night: Boolean,
        ppu: Float,
        w: Int,
        h: Int,
        cx: Float,
        roadY: Float,
    ): IntArray {
        val c = car(body)
        val big = Layer(c.w, c.h)
        // the paint, then what is not paint
        fun lay(px: IntArray, tint: Int) {
            val tr = (tint shr 16 and 255) / 255f
            val tg = (tint shr 8 and 255) / 255f
            val tb = (tint and 255) / 255f
            for (i in px.indices) {
                val p = px[i]
                val a = (p ushr 24) / 255f
                if (a <= 0f) continue
                big.over(
                    i,
                    (p shr 16 and 255) / 255f * tr,
                    (p shr 8 and 255) / 255f * tg,
                    (p and 255) / 255f * tb,
                    a,
                )
            }
        }
        lay(c.paint, rgb)
        lay(if (night) c.night else c.day, 0xFFFFFF)
        // the wheels, unturned, shaded by their arches
        val wheel = wheel(c.rim)
        val shade = wheelShade()
        val ws = c.wheelR / StreetLife.CAR_WHEEL
        for (wx in floatArrayOf(c.wheelX0, c.wheelX1)) {
            for (sp in arrayOf(wheel, shade)) {
                val r = sp.w / 2f * ws
                for (y in (c.wheelY - r).toInt()..(c.wheelY + r).toInt() + 1) {
                    for (x in (wx - r).toInt()..(wx + r).toInt() + 1) {
                        if (x < 0 || y < 0 || x >= c.w || y >= c.h) continue
                        val sx = ((x + .5f - wx) / ws + sp.ox).toInt()
                        val sy = ((y + .5f - c.wheelY) / ws + sp.oy).toInt()
                        if (sx < 0 || sy < 0 || sx >= sp.w || sy >= sp.h) continue
                        val p = sp.pixels[sy * sp.w + sx]
                        val k = if (night) .3f else 1f
                        big.over(
                            y * c.w + x,
                            (p shr 16 and 255) / 255f * k,
                            (p shr 8 and 255) / 255f * k,
                            (p and 255) / 255f * k,
                            (p ushr 24) / 255f,
                        )
                    }
                }
            }
        }
        // down to the small picture: a box of the big one under each pixel, premultiplied
        val f = ppu / PPU
        val bx = c.w / 2f
        val out = IntArray(w * h)
        val n = 4
        for (ty in 0 until h) for (tx in 0 until w) {
            var r = 0f
            var g = 0f
            var b = 0f
            var a = 0f
            for (j in 0 until n) for (i in 0 until n) {
                val sx = ((tx + (i + .5f) / n - cx) / f + bx).toInt()
                val sy = ((ty + (j + .5f) / n - roadY) / f + c.groundY).toInt()
                if (sx < 0 || sy < 0 || sx >= c.w || sy >= c.h) continue
                val k = (sy * c.w + sx) * 4
                val pa = big.px[k + 3]
                r += big.px[k] * pa
                g += big.px[k + 1] * pa
                b += big.px[k + 2] * pa
                a += pa
            }
            if (a <= 0f) continue
            val m = (n * n).toFloat()
            out[ty * w + tx] = Px.argbF(a / m, r / a, g / a, b / a)
        }
        return out
    }

    /** What every car's pictures take, in bytes. */
    fun bytes(): Int {
        var total = 0
        for (i in BODIES.indices) {
            val s = car(i)
            total += s.w * s.h * 4 * 4 + s.rackW * s.rackH * 4
        }
        val wh = wheel(0)
        total += wh.w * wh.h * 4 * (RIMS + 1)
        val sg = sign(false)
        total += sg.w * sg.h * 4 * 2
        return total
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) =
            (((a shr s and 255) + ((b shr s and 255) - (a shr s and 255)) * t).toInt()).coerceIn(
                0,
                255,
            )
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    // ---- outlines: densified, then a centripetal Catmull-Rom through the points

    private fun spline(pts: FloatArray): Pair<FloatArray, FloatArray> {
        val n0 = pts.size / 2
        val px = ArrayList<Float>()
        val py = ArrayList<Float>()
        for (i in 0 until n0) {
            val x0 = pts[i * 2]
            val y0 = pts[i * 2 + 1]
            val x1 = pts[((i + 1) % n0) * 2]
            val y1 = pts[((i + 1) % n0) * 2 + 1]
            val len = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
            val m = max(1, ceil(len / 6f).toInt())
            for (j in 0 until m) {
                px.add(x0 + (x1 - x0) * j / m)
                py.add(y0 + (y1 - y0) * j / m)
            }
        }
        val n = px.size
        val steps = 6
        val ox = FloatArray(n * steps)
        val oy = FloatArray(n * steps)
        for (i in 0 until n) {
            val i0 = (i - 1 + n) % n
            val i2 = (i + 1) % n
            val i3 = (i + 2) % n
            val x0 = px[i0]
            val y0 = py[i0]
            val x1 = px[i]
            val y1 = py[i]
            val x2 = px[i2]
            val y2 = py[i2]
            val x3 = px[i3]
            val y3 = py[i3]
            fun knot(ax: Float, ay: Float, bx: Float, by: Float) =
                max(1e-3f, sqrt(sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))))
            val t0 = 0f
            val t1 = t0 + knot(x0, y0, x1, y1)
            val t2 = t1 + knot(x1, y1, x2, y2)
            val t3 = t2 + knot(x2, y2, x3, y3)
            for (s in 0 until steps) {
                val t = t1 + (t2 - t1) * s / steps
                val a1x = (t1 - t) / (t1 - t0) * x0 + (t - t0) / (t1 - t0) * x1
                val a1y = (t1 - t) / (t1 - t0) * y0 + (t - t0) / (t1 - t0) * y1
                val a2x = (t2 - t) / (t2 - t1) * x1 + (t - t1) / (t2 - t1) * x2
                val a2y = (t2 - t) / (t2 - t1) * y1 + (t - t1) / (t2 - t1) * y2
                val a3x = (t3 - t) / (t3 - t2) * x2 + (t - t2) / (t3 - t2) * x3
                val a3y = (t3 - t) / (t3 - t2) * y2 + (t - t2) / (t3 - t2) * y3
                val b1x = (t2 - t) / (t2 - t0) * a1x + (t - t0) / (t2 - t0) * a2x
                val b1y = (t2 - t) / (t2 - t0) * a1y + (t - t0) / (t2 - t0) * a2y
                val b2x = (t3 - t) / (t3 - t1) * a2x + (t - t1) / (t3 - t1) * a3x
                val b2y = (t3 - t) / (t3 - t1) * a2y + (t - t1) / (t3 - t1) * a3y
                ox[i * steps + s] = (t2 - t) / (t2 - t1) * b1x + (t - t1) / (t2 - t1) * b2x
                oy[i * steps + s] = (t2 - t) / (t2 - t1) * b1y + (t - t1) / (t2 - t1) * b2y
            }
        }
        return Pair(ox, oy)
    }

    // ---- signed distances (pixels; negative inside), each with the box it can paint in

    private abstract class Sd(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
        abstract fun d(x: Float, y: Float): Float
    }

    private class Poly(val xs: FloatArray, val ys: FloatArray) :
        Sd(xs.minOrNull()!!, ys.minOrNull()!!, xs.maxOrNull()!!, ys.maxOrNull()!!) {
        override fun d(x: Float, y: Float): Float {
            // outside the box by a margin: the box's distance is near enough
            val bx = max(x0 - x, x - x1)
            val by = max(y0 - y, y - y1)
            if (bx > 2f || by > 2f) return max(bx, by)
            var dd = Float.MAX_VALUE
            var s = 1f
            val n = xs.size
            var j = n - 1
            for (i in 0 until n) {
                val ex = xs[j] - xs[i]
                val ey = ys[j] - ys[i]
                val wx = x - xs[i]
                val wy = y - ys[i]
                val t = ((wx * ex + wy * ey) / max(1e-9f, ex * ex + ey * ey)).coerceIn(0f, 1f)
                val qx = wx - ex * t
                val qy = wy - ey * t
                dd = min(dd, qx * qx + qy * qy)
                val c1 = y >= ys[i]
                val c2 = y < ys[j]
                val c3 = ex * wy > ey * wx
                if ((c1 && c2 && c3) || (!c1 && !c2 && !c3)) s = -s
                j = i
            }
            return s * sqrt(dd)
        }
    }

    /** [sd] worked out once for every pixel of a [w] by [h] picture. */
    private class Grid(val w: Int, val h: Int, sd: Sd) : Sd(sd.x0, sd.y0, sd.x1, sd.y1) {
        val g = FloatArray(w * h) { sd.d(it % w + .5f, it / w + .5f) }

        override fun d(x: Float, y: Float): Float {
            val xi = x.toInt()
            val yi = y.toInt()
            if (xi < 0 || yi < 0 || xi >= w || yi >= h) return 1e3f
            return g[yi * w + xi]
        }
    }

    private class Circle(val cx: Float, val cy: Float, val r: Float) :
        Sd(cx - r, cy - r, cx + r, cy + r) {
        override fun d(x: Float, y: Float): Float =
            sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) - r
    }

    private class Box(ax: Float, ay: Float, bx: Float, by: Float, val r: Float) :
        Sd(min(ax, bx), min(ay, by), max(ax, bx), max(ay, by)) {
        override fun d(x: Float, y: Float): Float {
            val cx = (x0 + x1) / 2f
            val cy = (y0 + y1) / 2f
            val hw = (x1 - x0) / 2f
            val hh = (y1 - y0) / 2f
            val rr = min(r, min(hw, hh))
            val qx = abs(x - cx) - (hw - rr)
            val qy = abs(y - cy) - (hh - rr)
            val ox = max(qx, 0f)
            val oy = max(qy, 0f)
            return sqrt(ox * ox + oy * oy) + min(max(qx, qy), 0f) - rr
        }
    }

    private class Seg(val ax: Float, val ay: Float, val bx: Float, val by: Float, val r: Float) :
        Sd(min(ax, bx) - r, min(ay, by) - r, max(ax, bx) + r, max(ay, by) + r) {
        override fun d(x: Float, y: Float): Float {
            val dx = bx - ax
            val dy = by - ay
            val l2 = max(1e-6f, dx * dx + dy * dy)
            val t = (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0f, 1f)
            val qx = x - (ax + dx * t)
            val qy = y - (ay + dy * t)
            return sqrt(qx * qx + qy * qy) - r
        }
    }

    private class Minus(val a: Sd, val b: Sd) : Sd(a.x0, a.y0, a.x1, a.y1) {
        override fun d(x: Float, y: Float): Float = max(a.d(x, y), -b.d(x, y))
    }

    private class And(val a: Sd, val b: Sd) :
        Sd(max(a.x0, b.x0), max(a.y0, b.y0), min(a.x1, b.x1), min(a.y1, b.y1)) {
        override fun d(x: Float, y: Float): Float = max(a.d(x, y), b.d(x, y))
    }

    private class Or(val a: Sd, val b: Sd) :
        Sd(min(a.x0, b.x0), min(a.y0, b.y0), max(a.x1, b.x1), max(a.y1, b.y1)) {
        override fun d(x: Float, y: Float): Float = min(a.d(x, y), b.d(x, y))
    }

    private class Grow(val a: Sd, val r: Float) : Sd(a.x0 - r, a.y0 - r, a.x1 + r, a.y1 + r) {
        override fun d(x: Float, y: Float): Float = a.d(x, y) - r
    }

    private class Erode(val a: Sd, val r: Float) : Sd(a.x0, a.y0, a.x1, a.y1) {
        override fun d(x: Float, y: Float): Float = a.d(x, y) + r
    }

    /** A line [r] either side of [a]'s edge. */
    private class Stroke(val a: Sd, val r: Float) : Sd(a.x0 - r, a.y0 - r, a.x1 + r, a.y1 + r) {
        override fun d(x: Float, y: Float): Float = abs(a.d(x, y)) - r
    }

    // ---- a small painter: float RGBA composited over

    private class Layer(val w: Int, val h: Int) {
        val px = FloatArray(w * h * 4)

        fun over(i: Int, r: Float, g: Float, b: Float, a: Float) {
            if (a <= 0f) return
            val k = i * 4
            val da = px[k + 3]
            val oa = a + da * (1f - a)
            if (oa <= 0f) return
            px[k] = (r * a + px[k] * da * (1f - a)) / oa
            px[k + 1] = (g * a + px[k + 1] * da * (1f - a)) / oa
            px[k + 2] = (b * a + px[k + 2] * da * (1f - a)) / oa
            px[k + 3] = oa
        }

        /** [rgb] inside [sd], [alpha] strong, its colour times [shade]. */
        fun fill(sd: Sd, rgb: Int, alpha: Float = 1f, shade: ((Float, Float) -> Float)? = null) {
            val r = (rgb shr 16 and 255) / 255f
            val g = (rgb shr 8 and 255) / 255f
            val b = (rgb and 255) / 255f
            val ya = max(0, floor(sd.y0).toInt() - 2)
            val yb = min(h - 1, ceil(sd.y1).toInt() + 2)
            val xa = max(0, floor(sd.x0).toInt() - 2)
            val xb = min(w - 1, ceil(sd.x1).toInt() + 2)
            for (y in ya..yb) for (x in xa..xb) {
                val cx = x + .5f
                val cy = y + .5f
                val cov = Px.clamp01(.5f - sd.d(cx, cy))
                if (cov <= 0f) continue
                val s = shade?.invoke(cx, cy) ?: 1f
                over(
                    y * w + x,
                    Px.clamp01(r * s),
                    Px.clamp01(g * s),
                    Px.clamp01(b * s),
                    cov * alpha,
                )
            }
        }

        /** [rgb] at the strength [f] gives, in the box given, inside [mask] if there is one. */
        fun field(
            rgb: Int,
            x0: Float,
            y0: Float,
            x1: Float,
            y1: Float,
            mask: Sd? = null,
            f: (Float, Float) -> Float,
        ) {
            val r = (rgb shr 16 and 255) / 255f
            val g = (rgb shr 8 and 255) / 255f
            val b = (rgb and 255) / 255f
            val ya = max(0, floor(min(y0, y1)).toInt())
            val yb = min(h - 1, ceil(max(y0, y1)).toInt())
            val xa = max(0, floor(min(x0, x1)).toInt())
            val xb = min(w - 1, ceil(max(x0, x1)).toInt())
            for (y in ya..yb) for (x in xa..xb) {
                val cx = x + .5f
                val cy = y + .5f
                var a = f(cx, cy)
                if (a <= 0f) continue
                if (mask != null) a *= Px.clamp01(.5f - mask.d(cx, cy))
                if (a > 0f) over(y * w + x, r, g, b, Px.clamp01(a))
            }
        }

        /** Takes away what is inside [sd]. */
        fun cut(sd: Sd) {
            val ya = max(0, floor(sd.y0).toInt() - 2)
            val yb = min(h - 1, ceil(sd.y1).toInt() + 2)
            val xa = max(0, floor(sd.x0).toInt() - 2)
            val xb = min(w - 1, ceil(sd.x1).toInt() + 2)
            for (y in ya..yb) for (x in xa..xb) {
                val cov = Px.clamp01(.5f - sd.d(x + .5f, y + .5f))
                if (cov > 0f) px[(y * w + x) * 4 + 3] *= 1f - cov
            }
        }

        fun argb(): IntArray =
            IntArray(w * h) {
                val k = it * 4
                val a = px[k + 3]
                if (a <= 0f) 0 else Px.argbF(a, px[k], px[k + 1], px[k + 2])
            }
    }
}
