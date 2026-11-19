package com.lifemax.rpg.feature.habits.presentation.scene3d

/**
 * The swatch library: seamless tiles cut from the LimeZu sprites (tools/swatches/swatches.py cuts them, the atlas is
 * tools/swatches/atlas/atlas.png, the data [SwatchData] is generated). General: anything built in code (stores, later
 * buildings, interiors, farms) draws its materials from here, at the art's own pixel size, never resampled.
 *
 * `get(id)` is the tile as cut. `tinted(id, ramp)` re-colours it in a [Ramp] by tone (dark / base / light / highlight),
 * keeping the art's pixel structure: the bricks and block courses are painted as grey tones for this.
 */
internal object Swatches {
    private val ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val tiles = HashMap<String, PixImg>()
    private val tinted = HashMap<String, PixImg>()

    val ids: List<String> get() = SwatchData.TILES.map { it.first }

    private fun decode(id: String): PixImg = tiles.getOrPut(id) {
        val t = SwatchData.TILES.firstOrNull { it.first == id } ?: error("no swatch $id")
        val (w, h) = t.second
        PixImg(w, h).also { im ->
            for (i in 0 until w * h) {
                val k = ALPHA.indexOf(t.third[i * 2]) * 64 + ALPHA.indexOf(t.third[i * 2 + 1])
                im.px[i] = SwatchData.PALETTE[k]
            }
        }
    }

    /** A copy of the swatch [id] (safe to paint on). */
    fun get(id: String): PixImg = decode(id).let { s -> PixImg(s.w, s.h).also { s.px.copyInto(it.px) } }

    /** The swatch's size. */
    fun size(id: String): Pair<Int, Int> = decode(id).let { it.w to it.h }

    /** One pixel, wrapping (for laying a swatch over a larger picture). */
    fun at(id: String, x: Int, y: Int): Int = decode(id).let { it.px[Math.floorMod(y, it.h) * it.w + Math.floorMod(x, it.w)] }

    /** Fills [im]'s rectangle with swatch [id] tiled from its own corner (wrapping), the art's pixels 1:1. */
    fun fill(im: PixImg, id: String, x0: Int = 0, y0: Int = 0, x1: Int = im.w - 1, y1: Int = im.h - 1, ox: Int = 0, oy: Int = 0) {
        val s = decode(id)
        for (y in y0..y1) for (x in x0..x1) im[x, y] = s.px[Math.floorMod(y - y0 + oy, s.h) * s.w + Math.floorMod(x - x0 + ox, s.w)]
    }

    private fun lum(c: Int) = ((c shr 16 and 255) * 3 + (c shr 8 and 255) * 4 + (c and 255)) / 8

    /**
     * Swatch [id] in [r]'s colours by tone. A `*_courses` swatch (painted as fixed grey roles) maps role for role (its
     * joints take [mortar] when given, else the ramp's outline); a cut one by where each pixel stands among the tile's
     * own tones (the darkest 18% dark, the lightest 18% light, the top 2% highlight).
     */
    fun tinted(id: String, r: Ramp, mortar: Int = r.o): PixImg = tinted.getOrPut("$id/${r.b}/${r.d}/$mortar") {
        val s = decode(id)
        val out = PixImg(s.w, s.h)
        if (id.endsWith("_courses")) {
            for (i in s.px.indices) { val v = lum(s.px[i]); out.px[i] = when { v < 0x50 -> mortar; v < 0x70 -> r.d; v < 0x96 -> r.b; v < 0xC0 -> r.l; else -> r.h } }
        } else {
            val sorted = s.px.map { lum(it) }.sorted()
            val lo = sorted[sorted.size * 18 / 100]; val hi = sorted[sorted.size * 82 / 100]; val top = sorted[sorted.size * 98 / 100]
            for (i in s.px.indices) { val v = lum(s.px[i]); out.px[i] = when { v <= lo -> r.d; v >= top && top > hi -> r.h; v >= hi -> r.l; else -> r.b } }
        }
        out
    }
}
