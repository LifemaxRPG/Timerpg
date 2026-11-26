package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.lifemax.rpg.feature.habits.domain.WorldTerrain
import kotlin.math.abs

/**
 * The near terrain's ground pictures (WORLD_GEN.md W5a; the terrain atlas's layers, [WorldTerrain.GRASS]...): seamless,
 * [n] texels a side at the town's texel (0.0389 m, so a 1024 picture is 39.8 m before it repeats; terrain.mat breaks the
 * repeat with a slow variation), painted as pixel art: a few flat shades a material broken by noise and an ordered dither,
 * then its small things scattered (tufts, pebbles, stones, furrows, ripples). The grass is the town's own picture
 * ([TownGround.grassPicture], the pack's grass tiles' tufts stamped in), the asphalt the town's asphalt tile, the gravel
 * the farms' (FarmPics) palette, so the countryside matches the town where they meet.
 */
internal object TerrainPics {
    private fun c(rgb: Long) = Paint.c(rgb)
    private val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    /** Layer [k] ([WorldTerrain.GRASS]...) as ARGB texels, [n] a side; [grass]: the grass layer if made already. */
    fun layer(k: Int, n: Int, pic: (String) -> PixImg?, grass: PixImg?): IntArray = when (k) {
        WorldTerrain.GRASS -> (grass ?: TownGround.grassPicture(n, pic)).px
        WorldTerrain.DRY -> dry(grass ?: TownGround.grassPicture(n, pic))
        WorldTerrain.DIRT -> dirt(n)
        WorldTerrain.FARM_X -> farm(n, false)
        WorldTerrain.FARM_Z -> farm(n, true)
        WorldTerrain.SAND -> sand(n)
        WorldTerrain.ROCK -> rock(n)
        WorldTerrain.SNOW -> snow(n)
        WorldTerrain.GRAVEL -> gravel(n)
        WorldTerrain.FOREST -> forest(n)
        WorldTerrain.WATER -> water(n)
        WorldTerrain.WHEAT -> wheat(n)
        WorldTerrain.CROP_X -> crop(n, false)
        WorldTerrain.CROP_Z -> crop(n, true)
        WorldTerrain.PASTURE -> pasture(grass ?: TownGround.grassPicture(n, pic))
        WorldTerrain.MEADOW -> meadow(grass ?: TownGround.grassPicture(n, pic))
        WorldTerrain.SCRUB -> scrub(grass ?: TownGround.grassPicture(n, pic))
        WorldTerrain.MARSH -> marsh(n)
        WorldTerrain.DUNE -> dune(n)
        WorldTerrain.WET_SAND -> wetSand(n)
        else -> asphalt(n, pic)
    }

    // ------------------------------------------------------------------ step 1b: the country's crops, grazing, wild ground and shore

    /** Standing wheat: straw-gold heads in short upright strokes, the drill rows faint along x, a darker patch where the wind laid it. */
    private fun wheat(n: Int): IntArray {
        val px = shades(n, intArrayOf(c(0xB08A3E), c(0xBE9A48), c(0xCAA852), c(0xD4B45E), c(0xDEC06C)), 501, midCells = 30, fineCells = 140, spread = .8f)
        val r = Paint.Rnd(502)
        // the heads: strokes two or three texels tall, light on top
        repeat(n * n / 9) {
            val x = r.int(n); val y = r.int(n); val len = 2 + r.int(2)
            val col = if (r.next() < .5) c(0xE8CC7A) else c(0xC69E4C)
            for (k in 0 until len) px[((y + k) % n) * n + x] = if (k == 0) col else c(0xA88440)
        }
        // the drill rows (every 0.5 m) a little darker between
        for (y in 0 until n) if (y % 13 == 0) for (x in 0 until n) if ((x * 7 + y) % 5 != 0) px[y * n + x] = c(0x9E7C38)
        // a few green weeds and the odd poppy
        scatter(px, n, n * n / 900, 503, intArrayOf(c(0x7A9A40), c(0x6E8E38)), 2)
        scatter(px, n, n * n / 6000, 504, intArrayOf(c(0xC8402E)))
        return px
    }

    /** A row crop (corn, beans, cotton): broad green rows (about 0.8 m apart) over dark soil, leaves lit on top; along x (or z, [across]). */
    private fun crop(n: Int, across: Boolean): IntArray {
        val period = 21
        val soil = shades(n, intArrayOf(c(0x5E4430), c(0x664A34), c(0x6E5038)), 511, spread = .6f)
        val leaf = shades(n, intArrayOf(c(0x2E5E28), c(0x3A6E2E), c(0x467E34), c(0x52903A), c(0x64A244)), 512, midCells = 40, fineCells = 200)
        val wob = TownGround.wrapNoise(n, 32, 513)
        val px = IntArray(n * n)
        val r = Paint.Rnd(514)
        val jag = IntArray(n) { r.int(3) }
        for (y in 0 until n) for (x in 0 until n) {
            val (a, b) = if (across) y to x else x to y
            val row = Math.floorMod(b + (wob(x, y) * 2f).toInt(), period)
            // (the leaves reach a ragged way across the gap, by the texel along the row)
            val reach = 6 + jag[a] + (if ((a / 3) % 4 == 0) 2 else 0)
            val d = kotlin.math.abs(row - period / 2)
            px[y * n + x] = when {
                d <= reach - 2 -> leaf[y * n + x]
                d <= reach -> if ((a + row) % 3 == 0) c(0x24502A) else leaf[y * n + x]
                d == reach + 1 -> c(0x4E3826)
                else -> soil[y * n + x]
            }
        }
        // highlights on the leaves' tops
        scatter(px, n, n * n / 60, 515, intArrayOf(c(0x7AB850), c(0x6CAC48)))
        return px
    }

    /** Pasture: the town's grass grazed short (a fresher, yellower green), clover and the odd darker tussock. */
    private fun pasture(grass: PixImg): IntArray {
        val n = grass.w
        val px = recolour(grass.px, intArrayOf(c(0x4A8E44), c(0x529848), c(0x5AA04C), c(0x62A850), c(0x6CB056), c(0x78B85E)))
        scatter(px, n, n * n / 500, 521, intArrayOf(c(0xF2F2E4), c(0xE6E6D6)))
        scatter(px, n, n * n / 1400, 522, intArrayOf(c(0x3E7A3A), c(0x447E3C)), 3)
        return px
    }

    /** A hay meadow: longer grass, a bluer green, flowers (white, yellow, violet, red) scattered in drifts. */
    private fun meadow(grass: PixImg): IntArray {
        val n = grass.w
        val px = recolour(grass.px, intArrayOf(c(0x3E7E44), c(0x46884A), c(0x4E924E), c(0x589C52), c(0x64A658), c(0x72B060)))
        // long blades: vertical strokes
        val r = Paint.Rnd(531)
        repeat(n * n / 30) {
            val x = r.int(n); val y = r.int(n)
            px[y * n + x] = c(0x7CB866); px[((y + 1) % n) * n + x] = c(0x4A8A4A)
        }
        val drift = TownGround.wrapNoise(n, 12, 532)
        val fl = intArrayOf(c(0xFFFFF0), c(0xF4D84A), c(0xA87ED0), c(0xE8E8F8), c(0xD84A3A))
        repeat(n * n / 120) {
            val x = r.int(n); val y = r.int(n)
            if (drift(x, y) < .45f) return@repeat
            val col = fl[r.int(fl.size)]
            px[y * n + x] = col
            if (r.next() < .5) px[y * n + (x + 1) % n] = col
        }
        return px
    }

    /** Scrub: dry grass with shrubs (round clumps of olive leaves, dark under, lit on top) a few texels to a metre across. */
    private fun scrub(grass: PixImg): IntArray {
        val n = grass.w
        val px = dry(grass)
        val r = Paint.Rnd(541)
        val leaf = intArrayOf(c(0x4E5E2E), c(0x5A6C34), c(0x66783A), c(0x768A44))
        repeat(n * n / 700) {
            val cx = r.int(n); val cy = r.int(n); val rad = 3 + r.int(10)
            for (dy in -rad..rad) for (dx in -rad..rad) {
                val d2 = dx * dx + dy * dy * 1.3f
                if (d2 > rad * rad) continue
                val x = Math.floorMod(cx + dx, n); val y = Math.floorMod(cy + dy, n)
                px[y * n + x] = when {
                    d2 > (rad - 1) * (rad - 1) && dy > 0 -> c(0x3A4422)
                    dy < -rad / 2 && r.next() < .5 -> leaf[3]
                    else -> leaf[r.int(3)]
                }
            }
        }
        scatter(px, n, n * n / 900, 542, intArrayOf(c(0x8A7A62), c(0x9A8A70)), 2)
        return px
    }

    /** Marsh: dark reeds and sedge in upright strokes, standing water showing between in pools. */
    private fun marsh(n: Int): IntArray {
        val px = shades(n, intArrayOf(c(0x3A5A34), c(0x42643A), c(0x4C6E40), c(0x587A46)), 551, midCells = 28)
        val pools = TownGround.wrapNoise(n, 18, 552)
        val fine = TownGround.wrapNoise(n, 90, 553)
        for (y in 0 until n) for (x in 0 until n) {
            val v = pools(x, y) * .75f + fine(x, y) * .25f
            if (v > .62f) px[y * n + x] = if (v > .7f) c(0x3E6488) else c(0x486C88)
            else if (v > .6f) px[y * n + x] = c(0x2E4A2C)
        }
        val r = Paint.Rnd(554)
        repeat(n * n / 14) {
            val x = r.int(n); val y = r.int(n)
            if (pools(x, y) > .6f) return@repeat
            val len = 2 + r.int(3)
            for (k in 0 until len) px[((y + k) % n) * n + x] = if (k == 0) c(0x8AA058) else c(0x5A7A3E)
        }
        // cattails
        scatter(px, n, n * n / 3000, 555, intArrayOf(c(0x6A4A2E)), 2)
        return px
    }

    /** Dunes: pale wind-rippled sand, marram grass in tufts of straw-green blades. */
    private fun dune(n: Int): IntArray {
        val px = sand(n)
        val r = Paint.Rnd(561)
        val clumps = TownGround.wrapNoise(n, 10, 562)
        repeat(n * n / 60) {
            val x = r.int(n); val y = r.int(n)
            if (clumps(x, y) < .5f) return@repeat
            val len = 2 + r.int(4)
            val lean = if (r.next() < .5) 1 else -1
            for (k in 0 until len) {
                val xx = Math.floorMod(x + (if (k > 1) lean else 0), n); val yy = Math.floorMod(y - k, n)
                px[yy * n + xx] = if (k == len - 1) c(0xB4BC78) else if (k == 0) c(0x6E7A44) else c(0x8E9A58)
            }
        }
        return px
    }

    /** Wet sand at the water's edge: darker and smoother, a sheen in streaks, shells and the odd pebble. */
    private fun wetSand(n: Int): IntArray {
        val px = shades(n, intArrayOf(c(0x9C8C66), c(0xA4946C), c(0xAC9C74), c(0xB4A47C)), 571, spread = .7f)
        val wob = TownGround.wrapNoise(n, 12, 572)
        for (y in 0 until n) for (x in 0 until n) {
            val v = y + wob(x, y) * 30f
            if (Math.floorMod(v.toInt(), 37) == 0 && (x / 5) % 3 != 0) px[y * n + x] = c(0xC8BC9A)
        }
        scatter(px, n, n * n / 1500, 573, intArrayOf(c(0xF0E8D8), c(0x7A6A52)))
        return px
    }

    /** Shades of [ramp] over [n] x [n] by three wrapped noises (slow, mid, fine), dithered a texel at a time at their edges. */
    private fun shades(n: Int, ramp: IntArray, seed: Int, slowCells: Int = 6, midCells: Int = 22, fineCells: Int = 96, spread: Float = 1f): IntArray {
        val slow = TownGround.wrapNoise(n, slowCells, seed)
        val mid = TownGround.wrapNoise(n, midCells, seed + 1)
        val fine = TownGround.wrapNoise(n, fineCells, seed + 2)
        val out = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val v = slow(x, y) * .55f + mid(x, y) * .3f + fine(x, y) * .15f
            val t = (((v - .3f) / .4f - .5f) * spread + .5f).coerceIn(0f, .999f) * (ramp.size - 1)
            val k = t.toInt()
            val d = (bayer[(y and 3) * 4 + (x and 3)] + .5f) / 16f
            out[y * n + x] = ramp[(if (t - k > d) k + 1 else k).coerceAtMost(ramp.size - 1)]
        }
        return out
    }

    private fun lum(p: Int): Int = ((p shr 16 and 255) * 3 + (p shr 8 and 255) * 4 + (p and 255)) / 8

    /** [src] recoloured into [ramp] by where each texel's lightness stands among the picture's own (as Swatches.tinted). */
    private fun recolour(src: IntArray, ramp: IntArray): IntArray {
        val hist = IntArray(256)
        for (p in src) hist[lum(p)]++
        val cut = IntArray(ramp.size - 1)
        var acc = 0; var k = 0
        for (v in 0 until 256) { acc += hist[v]; while (k < cut.size && acc >= src.size.toLong() * (k + 1) / ramp.size) { cut[k] = v; k++ } }
        return IntArray(src.size) { i -> val v = lum(src[i]); var r = 0; while (r < cut.size && v > cut[r]) r++; ramp[r] }
    }

    /** Dry grass: the town's grass's own texels (its tufts and specks) in straw and olive. */
    private fun dry(grass: PixImg): IntArray =
        recolour(grass.px, intArrayOf(c(0x7E7A42), c(0x948E4E), c(0xA59C58), c(0xB3A862), c(0xC2B66E), c(0xD2C682)))

    private fun scatter(px: IntArray, n: Int, count: Int, seed: Int, colours: IntArray, size: Int = 1) {
        val r = Paint.Rnd(seed)
        repeat(count) {
            val x = r.int(n); val y = r.int(n); val col = colours[r.int(colours.size)]
            for (dy in 0 until size) for (dx in 0 until size) if (size == 1 || r.next() < .8f) px[((y + dy) % n) * n + (x + dx) % n] = col
        }
    }

    private fun dirt(n: Int): IntArray {
        val px = shades(n, intArrayOf(c(0x8A6A48), c(0x957452), c(0xA07E5A), c(0xAA8862), c(0xB4926C)), 401)
        scatter(px, n, n * n / 90, 402, intArrayOf(c(0x6E5038), c(0x76583E)), 2)
        scatter(px, n, n * n / 160, 403, intArrayOf(c(0xC8B090), c(0xBCA282)), 2)
        scatter(px, n, n * n / 700, 404, intArrayOf(c(0x6E8E40), c(0x7A9A48)), 2)
        return px
    }

    /** Farmland: furrows along x (or z, [across]) about 0.8 m apart, the soil's ridges lit, a crop's clumps along them. */
    private fun farm(n: Int, across: Boolean): IntArray {
        val period = 20
        val soil = shades(n, intArrayOf(c(0x7E5236), c(0x86593A), c(0x8E6040), c(0x966846)), 411, spread = .7f)
        val px = IntArray(n * n)
        val r = Paint.Rnd(412)
        val wob = TownGround.wrapNoise(n, 32, 413)
        for (y in 0 until n) for (x in 0 until n) {
            val (a, b) = if (across) y to x else x to y
            val row = Math.floorMod(b + (wob(x, y) * 3f).toInt(), period)
            val s = soil[y * n + x]
            px[y * n + x] = when {
                row < 2 -> c(0x5E3C28)
                row < 4 -> c(0x6E4830)
                row in 9..11 -> c(0xA67852)
                else -> s
            }
        }
        // the crop: leafy clumps on the ridges
        val leaf = intArrayOf(c(0x4F8A34), c(0x5E9C3C), c(0x3E7A2C), c(0x6CA844))
        for (rowStart in 0 until n step period) {
            var a = r.int(6)
            while (a < n) {
                val w = 3 + r.int(3); val h = 3 + r.int(2)
                for (dy in 0 until h) for (dx in 0 until w) {
                    if (r.next() < .25f) continue
                    val aa = (a + dx) % n; val bb = (rowStart + 8 + dy - h / 2 + n) % n
                    val (x, y) = if (across) bb to aa else aa to bb
                    px[y * n + x] = if (dy == 0) leaf[3] else leaf[r.int(3)]
                }
                a += w + 2 + r.int(5)
            }
        }
        return px
    }

    private fun sand(n: Int): IntArray {
        val px = shades(n, intArrayOf(c(0xD0BE86), c(0xDCCB92), c(0xE4D49C), c(0xECDDA8), c(0xF2E6B6)), 421, spread = .8f)
        // wind ripples: wavy bands a few texels apart, a shade darker
        val wob = TownGround.wrapNoise(n, 16, 422)
        for (y in 0 until n) for (x in 0 until n) {
            val v = (y + x / 3 + wob(x, y) * 14f)
            if (Math.floorMod(v.toInt(), 11) == 0) px[y * n + x] = c(0xC8B47C)
        }
        scatter(px, n, n * n / 500, 423, intArrayOf(c(0xA89870), c(0xFFF6D8)))
        return px
    }

    /** Rock: blocky stones (cells about 26 texels, jittered), each its own grey, dark cracks between, lit along their tops. */
    private fun rock(n: Int): IntArray {
        val cell = 32
        val m = n / cell
        val r = Paint.Rnd(431)
        val jx = FloatArray(m * m) { r.next() * .7f + .15f }; val jy = FloatArray(m * m) { r.next() * .7f + .15f }
        val tone = IntArray(m * m) { r.int(4) }
        val greys = intArrayOf(c(0x7C766C), c(0x8A8478), c(0x958F84), c(0x6E695F))
        val px = IntArray(n * n)
        val grain = TownGround.wrapNoise(n, 128, 432)
        for (y in 0 until n) for (x in 0 until n) {
            val gx = x / cell; val gy = y / cell
            var best = Float.MAX_VALUE; var second = Float.MAX_VALUE; var bi = 0
            for (oy in -1..1) for (ox in -1..1) {
                val cx = Math.floorMod(gx + ox, m); val cy = Math.floorMod(gy + oy, m)
                val px0 = (gx + ox + jx[cy * m + cx]) * cell; val py0 = (gy + oy + jy[cy * m + cx]) * cell
                // (a squarish distance: stones with flat faces, not round blobs)
                val d = maxOf(abs(x - px0), abs(y - py0) * 1.4f) + (abs(x - px0) + abs(y - py0)) * .25f
                if (d < best) { second = best; best = d; bi = cy * m + cx } else if (d < second) second = d
            }
            val edge = second - best
            val base = greys[tone[bi]]
            px[y * n + x] = when {
                edge < 1.6f -> c(0x4E4A44)
                edge < 3.2f && y % 2 == 0 -> c(0x5E5952)
                else -> if (grain(x, y) > .62f) c(0xA8A296) else if (grain(x, y) < .3f) c(0x66615A) else base
            }
        }
        // the top edges of the stones catch the light: a texel under each crack's upper side
        for (y in 1 until n) for (x in 0 until n) if (px[(y - 1) * n + x] == c(0x4E4A44) && px[y * n + x] != c(0x4E4A44) && px[y * n + x] != c(0x5E5952)) px[y * n + x] = c(0xB4AEA2)
        return px
    }

    private fun snow(n: Int): IntArray {
        val px = shades(n, intArrayOf(c(0xD6E0EC), c(0xE2EAF4), c(0xECF2F8), c(0xF4F8FC)), 441, spread = .8f)
        scatter(px, n, n * n / 400, 442, intArrayOf(c(0xFFFFFF), c(0xC8D6E8)))
        return px
    }

    private fun gravel(n: Int): IntArray {
        val px = shades(n, intArrayOf(c(0x8A8070), c(0x988E7C), c(0xA39A86), c(0xB0A792)), 451, slowCells = 8, midCells = 40, fineCells = 160)
        scatter(px, n, n * n / 14, 452, intArrayOf(c(0x6E6656), c(0xB8AE98), c(0x8A8070), c(0xC4BAA4)), 2)
        return px
    }

    private fun forest(n: Int): IntArray {
        val px = shades(n, intArrayOf(c(0x34502E), c(0x3E5A34), c(0x4A6638), c(0x56703E)), 461)
        // leaf litter and needles, twigs, a little moss
        scatter(px, n, n * n / 40, 462, intArrayOf(c(0x6A5034), c(0x7A5C3A), c(0x56402E), c(0x8A6A40)), 2)
        val r = Paint.Rnd(463)
        repeat(n * n / 900) {
            val x = r.int(n); val y = r.int(n); val len = 4 + r.int(6); val dir = r.int(2)
            for (k in 0 until len) { val xx = (x + if (dir == 0) k else k / 3) % n; val yy = (y + if (dir == 1) k else k / 3) % n; px[yy * n + xx] = c(0x5A4430) }
        }
        scatter(px, n, n * n / 600, 464, intArrayOf(c(0x6E9A48), c(0x7AA850)), 3)
        return px
    }

    private fun water(n: Int): IntArray {
        val px = shades(n, intArrayOf(c(0x2E6290), c(0x346A98), c(0x3A70A0), c(0x4078A8)), 471, spread = .6f)
        // ripples: short light dashes across the flow, a dark one under each
        val r = Paint.Rnd(472)
        repeat(n * n / 260) {
            val x = r.int(n); val y = r.int(n); val len = 3 + r.int(6)
            for (k in 0 until len) { px[y * n + (x + k) % n] = if (k == 0 || k == len - 1) c(0x5A8CC0) else c(0x7FAFD8); px[((y + 1) % n) * n + (x + k) % n] = c(0x285A86) }
        }
        return px
    }

    private fun asphalt(n: Int, pic: (String) -> PixImg?): IntArray {
        val src = pic("asphalt/asphalt_24.png")
        if (src == null) return shades(n, intArrayOf(c(0x48444A), c(0x4E4A50), c(0x534F52), c(0x5A565A)), 481)
        return IntArray(n * n) { i -> src.px[(i / n % src.h) * src.w + (i % n) % src.w] or (0xFF shl 24) }
    }

    /** The mip chain of an ARGB picture ([n] a side, a power of two): each level the mean of four texels (sRGB bytes). */
    fun mips(px: IntArray, n: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        out += px
        var cur = px; var s = n
        while (s > 1) {
            val h = s / 2
            val nx = IntArray(h * h)
            for (y in 0 until h) for (x in 0 until h) {
                var a = 0; var r = 0; var g = 0; var b = 0
                for ((dx, dy) in arrayOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)) {
                    val p = cur[(y * 2 + dy) * s + x * 2 + dx]
                    a += p ushr 24; r += p shr 16 and 255; g += p shr 8 and 255; b += p and 255
                }
                nx[y * h + x] = ((a / 4) shl 24) or ((r / 4) shl 16) or ((g / 4) shl 8) or (b / 4)
            }
            out += nx; cur = nx; s = h
        }
        return out
    }

}
