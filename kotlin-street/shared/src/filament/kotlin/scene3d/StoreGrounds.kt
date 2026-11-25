package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.lifemax.rpg.feature.habits.presentation.scene3d.MeshBuilder.Side
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreKit.Site
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreKit.c
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreKit.tx
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * The stores' sites, the rest of each plot round the building ([StoreKit]), so a store is whole from behind and above:
 *  - the general store: a gravel apron to the road, a gravel drive down one side with the owner's truck, a backyard of
 *    grass (a woodpile, an outhouse in the early decades or a propane tank later, a rain barrel, a clothesline, a shade
 *    tree), a split-rail fence; weeds and a FOR SALE sign once it has shut, a chapel's notice board;
 *  - the markets: an asphalt car park with painted bays, lamps (warm incandescent in the fifties, sodium in the
 *    seventies and eighties, LED from 2010) and the era's cars, cart corrals with pixel carts, kerbed islands with trees,
 *    a sign at the road (the market's tower, the supermarket's pole sign, the superstore's pylon), a truck lane to the
 *    back, the service yard or truck court behind with dumpsters, a compactor, pallets and trailers at the docks, and for
 *    the superstore its fenced garden centre.
 */
internal object StoreGrounds {
    private val v = MeshBuilder.Companion::v

    /** The pack's own asphalt (city terrains), the worn one painted in its palette with oil dither and hairline cracks. */
    private val ASPHALT = Swatches.get("asphalt")
    private val OLD_ASPHALT = Swatches.get("asphalt_worn").also { im ->
        Pix.line(im, 3, 0, 12, 31, c(0x2E2C36)); Pix.line(im, 20, 5, 30, 18, c(0x2E2C36))
    }
    private val GRAVEL = PixImg(32, 32).also { im ->
        val r = Paint.Rnd(7)
        for (y in 0 until 32) for (x in 0 until 32) { val t = r.next(); im[x, y] = if (t < .2f) c(0x8A8070) else if (t < .4f) c(0xB0A690) else if (t < .45f) c(0x6A6458) else c(0x9E9480) }
    }
    private val GRASS = PixImg(16, 16).also { im ->
        val r = Paint.Rnd(5)
        for (y in 0 until 16) for (x in 0 until 16) { val t = r.next(); im[x, y] = if (t < .2f) c(0x2E6A3A) else if (t < .3f) c(0x5A9A4A) else c(0x3E7E42) }
    }
    private val DRY_GRASS = PixImg(16, 16).also { im ->
        val r = Paint.Rnd(6)
        for (y in 0 until 16) for (x in 0 until 16) { val t = r.next(); im[x, y] = if (t < .25f) c(0x6A6A3A) else if (t < .4f) c(0x9A9050) else c(0x7E7A44) }
    }
    /** The pack's pavement slabs (city terrains). */
    private val CONCRETE = Swatches.get("pavement")
    private val CHAIN = PixImg(8, 8).also { im ->
        for (y in 0 until 8) for (x in 0 until 8) if ((x + y) % 8 == 0 || (x - y + 8) % 8 == 0) im[x, y] = c(0x9AA0B0)
        im.hline(0, 7, 0, c(0x6C6E85))
    }
    private val PICKET = PixImg(8, 16).also { im ->
        for (y in 0 until 16) { im[1, y] = c(0x1E1E26); im[5, y] = c(0x1E1E26) }
        im.hline(0, 7, 1, c(0x2A2A34)); im.hline(0, 7, 13, c(0x2A2A34))
    }
    private val CURB = Ramp(c(0x4D4D66), c(0x9A9AAA), c(0xB4B4C0), c(0xC8C8D2), c(0xDCDCE4))

    fun build(b: StoreB, info: StoreInfo, site: Site) {
        when (info.kind) {
            StoreKind.GENERAL -> general(b, info, site)
            // (a market become a church has taken its tower down for a low sign of its own)
            StoreKind.MARKET -> lotSite(b, info, site, roadSign = if (info.state == StoreState.REUSED && info.reuse == StoreReuse.CHURCH) ::monumentSign else ::marketTower, lampH = 110f, walk = 58f)
            StoreKind.SUPERMARKET -> lotSite(b, info, site, roadSign = ::poleSign, lampH = 150f, walk = 74f)
            StoreKind.SUPERSTORE -> lotSite(b, info, site, roadSign = ::pylon, lampH = 190f, walk = 150f)
        }
    }

    // ------------------------------------------------------------------ ground helpers

    private fun pave(b: StoreB, img: PixImg, x0: Float, z0: Float, x1: Float, z1: Float, y: Float) {
        if (x1 - x0 < 1f || z1 - z0 < 1f) return
        b.ground(img).box(x0, -1f, z0, x1, y, z1, setOf(Side.TOP))
    }

    private fun line(b: StoreB, x0: Float, z0: Float, x1: Float, z1: Float, col: Int, y: Float = .26f) =
        b.det("ground:lines").quad(v(x0, y, z0), v(x1, y, z0), v(x1, y, z1), v(x0, y, z1), b.swatch(col))

    private val PAINT = c(0xF0F0E8)
    private val YELLOW = c(0xF6D04A)

    // ------------------------------------------------------------------ the general store's yard

    private fun general(b: StoreB, info: StoreInfo, s: Site) {
        val L = info.lot; val D = info.deep
        val px0 = -L / 2f + 1f; val px1 = L / 2f - 1f; val pzb = -D + 1f
        val closed = info.state == StoreState.CLOSED
        val r = Paint.Rnd(info.seed * 13 + 5)
        val grass = if (closed) DRY_GRASS else GRASS
        val m = b.det("yard")
        // the gravel apron from the porch to the road, the drive down the right side to the back
        pave(b, GRAVEL, px0, s.zf + 92f, px1, 0f, .1f)
        val dx0 = s.x1 + 30f
        pave(b, GRAVEL, dx0, s.zb + 40f, px1, s.zf + 92f, .1f)
        pave(b, grass, px0, pzb, px1, s.zf + 92f, .05f)
        // a split-rail fence round the back and sides of the yard
        val rail = Paint.swatch(c(0x8A7458))
        val fz = s.zb - 20f
        for ((a, e) in listOf(floatArrayOf(px0 + 4f, pzb + 4f) to floatArrayOf(px1 - 4f, pzb + 4f), floatArrayOf(px0 + 4f, pzb + 4f) to floatArrayOf(px0 + 4f, fz + 40f))) {
            val n = max(1, ((abs(e[0] - a[0]) + abs(e[1] - a[1])) / 60f).toInt())
            for (k in 0..n) { val t = k / n.toFloat(); m.beam(v(a[0] + (e[0] - a[0]) * t, 0f, a[1] + (e[1] - a[1]) * t), v(a[0] + (e[0] - a[0]) * t, 26f, a[1] + (e[1] - a[1]) * t), 2.4f, rail) }
            for (y in listOf(10f, 21f)) m.beam(v(a[0], y, a[1]), v(e[0], y, e[1]), 1.6f, rail)
        }
        // the woodpile against the lean-to's side, a rain barrel
        val woodEnd = b.pic("woodEnd") { PixImg(16, 12).also { im -> im.fill(c(0x5A3A22)); for (y in 0 until 12 step 3) for (x in (y / 3) % 2 until 16 step 3) { Pix.disc(im, x + 1f, y + 1.5f, 1.4f, c(0xC89A62)); im[x + 1, y + 1] = c(0x8A5A32) } } }
        val wx = s.x0 + 6f
        m.box(wx - 6f, 0f, s.zb - 60f, wx + 6f, 26f, s.zb - 4f) { sd, w, h -> when (sd) { Side.BOTTOM -> null; Side.FRONT, Side.BACK -> woodEnd; Side.TOP -> b.top(w, h, StoreKit.DECK); else -> b.panel(w, h, StoreKit.DECK, 3) } }
        m.prism(v(s.x1 - 14f, 0f, s.zb - 10f), v(0f, 1f, 0f), v(0f, 0f, 1f), 8f, 8f, 22f, 10, StoreKit.barrelStrip(b), capFar = Paint.cap(8f, Paint.IRON))
        // the outhouse in the early decades, a propane tank later
        val back = s.zb - 120f
        if (back > pzb + 40f) {
            if (info.year < 1950) {
                val ox = px0 + 40f
                val oz = max(back - 30f, pzb + 30f)
                val boards = b.tile(StoreKit.BOARDS)
                boards.box(ox - 16f, 0f, oz - 16f, ox + 16f, 64f, oz + 16f, setOf(Side.FRONT, Side.BACK, Side.LEFT, Side.RIGHT))
                b.tile(StoreKit.tin(3, true)).quad(v(ox - 20f, 72f, oz + 20f), v(ox + 20f, 72f, oz + 20f), v(ox + 20f, 62f, oz - 20f), v(ox - 20f, 62f, oz - 20f))
                boards.quad(v(ox - 16f, 72f, oz + 16f), v(ox + 16f, 72f, oz + 16f), v(ox + 16f, 64f, oz + 16f), v(ox - 16f, 64f, oz + 16f))
                val moon = b.pic("moon") { PixImg(tx(20f), tx(50f)).also { im -> im.fill(c(0x7A6A58)); im.border(c(0x3A3028)); Pix.disc(im, im.w / 2f, 8f, 3f, c(0x1A1410)); Pix.disc(im, im.w / 2f + 1.5f, 7f, 2.6f, c(0x7A6A58)) } }
                CinemaKit.face(m, moon, ox, 25f, oz + 16.2f, 20f, 50f, Side.FRONT)
            } else {
                val tz = max(back, pzb + 40f)
                m.prism(v(px0 + 30f, 22f, tz), v(1f, 0f, 0f), v(0f, 1f, 0f), 14f, 14f, 70f, 12, Paint.strip(Paint.stripSize(14f, 70f).first, 30, Paint.WHITE, 3), capFar = Paint.cap(14f, Paint.WHITE), capNear = Paint.cap(14f, Paint.WHITE))
                for (k in listOf(14f, 56f)) b.box(m, px0 + 30f + k - 4f, 0f, tz - 8f, px0 + 30f + k + 4f, 12f, tz + 8f, Paint.CONCRETE)
            }
            // a clothesline between two T posts
            if (!closed) {
                val cz0 = s.zb - 80f; val cz1 = max(pzb + 20f, s.zb - 260f)
                val cxl = (s.x0 + s.x1) / 2f + 20f
                val post = Paint.swatch(c(0x8A8A9A))
                for (z in listOf(cz0, cz1)) { m.beam(v(cxl, 0f, z), v(cxl, 70f, z), 2.4f, post); m.beam(v(cxl - 14f, 68f, z), v(cxl + 14f, 68f, z), 2f, post) }
                for (dx in listOf(-10f, 0f, 10f)) m.beam(v(cxl + dx, 68f, cz0), v(cxl + dx, 66f, cz1), .5f, b.swatch(c(0xD8D8D8)))
                val sheets = listOf(c(0xF6F6F0), c(0xC8D8F0), c(0xF0D0D0), c(0xE8E0C0))
                for (k in 0 until 4) { val z = cz0 + (cz1 - cz0) * (k + 1) / 5f; val w = 9f + (k % 2) * 4f; b.solid(m, cxl - 10.4f + (k % 3) * 10f, 66f - 30f + (k % 2) * 8f, z - w, cxl - 9.6f + (k % 3) * 10f, 67f, z + w, sheets[k]) }
            }
        }
        // a big shade tree in the yard
        tree(b, px0 + 72f, s.zb - 72f, 1.2f, info.seed)
        if (L > 300f) tree(b, px1 - 62f, max(pzb + 64f, s.zb - 220f), 1f, info.seed + 3)
        // the owner's truck on the drive (or none when it has shut), the era's
        if (!closed) {
            val k = r.int(8); val truck = info.year < 1990 || r.next() < .5f
            if (b.realCars) b.parked += LotCar((dx0 + px1) / 2f, s.zf - 40f, Math.PI.toFloat(), info.seed * 17 + k)
            else car(b, b.det("cars"), (dx0 + px1) / 2f, s.zf - 40f, carLook(b, info.year, 0, k, truck = truck), nose = -1f, along = false)
        }
        if (info.state == StoreState.OPEN && info.year >= 1950) yardLight(b, dx0 + 6f, -30f, info.year)
        // a closed store: tall weeds along the porch, a FOR SALE sign; a chapel's notice board; an antiques shop's flag
        if (closed) {
            val weed = b.pic("weed") { PixImg(10, 12).also { im -> val rr = Paint.Rnd(4); for (x in 0 until 10) { val h = 4 + rr.int(8); for (y in 0 until h) im[x, 11 - y] = if (y > h - 2) c(0x9A9A4A) else c(0x5A7A34) } } }
            for (k in 0 until 24) {
                val wx2 = px0 + 10f + r.next() * (L - 20f)
                val wz = min(-8f, s.zf + 76f + r.next() * 30f)
                if (s.keep.any { wx2 > it[0] && wx2 < it[2] && wz > it[1] && wz < it[3] }) continue
                b.det("yard").quad(v(wx2 - 6f, 14f, wz), v(wx2 + 6f, 14f, wz), v(wx2 + 6f, 0f, wz), v(wx2 - 6f, 0f, wz), weed)
                b.det("yard").quad(v(wx2, 14f, wz - 6f), v(wx2, 14f, wz + 6f), v(wx2, 0f, wz + 6f), v(wx2, 0f, wz - 6f), weed)
            }
            postSign(b, px1 - 50f, -24f, "FOR SALE", "INQUIRE", c(0xF6F0E0), c(0xA82A2A))
        }
        if (info.reuse == StoreReuse.CHURCH && info.state == StoreState.REUSED) noticeBoard(b, px0 + 50f, -26f, info.signName)
        if (info.reuse != null && info.reuse != StoreReuse.CHURCH && info.state == StoreState.REUSED) postSign(b, px1 - 46f, -24f, info.reuse.word.substringBefore("  "), "OPEN", c(0x2A3A2A), c(0xF6E8C0))
    }

    private fun yardLight(b: StoreB, x: Float, z: Float, year: Int) {
        val m = b.det("yard")
        m.upright(x, z, 0f, 150f, 3.4f, 2.6f, 8, Paint.strip(Paint.stripSize(3.4f, 150f).first, 40, StoreKit.DECK, 3), cap = Paint.cap(2.6f, StoreKit.DECK))
        m.beam(v(x, 140f, z), v(x - 20f, 146f, z), 1.4f, b.swatch(Paint.IRON.l))
        val lens = b.pic("mv") { PixImg(6, 4).also { im -> im.fill(c(0xE8F0FF)); im.border(Paint.STEEL.d); CinemaKit.glowAll(im) } }
        b.sign("lamps").box(x - 27f, 141f, z - 4f, x - 15f, 146f, z + 4f) { s, w, h -> if (s == Side.BOTTOM) lens else b.swatch(Paint.STEEL.b) }
        b.lights += CinemaLight(x - 21f, 138f, z, .85f, .92f, 1f, 22000f, 200f, down = true, cone = 1.4f)
    }

    private fun postSign(b: StoreB, x: Float, z: Float, a: String, e: String, paper: Int, ink: Int) {
        val m = b.det("yard")
        for (dx in listOf(-16f, 16f)) m.beam(v(x + dx, 0f, z), v(x + dx, 46f, z), 2f, b.swatch(c(0x8A7458)))
        val face = b.pic("ps$a$e") { PixImg(tx(40f), tx(24f)).also { im -> im.fill(paper); im.border(Pix.mix(paper, c(0x000000), .4f)); StoreSigns.letters(im, a, 2, 2, im.w - 3, im.h * 6 / 10, null, null, 2) { _, _ -> ink }; StoreSigns.small(im, e, im.w / 2, im.h - 8, ink) } }
        m.box(x - 20f, 22f, z - 1f, x + 20f, 46f, z + 1f) { s, _, _ -> when (s) { Side.FRONT, Side.BACK -> face; Side.BOTTOM -> null; else -> b.swatch(paper) } }
    }

    private fun noticeBoard(b: StoreB, x: Float, z: Float, name: String) {
        val m = b.det("yard")
        b.box(m, x - 34f, 0f, z - 4f, x + 34f, 10f, z + 4f, Paint.CONCRETE, 3)
        for (dx in listOf(-30f, 30f)) m.beam(v(x + dx, 10f, z), v(x + dx, 52f, z), 3f, b.swatch(c(0xF0ECE0)))
        val face = b.pic("nb$name") { PixImg(tx(56f), tx(36f)).also { im ->
            im.fill(c(0xF6F2E6)); im.border(c(0x3A3A4A))
            StoreSigns.letters(im, name, 3, 2, im.w - 4, 12, null, null, 2) { _, _ -> c(0x2A2A5A) }
            im.rect(3, 14, im.w - 4, im.h - 4, c(0x1A1A22))
            StoreSigns.small(im, "ALL WELCOME", im.w / 2, 17, c(0xF6F6F0)); StoreSigns.small(im, "SUNDAY 10 AM", im.w / 2, 25, c(0xF6F6F0))
            CinemaKit.glowAll(im, 140)
        } }
        b.box(m, x - 30f, 14f, z - 2.5f, x + 30f, 52f, z + 2.5f, Paint.WHITE, 4)
        CinemaKit.face(b.sign("board"), face, x, 33f, z + 2.6f, 56f, 36f, Side.FRONT)
        CinemaKit.face(b.sign("board"), face, x, 33f, z - 2.6f, 56f, 36f, Side.BACK)
    }

    // ------------------------------------------------------------------ the markets' car parks

    /**
     * A market's site: the car park in front of the building (bays, lamps, cars, corrals, islands), lanes down both
     * sides to the back, the service yard or truck court behind, the road sign; the superstore's garden centre.
     */
    private fun lotSite(b: StoreB, info: StoreInfo, s: Site, roadSign: (StoreB, StoreInfo, Float, Float) -> Unit, lampH: Float, walk: Float) {
        val L = info.lot; val D = info.deep
        val px0 = -L / 2f + 1f; val px1 = L / 2f - 1f; val pzb = -D + 1f
        val closed = info.state == StoreState.CLOSED
        val reused = info.state == StoreState.REUSED
        val r = Paint.Rnd(info.seed * 17 + info.kind.ordinal)
        val big = info.kind == StoreKind.SUPERSTORE
        val asphalt = if (closed || info.year - info.opened > 25) OLD_ASPHALT else ASPHALT
        // a planted strip along the road, the car park, lanes round the building, the back
        val strip = if (big) 70f else 40f
        pave(b, GRASS, px0, -strip, px1, 0f, .2f)
        CinemaKit.ledge(b.det("ground:kerb"), px0, 0f, -strip - 2f, px1, 1.6f, -strip, CURB)
        val front = s.zf + walk
        val gx0 = if (big) s.keep.getOrNull(1)?.get(0) ?: s.x0 else s.x0
        pave(b, asphalt, px0, s.zf - 2f, px1, -strip, .14f)
        pave(b, asphalt, px0, s.zb - 2f, gx0 - 2f, s.zf - 2f, .14f)
        // (beside the 1990s discount store, the land its extension will take is still grass)
        val sideEnd = if (info.has("discountStore")) min(px1, s.x1 + 240f) else px1
        pave(b, asphalt, s.x1 + 2f, s.zb - 2f, sideEnd, s.zf - 2f, .14f)
        if (sideEnd < px1) pave(b, GRASS, sideEnd, s.zb - 2f, px1, s.zf - 2f, .1f)
        pave(b, CONCRETE, px0, pzb, px1, s.zb - 2f, .16f)
        // the car park's rows: a fire lane by the walk, then double rows of bays with an aisle between each pair
        val bayW = 56f; val bayD = 112f; val aisle = 150f
        val lampsAt = ArrayList<FloatArray>()
        // where the doors are (the shoppers park nearest them), the paint (faded on a lot shut for years)
        val doors = if (big) StoreMarkets.facade(info).entrances.map { (k, _) -> (s.x0 + s.x1) / 2f + k * (s.x1 - s.x0) } else listOf((s.x0 + s.x1) / 2f)
        fun door(x: Float, z: Float) = doors.minOf { abs(it - x) } + abs(z - s.zf)
        val longShut = closed && info.yearsClosed >= 3
        val paint = if (longShut) Pix.mix(PAINT, c(0x4A4A50), .55f) else PAINT
        val yellow = if (longShut) Pix.mix(YELLOW, c(0x4A4A50), .55f) else YELLOW
        val corralsOn = info.has("cartCorrals") || (closed && info.yearsClosed >= 2 && info.kind != StoreKind.MARKET)
        var z = front + aisle * .8f
        var rowPair = 0
        val ex0 = px0 + 60f; val ex1 = px1 - 60f
        while (z + bayD * 2f < -strip - aisle * .5f) {
            val zm = z + bayD
            // the bays: lines across both rows, the middle line, the ends' islands
            val nx = ((ex1 - ex0 - 120f) / bayW).toInt()
            val bx0 = (ex0 + ex1) / 2f - nx * bayW / 2f
            for (k in 0..nx) { val x = bx0 + k * bayW; line(b, x - .8f, z, x + .8f, z + bayD * 2f, paint) }
            line(b, bx0, zm - .8f, bx0 + nx * bayW, zm + .8f, if (big) yellow else paint)
            for ((ix, w) in listOf(bx0 - 50f to 48f, bx0 + nx * bayW + 2f to 48f)) {
                CinemaKit.ledge(b.det("ground:kerb"), ix, 0f, z + 6f, ix + w, 5f, z + bayD * 2f - 6f, CURB)
                pave(b, GRASS, ix + 2f, z + 8f, ix + w - 2f, z + bayD * 2f - 8f, 5.2f)
                if (big || info.kind == StoreKind.SUPERMARKET && info.year >= 1980) tree(b, ix + w / 2f, zm, .8f, info.seed + rowPair * 7 + ix.toInt())
            }
            // a corral every few bays in the double rows (in place of two bays), carts in it
            val corrals = HashSet<Int>()
            if (corralsOn) { var k = 6 + rowPair * 3; while (k < nx - 4) { corrals += k; k += if (big) 11 else 9 } }
            // the bays the cars park in ([StoreCars] fills them by the hour); a shut store's corrals left behind, a few
            for (k in 0 until nx) {
                val x = bx0 + (k + .5f) * bayW
                if (k in corrals) { if (!closed || k % 3 == 0) corral(b, x + bayW / 2f, zm, info); continue }
                if (k - 1 in corrals) continue
                for (side in 0..1) {
                    val cz = if (side == 0) z + bayD / 2f + 4f else zm + bayD / 2f - 4f
                    b.bays += StoreBay(x, cz, if (side == 0) 1f else -1f, false, door(x, cz))
                }
            }
            // lamps down the middle line
            var lx = bx0 + bayW * 3f
            while (lx < bx0 + nx * bayW - bayW * 2f) { lampsAt += floatArrayOf(lx, zm); lx += if (big) 330f else 280f }
            z += bayD * 2f + aisle
            rowPair++
        }
        // a small lot with no room for a double row: one row of head-in bays along the walk, the lamps by the road
        if (rowPair == 0) {
            val z0 = front + 2f
            val nx = ((s.x1 - s.x0 - 40f) / bayW).toInt()
            val bx0 = (s.x0 + s.x1) / 2f - nx * bayW / 2f
            for (k in 0..nx) { val x = bx0 + k * bayW; line(b, x - .8f, z0, x + .8f, z0 + bayD, paint) }
            CinemaKit.ledge(b.det("ground:kerb"), bx0, 0f, z0, bx0 + nx * bayW, 3f, z0 + 4f, CURB)
            for (k in 0 until nx) { val x = bx0 + (k + .5f) * bayW; b.bays += StoreBay(x, z0 + bayD / 2f + 4f, -1f, false, door(x, z0)) }
            var lx = bx0 + 60f
            while (lx < bx0 + nx * bayW) { lampsAt += floatArrayOf(lx, -strip - 14f); lx += 240f }
        }
        // arrows and a crosswalk by the doors (the bigger stores), the fire lane's red kerb at the superstore
        if (info.kind != StoreKind.MARKET) {
            val cw = b.pic("zebra") { PixImg(8, 8).also { im -> im.fill(0); for (x in 0 until 4) im.vline(x, 0, 7, paint) } }
            val cxs = if (big) listOf(s.x0 + (s.x1 - s.x0) * .26f, s.x0 + (s.x1 - s.x0) * .74f) else listOf((s.x0 + s.x1) / 2f)
            for (cx in cxs) {
                var x = cx - 60f
                while (x < cx + 60f) { line(b, x, front, x + 8f, front + aisle * .8f, paint, .28f); x += 16f }
            }
            if (big) CinemaKit.ledge(b.det("ground:kerb"), s.x0, 0f, front - 2f, s.x1, 4.4f, front, Ramp(c(0x4A0A0A), c(0x8A1A1A), c(0xB82A26), c(0xC84A3A), c(0xE07060)))
        }
        // the staff's bays down the building's right side (by the side door), when there is room
        if (px1 - s.x1 > 140f) {
            val sx = s.x1 + 70f
            var sz = s.zf - 80f
            while (sz > s.zb + 60f && b.bays.count { it.staff } < (if (big) 30 else 10)) {
                line(b, sx - 52f, sz + bayW / 2f - .8f, sx + 52f, sz + bayW / 2f + .8f, paint)
                b.bays += StoreBay(sx, sz, 1f, true, 1e6f, staff = true); sz -= bayW
            }
        }
        // the pickup bays (2012 on) by the right entrance, EV chargers (2020 on) at the end of the first row
        if (info.has("pickupBays")) pickupBays(b, s.x1 - 260f, front + aisle * .8f + 4f)
        if (info.has("evChargers")) evChargers(b, ex0 + 70f, front + aisle * .8f + bayD - 6f, info.year)
        // a lot shut for years: cracks, weeds in the joints, a stray cart or two, one on its side
        if (longShut) decay(b, px0 + 60f, front + 20f, px1 - 60f, -strip - 20f, info)
        // the lamps (a real light at every other one; a pool of light on the asphalt under each); a shut store keeps
        // its security lights: a wall-pack at each front corner and one lamp, dim
        for ((k, p) in lampsAt.withIndex()) lotLamp(b, p[0], p[1], lampH, info.year, light = !closed && k % 2 == 0, double = big || info.kind == StoreKind.SUPERMARKET,
            security = closed && k == lampsAt.size / 2 && info.has("securityLights"),
            pool = if (closed) null else floatArrayOf(px0, pzb, px1, -2f))
        if (closed && info.has("securityLights")) for (x in listOf(s.x0 + 30f, s.x1 - 30f)) StoreMarkets.wallPack(b, x, 100f, s.zf + .5f, Side.FRONT, info.year < 2010, security = true)
        // the road sign at the car park's entrance corner
        roadSign(b, info, px1 - (if (big) 230f else if (info.kind == StoreKind.SUPERMARKET) 130f else 100f), -strip / 2f - 4f)
        // the back: dumpsters, pallets, a compactor and trailers at the docks; a chain-link fence round the yard
        val m = b.det("yard")
        val yz = (s.zb + pzb) / 2f
        if (info.kind == StoreKind.MARKET) {
            dumpster(b, m, s.x1 - 60f, s.zb - 30f, Paint.BINGREEN)
            for (k in 0 until 4) crates(b, m, s.x0 + (s.x1 - s.x0) * .5f + k * 22f, s.zb - 16f, r)
        } else {
            // the dumpster pad: a concrete slab in a three-sided block enclosure, two dumpsters and a recycling bin
            val dx = s.x1 - 110f; val dz = s.zb - 46f
            b.box(m, dx - 90f, 0f, dz - 40f, dx + 90f, 3f, dz + 42f, Paint.CONCRETE, 51)
            val cmu = Ramp(c(0x4A4A50), c(0x8A8A90), c(0x9E9EA4), c(0xB0B0B4), c(0xC4C4C8))
            b.box(m, dx - 90f, 0f, dz - 40f, dx - 84f, 44f, dz + 40f, cmu, 52)
            b.box(m, dx + 84f, 0f, dz - 40f, dx + 90f, 44f, dz + 40f, cmu, 52)
            b.box(m, dx - 90f, 0f, dz + 34f, dx + 90f, 44f, dz + 40f, cmu, 52)
            dumpster(b, m, dx - 40f, dz + 10f, Paint.BLUE)
            dumpster(b, m, dx + 40f, dz + 10f, Paint.BINGREEN)
            for (k in listOf(-1f, 1f)) m.upright(dx + k * 96f, dz - 46f, 0f, 36f, 3f, 3f, 8, null, swatch = b.swatch(Paint.YELLOW.b))
            compactor(b, m, s.x0 + (s.x1 - s.x0) * .2f, s.zb, big)
            for (k in 0 until (if (big) 10 else 4)) pallet(b, m, s.x0 + (s.x1 - s.x0) * .3f + k * 50f, s.zb - 30f - (k % 2) * 4f, r, k)
            if (!closed && !reused) {
                val n = if (big) 4 else 1
                val dockX0 = if (big) s.x0 + (s.x1 - s.x0) * .35f else s.x0 + (s.x1 - s.x0) * .08f
                val dockStep = if (big) 220f else 0f
                for (k in 0 until n) trailer(b, b.det("trailers"), dockX0 + 55f + k * dockStep, s.zb - 8f, max(pzb + 10f, s.zb - 300f), info.year, k)
            }
        }
        chainLink(b, px0 + 6f, pzb + 6f, px1 - 6f, pzb + 6f, 70f)
        chainLink(b, px0 + 6f, pzb + 6f, px0 + 6f, s.zb - 4f, 70f)
        chainLink(b, px1 - 6f, pzb + 6f, px1 - 6f, s.zb - 4f, 70f)
        // a tree line along the back fence (the plot's edge from above is a hedge of trees, not bare ground)
        var tx0 = px0 + 80f
        while (tx0 < px1 - 80f) { tree(b, tx0 + (r.next() - .5f) * 20f, pzb + 70f + r.next() * 12f, .65f + r.next() * .3f, info.seed + tx0.toInt()); tx0 += if (big) 150f else 110f }
        if (big) gardenCentre(b, info, s)
    }

    /** A car park lamp: a tall pole (concrete base), one or two heads; warm before 1965, sodium to 2010, white LED after. */
    private fun lotLamp(b: StoreB, x: Float, z: Float, h: Float, year: Int, light: Boolean, double: Boolean, security: Boolean = false, pool: FloatArray? = null) {
        val m = b.det("lamps")
        val sodium = year in 1965..2009
        val warm = year < 1965
        m.upright(x, z, 0f, 14f, 6f, 6f, 8, Paint.strip(Paint.stripSize(6f, 14f).first, 12, Paint.CONCRETE, 2), cap = Paint.cap(6f, Paint.CONCRETE))
        m.upright(x, z, 14f, h, 2.2f, 1.6f, 8, Paint.strip(Paint.stripSize(2.2f, h).first, 30, Paint.STEEL, 3), cap = null)
        val key = if (warm) 0 else if (sodium) 1 else 2
        val lens = b.pic("lotlens$key") { PixImg(8, 5).also { im -> im.fill(when (key) { 0 -> c(0xFFE0A0); 1 -> c(0xFFC870); else -> c(0xF0F4FF) }); im.border(Paint.STEEL.d); CinemaKit.glowAll(im) } }
        val arms = if (double) listOf(-1f, 1f) else listOf(1f)
        for (d in arms) {
            m.beam(v(x, h - 3f, z), v(x + d * 16f, h, z), 1.6f, b.swatch(Paint.STEEL.l))
            b.sign(if (security) "sec" else "lamps").box(x + d * 16f - 9f, h - 3f, z - 5f, x + d * 16f + 9f, h + 1.5f, z + 5f) { s, w, hh -> when (s) { Side.BOTTOM -> lens; Side.TOP -> b.top(w, hh, Paint.STEEL); else -> b.swatch(Paint.STEEL.b) } }
        }
        if (security) b.secLights += CinemaLight(x, h - 8f, z, 1f, .7f, .4f, 20000f, h * 1.3f, down = true, cone = 1.1f)
        if (pool != null) lightPool(b, x, z, h * 1.15f, key, pool)
        if (light) b.lights += when (key) {
            0 -> CinemaLight(x, h - 8f, z, 1f, .8f, .55f, 40000f, h * 1.7f, down = true, cone = 1.2f)
            1 -> CinemaLight(x, h - 8f, z, 1f, .66f, .3f, 60000f, h * 1.7f, down = true, cone = 1.2f)
            else -> CinemaLight(x, h - 8f, z, .92f, .95f, 1f, 60000f, h * 1.7f, down = true, cone = 1.2f)
        }
    }

    /**
     * A pool of lamplight baked onto the asphalt under a lamp: a disc of the asphalt's own speckle (3 texels a pixel)
     * glowing in the lamp's colour from dusk, strongest under the head and dithered out at its edge, so a big lot reads
     * evenly lit at night with only every other lamp a real light.
     */
    private fun lightPool(b: StoreB, x: Float, z: Float, r: Float, key: Int, clip: FloatArray) {
        val n = max(8, (r * 2f / (TEXEL * 3f)).toInt())
        val tint = when (key) { 0 -> c(0xFFE0A0); 1 -> c(0xFFB860); else -> c(0xE8F0FF) }
        val im = b.pic("pool$n,$key") { PixImg(n, n).also { im ->
            val c0 = n / 2f
            val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
            for (y in 0 until n) for (xx in 0 until n) {
                val d = kotlin.math.sqrt((xx + .5f - c0) * (xx + .5f - c0) + (y + .5f - c0) * (y + .5f - c0)) / c0
                if (d >= 1f) continue
                val keep = d < .45f || (1f - d) / .55f * 16f > bayer[(y % 4) * 4 + xx % 4]
                if (!keep) continue
                im[xx, y] = Pix.mix(ASPHALT[(xx * 3) % 32, (y * 3) % 32], tint, .05f)
                im.glowAt(xx, y, (95f * (1f - d).let { it * it }).toInt().coerceIn(8, 95))
            }
        } }
        // cut to the plot (x0, z0, x1, z1): the picture's matching part (one picture for every pool of this lamp)
        val a = max(x - r, clip[0]); val e = min(x + r, clip[2]); val f = max(z - r, clip[1]); val g = min(z + r, clip[3])
        if (e - a < 4f || g - f < 4f) return
        fun u(xx: Float) = (xx - (x - r)) / (2f * r) * n
        b.sign("pool").quad(v(a, .2f, f), v(e, .2f, f), v(e, .2f, g), v(a, .2f, g), im, u(a), u(f - z + x), u(e), u(g - z + x))
    }

    /** Painted pickup bays with their orange signs ("ORDER PICKUP"), four bays from [x] along the row at [z]. */
    private fun pickupBays(b: StoreB, x: Float, z: Float) {
        val orange = c(0xE8742A)
        val sign = b.pic("pickup") { PixImg(StoreKit.tx(30f), StoreKit.tx(24f)).also { im -> im.fill(orange); im.border(c(0x6A2A0A)); StoreSigns.small(im, im.w / 2, 3, "ORDER", c(0xFFFFFF)); StoreSigns.small(im, im.w / 2, 10, "PICKUP", c(0xFFFFFF)); StoreSigns.small(im, im.w / 2, 17, "1-4", c(0xFFFFFF)); CinemaKit.glowAll(im, 170) } }
        b.texts += "ORDER PICKUP"
        val m = b.det("pickup")
        for (k in 0..4) line(b, x + k * 56f - 1.5f, z, x + k * 56f + 1.5f, z + 112f, orange, .27f)
        line(b, x, z + 108f, x + 4 * 56f, z + 111f, orange, .27f)
        for (k in 0 until 4) {
            val px = x + k * 56f + 28f
            m.upright(px, z + 4f, 0f, 70f, 1.6f, 1.6f, 6, Paint.strip(Paint.stripSize(1.6f, 70f).first, 20, Paint.STEEL, k), cap = null)
            CinemaKit.face(b.sign("pickup"), sign, px, 60f, z + 5f, 30f, 24f, Side.FRONT)
        }
    }

    /** A row of EV chargers (2020 on): slim posts with a lit screen and a cable, green-painted bays. */
    private fun evChargers(b: StoreB, x: Float, z: Float, year: Int) {
        val post = b.pic("evpost") { PixImg(StoreKit.tx(12f), StoreKit.tx(56f)).also { im -> im.fill(c(0xF0F0F4)); im.border(c(0x3A3A44)); im.rect(2, 4, im.w - 3, 14, c(0x1A2A3A)); im.rect(3, 6, im.w - 4, 12, c(0x3AE08A)); im.glow(3, 6, im.w - 4, 12, 220); im.rect(2, 20, im.w - 3, 22, c(0x2A8A4A)) } }
        b.texts += "EV"
        val m = b.det("ev")
        for (k in 0 until 4) {
            val px = x + k * 56f
            line(b, px - 26f, z - 100f, px + 26f, z - 2f, c(0x2A7A4A), .25f)
            m.box(px - 6f, 0f, z - 5f, px + 6f, 56f, z + 5f) { s, w, h -> when (s) { Side.BOTTOM -> null; Side.FRONT, Side.BACK -> post; Side.TOP -> b.swatch(c(0xD8D8E0)); else -> b.swatch(c(0xE0E0E8)) } }
            m.beam(v(px + 6f, 30f, z - 5f), v(px + 10f, 8f, z - 14f), 1f, b.swatch(c(0x1A1A1E)))
        }
    }

    /** A car park shut for years: dark cracks, weeds in the joints and along the kerbs, stray carts (one on its side). */
    private fun decay(b: StoreB, x0: Float, z0: Float, x1: Float, z1: Float, info: StoreInfo) {
        if (x1 <= x0 || z1 <= z0) return
        val r = Paint.Rnd(info.seed * 3 + info.yearsClosed)
        val n = ((x1 - x0) * (z1 - z0) / 9000f * (info.yearsClosed.coerceAtMost(12) / 6f)).toInt().coerceIn(20, 900)
        val weeds = listOf(c(0x4A7A32), c(0x6A8A3A), c(0x3A5A2A), c(0x8A9A4A))
        repeat(n) {
            val x = x0 + r.next() * (x1 - x0); val z = z0 + r.next() * (z1 - z0)
            if (r.next() < .45f) {
                // a crack: two or three short dark segments, zigzag
                var cx = x; var cz = z
                repeat(2 + r.int(2)) { val nx = cx + (r.next() - .5f) * 40f; val nz = cz + 6f + r.next() * 20f; line(b, min(cx, nx), cz, max(cx, nx) + 1.2f, cz + 1.4f, c(0x24242A), .24f); line(b, nx, min(cz, nz), nx + 1.4f, max(cz, nz), c(0x24242A), .24f); cx = nx; cz = nz }
            } else {
                val w = 2f + r.next() * 5f
                line(b, x, z, x + w, z + w * .7f, weeds[r.int(weeds.size)], .3f)
                if (r.next() < .5f) b.solid(b.det("ground:weeds"), x + 1f, 0f, z + 1f, x + 2.2f, 3f + r.next() * 4f, z + 2f, weeds[r.int(weeds.size)])
            }
        }
        val m = b.det("strays")
        repeat(2) { cart(b, m, x0 + r.next() * (x1 - x0), z0 + r.next() * (z1 - z0), c(0x8A8A90), sideways = r.next() < .5f) }
        // one on its side: the basket's wire box lying down, a wheel up
        val tx = x0 + r.next() * (x1 - x0); val tz = z0 + r.next() * (z1 - z0)
        val wire = b.pic("wire") { PixImg(8, 8).also { im -> for (yy in 0 until 8) for (xx in 0 until 8) if (xx % 3 == 0 || yy % 3 == 0) im[xx, yy] = c(0xB8BCC4) } }
        b.tile(wire).box(tx, 0f, tz, tx + 30f, 18f, tz + 20f, setOf(Side.FRONT, Side.BACK, Side.LEFT, Side.RIGHT, Side.TOP))
        b.solid(m, tx + 30f, 2f, tz + 2f, tx + 40f, 4f, tz + 18f, c(0x9A9AA2))
        for (dz in listOf(3f, 15f)) b.solid(m, tx + 40f, 1f, tz + dz, tx + 44f, 7f, tz + dz + 3f, c(0x1A1A1E))
    }

    // ------------------------------------------------------------------ carts

    /** [n] carts nested in a row from ([x], [z]) along +x (or -z), facing the wall, their handles in [handle]. */
    fun cartRow(b: StoreB, m: MeshBuilder, x: Float, z: Float, n: Int, along: Boolean, handle: Int) {
        for (k in 0 until n) if (along) cart(b, m, x + k * 9f, z, handle, sideways = true) else cart(b, m, x, z - k * 9f, handle, sideways = false)
    }

    /** One cart at ([x], [z]): a wire basket (see-through), its frame and handle, four wheels; lengthwise along x when [sideways]. */
    private fun cart(b: StoreB, m: MeshBuilder, x: Float, z: Float, handle: Int, sideways: Boolean) {
        val side = b.pic("cart$handle") { StoreSigns.cartSide(handle) }
        val end = b.pic("cartEnd") { PixImg(10, 12).also { im -> val wire = c(0xB8BCC8); for (y in 0 until 12) for (xx in 0 until 10) if (xx % 2 == 0 || y % 3 == 0) im[xx, y] = wire; im.border(c(0x8A8E9A)) } }
        val len = StoreSigns.CART_W * TEXEL; val hgt = StoreSigns.CART_H * TEXEL; val w = 8f
        if (sideways) {
            for (dz in listOf(-w / 2f, w / 2f)) m.quad(v(x - len / 2f, hgt + 4f, z + dz), v(x + len / 2f, hgt + 4f, z + dz), v(x + len / 2f, 4f, z + dz), v(x - len / 2f, 4f, z + dz), side)
            m.quad(v(x + len / 2f - 1f, hgt - 1f, z + w / 2f), v(x + len / 2f - 1f, hgt - 1f, z - w / 2f), v(x + len / 2f - 1f, 13f, z - w / 2f), v(x + len / 2f - 1f, 13f, z + w / 2f), end)
            m.beam(v(x - len / 2f, hgt + 4f, z - w / 2f), v(x - len / 2f, hgt + 4f, z + w / 2f), 1f, b.swatch(handle))
        } else {
            for (dx in listOf(-w / 2f, w / 2f)) m.quad(v(x + dx, hgt + 4f, z + len / 2f), v(x + dx, hgt + 4f, z - len / 2f), v(x + dx, 4f, z - len / 2f), v(x + dx, 4f, z + len / 2f), side)
            m.beam(v(x - w / 2f, hgt + 4f, z + len / 2f), v(x + w / 2f, hgt + 4f, z + len / 2f), 1f, b.swatch(handle))
        }
    }

    /** A cart corral in the car park at ([x], [z]): a pipe pen (open at the aisle end), its sign, carts in it. */
    private fun corral(b: StoreB, x: Float, z: Float, info: StoreInfo) {
        val m = b.det("corrals")
        val pipe = b.swatch(if (info.year < 1990) c(0xC8C8D0) else c(0x2A6A3A))
        val hw = 16f; val hl = 90f
        for (dx in listOf(-hw, hw)) {
            for (dz in listOf(-hl, -hl / 3f, hl / 3f, hl)) m.beam(v(x + dx, 0f, z + dz), v(x + dx, 34f, z + dz), 2f, pipe)
            for (y in listOf(16f, 32f)) m.beam(v(x + dx, y, z - hl), v(x + dx, y, z + hl), 1.6f, pipe)
        }
        for (y in listOf(16f, 32f)) m.beam(v(x - hw, y, z - hl), v(x + hw, y, z - hl), 1.6f, pipe)
        val sign = b.pic("corralSign") { PixImg(tx(30f), tx(14f)).also { im -> im.fill(c(0x2A5AA8)); im.border(c(0x1A2A5A)); StoreSigns.small(im, "CART", im.w / 2, 1, c(0xFFFFFF)); StoreSigns.small(im, "RETURN", im.w / 2, 8, c(0xFFFFFF)) } }
        m.beam(v(x, 32f, z + hl), v(x, 62f, z + hl), 1.6f, pipe)
        m.box(x - 15f, 50f, z + hl - 1f, x + 15f, 64f, z + hl + 1f) { s, _, _ -> when (s) { Side.FRONT, Side.BACK -> sign; Side.BOTTOM -> null; else -> b.swatch(c(0x2A5AA8)) } }
        cartRow(b, m, x, z - hl + 18f + (info.seed % 3) * 9f, 4 + info.seed % 4, along = false, handle = c(0xC82A2A))
    }

    // ------------------------------------------------------------------ cars

    /** A car's pictures by era and colour (cached per look). */
    internal class CarLook(val side: PixImg, val nose: PixImg, val tail: PixImg, val top: PixImg, val cabSide: PixImg, val cabEnd: PixImg, val roof: PixImg, val len: Float, val wid: Float, val bodyH: Float, val cabH: Float, val cabLen: Float, val truck: Boolean)

    private val ERA_COLOURS = mapOf(
        0 to listOf(0xE89AA0, 0x8ACAC0, 0xF0E0A0, 0x3A4A6A, 0xE8E8E0, 0x6A2A2A, 0x2A5A3A, 0xA8C8E8),
        1 to listOf(0x8A5A22, 0xD87A2A, 0x6A7A2A, 0xE8D8A8, 0x3A5A8A, 0xA82A22, 0x2A2A2A, 0xC8A85A),
        2 to listOf(0x1A3A6A, 0x6A1A2A, 0xC8C8D0, 0x2A5A3A, 0xE8E8E8, 0x1A1A1E, 0x8A8A92, 0xA8862A),
        3 to listOf(0xC8CCD4, 0xF0F0F0, 0x1A1A1E, 0x6A6E78, 0x8A1A1A, 0x1A3A6A, 0x3A4A5A, 0xD8D4C8),
        // the 1910s-20s: black, a few dark greens and maroons
        4 to listOf(0x16161A, 0x1A1A1E, 0x1E2A22, 0x16161A, 0x3A1A1A, 0x1A1A1E, 0x22261E, 0x16161A),
    )

    internal fun carLook(b: StoreB, year: Int, salt: Int, k: Int, truck: Boolean = false): CarLook {
        val era = when { year < 1932 -> 4; year < 1965 -> 0; year < 1985 -> 1; year < 2005 -> 2; else -> 3 }
        val cols = ERA_COLOURS[era]!!
        val rgb = cols[(k + salt) % cols.size].toLong()
        val base = c(rgb)
        val r = Ramp(Pix.mix(base, c(0x000000), .7f), Pix.mix(base, c(0x000000), .3f), base, Pix.mix(base, c(0xFFFFFF), .2f), Pix.mix(base, c(0xFFFFFF), .5f))
        val suv = era == 3 && k % 3 == 0
        val isTruck = truck || (era >= 2 && k % 5 == 1)
        // the 1920s car: short, narrow and tall, its cab a box upright on it
        val len = when (era) { 4 -> 80f; 0 -> 104f; 1 -> 100f; 2 -> 92f; else -> 92f }
        val wid = if (era == 4) 32f else 38f
        val bodyH = if (era == 4) 12f else if (suv || isTruck) 18f else 14f
        val cabH = if (era == 4) 22f else if (suv) 16f else 12f
        val cabLen = if (era == 4) (if (isTruck) 26f else 40f) else if (isTruck) 30f else if (suv) 60f else 48f
        val key = "car$era,$rgb,$isTruck,$suv"
        val glass = c(0x2A3A54); val hi = c(0x6A8AA8)
        val side = b.pic("$key:side") { Paint.panel(tx(len), tx(bodyH), r, 3, wear = .4f).also { im ->
            im.hline(0, im.w - 1, 1, r.l); im.hline(0, im.w - 1, im.h - 3, r.d)
            if (era == 0) im.rect(0, im.h / 2, im.w - 1, im.h / 2 + 1, c(0xE8E8E0))
            if (era <= 1) im.hline(1, im.w - 2, im.h - 2, Paint.STEEL.h)
            for (x in listOf(im.w / 5, im.w * 4 / 5)) for (dy in 0..3) im.hline(x - 6 + dy, x + 6 - dy, im.h - 1 - dy, c(0x1A1A22))
        } }
        val nose = b.pic("$key:nose") { Paint.panel(tx(wid), tx(bodyH), r, 5).also { im -> im.rect(2, 4, 6, 6, c(0xFFF6D0)); im.rect(im.w - 7, 4, im.w - 3, 6, c(0xFFF6D0)); im.rect(9, 5, im.w - 10, 8, if (era == 0) Paint.STEEL.h else c(0x2A2A34)); im.hline(1, im.w - 2, im.h - 3, Paint.STEEL.h) } }
        val tail = b.pic("$key:tail") { Paint.panel(tx(wid), tx(bodyH), r, 6).also { im -> im.rect(2, 4, 6, 6, c(0xD82A2A)); im.rect(im.w - 7, 4, im.w - 3, 6, c(0xD82A2A)); im.rect(im.w / 2 - 5, 6, im.w / 2 + 4, 9, c(0xE8E8E0)); im.hline(1, im.w - 2, im.h - 3, Paint.STEEL.h) } }
        val top = b.pic("$key:top") { Paint.top(tx(wid), tx(len), r) }
        val cabSide = b.pic("$key:cab") { PixImg(tx(cabLen), tx(cabH)).also { im -> im.fill(r.b); im.rect(2, 1, im.w - 3, im.h - 2, glass); im.vline(im.w / 2, 1, im.h - 2, r.b); im.hline(3, im.w / 2 - 3, 2, hi); im.border(r.d) } }
        val cabEnd = b.pic("$key:cabE") { PixImg(tx(wid - 6f), tx(cabH)).also { im -> im.fill(r.b); im.rect(1, 1, im.w - 2, im.h - 2, glass); Pix.line(im, 3, im.h - 3, 8, 2, hi); im.border(r.d) } }
        val roof = b.pic("$key:roof") { Paint.top(tx(wid - 6f), tx(cabLen), r) }
        return CarLook(side, nose, tail, top, cabSide, cabEnd, roof, len, wid, bodyH, cabH, cabLen, isTruck)
    }

    /** A parked car at ([x], [z]): lengthwise along z (nose to +z when [nose] is 1) or, [along], along x (nose to +x). */
    internal fun car(b: StoreB, m: MeshBuilder, x: Float, z: Float, k: CarLook, nose: Float, along: Boolean) {
        val hl = k.len / 2f; val hw = k.wid / 2f
        // a0..a1 along the car, c0..c1 across it; the end at +a is the nose when [nose] > 0
        fun box(a0: Float, a1: Float, c0: Float, c1: Float, y0: Float, y1: Float, endNose: PixImg, endTail: PixImg, sidePic: PixImg, topPic: PixImg) {
            val plus = if (nose > 0f) endNose else endTail; val minus = if (nose > 0f) endTail else endNose
            if (!along) m.box(x + c0, y0, z + a0, x + c1, y1, z + a1) { s, _, _ -> when (s) { Side.BOTTOM -> null; Side.TOP -> topPic; Side.FRONT -> plus; Side.BACK -> minus; else -> sidePic } }
            else m.box(x + a0, y0, z + c0, x + a1, y1, z + c1) { s, w, h -> when (s) { Side.BOTTOM -> null; Side.TOP -> b.pic("carTopX$w,$h,${topPic.px[topPic.w + 1]}") { PixImg(w, h).also { im -> for (yy in 0 until h) for (xx in 0 until w) im[xx, yy] = topPic[yy * topPic.w / h, xx * topPic.h / w] } }; Side.RIGHT -> plus; Side.LEFT -> minus; else -> sidePic } }
        }
        box(-hl, hl, -hw, hw, 6f, 6f + k.bodyH, k.nose, k.tail, k.side, k.top)
        val o = (if (k.truck) hl - k.cabLen / 2f - 22f else -2f) * nose
        box(o - k.cabLen / 2f, o + k.cabLen / 2f, -hw + 3f, hw - 3f, 6f + k.bodyH, 6f + k.bodyH + k.cabH, k.cabEnd, k.cabEnd, k.cabSide, k.roof)
        val tyre = b.swatch(c(0x16161C))
        for (sc in listOf(-hw - .2f, hw - 3.8f)) for (sa in listOf(-hl + 12f, hl - 24f)) {
            if (!along) m.box(x + sc, 0f, z + sa, x + sc + 4f, 11f, z + sa + 12f) { s, _, _ -> if (s == Side.BOTTOM) null else tyre }
            else m.box(x + sa, 0f, z + sc, x + sa + 12f, 11f, z + sc + 4f) { s, _, _ -> if (s == Side.BOTTOM) null else tyre }
        }
    }

    // ------------------------------------------------------------------ the yard's things

    private fun dumpster(b: StoreB, m: MeshBuilder, x: Float, z: Float, r: Ramp) {
        val w = 70f; val d = 36f; val h = 30f
        val side = b.pic("dump${r.b}") { Paint.panel(tx(w), tx(h), r, 8, wear = 1.4f).also { im -> for (k in 1 until 4) im.vline(im.w * k / 4, 2, im.h - 3, r.d); im.rect(im.w / 2 - 8, 4, im.w / 2 + 7, 8, c(0xE8E0C8)) } }
        m.box(x - w / 2f, 4f, z - d / 2f, x + w / 2f, 4f + h, z + d / 2f) { s, ww, hh -> when (s) { Side.BOTTOM -> null; Side.TOP -> b.top(ww, hh, Paint.BLACK); Side.FRONT, Side.BACK -> side; else -> b.panel(ww, hh, r, 9) } }
        for (sx in listOf(-w / 2f + 5f, w / 2f - 5f)) for (sz in listOf(-d / 2f + 4f, d / 2f - 4f)) b.solid(m, x + sx - 2f, 0f, z + sz - 2f, x + sx + 2f, 4f, z + sz + 2f, c(0x1A1A22))
    }

    private fun crates(b: StoreB, m: MeshBuilder, x: Float, z: Float, rnd: Paint.Rnd) {
        val wood = b.pic("crate") { Paint.panel(tx(16f), tx(12f), Paint.WOOD, 3).also { im -> im.hline(0, im.w - 1, im.h / 2, Paint.WOOD.d) } }
        var y = 0f
        for (k in 0 until 2 + rnd.int(2)) {
            val ox = (rnd.next() - .5f) * 4f
            m.box(x - 8f + ox, y, z - 8f, x + 8f + ox, y + 12f, z + 8f) { s, w, h -> if (s == Side.BOTTOM) null else if (s == Side.TOP) b.top(w, h, Paint.WOOD) else wood }
            y += 12f
        }
    }

    private fun pallet(b: StoreB, m: MeshBuilder, x: Float, z: Float, r: Paint.Rnd, k: Int) {
        val slat = b.pic("pallet") { PixImg(tx(40f), tx(5f)).also { im -> im.fill(c(0xB8946A)); for (xx in 0 until im.w step 6) im.vline(xx, 0, im.h - 1, c(0x6A4A2A)) } }
        m.box(x - 20f, 0f, z - 20f, x + 20f, 5f, z + 20f) { s, w, h -> if (s == Side.BOTTOM) null else if (s == Side.TOP) b.top(w, h, Paint.WOOD) else slat }
        if (k % 3 != 2) {
            val load = if (k % 2 == 0) Ramp(c(0x3A2A1A), c(0x8A6A3A), c(0xA8865A), c(0xC0A070), c(0xD8BC8E)) else Paint.WHITE
            b.box(m, x - 18f, 5f, z - 18f, x + 18f, 5f + 16f + r.int(3) * 8f, z + 18f, load, 3 + k)
        }
    }

    private fun compactor(b: StoreB, m: MeshBuilder, x: Float, zb: Float, big: Boolean) {
        val l = if (big) 160f else 110f
        b.box(m, x - 30f, 0f, zb - 30f - l, x + 30f, 60f, zb - 30f, Paint.BLUE, 4, wear = 1.2f)
        b.box(m, x - 34f, 0f, zb - 30f, x + 34f, 74f, zb - 2f, Paint.STEEL, 5)
        for (k in 0..1) b.solid(m, x - 32f, 0f, zb - 30f - l + 20f + k * (l - 40f), x + 32f, 6f, zb - 24f - l + 20f + k * (l - 40f), c(0x1A1A22))
    }

    /** A box trailer at a dock: its body from [zDock] back toward [zFar], on its wheels, its landing legs; a tractor on one. */
    private fun trailer(b: StoreB, m: MeshBuilder, x: Float, zDock: Float, zFar: Float, year: Int, k: Int) {
        val len = min(300f, zDock - zFar - 10f)
        if (len < 120f) return
        val zf = zDock - 4f; val zr = zf - len
        val side = b.pic("trailer${k % 2}") { PixImg(tx(len), tx(84f)).also { im ->
            im.fill(c(0xE8E8EC)); im.border(c(0x5A5A6A))
            for (x in 0 until im.w step 10) im.vline(x, 1, im.h - 2, c(0xD0D0D8))
            im.hline(1, im.w - 2, im.h - 4, c(0x8A8A9A))
            if (k % 2 == 0) StoreSigns.letters(im, listOf("SWIFTLINE", "NORTHLINE", "OAKRIDGE FREIGHT")[k % 3], 8, im.h / 3, im.w - 9, im.h * 2 / 3, null, null, 4) { _, _ -> c(0x2A4A8A) }
        } }
        val end = b.pic("trailerEnd") { PixImg(tx(48f), tx(84f)).also { im -> im.fill(c(0xD8D8E0)); im.border(c(0x5A5A6A)); im.vline(im.w / 2, 1, im.h - 2, c(0x5A5A6A)); for (y in listOf(10, 40, 70)) im.hline(2, im.w - 3, y, c(0x8A8A9A)) } }
        m.box(x - 24f, 44f, zr, x + 24f, 128f, zf) { s, w, h -> when (s) { Side.BOTTOM -> b.panel(w, h, Paint.GREY); Side.TOP -> b.top(w, h, Paint.WHITE); Side.FRONT, Side.BACK -> end; else -> side } }
        val tyre = b.swatch(c(0x16161C))
        for (dz in listOf(28f, 52f)) for (sx in listOf(-24f, 18f)) m.box(x + sx, 0f, zr + dz - 10f, x + sx + 6f, 20f, zr + dz + 10f) { s, _, _ -> if (s == Side.BOTTOM) null else tyre }
        b.solid(m, x - 20f, 20f, zr + 14f, x + 20f, 44f, zr + 66f, c(0x2A2A30))
        for (sx in listOf(-16f, 14f)) b.solid(m, x + sx, 0f, zf - len * .7f, x + sx + 3f, 44f, zf - len * .7f + 3f, c(0x3A3A44))
        // a tractor coupled to the first trailer, ready to go
        if (k == 0 && zr - 140f > zFar) {
            val tz = zr - 6f
            val cab = Paint.RED
            b.box(m, x - 24f, 14f, tz - 70f, x + 24f, 92f, tz - 30f, cab, 3)
            b.box(m, x - 22f, 14f, tz - 120f, x + 22f, 58f, tz - 70f, cab, 4)
            val wsh = b.pic("wsh") { PixImg(tx(40f), tx(24f)).also { im -> im.fill(c(0x2A3A54)); im.border(cab.o); Pix.line(im, 3, im.h - 3, 10, 2, c(0x6A8AA8)) } }
            CinemaKit.face(m, wsh, x, 74f, tz - 70.2f, 40f, 24f, Side.BACK)
            for (dz in listOf(-108f, -40f, -20f)) for (sx in listOf(-24f, 18f)) m.box(x + sx, 0f, tz + dz - 10f, x + sx + 6f, 22f, tz + dz + 10f) { s, _, _ -> if (s == Side.BOTTOM) null else tyre }
            for (sx in listOf(-26f, 26f)) m.upright(x + sx, tz - 34f, 60f, 112f, 2f, 2f, 8, null, swatch = b.swatch(Paint.STEEL.h), cap = Paint.cap(2f, Paint.STEEL))
        }
    }

    private fun chainLink(b: StoreB, x0: Float, z0: Float, x1: Float, z1: Float, h: Float) {
        val dx = x1 - x0; val dz = z1 - z0
        val l = kotlin.math.sqrt(dx * dx + dz * dz)
        if (l < 2f) return
        b.tile(CHAIN).quad(v(x0, h, z0), v(x1, h, z1), v(x1, 0f, z1), v(x0, 0f, z0))
        b.tile(CHAIN).quad(v(x1, h, z1), v(x0, h, z0), v(x0, 0f, z0), v(x1, 0f, z1))
        val post = b.swatch(Paint.STEEL.l)
        val n = max(1, (l / 80f).toInt())
        val m = b.det("fence")
        for (k in 0..n) { val t = k / n.toFloat(); m.beam(v(x0 + dx * t, 0f, z0 + dz * t), v(x0 + dx * t, h + 1f, z0 + dz * t), 1.6f, post) }
        m.beam(v(x0, h, z0), v(x1, h, z1), 1f, post)
    }

    // ------------------------------------------------------------------ trees

    /**
     * A shade tree at ([x], [z]), [s] its size: a trunk that forks into two limbs, a crown of five lumpy eight-sided
     * clumps (each a squat double frustum in leaf strips, lit tops, darker undersides) set round and above the fork.
     */
    fun tree(b: StoreB, x: Float, z: Float, s: Float, seed: Int) {
        val m = b.det("trees")
        val trunkH = 56f * s
        val bark = b.pic("trunk") { Paint.strip(30, 40, Paint.BARK, 3) }
        m.upright(x, z, 0f, trunkH, 5f * s, 3.6f * s, 8, bark, cap = null)
        for (d in listOf(-1f, 1f)) m.beam(v(x, trunkH - 4f * s, z), v(x + d * 16f * s, trunkH + 30f * s, z + d * 6f * s), 3.4f * s, b.pic("barkSw") { Paint.swatch(Paint.BARK.b) })
        val leaf = b.pic("crown${seed % 3}") { Paint.strip(48, 24, Paint.LEAF, seed % 3, ends = false).also { im -> val r = Paint.Rnd(seed % 3); repeat(160) { im[r.int(48), r.int(24)] = if (r.next() < .55f) Paint.LEAF.h else Paint.LEAF.d }; for (y in 0 until 4) im.hline(0, 47, y, Pix.mix(Paint.LEAF.l, Paint.LEAF.h, .4f)); for (y in 19 until 24) im.hline(0, 47, y, Paint.LEAF.d) } }
        val under = b.pic("crownUnder") { Paint.strip(48, 8, Ramp(Paint.LEAF.o, Paint.LEAF.o, Paint.LEAF.d, Paint.LEAF.d, Paint.LEAF.b), 4, ends = false) }
        val cap = b.pic("crownCap") { Paint.cap(20f, Paint.LEAF) }
        val r = Paint.Rnd(seed * 7 + 1)
        val clumps = listOf(floatArrayOf(0f, 46f, 0f, 30f), floatArrayOf(-24f, 30f, 8f, 24f), floatArrayOf(22f, 32f, -6f, 25f), floatArrayOf(4f, 28f, 22f, 22f), floatArrayOf(-6f, 30f, -22f, 22f))
        for (c in clumps) {
            val cx = x + (c[0] + (r.next() - .5f) * 6f) * s; val cz = z + (c[2] + (r.next() - .5f) * 6f) * s
            val cy = trunkH + c[1] * s; val rad = c[3] * s
            // the clump: a lower frustum widening up to its belly, an upper one closing to a small top
            m.upright(cx, cz, cy - rad * .55f, cy, rad * .55f, rad, 8, under, cap = null)
            m.upright(cx, cz, cy, cy + rad * .8f, rad, rad * .45f, 8, leaf, cap = cap)
            m.prism(v(cx, cy - rad * .55f, cz), v(0f, -1f, 0f), v(0f, 0f, 1f), rad * .55f, rad * .2f, 3f * s, 8, under, capFar = cap)
        }
    }

    // ------------------------------------------------------------------ road signs

    /** The fifties market's sign tower at the road: a tall slab with FOOD stacked down it, the name across its top, a star. */
    private fun marketTower(b: StoreB, info: StoreInfo, x: Float, z: Float) {
        val m = b.det("sign")
        val closed = info.state == StoreState.CLOSED
        val H = 280f; val w = 36f; val d = 14f
        b.box(m, x - 30f, 0f, z - 14f, x + 30f, 20f, z + 14f, Ramp(c(0x3A1E1A), c(0x7A3A2A), c(0x9A4A34), c(0xB4603E), c(0xC87A52)), 3)
        val slab = b.pic("towerSlab$closed${info.state}${info.reuse}") { PixImg(tx(w), tx(H - 60f)).also { im ->
            im.fill(c(0xF6F0DC)); im.border(c(0x8A1A1A)); im.rect(1, 1, 2, im.h - 2, c(0xC82A2A)); im.rect(im.w - 3, 1, im.w - 2, im.h - 2, c(0xC82A2A))
            val word = if (info.state != StoreState.REUSED) "FOOD" else when (info.reuse) {
                StoreReuse.CHURCH -> "CHAPEL"; StoreReuse.GYM -> "GYM"; StoreReuse.THRIFT -> "THRIFT"; StoreReuse.FLEA -> "FLEA"
                StoreReuse.OUTLET -> "SALE"; StoreReuse.COMMUNITY -> "HALL"; else -> "OPEN" }
            val pitch = im.h / word.length
            for ((k, ch) in word.withIndex()) StoreSigns.letters(im, ch.toString(), 3, k * pitch + 4, im.w - 4, (k + 1) * pitch - 4, if (closed) null else c(0xFFFFFF), null, 6) { _, _ -> if (closed) c(0xB89A90) else c(0xD82A2A) }
            if (!closed) CinemaKit.glowAll(im, 200)
        } }
        val sm = if (closed) m else b.sign("road")
        m.box(x - w / 2f, 20f, z - d / 2f, x + w / 2f, H - 40f, z + d / 2f) { s, ww, hh -> when (s) { Side.BOTTOM -> null; Side.FRONT, Side.BACK -> null; Side.TOP -> b.top(ww, hh, Paint.WHITE); else -> b.panel(ww, hh, Paint.RED, 4) } }
        CinemaKit.face(sm, slab, x, 20f + (H - 60f) / 2f, z + d / 2f + .1f, w, H - 60f, Side.FRONT)
        CinemaKit.face(sm, slab, x, 20f + (H - 60f) / 2f, z - d / 2f - .1f, w, H - 60f, Side.BACK)
        // the name board across the top and a star
        val board = b.pic("towerBoard$closed") { PixImg(tx(150f), tx(34f)).also { im -> im.fill(c(0x1E7A7A)); im.border(c(0x0E3A3A)); StoreSigns.letters(im, info.signName, 3, 3, im.w - 4, im.h - 4, null, null, 3) { _, _ -> if (closed) c(0x6A9A9A) else c(0xFFF6E0) }; if (!closed) CinemaKit.glowAll(im, 220, c(0x1E7A7A)) } }
        b.box(m, x - 75f, H - 44f, z - 5f, x + 75f, H - 10f, z + 5f, Paint.STEEL, 5)
        CinemaKit.face(sm, board, x, H - 27f, z + 5.1f, 150f, 34f, Side.FRONT)
        CinemaKit.face(sm, board, x, H - 27f, z - 5.1f, 150f, 34f, Side.BACK)
        val star = b.pic("star$closed") { PixImg(tx(40f), tx(40f)).also { im ->
            val c0 = im.w / 2f
            for (y in 0 until im.h) for (xx in 0 until im.w) {
                val dx = xx + .5f - c0; val dy = y + .5f - c0
                val a = kotlin.math.atan2(dy, dx); val d0 = kotlin.math.sqrt(dx * dx + dy * dy)
                val rr = c0 * (.42f + .58f * abs(kotlin.math.cos(a * 4f)).let { it * it * it })
                if (d0 < rr) im[xx, y] = if (d0 < c0 * .3f) c(0xFFFFFF) else c(0xF6D83A)
            }
            if (!closed) CinemaKit.glowAll(im, 255)
        } }
        if (info.state == StoreState.REUSED) {
            // the new use's changeable-letter board where the starburst was
            val lines = when (info.reuse) { StoreReuse.CHURCH -> listOf("SUNDAY 10 AM", "ALL WELCOME"); StoreReuse.GYM -> listOf("OPEN 5 AM", "JOIN NOW")
                StoreReuse.THRIFT, StoreReuse.FLEA -> listOf("DONATIONS", "WELCOME"); StoreReuse.COMMUNITY -> listOf("BINGO FRIDAY", "ALL WELCOME"); else -> listOf("OPEN", "COME IN") }
            b.texts += lines
            val lb = b.pic("letterBoard${info.reuse}") { PixImg(tx(110f), tx(40f)).also { StoreSigns.letterBoard(it, lines, true) } }
            b.box(m, x - 57f, H - 4f, z - 4f, x + 57f, H + 40f, z + 4f, Paint.STEEL, 5, bottom = true)
            CinemaKit.face(sm, lb, x, H + 18f, z + 4.1f, 110f, 40f, Side.FRONT)
            CinemaKit.face(sm, lb, x, H + 18f, z - 4.1f, 110f, 40f, Side.BACK)
        } else {
            CinemaKit.face(sm, star, x, H + 8f, z + .2f, 40f, 40f, Side.FRONT)
            CinemaKit.face(sm, star, x, H + 8f, z - .2f, 40f, 40f, Side.BACK)
        }
        if (!closed) b.lights += CinemaLight(x, H - 60f, min(-2f, z + 30f), 1f, .9f, .8f, 6000f, 120f)
    }

    /**
     * A church's low monument sign at the road (where a store's tower stood): a brick plinth and piers, a cream face with
     * a gilt cross and the name, a changeable-letter board under it, lit softly at night.
     */
    private fun monumentSign(b: StoreB, info: StoreInfo, x: Float, z: Float) {
        val m = b.det("sign")
        val w = 110f; val h = 46f
        val brick = StoreKit.brick(Ramp(c(0x3A1E1A), c(0x7A3A2A), c(0x9A4A34), c(0xB4603E), c(0xC87A52)), c(0xB8A088), info.seed)
        val bt = b.tile(brick)
        bt.box(x - w / 2f - 12f, 0f, z - 10f, x + w / 2f + 12f, 14f, z + 10f, setOf(Side.FRONT, Side.BACK, Side.LEFT, Side.RIGHT, Side.TOP))
        for (sx in listOf(-1f, 1f)) bt.box(x + sx * (w / 2f + 5f) - 7f, 14f, z - 8f, x + sx * (w / 2f + 5f) + 7f, 14f + h + 8f, z + 8f, setOf(Side.FRONT, Side.BACK, Side.LEFT, Side.RIGHT, Side.TOP))
        b.box(m, x - w / 2f - 14f, 14f + h + 8f, z - 10f, x + w / 2f + 14f, 14f + h + 13f, z + 10f, Paint.CONCRETE, 4)
        b.box(m, x - w / 2f, 14f, z - 5f, x + w / 2f, 14f + h, z + 5f, Paint.WHITE, 5)
        val sm = b.sign("road")
        val lines = listOf("SUNDAY 10 AM", "ALL WELCOME")
        b.texts += info.signName; b.texts += lines
        val face = b.pic("monument${info.signName}") { PixImg(tx(w), tx(h)).also { im ->
            im.fill(c(0xF2EAD6)); im.border(c(0x6A3E2A))
            val top = im.h * 9 / 20
            val u = max(1, im.h / 30)
            // the cross and the name over the board
            val cx = 10 + 3 * u
            im.rect(cx - u, 3, cx + u, top - 3, c(0xC89A38)); im.rect(cx - 3 * u, 3 + (top - 6) / 4, cx + 3 * u, 3 + (top - 6) / 4 + 2 * u - 1, c(0xC89A38))
            StoreSigns.letters(im, info.signName, cx + 6 * u, 4, im.w - 6, top - 3, null, null, 3) { _, _ -> c(0x5A2620) }
            val lb = PixImg(im.w - 12, im.h - top - 6).also { StoreSigns.letterBoard(it, lines, true) }
            for (yy in 0 until lb.h) for (xx in 0 until lb.w) { im[6 + xx, top + 2 + yy] = lb[xx, yy]; im.mask[(top + 2 + yy) * im.w + 6 + xx] = lb.mask[yy * lb.w + xx] }
            im.glow(1, 1, im.w - 2, top - 2, 90)
        } }
        CinemaKit.face(sm, face, x, 14f + h / 2f, z + 5.1f, w, h, Side.FRONT)
        CinemaKit.face(sm, face, x, 14f + h / 2f, z - 5.1f, w, h, Side.BACK)
        // shrubs at its foot
        val shrub = b.pic("monShrub") { Paint.strip(24, 12, Paint.LEAF, 2, ends = false) }
        for (sx in listOf(-1f, 1f)) m.upright(x + sx * (w / 2f + 26f), z, 0f, 16f, 11f, 6f, 8, shrub, cap = b.pic("monShrubCap") { Paint.cap(12f, Paint.LEAF) })
        b.lights += CinemaLight(x, 6f, min(-2f, z + 30f), 1f, .92f, .8f, 1500f, 60f)
    }

    /** The seventies-eighties supermarket's pole sign: a steel pole, a backlit box with the name, a reader board under it. */
    private fun poleSign(b: StoreB, info: StoreInfo, x: Float, z: Float) {
        val m = b.det("sign")
        val closed = info.state == StoreState.CLOSED
        val H = 300f; val w = 220f; val h = 80f
        m.upright(x, z, 0f, H - h, 6f, 6f, 10, Paint.strip(Paint.stripSize(6f, H).first, 60, Paint.STEEL, 3), cap = null)
        b.box(m, x - 14f, 0f, z - 14f, x + 14f, 10f, z + 14f, Paint.CONCRETE, 2)
        b.box(m, x - w / 2f - 3f, H - h - 3f, z - 9f, x + w / 2f + 3f, H + 3f, z + 9f, Paint.BROWN, 4, bottom = true)
        val face = b.pic("pole$closed") { PixImg(tx(w), tx(h)).also { im ->
            im.fill(c(0xF6F2E6))
            if (closed) { im.fill(c(0x3A3A40)); for (k in 0 until 6) Pix.line(im, k * 30, 0, k * 30 + 40, im.h - 1, c(0x5A5A60)) }
            else { StoreSigns.letters(im, info.signName, 6, 6, im.w - 7, im.h - 7, null, null, 6) { r, hh -> if (info.year >= 1980) c(0xC82A2A) else c(0x8A3A14) }; CinemaKit.glowAll(im, 230) }
            im.border(Paint.BROWN.o)
        } }
        val sm = if (closed) m else b.sign("road")
        CinemaKit.face(sm, face, x, H - h / 2f, z + 9.1f, w, h, Side.FRONT)
        CinemaKit.face(sm, face, x, H - h / 2f, z - 9.1f, w, h, Side.BACK)
        val reader = b.pic("reader$closed") { PixImg(tx(180f), tx(36f)).also { im ->
            im.fill(c(0xF6F6F0)); im.border(c(0x2A2A30))
            if (!closed && info.state == StoreState.REUSED) { StoreSigns.letters(im, "WELCOME", 4, 4, im.w - 5, im.h - 5, null, null, 3) { _, _ -> c(0x1A1A22) }; CinemaKit.glowAll(im, 180) }
            else if (!closed) { StoreSigns.letters(im, "DOUBLE COUPONS", 4, 3, im.w - 5, im.h / 2 - 1, null, null, 2) { _, _ -> c(0x1A1A22) }; StoreSigns.letters(im, "OPEN 7 TO 11", 4, im.h / 2 + 1, im.w - 5, im.h - 4, null, null, 2) { _, _ -> c(0xC82A2A) }; CinemaKit.glowAll(im, 180) }
            else StoreSigns.letters(im, "FOR LEASE", 4, 4, im.w - 5, im.h - 5, null, null, 3) { _, _ -> c(0x1A1A6A) }
        } }
        b.box(m, x - 92f, H - h - 60f, z - 5f, x + 92f, H - h - 20f, z + 5f, Paint.STEEL, 6, bottom = true)
        CinemaKit.face(sm, reader, x, H - h - 40f, z + 5.1f, 180f, 36f, Side.FRONT)
        CinemaKit.face(sm, reader, x, H - h - 40f, z - 5.1f, 180f, 36f, Side.BACK)
    }

    /** The superstore's pylon at the road: two clad legs, a tall cabinet with the name lit both ways, OPEN 24 HOURS under it. */
    private fun pylon(b: StoreB, info: StoreInfo, x: Float, z: Float) {
        val m = b.det("sign")
        val closed = info.state == StoreState.CLOSED
        val H = 420f; val w = 280f; val legW = 40f
        val leg = b.tile(StoreKit.blockTile(Ramp(c(0x3A3630), c(0x8A8074), c(0xA0968A), c(0xB4AA9E), c(0xC8C0B4)), 9))
        for (dx in listOf(-w / 2f + legW / 2f, w / 2f - legW / 2f)) leg.box(x + dx - legW / 2f, 0f, z - 18f, x + dx + legW / 2f, H - 120f, z + 18f, setOf(Side.FRONT, Side.BACK, Side.LEFT, Side.RIGHT))
        m.box(x - w / 2f - 6f, H - 124f, z - 22f, x + w / 2f + 6f, H + 6f, z + 22f) { sd, ww, hh -> when (sd) { Side.FRONT, Side.BACK -> b.swatch(Paint.GREY.d); Side.TOP, Side.BOTTOM -> b.swatch(Paint.GREY.b); else -> b.panel(ww, hh, Paint.GREY, 3) } }
        CinemaKit.ledge(m, x - w / 2f - 10f, H + 6f, z - 26f, x + w / 2f + 10f, H + 14f, z + 26f, Paint.GREY, bottom = true)
        val name = info.signName.uppercase()
        val under = if (info.state == StoreState.REUSED) "WELCOME" else StoreMarkets.facade(info).under
        b.texts += listOf(name, under)
        val face = b.pic("pylon$closed$under") { PixImg(tx(w), tx(116f)).also { im ->
            im.fill(if (closed) c(0x2A2A30) else c(0x1E5A3A).let { if (info.seed % 4 == 1) c(0x8A1A1A) else if (info.seed % 4 == 2) c(0x1E285A) else if (info.seed % 4 == 3) c(0x9A4A12) else it })
            if (!closed) {
                StoreSigns.letters(im, name, 6, 6, im.w - 7, im.h * 6 / 10, null, null, 8) { _, _ -> c(0xFFFFFF) }
                StoreSigns.letters(im, under, 10, im.h * 6 / 10 + 4, im.w - 11, im.h - 8, null, null, 3) { _, _ -> c(0xF6D83A) }
                CinemaKit.glowAll(im, 230)
            } else for (k in 0 until 8) Pix.line(im, k * 40, 0, k * 40 + 50, im.h - 1, c(0x4A4A50))
            im.border(c(0x1A1A1E))
        } }
        val sm = if (closed) m else b.sign("road")
        CinemaKit.face(sm, face, x, H - 60f, z + 22.1f, w, 116f, Side.FRONT)
        CinemaKit.face(sm, face, x, H - 60f, z - 22.1f, w, 116f, Side.BACK)
        val hours = b.pic("hours$closed") { PixImg(tx(w - legW * 2f - 10f), tx(36f)).also { im -> im.fill(c(0xF6F6F0)); im.border(c(0x2A2A30)); StoreSigns.letters(im, if (closed) "CLOSED" else if (info.year >= 1997) "OPEN 24 HOURS" else "OPEN 7 AM TO 11 PM", 4, 4, im.w - 5, im.h - 5, null, null, 3) { _, _ -> if (closed) c(0x8A1A1A) else c(0x1A1A22) }; if (!closed) CinemaKit.glowAll(im, 190) } }
        b.box(m, x - w / 2f + legW, H - 190f, z - 8f, x + w / 2f - legW, H - 146f, z + 8f, Paint.STEEL, 4, bottom = true)
        CinemaKit.face(sm, hours, x, H - 168f, z + 8.1f, w - legW * 2f - 10f, 36f, Side.FRONT)
        CinemaKit.face(sm, hours, x, H - 168f, z - 8.1f, w - legW * 2f - 10f, 36f, Side.BACK)
        // the planted bed round its feet
        CinemaKit.ledge(b.det("ground:kerb"), x - w / 2f - 30f, 0f, z - 34f, x + w / 2f + 30f, 6f, z + 34f, CURB)
        pave(b, GRASS, x - w / 2f - 28f, z - 32f, x + w / 2f + 28f, z + 32f, 6.2f)
    }

    // ------------------------------------------------------------------ the superstore's garden centre

    private fun gardenCentre(b: StoreB, info: StoreInfo, s: Site) {
        val g = s.keep.getOrNull(1) ?: return
        val x0 = g[0] + 4f; val x1 = g[2]; val zb = g[1] + 4f; val zf = g[3] - 4f
        val closed = info.state != StoreState.OPEN
        val m = b.det("garden")
        pave(b, CONCRETE, x0, zb, x1, zf, .3f)
        // the black steel fence on three sides (the store's wall the fourth), a gate in front
        val fence = b.tile(PICKET)
        val H = 72f
        for ((a, e) in listOf(floatArrayOf(x0, zf) to floatArrayOf(x0 + (x1 - x0) * .35f, zf), floatArrayOf(x0 + (x1 - x0) * .65f, zf) to floatArrayOf(x1, zf), floatArrayOf(x0, zb) to floatArrayOf(x0, zf), floatArrayOf(x0, zb) to floatArrayOf(x1, zb))) {
            fence.quad(v(a[0], H, a[1]), v(e[0], H, e[1]), v(e[0], 0f, e[1]), v(a[0], 0f, a[1]))
            fence.quad(v(e[0], H, e[1]), v(a[0], H, a[1]), v(a[0], 0f, a[1]), v(e[0], 0f, e[1]))
            val n = max(1, ((abs(e[0] - a[0]) + abs(e[1] - a[1])) / 60f).toInt())
            for (k in 0..n) { val t = k / n.toFloat(); m.beam(v(a[0] + (e[0] - a[0]) * t, 0f, a[1] + (e[1] - a[1]) * t), v(a[0] + (e[0] - a[0]) * t, H + 4f, a[1] + (e[1] - a[1]) * t), 3f, b.swatch(c(0x1E1E26))) }
        }
        // the gate's posts and its sign
        val gx0 = x0 + (x1 - x0) * .35f; val gx1 = x0 + (x1 - x0) * .65f
        for (x in listOf(gx0, gx1)) b.box(m, x - 5f, 0f, zf - 5f, x + 5f, 110f, zf + 5f, Paint.BLACK, 3)
        val gs = b.pic("gsign$closed") { PixImg(tx(gx1 - gx0), tx(22f)).also { im -> im.fill(c(0x1E5A3A)); im.border(c(0x0E2A1A)); StoreSigns.letters(im, "GARDEN CENTER", 3, 3, im.w - 4, im.h - 4, null, null, 2) { _, _ -> if (closed) c(0x6A8A7A) else c(0xF6F6E0) }; if (!closed) CinemaKit.glowAll(im, 200, c(0x1E5A3A)) } }
        b.box(m, gx0, 88f, zf - 3f, gx1, 110f, zf + 3f, Paint.BLACK, 4, bottom = true)
        CinemaKit.face(if (closed) m else b.sign("garden"), gs, (gx0 + gx1) / 2f, 99f, zf + 3.1f, gx1 - gx0, 22f, Side.FRONT)
        // a shade roof over the back half on steel posts (dark green shade cloth)
        val shade = b.pic("shade") { PixImg(16, 16).also { im -> im.fill(c(0x1E3A2A)); for (k in 0 until 16 step 2) { im.hline(0, 15, k, c(0x2A4A36)) } } }
        val sz0 = zb + 10f; val sz1 = (zb + zf) / 2f
        b.tile(shade).quad(v(x0 + 6f, 120f, sz0), v(x1 - 4f, 120f, sz0), v(x1 - 4f, 120f, sz1), v(x0 + 6f, 120f, sz1))
        b.tile(shade).quad(v(x0 + 6f, 119.8f, sz1), v(x1 - 4f, 119.8f, sz1), v(x1 - 4f, 119.8f, sz0), v(x0 + 6f, 119.8f, sz0))
        for (x in listOf(x0 + 10f, (x0 + x1) / 2f, x1 - 10f)) for (z in listOf(sz0 + 4f, sz1 - 4f)) m.beam(v(x, 0f, z), v(x, 120f, z), 3f, b.swatch(Paint.STEEL.l))
        if (closed) return
        // plant tables in rows, pots of flowers on them; pallets of bagged mulch; young trees in pots by the fence
        val bloom = b.pic("bloom") { PixImg(32, 12).also { im -> val r = Paint.Rnd(3); im.fill(Paint.LEAF.d); for (y in 0 until 12) for (x in 0 until 32) if (r.next() < .35f) im[x, y] = listOf(c(0xE83A6A), c(0xF6D83A), c(0xF0F0F0), c(0x8A4AC8), c(0xE86A2A), Paint.LEAF.l)[r.int(6)] } }
        val tableTop = b.pic("ttop") { PixImg(32, 12).also { im -> val r = Paint.Rnd(7); for (y in 0 until 12) for (x in 0 until 32) im[x, y] = if ((x / 3 + y / 3) % 2 == 0) Paint.LEAF.b else if (r.next() < .4f) listOf(c(0xE83A6A), c(0xF6D83A), c(0xF0F0F0), c(0x8A4AC8))[r.int(4)] else Paint.LEAF.l } }
        var tz = sz0 + 30f
        while (tz < zf - 40f) {
            var tx0 = x0 + 30f
            while (tx0 + 80f < x1 - 20f) {
                m.box(tx0, 0f, tz - 14f, tx0 + 80f, 30f, tz + 14f) { sd, _, _ -> when (sd) { Side.BOTTOM -> null; Side.TOP -> tableTop; else -> bloom } }
                tx0 += 110f
            }
            tz += 80f
        }
        val bag = Ramp(c(0x3A2A1A), c(0x6A4A2A), c(0x8A5A32), c(0xA8743E), c(0xC8945A))
        for (k in 0 until 3) { val px = x1 - 50f - k * 46f; pallet(b, m, px, zf - 30f, Paint.Rnd(k), 0); b.box(m, px - 18f, 5f, zf - 48f, px + 18f, 29f, zf - 12f, bag, 3 + k) }
        for (k in 0 until 4) tree(b, x0 + 30f, zb + 40f + k * 50f, .45f, k)
    }
}
