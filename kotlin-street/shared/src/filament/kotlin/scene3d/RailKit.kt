package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.lifemax.rpg.feature.habits.domain.RailCar
import java.util.Base64
import java.util.zip.Inflater
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The railroad's meshes and pictures (docs/RAIL.md), made by code like the street props (no engine in here): the track
 * (ballast on a low bank, the pack's tie-and-rail tile laid along the line, steel rails standing on it), the old bed and
 * the rail trail where the rails are gone, the level crossings (timber planks over the road, crossbucks, flashing lights,
 * gates whose arms swing), the brick depot with its platform, and the trains of each era (a steam engine and tender,
 * diesels in the railroads' and the national service's colours, a modern train; coaches cut from the pack's train cars;
 * boxcars, hoppers, tank cars and a caboose). Street units; a texel is [TEXEL].
 */
internal object RailKit {
    private val pics = HashMap<String, PixImg>()

    /** The pack's piece [id] (tools/swatches/pack_rail.py), decoded once. */
    fun piece(id: String): PixImg = pics.getOrPut(id) {
        val s = RailPackData.ALL.first { it.id == id }
        val z = Base64.getDecoder().decode(s.data.joinToString(""))
        val inf = Inflater().apply { setInput(z) }
        val buf = ByteArray(1 + 256 * 4 + s.w * s.h)
        var n = 0
        while (!inf.finished() && n < buf.size) { val k = inf.inflate(buf, n, buf.size - n); if (k == 0 && inf.needsInput()) break; n += k }
        inf.end()
        val pn = (buf[0].toInt() and 255).let { if (it == 0) 256 else it }
        val pal = IntArray(pn) { i ->
            val r = buf[1 + i * 4].toInt() and 255; val g = buf[2 + i * 4].toInt() and 255; val b = buf[3 + i * 4].toInt() and 255; val a = buf[4 + i * 4].toInt() and 255
            if (a < 128) 0 else (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        PixImg(s.w, s.h).also { im -> for (i in 0 until s.w * s.h) im.px[i] = pal[buf[1 + pn * 4 + i].toInt() and 255] }
    }

    /** Rows [y0] until [y1] and columns [x0] until [x1] of [im] as a picture of their own. */
    fun crop(im: PixImg, x0: Int, y0: Int, x1: Int, y1: Int): PixImg = PixImg(x1 - x0, y1 - y0).also { o -> for (y in y0 until y1) for (x in x0 until x1) o[x - x0, y - y0] = im[x, y] }

    /** The first and last column holding any pixel. */
    private fun cols(im: PixImg, y0: Int, y1: Int): Pair<Int, Int> {
        var a = im.w; var b = 0
        for (y in y0 until y1) for (x in 0 until im.w) if (im[x, y] ushr 24 != 0) { a = min(a, x); b = max(b, x) }
        return a to b + 1
    }

    // ------------------------------------------------------------------ track

    /** Along the track, one tile of the pack's track (32 texels). */
    val TILE = 32f * TEXEL
    /** The tile's rows above and below the track's centre line (its rails at rows 8-9 and 48-49: centred on 29). */
    private const val ROW0 = 29
    private const val BALLAST_TOP = 4f
    private const val BALLAST_HALF = 46f
    private const val BANK_HALF = 64f
    const val RAIL_OFF = 20f * TEXEL
    const val RAIL_TOP = BALLAST_TOP + 3.4f

    private val ballastTile: PixImg by lazy { Swatches.get("ballast") }
    private fun ballastPic(w: Int, h: Int, seed: Int, tone: Int = 0): PixImg {
        val im = PixImg(w, h)
        for (y in 0 until h) for (x in 0 until w) im[x, y] = Swatches.at("ballast", x + seed * 7, y)
        if (tone != 0) for (i in im.px.indices) im.px[i] = Pix.mix(im.px[i], tone, .45f)
        return im
    }
    private val steel by lazy { Paint.swatch(Paint.c(0xB8BCCC)) }
    private val steelSide by lazy { Paint.swatch(Paint.c(0x5E6278)) }

    /** Resamples [pts] (x, z pairs) every [step] along it: points and unit normals (left of the way it runs: (-dz, dx)). */
    private fun resample(pts: FloatArray, step: Float): Pair<FloatArray, FloatArray> {
        val n = pts.size / 2
        val cum = FloatArray(n); for (i in 1 until n) cum[i] = cum[i - 1] + hypot(pts[i * 2] - pts[i * 2 - 2], pts[i * 2 + 1] - pts[i * 2 - 1])
        val L = cum[n - 1]
        val k = max(1, (L / step).roundToInt())
        val out = FloatArray((k + 1) * 2); val nor = FloatArray((k + 1) * 2)
        var seg = 0
        for (j in 0..k) {
            val s = L * j / k
            while (seg < n - 2 && cum[seg + 1] < s) seg++
            val l = (cum[seg + 1] - cum[seg]).coerceAtLeast(1e-6f)
            val f = ((s - cum[seg]) / l).coerceIn(0f, 1f)
            out[j * 2] = pts[seg * 2] + (pts[seg * 2 + 2] - pts[seg * 2]) * f
            out[j * 2 + 1] = pts[seg * 2 + 1] + (pts[seg * 2 + 3] - pts[seg * 2 + 1]) * f
        }
        for (j in 0..k) {
            val a = max(0, j - 1); val b = min(k, j + 1)
            val dx = out[b * 2] - out[a * 2]; val dz = out[b * 2 + 1] - out[a * 2 + 1]; val l = hypot(dx, dz).coerceAtLeast(1e-6f)
            nor[j * 2] = -dz / l; nor[j * 2 + 1] = dx / l
        }
        return out to nor
    }

    /**
     * A stretch of line along [pts]: its [state] (track: ballast, ties and rails; bed: the bare ballast grown over; trail:
     * a gravel path on the old bed), [concrete] ties (the modern line) or wooden ones. World coordinates.
     */
    fun track(m: MeshBuilder, pts: FloatArray, state: String, concrete: Boolean, siding: Boolean = false, wet: (Float, Float) -> Boolean = { _, _ -> false }) {
        if (pts.size < 4 || state == "none") return
        val (p, nr) = resample(pts, TILE)
        val k = p.size / 2 - 1
        val half = if (siding) BALLAST_HALF * .8f else BALLAST_HALF
        val tone = when (state) { "bed" -> Paint.c(0x6E8A4A); "trail" -> Paint.c(0xC8B48E); else -> 0 }
        val bankW = max(2, ((BANK_HALF - half) / TEXEL).roundToInt())
        val topW = max(2, (2 * half / TEXEL).roundToInt())
        val topPic = ballastPic(32, topW, 1, tone); val bankPic = ballastPic(32, bankW, 2, if (tone != 0) tone else Paint.c(0x5C6A44))
        val tie = if (state == "track") piece(if (concrete) "track_concrete" else "track_wood") else null
        val y = if (state == "trail") BALLAST_TOP - 1.5f else BALLAST_TOP
        fun v(j: Int, off: Float, yy: Float) = floatArrayOf(p[j * 2] + nr[j * 2] * off, yy, p[j * 2 + 1] + nr[j * 2 + 1] * off)
        val girder = Paint.panel(32, 12, Paint.IRON, 3).also { im -> for (x in 0 until 32 step 8) im.vline(x, 1, 10, Paint.IRON.l) }
        val pier = Paint.CONCRETE
        for (j in 0 until k) {
            val over = wet(p[j * 2], p[j * 2 + 1]) || wet(p[j * 2 + 2], p[j * 2 + 3])
            // the bank's top, then its two slopes down to the ground (the left one first: normal (-dz, dx) is the left);
            // over water a deck girder bridge instead: the deck on two plate girders, a concrete pier every few spans
            m.quad(v(j, -half, y), v(j + 1, -half, y), v(j + 1, half, y), v(j, half, y), topPic)
            if (!over) {
                m.quad(v(j, -BANK_HALF, 0f), v(j + 1, -BANK_HALF, 0f), v(j + 1, -half, y), v(j, -half, y), bankPic)
                m.quad(v(j, half, y), v(j + 1, half, y), v(j + 1, BANK_HALF, 0f), v(j, BANK_HALF, 0f), bankPic)
            } else {
                for (side in floatArrayOf(-half, half)) {
                    val a = v(j, side, y); val b = v(j + 1, side, y)
                    // (the girder's outer face, seen from its side)
                    if (side < 0) m.quad(a, b, v(j + 1, side, y - 12f), v(j, side, y - 12f), girder) else m.quad(b, a, v(j, side, y - 12f), v(j + 1, side, y - 12f), girder)
                }
                m.quad(v(j, half, y - 12f), v(j + 1, half, y - 12f), v(j + 1, -half, y - 12f), v(j, -half, y - 12f), Paint.swatch(Paint.IRON.o))
                if (j % 4 == 0) {
                    val cx = p[j * 2]; val cz = p[j * 2 + 1]
                    m.upright(cx, cz, -14f, y - 12f, 16f, 18f, 8, Paint.strip(Paint.stripSize(16f, 20f).first, Paint.stripSize(16f, 20f).second, pier, j), cap = null)
                }
            }
            if (tie != null) m.quad(v(j, -ROW0 * TEXEL, y + .3f), v(j + 1, -ROW0 * TEXEL, y + .3f), v(j + 1, (64 - ROW0) * TEXEL, y + .3f), v(j, (64 - ROW0) * TEXEL, y + .3f), tie)
            else if (state == "bed" && (j * 7919) % 5 == 0) {
                // a few rotten ties left in the grass
                val b = piece("track_broken")
                m.quad(v(j, -ROW0 * TEXEL, y + .2f), v(j + 1, -ROW0 * TEXEL, y + .2f), v(j + 1, (64 - ROW0) * TEXEL, y + .2f), v(j, (64 - ROW0) * TEXEL, y + .2f), b, 0f, 0f, 32f, 64f)
            }
        }
        if (tie != null) {
            // the rails standing on the ties: a steel head on a dark web, along the original points (fewer, longer pieces)
            val (q, qn) = resample(pts, TILE * 3)
            val kq = q.size / 2 - 1
            for (side in floatArrayOf(-RAIL_OFF, RAIL_OFF)) for (j in 0 until kq) {
                val a = floatArrayOf(q[j * 2] + qn[j * 2] * side, y + 2.2f, q[j * 2 + 1] + qn[j * 2 + 1] * side)
                val b = floatArrayOf(q[j * 2 + 2] + qn[j * 2 + 2] * side, y + 2.2f, q[j * 2 + 3] + qn[j * 2 + 3] * side)
                m.beam(a, b, 2.2f, steelSide)
                m.beam(floatArrayOf(a[0], a[1] + 1.4f, a[2]), floatArrayOf(b[0], b[1] + 1.4f, b[2]), 1.4f, steel)
            }
        }
    }

    // ------------------------------------------------------------------ level crossings

    private val WHITE = Paint.WHITE
    private val BLACKR = Paint.BLACK

    /**
     * The crossing's timber deck: where the track's bed ([tx], [tz] its way, [bed] out each side) runs over the road ([rx],
     * [rz] its way, [halfRoad] out each side) at ([x], [z]): a parallelogram with the road's and the bed's edges.
     */
    fun crossingDeck(m: MeshBuilder, x: Float, z: Float, tx: Float, tz: Float, rx: Float, rz: Float, halfRoad: Float, bed: Float = 40f) {
        val sin = kotlin.math.abs(rx * tz - rz * tx).coerceAtLeast(.25f)
        val a = halfRoad / sin; val b = bed / sin
        val y = BALLAST_TOP + .6f
        fun p(v: Float, u: Float) = floatArrayOf(x + tx * v + rx * u, y, z + tz * v + rz * u)
        val iw = max(2, (2 * a / TEXEL).roundToInt()); val ih = max(2, (2 * b / TEXEL).roundToInt())
        val im = PixImg(iw, ih).also { Swatches.fill(it, "plank_horizontal"); it.border(Paint.c(0x3A2A22)) }
        // (corners in turn so the face looks up whichever way the two run)
        val turn = tx * rz - tz * rx
        if (turn > 0f) m.quad(p(-a, -b), p(a, -b), p(a, b), p(-a, b), im)
        else m.quad(p(-a, b), p(a, b), p(a, -b), p(-a, -b), im)
    }

    /** A crossbuck sign post (local: post at the origin, the sign faces +z), with flashing lights under it if [lights]. */
    fun crossbuck(lights: Boolean): PropMesh {
        val m = MeshBuilder()
        val post = Paint.strip(Paint.stripSize(1.6f, 80f).first, Paint.stripSize(1.6f, 80f).second, WHITE, 3)
        m.upright(0f, 0f, 0f, 80f, 1.6f, 1.4f, 8, post, cap = null)
        // the X: two white boards crossed, black letters
        val bw = (44f / TEXEL).roundToInt(); val bh = (7f / TEXEL).roundToInt()
        fun board(text: String): PixImg = Paint.panel(bw, bh, WHITE, 5).also { im ->
            im.border(Paint.OUT)
            val tw = PixImg.textWidth(text); im.text(text, (bw - tw) / 2, (bh - 5) / 2, Paint.BLACK.b)
        }
        val b1 = board("RAILROAD"); val b2 = board("CROSSING")
        for ((i, b) in listOf(b1, b2).withIndex()) {
            val mark = m.mark
            m.box(-22f, 66f, .8f + i * .7f, 22f, 73f, 1.6f + i * .7f) { s, _, _ -> when (s) { MeshBuilder.Side.FRONT -> b; MeshBuilder.Side.BACK -> Paint.panel(bw, bh, WHITE, 9); else -> Paint.swatch(WHITE.d) } }
            // (turned about the z axis: a board each way, 35 degrees off level)
            rotateZ(m, mark, 0f, 69.5f, if (i == 0) 35f else -35f)
        }
        if (lights) {
            // the crossbar with two lamp heads (their glass is drawn by [lamp], which flashes)
            m.box(-16f, 50f, 1f, 16f, 53f, 3f) { s, w, h -> if (s == MeshBuilder.Side.BOTTOM) null else Paint.panel(w, h, BLACKR, 2) }
            for (sx in floatArrayOf(-12f, 12f)) {
                m.upright(sx, 4f, 42f, 50f, 5f, 5f, 10, null, swatch = Paint.swatch(BLACKR.b), cap = Paint.cap(5f, BLACKR))
                // the hood over each lamp
                m.box(sx - 6f, 48f, 4f, sx + 6f, 49.5f, 11f) { s, _, _ -> if (s == MeshBuilder.Side.BOTTOM) null else Paint.swatch(BLACKR.d) }
            }
        }
        return m.build()
    }

    /** One lamp's glass of a crossbuck's lights ([left] or right head), facing +z, glowing red when lit. */
    fun lamp(left: Boolean, lit: Boolean): PropMesh {
        val m = MeshBuilder()
        val s = (8f / TEXEL).roundToInt()
        val im = PixImg(s, s)
        val c = (s - 1) / 2f
        for (y in 0 until s) for (x in 0 until s) {
            val d = kotlin.math.sqrt((x - c) * (x - c) + (y - c) * (y - c))
            if (d <= c + .3f) im[x, y] = if (!lit) Paint.c(0x4A1A2A) else if (d < c * .45f) Paint.c(0xFFD0B0) else Paint.c(0xE63F38)
        }
        if (lit) im.glow(0, 0, s - 1, s - 1)
        val sx = if (left) -12f else 12f
        m.quad(floatArrayOf(sx - 4f, 50f, 9.1f), floatArrayOf(sx + 4f, 50f, 9.1f), floatArrayOf(sx + 4f, 42f, 9.1f), floatArrayOf(sx - 4f, 42f, 9.1f), im)
        return m.build()
    }

    /** A gate's base (local: at the origin, the arm's pivot 40 up, the arm along +x when down). */
    fun gateBase(): PropMesh {
        val m = MeshBuilder()
        m.panelBox(-6f, 0f, -6f, 6f, 34f, 6f, Paint.GREY, 4)
        m.box(-3f, 34f, -3f, 3f, 44f, 3f) { s, w, h -> if (s == MeshBuilder.Side.BOTTOM) null else Paint.panel(w, h, BLACKR, 6) }
        return m.build()
    }

    /** A gate's arm (local: the pivot at the origin, the arm out along +x [length]), red and white stripes, a lamp at its tip. */
    fun gateArm(length: Float): PropMesh {
        val m = MeshBuilder()
        val w = max(4, (length / TEXEL).roundToInt()); val h = max(3, (3f / TEXEL).roundToInt())
        val im = PixImg(w, h)
        val stripe = (12f / TEXEL).roundToInt().coerceAtLeast(2)
        for (x in 0 until w) for (y in 0 until h) im[x, y] = if ((x / stripe) % 2 == 0) Paint.RED.b else Paint.WHITE.l
        im.hline(0, w - 1, 0, Paint.OUT); im.hline(0, w - 1, h - 1, Paint.OUT)
        m.box(0f, -1.5f, -1.2f, length, 1.5f, 1.2f) { s, _, _ -> when (s) { MeshBuilder.Side.FRONT, MeshBuilder.Side.BACK -> im; else -> Paint.swatch(Paint.WHITE.b) } }
        // the counterweight behind the pivot
        m.box(-10f, -3f, -2f, -2f, 3f, 2f) { s, ww, hh -> if (s == MeshBuilder.Side.BOTTOM) null else Paint.panel(ww, hh, BLACKR, 3) }
        return m.build()
    }

    /** Turns the faces since [mark] about the z axis through ([cx], [cy]) by [deg] (counter-clockwise seen from +z). */
    private fun rotateZ(m: MeshBuilder, mark: Int, cx: Float, cy: Float, deg: Float) = m.transformSince(mark) { p ->
        val a = Math.toRadians(deg.toDouble()); val c = kotlin.math.cos(a).toFloat(); val s = kotlin.math.sin(a).toFloat()
        val x = p[0] - cx; val y = p[1] - cy
        p[0] = cx + x * c - y * s; p[1] = cy + x * s + y * c
    }

    // ------------------------------------------------------------------ the depot

    /** The depot's walls: length along the track and depth (street units: about 14 by 4.5 m). */
    const val DEPOT_L = 300f
    const val DEPOT_D = 96f

    /**
     * The temporary depot (until the hero model comes): a brick station with a hipped slate roof on deep eaves, windows and
     * doors both sides, the town's name on boards at both ends, a platform out to the track. Local: x along the track,
     * +z towards it, the ground at y 0, the building's middle at the origin; [toTrack] how far the track's centre is.
     * [state]: open (lit at night), closed (boarded), reused (lit, another sign).
     */
    fun depot(name: String, state: String, toTrack: Float, reuse: String = "MUSEUM"): PropMesh {
        val m = MeshBuilder()
        val L = DEPOT_L; val D = DEPOT_D; val H = 72f
        val brick = { w: Int, h: Int, seed: Int ->
            PixImg(w, h).also { im -> Swatches.fill(im, "brick_red", ox = seed * 13); im.hline(0, w - 1, h - 1, Paint.c(0x4A2A2A)); im.border(Paint.c(0x4A2A2A)) }
        }
        val glassOn = state != "closed"
        fun windows(im: PixImg, n: Int, door: Int) {
            val ww = (22f / TEXEL).roundToInt(); val wh = (32f / TEXEL).roundToInt(); val sill = (24f / TEXEL).roundToInt()
            for (i in 0 until n) {
                val cx = ((i + .5f) / n * im.w).roundToInt()
                val isDoor = i == door
                val w = if (isDoor) (26f / TEXEL).roundToInt() else ww
                val h = if (isDoor) (52f / TEXEL).roundToInt() else wh
                val x0 = cx - w / 2; val y1 = im.h - 1 - (if (isDoor) 0 else sill); val y0 = y1 - h
                im.rect(x0 - 1, y0 - 2, x0 + w, y1, Paint.c(0x3A2A2A))
                // a stone lintel over each
                im.rect(x0 - 2, y0 - 4, x0 + w + 1, y0 - 2, Paint.c(0xD8CCB0))
                if (isDoor) {
                    im.rect(x0, y0, x0 + w - 1, y1, if (state == "closed") Paint.c(0x6B5052) else Paint.c(0x5A3E36))
                    im.rect(x0 + 2, y0 + 2, x0 + w - 3, y0 + h / 3, if (glassOn) Paint.c(0x9FD8E0) else Paint.c(0x6B5052))
                    if (glassOn) im.glow(x0 + 2, y0 + 2, x0 + w - 3, y0 + h / 3, 200)
                } else if (state == "closed") {
                    // boarded up: planks across
                    for (y in y0 until y1) for (x in x0 until x0 + w) im[x, y] = if ((y - y0) % 4 == 3) Paint.c(0x5E4030) else Paint.c(0xA87A50)
                } else {
                    im.rect(x0, y0, x0 + w - 1, y1 - 1, Paint.c(0x7FB8C8))
                    im.rect(x0 + w / 2, y0, x0 + w / 2, y1 - 1, Paint.c(0xE8E0D0)); im.hline(x0, x0 + w - 1, y0 + h / 2, Paint.c(0xE8E0D0))
                    im.glow(x0, y0, x0 + w - 1, y1 - 1, 220)
                }
                im.rect(x0 - 2, y1, x0 + w + 1, y1 + 1, Paint.c(0xD8CCB0))
            }
        }
        m.box(-L / 2, 0f, -D / 2, L / 2, H, D / 2) { s, w, h ->
            when (s) {
                MeshBuilder.Side.FRONT -> brick(w, h, 1).also { windows(it, 6, 2) }
                MeshBuilder.Side.BACK -> brick(w, h, 2).also { windows(it, 5, 2) }
                MeshBuilder.Side.LEFT, MeshBuilder.Side.RIGHT -> brick(w, h, 3).also { windows(it, 2, -1) }
                MeshBuilder.Side.TOP -> null
                MeshBuilder.Side.BOTTOM -> null
            }
        }
        // a stone base course
        m.box(-L / 2 - 1.5f, 0f, -D / 2 - 1.5f, L / 2 + 1.5f, 6f, D / 2 + 1.5f) { s, w, h -> if (s == MeshBuilder.Side.BOTTOM || s == MeshBuilder.Side.TOP) null else Paint.panel(w, h, Paint.CONCRETE, 7) }
        // the roof: hipped, deep eaves (deepest on the track side, over the platform), slate
        val ex = 26f; val ezB = 26f; val ezF = 52f; val ridge = H + 46f
        val x0 = -L / 2 - ex; val x1 = L / 2 + ex; val z0 = -D / 2 - ezB; val z1 = D / 2 + ezF
        val rz = (z0 + z1) / 2f
        val rx0 = x0 + (z1 - z0) / 2f; val rx1 = x1 - (z1 - z0) / 2f
        val eave = H - 2f
        fun slate(pts: List<FloatArray>, n: FloatArray) {
            var minX = Float.MAX_VALUE; var minV = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
            // (laid by the face's own axes: u along its foot, v up its slope)
            val u = norm(sub(pts[1], pts[0])); val nn = norm(n); val vv = norm(cross(nn, u))
            for (p in pts) { val a = dot(p, u); val b = -dot(p, vv); minX = min(minX, a); maxX = max(maxX, a); minV = min(minV, b); maxV = max(maxV, b) }
            val w = max(2, ((maxX - minX) / TEXEL).roundToInt() + 1); val h = max(2, ((maxV - minV) / TEXEL).roundToInt() + 1)
            val im = PixImg(w, h); Swatches.fill(im, "roof_slate")
            im.hline(0, w - 1, h - 1, Paint.c(0x2E3040)); im.hline(0, w - 1, h - 2, Paint.c(0x2E3040))
            val origin = floatArrayOf(u[0] * minX - vv[0] * minV, u[1] * minX - vv[1] * minV, u[2] * minX - vv[2] * minV)
            m.flat(pts, im, origin, u, floatArrayOf(-vv[0], -vv[1], -vv[2]), nn)
        }
        val A = floatArrayOf(x0, eave, z1); val B = floatArrayOf(x1, eave, z1); val C = floatArrayOf(x1, eave, z0); val Dd = floatArrayOf(x0, eave, z0)
        val R0 = floatArrayOf(rx0, ridge, rz); val R1 = floatArrayOf(rx1, ridge, rz)
        // front (track side, +z), back, the two hip ends
        slate(listOf(A, B, R1, R0), normal(A, B, R1))
        slate(listOf(C, Dd, R0, R1), normal(C, Dd, R0))
        slate(listOf(B, C, R1), normal(B, C, R1))
        slate(listOf(Dd, A, R0), normal(Dd, A, R0))
        // the eaves' underside and fascia
        val soffit = Paint.swatch(Paint.c(0x6B5052))
        m.quad(floatArrayOf(x0, eave, z0), floatArrayOf(x1, eave, z0), floatArrayOf(x1, eave, z1), floatArrayOf(x0, eave, z1), soffit)
        m.box(x0, eave - 4f, z1 - 2f, x1, eave, z1) { s, w, h -> if (s == MeshBuilder.Side.FRONT) Paint.panel(w, h, Paint.c(0x2A5040).let { Ramp(Paint.OUT, it, Paint.c(0x2E6A50), Paint.c(0x3C8060), Paint.c(0x60A080)) }, 3) else null }
        // brackets under the track-side eave
        for (i in 0..4) {
            val bx = -L / 2 + 10f + i * (L - 20f) / 4f
            m.beam(floatArrayOf(bx, eave - 22f, D / 2), floatArrayOf(bx, eave - 1f, D / 2 + ezF - 6f), 3f, Paint.swatch(Paint.c(0x5A3E36)))
        }
        // a chimney
        m.panelBox(L / 4 - 7f, ridge - 22f, -7f, L / 4 + 7f, ridge + 16f, 7f, Paint.TERRACOTTA, 11)
        // the town's name on a board at each end, and over the track-side door
        val label = if (state == "reused") reuse else name.uppercase()
        val tw = PixImg.textWidth(label)
        val bw = max(tw + 8, (40f / TEXEL).roundToInt()); val bh = 11
        val board = PixImg(bw, bh).also { im -> im.fill(Paint.c(0x1F4A3A)); im.border(Paint.c(0xE8E0D0)); im.text(label, (bw - tw) / 2, 3, Paint.c(0xF2E8C8)) }
        val bwU = bw * TEXEL; val bhU = bh * TEXEL
        for (sx in floatArrayOf(-1f, 1f)) {
            val x = sx * (L / 2 + 1.2f)
            if (sx > 0) m.quad(floatArrayOf(x, 62f, bwU / 2), floatArrayOf(x, 62f, -bwU / 2), floatArrayOf(x, 62f - bhU, -bwU / 2), floatArrayOf(x, 62f - bhU, bwU / 2), board)
            else m.quad(floatArrayOf(x, 62f, -bwU / 2), floatArrayOf(x, 62f, bwU / 2), floatArrayOf(x, 62f - bhU, bwU / 2), floatArrayOf(x, 62f - bhU, -bwU / 2), board)
        }
        val fz = D / 2 + ezF - 1f
        m.quad(floatArrayOf(-bwU / 2, eave - 5f, fz), floatArrayOf(bwU / 2, eave - 5f, fz), floatArrayOf(bwU / 2, eave - 5f - bhU, fz), floatArrayOf(-bwU / 2, eave - 5f - bhU, fz), board)
        // the platform: brick paving from the wall out to the track's edge, a white edge line
        val pz0 = D / 2; val pz1 = toTrack - 40f
        if (pz1 > pz0 + 10f) {
            val pw = ((L + 160f) / TEXEL).roundToInt(); val ph = ((pz1 - pz0) / TEXEL).roundToInt()
            m.box(-L / 2 - 80f, 0f, pz0, L / 2 + 80f, 7f, pz1) { s, w, h ->
                when (s) {
                    MeshBuilder.Side.TOP -> PixImg(pw, ph).also { im -> Swatches.fill(im, "pavement"); im.hline(0, pw - 1, ph - 2, Paint.c(0xF2E8C8)); im.hline(0, pw - 1, ph - 1, Paint.c(0xF2E8C8)) }
                    MeshBuilder.Side.BOTTOM -> null
                    else -> Paint.panel(w, h, Paint.CONCRETE, 4)
                }
            }
            // a bench and a baggage cart on it
            m.panelBox(-60f, 7f, pz0 + 8f, -30f, 15f, pz0 + 16f, Paint.WOOD, 3)
            m.panelBox(40f, 7f, pz1 - 30f, 75f, 18f, pz1 - 14f, Paint.BROWN, 5)
        }
        return m.build()
    }

    private fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun norm(a: FloatArray): FloatArray { val l = kotlin.math.sqrt(dot(a, a)).coerceAtLeast(1e-9f); return floatArrayOf(a[0] / l, a[1] / l, a[2] / l) }
    /** The outward normal of a face whose corners run [a], [b], [c] as quad() takes them (seen from outside, clockwise). */
    private fun normal(a: FloatArray, b: FloatArray, c: FloatArray): FloatArray {
        // quad(): n = cross(d - a, b - a) for corners a b c d; for a, b, c (d after c) the same turn
        val n = cross(sub(c, b), sub(a, b))
        return norm(floatArrayOf(-n[0], -n[1], -n[2])).let { if (it[1] < 0f) floatArrayOf(-it[0], -it[1], -it[2]) else it }
    }

    // ------------------------------------------------------------------ the trains

    private val carCache = HashMap<String, PropMesh>()

    /** The mesh of a car (local: x along, its front at +x; y up from the rail's top; z across), cached by kind and livery. */
    fun car(kind: RailCar, livery: String = ""): PropMesh = carCache.getOrPut("$kind/$livery") {
        val m = MeshBuilder()
        val L = kind.length - 4f
        when (kind) {
            RailCar.COACH_ORANGE -> coach(m, L, "car_orange_mid")
            RailCar.COACH_GREEN -> coach(m, L, "car_green_mid")
            RailCar.COACH_GREY -> coach(m, L, "car_grey_mid")
            RailCar.STEAM -> steam(m, L)
            RailCar.TENDER -> tender(m, L)
            RailCar.DIESEL -> diesel(m, L, livery.ifEmpty { "road" })
            RailCar.NATIONAL -> diesel(m, L, "national")
            RailCar.MODERN -> modern(m, L)
            RailCar.BOXCAR -> boxcar(m, L, livery)
            RailCar.HOPPER -> hopper(m, L)
            RailCar.TANK -> tank(m, L)
            RailCar.CABOOSE -> caboose(m, L)
        }
        m.build()
    }

    /** Two trucks under a car ([L] long, [hw] half wide), their wheels painted on, from the rail top up to [top]. */
    private fun trucks(m: MeshBuilder, L: Float, hw: Float = 18f, top: Float = 12f) {
        val side = { w: Int, h: Int -> PixImg(w, h).also { im ->
            im.fill(Paint.BLACK.b); im.border(Paint.OUT)
            val r = h / 2 - 1
            for (cx in intArrayOf(w / 4, 3 * w / 4)) for (y in 0 until h) for (x in 0 until w) {
                val d = hypot((x - cx).toFloat(), (y - h / 2f))
                if (d <= r) im[x, y] = if (d > r - 1.2f) Paint.OUT else if (d < 1.5f) Paint.STEEL.l else Paint.STEEL.d
            }
        } }
        for (sx in floatArrayOf(-1f, 1f)) {
            val c = sx * (L / 2f - 34f)
            m.box(c - 24f, 0f, -hw, c + 24f, top, hw) { s, w, h -> when (s) { MeshBuilder.Side.FRONT, MeshBuilder.Side.BACK -> side(w, h); MeshBuilder.Side.BOTTOM -> null; else -> Paint.swatch(Paint.BLACK.d) } }
        }
    }

    /** A coach wearing the pack's car [id]: its side band on both sides, its roof band on top, plain ends. */
    private fun coach(m: MeshBuilder, L: Float, id: String) {
        val im = piece(id)
        val (sa, sb) = cols(im, 66, 120)
        val side = crop(im, sa, 64, sb, 124)
        val roof = crop(im, sa, 4, sb, 60)
        // glass glows at night
        for (y in 0 until side.h) for (x in 0 until side.w) {
            val c = side[x, y]; val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
            if (c ushr 24 == 255 && b > r + 35 && g > r && (r + g + b) / 3 > 100) side.glowAt(x, y, 170)
        }
        val endR = Ramp(Paint.OUT, Pix.mix(side[side.w / 2, side.h - 6], Paint.OUT, .3f), side[side.w / 2, side.h - 6], side[side.w / 2, side.h - 6], side[side.w / 2, side.h - 6])
        val y0 = 13f; val y1 = y0 + 50f; val hw = 23f
        trucks(m, L)
        m.box(-L / 2, y0, -hw, L / 2, y1, hw) { s, w, h ->
            when (s) {
                MeshBuilder.Side.FRONT, MeshBuilder.Side.BACK -> side
                MeshBuilder.Side.TOP -> roof
                MeshBuilder.Side.BOTTOM -> Paint.swatch(Paint.BLACK.d)
                else -> Paint.panel(w, h, endR, 5).also { e -> e.rect(w / 2 - 6, 4, w / 2 + 5, h - 2, Paint.OUT); e.rect(w / 2 - 5, 5, w / 2 + 4, h / 2, Paint.c(0x7FB8C8)) }
            }
        }
        // the roof's crown and the gangways between cars
        m.box(-L / 2 + 6f, y1, -hw + 6f, L / 2 - 6f, y1 + 4f, hw - 6f) { s, w, h -> if (s == MeshBuilder.Side.TOP) Paint.top(w, h, Paint.GREY) else if (s == MeshBuilder.Side.BOTTOM) null else Paint.panel(w, h, Paint.GREY, 2) }
        for (sx in floatArrayOf(-1f, 1f)) m.box(sx * L / 2 - 3f, y0 + 4f, -12f, sx * L / 2 + 3f, y1 - 6f, 12f) { s, w, h -> if (s == MeshBuilder.Side.BOTTOM) null else Paint.panel(w, h, Paint.BLACK, 1) }
    }

    /** A headlamp facing +x at the front ([x]), [y] high, glowing at night. */
    private fun headlight(m: MeshBuilder, x: Float, y: Float) {
        val im = PixImg(6, 6).also { it.fill(Paint.c(0xFFF2C0)); it.border(Paint.OUT); it.glow(1, 1, 4, 4) }
        m.quad(floatArrayOf(x + .2f, y + 3f, 3f), floatArrayOf(x + .2f, y + 3f, -3f), floatArrayOf(x + .2f, y - 3f, -3f), floatArrayOf(x + .2f, y - 3f, 3f), im)
    }

    private fun steam(m: MeshBuilder, L: Float) {
        val B = Paint.BLACK
        val boilerR = 21f; val by = 50f
        // frame and running board
        m.box(-L / 2, 12f, -20f, L / 2, 20f, 20f) { s, w, h -> if (s == MeshBuilder.Side.BOTTOM) null else Paint.panel(w, h, B, 3) }
        m.box(-L / 2 + 50f, 26f, -27f, L / 2 - 18f, 28f, 27f) { s, w, h -> if (s == MeshBuilder.Side.TOP) Paint.top(w, h, Paint.IRON) else if (s == MeshBuilder.Side.BOTTOM) null else Paint.swatch(B.o) }
        // the boiler along +x, its smokebox door at the front
        val (sw, sh) = Paint.stripSize(boilerR, L - 70f)
        val boiler = Paint.strip(sh, sw, B, 7, ends = false).let { im ->
            // (bands round the boiler)
            for (k in 1..4) { val x = k * im.w / 5; im.vline(x, 0, im.h - 1, Paint.STEEL.d) }
            im
        }
        val door = Paint.cap(boilerR, Paint.IRON)
        m.prism(floatArrayOf(-L / 2 + 50f, by, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), boilerR, boilerR, L - 70f, 12, null, swatch = boiler, capFar = door)
        // smokestack, sand dome and steam dome
        val sx = L / 2 - 38f
        m.upright(sx, 0f, by + 14f, by + 46f, 6.5f, 8.5f, 10, Paint.strip(Paint.stripSize(8f, 32f).first, Paint.stripSize(8f, 32f).second, B, 2), cap = Paint.cap(8.5f, B))
        m.upright(sx - 60f, 0f, by + 16f, by + 30f, 9f, 7f, 10, Paint.strip(Paint.stripSize(9f, 14f).first, Paint.stripSize(9f, 14f).second, B, 4), cap = Paint.cap(7f, B))
        m.upright(sx - 100f, 0f, by + 16f, by + 28f, 8f, 6f, 10, Paint.strip(Paint.stripSize(8f, 12f).first, Paint.stripSize(8f, 12f).second, Paint.YELLOW, 4), cap = Paint.cap(6f, Paint.YELLOW))
        // the cab at the back
        val cab0 = -L / 2; val cab1 = -L / 2 + 52f
        m.box(cab0, 20f, -27f, cab1, 92f, 27f) { s, w, h ->
            when (s) {
                MeshBuilder.Side.BOTTOM -> null
                MeshBuilder.Side.TOP -> Paint.top(w, h, B)
                else -> Paint.panel(w, h, B, 5).also { im -> if (s != MeshBuilder.Side.LEFT) { val ww = w / 3; im.rect(w / 2 - ww / 2, 6, w / 2 + ww / 2, h / 3, Paint.c(0x7FB8C8)); im.glow(w / 2 - ww / 2, 6, w / 2 + ww / 2, h / 3, 160) } }
            }
        }
        m.box(cab0 - 4f, 92f, -31f, cab1 + 4f, 96f, 31f) { s, w, h -> if (s == MeshBuilder.Side.BOTTOM) null else Paint.panel(w, h, B, 2) }
        // driving wheels: three big ones a side, red-rimmed, and a pony truck in front
        val wheel = Paint.cap(17f, Paint.RED).also { im -> val c = im.w / 2; im.rect(c - 1, c - 1, c + 1, c + 1, Paint.STEEL.l) }
        for (zs in floatArrayOf(-1f, 1f)) {
            for (k in 0..2) m.prism(floatArrayOf(-L / 2 + 70f + k * 40f, 17f, zs * 21f), floatArrayOf(0f, 0f, zs), floatArrayOf(0f, 1f, 0f), 17f, 17f, 3f, 12, null, swatch = Paint.swatch(Paint.BLACK.b), capFar = wheel, capNear = wheel)
            m.prism(floatArrayOf(L / 2 - 40f, 9f, zs * 18f), floatArrayOf(0f, 0f, zs), floatArrayOf(0f, 1f, 0f), 9f, 9f, 3f, 10, null, swatch = Paint.swatch(Paint.BLACK.b), capFar = Paint.cap(9f, Paint.IRON))
            // the side rod
            m.box(-L / 2 + 66f, 15f, zs * 25f - 1f, -L / 2 + 154f, 19f, zs * 25f + 1f) { s, _, _ -> if (s == MeshBuilder.Side.BOTTOM) null else Paint.swatch(Paint.STEEL.b) }
        }
        // the pilot (cowcatcher) at the front
        m.hexa(listOf(floatArrayOf(L / 2 - 6f, 0f, -20f), floatArrayOf(L / 2 + 14f, 0f, -20f), floatArrayOf(L / 2 + 14f, 0f, 20f), floatArrayOf(L / 2 - 6f, 0f, 20f)).map { floatArrayOf(it[0], 2f, it[2]) },
            listOf(floatArrayOf(L / 2 - 6f, 14f, -20f), floatArrayOf(L / 2 + 2f, 14f, -20f), floatArrayOf(L / 2 + 2f, 14f, 20f), floatArrayOf(L / 2 - 6f, 14f, 20f)),
            Paint.panel(20, 10, Paint.RED, 1), Paint.panel(20, 10, Paint.RED, 2), Paint.panel(20, 10, Paint.RED, 3), Paint.panel(20, 10, Paint.RED, 4), Paint.top(20, 10, Paint.IRON))
        headlight(m, L / 2 - 10f + .5f, by + 26f)
        m.box(L / 2 - 16f, by + 20f, -6f, L / 2 - 10f, by + 32f, 6f) { s, w, h -> if (s == MeshBuilder.Side.BOTTOM) null else Paint.panel(w, h, B, 1) }
    }

    private fun tender(m: MeshBuilder, L: Float) {
        val B = Paint.BLACK
        trucks(m, L)
        m.box(-L / 2, 13f, -26f, L / 2, 66f, 26f) { s, w, h ->
            when (s) {
                MeshBuilder.Side.BOTTOM -> null
                MeshBuilder.Side.TOP -> PixImg(w, h).also { im -> im.fill(Paint.c(0x23232F)); Paint.speckle(im, B, 3, 3f) }
                else -> Paint.panel(w, h, B, 8)
            }
        }
        m.box(-L / 2 + 10f, 66f, -20f, L / 2 - 30f, 72f, 20f) { s, w, h -> if (s == MeshBuilder.Side.BOTTOM) null else PixImg(w, h).also { it.fill(Paint.c(0x16161F)); Paint.speckle(it, B, 9, 4f) } }
    }

    /** A diesel road unit: a long hood, the cab at the front, the [livery]'s colours (road: a railroad's; national: the national service's). */
    private fun diesel(m: MeshBuilder, L: Float, livery: String) {
        val (body, stripe, trim) = when (livery) {
            "national" -> Triple(Ramp(Paint.c(0x3A3A50), Paint.c(0x8B8FA6), Paint.c(0xB8BCCC), Paint.c(0xC6CBDA), Paint.c(0xE8ECF4)), Paint.c(0xCB2A2A), Paint.c(0x243A6E))
            "freight" -> Triple(Ramp(Paint.c(0x16302A), Paint.c(0x1C5040), Paint.c(0x23664E), Paint.c(0x2E7A5C), Paint.c(0x5CA080)), Paint.c(0xEDB52A), Paint.c(0x16161F))
            else -> Triple(Ramp(Paint.c(0x3A1A22), Paint.c(0x6E2430), Paint.c(0x8E2E3A), Paint.c(0xA8404A), Paint.c(0xC86A6A)), Paint.c(0xEDB52A), Paint.c(0x16161F))
        }
        fun livery(w: Int, h: Int, seed: Int, cab: Boolean): PixImg = Paint.panel(w, h, body, seed).also { im ->
            val y = h * 2 / 3; im.hline(0, w - 1, y, stripe); im.hline(0, w - 1, y + 1, stripe); im.hline(0, w - 1, h - 3, trim)
            if (!cab) for (k in 1 until w / 14) im.vline(k * 14, 2, y - 2, body.d)
        }
        trucks(m, L, 20f, 14f)
        m.box(-L / 2, 14f, -24f, L / 2, 20f, 24f) { s, w, h -> if (s == MeshBuilder.Side.BOTTOM) null else Paint.panel(w, h, Paint.BLACK, 2) }
        // the long hood, then the cab with its windows, then a short nose
        val cab0 = L / 2 - 78f; val cab1 = L / 2 - 30f
        m.box(-L / 2 + 6f, 20f, -18f, cab0, 68f, 18f) { s, w, h -> if (s == MeshBuilder.Side.BOTTOM) null else if (s == MeshBuilder.Side.TOP) Paint.top(w, h, body) else livery(w, h, 3, false) }
        m.box(cab0, 20f, -24f, cab1, 88f, 24f) { s, w, h ->
            when (s) {
                MeshBuilder.Side.BOTTOM -> null
                MeshBuilder.Side.TOP -> Paint.top(w, h, body)
                else -> livery(w, h, 5, true).also { im -> val y0 = 5; val y1 = h / 3; im.rect(3, y0, w - 4, y1, Paint.OUT); im.rect(4, y0 + 1, w - 5, y1 - 1, Paint.c(0x7FB8C8)); im.glow(4, y0 + 1, w - 5, y1 - 1, 170) }
            }
        }
        m.box(cab1, 20f, -18f, L / 2, 58f, 18f) { s, w, h -> if (s == MeshBuilder.Side.BOTTOM) null else if (s == MeshBuilder.Side.TOP) Paint.top(w, h, body) else livery(w, h, 7, false) }
        if (livery == "national") m.box(-L / 2 + 40f, 46f, 18.2f, -L / 2 + 40f + PixImg.textWidth("PENNANT") * TEXEL * 2, 46f + 6f * TEXEL * 2, 18.4f) { s, w, h ->
            if (s == MeshBuilder.Side.FRONT) PixImg(w, h).also { im -> im.fill(body.b); im.text("PENNANT", 0, 0, trim) } else null
        }
        headlight(m, L / 2, 50f)
        // the fans and the horn on the hood's top
        for (k in 0..2) m.upright(-L / 2 + 40f + k * 45f, 0f, 68f, 71f, 9f, 9f, 10, null, swatch = Paint.swatch(Paint.IRON.d), cap = Paint.cap(9f, Paint.IRON))
    }

    /** A modern passenger engine: a long body with a slanted nose, white with a blue band. */
    private fun modern(m: MeshBuilder, L: Float) {
        val W = Ramp(Paint.c(0x3A3A50), Paint.c(0xA8A6BE), Paint.c(0xD6D2E2), Paint.c(0xEBE4F2), Paint.c(0xFFFFFF))
        fun liv(w: Int, h: Int, seed: Int) = Paint.panel(w, h, W, seed).also { im ->
            val y = h * 3 / 5; for (k in 0..2) im.hline(0, w - 1, y + k, Paint.c(0x2D4C8E)); im.hline(0, w - 1, y + 4, Paint.c(0xCB2A2A))
            // the dark skirt over the trucks, and grilles along the engine room
            im.rect(0, h - h / 6, w - 1, h - 2, Paint.c(0x3C3C52)); im.hline(0, w - 1, h - h / 6, Paint.OUT)
            if (w > 60) for (g in 0 until 3) { val x0 = w / 5 + g * w / 5; im.rect(x0, h / 6, x0 + w / 9, y - 4, Paint.c(0xA8A6BE)); for (x in x0 + 1 until x0 + w / 9 step 2) im.vline(x, h / 6 + 1, y - 5, Paint.c(0x6C6E85)) }
        }
        trucks(m, L, 20f, 14f)
        val nose = 40f
        m.box(-L / 2, 14f, -26f, L / 2 - nose, 94f, 26f) { s, w, h ->
            when (s) { MeshBuilder.Side.BOTTOM -> null; MeshBuilder.Side.TOP -> Paint.top(w, h, Paint.GREY); else -> liv(w, h, 4).also { im -> if (s != MeshBuilder.Side.LEFT && s != MeshBuilder.Side.RIGHT) { for (k in 0 until 4) { val x0 = 20 + k * (w - 40) / 4; im.rect(x0, h / 5, x0 + 10, h / 5 + 10, Paint.c(0x7FB8C8)); im.glow(x0, h / 5, x0 + 10, h / 5 + 10, 150) } } } }
        }
        // the nose: from the full section back to a low, narrower face at the front
        val b = listOf(floatArrayOf(L / 2 - nose, 14f, -26f), floatArrayOf(L / 2, 14f, -26f), floatArrayOf(L / 2, 14f, 26f), floatArrayOf(L / 2 - nose, 14f, 26f))
        val t = listOf(floatArrayOf(L / 2 - nose, 94f, -26f), floatArrayOf(L / 2 - 2f, 52f, -22f), floatArrayOf(L / 2 - 2f, 52f, 22f), floatArrayOf(L / 2 - nose, 94f, 26f))
        val glass = PixImg(24, 18).also { it.fill(Paint.c(0x5A7E9A)); it.border(Paint.OUT); it.glow(1, 1, 22, 16, 120) }
        m.hexa(b, t, liv(30, 50, 1), liv(30, 50, 2), liv(30, 50, 3), liv(52, 40, 6), glass)
        headlight(m, L / 2, 34f)
        // roof gear: radiator housings and a horn
        for (k in 0..2) m.panelBox(-L / 2 + 40f + k * 70f, 94f, -16f, -L / 2 + 90f + k * 70f, 100f, 16f, Paint.GREY, 3 + k)
    }

    private fun boxcar(m: MeshBuilder, L: Float, livery: String) {
        val r = if (livery == "b") Paint.TERRACOTTA else Paint.BROWN
        trucks(m, L)
        m.box(-L / 2, 13f, -25f, L / 2, 80f, 25f) { s, w, h ->
            when (s) {
                MeshBuilder.Side.BOTTOM -> null
                MeshBuilder.Side.TOP -> Paint.top(w, h, Paint.GREY)
                MeshBuilder.Side.FRONT, MeshBuilder.Side.BACK -> Paint.panel(w, h, r, 4).also { im ->
                    for (k in 1 until w / 10) im.vline(k * 10, 1, h - 2, r.d)
                    val d0 = w / 2 - 14; im.rect(d0, 4, d0 + 28, h - 3, r.d); im.border(Paint.OUT)
                    im.rect(d0 + 2, 6, d0 + 26, h - 5, r.b); im.text("RR", 6, h / 3, Paint.c(0xE8E0D0))
                }
                else -> Paint.panel(w, h, r, 7)
            }
        }
    }

    private fun hopper(m: MeshBuilder, L: Float) {
        trucks(m, L)
        val r = Paint.GREY
        val b = listOf(floatArrayOf(-L / 2 + 30f, 10f, -22f), floatArrayOf(L / 2 - 30f, 10f, -22f), floatArrayOf(L / 2 - 30f, 10f, 22f), floatArrayOf(-L / 2 + 30f, 10f, 22f))
        val t = listOf(floatArrayOf(-L / 2, 70f, -25f), floatArrayOf(L / 2, 70f, -25f), floatArrayOf(L / 2, 70f, 25f), floatArrayOf(-L / 2, 70f, 25f))
        val side = Paint.panel(40, 20, r, 5).also { im -> for (k in 1..3) im.vline(k * 10, 1, 18, r.d) }
        m.hexa(b, t, side, side, Paint.panel(16, 20, r, 6), Paint.panel(16, 20, r, 7), PixImg(40, 16).also { it.fill(Paint.c(0x3C3226)); Paint.speckle(it, Paint.BROWN, 5, 3f) })
    }

    private fun tank(m: MeshBuilder, L: Float) {
        trucks(m, L)
        m.box(-L / 2, 13f, -14f, L / 2, 20f, 14f) { s, w, h -> if (s == MeshBuilder.Side.BOTTOM) null else Paint.panel(w, h, Paint.BLACK, 1) }
        val (sw, sh) = Paint.stripSize(22f, L - 10f)
        val strip = Paint.strip(sh, sw, Paint.BLACK, 3, ends = false).also { im -> im.vline(im.w / 2, 0, im.h - 1, Paint.c(0xE8E0D0)) }
        m.prism(floatArrayOf(-L / 2 + 5f, 44f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), 22f, 22f, L - 10f, 12, null, swatch = strip, capFar = Paint.cap(22f, Paint.BLACK), capNear = Paint.cap(22f, Paint.BLACK))
        m.upright(0f, 0f, 64f, 72f, 7f, 7f, 8, null, swatch = Paint.swatch(Paint.BLACK.l), cap = Paint.cap(7f, Paint.BLACK))
    }

    private fun caboose(m: MeshBuilder, L: Float) {
        val r = Paint.RED
        trucks(m, L)
        val win = { w: Int, h: Int, seed: Int -> Paint.panel(w, h, r, seed).also { im -> for (k in 0..1) { val x0 = w / 4 + k * w / 3; im.rect(x0, h / 4, x0 + 6, h / 4 + 8, Paint.c(0x7FB8C8)); im.glow(x0, h / 4, x0 + 6, h / 4 + 8, 200) } } }
        m.box(-L / 2 + 8f, 13f, -24f, L / 2 - 8f, 74f, 24f) { s, w, h -> when (s) { MeshBuilder.Side.BOTTOM -> null; MeshBuilder.Side.TOP -> Paint.top(w, h, Paint.GREY); else -> win(w, h, s.ordinal) } }
        m.box(-22f, 74f, -18f, 22f, 98f, 18f) { s, w, h -> when (s) { MeshBuilder.Side.BOTTOM -> null; MeshBuilder.Side.TOP -> Paint.top(w, h, Paint.GREY); else -> win(w, h, 9 + s.ordinal) } }
        for (sx in floatArrayOf(-1f, 1f)) m.box(sx * (L / 2 - 4f) - 4f, 13f, -24f, sx * (L / 2 - 4f) + 4f, 16f, 24f) { s, _, _ -> if (s == MeshBuilder.Side.BOTTOM) null else Paint.swatch(Paint.BLACK.b) }
    }

    /** A puff of steam or smoke (a cross of two cards), [shade] 0 white steam .. 1 dark smoke. */
    fun puff(shade: Float): PropMesh {
        val m = MeshBuilder()
        val s = 20
        val im = PixImg(s, s)
        val c = (s - 1) / 2f
        val base = Pix.mix(Paint.c(0xEEEEF4), Paint.c(0x3C3C52), shade)
        for (y in 0 until s) for (x in 0 until s) {
            val d = hypot(x - c, y - c) + (((x * 7 + y * 13) % 5) - 2) * .4f
            if (d < c) im[x, y] = if (d > c - 1.5f) Pix.mix(base, Paint.OUT, .3f) else if (x + y < s - 3) Pix.mix(base, Paint.c(0xFFFFFF), .3f) else base
        }
        val r = 14f
        m.quad(floatArrayOf(-r, r, 0f), floatArrayOf(r, r, 0f), floatArrayOf(r, -r, 0f), floatArrayOf(-r, -r, 0f), im)
        m.quad(floatArrayOf(r, r, 0f), floatArrayOf(-r, r, 0f), floatArrayOf(-r, -r, 0f), floatArrayOf(r, -r, 0f), im)
        m.quad(floatArrayOf(0f, r, -r), floatArrayOf(0f, r, r), floatArrayOf(0f, -r, r), floatArrayOf(0f, -r, -r), im)
        m.quad(floatArrayOf(0f, r, r), floatArrayOf(0f, r, -r), floatArrayOf(0f, -r, -r), floatArrayOf(0f, -r, r), im)
        return m.build()
    }
}
