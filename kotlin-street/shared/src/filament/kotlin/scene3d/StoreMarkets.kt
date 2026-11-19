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
    fun wallPack(b: StoreB, x: Float, y: Float, z: Float, n: Side, sodium: Boolean) {
        val lens = b.pic("pack$sodium") { PixImg(6, 4).also { im -> im.fill(if (sodium) c(0xFFC870) else c(0xF0F4FF)); im.border(Paint.IRON.b); CinemaKit.glowAll(im) } }
        val out = if (n == Side.BACK) -1f else 1f
        b.sign("lamps").box(x - 5f, y - 4f, min(z, z + out * 6f), x + 5f, y + 2f, max(z, z + out * 6f)) { s, w, h -> if (s == Side.BOTTOM) lens else b.swatch(Paint.IRON.l) }
        b.lights += if (sodium) CinemaLight(x, y - 6f, z + out * 14f, 1f, .66f, .3f, 18000f, 150f, down = true, cone = 1.3f) else CinemaLight(x, y - 6f, z + out * 14f, .92f, .95f, 1f, 18000f, 150f, down = true, cone = 1.3f)
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
        b.tile(StoreKit.ROOF).box(x0 + 2f, 0f, zb + 2f, x1 - 2f, h - .4f, zf - 2f, setOf(Side.TOP))
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
        val brick = CinemaKit.brickTile(BUFF_BRICK_M, c(0xE8DCC8), seed)
        val wall = b.tile(brick)
        val det = b.det()
        wall.box(x0, 0f, zb, x1, H, zf, setOf(Side.LEFT, Side.RIGHT, Side.BACK))
        parapet(b, brick, x0, x1, zb, zf - 7f, H, 8f, Paint.CONCRETE, front = false)
        // the front's raised sign band over the canopy, in enamel panels (cream, a turquoise band in the sixties)
        val band = if (sixties) TURQ else CREAM_ENAMEL
        val bw = tx(W); val bh = tx(PH - 100f)
        val B = PixImg(bw, bh)
        StoreKit.fillFrom(B, StoreKit.panelTile(band, seed, 40, 40, 20))
        B.rect(0, bh - 8, bw - 1, bh - 4, if (sixties) CREAM_ENAMEL.b else TURQ.b); B.hline(0, bw - 1, bh - 8, Pix.mix(B[0, bh - 9], c(0xFFFFFF), .3f))
        B.rect(0, 0, bw - 1, 3, ALU.l); B.hline(0, bw - 1, 3, ALU.d)
        val name = info.signName
        val lettersCol = if (closed) Pix.mix(SIGN_RED, band.b, .55f) else SIGN_RED
        StoreSigns.letters(B, name, 10, 6, bw - 11, bh - 11, if (closed) null else c(0xFFFFFF), if (closed) null else band.o, 6) { r, h -> if (r < h / 4) Pix.mix(lettersCol, c(0xFFFFFF), .3f) else lettersCol }
        if (!closed) for (i in B.px.indices) if (B.px[i] == SIGN_RED || B.px[i] == c(0xFFFFFF) || B.px[i] == Pix.mix(SIGN_RED, c(0xFFFFFF), .3f)) { B.glow(0, 0, 0, 0, 0); B.mask[i] = (255 shl 24) or (255 shl 16) or 255 }
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
        val specials = listOf("SALE" to "THIS WEEK", "FRYERS" to "39 LB", "COFFEE" to "2 LB 89", "BREAD" to "2 FOR 25", "PRODUCE" to "FRESH DAILY", "STAMPS" to "DOUBLE")
        for (k in 0 until bays) {
            val a = col(gx0 + k * bayW) + 1; val e = col(gx0 + (k + 1) * bayW) - 1
            S.rect(a - 1, row(100f), e + 1, sfh - 1, ALU.d)
            if (k in doorBays) {
                // a door pair (the magic-eye doors) with its mat, IN / OUT over it
                if (closed) StoreSigns.boarded(S, a + 1, row(80f), e - 1, row(2f), seed + k) else StoreSigns.inside(S, a + 1, row(80f), e - 1, row(2f), seed + k, 0, (if (reused) info.reuse ?: StoreReuse.CHURCH else null))
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
        // the canopy: a flat slab out over the walk on slim steel columns, its soffit of downlights, its aluminium fascia
        val cz1 = zf + 48f
        val fascia = b.pic("mfascia$sixties") { PixImg(16, tx(9f)).also { im -> im.fill(ALU.l); im.hline(0, 15, 0, ALU.h); im.rect(0, im.h / 2 - 1, 15, im.h / 2, if (sixties) TURQ.b else SIGN_RED); im.hline(0, 15, im.h - 1, ALU.o) } }
        b.tile(fascia).box(x0 - 4f, 92f, zf, x1 + 4f, 101f, cz1, setOf(Side.FRONT, Side.LEFT, Side.RIGHT))
        b.tile(StoreKit.ROOF).box(x0 - 4f, 92f, zf, x1 + 4f, 101f, cz1, setOf(Side.TOP))
        val soffit = b.pic("msoff$closed") { PixImg(tx(64f), tx(48f)).also { im -> im.fill(c(0xE8E4D8)); for (y in 6 until im.h - 3 step 14) for (x in 6 until im.w - 3 step 16) { im.rect(x, y, x + 2, y + 1, c(0xFFFCF0)); if (!closed) im.glow(x, y, x + 2, y + 1, 255) }; im.border(c(0xB8B4A8)) } }
        var sx = x0 - 4f
        while (sx < x1 + 3f) { val e = min(sx + 64f, x1 + 4f); b.lit("soffit").quad(v(sx, 92f, cz1), v(e, 92f, cz1), v(e, 92f, zf), v(sx, 92f, zf), soffit, 0f, 0f, soffit.w * (e - sx) / 64f, soffit.h.toFloat()); sx += 64f }
        var colX = x0 + 30f
        while (colX < x1 - 20f) { det.upright(colX, cz1 - 5f, 4f, 92f, 1.8f, 1.8f, 8, null, swatch = b.swatch(ALU.l)); colX += (W - 60f) / 4f }
        if (!closed) for (k in 0 until 3) b.lights += CinemaLight(x0 + W * (k + .5f) / 3f, 88f, zf + 26f, .95f, .95f, 1f, 30000f, 150f, down = true, cone = 1.4f)
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
        roofPlant(b, x0 + 40f, x1 - 40f, zb + 40f, zf - 60f, H, seed, 170f, 200f, sky = true)
        return Site(x0, x1, zf, zb - 46f, listOf(floatArrayOf(x0 - 6f, zf, x1 + 6f, cz1 + 10f)))
    }

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
        val brick = CinemaKit.brickTile(TAN_M, c(0xC8B49A), seed)
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
        val fascT = b.tile(brick)
        fascT.quad(v(x1 + inn, my1, zf - wrap), v(x1 + inn, my1, zf - wrap), v(x1 + out, my0, zf - wrap), v(x1 + inn, my0, zf - wrap))
        fascT.quad(v(x0 - inn, my1, zf - wrap), v(x0 - inn, my1, zf - wrap), v(x0 - inn, my0, zf - wrap), v(x0 - out, my0, zf - wrap))
        // its flat top (a cap over the slope) and the walk's soffit under it
        b.tile(StoreKit.ROOF).box(x0 - inn, my1 - 1f, zf - wrap, x1 + inn, my1 + 1f, zf + inn, setOf(Side.TOP))
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
            StoreSigns.letters(im, info.signName, 6, 4, im.w - 7, im.h - 5, null, null, 6) { r, h -> if (closed) Pix.mix(signCol, c(0xF6F2E6), .6f) else signCol }
            if (closed) { val r = Paint.Rnd(seed); repeat(5) { val x = r.int(im.w - 20); im.rect(x, 3 + r.int(im.h - 10), x + 6 + r.int(14), 3 + r.int(im.h - 10) + 3, c(0x3A3A40)) } }
            else CinemaKit.glowAll(im, 230)
        }
        val signM = if (closed) det else b.sign("box")
        b.box(det, cx - sw / 2f - 3f, (my0 + my1) / 2f - sh / 2f - 3f, sz - 34f, cx + sw / 2f + 3f, (my0 + my1) / 2f + sh / 2f + 3f, sz, ALU, 3, bottom = true)
        CinemaKit.face(signM, face, cx, (my0 + my1) / 2f, sz + .1f, sw, sh, Side.FRONT)
        for (k in listOf(-1f, 1f)) {
            val word = if (reused) (if (k < 0f) "WELCOME" else when (info.reuse) { StoreReuse.GYM -> "OPEN 24 HRS"; StoreReuse.CHURCH -> "SUNDAY 10 AM"; StoreReuse.THRIFT, StoreReuse.FLEA -> "DONATIONS"; StoreReuse.OUTLET -> "CLEARANCE"; else -> "ALL WELCOME" })
                else if (k < 0f) "BAKERY  DELI" else if (eighties) "FOOD & DRUG" else "MEAT  PRODUCE"
            val sub = b.pic("sub$word$closed") { PixImg(tx(200f), tx(24f)).also { im -> im.fill(c(0x3A2A1E)); im.border(c(0x1A120C)); StoreSigns.letters(im, word, 3, 3, im.w - 4, im.h - 4, null, null, 3) { _, _ -> if (closed) c(0x8A7A6A) else c(0xF6E8C0) }; if (!closed) CinemaKit.glowAll(im, 220, c(0x3A2A1E)) } }
            b.box(det, cx + k * (sw / 2f + 160f) - 102f, (my0 + my1) / 2f - 14f, sz - 30f, cx + k * (sw / 2f + 160f) + 102f, (my0 + my1) / 2f + 14f, sz - 1f, Paint.BLACK, 3, bottom = true)
            CinemaKit.face(signM, sub, cx + k * (sw / 2f + 160f), (my0 + my1) / 2f, sz - .9f, 200f, 24f, Side.FRONT)
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
                if (closed) StoreSigns.boarded(im, 2, 12, im.w - 3, im.h - 2, seed + 9) else StoreSigns.inside(im, 2, 12, im.w - 3, im.h - 2, seed + inOut.length * 7, 1, (if (reused) info.reuse ?: StoreReuse.CHURCH else null))
                im.vline(im.w / 2, 12, im.h - 1, ALU.d); im.vline(im.w / 2 + 1, 12, im.h - 1, ALU.l)
                im.rect(0, 0, im.w - 1, 10, c(0x1A1A22)); if (!closed && !reused) { StoreSigns.small(im, im.w / 2, 3, inOut, c(0x5AF07A)); im.glow(1, 1, im.w - 2, 9, 230) }
                im.vline(0, 0, im.h - 1, ALU.d); im.vline(im.w - 1, 0, im.h - 1, ALU.l)
            }
        } }
        val lit = b.sign("glass")
        for (k in 0 until nb) {
            val a = gx0 + k * bw
            val pic = when (k) { 1 -> doorPic("IN"); nb - 2 -> doorPic("OUT"); else -> bayPics[(k + seed) % 3] }
            lit.quad(v(a, my0 - 4f, zf + .3f), v(a + bw, my0 - 4f, zf + .3f), v(a + bw, 0f, zf + .3f), v(a, 0f, zf + .3f), pic)
        }
        if (!closed) for (k in 0 until 4) b.lights += CinemaLight(gx0 + (gx1 - gx0) * (k + .5f) / 4f, my0 - 8f, zf + out / 2f, .92f, .94f, 1f, 40000f, 170f, down = true, cone = 1.4f)
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
        roofPlant(b, x0 + 60f, x1 - 60f, zb + 60f, zf - 80f, H, seed, 210f, 220f, sky = eighties)
        return Site(x0 - out, x1 + out, zf, zb - 4f, listOf(floatArrayOf(x0 - out - 6f, zf, x1 + out + 6f, zf + out + 14f)))
    }

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
        val W = (L - garden - 180f).coerceIn(1500f, 2300f).coerceAtMost(L - garden - 60f)
        val x0 = -L / 2f + garden + 30f; val x1 = x0 + W
        val cx = (x0 + x1) / 2f
        val Db = (D * .47f).coerceIn(1100f, 1650f)
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
        // two entrance towers with their canopies and glass vestibules
        val towers = listOf(cx - W * .24f to (if (info.year >= 2000) "HOME  PHARMACY" else "GENERAL MERCHANDISE"), cx + W * .24f to "GROCERY")
        val TH = 300f; val TW = 300f; val TD = 40f
        val towerWall = b.tile(StoreKit.panelTile(brand.deep, seed + 3, 48, 48, 30))
        for ((tcx, word) in towers) {
            val a = tcx - TW / 2f; val e = tcx + TW / 2f
            towerWall.box(a, 120f, zf - 4f, e, TH, zf + TD, setOf(Side.FRONT, Side.LEFT, Side.RIGHT, Side.TOP))
            b.tile(block).box(a, 0f, zf - 4f, a + 40f, 120f, zf + TD, setOf(Side.FRONT, Side.LEFT, Side.RIGHT))
            b.tile(block).box(e - 40f, 0f, zf - 4f, e, 120f, zf + TD, setOf(Side.FRONT, Side.LEFT, Side.RIGHT))
            b.tile(StoreKit.ROOF).box(a + 2f, 0f, zf - 2f, e - 2f, TH - .3f, zf + TD - 2f, setOf(Side.TOP))
            CinemaKit.ledge(det, a - 4f, TH, zf - 6f, e + 4f, TH + 6f, zf + TD + 4f, brand.accent, bottom = true)
            CinemaKit.ledge(det, a - 2f, 116f, zf + TD - 1f, e + 2f, 124f, zf + TD + 3f, BLOCK, bottom = true)
            // the tower's word in lit letters
            if (!closed) channel(b, word, c(0xFFFFFF), brand.deep.o, tcx, 250f, zf + TD, TW - 40f, 40f, 4)
            else { val ghost = b.pic("ghost$word") { PixImg(tx(TW - 40f), tx(40f)).also { im -> StoreSigns.letters(im, word, 0, 0, im.w - 1, im.h - 1, null, null, 4) { _, _ -> Pix.mix(brand.deep.b, c(0x000000), .35f) } } }; CinemaKit.face(det, ghost, tcx, 250f, zf + TD + .2f, TW - 40f, 40f, Side.FRONT) }
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
        // the name sign over the middle on a raised parapet, a sunburst beside it, SUPERCENTER under it
        val nW = min(760f, W * .36f)
        b.tile(eifs).box(cx - nW / 2f - 30f, H, zf - 4f, cx + nW / 2f + 30f, 280f, zf + 2f, setOf(Side.FRONT, Side.LEFT, Side.RIGHT, Side.BACK))
        CinemaKit.ledge(det, cx - nW / 2f - 34f, 280f, zf - 6f, cx + nW / 2f + 34f, 286f, zf + 4f, brand.accent, bottom = true)
        b.tile(StoreKit.ROOF).box(cx - nW / 2f - 28f, 0f, zf - 4f, cx + nW / 2f + 28f, 279.8f, zf + 2f, setOf(Side.TOP))
        val nameText = info.signName.uppercase().replace(" SUPERCENTER", "")
        if (!closed) {
            channel(b, nameText, brand.accent.b, c(0xFFFFFF), cx - 30f, 226f, zf + 2f, nW - 80f, 70f, 8)
            sunburst(b, cx + nW / 2f - 22f, 236f, zf + 4.5f, 34f, brand.star)
            if (!reused) channel(b, if (info.year >= 2000) "SUPERCENTER" else "DISCOUNT CITY", brand.deep.b, c(0xFFFFFF), cx, 172f, zf + 4f, nW * .55f, 22f, 3)
        } else {
            // the letters taken down: their ghost on the panels, bolt holes
            val ghost = PixImg(tx(nW - 80f), tx(70f)).also { im -> StoreSigns.letters(im, nameText, 0, 0, im.w - 1, im.h - 1, null, null, 8) { _, _ -> Pix.mix(EIFS.b, c(0x5A5A50), .35f) } }
            StoreKit.front(det, ghost, cx - 30f - (nW - 80f) / 2f, cx - 30f + (nW - 80f) / 2f, 191f, 261f, zf + 2.2f)
        }
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
            val ix = towers[1].first + 290f
            walk.box(ix - 35f, 4f, zf + 2f, ix + 35f, 44f, zf + 30f) { s, w, h -> when (s) { Side.BOTTOM -> null; Side.FRONT -> ice; Side.TOP -> b.top(w, h, Paint.WHITE); else -> b.panel(w, h, Paint.WHITE) } }
            val cage = b.pic("cage") { PixImg(8, 8).also { im -> for (yy in 0 until 8) for (xx in 0 until 8) if (xx % 4 == 0 || yy % 4 == 0) im[xx, yy] = c(0xD8DCE4); im.hline(0, 7, 0, c(0x6C6E85)) } }
            val cgx = towers[0].first - 300f
            b.tile(cage).box(cgx - 30f, 4f, zf + 4f, cgx + 30f, 54f, zf + 34f, setOf(Side.FRONT, Side.LEFT, Side.RIGHT, Side.TOP))
            for (k in 0 until 5) walk.upright(cgx - 22f + k * 11f, zf + 18f, 4f, 30f, 4.2f, 4.2f, 8, Paint.strip(Paint.stripSize(4.2f, 26f).first, 22, Paint.WHITE, k), cap = Paint.cap(4.2f, Paint.WHITE))
            if (info.year >= 2005) {
                val kiosk = b.pic("kiosk") { PixImg(tx(34f), tx(70f)).also { im -> im.fill(c(0xB82A2A)); im.border(c(0x4A0A0A)); im.rect(4, 6, im.w - 5, 22, c(0x1A1A22)); StoreSigns.small(im, "MOVIES", im.w / 2, 12, c(0xF6E8C0)); im.rect(5, 28, im.w - 6, 44, c(0x6A9AC8)); CinemaKit.glowAll(im, 180, c(0xB82A2A)) } }
                val kx = towers[1].first + 220f
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

    /** A made-up chain's sunburst: a lit disc with eight rays (cut-out), on a wall facing +z. */
    private fun sunburst(b: StoreB, cx: Float, cy: Float, z: Float, r: Float, col: Int) {
        val n = tx(r * 2f)
        val im = PixImg(n, n)
        val c0 = n / 2f
        for (y in 0 until n) for (x in 0 until n) {
            val dx = x + .5f - c0; val dy = y + .5f - c0
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            val a = kotlin.math.atan2(dy, dx)
            val ray = abs(cos(a * 4f)) > .82f
            if (d < c0 * .42f || (ray && d < c0 * .98f)) im[x, y] = if (d < c0 * .3f) Pix.mix(col, c(0xFFFFFF), .4f) else col
        }
        CinemaKit.glowAll(im, 255)
        CinemaKit.face(b.sign("letters"), im, cx, cy, z, r * 2f, r * 2f, Side.FRONT)
    }
}
