package com.lifemax.rpg.feature.habits.presentation.scene3d

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * 3D interiors cut from LimeZu's Modern Interiors sprites (docs/INTERIORS_3D.md; a trial, not yet wired into the game).
 * No engine in here: it makes [PropMesh]es from the pack's own pictures (cut by tools/cut_interiors.py into
 * assets/room3d/), which [PropLayer] hands to Filament like the street props.
 *
 * The pack draws furniture in its 3/4 top-down way: the rows nearer the picture's top are the thing's top seen from above,
 * the rows under them its front. One art pixel is one texel everywhere ([TEXEL] street units, the buildings' density; a
 * person is 44 px tall, about 1.7 m, so a metre is about 26 px), so a piece is as big as the art draws it:
 *  - box furniture: the top rows on a box's top, the front rows on its front, its sides and back filled from the front's
 *    own edge column and each row's commonest colour (so no side is blank and nothing is mirrored);
 *  - few-part shapes (tables, stools, chairs, a booth, a lamp): a handful of boxes, rings and beams wearing parts of the
 *    picture and its own colours;
 *  - small irregular things (plants, a coffee machine, people) stand as camera-facing cards; plates lie flat.
 * Where the art is too short for the person beside it (a table's legs, a counter), a plain row of the picture is
 * repeated (never a texel stretched), so every texel stays square.
 *
 * A piece's own frame: x across its front (the picture's x), y up, z out of its front (+z); the origin is the middle of
 * its front edge on the floor, so it stands back from where it is placed into -z.
 */
/** A picture as ARGB pixels, row by row: how a loader outside this module hands [RoomKit] the pack's pictures. */
class RoomArt(val w: Int, val h: Int, val argb: IntArray)

object RoomKit {
    const val T = TEXEL

    /** The mesh of the box or parts piece [name] from its picture ([art] loads one by name). */
    fun meshOf(name: String, art: (String) -> RoomArt): PropMesh = mesh(name) { n -> art(n).let { pix(it.w, it.h, it.argb) } }

    /** [a] made [h] rows tall by repeating its row [at] (see [rows]). */
    fun rowsOf(a: RoomArt, h: Int, at: Int): RoomArt = rows(pix(a.w, a.h, a.argb), h, at).let { RoomArt(it.w, it.h, it.px) }

    // ---- pictures ------------------------------------------------------------------------------------------------

    /** A picture from ARGB pixels (the caller loads the PNG: ImageIO on the desktop, BitmapFactory on the phone). */
    internal fun pix(w: Int, h: Int, argb: IntArray) = PixImg(w, h).also { argb.copyInto(it.px) }

    internal fun crop(s: PixImg, x0: Int, y0: Int, x1: Int, y1: Int): PixImg {
        val o = PixImg(x1 - x0, y1 - y0)
        for (y in y0 until y1) for (x in x0 until x1) o[x - x0, y - y0] = s[x, y]
        return o
    }

    /**
     * [s] made [h] rows tall by repeating its row [at] (a plain row of the art: a counter's panel, a table leg), or cut
     * at that row if [h] is less; texels stay square.
     */
    internal fun rows(s: PixImg, h: Int, at: Int): PixImg {
        if (h == s.h) return s
        val o = PixImg(s.w, h)
        val extra = h - s.h
        for (y in 0 until h) {
            val sy = when {
                y <= at -> y
                extra >= 0 && y <= at + extra -> at
                else -> (y - extra).coerceIn(0, s.h - 1)
            }
            for (x in 0 until s.w) o[x, y] = s[x, sy]
        }
        return o
    }

    /** [s] made [w] columns wide by repeating its column [at]. */
    internal fun cols(s: PixImg, w: Int, at: Int): PixImg {
        if (w == s.w) return s
        val o = PixImg(w, s.h)
        val extra = w - s.w
        for (x in 0 until w) {
            val sx = when { x <= at -> x; extra >= 0 && x <= at + extra -> at; else -> (x - extra).coerceIn(0, s.w - 1) }
            for (y in 0 until s.h) o[x, y] = s[sx, y]
        }
        return o
    }

    private fun opaque(c: Int) = (c ushr 24) > 127

    /** The commonest opaque colour of row [y] of [s] between [x0] and [x1] (exclusive), or 0. */
    internal fun rowMode(s: PixImg, y: Int, x0: Int = 0, x1: Int = s.w): Int {
        val n = HashMap<Int, Int>()
        for (x in x0 until x1) { val c = s[x, y]; if (opaque(c)) n[c] = (n[c] ?: 0) + 1 }
        return n.maxByOrNull { it.value }?.key ?: 0
    }

    /** The darkest opaque colour of [s]: the pack's navy outline as this picture draws it. */
    internal fun outline(s: PixImg): Int {
        var best = 0; var lum = Int.MAX_VALUE
        for (c in s.px) if (opaque(c)) { val l = ((c shr 16) and 255) * 3 + ((c shr 8) and 255) * 6 + (c and 255); if (l < lum) { lum = l; best = c } }
        return best
    }

    /**
     * A side face [w] texels deep for [front]: each row the front's column [col] (inside its outline), its two upright
     * edges the front's outline column [edge] for that row.
     */
    internal fun side(front: PixImg, w: Int, col: Int, edge: Int): PixImg {
        val o = PixImg(w, front.h)
        for (y in 0 until front.h) {
            val c = front[col, y]; val e = front[edge, y].takeIf(::opaque) ?: c
            for (x in 0 until w) o[x, y] = if (x == 0 || x == w - 1) e else c
        }
        return o
    }

    /** A back face for [front]: each row its commonest colour (the material without the details), outlined round. */
    internal fun back(front: PixImg, out: Int): PixImg {
        val o = PixImg(front.w, front.h)
        for (y in 0 until front.h) {
            val m = rowMode(front, y, 1, front.w - 1)
            for (x in 0 until front.w) o[x, y] = if (m == 0) 0 else if (x == 0 || x == front.w - 1) out else m
        }
        return o
    }

    /** A picture [w] wide made of [s]'s columns [x0] to [x1] repeated (a tablecloth's hem round its other sides). */
    internal fun wrap(s: PixImg, w: Int, x0: Int, x1: Int): PixImg {
        val o = PixImg(w, s.h)
        for (x in 0 until w) for (y in 0 until s.h) o[x, y] = s[x0 + x % (x1 - x0), y]
        return o
    }

    /** Marks every pixel of [s] that [pick] chooses to glow at night in its own colours. */
    internal fun glowWhere(s: PixImg, amount: Int = 255, pick: (Int) -> Boolean) {
        for (y in 0 until s.h) for (x in 0 until s.w) if (opaque(s[x, y]) && pick(s[x, y])) s.glowAt(x, y, amount)
    }

    /** How colourful a pixel is (0 grey to 255). */
    fun chroma(c: Int): Int { val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255; return max(r, max(g, b)) - min(r, min(g, b)) }

    // ---- box furniture ---------------------------------------------------------------------------------------------

    /**
     * One box cut from [s]: columns [x0]..[x1] (exclusive) across; its top the rows [t0]..[t1] (none if [t0] < 0) laid
     * [depth] texels deep (row [topAt] repeated to reach it); its front the rows [f0]..[f1] raised [height] texels (row
     * [frontAt] repeated); standing on [y] (texels up), its front [z] texels behind the piece's front; across from [x]
     * (texels, the picture's column 0 by default at the piece's middle less half the picture). [wrapSides]: its sides and
     * back wear the front's middle columns (a cloth's hem) instead of the edge colours. [top]/[front] let a caller mark
     * glows on the pictures before they are used.
     */
    internal class Block(
        val x0: Int, val x1: Int, val t0: Int, val t1: Int, val f0: Int, val f1: Int,
        val depth: Int = t1 - t0, val height: Int = f1 - f0, val topAt: Int = (t1 - t0) / 2, val frontAt: Int = (f1 - f0) / 2,
        val y: Int = 0, val z: Int = 0, val wrapSides: Boolean = false, val back: Boolean = true,
    )

    internal fun block(m: MeshBuilder, s: PixImg, b: Block, frontGlow: ((PixImg) -> Unit)? = null, topGlow: ((PixImg) -> Unit)? = null) {
        val out = outline(s)
        val front = rows(crop(s, b.x0, b.f0, b.x1, b.f1), b.height, b.frontAt).also { frontGlow?.invoke(it) }
        val top = if (b.t0 < 0) null else rows(crop(s, b.x0, b.t0, b.x1, b.t1), b.depth, b.topAt).also { topGlow?.invoke(it) }
        val left = if (b.wrapSides) wrap(front, b.depth, front.w / 3, front.w * 2 / 3) else side(front, b.depth, 2, 0)
        val right = if (b.wrapSides) wrap(front, b.depth, front.w / 3, front.w * 2 / 3) else side(front, b.depth, front.w - 3, front.w - 1)
        val backImg = if (b.wrapSides) wrap(front, front.w, front.w / 3, front.w * 2 / 3) else back(front, out)
        val half = s.w / 2f
        val x0 = (b.x0 - half) * T; val x1 = (b.x1 - half) * T
        val y0 = b.y * T; val y1 = (b.y + b.height) * T
        val z1 = -b.z * T; val z0 = z1 - b.depth * T
        m.box(x0, y0, z0, x1, y1, z1) { side, _, _ ->
            when (side) {
                MeshBuilder.Side.FRONT -> front
                MeshBuilder.Side.TOP -> top
                MeshBuilder.Side.LEFT -> left
                MeshBuilder.Side.RIGHT -> right
                MeshBuilder.Side.BACK -> if (b.back) backImg else null
                MeshBuilder.Side.BOTTOM -> null
            }
        }
    }

    /** A thin upright leg from the floor to [h] texels at ([x], [z]) texels, [t] thick, in [c]'s colour with its darker side. */
    internal fun leg(m: MeshBuilder, x: Float, z: Float, h: Float, t: Float, c: Int, dark: Int) {
        val lit = Paint.swatch(c); val sh = Paint.swatch(dark)
        val hx = t / 2f * T
        m.box(x * T - hx, 0f, z * T - hx, x * T + hx, h * T, z * T + hx) { side, _, _ ->
            when (side) { MeshBuilder.Side.BOTTOM, MeshBuilder.Side.TOP -> null; MeshBuilder.Side.FRONT, MeshBuilder.Side.LEFT -> lit; else -> sh }
        }
    }

    // ---- the diner's pieces ----------------------------------------------------------------------------------------

    /** How a piece stands: [BOX] furniture, a few [PARTS], a camera-facing [CARD], or a [FLAT] thing lying on a surface. */
    enum class Kind { BOX, PARTS, CARD, FLAT }

    /** A piece of the trial room: its picture's name in assets/room3d/diner, its kind, its footprint (texels). */
    class Piece(val name: String, val kind: Kind, val w: Int, val d: Int, val h: Int)

    val PIECES = listOf(
        Piece("counter", Kind.BOX, 54, 20, 27), Piece("display", Kind.BOX, 30, 30, 27), Piece("fridge", Kind.BOX, 32, 26, 52),
        Piece("stove", Kind.BOX, 28, 30, 24), Piece("kitchenCounter", Kind.BOX, 58, 24, 27), Piece("jukebox", Kind.BOX, 32, 20, 56),
        Piece("sideTable", Kind.BOX, 30, 16, 22),
        Piece("booth", Kind.PARTS, 62, 28, 32), Piece("clothTable", Kind.PARTS, 60, 34, 20), Piece("roundTable", Kind.PARTS, 48, 30, 20),
        Piece("stool", Kind.PARTS, 22, 14, 24), Piece("chair", Kind.PARTS, 26, 18, 28), Piece("lamp", Kind.PARTS, 19, 19, 22),
        Piece("pendant", Kind.PARTS, 18, 18, 18),
        Piece("plantBig", Kind.CARD, 36, 0, 60), Piece("palm", Kind.CARD, 46, 0, 60), Piece("plantSmall", Kind.CARD, 28, 0, 46),
        Piece("coffee", Kind.CARD, 44, 0, 38), Piece("plateFood", Kind.FLAT, 14, 12, 0), Piece("plateFood2", Kind.FLAT, 12, 10, 0),
    )

    /** Heights (texels) a card or a flat thing stands on, by what it stands on. */
    const val COUNTER_TOP = 27
    const val TABLE_TOP = 20

    /** The mesh of the box or parts piece [name], made from its picture ([art] loads one by name). */
    internal fun mesh(name: String, art: (String) -> PixImg): PropMesh {
        val s = art(if (name == "pendant") "lamp" else name)
        val m = MeshBuilder()
        when (name) {
            // the counter: a top slab over a cabinet set 2 px back, the pink panels raised to counter height (27 px, 1 m)
            "counter" -> {
                block(m, s, Block(4, 58, 8, 28, 28, 33, y = 22))
                block(m, s, Block(6, 56, -1, -1, 33, 48, depth = 16, height = 22, frontAt = 7, z = 2))
            }
            // the ice cream display: its glass top seen from above, its striped front raised to the counter's height
            "display" -> block(m, s, Block(2, 32, 14, 44, 44, 64, height = 27, frontAt = 6), topGlow = { im -> glowWhere(im, 140) { chroma(it) > 60 } })
            "fridge" -> block(m, s, Block(0, 32, 16, 32, 32, 84, depth = 26, topAt = 8))
            "stove" -> {
                block(m, s, Block(2, 30, 6, 36, 36, 52, height = 24, frontAt = 11))
                // the pan's handle over the edge, a board one texel thick at the burner's height
                val h = crop(s, 30, 19, 44, 23)
                m.box((30 - s.w / 2f) * T, 23.6f * T, -(23 - 6) * T, (44 - s.w / 2f) * T, 24.4f * T, -(19 - 6) * T) { side, _, _ ->
                    if (side == MeshBuilder.Side.TOP) h else if (side == MeshBuilder.Side.BOTTOM) null else Paint.swatch(outline(s))
                }
            }
            "kitchenCounter" -> block(m, s, Block(4, 62, 12, 22, 22, 40, depth = 24, topAt = 5, height = 27, frontAt = 12))
            // the machine: its coloured lights and screen glow at night
            "jukebox" -> block(m, s, Block(0, 32, 2, 14, 14, 70, depth = 20, topAt = 6), frontGlow = { im -> glowWhere(im, 255) { chroma(it) > 70 || (it and 255) > 225 } })
            "sideTable" -> block(m, s, Block(0, 30, 12, 28, 28, 50))
            "booth" -> {
                // the back: the art's rail from above, the backrest's face down to the floor behind the seat
                block(m, s, Block(0, 62, 8, 16, 16, 36, height = 32, frontAt = 17, z = 20))
                // the seat: its cushion from above, its front the art's lower band raised to a seat's height (12 px)
                block(m, s, Block(0, 62, 36, 50, 50, 58, depth = 20, topAt = 7, height = 12, frontAt = 3))
            }
            // the tablecloth: its top and its hem round all four sides over four dark legs (the art hides them)
            "clothTable" -> {
                block(m, s, Block(2, 62, 0, 34, 34, 44, y = 10, wrapSides = true))
                val c = outline(s)
                for (lx in listOf(8f, 54f)) for (lz in listOf(-6f, -28f)) leg(m, lx - s.w / 2f, lz, 10f, 2f, c, c)
            }
            "roundTable" -> roundTable(m, s)
            "stool" -> {
                block(m, s, Block(6, 28, 8, 18, 18, 22, depth = 14, topAt = 5, y = 20))
                val c = s[8, 30]; val d = s[9, 30].takeIf { it != c } ?: outline(s)
                for (lx in listOf(9f, 25f)) for (lz in listOf(-2f, -12f)) leg(m, lx - s.w / 2f, lz, 20f, 2f, c, d)
            }
            "chair" -> chair(m, s)
            "lamp" -> lamp(m, s, hang = false)
            "pendant" -> lamp(m, s, hang = true)
            else -> error("no mesh for $name")
        }
        return m.build()
    }

    /** The round table: its oval top laid flat (the art's own outline cuts it round), a rim under it, four legs. */
    private fun roundTable(m: MeshBuilder, s: PixImg) {
        val top = crop(s, 8, 2, 56, 32)
        val h = 20f
        // (the top's oval: 48 across, 30 deep; its middle 15 back from the front)
        val cz = -15f
        m.flat(
            listOf(MeshBuilder.v(-24 * T, h * T, (cz - 15) * T), MeshBuilder.v(24 * T, h * T, (cz - 15) * T), MeshBuilder.v(24 * T, h * T, (cz + 15) * T), MeshBuilder.v(-24 * T, h * T, (cz + 15) * T)),
            top, MeshBuilder.v(-24 * T, h * T, (cz - 15) * T), MeshBuilder.v(1f, 0f, 0f), MeshBuilder.v(0f, 0f, 1f), MeshBuilder.v(0f, 1f, 0f),
        )
        // the rim: the art's band under the top's front edge (rows 32..37), round the oval as a ring of faces
        val band = crop(s, 30, 32, 34, 38).let { b -> PixImg(1, b.h).also { o -> for (y in 0 until b.h) o[0, y] = rowMode(b, y) } }
        val n = 24
        val a = 23.5f; val bz = 14.5f; val rh = band.h.toFloat()
        for (i in 0 until n) {
            val a0 = (2 * PI * i / n).toFloat(); val a1 = (2 * PI * (i + 1) / n).toFloat()
            val p0 = floatArrayOf(sin(a0) * a * T, 0f, (cz + cos(a0) * bz) * T); val p1 = floatArrayOf(sin(a1) * a * T, 0f, (cz + cos(a1) * bz) * T)
            m.quad(
                MeshBuilder.v(p0[0], h * T, p0[2]), MeshBuilder.v(p1[0], h * T, p1[2]),
                MeshBuilder.v(p1[0], (h - rh) * T, p1[2]), MeshBuilder.v(p0[0], (h - rh) * T, p0[2]), band,
            )
        }
        val c = s[14, 40]; val d = s[13, 40]
        for (lx in listOf(-15f, 15f)) for (lz in listOf(cz - 8f, cz + 8f)) leg(m, lx, lz, h - rh, 3f, c, d)
    }

    /**
     * The side-view chair (the art draws it facing +x: its back on the left): turned here to face +z, its seat the
     * art's slats from above, its back a board wearing the post's colours row by row, four legs.
     */
    private fun chair(m: MeshBuilder, s: PixImg) {
        // the seat: art columns 10..30 run front to back (the art's +x is the chair's front), rows 18..36 across
        val slats = crop(s, 10, 18, 30, 36)
        // turned: across (x) is the art's rows, depth (z) the art's columns, front (+z) the art's column 30
        val seat = PixImg(slats.h, slats.w)
        for (y in 0 until seat.h) for (x in 0 until seat.w) seat[x, y] = slats[seat.w - 1 - x, y]
        val w = seat.w.toFloat(); val d = seat.h.toFloat()
        val sy = 11f; val st = 2f
        val out = outline(s)
        val edge = PixImg(seat.w, 2).also { it.fill(s[20, 34]); it.hline(0, seat.w - 1, 1, out) }
        val sideEdge = PixImg(seat.h, 2).also { it.fill(s[20, 34]); it.hline(0, seat.h - 1, 1, out) }
        m.box(-w / 2f * T, sy * T, -d * T, w / 2f * T, (sy + st) * T, 0f) { side, _, _ ->
            when (side) { MeshBuilder.Side.TOP -> seat; MeshBuilder.Side.BOTTOM -> null; MeshBuilder.Side.FRONT, MeshBuilder.Side.BACK -> edge; else -> sideEdge }
        }
        // the back: rows 2..18 of the post (its light middle column per row), across the seat's width, 3 thick
        val bh = 16
        val face = PixImg(seat.w, bh)
        for (y in 0 until bh) { val c = s[6, 2 + y]; for (x in 0 until seat.w) face[x, y] = if (x == 0 || x == seat.w - 1 || y == 0) out else c }
        val thin = PixImg(3, bh).also { im -> for (y in 0 until bh) for (x in 0 until 3) im[x, y] = if (y == 0) out else s[5 + x, 2 + y] }
        val top = PixImg(seat.w, 3).also { it.fill(s[6, 6]); it.border(out) }
        m.box(-w / 2f * T, (sy + st) * T, -d * T, w / 2f * T, (sy + st + bh) * T, (-d + 3f) * T) { side, _, _ ->
            when (side) { MeshBuilder.Side.FRONT, MeshBuilder.Side.BACK -> face; MeshBuilder.Side.TOP -> top; MeshBuilder.Side.BOTTOM -> null; else -> thin }
        }
        val c = s[12, 40]; val dk = s[11, 40].takeIf { it != c } ?: out
        for (lx in listOf(-w / 2f + 1.5f, w / 2f - 1.5f)) for (lz in listOf(-1.5f, -d + 1.5f)) leg(m, lx, lz, sy, 2f, c, dk)
    }

    /**
     * A lamp from the table lamp's picture: its shade (rows 4..26) round a frustum, the art's front half twice round,
     * glowing at night; [hang]: a pendant on a cord from the ceiling (its frame's origin the cord's top, y down from it)
     * instead of a table lamp's stem and foot.
     */
    private fun lamp(m: MeshBuilder, s: PixImg, hang: Boolean) {
        // (rows as tall as the shade stands, columns as many as its round: every texel square)
        val front = if (hang) crop(s, 5, 9, 27, 19) else crop(s, 5, 7, 27, 21)
        // round: the front's columns, then mirrored, so the seam at the back meets
        val strip = PixImg(front.w * 2, front.h)
        for (y in 0 until front.h) for (x in 0 until front.w) { strip[x, y] = front[x, y]; strip[strip.w - 1 - x, y] = front[x, y] }
        // (its transparent corners filled with each row's colour: the shade is a whole frustum)
        for (y in 0 until strip.h) { val c = rowMode(strip, y); for (x in 0 until strip.w) if ((strip[x, y] ushr 24) < 128) strip[x, y] = c }
        strip.glow(0, 0, strip.w - 1, strip.h - 1, 200)
        val out = outline(s)
        // (a cap's picture is laid a texel a texel across its disc: as wide as the disc)
        val cap = PixImg(20, 20).also { it.fill(s[16, 10]); it.glow(0, 0, 19, 19, 160) }
        val inside = PixImg(20, 20).also { it.fill(s[16, 21]); it.glow(0, 0, 19, 19, 255) }
        val base = s[16, 34]
        if (!hang) {
            m.upright(0f, -10f * T, 0f, 2f * T, 6f * T, 5f * T, 8, null, Paint.swatch(base), PixImg(12, 12).also { it.fill(s[14, 32]) })
            m.upright(0f, -10f * T, 2f * T, 8f * T, 1f * T, 1f * T, 4, null, Paint.swatch(out))
            m.prism(MeshBuilder.v(0f, 8f * T, -10f * T), MeshBuilder.v(0f, 1f, 0f), MeshBuilder.v(0f, 0f, 1f), 9.5f * T, 4.5f * T, 14f * T, 12, strip, capFar = cap, capNear = inside)
        } else {
            // hanging: a cord from the ceiling, the shade's bottom 18 under it, smaller than the table lamp's
            m.upright(0f, 0f, -8f * T, 0f, .6f * T, .6f * T, 4, null, Paint.swatch(out))
            m.prism(MeshBuilder.v(0f, -18f * T, 0f), MeshBuilder.v(0f, 1f, 0f), MeshBuilder.v(0f, 0f, 1f), 9f * T, 5f * T, 10f * T, 12, strip, capFar = cap, capNear = inside)
        }
    }

    /** [mesh] raised [dy] street units (a pendant hung from the ceiling, a card or a plate on a counter or table). */
    fun lift(mesh: PropMesh, dy: Float): PropMesh {
        val p = mesh.pos.copyOf()
        for (i in 1 until p.size step 3) p[i] += dy
        val b = mesh.bounds.copyOf(); b[1] += dy; b[4] += dy
        return PropMesh(p, mesh.uv, mesh.nrm, mesh.tan, mesh.idx, mesh.texW, mesh.texH, mesh.tex, mesh.mask, b)
    }

    /**
     * A camera-facing card of [s]'s opaque bounds: its feet at the origin, facing +z (the caller turns it to the camera),
     * leaning back [lean] radians from upright (for a camera looking steeply down, which would see an upright card edge on).
     */
    internal fun card(s: PixImg, lean: Float = 0f): PropMesh {
        var x0 = s.w; var y0 = s.h; var x1 = -1; var y1 = -1
        for (y in 0 until s.h) for (x in 0 until s.w) if ((s[x, y] ushr 24) > 127) { x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y) }
        val im = crop(s, x0, y0, x1 + 1, y1 + 1)
        val m = MeshBuilder()
        val w = im.w * T / 2f; val h = im.h * T
        val hy = h * cos(lean); val hz = -h * sin(lean)
        m.quad(MeshBuilder.v(-w, hy, hz), MeshBuilder.v(w, hy, hz), MeshBuilder.v(w, 0f, 0f), MeshBuilder.v(-w, 0f, 0f), im)
        return m.build()
    }

    /** A thing lying flat (a plate): [s]'s opaque bounds on the floor plane a hair up, centred on the origin, its top row far. */
    internal fun flat(s: PixImg): PropMesh {
        var x0 = s.w; var y0 = s.h; var x1 = -1; var y1 = -1
        for (y in 0 until s.h) for (x in 0 until s.w) if ((s[x, y] ushr 24) > 127) { x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y) }
        val im = crop(s, x0, y0, x1 + 1, y1 + 1)
        val m = MeshBuilder()
        val w = im.w * T / 2f; val d = im.h * T / 2f; val y = .15f
        m.quad(MeshBuilder.v(-w, y, -d), MeshBuilder.v(w, y, -d), MeshBuilder.v(w, y, d), MeshBuilder.v(-w, y, d), im)
        return m.build()
    }

    // ---- the pack's pieces from pieces.json (tools/cut_pieces.py) -----------------------------------------------------

    /**
     * One design from `pieces.json`, as the builders need it: its family and kind, its picture's size, the guessed top/front
     * split of a box ([splitRow]: the first row of the front) and the family's own row and column ranges ([s], keys joined by
     * dots: "along.head", "back_top", "seams"; a flag is [1] or [0]). Written by the cutter, read by the preview.
     */
    class PieceSpec(val id: String, val family: String, val kind: String, val w: Int, val h: Int, val splitRow: Int, val s: Map<String, IntArray>) {
        fun r(k: String): IntArray = s[k] ?: error("$id ($family) has no $k")
        fun has(k: String) = s.containsKey(k)
        fun flag(k: String) = s[k]?.firstOrNull() == 1
    }

    /** Heights (texels) for a plan-view box (its front band only a few rows: counters, nightstands, shelves seen from above). */
    private val FAMILY_H = mapOf(
        "counter" to 27, "cabinet" to 30, "dresser" to 28, "nightstand" to 20, "wardrobe" to 64, "shop_shelf" to 40, "shop_counter" to 27,
        "register" to 27, "sink" to 27, "vanity" to 27, "oven" to 27, "display" to 27, "ottoman" to 14, "sofa_corner" to 18, "desk" to 24,
        "shower" to 60, "fridge" to 52, "stove" to 24, "bookcase" to 50,
    )

    /** The row of [s] between [y0] and [y1] with the fewest different colours: a plain row to repeat (a panel, a plank). */
    internal fun plainRow(s: PixImg, y0: Int, y1: Int): Int {
        var best = y0; var n = Int.MAX_VALUE
        val lo = (y0 + 2).coerceAtMost(y1 - 1); val hi = (y1 - 2).coerceAtLeast(lo + 1)
        for (y in lo until hi.coerceAtMost(y1)) {
            val c = HashSet<Int>(); for (x in 2 until s.w - 2) c += s[x, y]
            if (c.size < n) { n = c.size; best = y }
        }
        return best
    }

    /** [s] with columns [a] until [b] taken out (an armchair: a sofa with its seats cut down to one). */
    internal fun colsOut(s: PixImg, a: Int, b: Int): PixImg {
        val o = PixImg(s.w - (b - a), s.h)
        var ox = 0
        for (x in 0 until s.w) if (x < a || x >= b) { for (y in 0 until s.h) o[ox, y] = s[x, y]; ox++ }
        return o
    }

    /** [pieceMesh] for a loader outside this module (the tests): the design's picture as a [RoomArt]. */
    fun pieceMeshOf(p: PieceSpec, a: RoomArt): PropMesh = pieceMesh(p, pix(a.w, a.h, a.argb))

    /** The mesh of design [p] from its picture [s] (the design's own pixels, cropped to its art): dispatches on the family. */
    internal fun pieceMesh(p: PieceSpec, s: PixImg): PropMesh {
        val m = MeshBuilder()
        when (p.family) {
            "sofa", "chair_front" -> seat(m, s, p, seats = 3)
            "armchair" -> {
                val seams = p.r("seams")
                // one seat: the left arm and first cushion, then the right arm's last 5 columns
                seat(m, colsOut(s, seams[0] + 1, s.w - 5), p, seats = 1)
            }
            "bed_up" -> bedUp(m, s, p)
            "bed_side" -> bedSide(m, s, p)
            "toilet" -> toilet(m, s, p)
            "tub" -> tub(m, s, p)
            "desk", "table", "table_end", "bench" -> if (p.has("top")) deskTable(m, s, p) else genericBox(m, s, p)
            "bookcase" -> if (p.has("top") && p.splitRow > 6) bookcase(m, s, p) else genericBox(m, s, p)
            "chair" -> if (p.has("seat")) chairSide(m, s, p) else genericBox(m, s, p)
            "window", "door", "curtain", "picture", "tv", "board", "wall_decor" -> plate(m, s)
            else -> genericBox(m, s, p)
        }
        return m.build()
    }

    /** A box: the guessed top rows on its top, front rows on its front, as [Block] does; a plan-view piece's height from its family. */
    private fun genericBox(m: MeshBuilder, s: PixImg, p: PieceSpec) {
        val y = p.splitRow.coerceIn(3, s.h - 3)
        val frontRows = s.h - y
        val depth = (y - 1).coerceIn(4, 60)
        val height = if (frontRows >= 14) frontRows else (FAMILY_H[p.family] ?: 24)
        val at = (plainRow(s, y, s.h) - y).coerceIn(0, frontRows - 1)
        block(m, s, Block(0, s.w, 1, y, y, s.h, depth = depth, height = height, topAt = (y - 1) / 2, frontAt = at))
    }

    /**
     * Sofa, armchair: a back block (the rail from above, the backrest's face, carried down to the floor behind the seat) and a
     * seat block (the cushion from above, its front down to the floor), as the booth is; the arms are the picture's end columns.
     */
    private fun seat(m: MeshBuilder, s: PixImg, p: PieceSpec, seats: Int) {
        val bt = p.r("back_top"); val bf = p.r("back_face"); val st = p.r("seat_top"); val sf = p.r("seat_front")
        val seatH = (sf[1] - sf[0]).coerceIn(10, 18)
        val sd = st[1] - st[0]; val bd = (bt[1] - bt[0]).coerceAtLeast(3)
        val faceRows = bf[1] - bf[0]
        block(m, s, Block(0, s.w, st[0], st[1], sf[0], sf[1], depth = sd, height = seatH, frontAt = ((sf[1] - sf[0]) / 3).coerceAtLeast(0), topAt = sd / 2))
        block(m, s, Block(0, s.w, bt[0], bt[0] + bd, bf[0], bf[1], depth = bd, height = seatH + faceRows, frontAt = faceRows - 1, topAt = bd / 2, z = sd))
    }

    /** A bed seen from its foot (the headboard at the top of the picture): frame and mattress, pillows, headboard, four legs. */
    private fun bedUp(m: MeshBuilder, s: PixImg, p: PieceSpec) {
        val hd = p.r("along.head"); val pl = p.r("along.pillow"); val rl = p.r("along.rail"); val lg = p.r("along.legs")
        val legH = (lg[1] - lg[0]).coerceIn(3, 6); val frame = 12
        val deep = rl[0] - pl[0]
        // the mattress: its top from the pillows to the foot, the rail's rows its front raised to the frame's height
        block(m, s, Block(0, s.w, pl[0], rl[0], rl[0], rl[1], depth = deep, height = frame, frontAt = (rl[1] - rl[0]) / 2, topAt = deep / 2, y = legH))
        // the pillows: a low box over the pillow rows, their lower rows its front
        val pd = pl[1] - pl[0]
        block(m, s, Block(2, s.w - 2, pl[0], pl[1], (pl[1] - 3).coerceAtLeast(pl[0]), pl[1], depth = pd, height = 3, frontAt = 1, topAt = pd / 2, y = legH + frame, z = rl[0] - pl[1]))
        // the headboard: its face the picture's top rows, carried down to the floor behind the mattress and up to 1 m
        val hh = 28
        block(m, s, Block(0, s.w, 0, 3, 0, hd[1], depth = 3, height = hh, frontAt = (hd[1] * 2 / 3).coerceAtMost(hd[1] - 1), topAt = 1, z = deep))
        val c = s[2, lg[0] + 1].takeIf { (it ushr 24) > 127 } ?: outline(s); val dk = outline(s)
        for (lx in listOf(3f, s.w - 3f)) for (lz in listOf(-3f, -deep + 3f)) leg(m, lx - s.w / 2f, lz, legH.toFloat(), 3f, c, dk)
    }

    /** A bed seen from its side (the head at one end): frame and mattress along the picture's columns, pillow, headboard, legs. */
    private fun bedSide(m: MeshBuilder, s: PixImg, p: PieceSpec) {
        val hc = p.r("cols.head"); val pc = p.r("cols.pillow")
        val tr = p.r("rows.top"); val rr = p.r("rows.rail"); val lr = p.r("rows.legs")
        val legH = (lr[1] - lr[0]).coerceIn(3, 6); val frame = 12
        val deep = tr[1] - tr[0]
        block(m, s, Block(0, s.w, tr[0], tr[1], rr[0], rr[1], depth = deep, height = frame, frontAt = (rr[1] - rr[0]) / 2, topAt = deep / 2, y = legH))
        // the pillow, its top from the picture
        val inset = 3
        block(m, s, Block(pc[0], pc[1], tr[0] + inset, tr[1] - inset, (tr[1] - 3), tr[1], depth = deep - 2 * inset, height = 3, frontAt = 1, topAt = (deep - 2 * inset) / 2, y = legH + frame, z = inset))
        // the headboard: a tall slab at the end, its top the strip, its faces the rail's wood
        block(m, s, Block(hc[0], hc[1], tr[0], tr[1], rr[0], rr[1], depth = deep, height = 28, frontAt = (rr[1] - rr[0]) / 2, topAt = deep / 2))
        val c = s[(hc[0] + hc[1]) / 2, rr[0] + 1].takeIf { (it ushr 24) > 127 } ?: outline(s); val dk = outline(s)
        for (lx in listOf(4f, s.w - 4f)) for (lz in listOf(-3f, -deep + 3f)) leg(m, lx - s.w / 2f, lz, legH.toFloat(), 3f, c, dk)
    }

    /** A toilet from above: its tank (a box at the back), the bowl with its seat (the picture's seat rows on the top). */
    private fun toilet(m: MeshBuilder, s: PixImg, p: PieceSpec) {
        val tk = p.r("tank"); val bw = p.r("bowl"); val fr = p.r("front")
        val bd = bw[1] - bw[0]
        block(m, s, Block(0, s.w, bw[0], bw[1], fr[0], fr[1], depth = bd, height = 15, frontAt = (fr[1] - fr[0]) / 2, topAt = bd / 2))
        val td = (tk[1] - tk[0] - 1).coerceAtLeast(4)
        block(m, s, Block(2, s.w - 2, tk[0] + 1, tk[1], (tk[1] - 4).coerceAtLeast(tk[0]), tk[1], depth = td, height = 22, frontAt = 1, topAt = td / 2, z = bd))
    }

    /** A bath from above: a box (the picture's tub and taps on its top, its rim's rows the front). */
    private fun tub(m: MeshBuilder, s: PixImg, p: PieceSpec) {
        val t = p.r("top"); val f = p.r("front")
        val d = t[1] - t[0]
        block(m, s, Block(0, s.w, t[0], t[1], f[0], f[1], depth = d, height = 17, frontAt = (f[1] - f[0]) / 2, topAt = d / 2))
    }

    /**
     * A table or desk (a plan-view slab with its rail and legs below): the slab's top from the picture, the rail's rows its
     * edge, four legs in the legs' colours; a chair behind it (the picture's rows above the top) as a seat and a back board.
     */
    private fun deskTable(m: MeshBuilder, s: PixImg, p: PieceSpec) {
        val ch = p.r("chair"); val tp = p.r("top"); val rl = p.r("rail"); val lg = p.r("legs")
        val topH = p.s["h"]?.get(0) ?: if (p.family == "desk" || p.family == "table_end") 22 else 20
        val depth = (tp[1] - tp[0]).coerceIn(6, 70)
        val thick = (rl[1] - rl[0]).coerceIn(3, 8)
        val x0 = 0; val x1 = s.w
        block(m, s, Block(x0, x1, tp[0], tp[1], rl[0], rl[1], depth = depth, height = thick, frontAt = (rl[1] - rl[0]) / 2, topAt = depth / 2, y = topH - thick))
        val out = outline(s)
        var lc = out
        run { val ry = ((lg[0] + lg[1]) / 2).coerceIn(0, s.h - 1); for (x in 0 until s.w / 3) if ((s[x, ry] ushr 24) > 127 && s[x, ry] != out) { lc = s[x, ry]; break } }
        val inset = 3f
        for (lx in listOf(inset + 1f, s.w - inset - 1f)) for (lz in listOf(-inset, -depth + inset)) leg(m, lx - s.w / 2f, lz, topH - thick.toFloat(), 3f, lc, out)
        val cr = ch[1] - ch[0]
        if (cr >= 6) {
            // the chair behind: its back (the rows over the top, at most 18) on a board, its seat a slab under the top
            val rows = cr.coerceAtMost(18); val r0 = ch[1] - rows
            var cx0 = s.w; var cx1 = 0
            for (y in ch[0] until ch[1]) for (x in 0 until s.w) if ((s[x, y] ushr 24) > 127) { cx0 = min(cx0, x); cx1 = max(cx1, x + 1) }
            if (cx1 > cx0 + 4) {
                block(m, s, Block(cx0, cx1, r0, r0 + 2, r0, ch[1], depth = 2, height = rows, topAt = 0, frontAt = rows / 2, y = 11, z = depth + 4))
                val seatTop = rowMode(s, ch[1] - 1, cx0, cx1).takeIf { it != 0 } ?: rowMode(s, ch[1] - 2, cx0, cx1)
                val st = PixImg(cx1 - cx0, 12).also { it.fill(seatTop); it.border(out) }
                val sd = PixImg(cx1 - cx0, 2).also { it.fill(seatTop); it.hline(0, cx1 - cx0 - 1, 1, out) }
                val sx0 = (cx0 - s.w / 2f) * T; val sx1 = (cx1 - s.w / 2f) * T
                m.box(sx0, 9f * T, -(depth + 4 + 12) * T, sx1, 11f * T, -(depth + 4) * T) { side, _, _ ->
                    when (side) { MeshBuilder.Side.TOP -> st; MeshBuilder.Side.BOTTOM -> null; else -> sd }
                }
                for (lx in listOf(cx0 + 2f, cx1 - 2f)) for (lz in listOf(-(depth + 4f) + 1.5f, -(depth + 4f + 12f) + 1.5f)) leg(m, lx - s.w / 2f, lz, 9f, 2f, lc, out)
            }
        }
    }

    /**
     * A bookcase with depth: two upright sides and a lintel as full boxes, the shelves' face set back [DEEP] texels, so the
     * books sit inside it and its inner sides, top and plinth show.
     */
    private fun bookcase(m: MeshBuilder, s: PixImg, p: PieceSpec) {
        val y = p.splitRow
        val D = (y - 1).coerceIn(8, 24); val H = s.h - y
        val deep = 6; val post = 4
        // the posts' faces are the picture's own edge columns
        block(m, s, Block(0, post, 1, y, y, s.h, depth = D, height = H, topAt = (y - 1) / 2, frontAt = H / 2))
        block(m, s, Block(s.w - post, s.w, 1, y, y, s.h, depth = D, height = H, topAt = (y - 1) / 2, frontAt = H / 2))
        // the lintel: the top from above, its front the picture's first rows under the top edge
        block(m, s, Block(post, s.w - post, 1, y, y, y + 4, depth = D, height = 4, topAt = (y - 1) / 2, frontAt = 1, y = H - 4))
        // the shelves' face, set back
        block(m, s, Block(post, s.w - post, -1, -1, y + 4, s.h - 3, depth = D - deep, height = H - 7, frontAt = (H - 7) / 2, y = 3, z = deep))
        // the plinth
        block(m, s, Block(post, s.w - post, -1, -1, s.h - 3, s.h, depth = D, height = 3, frontAt = 1))
    }

    /**
     * A chair drawn from its side (the post at one end, [PieceSpec] "post_left"): turned to face +z, its seat the art's slats from
     * above, its back a board wearing the post's colours row by row, four legs (the trial's chair, any chair design).
     */
    private fun chairSide(m: MeshBuilder, s: PixImg, p: PieceSpec) {
        val seatR = p.r("seat"); val legs = p.r("legs"); val left = p.flag("post_left")
        // the seat's slats: the columns not the post, the rows of the seat less its front rail
        val postW = run { var c = 0; for (x in 0 until s.w) { val col = if (left) x else s.w - 1 - x; if ((s[col, 1] ushr 24) > 127 || (s[col, 3] ushr 24) > 127) c++ else break }; c.coerceIn(3, 9) }
        val cx0 = if (left) postW + 1 else 1; val cx1 = if (left) s.w - 1 else s.w - postW - 1
        val r0 = seatR[0] + 2; val r1 = (seatR[1] - 4).coerceAtLeast(r0 + 4)
        val slats = crop(s, cx0, r0, cx1, r1)
        // turned: across is the art's rows, depth its columns, the front (+z) the art's end away from the post
        val seat = PixImg(slats.h, slats.w)
        for (y in 0 until seat.h) for (x in 0 until seat.w) seat[x, y] = if (left) slats[y, seat.w - 1 - x] else slats[slats.w - 1 - y, seat.w - 1 - x]
        val w = seat.w.toFloat(); val d = seat.h.toFloat()
        val sy = 11f; val st = 2f; val out = outline(s)
        val edgeC = rowMode(s, seatR[1] - 2).takeIf { it != 0 } ?: s[s.w / 2, seatR[1] - 2]
        val edge = PixImg(seat.w, 2).also { it.fill(edgeC); it.hline(0, seat.w - 1, 1, out) }
        val sideEdge = PixImg(seat.h, 2).also { it.fill(edgeC); it.hline(0, seat.h - 1, 1, out) }
        m.box(-w / 2f * T, sy * T, -d * T, w / 2f * T, (sy + st) * T, 0f) { side, _, _ ->
            when (side) { MeshBuilder.Side.TOP -> seat; MeshBuilder.Side.BOTTOM -> null; MeshBuilder.Side.FRONT, MeshBuilder.Side.BACK -> edge; else -> sideEdge }
        }
        // the back: the post's rows (its middle column) up to the seat, 16 at most, on a board across the seat's width
        val bh = (seatR[0] + 8).coerceIn(10, 18)
        val face = PixImg(seat.w, bh)
        val pc = if (left) postW / 2 else s.w - 1 - postW / 2
        for (y in 0 until bh) { val c = s[pc, y.coerceIn(0, s.h - 1)].takeIf { (it ushr 24) > 127 } ?: edgeC; for (x in 0 until seat.w) face[x, y] = if (x == 0 || x == seat.w - 1 || y == 0) out else c }
        val top = PixImg(seat.w, 3).also { it.fill(s[pc, 3]); it.border(out) }
        val thin = PixImg(3, bh).also { im -> for (y in 0 until bh) for (x in 0 until 3) im[x, y] = if (y == 0) out else s[pc, y] }
        m.box(-w / 2f * T, (sy + st) * T, -d * T, w / 2f * T, (sy + st + bh) * T, (-d + 3f) * T) { side, _, _ ->
            when (side) { MeshBuilder.Side.FRONT, MeshBuilder.Side.BACK -> face; MeshBuilder.Side.TOP -> top; MeshBuilder.Side.BOTTOM -> null; else -> thin }
        }
        val lc = s[if (left) s.w - 6 else 5, (legs[0] + legs[1]) / 2].takeIf { (it ushr 24) > 127 } ?: out
        for (lx in listOf(-w / 2f + 1.5f, w / 2f - 1.5f)) for (lz in listOf(-1.5f, -d + 1.5f)) leg(m, lx, lz, sy, 2f, lc, out)
    }

    /** A thin plate (a window, a door, curtains, a picture, a flat TV): the picture on its front, its edge the outline colour. */
    private fun plate(m: MeshBuilder, s: PixImg) {
        block(m, s, Block(0, s.w, 0, 1, 0, s.h, depth = 3, height = s.h, topAt = 0, frontAt = s.h / 2))
    }

    // ---- the room's shell ------------------------------------------------------------------------------------------

    /** The wall's face height (texels): the art's 50 rows with its plain upper colour carried up to about 3 m. */
    const val WALL_H = 78
    /** The wall's thickness (texels): the art's cap seen from above. */
    const val WALL_T = 8
    /** A cut-away wall's height (Sims-style, the side nearest the camera): its skirting and a little more. */
    const val CUT_H = 12

    /** An opening in a wall: from [x0] to [x1] along it, [y0] to [y1] up (texels); [art] painted round it (a window's frame). */
    class Opening(val x0: Int, val x1: Int, val y0: Int, val y1: Int)

    /** A picture painted on a wall: [img] with its top-left at ([x], [y]) (texels along, up from the floor to its bottom). */
    internal class Decal(val img: PixImg, val x: Int, val y: Int)

    /**
     * The inside face of a wall [len] texels long, from the wall art (its middle tile repeated, its plain upper colour
     * carried up to [WALL_H]), the [decals] painted on it and the [openings] cut through (transparent).
     */
    internal fun wallFace(wall: PixImg, len: Int, decals: List<Decal>, openings: List<Opening>, h: Int = WALL_H): PixImg {
        // the art's face rows 12..61 (50); above them its row 20 repeated
        val face = rows(crop(wall, 32, 12, 64, 62), WALL_H, 4)
        val o = PixImg(len, h)
        for (y in 0 until h) for (x in 0 until len) o[x, y] = face[x % 32, y + (WALL_H - h)]
        for (d in decals) {
            val top = h - d.y - d.img.h
            for (y in 0 until d.img.h) for (x in 0 until d.img.w) { val c = d.img[x, y]; if ((c ushr 24) > 127) o[d.x + x, top + y] = c }
        }
        for (op in openings) for (y in op.y0 until op.y1) for (x in op.x0 until op.x1) {
            val yy = h - 1 - y
            if (yy in 0 until h) o[x, yy] = 0
        }
        return o
    }

    /**
     * A wall from (0, 0) along +x [len] texels (its inside face towards +z, [WALL_T] thick into -z) wearing [inside]
     * ([h] rows tall), its outside [outside], its cap the art's top band; each opening lined (sill, head, jambs) in
     * [reveal]. The caller turns and places it.
     */
    internal fun wall(wall: PixImg, inside: PixImg, outside: PixImg, openings: List<Opening>, reveal: Int): PropMesh {
        val m = MeshBuilder()
        val len = inside.w; val h = inside.h
        val L = len * T; val H = h * T; val t = WALL_T * T
        m.quad(MeshBuilder.v(0f, H, 0f), MeshBuilder.v(L, H, 0f), MeshBuilder.v(L, 0f, 0f), MeshBuilder.v(0f, 0f, 0f), inside)
        m.quad(MeshBuilder.v(L, H, -t), MeshBuilder.v(0f, H, -t), MeshBuilder.v(0f, 0f, -t), MeshBuilder.v(L, 0f, -t), outside)
        // the cap: the art's rows 1..9 (outline, white, outline) along the wall
        val capRow = crop(wall, 32, 1, 64, 1 + WALL_T)
        val cap = PixImg(len, WALL_T).also { im -> for (y in 0 until WALL_T) for (x in 0 until len) im[x, y] = capRow[x % 32, y] }
        m.quad(MeshBuilder.v(0f, H, -t), MeshBuilder.v(L, H, -t), MeshBuilder.v(L, H, 0f), MeshBuilder.v(0f, H, 0f), cap)
        val ends = PixImg(WALL_T, h).also { im -> for (y in 0 until h) for (x in 0 until WALL_T) im[x, y] = inside[0, y].takeIf { (it ushr 24) > 127 } ?: capRow[0, 4] }
        m.quad(MeshBuilder.v(0f, H, -t), MeshBuilder.v(0f, H, 0f), MeshBuilder.v(0f, 0f, 0f), MeshBuilder.v(0f, 0f, -t), ends)
        m.quad(MeshBuilder.v(L, H, 0f), MeshBuilder.v(L, H, -t), MeshBuilder.v(L, 0f, -t), MeshBuilder.v(L, 0f, 0f), ends)
        val rv = Paint.swatch(reveal)
        for (op in openings) {
            if (op.y0 >= h) continue
            val x0 = op.x0 * T; val x1 = op.x1 * T; val y0 = op.y0 * T; val y1 = min(op.y1, h) * T
            if (op.y0 > 0) m.quad(MeshBuilder.v(x0, y0, -t), MeshBuilder.v(x1, y0, -t), MeshBuilder.v(x1, y0, 0f), MeshBuilder.v(x0, y0, 0f), rv)
            if (op.y1 < h) m.quad(MeshBuilder.v(x0, y1, 0f), MeshBuilder.v(x1, y1, 0f), MeshBuilder.v(x1, y1, -t), MeshBuilder.v(x0, y1, -t), rv)
            m.quad(MeshBuilder.v(x0, y1, 0f), MeshBuilder.v(x0, y1, -t), MeshBuilder.v(x0, y0, -t), MeshBuilder.v(x0, y0, 0f), rv)
            m.quad(MeshBuilder.v(x1, y1, -t), MeshBuilder.v(x1, y1, 0f), MeshBuilder.v(x1, y0, 0f), MeshBuilder.v(x1, y0, -t), rv)
        }
        return m.build()
    }

    /** The outside face for an inside [face]: its plain colours (each row's commonest), the same openings cut. */
    internal fun outsideOf(face: PixImg): PixImg {
        val o = PixImg(face.w, face.h)
        for (y in 0 until face.h) {
            val c = rowMode(face, y)
            for (x in 0 until face.w) o[x, y] = if ((face[x, y] ushr 24) < 128) 0 else c
        }
        // mirrored: seen from outside, the wall's start is on the right
        val m = PixImg(o.w, o.h)
        for (y in 0 until o.h) for (x in 0 until o.w) m[x, y] = o[o.w - 1 - x, y]
        return m
    }

    /**
     * A window from the pack's window picture: its frame (the art's columns 4..60, rows 16..58) to paint on a wall at
     * [x], [y] (texels: along, up to its bottom), and the opening its glass leaves (cut through the wall: the room sees
     * out and the sun comes in).
     */
    internal fun window(art: PixImg, x: Int, y: Int): Pair<Decal, Opening> {
        val f = crop(art, 4, 16, 60, 58)
        // the glass: the frame's inside (rows 4..36, columns 4..52 of the cut), which the art paints light blue and white
        val gx0 = 4; val gx1 = 52; val gy0 = 4; val gy1 = 36
        for (yy in gy0 until gy1) for (xx in gx0 until gx1) f[xx, yy] = 0
        return Decal(f, x, y) to Opening(x + gx0, x + gx1, y + (f.h - gy1), y + (f.h - gy0))
    }

    /**
     * The ceiling seen from inside: [w] x [d] texels at [h] texels up, facing down, in 32-texel panels of the wall art's
     * cap white with the art's grey seams.
     */
    internal fun ceiling(wall: PixImg, w: Int, d: Int, h: Int): PropMesh {
        val white = wall[40, 5]; val seam = rows(crop(wall, 32, 12, 64, 14), 1, 0)[0, 0]
        val tile = PixImg(32, 32).also { it.fill(white); it.hline(0, 31, 0, seam); it.vline(0, 0, 31, seam) }
        val m = MeshBuilder()
        val y = h * T
        var z = 0
        while (z < d) {
            var x = 0
            val dz = min(32, d - z)
            while (x < w) {
                val dx = min(32, w - x)
                m.quad(MeshBuilder.v(x * T, y, (z + dz) * T), MeshBuilder.v((x + dx) * T, y, (z + dz) * T), MeshBuilder.v((x + dx) * T, y, z * T), MeshBuilder.v(x * T, y, z * T), tile, 0f, 0f, dx.toFloat(), dz.toFloat())
                x += dx
            }
            z += dz
        }
        return m.build()
    }

    /** The floor: [w] x [d] texels of the floor art's 96 x 64 block, laid from (0, 0) along +x and +z. */
    internal fun floor(art: PixImg, w: Int, d: Int): PropMesh {
        val m = MeshBuilder()
        var z = 0
        while (z < d) {
            var x = 0
            val dz = min(64, d - z)
            while (x < w) {
                val dx = min(96, w - x)
                m.quad(
                    MeshBuilder.v(x * T, 0f, z * T), MeshBuilder.v((x + dx) * T, 0f, z * T),
                    MeshBuilder.v((x + dx) * T, 0f, (z + dz) * T), MeshBuilder.v(x * T, 0f, (z + dz) * T),
                    art, 0f, 0f, dx.toFloat(), dz.toFloat(),
                )
                x += dx
            }
            z += dz
        }
        return m.build()
    }
}
