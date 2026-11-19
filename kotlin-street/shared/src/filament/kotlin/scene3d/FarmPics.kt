package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.lifemax.rpg.feature.habits.domain.CropBook

/**
 * The farms' pictures ([TownFarms]), in the pack's pixels: the crop sheet's soil tiles (tilled, dark, ploughed; snow lying
 * on them with the furrows still showing), a hay meadow (growing, mown with its swaths, winter-brown, under snow), the
 * farmyard's dirt and the lanes' and verges' gravel, the fences' boards, the country road (the town's asphalt with white
 * edge lines and a dashed yellow centre), and stubble cards (corn's cut stalks, wheat's short brush).
 */
internal class FarmPics(private val atlas: PixImg?, private val book: CropBook?, private val art: FarmArt) {
    private fun c(rgb: Long) = Paint.c(rgb)

    private fun tile(name: String, fallback: Int): PixImg {
        val r = book?.soils?.get(name)
        val a = atlas
        val out = PixImg(32, 32)
        if (r == null || a == null) { out.fill(fallback); return out }
        for (y in 0 until 32) for (x in 0 until 32) out[x, y] = a[r[0] + x, r[1] + y] or (0xFF shl 24)
        return out
    }

    private fun lum(p: Int): Float = (((p shr 16) and 255) * .3f + ((p shr 8) and 255) * .59f + (p and 255) * .11f) / 255f
    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 255) * (1f - t) + ((b shr s) and 255) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    val soil: PixImg by lazy { tile("tilled", c(0x8A5A3A)) }
    val soilFurrows: PixImg by lazy { tile("furrows", c(0x7E5236)) }
    /** Bare winter ground: the dark tile, a frost's grey over it. */
    val soilDark: PixImg by lazy { tile("tilled_dark", c(0x5E4030)).also { im -> for (i in im.px.indices) im.px[i] = mix(im.px[i], c(0x8C8478), .25f) } }
    /** Snow over the furrows: white, the tile's dark lines showing through as blue-grey hollows. */
    val snowSoil: PixImg by lazy {
        // (the ridges' own slopes shade it; the soil shows through only in a few specks and a broken line down a furrow)
        val t = tile("tilled", c(0x8A5A3A))
        val r = Paint.Rnd(41)
        PixImg(32, 32).also { im ->
            for (y in 0 until 32) for (x in 0 until 32) {
                val v = r.next()
                im[x, y] = when {
                    v < .025f -> t[x, y] or (0xFF shl 24)
                    v < .09f -> c(0xD6E0EC)
                    y == 15 && r.next() < .35f -> c(0xC4D0E0)
                    else -> c(0xF1F5FA)
                }
            }
        }
    }

    private fun grassy(base: Long, dark: Long, light: Long, tall: Long, seed: Int, stripes: Boolean = false): PixImg {
        val im = PixImg(32, 32)
        val r = Paint.Rnd(seed)
        for (y in 0 until 32) for (x in 0 until 32) {
            val v = r.next()
            im[x, y] = c(if (v < .18f) dark else if (v < .32f) light else base)
        }
        // blades: short upright strokes
        repeat(60) { val x = r.int(32); val y = r.int(32); im[x, y] = c(tall); im[x, (y + 1) % 32] = c(tall) }
        if (stripes) for (y in 0 until 32) for (x in 0 until 32) if ((y / 8) % 2 == 0) im[x, y] = mix(im[x, y], c(0xE2D58A), .35f)
        return im
    }

    val meadow: PixImg by lazy { grassy(0x5C9A3C, 0x3F7A30, 0x7DB54A, 0x9BCB5A, 3) }
    /** A mown meadow: short, paler, the swaths in bands. */
    val mown: PixImg by lazy { grassy(0x9AB45A, 0x7F9A48, 0xB7C66E, 0xD9CF7A, 5, stripes = true) }
    val meadowWinter: PixImg by lazy { grassy(0x9C8A5E, 0x7E6E4C, 0xB4A478, 0xC2B080, 7) }
    val snowMeadow: PixImg by lazy {
        val r = Paint.Rnd(9)
        PixImg(32, 32).also { im -> for (i in im.px.indices) im.px[i] = if (r.next() < .07f) c(0xB4A478) else if (r.next() < .1f) c(0xDCE4EE) else c(0xF1F5FA) }
    }

    val dirt: PixImg by lazy {
        val t = tile("dirt", c(0xB08A5C))
        val r = Paint.Rnd(13)
        t.also { im -> for (i in im.px.indices) if (r.next() < .05f) im.px[i] = mix(im.px[i], c(0x6E5038), .5f) else if (r.next() < .03f) im.px[i] = c(0x7A9A48) }
    }

    val gravel: PixImg by lazy {
        val r = Paint.Rnd(17)
        PixImg(32, 32).also { im ->
            for (i in im.px.indices) { val v = r.next(); im.px[i] = c(if (v < .2f) 0x8A8070 else if (v < .4f) 0xB8AE98 else if (v < .48f) 0x6E6656 else 0xA39A86) }
        }
    }

    /** Weathered boards, grain running along u. */
    val wood: PixImg by lazy {
        val r = Paint.Rnd(23)
        PixImg(16, 16).also { im ->
            for (y in 0 until 16) for (x in 0 until 16) {
                val v = r.next()
                im[x, y] = c(if (y % 4 == 3) 0x5A4030 else if (v < .2f) 0x7A5A40 else if (v < .3f) 0xA88460 else 0x8E6C4C)
            }
        }
    }

    /** The country road across its width ([ROAD_TEX] texels, 64 along): the town's asphalt, edge lines, a dashed centre. */
    val road: PixImg by lazy {
        val w = ROAD_TEX; val h = 64
        val src = art.townImage("asphalt/asphalt_24.png")?.let { im -> PixImg(im.width, im.height).also { StreetTextures.argb(im).copyInto(it.px) } }
        val r = Paint.Rnd(29)
        PixImg(w, h).also { im ->
            for (y in 0 until h) for (x in 0 until w) {
                val a = src?.let { it[x % it.w, y % it.h] or (0xFF shl 24) } ?: c(if (r.next() < .2f) 0x48444A else 0x534F52)
                val edge = x == 4 || x == 5 || x == w - 5 || x == w - 6
                val mid = (x == w / 2 - 1 || x == w / 2) && y < h / 2
                im[x, y] = when {
                    x < 2 || x >= w - 2 -> c(0x6E6A64)
                    edge -> c(0xE9E6DA)
                    mid -> c(0xE8C440)
                    else -> a
                }
            }
        }
    }

    /** Corn stubble: a row's cut stalks, dry and broken, a leaf or two lying. */
    val cornStubble: PixImg by lazy {
        val r = Paint.Rnd(31)
        PixImg(20, 12).also { im ->
            for (k in 0 until 4) {
                val x = 2 + k * 5 + r.int(2); val hgt = 5 + r.int(6)
                for (y in 12 - hgt until 12) { im[x, y] = c(0xC8A868); im[x + 1, y] = c(0x9C7E48) }
                im[x, 12 - hgt] = c(0xE0C888)
            }
            for (k in 0 until 3) { val x = r.int(16); im.hline(x, x + 3, 11 - r.int(2), c(0xB89A5A)) }
        }
    }

    /** Wheat stubble: a short golden brush. */
    val wheatStubble: PixImg by lazy {
        val r = Paint.Rnd(37)
        PixImg(24, 7).also { im ->
            for (x in 0 until 24) {
                if (r.next() < .35f) continue
                val hgt = 3 + r.int(4)
                for (y in 7 - hgt until 7) im[x, y] = c(if (y == 7 - hgt) 0xF0D890 else if (r.next() < .3f) 0xB09040 else 0xD8B860)
            }
        }
    }

    private val canopies = HashMap<String, PixImg>()

    /**
     * A crop's canopy seen from above, from its frame ([u], [v], [w], [h] of the crop sheet): its own colours scattered over
     * 16 x 16 texels with a third left open, so a field of it reads as a crop from above and from afar (where the cards
     * are not drawn) and its soil shows between.
     */
    fun canopy(u: Int, v: Int, w: Int, h: Int, open: Float): PixImg = synchronized(canopies) {
        canopies.getOrPut("$u/$v/$w/$h/$open") {
            val a = atlas
            val cols = ArrayList<Int>()
            if (a != null) for (y in v until v + h) for (x in u until u + w) {
                val p = a[x, y]
                if ((p ushr 24) < 200) continue
                // (not the outline's darkest ink)
                if (lum(p) < .16f) continue
                cols += p or (0xFF shl 24)
            }
            if (cols.isEmpty()) cols += c(0x4F8A34)
            val r = Paint.Rnd(u * 31 + v * 7 + w)
            PixImg(16, 16).also { im -> for (i in im.px.indices) im.px[i] = if (r.next() < open) 0 else cols[r.int(cols.size)] }
        }
    }

    companion object { const val ROAD_TEX = 118 }
}
