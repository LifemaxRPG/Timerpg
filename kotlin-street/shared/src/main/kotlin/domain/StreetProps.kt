package com.lifemax.rpg.feature.habits.domain

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The street's other walkers: a small dog trotting on a lead, and a bicycle with its rider pedalling. The same
 * kind of shapes as [StreetRig], baked by the same rasteriser. Units, x forward, y down, the ground at y = 0.
 */

object DogRig {
    const val BODY = .62f
    const val FAR = .5f

    /** Frame [frame] of the dog: [StreetGait.DOG_FRAMES] of the trot, then the sniff. */
    fun frame(frame: Int): List<Shape> =
        if (frame < StreetGait.DOG_FRAMES) trot(frame / StreetGait.DOG_FRAMES.toFloat())
        else sniff()

    const val SNIFF_FRAME = StreetGait.DOG_FRAMES
    const val TOTAL_FRAMES = StreetGait.DOG_FRAMES + 1

    private fun rad(d: Float) = d * (PI.toFloat() / 180f)

    /** The dog's coat: a warm tan, the far legs and the ear darker; its collar red. */
    private const val FUR = 0x9C6B42
    private const val COLLAR_TONE = .3f

    private fun colour(tone: Float): Int =
        if (tone == COLLAR_TONE) 0xB0302A else StreetRig.dim(FUR, tone / BODY)

    private fun body(
        out: ArrayList<Shape>,
        legs: FloatArray,
        bob: Float,
        headDown: Float,
        tail: Float,
        sniffing: Boolean,
    ) {
        val s = StreetLife.DOG_SIZE
        val y0 = -5.9f * s - bob
        fun add(pts: FloatArray, tone: Float) {
            out += Shape(Shape.BODY, pts, tone, colour(tone))
        }
        // the far legs (hind at the back, fore at the front)
        leg(out, -3.0f * s, y0 + .5f, legs[3], 2.5f * s, 2.3f * s, BODY * FAR, true)
        leg(out, 2.7f * s, y0 + .7f, legs[1], 2.4f * s, 2.3f * s, BODY * FAR, false)
        // the body: a capsule deeper at the chest, with a tucked belly
        add(
            FigBuilder.capsule(-3.2f * s, y0 - .1f, 1.35f * s, 2.9f * s, y0 - .3f, 1.6f * s, 8),
            BODY,
        )
        // the tail, up and wagging
        val ta = rad(-55f + tail)
        add(
            FigBuilder.capsule(
                -4.3f * s,
                y0 - .8f,
                .4f * s,
                -4.3f * s + cos(ta) * -2.4f * s,
                y0 - .8f + sin(ta) * 2.4f * s,
                .22f * s,
                5,
            ),
            BODY,
        )
        // the neck and the head, the muzzle, an ear
        val hd = rad(headDown)
        val nx = 4.2f * s + cos(hd) * .5f
        val ny = y0 - 2.0f * s + sin(hd) * 2.2f * s
        add(FigBuilder.capsule(3.0f * s, y0 - .9f, .95f * s, nx, ny, .95f * s, 6), BODY)
        add(FigBuilder.ellipse(nx + .3f * s, ny, 1.15f * s, 1.05f * s, headDown, 10), BODY)
        add(
            FigBuilder.capsule(
                nx + .8f * s,
                ny + .15f * s,
                .7f * s,
                nx + cos(hd) * 2.1f * s,
                ny + .25f * s + sin(hd) * 2.1f * s,
                .42f * s,
                5,
            ),
            BODY,
        )
        add(
            FigBuilder.ellipse(nx - .2f * s, ny - .8f * s, .45f * s, .85f * s, headDown - 35f, 7),
            BODY * .8f,
        )
        // the collar, so the lead has somewhere to go
        add(FigBuilder.ellipse(3.3f * s, y0 - 1.3f * s, .4f * s, 1.0f * s, 20f, 6), COLLAR_TONE)
        // the near legs
        leg(out, -2.6f * s, y0 + .5f, legs[2], 2.5f * s, 2.3f * s, BODY, true)
        leg(out, 3.1f * s, y0 + .7f, legs[0], 2.4f * s, 2.3f * s, BODY, false)
    }

    private fun leg(
        out: ArrayList<Shape>,
        x: Float,
        y: Float,
        a: Float,
        upper: Float,
        lower: Float,
        tone: Float,
        hind: Boolean,
    ) {
        // a dog's hind leg bends back at the hock, the fore leg forward at the wrist
        val bend = if (hind) -28f else 18f
        val kx = x + sin(rad(a)) * upper
        val ky = y + cos(rad(a)) * upper
        val fx = kx + sin(rad(a + bend)) * lower
        val fy = ky + cos(rad(a + bend)) * lower
        val rgb = colour(tone)
        out += Shape(Shape.BODY, FigBuilder.capsule(x, y, .75f, kx, ky, .45f, 6), tone, rgb)
        out += Shape(Shape.BODY, FigBuilder.capsule(kx, ky, .45f, fx, fy, .32f, 6), tone, rgb)
        out += Shape(Shape.BODY, FigBuilder.ellipse(fx + .3f, fy, .6f, .32f, 0f, 6), tone, rgb)
    }

    fun trot(f: Float): List<Shape> {
        val out = ArrayList<Shape>()
        val legs = FloatArray(4)
        StreetGait.dogLegs(f, legs)
        body(
            out,
            legs,
            StreetGait.dogBob(f),
            8f + 4f * sin(4f * PI.toFloat() * f),
            StreetGait.dogTail(f),
            false,
        )
        ground(out)
        return out
    }

    fun sniff(): List<Shape> {
        val out = ArrayList<Shape>()
        body(out, floatArrayOf(10f, -8f, -6f, 8f), 0f, 58f, 10f, true)
        ground(out)
        return out
    }

    private fun ground(out: ArrayList<Shape>) {
        var low = -1e9f
        for (s in out) {
            var i = 1
            while (i < s.pts.size) {
                low = kotlin.math.max(low, s.pts[i])
                i += 2
            }
        }
        for (s in out) {
            var i = 1
            while (i < s.pts.size) {
                s.pts[i] -= low
                i += 2
            }
        }
    }
}

object CycleRig {
    /** The bicycle's geometry, units from the ground under the middle of the wheelbase. */
    const val WHEELBASE = 19f
    const val R = StreetGait.WHEEL_R

    /** The bottom bracket (a little below the axles, as on a real frame) and the cranks. */
    const val BB_X = .4f
    const val BB_Y = -3.9f
    const val CRANK = 2.6f

    /** The chainring's and the rear sprocket's radii. */
    const val RING = 1.75f
    const val COG = .8f

    /** The seat tube's angle from level, and the rider's hip along it from the bottom bracket. */
    private const val SEAT_ANGLE = 72f
    private const val HIP_REACH = 9.6f

    /** The rider's hip over the saddle. */
    val HIP_X = BB_X - cos(rad(SEAT_ANGLE)) * HIP_REACH
    val HIP_Y = BB_Y - sin(rad(SEAT_ANGLE)) * HIP_REACH

    /** Where the seat tube meets the top tube and the stays. */
    private val SEAT_X = BB_X - cos(rad(SEAT_ANGLE)) * 6f
    private val SEAT_Y = BB_Y - sin(rad(SEAT_ANGLE)) * 6f

    /** The head tube, top and bottom; the fork's bend; the bar's clamp and the grips. */
    const val HEAD_X = 6.5f
    const val HEAD_Y = -11.4f
    private const val CROWN_X = 7.05f
    private const val CROWN_Y = -9.9f
    private const val BEND_X = 8.85f
    private const val BEND_Y = -6.1f
    private const val CLAMP_X = 7.15f
    private const val CLAMP_Y = -12.75f
    const val GRIP_X = 6.15f
    const val GRIP_Y = -13.3f

    /** The front lamp (on the fork's crown) and the rear one (under the saddle), for the night. */
    const val LAMP_X = 7.85f
    const val LAMP_Y = -10.2f
    const val TAIL_X = -3.35f
    const val TAIL_Y = -10.25f

    /** The bicycle's colours: the frame, the mudguards, the saddle and grips, the steel. */
    const val PAINT = 0x2D6C72
    private const val GUARD = 0x2A2D33
    private const val SADDLE = 0x5B3A26
    private const val STEEL = 0xB9BDC4
    private const val DARK_STEEL = 0x3B3F47
    private const val CHAIN = 0x34373E

    const val FAR = .55f

    private fun rad(d: Float) = d * (PI.toFloat() / 180f)

    /** The near pedal's position at crank angle [a] (radians, 0 at the front, turning forward). */
    fun pedalX(a: Float) = BB_X + cos(a) * CRANK

    fun pedalY(a: Float) = BB_Y + sin(a) * CRANK

    private fun add(out: ArrayList<Shape>, pts: FloatArray, rgb: Int, mat: Int, alpha: Float = 1f) {
        out += Shape(Shape.BODY, pts, 1f, rgb, mat, alpha)
    }

    private fun tube(
        out: ArrayList<Shape>,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        r: Float,
        rgb: Int = PAINT,
        mat: Int = Mat.METAL,
        r1: Float = r,
    ) = add(out, FigBuilder.capsule(x0, y0, r, x1, y1, r1, 4), rgb, mat)

    /** An arc of a ring about ([cx], [cy]): radii [r0] to [r1], from [a0] to [a1] degrees. */
    private fun arc(cx: Float, cy: Float, r0: Float, r1: Float, a0: Float, a1: Float): FloatArray {
        val n = 16
        val out = FloatArray((n + 1) * 4)
        for (i in 0..n) {
            val a = rad(a0 + (a1 - a0) * i / n)
            out[i * 2] = cx + cos(a) * r1
            out[i * 2 + 1] = cy + sin(a) * r1
            val k = (2 * n + 1 - i) * 2
            out[k] = cx + cos(a) * r0
            out[k + 1] = cy + sin(a) * r0
        }
        return out
    }

    /**
     * The bicycle (without its wheels, which turn on their own) and its rider at frame [frame] of
     * [StreetGait.CRANK_FRAMES] of a turn of the cranks: the near pedal at the front at frame 0.
     * The rider pedals as a person does: the hips rock a little with each stroke, the knees rise
     * and fall over the pedals, the ankles drop the heel over the top and point the toe through the
     * bottom, and the shoulders and head ride the stroke's small bob.
     */
    fun frame(look: Int, frame: Int): List<Shape> {
        val a = frame / StreetGait.CRANK_FRAMES.toFloat() * 2f * PI.toFloat()
        val out = ArrayList<Shape>()
        val lk = StreetLooks.ALL[look]
        val w = lk.wear
        val hgt = lk.height
        val b = lk.build
        val rx = -WHEELBASE / 2f
        val fx = WHEELBASE / 2f
        val ay = -R
        val fa = a + PI.toFloat()
        // the body over the stroke: twice a turn the body settles a little on the saddle, the
        // pelvis rocks towards the pushing leg, the back leans and lifts a touch with it
        val bob = .1f * cos(2f * a)
        val rock = sin(a)
        val hipX = HIP_X
        val hipY = HIP_Y + bob
        val lean = 40f + 1.2f * sin(2f * a + .6f)
        val torsoL = 7.4f * hgt

        // ---- what is beyond the frame: the far arm, the far leg on its pedal and crank
        val ux = sin(rad(lean))
        val uy = -cos(rad(lean))
        val nx = cos(rad(lean))
        val ny = sin(rad(lean))
        val sx = hipX + ux * torsoL
        val sy = hipY + uy * torsoL
        arm(out, lk, sx - nx * .5f * b, sy - ny * .5f * b, GRIP_X + .2f, GRIP_Y + .1f, FAR)
        leg(out, lk, hipX - .2f, hipY - .14f * rock, fa, FAR)
        pedal(out, fa, FAR)
        tube(
            out,
            BB_X,
            BB_Y,
            pedalX(fa),
            pedalY(fa),
            .34f,
            StreetRig.dim(STEEL, .6f),
            Mat.METAL,
            .26f,
        )

        // ---- the bicycle: mudguards, the rear sprocket, the stays, the main triangle, the fork
        val guard = R + .55f
        add(out, arc(rx, ay, guard - .3f, guard + .05f, 195f, 345f), GUARD, Mat.METAL)
        add(out, arc(fx, ay, guard - .3f, guard + .05f, 200f, 335f), GUARD, Mat.METAL)
        add(out, FigBuilder.ellipse(rx, ay, COG, COG, 0f, 12), DARK_STEEL, Mat.METAL)
        tube(out, BB_X, BB_Y, rx, ay, .3f, r1 = .24f)
        tube(out, SEAT_X, SEAT_Y, rx, ay, .26f, r1 = .22f)
        tube(out, BB_X, BB_Y, CROWN_X, CROWN_Y, .48f, r1 = .42f)
        tube(
            out,
            BB_X,
            BB_Y,
            SEAT_X + (SEAT_X - BB_X) * .12f,
            SEAT_Y + (SEAT_Y - BB_Y) * .12f,
            .42f,
        )
        tube(out, SEAT_X, SEAT_Y + .1f, HEAD_X, HEAD_Y + .35f, .38f)
        tube(out, CROWN_X, CROWN_Y, BEND_X, BEND_Y, .32f, r1 = .26f)
        tube(out, BEND_X, BEND_Y, fx, ay, .26f, r1 = .22f)
        tube(out, HEAD_X, HEAD_Y, CROWN_X, CROWN_Y, .55f)
        // the derailleur hanging under the sprocket, and the chain's two runs to the chainring
        add(
            out,
            FigBuilder.capsule(rx + .3f, ay + .5f, .32f, rx + .1f, ay + 1.8f, .28f, 4),
            DARK_STEEL,
            Mat.METAL,
        )
        tube(out, BB_X, BB_Y - RING, rx, ay - COG, .17f, CHAIN, Mat.METAL)
        tube(out, BB_X, BB_Y + RING, rx + .1f, ay + 1.9f, .17f, CHAIN, Mat.METAL)
        // the chain's links running: light plates along the top run, moving with the cranks
        val run = sqrt((rx - BB_X) * (rx - BB_X) + (ay - COG - BB_Y + RING).let { it * it })
        val step = .95f
        val shift = (a * RING) % step
        var d = shift
        while (d < run - .3f) {
            val t = d / run
            add(
                out,
                FigBuilder.ellipse(
                    BB_X + (rx - BB_X) * t,
                    BB_Y - RING + (ay - COG - BB_Y + RING) * t,
                    .2f,
                    .11f,
                    0f,
                    5,
                ),
                0x8A8F98,
                Mat.FLAT,
                .55f,
            )
            d += step
        }
        // the seat post and the saddle, the stem and the bar, its brake lever and the near grip
        tube(out, SEAT_X, SEAT_Y, hipX - .15f, HIP_Y + 2.45f, .26f, STEEL)
        add(
            out,
            FigBuilder.chaikin(
                floatArrayOf(
                    HIP_X - 1.75f,
                    HIP_Y + 1.5f,
                    HIP_X + .2f,
                    HIP_Y + 1.6f,
                    HIP_X + 1.65f,
                    HIP_Y + 1.85f,
                    HIP_X + 1.5f,
                    HIP_Y + 2.15f,
                    HIP_X - .3f,
                    HIP_Y + 2.45f,
                    HIP_X - 1.7f,
                    HIP_Y + 2.3f,
                ),
                2,
            ),
            SADDLE,
            Mat.LEATHER,
        )
        tube(out, HEAD_X, HEAD_Y, HEAD_X - .12f, CLAMP_Y + .35f, .32f, STEEL)
        tube(out, HEAD_X - .12f, CLAMP_Y + .35f, CLAMP_X, CLAMP_Y, .3f, STEEL)
        tube(out, CLAMP_X, CLAMP_Y, GRIP_X + .55f, GRIP_Y + .05f, .24f, STEEL)
        tube(out, GRIP_X + .55f, GRIP_Y + .2f, GRIP_X + 1.3f, GRIP_Y + 1.1f, .12f, DARK_STEEL)
        tube(out, GRIP_X - .5f, GRIP_Y, GRIP_X + .7f, GRIP_Y - .05f, .4f, SADDLE, Mat.RUBBER)
        // the lamps: the head lamp on the crown, the tail lamp under the saddle
        add(
            out,
            FigBuilder.capsule(LAMP_X - .55f, LAMP_Y, .45f, LAMP_X + .2f, LAMP_Y, .5f, 4),
            0x1E2026,
            Mat.METAL,
        )
        add(out, FigBuilder.ellipse(LAMP_X + .45f, LAMP_Y, .2f, .42f, 0f, 6), 0xE8ECF0, Mat.FLAT)
        add(
            out,
            FigBuilder.capsule(TAIL_X, TAIL_Y - .35f, .3f, TAIL_X, TAIL_Y + .35f, .3f, 4),
            0xB0201C,
            Mat.METAL,
        )
        // the axles' nuts, where the wheels hang
        for (x in floatArrayOf(rx, fx)) {
            add(out, FigBuilder.ellipse(x, ay, .42f, .42f, 0f, 8), STEEL, Mat.METAL)
        }
        // the tyres' sheen along their tops: it stays where the light is as the wheels turn
        for (x in floatArrayOf(rx, fx)) {
            add(out, arc(x, ay, R - .22f, R - .08f, 215f, 300f), 0xFFFFFF, Mat.FLAT, .22f)
        }

        // ---- the rider's body: the torso over the saddle, the pelvis on it, the neck and head
        val hipW = 1.8f * b
        val widths = floatArrayOf(hipW, 1.45f * b, 1.8f * b, 1.65f * b)
        val at = floatArrayOf(0f, .36f, .72f, .95f)
        // the back rounds a little over the bars
        val bow = floatArrayOf(0f, -.2f, -.35f, -.1f)
        val front = ArrayList<Float>()
        val back = ArrayList<Float>()
        for (i in 0 until 4) {
            val cx = hipX + ux * torsoL * at[i] + nx * bow[i]
            val cy = hipY + uy * torsoL * at[i] + ny * bow[i]
            front += cx + nx * widths[i]
            front += cy + ny * widths[i]
            back += cx - nx * widths[i]
            back += cy - ny * widths[i]
        }
        val tx = sx + ux * .7f
        val ty = sy + uy * .7f
        front += tx + nx * .9f
        front += ty + ny * .9f
        back += tx - nx * .9f
        back += ty - ny * .9f
        val outline = ArrayList<Float>(front)
        var k = back.size - 2
        while (k >= 0) {
            outline += back[k]
            outline += back[k + 1]
            k -= 2
        }
        add(out, FigBuilder.chaikin(outline.toFloatArray(), 2), w.top, Mat.CLOTH)
        // a crease across the belly where the body folds forward
        add(
            out,
            FigBuilder.capsule(
                hipX + ux * torsoL * .3f + nx * hipW * .3f,
                hipY + uy * torsoL * .3f + ny * hipW * .3f,
                .14f,
                hipX + ux * torsoL * .42f + nx * hipW * .95f,
                hipY + uy * torsoL * .42f + ny * hipW * .95f,
                .14f,
                3,
            ),
            StreetRig.dim(w.top, .6f),
            Mat.FLAT,
            .45f,
        )
        val pelvisA = lean * .45f + 5f * rock
        add(
            out,
            FigBuilder.ellipse(hipX + .1f, hipY + .2f, 2.6f * b, 1.9f * hgt, pelvisA, 12),
            w.legs,
            Mat.CLOTH,
        )
        add(
            out,
            FigBuilder.capsule(
                hipX + ux * 1.4f - nx * 2.2f * b,
                hipY + uy * 1.4f - ny * 2.2f * b,
                .32f,
                hipX + ux * 1.4f + nx * 2.3f * b,
                hipY + uy * 1.4f + ny * 2.3f * b,
                .32f,
                3,
            ),
            0x2B2320,
            Mat.LEATHER,
        )
        // the neck, the tee's neckline, then the head looking up the road under a helmet
        val headA = lean * .3f - 4f + 1.5f * sin(2f * a + .2f)
        val neck = 1.0f * hgt
        val nkx = sx + sin(rad(lean * .6f)) * neck * .7f
        val nky = sy - cos(rad(lean * .6f)) * neck * .7f
        val hry = 1.65f * hgt * lk.head
        val hdx = nkx + sin(rad(headA)) * hry * .95f + 1.05f
        val hdy = nky - cos(rad(headA)) * hry * .95f - .1f
        add(
            out,
            FigBuilder.capsule(sx, sy, 1.0f * b, hdx - .3f, hdy + .4f, .85f * b, 5),
            w.skin,
            Mat.SKIN,
        )
        add(
            out,
            FigBuilder.ellipse(sx + ux * .1f, sy + uy * .1f, 1.4f * b, .5f * b, lean, 8),
            StreetRig.dim(w.top, .85f),
            Mat.CLOTH,
        )
        StreetRig.riderHead(out, lk, hdx, hdy, headA)

        // ---- the near side: the chainring and its spider, the crank, the pedal, the leg on it
        add(out, ring(BB_X, BB_Y, RING, RING - .32f), DARK_STEEL, Mat.METAL)
        for (i in 0 until 5) {
            val sa = a + i * 2f * PI.toFloat() / 5f + .3f
            tube(
                out,
                BB_X,
                BB_Y,
                BB_X + cos(sa) * (RING - .2f),
                BB_Y + sin(sa) * (RING - .2f),
                .2f,
                StreetRig.dim(STEEL, .85f),
                Mat.METAL,
                .14f,
            )
        }
        tube(out, BB_X, BB_Y, pedalX(a), pedalY(a), .38f, STEEL, Mat.METAL, .28f)
        add(out, FigBuilder.ellipse(BB_X, BB_Y, .5f, .5f, 0f, 8), DARK_STEEL, Mat.METAL)
        pedal(out, a, 1f)
        leg(out, lk, hipX + .2f, hipY + .14f * rock, a, 1f)

        // ---- the near arm to its grip
        arm(out, lk, sx + nx * .4f * b, sy + ny * .4f * b, GRIP_X + .1f, GRIP_Y, 1f)
        return out
    }

    /**
     * A ring about ([cx], [cy]): outer radius [ro], inner [ri] (wound back, so it has its hole).
     */
    private fun ring(cx: Float, cy: Float, ro: Float, ri: Float): FloatArray {
        val n = 20
        val out = FloatArray((n + 1) * 4)
        for (i in 0..n) {
            val t = i / n.toFloat() * 2f * PI.toFloat()
            out[i * 2] = cx + cos(t) * ro
            out[i * 2 + 1] = cy + sin(t) * ro
            val k = (2 * n + 1 - i) * 2
            out[k] = cx + cos(t) * ri
            out[k + 1] = cy + sin(t) * ri
        }
        return out
    }

    /**
     * How far the foot points its toe down at crank angle [a], degrees: the heel drops over the top
     * and the front of the stroke, the toe points through the bottom and the back (ankling).
     */
    fun toeDown(a: Float): Float = 13f + 11f * sin(a - .7f)

    /**
     * The pedal at crank angle [a]: a platform level with the foot on it, a reflector at its back.
     */
    private fun pedal(out: ArrayList<Shape>, a: Float, shade: Float) {
        val px = pedalX(a)
        val py = pedalY(a)
        val t = rad(toeDown(a))
        val c = cos(t)
        val s = sin(t)
        add(
            out,
            FigBuilder.capsule(
                px - c * 1.05f,
                py - s * 1.05f,
                .3f,
                px + c * 1.05f,
                py + s * 1.05f,
                .3f,
                3,
            ),
            StreetRig.dim(0x2C2F36, shade),
            Mat.METAL,
        )
        add(
            out,
            FigBuilder.ellipse(px - c * .85f, py - s * .85f + .05f, .26f, .2f, 0f, 5),
            StreetRig.dim(0xE07A1E, shade),
            Mat.FLAT,
            .9f,
        )
    }

    /**
     * The rider's leg from the hip ([hx], [hy]) to the pedal at crank angle [a]: the ball of the
     * foot on the pedal, the foot at [toeDown], the knee by the two bones' lengths forward of the
     * line from the hip to the ankle.
     */
    private fun leg(
        out: ArrayList<Shape>,
        lk: StreetLook,
        hx: Float,
        hy: Float,
        a: Float,
        shade: Float,
    ) {
        val hgt = lk.height
        val b = lk.build
        val th = 5.9f * hgt
        val sh = 5.7f * hgt
        val t = rad(toeDown(a))
        val ffx = cos(t)
        val ffy = sin(t)
        val ddx = -sin(t)
        val ddy = cos(t)
        // the ball of the foot (2 forward, the sole's depth down from the ankle) on the pedal's top
        val topX = pedalX(a) + ddx * -.3f
        val topY = pedalY(a) + ddy * -.3f
        val ax = topX - ffx * 1.95f * hgt - ddx * .98f * .85f * b
        val ay = topY - ffy * 1.95f * hgt - ddy * .98f * .85f * b
        val dx = ax - hx
        val dy = ay - hy
        val d = min(sqrt(dx * dx + dy * dy), (th + sh) * .995f)
        val base = atan2(dy, dx)
        val k = acos(((th * th + d * d - sh * sh) / (2f * th * d)).coerceIn(-1f, 1f))
        val kx = hx + cos(base - k) * th
        val ky = hy + sin(base - k) * th
        val rgb = StreetRig.dim(lk.wear.legs, shade)
        // the shoe, then the trouser leg over its top: full over the thigh, straight from the knee
        StreetRig.shoe(out, lk, ax, ay, ffx, ffy, ddx, ddy, shade)
        val sl = max(1e-3f, sqrt((ax - kx) * (ax - kx) + (ay - ky) * (ay - ky)))
        val hemX = ax + (ax - kx) / sl * .12f
        val hemY = ay + (ay - ky) / sl * .12f
        out +=
            Shape(
                Shape.BODY,
                StreetRig.limb(hx, hy, kx, ky, StreetRig.THIGH_F, StreetRig.THIGH_B, b),
                1f,
                rgb,
            )
        out +=
            Shape(
                Shape.BODY,
                StreetRig.limb(kx, ky, hemX, hemY, StreetRig.SHIN_F, StreetRig.SHIN_B, b, true),
                1f,
                rgb,
            )
        // the knee's fold, at the back of the bend
        out +=
            Shape(
                Shape.BODY,
                FigBuilder.ellipse(kx - (kx - hx) * .08f, ky + .3f, .35f, .55f, 0f, 6),
                1f,
                StreetRig.dim(rgb, .7f),
                Mat.FLAT,
                .45f,
            )
    }

    /**
     * The rider's arm from the shoulder ([sx], [sy]) to the grip at ([hx], [hy]): the tee's sleeve
     * over the upper arm, the bare forearm, the hand closed round the grip; the elbow bent, hanging
     * below the line to the bars.
     */
    private fun arm(
        out: ArrayList<Shape>,
        lk: StreetLook,
        sx: Float,
        sy: Float,
        hx: Float,
        hy: Float,
        shade: Float,
    ) {
        val hgt = lk.height
        val b = lk.build
        val ua = 4.1f * hgt
        val fa = 3.7f * hgt
        val dx = hx - sx
        val dy = hy - sy
        val d = min(sqrt(dx * dx + dy * dy), (ua + fa) * .99f)
        val base = atan2(dy, dx)
        val k = acos(((ua * ua + d * d - fa * fa) / (2f * ua * d)).coerceIn(-1f, 1f))
        val ex = sx + cos(base + k) * ua
        val ey = sy + sin(base + k) * ua
        val skin = StreetRig.dim(lk.wear.skin, shade)
        add(
            out,
            StreetRig.limb(sx, sy, ex, ey, StreetRig.BARE_UP_F, StreetRig.BARE_UP_B, b),
            skin,
            Mat.SKIN,
        )
        add(
            out,
            StreetRig.limb(ex, ey, hx, hy, StreetRig.BARE_FORE_F, StreetRig.BARE_FORE_B, b),
            skin,
            Mat.SKIN,
        )
        val mx = sx + (ex - sx) * .55f
        val my = sy + (ey - sy) * .55f
        add(
            out,
            FigBuilder.hull(StreetRig.bowed(sx, sy, 1.2f * b, mx, my, 1.0f * b, 0f)),
            StreetRig.dim(lk.wear.top, shade),
            Mat.CLOTH,
        )
        // the fist round the grip, the thumb over it
        add(out, FigBuilder.ellipse(hx + .1f, hy + .05f, .62f * b, .55f * b, 0f, 8), skin, Mat.SKIN)
        add(
            out,
            FigBuilder.ellipse(hx + .45f, hy - .3f, .3f * b, .22f * b, -20f, 6),
            StreetRig.dim(skin, .88f),
            Mat.SKIN,
        )
    }
}
