package com.lifemax.rpg.feature.habits.presentation.scene3d

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * The theater's site: the rest of its plot round the building, made by code in the same pixel style ([CinemaKit]), so the
 * landmark is whole from behind and above, not a box on a lawn. By era:
 *  - the palace and the modernised hall: an alley down each side (setts or concrete, the fire exits opening onto it), bins,
 *    a dumpster and crates, a smokers' bench; a service yard behind with the loading door; brick walls or chain link
 *    along the plot's sides and back;
 *  - the twin: a car park beside it (asphalt, painted bays, tall lamps, parked cars) wrapping round behind to the yard;
 *  - the multiplex: a lane down one side to the car park behind the halls, its bays in two rows facing an aisle, lamps,
 *    parked cars, a planted strip by the drum.
 * The model's frame is the theater's (CinemaKit): the plot's middle at x 0, the building line at z 0, the plot running
 * back to -deep. A forecourt is paved between the building line and the pavement.
 */
internal object CinemaGrounds {
    private val v = MeshBuilder.Companion::v
    private fun c(rgb: Long) = Paint.c(rgb)
    private fun tx(u: Float) = max(1, (u / TEXEL).toInt())

    /** The street's setback in front of the building line (the export's 32 art pixels). */
    private const val FORECOURT = 25f

    private val ASPHALT = PixImg(32, 32).also { im ->
        val r = Paint.Rnd(91)
        for (y in 0 until 32) for (x in 0 until 32) { val t = r.next(); im[x, y] = if (t < .16f) c(0x2E2E38) else if (t < .26f) c(0x4A4A56) else c(0x3A3A46) }
    }
    private val SETTS = PixImg(32, 32).also { im ->
        val r = Paint.Rnd(17)
        for (row in 0 until 8) {
            val off = if (row % 2 == 0) 0 else 4
            for (k in -1 until 5) {
                val base = listOf(c(0x7A7068), c(0x6A625C), c(0x847A70), c(0x726A62))[r.int(4)]
                for (y in row * 4 until row * 4 + 3) for (x in k * 8 + off until k * 8 + off + 7) im[(x + 32) % 32, y] = base
                im[(k * 8 + off + 32) % 32, row * 4] = c(0x968C80)
            }
            for (x in 0 until 32) im[x, row * 4 + 3] = c(0x4E4842)
            for (k in 0 until 4) for (y in row * 4 until row * 4 + 3) im[(k * 8 + off + 7 + 32) % 32, y] = c(0x4E4842)
        }
    }
    private val SLABS = PixImg(32, 32).also { im ->
        val r = Paint.Rnd(23)
        for (y in 0 until 32) for (x in 0 until 32) im[x, y] = if (r.next() < .1f) c(0x9A9AA6) else c(0xB0B0BA)
        for (k in 0 until 32) { im[k, 0] = c(0x7E7E8C); im[0, k] = c(0x7E7E8C); im[k, 16] = c(0x8A8A96) }
    }
    private val CHAIN = PixImg(8, 8).also { im ->
        for (y in 0 until 8) for (x in 0 until 8) if ((x + y) % 8 == 0 || (x - y + 8) % 8 == 0) im[x, y] = c(0x9AA0B0)
        im.hline(0, 7, 0, c(0x6C6E85))
    }
    private val WALL_BRICK = CinemaKit.brickTile(Ramp(c(0x3A1E1A), c(0x6A3226), c(0x84402E), c(0x9A5038), c(0xB06648)), c(0xA89080), 51)
    private val GRASS = PixImg(16, 16).also { im ->
        val r = Paint.Rnd(5)
        for (y in 0 until 16) for (x in 0 until 16) { val t = r.next(); im[x, y] = if (t < .2f) c(0x2E6A3A) else if (t < .3f) c(0x5A9A4A) else c(0x3E7E42) }
    }
    private val CURB = Ramp(c(0x4D4D66), c(0x9A9AAA), c(0xB4B4C0), c(0xC8C8D2), c(0xDCDCE4))

    /** The site's parts round the built theater: [body] its footprint (min x, min z, max x, max z) on [plot]. */
    fun build(era: CinemaEra, plot: CinemaKit.Plot, body: FloatArray, seed: Int): CinemaKit.B {
        val b = CinemaKit.B(era)
        val L = plot.lot; val D = plot.deep
        val px0 = -L / 2f + 2f; val px1 = L / 2f - 2f; val pzb = -D + 2f
        val bx0 = plot.cx - plot.w / 2f; val bx1 = plot.cx + plot.w / 2f
        val back = plot.back
        val rnd = Paint.Rnd(seed * 31 + era.ordinal)
        // the forecourt between the building line and the pavement, across the plot
        val fore = if (era == CinemaEra.PALACE) CinemaKit.FLOOR else SLABS
        pave(b, fore, px0, 0f, px1, FORECOURT, .1f)
        when (era) {
            CinemaEra.PALACE, CinemaEra.MODERN -> {
                val ground = if (era == CinemaEra.PALACE) SETTS else SLABS
                // the alleys either side and the yard behind
                if (bx0 - px0 > 20f) pave(b, ground, px0, back - 1f, bx0, 0f, .12f)
                if (px1 - bx1 > 20f) pave(b, ground, bx1, back - 1f, px1, 0f, .12f)
                pave(b, SLABS, px0, pzb, px1, back, .12f)
                // the boundary: brick walls (the palace) or chain link (the modern), from just behind the front to the back
                val zFrom = -60f
                if (era == CinemaEra.PALACE) {
                    brickWall(b, px0, pzb, px0 + 5f, zFrom, 46f)
                    brickWall(b, px1 - 5f, pzb, px1, zFrom, 46f)
                    brickWall(b, px0 + 5f, pzb, -40f, pzb + 5f, 46f)
                    brickWall(b, 40f, pzb, px1 - 5f, pzb + 5f, 46f)
                    gate(b, -40f, 40f, pzb + 2.5f, 46f)
                } else {
                    chainLink(b, px0 + 1f, zFrom, px0 + 1f, pzb + 1f, 44f)
                    chainLink(b, px1 - 1f, zFrom, px1 - 1f, pzb + 1f, 44f)
                    chainLink(b, px0 + 1f, pzb + 1f, -40f, pzb + 1f, 44f)
                    chainLink(b, 40f, pzb + 1f, px1 - 1f, pzb + 1f, 44f)
                    gate(b, -40f, 40f, pzb + 1f, 44f)
                }
                // the alleys' dressing: bins near the back, a smokers' bench by the lobby, crates
                val lx = (px0 + bx0) / 2f; val rx = (bx1 + px1) / 2f
                if (bx0 - px0 > 40f) {
                    for (k in 0 until 3) wheelieBin(b, px0 + 12f, back + 40f + k * 16f, Paint.BINGREEN, rnd.int(3))
                    crates(b, px0 + 14f, back + 110f, rnd)
                    alleyLamp(b, bx0 - 1f, -90f, -1f, era)
                    alleyLamp(b, bx0 - 1f, back + 160f, -1f, era)
                }
                if (px1 - bx1 > 40f) {
                    bench(b, px1 - 14f, -120f, -1f)
                    ashBin(b, px1 - 12f, -98f)
                    for (k in 0 until 2) wheelieBin(b, px1 - 12f, back + 50f + k * 16f, Paint.GREY, rnd.int(3))
                    alleyLamp(b, bx1 + 1f, -90f, 1f, era)
                    alleyLamp(b, bx1 + 1f, back + 160f, 1f, era)
                }
                // the yard: two dumpsters against the back wall, a pallet stack, a light over the loading door
                dumpster(b, px0 + 50f, pzb + 26f, Paint.BINGREEN)
                dumpster(b, px0 + 120f, pzb + 26f, Paint.BLUE)
                crates(b, px0 + 190f, pzb + 24f, rnd)
                if (back - pzb > 100f) cars(b, px1 - 110f, pzb + 56f, 2, 1f, rnd)
            }
            CinemaEra.TWIN -> {
                // the car park beside it (when the plot spares the room), wrapping round behind to the yard
                pave(b, SLABS, px0, back - 1f, bx0, 0f, .12f)
                val cx0 = bx1 + 4f
                if (px1 - cx0 > 150f) {
                    pave(b, ASPHALT, cx0, pzb, px1, 0f, .12f)
                    kerb(b, cx0, -2f, px1, 0f)
                    // bays along the plot's edge, nose to the fence; the aisle by the building
                    val bayL = min(104f, (px1 - cx0) * .5f)
                    bays(b, px1 - bayL, px1, -70f, back + 30f, 52f, true)
                    cars(b, px1 - bayL / 2f - 4f, -96f, ((-70f - back - 30f) / 52f).toInt(), 1f, rnd, step = 52f, along = false, sideways = true)
                    for (z in generateSequence(-130f) { it - 240f }.takeWhile { it > back + 60f }) carLamp(b, cx0 + 14f, z, era)
                }
                pave(b, ASPHALT, px0, pzb, if (px1 - cx0 > 150f) cx0 else px1, back, .12f)
                bays(b, px0 + 40f, px1 - 10f, pzb + 104f, pzb, 52f, false)
                cars(b, px0 + 66f, pzb + 52f, ((px1 - px0 - 60f) / 52f).toInt(), 1f, rnd, step = 52f, along = true)
                carLamp(b, (px0 + px1) / 2f, back - 30f, era)
                chainLink(b, px0 + 1f, pzb + 1f, px1 - 1f, pzb + 1f, 44f)
                chainLink(b, px0 + 1f, -60f, px0 + 1f, pzb + 1f, 44f)
                dumpster(b, bx0 + 60f, back - 30f, Paint.BINGREEN)
                for (k in 0 until 3) wheelieBin(b, bx0 + 110f + k * 16f, back - 18f, Paint.GREY, rnd.int(3))
            }
            CinemaEra.MULTIPLEX -> {
                // the lane down the right to the car park behind
                val lx0 = body[2] + 4f
                if (px1 - lx0 > 50f) {
                    pave(b, ASPHALT, lx0, back, px1, 0f, .12f)
                    kerb(b, lx0, -2f, px1, 0f)
                    arrows(b, (lx0 + px1) / 2f, -120f, back + 60f)
                    for (z in generateSequence(-160f) { it - 260f }.takeWhile { it > back + 40f }) carLamp(b, px1 - 26f, z, era)
                }
                // a planted strip down the left, by the drum
                if (body[0] - px0 > 8f) pave(b, GRASS, px0, back, body[0] - 1f, 0f, .12f)
                if (body[0] - px0 > 20f) for (z in generateSequence(-80f) { it - 70f }.takeWhile { it > back + 30f }) shrub(b, (px0 + body[0]) / 2f, z)
                // the car park: bays along the back, an aisle, a second row facing it against the halls
                pave(b, ASPHALT, px0, pzb, px1, back, .12f)
                val depth = back - pzb
                bays(b, px0 + 8f, px1 - 8f, pzb + 104f, pzb, 52f, false)
                cars(b, px0 + 34f, pzb + 52f, ((px1 - px0 - 20f) / 52f).toInt(), 1f, rnd, step = 52f, along = true)
                if (depth > 330f) {
                    bays(b, px0 + 8f, body[2] - 8f, back - 4f, back - 108f, 52f, false)
                    cars(b, px0 + 34f, back - 56f, ((body[2] - px0 - 20f) / 52f).toInt(), -1f, rnd, step = 52f, along = true)
                }
                for (x in generateSequence(px0 + 90f) { it + 220f }.takeWhile { it < px1 - 40f }) carLamp(b, x, pzb + 112f, era)
                // the low rail round it, the bins by the halls' back
                chainLink(b, px0 + 1f, pzb + 1f, px1 - 1f, pzb + 1f, 30f)
                chainLink(b, px0 + 1f, back, px0 + 1f, pzb + 1f, 30f)
                chainLink(b, px1 - 1f, back, px1 - 1f, pzb + 1f, 30f)
                dumpster(b, body[2] - 60f, back - 14f, Paint.BINGREEN)
                for (k in 0 until 3) wheelieBin(b, body[2] - 100f - k * 16f, back - 10f, Paint.GREY, rnd.int(3))
            }
        }
        return b
    }

    // ------------------------------------------------------------------ ground

    private fun pave(b: CinemaKit.B, img: PixImg, x0: Float, z0: Float, x1: Float, z1: Float, y: Float) {
        if (x1 - x0 < 1f || z1 - z0 < 1f) return
        b.tile(img).box(x0, -1f, z0, x1, y, z1, setOf(MeshBuilder.Side.TOP))
    }

    private fun kerb(b: CinemaKit.B, x0: Float, z0: Float, x1: Float, z1: Float) =
        CinemaKit.ledge(b.det, x0, 0f, z0, x1, 1.6f, z1, CURB)

    private val LINE = Paint.swatch(c(0xF0F0E8))
    private val YELLOW_LINE = Paint.swatch(c(0xF6D04A))

    /** A row of parking bays from [x0] to [x1], their ends at [zNear] and [zFar], [pitch] apart: painted lines. */
    private fun bays(b: CinemaKit.B, x0: Float, x1: Float, zNear: Float, zFar: Float, pitch: Float, alongZ: Boolean) {
        val y = .22f
        if (alongZ) {
            // bays across a strip running down z: lines along x every [pitch] of z
            var z = zNear
            while (z >= zFar) { flatLine(b, x0, z - .7f, x1, z + .7f, y, LINE); z -= pitch }
            flatLine(b, x0, zFar, x0 + 1.4f, zNear, y, LINE)
        } else {
            var x = x0
            while (x <= x1) { flatLine(b, x - .7f, min(zNear, zFar), x + .7f, max(zNear, zFar), y, LINE); x += pitch }
            flatLine(b, x0, zNear - .7f, x1, zNear + .7f, y, LINE)
        }
    }

    private fun flatLine(b: CinemaKit.B, x0: Float, z0: Float, x1: Float, z1: Float, y: Float, img: PixImg) =
        b.det.quad(v(x0, y, z0), v(x1, y, z0), v(x1, y, z1), v(x0, y, z1), img)

    /** Lane arrows painted down a lane at [x] from [z0] to [z1]. */
    private fun arrows(b: CinemaKit.B, x: Float, z0: Float, z1: Float) {
        val arrow = PixImg(9, 18).also { im -> for (y in 0 until 18) for (xx in 0 until 9) if ((y < 7 && abs(xx - 4) <= y / 2) || (y >= 7 && xx in 3..5)) im[xx, y] = c(0xF0F0E8) }
        var z = z0
        while (z > z1) { b.det.quad(v(x - 4f, .22f, z - 8f), v(x + 4f, .22f, z - 8f), v(x + 4f, .22f, z + 8f), v(x - 4f, .22f, z + 8f), arrow); z -= 200f }
        flatLine(b, x - .7f, z1, x + .7f, z0 + 60f, .22f, YELLOW_LINE)
    }

    // ------------------------------------------------------------------ boundaries

    private fun brickWall(b: CinemaKit.B, x0: Float, z0: Float, x1: Float, z1: Float, h: Float) {
        b.tile(WALL_BRICK).box(x0, 0f, z0, x1, h, z1, setOf(MeshBuilder.Side.LEFT, MeshBuilder.Side.RIGHT, MeshBuilder.Side.FRONT, MeshBuilder.Side.BACK))
        CinemaKit.ledge(b.det, x0 - .8f, h, z0 - .8f, x1 + .8f, h + 2.4f, z1 + .8f, Paint.CONCRETE)
    }

    /** Chain link from ([x0], [z0]) to ([x1], [z1]), [h] tall, on steel posts with a top rail. */
    private fun chainLink(b: CinemaKit.B, x0: Float, z0: Float, x1: Float, z1: Float, h: Float) {
        val dx = x1 - x0; val dz = z1 - z0
        val l = kotlin.math.sqrt(dx * dx + dz * dz)
        if (l < 2f) return
        b.tile(CHAIN).quad(v(x0, h, z0), v(x1, h, z1), v(x1, 0f, z1), v(x0, 0f, z0))
        val post = Paint.swatch(Paint.STEEL.l)
        val n = max(1, (l / 60f).toInt())
        for (k in 0..n) { val t = k / n.toFloat(); b.det.beam(v(x0 + dx * t, 0f, z0 + dz * t), v(x0 + dx * t, h + 1f, z0 + dz * t), 1.2f, post) }
        b.det.beam(v(x0, h, z0), v(x1, h, z1), .8f, post)
    }

    /** A pair of gates between [x0] and [x1] at [z]: steel frames of chain link, closed. */
    private fun gate(b: CinemaKit.B, x0: Float, x1: Float, z: Float, h: Float) {
        val post = Paint.swatch(Paint.IRON.l)
        for (x in listOf(x0, x1)) b.det.beam(v(x, 0f, z), v(x, h + 4f, z), 2.4f, post)
        b.tile(CHAIN).quad(v(x0, h - 2f, z), v(x1, h - 2f, z), v(x1, 2f, z), v(x0, 2f, z))
        for (y in listOf(2f, h - 2f)) b.det.beam(v(x0, y, z), v(x1, y, z), 1.2f, post)
        b.det.beam(v((x0 + x1) / 2f, 2f, z), v((x0 + x1) / 2f, h - 2f, z), 1.2f, post)
    }

    // ------------------------------------------------------------------ things

    private val binCache = HashMap<Ramp, PixImg>()

    private fun wheelieBin(b: CinemaKit.B, x: Float, z: Float, r: Ramp, variant: Int) {
        val side = binCache.getOrPut(r) { Paint.panel(tx(13f), tx(22f), r, 4).also { im -> im.hline(0, im.w - 1, 2, r.l); im.hline(1, im.w - 2, im.h - 4, r.d) } }
        b.det.box(x - 6.5f, 2f, z - 6.5f, x + 6.5f, 22f, z + 6.5f) { s, w, h -> if (s == MeshBuilder.Side.BOTTOM) null else if (s == MeshBuilder.Side.TOP) CinemaKit.topOf(w, h, r) else side }
        for (sx in listOf(-5f, 5f)) b.det.box(x + sx - 1f, 0f, z + 4f, x + sx + 1f, 4f, z + 6f) { s, _, _ -> if (s == MeshBuilder.Side.BOTTOM) null else Paint.swatch(c(0x1A1A22)) }
        if (variant == 1) b.det.box(x - 7f, 22f, z - 7f, x + 7f, 23.4f, z + 7f) { s, w, h -> if (s == MeshBuilder.Side.BOTTOM) null else CinemaKit.topOf(w, h, r) }
    }

    private fun dumpster(b: CinemaKit.B, x: Float, z: Float, r: Ramp) {
        val w = 66f; val d = 34f; val h = 30f
        val side = Paint.panel(tx(w), tx(h), r, 8, wear = 1.4f).also { im ->
            for (k in 1 until 4) im.vline(im.w * k / 4, 2, im.h - 3, r.d)
            im.rect(im.w / 2 - 8, 4, im.w / 2 + 7, 8, c(0xE8E0C8)); im.hline(im.w / 2 - 6, im.w / 2 + 5, 6, r.o)
        }
        b.det.box(x - w / 2f, 4f, z - d / 2f, x + w / 2f, 4f + h, z + d / 2f) { s, ww, hh -> if (s == MeshBuilder.Side.BOTTOM) null else if (s == MeshBuilder.Side.TOP) Paint.top(ww, hh, Ramp(c(0x16161F), c(0x23232F), c(0x2E2E40), c(0x3C3C52), c(0x56566E))) else if (s == MeshBuilder.Side.FRONT || s == MeshBuilder.Side.BACK) side else Paint.panel(ww, hh, r, 9) }
        for (sx in listOf(-w / 2f + 5f, w / 2f - 5f)) for (sz in listOf(-d / 2f + 4f, d / 2f - 4f)) b.det.box(x + sx - 2f, 0f, z + sz - 2f, x + sx + 2f, 4f, z + sz + 2f) { s, _, _ -> if (s == MeshBuilder.Side.BOTTOM) null else Paint.swatch(c(0x1A1A22)) }
    }

    private fun crates(b: CinemaKit.B, x: Float, z: Float, rnd: Paint.Rnd) {
        val wood = Paint.panel(tx(16f), tx(12f), Paint.WOOD, 3).also { im -> im.hline(0, im.w - 1, im.h / 2, Paint.WOOD.d) }
        var y = 0f
        for (k in 0 until 2 + rnd.int(2)) {
            val ox = (rnd.next() - .5f) * 4f
            b.det.box(x - 8f + ox, y, z - 8f, x + 8f + ox, y + 12f, z + 8f) { s, w, h -> if (s == MeshBuilder.Side.BOTTOM) null else if (s == MeshBuilder.Side.TOP) Paint.top(w, h, Paint.WOOD) else wood }
            y += 12f
        }
    }

    private fun bench(b: CinemaKit.B, x: Float, z: Float, facing: Float) {
        val wood = Paint.swatch(Paint.WOOD.l); val iron = Paint.swatch(Paint.IRON.b)
        for (dz in listOf(-14f, 14f)) { b.det.beam(v(x, 0f, z + dz), v(x, 9f, z + dz), 1.4f, iron) }
        b.det.box(x - 4f, 9f, z - 17f, x + 4f, 10.4f, z + 17f) { s, _, _ -> if (s == MeshBuilder.Side.BOTTOM) null else wood }
        b.det.box(x - facing * 4f - .7f, 12f, z - 17f, x - facing * 4f + .7f, 18f, z + 17f) { s, _, _ -> if (s == MeshBuilder.Side.BOTTOM) null else wood }
    }

    private fun ashBin(b: CinemaKit.B, x: Float, z: Float) {
        b.det.upright(x, z, 0f, 20f, 3.4f, 3.4f, 8, Paint.strip(Paint.stripSize(3.4f, 20f).first, Paint.stripSize(3.4f, 20f).second, Paint.STEEL, 2), cap = Paint.cap(3.4f, Paint.IRON))
    }

    private fun shrub(b: CinemaKit.B, x: Float, z: Float) {
        val leaf = Paint.panel(tx(16f), tx(12f), Paint.LEAF, 6, edges = false).also { im -> val r = Paint.Rnd(3); repeat(20) { im[r.int(im.w), r.int(im.h)] = Paint.LEAF.h } }
        b.det.box(x - 8f, 0f, z - 8f, x + 8f, 14f, z + 8f) { s, w, h -> if (s == MeshBuilder.Side.BOTTOM) null else if (s == MeshBuilder.Side.TOP) Paint.top(w, h, Paint.LEAF) else leaf }
    }

    /** A bracket lamp on a wall at [x] (facing out by [out]: -1 left, +1 right), glowing at night and lighting the alley. */
    private fun alleyLamp(b: CinemaKit.B, x: Float, z: Float, out: Float, era: CinemaEra) {
        val y = 70f
        b.det.beam(v(x, y, z), v(x + out * 8f, y + 2f, z), 1f, Paint.swatch(Paint.IRON.b))
        val glass = PixImg(4, 4).also { it.fill(c(0xFFE8A8)); it.border(c(0xC8A050)); CinemaKit.glowAll(it) }
        b.sign.box(x + out * 8f - 2.5f, y - 4f, z - 2.5f, x + out * 8f + 2.5f, y + 1f, z + 2.5f) { s, _, _ -> if (s == MeshBuilder.Side.TOP) Paint.swatch(Paint.IRON.b) else glass }
        b.lights += CinemaLight(x + out * 12f, y - 6f, z, 1f, .74f, .46f, 14000f, 110f)
    }

    private val lampHeads = HashMap<CinemaEra, PixImg>()

    /** A car park's tall lamp at ([x], [z]): a steel pole, a head throwing light down (sodium in the twin's day, white LED after). */
    private fun carLamp(b: CinemaKit.B, x: Float, z: Float, era: CinemaEra) {
        val h = 150f
        b.det.upright(x, z, 0f, h, 1.8f, 1.4f, 8, Paint.strip(Paint.stripSize(1.8f, h).first, 20, Paint.STEEL, 3), cap = null)
        b.det.upright(x, z, 0f, 6f, 4f, 4f, 8, Paint.strip(Paint.stripSize(4f, 6f).first, 8, Paint.CONCRETE, 2), cap = Paint.cap(4f, Paint.CONCRETE))
        val sodium = era == CinemaEra.TWIN
        val lens = lampHeads.getOrPut(era) { PixImg(6, 4).also { im -> im.fill(if (sodium) c(0xFFC870) else c(0xF0F4FF)); im.border(Paint.STEEL.d); CinemaKit.glowAll(im) } }
        for (dx in listOf(-1f, 1f)) {
            b.det.beam(v(x, h - 2f, z), v(x + dx * 12f, h, z), 1.2f, Paint.swatch(Paint.STEEL.l))
            b.sign.box(x + dx * 12f - 6f, h - 2f, z - 3.5f, x + dx * 12f + 6f, h + 1f, z + 3.5f) { s, w, hh -> when (s) { MeshBuilder.Side.BOTTOM -> lens; MeshBuilder.Side.TOP -> Paint.top(w, hh, Paint.STEEL); else -> Paint.swatch(Paint.STEEL.b) } }
        }
        b.lights += if (sodium) CinemaLight(x, h - 6f, z, 1f, .66f, .3f, 52000f, 240f, down = true, cone = 1.1f)
        else CinemaLight(x, h - 6f, z, .92f, .95f, 1f, 52000f, 240f, down = true, cone = 1.1f)
    }

    // ------------------------------------------------------------------ cars

    private class CarLook(val side: PixImg, val end0: PixImg, val end1: PixImg, val top: PixImg, val topX: PixImg, val cabSide: PixImg, val cabEnd: PixImg, val roof: PixImg, val roofX: PixImg)
    private val carLooks = HashMap<Int, CarLook>()
    private val CAR_COLOURS = listOf(
        Ramp(c(0x3A0A10), c(0x8A1A20), c(0xB8282A), c(0xD84A3A), c(0xF08A70)), Paint.BLUE, Paint.WHITE, Paint.BLACK,
        Ramp(c(0x2A3A1A), c(0x4A6A2A), c(0x6A8A3A), c(0x8AA85A), c(0xB8C88A)), Paint.YELLOW, Paint.GREY, Paint.BEIGE,
    )

    private fun carLook(k: Int): CarLook = carLooks.getOrPut(k) {
        val r = CAR_COLOURS[k % CAR_COLOURS.size]
        val glass = c(0x2A3A54); val glassHi = c(0x6A8AA8)
        val side = Paint.panel(tx(92f), tx(14f), r, 3 + k, wear = .4f).also { im ->
            im.hline(0, im.w - 1, 1, r.l); im.hline(0, im.w - 1, im.h - 3, r.d)
            im.vline(im.w / 2, 2, im.h - 3, r.d); im.hline(im.w / 2 - 6, im.w / 2 - 3, 5, Paint.STEEL.h); im.hline(im.w / 2 + 3, im.w / 2 + 6, 5, Paint.STEEL.h)
            for (x in listOf(im.w / 5, im.w * 4 / 5)) for (dy in 0..3) im.hline(x - 6 + dy, x + 6 - dy, im.h - 1 - dy, c(0x1A1A22))
        }
        val front = Paint.panel(tx(40f), tx(14f), r, 5).also { im -> im.rect(2, 4, 7, 6, c(0xFFF6D0)); im.rect(im.w - 8, 4, im.w - 3, 6, c(0xFFF6D0)); im.rect(10, 5, im.w - 11, 8, c(0x2A2A34)); im.hline(1, im.w - 2, im.h - 3, Paint.STEEL.h) }
        val rear = Paint.panel(tx(40f), tx(14f), r, 6).also { im -> im.rect(2, 4, 7, 6, c(0xD82A2A)); im.rect(im.w - 8, 4, im.w - 3, 6, c(0xD82A2A)); im.rect(im.w / 2 - 6, 6, im.w / 2 + 5, 9, c(0xE8E8E0)); im.hline(1, im.w - 2, im.h - 3, Paint.STEEL.h) }
        val top = Paint.top(tx(40f), tx(92f), r)
        val cabSide = PixImg(tx(48f), tx(12f)).also { im -> im.fill(r.b); im.rect(2, 1, im.w - 3, im.h - 2, glass); im.vline(im.w / 2, 1, im.h - 2, r.b); im.hline(3, im.w / 2 - 3, 2, glassHi); im.border(r.d) }
        val cabEnd = PixImg(tx(32f), tx(12f)).also { im -> im.fill(r.b); im.rect(1, 1, im.w - 2, im.h - 2, glass); Pix.line(im, 3, im.h - 3, 8, 2, glassHi); im.border(r.d) }
        val roof = Paint.top(tx(32f), tx(48f), r)
        CarLook(side, front, rear, top, Paint.top(tx(92f), tx(40f), r), cabSide, cabEnd, roof, Paint.top(tx(48f), tx(32f), r))
    }

    /**
     * [n] parked cars from ([x], [z]) every [step] (along +x when [along], else down -z); [nose] which way they face
     * (1: -z or +x); lengthwise along z unless [sideways]; some bays left empty (seeded).
     */
    private fun cars(b: CinemaKit.B, x: Float, z: Float, n: Int, nose: Float, rnd: Paint.Rnd, step: Float = 52f, along: Boolean = true, sideways: Boolean = false) {
        for (i in 0 until n) {
            if (rnd.next() < .42f) continue
            val cx = if (along) x + i * step else x
            val cz = if (along) z else z - i * step
            car(b, cx + (rnd.next() - .5f) * 3f, cz + (rnd.next() - .5f) * 3f, nose, carLook(rnd.int(CAR_COLOURS.size * 3)), sideways)
        }
    }

    /** A parked car at ([x], [z]), lengthwise along z (its nose to -z when [nose] is 1) or, [sideways], along x (nose to +x). */
    private fun car(b: CinemaKit.B, x: Float, z: Float, nose: Float, k: CarLook, sideways: Boolean) {
        val hw = 20f; val hl = 46f
        // a box from (along a0..a1, across c0..c1): the car's own axes mapped to the world's
        fun box(a0: Float, a1: Float, c0: Float, c1: Float, y0: Float, y1: Float, paint: (Boolean, Boolean, Boolean) -> PixImg?) {
            // paint(end, front-end, top): end faces are the car's ends, the rest its sides
            if (!sideways) b.det.box(x + c0, y0, z + a0, x + c1, y1, z + a1) { s, _, _ ->
                when (s) { MeshBuilder.Side.BOTTOM -> null; MeshBuilder.Side.TOP -> paint(false, false, true); MeshBuilder.Side.BACK -> paint(true, nose > 0f, false); MeshBuilder.Side.FRONT -> paint(true, nose <= 0f, false); else -> paint(false, false, false) }
            } else b.det.box(x + a0, y0, z + c0, x + a1, y1, z + c1) { s, _, _ ->
                when (s) { MeshBuilder.Side.BOTTOM -> null; MeshBuilder.Side.TOP -> paint(false, false, true); MeshBuilder.Side.RIGHT -> paint(true, nose > 0f, false); MeshBuilder.Side.LEFT -> paint(true, nose <= 0f, false); else -> paint(false, false, false) }
            }
        }
        box(-hl, hl, -hw, hw, 6f, 20f) { end, front, top -> if (top) (if (sideways) k.topX else k.top) else if (end) (if (front) k.end0 else k.end1) else k.side }
        val off = (if (sideways) -nose else nose) * 4f
        box(off - 24f, off + 24f, -hw + 3f, hw - 3f, 20f, 32f) { end, _, top -> if (top) (if (sideways) k.roofX else k.roof) else if (end) k.cabEnd else k.cabSide }
        val tyre = Paint.swatch(c(0x16161C))
        for (sc in listOf(-hw - .2f, hw - 3.8f)) for (sa in listOf(-hl + 12f, hl - 22f)) box(sa, sa + 12f, sc, sc + 4f, 0f, 10f) { _, _, _ -> tyre }
    }
}
