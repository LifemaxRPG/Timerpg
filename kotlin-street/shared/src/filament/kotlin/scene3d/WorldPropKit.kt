package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.lifemax.rpg.feature.habits.domain.WorldCountry
import kotlin.math.hypot


/**
 * The open country's things by the roads and fields (docs/WORLD_STREAM.md, step 1b; [WorldProps] streams them): utility
 * poles with their crossarms, insulators and sagging wires, telegraph poles along the railways, post-and-wire and
 * post-and-rail fences, mile markers and billboards. Painted texel by texel in the town's way (pixel pictures at the
 * town's texel on simple real geometry, lit and shadowed by the sun), all in one fixed atlas ([atlas]) so every cell's
 * mesh wears the same picture (one texture on the GPU, packed once).
 *
 * The billboards are the game's own (no real brands), their style by the era: painted boards in a cream frame on wooden
 * legs before 1950, a steel frame after.
 */
internal object WorldPropKit {
    private fun c(rgb: Long) = Paint.c(rgb)

    private fun pic(w: Int, h: Int, f: (PixImg) -> Unit) = PixImg(w, h).also(f)

    /** Weathered pole wood: vertical grain, checks, a darker foot. */
    private val poleWood = pic(6, 48) { p ->
        val r = Paint.Rnd(601)
        for (y in 0 until p.h) for (x in 0 until p.w) p[x, y] = when { x == 0 -> c(0x4A3A2C); x == p.w - 1 -> c(0x6E5A44); else -> if (r.next() < .2f) c(0x5A4836) else c(0x66523E) }
        for (k in 0 until 6) { val x = 1 + r.int(4); val y = r.int(44); for (d in 0..3) p[x, y + d] = c(0x3E3026) }
        for (y in 40 until 48) for (x in 0 until p.w) p[x, y] = c(0x3E3228)
    }
    private val armWood = pic(32, 3) { p -> for (x in 0 until 32) { p[x, 0] = c(0x7A6650); p[x, 1] = c(0x5E4C3A); p[x, 2] = c(0x46382C) } }
    private val glass = pic(2, 3) { p -> p[0, 0] = c(0x9AD2B8); p[1, 0] = c(0x6EAA92); p[0, 1] = c(0x5E9A84); p[1, 1] = c(0x4E8270); p[0, 2] = c(0x3E6A5A); p[1, 2] = c(0x3E6A5A) }
    private val wire = Paint.swatch(c(0x26262C))
    private val postWood = pic(4, 24) { p ->
        for (y in 0 until 24) for (x in 0 until 4) p[x, y] = if (x == 0) c(0x5C5044) else if (x == 3) c(0x8A7C6A) else c(0x766856)
        p[1, 0] = c(0x9A8C78); p[2, 0] = c(0x9A8C78)
    }
    private val railWood = pic(48, 3) { p -> val r = Paint.Rnd(611); for (x in 0 until 48) { p[x, 0] = c(0x9C8A70); p[x, 1] = if (r.next() < .15f) c(0x6E5E4A) else c(0x84745E); p[x, 2] = c(0x5E5040) } }
    private val white = pic(4, 24) { p -> for (y in 0 until 24) for (x in 0 until 4) p[x, y] = if (x == 0) c(0xB8B8B0) else c(0xECECE4); for (x in 0 until 4) p[x, 2] = c(0xD84A3A) }
    private val milePlate = pic(8, 10) { p ->
        p.fill(c(0x2E7A4A)); p.border(c(0xE8E8E0))
        p.text("M", 2, 2, c(0xF4F4EC))
    }
    private val planks = pic(64, 22) { p ->
        val r = Paint.Rnd(621)
        for (y in 0 until 22) for (x in 0 until 64) p[x, y] = if (y % 5 == 4) c(0x5A5248) else if (r.next() < .1f) c(0x7A7064) else c(0x8A8074)
        for (x in 0 until 64 step 16) for (y in 0 until 22) p[x, y] = c(0x4E463E)
    }
    private val steel = pic(4, 4) { p -> p.fill(c(0x4A4C54)); p[0, 0] = c(0x6A6C74); p[1, 0] = c(0x6A6C74) }

    /** [s] in the pixel font at [scale] texels a font pixel, its top-left at ([x], [y]). */
    private fun big(p: PixImg, s: String, x: Int, y: Int, scale: Int, col: Int, shadow: Int? = null) {
        if (shadow != null) big(p, s, x + scale / 2 + 1, y + scale / 2 + 1, scale, shadow)
        var cx = x
        for (ch in s) {
            val g = PixImg.FONT[ch.uppercaseChar()]
            if (g != null) for (r in 0 until 5) for (k in 0 until 3) if (g[r * 3 + k] == '1') p.rect(cx + k * scale, y + r * scale, cx + k * scale + scale - 1, y + r * scale + scale - 1, col)
            cx += 4 * scale
        }
    }
    private fun bigWidth(s: String, scale: Int) = (s.length * 4 - 1) * scale
    private fun lighter(col: Int): Int {
        fun ch(s: Int): Int = (((col shr s) and 255) * 7 + 255) / 8
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private const val BW = 256
    private const val BH = 88

    /** A board's face: [bg] ground, [lines] of text (each its colour and scale) centred, a picture drawn by [art]. */
    private fun board(bg: Int, frame: Int, lines: List<Triple<String, Int, Int>>, shadow: Int?, art: (PixImg) -> Unit = {}): PixImg = pic(BW, BH) { p ->
        p.fill(bg)
        // (painted: a few lighter streaks of the weather)
        val r = Paint.Rnd(bg)
        val streak = lighter(bg)
        repeat(60) { val x = r.int(BW); val y = r.int(BH); p.hline(x, (x + 3 + r.int(6)).coerceAtMost(BW - 1), y, streak) }
        art(p)
        val total = lines.sumOf { 5 * it.third + 4 } - 4
        var y = (BH - total) / 2
        for ((s, col, sc) in lines) { big(p, s, (BW - bigWidth(s, sc)) / 2, y, sc, col, shadow); y += 5 * sc + 4 }
        for (k in 0 until 3) { p.hline(0, BW - 1, k, frame); p.hline(0, BW - 1, BH - 1 - k, frame); p.vline(k, 0, BH - 1, frame); p.vline(BW - 1 - k, 0, BH - 1, frame) }
    }

    /** The game's own billboards (original names; one is the coast's Dove Beach, step 4's town). */
    private val boards: List<PixImg> = run {
        val cream = c(0xE8DCB8); val ink = c(0x2A2A3A)
        listOf(
            board(c(0xC8382E), cream, listOf(Triple("FIZZ COLA", c(0xFFF6E0), 6), Triple("ICE COLD", c(0xFFE07A), 3)), c(0x7A1E18)) { p ->
                // a bottle at each end
                for (x in listOf(14, BW - 26)) { p.rect(x + 3, 18, x + 8, 30, c(0x5A2A1E)); p.rect(x, 30, x + 11, 70, c(0x6A3424)); p.rect(x + 2, 40, x + 9, 52, c(0xFFF6E0)) }
            },
            board(c(0xF0C83A), ink, listOf(Triple("ACME TIRES", ink, 5), Triple("THEY GRIP", c(0x8A2A1E), 3)), null) { p ->
                p.rect(BW - 60, 14, BW - 14, 74, ink); p.rect(BW - 48, 26, BW - 26, 62, c(0x8A8A90)); p.rect(BW - 42, 34, BW - 32, 54, ink)
            },
            board(c(0x2E5A9A), cream, listOf(Triple("MOTEL", c(0xFFF6E0), 6), Triple("12 MI AHEAD", c(0xF4D84A), 3)), c(0x16305A)) { p ->
                for (k in 0 until 6) p.rect(12 + k * 6, 60 - k * 6, 16 + k * 6, 64 - k * 6, c(0xF4D84A))
            },
            board(c(0xF4F0E4), c(0xC8382E), listOf(Triple("EAT AT", c(0xC8382E), 4), Triple("ROSIE'S", c(0x2A2A3A), 6)), null) { p ->
                p.rect(12, 30, 40, 58, c(0xD8A050)); p.rect(16, 34, 36, 40, c(0x7A3A1E))
            },
            board(c(0x2E7A4A), cream, listOf(Triple("SUNRAY GAS", c(0xFFF6E0), 5), Triple("NEXT EXIT", c(0xF4D84A), 3)), c(0x16402A)) { p ->
                for (k in 0 until 8) p.rect(BW - 40 + k, 20 + k, BW - 18 - k, 22 + k, c(0xF4D84A))
            },
            board(c(0x6ABCDC), c(0xF4F0E4), listOf(Triple("VISIT", c(0xFFFFFF), 3), Triple("DOVE BEACH", c(0x1E3A6A), 6)), null) { p ->
                p.rect(3, 64, BW - 4, BH - 4, c(0xE8D49A)); for (x in 3 until BW - 3 step 9) p.rect(x, 60, x + 5, 63, c(0xFFFFFF))
            },
            board(c(0xD8A050), ink, listOf(Triple("GOLDEN LOAF", ink, 5), Triple("BREAD", c(0xFFF6E0), 4)), null) { p ->
                p.rect(10, 28, 46, 60, c(0xA8682E)); p.rect(14, 24, 42, 30, c(0xC8883E))
            },
            board(c(0x3A3A4A), c(0xF4D84A), listOf(Triple("DRIVE SAFELY", c(0xF4D84A), 4), Triple("ARRIVE ALIVE", c(0xFFFFFF), 3)), null),
        )
    }

    private val atlas: MeshBuilder.FixedAtlas by lazy {
        MeshBuilder.FixedAtlas.pack(listOf(poleWood, armWood, glass, wire, postWood, railWood, white, milePlate, planks, steel) + boards)
    }

    /** [d]'s things as one mesh in the cell's frame (street units, [u] a metre), as they stand in [year]; null for none. */
    fun pack(d: WorldCountry.Scatter, u: Float, year: Int): PropLayer.Packed? {
        val m = MeshBuilder()
        for (p in d.poles) pole(m, p, u)
        for (f in d.fences) fence(m, f, u)
        for (s in d.signs) if (s[5].toInt() == WorldCountry.MILE) mile(m, s, u) else billboard(m, s, u, year)
        if (m.mark == 0) return null
        return PropLayer.Packing.pack(m.build(atlas), IDENTITY, share)
    }

    private val share: MutableMap<IntArray, StreetTextures.Prepared> = java.util.Collections.synchronizedMap(java.util.IdentityHashMap())
    private val IDENTITY = listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f))
    private fun v(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)

    /** A wire from [a] to [b], [t] thick: two crossed ribbons (an upright and a flat one, both sides seen), not a beam's six faces. */
    private fun wire(m: MeshBuilder, a: FloatArray, b: FloatArray, t: Float) {
        val dx = b[0] - a[0]; val dz = b[2] - a[2]
        val l = hypot(dx, dz).coerceAtLeast(1e-4f)
        val sx = -dz / l * t / 2; val sz = dx / l * t / 2; val h = t / 2
        m.quad(v(a[0], a[1] + h, a[2]), v(b[0], b[1] + h, b[2]), v(b[0], b[1] - h, b[2]), v(a[0], a[1] - h, a[2]), wire)
        m.quad(v(a[0] + sx, a[1], a[2] + sz), v(b[0] + sx, b[1], b[2] + sz), v(b[0] - sx, b[1], b[2] - sz), v(a[0] - sx, a[1], a[2] - sz), wire)
    }

    /** A pole (x, y, z, the next's x, y, z or NaN, its height, its kind) with its arms and its wires to the next. */
    private fun pole(m: MeshBuilder, p: FloatArray, u: Float) {
        val x = p[0] * u; val y = p[1] * u; val z = p[2] * u
        val h = p[6] * u
        val telegraph = p[7].toInt() == WorldCountry.TELEGRAPH
        // (the wires' way: to the next pole, or along the line it stands on, the next's way if it has none)
        var dx = 1f; var dz = 0f
        val hasNext = !p[3].isNaN()
        if (hasNext) { val l = hypot(p[3] - p[0], p[5] - p[2]).coerceAtLeast(1e-3f); dx = (p[3] - p[0]) / l; dz = (p[5] - p[2]) / l }
        m.upright(x, z, y - .3f * u, y + h, .14f * u, .11f * u, 6, poleWood)
        val ax = -dz; val az = dx
        val arms = if (telegraph) listOf(h - .35f * u, h - .95f * u) else listOf(h - .55f * u)
        val half = (if (telegraph) .75f else 1.1f) * u
        val spots = ArrayList<FloatArray>()
        for (ay in arms) {
            m.beam(v(x - ax * half, y + ay, z - az * half), v(x + ax * half, y + ay, z + az * half), .1f * u, armWood)
            val offs = if (telegraph) floatArrayOf(-.6f, -.2f, .2f, .6f) else floatArrayOf(-.95f, -.3f, .95f)
            for (o in offs) {
                val ix = x + ax * o * u; val iz = z + az * o * u
                m.solidBox(ix - .035f * u, y + ay + .05f * u, iz - .035f * u, ix + .035f * u, y + ay + .17f * u, iz + .035f * u, glass)
                spots += floatArrayOf(o, ay + .17f * u)
            }
        }
        if (!hasNext) return
        // the wires to the next pole's same insulators, sagging (a parabola through six segments)
        val nx = p[3] * u; val ny = p[4] * u; val nz = p[5] * u
        val span = hypot(nx - x, nz - z)
        val sag = span * .02f
        for ((o, oy) in spots.map { it[0] to it[1] }) {
            val a = v(x + ax * o * u, y + oy, z + az * o * u)
            val b = v(nx + ax * o * u, ny + oy, nz + az * o * u)
            var prev = a
            for (k in 1..6) {
                val t = k / 6f
                val q = v(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t - sag * 4f * t * (1 - t), a[2] + (b[2] - a[2]) * t)
                wire(m, prev, q, .035f * u)
                prev = q
            }
        }
    }

    /** A fence run (x0, y0, z0, x1, y1, z1, kind): posts at both ends, wires or rails between. */
    private fun fence(m: MeshBuilder, f: FloatArray, u: Float) {
        val ax = f[0] * u; val ay = f[1] * u; val az = f[2] * u; val bx = f[3] * u; val by = f[4] * u; val bz = f[5] * u
        val ph = 1.25f * u; val pt = .06f * u
        for ((x, y, z) in listOf(Triple(ax, ay, az), Triple(bx, by, bz))) m.solidBox(x - pt, y - .2f * u, z - pt, x + pt, y + ph, z + pt, postWood)
        if (f[6].toInt() == WorldCountry.RAIL) {
            for (hh in floatArrayOf(.55f, 1.0f)) m.beam(v(ax, ay + hh * u, az), v(bx, by + hh * u, bz), .08f * u, railWood)
        } else {
            for (hh in floatArrayOf(.45f, .8f, 1.15f)) wire(m, v(ax, ay + hh * u, az), v(bx, by + hh * u, bz), .025f * u)
        }
    }

    /** A mile marker (x, y, z, facing x, z, kind, the mile): a white post, a green plate facing the traffic. */
    private fun mile(m: MeshBuilder, s: FloatArray, u: Float) {
        val x = s[0] * u; val y = s[1] * u; val z = s[2] * u
        val t = .04f * u
        m.solidBox(x - t, y - .2f * u, z - t, x + t, y + 1.05f * u, z + t, white)
        val fx = s[3]; val fz = s[4]
        val rx = fz; val rz = -fx
        val w = .16f * u; val top = y + 1.25f * u; val bot = y + .85f * u
        val ox = fx * t * 1.2f; val oz = fz * t * 1.2f
        m.quad(v(x - rx * w + ox, top, z - rz * w + oz), v(x + rx * w + ox, top, z + rz * w + oz), v(x + rx * w + ox, bot, z + rz * w + oz), v(x - rx * w + ox, bot, z - rz * w + oz), milePlate)
        m.quad(v(x + rx * w - ox, top, z + rz * w - oz), v(x - rx * w - ox, top, z - rz * w - oz), v(x - rx * w - ox, bot, z - rz * w - oz), v(x + rx * w - ox, bot, z + rz * w - oz), milePlate)
    }

    /** A billboard (x, y, z, facing x, z, kind, seed): its legs, the board and its back, the frame by the era. */
    private fun billboard(m: MeshBuilder, s: FloatArray, u: Float, year: Int) {
        val x = s[0] * u; val y = s[1] * u; val z = s[2] * u
        val fx = s[3]; val fz = s[4]
        val l = hypot(fx, fz).coerceAtLeast(1e-3f)
        val nx = fx / l; val nz = fz / l
        val rx = nz; val rz = -nx
        val w = 5f * u; val bot = y + 2.6f * u; val top = bot + 3.44f * u
        val legs = if (year < 1950) postWood else steel
        for (k in floatArrayOf(-3.4f, 3.4f)) {
            val lx = x + rx * k * u - nx * .15f * u; val lz = z + rz * k * u - nz * .15f * u
            m.beam(v(lx, y - .3f * u, lz), v(lx, top - .2f * u, lz), .24f * u, legs)
        }
        val img = boards[(s[6].toInt() and 0x7fffffff) % boards.size]
        m.quad(v(x - rx * w, top, z - rz * w), v(x + rx * w, top, z + rz * w), v(x + rx * w, bot, z + rz * w), v(x - rx * w, bot, z - rz * w), img)
        // its back, a hand's breadth behind, of planks
        val bx = -nx * .12f * u; val bz = -nz * .12f * u
        m.quad(v(x + rx * w + bx, top, z + rz * w + bz), v(x - rx * w + bx, top, z - rz * w + bz), v(x - rx * w + bx, bot, z - rz * w + bz), v(x + rx * w + bx, bot, z + rz * w + bz), planks)
        // (its top and sides, so it has a thickness from above and the side)
        m.quad(v(x - rx * w + bx, top, z - rz * w + bz), v(x + rx * w + bx, top, z + rz * w + bz), v(x + rx * w, top, z + rz * w), v(x - rx * w, top, z - rz * w), if (year < 1950) planks else steel)
        if (year >= 1950) m.beam(v(x - rx * w * .9f + nx * .3f * u, bot - .1f * u, z - rz * w * .9f + nz * .3f * u), v(x + rx * w * .9f + nx * .3f * u, bot - .1f * u, z + rz * w * .9f + nz * .3f * u), .08f * u, steel)
    }
}
