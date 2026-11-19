package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.lifemax.rpg.feature.habits.domain.PropKind
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The yards' things made by code, the way [PropKit] makes the street props (no engine in here: [YardLayer] hands them to
 * Filament): every item of street.json's `graph.yards` (and the lanes' verge trees) becomes low-poly geometry wearing
 * pixel pictures painted texel by texel in the LimeZu palette at the buildings' density ([TEXEL] street units a texel),
 * sampled nearest, lit and shadowed by the sun.
 *
 *  - The ground (lawn, meadow, gravel) and the hard surfaces (driveways, paths, patios, pads, the pool's deck, the
 *    dropped kerb) are flat polygons wearing a seamless tile repeated across them (one draw a tile picture).
 *  - Edges (fences, gates, hedges, walls) are built along their rect's long side out of posts, rails, pickets, boards or
 *    a clipped leaf mass, repeating their pictures along the length.
 *  - Objects are built in their own frame (the rect's middle on the ground, x along, z out of the item's `face`) and
 *    sized by the rect; the shed's door, the garage's door, the bench's seat face the way the item faces.
 *
 * [bake] turns items into world-space meshes: one per kind (its pictures' atlases packed into one), and one per ground
 * tile, so a whole town's yards are a hundred draws or so.
 */

/** One yard item from street.json: [kind] in world rect ([x0], [z0])-([x1], [z1]), facing ([fx], [fz]), its [variant]. */
class YardItem(
    val kind: String, val x0: Float, val z0: Float, val x1: Float, val z1: Float,
    val fx: Float, val fz: Float, val variant: Int, val layer: String, val on: String?, val zone: String,
) {
    val cx get() = (x0 + x1) / 2f
    val cz get() = (z0 + z1) / 2f
    override fun toString() = "$kind/$variant [${x0.toInt()},${z0.toInt()} ${(x1 - x0).toInt()}x${(z1 - z0).toInt()}]"
}

/** One lot's yard: its parcel, the house's footprint, front and back rects (world, x0 z0 x1 z1), its items. */
class Yard(
    val lot: String, val sprite: String, val row: String, val neighbourhood: String, val style: String, val kind: String,
    val parcel: FloatArray, val house: FloatArray, val front: FloatArray?, val back: FloatArray, val pool: Boolean,
    val items: List<YardItem>,
)

/** Reads `graph.yards` and the lanes' items from a street.json v2. */
object YardData {
    private fun rect(a: JSONArray) = FloatArray(4) { a.getDouble(it).toFloat() }

    fun item(o: JSONObject): YardItem {
        val r = o.getJSONArray("rect"); val f = o.getJSONArray("face")
        return YardItem(
            o.getString("kind"), r.getDouble(0).toFloat(), r.getDouble(1).toFloat(), r.getDouble(2).toFloat(), r.getDouble(3).toFloat(),
            f.getDouble(0).toFloat(), f.getDouble(1).toFloat(), o.optInt("variant", 0), o.optString("layer", "object"),
            o.optString("on").takeIf { it.isNotEmpty() }, o.optString("zone", "back"),
        )
    }

    fun yards(streetJson: String): List<Yard> {
        val g = JSONObject(streetJson).optJSONObject("graph") ?: return emptyList()
        val ys = g.optJSONArray("yards") ?: return emptyList()
        return (0 until ys.length()).map { i ->
            val y = ys.getJSONObject(i)
            val items = y.getJSONArray("items")
            Yard(
                y.getString("lot"), y.optString("sprite"), y.optString("row"), y.optString("neighbourhood"), y.optString("style"), y.optString("kind"),
                rect(y.getJSONArray("parcel")), rect(y.getJSONArray("house")), y.optJSONArray("front")?.let { rect(it) }, rect(y.getJSONArray("back")),
                y.optJSONObject("household")?.optBoolean("pool") ?: false,
                (0 until items.length()).map { item(items.getJSONObject(it)) },
            )
        }
    }

    /** The lanes' and verges' own items (street trees). */
    fun laneItems(streetJson: String): List<YardItem> {
        val g = JSONObject(streetJson).optJSONObject("graph") ?: return emptyList()
        val ls = g.optJSONArray("lanes") ?: return emptyList()
        val out = ArrayList<YardItem>()
        for (i in 0 until ls.length()) {
            val its = ls.getJSONObject(i).optJSONArray("items") ?: continue
            for (k in 0 until its.length()) out += item(its.getJSONObject(k))
        }
        return out
    }
}

/** A world-space mesh ready for [YardLayer]: [tiled] repeats its one picture (ground); [water] shimmers. */
class YardBatch(val group: String, val mesh: PropMesh, val tiled: Boolean, val casts: Boolean, val water: Boolean)

// ---------------------------------------------------------------------------------------------------------- vectors
private fun v3(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
private fun vadd(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
private fun vsub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
private fun vmul(a: FloatArray, s: Float) = floatArrayOf(a[0] * s, a[1] * s, a[2] * s)
private fun vdot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
private fun vcross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
private fun vlen(a: FloatArray) = sqrt(vdot(a, a))
private fun vnrm(a: FloatArray): FloatArray { val l = vlen(a).coerceAtLeast(1e-9f); return floatArrayOf(a[0] / l, a[1] / l, a[2] / l) }
private val PIf = PI.toFloat()

/** The yards' own colours beside [Paint]'s. */
internal object YP {
    private fun c(rgb: Long) = Paint.c(rgb)
    /** The LimeZu grass tiles' own greens (public/assets/grass): outline, tuft shadow, tuft, base, lit tuft. */
    val GRASS = Ramp(c(0x34555D), c(0x39665A), c(0x37854E), c(0x479757), c(0x5BA864))
    val GRASS_DRY = Ramp(c(0x4A5A3E), c(0x5E7A44), c(0x6E8E4A), c(0x80A052), c(0x98B466))
    val GRASS_LUSH = Ramp(c(0x2E5048), c(0x2F7A48), c(0x3A8A50), c(0x4A9E5A), c(0x6CBA6A))
    val MEADOW = Ramp(c(0x3E5A3A), c(0x58843E), c(0x6E9C46), c(0x82AE50), c(0xA6C866))
    val SOIL = Ramp(c(0x2E2020), c(0x4A3428), c(0x5E4434), c(0x7A5A42), c(0x94704E))
    val SAND = Ramp(c(0x6B5A3A), c(0xC4A266), c(0xDCBE80), c(0xEAD49A), c(0xFAEABA))
    val STONE = Ramp(c(0x4D4A58), c(0x8A8478), c(0xA8A294), c(0xC0BAAC), c(0xD8D2C4))
    val SANDSTONE = Ramp(c(0x5A4A3A), c(0xB09A74), c(0xCAB48A), c(0xDCC8A0), c(0xEEDEBC))
    val SLATE = Ramp(c(0x24283A), c(0x3A4054), c(0x4A5068), c(0x5E6680), c(0x7A84A0))
    val WATER = Ramp(c(0x1A3A6E), c(0x2A6AB0), c(0x3A8ED8), c(0x5CB0EC), c(0xD2F2FF))
    val POND = Ramp(c(0x1A3440), c(0x24525A), c(0x2E6A6C), c(0x4A8A84), c(0xA8DCCC))
    val SAGE = Ramp(c(0x2E3E36), c(0x5E7A66), c(0x7A967E), c(0x94AE96), c(0xB8CEB8))
    val SKY = Ramp(c(0x24344A), c(0x4E6E92), c(0x6A8EB2), c(0x8AAACA), c(0xB6CCE2))
    val HONEY = Ramp(c(0x5A3A22), c(0xA8702E), c(0xC88E3E), c(0xDEAA56), c(0xF0C878))
    val TEAK = Ramp(c(0x3E3430), c(0x6E625A), c(0x8A7E74), c(0xA49A8E), c(0xC0B8AC))
    val RENDER = Ramp(c(0x4A4650), c(0xC2BCB0), c(0xDAD4C8), c(0xE8E4DA), c(0xF6F4EE))
    val FELT = Ramp(c(0x1A1A24), c(0x2A2A36), c(0x363644), c(0x464656), c(0x5A5A6E))
    val ROOFRED = Ramp(c(0x3A1A1A), c(0x7A3428), c(0x98442E), c(0xB45A3C), c(0xCF7A4E))
    val CANVAS = Ramp(c(0x3A3A50), c(0xC8C0B0), c(0xE0D8C8), c(0xEEE8DC), c(0xFFFFFF))
    val LEAF_DARK = Ramp(c(0x142E2A), c(0x1A4A3A), c(0x205E44), c(0x2E7A4E), c(0x48945A))
    val LEAF_YELLOW = Ramp(c(0x2A4A2A), c(0x4A7A2E), c(0x6A9A34), c(0x8CB43E), c(0xB4D05A))
    val LEAF_COPPER = Ramp(c(0x2A1A24), c(0x5A2A34), c(0x7A3A3E), c(0x9A5048), c(0xBC7058))
    val LEAF_BLUE = Ramp(c(0x16302E), c(0x1E4A46), c(0x2A6058), c(0x3A7A6C), c(0x5A9886))
    val PINE = Ramp(c(0x102622), c(0x163C34), c(0x1E5040), c(0x28664C), c(0x3A7E5A))
    val BIRCH = Ramp(c(0x3A3A48), c(0xB8B4AC), c(0xDAD6CC), c(0xEEEAE2), c(0xFFFFFF))
    val PURPLE = Ramp(c(0x2A1A3A), c(0x5A3A8A), c(0x7A52B0), c(0x9A72CA), c(0xC8A8E8))
    val ASPHALT = Ramp(c(0x24242E), c(0x3E3E4A), c(0x4C4C58), c(0x5A5A66), c(0x70707C))
    val CHROME = Paint.STEEL
    val BLOOMS = listOf(
        intArrayOf(c(0xE63F38), c(0xF2836B)), intArrayOf(c(0xF7D650), c(0xFFF59A)), intArrayOf(c(0xC870D8), c(0xE8A8F0)),
        intArrayOf(c(0xF0F0F8), c(0xFFFFFF)), intArrayOf(c(0xF48CB0), c(0xFFC0D8)), intArrayOf(c(0xEE6420), c(0xFFB066)),
        intArrayOf(c(0x5C7CE0), c(0x9AB4F4)),
    )
    val FRUIT = listOf(intArrayOf(c(0xCB2A2A), c(0xF2836B)), intArrayOf(c(0xEE8A20), c(0xFFC060)), intArrayOf(c(0xE8D040), c(0xFFF08A)), intArrayOf(c(0x7A2A5A), c(0xB05A8A)))
}

// ---------------------------------------------------------------------------------------------------------- pictures
/** The yards' pictures, each painted once (one object per key, so a mesh's atlas holds each once). */
internal object YardPics {
    private val cache = HashMap<String, PixImg>()
    fun pic(key: String, w: Int, h: Int, paint: (PixImg) -> Unit): PixImg = synchronized(cache) { cache.getOrPut(key) { PixImg(w, h).also(paint) } }

    fun swatch(c: Int) = pic("sw/$c", 1, 1) { it.fill(c) }

    /** One colour, big enough to cap a prism of radius [r] (a cap's picture is laid at [TEXEL] a texel). */
    fun disc(c: Int, r: Float): PixImg { val n = max(2, ceil(2f * r / TEXEL).toInt() + 2); return pic("disc/$c/$n", n, n) { it.fill(c) } }

    /** Sets a texel with wrap-around (seamless tiles). */
    private fun PixImg.wset(x: Int, y: Int, c: Int) { this[((x % w) + w) % w, ((y % h) + h) % h] = c }

    /**
     * The lawn: the LimeZu grass tile's flat green with its little tufts (a lit blade over a shadow) scattered seamlessly;
     * [v] 0 plain, 1 mown stripes, 2 lush with clover, 3 dry and patchy.
     */
    fun grass(v: Int): PixImg = pic("grass/$v", 64, 64) { im ->
        val r = when (v) { 2 -> YP.GRASS_LUSH; 3 -> YP.GRASS_DRY; else -> YP.GRASS }
        im.fill(r.l)
        if (v == 1) for (y in 0 until 64) for (x in 0 until 64) if ((x / 16) % 2 == 0) im[x, y] = Paint.c(0x4E9E5C)
        val rnd = Paint.Rnd(311 + v)
        repeat(if (v == 3) 26 else 34) {
            val x = rnd.int(64); val y = rnd.int(64)
            // a tuft: two blades in shadow tone with a lit tip
            im.wset(x, y, r.b); im.wset(x + 2, y, r.b); im.wset(x + 1, y + 1, r.b); im.wset(x, y + 1, r.d); im.wset(x + 2, y + 1, r.d)
            im.wset(x + 1, y + 2, r.d)
            if (rnd.next() < .5f) im.wset(x + 1, y - 1, r.h)
        }
        // a few LimeZu-style darker clumps
        repeat(5) {
            val x = rnd.int(64); val y = rnd.int(64)
            for (dy in 0..2) for (dx in -dy..dy) im.wset(x + dx, y + dy, r.b)
            im.wset(x - 2, y + 3, r.d); im.wset(x + 2, y + 3, r.d); im.wset(x, y - 1, r.h)
        }
        if (v == 2) repeat(14) { val x = rnd.int(64); val y = rnd.int(64); im.wset(x, y, Paint.c(0xF0F0F8)); im.wset(x + 1, y, Paint.c(0xF7D650)) }
        if (v == 3) repeat(10) { val x = rnd.int(64); val y = rnd.int(64); for (k in 0..3) im.wset(x + k, y + (k % 2), Paint.c(0x9A9A5A)) }
    }

    /** The meadow: longer, yellower grass, its blades in strokes, wildflowers ([v]: which colours). */
    fun meadow(v: Int): PixImg = pic("meadow/$v", 64, 64) { im ->
        val r = YP.MEADOW
        im.fill(r.b)
        val rnd = Paint.Rnd(411 + v)
        repeat(160) {
            val x = rnd.int(64); val y = rnd.int(64); val t = rnd.next()
            val c = if (t < .4f) r.d else if (t < .75f) r.l else r.h
            im.wset(x, y, c); im.wset(x, y + 1, c); if (t < .2f) im.wset(x, y + 2, r.o)
        }
        val fl = listOf(YP.BLOOMS[v % 7], YP.BLOOMS[(v + 3) % 7], YP.BLOOMS[3])
        repeat(22) { val x = rnd.int(64); val y = rnd.int(64); val b = fl[rnd.int(fl.size)]; im.wset(x, y, b[0]); im.wset(x, y - 1, b[1]) }
    }

    /** Gravel: chippings in three tones ([v] beige, grey, red, white). */
    fun gravel(v: Int): PixImg = pic("gravel/$v", 48, 48) { im ->
        val (b, d, l) = when (v % 4) {
            0 -> Triple(Paint.c(0xB8AE98), Paint.c(0x8E8676), Paint.c(0xD6CEB8))
            1 -> Triple(Paint.c(0x9C9CA8), Paint.c(0x74747E), Paint.c(0xBEBEC8))
            2 -> Triple(Paint.c(0xB08470), Paint.c(0x8A5E50), Paint.c(0xCCA08A))
            else -> Triple(Paint.c(0xD8D6D0), Paint.c(0xA8A6A0), Paint.c(0xF2F0EC))
        }
        im.fill(b)
        val rnd = Paint.Rnd(511 + v)
        repeat(420) { val x = rnd.int(48); val y = rnd.int(48); val t = rnd.next(); im.wset(x, y, if (t < .45f) d else l); if (t < .1f) im.wset(x + 1, y, d) }
    }

    /** Slabs: [cols] x [rows] slabs of [sw] x [sh] texels, joints in the ramp's dark tone, staggered rows if [stagger]. */
    private fun slabs(im: PixImg, r: Ramp, sw: Int, sh: Int, stagger: Boolean, seed: Int, speck: Float = 1f) {
        im.fill(r.b)
        val rnd = Paint.Rnd(seed)
        for (y in 0 until im.h) for (x in 0 until im.w) {
            val row = y / sh
            val xx = x + if (stagger && row % 2 == 1) sw / 2 else 0
            val jx = xx % sw == 0; val jy = y % sh == 0
            if (jx || jy) im[x, y] = r.d
            else if (xx % sw == 1 || y % sh == 1) im[x, y] = r.l
        }
        // each slab a shade of its own
        for (y in 0 until im.h step sh) for (x in 0 until im.w step sw) {
            val t = rnd.next()
            if (t < .3f) for (yy in y + 2 until min(im.h, y + sh)) for (xx in x + 2 until min(im.w, x + sw)) {
                val sx = xx - if (stagger && (y / sh) % 2 == 1) sw / 2 else 0
                if (im[((sx % im.w) + im.w) % im.w, yy] == r.b) im[((sx % im.w) + im.w) % im.w, yy] = if (t < .15f) r.h else r.l
            }
        }
        repeat((im.w * im.h / 40f * speck).toInt()) { val x = rnd.int(im.w); val y = rnd.int(im.h); if (im[x, y] == r.b) im[x, y] = r.d }
    }

    /** Bricks: [bw] x [bh] texels, stretcher bond, mortar between. */
    private fun bricks(im: PixImg, brick: Ramp, mortar: Int, bw: Int, bh: Int, seed: Int) {
        val rnd = Paint.Rnd(seed)
        for (y in 0 until im.h) for (x in 0 until im.w) {
            val row = y / bh
            val xx = x + if (row % 2 == 1) bw / 2 else 0
            im[x, y] = if (y % bh == bh - 1 || xx % bw == bw - 1) mortar else if (y % bh == 0) brick.l else brick.b
        }
        for (y in 0 until im.h step bh) for (x in -bw until im.w step bw) {
            val t = rnd.next(); if (t > .35f) continue
            val c = if (t < .18f) brick.d else brick.h
            val off = if ((y / bh) % 2 == 1) -bw / 2 else 0
            for (xx in x + off + 1 until x + off + bw - 1) for (yy in y + 1 until y + bh - 1) if (xx in 0 until im.w && yy < im.h) im[xx, yy] = c
        }
    }

    /** A hard surface's tile by its key (the kind and its look). */
    fun surface(key: String): PixImg = when (key) {
        "asphalt" -> pic(key, 48, 48) { im ->
            val r = YP.ASPHALT; im.fill(r.b); val rnd = Paint.Rnd(601)
            repeat(500) { val x = rnd.int(48); val y = rnd.int(48); im[x, y] = if (rnd.next() < .6f) r.d else r.l }
            repeat(6) { val x = rnd.int(48); val y = rnd.int(48); im[x, y] = r.h }
        }
        "concrete" -> pic(key, 64, 64) { slabs(it, Paint.CONCRETE, 64, 32, false, 602, .6f) }
        "blocks" -> pic(key, 48, 48) { im ->
            // block paving: small red-grey blocks in herringbone pairs
            val a = Ramp(Paint.c(0x4A2A2A), Paint.c(0x7A4A44), Paint.c(0x94605A), Paint.c(0xAA766C), Paint.c(0xC09086))
            val rnd = Paint.Rnd(603)
            for (y in 0 until 48) for (x in 0 until 48) {
                val cell = ((x / 4) + (y / 8)) % 2
                val bx = if (cell == 0) x % 8 else x % 4; val by = if (cell == 0) y % 4 else y % 8
                im[x, y] = if (bx == 0 || by == 0) a.d else if (bx == 1 || by == 1) a.l else a.b
            }
            repeat(30) { val x = rnd.int(48); val y = rnd.int(48); if (im[x, y] == a.b) im[x, y] = Paint.c(0x8A8890) }
        }
        "slabs" -> pic(key, 48, 48) { slabs(it, Paint.CONCRETE, 16, 16, false, 604) }
        "brickpath" -> pic(key, 48, 48) { bricks(it, Paint.TERRACOTTA, Paint.c(0x8E7A6A), 12, 5, 605) }
        "sandstone" -> pic(key, 60, 60) { im ->
            // crazy-ish sandstone flags: slabs of two sizes
            slabs(im, YP.SANDSTONE, 20, 15, true, 606)
        }
        "greyslabs" -> pic(key, 48, 48) { slabs(it, YP.STONE, 24, 24, true, 607) }
        "tiles" -> pic(key, 40, 40) { slabs(it, Paint.TERRACOTTA, 10, 10, false, 608, .3f) }
        "travertine" -> pic(key, 60, 60) { slabs(it, Ramp(Paint.c(0x6A6458), Paint.c(0xCFC6B4), Paint.c(0xE6DECE), Paint.c(0xF0EADE), Paint.c(0xFAF6EE)), 30, 20, true, 609, .4f) }
        "grasscrete" -> pic(key, 40, 40) { im ->
            slabs(im, Paint.CONCRETE, 20, 20, false, 610, .2f)
            for (y in 0 until 40) for (x in 0 until 40) if ((x % 10) in 3..7 && (y % 10) in 3..7) im[x, y] = if ((x + y) % 3 == 0) YP.GRASS.b else YP.GRASS.l
        }
        "kerb" -> pic(key, 32, 32) { im ->
            slabs(im, Paint.CONCRETE, 32, 32, false, 611, .3f)
            // the ramp's grooves across it
            for (y in 4 until 30 step 4) im.hline(2, 29, y, Paint.CONCRETE.d)
        }
        "planks" -> planks(YP.HONEY, 0)
        else -> when {
            key.startsWith("planks/") -> { val v = key.removePrefix("planks/").toInt(); planks(deckRamp(v), v) }
            key.startsWith("gravel/") -> gravel(key.removePrefix("gravel/").toInt())
            key.startsWith("grass/") -> grass(key.removePrefix("grass/").toInt())
            key.startsWith("meadow/") -> meadow(key.removePrefix("meadow/").toInt())
            key == "water" -> water(false)
            key == "pondwater" -> water(true)
            key == "sand" -> pic(key, 32, 32) { im -> im.fill(YP.SAND.b); val rnd = Paint.Rnd(612); repeat(160) { im[rnd.int(32), rnd.int(32)] = if (rnd.next() < .5f) YP.SAND.d else YP.SAND.l } }
            key == "soil" -> pic(key, 32, 32) { im -> im.fill(YP.SOIL.b); val rnd = Paint.Rnd(613); repeat(220) { im[rnd.int(32), rnd.int(32)] = if (rnd.next() < .5f) YP.SOIL.d else YP.SOIL.l } }
            key == "bark" -> pic(key, 32, 32) { im -> im.fill(Paint.c(0x6A4A36)); val rnd = Paint.Rnd(614); repeat(120) { val x = rnd.int(32); val y = rnd.int(32); im.hline(x, x + 2, y, if (rnd.next() < .5f) Paint.c(0x4A3428) else Paint.c(0x8A6448)) } }
            else -> error("no surface $key")
        }
    }

    fun deckRamp(v: Int) = when (v % 4) { 0 -> Paint.WOOD; 1 -> Paint.BROWN; 2 -> YP.TEAK; else -> YP.HONEY }

    /** Deck boards along the picture's x, their joints and nail heads. */
    fun planks(r: Ramp, v: Int): PixImg = pic("planks/${r.b}/$v", 48, 30) { im ->
        val rnd = Paint.Rnd(620 + v)
        for (y in 0 until 30) for (x in 0 until 48) {
            val b = y / 6; val yy = y % 6
            val jx = (x + b * 17) % 48 == 0
            im[x, y] = if (yy == 5 || jx) r.o else if (yy == 0) r.l else if ((b + v) % 3 == 0) r.b else if ((b + v) % 3 == 1) r.l else r.b
        }
        repeat(40) { val x = rnd.int(46); val y = rnd.int(30); if (y % 6 in 1..4) im.hline(x, x + 2, y, r.d) }
        for (y in 2 until 30 step 6) for (x in 3 until 48 step 24) im[(x + y * 5) % 48, y] = r.o
    }

    /** Water: deep blue, ripples of lit lines (a pool) or green-brown with flecks (a pond); the layer scrolls it. */
    fun water(pond: Boolean): PixImg = pic(if (pond) "pondwater" else "water", 48, 48) { im ->
        val r = if (pond) YP.POND else YP.WATER
        im.fill(r.b)
        val rnd = Paint.Rnd(if (pond) 701 else 702)
        for (y in 0 until 48) for (x in 0 until 48) if (((x + 2 * y) / 6 + (y / 7)) % 5 == 0 && rnd.next() < .5f) im[x, y] = r.d
        repeat(if (pond) 18 else 26) {
            val x = rnd.int(48); val y = rnd.int(48); val l = 2 + rnd.int(4)
            for (k in 0 until l) im.wset(x + k, y + if (k == 0 || k == l - 1) 1 else 0, r.l)
            if (rnd.next() < .5f) im.wset(x + l / 2, y - 1, r.h)
        }
        if (pond) repeat(12) { im.wset(rnd.int(48), rnd.int(48), r.o) }
    }

    /**
     * Foliage: clusters of leaves (each a lit arc over a shadow) packed over the dark tone, [tone] -1 shade, 0, 1 lit;
     * blossom or fruit dotted in.
     */
    fun leaves(r: Ramp, tone: Int, seed: Int, dots: IntArray? = null, dotRate: Float = .02f, fine: Boolean = false): PixImg =
        pic("leaf/${r.b}/$tone/$seed/${dots?.get(0)}/$dotRate/$fine", 40, 40) { im ->
            val base = when (tone) { 1 -> r.l; -1 -> r.d; else -> r.b }
            val hi = when (tone) { 1 -> r.h; -1 -> r.b; else -> r.l }
            val lo = when (tone) { 1 -> r.b; -1 -> r.o; else -> r.d }
            im.fill(lo)
            val rnd = Paint.Rnd(seed)
            val rad = if (fine) 1.6f else 2.6f
            repeat(if (fine) 260 else 120) {
                val x = rnd.int(40); val y = rnd.int(40)
                val ri = rad.toInt() + 1
                for (dy in -ri..ri) for (dx in -ri..ri) {
                    if (dx * dx + dy * dy > rad * rad) continue
                    im.wset(x + dx, y + dy, if (dx + dy <= -ri + 1 && dy < 0) hi else base)
                }
            }
            repeat(if (fine) 20 else 40) { im.wset(rnd.int(40), rnd.int(40), lo) }
            if (dots != null) repeat((1600 * dotRate).toInt()) {
                val x = rnd.int(40); val y = rnd.int(40)
                im.wset(x, y, dots[0]); im.wset(x + 1, y, dots[0]); im.wset(x, y - 1, dots[1])
            }
        }

    /** Needles for conifers: short dark strokes down the picture. */
    fun needles(r: Ramp, tone: Int, seed: Int): PixImg = pic("needle/${r.b}/$tone/$seed", 40, 40) { im ->
        im.fill(if (tone > 0) r.b else r.d)
        val rnd = Paint.Rnd(seed)
        repeat(260) {
            val x = rnd.int(40); val y = rnd.int(40)
            val c = when (rnd.int(4)) { 0 -> r.o; 1 -> if (tone > 0) r.h else r.b; else -> if (tone > 0) r.l else r.b }
            im.wset(x, y, c); im.wset(x + 1, y + 1, c)
        }
    }

    /** Bark: vertical streaks; birch: white with black marks. */
    fun bark(birch: Boolean): PixImg = pic("bark/$birch", 16, 24) { im ->
        val r = if (birch) YP.BIRCH else Paint.BARK
        im.fill(r.b)
        val rnd = Paint.Rnd(if (birch) 801 else 802)
        if (birch) repeat(14) { val x = rnd.int(16); val y = rnd.int(24); im.hline(x, x + 2 + rnd.int(3), y, Paint.c(0x2E2E3A)) }
        else {
            for (x in 0 until 16 step 3) for (y in 0 until 24) if (rnd.next() < .7f) im[x, y] = r.d
            repeat(12) { im[rnd.int(16), rnd.int(24)] = r.l }
        }
    }

    /** Vertical boards (a close-board fence, a shed's walls): [bw] texels each, a gap of the outline between. */
    fun boards(r: Ramp, bw: Int, h: Int, seed: Int, rails: Boolean = false): PixImg = pic("boards/${r.b}/$bw/$h/$seed/$rails", bw * 6, h) { im ->
        val rnd = Paint.Rnd(seed)
        for (y in 0 until h) for (x in 0 until im.w) {
            val k = x % bw
            im[x, y] = if (k == bw - 1) r.d else if (k == 0) r.l else r.b
        }
        for (b in 0 until 6) { val t = rnd.next(); if (t < .35f) for (y in 0 until h) for (x in b * bw + 1 until b * bw + bw - 1) if (im[x, y] == r.b) im[x, y] = if (t < .17f) r.l else r.d }
        repeat(im.w * h / 30) { val x = rnd.int(im.w); val y = rnd.int(h); if (im[x, y] == r.b) im.vline(x, y, min(h - 1, y + 2), r.d) }
        im.hline(0, im.w - 1, h - 1, r.o)
        if (rails) { im.hline(0, im.w - 1, 3, r.d); im.hline(0, im.w - 1, h - 5, r.d) }
    }

    /** Lap panels: thin horizontal slats woven (a garden fence panel). */
    fun lap(r: Ramp, h: Int): PixImg = pic("lap/${r.b}/$h", 40, h) { im ->
        for (y in 0 until h) for (x in 0 until 40) {
            val s = y % 3; val k = (x / 10 + y / 3) % 2
            im[x, y] = if (s == 2) r.d else if (s == 0 && k == 0) r.l else r.b
        }
        im.hline(0, 39, 0, r.l); im.hline(0, 39, h - 1, r.o)
    }

    /** Chain-link: a diamond mesh over nothing (cut out) in galvanised grey. */
    fun chain(h: Int): PixImg = pic("chain/$h", 16, h) { im ->
        val c = Paint.STEEL.l; val d = Paint.STEEL.d
        for (y in 0 until h) for (x in 0 until 16) im[x, y] = 0
        for (y in 0 until h) for (x in 0 until 16) if ((x + y) % 4 == 0 || (x - y + 64) % 4 == 0) im[x, y] = if ((x + y) % 8 == 0) d else c
        im.hline(0, 15, 0, Paint.STEEL.b); im.hline(0, 15, h - 1, Paint.STEEL.b)
    }

    /** A picket: pointed top cut out, a lit left edge, the outline down its right. */
    fun picket(r: Ramp, h: Int): PixImg = pic("picket/${r.b}/$h", 3, h) { im ->
        for (y in 0 until h) { im[0, y] = r.l; im[1, y] = r.b; im[2, y] = r.d }
        im[0, 0] = 0; im[2, 0] = 0; im[1, 0] = r.h
        im.hline(0, 2, h - 1, r.o)
    }

    /** Stone courses (a low wall): random-width stones, mortar between. */
    fun stones(r: Ramp, mortar: Int, seed: Int): PixImg = pic("stones/${r.b}/$seed", 40, 16) { im ->
        val rnd = Paint.Rnd(seed)
        im.fill(mortar)
        var y = 0
        while (y < 16) {
            val sh = 3 + rnd.int(3)
            var x = -rnd.int(6)
            while (x < 40) {
                val sw = 4 + rnd.int(6)
                val t = rnd.next(); val c = if (t < .25f) r.d else if (t < .6f) r.b else if (t < .85f) r.l else r.h
                for (yy in y until min(16, y + sh - 1)) for (xx in max(0, x) until min(40, x + sw - 1)) im[xx, yy] = c
                if (y < 16) for (xx in max(0, x) until min(40, x + sw - 1)) im[xx, y] = if (c == r.d) r.b else r.h
                x += sw
            }
            y += sh
        }
    }

    /** Bricks for walls. */
    fun brickWall(r: Ramp, seed: Int): PixImg = pic("brickwall/${r.b}/$seed", 48, 24) { bricks(it, r, Paint.c(0xCFBFAE), 8, 4, seed) }

    /** Render: a plain painted wall with a few marks. */
    fun render(r: Ramp, seed: Int): PixImg = pic("render/${r.b}/$seed", 32, 32) { im -> im.fill(r.b); val rnd = Paint.Rnd(seed); repeat(40) { im[rnd.int(32), rnd.int(32)] = if (rnd.next() < .6f) r.d else r.l } }

    /** Roofing felt or shingles. */
    fun shingles(r: Ramp, seed: Int): PixImg = pic("shingle/${r.b}/$seed", 32, 24) { im ->
        val rnd = Paint.Rnd(seed)
        for (y in 0 until 24) for (x in 0 until 32) {
            val row = y / 4; val xx = x + if (row % 2 == 1) 4 else 0
            im[x, y] = if (y % 4 == 3) r.o else if (xx % 8 == 0) r.d else if (y % 4 == 0) r.l else r.b
        }
        repeat(20) { val x = rnd.int(32); val y = rnd.int(24); if (y % 4 in 1..2) im[x, y] = r.h }
    }

    /** Corrugated sheet (a carport's or bike shed's roof): ridges along the picture's y. */
    fun corrugated(r: Ramp): PixImg = pic("corr/${r.b}", 24, 24) { im ->
        for (y in 0 until 24) for (x in 0 until 24) im[x, y] = when (x % 4) { 0 -> r.h; 1 -> r.l; 2 -> r.b; else -> r.d }
    }

    /** Fabric stripes ([a], [b] alternating every [s] texels across x). */
    fun stripes(a: Int, b: Int, s: Int, w: Int = 24, h: Int = 24): PixImg = pic("stripe/$a/$b/$s/$w/$h", w, h) { im ->
        for (y in 0 until h) for (x in 0 until w) im[x, y] = if ((x / s) % 2 == 0) a else b
    }
}

// ---------------------------------------------------------------------------------------------------------- geometry
/**
 * A face from its corners in order (convex), its picture laid from its own top-left at [TEXEL] a texel along its
 * horizontal-ish edge, shifted by ([du], [dv]) texels; the normal points away from [inside] when given.
 */
private fun MeshBuilder.face(pts: List<FloatArray>, img: PixImg, inside: FloatArray? = null, du: Float = 0f, dv: Float = 0f, fit: Boolean = false) {
    if (pts.size < 3) return
    // Newell's normal
    var nx = 0f; var ny = 0f; var nz = 0f
    for (i in pts.indices) {
        val a = pts[i]; val b = pts[(i + 1) % pts.size]
        nx += (a[1] - b[1]) * (a[2] + b[2]); ny += (a[2] - b[2]) * (a[0] + b[0]); nz += (a[0] - b[0]) * (a[1] + b[1])
    }
    var n = floatArrayOf(nx, ny, nz)
    if (vlen(n) < 1e-7f) return
    n = vnrm(n)
    if (inside != null) {
        val c = pts.fold(FloatArray(3)) { s, p -> vadd(s, p) }.let { vmul(it, 1f / pts.size) }
        if (vdot(n, vsub(c, inside)) < 0f) n = vmul(n, -1f)
    }
    // the picture's right: horizontal in the face (or x for a flat one), its down: n x right
    val up = floatArrayOf(0f, 1f, 0f)
    var right = if (abs(n[1]) > .95f) floatArrayOf(1f, 0f, 0f) else vnrm(vcross(up, n))
    if (abs(n[1]) > .95f && n[1] < 0f) right = floatArrayOf(-1f, 0f, 0f)
    val down = vnrm(vcross(n, right)).let { if (abs(n[1]) <= .95f && it[1] > 0f) vmul(it, -1f) else it }
    val us = pts.map { vdot(it, right) / TEXEL }; val vs = pts.map { vdot(it, down) / TEXEL }
    val u0 = us.min(); val v0 = vs.min()
    var su = 1f; var sv = 1f
    val spanU = us.max() - u0; val spanV = vs.max() - v0
    if (fit || spanU > img.w) su = img.w / spanU.coerceAtLeast(1e-4f)
    if (fit || spanV > img.h) sv = img.h / spanV.coerceAtLeast(1e-4f)
    val ou = if (fit) 0f else min(du, img.w - spanU * su).coerceAtLeast(0f)
    val ov = if (fit) 0f else min(dv, img.h - spanV * sv).coerceAtLeast(0f)
    val p = FloatArray(pts.size * 3); val uv = FloatArray(pts.size * 2)
    for ((i, q) in pts.withIndex()) {
        q.copyInto(p, i * 3)
        uv[i * 2] = ou + (us[i] - u0) * su; uv[i * 2 + 1] = ov + (vs[i] - v0) * sv
    }
    poly(p, uv, img, n, right)
}

/**
 * A parallelogram [a] top-left, [b] top-right, [d] bottom-left wearing [img] repeated at [TEXEL] a texel (whole tiles
 * from the bottom up and from the left).
 */
private fun MeshBuilder.tq(a: FloatArray, b: FloatArray, d: FloatArray, img: PixImg) {
    val eu = vsub(b, a); val ev = vsub(d, a)
    val lu = vlen(eu); val lv = vlen(ev)
    if (lu < 1e-3f || lv < 1e-3f) return
    val tw = img.w * TEXEL; val th = img.h * TEXEL
    val rows = max(1, ceil(lv / th - 1e-3f).toInt())
    val first = lv - (rows - 1) * th
    var u = 0f
    while (u < lu - 1e-3f) {
        val u2 = min(lu, u + tw)
        var t = 0f
        for (r in 0 until rows) {
            val t2 = if (r == 0) first else t + th
            val vTop = if (r == 0) img.h - first / TEXEL else 0f
            fun p(uu: Float, tt: Float) = vadd(a, vadd(vmul(eu, uu / lu), vmul(ev, tt / lv)))
            quad(p(u, t), p(u2, t), p(u2, t2), p(u, t2), img, 0f, vTop.coerceAtLeast(0f), (u2 - u) / TEXEL, vTop.coerceAtLeast(0f) + (t2 - t) / TEXEL)
            t = t2
        }
        u = u2
    }
}

/** A box with repeating pictures: [side] round it (or [front], [back], [ends]), [top] on it; no bottom. */
private fun MeshBuilder.tbox(
    x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, side: PixImg?, top: PixImg?,
    front: PixImg? = side, back: PixImg? = side, ends: PixImg? = side,
) {
    front?.let { tq(v3(x0, y1, z1), v3(x1, y1, z1), v3(x0, y0, z1), it) }
    back?.let { tq(v3(x1, y1, z0), v3(x0, y1, z0), v3(x1, y0, z0), it) }
    ends?.let { tq(v3(x1, y1, z1), v3(x1, y1, z0), v3(x1, y0, z1), it); tq(v3(x0, y1, z0), v3(x0, y1, z1), v3(x0, y0, z0), it) }
    top?.let { tq(v3(x0, y1, z0), v3(x1, y1, z0), v3(x0, y1, z1), it) }
}

/** A box in one flat colour. */
private fun MeshBuilder.sbox(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, c: Int) {
    val s = YardPics.swatch(c)
    box(x0, y0, z0, x1, y1, z1) { side, _, _ -> if (side == MeshBuilder.Side.BOTTOM) null else s }
}

/** A box in a ramp's panels (outlined, lit top lip), its top in the light tone. */
private fun MeshBuilder.rbox(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r: Ramp, seed: Int, edges: Boolean = true) =
    panelBox(x0, y0, z0, x1, y1, z1, r, seed, edges)

/**
 * A faceted ellipsoid round ([cx], [cy], [cz]): [n] sides, [rings] bands top to bottom; each band wears the picture
 * for its height ([pics]: lit at the top, shaded underneath), each facet a different part of it. [squash] flattens
 * its bottom (a crown's underside), [cutBelow] stops it above that height (resting on the ground).
 */
private fun MeshBuilder.ball(cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float, n: Int, rings: Int, pics: List<PixImg>, seed: Int, twist: Float = 0f) {
    val c = v3(cx, cy, cz)
    val rnd = Paint.Rnd(seed)
    fun p(i: Int, j: Int): FloatArray {
        val phi = PIf * i / rings
        val th = 2f * PIf * j / n + twist + if (i % 2 == 1) PIf / n else 0f
        return v3(cx + rx * sin(phi) * sin(th), cy + ry * cos(phi), cz + rz * sin(phi) * cos(th))
    }
    for (i in 0 until rings) {
        val img = pics[(i * pics.size / rings).coerceIn(0, pics.size - 1)]
        for (j in 0 until n) {
            val pts = when (i) {
                0 -> listOf(p(0, 0), p(1, j + 1), p(1, j))
                rings - 1 -> listOf(p(i, j), p(i, j + 1), p(rings, 0))
                else -> listOf(p(i, j), p(i, j + 1), p(i + 1, j + 1), p(i + 1, j))
            }
            face(pts, img, c, rnd.next() * 30f, rnd.next() * 30f)
        }
    }
}

/** A gable roof over x0..x1 (ridge along x) from eaves at [ye] (its edges [z0], [z1]) up to the ridge at [yr]. */
private fun MeshBuilder.gable(x0: Float, x1: Float, z0: Float, z1: Float, ye: Float, yr: Float, roof: PixImg, gableEnd: PixImg?, thick: Float = .8f, under: Int? = null) {
    val zm = (z0 + z1) / 2f
    // the two slopes (repeating tiles), each with its edge
    tq(v3(x0, yr, zm), v3(x1, yr, zm), v3(x0, ye, z1), roof)
    tq(v3(x1, yr, zm), v3(x0, yr, zm), v3(x1, ye, z0), roof)
    val e = YardPics.swatch(under ?: Paint.OUT)
    // the fascia boards along the eaves
    quad(v3(x0, ye, z1), v3(x1, ye, z1), v3(x1, ye - thick, z1), v3(x0, ye - thick, z1), e)
    quad(v3(x1, ye, z0), v3(x0, ye, z0), v3(x0, ye - thick, z0), v3(x1, ye - thick, z0), e)
    // the gable ends (triangles) and the roof's edge over them
    if (gableEnd != null) for (x in listOf(x0, x1)) face(listOf(v3(x, ye, z0), v3(x, yr, zm), v3(x, ye, z1)), gableEnd, v3((x0 + x1) / 2f, ye, zm))
    for (x in listOf(x0, x1)) {
        face(listOf(v3(x, ye - thick, z0), v3(x, ye, z0), v3(x, yr, zm), v3(x, yr - thick, zm)), e, v3((x0 + x1) / 2f, ye, zm))
        face(listOf(v3(x, ye - thick, z1), v3(x, ye, z1), v3(x, yr, zm), v3(x, yr - thick, zm)), e, v3((x0 + x1) / 2f, ye, zm))
    }
}

// ---------------------------------------------------------------------------------------------------------- models
/** A flat polygon of an item in its own frame: ([pts] x, z pairs), at [y], its tile [surface], laid from ([ox], [oz]) along ([ux], [uz]). */
internal class LocalFlat(val surface: String, val pts: FloatArray, val y: Float, val ox: Float, val oz: Float, val ux: Float, val uz: Float)

/** Another kit's mesh placed in an item's frame at ([x], [z]), its x along ([ax], [az]), its z out along ([ox], [oz]). */
internal class Sub(val mesh: PropMesh, val x: Float, val z: Float, val ax: Float, val az: Float, val ox: Float, val oz: Float, val casts: Boolean = true)

/** An item's model in its own frame: its mesh (if any), its flat parts and other kits' meshes. */
internal class YardModel(val mesh: PropMesh?, val flats: List<LocalFlat>, val subs: List<Sub>)

/** Where an item stands: its middle, its x along ([ax], [az]), z out ([ox], [oz]), its size [w] along x and [d] along z. */
internal class Frame(val cx: Float, val cz: Float, val ax: Float, val az: Float, val ox: Float, val oz: Float, val w: Float, val d: Float) {
    fun wx(lx: Float, lz: Float) = cx + ax * lx + ox * lz
    fun wz(lx: Float, lz: Float) = cz + az * lx + oz * lz
}

/** The builder an item's model is made in. */
internal class Out(val w: Float, val d: Float) {
    val m = MeshBuilder()
    val flats = ArrayList<LocalFlat>()
    val subs = ArrayList<Sub>()
    var any = false

    /** A flat rectangle x0..x1, z0..z1 at [y] in [surface], its tiles from its corner along x (or z if [alongZ]). */
    fun flat(surface: String, x0: Float, z0: Float, x1: Float, z1: Float, y: Float, alongZ: Boolean = false) {
        flats += LocalFlat(surface, floatArrayOf(x0, z0, x1, z0, x1, z1, x0, z1), y, x0, z0, if (alongZ) 0f else 1f, if (alongZ) 1f else 0f)
    }

    fun poly(surface: String, pts: FloatArray, y: Float) { flats += LocalFlat(surface, pts, y, pts[0], pts[1], 1f, 0f) }
}

object YardKit {
    /** Ground that lies under the rest (tiled). */
    val GROUND = setOf("lawn", "meadow", "gravel")
    /** Hard surfaces (tiled, a little over the ground). */
    val SURFACES = setOf("driveway", "kerbCut", "frontPath", "patio", "parkingPad", "poolDeck", "deck")
    /** Edges built along their rect's long side. */
    val EDGES = setOf("frontFence", "gate", "sideFence", "backFence", "sideGate", "postRail", "hedge", "lowWall")
    /** Things standing in the yard. */
    val OBJECTS = setOf(
        "bench", "bikeShed", "bins", "birdbath", "bush", "carport", "chair", "compost", "doghouse", "firePit", "flowerBed",
        "fruitTree", "garage", "gazebo", "greenhouse", "grill", "hammock", "logPile", "mailbox", "pallets", "planter",
        "playSet", "pond", "pool", "sandpit", "shed", "swing", "table", "trampoline", "tree", "vegBed", "washingLine",
    )
    /** Every kind the kit builds. */
    val KINDS: Set<String> = GROUND + SURFACES + EDGES + OBJECTS

    /** Kinds whose model has no front: turned so their long side runs along x. */
    private val LONG = setOf("washingLine", "logPile", "hammock", "vegBed", "flowerBed", "pallets", "playSet", "swing", "pool", "pond", "poolDeck", "deck", "patio", "parkingPad", "driveway", "frontPath", "kerbCut")

    /** Kinds that throw shadows. */
    private val FLATS_ONLY = GROUND + setOf("driveway", "kerbCut", "parkingPad")

    /** Where [it] stands and how it is turned. */
    internal fun frame(it: YardItem): Frame {
        val rw = it.x1 - it.x0; val rd = it.z1 - it.z0
        var fx = it.fx; var fz = it.fz
        val fl = sqrt(fx * fx + fz * fz)
        if (fl < 1e-4f) { fx = 0f; fz = 1f } else { fx /= fl; fz /= fl }
        if (it.kind in EDGES || it.kind in GROUND) {
            // an edge's out is across its long side, the way it faces
            if (rw >= rd) { fz = if (it.fz < 0f) -1f else 1f; fx = 0f } else { fx = if (it.fx < 0f) -1f else 1f; fz = 0f }
        }
        var ax = -fz; var az = fx
        var w: Float; var d: Float
        when {
            abs(ax) > .9f -> { w = rw; d = rd }
            abs(az) > .9f -> { w = rd; d = rw }
            else -> { w = min(rw, rd) * .7f; d = w }
        }
        if (it.kind in LONG && d > w * 1.15f) {
            // turned a quarter: the old along is the new out
            val nox = ax; val noz = az
            ax = -noz; az = nox; fx = nox; fz = noz
            val t = w; w = d; d = t
        }
        return Frame(it.cx, it.cz, ax, az, fx, fz, w, d)
    }

    /**
     * The models made, by kind, look and size, the least recently used let go past [CACHE_BYTES] (each size of a fence or
     * a shed is its own mesh with its own atlas: kept all, the town's came to some 60 MB of the phone's Java heap for good).
     * A model is a pure function of its key, so one made again is the same.
     */
    private val cache = object : LinkedHashMap<String, YardModel>(256, .75f, true) {
        var bytes = 0L
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, YardModel>?): Boolean {
            if (bytes <= CACHE_BYTES || eldest == null) return false
            bytes -= sizeOf(eldest.value)
            return true
        }
    }
    private fun sizeOf(m: YardModel): Long {
        val me = m.mesh ?: return 64L
        return (me.pos.size + me.uv.size + me.nrm.size + me.tan.size + me.idx.size + me.tex.size + (me.mask?.size ?: 0)) * 4L +
            m.subs.sumOf { s -> (s.mesh.pos.size + s.mesh.uv.size + s.mesh.nrm.size + s.mesh.tan.size + s.mesh.idx.size + s.mesh.tex.size) * 4L }
    }
    private const val CACHE_BYTES = 16L shl 20

    /** Lets every model made go (the town's yards are baked: the next town makes its own as it needs them). */
    fun trim() = synchronized(cache) { cache.clear(); cache.bytes = 0L }

    /** [kind]'s model in [variant] for a [w] x [d] rect (its own frame), made once per size. */
    internal fun model(kind: String, variant: Int, w: Float, d: Float, zone: String = "back"): YardModel {
        val rw = (w * 2f).roundToInt() / 2f; val rd = (d * 2f).roundToInt() / 2f
        val key = "$kind/$variant/$rw/$rd/$zone"
        synchronized(cache) { cache[key]?.let { return it } }
        val o = Out(rw, rd)
        build(kind, variant, rw, rd, zone, o)
        val mesh = if (o.any) o.m.build().also { mm -> for (i in 0 until mm.vertexCount) if (mm.pos[i * 3 + 1] < 0f) mm.pos[i * 3 + 1] = 0f; if (mm.bounds[1] < 0f) mm.bounds[1] = 0f } else null
        val m = YardModel(mesh, o.flats, o.subs)
        synchronized(cache) { if (cache.put(key, m) == null) cache.bytes += sizeOf(m) }
        return m
    }

    /** Whether an item of [kind] throws a shadow. */
    fun casts(kind: String) = kind !in FLATS_ONLY && kind != "pond" && kind != "kerbCut"

    // ---------------------------------------------------------------------------------------- baking
    /**
     * [items] as world-space batches: one mesh per kind (all its items, their pictures in one atlas; more than one if the
     * atlas would grow past [maxAtlas] texels), one tiled mesh per ground or surface tile. [perKind] false: every kind
     * together instead (one atlas for those that throw a shadow, one for those that do not: a lot in a few draws).
     */
    fun bake(items: List<YardItem>, maxAtlas: Int = 2048, perKind: Boolean = true): List<YardBatch> {
        val byKind = LinkedHashMap<String, MutableList<Pair<PropMesh, FloatArray>>>()
        val flats = LinkedHashMap<String, MutableList<FloatArray>>() // surface -> world polys: [y, ox, oz, ux, uz, x0, z0, x1, z1, ...]
        val subsCast = HashMap<String, Boolean>()
        for (it in items) {
            if (it.kind !in KINDS) continue
            val f = frame(it)
            val m = model(it.kind, it.variant, f.w, f.d, it.zone)
            m.mesh?.let { mesh -> byKind.getOrPut(it.kind) { ArrayList() } += mesh to floatArrayOf(f.cx, f.cz, f.ax, f.az, f.ox, f.oz) }
            for (s in m.subs) {
                val wx = f.wx(s.x, s.z); val wz = f.wz(s.x, s.z)
                val ax = f.ax * s.ax + f.ox * s.az; val az = f.az * s.ax + f.oz * s.az
                val ox = f.ax * s.ox + f.ox * s.oz; val oz = f.az * s.ox + f.oz * s.oz
                val g = it.kind + if (s.casts) "" else "/flat"
                subsCast[g] = s.casts
                byKind.getOrPut(g) { ArrayList() } += s.mesh to floatArrayOf(wx, wz, ax, az, ox, oz)
            }
            for (fl in m.flats) {
                val n = fl.pts.size / 2
                val a = FloatArray(5 + n * 2)
                a[0] = fl.y; a[1] = f.wx(fl.ox, fl.oz); a[2] = f.wz(fl.ox, fl.oz)
                a[3] = f.ax * fl.ux + f.ox * fl.uz; a[4] = f.az * fl.ux + f.oz * fl.uz
                for (k in 0 until n) { a[5 + k * 2] = f.wx(fl.pts[k * 2], fl.pts[k * 2 + 1]); a[6 + k * 2] = f.wz(fl.pts[k * 2], fl.pts[k * 2 + 1]) }
                flats.getOrPut(fl.surface) { ArrayList() } += a
            }
        }
        val out = ArrayList<YardBatch>()
        for ((surface, polys) in flats) out += YardBatch("flat:$surface", flatMesh(surface, polys), tiled = true, casts = false, water = surface == "water" || surface == "pondwater")
        if (perKind) {
            for ((kind, list) in byKind) for (mesh in merge(list, maxAtlas)) out += YardBatch(kind, mesh, tiled = false, casts = subsCast[kind] ?: casts(kind), water = false)
        } else {
            val (solid, flat) = byKind.entries.partition { (kind, _) -> subsCast[kind] ?: casts(kind.substringBefore('/')) }
            for ((group, part) in listOf("solid" to solid, "flat" to flat)) for (mesh in merge(part.flatMap { it.value }, maxAtlas))
                out += YardBatch(group, mesh, tiled = false, casts = group == "solid", water = false)
        }
        return out
    }

    /** The world polygons of one tile picture as one mesh (uv in tiles: the sampler repeats it). */
    private fun flatMesh(surface: String, polys: List<FloatArray>): PropMesh {
        val img = YardPics.surface(surface)
        val tw = img.w * TEXEL; val th = img.h * TEXEL
        val nv = polys.sumOf { (it.size - 5) / 2 }
        val ni = polys.sumOf { ((it.size - 5) / 2 - 2) * 3 }
        val pos = FloatArray(nv * 3); val uv = FloatArray(nv * 2); val nr = FloatArray(nv * 3); val tn = FloatArray(nv * 3)
        val idx = IntArray(ni)
        var vi = 0; var ii = 0
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (p in polys) {
            val n = (p.size - 5) / 2
            val ux = p[3]; val uz = p[4]
            // v runs along the right-hand perpendicular of u on the ground
            val vx = -uz; val vz = ux
            for (k in 0 until n) {
                val x = p[5 + k * 2]; val z = p[6 + k * 2]
                pos[(vi + k) * 3] = x; pos[(vi + k) * 3 + 1] = p[0]; pos[(vi + k) * 3 + 2] = z
                uv[(vi + k) * 2] = ((x - p[1]) * ux + (z - p[2]) * uz) / tw
                uv[(vi + k) * 2 + 1] = ((x - p[1]) * vx + (z - p[2]) * vz) / th
                nr[(vi + k) * 3 + 1] = 1f
                tn[(vi + k) * 3] = ux; tn[(vi + k) * 3 + 2] = uz
                b[0] = min(b[0], x); b[1] = min(b[1], p[0]); b[2] = min(b[2], z); b[3] = max(b[3], x); b[4] = max(b[4], p[0]); b[5] = max(b[5], z)
            }
            for (k in 1 until n - 1) { idx[ii++] = vi; idx[ii++] = vi + k; idx[ii++] = vi + k + 1 }
            vi += n
        }
        return PropMesh(pos, uv, nr, tn, idx, img.w, img.h, img.px, null, b)
    }

    /**
     * Many meshes placed in the world ([at]: x, z, along x, z, out x, z, as [PropLayer] places them) baked into as few
     * meshes as their atlases allow: each distinct atlas (by content) packed once.
     */
    fun merge(list: List<Pair<PropMesh, FloatArray>>, maxAtlas: Int = 2048): List<PropMesh> {
        if (list.isEmpty()) return emptyList()
        // the distinct atlases, by content
        val keyOf = HashMap<PropMesh, Int>()
        val atlases = LinkedHashMap<Int, PropMesh>()
        for ((m, _) in list) keyOf.getOrPut(m) {
            val h = 31 * (31 * m.tex.contentHashCode() + (m.mask?.contentHashCode() ?: 0)) + m.texW * 4099 + m.texH
            atlases.getOrPut(h) { m }
            h
        }
        // pack them on shelves, as many sheets as needed
        class Slot(val sheet: Int, val x: Int, val y: Int)
        val slots = HashMap<Int, Slot>()
        val width = max(256, atlases.values.maxOf { it.texW } + 4).let { var w = 256; while (w < it) w *= 2; max(w, 1024).coerceAtMost(maxAtlas) }
        val sheetH = ArrayList<Int>()
        var sheet = 0; var x = 0; var y = 0; var shelf = 0
        for ((k, a) in atlases.entries.sortedByDescending { it.value.texH }) {
            val aw = a.texW + 4; val ah = a.texH + 4
            if (x + aw > width) { x = 0; y += shelf; shelf = 0 }
            if (y + ah > maxAtlas && y > 0) { sheetH += y + shelf; sheet++; x = 0; y = 0; shelf = 0 }
            slots[k] = Slot(sheet, x + 2, y + 2)
            x += aw; shelf = max(shelf, ah)
        }
        sheetH += y + shelf
        val out = ArrayList<PropMesh>()
        for (s in 0..sheet) {
            val h = sheetH[s].let { var p = 16; while (p < it) p *= 2; p }
            // (the mask only for a sheet that has one: most have none, and it was an atlas-sized array made for nothing)
            val anyMask = atlases.any { (k, a) -> a.mask != null && slots[k]!!.sheet == s }
            val tex = IntArray(width * h); val mask = if (anyMask) IntArray(width * h) else null
            for ((k, a) in atlases) {
                val sl = slots[k]!!; if (sl.sheet != s) continue
                for (yy in -2 until a.texH + 2) for (xx in -2 until a.texW + 2) {
                    val sx = xx.coerceIn(0, a.texW - 1); val sy = yy.coerceIn(0, a.texH - 1)
                    val di = (sl.y + yy) * width + sl.x + xx
                    tex[di] = a.tex[sy * a.texW + sx]
                    if (mask != null) a.mask?.let { mask[di] = it[sy * a.texW + sx] }
                }
            }
            val mine = list.filter { slots[keyOf[it.first]!!]!!.sheet == s }
            val nv = mine.sumOf { it.first.vertexCount }; val ni = mine.sumOf { it.first.idx.size }
            val pos = FloatArray(nv * 3); val uv = FloatArray(nv * 2); val nr = FloatArray(nv * 3); val tn = FloatArray(nv * 3); val idx = IntArray(ni)
            var vi = 0; var ii = 0
            val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            for ((m, a) in mine) {
                val sl = slots[keyOf[m]!!]!!
                for (i in 0 until m.vertexCount) {
                    val lx = m.pos[i * 3]; val ly = m.pos[i * 3 + 1]; val lz = m.pos[i * 3 + 2]
                    val wx = a[0] + a[2] * lx + a[4] * lz; val wz = a[1] + a[3] * lx + a[5] * lz
                    pos[(vi + i) * 3] = wx; pos[(vi + i) * 3 + 1] = ly; pos[(vi + i) * 3 + 2] = wz
                    nr[(vi + i) * 3] = a[2] * m.nrm[i * 3] + a[4] * m.nrm[i * 3 + 2]; nr[(vi + i) * 3 + 1] = m.nrm[i * 3 + 1]; nr[(vi + i) * 3 + 2] = a[3] * m.nrm[i * 3] + a[5] * m.nrm[i * 3 + 2]
                    tn[(vi + i) * 3] = a[2] * m.tan[i * 3] + a[4] * m.tan[i * 3 + 2]; tn[(vi + i) * 3 + 1] = m.tan[i * 3 + 1]; tn[(vi + i) * 3 + 2] = a[3] * m.tan[i * 3] + a[5] * m.tan[i * 3 + 2]
                    uv[(vi + i) * 2] = (sl.x + m.uv[i * 2] * m.texW) / width
                    uv[(vi + i) * 2 + 1] = (sl.y + m.uv[i * 2 + 1] * m.texH) / h
                    b[0] = min(b[0], wx); b[1] = min(b[1], ly); b[2] = min(b[2], wz); b[3] = max(b[3], wx); b[4] = max(b[4], ly); b[5] = max(b[5], wz)
                }
                for (k in m.idx) idx[ii++] = vi + k
                vi += m.vertexCount
            }
            out += PropMesh(pos, uv, nr, tn, idx, width, h, tex, mask, b)
        }
        return out
    }

    /** Every kind and a few looks, for the preview's lineup: (kind, variant, w, d). */
    fun lineup(): List<YardItem> {
        val sizes = mapOf(
            "lawn" to (60f to 60f), "meadow" to (60f to 60f), "gravel" to (60f to 60f), "driveway" to (60f to 82f), "kerbCut" to (38f to 60f),
            "frontPath" to (26f to 60f), "patio" to (60f to 60f), "parkingPad" to (60f to 80f), "poolDeck" to (80f to 80f), "deck" to (60f to 60f),
            "frontFence" to (60f to 4f), "gate" to (28f to 4f), "sideFence" to (60f to 4f), "backFence" to (60f to 4f), "sideGate" to (36f to 4f),
            "postRail" to (60f to 4f), "hedge" to (60f to 16f), "lowWall" to (60f to 8f),
            "bench" to (50f to 18f), "bikeShed" to (90f to 50f), "bins" to (60f to 26f), "birdbath" to (16f to 16f), "bush" to (26f to 26f),
            "carport" to (82f to 170f), "chair" to (16f to 16f), "compost" to (30f to 30f), "doghouse" to (32f to 36f), "firePit" to (40f to 40f),
            "flowerBed" to (60f to 12f), "fruitTree" to (56f to 56f), "garage" to (110f to 170f), "gazebo" to (90f to 90f), "greenhouse" to (70f to 110f),
            "grill" to (18f to 14f), "hammock" to (100f to 30f), "logPile" to (50f to 20f), "mailbox" to (8f to 10f), "pallets" to (48f to 40f),
            "planter" to (40f to 16f), "playSet" to (120f to 70f), "pond" to (70f to 50f), "pool" to (114f to 194f), "sandpit" to (48f to 48f),
            "shed" to (58f to 70f), "swing" to (60f to 30f), "table" to (40f to 32f), "trampoline" to (96f to 96f), "tree" to (64f to 64f),
            "vegBed" to (96f to 36f), "washingLine" to (64f to 64f),
        )
        val looks = mapOf(
            "lawn" to listOf(0, 1, 2, 3), "meadow" to listOf(0, 1), "gravel" to listOf(0, 1, 2, 3), "driveway" to listOf(0, 1, 2), "frontPath" to listOf(0, 1, 2),
            "patio" to listOf(0, 1, 2, 3), "parkingPad" to listOf(0, 1, 2), "deck" to listOf(0, 1, 2, 3), "frontFence" to listOf(0, 1, 2), "gate" to listOf(0, 1, 2),
            "sideFence" to listOf(0, 1, 2), "backFence" to listOf(0, 1, 2), "sideGate" to listOf(0, 5), "postRail" to listOf(0, 1, 2), "hedge" to listOf(0, 1, 2),
            "lowWall" to listOf(0, 1, 2), "bench" to listOf(0, 1, 2, 3, 4), "bins" to listOf(0, 1), "bush" to listOf(0, 2, 3, 4, 5, 6, 7, 8, 9), "chair" to listOf(0, 1, 2, 3, 9),
            "compost" to listOf(0, 1), "doghouse" to listOf(0, 1), "firePit" to listOf(0), "flowerBed" to listOf(0, 1, 2, 3, 4, 5), "fruitTree" to listOf(0, 1, 2, 3),
            "garage" to listOf(1, 2, 3), "greenhouse" to listOf(1), "grill" to listOf(0, 1), "mailbox" to listOf(0, 1, 2, 3), "pallets" to listOf(0), "planter" to listOf(1, 2, 3),
            "playSet" to listOf(0), "pond" to listOf(1), "pool" to listOf(0, 2), "shed" to listOf(0, 1, 2, 3, 4), "table" to listOf(0, 1, 2, 3), "trampoline" to listOf(0, 1),
            "tree" to listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), "vegBed" to listOf(0, 1, 2), "washingLine" to listOf(0, 1), "carport" to listOf(0, 1, 2), "logPile" to listOf(0),
        )
        val out = ArrayList<YardItem>()
        for (k in KINDS) {
            val (w, d) = sizes[k] ?: (40f to 40f)
            for (v in looks[k] ?: listOf(0)) {
                val (ww, dd) = if (k == "washingLine" && v == 1) 140f to 16f else w to d
                out += YardItem(k, 0f, 0f, ww, dd, 0f, 1f, v, if (k in GROUND) "ground" else if (k in SURFACES) "surface" else if (k in EDGES) "edge" else "object", null, if (k == "hedge" || k == "frontFence" || k == "lowWall") "front" else "back")
            }
        }
        return out
    }

    // ---------------------------------------------------------------------------------------- the builders
    private fun build(kind: String, v: Int, w: Float, d: Float, zone: String, o: Out) {
        val hw = w / 2f; val hd = d / 2f
        when (kind) {
            // the ground and hard surfaces
            "lawn" -> o.flat("grass/${v % 4}", -hw, -hd, hw, hd, .04f)
            "meadow" -> { o.flat("meadow/${v % 4}", -hw, -hd, hw, hd, .045f); tufts(o, w, d, v) }
            "gravel" -> o.flat("gravel/${v % 4}", -hw, -hd, hw, hd, .05f)
            "driveway" -> o.flat(when (v % 3) { 0 -> "asphalt"; 1 -> "concrete"; else -> "blocks" }, -hw, -hd, hw, hd, .10f)
            "kerbCut" -> o.flat("kerb", -hw, -hd, hw, hd, .14f)
            "parkingPad" -> o.flat(when (v % 3) { 0 -> "concrete"; 1 -> "gravel/1"; else -> "grasscrete" }, -hw, -hd, hw, hd, .12f)
            "frontPath" -> { o.flat(when (v % 3) { 0 -> "slabs"; 1 -> "brickpath"; else -> "concrete" }, -hw, -hd, hw, hd, .14f); edging(o, w, d, if (v % 3 == 1) Paint.TERRACOTTA.o else Paint.CONCRETE.d, sides = true) }
            "patio" -> { o.flat(when (v % 4) { 0 -> "sandstone"; 1 -> "greyslabs"; 2 -> "brickpath"; else -> "tiles" }, -hw, -hd, hw, hd, .16f); edging(o, w, d, Paint.CONCRETE.d, sides = false) }
            "poolDeck" -> { o.flat("travertine", -hw, -hd, hw, hd, .16f); edging(o, w, d, Paint.CONCRETE.d, sides = false) }
            "deck" -> deck(o, w, d, v)
            // edges
            "frontFence" -> picketFence(o, w, d, v)
            "gate" -> gate(o, w, d, v)
            "sideFence" -> boardFence(o, w, d, v, 24f)
            "backFence" -> when (v % 3) { 0 -> lapFence(o, w, d); 1 -> boardFence(o, w, d, 1, 28f); else -> chainFence(o, w, d) }
            "sideGate" -> sideGate(o, w, d, v)
            "postRail" -> postRail(o, w, d, v)
            "hedge" -> hedge(o, w, d, v, zone)
            "lowWall" -> lowWall(o, w, d, v)
            // objects
            "bench" -> bench(o, w, d, v)
            "chair" -> if (v >= 9) lounger(o, w, d) else chair(o, w, d, v)
            "table" -> table(o, w, d, v)
            "grill" -> grill(o, w, d, v)
            "bins" -> bins(o, w, d, v)
            "mailbox" -> { o.subs += Sub(PropKit.mesh(PropKind.HOME_MAILBOX, v % 6), 0f, 0f, 1f, 0f, 0f, 1f) }
            "planter" -> planter(o, w, d, v)
            "birdbath" -> birdbath(o, w, d, v)
            "bush" -> bush(o, w, d, v)
            "tree" -> tree(o, w, d, v)
            "fruitTree" -> fruitTree(o, w, d, v)
            "flowerBed" -> flowerBed(o, w, d, v)
            "vegBed" -> vegBed(o, w, d, v)
            "compost" -> compost(o, w, d, v)
            "shed" -> shed(o, w, d, v)
            "garage" -> garage(o, w, d, v)
            "carport" -> carport(o, w, d, v)
            "bikeShed" -> bikeShed(o, w, d, v)
            "doghouse" -> doghouse(o, w, d, v)
            "firePit" -> firePit(o, w, d, v)
            "logPile" -> logPile(o, w, d, v)
            "pallets" -> pallets(o, w, d, v)
            "greenhouse" -> greenhouse(o, w, d, v)
            "gazebo" -> gazebo(o, w, d, v)
            "hammock" -> hammock(o, w, d, v)
            "playSet" -> playSet(o, w, d, v)
            "swing" -> swing(o, w, d, v)
            "sandpit" -> sandpit(o, w, d, v)
            "trampoline" -> trampoline(o, w, d, v)
            "washingLine" -> washingLine(o, w, d, v)
            "pond" -> pond(o, w, d, v)
            "pool" -> pool(o, w, d, v)
            else -> error("no yard builder for $kind")
        }
    }

    // ---------------------------------------------------------------------------------------- surfaces
    /** A thin kerb of edging round a surface (or down its long sides only). */
    private fun edging(o: Out, w: Float, d: Float, c: Int, sides: Boolean) {
        val hw = w / 2f; val hd = d / 2f; val t = TEXEL
        val m = o.m; o.any = true
        m.sbox(-hw, 0f, -hd, -hw + t, .5f, hd, c); m.sbox(hw - t, 0f, -hd, hw, .5f, hd, c)
        if (!sides) { m.sbox(-hw + t, 0f, -hd, hw - t, .5f, -hd + t, c); m.sbox(-hw + t, 0f, hd - t, hw - t, .5f, hd, c) }
    }

    /** Meadow tufts: crossed cards of long grass scattered over it. */
    private fun tufts(o: Out, w: Float, d: Float, v: Int) {
        val r = YP.MEADOW
        val bl = YP.BLOOMS[(v * 2 + 1) % YP.BLOOMS.size]
        val card = YardPics.pic("tuft/$v", 10, 10) { im ->
            for (x in 0 until 10) {
                val top = 2 + ((x * 7 + 3) % 5)
                for (y in top until 10) im[x, y] = if (y == top) r.h else if (x % 2 == 0) r.l else r.d
            }
            im[2, 1] = bl[0]; im[7, 3] = bl[1]
        }
        val rnd = Paint.Rnd(911 + v)
        val n = (w * d / 260f).toInt().coerceIn(2, 600)
        val m = o.m; o.any = true
        repeat(n) {
            val s = 3.5f + rnd.next() * 3f
            val x = -w / 2f + s + rnd.next() * (w - 2 * s); val z = -d / 2f + s + rnd.next() * (d - 2 * s)
            val a = rnd.next() * PIf
            for (k in 0..1) {
                val aa = a + k * PIf / 2f
                val dx = cos(aa) * s; val dz = sin(aa) * s
                m.quad(v3(x - dx, s * 1.3f, z - dz), v3(x + dx, s * 1.3f, z + dz), v3(x + dx, 0f, z + dz), v3(x - dx, 0f, z - dz), card)
            }
        }
    }

    private fun deck(o: Out, w: Float, d: Float, v: Int) {
        val r = YardPics.deckRamp(v)
        val hw = w / 2f; val hd = d / 2f; val h = 3.2f
        o.flat("planks/$v", -hw, -hd, hw, hd, h)
        val m = o.m; o.any = true
        val side = YardPics.pic("deckside/${r.b}", 24, 4) { im -> im.fill(r.b); im.hline(0, 23, 0, r.h); im.hline(0, 23, 3, r.o); for (x in 0 until 24 step 8) im[x, 2] = r.d }
        m.tbox(-hw, 0f, -hd, hw, h - .01f, hd, side, null)
        // a step down at the front
        val s = min(30f, w * .4f)
        m.tbox(-s / 2f, 0f, hd - 4.5f, s / 2f, h / 2f, hd, side, YardPics.surface("planks/$v"))
    }

    // ---------------------------------------------------------------------------------------- edges
    private fun post(m: MeshBuilder, x: Float, z: Float, s: Float, h: Float, r: Ramp, capped: Boolean = true) {
        val hs = s / 2f
        val side = YardPics.pic("post/${r.b}/$h", max(2, (s / TEXEL).roundToInt()), max(2, (h / TEXEL).roundToInt())) { im ->
            im.fill(r.b); im.vline(0, 0, im.h - 1, r.l); im.vline(im.w - 1, 0, im.h - 1, r.d); im.hline(0, im.w - 1, im.h - 1, r.o); im.hline(0, im.w - 1, 0, r.h)
        }
        m.box(x - hs, 0f, z - hs, x + hs, h, z + hs) { sd, _, _ -> when (sd) { MeshBuilder.Side.BOTTOM -> null; MeshBuilder.Side.TOP -> YardPics.swatch(r.h); else -> side } }
        if (capped) m.sbox(x - hs - .35f, h, z - hs - .35f, x + hs + .35f, h + .7f, z + hs + .35f, r.l)
    }

    private fun picketRamp(v: Int) = when (v % 3) { 0 -> Paint.WHITE; 1 -> Paint.WOOD; else -> YP.SAGE }

    private fun picketFence(o: Out, w: Float, d: Float, v: Int, h: Float = 17f) {
        val r = picketRamp(v)
        val m = o.m; o.any = true
        val hw = w / 2f
        val pic = YardPics.picket(r, (h / TEXEL).roundToInt())
        val edge = YardPics.swatch(r.d)
        // posts every 40 or so, two rails behind the pickets, pickets 2.5 wide every 4.1
        val bays = max(1, (w / 40f).roundToInt())
        for (i in 0..bays) post(m, -hw + 1.2f + (w - 2.4f) * i / bays, -.6f, 2.4f, h + 1f, r)
        for (y in listOf(4f, h - 4f)) m.sbox(-hw, y, -1.6f, hw, y + 1.4f, -.6f, r.d)
        val n = max(1, ((w - 2f) / 4.1f).toInt())
        val gap = (w - n * 2.5f) / n
        for (i in 0 until n) {
            val x0 = -hw + gap / 2f + i * (2.5f + gap)
            m.box(x0, .4f, -.4f, x0 + 2.5f, h, .4f) { sd, _, _ -> when (sd) { MeshBuilder.Side.FRONT, MeshBuilder.Side.BACK -> pic; MeshBuilder.Side.BOTTOM, MeshBuilder.Side.TOP -> null; else -> edge } }
        }
    }

    private fun gate(o: Out, w: Float, d: Float, v: Int) {
        val r = picketRamp(v)
        val m = o.m; o.any = true
        val hw = w / 2f; val h = 16f
        // two taller posts with ball caps, the gate between
        for (x in listOf(-hw + 1.4f, hw - 1.4f)) {
            post(m, x, 0f, 2.8f, h + 3f, r, capped = false)
            m.ball(x, h + 4.4f, 0f, 1.6f, 1.6f, 1.6f, 6, 3, listOf(YardPics.swatch(r.l), YardPics.swatch(r.b), YardPics.swatch(r.d)), 1)
        }
        val x0 = -hw + 3.2f; val x1 = hw - 3.2f
        val pic = YardPics.picket(r, ((h - 1f) / TEXEL).roundToInt())
        val edge = YardPics.swatch(r.d)
        for (y in listOf(3.5f, h - 4.5f)) m.sbox(x0, y, -1.2f, x1, y + 1.6f, -.3f, r.b)
        m.beam(v3(x0 + .8f, 4.3f, -.8f), v3(x1 - .8f, h - 4f, -.8f), 1.2f, YardPics.swatch(r.b))
        val n = max(2, ((x1 - x0) / 3.6f).toInt())
        val gap = ((x1 - x0) - n * 2.5f) / max(1, n - 1)
        for (i in 0 until n) {
            val px = x0 + i * (2.5f + gap)
            m.box(px, 1.4f, -.3f, px + 2.5f, h, .5f) { sd, _, _ -> when (sd) { MeshBuilder.Side.FRONT, MeshBuilder.Side.BACK -> pic; MeshBuilder.Side.BOTTOM, MeshBuilder.Side.TOP -> null; else -> edge } }
        }
        // the latch
        m.sbox(x1 - 2.4f, 9f, .5f, x1 - .4f, 10.2f, 1.2f, Paint.IRON.b)
    }

    private fun boardRamp(v: Int) = when (v % 3) { 0 -> Paint.WOOD; 1 -> Paint.BROWN; else -> YP.TEAK }

    private fun boardFence(o: Out, w: Float, d: Float, v: Int, h: Float) {
        val r = boardRamp(v)
        val m = o.m; o.any = true
        val hw = w / 2f
        val face = YardPics.boards(r, 4, (h / TEXEL).roundToInt(), 31 + v)
        val back = YardPics.boards(r, 4, (h / TEXEL).roundToInt(), 41 + v, rails = true)
        m.tbox(-hw, 0f, -.6f, hw, h, .6f, YardPics.swatch(r.d), YardPics.swatch(r.l), face, back)
        // posts proud of the back, a capping rail over the top
        val bays = max(1, (w / 48f).roundToInt())
        for (i in 0..bays) post(m, -hw + 1.3f + (w - 2.6f) * i / bays, -1.2f, 2.4f, h + .6f, r.let { Ramp(it.o, it.d, it.d, it.b, it.l) }, capped = false)
        m.sbox(-hw, h, -1f, hw, h + .8f, 1f, r.l)
    }

    private fun lapFence(o: Out, w: Float, d: Float) {
        val r = Paint.WOOD; val h = 26f
        val m = o.m; o.any = true
        val hw = w / 2f
        val bays = max(1, (w / 48f).roundToInt())
        val bw = w / bays
        val panel = YardPics.lap(r, (h / TEXEL).roundToInt())
        for (i in 0 until bays) {
            val x0 = -hw + i * bw + 1.3f; val x1 = -hw + (i + 1) * bw - 1.3f
            m.tbox(x0, 1f, -.6f, x1, h, .6f, YardPics.swatch(r.d), YardPics.swatch(r.l), panel, panel, null)
            // the panel's frame
            m.sbox(x0, h, -.9f, x1, h + .8f, .9f, r.l); m.sbox(x0, .4f, -.9f, x1, 1.2f, .9f, r.d)
        }
        val pr = Ramp(Paint.c(0x3A2A22), Paint.c(0x5E4030), Paint.c(0x6E4C38), Paint.c(0x86604A), Paint.c(0xA07A60))
        for (i in 0..bays) post(m, -hw + i * bw + (if (i == 0) 1.3f else if (i == bays) -1.3f else 0f), 0f, 2.6f, h + 1.4f, pr)
    }

    private fun chainFence(o: Out, w: Float, d: Float) {
        val h = 24f
        val m = o.m; o.any = true
        val hw = w / 2f
        val mesh = YardPics.chain((h / TEXEL).roundToInt())
        m.tq(v3(-hw, h, 0f), v3(hw, h, 0f), v3(-hw, 0f, 0f), mesh)
        val bays = max(1, (w / 50f).roundToInt())
        for (i in 0..bays) {
            val x = -hw + 1f + (w - 2f) * i / bays
            m.upright(x, 0f, 0f, h + 1.2f, .9f, .9f, 6, null, YardPics.swatch(Paint.STEEL.b), cap = YardPics.disc(Paint.STEEL.l, .9f))
        }
        m.beam(v3(-hw, h, 0f), v3(hw, h, 0f), .7f, YardPics.swatch(Paint.STEEL.d))
    }

    private fun sideGate(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val hw = w / 2f
        if (v >= 5) {
            // across a driveway: a pair of wide gates, iron bars (5), white rails (6) or boards (7)
            val h = 18f
            val r = when (v) { 5 -> Paint.IRON; 6 -> Paint.WHITE; else -> Paint.WOOD }
            for (x in listOf(-hw + 1.6f, hw - 1.6f)) post(m, x, 0f, 3.2f, h + 3f, if (v == 5) Paint.IRON else Paint.BROWN)
            for (s in listOf(-1f, 1f)) {
                val a = s * (hw - 3.4f); val b = s * .4f
                val x0 = min(a, b); val x1 = max(a, b)
                if (v == 7) m.tbox(x0, 2f, -.6f, x1, h, .6f, YardPics.swatch(r.d), YardPics.swatch(r.l), YardPics.boards(r, 4, (16f / TEXEL).roundToInt(), 77), YardPics.boards(r, 4, (16f / TEXEL).roundToInt(), 78, true), null)
                else {
                    for (y in listOf(2.5f, h - 1f)) m.sbox(x0, y - .8f, -.6f, x1, y + .6f, .6f, r.b)
                    val n = max(2, ((x1 - x0) / 4f).toInt())
                    for (i in 0..n) { val x = x0 + (x1 - x0) * i / n; m.beam(v3(x, 1.7f, 0f), v3(x, h + if (v == 5) 1.6f else 0f, 0f), .9f, YardPics.swatch(if (v == 5) r.l else r.b)) }
                    if (v == 6) m.beam(v3(x0, 2.5f, .5f), v3(x1, h - 1f, .5f), 1f, YardPics.swatch(r.b))
                }
            }
        } else {
            // a garden gate of boards with its Z brace, in a frame
            val r = boardRamp(v)
            val h = 24f
            for (x in listOf(-hw + 1.4f, hw - 1.4f)) post(m, x, 0f, 2.8f, h + 1.4f, Paint.BROWN)
            val x0 = -hw + 3f; val x1 = hw - 3f
            m.tbox(x0, 1.2f, -.6f, x1, h, .6f, YardPics.swatch(r.d), YardPics.swatch(r.l), YardPics.boards(r, 4, (h / TEXEL).roundToInt(), 51 + v), YardPics.boards(r, 4, (h / TEXEL).roundToInt(), 61 + v), null)
            val br = YardPics.swatch(r.d)
            for (z in listOf(.9f, -.9f)) {
                m.beam(v3(x0, 5f, z), v3(x1, 5f, z), 1.5f, br); m.beam(v3(x0, h - 4f, z), v3(x1, h - 4f, z), 1.5f, br)
                m.beam(v3(x0 + 1f, 5.6f, z), v3(x1 - 1f, h - 4.6f, z), 1.3f, br)
            }
            m.sbox(x1 - 3f, 12f, .9f, x1 - .5f, 13.2f, 1.6f, Paint.IRON.b)
        }
    }

    private fun postRail(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val hw = w / 2f; val h = 17f
        val r = if (v % 3 == 2) Paint.BARK else Paint.WOOD
        val bays = max(1, (w / 40f).roundToInt())
        for (i in 0..bays) post(m, -hw + 1.4f + (w - 2.8f) * i / bays, 0f, 2.8f, h, Paint.BROWN, capped = false)
        val rails = if (v % 3 == 0) listOf(5f, 10f, 15f) else listOf(7f, 14.5f)
        val sw = YardPics.pic("rail/${r.b}", 24, 2) { im -> im.hline(0, 23, 0, r.l); im.hline(0, 23, 1, r.d); for (x in 0 until 24 step 7) im[x, 1] = r.o }
        for (y in rails) {
            if (v % 3 == 2) {
                // split rails, round and a little crooked
                for (i in 0 until bays) {
                    val xa = -hw + 1.4f + (w - 2.8f) * i / bays; val xb = -hw + 1.4f + (w - 2.8f) * (i + 1) / bays
                    m.prism(v3(xa, y + (i % 2) * .6f, .9f), v3(xb - xa, if (i % 2 == 0) .6f else -.6f, 0f), v3(0f, 1f, 0f), .9f, .9f, vlen(v3(xb - xa, .6f, 0f)), 5, null, YardPics.swatch(r.b))
                }
            } else m.tbox(-hw, y - 1f, .9f, hw, y + 1f, 1.9f, sw, YardPics.swatch(r.l), sw, sw, YardPics.swatch(r.d))
        }
    }

    private fun hedgeRamp(v: Int) = when (v % 3) { 0 -> YP.GRASS_LUSH; 1 -> YP.LEAF_DARK; else -> Paint.LEAF }

    private fun hedge(o: Out, w: Float, d: Float, v: Int, zone: String) {
        val m = o.m; o.any = true
        val r = hedgeRamp(v)
        val h = if (zone == "front") 17f + (v % 3) * 2f else 26f + (v % 3) * 3f
        val hw = w / 2f; val hd = d / 2f
        val inset = min(1.6f, hd * .3f)
        val side = YardPics.leaves(r, 0, 51 + v, fine = true)
        val shade = YardPics.leaves(r, -1, 52 + v, fine = true)
        val top = YardPics.leaves(r, 1, 53 + v, fine = true)
        // a clipped mass: straight sides, a bevelled lit top, its foot in shade
        val y1 = h - 2.2f
        val foot = 4f
        m.tq(v3(-hw, y1, hd), v3(hw, y1, hd), v3(-hw, foot, hd), side)
        m.tq(v3(-hw, foot, hd), v3(hw, foot, hd), v3(-hw, 0f, hd), shade)
        m.tq(v3(hw, y1, -hd), v3(-hw, y1, -hd), v3(hw, foot, -hd), side)
        m.tq(v3(hw, foot, -hd), v3(-hw, foot, -hd), v3(hw, 0f, -hd), shade)
        // the ends and the bevels
        for (x in listOf(-hw, hw)) {
            m.face(listOf(v3(x, 0f, -hd), v3(x, y1, -hd), v3(x, h, -hd + inset), v3(x, h, hd - inset), v3(x, y1, hd), v3(x, 0f, hd)), side, v3(0f, h / 2f, 0f))
        }
        m.tq(v3(-hw, h, hd - inset), v3(hw, h, hd - inset), v3(-hw, y1, hd), top)
        m.tq(v3(hw, h, -hd + inset), v3(-hw, h, -hd + inset), v3(hw, y1, -hd), top)
        m.tq(v3(-hw, h, -hd + inset), v3(hw, h, -hd + inset), v3(-hw, h, hd - inset), top)
    }

    private fun lowWall(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val hw = w / 2f; val hd = d / 2f
        val h = 11f
        val (face, cope) = when (v % 3) {
            0 -> YardPics.brickWall(Paint.TERRACOTTA, 71) to Paint.CONCRETE
            1 -> YardPics.stones(YP.STONE, Paint.c(0x6E6A62), 72) to YP.STONE
            else -> YardPics.render(YP.RENDER, 73) to Paint.CONCRETE
        }
        m.tbox(-hw, 0f, -hd + .7f, hw, h - 1.2f, hd - .7f, face, null)
        val cp = YardPics.pic("cope/${cope.b}", 16, 2) { im -> im.hline(0, 15, 0, cope.h); im.hline(0, 15, 1, cope.d); im[0, 1] = cope.o; im[8, 1] = cope.o }
        m.tbox(-hw, h - 1.2f, -hd, hw, h, hd, cp, YardPics.pic("copetop/${cope.b}", 16, 8) { im -> im.fill(cope.l); for (x in 0 until 16 step 8) im.vline(x, 0, 7, cope.d) })
        // piers at the ends
        for (x in listOf(-hw, hw - 5f)) if (w > 20f) m.tbox(x, 0f, -hd, x + 5f, h + 2.4f, hd, face, YardPics.swatch(cope.l))
    }

    // ---------------------------------------------------------------------------------------- objects
    private fun benchRamp(v: Int) = when (v % 5) { 0 -> Paint.WOOD; 1 -> Paint.WHITE; 2 -> Paint.SIGN; 3 -> Paint.BROWN; else -> YP.TEAK }

    private fun slatPic(r: Ramp, seed: Int) = YardPics.pic("slat/${r.b}/$seed", 32, 3) { im ->
        im.fill(r.l); im.hline(0, 31, 0, r.h); im.hline(0, 31, 2, r.b)
        val rnd = Paint.Rnd(seed); repeat(6) { val x = rnd.int(30); im.hline(x, x + 2, 1, r.b) }
    }

    private fun bench(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val r = benchRamp(v)
        val hw = w / 2f - 1f; val hd = d / 2f - 1f
        val sl = slatPic(r, 101 + v)
        val end = YardPics.swatch(r.o)
        val legs = if (v % 5 == 2) Paint.IRON else r
        // the seat slats, the back's two, legs and arms at both ends
        val seat = 9.2f
        for (k in 0..2) { val z0 = -hd + 4f + k * (2 * hd - 5f) / 3f; m.box(-hw, seat - 1f, z0, hw, seat, z0 + 2.4f) { s, _, _ -> when (s) { MeshBuilder.Side.LEFT, MeshBuilder.Side.RIGHT -> end; MeshBuilder.Side.BOTTOM -> null; else -> sl } } }
        for (y in listOf(12f, 15.6f)) m.box(-hw, y, -hd + .6f, hw, y + 2.4f, -hd + 1.6f) { s, _, _ -> when (s) { MeshBuilder.Side.LEFT, MeshBuilder.Side.RIGHT -> end; MeshBuilder.Side.BOTTOM -> null; else -> sl } }
        for (x in listOf(-hw + 1f, hw - 1f)) {
            m.panelBox(x - 1f, 0f, hd - 3f, x + 1f, 12.6f, hd - 1f, legs, 3)
            m.panelBox(x - 1f, 0f, -hd + .4f, x + 1f, 18.4f, -hd + 2.2f, legs, 4)
            m.panelBox(x - 1.2f, 12.6f, -hd + .4f, x + 1.2f, 13.6f, hd - .6f, legs, 5)
        }
        m.sbox(-hw + 1f, 3f, -hd + 1f, hw - 1f, 4f, -hd + 2f, r.d)
    }

    private fun chair(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val s = min(w, d) / 2f - .3f
        val (r, legC) = when (v % 4) { 0 -> Paint.WOOD to Paint.WOOD.d; 1 -> Paint.WHITE to Paint.WHITE.d; 2 -> Paint.IRON to Paint.IRON.o; else -> YP.HONEY to YP.HONEY.d }
        val seat = 8.6f
        val hs = min(s, 6f)
        for (x in listOf(-hs + .6f, hs - .6f)) for (z in listOf(-hs + .6f, hs - .6f)) m.beam(v3(x, 0f, z), v3(x, seat, z), .9f, YardPics.swatch(legC))
        if (v % 4 == 3) {
            // rattan: a woven tub
            val weave = YardPics.pic("rattan", 12, 8) { im -> for (y in 0 until 8) for (x in 0 until 12) im[x, y] = if ((x + y) % 3 == 0) r.d else if ((x - y + 12) % 3 == 0) r.l else r.b }
            m.tbox(-hs, 2f, -hs, hs, seat + 1f, hs, weave, YardPics.swatch(Paint.c(0xE8E0D0)))
            m.tbox(-hs, seat + 1f, -hs, hs, seat + 8f, -hs + 2f, weave, YardPics.swatch(r.l))
            for (x in listOf(-hs, hs - 2f)) m.tbox(x, seat + 1f, -hs + 2f, x + 2f, seat + 5f, hs, weave, YardPics.swatch(r.l))
        } else {
            m.rbox(-hs, seat, -hs, hs, seat + 1.2f, hs, r, 7, edges = false)
            m.rbox(-hs, seat + 1.2f, -hs, hs, seat + 10f, -hs + 1.2f, r, 8)
            if (v % 4 == 0) for (k in 1..2) m.sbox(-hs + 1f, seat + 1.2f + k * 2.8f, -hs + 1.2f, hs - 1f, seat + 1.6f + k * 2.8f, -hs + 1.4f, r.d)
        }
    }

    private fun lounger(o: Out, w: Float, d: Float) {
        val m = o.m; o.any = true
        // a sun lounger along its long side (x), its back raised at -x
        val hl = max(w, d) / 2f - .5f; val hs = min(w, d) / 2f - .5f
        val alongX = w >= d
        fun p(x: Float, y: Float, z: Float) = if (alongX) v3(x, y, z) else v3(z, y, x)
        val cushion = YardPics.stripes(Paint.c(0x3E8AC8), Paint.WHITE.l, 2, 24, 12)
        val frame = YardPics.swatch(Paint.WHITE.d)
        for (x in listOf(-hl + 1f, hl - 1f)) for (z in listOf(-hs + .6f, hs - .6f)) m.beam(p(x, 0f, z), p(x, 6f, z), .9f, frame)
        m.face(listOf(p(-hl + 9f, 6f, -hs), p(hl, 6f, -hs), p(hl, 6f, hs), p(-hl + 9f, 6f, hs)), cushion, p(0f, 0f, 0f))
        m.face(listOf(p(-hl, 15f, -hs), p(-hl + 9f, 6f, -hs), p(-hl + 9f, 6f, hs), p(-hl, 15f, hs)), cushion, p(0f, 0f, 0f))
        for (z in listOf(-hs, hs)) { m.beam(p(-hl + 9f, 5.4f, z), p(hl, 5.4f, z), .8f, frame); m.beam(p(-hl, 14.4f, z), p(-hl + 9f, 5.4f, z), .8f, frame) }
    }

    private fun table(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val hw = w / 2f - 1f; val hd = d / 2f - 1f
        val top = 13f
        when (v % 4) {
            0 -> {
                // a wooden table: plank top, four legs
                val r = Paint.WOOD
                m.tbox(-hw + 2f, top, -hd + 2f, hw - 2f, top + 1.4f, hd - 2f, YardPics.swatch(r.d), YardPics.planks(r, 3))
                for (x in listOf(-hw + 4f, hw - 4f)) for (z in listOf(-hd + 4f, hd - 4f)) m.panelBox(x - 1f, 0f, z - 1f, x + 1f, top, z + 1f, r, 9)
            }
            else -> {
                // a round table on a pedestal, a parasol through it (white, metal, honey)
                val r = when (v % 4) { 1 -> Paint.WHITE; 2 -> Paint.IRON; else -> YP.HONEY }
                val rad = min(hw, hd) - 1.5f
                m.upright(0f, 0f, top, top + 1.2f, rad, rad, 10, null, YardPics.swatch(r.b), cap = Paint.cap(rad, r))
                m.upright(0f, 0f, 0f, top, 1f, .8f, 6, null, YardPics.swatch(r.d))
                m.upright(0f, 0f, 0f, .8f, 4f, 3.6f, 8, null, YardPics.swatch(r.o), cap = YardPics.disc(r.d, 3.6f))
                if (v % 4 != 2) {
                    val pr = min(hw, hd) + .6f
                    m.upright(0f, 0f, top + 1.2f, 38f, .45f, .45f, 6, null, YardPics.swatch(Paint.WHITE.d))
                    val (a, b) = if (v % 4 == 1) Paint.c(0xCB2A2A) to Paint.c(0xF0ECE4) else Paint.c(0x2E9460) to Paint.c(0xF0ECE4)
                    val strip = YardPics.stripes(a, b, 3, 24, 8)
                    m.upright(0f, 0f, 33f, 37.6f, pr, .6f, 8, strip)
                    m.upright(0f, 0f, 32.4f, 33f, pr, pr, 8, null, YardPics.swatch(a))
                }
            }
        }
    }

    private fun grill(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        if (v % 2 == 0) {
            // a kettle: a black bowl and lid on three legs
            val rr = min(w, d) / 2f - 1f
            val r = Paint.BLACK
            for (k in 0..2) { val a = k * 2f * PIf / 3f; m.beam(v3(sin(a) * rr * .9f, 0f, cos(a) * rr * .9f), v3(sin(a) * rr * .45f, 10f, cos(a) * rr * .45f), .8f, YardPics.swatch(Paint.STEEL.d)) }
            m.ball(0f, 13f, 0f, rr * .8f, 4.4f, rr * .8f, 8, 4, listOf(YardPics.swatch(r.h), YardPics.swatch(r.l), YardPics.swatch(r.b), YardPics.swatch(r.d)), 3)
            m.sbox(-.6f, 17.2f, -.6f, .6f, 18.2f, .6f, Paint.STEEL.l)
        } else {
            // a gas grill trolley: steel box, lid, side shelf, the knobs
            val hw = w / 2f - .5f; val hd = d / 2f - .5f
            val r = Paint.STEEL
            m.rbox(-hw + 3f, 2f, -hd + 1f, hw - 3f, 12f, hd - 1f, Paint.IRON, 11)
            m.rbox(-hw + 3f, 12f, -hd + 1f, hw - 3f, 16f, hd - 1f, r, 12)
            m.sbox(hw - 3f, 11.4f, -hd + 2f, hw, 12f, hd - 2f, r.l)
            for (x in listOf(-hw + 3.6f, hw - 3.6f)) for (z in listOf(-hd + 1.6f, hd - 1.6f)) m.sbox(x - .5f, 0f, z - .5f, x + .5f, 2f, z + .5f, Paint.BLACK.b)
            for (k in 0..2) m.sbox(-hw + 5f + k * 2.6f, 10.4f, hd - 1f, -hw + 6f + k * 2.6f, 11.4f, hd - .4f, Paint.RED.b)
            m.sbox(-hw + 4f, 15.2f, hd - 1f, hw - 4f, 15.8f, hd - .4f, r.h)
        }
    }

    private fun bins(o: Out, w: Float, d: Float, v: Int) {
        val n = max(1, ((w - 2f) / 14f).toInt()).coerceAtMost(6)
        val pitch = w / n
        for (i in 0 until n) {
            val x = -w / 2f + pitch * (i + .5f)
            o.subs += Sub(PropKit.mesh(PropKind.WHEELIE_BIN, (v + i) % 4, seed = v * 5 + i), x, 0f, 1f, 0f, 0f, 1f)
        }
    }

    private fun planter(o: Out, w: Float, d: Float, v: Int) {
        val n = max(1, (w / 21f).toInt())
        val pitch = w / n
        for (i in 0 until n) o.subs += Sub(PropKit.mesh(PropKind.PLANTER, v % 3, seed = v + i), -w / 2f + pitch * (i + .5f), 0f, 1f, 0f, 0f, 1f)
    }

    private fun birdbath(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val r = YP.STONE
        val rr = min(w, d) / 2f - .2f
        val (sw, sh) = Paint.stripSize(2f, 9f)
        m.upright(0f, 0f, 0f, 1.4f, rr * .6f, rr * .55f, 8, Paint.strip(Paint.stripSize(rr * .6f, 1.4f).first, 2, r, 5), cap = null)
        m.upright(0f, 0f, 1.4f, 10f, 1.8f, 1.4f, 8, Paint.strip(sw, sh, r, 6, ends = false))
        m.upright(0f, 0f, 10f, 12f, 2f, rr, 10, Paint.strip(Paint.stripSize(rr, 2f).first, 3, r, 7))
        m.upright(0f, 0f, 12f, 13f, rr, rr, 10, null, YardPics.swatch(r.l), cap = YardPics.pic("bathwater", 12, 12) { im ->
            val c = 5.5f
            for (y in 0 until 12) for (x in 0 until 12) { val dd = sqrt((x - c) * (x - c) + (y - c) * (y - c)); im[x, y] = if (dd > 5f) r.l else if (dd > 4f) r.b else if (x < y) YP.WATER.l else YP.WATER.b }
            im[4, 4] = YP.WATER.h; im[5, 4] = YP.WATER.h
        })
        // a bird on the rim sometimes
        if (v % 3 == 0) { m.sbox(rr - 2.4f, 13f, -.6f, rr - .4f, 14.4f, .6f, Paint.c(0x5E4030)); m.sbox(rr - 2.8f, 14f, -.4f, rr - 2f, 15f, .4f, Paint.c(0x5E4030)) }
    }

    // ---------------------------------------------------------------------------------------- plants
    private fun crownPics(r: Ramp, seed: Int, dots: IntArray? = null, rate: Float = .02f) =
        listOf(YardPics.leaves(r, 1, seed, dots, rate), YardPics.leaves(r, 0, seed + 1, dots, rate), YardPics.leaves(r, 0, seed + 2, dots, rate), YardPics.leaves(r, -1, seed + 3, dots, rate * .5f))

    private fun trunk(m: MeshBuilder, h: Float, r: Float, birch: Boolean = false, lean: Float = 0f) {
        val img = YardPics.bark(birch)
        m.prism(v3(0f, 0f, 0f), v3(lean, h, 0f), v3(0f, 0f, 1f), r, r * .7f, vlen(v3(lean, h, 0f)), 6, img)
        // the flare at its foot
        m.upright(0f, 0f, 0f, 1.6f, r * 1.5f, r, 6, img)
    }

    private fun bush(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val s = min(w, d) / 2f - .3f
        when (v) {
            7 -> {
                // a topiary ball on a stem, in a box
                trunk(m, s * .8f, 1f)
                m.ball(0f, s * .8f + s * .6f, 0f, s * .6f, s * .6f, s * .6f, 10, 6, crownPics(YP.LEAF_DARK, 141), 7)
                m.rbox(-s * .45f, 0f, -s * .45f, s * .45f, s * .4f, s * .45f, Paint.TERRACOTTA, 9)
            }
            8 -> {
                // a clipped cone
                val pics = crownPics(YP.LEAF_DARK, 151)
                m.prism(v3(0f, 0f, 0f), v3(0f, 1f, 0f), v3(0f, 0f, 1f), s * .85f, .3f, s * 2.4f, 8, pics[1])
            }
            9 -> {
                // a clipped box shape
                val p = crownPics(YP.LEAF_DARK, 161)
                m.tbox(-s * .85f, 0f, -s * .85f, s * .85f, s * 1.1f, s * .85f, p[1], p[0])
            }
            else -> {
                val (r, dots) = when (v) {
                    0 -> Paint.LEAF to null; 1 -> YP.LEAF_DARK to null; 2 -> Paint.LEAF to YP.BLOOMS[4]; 3 -> YP.LEAF_DARK to YP.BLOOMS[3]
                    4 -> YP.LEAF_YELLOW to YP.BLOOMS[1]; 5 -> YP.LEAF_COPPER to null; else -> YP.LEAF_BLUE to YP.BLOOMS[6]
                }
                val pics = crownPics(r, 171 + v * 7, dots, .05f)
                m.ball(0f, s * .62f, 0f, s * .95f, s * .7f, s * .95f, 9, 5, pics, v)
                m.ball(s * .3f, s * .95f, -s * .2f, s * .55f, s * .5f, s * .55f, 7, 4, pics, v + 11)
            }
        }
    }

    private fun tree(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val s = min(w, d) / 2f - .3f
        when (v) {
            5, 6 -> {
                // a conifer: three tiers of needles on a short trunk
                val r = if (v == 5) YP.PINE else YP.LEAF_BLUE
                trunk(m, 10f, 1.8f)
                val hTot = s * 3.4f
                val tiers = 3
                for (k in 0 until tiers) {
                    val y0 = 6f + k * hTot * .26f; val rr = s * (1f - k * .24f)
                    m.prism(v3(0f, y0, 0f), v3(0f, 1f, 0f), v3(0f, 0f, 1f), rr, .4f, hTot * .42f, 9, YardPics.needles(r, if (k == tiers - 1) 1 else 0, 181 + k))
                    m.upright(0f, 0f, y0 - .01f, y0, rr, rr, 9, null, YardPics.swatch(r.o), cap = YardPics.disc(r.d, rr))
                }
            }
            7 -> {
                // a birch: white trunk, light airy lobes
                trunk(m, s * 1.6f, 1.5f, birch = true)
                val pics = crownPics(YP.LEAF_YELLOW, 191)
                m.ball(0f, s * 1.9f, 0f, s * .62f, s * .8f, s * .62f, 8, 5, pics, 1)
                m.ball(-s * .35f, s * 1.5f, s * .2f, s * .45f, s * .5f, s * .45f, 7, 4, pics, 2)
                m.ball(s * .38f, s * 1.6f, -s * .15f, s * .45f, s * .55f, s * .45f, 7, 4, pics, 3)
            }
            8 -> {
                // a poplar: tall and narrow
                trunk(m, s * .6f, 1.6f)
                m.ball(0f, s * 2.1f, 0f, s * .5f, s * 1.75f, s * .5f, 8, 7, crownPics(YP.LEAF_DARK, 201), 4)
            }
            else -> {
                // the round broadleaf (LimeZu's tree): a big crown and lobes over a stout trunk
                val r = when (v) { 0, 3 -> Paint.LEAF; 1 -> YP.LEAF_DARK; 2 -> YP.GRASS_LUSH; 4 -> YP.LEAF_YELLOW; else -> YP.LEAF_COPPER }
                val pics = crownPics(r, 211 + v * 5)
                val th = s * 1.05f
                trunk(m, th + s * .4f, 2.4f)
                // two branches into the crown
                m.beam(v3(0f, th * .7f, 0f), v3(-s * .4f, th + s * .3f, s * .1f), 1.6f, YardPics.swatch(Paint.BARK.b))
                m.beam(v3(0f, th * .75f, 0f), v3(s * .38f, th + s * .35f, -s * .1f), 1.5f, YardPics.swatch(Paint.BARK.d))
                val cy = th + s * .7f
                m.ball(0f, cy, 0f, s * .78f, s * .62f, s * .78f, 10, 6, pics, v)
                m.ball(-s * .45f, cy - s * .12f, s * .25f, s * .5f, s * .45f, s * .5f, 8, 5, pics, v + 3)
                m.ball(s * .48f, cy - s * .08f, -s * .2f, s * .48f, s * .45f, s * .48f, 8, 5, pics, v + 5)
                m.ball(s * .1f, cy + s * .42f, s * .1f, s * .48f, s * .4f, s * .48f, 8, 5, pics, v + 7)
            }
        }
    }

    private fun fruitTree(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val s = min(w, d) / 2f - .3f
        val fruit = YP.FRUIT[v % YP.FRUIT.size]
        // a blossoming one (pink and white) every few
        val dots = if (v >= 7) YP.BLOOMS[4] else fruit
        val pics = crownPics(Paint.LEAF, 231 + v, dots, .045f)
        val th = s * .75f
        trunk(m, th + 4f, 1.8f, lean = s * .1f)
        m.ball(s * .1f, th + s * .55f, 0f, s * .9f, s * .55f, s * .9f, 10, 5, pics, v)
        m.ball(-s * .3f, th + s * .85f, s * .2f, s * .5f, s * .35f, s * .5f, 8, 4, pics, v + 2)
        // a couple of windfalls under it
        if (v < 7) for (k in 0..2) { val a = k * 2.2f + v; m.sbox(cos(a) * s * .5f - .7f, 0f, sin(a) * s * .5f - .7f, cos(a) * s * .5f + .7f, 1.4f, sin(a) * s * .5f + .7f, fruit[0]) }
    }

    private fun flowerBed(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val hw = w / 2f; val hd = d / 2f
        // the soil, raised a little in an edging
        val edge = if (v % 2 == 0) Paint.CONCRETE else Paint.WOOD
        m.tbox(-hw, 0f, -hd, hw, 1.2f, hd, YardPics.swatch(edge.d), YardPics.surface("soil"))
        val cols = listOf(YP.BLOOMS[v % 7], YP.BLOOMS[(v + 2) % 7], YP.BLOOMS[(v + 4) % 7])
        val rnd = Paint.Rnd(251 + v)
        val step = 3.6f
        val n = max(1, ((w - 2f) / step).toInt())
        val rows = if (d >= 16f) 2 else 1
        for (row in 0 until rows) for (i in 0 until n) {
            val x = -hw + 1f + step * (i + .5f) + (rnd.next() - .5f) * 1.2f
            val z = if (rows == 1) 0f else -hd / 2f + row * hd
            val bl = cols[(i + row) % cols.size]
            val h = 6f + rnd.next() * 5f
            val rr = min(2.8f, hd - .4f)
            val pic = YardPics.leaves(Paint.LEAF, 0, 260 + (i + row) % 3, bl, .12f, fine = true)
            m.prism(v3(x, 1.2f, z), v3(0f, 1f, 0f), v3(0f, 0f, 1f), rr * .6f, rr, h * .6f, 6, pic)
            m.prism(v3(x, 1.2f + h * .6f, z), v3(0f, 1f, 0f), v3(0f, 0f, 1f), rr, rr * .3f, h * .4f, 6, pic, capFar = YardPics.disc(bl[1], rr * .3f))
        }
    }

    private fun vegBed(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val hw = w / 2f; val hd = d / 2f
        val r = Paint.WOOD
        val side = YardPics.pic("vegside", 24, 7) { im -> im.fill(r.b); im.hline(0, 23, 0, r.h); im.hline(0, 23, 3, r.d); im.hline(0, 23, 6, r.o); for (x in 0 until 24 step 6) im[x, 1] = r.d }
        m.tbox(-hw, 0f, -hd, hw, 5.6f, hd, side, null)
        val soil = YardPics.surface("soil")
        m.tq(v3(-hw + 1f, 5f, -hd + 1f), v3(hw - 1f, 5f, -hd + 1f), v3(-hw + 1f, 5f, hd - 1f), soil)
        val rows = max(1, ((d - 4f) / 10f).toInt())
        val per = max(1, ((w - 4f) / 9f).toInt())
        when (v % 3) {
            0, 1 -> {
                // lettuces (light) or cabbages (blue-green, bigger)
                val (lr, rad) = if (v % 3 == 0) YP.LEAF_YELLOW to 3f else YP.LEAF_BLUE to 3.8f
                val pics = listOf(YardPics.leaves(lr, 1, 271, fine = true), YardPics.leaves(lr, 0, 272, fine = true), YardPics.leaves(lr, -1, 273, fine = true))
                for (rI in 0 until rows) for (i in 0 until per) {
                    val x = -hw + 2f + (w - 4f) * (i + .5f) / per; val z = -hd + 2f + (d - 4f) * (rI + .5f) / rows
                    m.ball(x, 5f + rad * .5f, z, rad, rad * .75f, rad, 6, 3, pics, i + rI * 7)
                }
            }
            else -> {
                // beans up a cane wigwam in each row
                val cane = YardPics.swatch(Paint.c(0xC8A868))
                val leafPic = YardPics.leaves(Paint.LEAF, 0, 281, YP.BLOOMS[0], .03f, fine = true)
                for (rI in 0 until rows) {
                    val z = -hd + 2f + (d - 4f) * (rI + .5f) / rows
                    val pts = (0 until per).map { -hw + 2f + (w - 4f) * (it + .5f) / per }
                    for (x in pts) {
                        m.beam(v3(x, 5f, z - 2.4f), v3(x, 26f, z), .6f, cane); m.beam(v3(x, 5f, z + 2.4f), v3(x, 26f, z), .6f, cane)
                        m.prism(v3(x, 5.5f, z), v3(0f, 1f, 0f), v3(0f, 0f, 1f), 2.8f, 1f, 18f, 5, leafPic)
                    }
                    m.beam(v3(pts.first(), 25f, z), v3(pts.last(), 25f, z), .6f, cane)
                }
            }
        }
    }

    private fun compost(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val hw = w / 2f - .5f; val hd = d / 2f - .5f
        if (v % 2 == 0) {
            // a slatted wooden bay, the heap showing over its front
            val r = Paint.WOOD
            for (k in 0 until 5) {
                val y = k * 3.6f
                val sl = slatPic(r, 300 + k)
                m.box(-hw, y, -hd, hw, y + 2.6f, -hd + 1.2f) { s, _, _ -> if (s == MeshBuilder.Side.BOTTOM) null else sl }
                for (x in listOf(-hw, hw - 1.2f)) m.box(x, y, -hd, x + 1.2f, y + 2.6f, hd) { s, _, _ -> if (s == MeshBuilder.Side.BOTTOM) null else sl }
                if (k < 3) m.box(-hw, y, hd - 1.2f, hw, y + 2.6f, hd) { s, _, _ -> if (s == MeshBuilder.Side.BOTTOM) null else sl }
            }
            for (x in listOf(-hw, hw - 1.6f)) for (z in listOf(-hd, hd - 1.6f)) m.sbox(x, 0f, z, x + 1.6f, 18f, z + 1.6f, r.d)
            val heap = YardPics.pic("heap", 24, 24) { im -> im.fill(YP.SOIL.b); val rnd = Paint.Rnd(311); repeat(90) { val x = rnd.int(24); val y = rnd.int(24); im[x, y] = when (rnd.int(4)) { 0 -> YP.SOIL.d; 1 -> Paint.LEAF.b; 2 -> Paint.c(0xC8A050); else -> YP.SOIL.l } } }
            m.ball(0f, 9f, 0f, hw - 1.5f, 7f, hd - 1.5f, 8, 4, listOf(heap, heap), 2)
        } else {
            // a black plastic bin with its lid and hatch
            val r = Paint.BLACK
            val rr = min(hw, hd) * .62f
            val (sw, sh) = Paint.stripSize(rr, 20f)
            m.upright(0f, 0f, 0f, 20f, rr, rr * .75f, 8, Paint.strip(sw, sh, r, 9).also { im -> im.rect(im.w / 2 - 3, im.h - 6, im.w / 2 + 3, im.h - 2, r.d); im.hline(im.w / 2 - 3, im.w / 2 + 3, im.h - 6, r.l) })
            m.upright(0f, 0f, 20f, 22.4f, rr * .8f, rr * .6f, 8, null, YardPics.swatch(r.l), cap = Paint.cap(rr * .6f, r))
        }
    }

    // ---------------------------------------------------------------------------------------- buildings
    private fun shedRamp(v: Int) = when (v % 5) { 0 -> Paint.WOOD; 1 -> YP.SAGE; 2 -> YP.SKY; 3 -> Paint.TERRACOTTA; else -> YP.TEAK }

    private fun shed(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val r = shedRamp(v)
        val hw = w / 2f - 3f; val hd = d / 2f - 6f
        val wall = 28f; val ridge = 40f
        val walls = YardPics.boards(r, 4, (wall / TEXEL).roundToInt(), 321 + v)
        val gableEnd = YardPics.boards(r, 4, ((ridge - wall) / TEXEL).roundToInt() + 2, 331 + v)
        // a pent (one slope) roof for v >= 5, else a gable over the door end
        val door = YardPics.pic("sheddoor/${r.b}", 16, 28) { im ->
            im.fill(r.d); im.border(r.o)
            for (x in 2 until 15 step 4) im.vline(x, 1, 26, r.b)
            // the ledges and brace
            im.hline(1, 14, 4, r.l); im.hline(1, 14, 22, r.l); for (k in 0 until 17) im[1 + k * 13 / 17, 21 - k] = r.l
            im[12, 14] = Paint.STEEL.h; im[13, 14] = Paint.STEEL.l
        }
        val win = YardPics.pic("shedwin/${r.b}", 14, 10) { im ->
            im.fill(r.l); im.rect(1, 1, 12, 8, Paint.OUT); im.rect(2, 2, 11, 7, Paint.c(0x34435C)); im.vline(6, 2, 7, r.l); im.hline(2, 11, 4, r.l)
            im[3, 2] = Paint.c(0x8FA9D8); im[4, 2] = Paint.c(0x8FA9D8); im[3, 3] = Paint.c(0x8FA9D8)
        }
        // the shed's frame: x across the door end (front, +z), the ridge front to back (along z)
        m.tbox(-hw, 0f, -hd, hw, wall, hd, walls, null)
        m.sbox(-hw - .4f, 0f, -hd - .4f, hw + .4f, 2f, hd + .4f, Paint.CONCRETE.d)
        // door at the front, a window on the right side
        m.quad(v3(-5.6f, 23.4f, hd + .15f), v3(7.6f, 23.4f, hd + .15f), v3(7.6f, 0f, hd + .15f), v3(-5.6f, 0f, hd + .15f), door)
        m.quad(v3(hw + .15f, 22f, hd - 6f), v3(hw + .15f, 22f, hd - 18f), v3(hw + .15f, 13.6f, hd - 18f), v3(hw + .15f, 13.6f, hd - 6f), win)
        // the gable roof: ridge along z
        val roof = YardPics.shingles(if (v % 2 == 0) YP.FELT else YP.ROOFRED, 341 + v)
        val ov = 2.4f
        val zf = hd + ov; val zb = -hd - ov; val xl = -hw - ov; val xr = hw + ov
        m.tq(v3(0f, ridge, zf), v3(0f, ridge, zb), v3(xr, wall - 1f, zf), roof)
        m.tq(v3(0f, ridge, zb), v3(0f, ridge, zf), v3(xl, wall - 1f, zb), roof)
        val trim = YardPics.swatch(r.o)
        for (z in listOf(zf, zb)) {
            m.face(listOf(v3(-hw, wall, z - ov * (if (z > 0) 1 else -1)), v3(0f, ridge - .6f, z - ov * (if (z > 0) 1 else -1)), v3(hw, wall, z - ov * (if (z > 0) 1 else -1))), gableEnd, v3(0f, wall, 0f))
            m.face(listOf(v3(xl, wall - 1f, z), v3(0f, ridge, z), v3(0f, ridge - 1.2f, z), v3(xl, wall - 2.2f, z)), trim, v3(0f, wall, 0f))
            m.face(listOf(v3(xr, wall - 1f, z), v3(0f, ridge, z), v3(0f, ridge - 1.2f, z), v3(xr, wall - 2.2f, z)), trim, v3(0f, wall, 0f))
        }
        m.quad(v3(xr, wall - 1f, zb), v3(xr, wall - 1f, zf), v3(xr, wall - 2.2f, zf), v3(xr, wall - 2.2f, zb), trim)
        m.quad(v3(xl, wall - 1f, zf), v3(xl, wall - 1f, zb), v3(xl, wall - 2.2f, zb), v3(xl, wall - 2.2f, zf), trim)
        // a water butt at the corner
        if (v % 3 == 0) m.upright(hw - 4f, hd + 3.2f, 0f, 12f, 3f, 3f, 8, Paint.strip(Paint.stripSize(3f, 12f).first, 15, Paint.SIGN, 3), cap = Paint.cap(3f, Paint.BLACK))
    }

    private fun garage(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        // (inset by the eaves' overhang: the roof stays inside the rect)
        val hw = w / 2f - 3f; val hd = d / 2f - 3f
        val wall = 36f; val ridge = 50f
        val (walls, ramp) = when (v % 4) {
            1 -> YardPics.brickWall(Paint.TERRACOTTA, 351) to Paint.TERRACOTTA
            2 -> YardPics.render(YP.RENDER, 352) to YP.RENDER
            3 -> YardPics.brickWall(Paint.BEIGE, 353) to Paint.BEIGE
            else -> YardPics.render(Paint.BEIGE, 354) to Paint.BEIGE
        }
        m.tbox(-hw, 0f, -hd, hw, wall, hd, walls, null)
        // the up-and-over door on the front (+z), its colour by variant
        val dr = when (v % 4) { 1 -> Paint.WHITE; 2 -> Paint.WOOD; 3 -> Paint.GREY; else -> Paint.BLUE }
        val dw = min(hw * 2f - 10f, 92f)
        val door = YardPics.pic("garagedoor/${dr.b}/${dw.toInt()}", (dw / TEXEL).roundToInt(), (30f / TEXEL).roundToInt()) { im ->
            im.fill(dr.b); im.border(dr.o)
            for (y in 4 until im.h - 1 step 5) { im.hline(1, im.w - 2, y, dr.d); im.hline(1, im.w - 2, y + 1, dr.l) }
            im.rect(im.w / 2 - 3, im.h - 8, im.w / 2 + 2, im.h - 7, Paint.STEEL.l)
        }
        m.quad(v3(-dw / 2f, 30f, hd + .2f), v3(dw / 2f, 30f, hd + .2f), v3(dw / 2f, 0f, hd + .2f), v3(-dw / 2f, 0f, hd + .2f), door)
        // a lintel and the lamp over the door (it glows at night)
        m.sbox(-dw / 2f - 1.6f, 30f, hd, dw / 2f + 1.6f, 32f, hd + .8f, ramp.d)
        val lamp = YardPics.pic("walllamp", 3, 4) { im -> im.fill(Paint.c(0xF7D650)); im.hline(0, 2, 0, Paint.IRON.b); im.hline(0, 2, 3, Paint.IRON.b); im.glow(0, 1, 2, 2, 255) }
        m.box(-1.4f, 32.6f, hd, 1.4f, 35.4f, hd + 2f) { s, _, _ -> if (s == MeshBuilder.Side.BOTTOM) YardPics.swatch(Paint.IRON.b) else lamp }
        // a side door
        val sd = YardPics.pic("sidedoor", 12, 26) { im -> im.fill(Paint.WOOD.b); im.border(Paint.OUT); im.rect(2, 2, 9, 9, Paint.c(0x34435C)); im[9, 14] = Paint.STEEL.h }
        m.quad(v3(hw + .15f, 21.4f, hd - 10f), v3(hw + .15f, 21.4f, hd - 20f), v3(hw + .15f, 0f, hd - 20f), v3(hw + .15f, 0f, hd - 10f), sd)
        // a gabled roof, the ridge front to back
        val roof = YardPics.shingles(if (v % 2 == 0) YP.SLATE else YP.ROOFRED, 361 + v)
        val ov = 2.6f
        val zf = hd + ov; val zb = -hd - ov; val xl = -hw - ov; val xr = hw + ov
        m.tq(v3(0f, ridge, zf), v3(0f, ridge, zb), v3(xr, wall - 1.2f, zf), roof)
        m.tq(v3(0f, ridge, zb), v3(0f, ridge, zf), v3(xl, wall - 1.2f, zb), roof)
        val trim = YardPics.swatch(Paint.WHITE.b)
        for ((z, zw) in listOf(zf to hd, zb to -hd)) {
            m.face(listOf(v3(-hw, wall, zw), v3(0f, ridge - .8f, zw), v3(hw, wall, zw)), walls, v3(0f, wall, 0f))
            m.face(listOf(v3(xl, wall - 1.2f, z), v3(0f, ridge, z), v3(0f, ridge - 1.4f, z), v3(xl, wall - 2.6f, z)), trim, v3(0f, wall, 0f))
            m.face(listOf(v3(xr, wall - 1.2f, z), v3(0f, ridge, z), v3(0f, ridge - 1.4f, z), v3(xr, wall - 2.6f, z)), trim, v3(0f, wall, 0f))
        }
        m.quad(v3(xr, wall - 1.2f, zb), v3(xr, wall - 1.2f, zf), v3(xr, wall - 2.6f, zf), v3(xr, wall - 2.6f, zb), trim)
        m.quad(v3(xl, wall - 1.2f, zf), v3(xl, wall - 1.2f, zb), v3(xl, wall - 2.6f, zb), v3(xl, wall - 2.6f, zf), trim)
        // its apron
        o.flat("concrete", -hw, hd - .5f, hw, hd + .5f, .12f)
    }

    private fun carport(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val hw = w / 2f - 1f; val hd = d / 2f - 1f
        val (postR, roofR) = when (v % 3) { 0 -> Paint.WOOD to YP.SKY; 1 -> Paint.WHITE to Paint.STEEL; else -> Paint.IRON to Paint.GREY }
        val h0 = 34f; val h1 = 30f
        val n = max(2, (d / 60f).roundToInt() + 1)
        for (i in 0 until n) {
            val z = -hd + 1.6f + (2 * hd - 3.2f) * i / (n - 1)
            for (x in listOf(-hw + 1.6f, hw - 1.6f)) {
                val h = h1 + (h0 - h1) * (z + hd) / (2 * hd)
                post(m, x, z, 3f, h, postR, capped = false)
            }
        }
        // beams along both sides and the roof sheet, sloping down to the back
        for (x in listOf(-hw + 1.6f, hw - 1.6f)) m.beam(v3(x, h1 + 1f, -hd), v3(x, h0 + 1f, hd), 2.4f, YardPics.swatch(postR.d))
        val sheet = YardPics.corrugated(roofR)
        val t = .7f
        m.tq(v3(-hw - 1f, h1 + 2.6f, -hd - 1f), v3(hw + 1f, h1 + 2.6f, -hd - 1f), v3(-hw - 1f, h0 + 2.6f, hd + 1f), sheet)
        m.tq(v3(-hw - 1f, h0 + 2.6f, hd + 1f), v3(hw + 1f, h0 + 2.6f, hd + 1f), v3(-hw - 1f, h0 + 2.6f - t, hd + 1f), YardPics.swatch(postR.b))
        // a light under its front beam
        val lamp = YardPics.pic("carportlamp", 4, 2) { im -> im.fill(Paint.c(0xFFF59A)); im.glow(0, 0, 3, 1, 230) }
        m.box(-2f, h0 - .2f, hd - 4f, 2f, h0 + 1f, hd - 2f) { _, _, _ -> lamp }
    }

    private fun bikeShed(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val hw = w / 2f - 1f; val hd = d / 2f - 1f
        val r = shedRamp(v)
        val back = 30f; val front = 34f
        val boards = YardPics.boards(r, 4, (back / TEXEL).roundToInt(), 371 + v)
        // back wall and two sides, open at the front
        m.tbox(-hw, 0f, -hd, hw, back, -hd + 1.4f, boards, null)
        for (x in listOf(-hw, hw - 1.4f)) m.tbox(x, 0f, -hd + 1.4f, x + 1.4f, back, hd, boards, null)
        for (x in listOf(-hw + .7f, hw - .7f)) post(m, x, hd - .8f, 2.2f, front, Paint.BROWN, capped = false)
        val sheet = YardPics.corrugated(Paint.GREY)
        m.tq(v3(-hw - 1.5f, back + 1f, -hd - 1.5f), v3(hw + 1.5f, back + 1f, -hd - 1.5f), v3(-hw - 1.5f, front + 1f, hd + 2f), sheet)
        m.sbox(-hw, front - .5f, hd - 2f, hw, front + 1f, hd, r.d)
        // a concrete floor and bikes in their racks
        o.flat("concrete", -hw, -hd, hw, hd, .2f)
        val n = max(1, ((w - 6f) / 38f).toInt())
        for (i in 0 until n) o.subs += Sub(PropKit.mesh(PropKind.BIKE_RACK, if (i % 2 == 0) 1 else 0, seed = v + i), -hw + 3f + (w - 6f) * (i + .5f) / n, -hd + 8f, 1f, 0f, 0f, 1f)
    }

    private fun doghouse(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val hw = w / 2f - 2f; val hd = d / 2f - 5f
        val r = when (v % 4) { 0 -> Paint.WOOD; 1 -> Paint.RED; 2 -> YP.SKY; else -> Paint.BROWN }
        val wall = 14f; val ridge = 22f
        val walls = YardPics.boards(r, 3, (wall / TEXEL).roundToInt(), 381 + v)
        m.tbox(-hw, 0f, -hd, hw, wall, hd, walls, null)
        // the arched door (dark) on the front, the dog's name plate
        val door = YardPics.pic("kennel", 10, 12) { im ->
            for (y in 0 until 12) for (x in 0 until 10) { val dx = x - 4.5f; val inside = if (y < 5) dx * dx + (y - 5f) * (y - 5f) * 1.2f < 20f else true; im[x, y] = if (inside) Paint.c(0x16161F) else 0 }
            for (y in 0 until 12) for (x in 0 until 10) if (im[x, y] != 0 && (x == 0 || x == 9 || im[x, max(0, y - 1)] == 0)) im[x, y] = r.o
        }
        m.quad(v3(-4f, 10f, hd + .15f), v3(4.2f, 10f, hd + .15f), v3(4.2f, 0f, hd + .15f), v3(-4f, 0f, hd + .15f), door)
        val roof = YardPics.shingles(YP.ROOFRED, 391)
        val ov = 1.6f
        m.tq(v3(0f, ridge, hd + ov), v3(0f, ridge, -hd - ov), v3(hw + ov, wall - .6f, hd + ov), roof)
        m.tq(v3(0f, ridge, -hd - ov), v3(0f, ridge, hd + ov), v3(-hw - ov, wall - .6f, -hd - ov), roof)
        for (z in listOf(hd, -hd)) m.face(listOf(v3(-hw, wall, z), v3(0f, ridge - .4f, z), v3(hw, wall, z)), walls, v3(0f, wall, 0f))
        // the bowl
        m.upright(hw - 3f, hd + 2.4f, 0f, 1.6f, 2f, 2.4f, 8, null, YardPics.swatch(Paint.RED.b), cap = YardPics.disc(Paint.RED.d, 2.4f))
    }

    // ---------------------------------------------------------------------------------------- leisure
    private fun firePit(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val rr = min(w, d) / 2f - 1f
        val ring = YardPics.stones(YP.STONE, Paint.c(0x4D4A58), 401)
        // a ring of stones round a bed of embers (they glow at night), logs on it
        m.prism(v3(0f, 0f, 0f), v3(0f, 1f, 0f), v3(0f, 0f, 1f), rr * .55f, rr * .5f, 5f, 12, ring)
        val embers = YardPics.pic("embers", 12, 12) { im ->
            val rnd = Paint.Rnd(402)
            for (y in 0 until 12) for (x in 0 until 12) { val t = rnd.next(); im[x, y] = if (t < .3f) Paint.c(0x3C3C52) else if (t < .55f) Paint.c(0x56566E) else if (t < .8f) Paint.c(0xEE6420) else Paint.c(0xF7D650) }
            for (y in 0 until 12) for (x in 0 until 12) if (im[x, y] == Paint.c(0xEE6420) || im[x, y] == Paint.c(0xF7D650)) im.glowAt(x, y, 255)
        }
        m.upright(0f, 0f, 2.4f, 2.6f, rr * .45f, rr * .45f, 12, null, YardPics.swatch(Paint.c(0x3C3C52)), cap = embers)
        val log = YardPics.bark(false)
        for (k in 0..2) {
            val a = k * PIf * 2f / 3f + .3f
            m.prism(v3(cos(a) * rr * .35f, 3f, sin(a) * rr * .35f), v3(-cos(a), .45f, -sin(a)), v3(0f, 1f, 0f), .9f, .9f, rr * .55f, 6, log, capNear = YardPics.disc(Paint.c(0xC8A060), .9f))
        }
        // log stools round it
        for (k in 0 until 3) {
            val a = k * PIf * 2f / 3f + PIf / 3f
            val sx = cos(a) * (rr - 2.4f); val sz = sin(a) * (rr - 2.4f)
            m.upright(sx, sz, 0f, 6f, 2.2f, 2.2f, 7, log, cap = YardPics.pic("logend", 6, 6) { im -> im.fill(Paint.c(0xC8A060)); im.border(Paint.BARK.d); im[2, 2] = Paint.c(0x94704E); im[3, 3] = Paint.c(0x94704E) })
        }
    }

    private fun logPile(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val hw = w / 2f - .5f; val hd = d / 2f - .5f
        val bark = YardPics.bark(false)
        val end = YardPics.pic("logring", 6, 6) { im -> im.fill(Paint.c(0xD8B070)); im.border(Paint.BARK.b); im[2, 2] = Paint.c(0xA8804A); im[3, 3] = Paint.c(0xA8804A); im[2, 3] = Paint.c(0xB89058) }
        val rr = 2.1f
        val perRow = max(1, ((2 * hd) / (2 * rr)).toInt())
        val rows = 4
        for (row in 0 until rows) {
            val n = perRow - (row % 2)
            for (i in 0 until n) {
                val z = -hd + rr + (row % 2) * rr + i * 2 * rr
                if (z > hd - rr + .01f) continue
                val y = rr + row * rr * 1.7f
                m.prism(v3(-hw + .4f, y, z), v3(1f, 0f, 0f), v3(0f, 1f, 0f), rr, rr, 2 * hw - .8f, 6, bark, capFar = end, capNear = end, a0 = (-PI).toFloat() + (i + row) * .4f, a1 = PI.toFloat() + (i + row) * .4f)
            }
        }
        // a small roof over it
        m.sbox(-hw, 0f, -hd, -hw + 1f, 16f, -hd + 1f, Paint.BROWN.b); m.sbox(hw - 1f, 0f, -hd, hw, 16f, -hd + 1f, Paint.BROWN.b)
        m.tq(v3(-hw, 17f, -hd), v3(hw, 17f, -hd), v3(-hw, 15f, hd), YardPics.corrugated(Paint.GREY))
    }

    private fun pallets(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val hw = w / 2f - 1f; val hd = d / 2f - 1f
        val r = Paint.WOOD
        val n = 2 + v % 3
        val sl = slatPic(r, 411)
        for (k in 0 until n) {
            val y = k * 3.4f
            val tw = if (k % 2 == 0) 0f else .6f
            for (z in listOf(-hd + tw, -.6f, hd - 1.2f - tw)) m.box(-hw, y, z, hw, y + 1.4f, z + 1.2f) { s, _, _ -> if (s == MeshBuilder.Side.BOTTOM) null else YardPics.swatch(r.d) }
            val nTop = max(3, ((2 * hw) / 4f).toInt())
            for (i in 0 until nTop) { val x = -hw + (2 * hw - 2.4f) * i / (nTop - 1); m.box(x, y + 1.4f, -hd, x + 2.4f, y + 2.2f, hd) { s, _, _ -> if (s == MeshBuilder.Side.BOTTOM) null else sl } }
        }
    }

    private fun greenhouse(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val hw = w / 2f - 1f; val hd = d / 2f - 1f
        val wall = 26f; val ridge = 38f
        val frame = if (v % 2 == 1) Paint.WHITE else Paint.STEEL
        val fsw = YardPics.swatch(frame.b)
        // a brick base, aluminium frame, glass panes (clear: their glints painted on)
        m.tbox(-hw, 0f, -hd, hw, 4f, hd, YardPics.brickWall(Paint.TERRACOTTA, 421), YardPics.swatch(Paint.CONCRETE.l))
        val glass = YardPics.pic("glass", 12, 18) { im ->
            for (y in 0 until 18) for (x in 0 until 12) im[x, y] = 0
            for (k in 0 until 6) { im[2 + k, 12 - k * 2] = Paint.c(0xE6F2FA); im[2 + k, 13 - k * 2] = Paint.c(0xB6D2E6) }
            im[8, 4] = Paint.c(0xFFFFFF); im[9, 3] = Paint.c(0xE6F2FA)
        }
        // the panes on all four walls and the roof, mullions between
        val cols = max(2, ((2 * hw) / 10f).toInt()); val deep = max(2, ((2 * hd) / 10f).toInt())
        for (z in listOf(hd, -hd)) {
            m.tq(v3(-hw, wall, z), v3(hw, wall, z), v3(-hw, 4f, z), glass)
            for (i in 0..cols) { val x = -hw + 2 * hw * i / cols; m.beam(v3(x, 4f, z), v3(x, wall, z), .9f, fsw) }
            m.face(listOf(v3(-hw, wall, z), v3(0f, ridge, z), v3(hw, wall, z)), glass, v3(0f, wall, 0f), fit = true)
            m.beam(v3(-hw, wall, z), v3(0f, ridge, z), .9f, fsw); m.beam(v3(0f, ridge, z), v3(hw, wall, z), .9f, fsw)
            m.beam(v3(0f, wall, z), v3(0f, ridge, z), .9f, fsw)
        }
        for (x in listOf(hw, -hw)) {
            m.tq(v3(x, wall, hd), v3(x, wall, -hd), v3(x, 4f, hd), glass)
            for (i in 0..deep) { val z = -hd + 2 * hd * i / deep; m.beam(v3(x, 4f, z), v3(x, wall, z), .9f, fsw) }
            m.beam(v3(x, wall, -hd), v3(x, wall, hd), 1f, fsw)
        }
        for (s in listOf(-1f, 1f)) {
            m.tq(v3(0f, ridge, if (s > 0) hd else -hd), v3(0f, ridge, if (s > 0) -hd else hd), v3(s * hw, wall, if (s > 0) hd else -hd), glass)
            for (i in 0..deep) { val z = -hd + 2 * hd * i / deep; m.beam(v3(0f, ridge, z), v3(s * hw, wall, z), .8f, fsw) }
        }
        m.beam(v3(0f, ridge, -hd), v3(0f, ridge, hd), 1.2f, fsw)
        // inside: staging along both sides with tomato plants in pots
        val pics = listOf(YardPics.leaves(Paint.LEAF, 1, 431, YP.FRUIT[0], .04f, fine = true), YardPics.leaves(Paint.LEAF, 0, 432, YP.FRUIT[0], .04f, fine = true))
        for (s in listOf(-1f, 1f)) {
            val x0 = s * (hw - 7f)
            m.tbox(x0 - 5f, 9f, -hd + 2f, x0 + 5f, 10f, hd - 2f, YardPics.swatch(Paint.WOOD.d), YardPics.planks(Paint.WOOD, 1))
            val n = max(2, ((2 * hd - 6f) / 12f).toInt())
            for (i in 0 until n) {
                val z = -hd + 5f + (2 * hd - 10f) * i / (n - 1)
                m.upright(x0, z, 10f, 12.6f, 2f, 2.4f, 6, null, YardPics.swatch(Paint.TERRACOTTA.b))
                m.ball(x0, 17f, z, 3.4f, 5f, 3.4f, 6, 3, pics, i)
            }
        }
        // the sliding door on the front
        m.sbox(-5f, 4f, hd - .2f, -4.2f, wall, hd + .6f, frame.d); m.sbox(4.2f, 4f, hd - .2f, 5f, wall, hd + .6f, frame.d)
    }

    private fun gazebo(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val rr = min(w, d) / 2f - 1f
        val n = 8
        val r = Paint.WHITE
        // an octagonal deck, eight posts, low rails, a pointed roof, lanterns that glow at night
        val deckR = rr - 1f
        m.prism(v3(0f, 0f, 0f), v3(0f, 1f, 0f), v3(0f, 0f, 1f), deckR, deckR, 3f, n, YardPics.pic("gzbase", 40, 4) { im -> im.fill(Paint.WOOD.b); im.hline(0, 39, 0, Paint.WOOD.h); im.hline(0, 39, 3, Paint.WOOD.o) },
            capFar = YardPics.pic("gzfloor/${(deckR * 2.4f).toInt()}", (2f * deckR / TEXEL).toInt() + 3, (2f * deckR / TEXEL).toInt() + 3) { im ->
                val r = Paint.WOOD
                for (y in 0 until im.h) for (x in 0 until im.w) im[x, y] = if (y % 6 == 5) r.o else if (y % 6 == 0) r.l else if ((x + y / 6 * 13) % 40 == 0) r.d else r.b
            })
        val postR = deckR - 2f
        val h = 32f
        for (k in 0 until n) {
            val a = 2f * PIf * (k + .5f) / n
            val x = sin(a) * postR; val z = cos(a) * postR
            m.upright(x, z, 3f, h, 1.2f, 1.2f, 6, null, YardPics.swatch(r.b), cap = YardPics.disc(r.l, 1.2f))
            val a2 = 2f * PIf * (k + 1.5f) / n
            val x2 = sin(a2) * postR; val z2 = cos(a2) * postR
            if (k != 0) {
                m.beam(v3(x, 12f, z), v3(x2, 12f, z2), .9f, YardPics.swatch(r.b))
                m.beam(v3(x, 7f, z), v3(x2, 7f, z2), .6f, YardPics.swatch(r.d))
            }
            m.beam(v3(x, h - .6f, z), v3(x2, h - .6f, z2), 1.4f, YardPics.swatch(r.d))
            // the lanterns hung under the eaves
            if (k % 2 == 0) {
                val lamp = YardPics.pic("lantern", 2, 3) { im -> im.fill(Paint.c(0xFFD070)); im.glow(0, 0, 1, 2, 255) }
                val lx = (x + x2) / 2f * .95f; val lz = (z + z2) / 2f * .95f
                m.box(lx - .8f, h - 5f, lz - .8f, lx + .8f, h - 3f, lz + .8f) { _, _, _ -> lamp }
            }
        }
        val roof = YardPics.shingles(YP.ROOFRED, 441)
        m.prism(v3(0f, h, 0f), v3(0f, 1f, 0f), v3(0f, 0f, 1f), rr + .5f, .5f, 14f, n, roof, capNear = YardPics.disc(r.d, rr + .5f))
        m.upright(0f, 0f, h + 13.6f, h + 17f, .6f, .2f, 6, null, YardPics.swatch(Paint.YELLOW.b))
    }

    private fun hammock(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val hw = w / 2f - 1f; val hd = d / 2f - 1f
        // a curved wooden stand along x, the hammock slung between its ends
        val r = Paint.BROWN
        val sw = YardPics.swatch(r.b)
        m.beam(v3(-hw + 4f, 1f, 0f), v3(hw - 4f, 1f, 0f), 2.2f, sw)
        for (s in listOf(-1f, 1f)) {
            m.beam(v3(s * (hw - 14f), 1f, 0f), v3(s * (hw - .8f), 18f, 0f), 2f, sw)
            for (z in listOf(-hd + 1f, hd - 1f)) m.beam(v3(s * (hw - 6f), .6f, z), v3(s * (hw - 6f), .6f, 0f), 1.6f, YardPics.swatch(r.d))
        }
        val (a, b) = when (v % 3) { 0 -> Paint.c(0xCB2A2A) to Paint.c(0xF7D650); 1 -> Paint.c(0x2D4C8E) to Paint.c(0xEBE4F2); else -> Paint.c(0x2E9460) to Paint.c(0xE0B870) }
        val cloth = YardPics.stripes(a, b, 2, 16, 24)
        val seg = 8
        val half = min(hd, 7f)
        fun p(i: Int, z: Float): FloatArray {
            val t = i / seg.toFloat()
            val x = -(hw - 6f) + t * 2f * (hw - 6f)
            val y = 15f - 9f * sin(PIf * t)
            val zz = z * sin(PIf * t).coerceAtLeast(.25f)
            return v3(x, y, zz)
        }
        for (i in 0 until seg) m.face(listOf(p(i, -half), p(i + 1, -half), p(i + 1, half), p(i, half)), cloth, v3(0f, 30f, 0f))
        val rope = YardPics.swatch(Paint.c(0xE0D8C8))
        m.beam(v3(-(hw - 1.4f), 17.4f, 0f), p(0, 0f), .5f, rope); m.beam(v3(hw - 1.4f, 17.4f, 0f), p(seg, 0f), .5f, rope)
    }

    private fun playSet(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val hw = w / 2f - 1f; val hd = d / 2f - 1f
        val r = Paint.WOOD
        val sw = YardPics.swatch(r.b)
        // the tower at -x: four posts, a deck, a little roof, a slide off its front
        val tx0 = -hw; val tx1 = -hw + min(34f, w * .32f)
        val tz0 = -hd + 2f; val tz1 = min(hd - 2f, tz0 + 30f)
        val deckY = 18f; val top = 44f
        for (x in listOf(tx0 + 1.2f, tx1 - 1.2f)) for (z in listOf(tz0 + 1.2f, tz1 - 1.2f)) post(m, x, z, 2.6f, top, r, capped = false)
        m.tbox(tx0, deckY, tz0, tx1, deckY + 1.4f, tz1, YardPics.swatch(r.d), YardPics.planks(r, 4))
        // its rails, a ladder at the back, the roof
        for (z in listOf(tz0 + .6f, tz1 - .6f)) m.beam(v3(tx0, deckY + 9f, z), v3(tx1, deckY + 9f, z), 1f, sw)
        val roofR = if (v % 2 == 0) Paint.SIGN else Paint.RED
        val ov = 2f
        m.tq(v3(tx0 - ov, top + 9f, (tz0 + tz1) / 2f), v3(tx1 + ov, top + 9f, (tz0 + tz1) / 2f), v3(tx0 - ov, top, tz1 + ov), YardPics.stripes(roofR.b, roofR.l, 4, 16, 16))
        m.tq(v3(tx1 + ov, top + 9f, (tz0 + tz1) / 2f), v3(tx0 - ov, top + 9f, (tz0 + tz1) / 2f), v3(tx1 + ov, top, tz0 - ov), YardPics.stripes(roofR.b, roofR.l, 4, 16, 16))
        for (k in 1..4) m.beam(v3(tx0 + .6f, deckY * k / 5f, tz0 + 6f), v3(tx0 + .6f, deckY * k / 5f, tz1 - 6f), 1f, YardPics.swatch(r.l))
        // the slide: down off the deck's front (+z) edge... along x past the tower, to stay inside the rect
        val slide = YardPics.pic("slide/${v % 3}", 8, 24) { im -> val c = if (v % 3 == 1) Paint.BLUE else Paint.YELLOW; im.fill(c.l); im.vline(0, 0, 23, c.o); im.vline(7, 0, 23, c.o); im.vline(1, 0, 23, c.h); im.vline(6, 0, 23, c.d) }
        val sz0 = tz1 - 10f; val sz1 = tz1 - 1f
        val slideEnd = min(hw, tx1 + 40f)
        m.face(listOf(v3(tx1, deckY + 1.4f, sz0), v3(tx1, deckY + 1.4f, sz1), v3(slideEnd - 6f, 2.4f, sz1), v3(slideEnd - 6f, 2.4f, sz0)), slide, v3(0f, -50f, 0f))
        m.face(listOf(v3(slideEnd - 6f, 2.4f, sz0), v3(slideEnd - 6f, 2.4f, sz1), v3(slideEnd, 1.4f, sz1), v3(slideEnd, 1.4f, sz0)), slide, v3(0f, -50f, 0f))
        // the swing beam from the tower along x, two swings on it
        val bx0 = tx1; val bx1 = hw - 1f
        val sb = top - 6f
        if (bx1 - bx0 > 30f) {
            m.beam(v3(bx0, sb, -hd + 16f), v3(bx1, sb, -hd + 16f), 2.4f, sw)
            for (z in listOf(-hd + 2f, -hd + 30f)) m.beam(v3(bx1 - 4f, 0f, z), v3(bx1, sb, -hd + 16f), 2.2f, sw)
            val seats = listOf(bx0 + (bx1 - bx0) * .35f, bx0 + (bx1 - bx0) * .72f)
            val chain = YardPics.swatch(Paint.STEEL.d)
            for ((i, sx) in seats.withIndex()) {
                val seat = if (i == 0) Paint.RED else Paint.BLUE
                for (dx in listOf(-3.6f, 3.6f)) m.beam(v3(sx + dx, sb - 1f, -hd + 16f), v3(sx + dx, 9.6f, -hd + 16f), .4f, chain)
                m.rbox(sx - 4.2f, 8.6f, -hd + 14.2f, sx + 4.2f, 9.8f, -hd + 17.8f, seat, 3)
            }
        }
    }

    private fun swing(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val hw = w / 2f - 1f; val hd = d / 2f - 1f
        val r = if (v % 2 == 0) Paint.GREY else Paint.SIGN
        val sw = YardPics.swatch(r.b)
        val top = 34f
        m.beam(v3(-hw + 1f, top, 0f), v3(hw - 1f, top, 0f), 1.6f, sw)
        for (x in listOf(-hw + 2f, hw - 2f)) for (z in listOf(-hd + 1f, hd - 1f)) m.beam(v3(x, 0f, z), v3(x, top, 0f), 1.4f, sw)
        val chain = YardPics.swatch(Paint.STEEL.d)
        val seats = if (w > 50f) listOf(-hw * .45f, hw * .45f) else listOf(0f)
        for ((i, sx) in seats.withIndex()) {
            for (dx in listOf(-3.4f, 3.4f)) m.beam(v3(sx + dx, top - .8f, 0f), v3(sx + dx, 9.6f, 0f), .4f, chain)
            m.rbox(sx - 4f, 8.6f, -1.8f, sx + 4f, 9.8f, 1.8f, if (i == 0) Paint.YELLOW else Paint.RED, 5)
        }
    }

    private fun sandpit(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val hw = w / 2f - .5f; val hd = d / 2f - .5f
        val r = if (v % 2 == 0) Paint.WOOD else Paint.SIGN
        val side = YardPics.pic("pitside/${r.b}", 24, 4) { im -> im.fill(r.b); im.hline(0, 23, 0, r.h); im.hline(0, 23, 3, r.o) }
        // the frame with seats at its corners, the sand, a bucket and spade
        for ((x0, z0, x1, z1) in listOf(listOf(-hw, -hd, hw, -hd + 2f), listOf(-hw, hd - 2f, hw, hd), listOf(-hw, -hd + 2f, -hw + 2f, hd - 2f), listOf(hw - 2f, -hd + 2f, hw, hd - 2f)))
            m.tbox(x0, 0f, z0, x1, 3.4f, z1, side, YardPics.swatch(r.l))
        for ((sx, sz) in listOf(-1f to -1f, 1f to 1f)) m.face(listOf(v3(sx * hw, 3.5f, sz * hd), v3(sx * (hw - 9f), 3.5f, sz * hd), v3(sx * hw, 3.5f, sz * (hd - 9f))), YardPics.planks(r, 3), v3(0f, -10f, 0f))
        o.flat("sand", -hw + 2f, -hd + 2f, hw - 2f, hd - 2f, 2.2f)
        // a heap of sand, the bucket
        m.ball(hw * .2f, 2.2f, -hd * .2f, 6f, 2.6f, 5f, 7, 3, listOf(YardPics.surface("sand"), YardPics.surface("sand")), 3)
        val bucket = if (v % 3 == 0) Paint.RED else if (v % 3 == 1) Paint.BLUE else Paint.YELLOW
        m.upright(-hw * .4f, hd * .3f, 2.2f, 6f, 1.8f, 2.3f, 7, null, YardPics.swatch(bucket.b), cap = YardPics.disc(bucket.d, 2.3f))
        m.beam(v3(hw * .4f, 2.6f, hd * .4f), v3(hw * .4f + 4f, 4f, hd * .4f - 3f), .6f, YardPics.swatch(Paint.YELLOW.b))
    }

    private fun trampoline(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val rr = min(w, d) / 2f - 1f
        val n = 16
        val h = 9f
        // legs, the frame ring, the blue pad round the black mat
        for (k in 0 until 6) {
            val a = 2f * PIf * k / 6
            val x = sin(a) * (rr - 2f); val z = cos(a) * (rr - 2f)
            m.beam(v3(x, 0f, z), v3(x, h, z), 1.2f, YardPics.swatch(Paint.STEEL.d))
            m.beam(v3(x * .82f, 0f, z * .82f), v3(x, 0f, z), 1f, YardPics.swatch(Paint.STEEL.d))
        }
        val pad = if (v % 3 == 0) Paint.BLUE else if (v % 3 == 1) Paint.SIGN else Paint.RED
        val padStrip = YardPics.pic("tramppad/${pad.b}", 48, 3) { im -> im.fill(pad.b); im.hline(0, 47, 0, pad.h); im.hline(0, 47, 2, pad.d); for (x in 0 until 48 step 6) im[x, 1] = pad.l }
        m.prism(v3(0f, h - 1.2f, 0f), v3(0f, 1f, 0f), v3(0f, 0f, 1f), rr - .4f, rr - .4f, 2f, n, padStrip)
        // the pad's top ring and the mat inside, as rings of quads
        val padTop = YardPics.swatch(pad.l)
        val mat = YardPics.pic("trampmat", 16, 16) { im -> im.fill(Paint.BLACK.b); for (y in 0 until 16 step 4) im.hline(0, 15, y, Paint.BLACK.l); for (x in 0 until 16 step 4) im.vline(x, 0, 15, Paint.BLACK.d) }
        for (k in 0 until n) {
            val a0 = 2f * PIf * k / n; val a1 = 2f * PIf * (k + 1) / n
            fun at(r: Float, a: Float, y: Float) = v3(sin(a) * r, y, cos(a) * r)
            m.face(listOf(at(rr - .4f, a0, h + .8f), at(rr - .4f, a1, h + .8f), at(rr - 6f, a1, h + .8f), at(rr - 6f, a0, h + .8f)), padTop, v3(0f, -10f, 0f))
            m.face(listOf(at(rr - 6f, a0, h + .2f), at(rr - 6f, a1, h + .2f), v3(0f, h - .6f, 0f)), mat, v3(0f, -10f, 0f))
        }
        // a safety net for some: posts and a mesh cut out
        if (v % 2 == 0) {
            val net = YardPics.pic("net", 8, 8) { im -> for (y in 0 until 8) for (x in 0 until 8) im[x, y] = if (x % 4 == 0 || y % 4 == 0) Paint.c(0x2E2E40) else 0 }
            val top = h + 22f
            val k6 = 8
            for (k in 0 until k6) {
                val a0 = 2f * PIf * k / k6; val a1 = 2f * PIf * (k + 1) / k6
                val rn = rr - 1.4f
                m.beam(v3(sin(a0) * rn, h, cos(a0) * rn), v3(sin(a0) * rn, top, cos(a0) * rn), .8f, YardPics.swatch(Paint.STEEL.b))
                if (k != 0) m.tq(v3(sin(a0) * rn, top, cos(a0) * rn), v3(sin(a1) * rn, top, cos(a1) * rn), v3(sin(a0) * rn, h + 1f, cos(a0) * rn), net)
            }
        }
    }

    private fun washingLine(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val hw = w / 2f - 1f; val hd = d / 2f - 1f
        val steel = YardPics.swatch(Paint.STEEL.b)
        val line = YardPics.swatch(Paint.c(0xE8E4DA))
        val clothes = clothes()
        val rnd = Paint.Rnd(451 + v)
        fun hang(a: FloatArray, b: FloatArray, n: Int) {
            for (i in 0 until n) {
                val t0 = (i + .25f) / n; val t1 = t0 + .6f / n
                val p0 = vadd(a, vmul(vsub(b, a), t0)); val p1 = vadd(a, vmul(vsub(b, a), t1))
                val img = clothes[rnd.int(clothes.size)]
                val hh = img.h * TEXEL
                m.quad(p0, p1, vadd(p1, v3(0f, -hh, 0f)), vadd(p0, v3(0f, -hh, 0f)), img)
            }
        }
        if (w / max(d, 1f) < 2f) {
            // rotary: the pole, four arms, three rings of line
            val rr = min(hw, hd)
            val top = 30f
            m.upright(0f, 0f, 0f, top + 2f, .8f, .8f, 6, null, steel, cap = steel)
            val arms = (0 until 4).map { 2f * PIf * (it + .5f) / 4f + .3f }
            for (a in arms) m.beam(v3(0f, top - 4f, 0f), v3(sin(a) * rr, top, cos(a) * rr), .6f, steel)
            for (k in 1..3) {
                val rk = rr * k / 3f
                for (i in 0 until 4) {
                    val a = arms[i]; val b = arms[(i + 1) % 4]
                    val pa = v3(sin(a) * rk, top - 4f + 4f * k / 3f, cos(a) * rk); val pb = v3(sin(b) * rk, top - 4f + 4f * k / 3f, cos(b) * rk)
                    m.beam(pa, pb, .25f, line)
                    if (k == 3 || (k == 2 && i % 2 == 0)) hang(pa, pb, if (k == 3) 2 else 1)
                }
            }
        } else {
            // two T posts and two lines between, a prop in the middle
            val top = 30f
            for (x in listOf(-hw + 1f, hw - 1f)) {
                m.upright(x, 0f, 0f, top, .8f, .8f, 6, null, steel, cap = steel)
                m.beam(v3(x, top - .6f, -min(hd, 6f)), v3(x, top - .6f, min(hd, 6f)), .8f, steel)
            }
            for (z in listOf(-min(hd, 6f) + .6f, min(hd, 6f) - .6f)) {
                val a = v3(-hw + 1f, top - .3f, z); val b = v3(hw - 1f, top - .3f, z)
                m.beam(a, b, .25f, line)
                hang(a, b, max(2, (w / 18f).toInt()))
            }
            m.beam(v3(0f, 0f, 0f), v3(0f, top - .4f, 0f), .7f, YardPics.swatch(Paint.WOOD.b))
        }
    }

    /** Washing: shirts, towels, socks, a dress, pegged at the top (cut out round them). */
    private fun clothes(): List<PixImg> {
        val cols = listOf(Paint.c(0xE63F38), Paint.c(0x416EB0), Paint.c(0xF7D650), Paint.c(0xFFFFFF), Paint.c(0x2E9460), Paint.c(0xF48CB0), Paint.c(0xC870D8))
        val out = ArrayList<PixImg>()
        for ((i, c) in cols.withIndex()) {
            val dark = Paint.c(0x3A3A50)
            out += YardPics.pic("shirt/$i", 10, 10) { im ->
                for (y in 0 until 10) for (x in 0 until 10) im[x, y] = 0
                im.rect(2, 0, 7, 9, c); im.rect(0, 0, 9, 3, c); im[4, 0] = dark; im[5, 0] = dark
                im.vline(2, 4, 9, dark); im.vline(7, 4, 9, dark); im.hline(2, 7, 9, dark); im.hline(0, 1, 3, dark); im.hline(8, 9, 3, dark)
            }
            out += YardPics.pic("towel/$i", 8, 12) { im -> im.fill(c); im.hline(0, 7, 9, Paint.WHITE.l); im.hline(0, 7, 10, Paint.WHITE.l); im.hline(0, 7, 11, dark) }
            if (i % 2 == 0) out += YardPics.pic("sock/$i", 4, 6) { im -> for (y in 0 until 6) for (x in 0 until 4) im[x, y] = 0; im.rect(0, 0, 1, 5, c); im.rect(0, 4, 3, 5, c); im[3, 5] = dark }
        }
        return out
    }

    private fun pond(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val hw = w / 2f - 1f; val hd = d / 2f - 1f
        // an irregular oval of water, stones round its edge, lily pads and reeds
        val n = 14
        val rnd = Paint.Rnd(461 + v)
        val rad = (0 until n).map { .8f + rnd.next() * .14f }
        val pts = FloatArray(n * 2)
        for (k in 0 until n) { val a = 2f * PIf * k / n; pts[k * 2] = sin(a) * hw * rad[k]; pts[k * 2 + 1] = cos(a) * hd * rad[k] }
        o.poly("pondwater", pts, .5f)
        val stone = listOf(YardPics.swatch(YP.STONE.l), YardPics.swatch(YP.STONE.b), YardPics.swatch(YP.STONE.d))
        for (k in 0 until n * 2) {
            val a = 2f * PIf * k / (n * 2)
            val rk = rad[(k / 2) % n]
            val sx = sin(a) * hw * (rk + .06f).coerceAtMost(.9f); val sz = cos(a) * hd * (rk + .06f).coerceAtMost(.9f)
            val s = 2f + rnd.next() * .8f
            m.ball(sx, .4f, sz, s, 1.6f + rnd.next(), s, 5, 2, stone, k)
        }
        val pad = YardPics.pic("lily", 6, 6) { im -> val c = 2.5f; for (y in 0 until 6) for (x in 0 until 6) { val dd = (x - c) * (x - c) + (y - c) * (y - c); im[x, y] = if (dd > 7f) 0 else if (x >= 3 && y < 3 && x - 3 <= 2 - y) 0 else if (dd > 4f) Paint.LEAF.d else Paint.LEAF.l }; im[1, 1] = Paint.c(0xF48CB0) }
        for (k in 0..3) {
            val x = (rnd.next() - .5f) * hw; val z = (rnd.next() - .5f) * hd
            m.quad(v3(x - 2.4f, .6f, z - 2.4f), v3(x + 2.4f, .6f, z - 2.4f), v3(x + 2.4f, .6f, z + 2.4f), v3(x - 2.4f, .6f, z + 2.4f), pad)
        }
        val reed = YardPics.pic("reeds", 8, 16) { im ->
            for (y in 0 until 16) for (x in 0 until 8) im[x, y] = 0
            for (x in 0 until 8 step 2) { val t = (x * 5) % 6; for (y in t until 16) im[x, y] = if (y == t) YP.MEADOW.h else YP.MEADOW.d }
            im.rect(2, 2, 2, 5, Paint.c(0x5E4030)); im.rect(6, 3, 6, 6, Paint.c(0x5E4030))
        }
        for (k in 0..2) {
            val a = PIf * .7f + k * .35f
            val x = sin(a) * hw * .78f; val z = cos(a) * hd * .78f
            for (q in 0..1) { val aa = q * PIf / 2f + k; val dx = cos(aa) * 3.2f; val dz = sin(aa) * 3.2f; m.quad(v3(x - dx, 13f, z - dz), v3(x + dx, 13f, z + dz), v3(x + dx, 0f, z + dz), v3(x - dx, 0f, z - dz), reed) }
        }
    }

    private fun pool(o: Out, w: Float, d: Float, v: Int) {
        val m = o.m; o.any = true
        val hw = w / 2f; val hd = d / 2f
        val cope = 3.2f
        // the water a little under the coping; rounded ends for the kidney-ish look (2), square (0, 1)
        val pts: FloatArray = if (v % 3 == 2) {
            val list = ArrayList<Float>()
            val rr = min(hw, hd) - cope
            val k = 6
            for (i in 0..k) { val a = -PIf / 2f + PIf * i / k; list += hw - cope - rr + cos(a) * rr; list += sin(a) * rr * ((hd - cope) / rr) }
            for (i in 0..k) { val a = PIf / 2f + PIf * i / k; list += -hw + cope + rr + cos(a) * rr; list += sin(a) * rr * ((hd - cope) / rr) }
            list.toFloatArray()
        } else floatArrayOf(-hw + cope, -hd + cope, hw - cope, -hd + cope, hw - cope, hd - cope, -hw + cope, hd - cope)
        o.poly("water", pts, .7f)
        // the coping round it: a ring of white stone blocks following the water's edge
        val c = YP.RENDER
        val n = pts.size / 2
        val top = YardPics.pic("coping", 8, 4) { im -> im.fill(c.h); im.hline(0, 7, 3, c.b); im.vline(7, 0, 3, c.d) }
        val sideImg = YardPics.swatch(c.b)
        val inner = YardPics.pic("poolwall", 8, 3) { im -> im.fill(Paint.c(0x5CB0EC)); im.hline(0, 7, 0, Paint.c(0x2D4C8E)); for (x in 0 until 8 step 2) im[x, 0] = Paint.c(0xF0F0F8) }
        // the outer edge: the water polygon pushed out by the coping's width
        fun out(i: Int): FloatArray {
            val x = pts[i * 2]; val z = pts[i * 2 + 1]
            val px = pts[((i - 1 + n) % n) * 2]; val pz = pts[((i - 1 + n) % n) * 2 + 1]
            val nx2 = pts[((i + 1) % n) * 2]; val nz2 = pts[((i + 1) % n) * 2 + 1]
            // the normals of both edges (outwards: the polygon runs counter-clockwise seen from above? use the centre)
            fun nrm2(ax: Float, az: Float, bx: Float, bz: Float): FloatArray { val dx = bx - ax; val dz = bz - az; val l = sqrt(dx * dx + dz * dz).coerceAtLeast(1e-4f); var ox = dz / l; var oz = -dx / l; if (ox * (ax + bx) + oz * (az + bz) < 0f) { ox = -ox; oz = -oz }; return floatArrayOf(ox, oz) }
            val n1 = nrm2(px, pz, x, z); val n2 = nrm2(x, z, nx2, nz2)
            var mx = n1[0] + n2[0]; var mz = n1[1] + n2[1]; val ml = sqrt(mx * mx + mz * mz).coerceAtLeast(1e-4f); mx /= ml; mz /= ml
            val cosH = (mx * n1[0] + mz * n1[1]).coerceAtLeast(.5f)
            return floatArrayOf(x + mx * cope / cosH, z + mz * cope / cosH)
        }
        val yT = 1.6f
        for (i in 0 until n) {
            val j = (i + 1) % n
            val a = out(i); val b = out(j)
            val ai = v3(pts[i * 2], yT, pts[i * 2 + 1]); val bi = v3(pts[j * 2], yT, pts[j * 2 + 1])
            m.face(listOf(ai, bi, v3(b[0], yT, b[1]), v3(a[0], yT, a[1])), top, v3(0f, -10f, 0f))
            m.face(listOf(v3(a[0], yT, a[1]), v3(b[0], yT, b[1]), v3(b[0], 0f, b[1]), v3(a[0], 0f, a[1])), sideImg, v3(0f, yT / 2f, 0f))
            // the inside wall down to the water, its tile band
            m.face(listOf(ai, bi, v3(pts[j * 2], .7f, pts[j * 2 + 1]), v3(pts[i * 2], .7f, pts[i * 2 + 1])), inner, v3(0f, 0f, 0f), fit = true)
        }
        // the ladder at one end, the lights in the walls (glow at night)
        val ch = YardPics.swatch(Paint.STEEL.h)
        val lx = hw * .3f; val lz = -hd + cope
        for (dx in listOf(-2.4f, 2.4f)) {
            m.beam(v3(lx + dx, .8f, lz + 1.2f), v3(lx + dx, 7f, lz + 1.2f), .5f, ch)
            m.beam(v3(lx + dx, 7f, lz + 1.2f), v3(lx + dx, 7f, lz - 1.6f), .5f, ch)
            m.beam(v3(lx + dx, 7f, lz - 1.6f), v3(lx + dx, yT, lz - 2.4f), .5f, ch)
        }
        val light = YardPics.pic("poollight", 2, 2) { im -> im.fill(Paint.c(0xD2F2FF)); im.glow(0, 0, 1, 1, 255) }
        for (s in listOf(-1f, 1f)) {
            val z = s * (hd - cope - .05f)
            m.quad(v3(-2f, 1.4f, z), v3(2f, 1.4f, z), v3(2f, .8f, z), v3(-2f, .8f, z), light)
        }
        // a glow on the water at night: a lit patch over each light
        val patch = YardPics.pic("poolglow", 4, 4) { im -> im.fill(Paint.c(0x9ADCF8)); im.glow(0, 0, 3, 3, 150) }
        for (s in listOf(-1f, 1f)) {
            val z = s * (hd - cope - 5f)
            m.quad(v3(-5f, .75f, z - 4f), v3(5f, .75f, z - 4f), v3(5f, .75f, z + 4f), v3(-5f, .75f, z + 4f), patch)
        }
    }
}
