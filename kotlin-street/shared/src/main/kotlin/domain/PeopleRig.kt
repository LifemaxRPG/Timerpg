package com.lifemax.rpg.feature.habits.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * The people in the windows are small articulated rigs: a head, neck, torso and pelvis, upper and lower arms and
 * legs, in believable proportions (about seven and a half heads tall), posed by angles and drawn as smooth filled
 * shapes with tapered limbs, hair and clothes. Pure data and arithmetic with no Compose and no Android: the sprite
 * generator turns the shapes into pixels once, and the tests (and a designer's contact sheets) read them on any JVM.
 *
 * Space is the building's own: units (dp at scale 1), x to the right, y down, a standing person on y = 0. A person
 * faces +x; the painter mirrors them for the other way.
 */

/**
 * A filled polygon of a figure or of a room: [pts] as x, y, x, y..., painted in order of [layer].
 * The window figures are painted by [tone]; the street's people carry their own colour: [rgb] (no
 * colour: -1), what it is made of ([mat], one of [Mat]'s) and how opaque it is ([alpha]).
 */
class Shape(
    val layer: Int,
    val pts: FloatArray,
    val tone: Float,
    val rgb: Int = -1,
    val mat: Int = Mat.CLOTH,
    val alpha: Float = 1f,
) {
    companion object {
        const val FAR = 0
        const val BODY = 1
        const val NEAR = 2
        const val PROP = 3
    }
}

/** What a coloured [Shape] is made of: how it takes the light. */
object Mat {
    const val CLOTH = 0
    const val SKIN = 1
    const val HAIR = 2
    /** Leather and shoes: a soft sheen. */
    const val LEATHER = 3
    /** A bicycle's paint and chrome: a sharp highlight. */
    const val METAL = 4
    const val RUBBER = 5
    /** Painted as it is, unlit: a seam, a fold, a glint, a lamp's lens. */
    const val FLAT = 6
    /** Jeans: a faint diagonal twill in the cloth. */
    const val DENIM = 7
    /** A wool coat or trousers: matte, a soft sheen at the turning edges. */
    const val WOOL = 8
    /** A nylon jacket: a broad, soft sheen. */
    const val NYLON = 9
    /** A knitted hat: its ribs. */
    const val KNIT = 10
    /** Tweed: flecks of other colours in the weave. */
    const val TWEED = 11
}

/** Hair, from a bare crown to a cap. */
enum class Hair {
    SHORT,
    BUN,
    PONY,
    CAP,
    LONG,
    BEANIE,
}

/** What they wear on top: only the outline shows. */
enum class Top {
    TEE,
    HOODIE,
    JACKET,
}

/** How a body is built: [height] and [build] (thickness) as factors, [head] the head's size. */
class Body(val height: Float, val build: Float, val head: Float)

/** One person's look: a body, its hair, what it wears. */
class Look(val body: Body, val hair: Hair, val top: Top, val skirt: Boolean)

object Looks {
    val SLIM = Body(1f, .86f, 1f)
    val BROAD = Body(.97f, 1.2f, 1.03f)
    val TALL = Body(1.1f, .92f, .97f)
    val SHORT = Body(.88f, 1f, 1.08f)

    val ALL =
        arrayOf(
            Look(SLIM, Hair.PONY, Top.TEE, false),
            Look(BROAD, Hair.SHORT, Top.HOODIE, false),
            Look(TALL, Hair.CAP, Top.JACKET, false),
            Look(SHORT, Hair.BUN, Top.TEE, true),
            Look(TALL, Hair.LONG, Top.JACKET, true),
            Look(BROAD, Hair.BEANIE, Top.TEE, false),
        )

    val COUNT: Int
        get() = ALL.size

    /** The children: small, a big head, two of them. */
    val CHILDREN =
        arrayOf(
            Look(Body(.6f, 1.02f, 1.36f), Hair.SHORT, Top.TEE, false),
            Look(Body(.56f, 1f, 1.4f), Hair.PONY, Top.TEE, true),
        )
}

/** The numbers that make a pose, by position in a pose's array. */
object K {
    /**
     * Pelvis x, how far the body is turned (0 facing us, 1 side on), torso lean forward in degrees.
     */
    const val X = 0
    const val TURN = 1
    const val LEAN = 2

    /** The head's tilt from the torso. */
    const val HEAD = 3

    /**
     * Arms and legs: angle from hanging straight down, forward positive; then the elbow or knee
     * bend.
     */
    const val AN = 4
    const val EN = 5
    const val AF = 6
    const val EF = 7
    const val LN = 8
    const val KN = 9
    const val LF = 10
    const val KF = 11

    /** Lift off the floor (a hop). */
    const val LIFT = 12

    /** 1 when [X] is where the near ankle stands (feet planted), 0 when it is the pelvis. */
    const val PIN = 13

    /**
     * The ankles' tilt from square to the shin, toes up positive (a heel strike has the toes up, a
     * toe-off has them down), near then far.
     */
    const val ANN = 14
    const val ANF = 15

    /**
     * The counter-rotation of a walk, in units of build: how far the near hip is ahead of the
     * pelvis's middle (and the far one behind it) beyond what the turn gives, and the same for the
     * shoulders ([SHX]). A walk has the near hip forward when the near shoulder is back.
     */
    const val TWIST = 16
    const val SHX = 17
    const val SIZE = 18

    val NAMES =
        arrayOf(
            "x",
            "turn",
            "lean",
            "head",
            "an",
            "en",
            "af",
            "ef",
            "ln",
            "kn",
            "lf",
            "kf",
            "lift",
            "pin",
            "ann",
            "anf",
            "twist",
            "shx",
        )
}

/**
 * A pose as numbers, with the ones not named at rest (standing, arms by the sides, slightly
 * turned).
 */
fun pose(
    x: Float = 0f,
    turn: Float = .3f,
    lean: Float = 0f,
    head: Float = 0f,
    an: Float = 6f,
    en: Float = 12f,
    af: Float = -6f,
    ef: Float = -12f,
    ln: Float = 2f,
    kn: Float = 4f,
    lf: Float = -2f,
    kf: Float = 4f,
    lift: Float = 0f,
    pin: Float = 0f,
    ann: Float = 0f,
    anf: Float = 0f,
    twist: Float = 0f,
    shx: Float = 0f,
): FloatArray =
    floatArrayOf(
        x,
        turn,
        lean,
        head,
        an,
        en,
        af,
        ef,
        ln,
        kn,
        lf,
        kf,
        lift,
        pin,
        ann,
        anf,
        twist,
        shx,
    )

/** Where a figure's joints ended up, for props and rooms to hang on. */
class Joints {
    var headX = 0f
    var headY = 0f
    var headAngle = 0f
    var shoulderX = 0f
    var shoulderY = 0f
    var pelvisX = 0f
    var pelvisY = 0f
    var handNX = 0f
    var handNY = 0f
    var handFX = 0f
    var handFY = 0f
    var foreN = 0f
    var foreF = 0f
    var footNX = 0f
    var footNY = 0f
    var footFX = 0f
    var footFY = 0f
    var kneeNX = 0f
    var kneeNY = 0f
}

/** Which of the (up to) four layers a hand-held prop is drawn in. */
fun interface PropMaker {
    fun make(f: FigBuilder, j: Joints, phase: Float)
}

/**
 * Builds the shapes of one person in one pose. [swing] is how far hair and loose cloth swing
 * (degrees, from the motion); [props] adds what is held, after the joints are known.
 */
class FigBuilder(val look: Look, val p: FloatArray, val swing: Float) {
    private val layers = Array(4) { ArrayList<Shape>() }
    val joints = Joints()

    fun add(layer: Int, pts: FloatArray, tone: Float) {
        layers[layer] += Shape(layer, pts, tone)
    }

    /**
     * All shapes, back to front: the body standing on y = 0 (raised by the pose's lift), then what
     * is held.
     */
    fun shapes(): List<Shape> {
        val out = ArrayList<Shape>()
        for (l in layers) out += l
        return out
    }

    /** Puts the figure on the floor: the lowest point of the body goes to y = -lift. */
    private fun ground() {
        var low = -1e9f
        for (l in 0..2) for (s in layers[l]) {
            var i = 1
            while (i < s.pts.size) {
                low = max(low, s.pts[i])
                i += 2
            }
        }
        val dy = -low - p[K.LIFT]
        for (l in layers) {
            for (k in l.indices) {
                val q = l[k].pts
                var i = 1
                while (i < q.size) {
                    q[i] += dy
                    i += 2
                }
            }
        }
        joints.headY += dy
        joints.shoulderY += dy
        joints.pelvisY += dy
        joints.handNY += dy
        joints.handFY += dy
        joints.footNY += dy
        joints.footFY += dy
        joints.kneeNY += dy
    }

    fun build(props: PropMaker?, phase: Float): FigBuilder {
        val body = look.body
        val hgt = body.height
        val b = body.build
        val hs = body.head
        val torsoL = 7.4f * hgt
        val neck = 1.0f * hgt
        val headRy = 1.65f * hgt * hs
        val headRx = 1.35f * hgt * hs
        val ua = 4.1f * hgt
        val fa = 3.7f * hgt
        val th = 5.9f * hgt
        val sh = 5.7f * hgt
        val turn = p[K.TURN]
        val lean = p[K.LEAN]
        val jacket = look.top == Top.JACKET
        val hood = look.top == Top.HOODIE
        val thick = if (jacket || hood) 1.1f else 1f

        val twist = p[K.TWIST]
        val hw = (lerp(1.5f, .4f, turn) + twist) * b
        val px =
            if (p[K.PIN] > .5f)
                p[K.X] - (hw + sin(rad(p[K.LN])) * th + sin(rad(p[K.LN] - p[K.KN])) * sh)
            else p[K.X]
        val py = 0f
        val ux = sin(rad(lean))
        val uy = -cos(rad(lean))
        val nx = cos(rad(lean))
        val ny = sin(rad(lean))
        val sx = px + ux * torsoL
        val sy = py + uy * torsoL
        joints.pelvisX = px
        joints.pelvisY = py
        joints.shoulderX = sx
        joints.shoulderY = sy

        // ---- the legs
        legs(px, py, hw, th, sh, b, hgt, FAR_TONE, Shape.FAR, p[K.LF], p[K.KF], p[K.ANF], -1f)
        // ---- the far arm
        val sw = (lerp(2.9f, .5f, turn) + p[K.SHX]) * b
        arm(
            sx - nx * sw,
            sy - ny * sw,
            ua,
            fa,
            b * thick,
            p[K.AF],
            p[K.EF],
            Shape.FAR,
            FAR_TONE,
            false,
        )

        // ---- the torso, neck, head
        val shoulderW = lerp(3.3f, 1.9f, turn) * b * thick
        val chestW = lerp(3.0f, 2.1f, turn) * b * thick
        val waistW = lerp(2.4f, 1.8f, turn) * b * (if (hood) 1.08f else 1f)
        val hipW = lerp(2.8f, 2.1f, turn) * b
        val widths = floatArrayOf(hipW, waistW, chestW, shoulderW * .96f)
        val at = floatArrayOf(0f, .36f, .74f, .95f)
        val torso = ArrayList<Float>()
        val left = ArrayList<Float>()
        for (i in 0 until 4) {
            val cx = px + ux * torsoL * at[i]
            val cy = py + uy * torsoL * at[i]
            torso += cx + nx * widths[i]
            torso += cy + ny * widths[i]
            left += cx - nx * widths[i]
            left += cy - ny * widths[i]
        }
        // the shoulders round off towards the neck
        val topx = sx + ux * .75f
        val topy = sy + uy * .75f
        torso += topx + nx * shoulderW * .42f
        torso += topy + ny * shoulderW * .42f
        left += topx - nx * shoulderW * .42f
        left += topy - ny * shoulderW * .42f
        val outline = ArrayList<Float>(torso)
        var k = left.size - 2
        while (k >= 0) {
            outline += left[k]
            outline += left[k + 1]
            k -= 2
        }
        add(Shape.BODY, chaikin(outline.toFloatArray(), 2), BODY_TONE)
        if (jacket) {
            // a longer hem over the hips
            val hx = ux * 2.2f
            val hy = uy * 2.2f
            add(
                Shape.BODY,
                chaikin(
                    floatArrayOf(
                        px + nx * (hipW + .5f) - hx,
                        py + ny * (hipW + .5f) - hy,
                        px + nx * (waistW) + ux * 1f,
                        py + ny * waistW + uy * 1f,
                        px - nx * waistW + ux * 1f,
                        py - ny * waistW + uy * 1f,
                        px - nx * (hipW + .5f) - hx,
                        py - ny * (hipW + .5f) - hy,
                    ),
                    2,
                ),
                BODY_TONE,
            )
        }
        // the pelvis, where the legs join
        add(Shape.BODY, ellipse(px, py + .2f, hipW * .95f, 1.9f * hgt, lean, 10), BODY_TONE)

        val headA = lean + p[K.HEAD]
        val hux = sin(rad(headA))
        val huy = -cos(rad(headA))
        val hfx = cos(rad(headA))
        val hfy = sin(rad(headA))
        val nkx = sx + sin(rad(lean + p[K.HEAD] * .5f)) * (neck * .55f)
        val nky = sy - cos(rad(lean + p[K.HEAD] * .5f)) * (neck * .55f)
        val hx = nkx + hux * (neck * .6f + headRy * .92f)
        val hy = nky + huy * (neck * .6f + headRy * .92f)
        joints.headX = hx
        joints.headY = hy
        joints.headAngle = headA
        add(Shape.BODY, capsule(sx, sy, 1.15f * b, hx, hy, .95f * b, 6), BODY_TONE)
        // the head: an egg, jaw narrower than the brow
        add(Shape.BODY, head(hx, hy, headRx, headRy, headA, turn), HEAD_TONE)
        hair(look.hair, hx, hy, headRx, headRy, hux, huy, hfx, hfy, hgt)
        if (hood) {
            add(
                Shape.BODY,
                ellipse(
                    sx - hfx * 1.2f + hux * .2f,
                    sy - hfy * 1.2f + huy * .4f,
                    1.7f * b,
                    1.4f * hgt,
                    headA,
                    9,
                ),
                BODY_TONE,
            )
        }

        // ---- the skirt, if there is one, then the near leg over it
        if (look.skirt) skirt(px, py, hw, hipW, th, hgt)
        legs(px, py, hw, th, sh, b, hgt, NEAR_LEG_TONE, Shape.NEAR, p[K.LN], p[K.KN], p[K.ANN], 1f)

        // ---- the near arm
        arm(
            sx + nx * sw,
            sy + ny * sw,
            ua,
            fa,
            b * thick,
            p[K.AN],
            p[K.EN],
            Shape.NEAR,
            NEAR_TONE,
            true,
        )
        ground()
        props?.make(this, joints, phase)
        return this
    }

    private fun legs(
        px: Float,
        py: Float,
        hw: Float,
        th: Float,
        sh: Float,
        b: Float,
        hgt: Float,
        tone: Float,
        layer: Int,
        l: Float,
        kn: Float,
        ankle: Float,
        side: Float,
    ) {
        val hx = px + side * hw
        val hy = py
        val tx = hx + sin(rad(l)) * th
        val ty = hy + cos(rad(l)) * th
        val sa = l - kn
        val ax = tx + sin(rad(sa)) * sh
        val ay = ty + cos(rad(sa)) * sh
        val r0 = 1.78f * b
        add(layer, capsule(hx, hy, r0, tx, ty, 1.2f * b, 8), tone)
        add(layer, capsule(tx, ty, 1.2f * b, ax, ay, .75f * b, 8), tone)
        // the foot: heel to toe, flat on the floor when the shin is upright; the ankle turns it
        // (toes up positive) from square to the shin
        val fx = sin(rad(sa + 90f + ankle))
        val fy = cos(rad(sa + 90f + ankle))
        val dx = sin(rad(sa + ankle))
        val dy = cos(rad(sa + ankle))
        add(
            layer,
            capsule(
                ax + dx * .3f - fx * .25f,
                ay + dy * .3f - fy * .25f,
                .68f * b,
                ax + dx * .38f + fx * 2.1f * hgt,
                ay + dy * .38f + fy * 2.1f * hgt,
                .52f * b,
                6,
            ),
            tone,
        )
        if (side > 0f) {
            joints.footNX = ax
            joints.footNY = ay
            joints.kneeNX = tx
            joints.kneeNY = ty
        } else {
            joints.footFX = ax
            joints.footFY = ay
        }
    }

    private fun arm(
        sx: Float,
        sy: Float,
        ua: Float,
        fa: Float,
        b: Float,
        a: Float,
        e: Float,
        layer: Int,
        tone: Float,
        near: Boolean,
    ) {
        val ex = sx + sin(rad(a)) * ua
        val ey = sy + cos(rad(a)) * ua
        val fa0 = a + e
        val wx = ex + sin(rad(fa0)) * fa
        val wy = ey + cos(rad(fa0)) * fa
        add(layer, capsule(sx, sy, 1.12f * b, ex, ey, .88f * b, 7), tone)
        add(layer, capsule(ex, ey, .88f * b, wx, wy, .62f * b, 7), tone)
        // the hand
        val hx = wx + sin(rad(fa0)) * .55f
        val hy = wy + cos(rad(fa0)) * .55f
        add(layer, ellipse(hx, hy, .78f, .95f, fa0, 7), tone)
        if (near) {
            joints.handNX = hx
            joints.handNY = hy
            joints.foreN = fa0
        } else {
            joints.handFX = hx
            joints.handFY = hy
            joints.foreF = fa0
        }
    }

    private fun skirt(px: Float, py: Float, hw: Float, hipW: Float, th: Float, hgt: Float) {
        // from the waist to the knee, flared: a hull of the hips and the knees' level
        val ln = p[K.LN]
        val lf = p[K.LF]
        val hem = th * .78f
        val ax = px + sin(rad(ln)) * hem
        val ay = py + cos(rad(ln)) * hem
        val bx = px + sin(rad(lf)) * hem
        val by = py + cos(rad(lf)) * hem
        val w = hipW * 1.25f
        add(
            Shape.BODY,
            hull(
                floatArrayOf(
                    px - hipW,
                    py - .5f,
                    px + hipW,
                    py - .5f,
                    ax + w,
                    ay,
                    ax - w * .6f,
                    ay,
                    bx + w * .6f,
                    by,
                    bx - w,
                    by,
                )
            ),
            BODY_TONE,
        )
    }

    private fun hair(
        h: Hair,
        hx: Float,
        hy: Float,
        rx: Float,
        ry: Float,
        ux: Float,
        uy: Float,
        fx: Float,
        fy: Float,
        hgt: Float,
    ) {
        val ang = joints.headAngle
        when (h) {
            Hair.SHORT ->
                add(
                    Shape.BODY,
                    ellipse(
                        hx + ux * ry * .28f - fx * rx * .22f,
                        hy + uy * ry * .28f - fy * rx * .22f,
                        rx * 1.1f,
                        ry * .84f,
                        ang,
                        12,
                    ),
                    HEAD_TONE,
                )
            Hair.BUN -> {
                add(
                    Shape.BODY,
                    ellipse(
                        hx + ux * ry * .25f - fx * rx * .2f,
                        hy + uy * ry * .25f - fy * rx * .2f,
                        rx * 1.08f,
                        ry * .82f,
                        ang,
                        12,
                    ),
                    HEAD_TONE,
                )
                add(
                    Shape.BODY,
                    ellipse(
                        hx + ux * ry * 1.0f - fx * rx * .7f,
                        hy + uy * ry * 1.0f - fy * rx * .7f,
                        1.0f * hgt,
                        .95f * hgt,
                        ang,
                        9,
                    ),
                    HEAD_TONE,
                )
            }
            Hair.PONY -> {
                add(
                    Shape.BODY,
                    ellipse(
                        hx + ux * ry * .28f - fx * rx * .25f,
                        hy + uy * ry * .28f - fy * rx * .25f,
                        rx * 1.1f,
                        ry * .84f,
                        ang,
                        12,
                    ),
                    HEAD_TONE,
                )
                // the tail swings with the head
                val x0 = hx - fx * rx * .95f + ux * ry * .35f
                val y0 = hy - fy * rx * .95f + uy * ry * .35f
                val a1 = ang + 200f + swing
                val a2 = a1 + 14f + swing * .6f
                val x1 = x0 + sin(rad(a1)) * 1.9f * hgt
                val y1 = y0 - cos(rad(a1)) * 1.9f * hgt
                val x2 = x1 + sin(rad(a2)) * 2.3f * hgt
                val y2 = y1 - cos(rad(a2)) * 2.3f * hgt
                add(Shape.BODY, capsule(x0, y0, .85f, x1, y1, .8f, 6), HEAD_TONE)
                add(Shape.BODY, capsule(x1, y1, .8f, x2, y2, .3f, 6), HEAD_TONE)
            }
            Hair.CAP -> {
                add(
                    Shape.BODY,
                    ellipse(
                        hx + ux * ry * .42f - fx * rx * .1f,
                        hy + uy * ry * .42f - fy * rx * .1f,
                        rx * 1.12f,
                        ry * .62f,
                        ang,
                        12,
                    ),
                    HEAD_TONE,
                )
                // the peak
                val bx = hx + ux * ry * .34f + fx * rx * .85f
                val by = hy + uy * ry * .34f + fy * rx * .85f
                add(
                    Shape.BODY,
                    capsule(bx, by, .42f, bx + fx * 1.9f, by + fy * 1.9f + .2f, .3f, 5),
                    HEAD_TONE,
                )
            }
            Hair.LONG -> {
                add(
                    Shape.BODY,
                    ellipse(
                        hx + ux * ry * .26f - fx * rx * .25f,
                        hy + uy * ry * .26f - fy * rx * .25f,
                        rx * 1.12f,
                        ry * .86f,
                        ang,
                        12,
                    ),
                    HEAD_TONE,
                )
                val x0 = hx - fx * rx * .55f
                val y0 = hy - fy * rx * .55f
                val x1 = x0 - fx * .3f - ux * (ry * 1.6f) + sin(rad(swing)) * .8f
                val y1 = y0 - fy * .3f - uy * (ry * 1.6f)
                add(Shape.BODY, capsule(x0, y0, 1.25f, x1, y1, 1.05f, 7), HEAD_TONE)
            }
            Hair.BEANIE -> {
                add(
                    Shape.BODY,
                    ellipse(
                        hx + ux * ry * .45f,
                        hy + uy * ry * .45f,
                        rx * 1.14f,
                        ry * .66f,
                        ang,
                        12,
                    ),
                    HEAD_TONE,
                )
                add(
                    Shape.BODY,
                    ellipse(hx + ux * ry * 1.1f, hy + uy * ry * 1.1f, .6f, .6f, ang, 7),
                    HEAD_TONE,
                )
            }
        }
    }

    private fun head(
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
        ang: Float,
        turn: Float,
    ): FloatArray {
        val n = 16
        val out = FloatArray(n * 2 + 6)
        val ca = cos(rad(ang))
        val sa = sin(rad(ang))
        for (i in 0 until n) {
            val t = (i / n.toFloat()) * 2f * PI.toFloat()
            // local x forward, y down; the jaw (lower half) is narrower
            val down = max(0f, sin(t))
            val lx = cos(t) * rx * (1f - .2f * down)
            val ly = sin(t) * ry * (1f + .04f * down)
            out[i * 2] = cx + lx * ca - ly * sa
            out[i * 2 + 1] = cy + lx * sa + ly * ca
        }
        // a small nose and chin when the face is in profile
        val s = turn
        val lx = rx * (.98f + .3f * s)
        val ly = ry * .05f
        out[n * 2] = cx + (rx * .9f) * ca - (-ry * .25f) * sa
        out[n * 2 + 1] = cy + (rx * .9f) * sa + (-ry * .25f) * ca
        out[n * 2 + 2] = cx + lx * ca - ly * sa
        out[n * 2 + 3] = cy + lx * sa + ly * ca
        out[n * 2 + 4] = cx + (rx * .88f) * ca - (ry * .4f) * sa
        out[n * 2 + 5] = cy + (rx * .88f) * sa + (ry * .4f) * ca
        return hull(out)
    }

    companion object {
        const val FAR_TONE = .5f
        const val BODY_TONE = .78f
        const val HEAD_TONE = .84f
        const val NEAR_LEG_TONE = .9f
        const val NEAR_TONE = 1f

        fun rad(deg: Float): Float = deg * (PI.toFloat() / 180f)

        fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        /**
         * A stadium between two circles: a limb tapering from [r0] at (x0, y0) to [r1] at (x1, y1).
         */
        fun capsule(
            x0: Float,
            y0: Float,
            r0: Float,
            x1: Float,
            y1: Float,
            r1: Float,
            arc: Int,
        ): FloatArray {
            val t = atan2(y1 - y0, x1 - x0)
            val out = FloatArray((arc * 2 + 2) * 2)
            var o = 0
            for (i in 0..arc) {
                val a = t + PI.toFloat() / 2f + PI.toFloat() * i / arc
                out[o++] = x0 + cos(a) * r0
                out[o++] = y0 + sin(a) * r0
            }
            for (i in 0..arc) {
                val a = t - PI.toFloat() / 2f + PI.toFloat() * i / arc
                out[o++] = x1 + cos(a) * r1
                out[o++] = y1 + sin(a) * r1
            }
            return out
        }

        fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, rotDeg: Float, n: Int): FloatArray {
            val out = FloatArray(n * 2)
            val ca = cos(rad(rotDeg))
            val sa = sin(rad(rotDeg))
            for (i in 0 until n) {
                val t = (i / n.toFloat()) * 2f * PI.toFloat()
                val lx = cos(t) * rx
                val ly = sin(t) * ry
                out[i * 2] = cx + lx * ca - ly * sa
                out[i * 2 + 1] = cy + lx * sa + ly * ca
            }
            return out
        }

        /**
         * Rounds a closed polygon's corners: each pass cuts every corner at a quarter and three
         * quarters.
         */
        fun chaikin(p: FloatArray, passes: Int): FloatArray {
            var cur = p
            repeat(passes) {
                val n = cur.size / 2
                val out = FloatArray(n * 4)
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    val x0 = cur[i * 2]
                    val y0 = cur[i * 2 + 1]
                    val x1 = cur[j * 2]
                    val y1 = cur[j * 2 + 1]
                    out[i * 4] = x0 * .75f + x1 * .25f
                    out[i * 4 + 1] = y0 * .75f + y1 * .25f
                    out[i * 4 + 2] = x0 * .25f + x1 * .75f
                    out[i * 4 + 3] = y0 * .25f + y1 * .75f
                }
                cur = out
            }
            return cur
        }

        /** The convex hull of [p] (monotone chain). */
        fun hull(p: FloatArray): FloatArray {
            val n = p.size / 2
            val idx = (0 until n).sortedWith(compareBy({ p[it * 2] }, { p[it * 2 + 1] }))
            fun cross(o: Int, a: Int, b: Int) =
                (p[a * 2] - p[o * 2]) * (p[b * 2 + 1] - p[o * 2 + 1]) -
                    (p[a * 2 + 1] - p[o * 2 + 1]) * (p[b * 2] - p[o * 2])
            val h = ArrayList<Int>()
            for (i in idx) {
                while (h.size >= 2 && cross(h[h.size - 2], h[h.size - 1], i) <= 0f) h.removeAt(
                    h.size - 1
                )
                h += i
            }
            val lower = h.size + 1
            for (i in idx.reversed().drop(1)) {
                while (h.size >= lower && cross(h[h.size - 2], h[h.size - 1], i) <= 0f) h.removeAt(
                    h.size - 1
                )
                h += i
            }
            h.removeAt(h.size - 1)
            val out = FloatArray(h.size * 2)
            for ((k, i) in h.withIndex()) {
                out[k * 2] = p[i * 2]
                out[k * 2 + 1] = p[i * 2 + 1]
            }
            return out
        }

        fun bounds(shapes: List<Shape>, upTo: Int = Shape.PROP): FloatArray {
            var x0 = 1e9f
            var y0 = 1e9f
            var x1 = -1e9f
            var y1 = -1e9f
            for (s in shapes) {
                if (s.layer > upTo) continue
                var i = 0
                while (i < s.pts.size) {
                    x0 = min(x0, s.pts[i])
                    x1 = max(x1, s.pts[i])
                    y0 = min(y0, s.pts[i + 1])
                    y1 = max(y1, s.pts[i + 1])
                    i += 2
                }
            }
            return floatArrayOf(x0, y0, x1, y1)
        }

        fun same(a: FloatArray, b: FloatArray, tol: Float): Boolean {
            if (a.size != b.size) return false
            for (i in a.indices) if (abs(a[i] - b[i]) > tol) return false
            return true
        }
    }
}
