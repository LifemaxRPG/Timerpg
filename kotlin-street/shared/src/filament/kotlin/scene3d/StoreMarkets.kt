package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.lifemax.rpg.feature.habits.presentation.scene3d.MeshBuilder.Side
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreKit.Site
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreKit.c
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreKit.tx
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/*
 * The supermarkets by era (see [StoreKit]): the 1950s-60s market, the 1970s-80s supermarket and the 1990s-on big-box
 * superstore. Long walls wear small repeating tiles; what is unique (the storefront glass with the aisles behind it, the
 * signs, the entrance towers) is painted once per bay and shared between bays, so even the superstore's 110 m front
 * costs a few hundred kilobytes of pictures.
 */
internal object StoreMarkets {
    private val v = MeshBuilder.Companion::v

    private val ALU = Ramp(c(0x3A3E4A), c(0x9AA0AC), c(0xB8BEC8), c(0xD0D4DC), c(0xEEF0F4))
    private val CREAM_ENAMEL = Ramp(c(0x6A5E4A), c(0xD8CCAE), c(0xEEE4C8), c(0xF6F0DC), c(0xFFFAEE))
    private val TURQ = Ramp(c(0x0E3A3A), c(0x1E7A7A), c(0x2EA0A0), c(0x4ABCB8), c(0x8AE0D8))
    private val SIGN_RED = c(0xE8302A)
    private val EIFS = Ramp(c(0x5A5444), c(0xC8BCA0), c(0xDCD2B8), c(0xE8E0CA), c(0xF4EEDE))
    private val BLOCK = Ramp(c(0x3A3630), c(0x8A8074), c(0xA0968A), c(0xB4AA9E), c(0xC8C0B4))

    /** The superstore chain's colours (made up), by seed: an accent, a darker one, the sign's letters. */
    private class Brand(val accent: Ramp, val deep: Ramp, val letters: Int, val star: Int)
    private val BRANDS = listOf(
        Brand(Ramp(c(0x0A2A1A), c(0x1A5A3A), c(0x2A7A4E), c(0x3A9A64), c(0x6AC08A)), Ramp(c(0x06140C), c(0x0E2E1E), c(0x16402A), c(0x1E5436), c(0x2E6E4A)), c(0xFFFFFF), c(0xF6C83A)),
        Brand(Ramp(c(0x3A0A0A), c(0x8A1A1A), c(0xB82A26), c(0xD84A3E), c(0xF0806A)), Ramp(c(0x1A1A22), c(0x2A2A34), c(0x3A3A46), c(0x4A4A58), c(0x6A6A7A)), c(0xFFFFFF), c(0xFFFFFF)),
        Brand(Ramp(c(0x0A2A3A), c(0x1A5A6A), c(0x2A808A), c(0x3AA0A8), c(0x7AD0D0)), Ramp(c(0x0A0E2A), c(0x141C44), c(0x1E285A), c(0x2A3674), c(0x4A5698)), c(0xFFF6E0), c(0xF08A2A)),
        Brand(Ramp(c(0x3A1A0A), c(0x9A4A12), c(0xD86A1A), c(0xF08A3A), c(0xFFB46A)), Ramp(c(0x20160E), c(0x3A2A1E), c(0x4E3A2A), c(0x644C38), c(0x84684E)), c(0xFFFFFF), c(0xFFE07A)),
    )

    // ------------------------------------------------------------------ shared fittings

    /** A rolling steel door's picture [w] x [h] units. */
    fun roller(b: StoreB, w: Float, h: Float, r: Ramp = Paint.STEEL): PixImg = b.pic("roll$w,$h,${r.b}") {
        PixImg(tx(w), tx(h)).also { im ->
            im.fill(r.b)
            for (y in 0 until im.h) when (y % 3) { 0 -> im.hline(0, im.w - 1, y, r.d); 1 -> im.hline(0, im.w - 1, y, r.l) }
            im.border(Paint.OUT); im.rect(0, 0, im.w - 1, 2, Paint.IRON.b)
            im.rect(im.w / 2 - 3, im.h - 4, im.w / 2 + 2, im.h - 3, Paint.YELLOW.b)
        }
    }

    /** A steel back door with a little lit EXIT over it, on a wall facing [n]. */
    fun backDoor(b: StoreB, x: Float, z: Float, n: Side, exit: Boolean = true) {
        val door = b.pic("bdoor2") { PixImg(tx(22f), tx(46f)).also { im -> im.fill(Paint.GREY.b); im.border(Paint.OUT); for (y in 2 until im.h - 2) im[1, y] = Paint.GREY.l; im.hline(3, im.w - 4, im.h / 2, Paint.STEEL.h) } }
        CinemaKit.face(b.det(), door, x, 23f, z, 22f, 46f, n)
        if (exit) {
            val ex = b.pic("exit") { PixImg(17, 7).also { im -> im.fill(c(0x1E8A3A)); im.border(c(0x0E4A1E)); im.text("EXIT", 1, 1, c(0xE8FFE8)); CinemaKit.glowAll(im) } }
            CinemaKit.face(b.sign(), ex, x, 54f, z + (if (n == Side.FRONT) .3f else -.3f), 14f, 5.8f, n)
        }
    }

    /** A wall pack lamp on a wall facing [n] at ([x], [y], [z]) throwing [warm] or cool light out. */
    fun wallPack(b: StoreB, x: Float, y: Float, z: Float, n: Side, sodium: Boolean, security: Boolean = false) {
        val lens = b.pic("pack$sodium") { PixImg(6, 4).also { im -> im.fill(if (sodium) c(0xFFC870) else c(0xF0F4FF)); im.border(Paint.IRON.b); CinemaKit.glowAll(im) } }
        val out = if (n == Side.BACK) -1f else 1f
        b.sign(if (security) "sec" else "lamps").box(x - 5f, y - 4f, min(z, z + out * 6f), x + 5f, y + 2f, max(z, z + out * 6f)) { s, w, h -> if (s == Side.BOTTOM) lens else b.swatch(Paint.IRON.l) }
        (if (security) b.secLights else b.lights) += if (sodium) CinemaLight(x, y - 6f, z + out * 14f, 1f, .66f, .3f, 18000f, 150f, down = true, cone = 1.3f) else CinemaLight(x, y - 6f, z + out * 14f, .92f, .95f, 1f, 18000f, 150f, down = true, cone = 1.3f)
    }

    /** Loading docks along a back wall at [z] (facing -z): [n] doors from [x0] to [x1], dock height 44, seals, bumpers, lights. */
    fun docks(b: StoreB, x0: Float, x1: Float, z: Float, n: Int, sodium: Boolean, seals: Boolean = true) {
        val m = b.det("docks")
        val door = roller(b, 60f, 72f)
        val seal = b.pic("seal") { PixImg(tx(84f), tx(96f)).also { im -> im.fill(c(0x1E1E24)); for (y in 0 until im.h step 4) im.hline(0, im.w - 1, y, c(0x2E2E36)); im.rect(10, 10, im.w - 11, im.h - 1, 0); im.border(c(0x0E0E12)) } }
        for (k in 0 until n) {
            val x = x0 + (x1 - x0) * (k + .5f) / n
            CinemaKit.face(m, door, x, 44f + 36f, z - .3f, 60f, 72f, Side.BACK)
            if (seals) CinemaKit.face(m, seal, x, 44f + 40f, z - 2.2f, 84f, 96f, Side.BACK)
            // the leveler's lip and two bumpers, a yellow stripe on the dock face
            b.box(m, x - 32f, 0f, z - 3f, x + 32f, 44f, z, Paint.CONCRETE, 5)
            for (dx in listOf(-26f, 22f)) b.solid(m, x + dx, 26f, z - 7f, x + dx + 5f, 40f, z - 3f, c(0x1A1A1E))
            b.solid(m, x - 32f, 42f, z - 3.3f, x + 32f, 44f, z - 3f, Paint.YELLOW.b)
            // a dock light on its arm
            m.beam(v(x + 40f, 100f, z), v(x + 46f, 104f, z - 18f), 1.4f, b.swatch(Paint.IRON.l))
            if (k % 2 == 0) wallPack(b, x, 140f, z, Side.BACK, sodium)
        }
    }

    /**
     * A long plain wall dressed so it does not read as one brick plane: pilasters every [pitch] in a darker brick, a
     * concrete base course and a cap band under the coping, downspouts from scuppers at every other pilaster, louvred
     * vents high up in some bays. The wall runs along x (at z = [at], [n] FRONT or BACK) or along z (at x = [at], [n]
     * LEFT or RIGHT) from [a] to [b0]; nothing goes within 40 of the points in [keep] (doors, docks, ladders).
     */
    fun dressWall(b: StoreB, n: Side, at: Float, a: Float, b0: Float, h: Float, seed: Int, keep: List<Float> = emptyList(), pitch: Float = 120f) {
        val m = b.det("wall")
        val out = when (n) { Side.BACK, Side.LEFT -> -1f; else -> 1f }
        val alongX = n == Side.BACK || n == Side.FRONT
        // a box spanning [u0]..[u1] along the wall, [y0]..[y1] up, [d0]..[d1] out from it
        fun box(mm: MeshBuilder, u0: Float, u1: Float, y0: Float, y1: Float, d0: Float, d1: Float, r: Ramp, sd: Int) {
            val o0 = at + out * d0; val o1 = at + out * d1
            if (alongX) b.box(mm, u0, y0, min(o0, o1), u1, y1, max(o0, o1), r, sd) else b.box(mm, min(o0, o1), y0, u0, max(o0, o1), y1, u1, r, sd)
        }
        val pierBrick = b.tile(StoreKit.brick(PIER_BRICK, c(0xB8A088), seed))
        val base = Paint.CONCRETE
        // the base course and the cap band, the whole length
        box(m, a, b0, 0f, 9f, 0f, 1.5f, base, seed + 31)
        box(m, a, b0, h - 16f, h - 12f, 0f, 2f, base, seed + 32)
        val vent = b.pic("louvre") { PixImg(tx(20f), tx(14f)).also { im -> im.fill(Paint.STEEL.b); for (y in 1 until im.h - 1 step 2) im.hline(1, im.w - 2, y, Paint.STEEL.d); im.border(Paint.OUT) } }
        val pipe = Ramp(c(0x3A2E26), c(0x6A5848), c(0x7E6A58), c(0x927C68), c(0xA8927C))
        val len = b0 - a
        val k0 = max(1, (len / pitch).toInt())
        for (k in 1 until k0) {
            val u = a + len * k / k0
            if (keep.any { abs(it - u) < 40f }) continue
            // the pilaster (as tall as the wall, standing 4 out)
            val o0 = at + out * 0f; val o1 = at + out * 4f
            if (alongX) pierBrick.box(u - 9f, 0f, min(o0, o1), u + 9f, h - 16f, max(o0, o1), setOf(Side.LEFT, Side.RIGHT, if (out > 0f) Side.FRONT else Side.BACK))
            else pierBrick.box(min(o0, o1), 0f, u - 9f, max(o0, o1), h - 16f, u + 9f, setOf(Side.FRONT, Side.BACK, if (out > 0f) Side.RIGHT else Side.LEFT))
            if ((k + seed) % 2 == 0) {
                // a downspout from its scupper's collector head, a splash block at its foot
                val du = u + 16f
                box(m, du - 5f, du + 5f, h - 26f, h - 14f, 0f, 7f, pipe, seed + 33)
                box(m, du - 2f, du + 2f, 4f, h - 26f, 2f, 6f, pipe, seed + 34)
                box(m, du - 5f, du + 5f, 0f, 2f, 2f, 16f, base, seed + 35)
            } else if ((k + seed) % 3 == 0) {
                // a louvred vent high in the bay
                val vu = u + pitch * .5f * (len / k0 / pitch)
                if (keep.none { abs(it - vu) < 40f }) {
                    val vy = h - 40f
                    if (alongX) CinemaKit.face(m, vent, vu, vy, at + out * .4f, 20f, 14f, n) else CinemaKit.face(m, vent, at + out * .4f, vy, vu, 20f, 14f, n)
                }
            }
        }
    }

    private val PIER_BRICK = Ramp(c(0x3A2018), c(0x74442E), c(0x8A5238), c(0x9E6244), c(0xB27656))

    /** Rooftop units in a grid over [x0]..[x1] x [zb]..[zf] at roof height [h]; skylights between if [sky]. */
    fun roofPlant(b: StoreB, x0: Float, x1: Float, zb: Float, zf: Float, h: Float, seed: Int, pitchX: Float, pitchZ: Float, sky: Boolean) {
        val m = b.det("roof")
        val rnd = Paint.Rnd(seed)
        val cols = max(1, ((x1 - x0) / pitchX).toInt()); val rows = max(1, ((zf - zb) / pitchZ).toInt())
        val unitSide = b.pic("rtu") { Paint.panel(tx(44f), tx(18f), Paint.STEEL, 3, wear = .5f).also { im -> for (y in 3 until im.h - 3 step 2) im.hline(3, im.w / 2, y, Paint.STEEL.d) } }
        val unitTop = b.pic("rtuT") { Paint.top(tx(44f), tx(28f), Paint.STEEL).also { im -> val cx = im.w * .7f; val cy = im.h / 2f; Pix.disc(im, cx, cy, im.h * .36f, Paint.IRON.b); Pix.ring(im, cx, cy, im.h * .36f, 1f, Paint.OUT); Pix.disc(im, cx, cy, 1.5f, Paint.STEEL.l) } }
        val skyTop = b.pic("sky") { PixImg(tx(24f), tx(24f)).also { im -> im.fill(c(0xB8D0E0)); im.border(ALU.d); Pix.line(im, 2, im.h - 3, im.w - 3, 2, c(0xE8F4FA)); im.rect(0, im.h / 2, im.w - 1, im.h / 2, ALU.b) } }
        for (i in 0 until cols) for (j in 0 until rows) {
            val x = x0 + (x1 - x0) * (i + .5f) / cols + (rnd.next() - .5f) * 20f
            val z = zb + (zf - zb) * (j + .5f) / rows + (rnd.next() - .5f) * 20f
            if ((i + j) % 3 != 2) {
                m.box(x - 22f, h, z - 14f, x + 22f, h + 18f, z + 14f) { s, _, _ -> when (s) { Side.BOTTOM -> null; Side.TOP -> unitTop; else -> unitSide } }
                // its curb and duct
                b.box(m, x - 24f, h - .5f, z - 16f, x + 24f, h + 2f, z + 16f, Paint.STEEL, 2)
            } else if (sky) {
                for (k in 0 until 2) {
                    val sx = x + (k - .5f) * 50f
                    m.box(sx - 12f, h - .5f, z - 12f, sx + 12f, h + 5f, z + 12f) { s, w, hh -> when (s) { Side.BOTTOM -> null; Side.TOP -> skyTop; else -> b.panel(w, hh, ALU, 2) } }
                }
            }
        }
    }

    /** A roof's parapet coping round [x0]..[x1] x [zb]..[zf] at [h] ([t] tall above it) in [r]. */
    fun parapet(b: StoreB, wall: PixImg, x0: Float, x1: Float, zb: Float, zf: Float, h: Float, t: Float, r: Ramp, front: Boolean = true) {
        val tm = b.tile(wall)
        tm.box(x0, h - 1f, zb, x1, h + t, zf, if (front) setOf(Side.LEFT, Side.RIGHT, Side.BACK, Side.FRONT) else setOf(Side.LEFT, Side.RIGHT, Side.BACK))
        // the parapet's inner faces (seen from above)
        val inner = b.tile(wall)
        inner.quad(v(x1 - 2f, h + t, zf - 2f), v(x0 + 2f, h + t, zf - 2f), v(x0 + 2f, h, zf - 2f), v(x1 - 2f, h, zf - 2f))
        inner.quad(v(x0 + 2f, h + t, zb + 2f), v(x1 - 2f, h + t, zb + 2f), v(x1 - 2f, h, zb + 2f), v(x0 + 2f, h, zb + 2f))
        inner.quad(v(x0 + 2f, h + t, zf - 2f), v(x0 + 2f, h + t, zb + 2f), v(x0 + 2f, h, zb + 2f), v(x0 + 2f, h, zf - 2f))
        inner.quad(v(x1 - 2f, h + t, zb + 2f), v(x1 - 2f, h + t, zf - 2f), v(x1 - 2f, h, zf - 2f), v(x1 - 2f, h, zb + 2f))
        val m = b.det("coping")
        if (front) CinemaKit.ledge(m, x0 - 1f, h + t, zf - 2.5f, x1 + 1f, h + t + 2.5f, zf + 1f, r)
        CinemaKit.ledge(m, x0 - 1f, h + t, zb - 1f, x1 + 1f, h + t + 2.5f, zb + 2.5f, r)
        CinemaKit.ledge(m, x0 - 1f, h + t, zb + 2.5f, x0 + 2.5f, h + t + 2.5f, zf - 2.5f, r)
        CinemaKit.ledge(m, x1 - 2.5f, h + t, zb + 2.5f, x1 + 1f, h + t + 2.5f, zf - 2.5f, r)
        b.tile(b.roof).box(x0 + 2f, 0f, zb + 2f, x1 - 2f, h - .4f, zf - 2f, setOf(Side.TOP))
    }

    /** A roof ladder up a back wall at ([x], [z]) to [top]. */
    fun ladder(b: StoreB, x: Float, z: Float, top: Float) {
        val m = b.det("docks")
        val rail = b.swatch(Paint.IRON.l)
        for (dx in listOf(-4.5f, 4.5f)) m.beam(v(x + dx, 60f, z - 1.6f), v(x + dx, top + 6f, z - 1.6f), 1f, rail)
        var y = 63f
        while (y < top) { m.beam(v(x - 4.5f, y, z - 1.6f), v(x + 4.5f, y, z - 1.6f), .7f, rail); y += 6f }
        y = 80f
        while (y < top - 2f) { m.beam(v(x - 6f, y, z - 1.6f), v(x - 6f, y, z - 9f), .6f, rail); m.beam(v(x + 6f, y, z - 1.6f), v(x + 6f, y, z - 9f), .6f, rail); m.beam(v(x - 6f, y, z - 9f), v(x + 6f, y, z - 9f), .6f, rail); y += 14f }
    }

    /** Cut-out channel letters: [s] in [col] on a transparent picture [w] x [h] units, with a darker return behind for depth; glowing. */
    fun channel(b: StoreB, s: String, col: Int, rim: Int, cx: Float, cy: Float, z: Float, w: Float, h: Float, maxScale: Int = 6) {
        b.texts += s
        val face = PixImg(tx(w), tx(h))
        StoreSigns.letters(face, s, 0, 0, face.w - 1, face.h - 1, rim, null, maxScale) { r, hh -> if (r < hh / 4) Pix.mix(col, c(0xFFFFFF), .35f) else col }
        CinemaKit.glowAll(face, 255)
        StoreKit.front(b.sign("letters"), face, cx - w / 2f, cx + w / 2f, cy - h / 2f, cy + h / 2f, z + 2.4f)
    }

    // ------------------------------------------------------------------ the 1950s-60s market

    fun market(b: StoreB, info: StoreInfo): Site {
        val seed = info.seed
        val L = info.lot; val D = info.deep
        val sixties = info.year >= 1960
        val closed = info.state == StoreState.CLOSED
        val reused = info.state == StoreState.REUSED
        val W = (L - 160f).coerceIn(440f, 640f)
        val x0 = -W / 2f; val x1 = W / 2f
        val zf = -min(320f, D * .3f)
        val Db = (D + zf - 120f).coerceIn(400f, 640f)
        val zb = zf - Db
        val H = 120f; val PH = 172f
        val brick = StoreKit.brick(BUFF_BRICK_M, c(0xE8DCC8), seed)
        val wall = b.tile(brick)
        val det = b.det()
        wall.box(x0, 0f, zb, x1, H, zf, setOf(Side.LEFT, Side.RIGHT, Side.BACK))
        parapet(b, brick, x0, x1, zb, zf - 7f, H, 8f, Paint.CONCRETE, front = false)
        // a store become a church: repainted plain (cream, a brown trim), a cross on its sign, over its door and on its
        // roof's small steeple; the glass painted over with a few coloured panes; a low monument sign at the road
        val church = reused && info.reuse == StoreReuse.CHURCH
        // the front's raised sign band over the canopy, in enamel panels (cream, a turquoise band in the sixties)
        val band = if (sixties && !church) TURQ else CREAM_ENAMEL
        val bw = tx(W); val bh = tx(PH - 100f)
        val B = PixImg(bw, bh)
        StoreKit.fillFrom(B, StoreKit.panelTile(band, seed, 40, 40, 20))
        B.rect(0, bh - 8, bw - 1, bh - 4, if (church) CHURCH_TRIM else if (sixties) CREAM_ENAMEL.b else TURQ.b); B.hline(0, bw - 1, bh - 8, Pix.mix(B[0, bh - 9], c(0xFFFFFF), .3f))
        B.rect(0, 0, bw - 1, 3, if (church) CHURCH_TRIM else ALU.l); B.hline(0, bw - 1, 3, if (church) Pix.mix(CHURCH_TRIM, c(0), .3f) else ALU.d)
        val name = info.signName
        b.texts += name
        val longShut = closed && info.yearsClosed >= 2
        // a store shut for years has had its letters taken down: their ghost on the enamel, a row of bolt holes
        val red = if (church) CHURCH_LETTERS else SIGN_RED
        val lettersCol = if (longShut) Pix.mix(band.b, c(0x3A3A30), .18f) else if (closed) Pix.mix(SIGN_RED, band.b, .55f) else red
        // (a church's name in modest painted letters, a third smaller, a gilt cross before it)
        val lx0 = if (church) tx(W * .2f) else 10
        val ly0 = if (church) 6 + bh / 6 else 6; val ly1 = if (church) bh - 11 - bh / 6 else bh - 11
        if (church) {
            val cxp = lx0 - 14; val u = max(1, bh / 24)
            B.rect(cxp - u, ly0 - 2, cxp + u, ly1 + 2, GILT); B.rect(cxp - 4 * u, ly0 + (ly1 - ly0) / 4 - u, cxp + 4 * u, ly0 + (ly1 - ly0) / 4 + u, GILT)
        }
        StoreSigns.letters(B, name, lx0, ly0, bw - 11, ly1, if (closed || church) null else c(0xFFFFFF), if (closed || church) null else band.o, 6) { r, h -> if (longShut) (if (r == h / 2) c(0x2A2A26) else lettersCol) else if (r < h / 4 && !church) Pix.mix(lettersCol, c(0xFFFFFF), .3f) else lettersCol }
        if (!closed) for (i in B.px.indices) if (B.px[i] == red || B.px[i] == GILT || (!church && (B.px[i] == c(0xFFFFFF) || B.px[i] == Pix.mix(SIGN_RED, c(0xFFFFFF), .3f)))) { B.glow(0, 0, 0, 0, 0); B.mask[i] = (255 shl 24) or (255 shl 16) or 255 }
        StoreKit.front(if (closed) det else b.sign("band"), B, x0, x1, 100f, PH, zf)
        b.tile(brick).box(x0, H - 1f, zf - 6f, x1, PH, zf - .02f, setOf(Side.LEFT, Side.RIGHT, Side.BACK))
        CinemaKit.ledge(det, x0 - 1.5f, PH, zf - 7f, x1 + 1.5f, PH + 3f, zf + 1.5f, ALU)
        // the storefront under the canopy: plate glass in aluminium bays, two doors in the middle, specials on the glass
        val sfw = tx(W); val sfh = tx(100f)
        val S = PixImg(sfw, sfh)
        S.fill(ALU.b)
        fun col(x: Float) = ((x - x0) / W * sfw).toInt()
        fun row(y: Float) = ((100f - y) / 100f * sfh).toInt().coerceIn(0, sfh - 1)
        // brick ends, the kickplate in tile, glass bays
        StoreKit.fillFrom(S, brick, 0, 0, col(x0 + 24f), sfh - 1); StoreKit.fillFrom(S, brick, col(x1 - 24f), 0, sfw - 1, sfh - 1)
        val gx0 = x0 + 26f; val gx1 = x1 - 26f
        val bays = max(4, ((gx1 - gx0) / 64f).toInt())
        val bayW = (gx1 - gx0) / bays
        val doorBays = setOf(bays / 2 - 1, bays / 2)
        b.doors += floatArrayOf(gx0 + bays / 2 * bayW, zf)
        val specials = listOf("SALE" to "THIS WEEK", "FRYERS" to "39 LB", "COFFEE" to "2 LB 89", "BREAD" to "2 FOR 25", "PRODUCE" to "FRESH DAILY", "STAMPS" to "DOUBLE")
        for (k in 0 until bays) {
            val a = col(gx0 + k * bayW) + 1; val e = col(gx0 + (k + 1) * bayW) - 1
            S.rect(a - 1, row(100f), e + 1, sfh - 1, ALU.d)
            if (k in doorBays) {
                // a door pair (the magic-eye doors) with its mat, IN / OUT over it
                if (closed) StoreSigns.boarded(S, a + 1, row(80f), e - 1, row(2f), seed + k)
                else if (reused && info.reuse == StoreReuse.CHURCH) StoreSigns.churchDoor(S, a + 1, row(80f), e - 1, row(2f))
                else StoreSigns.inside(S, a + 1, row(80f), e - 1, row(2f), seed + k, 0, (if (reused) info.reuse ?: StoreReuse.CHURCH else null))
                S.vline((a + e) / 2, row(80f), sfh - 1, ALU.b); S.hline(a, e, row(40f), ALU.l)
                S.rect(a, row(92f), e, row(82f), c(0x1A1A22)); if (!closed && !reused) { StoreSigns.small(S, (a + e) / 2, row(90f), if (k == bays / 2 - 1) "IN" else "OUT", c(0x5AF07A)); S.glow(a, row(92f), e, row(82f), 220) }
            } else {
                S.rect(a, row(14f), e, sfh - 1, c(0x6A5A4A)); for (x in a until e step 3) S.vline(x, row(14f), sfh - 1, c(0x5A4A3A))
                if (closed) StoreSigns.boarded(S, a + 1, row(92f), e - 1, row(16f), seed + k * 3) else StoreSigns.inside(S, a + 1, row(92f), e - 1, row(16f), seed + k * 5, 0, (if (reused) info.reuse ?: StoreReuse.CHURCH else null))
                if (!closed && !reused && (k + seed) % 2 == 0) { val sp = specials[(k + seed) % specials.size]; StoreSigns.special(S, a + 3, row(60f), e - a - 6, tx(26f), sp.first, sp.second, if (k % 3 == 0) c(0xF6E83A) else c(0xF6F0E0), c(0xC82A2A)) }
                if (closed && k % 3 == 1) StoreSigns.card(S, a + 3, row(64f), "FOR LEASE", c(0xF0F0F0), c(0x1A1A6A))
            }
            S.vline(a - 1, row(100f), sfh - 1, ALU.l)
        }
        S.hline(0, sfw - 1, sfh - 1, c(0x2A2A30))
        StoreKit.front(if (closed) det else b.sign("glass"), S, x0, x1, 0f, 100f, zf)
        if (closed && info.has("forLease")) forLease(b, 0f, 128f, zf + 1f, min(260f, W * .45f), seed, info.yearsClosed)
        // the canopy: a flat slab out over the walk on slim steel columns, its soffit of downlights, its aluminium fascia
        val cz1 = zf + 48f
        val fascia = b.pic("mfascia$sixties$church") { PixImg(16, tx(9f)).also { im -> im.fill(if (church) CREAM_ENAMEL.l else ALU.l); im.hline(0, 15, 0, if (church) CREAM_ENAMEL.h else ALU.h); im.rect(0, im.h / 2 - 1, 15, im.h / 2, if (church) CHURCH_TRIM else if (sixties) TURQ.b else SIGN_RED); im.hline(0, 15, im.h - 1, if (church) CREAM_ENAMEL.o else ALU.o) } }
        b.tile(fascia).box(x0 - 4f, 92f, zf, x1 + 4f, 101f, cz1, setOf(Side.FRONT, Side.LEFT, Side.RIGHT))
        b.tile(b.roof).box(x0 - 4f, 92f, zf, x1 + 4f, 101f, cz1, setOf(Side.TOP))
        val soffit = b.pic("msoff$closed") { PixImg(tx(64f), tx(48f)).also { im -> im.fill(c(0xE8E4D8)); for (y in 6 until im.h - 3 step 14) for (x in 6 until im.w - 3 step 16) { im.rect(x, y, x + 2, y + 1, c(0xFFFCF0)); if (!closed) im.glow(x, y, x + 2, y + 1, 255) }; im.border(c(0xB8B4A8)) } }
        var sx = x0 - 4f
        while (sx < x1 + 3f) { val e = min(sx + 64f, x1 + 4f); b.lit("soffit").quad(v(sx, 92f, cz1), v(e, 92f, cz1), v(e, 92f, zf), v(sx, 92f, zf), soffit, 0f, 0f, soffit.w * (e - sx) / 64f, soffit.h.toFloat()); sx += 64f }
        var colX = x0 + 30f
        while (colX < x1 - 20f) { det.upright(colX, cz1 - 5f, 4f, 92f, 1.8f, 1.8f, 8, null, swatch = b.swatch(ALU.l)); colX += (W - 60f) / 4f }
        if (!closed) for (k in 0 until 3) b.lights += CinemaLight(x0 + W * (k + .5f) / 3f, 88f, zf + 26f, .95f, .95f, 1f, 30000f, 150f, down = true, cone = 1.4f)
        if (church) {
            val dx = gx0 + bays / 2 * bayW
            // a white cross standing on the canopy over the doors
            b.box(det, dx - 2.5f, 101f, cz1 - 9f, dx + 2.5f, 146f, cz1 - 4f, Paint.WHITE, 11)
            b.box(det, dx - 13f, 126f, cz1 - 9f, dx + 13f, 131f, cz1 - 4f, Paint.WHITE, 11)
            steeple(b, det, dx, zf - 60f, H, seed)
        }
        // the walk under it
        b.box(det, x0 - 6f, 0f, zf, x1 + 6f, 4f, cz1 + 10f, Paint.CONCRETE, 7)
        // the back: a dock with its roller door, steps, a back door, a wall lamp; a ladder to the roof
        docks(b, x0 + W * .18f, x0 + W * .38f, zb, 1, sodium = true, seals = false)
        b.box(det, x0 + W * .1f, 0f, zb - 46f, x0 + W * .46f, 44f, zb, Paint.CONCRETE, 9)
        for (k in 0 until 4) b.box(det, x0 + W * .46f, 0f, zb - 46f + k * 10f, x0 + W * .46f + 12f, 44f - k * 11f, zb - 36f + k * 10f, Paint.CONCRETE, 10)
        backDoor(b, x0 + W * .66f, zb - .3f, Side.BACK)
        wallPack(b, x0 + W * .66f, 74f, zb, Side.BACK, sodium = true)
        ladder(b, x0 + W * .86f, zb - .4f, H + 8f)
        // the sides: a side door, two wall lamps
        backDoor(b, x1 + .3f, zf - Db * .7f, Side.RIGHT)
        dressWall(b, Side.BACK, zb, x0, x1, H, seed, keep = listOf(x0 + W * .22f, x0 + W * .34f, x0 + W * .1f, x0 + W * .46f, x0 + W * .66f, x0 + W * .86f), pitch = 110f)
        dressWall(b, Side.RIGHT, x1, zb, zf - 8f, H, seed + 1, keep = listOf(zf - Db * .7f), pitch = 110f)
        dressWall(b, Side.LEFT, x0, zb, zf - 8f, H, seed + 2, pitch = 110f)
        roofPlant(b, x0 + 40f, x1 - 40f, zb + 40f, zf - 60f, H, seed, 170f, 200f, sky = true)
        return Site(x0, x1, zf, zb - 46f, listOf(floatArrayOf(x0 - 6f, zf, x1 + 6f, cz1 + 10f)))
    }

    /**
     * A small white steeple put up on a store become a church, its foot at ([x], [z]) on the roof at [y]: a clapboard
     * base, an open belfry on four posts, a four-sided spire and a cross.
     */
    private fun steeple(b: StoreB, m: MeshBuilder, x: Float, z: Float, y: Float, seed: Int) {
        val r = 22f
        b.box(m, x - r, y - 2f, z - r, x + r, y + 54f, z + r, StoreKit.WHITEWASH, seed + 21)
        b.box(m, x - r - 2f, y + 54f, z - r - 2f, x + r + 2f, y + 58f, z + r + 2f, CHURCH_TRIM_RAMP, seed + 22)
        val post = b.swatch(StoreKit.WHITEWASH.l)
        for (sx in listOf(-1f, 1f)) for (sz in listOf(-1f, 1f)) m.upright(x + sx * (r - 4f), z + sz * (r - 4f), y + 58f, y + 88f, 2.4f, 2.4f, 6, null, swatch = post)
        // a bell in the belfry's shade
        m.upright(x, z, y + 68f, y + 80f, 6f, 3f, 8, null, swatch = b.swatch(c(0x8A6A3A)))
        b.box(m, x - r - 3f, y + 88f, z - r - 3f, x + r + 3f, y + 92f, z + r + 3f, CHURCH_TRIM_RAMP, seed + 23)
        m.upright(x, z, y + 92f, y + 150f, r * 1.25f, 0.5f, 4, null, swatch = b.swatch(c(0x5A4A44)))
        b.box(m, x - 1.8f, y + 146f, z - 1.8f, x + 1.8f, y + 178f, z + 1.8f, Paint.WHITE, seed + 24)
        b.box(m, x - 9f, y + 164f, z - 1.8f, x + 9f, y + 168f, z + 1.8f, Paint.WHITE, seed + 24)
    }

    /** A church's brown trim and its painted letters, a gilt cross. */
    private val CHURCH_TRIM = c(0x6A3E2A)
    private val CHURCH_TRIM_RAMP = Ramp(c(0x2A1A14), c(0x4E2E20), c(0x6A3E2A), c(0x845238), c(0xA06A4A))
    private val CHURCH_LETTERS = c(0x5A2620)
    private val GILT = c(0xD8B048)

    private val BUFF_BRICK_M = Ramp(c(0x5A4A36), c(0xB89A70), c(0xD0B48A), c(0xE0C8A0), c(0xEED8B8))

    // ------------------------------------------------------------------ the 1970s-80s supermarket

    fun supermarket(b: StoreB, info: StoreInfo): Site {
        val seed = info.seed
        val L = info.lot; val D = info.deep
        val closed = info.state == StoreState.CLOSED
        val reused = info.state == StoreState.REUSED
        val eighties = info.year >= 1980
        val W = (L - 300f).coerceIn(800f, 1120f).coerceAtMost(L - 240f)
        val cx = -L / 2f + 80f + W / 2f
        val x0 = cx - W / 2f; val x1 = cx + W / 2f
        val zf = -min(640f, D * .38f)
        val Db = (D + zf - 200f).coerceIn(600f, 900f)
        val zb = zf - Db
        val H = 140f
        val brick = StoreKit.brick(TAN_M, c(0xC8B49A), seed)
        val det = b.det()
        b.tile(brick).box(x0, 0f, zb, x1, H, zf, setOf(Side.LEFT, Side.RIGHT, Side.BACK, Side.FRONT))
        parapet(b, brick, x0, x1, zb, zf, H, 10f, Paint.CONCRETE)
        // the mansard across the front and round the corners: cedar shakes from the walk's soffit (84) up to 162
        val my0 = 84f; val my1 = 166f; val out = 60f; val inn = 10f
        val shake = b.tile(if (eighties) StoreKit.shingles(Ramp(c(0x2A1A12), c(0x4A3022), c(0x5A3A28), c(0x6A4A34), c(0x84604A)), seed) else StoreKit.shakes(seed))
        val wrap = 140f
        // front slope, the two corner returns, their ends
        shake.quad(v(x0 - out, my1, zf + inn), v(x1 + out, my1, zf + inn), v(x1 + out, my0, zf + out), v(x0 - out, my0, zf + out))
        shake.quad(v(x1 + inn, my1, zf + inn), v(x1 + inn, my1, zf - wrap), v(x1 + out, my0, zf - wrap), v(x1 + out, my0, zf + out))
        shake.quad(v(x0 - inn, my1, zf - wrap), v(x0 - inn, my1, zf + inn), v(x0 - out, my0, zf + out), v(x0 - out, my0, zf - wrap))
        // the returns end in hips: each slope turns the corner back to the wall at the same pitch (no open end), a
        // flashing along the hip, the eave's ledge carried round under it
        val hip = out - inn
        shake.quad(v(x1 + inn, my1, zf - wrap), v(x1 + inn, my1, zf - wrap), v(x1 + inn, my0, zf - wrap - hip), v(x1 + out, my0, zf - wrap))
        shake.quad(v(x0 - inn, my1, zf - wrap), v(x0 - inn, my1, zf - wrap), v(x0 - out, my0, zf - wrap), v(x0 - inn, my0, zf - wrap - hip))
        for (sx in listOf(-1f, 1f)) {
            val ex = if (sx > 0f) x1 else x0
            det.beam(v(ex + sx * inn, my1 + .5f, zf - wrap), v(ex + sx * out, my0 + .5f, zf - wrap), 2f, b.swatch(Paint.BLACK.b))
            det.beam(v(ex + sx * inn, my1 + .5f, zf - wrap), v(ex + sx * inn, my0 + .5f, zf - wrap - hip), 2f, b.swatch(Paint.BLACK.b))
            det.beam(v(ex + sx * inn, my1 + .5f, zf + inn), v(ex + sx * out, my0 + .5f, zf + out), 2f, b.swatch(Paint.BLACK.b))
            // the eave along the return's foot and round the hip to the wall
            CinemaKit.ledge(det, min(ex + sx * (out + 2f), ex + sx * (out - 3f)), my0 - 4f, zf - wrap, max(ex + sx * (out + 2f), ex + sx * (out - 3f)), my0 + 1f, zf + out, Paint.CONCRETE, bottom = true)
            // the walk's soffit under the return, closing it from below
            b.tile(b.roof).box(min(ex, ex + sx * out), my0 - 5f, zf - wrap, max(ex, ex + sx * out), my0 - 4f, zf, setOf(Side.BOTTOM))
        }
        // its flat top (a cap over the slope) and the walk's soffit under it
        b.tile(b.roof).box(x0 - inn, my1 - 1f, zf - wrap, x1 + inn, my1 + 1f, zf + inn, setOf(Side.TOP))
        CinemaKit.ledge(det, x0 - out - 2f, my0 - 4f, zf + out - 2f, x1 + out + 2f, my0 + 1f, zf + out + 2f, Paint.CONCRETE, bottom = true)
        CinemaKit.ledge(det, x0 - inn - 2f, my1, zf + inn - 2f, x1 + inn + 2f, my1 + 3f, zf + inn + 2f, Paint.BLACK)
        val soffit = b.pic("ssoff$closed") { PixImg(tx(80f), tx(out)).also { im -> im.fill(c(0xC8BCA8)); for (y in 0 until im.h step 6) im.hline(0, im.w - 1, y, c(0xB0A490)); for (x in 8 until im.w - 4 step 24) for (y in 8 until im.h - 4 step 24) { im.rect(x, y, x + 3, y + 3, c(0xFFF6E0)); if (!closed) im.glow(x, y, x + 3, y + 3, 255) } } }
        var sx = x0 - out
        while (sx < x1 + out - .5f) { val e = min(sx + 80f, x1 + out); b.lit("soffit").quad(v(sx, my0 - 4f, zf + out), v(e, my0 - 4f, zf + out), v(e, my0 - 4f, zf), v(sx, my0 - 4f, zf), soffit, 0f, 0f, soffit.w * (e - sx) / 80f, soffit.h.toFloat()); sx += 80f }
        // the brick piers holding it up
        var px = x0 - out + 20f
        val piers = ArrayList<Float>()
        while (px < x1 + out - 10f) { piers += px; b.tile(brick).box(px - 7f, 0f, zf + out - 16f, px + 7f, my0 - 4f, zf + out - 2f, setOf(Side.LEFT, Side.RIGHT, Side.FRONT, Side.BACK)); px += (W + out * 2f - 40f) / 12f }
        // the backlit sign box on the mansard (white, the name in red or brown), and a FOOD & DRUG sign at one end
        val sw = min(W * .52f, 560f); val sh = 52f
        val sz = zf + out + 5f
        val signCol = if (eighties) c(0xC82A2A) else c(0x8A3A14)
        val face = PixImg(tx(sw), tx(sh)).also { im ->
            im.fill(c(0xF6F2E6)); im.border(ALU.d); im.hline(1, im.w - 2, 1, ALU.l)
            // shut for years: the backlit faces taken out of the box, a blank grey panel with the name's shadow
            if (closed && info.yearsClosed >= 2) { im.fill(c(0xB8B8B4)); im.border(ALU.d); StoreSigns.letters(im, info.signName, 6, 4, im.w - 7, im.h - 5, null, null, 6) { _, _ -> c(0xAAAAA4) } }
            else StoreSigns.letters(im, info.signName, 6, 4, im.w - 7, im.h - 5, null, null, 6) { r, h -> if (closed) Pix.mix(signCol, c(0xF6F2E6), .6f) else signCol }
            if (!closed) CinemaKit.glowAll(im, 230)
        }
        val signM = if (closed) det else b.sign("box")
        // (the cabinet stands just off the slope, its back and top grey sheet metal on braces down to the shakes: no
        // black box over the roof from behind)
        val cy0 = (my0 + my1) / 2f - sh / 2f - 3f; val cy1 = (my0 + my1) / 2f + sh / 2f + 3f
        signBox(b, det, cx - sw / 2f - 3f, cy0, sz - 12f, cx + sw / 2f + 3f, cy1, sz, ALU)
        signBraces(b, det, cx - sw / 2f, cx + sw / 2f, cy0, cy1, sz - 12f, zf + inn, zf + out, my0, my1)
        CinemaKit.face(signM, face, cx, (my0 + my1) / 2f, sz + .1f, sw, sh, Side.FRONT)
        b.texts += info.signName
        // the two boards either side of the name, fitted between it and the mansard's ends
        val subW = min(200f, W / 2f - sw / 2f - 40f).coerceAtLeast(80f)
        val kx = sw / 2f + 16f + subW / 2f
        for (k in listOf(-1f, 1f)) {
            val word = if (closed && info.yearsClosed >= 2) "" else if (reused) (if (k < 0f) "WELCOME" else when (info.reuse) { StoreReuse.GYM -> "OPEN 24 HRS"; StoreReuse.CHURCH -> "SUNDAY 10 AM"; StoreReuse.THRIFT, StoreReuse.FLEA -> "DONATIONS"; StoreReuse.OUTLET -> "CLEARANCE"; else -> "ALL WELCOME" })
                else if (k < 0f) "BAKERY  DELI" else if (eighties) "FOOD & DRUG" else "MEAT  PRODUCE"
            b.texts += word
            val sub = b.pic("sub$word$closed$subW") { PixImg(tx(subW), tx(24f)).also { im -> im.fill(c(0x3A2A1E)); im.border(c(0x1A120C)); StoreSigns.letters(im, word, 3, 3, im.w - 4, im.h - 4, null, null, 3) { _, _ -> if (closed) c(0x8A7A6A) else c(0xF6E8C0) }; if (!closed) CinemaKit.glowAll(im, 220, c(0x3A2A1E)) } }
            signBox(b, det, cx + k * kx - subW / 2f - 2f, (my0 + my1) / 2f - 14f, sz - 10f, cx + k * kx + subW / 2f + 2f, (my0 + my1) / 2f + 14f, sz - 1f, SIGN_DARK)
            signBraces(b, det, cx + k * kx - subW / 2f, cx + k * kx + subW / 2f, (my0 + my1) / 2f - 14f, (my0 + my1) / 2f + 14f, sz - 10f, zf + inn, zf + out, my0, my1)
            CinemaKit.face(signM, sub, cx + k * kx, (my0 + my1) / 2f, sz - .9f, subW, 24f, Side.FRONT)
        }
        // the storefront under the walk: glass bays between the middle piers (aisles behind, specials on the glass),
        // two entrances with sliding doors, brick to either end with a bench and the machines
        val gx0 = cx - W * .32f; val gx1 = cx + W * .32f
        val nb = max(6, ((gx1 - gx0) / 86f).toInt())
        val bw = (gx1 - gx0) / nb
        val bayPics = (0 until 3).map { k -> b.pic("sbay$k$closed$eighties") {
            PixImg(tx(bw), tx(my0 - 4f)).also { im ->
                im.fill(ALU.b)
                if (closed) StoreSigns.boarded(im, 2, 2, im.w - 3, im.h - 12, seed + k) else StoreSigns.inside(im, 2, 2, im.w - 3, im.h - 12, seed + k * 17, 1, (if (reused) info.reuse ?: StoreReuse.CHURCH else null))
                im.rect(0, im.h - 11, im.w - 1, im.h - 1, Paint.CONCRETE.b); im.hline(0, im.w - 1, im.h - 11, ALU.h)
                im.vline(0, 0, im.h - 1, ALU.d); im.vline(im.w - 1, 0, im.h - 1, ALU.l); im.hline(0, im.w - 1, 0, ALU.d)
                if (!closed && !reused && k != 1) StoreSigns.special(im, 4, im.h / 3, im.w - 8, tx(24f), listOf("SAVE", "SALE", "SPECIALS")[k], listOf("EVERY DAY", "THIS WEEK", "SEE INSIDE")[k], if (k == 0) c(0xF6E83A) else c(0xF6F0E0), c(0xC82A2A))
                if (closed && k == 1) StoreSigns.card(im, 4, im.h / 3, "FOR LEASE", c(0xF0F0F0), c(0x1A1A6A))
            }
        } }
        val doorPic = { inOut: String -> b.pic("sdoorP$inOut$closed") {
            PixImg(tx(bw), tx(my0 - 4f)).also { im ->
                im.fill(ALU.b)
                if (closed) StoreSigns.boarded(im, 2, 12, im.w - 3, im.h - 2, seed + 9)
                else if (reused && info.reuse == StoreReuse.CHURCH) StoreSigns.churchDoor(im, 2, 12, im.w - 3, im.h - 2)
                else StoreSigns.inside(im, 2, 12, im.w - 3, im.h - 2, seed + inOut.length * 7, 1, (if (reused) info.reuse ?: StoreReuse.CHURCH else null))
                im.vline(im.w / 2, 12, im.h - 1, ALU.d); im.vline(im.w / 2 + 1, 12, im.h - 1, ALU.l)
                im.rect(0, 0, im.w - 1, 10, c(0x1A1A22)); if (!closed && !reused) { StoreSigns.small(im, im.w / 2, 3, inOut, c(0x5AF07A)); im.glow(1, 1, im.w - 2, 9, 230) }
                im.vline(0, 0, im.h - 1, ALU.d); im.vline(im.w - 1, 0, im.h - 1, ALU.l)
            }
        } }
        val lit = b.sign("glass")
        for (k in listOf(1, nb - 2)) b.doors += floatArrayOf(gx0 + (k + .5f) * bw, zf)
        for (k in 0 until nb) {
            val a = gx0 + k * bw
            val pic = when (k) { 1 -> doorPic("IN"); nb - 2 -> doorPic("OUT"); else -> bayPics[(k + seed) % 3] }
            lit.quad(v(a, my0 - 4f, zf + .3f), v(a + bw, my0 - 4f, zf + .3f), v(a + bw, 0f, zf + .3f), v(a, 0f, zf + .3f), pic)
        }
        if (!closed) for (k in 0 until 4) b.lights += CinemaLight(gx0 + (gx1 - gx0) * (k + .5f) / 4f, my0 - 8f, zf + out / 2f, .92f, .94f, 1f, 40000f, 170f, down = true, cone = 1.4f)
        if (closed && info.has("forLease")) forLease(b, cx, 70f, zf + 1.5f, min(300f, (gx1 - gx0) * .4f), seed, info.yearsClosed)
        // the walk and its things (machines, an ice chest, a bench, a phone, carts by the doors)
        b.box(det, x0 - out - 6f, 0f, zf, x1 + out + 6f, 4f, zf + out + 14f, Paint.CONCRETE, 7)
        val walk = b.det("walk")
        if (!closed && !reused) {
            frontThings(b, walk, info, gx0 - 30f, gx1 + 30f, x0 - out + 30f, x1 + out - 30f, zf, 4f, piers)
            StoreGrounds.cartRow(b, walk, gx0 + bw * .3f, zf + 14f, 6, along = true, handle = c(0xC82A2A))
        }
        // the back: three docks under sodium wall packs, a back door, a ladder; the sides' doors
        docks(b, x0 + W * .08f, x0 + W * .4f, zb, 3, sodium = true)
        backDoor(b, x0 + W * .55f, zb - .3f, Side.BACK)
        ladder(b, x0 + W * .7f, zb - .4f, H + 10f)
        wallPack(b, x0 + W * .55f, 74f, zb, Side.BACK, sodium = true)
        backDoor(b, x1 + .3f, zf - Db * .75f, Side.RIGHT); backDoor(b, x0 - .3f, zf - Db * .6f, Side.LEFT)
        for (z in listOf(zf - Db * .3f, zf - Db * .8f)) { wallPack(b, x1 + .5f, 120f, z, Side.RIGHT, true) }
        // the long walls dressed: pilasters, downspouts, vents, base and cap courses (kept clear of the doors and docks)
        dressWall(b, Side.BACK, zb, x0, x1, H, seed, keep = listOf(x0 + W * .08f, x0 + W * .19f, x0 + W * .29f, x0 + W * .4f, x0 + W * .55f, x0 + W * .7f))
        dressWall(b, Side.RIGHT, x1, zb, zf - wrap - hip, H, seed + 1, keep = listOf(zf - Db * .75f, zf - Db * .3f, zf - Db * .8f))
        dressWall(b, Side.LEFT, x0, zb, zf - wrap - hip, H, seed + 2, keep = listOf(zf - Db * .6f))
        // a second exit and a man door by the docks, a gas meter and a electric service on the side
        backDoor(b, x0 + W * .86f, zb - .3f, Side.BACK)
        b.box(det, x0 - 10f, 20f, zf - Db * .45f - 14f, x0, 70f, zf - Db * .45f + 14f, Paint.GREY, 41)
        b.box(det, x0 - 6f, 0f, zf - Db * .45f + 24f, x0, 30f, zf - Db * .45f + 36f, Paint.YELLOW, 42)
        roofPlant(b, x0 + 60f, x1 - 60f, zb + 60f, zf - 80f, H, seed, 210f, 220f, sky = eighties)
        return Site(x0 - out, x1 + out, zf, zb - 4f, listOf(floatArrayOf(x0 - out - 6f, zf, x1 + out + 6f, zf + out + 14f)))
    }

    /** A sign cabinet: its face's frame in [r], its top, sides and back grey sheet metal with seams. */
    private fun signBox(b: StoreB, m: MeshBuilder, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r: Ramp) {
        val sheet = b.pic("signSheet") { Paint.panel(tx(48f), tx(24f), SHEET, 6, wear = .6f).also { im -> for (x in 0 until im.w step 8) im.vline(x, 0, im.h - 1, SHEET.d); for (x in 4 until im.w step 8) im.vline(x, 0, im.h - 1, SHEET.l) } }
        m.box(x0, y0, z0, x1, y1, z1) { s, w, h -> when (s) { Side.FRONT -> b.panel(w, h, r, 3); else -> sheet } }
    }

    /** The braces holding a sign cabinet ([x0]..[x1], [y0]..[y1], its back at [zBack]) off a mansard's slope ([zIn] at [yTop] to [zOut] at [yLow]). */
    private fun signBraces(b: StoreB, m: MeshBuilder, x0: Float, x1: Float, y0: Float, y1: Float, zBack: Float, zIn: Float, zOut: Float, yLow: Float, yTop: Float) {
        val sw = b.swatch(SHEET.d)
        fun slopeZ(y: Float) = zOut + (zIn - zOut) * ((y - yLow) / (yTop - yLow)).coerceIn(0f, 1f)
        val n = max(2, ((x1 - x0) / 120f).toInt() + 1)
        for (k in 0 until n) {
            val x = x0 + 12f + (x1 - x0 - 24f) * k / (n - 1)
            for (y in listOf(y0 + 6f, y1 - 6f)) m.beam(v(x, y, zBack), v(x, y, slopeZ(y) + 1f), 1.6f, sw)
            m.beam(v(x, y1 - 6f, zBack), v(x, y0 + 6f, slopeZ(y0 + 6f) + 1f), 1.2f, sw)
        }
    }

    private val SHEET = Ramp(c(0x3A3E48), c(0x7A808C), c(0x8E94A0), c(0xA2A8B2), c(0xB8BEC6))
    private val SIGN_DARK = Ramp(c(0x1A120C), c(0x2A1E14), c(0x3A2A1E), c(0x4A3828), c(0x5A4634))

    private val TAN_M = Ramp(c(0x4A3424), c(0x9A7454), c(0xB08866), c(0xC49C7A), c(0xD8B494))

    /** The walk's things: drink machines, an ice chest, a bench, a phone, a newspaper box, a propane cage (later). */
    private fun frontThings(b: StoreB, m: MeshBuilder, info: StoreInfo, gx0: Float, gx1: Float, w0: Float, w1: Float, zf: Float, y: Float, piers: List<Float>) {
        fun clear(x: Float) = piers.none { abs(it - x) < 22f }
        // drink machines (made-up labels), both ends
        val labels = listOf("COLD DRINKS" to c(0xC82A2A), "ICE COLD" to c(0x2A5AA8), "SODA" to c(0x2A8A4A))
        var x = gx0 - 40f
        var k = 0
        while (x > w0 + 30f && k < 2) {
            if (clear(x)) { machine(b, m, x, zf, y, labels[(k + info.seed) % labels.size]); k++ }
            x -= 34f
        }
        // the ice chest and the bench on the other side; a propane cage from the eighties; a payphone
        var ix = gx1 + 50f
        while (!clear(ix) && ix < w1 - 60f) ix += 12f
        val ice = b.pic("ice2") { PixImg(tx(54f), tx(36f)).also { im -> im.fill(c(0xE8ECF0)); im.border(c(0x5A6A7A)); im.hline(1, im.w - 2, 2, c(0xFFFFFF)); StoreSigns.letters(im, "ICE", 3, 6, im.w - 4, im.h - 7, c(0x1A3A7A), null, 3) { _, _ -> c(0x6AB0F0) } } }
        m.box(ix - 27f, y, zf + 2f, ix + 27f, y + 36f, zf + 26f) { s, w, h -> when (s) { Side.BOTTOM -> null; Side.FRONT -> ice; Side.TOP -> b.top(w, h, Paint.WHITE); else -> b.panel(w, h, Paint.WHITE) } }
        var bx = ix + 70f
        while (!clear(bx) && bx < w1) bx += 12f
        if (bx < w1 - 20f) {
            val wood = b.swatch(StoreKit.DECK.l)
            for (dx in listOf(-20f, 20f)) m.beam(v(bx + dx, y, zf + 10f), v(bx + dx, y + 10f, zf + 10f), 2f, b.swatch(Paint.IRON.b))
            m.box(bx - 24f, y + 10f, zf + 4f, bx + 24f, y + 12f, zf + 16f) { s, _, _ -> if (s == Side.BOTTOM) null else wood }
            m.box(bx - 24f, y + 12f, zf + 2f, bx + 24f, y + 24f, zf + 4f) { s, _, _ -> if (s == Side.BOTTOM) null else wood }
        }
        // a payphone on its post
        val px = gx0 - 120f
        if (px > w0 && clear(px)) {
            m.beam(v(px, y, zf + 30f), v(px, y + 50f, zf + 30f), 2.4f, b.swatch(ALU.d))
            val phone = b.pic("phone") { PixImg(tx(14f), tx(24f)).also { im -> im.fill(ALU.l); im.border(ALU.o); im.rect(3, 3, im.w - 4, 8, c(0x2A5AA8)); StoreSigns.small(im, "TEL", im.w / 2, 3, c(0xFFFFFF)); im.rect(3, 11, im.w - 4, im.h - 4, c(0x2A2A30)); for (yy in 12 until im.h - 5 step 2) for (xx in 4 until im.w - 4 step 2) im[xx, yy] = c(0xD8D8E0) } }
            m.box(px - 7f, y + 34f, zf + 31f, px + 7f, y + 58f, zf + 40f) { s, _, _ -> when (s) { Side.BOTTOM -> null; Side.FRONT -> phone; else -> b.swatch(ALU.b) } }
        }
        if (info.year >= 1985) {
            val cage = b.pic("cage") { PixImg(8, 8).also { im -> for (yy in 0 until 8) for (xx in 0 until 8) if (xx % 4 == 0 || yy % 4 == 0) im[xx, yy] = c(0xD8DCE4); im.hline(0, 7, 0, c(0x6C6E85)) } }
            val cx = gx1 + 140f
            if (cx < w1 - 30f && clear(cx)) {
                b.tile(cage).box(cx - 24f, y, zf + 4f, cx + 24f, y + 46f, zf + 28f, setOf(Side.FRONT, Side.LEFT, Side.RIGHT, Side.TOP))
                for (k2 in 0 until 4) m.upright(cx - 15f + k2 * 10f, zf + 16f, y, y + 26f, 4f, 4f, 8, Paint.strip(Paint.stripSize(4f, 26f).first, 22, Paint.WHITE, k2), cap = Paint.cap(4f, Paint.WHITE))
            }
        }
    }

    /** A drink machine against the wall: a lit front with a made-up label, the slot, buttons. */
    private fun machine(b: StoreB, m: MeshBuilder, x: Float, zf: Float, y: Float, label: Pair<String, Int>) {
        val (word, col) = label
        val r = Ramp(Pix.mix(col, c(0x000000), .6f), Pix.mix(col, c(0x000000), .3f), col, Pix.mix(col, c(0xFFFFFF), .2f), Pix.mix(col, c(0xFFFFFF), .5f))
        val front = b.pic("mach$word") { PixImg(tx(30f), tx(66f)).also { im ->
            im.fill(r.b); im.border(r.o)
            im.rect(3, 4, im.w - 4, im.h / 2, c(0xF6F6F0)); StoreSigns.letters(im, word, 4, 6, im.w - 5, im.h / 2 - 2, null, null, 2) { _, _ -> col }
            CinemaKit.glowAll(im, 200); for (yy in im.h / 2 + 1 until im.h) for (xx in 0 until im.w) im.mask[yy * im.w + xx] = 0
            for (k in 0 until 4) im.rect(im.w - 8, im.h / 2 + 4 + k * 5, im.w - 5, im.h / 2 + 6 + k * 5, c(0xE8E8E0))
            im.rect(4, im.h - 12, 14, im.h - 6, c(0x1A1A1E))
        } }
        m.box(x - 15f, y, zf + 1f, x + 15f, y + 66f, zf + 26f) { s, w, h -> when (s) { Side.BOTTOM -> null; Side.FRONT -> null; Side.TOP -> b.top(w, h, r); else -> b.panel(w, h, r, 4) } }
        CinemaKit.face(b.sign("machines"), front, x, y + 33f, zf + 26.1f, 30f, 66f, Side.FRONT)
    }

    // ------------------------------------------------------------------ the 1990s-on superstore

    fun superstore(b: StoreB, info: StoreInfo): Site {
        val seed = info.seed
        val L = info.lot; val D = info.deep
        val closed = info.state == StoreState.CLOSED
        val reused = info.state == StoreState.REUSED
        val brand = BRANDS[(seed / 2) % BRANDS.size]
        val garden = 430f
        val face = facade(info)
        // the 1990s discount store is about 60% as wide and 75% as deep as the supercenter it is extended into
        val Wfull = (L - garden - 180f).coerceIn(1500f, 2300f).coerceAtMost(L - garden - 60f)
        val W = if (face.discount) Wfull * .6f else Wfull
        val x0 = -L / 2f + garden + 30f; val x1 = x0 + W
        val cx = (x0 + x1) / 2f
        val Db = (D * .47f).coerceIn(1100f, 1650f) * (if (face.discount) .75f else 1f)
        val zf = -(D - Db - 300f).coerceAtLeast(900f)
        val zb = zf - Db
        val H = 190f
        val block = StoreKit.blockTile(BLOCK, seed)
        val eifs = StoreKit.panelTile(EIFS, seed, 64, 48, 50)
        val det = b.det()
        // the box: split-face block to 60, smooth panels above, a coloured band, a cornice, the parapet
        b.tile(block).box(x0, 0f, zb, x1, 60f, zf, setOf(Side.LEFT, Side.RIGHT, Side.BACK, Side.FRONT))
        b.tile(eifs).box(x0, 60f, zb, x1, H, zf, setOf(Side.LEFT, Side.RIGHT, Side.BACK, Side.FRONT))
        StoreKit.rim(det, x0, zb, x1, zf, 58f, 62f, 2f, BLOCK)
        val bandR = brand.accent
        CinemaKit.ledge(b.det("band"), x0 - 3f, 150f, zf - 2f, x1 + 3f, 164f, zf + 3f, bandR)
        CinemaKit.ledge(b.det("band"), x0 - 3f, 150f, zb - 3f, x1 + 3f, 164f, zb + 2f, bandR)
        CinemaKit.ledge(b.det("band"), x0 - 3f, 150f, zb + 2f, x0 + 2f, 164f, zf - 2f, bandR)
        CinemaKit.ledge(b.det("band"), x1 - 2f, 150f, zb + 2f, x1 + 3f, 164f, zf - 2f, bandR)
        parapet(b, eifs, x0, x1, zb, zf, H, 12f, EIFS)
        // pilasters along the front, the sides and the back
        val pil = Ramp(EIFS.o, EIFS.d, EIFS.d, EIFS.b, EIFS.l)
        var px = x0 + 100f
        while (px < x1 - 60f) { CinemaKit.ledge(b.det("pil"), px - 10f, 0f, zf, px + 10f, H + 6f, zf + 6f, pil); CinemaKit.ledge(b.det("pil"), px - 10f, 0f, zb - 6f, px + 10f, H + 6f, zb, pil); px += 200f }
        var pz = zf - 150f
        while (pz > zb + 60f) { CinemaKit.ledge(b.det("pil"), x1, 0f, pz - 10f, x1 + 6f, H + 6f, pz + 10f, pil); CinemaKit.ledge(b.det("pil"), x0 - 6f, 0f, pz - 10f, x0, H + 6f, pz + 10f, pil); pz -= 220f }
        // the entrances (one or two by the chain's facade), each a tower with its canopy and glass vestibule
        val towers = face.entrances.map { (k, word) -> cx + k * W to word }
        for ((tcx, _) in towers) b.doors += floatArrayOf(tcx, zf + 40f)
        val TW = 300f; val TD = 40f
        val TH = if (face.gable) 280f else 300f
        val towerWall = b.tile(StoreKit.panelTile(brand.deep, seed + 3, 48, 48, 30))
        for ((tcx, word) in towers) {
            val a = tcx - TW / 2f; val e = tcx + TW / 2f
            towerWall.box(a, 120f, zf - 4f, e, TH, zf + TD, setOf(Side.FRONT, Side.LEFT, Side.RIGHT, Side.TOP))
            b.tile(block).box(a, 0f, zf - 4f, a + 40f, 120f, zf + TD, setOf(Side.FRONT, Side.LEFT, Side.RIGHT))
            b.tile(block).box(e - 40f, 0f, zf - 4f, e, 120f, zf + TD, setOf(Side.FRONT, Side.LEFT, Side.RIGHT))
            b.tile(b.roof).box(a + 2f, 0f, zf - 2f, e - 2f, TH - .3f, zf + TD - 2f, setOf(Side.TOP))
            CinemaKit.ledge(det, a - 4f, TH, zf - 6f, e + 4f, TH + 6f, zf + TD + 4f, brand.accent, bottom = true)
            CinemaKit.ledge(det, a - 2f, 116f, zf + TD - 1f, e + 2f, 124f, zf + TD + 3f, BLOCK, bottom = true)
            // a gabled tower top (a farmhouse front) for some chains, its ends closed
            if (face.gable) {
                val gm = b.tile(StoreKit.panelTile(brand.accent, seed + 5, 32, 32, 10))
                val ry = TH + 6f; val peak = ry + 70f; val gz0 = zf - 6f; val gz1 = zf + TD + 4f
                gm.quad(v(a - 6f, ry, gz1), v(tcx, peak, gz1), v(tcx, peak, gz0), v(a - 6f, ry, gz0))
                gm.quad(v(tcx, peak, gz1), v(e + 6f, ry, gz1), v(e + 6f, ry, gz0), v(tcx, peak, gz0))
                val tri = b.tile(StoreKit.panelTile(brand.deep, seed + 7, 32, 32, 10))
                tri.quad(v(a - 4f, ry, gz1 - .5f), v(tcx, peak - 2f, gz1 - .5f), v(tcx, peak - 2f, gz1 - .5f), v(e + 4f, ry, gz1 - .5f))
                tri.quad(v(e + 4f, ry, gz0 + .5f), v(tcx, peak - 2f, gz0 + .5f), v(tcx, peak - 2f, gz0 + .5f), v(a - 4f, ry, gz0 + .5f))
            }
            // the entrance's word in lit letters (or none), the ghost of it once the store has long shut
            if (word.isNotEmpty()) {
                if (!closed) channel(b, word, c(0xFFFFFF), brand.deep.o, tcx, TH - 50f, zf + TD, TW - 40f, 40f, 4)
                else { val ghost = b.pic("ghost$word") { PixImg(tx(TW - 40f), tx(40f)).also { im -> StoreSigns.letters(im, word, 0, 0, im.w - 1, im.h - 1, null, null, 4) { _, _ -> Pix.mix(brand.deep.b, c(0x000000), .35f) } } }; CinemaKit.face(det, ghost, tcx, TH - 50f, zf + TD + .2f, TW - 40f, 40f, Side.FRONT) }
            }
            // the vestibule: glass, sliding doors, the store lit inside
            val ves = b.pic("ves$closed") { PixImg(tx(TW - 80f), tx(116f)).also { im ->
                im.fill(ALU.b)
                if (closed) { im.rect(2, 2, im.w - 3, im.h - 3, c(0x1A1E26)); StoreSigns.card(im, im.w / 2 - 20, im.h / 2, "CLOSED", c(0xF0F0F0), c(0x8A1A1A)) }
                else StoreSigns.aisles(im, 2, 2, im.w - 3, im.h - 3, seed + 41, 2, 170)
                for (xx in listOf(im.w / 4, im.w / 2, im.w * 3 / 4)) im.vline(xx, 2, im.h - 3, ALU.d)
                im.rect(0, 0, im.w - 1, 10, ALU.l); if (!closed) { StoreSigns.small(im, im.w / 4, 3, "ENTER", c(0x1A6A2A)); StoreSigns.small(im, im.w * 3 / 4, 3, "EXIT", c(0x8A1A1A)) }
            } }
            CinemaKit.face(if (closed) det else b.sign("glass"), ves, tcx, 58f, zf + TD - 2f, TW - 80f, 116f, Side.FRONT)
            // the canopy out over the walk on four columns, lit underneath
            val cz1 = zf + TD + 90f
            b.box(det, a + 10f, 120f, zf + TD, e - 10f, 132f, cz1, brand.accent, 5, bottom = false)
            val csoff = b.pic("csoff$closed") { PixImg(tx(TW - 20f), tx(cz1 - zf - TD)).also { im -> im.fill(c(0xE8E8EC)); for (y in 6 until im.h - 3 step 16) for (x in 8 until im.w - 4 step 20) { im.rect(x, y, x + 3, y + 3, c(0xFFFFFF)); if (!closed) im.glow(x, y, x + 3, y + 3) }; im.border(c(0xB8B8C0)) } }
            CinemaKit.face(b.lit("soffit"), csoff, tcx, 119.8f, (zf + TD + cz1) / 2f, TW - 20f, cz1 - zf - TD, Side.BOTTOM)
            for (k in 0 until 4) det.upright(a + 24f + (TW - 48f) * k / 3f, cz1 - 10f, 4f, 120f, 4f, 4f, 10, Paint.strip(Paint.stripSize(4f, 116f).first, 40, BLOCK, k), cap = null)
            if (!closed) { b.lights += CinemaLight(tcx - 70f, 114f, zf + TD + 46f, .95f, .96f, 1f, 50000f, 200f, down = true, cone = 1.4f); b.lights += CinemaLight(tcx + 70f, 114f, zf + TD + 46f, .95f, .96f, 1f, 50000f, 200f, down = true, cone = 1.4f) }
        }
        // the name sign on a raised parapet clear of the entrances, the chain's emblem beside it, its line under it
        val nW = min(760f, W * .36f)
        val ncx = if (towers.size == 1) (if (towers[0].first < cx) cx + W * .14f else cx - W * .14f) else cx
        b.tile(eifs).box(ncx - nW / 2f - 30f, H, zf - 4f, ncx + nW / 2f + 30f, 280f, zf + 2f, setOf(Side.FRONT, Side.LEFT, Side.RIGHT, Side.BACK))
        CinemaKit.ledge(det, ncx - nW / 2f - 34f, 280f, zf - 6f, ncx + nW / 2f + 34f, 286f, zf + 4f, brand.accent, bottom = true)
        b.tile(b.roof).box(ncx - nW / 2f - 28f, 0f, zf - 4f, ncx + nW / 2f + 28f, 279.8f, zf + 2f, setOf(Side.TOP))
        val nameText = info.signName.uppercase()
        val longShut = closed && info.yearsClosed >= 2
        if (!closed) {
            channel(b, nameText, brand.accent.b, c(0xFFFFFF), ncx + 26f, 226f, zf + 2f, nW - 80f, 70f, 8)
            emblem(b, face.emblem, ncx - nW / 2f + 26f, 230f, zf + 4.5f, 38f, brand)
            if (!reused) channel(b, face.under, brand.deep.b, c(0xFFFFFF), ncx, 172f, zf + 4f, nW * .6f, 22f, 3)
            if (info.has("pharmacy")) channel(b, "PHARMACY", brand.deep.b, c(0xFFFFFF), x0 + W * .1f, 230f - 58f, zf + 4f, 220f, 24f, 3)
        } else if (longShut) {
            // the letters taken down: their ghost on the panels, bolt holes in rows
            val ghost = PixImg(tx(nW - 80f), tx(70f)).also { im ->
                StoreSigns.letters(im, nameText, 0, 0, im.w - 1, im.h - 1, null, null, 8) { _, _ -> Pix.mix(EIFS.b, c(0x5A5A50), .35f) }
                for (y in listOf(im.h / 4, im.h * 3 / 4)) for (x in 2 until im.w - 2 step 7) if (im[x, y] != 0) im[x, y] = c(0x2A2A26)
            }
            StoreKit.front(det, ghost, ncx + 26f - (nW - 80f) / 2f, ncx + 26f + (nW - 80f) / 2f, 191f, 261f, zf + 2.2f)
        } else {
            // just shut: the name still up, unlit
            val dead = PixImg(tx(nW - 80f), tx(70f)).also { im -> StoreSigns.letters(im, nameText, 0, 0, im.w - 1, im.h - 1, null, null, 8) { _, _ -> Pix.mix(brand.accent.b, c(0x3A3A40), .45f) } }
            StoreKit.front(det, dead, ncx + 26f - (nW - 80f) / 2f, ncx + 26f + (nW - 80f) / 2f, 191f, 261f, zf + 2.2f)
        }
        if (closed && info.has("forLease")) forLease(b, ncx, 105f, zf + 7f, min(420f, nW), seed, info.yearsClosed)
        // the garden centre's sign on the left end, the auto care's at the right
        if (!closed) {
            channel(b, "GARDEN CENTER", brand.accent.h, brand.deep.o, x0 + 190f, 172f, zf + 4f, 300f, 26f, 3)
            channel(b, "AUTO CARE", c(0xFFFFFF), brand.deep.o, x1 - 200f, 172f, zf + 4f, 240f, 26f, 3)
        }
        // the auto care: three bays with glazed roll-up doors at the right end
        val bayDoor = b.pic("bay$closed") { PixImg(tx(70f), tx(84f)).also { im -> im.fill(ALU.b); for (yy in 0 until im.h step 12) im.hline(0, im.w - 1, yy, ALU.d); for (r in 0 until 7) for (k in 0 until 4) { val xa = 3 + k * (im.w - 6) / 4; im.rect(xa, r * 12 + 3, xa + (im.w - 6) / 4 - 3, r * 12 + 8, if (closed) c(0x3A3E46) else c(0x8AA8C0)) }; if (!closed) CinemaKit.lamp(im, 2, 2, im.w - 3, im.h - 3, 110); im.border(Paint.OUT) } }
        for (k in 0 until 3) CinemaKit.face(b.lit("bays"), bayDoor, x1 - 300f + k * 100f, 42f, zf + .3f, 70f, 84f, Side.FRONT)
        // the garden centre's own door from the store
        CinemaKit.face(det, roller(b, 90f, 100f, ALU), x0 - .3f, 50f, zf - 200f, 90f, 100f, Side.LEFT)
        // the walk across the front, bollards, cart corrals by the doors, benches, an ice chest, a kiosk, a propane cage
        b.box(det, x0, 0f, zf, x1, 4f, zf + 150f, Paint.CONCRETE, 7)
        val walk = b.det("walk")
        val bollard = Paint.strip(Paint.stripSize(3f, 30f).first, 36, Paint.YELLOW, 3)
        var bxp = x0 + 40f
        while (bxp < x1 - 40f) { if (towers.none { abs(it.first - bxp) < 170f }) walk.upright(bxp, zf + 140f, 4f, 36f, 3f, 3f, 8, bollard, cap = Paint.cap(3f, Paint.YELLOW)); bxp += 60f }
        if (!closed && !reused) {
            for ((tcx, _) in towers) {
                StoreGrounds.cartRow(b, walk, tcx - 260f, zf + 16f, 8, along = true, handle = brand.accent.b)
                StoreGrounds.cartRow(b, walk, tcx + 190f, zf + 16f, 6, along = true, handle = brand.accent.b)
            }
            val ice = b.pic("ice3") { PixImg(tx(70f), tx(40f)).also { im -> im.fill(c(0xF0F4F8)); im.border(c(0x4A6A8A)); StoreSigns.letters(im, "ICE", 4, 6, im.w - 5, im.h - 7, c(0x1A3A7A), null, 3) { _, _ -> c(0x6AB0F0) } } }
            val ix = towers.last().first + 290f
            walk.box(ix - 35f, 4f, zf + 2f, ix + 35f, 44f, zf + 30f) { s, w, h -> when (s) { Side.BOTTOM -> null; Side.FRONT -> ice; Side.TOP -> b.top(w, h, Paint.WHITE); else -> b.panel(w, h, Paint.WHITE) } }
            val cage = b.pic("cage") { PixImg(8, 8).also { im -> for (yy in 0 until 8) for (xx in 0 until 8) if (xx % 4 == 0 || yy % 4 == 0) im[xx, yy] = c(0xD8DCE4); im.hline(0, 7, 0, c(0x6C6E85)) } }
            val cgx = towers.first().first - 300f
            b.tile(cage).box(cgx - 30f, 4f, zf + 4f, cgx + 30f, 54f, zf + 34f, setOf(Side.FRONT, Side.LEFT, Side.RIGHT, Side.TOP))
            for (k in 0 until 5) walk.upright(cgx - 22f + k * 11f, zf + 18f, 4f, 30f, 4.2f, 4.2f, 8, Paint.strip(Paint.stripSize(4.2f, 26f).first, 22, Paint.WHITE, k), cap = Paint.cap(4.2f, Paint.WHITE))
            if (info.year >= 2005) {
                val kiosk = b.pic("kiosk") { PixImg(tx(34f), tx(70f)).also { im -> im.fill(c(0xB82A2A)); im.border(c(0x4A0A0A)); im.rect(4, 6, im.w - 5, 22, c(0x1A1A22)); StoreSigns.small(im, "MOVIES", im.w / 2, 12, c(0xF6E8C0)); im.rect(5, 28, im.w - 6, 44, c(0x6A9AC8)); CinemaKit.glowAll(im, 180, c(0xB82A2A)) } }
                val kx = towers.last().first + 220f
                walk.box(kx - 17f, 4f, zf + 2f, kx + 17f, 74f, zf + 26f) { s, w, h -> if (s == Side.BOTTOM) null else if (s == Side.TOP) b.top(w, h, Paint.RED) else b.panel(w, h, Paint.RED) }
                CinemaKit.face(b.sign("machines"), kiosk, kx, 39f, zf + 26.1f, 34f, 70f, Side.FRONT)
            }
            for (k in listOf(-1f, 1f)) {
                val bx = cx + k * 90f
                val wood = b.swatch(c(0x3A4A3A))
                for (dx in listOf(-22f, 22f)) walk.beam(v(bx + dx, 4f, zf + 30f), v(bx + dx, 14f, zf + 30f), 2f, b.swatch(Paint.IRON.b))
                walk.box(bx - 26f, 14f, zf + 24f, bx + 26f, 16f, zf + 36f) { s, _, _ -> if (s == Side.BOTTOM) null else wood }
                walk.box(bx - 26f, 16f, zf + 22f, bx + 26f, 30f, zf + 24f) { s, _, _ -> if (s == Side.BOTTOM) null else wood }
            }
        }
        // the back: a long row of docks with seals, a back door or two, ladders, compactor (in the grounds)
        val dockN = max(6, ((W * .5f) / 110f).toInt())
        docks(b, x0 + W * .35f, x0 + W * .35f + dockN * 110f, zb, dockN, sodium = info.year < 2010)
        for (k in listOf(.12f, .25f, .9f)) { backDoor(b, x0 + W * k, zb - .3f, Side.BACK); wallPack(b, x0 + W * k, 80f, zb, Side.BACK, info.year < 2010) }
        ladder(b, x0 + W * .18f, zb - .4f, H + 12f)
        for (z in listOf(zf - Db * .3f, zf - Db * .7f)) { backDoor(b, x1 + .3f, z, Side.RIGHT); wallPack(b, x1 + .5f, 120f, z, Side.RIGHT, info.year < 2010) }
        roofPlant(b, x0 + 80f, x1 - 80f, zb + 80f, zf - 100f, H, seed, 220f, 240f, sky = true)
        // the garden centre's fenced yard on the left, its shade roof, plant tables, pallets (StoreGrounds fills it)
        return Site(x0, x1, zf, zb - 6f, listOf(floatArrayOf(x0, zf, x1, zf + 150f), floatArrayOf(x0 - garden, zf - 640f, x0, zf + 30f)))
    }

    /** The chain's emblem (never a real chain's: no spark or sunburst): a leaf, a wave, a barn, or a star in a ring. */
    enum class Emblem { LEAF, WAVE, BARN, RING_STAR }

    /**
     * A made-up chain's storefront, by its seed: how many entrances and what they say, its emblem, the line under its name,
     * the tower tops. Varied so no superstore wears one real chain's trade dress (StoreKitTest).
     */
    class Facade(val entrances: List<Pair<Float, String>>, val emblem: Emblem, val under: String, val gable: Boolean, val discount: Boolean)

    private val ENTRANCE_WORDS = listOf("MARKET" to "HOME", "FOODS" to "GENERAL", "" to "", "FRESH FOODS" to "HOME & MORE", "" to "")
    private val UNDER = listOf("DISCOUNT FOODS", "FAMILY CENTER", "VALUE CENTER", "EVERYDAY SAVINGS", "FOODS & MORE", "FAMILY STORE")

    fun facade(info: StoreInfo): Facade {
        val s = info.seed
        val discount = info.has("discountStore")
        val two = !discount && Math.floorMod(s / 3, 3) != 0
        val (wa, wb) = ENTRANCE_WORDS[Math.floorMod(s / 5, ENTRANCE_WORDS.size)]
        val entrances = if (two) listOf(-.24f to wa, .24f to wb) else listOf((if (Math.floorMod(s, 2) == 0) -.2f else .2f) to "")
        val under = if (discount) "FAMILY DISCOUNT" else UNDER[Math.floorMod(s / 7, UNDER.size)]
        return Facade(entrances, Emblem.entries[Math.floorMod(s / 11, Emblem.entries.size)], under, Math.floorMod(s / 13, 2) == 0, discount)
    }

    /** The chain's [e] emblem, [r] across, lit, cut out on a wall facing +z at ([cx], [cy], [z]). */
    private fun emblem(b: StoreB, e: Emblem, cx: Float, cy: Float, z: Float, r: Float, brand: Brand) {
        val n = tx(r * 2f)
        val im = PixImg(n, n)
        val c0 = n / 2f
        val col = brand.star; val dark = brand.deep.b; val light = c(0xFFFFFF)
        for (y in 0 until n) for (x in 0 until n) {
            val u = (x + .5f - c0) / c0; val w = (y + .5f - c0) / c0
            val d = kotlin.math.sqrt(u * u + w * w)
            val px = when (e) {
                // a leaf: a pointed oval leaning right, its midrib
                Emblem.LEAF -> { val p = (u - w) * .7071f; val q = (u + w) * .7071f; if (p * p / .9f + q * q / .2f < 1f) (if (abs(q) < .06f) dark else col) else 0 }
                // three waves in a disc
                Emblem.WAVE -> if (d < .95f) (if (kotlin.math.sin((u * 3.2f + w * 0f) * 3.14f) * .12f + ((w + 1f) * 3f % 1f) < .32f && w > -.6f) light else col) else 0
                // a barn front: a gambrel roof over a square with a white X door
                Emblem.BARN -> {
                    val roof = w < -.1f && abs(u) < .9f + (w + .1f) * .9f && w > -.95f + abs(u) * .5f
                    val wall = w >= -.1f && w < .9f && abs(u) < .75f
                    if (wall && w > .25f && abs(u) < .35f && (abs(abs(u) - (w - .25f) * .54f) < .07f || abs(u) > .3f || w > .85f)) light else if (roof || wall) col else 0
                }
                // a five-point star inside a ring
                Emblem.RING_STAR -> {
                    val a = kotlin.math.atan2(w, u) + 1.5708f
                    val k = ((a / (2f * 3.14159f / 5f)) % 1f + 1f) % 1f
                    val edge = .38f + .45f * abs(k - .5f) * 2f
                    if (d > .8f && d < .97f) col else if (d < (1f - edge) * .9f + .2f && d < .74f) light else 0
                }
            }
            if (px != 0) im[x, y] = px
        }
        CinemaKit.glowAll(im, 255)
        CinemaKit.face(b.sign("letters"), im, cx, cy, z, r * 2f, r * 2f, Side.FRONT)
    }

    /**
     * A shut big store's banner: a vinyl AVAILABLE / FOR LEASE with a made-up agent and number, [w] wide, centred at
     * ([cx], [cy]) on a wall facing +z; weathered once it has hung for years.
     */
    fun forLease(b: StoreB, cx: Float, cy: Float, z: Float, w: Float, seed: Int, years: Int) {
        val agents = listOf("PRAIRIE REALTY", "OAKES COMMERCIAL", "TRI COUNTY PROPERTIES", "BELL & VARNEY REALTY", "HOMESTEAD LAND CO")
        val agent = agents[Math.floorMod(seed, agents.size)]
        val phone = "555-" + (1000 + Math.floorMod(seed * 37, 9000))
        val h = w * .28f
        val im = b.pic("lease$seed$years$w") { PixImg(tx(w), tx(h)).also { p ->
            p.fill(c(0xF4F4F0)); p.border(c(0x1E3A7A)); p.rect(1, 1, p.w - 2, p.h * 2 / 5, c(0x1E3A7A))
            StoreSigns.letters(p, "AVAILABLE", 3, 2, p.w - 4, p.h * 2 / 5 - 1, null, null, 3) { _, _ -> c(0xF4F4F0) }
            StoreSigns.letters(p, "FOR LEASE", 3, p.h * 2 / 5 + 2, p.w - 4, p.h * 3 / 4, null, null, 3) { _, _ -> c(0xC82A2A) }
            StoreSigns.small(p, p.w / 2, p.h * 3 / 4 + 2, "$agent $phone", c(0x1E3A7A))
            if (years >= 5) { val r = Paint.Rnd(seed); repeat(p.w * p.h / 30) { val x = r.int(p.w); val y = r.int(p.h); p[x, y] = Pix.mix(p[x, y], c(0x8A8470), .45f) }; Pix.line(p, p.w - 12, 0, p.w - 1, 10, 0) }
        } }
        b.texts += "AVAILABLE FOR LEASE $agent"
        CinemaKit.face(b.det("lease"), im, cx, cy, z, w, h, Side.FRONT)
    }
}
