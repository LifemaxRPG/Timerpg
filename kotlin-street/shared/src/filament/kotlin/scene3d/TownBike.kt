package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import com.lifemax.rpg.feature.habits.presentation.scene.BikeState
import com.lifemax.rpg.feature.habits.presentation.scene.BikeStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The player's bicycle (docs/TOWN.md "Open world: the bicycle") in our style: simple real geometry wearing pixel pictures
 * painted texel by texel in the pack's palette (the navy outline, two or three tones), sampled nearest. Two wheels (a tread
 * band and a spoked disc each side, see-through between the spokes), the frame's tubes, the fork and bar that steer, the
 * saddle, the cranks and pedals that turn with the pedalling (the pedals stay level), lamp, mudguards, rack or basket by
 * the era: a 1950s cruiser, a 1970s ten-speed, a 1980s BMX, a modern hybrid ([BikeStyle]).
 *
 * The bike's own frame: +x forward, y up, z to the rider's right; its middle between the wheels on the ground at the origin.
 */
internal object BikeKit {
    /** The parts, each its own mesh: the frame (fixed), the fork and bar (steer), a wheel (turns), the cranks, a pedal. */
    class Parts(val frame: PropMesh, val fork: PropMesh, val wheel: PropMesh, val crank: PropMesh, val pedal: PropMesh, val geo: Geometry)

    /** Where things are on a style's bike (its own frame). */
    class Geometry(
        val r: Float, val rear: FloatArray, val front: FloatArray, val bb: FloatArray, val seat: FloatArray, val headTop: FloatArray,
        val headBottom: FloatArray, val crank: Float, val pedalZ: Float,
        /** Where the rider's hips sit (over the saddle) and the bar's grips (for the hands). */
        val hips: FloatArray,
    )

    private fun c(rgb: Long): Int = (0xFF000000L or rgb).toInt()
    private val OUTLINE = c(0x2A2238)
    private val TYRE = c(0x2E2C36)
    private val TYRE_L = c(0x47454F)
    private val CHROME = c(0xC9CFD8)
    private val CHROME_D = c(0x8A93A3)
    private val SPOKE = c(0xB7BECA)
    private val SADDLE = c(0x3B2A26)
    private val SADDLE_L = c(0x5A4038)
    private val GRIP = c(0x2B2730)
    private val LAMP = c(0xF6EDB8)

    private class Look(val frame: Int, val frameL: Int, val frameD: Int, val accent: Int, val tyreW: Float, val wall: Int)

    private fun look(s: BikeStyle) = when (s) {
        // (a red and cream cruiser, whitewall tyres)
        BikeStyle.CRUISER -> Look(c(0xB23A3A), c(0xD9605A), c(0x7E2630), c(0xEFE4C8), 1.5f, c(0xE8E2D2))
        // (an orange ten-speed with white bar tape)
        BikeStyle.TEN_SPEED -> Look(c(0xD9772E), c(0xF09A4A), c(0x9C4E22), c(0xEDEAE2), .7f, TYRE)
        // (a chrome-blue BMX, yellow pads)
        BikeStyle.BMX -> Look(c(0x3C7FD0), c(0x6AA6EA), c(0x284F92), c(0xF2D23C), 1.3f, TYRE)
        // (a sage-green hybrid, black bits)
        BikeStyle.HYBRID -> Look(c(0x5E8A62), c(0x80AE80), c(0x3E5E46), c(0x2D2B33), .95f, TYRE)
    }

    fun geometry(s: BikeStyle): Geometry = when (s) {
        BikeStyle.BMX -> Geometry(5.4f, v(-8.6f, 5.4f, 0f), v(8.8f, 5.4f, 0f), v(-.6f, 5.6f, 0f), v(-3.6f, 12.2f, 0f),
            v(6.4f, 13.4f, 0f), v(7.2f, 10.2f, 0f), 3.4f, 2.6f, v(-4.6f, 15.8f, 0f))
        BikeStyle.CRUISER -> Geometry(7f, v(-11.4f, 7f, 0f), v(11.2f, 7f, 0f), v(-1.2f, 5.6f, 0f), v(-4.6f, 16.2f, 0f),
            v(7.2f, 15.6f, 0f), v(8.4f, 11.8f, 0f), 3.4f, 2.6f, v(-6f, 19.6f, 0f))
        BikeStyle.TEN_SPEED -> Geometry(7f, v(-10.8f, 7f, 0f), v(10.8f, 7f, 0f), v(-.8f, 5.8f, 0f), v(-4.2f, 17.4f, 0f),
            v(7.6f, 17.2f, 0f), v(8.6f, 13.6f, 0f), 3.5f, 2.4f, v(-5.2f, 19.8f, 0f))
        BikeStyle.HYBRID -> Geometry(7f, v(-10.9f, 7f, 0f), v(11f, 7f, 0f), v(-1f, 5.8f, 0f), v(-4.4f, 16.6f, 0f),
            v(7.6f, 17f, 0f), v(8.6f, 13.2f, 0f), 3.5f, 2.5f, v(-5.4f, 19.6f, 0f))
    }

    private val cache = HashMap<BikeStyle, Parts>()

    fun parts(s: BikeStyle): Parts = synchronized(cache) { cache.getOrPut(s) { build(s) } }

    private fun v(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
    private fun add(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
    private fun lerp(a: FloatArray, b: FloatArray, t: Float) = floatArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)
    private fun swatch(c: Int) = PixImg(1, 1).also { it.fill(c) }

    /** A tube's picture: a lit stripe along its top, the colour, a darker underside, round as the light finds it. */
    private fun tube(base: Int, light: Int, dark: Int) = PixImg(1, 4).also { it[0, 0] = light; it[0, 1] = base; it[0, 2] = base; it[0, 3] = dark }

    /** The wheel's side: tyre, rim, spokes and hub on a 32-texel disc, clear between the spokes. */
    private fun disc(s: BikeStyle, lk: Look): PixImg {
        val n = 32
        val img = PixImg(n, n)
        val c0 = (n - 1) / 2f
        val spokes = if (s == BikeStyle.BMX) 36 else 32
        val mag = s == BikeStyle.BMX && false
        for (y in 0 until n) for (x in 0 until n) {
            val dx = x - c0; val dy = y - c0
            val r = sqrt(dx * dx + dy * dy) / (n / 2f)
            val a = kotlin.math.atan2(dy, dx)
            img[x, y] = when {
                r > 1f -> 0
                r > .93f -> OUTLINE
                r > .80f -> if (lk.wall != TYRE && r < .86f) lk.wall else TYRE
                r > .74f -> CHROME
                r > .70f -> CHROME_D
                r < .13f -> if (r < .07f) CHROME_D else CHROME
                mag -> 0
                else -> {
                    // spokes crossing in pairs (laced): a thin line every 2 pi / spokes, offset each side
                    val k = (a / (2.0 * PI) * spokes + r * 2.2).let { it - kotlin.math.floor(it) }
                    if (k < .16 || k > .94) SPOKE else 0
                }
            }
        }
        return img
    }

    /** The tread's band round the wheel: dark rubber with a lighter knurl. */
    private fun tread(s: BikeStyle) = PixImg(24, 2).also { p ->
        p.fill(TYRE)
        for (x in 0 until 24) if (x % (if (s == BikeStyle.BMX) 2 else 3) == 0) { p[x, 0] = TYRE_L; p[x, 1] = TYRE_L }
    }

    private fun build(s: BikeStyle): Parts {
        val g = geometry(s)
        val lk = look(s)
        val paint = tube(lk.frame, lk.frameL, lk.frameD)
        val chrome = tube(CHROME, c(0xE6EAF0), CHROME_D)
        val black = tube(c(0x34313B), c(0x4A4652), c(0x232028))
        val accent = tube(lk.accent, lk.accent, OUTLINE)
        val t = if (s == BikeStyle.CRUISER) 1.15f else if (s == BikeStyle.BMX) 1.05f else .85f

        // the frame: tubes, saddle and its post, mudguards, rack or basket, chain ring and chain, the lamp
        val f = MeshBuilder()
        val stays = g.seat.let { lerp(it, g.rear, .06f) }
        f.beam(g.bb, g.seat, t, paint)                                            // seat tube
        f.beam(g.bb, g.headBottom, t * 1.1f, paint)                               // down tube
        f.beam(lerp(g.seat, g.bb, .06f), g.headTop, t, paint)                     // top tube
        for (sz in floatArrayOf(-1.1f, 1.1f)) {
            f.beam(add(g.bb, v(0f, 0f, sz * .6f)), add(g.rear, v(0f, 0f, sz)), t * .7f, paint)   // chain stays
            f.beam(add(stays, v(0f, 0f, sz * .5f)), add(g.rear, v(0f, 0f, sz)), t * .7f, paint)  // seat stays
        }
        f.beam(g.headBottom, g.headTop, t * 1.4f, paint)                          // head tube
        if (s == BikeStyle.CRUISER) {
            // a cruiser's second, curved top tube (a tank's line) and its cream panel
            f.beam(lerp(g.seat, g.bb, .45f), lerp(g.headTop, g.headBottom, .7f), t, accent)
        }
        val saddleTop = add(g.seat, v(-.4f, 2.2f, 0f))
        f.beam(g.seat, saddleTop, .55f, chrome)                                   // seat post
        val sw = if (s == BikeStyle.CRUISER) 1.6f else if (s == BikeStyle.BMX) .95f else 1.1f
        val sl = if (s == BikeStyle.CRUISER) 2.6f else 2.8f
        f.box(saddleTop[0] - sl, saddleTop[1], -sw, saddleTop[0] + sl * .9f, saddleTop[1] + 1.1f, sw) { side, _, _ ->
            if (side == MeshBuilder.Side.BOTTOM) null else if (side == MeshBuilder.Side.TOP) swatch(SADDLE_L) else swatch(SADDLE) }
        // the chain ring (right) and the chain to the rear cog
        // (a cap's picture is laid at the buildings' texel density: 6 texels across the ring)
        val ring = PixImg(6, 6).also { p ->
            for (y in 0 until 6) for (x in 0 until 6) {
                val r = hypot(x - 2.5f, y - 2.5f) / 3f
                p[x, y] = when { r > 1.05f -> 0; r > .75f -> if ((x + y) % 2 == 0) CHROME_D else OUTLINE; r > .4f -> CHROME; else -> CHROME_D }
            }
        }
        val ringR = if (s == BikeStyle.BMX) 2f else 2.4f
        f.prism(add(g.bb, v(0f, 0f, 1.25f)), v(0f, 0f, 1f), v(0f, 1f, 0f), ringR, ringR, .25f, 12, null, swatch(CHROME_D), capFar = ring, capNear = ring)
        val cogR = 1.1f
        val chain = tube(c(0x55525C), c(0x6E6A74), c(0x3A3740))
        f.beam(add(g.bb, v(0f, ringR, 1.35f)), add(g.rear, v(0f, cogR, 1.35f)), .3f, chain)
        f.beam(add(g.bb, v(0f, -ringR, 1.35f)), add(g.rear, v(0f, -cogR, 1.35f)), .3f, chain)
        // mudguards (the cruiser's and the hybrid's) as arcs over the wheels
        if (s == BikeStyle.CRUISER || s == BikeStyle.HYBRID) {
            val guard = if (s == BikeStyle.CRUISER) accent else black
            val gr = g.r + .9f
            // (the rear's from behind the seat stays round to behind; the front's over the top)
            f.prism(add(g.rear, v(0f, 0f, -1.05f)), v(0f, 0f, 1f), v(0f, 1f, 0f), gr, gr, 2.1f, 10, null, guard, a0 = -1.9f, a1 = .5f)
            f.prism(add(g.front, v(0f, 0f, -1.05f)), v(0f, 0f, 1f), v(0f, 1f, 0f), gr, gr, 2.1f, 8, null, guard, a0 = -.4f, a1 = 1.5f)
        }
        if (s == BikeStyle.CRUISER) {
            // the chain guard: a cream plate over the chain's top
            f.box(g.rear[0] + 2f, g.bb[1] + .4f, 1.5f, g.bb[0] + 1.2f, g.bb[1] + ringR + .9f, 1.75f) { side, _, _ -> if (side == MeshBuilder.Side.BOTTOM) null else swatch(lk.accent) }
        }
        if (s == BikeStyle.HYBRID) {
            // a rear rack: two rails from over the axle to the seat stays, a deck
            val deckY = g.r + 6.4f
            for (sz in floatArrayOf(-1.4f, 1.4f)) {
                f.beam(add(g.rear, v(0f, 0f, sz)), v(g.rear[0] - .5f, deckY, sz), .35f, black)
                f.beam(v(g.rear[0] - 2.5f, deckY, sz), v(g.seat[0] - 1.6f, deckY, sz), .35f, black)
            }
            f.box(g.rear[0] - 2.6f, deckY - .2f, -1.5f, g.seat[0] - 1.8f, deckY + .2f, 1.5f) { side, _, _ -> if (side == MeshBuilder.Side.BOTTOM) null else swatch(c(0x34313B)) }
        }
        if (s == BikeStyle.TEN_SPEED) {
            // a bottle on the down tube
            val mid = lerp(g.bb, g.headBottom, .45f)
            f.prism(add(mid, v(0f, 1f, 0f)), v(.62f, .78f, 0f), v(0f, 0f, 1f), .75f, .75f, 3.4f, 6, null, swatch(c(0xE8E6DE)), capFar = swatch(c(0xD3443A)))
        }
        val frame = f.build()

        // the fork and bar: built in the frame's own coordinates (they turn about the head tube's line)
        val k = MeshBuilder()
        val crown = lerp(g.headBottom, g.headTop, -.06f)
        for (sz in floatArrayOf(-1.15f, 1.15f)) k.beam(add(crown, v(0f, 0f, sz * .8f)), add(g.front, v(0f, 0f, sz)), t * .75f, if (s == BikeStyle.BMX) chrome else paint)
        k.beam(add(crown, v(0f, 0f, -1.2f)), add(crown, v(0f, 0f, 1.2f)), t, paint)
        val stemTop = add(g.headTop, v(-.3f, if (s == BikeStyle.BMX) 1.2f else 1.6f, 0f))
        k.beam(g.headTop, stemTop, .6f, chrome)
        val barW = when (s) { BikeStyle.CRUISER -> 5.6f; BikeStyle.TEN_SPEED -> 3.4f; BikeStyle.BMX -> 5.2f; BikeStyle.HYBRID -> 5.4f }
        when (s) {
            BikeStyle.CRUISER -> {
                // swept back to the rider: out, then back and a little down, white grips
                val stemF = add(stemTop, v(.8f, 0f, 0f))
                k.beam(stemTop, stemF, .5f, chrome)
                for (sz in floatArrayOf(-1f, 1f)) {
                    val mid = add(stemF, v(-.4f, .4f, sz * barW * .55f))
                    val end = add(stemTop, v(-4.2f, .2f, sz * barW))
                    k.beam(stemF, mid, .5f, chrome); k.beam(mid, end, .5f, chrome)
                    k.beam(end, add(end, v(-1.6f, -.1f, 0f)), .8f, swatch(lk.accent))
                }
            }
            BikeStyle.TEN_SPEED -> {
                // drop bars: across, forward, down and back, taped
                val stemF = add(stemTop, v(2.2f, -.2f, 0f))
                k.beam(stemTop, stemF, .5f, chrome)
                val tape = tube(lk.accent, c(0xFFFFFF), c(0xC9C4B8))
                k.beam(add(stemF, v(0f, 0f, -barW)), add(stemF, v(0f, 0f, barW)), .55f, tape)
                for (sz in floatArrayOf(-1f, 1f)) {
                    val a = add(stemF, v(0f, 0f, sz * barW)); val b = add(a, v(1.8f, -.6f, 0f)); val c2 = add(b, v(0f, -2.4f, 0f)); val d = add(c2, v(-1.9f, -.5f, 0f))
                    k.beam(a, b, .55f, tape); k.beam(b, c2, .55f, tape); k.beam(c2, d, .55f, tape)
                }
            }
            BikeStyle.BMX -> {
                // the high-rise bar: two uprights, a crossbar, the bar across, a pad on the crossbar
                val base = add(stemTop, v(.6f, 0f, 0f))
                for (sz in floatArrayOf(-1f, 1f)) k.beam(add(base, v(0f, 0f, sz * 1.1f)), add(base, v(-1.3f, 4.6f, sz * 2.4f)), .5f, chrome)
                k.beam(add(base, v(-.7f, 2.4f, -1.8f)), add(base, v(-.7f, 2.4f, 1.8f)), .5f, chrome)
                k.box(base[0] - 1.6f, base[1] + 1.8f, -2.2f, base[0] + .2f, base[1] + 3.1f, 2.2f) { side, _, _ -> if (side == MeshBuilder.Side.BOTTOM) null else swatch(lk.accent) }
                val top = add(base, v(-1.3f, 4.6f, 0f))
                k.beam(add(top, v(0f, 0f, -barW)), add(top, v(0f, 0f, barW)), .55f, chrome)
                for (sz in floatArrayOf(-1f, 1f)) k.beam(add(top, v(0f, 0f, sz * (barW - 1.6f))), add(top, v(0f, 0f, sz * (barW + .2f))), .85f, swatch(GRIP))
            }
            BikeStyle.HYBRID -> {
                val stemF = add(stemTop, v(1.6f, .3f, 0f))
                k.beam(stemTop, stemF, .55f, black)
                k.beam(add(stemF, v(0f, 0f, -barW)), add(stemF, v(0f, 0f, barW)), .5f, black)
                for (sz in floatArrayOf(-1f, 1f)) k.beam(add(stemF, v(0f, 0f, sz * (barW - 1.5f))), add(stemF, v(0f, 0f, sz * (barW + .2f))), .85f, swatch(GRIP))
            }
        }
        // the lamp on the fork crown (it glows at night) and, on the cruiser, a basket over the front wheel
        val lampImg = PixImg(3, 3).also { it.fill(CHROME_D); it[1, 1] = LAMP; it[0, 1] = LAMP; it[1, 0] = LAMP; it.glow(0, 0, 2, 2, 255) }
        val lampAt = if (s == BikeStyle.CRUISER) add(g.headBottom, v(1.6f, 1.8f, 0f)) else add(g.headBottom, v(1f, .2f, 0f))
        k.box(lampAt[0] - .7f, lampAt[1] - .7f, -.7f, lampAt[0] + .9f, lampAt[1] + .7f, .7f) { side, _, _ -> if (side == MeshBuilder.Side.RIGHT) lampImg else if (side == MeshBuilder.Side.BOTTOM) null else swatch(CHROME_D) }
        if (s == BikeStyle.CRUISER) {
            // a wicker basket: woven sides (see-through gaps), on the bar's stem
            val wick = PixImg(8, 5).also { p -> for (y in 0 until 5) for (x in 0 until 8) p[x, y] = if (y == 0) c(0x8A5A30) else if ((x + y) % 2 == 0) c(0xC08A4E) else c(0x9A6A3A) }
            val bx0 = g.headTop[0] + 1.2f; val by0 = g.headTop[1] - 2.6f
            k.box(bx0, by0, -2.8f, bx0 + 5f, by0 + 3.4f, 2.8f) { side, _, _ -> when (side) { MeshBuilder.Side.TOP -> null; MeshBuilder.Side.BOTTOM -> swatch(c(0x7A4E2A)); else -> wick } }
        }
        val fork = k.build()

        // a wheel about its axle (+z): the tread band and a spoked disc each side
        val w = MeshBuilder()
        val tw = lk.tyreW
        val strip = tread(s)
        w.prism(v(0f, 0f, -tw / 2f), v(0f, 0f, 1f), v(0f, 1f, 0f), g.r, g.r, tw, 20, strip)
        val sideImg = disc(s, lk)
        for (sz in floatArrayOf(-1f, 1f)) {
            val z = sz * (tw / 2f + .02f)
            val n = 20
            val p = FloatArray(n * 3); val uv = FloatArray(n * 2)
            for (i in 0 until n) {
                val a = (i * 2.0 * PI / n).toFloat() * (if (sz > 0) 1f else -1f)
                p[i * 3] = cos(a) * g.r; p[i * 3 + 1] = sin(a) * g.r; p[i * 3 + 2] = z
                uv[i * 2] = (.5f + cos(a) * .5f) * sideImg.w; uv[i * 2 + 1] = (.5f - sin(a) * .5f) * sideImg.h
            }
            w.poly(p, uv, sideImg, v(0f, 0f, sz), v(1f, 0f, 0f))
        }
        // (the hub's axle through it)
        w.beam(v(0f, 0f, -tw / 2f - .6f), v(0f, 0f, tw / 2f + .6f), .55f, chrome)
        val wheel = w.build()

        // the cranks about the bottom bracket (+z): the right arm down at a turn of 0, the left up; the axle through
        val cr = MeshBuilder()
        cr.beam(v(0f, 0f, -g.pedalZ + .5f), v(0f, 0f, g.pedalZ - .5f), .6f, chrome)
        cr.beam(v(0f, 0f, g.pedalZ - .6f), v(0f, -g.crank, g.pedalZ - .6f), .55f, chrome)
        cr.beam(v(0f, 0f, -g.pedalZ + .6f), v(0f, g.crank, -g.pedalZ + .6f), .55f, chrome)
        val crank = cr.build()

        // a pedal (one mesh, at either crank's end): a block with a reflector's orange each end
        val pd = MeshBuilder()
        val refl = PixImg(2, 1).also { it[0, 0] = c(0xF08A2A); it[1, 0] = c(0xF08A2A); it.glow(0, 0, 1, 0, 140) }
        pd.box(-1.2f, -.35f, -1.1f, 1.2f, .35f, 1.1f) { side, _, _ -> when (side) { MeshBuilder.Side.LEFT, MeshBuilder.Side.RIGHT -> refl; else -> swatch(c(0x3A3742)) } }
        val pedal = pd.build()
        return Parts(frame, fork, wheel, crank, pedal, g)
    }
}

/**
 * The bike drawn in the town: one entity a part (frame, fork, two wheels, cranks, two pedals), each with its shadow twin,
 * placed every frame from the ride's [BikeState] (where it stands and heads, its lean, the bar's turn, the wheels' and
 * cranks' turns). Made by [TownPeople] for the figure that rides.
 */
internal class TownBike(
    private val engine: Engine,
    private val scene: Scene,
    private val lit: Material,
    private val proxyMat: Material,
    private val textures: StreetTextures,
) {
    private val crisp = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.NEAREST, TextureSampler.WrapMode.CLAMP_TO_EDGE).also { it.anisotropy = 8f }
    private val nearest = TextureSampler(TextureSampler.MinFilter.NEAREST, TextureSampler.MagFilter.NEAREST, TextureSampler.WrapMode.CLAMP_TO_EDGE)

    private class Part(val e: Int, val proxy: Int, val mi: MaterialInstance, val pmi: MaterialInstance, val vb: VertexBuffer, val ib: IndexBuffer, val glows: Boolean)
    private var style: BikeStyle? = null
    private val parts = ArrayList<Part>()
    private var geo: BikeKit.Geometry? = null

    // matrices (column-major), kept: a frame allocates nothing once the style is built
    private val bike = FloatArray(16)
    private val m = FloatArray(16)
    private val m2 = FloatArray(16)
    private val tmp = FloatArray(16)

    private fun make(mesh: PropMesh): Part {
        val packed = PropLayer.Packing.pack(mesh, listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f)))!!
        val vb = TownVertex.buffer(engine, packed.vertices).also { it.setBufferAt(engine, 0, packed.vb) }
        val ib = TownVertex.indices(engine, packed.indices, packed.shortIndices).also { it.setBuffer(engine, packed.ib) }
        val tex = textures.make(packed.tex)
        val mask = packed.mask?.let { textures.make(it) } ?: textures.black
        val b = packed.box
        val box = Box(b[0], b[1], b[2], b[3] + 2f, b[4] + 2f, b[5] + 2f)
        val mi = lit.createInstance().also { mm ->
            mm.setParameter("albedo", tex, crisp); mm.setParameter("mask", mask, nearest)
            mm.setParameter("uvRect", 0f, 0f, 1f, 1f); mm.setParameter("tint", 1f, 1f, 1f, 1f)
            mm.setParameter("glow", 0f, 0f, 0f, 0f); mm.setParameter("glass", 1f, 1f, 1f, 1f)
            mm.setParameter("fade", 1f); mm.setParameter("cell", 2f)
        }
        val e = EntityManager.get().create()
        RenderableManager.Builder(1).boundingBox(box).culling(false).castShadows(false).receiveShadows(true).priority(4)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib).material(0, mi).build(engine, e)
        engine.transformManager.create(e)
        scene.addEntity(e)
        val pmi = proxyMat.createInstance().also { mm -> mm.setParameter("albedo", tex, crisp); mm.setParameter("uvRect", 0f, 0f, 1f, 1f) }
        val p = EntityManager.get().create()
        RenderableManager.Builder(1).boundingBox(box).culling(false).castShadows(true).receiveShadows(false)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib).material(0, pmi).build(engine, p)
        engine.transformManager.create(p)
        scene.addEntity(p)
        return Part(e, p, mi, pmi, vb, ib, packed.mask != null)
    }

    private fun build(s: BikeStyle) {
        clear()
        val p = BikeKit.parts(s)
        // frame, fork, front wheel, rear wheel, cranks, right pedal, left pedal
        parts += make(p.frame); parts += make(p.fork); parts += make(p.wheel); parts += make(p.wheel)
        parts += make(p.crank); parts += make(p.pedal); parts += make(p.pedal)
        geo = p.geo
        style = s
    }

    private fun show(on: Boolean) {
        val rm = engine.renderableManager
        for (p in parts) for (e in intArrayOf(p.e, p.proxy)) { val i = rm.getInstance(e); if (i != 0) rm.setLayerMask(i, 0xff, if (on) 1 else 0) }
    }

    /** Hides the bike (nobody rides). */
    fun hide() { if (parts.isNotEmpty()) show(false) }

    /**
     * Stands the bike of [b] at world ([x], [z]) heading ([hx], [hz]) for this frame: [look] the frame's clip-space matrix,
     * [glow] how far the lamp is lit (0 day .. 1 night), [fade] the near side's cut (1 whole). Returns the bike's matrix (its
     * own frame to the world, scaled by [BikeState.SCALE]: the rider's card stands in it).
     */
    fun update(b: BikeState, x: Float, z: Float, hx: Float, hz: Float, look: FloatArray, glow: Float, fade: Float): FloatArray {
        if (style != b.style) build(b.style)
        val g = geo!!
        show(fade > .02f)
        // the bike's frame to the world: its heading, then its lean about its own length (right: its top towards +z)
        val cl = cos(b.lean); val sl = sin(b.lean)
        // columns: X = (hx, 0, hz), Y' = cl * up + sl * Z, Z' = -sl * up + cl * Z, where Z = (-hz, 0, hx)
        val k = BikeState.SCALE
        bike[0] = hx * k; bike[1] = 0f; bike[2] = hz * k; bike[3] = 0f
        bike[4] = -hz * sl * k; bike[5] = cl * k; bike[6] = hx * sl * k; bike[7] = 0f
        bike[8] = -hz * cl * k; bike[9] = -sl * k; bike[10] = hx * cl * k; bike[11] = 0f
        bike[12] = x; bike[13] = 0f; bike[14] = z; bike[15] = 1f
        set(parts[0], bike)
        // the fork and bar: turned about the head tube's line by the bar's turn
        val ax = g.headTop[0] - g.headBottom[0]; val ay = g.headTop[1] - g.headBottom[1]
        val al = hypot(ax, ay)
        rotAbout(tmp, g.headBottom[0], g.headBottom[1], 0f, ax / al, ay / al, 0f, b.steer)
        mul(bike, tmp, m)
        set(parts[1], m)
        // the front wheel turns with the fork, about its axle; the rear about its own
        spinAt(tmp, g.front[0], g.front[1], -b.wheel)
        mul(m, tmp, m2)
        set(parts[2], m2)
        spinAt(tmp, g.rear[0], g.rear[1], -b.wheel)
        mul(bike, tmp, m2)
        set(parts[3], m2)
        // the cranks, and the pedals at their ends kept level
        spinAt(tmp, g.bb[0], g.bb[1], -b.crank)
        mul(bike, tmp, m2)
        set(parts[4], m2)
        val ca = -b.crank
        for (side in 0..1) {
            val sgn = if (side == 0) 1f else -1f
            // (the right arm points down at a turn of 0: (0, -L) turned by ca)
            val px = g.bb[0] + sgn * sin(ca) * g.crank
            val py = g.bb[1] - sgn * cos(ca) * g.crank
            translate(tmp, px, py, sgn * (g.pedalZ + .6f))
            mul(bike, tmp, m2)
            set(parts[5 + side], m2)
        }
        for (p in parts) {
            p.mi.setParameter("look", MaterialInstance.FloatElement.MAT4, look, 0, 1)
            p.mi.setParameter("fade", fade)
            if (p.glows) p.mi.setParameter("glow", 1f, .92f, .7f, glow)
        }
        return bike
    }

    /** The bike's geometry (the rider sits by it). */
    val geometry: BikeKit.Geometry? get() = geo

    private fun set(p: Part, mat: FloatArray) {
        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(p.e), mat)
        tm.setTransform(tm.getInstance(p.proxy), mat)
    }

    private fun clear() {
        val em = EntityManager.get()
        for (p in parts) {
            for (e in intArrayOf(p.e, p.proxy)) { scene.remove(e); engine.destroyEntity(e); em.destroy(e) }
            engine.destroyMaterialInstance(p.mi); engine.destroyMaterialInstance(p.pmi)
            engine.destroyVertexBuffer(p.vb); engine.destroyIndexBuffer(p.ib)
        }
        parts.clear()
        style = null
    }

    fun destroy() = clear()

    companion object {
        /** out = a * b (4 x 4, column-major). */
        fun mul(a: FloatArray, b: FloatArray, out: FloatArray) {
            for (c in 0 until 4) for (r in 0 until 4) {
                var s = 0f
                for (k in 0 until 4) s += a[k * 4 + r] * b[c * 4 + k]
                out[c * 4 + r] = s
            }
        }

        fun translate(out: FloatArray, x: Float, y: Float, z: Float) {
            java.util.Arrays.fill(out, 0f)
            out[0] = 1f; out[5] = 1f; out[10] = 1f; out[15] = 1f
            out[12] = x; out[13] = y; out[14] = z
        }

        /** A part made about its own axle (+z at the origin) set at ([x], [y], 0) and turned by [a] (negative rolls forward, +x). */
        fun spinAt(out: FloatArray, x: Float, y: Float, a: Float) {
            val c = cos(a); val s = sin(a)
            java.util.Arrays.fill(out, 0f)
            out[0] = c; out[1] = s; out[4] = -s; out[5] = c; out[10] = 1f; out[15] = 1f
            out[12] = x; out[13] = y
        }

        /** A turn by [a] about the unit axis ([ux], [uy], [uz]) through ([px], [py], [pz]) (Rodrigues). */
        fun rotAbout(out: FloatArray, px: Float, py: Float, pz: Float, ux: Float, uy: Float, uz: Float, a: Float) {
            val c = cos(a); val s = sin(a); val t = 1f - c
            val r00 = t * ux * ux + c; val r01 = t * ux * uy - s * uz; val r02 = t * ux * uz + s * uy
            val r10 = t * ux * uy + s * uz; val r11 = t * uy * uy + c; val r12 = t * uy * uz - s * ux
            val r20 = t * ux * uz - s * uy; val r21 = t * uy * uz + s * ux; val r22 = t * uz * uz + c
            out[0] = r00; out[1] = r10; out[2] = r20; out[3] = 0f
            out[4] = r01; out[5] = r11; out[6] = r21; out[7] = 0f
            out[8] = r02; out[9] = r12; out[10] = r22; out[11] = 0f
            out[12] = px - (r00 * px + r01 * py + r02 * pz)
            out[13] = py - (r10 * px + r11 * py + r12 * pz)
            out[14] = pz - (r20 * px + r21 * py + r22 * pz)
            out[15] = 1f
        }
    }
}
