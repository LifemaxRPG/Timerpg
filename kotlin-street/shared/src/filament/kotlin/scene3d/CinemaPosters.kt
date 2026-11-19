package com.lifemax.rpg.feature.habits.presentation.scene3d

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The cinema's posters, painted texel by texel in the pack's way (a navy outline, flat tones, a few bands of shading) in
 * the style of the decade the film is from: 1950s painted creature, western and musical bills with big hand-lettered
 * titles; 1960s epics and spies in flat bold colour; 1970s disasters and car chases with photo-like blocks; 1980s neon
 * grids and chrome; 1990s dark faces and bright family bills; 2000s orange and teal; 2010s heroic silhouettes in a line;
 * 2020s minimal. Every shape is generic (no real character, logo or person) and every title is made up by its caller.
 */

/** A film on the cinema's bill: made up by the town's generator (never a real one). */
data class CinemaFilm(
    val title: String,
    val tagline: String = "",
    /** The rating as the poster prints it ("PG", "R", "APPROVED", "" for none). */
    val rating: String = "",
    /** What kind of picture: monster, western, musical, epic, spy, disaster, chase, action, scifi, thriller, family, blockbuster, hero, drama, horror, comedy, romance. */
    val genre: String = "drama",
    /** The decade its poster is drawn in (1940, 1950, ... 2020). */
    val decade: Int = 1950,
    /** A palette pick (any int; each style takes it modulo its own palettes) when [colours] are not given. */
    val palette: Int = 0,
    val seed: Int = 0,
    /** The poster's look by name (the town's: painted-pulp, painted-epic, airbrush, neon-grid, photo-montage, blockbuster, teal-orange, minimal); empty: by [decade]. */
    val style: String = "",
    /** What the picture shows (the town's: creature, rider, dancers, rocket, colossus, agent, flames, car, hero, face, animal, portal...); empty: by [genre]. */
    val motif: String = "",
    /** The town's four colours (ARGB): background, main, accent, lettering; empty: the style's own. */
    val colours: List<Int> = emptyList(),
    /** The made-up lead (for the billing; not drawn legibly at this size). */
    val star: String = "",
)

/** The bigger pixel font (5 x 7) for marquees, signs and poster titles. */
internal object BigFont {
    const val W = 5
    const val H = 7

    private val glyphs: Map<Char, IntArray> = mapOf(
        'A' to "01110 10001 10001 11111 10001 10001 10001", 'B' to "11110 10001 10001 11110 10001 10001 11110",
        'C' to "01110 10001 10000 10000 10000 10001 01110", 'D' to "11110 10001 10001 10001 10001 10001 11110",
        'E' to "11111 10000 10000 11110 10000 10000 11111", 'F' to "11111 10000 10000 11110 10000 10000 10000",
        'G' to "01110 10001 10000 10111 10001 10001 01111", 'H' to "10001 10001 10001 11111 10001 10001 10001",
        'I' to "01110 00100 00100 00100 00100 00100 01110", 'J' to "00111 00010 00010 00010 00010 10010 01100",
        'K' to "10001 10010 10100 11000 10100 10010 10001", 'L' to "10000 10000 10000 10000 10000 10000 11111",
        'M' to "10001 11011 10101 10101 10001 10001 10001", 'N' to "10001 10001 11001 10101 10011 10001 10001",
        'O' to "01110 10001 10001 10001 10001 10001 01110", 'P' to "11110 10001 10001 11110 10000 10000 10000",
        'Q' to "01110 10001 10001 10001 10101 10010 01101", 'R' to "11110 10001 10001 11110 10100 10010 10001",
        'S' to "01111 10000 10000 01110 00001 00001 11110", 'T' to "11111 00100 00100 00100 00100 00100 00100",
        'U' to "10001 10001 10001 10001 10001 10001 01110", 'V' to "10001 10001 10001 10001 10001 01010 00100",
        'W' to "10001 10001 10001 10101 10101 10101 01010", 'X' to "10001 10001 01010 00100 01010 10001 10001",
        'Y' to "10001 10001 01010 00100 00100 00100 00100", 'Z' to "11111 00001 00010 00100 01000 10000 11111",
        '0' to "01110 10001 10011 10101 11001 10001 01110", '1' to "00100 01100 00100 00100 00100 00100 01110",
        '2' to "01110 10001 00001 00010 00100 01000 11111", '3' to "11111 00010 00100 00010 00001 10001 01110",
        '4' to "00010 00110 01010 10010 11111 00010 00010", '5' to "11111 10000 11110 00001 00001 10001 01110",
        '6' to "00110 01000 10000 11110 10001 10001 01110", '7' to "11111 00001 00010 00100 01000 01000 01000",
        '8' to "01110 10001 10001 01110 10001 10001 01110", '9' to "01110 10001 10001 01111 00001 00010 01100",
        '.' to "00000 00000 00000 00000 00000 01100 01100", ',' to "00000 00000 00000 00000 01100 00100 01000",
        '!' to "00100 00100 00100 00100 00100 00000 00100", '?' to "01110 10001 00001 00010 00100 00000 00100",
        '-' to "00000 00000 00000 11111 00000 00000 00000", '\'' to "01100 00100 01000 00000 00000 00000 00000",
        ':' to "00000 01100 01100 00000 01100 01100 00000", '&' to "01100 10010 10100 01000 10101 10010 01101",
        '/' to "00001 00010 00010 00100 01000 01000 10000", ' ' to "00000 00000 00000 00000 00000 00000 00000",
    ).mapValues { (_, s) -> s.split(" ").map { it.toInt(2) }.toIntArray() }

    fun has(c: Char) = glyphs.containsKey(c.uppercaseChar())

    /** The width of [s] at [scale] with [gap] texels between letters. */
    fun width(s: String, scale: Int = 1, gap: Int = 1): Int = if (s.isEmpty()) 0 else s.length * (W * scale + gap) - gap

    /** Whether texel ([x], [y]) of glyph [c] at [scale] is ink. */
    fun ink(c: Char, x: Int, y: Int, scale: Int = 1): Boolean {
        val g = glyphs[c.uppercaseChar()] ?: return false
        val gx = x / scale; val gy = y / scale
        if (gx !in 0 until W || gy !in 0 until H) return false
        return (g[gy] shr (W - 1 - gx)) and 1 == 1
    }

    /** Draws [s] with its top-left at ([x], [y]); [fill] gives each ink texel's colour by its row inside the letter (0 top). */
    fun draw(im: PixImg, s: String, x: Int, y: Int, scale: Int = 1, gap: Int = 1, fill: (Int) -> Int) {
        var cx = x
        for (ch in s) {
            for (yy in 0 until H * scale) for (xx in 0 until W * scale) if (ink(ch, xx, yy, scale)) im[cx + xx, y + yy] = fill(yy)
            cx += W * scale + gap
        }
    }

    fun draw(im: PixImg, s: String, x: Int, y: Int, c: Int, scale: Int = 1, gap: Int = 1) = draw(im, s, x, y, scale, gap) { c }

    /** [s] with an outline (every texel round the ink) in [out] and a drop shadow [shadow] (null for none) under it. */
    fun outlined(im: PixImg, s: String, x: Int, y: Int, scale: Int, gap: Int, out: Int, shadow: Int?, fill: (Int) -> Int) {
        val w = width(s, scale, gap); val h = H * scale
        val ink = Array(h + 4) { BooleanArray(w + 4) }
        var cx = 2
        for (ch in s) {
            for (yy in 0 until h) for (xx in 0 until W * scale) if (ink(ch, xx, yy, scale)) ink[yy + 2][cx + xx] = true
            cx += W * scale + gap
        }
        fun at(px: Int, py: Int) = py in ink.indices && px in 0 until w + 4 && ink[py][px]
        if (shadow != null) for (yy in 0 until h + 4) for (xx in 0 until w + 4) if (at(xx - 1, yy - 2) || at(xx - 2, yy - 2)) im[x + xx - 2, y + yy - 2] = shadow
        for (yy in 0 until h + 4) for (xx in 0 until w + 4) {
            if (at(xx, yy)) continue
            var near = false
            for (dy in -1..1) for (dx in -1..1) if (at(xx + dx, yy + dy)) near = true
            if (near) im[x + xx - 2, y + yy - 2] = out
        }
        for (yy in 0 until h + 4) for (xx in 0 until w + 4) if (at(xx, yy)) im[x + xx - 2, y + yy - 2] = fill(yy - 2)
    }
}

/** Small drawing helpers over a [PixImg] for the posters and signs. */
internal object Pix {
    fun mix(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a shr s) and 255) * (1f - u) + ((b shr s) and 255) * u).roundToInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** A vertical gradient from [top] to [bottom] in [bands] flat bands, a checker of the two at each band's edge. */
    fun bands(im: PixImg, x0: Int, y0: Int, x1: Int, y1: Int, top: Int, bottom: Int, bands: Int) {
        val h = max(1, y1 - y0)
        for (y in y0..y1) {
            val t = (y - y0).toFloat() / h * bands
            val b = t.toInt().coerceAtMost(bands - 1)
            val c0 = mix(top, bottom, b / max(1f, bands - 1f))
            val c1 = mix(top, bottom, (b + 1).coerceAtMost(bands - 1) / max(1f, bands - 1f))
            val edge = t - b > .8f
            for (x in x0..x1) im[x, y] = if (edge && (x + y) % 2 == 0) c1 else c0
        }
    }

    fun disc(im: PixImg, cx: Float, cy: Float, r: Float, c: Int) {
        for (y in (cy - r).toInt() - 1..(cy + r).toInt() + 1) for (x in (cx - r).toInt() - 1..(cx + r).toInt() + 1) {
            val dx = x + .5f - cx; val dy = y + .5f - cy
            if (dx * dx + dy * dy <= r * r) im[x, y] = c
        }
    }

    fun ellipse(im: PixImg, cx: Float, cy: Float, rx: Float, ry: Float, c: Int) {
        for (y in (cy - ry).toInt() - 1..(cy + ry).toInt() + 1) for (x in (cx - rx).toInt() - 1..(cx + rx).toInt() + 1) {
            val dx = (x + .5f - cx) / rx; val dy = (y + .5f - cy) / ry
            if (dx * dx + dy * dy <= 1f) im[x, y] = c
        }
    }

    fun ring(im: PixImg, cx: Float, cy: Float, r: Float, t: Float, c: Int) {
        for (y in (cy - r).toInt() - 1..(cy + r).toInt() + 1) for (x in (cx - r).toInt() - 1..(cx + r).toInt() + 1) {
            val d = sqrt((x + .5f - cx) * (x + .5f - cx) + (y + .5f - cy) * (y + .5f - cy))
            if (d <= r && d > r - t) im[x, y] = c
        }
    }

    fun line(im: PixImg, x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
        var x = x0; var y = y0
        val dx = abs(x1 - x0); val dy = -abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1; val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            im[x, y] = c
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }

    fun tri(im: PixImg, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, c: Int) {
        val x0 = min(ax, min(bx, cx)).toInt() - 1; val x1 = max(ax, max(bx, cx)).toInt() + 1
        val y0 = min(ay, min(by, cy)).toInt() - 1; val y1 = max(ay, max(by, cy)).toInt() + 1
        fun e(px: Float, py: Float, qx: Float, qy: Float, x: Float, y: Float) = (qx - px) * (y - py) - (qy - py) * (x - px)
        for (y in y0..y1) for (x in x0..x1) {
            val px = x + .5f; val py = y + .5f
            val e0 = e(ax, ay, bx, by, px, py); val e1 = e(bx, by, cx, cy, px, py); val e2 = e(cx, cy, ax, ay, px, py)
            if ((e0 >= 0 && e1 >= 0 && e2 >= 0) || (e0 <= 0 && e1 <= 0 && e2 <= 0)) im[x, y] = c
        }
    }

    /**
     * A standing person's silhouette, generic: feet at ([cx], [feet]), [h] texels tall; [pose] 0 standing, 1 arms out,
     * 2 a cape behind, 3 a wide-brimmed hat, 4 a skirt, 5 running.
     */
    fun figure(im: PixImg, cx: Float, feet: Float, h: Float, c: Int, pose: Int = 0, cape: Int = c) {
        val top = feet - h
        if (pose == 2) tri(im, cx, top + h * .2f, cx - h * .3f, feet - h * .05f, cx + h * .3f, feet - h * .05f, cape)
        // head
        disc(im, cx, top + h * .09f, max(1.2f, h * .085f), c)
        if (pose == 3) { im.rect((cx - h * .17f).toInt(), (top + h * .07f).toInt(), (cx + h * .17f).toInt(), (top + h * .09f).toInt(), c); disc(im, cx, top + h * .04f, h * .08f, c) }
        // neck, shoulders and torso
        for (y in (top + h * .16f).toInt()..(top + h * .55f).toInt()) {
            val t = (y - top) / h
            val half = when { t < .19f -> h * .05f; t < .26f -> h * .14f; else -> h * (.14f - (t - .26f) * .2f) }
            im.rect((cx - half).toInt(), y, (cx + half).toInt(), y, c)
        }
        if (pose == 4) tri(im, cx, top + h * .42f, cx - h * .24f, feet - h * .12f, cx + h * .24f, feet - h * .12f, c)
        // arms
        when (pose) {
            1 -> { line(im, (cx - h * .13f).toInt(), (top + h * .24f).toInt(), (cx - h * .36f).toInt(), (top + h * .1f).toInt(), c); line(im, (cx + h * .13f).toInt(), (top + h * .24f).toInt(), (cx + h * .36f).toInt(), (top + h * .1f).toInt(), c) }
            5 -> { line(im, (cx - h * .12f).toInt(), (top + h * .26f).toInt(), (cx - h * .3f).toInt(), (top + h * .4f).toInt(), c); line(im, (cx + h * .12f).toInt(), (top + h * .26f).toInt(), (cx + h * .26f).toInt(), (top + h * .16f).toInt(), c) }
            else -> { im.rect((cx - h * .17f).toInt(), (top + h * .25f).toInt(), (cx - h * .13f).toInt(), (top + h * .52f).toInt(), c); im.rect((cx + h * .13f).toInt(), (top + h * .25f).toInt(), (cx + h * .17f).toInt(), (top + h * .52f).toInt(), c) }
        }
        // legs
        val lw = max(1f, h * .05f)
        if (pose == 5) {
            line(im, cx.toInt(), (top + h * .55f).toInt(), (cx - h * .22f).toInt(), feet.toInt() - 1, c); line(im, (cx - 1).toInt(), (top + h * .55f).toInt(), (cx - h * .22f - 1).toInt(), feet.toInt() - 1, c)
            line(im, cx.toInt(), (top + h * .55f).toInt(), (cx + h * .2f).toInt(), (top + h * .8f).toInt(), c); line(im, (cx + h * .2f).toInt(), (top + h * .8f).toInt(), (cx + h * .12f).toInt(), feet.toInt() - 1, c)
        } else {
            im.rect((cx - h * .1f).toInt(), (top + h * .55f).toInt(), (cx - h * .1f + lw).toInt(), feet.toInt() - 1, c)
            im.rect((cx + h * .1f - lw).toInt(), (top + h * .55f).toInt(), (cx + h * .1f).toInt(), feet.toInt() - 1, c)
            im.rect((cx - h * .1f).toInt(), (top + h * .55f).toInt(), (cx + h * .1f).toInt(), (top + h * .6f).toInt(), c)
        }
    }

    /** A row of rough rooftops (a skyline) along [base], its tallest [h] texels. */
    fun skyline(im: PixImg, x0: Int, x1: Int, base: Int, h: Int, c: Int, rnd: Paint.Rnd, lit: Int? = null) {
        var x = x0
        while (x <= x1) {
            val w = 3 + rnd.int(6); val bh = h / 3 + rnd.int(max(1, h * 2 / 3))
            im.rect(x, base - bh, x + w - 1, base, c)
            if (lit != null) for (yy in base - bh + 2 until base step 3) for (xx in x + 1 until x + w - 1 step 2) if (rnd.next() < .35f) im[xx, yy] = lit
            x += w
        }
    }
}

/** Paints each film's poster in its decade's style. */
object CinemaPosters {
    /** The poster's size in texels (a one-sheet's 2:3). */
    const val W = 48
    const val H = 72

    private fun c(rgb: Long) = Paint.c(rgb)
    private val INK = c(0x1A1A28)
    private val PAPER = c(0xF2E8D0)

    /** [f]'s poster as ARGB pixels, [W] x [H], rows top first. */
    fun argb(f: CinemaFilm): IntArray = paint(f).px

    /** The decade each of the town's poster styles is drawn as. */
    private val STYLE_DECADE = mapOf("painted-pulp" to 1950, "painted-epic" to 1960, "airbrush" to 1970, "neon-grid" to 1980,
        "photo-montage" to 1990, "blockbuster" to 2000, "teal-orange" to 2010, "minimal" to 2020)

    /** The town's motifs as the painters' picture kinds. */
    private val MOTIF_GENRE = mapOf(
        "creature" to "monster", "rider" to "western", "dancers" to "musical", "rocket" to "scifi", "silhouette" to "noir",
        "colossus" to "epic", "agent" to "spy", "surfer" to "surfer", "castle" to "castle", "flames" to "disaster", "car" to "chase",
        "gun" to "action", "planet" to "scifi", "eye" to "thriller", "fighter" to "action", "hero" to "hero", "grid" to "grid",
        "knife" to "horror", "teens" to "comedy", "sword" to "scifi", "duo" to "action", "face" to "thriller", "animal" to "family",
        "explosion" to "blockbuster", "couple" to "romance", "money" to "thriller", "army" to "hero", "cartoon" to "family",
        "static" to "horror", "vault" to "thriller", "wave" to "disaster", "group" to "comedy", "shadow" to "horror",
        "astronaut" to "scifi", "symbol" to "symbol", "portal" to "portal", "portrait" to "portrait", "microphone" to "microphone",
    )

    internal fun paint(film: CinemaFilm): PixImg {
        val f = film.copy(decade = STYLE_DECADE[film.style] ?: film.decade, genre = MOTIF_GENRE[film.motif] ?: film.genre)
        val im = PixImg(W, H)
        im.fill(INK)
        val r = Paint.Rnd(f.seed * 7919 + f.title.hashCode())
        when (f.decade) {
            in Int.MIN_VALUE..1959 -> fifties(im, f, r)
            in 1960..1969 -> sixties(im, f, r)
            in 1970..1979 -> seventies(im, f, r)
            in 1980..1989 -> eighties(im, f, r)
            in 1990..1999 -> nineties(im, f, r)
            in 2000..2009 -> noughties(im, f, r)
            in 2010..2019 -> tens(im, f, r)
            else -> twenties(im, f, r)
        }
        return im
    }

    // ------------------------------------------------------------------ lettering

    /** Splits [s] into lines of at most [n] letters (by words). */
    internal fun wrap(s: String, n: Int): List<String> {
        val out = ArrayList<String>()
        var line = ""
        for (w in s.uppercase().split(" ").filter { it.isNotEmpty() }) {
            val word = if (w.length > n) w.take(n) else w
            line = if (line.isEmpty()) word else if (line.length + 1 + word.length <= n) "$line $word" else { out += line; word }
        }
        if (line.isNotEmpty()) out += line
        return out
    }

    /**
     * The title as the bill letters it, fitted into rows [y0]..[y1] and centred there: the whole title big on one line
     * if it fits, else the lead words small over the last word big, else two big lines, else the small font; outlined
     * in [out] (and a drop [shadow]), [fill] by row inside the letters, the small words in [small].
     */
    private fun title(im: PixImg, t: String, y0: Int, y1: Int, out: Int, shadow: Int?, scaleMax: Int = 2, small0: Int = out, fill0: (Int) -> Int) {
        // (the town's palettes can letter in the band's own colour: such letters turn white or black to read)
        val band = im[1, y1]
        val small = readable(small0, band)
        val fill = { row: Int -> fill0(row).let { if (abs(lum(it) - lum(band)) < .3f && abs(lum(out) - lum(band)) < .3f) readable(it, band) else it } }
        val s = t.uppercase().filter { BigFont.has(it) }
        val words = s.split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) return
        val room = y1 - y0 + 1
        val maxW = W - 4
        val layouts = ArrayList<Pair<Int, (Int) -> Unit>>()
        fun big(line: String, sc: Int, y: Int) = BigFont.outlined(im, line, (W - BigFont.width(line, sc)) / 2, y + 1, sc, 1, out, shadow, fill)
        fun smallLines(lines: List<String>, y: Int) { for ((k, ln) in lines.withIndex()) im.text(ln, (W - PixImg.textWidth(ln)) / 2, y + k * 6, small) }
        for (sc in scaleMax downTo 1) {
            if (BigFont.width(s, sc) <= maxW) layouts += (BigFont.H * sc + 2) to { y: Int -> big(s, sc, y) }
            val last = words.last(); val lead = wrap(words.dropLast(1).joinToString(" "), 11)
            if (words.size > 1 && BigFont.width(last, sc) <= maxW && lead.size <= 2) layouts += (lead.size * 6 + BigFont.H * sc + 2) to { y: Int -> smallLines(lead, y); big(last, sc, y + lead.size * 6) }
            val two = wrap(s, maxW / (BigFont.W * sc + 1))
            if (two.size == 2 && two.all { BigFont.width(it, sc) <= maxW }) layouts += (2 * (BigFont.H * sc + 2) + 1) to { y: Int -> big(two[0], sc, y); big(two[1], sc, y + BigFont.H * sc + 3) }
        }
        val lines = wrap(s, 11)
        val whole = layouts.size
        layouts += (lines.size * 6 - 1) to { y: Int -> smallLines(lines, y) }
        for (n in lines.size - 1 downTo 1) layouts += (n * 6 - 1) to { y: Int -> smallLines(lines.take(n), y) }
        // the biggest whole title that fits; else the band grows up over the picture (a long title is never cut short)
        val fit = layouts.take(whole + 1).firstOrNull { it.first <= room }
        if (fit != null) { fit.second(y0 + (room - fit.first) / 2); return }
        val (h, draw) = layouts.take(whole + 1).filter { it.first <= room + 18 }.firstOrNull() ?: layouts[whole]
        val top = y1 - h
        im.rect(0, top - 1, W - 1, y0 - 1, im[1, y1])
        draw(top)
    }

    /** [c], or white or black where [c] would not read on [band]. */
    private fun readable(c: Int, band: Int): Int = if (abs(lum(c) - lum(band)) >= .3f) c else if (lum(band) < .5f) c(0xF8F8F0) else c(0x141418)

    /** As much of [s] as fits one small line, ended on a whole word that does not leave the line hanging. */
    private fun blurb(s: String): String {
        val stop = setOf("THE", "A", "AN", "OF", "TO", "IN", "IS", "IT", "AND", "ONE", "NO", "YOU", "SOME", "EVERY", "BRING", "LAUGH", "SEE")
        val words = s.uppercase().split(" ").filter { it.isNotEmpty() }
        var best = ""
        var line = ""
        for (w in words) {
            line = if (line.isEmpty()) w else "$line $w"
            if (line.length > 11) break
            if (w !in stop || line.length == s.length) best = line
        }
        return if (best.isEmpty()) "SEE IT NOW" else best
    }

    /** A line of small letters centred at [y]: the tagline's first words that make sense alone. */
    private fun small(im: PixImg, s: String, y: Int, c: Int) {
        val t = blurb(s)
        im.text(t, (W - PixImg.textWidth(t)) / 2, y, c)
    }

    /** The credits' billing block along the foot (rows H-6 and H-4), the rating's box at its right (rows H-9..H-2). */
    private fun billing(im: PixImg, c: Int, rating: String, r: Paint.Rnd, rows: Int = 2) {
        val rt = rating.uppercase().take(5).let { if (it == "APPROVED") "" else it }
        val bw = if (rt.isEmpty()) 0 else PixImg.textWidth(rt) + 3
        val end = W - 5 - (if (bw > 0) bw + 2 else 0)
        for (k in 0 until rows) {
            var x = 4
            while (x < end) { val w = 1 + r.int(4); im.hline(x, min(end, x + w - 1), H - 6 + k * 2, c); x += w + 1 }
        }
        if (rt.isNotEmpty()) {
            im.rect(W - 3 - bw, H - 9, W - 3, H - 3, c)
            im.text(rt, W - 1 - bw, H - 8, if (lum(c) > .5f) INK else c(0xFFFFFF))
        }
    }

    private fun lum(c: Int) = (((c shr 16) and 255) * .3f + ((c shr 8) and 255) * .59f + (c and 255) * .11f) / 255f

    /** Film grain / paint wear: a few specks of [a] and [b] over the picture. */
    private fun grain(im: PixImg, r: Paint.Rnd, n: Int, a: Int, b: Int, x0: Int = 0, y0: Int = 0, x1: Int = W - 1, y1: Int = H - 1) {
        repeat(n) { im[x0 + r.int(x1 - x0 + 1), y0 + r.int(y1 - y0 + 1)] = if (r.next() < .5f) a else b }
    }

    /** The town's colour [i] (background, main, accent, lettering) for [f], or [default]. */
    private fun col(f: CinemaFilm, i: Int, default: Int): Int = f.colours.getOrNull(i) ?: default

    private fun pick(palettes: List<IntArray>, f: CinemaFilm) = palettes[((f.palette % palettes.size) + palettes.size) % palettes.size]

    /** A cheerful cartoon critter (round body, big ears and eyes) standing at ([cx], [feet]), [fur] coloured. */
    private fun critter(im: PixImg, cx: Float, feet: Float, fur: Int) {
        val by = feet - 9f; val hy = feet - 22f
        Pix.disc(im, cx, by, 9f, INK); Pix.disc(im, cx, by, 8f, fur)
        Pix.disc(im, cx, hy, 8f, INK); Pix.disc(im, cx, hy, 7f, fur)
        Pix.disc(im, cx - 7f, hy - 6f, 3.5f, INK); Pix.disc(im, cx + 7f, hy - 6f, 3.5f, INK); Pix.disc(im, cx - 7f, hy - 6f, 2.5f, fur); Pix.disc(im, cx + 7f, hy - 6f, 2.5f, fur)
        Pix.disc(im, cx - 3f, hy - 1f, 2.2f, c(0xFFFFFF)); Pix.disc(im, cx + 3f, hy - 1f, 2.2f, c(0xFFFFFF))
        im[(cx - 3).toInt(), hy.toInt() - 1] = INK; im[(cx + 3).toInt(), hy.toInt() - 1] = INK; im[(cx - 2).toInt(), hy.toInt() - 1] = INK; im[(cx + 4).toInt(), hy.toInt() - 1] = INK
        Pix.disc(im, cx, hy + 3f, 1.2f, INK); im.hline((cx - 2).toInt(), (cx + 2).toInt(), (hy + 5).toInt(), INK)
    }

    /** A row of [n] photographed faces (skin, hair, a little grain) across rows [y0]..[y1]. */
    private fun faces(im: PixImg, n: Int, y0: Int, y1: Int, seed: Int, r: Paint.Rnd, back: Int) {
        val w = W / n
        for (k in 0 until n) {
            val x = k * w
            im.rect(x, y0, x + w - 1, y1, back)
            val cx = x + w / 2f; val h = (y1 - y0).toFloat()
            val skin = c(listOf(0xE8B890, 0xC88A60, 0xF0C8A0, 0x8A5A3A)[(k + seed) % 4].toLong())
            Pix.ellipse(im, cx, y0 + h * .55f, w * .36f, h * .38f, skin)
            Pix.ellipse(im, cx, y0 + h * .28f, w * .4f, h * .2f, c(listOf(0x3A2A1A, 0xE8C860, 0x8A3A1A, 0x1A1A1A)[(k * 3 + seed) % 4].toLong()))
            im[(cx - w * .14f).toInt(), (y0 + h * .52f).toInt()] = INK; im[(cx + w * .14f).toInt(), (y0 + h * .52f).toInt()] = INK
            im.hline((cx - 2).toInt(), (cx + 1).toInt(), (y0 + h * .72f).toInt(), c(0x8A3A3A))
            for (q in 0 until w * 2) { val px = x + r.int(w); val py = y0 + r.int(y1 - y0 + 1); im[px, py] = Pix.mix(im[px, py], c(0x000000), .2f) }
        }
    }

    // ------------------------------------------------------------------ the decades

    /** 1940s-50s: painted bills on cream paper: a creature over a town, a rider at sunset, dancers in a spotlight. */
    private fun fifties(im: PixImg, f: CinemaFilm, r: Paint.Rnd) {
        im.fill(PAPER)
        val red = c(0xD8342C); val yellow = c(0xF6C83A)
        when (f.genre) {
            "scifi" -> {
                // the saucer over the town, its beam on a running crowd; or a finned rocket climbing on its flame
                Pix.bands(im, 2, 2, W - 3, 52, col(f, 0, c(0x101838)).let { if (lum(it) > .4f) Pix.mix(it, c(0x101030), .7f) else it }, c(0x3A2A6A), 5)
                for (k in 0 until 14) im[3 + r.int(W - 6), 3 + r.int(30)] = c(0xFFFFFF)
                val hull = col(f, 1, c(0xA8B8C8)).let { if (lum(it) < .35f) c(0xA8B8C8) else it }
                if ((f.seed and 0x7fffffff) % 2 == 0) {
                    val sx = 18f + r.int(12)
                    Pix.tri(im, sx, 22f, sx - 13f, 52f, sx + 13f, 52f, Pix.mix(c(0xC8F07A), c(0x3A2A6A), .35f))
                    Pix.ellipse(im, sx, 20f, 14f, 4f, Pix.mix(hull, c(0x000000), .3f)); Pix.ellipse(im, sx, 19f, 13f, 3f, hull)
                    Pix.ellipse(im, sx, 16f, 6f, 4f, c(0x8AE8F6)); im.hline((sx - 3).toInt(), (sx - 1).toInt(), 14, c(0xFFFFFF))
                    for (k in -2..2) Pix.disc(im, sx + k * 5f, 21f, .9f, yellow)
                } else {
                    Pix.tri(im, 24f, 6f, 19f, 18f, 29f, 18f, col(f, 2, red)); im.rect(19, 18, 29, 38, hull); im.rect(20, 18, 21, 38, c(0xFFFFFF))
                    Pix.disc(im, 24f, 25f, 2.2f, c(0x2A4A8A)); Pix.tri(im, 19f, 30f, 13f, 42f, 19f, 38f, col(f, 2, red)); Pix.tri(im, 29f, 30f, 35f, 42f, 29f, 38f, col(f, 2, red))
                    Pix.tri(im, 20f, 38f, 28f, 38f, 24f, 50f, yellow); Pix.tri(im, 22f, 38f, 26f, 38f, 24f, 45f, c(0xFFFFFF))
                }
                Pix.skyline(im, 2, W - 3, 52, 10, c(0x1A1A28), r, lit = yellow)
                for (k in 0 until 3) Pix.figure(im, 8f + k * 6f, 56f, 8f, c(0xF0E0D0), pose = 4)
                im.rect(2, 53, W - 3, 56, c(0x2A2A3A))
                title(im, f.title, 57, H - 3, col(f, 3, INK), null, small0 = col(f, 3, INK)) { if (it < 4) Pix.mix(col(f, 2, red), c(0xFFFFFF), .45f) else col(f, 2, red) }
            }
            "monster", "horror" -> {
                val sky = pick(listOf(intArrayOf(0x2A3A6A.toInt(), 0x7A4A8A.toInt()), intArrayOf(0x1E4A4A.toInt(), 0x8AA84A.toInt()), intArrayOf(0x4A1E2A.toInt(), 0xD86A3A.toInt())), f)
                Pix.bands(im, 2, 2, W - 3, 52, col(f, 0, c(sky[0].toLong())).let { if (f.colours.isEmpty()) it else Pix.mix(it, c(0x101020), .7f) }, col(f, 0, c(sky[1].toLong())), 5)
                // the creature: a hulking body with arms raised, its eyes lit, over the town
                val body = pick(listOf(intArrayOf(0x3C7A3A.toInt(), 0x24502A.toInt()), intArrayOf(0x6A4A8A.toInt(), 0x3E2A5A.toInt()), intArrayOf(0x8A5A2A.toInt(), 0x5A3A1A.toInt())), f)
                val b0 = col(f, 1, c(body[0].toLong())); val b1 = Pix.mix(b0, c(0x000000), .35f)
                val cx = 26f + r.int(8) - 4
                Pix.ellipse(im, cx, 34f, 13f, 15f, b1)
                Pix.ellipse(im, cx - 1, 33f, 11f, 13f, b0)
                Pix.disc(im, cx, 18f, 7f, b0)
                Pix.disc(im, cx - 3, 17f, 1.6f, yellow); Pix.disc(im, cx + 3, 17f, 1.6f, yellow)
                im[cx.toInt() - 3, 17] = INK; im[cx.toInt() + 3, 17] = INK
                im.hline(cx.toInt() - 3, cx.toInt() + 3, 22, INK); for (k in -2..2 step 2) im[cx.toInt() + k, 23] = PAPER
                // arms and claws
                for (s in listOf(-1, 1)) {
                    Pix.line(im, (cx + s * 10).toInt(), 28, (cx + s * 18).toInt(), 12, b1); Pix.line(im, (cx + s * 11).toInt(), 28, (cx + s * 19).toInt(), 12, b0)
                    for (k in 0..2) Pix.line(im, (cx + s * 19).toInt(), 12, (cx + s * (17 + k * 2)).toInt(), 8, PAPER)
                }
                Pix.skyline(im, 2, W - 3, 52, 14, c(0x1A1A28), r, lit = yellow)
                // a tiny screaming figure at the front
                Pix.figure(im, 12f, 56f, 10f, c(0xF0E0D0), pose = 4)
                im.rect(2, 53, W - 3, 56, c(0x2A2A3A))
                title(im, f.title, 57, H - 3, col(f, 3, INK), null, small0 = col(f, 3, INK)) { if (it < 4) Pix.mix(col(f, 2, red), c(0xFFFFFF), .45f) else col(f, 2, red) }
            }
            "western" -> {
                Pix.bands(im, 2, 2, W - 3, 50, col(f, 0, c(0xF6D85A)), col(f, 2, c(0xD8502A)), 6)
                Pix.disc(im, 30f, 30f, 8f, c(0xFFF0A0))
                // the mesas
                val mesa = c(0x7A3A2A)
                im.rect(2, 34, 18, 50, mesa); Pix.tri(im, 2f, 34f, 2f, 50f, -6f, 50f, mesa); Pix.tri(im, 18f, 34f, 24f, 50f, 18f, 50f, mesa)
                im.rect(34, 38, 45, 50, mesa)
                im.rect(2, 44, W - 3, 54, c(0x5A2A1E))
                // the rider: a horse's body, legs, the rider in a wide hat
                val dark = INK
                Pix.ellipse(im, 24f, 42f, 7f, 3.5f, dark)
                Pix.line(im, 29, 40, 33, 35, dark); Pix.line(im, 30, 40, 34, 35, dark); Pix.ellipse(im, 34f, 35f, 2.5f, 1.6f, dark)
                for (lx in listOf(19, 21, 27, 29)) Pix.line(im, lx, 44, lx + (if (lx % 4 == 1) -1 else 1), 49, dark)
                Pix.line(im, 17, 41, 14, 46, dark)
                Pix.figure(im, 24f, 41f, 13f, dark, pose = 3)
                // a cactus
                im.rect(8, 40, 9, 52, c(0x2A4A2A)); im.rect(6, 43, 6, 46, c(0x2A4A2A)); im.rect(6, 46, 8, 46, c(0x2A4A2A))
                title(im, f.title, 55, H - 3, INK, c(0x7A2A1A), small0 = INK) { if (it < 3) yellow else c(0xF08A2A) }
            }
            "musical", "romance", "comedy" -> {
                val bg = pick(listOf(intArrayOf(0xE85A8A.toInt(), 0x7A2A6A.toInt()), intArrayOf(0x3AA8B8.toInt(), 0x1E4A7A.toInt()), intArrayOf(0xF6A83A.toInt(), 0xC83A3A.toInt())), f)
                Pix.bands(im, 2, 2, W - 3, 54, col(f, 0, c(bg[0].toLong())), Pix.mix(col(f, 0, c(bg[1].toLong())), col(f, 1, c(bg[1].toLong())), if (f.colours.isEmpty()) 0f else .6f), 4)
                // spotlight rays from the top
                val ray = Pix.mix(c(bg[0].toLong()), c(0xFFFFFF), .45f)
                Pix.tri(im, 24f, 0f, 8f, 54f, 20f, 54f, ray); Pix.tri(im, 24f, 0f, 28f, 54f, 40f, 54f, ray)
                // the dancing couple
                Pix.figure(im, 19f, 50f, 22f, INK, pose = 1)
                Pix.figure(im, 30f, 50f, 20f, c(0xC8283A), pose = 4)
                // stars
                for (k in 0 until 6) { val x = 4 + r.int(W - 8); val y = 4 + r.int(20); im[x, y] = c(0xFFFFFF); im[x - 1, y] = yellow; im[x + 1, y] = yellow; im[x, y - 1] = yellow; im[x, y + 1] = yellow }
                im.rect(2, 50, W - 3, 54, c(0x3A1A2A))
                title(im, f.title, 55, H - 3, col(f, 3, INK), null, small0 = col(f, 3, INK)) { if (it < 4) col(f, 2, red) else Pix.mix(col(f, 2, red), c(0x000000), .25f) }
            }
            else -> {
                // noir and drama: a couple in a city at night, a big painted face behind
                Pix.bands(im, 2, 2, W - 3, 54, c(0x3A3A5A), c(0x1A1A28), 4)
                Pix.disc(im, 30f, 20f, 11f, c(0xE8B88A)); Pix.disc(im, 33f, 18f, 9f, c(0xF4D0A8))
                im.rect(19, 9, 41, 13, c(0x5A2A1A)); Pix.disc(im, 28f, 22f, 1.2f, INK); Pix.disc(im, 35f, 22f, 1.2f, INK); im.hline(29, 34, 27, red)
                Pix.skyline(im, 2, W - 3, 54, 18, c(0x14141E), r, lit = yellow)
                Pix.figure(im, 14f, 54f, 18f, INK, pose = 3)
                title(im, f.title, 55, H - 3, INK, null, small0 = INK) { red }
            }
        }
        // the paper's white border and a blurb up top
        im.border(c(0xC8B898))
        if (f.tagline.isNotEmpty()) { im.rect(2, 2, W - 3, 8, c(0xF2E8D0)); small(im, f.tagline, 3, c(0xA81E2A)) }
        grain(im, r, 30, c(0xE8DCC0), c(0xD8C8A8), 2, 2, W - 3, H - 3)
        if (f.rating.isNotEmpty()) im.text(if (f.rating.length > 4) "" else f.rating.uppercase(), W - 4 - PixImg.textWidth(f.rating.uppercase().take(4)), H - 7, c(0x7A6A5A))
    }

    /** 1960s: flat bold colour: the epic's sand and gold, the spy's black and target rings. */
    private fun sixties(im: PixImg, f: CinemaFilm, r: Paint.Rnd) {
        when (f.genre) {
            "epic", "western", "drama" -> {
                Pix.bands(im, 0, 0, W - 1, 46, c(0xF6C86A), c(0xC8682A), 5)
                Pix.disc(im, 34f, 18f, 7f, c(0xFFF2B0))
                // a temple's columns and a pyramid, an army of dots across the dunes
                Pix.tri(im, 6f, 40f, 18f, 22f, 30f, 40f, c(0xD89A4A)); Pix.tri(im, 18f, 22f, 30f, 40f, 18f, 40f, c(0xA8682A))
                im.rect(30, 26, 45, 28, c(0xE8D8B0)); for (x in 31..44 step 4) im.rect(x, 29, x + 1, 40, c(0xE8D8B0))
                im.rect(0, 40, W - 1, 48, c(0x8A4A2A))
                for (k in 0 until 40) im[r.int(W), 41 + r.int(6)] = if (r.next() < .5f) INK else c(0xE8C88A)
                im.rect(0, 48, W - 1, H - 1, c(0x1A1A28))
                title(im, f.title, 49, H - 10, c(0x5A2A0A), null, small0 = c(0xF6C86A)) { if (it < 3) c(0xFFF2B0) else c(0xE8A82A) }
                billing(im, c(0x8A7A6A), f.rating, r)
            }
            "surfer", "family", "musical", "comedy" -> {
                // the beach picture: a big sun, a curling wave, a surfer riding it
                Pix.bands(im, 0, 0, W - 1, 30, col(f, 0, c(0xF6D86A)), c(0xF6A84A), 4)
                Pix.disc(im, 34f, 16f, 9f, col(f, 2, c(0xE85A8A)))
                im.rect(0, 30, W - 1, 48, col(f, 1, c(0x2A6AA0)))
                for (x in 0 until W) { val y = 30 + ((x * 7 / 10) % 7); im.vline(x, y, y + 1, c(0xF0F8FF)) }
                Pix.ellipse(im, 18f, 34f, 12f, 8f, Pix.mix(col(f, 1, c(0x2A6AA0)), c(0xFFFFFF), .3f)); Pix.ellipse(im, 20f, 36f, 9f, 6f, col(f, 1, c(0x2A6AA0)))
                im.rect(14, 32, 26, 33, c(0xF6E8C0)); Pix.figure(im, 20f, 32f, 14f, c(0x1A1A28), pose = 1)
                im.rect(0, 48, W - 1, H - 1, c(0xF0E8D8))
                title(im, f.title, 49, H - 10, col(f, 3, c(0x1A2A4A)), null, small0 = col(f, 3, c(0x1A2A4A))) { if (it < 3) col(f, 2, c(0xE85A8A)) else Pix.mix(col(f, 2, c(0xE85A8A)), c(0x000000), .2f) }
                billing(im, c(0x8A8A9A), f.rating, r)
            }
            "castle", "horror" -> {
                // the gothic: a castle on a crag under a green moon, bats
                Pix.bands(im, 0, 0, W - 1, 48, c(0x0A1A10), col(f, 0, c(0x3A5A2A)), 4)
                Pix.disc(im, 14f, 14f, 7f, col(f, 1, c(0xE8D070)))
                Pix.tri(im, 10f, 48f, 30f, 22f, 52f, 48f, c(0x0A0A0A))
                im.rect(22, 18, 38, 34, c(0x0A0A0A)); for (x in listOf(22, 26, 30, 34)) im.rect(x, 15, x + 2, 18, c(0x0A0A0A)); im.rect(35, 10, 39, 22, c(0x0A0A0A)); Pix.tri(im, 34f, 10f, 37f, 4f, 40f, 10f, c(0x0A0A0A))
                im[29, 24] = col(f, 2, c(0xC83A2A)); im[29, 25] = col(f, 2, c(0xC83A2A)); im[37, 14] = c(0xF6C83A)
                for (k in 0 until 4) { val x = 8 + k * 9; val y = 26 + (k % 2) * 5; im.hline(x - 2, x + 2, y, c(0x0A0A0A)); im[x - 2, y - 1] = c(0x0A0A0A); im[x + 2, y - 1] = c(0x0A0A0A) }
                im.rect(0, 48, W - 1, H - 1, c(0x0A0A0A))
                title(im, f.title, 49, H - 10, c(0x3A0A0A), null, small0 = col(f, 1, c(0xE8D070))) { if (it < 3) Pix.mix(col(f, 2, c(0xC83A2A)), c(0xFFFFFF), .4f) else col(f, 2, c(0xC83A2A)) }
                billing(im, c(0x5A5A4A), f.rating, r)
            }
            else -> {
                // spy, thriller and the rest: black, mod target rings, a silhouette in a sharp suit
                val pal = pick(listOf(intArrayOf(0xE83A3A.toInt(), 0xF6E8D0.toInt()), intArrayOf(0x3AA8E8.toInt(), 0xF6E8D0.toInt()), intArrayOf(0xF6C83A.toInt(), 0xF6E8D0.toInt())), f)
                val a = col(f, 2, c(pal[0].toLong())); val b = col(f, 1, c(pal[1].toLong()))
                im.fill(c(0x101018))
                for (k in 0 until 5) Pix.ring(im, 30f, 24f, 22f - k * 4.5f, 2f, if (k % 2 == 0) a else c(0x101018))
                Pix.disc(im, 30f, 24f, 3f, a)
                Pix.figure(im, 17f, 48f, 30f, b, pose = 0)
                im.rect(14, 16, 20, 18, c(0x101018)); im[17, 19] = a
                // the gun-less pose: a raised hand
                Pix.line(im, 22, 25, 27, 14, b)
                im.rect(0, 48, W - 1, 49, a)
                title(im, f.title, 51, H - 10, c(0x101018), null, small0 = b) { b }
                billing(im, c(0x6A6A7A), f.rating, r)
            }
        }
    }

    /** 1970s: disasters and car chases, with the cast's photo blocks along the foot. */
    private fun seventies(im: PixImg, f: CinemaFilm, r: Paint.Rnd) {
        when (f.genre) {
            "chase", "action", "comedy" -> {
                Pix.bands(im, 0, 0, W - 1, 26, c(0xF6D88A), c(0xE8A84A), 3)
                // a road to the horizon, a car leaping with its dust and speed lines
                im.rect(0, 26, W - 1, 52, c(0xC89A5A)); for (k in 0 until 40) im[r.int(W), 27 + r.int(25)] = c(0xA87A4A)
                Pix.tri(im, 24f, 26f, -10f, 52f, 58f, 52f, c(0x5A4A3A))
                for (y in 30..50 step 5) im.rect(23, y, 24, y + 2, c(0xF6E8A8))
                im.rect(0, 22, W - 1, 26, c(0x8A6A3A))
                val car = pick(listOf(0xE83A2A, 0x2A5AE8, 0xF6C83A, 0x2A2A2A).map { it.toInt() }.map { intArrayOf(it) }, f)[0]
                val cc = c(car.toLong())
                im.rect(10, 34, 36, 39, cc); im.rect(15, 30, 29, 34, cc); im.rect(17, 31, 27, 33, c(0x8AC8E8)); im.hline(10, 36, 34, Pix.mix(cc, c(0xFFFFFF), .4f))
                Pix.disc(im, 15f, 40f, 3f, INK); Pix.disc(im, 31f, 40f, 3f, INK); Pix.disc(im, 15f, 40f, 1f, c(0xA8A8A8)); Pix.disc(im, 31f, 40f, 1f, c(0xA8A8A8))
                for (k in 0 until 4) im.hline(1, 7, 31 + k * 3, c(0xF6E8D0))
                for (k in 0 until 14) im[36 + r.int(10), 36 + r.int(8)] = c(0xC8A87A)
                im.rect(0, 52, W - 1, H - 1, c(0x3A2A1A))
                title(im, f.title, 51, H - 10, INK, null, small0 = c(0xF6D88A)) { c(0xF6C83A) }
            }
            "scifi" -> {
                // airbrushed space: a ringed planet glowing over a starfield, a starship streaking across
                Pix.bands(im, 0, 0, W - 1, 44, c(0x05050C), col(f, 0, c(0x2A1A3A)), 4)
                for (k in 0 until 40) im[r.int(W), r.int(44)] = if (r.next() < .3f) c(0xF6D8A0) else c(0xFFFFFF)
                Pix.disc(im, 30f, 26f, 13f, Pix.mix(col(f, 1, c(0xE87A2A)), c(0x000000), .3f)); Pix.disc(im, 28f, 24f, 11f, col(f, 1, c(0xE87A2A))); Pix.disc(im, 25f, 20f, 5f, Pix.mix(col(f, 1, c(0xE87A2A)), c(0xFFFFFF), .4f))
                for (x in 10..47) { val y = (26 + (x - 30) * .3f).toInt(); im[x, y] = col(f, 2, c(0xF8D04A)); im[x, y + 1] = Pix.mix(col(f, 2, c(0xF8D04A)), c(0x000000), .4f) }
                Pix.tri(im, 4f, 10f, 18f, 7f, 4f, 13f, c(0xD8D8E8)); for (k in 0 until 12) im[k - 8 + 4, 10] = c(0x8AC8FF)
                im.rect(0, 43, W - 1, H - 1, c(0x101010))
                title(im, f.title, 45, H - 10, c(0x2A1A0A), null, small0 = col(f, 3, c(0xF0E8D0))) { if (it < 3) c(0xFFF6D0) else col(f, 2, c(0xF8D04A)) }
            }
            "thriller" -> {
                // the conspiracy: one huge eye, a tiny figure caught in its pupil
                im.fill(c(0x101418))
                Pix.ellipse(im, 24f, 24f, 21f, 11f, c(0xE8E0D0)); Pix.disc(im, 24f, 24f, 9f, col(f, 1, c(0x3A6A7A))); Pix.disc(im, 24f, 24f, 4f, c(0x050505))
                Pix.figure(im, 24f, 27f, 6f, c(0xE8E0D0)); im[27, 21] = c(0xFFFFFF)
                for (x in 4..44) { val d = (x - 24) / 21f; val y = (24 - 11 * kotlin.math.sqrt((1 - d * d).coerceAtLeast(0f))).toInt(); im[x, y - 1] = c(0x2A2A2A) }
                im.rect(0, 40, W - 1, H - 1, c(0x101418))
                title(im, f.title, 44, H - 10, c(0x000000), null, scaleMax = 1, small0 = c(0xC8C8C8)) { col(f, 2, c(0xE84A2A)) }
            }
            "action" -> {
                // the cop picture: a lone figure under a street lamp, the city behind at dusk, a badge
                Pix.bands(im, 0, 0, W - 1, 46, col(f, 0, c(0x2A1A10)), Pix.mix(col(f, 1, c(0xE87A2A)), c(0x000000), .2f), 5)
                Pix.skyline(im, 0, W - 1, 46, 22, c(0x14100C), r, lit = col(f, 2, c(0xF8D04A)))
                im.rect(36, 10, 37, 46, c(0x14100C)); im.rect(32, 9, 37, 10, c(0x14100C)); Pix.tri(im, 32f, 10f, 26f, 46f, 38f, 46f, Pix.mix(col(f, 2, c(0xF8D04A)), c(0x000000), .55f))
                Pix.figure(im, 30f, 46f, 22f, c(0x0A0806), pose = 0)
                Pix.disc(im, 10f, 12f, 5f, col(f, 2, c(0xF8D04A))); Pix.disc(im, 10f, 12f, 3f, Pix.mix(col(f, 2, c(0xF8D04A)), c(0xFFFFFF), .4f)); im[10, 12] = c(0x8A6A1A)
                im.rect(0, 46, W - 1, H - 1, c(0x101010))
                title(im, f.title, 48, H - 10, c(0x000000), null, small0 = col(f, 3, c(0xF0E8D0))) { if (it < 3) c(0xFFFFFF) else col(f, 3, c(0xF0E8D0)) }
            }
            "horror" -> {
                // a dark house on a hill under a full moon, one window lit
                Pix.bands(im, 0, 0, W - 1, 56, c(0x0A0A14), c(0x3A1A3A), 4)
                Pix.disc(im, 34f, 14f, 8f, c(0xE8E0B8)); Pix.disc(im, 31f, 12f, 2f, c(0xC8C098))
                Pix.ellipse(im, 24f, 56f, 34f, 14f, c(0x08080C))
                im.rect(12, 30, 32, 46, c(0x08080C)); Pix.tri(im, 9f, 31f, 22f, 20f, 35f, 31f, c(0x08080C)); im.rect(27, 20, 30, 28, c(0x08080C))
                im.rect(19, 35, 21, 38, c(0xF6C83A)); im.rect(15, 35, 16, 37, c(0x1A1A28))
                for (k in 0 until 3) Pix.line(im, 36 + k * 3, 46, 37 + k * 2, 36 - k * 2, c(0x08080C))
                im.rect(0, 54, W - 1, H - 1, c(0x08080C))
                title(im, f.title, 55, H - 10, c(0x3A0A0A), null, small0 = c(0xE8E0B8)) { if (it < 3) c(0xF6E8D0) else c(0xC82A2A) }
            }
            "drama", "romance" -> {
                // two faces in big photo blocks, warm and grainy
                im.fill(c(0xE8D8B8))
                for ((k, x) in listOf(4, 25).withIndex()) {
                    im.rect(x, 6, x + 18, 40, c(0x3A2A22))
                    val skin = c(listOf(0xE8B890, 0xC88A60, 0xF0C8A0, 0x8A5A3A)[(k + f.seed) % 4].toLong())
                    Pix.ellipse(im, x + 9.5f, 24f, 6.5f, 9f, skin)
                    Pix.ellipse(im, x + 9.5f, 15f, 7.5f, 4f, c(listOf(0x3A2A1A, 0xE8C860, 0x8A3A1A, 0x1A1A1A)[(k * 3 + f.seed) % 4].toLong()))
                    im[x + 7, 23] = INK; im[x + 12, 23] = INK; im.hline(x + 8, x + 11, 29, c(0x8A3A3A))
                    for (q in 0 until 30) { val px = x + r.int(19); val py = 6 + r.int(35); im[px, py] = Pix.mix(im[px, py], c(0x000000), .25f) }
                }
                im.rect(0, 44, W - 1, H - 1, c(0xE8D8B8))
                title(im, f.title, 45, H - 10, c(0x5A2A1A), null, scaleMax = 1, small0 = c(0x5A2A1A)) { c(0x8A2A1A) }
            }
            else -> {
                // the disaster: a tower ablaze (or a liner overturning) under a black-orange sky
                Pix.bands(im, 0, 0, W - 1, 44, c(0x1A0A0A), c(0xD84A1A), 5)
                if (f.genre == "disaster" && f.seed % 2 == 0) {
                    // a liner upside down in the waves
                    im.rect(0, 30, W - 1, 44, c(0x1A3A5A))
                    im.rect(8, 22, 40, 30, c(0xE8E8E8)); im.rect(8, 22, 40, 23, c(0xA81E1E)); for (x in 11..38 step 4) im[x, 26] = c(0x2A2A3A)
                    for (k in 0 until 20) im[r.int(W), 30 + r.int(14)] = c(0x8AC8E8)
                } else {
                    im.rect(16, 6, 32, 44, c(0x3A3A4A)); for (y in 9 until 44 step 4) for (x in 18..30 step 3) im[x, y] = if (y < 22) c(0xF6A83A) else c(0xF6E8A8)
                    for (k in 0 until 30) { val x = 12 + r.int(24); val y = 2 + r.int(22); im[x, y] = if (r.next() < .5f) c(0xF6C83A) else c(0xE85A1A) }
                    Pix.tri(im, 14f, 14f, 24f, 0f, 34f, 14f, c(0xE8681A)); Pix.tri(im, 18f, 14f, 24f, 4f, 30f, 14f, c(0xF6C83A))
                }
                // the cast in photo blocks
                im.rect(0, 43, W - 1, H - 1, c(0x101010))
                for (k in 0 until 4) {
                    val x = 3 + k * 11
                    im.rect(x, 44, x + 9, 51, c(0x3A2A2A))
                    Pix.disc(im, x + 5f, 48.5f, 2.8f, c(listOf(0xE8B890, 0xC88A60, 0xF0C8A0, 0x8A5A3A)[(k + f.seed) % 4].toLong()))
                    im.rect(x + 2, 44, x + 7, 45, c(listOf(0x3A2A1A, 0xE8C860, 0x8A3A1A, 0x1A1A1A)[(k * 3 + f.seed) % 4].toLong()))
                    for (q in 0 until 6) im[x + 1 + r.int(8), 45 + r.int(6)] = c(0x5A4A4A)
                }
                title(im, f.title, 52, H - 10, c(0x101010), null, scaleMax = 1, small0 = c(0xF6E8D0)) { c(0xF6E8D0) }
            }
        }
        billing(im, c(0x8A7A6A), f.rating, r)
        grain(im, r, 40, c(0x2A2A2A), c(0xE8D8B0))
    }

    /** 1980s: neon grids to a striped sun, chrome titles; starships and explosions. */
    private fun eighties(im: PixImg, f: CinemaFilm, r: Paint.Rnd) {
        val chrome = intArrayOf(c(0xFFFFFF), c(0xC8E8FF), c(0x8AB8E8), c(0x5A6A9A), c(0x2A2A5A), c(0xE8A8F0), c(0xFFFFFF))
        when (f.genre) {
            "horror" -> {
                // a blood moon over bare trees, a clawed hand out of the earth
                im.fill(c(0x0A0610))
                val moon = col(f, 2, c(0xE83A3A)).let { if (lum(it) < .25f) c(0xE83A3A) else it }
                val mx = 14f + r.int(20)
                Pix.disc(im, mx, 16f, 10f, Pix.mix(moon, c(0x0A0610), .5f)); Pix.disc(im, mx, 16f, 8f, moon)
                for (t in 0 until 3) { val tx = 4 + t * 18 + r.int(6); Pix.line(im, tx, 46, tx + r.int(5) - 2, 22 + r.int(6), c(0x000000)); Pix.line(im, tx, 32, tx + 6, 26, c(0x000000)); Pix.line(im, tx, 36, tx - 5, 30, c(0x000000)) }
                im.rect(0, 44, W - 1, 50, c(0x1A0A14))
                val hand = c(0x8A9A7A)
                im.rect(20, 34, 27, 46, hand); for (k in 0 until 4) im.rect(20 + k * 2, 27 + (k % 2), 20 + k * 2, 34, hand); Pix.line(im, 27, 38, 31, 33, hand)
                for (k in 0 until 4) im[20 + k * 2, 26 + (k % 2)] = c(0xF6F0D0)
            }
            "scifi" -> {
                im.fill(c(0x0A0A1A))
                for (k in 0 until 50) im[r.int(W), r.int(50)] = if (r.next() < .3f) c(0x8AA8FF) else c(0xFFFFFF)
                // the planet (its place and colour seeded) with a ring, a starship crossing
                val pr = pick(listOf(intArrayOf(0x3A2A8A.toInt(), 0x5A4AC8.toInt(), 0x8A7AE8.toInt()), intArrayOf(0x8A2A1A.toInt(), 0xC85A2A.toInt(), 0xF6A86A.toInt()), intArrayOf(0x1A5A4A.toInt(), 0x2A9A7A.toInt(), 0x8AE8C8.toInt())), f.copy(palette = f.seed))
                val px = 14f + ((f.seed * 7) and 0x7fffffff) % 20; val py = 22f + ((f.seed * 3) and 0x7fffffff) % 14
                Pix.disc(im, px, py, 14f, c(pr[0].toLong())); Pix.disc(im, px + 3, py - 3, 12f, c(pr[1].toLong())); Pix.disc(im, px + 6, py - 6, 6f, c(pr[2].toLong()))
                val tilt = if (f.seed % 2 == 0) .25f else -.3f
                for (x in 0 until W) { val y = (py + (x - px) * tilt).toInt(); if (abs(x - px) > 6) { im[x, y] = c(0xE8A8F0); if (x % 2 == 0) im[x, y + 1] = c(0xA86AC8) } }
                val sy = 8f + r.int(8); val sx = if (px > 24) 4f else 26f
                Pix.tri(im, sx, sy + 4, sx + 18, sy, sx, sy + 10, c(0xA8A8C8)); Pix.tri(im, sx, sy + 4, sx + 18, sy, sx + 6, sy + 2, c(0xE8E8FF))
                im.rect(sx.toInt() - 2, sy.toInt() + 4, sx.toInt() - 1, sy.toInt() + 8, c(0x3AE8FF))
            }
            "action", "chase", "hero" -> {
                im.fill(c(0x1A0A1A))
                // an explosion behind a hero
                Pix.disc(im, 24f, 26f, 18f, c(0xA81E1A)); Pix.disc(im, 24f, 26f, 13f, c(0xE8681A)); Pix.disc(im, 24f, 24f, 8f, c(0xF6D83A)); Pix.disc(im, 24f, 23f, 3f, c(0xFFFFE8))
                for (k in 0 until 24) { val a = r.next() * 6.28f; val d = 14f + r.next() * 8f; im[(24 + cos(a) * d).toInt(), (26 + sin(a) * d).toInt()] = c(0xF6A83A) }
                Pix.figure(im, 24f, 50f, 34f, c(0x101010), pose = 0)
                im.rect(14, 22, 34, 25, c(0x101010))
            }
            else -> {
                // the grid: a magenta grid to a striped sun on the horizon
                Pix.bands(im, 0, 0, W - 1, 30, col(f, 0, c(0x1A0A3A)), Pix.mix(col(f, 0, c(0x1A0A3A)), col(f, 1, c(0xA82A8A)), if (f.colours.isEmpty()) 1f else .6f), 5)
                Pix.disc(im, 24f, 30f, 12f, col(f, 2, c(0xF6C83A))); for (y in 22..30 step 3) im.hline(10, 38, y, Pix.mix(c(0xA82A8A), c(0xF6683A), (y - 20) / 10f))
                im.rect(0, 31, W - 1, 50, c(0x14061E))
                for (k in -8..8) Pix.line(im, 24 + k * 2, 31, 24 + k * 9, 50, col(f, 1, c(0xE83AC8)))
                for (y in listOf(31, 33, 36, 40, 45, 50)) im.hline(0, W - 1, y, col(f, 1, c(0xE83AC8)))
                Pix.figure(im, 24f, 49f, 18f, c(0x0A0A14), pose = 0)
            }
        }
        im.rect(0, 50, W - 1, H - 1, c(0x0A0A14))
        title(im, f.title, 51, H - 10, c(0x14143A), null, small0 = c(0xC8E8FF)) { chrome[it.coerceIn(0, chrome.size - 1)] }
        // the neon edge
        for (x in 0 until W) { im[x, 0] = col(f, 1, c(0xE83AC8)); im[x, H - 1] = col(f, 2, c(0x3AE8FF)) }
        billing(im, c(0x6A5A8A), f.rating, r)
    }

    /** 1990s: a thriller's face half in shadow; a family picture's bright sky and a cheerful critter. */
    private fun nineties(im: PixImg, f: CinemaFilm, r: Paint.Rnd) {
        if (f.genre == "family" || f.genre == "comedy") {
            Pix.bands(im, 0, 0, W - 1, 46, c(0x3A8AE8), c(0x8AD8F6), 4)
            for ((k, col) in listOf(0xE83A3A, 0xF6A83A, 0xF6E83A, 0x3AC85A, 0x3A6AE8).withIndex()) Pix.ring(im, 24f, 46f, 30f - k * 2f, 2f, c(col.toLong()))
            Pix.ellipse(im, 24f, 52f, 34f, 12f, c(0x3AA83A))
            // the critter: round body, big ears, big eyes
            critter(im, 24f, 49f, col(f, 2, c(listOf(0xC88A4A, 0xE8E8E8, 0x8A8A9A, 0xF6C86A)[(f.palette % 4 + 4) % 4].toLong())))
            im.rect(0, 52, W - 1, H - 1, c(0x3AA83A))
            title(im, f.title, 53, H - 10, c(0x1E3A8A), c(0x0A1A4A), small0 = c(0xFFFFFF)) { if (it < 3) c(0xFFF6A0) else c(0xF6C83A) }
            billing(im, c(0x1E6A1E), f.rating, r)
        } else if (f.genre != "thriller" && f.genre != "horror") {
            // the photo montage: the cast's faces in a row under a dark sky, the title bold
            Pix.bands(im, 0, 0, W - 1, 30, col(f, 0, c(0x1A2A4A)), Pix.mix(col(f, 0, c(0x1A2A4A)), col(f, 2, c(0xE8A020)), .5f), 4)
            if (f.genre == "action" || f.genre == "disaster" || f.genre == "blockbuster" || f.genre == "scifi") { Pix.disc(im, 24f, 30f, 12f, Pix.mix(col(f, 2, c(0xE8A020)), c(0xC82A1A), .4f)); Pix.disc(im, 24f, 30f, 7f, col(f, 2, c(0xE8A020))) }
            else Pix.disc(im, 36f, 10f, 5f, Pix.mix(col(f, 2, c(0xE8A020)), c(0xFFFFFF), .4f))
            faces(im, 3, 24, 46, f.seed, r, Pix.mix(col(f, 0, c(0x1A2A4A)), c(0x000000), .3f))
            im.rect(0, 47, W - 1, H - 1, c(0x101010))
            title(im, f.title, 49, H - 10, c(0x000000), null, scaleMax = 1, small0 = col(f, 3, c(0xFFFFFF))) { col(f, 3, c(0xFFFFFF)) }
            billing(im, c(0x5A5A5A), f.rating, r)
        } else {
            // the thriller: black, a face half lit cold blue, the title small and spaced
            im.fill(c(0x08080C))
            val lit = col(f, 1, c(0x8AA8C8)); val mid = Pix.mix(lit, c(0x101018), .45f); val dark = Pix.mix(lit, c(0x000000), .8f)
            Pix.ellipse(im, 24f, 28f, 14f, 19f, dark)
            for (y in 9..47) for (x in 10..38) {
                val dx = (x + .5f - 24f) / 14f; val dy = (y + .5f - 28f) / 19f
                if (dx * dx + dy * dy <= 1f && x > 24 + (y - 28) / 6) im[x, y] = if (dx > .45f) mid else lit
            }
            im.rect(15, 24, 20, 25, c(0x08080C)); im.rect(28, 24, 33, 25, INK); im[30, 24] = c(0xE8F0FF)
            im.vline(25, 27, 34, mid); im.hline(20, 29, 39, c(0x2A3040))
            if (f.genre == "horror") for (k in 0 until 3) im.vline(8 + k * 15, 0, 10 + k * 3, c(0x8A1A1A))
            im.rect(0, 50, W - 1, H - 1, c(0x08080C))
            title(im, f.title, 51, H - 10, c(0x08080C), null, scaleMax = 1, small0 = c(0xE8E8F0)) { c(0xE8E8F0) }
            if (f.tagline.isNotEmpty()) small(im, f.tagline, 4, c(0x6A7A9A))
            billing(im, c(0x3A3A4A), f.rating, r)
        }
    }

    /** 2000s: the blockbuster's orange and teal, a hero from behind before the blast, a steel title. */
    private fun noughties(im: PixImg, f: CinemaFilm, r: Paint.Rnd) {
        Pix.bands(im, 0, 0, W - 1, 50, col(f, 0, c(0x0A3A4A)), Pix.mix(col(f, 0, c(0x0A3A4A)), col(f, 2, c(0x3A8A9A)), if (f.colours.isEmpty()) 1f else .55f), 5)
        if (f.genre == "comedy") {
            // the comedy one-sheet: a white field, the cast in a row grinning, a big red title
            im.fill(c(0xF8F8F4))
            for (k in 0 until 4) Pix.figure(im, 8f + k * 10.5f, 48f, 24f + (k % 2) * 3, listOf(c(0x2A3A6A), c(0xC83A2A), c(0x3A6A3A), c(0x5A3A2A))[(k + f.seed) % 4], pose = if (k % 2 == 0) 1 else 0)
            for (k in 0 until 4) Pix.disc(im, 8f + k * 10.5f, 48f - (24f + (k % 2) * 3) + 2f, 2.2f, c(listOf(0xE8B890, 0xC88A60, 0xF0C8A0, 0x8A5A3A)[(k + f.seed) % 4].toLong()))
            title(im, f.title, 50, H - 10, c(0x5A0A0A), null, small0 = c(0x3A3A3A)) { col(f, 2, c(0xE83A2A)) }
            billing(im, c(0xA8A8A8), f.rating, r, rows = 1)
            return
        }
        if (f.genre == "horror") {
            // found footage: night-vision green static, a figure at the end of a corridor
            for (y in 0 until H) for (x in 0 until W) im[x, y] = if (r.next() < .2f) c(0x2A5A2A) else if (r.next() < .1f) c(0x5A8A5A) else c(0x0A1A0A)
            Pix.tri(im, 24f, 20f, 4f, 50f, 44f, 50f, c(0x143014)); Pix.figure(im, 24f, 34f, 10f, c(0x000000))
            im.text("REC", 3, 3, c(0xE83A2A)); im[15, 4] = c(0xE83A2A)
            title(im, f.title, 54, H - 10, c(0x000000), null, scaleMax = 1, small0 = c(0xA8E8A8)) { c(0xD8F8D8) }
            billing(im, c(0x3A5A3A), f.rating, r, rows = 1)
            return
        }
        if (f.genre == "family") {
            // the bright family bill: a sky of balloons, a cartoon house
            Pix.bands(im, 0, 0, W - 1, 50, c(0x5AB8F6), c(0xC8F0FF), 3)
            for (k in 0 until 7) { val x = 6f + r.int(36); val y = 6f + r.int(22); Pix.ellipse(im, x, y, 3f, 4f, c(listOf(0xE83A3A, 0xF6C83A, 0x3AC85A, 0xE85AB8)[k % 4].toLong())); im.vline(x.toInt(), (y + 4).toInt(), (y + 10).toInt(), c(0x5A5A6A)) }
            im.rect(14, 34, 34, 50, c(0xF6E8C8)); Pix.tri(im, 11f, 35f, 24f, 24f, 37f, 35f, c(0xE85A3A)); im.rect(21, 42, 26, 50, c(0x5A3A2A))
        } else {
            // the blast below, the hero from behind against it
            Pix.disc(im, 24f, 52f, 22f, Pix.mix(col(f, 1, c(0xF6A83A)), c(0xC8281A), .45f)); Pix.disc(im, 24f, 54f, 15f, col(f, 1, c(0xF6A83A))); Pix.disc(im, 24f, 56f, 8f, c(0xFFE8A0))
            for (k in 0 until 30) { val a = r.next() * 3.14f + 3.14f; val d = 20f + r.next() * 10f; im[(24 + cos(a) * d).toInt(), (52 + sin(a) * d).toInt()] = c(0xF6683A) }
            Pix.skyline(im, 0, W - 1, 50, 14, c(0x0A1A24), r)
            Pix.figure(im, 24f, 54f, 26f, c(0x0A0A10), pose = 0)
        }
        im.rect(0, 54, W - 1, H - 1, c(0x0A1218))
        val steel = intArrayOf(c(0xFFFFFF), c(0xD8E0E8), c(0xA8B8C8), c(0x7A8A9A), c(0xC8D0D8), c(0xE8F0F8), c(0xA8B0B8))
        title(im, f.title, 55, H - 10, c(0x0A1218), null, small0 = c(0xA8B8C8)) { steel[it.coerceIn(0, steel.size - 1)] }
        // the release line: a season or a date, seeded
        val when0 = listOf("SUMMER", "THIS JULY", "MAY 4", "DECEMBER", "THIS FALL", "JUNE 18", "HOLIDAYS", "AUGUST")[(f.seed and 0x7fffffff) % 8]
        if (f.genre != "family") im.text(when0, (W - PixImg.textWidth(when0)) / 2, 3, c(0xF6A83A))
        billing(im, c(0x4A5A6A), f.rating, r, rows = 1)
    }

    /** 2010s: the team in a line on a ridge, a lens flare across a dark city, the title compact at the foot. */
    private fun tens(im: PixImg, f: CinemaFilm, r: Paint.Rnd) {
        val pal = pick(listOf(intArrayOf(0x0A1A3A.toInt(), 0x3A5A8A.toInt(), 0xE8A83A.toInt()), intArrayOf(0x2A0A0A.toInt(), 0x8A2A1A.toInt(), 0xF6C83A.toInt()), intArrayOf(0x0A2A2A.toInt(), 0x2A6A6A.toInt(), 0x8AE8F6.toInt())), f)
        if (f.genre == "horror") {
            // a dark hall, a door ajar spilling light, a small figure in it
            im.fill(c(0x08080C))
            Pix.tri(im, 24f, 30f, -20f, 72f, 68f, 72f, c(0x101016))
            im.rect(20, 14, 28, 34, Pix.mix(col(f, 1, c(0x3AC0D0)), c(0x000000), .5f)); im.rect(22, 15, 27, 34, col(f, 1, c(0x3AC0D0)))
            Pix.tri(im, 22f, 34f, 27f, 34f, 34f, 56f, Pix.mix(col(f, 1, c(0x3AC0D0)), c(0x08080C), .7f)); Pix.figure(im, 24f, 34f, 9f, c(0x000000))
            title(im, f.title, 57, H - 10, c(0x000000), null, scaleMax = 1, small0 = c(0xC8C8D0)) { c(0xE8E8F0) }
            billing(im, c(0x2A2A3A), f.rating, r, rows = 1)
            return
        }
        if (f.genre == "family") {
            Pix.bands(im, 0, 0, W - 1, 50, col(f, 0, c(0x3A8AE8)).let { if (lum(it) < .3f) c(0x3A8AE8) else it }, c(0xC8F0FF), 4)
            Pix.ellipse(im, 24f, 56f, 34f, 12f, c(0x3AA83A))
            critter(im, 16f, 52f, col(f, 2, c(0xF6C86A))); critter(im, 33f, 54f, Pix.mix(col(f, 1, c(0xE87A2A)), c(0xFFFFFF), .3f))
            im.rect(0, 56, W - 1, H - 1, c(0x2A8A2A))
            title(im, f.title, 57, H - 10, c(0x1E3A8A), c(0x0A1A4A), small0 = c(0xFFFFFF)) { if (it < 3) c(0xFFF6A0) else c(0xF6C83A) }
            billing(im, c(0x1E6A1E), f.rating, r, rows = 1)
            return
        }
        val top = col(f, 0, c(pal[0].toLong())); val bot = if (f.colours.isEmpty()) c(pal[1].toLong()) else Pix.mix(top, col(f, 2, top), .6f); val flare = col(f, 1, c(pal[2].toLong()))
        Pix.bands(im, 0, 0, W - 1, 56, top, bot, 5)
        Pix.skyline(im, 0, W - 1, 44, 18, Pix.mix(top, bot, .5f), r, lit = Pix.mix(flare, top, .4f))
        val lone = (f.seed and 0x7fffffff) % 3 == 1
        if (lone) {
            // the lone hero: a huge moon behind one caped figure on a rooftop's edge
            Pix.disc(im, 24f, 24f, 15f, Pix.mix(flare, top, .25f)); Pix.disc(im, 24f, 24f, 13f, Pix.mix(flare, c(0xFFFFFF), .35f))
            for (k in 0 until 6) Pix.disc(im, 17f + r.int(14), 18f + r.int(12), 1.5f + r.next() * 1.5f, Pix.mix(flare, top, .1f))
            im.rect(6, 48, 41, 56, c(0x08080C)); im.rect(9, 44, 12, 48, c(0x08080C))
            Pix.figure(im, 24f, 48f, 28f, c(0x08080C), pose = 2, cape = Pix.mix(bot, c(0x08080C), .7f))
            im.rect(0, 56, W - 1, H - 1, c(0x08080C))
            title(im, f.title, 57, H - 10, c(0x08080C), null, scaleMax = 1, small0 = c(0xE8E8F0)) { c(0xE8E8F0) }
            billing(im, c(0x3A3A4A), f.rating, r, rows = 1)
            return
        }
        // the flare (its height and its sun seeded)
        val fy = 18 + (f.seed and 0x7fffffff) % 9; val fx = 10f + ((f.seed * 13) and 0x7fffffff) % 28
        im.hline(0, W - 1, fy, Pix.mix(flare, c(0xFFFFFF), .5f)); im.hline(6, W - 7, fy - 1, flare); im.hline(6, W - 7, fy + 1, flare)
        Pix.disc(im, fx, fy.toFloat(), 3f, c(0xFFFFFF))
        // the ridge and the team: capes and heights varied
        Pix.ellipse(im, 24f, 62f, 30f, 14f, c(0x08080C))
        val n = 3 + f.seed % 3
        for (k in 0 until n) {
            val x = 24f + (k - (n - 1) / 2f) * (36f / n)
            val h = if (k == n / 2) 26f else 18f + ((k * 7 + f.seed) % 5)
            Pix.figure(im, x, 50f, h, c(0x08080C), pose = if ((k + f.seed) % 3 == 0) 2 else 0, cape = Pix.mix(bot, c(0x08080C), .6f))
        }
        im.rect(0, 56, W - 1, H - 1, c(0x08080C))
        title(im, f.title, 57, H - 10, c(0x08080C), null, scaleMax = 1, small0 = c(0xE8E8F0)) { c(0xE8E8F0) }
        billing(im, c(0x3A3A4A), f.rating, r, rows = 1)
    }

    /** 2020s: minimal: a flat pastel field, one simple sign, the title small and spaced; or a face in duotone. */
    private fun twenties(im: PixImg, f: CinemaFilm, r: Paint.Rnd) {
        val pal = pick(listOf(intArrayOf(0xE8C8B8.toInt(), 0x3A2A3A.toInt(), 0xD85A3A.toInt()), intArrayOf(0xB8D8C8.toInt(), 0x1A3A3A.toInt(), 0xF6E8D0.toInt()), intArrayOf(0x2A2A3A.toInt(), 0xE8E0D0.toInt(), 0xE8A83A.toInt()), intArrayOf(0xF6E8A8.toInt(), 0x2A2A2A.toInt(), 0x3A6AE8.toInt())), f)
        val bg = col(f, 0, c(pal[0].toLong())); val ink = col(f, 1, c(pal[1].toLong())); val acc = col(f, 2, c(pal[2].toLong()))
        im.fill(bg)
        val variant = when (f.genre) { "symbol" -> 0; "portrait", "thriller", "drama" -> 1; "portal", "horror" -> 2; "microphone" -> 4; "hero", "action" -> 5; else -> (f.seed and 0x7fffffff) % 4 }
        when (variant) {
            4 -> { // a microphone on its stand
                Pix.disc(im, 24f, 16f, 5f, ink); Pix.disc(im, 24f, 16f, 3.5f, acc); for (y in 12..20 step 2) im.hline(21, 27, y, ink)
                im.rect(23, 21, 24, 44, ink); im.rect(17, 44, 30, 45, ink)
            }
            5 -> { // a lone figure in a slab of light
                Pix.tri(im, 24f, 0f, 10f, 46f, 38f, 46f, Pix.mix(bg, acc, .35f)); Pix.figure(im, 24f, 46f, 16f, ink); im.hline(4, W - 5, 46, ink)
            }
            0 -> { Pix.disc(im, 24f, 26f, 9f, acc); im.hline(4, W - 5, 36, ink); Pix.figure(im, 14f, 36f, 6f, ink) }
            1 -> { // a duotone face
                for (y in 6..50) for (x in 6..41) { val dx = (x - 24f) / 15f; val dy = (y - 28f) / 20f; if (dx * dx + dy * dy < 1f) im[x, y] = if ((x + y) % 2 == 0 && x > 26) ink else Pix.mix(acc, ink, ((x - 6) / 36f) * .7f) }
                im.rect(14, 24, 20, 25, ink); im.rect(28, 24, 34, 25, bg)
            }
            2 -> { im.rect(20, 8, 27, 46, acc); im.rect(22, 10, 25, 44, bg); Pix.figure(im, 24f, 46f, 7f, ink) }
            else -> { for (k in 0 until 7) im.hline(8, W - 9, 12 + k * 5, if (k == 3) acc else Pix.mix(bg, ink, .2f)); Pix.disc(im, 24f, 27f, 3f, ink) }
        }
        // the title spaced wide, small, at the foot
        val lines = wrap(f.title, 11).take(4)
        var y = H - 2 - lines.size * 6
        val lt = readable(col(f, 3, ink), bg)
        for (ln in lines) { im.text(ln, (W - PixImg.textWidth(ln)) / 2, y, lt); y += 6 }
        if (f.tagline.isNotEmpty()) small(im, f.tagline, 3, Pix.mix(ink, bg, .4f))
        if (lines.size <= 2) im.text("IN CINEMAS", (W - PixImg.textWidth("IN CINEMAS")) / 2, H - 9 - lines.size * 6, Pix.mix(ink, bg, .5f))
    }
}

/**
 * A made-up bill for the desktop preview and as the fallback when the town's generator gave none: [n] films of [year]'s
 * decade (each poster in its style), seeded. Every title is put together from generic words.
 */
object CinemaBill {
    private val genres = mapOf(
        1940 to listOf("drama", "musical", "western", "monster"),
        1950 to listOf("monster", "western", "musical", "scifi", "drama", "romance"),
        1960 to listOf("epic", "spy", "western", "drama", "spy", "comedy"),
        1970 to listOf("disaster", "chase", "disaster", "horror", "chase", "drama"),
        1980 to listOf("action", "scifi", "drama", "horror", "comedy", "action"),
        1990 to listOf("thriller", "family", "thriller", "comedy", "family", "horror"),
        2000 to listOf("blockbuster", "family", "blockbuster", "comedy", "thriller"),
        2010 to listOf("hero", "hero", "drama", "family", "hero"),
        2020 to listOf("drama", "horror", "drama", "romance", "hero"),
    )
    /** Each genre's title parts: a lead (taking any noun phrase) and an end; no leads: the ends are whole titles. */
    private val words = mapOf(
        "monster" to (listOf("ATTACK OF THE", "RETURN OF THE", "NIGHT OF THE", "BEWARE THE", "REVENGE OF THE") to listOf("CRAB MEN", "GLOWMOTH", "MUD BEAST", "IRON SQUID", "SWAMP GIANT", "BOG WORMS")),
        "scifi" to (listOf("ROCKET TO", "INVADERS FROM", "ESCAPE FROM", "BEYOND", "LOST ON") to listOf("ORBIS", "THE RED MOON", "NEBULA NINE", "STAR CITY", "VOLTAR", "PLANET ZOG")),
        "western" to (listOf("SADDLE OF", "GUNSMOKE OVER", "THE LAST RIDE TO", "RIDERS OF", "SHOWDOWN AT") to listOf("COPPER CREEK", "MESA ROJA", "DRY GULCH", "LONE PINE", "BITTER WELLS", "DEADMAN FLATS")),
        "musical" to (listOf("DANCING ON", "A SONG FOR", "STEPPING OUT IN", "MOONLIGHT ON", "SPRINGTIME IN") to listOf("MAPLE LANE", "PARIS SPRING", "THE BANDSTAND", "BLUEBELL PIER", "CORAL BAY")),
        "romance" to (listOf("AN AFFAIR IN", "LETTERS FROM", "SUMMER IN", "THE GIRL FROM", "A KISS IN") to listOf("CAPRIOLA", "SEASIDE", "VENICE BAY", "LAKE MERRIN", "JUNE HARBOR")),
        "drama" to (listOf("THE WINTER OF", "HOUSE OF", "THE HOURS OF", "A LETTER TO", "THE LONG ROAD TO") to listOf("ASHFORD", "MARGO", "ORCHARD HILL", "THE VIGIL", "GRAYMOOR")),
        "epic" to (listOf("THE FALL OF", "KING OF", "SWORD OF", "THE SANDS OF", "EMPRESS OF") to listOf("KARTHOS", "ARAMOR", "TEN TOWERS", "VALERIA", "THE DELTA")),
        "spy" to (listOf("AGENT", "OPERATION", "CODE NAME", "DEADLINE FOR", "THE MAN FROM") to listOf("NIGHTJAR", "SILVERFOX", "KESTREL", "BRASSWOOD", "CIPHER NINE")),
        "comedy" to (emptyList<String>() to listOf("WHO STOLE THE TUBA", "MY UNCLE PIBBS", "BEACH WEEK MAYHEM", "THAT CRAZY TROPHY", "HOLIDAY WITH GUS", "OOPS ALL WEDDINGS")),
        "disaster" to (listOf("INFERNO AT", "THE BURNING", "FLOOD OF", "THE LAST VOYAGE OF", "TREMOR AT") to listOf("SKYTOWER", "THE ARCTURUS", "BAY CITY", "PIER 40", "GLASS SUMMIT")),
        "chase" to (emptyList<String>() to listOf("HIGHWAY BANDITS", "FREEWAY HAULERS", "DUSTBOWL PURSUIT", "RED LIGHT RUNNERS", "THE COPPERHEAD RUN", "SMOKEY RIDGE GETAWAY")),
        "horror" to (listOf("DON'T OPEN", "WHISPERS IN", "IT CAME FROM", "THE THING IN", "NIGHT AT") to listOf("THE CELLAR", "THE ATTIC", "ROOM 9", "THE FOG BANK", "BLACK LAKE", "HOLLOW PINES")),
        "action" to (emptyList<String>() to listOf("IRON TEMPEST", "STEEL VENDETTA", "RAZOR PROTOCOL", "BLACKOUT RUN", "THUNDERHEAD", "LAST SHOT AT ZERO")),
        "thriller" to (emptyList<String>() to listOf("THE GRAYMOOR WITNESS", "COLD TRACE", "SILENT ALIBI", "THE NINTH DOOR", "BLIND CORNER", "PALE MOTIVE")),
        "family" to (listOf("A PUP NAMED", "MY FRIEND", "THE AMAZING", "GOOD BOY", "LITTLE") to listOf("WAFFLES", "GIZMO", "PICKLES", "BARNABY", "NOODLE", "BISCUIT")),
        "blockbuster" to (emptyList<String>() to listOf("SKYFIRE UPRISING", "DARK MERIDIAN", "PLANETFALL", "TITANFORGE", "BREACH OF DAWN", "ZERO HORIZON")),
        "hero" to (emptyList<String>() to listOf("GUARDIANS OF VOLTA", "THE MIGHTY PRISM", "LEGION OF THE STORM", "CAPTAIN AURORA", "KEEPERS OF SEVEN SUNS", "VOLTA RISING", "THE PRISM SQUAD")),
    )
    private val taglines = listOf("SEE IT NOW", "YOU WON'T BELIEVE IT", "THE PICTURE OF THE YEAR", "NO ONE IS SAFE", "ONE LAST CHANCE",
        "LAUGH TILL IT HURTS", "IN GLORIOUS COLOR", "THE LEGEND BEGINS", "EVERY SECOND COUNTS", "BRING THE FAMILY", "SOME DOORS STAY SHUT")

    internal fun rating(decade: Int, r: Paint.Rnd, genre: String): String = when {
        decade < 1970 -> if (decade < 1960) "" else "APPROVED"
        genre == "family" -> "G"
        genre == "horror" || genre == "thriller" -> "R"
        else -> listOf("PG", "PG", "PG-13", "R")[r.int(4)].let { if (decade < 1990 && it == "PG-13") "PG" else it }
    }

    fun film(decade: Int, seed: Int): CinemaFilm {
        val r = Paint.Rnd(seed * 31 + decade)
        val g = (genres[decade] ?: genres[decade.coerceIn(1940, 2020) / 10 * 10] ?: genres.getValue(2020))
        val genre = g[r.int(g.size)]
        val (a, b) = words[genre] ?: words.getValue("drama")
        val title = if (a.isNotEmpty() && r.next() < .75f) "${a[r.int(a.size)]} ${b[r.int(b.size)]}" else b[r.int(b.size)]
        return CinemaFilm(title, taglines[r.int(taglines.size)], rating(decade, r, genre), genre, decade, r.int(12), seed)
    }

    /** [year]'s bill: [now] showing and [soon] coming, seeded by [seed] and the year (what plays changes each year). */
    fun sample(name: String, year: Int, seed: Int, now: Int = 4, soon: Int = 4): CinemaInfo {
        val decade = (year / 10 * 10).coerceIn(1940, 2020)
        val films = ArrayList<CinemaFilm>()
        var k = 0
        while (films.size < now + soon && k < 60) {
            val f = film(decade, seed * 1009 + year * 13 + k++)
            if (films.none { it.title == f.title }) films += f
        }
        return CinemaInfo(name, year, films.take(now), films.drop(now).take(soon))
    }
}
