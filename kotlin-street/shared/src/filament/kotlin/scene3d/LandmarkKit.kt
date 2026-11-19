package com.lifemax.rpg.feature.habits.presentation.scene3d

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Hand-made landmark models (glTF .glb, docs/LANDMARKS_GLB.md and docs/CHATGPT_MODEL_GUIDE.md) built for the town:
 * [GlbFile] reads the file, [LandmarkKit.build] turns it into the same [CinemaModel] the cinema and the stores draw
 * through ([CinemaLayer]: our lit material, nearest-sampled, repeat-wrapped), merged by material, with:
 *
 * - metres turned into street units ([UNITS_PER_METRE]: one 0.0388 m texel is the buildings' [TEXEL]),
 * - the model's +Z entrance kept as the model frame's +z (out towards the road): the frame stands in a slot as the
 *   stores' does, so the street's mirrored world is the frame's business, not the model's,
 * - `glass_*` materials as lit windows (the cinema's warm lobby glass at night, the sky in them by day), `glow_*` and
 *   `sign_*` as signs lit from dusk in their own colours (where their emissive picture is bright), a real downward
 *   light under each lit ceiling panel,
 * - the `collision` mesh (extras render:false) never drawn but turned into the walk's boxes, its boxes tagged from
 *   model-info.json (lot, attachment) so dropping the `lot` group or hiding a node drops their boxes too,
 * - `door_*` nodes as the walk's doors (each facing its node's +Z),
 * - the `sign_price` panel's picture drawn from the town's year ([GasPrices]), at the art's pixel scale,
 * - the catalogue's re-skins: a material's picture swapped for one of our swatches ([LandmarkSpec.swatches]).
 */

/** A landmark model in the catalogue (assets/landmarks/landmarks.json). */
data class LandmarkSpec(
    /** `landmark_glb_<name>`: the landmark kind the town generator places. */
    val kind: String,
    val name: String,
    /** The .glb, relative to the catalogue's folder. */
    val file: String,
    /** Its model-info.json (relative), or null. */
    val info: String?,
    /** What it is (gas_station, diner, bowling): the generator's business kind. */
    val role: String,
    /** The years it may be built in (its design), and the year it is built for in the preview. */
    val from: Int, val to: Int, val built: Int,
    /** The lot (width along the street x depth back), metres; the building's own walls. */
    val lotW: Float, val lotD: Float, val buildingW: Float, val buildingD: Float,
    /** The optional lot group's node ("lot"). */
    val lot: String = "lot",
    /** Material name -> swatch id: the model's picture replaced by ours, at the same texel size. */
    val swatches: Map<String, String> = emptyMap(),
    /** The price panel, if it has one. */
    val price: PriceBoard? = null,
    /** Nodes hidden by default (a state not shown: an open door panel...). */
    val hidden: Set<String> = emptySet(),
    /** What its see-through glass shows of the room: "shelves" (a shop's goods) or "hall" (a lit wall, a counter, lamps). */
    val room: String = "shelves",
    /** The model's painted variants (the same model, another name on it: the works'), files as [file]; empty: [file] only. */
    val variants: List<String> = emptyList(),
) {
    /** The file of variant [v] (the town's pick: street.json's glbLandmarks `variant`). */
    fun fileOf(v: Int): String = if (variants.isEmpty()) file else variants[Math.floorMod(v, variants.size)]

    /** The lot in street units. */
    val width: Float get() = lotW * LandmarkKit.UNITS_PER_METRE
    val depth: Float get() = lotD * LandmarkKit.UNITS_PER_METRE

    /** Whether it may stand in [year] (built then or before; later years keep it, as the simulation decides). */
    fun designFits(year: Int) = year in from..to

    companion object {
        /** The catalogue's entries (its JSON text). */
        fun catalogue(json: String): List<LandmarkSpec> {
            val root = org.json.JSONObject(json)
            val a = root.getJSONArray("landmarks")
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val era = o.getJSONObject("era"); val fp = o.getJSONObject("footprint")
                fun pair(k: String) = fp.getJSONArray(k).let { it.getDouble(0).toFloat() to it.getDouble(1).toFloat() }
                val (lw, ld) = pair("lot"); val (bw, bd) = pair("building")
                val sw = o.optJSONObject("swatches")?.let { s -> s.keys().asSequence().associateWith { s.getString(it) } } ?: emptyMap()
                val hid = o.optJSONArray("hidden")?.let { h -> (0 until h.length()).map { h.getString(it) }.toSet() } ?: emptySet()
                LandmarkSpec(o.getString("kind"), o.getString("name"), o.getString("file"), o.optString("info").ifBlank { null }, o.getString("role"),
                    era.getInt("from"), era.getInt("to"), era.optInt("built", era.getInt("from")), lw, ld, bw, bd, o.optString("lot", "lot"), sw,
                    o.optJSONObject("price")?.let { PriceBoard.parse(it) }, hid, o.optString("room", "shelves"),
                    o.optJSONArray("variants")?.let { v -> (0 until v.length()).map { v.getString(it) } } ?: emptyList())
            }
        }
    }
}

/**
 * A price panel's picture ([LandmarkSpec.price]): [artW] x [artH] art pixels (2 x 2 texels each) of [paper], one row of
 * digits in [ink] for each of [grades] at [rows] (art pixels from the top), right-aligned (or from [left]), in the
 * 5x7 or the narrow 3x7 digits ([font] "5x7"), or the small 3x5 ("3x5") of a hand-lettered board.
 */
data class PriceBoard(val node: String, val grades: List<String>, val artW: Int, val artH: Int, val paper: Int, val ink: Int, val rows: List<Int>, val left: Int?, val font: String) {
    /** The picture's texels for [year] (ARGB, rows top first, [artW] * 2 wide). */
    fun texels(year: Int, seed: Int): IntArray = picture(year, seed).px

    /** The picture for [year] (texels, ARGB), [seed] the town's (a few cents up or down). */
    internal fun picture(year: Int, seed: Int): PixImg {
        val im = PixImg(artW * 2, artH * 2)
        im.fill(paper)
        for ((k, g) in grades.withIndex()) {
            val text = GasPrices.text(GasPrices.price(year, g, seed))
            val glyphs = when { font == "3x5" -> SMALL; text.length <= 4 -> WIDE; else -> NARROW }
            val width = text.sumOf { (glyphs[it]?.get(0)?.length ?: 3) + 1 } - 1
            var x = left ?: (artW - width - 1)
            val y = rows.getOrElse(k) { k * 14 }
            for (ch in text) {
                val g2 = glyphs[ch] ?: continue
                for ((j, line) in g2.withIndex()) for ((i, p) in line.withIndex()) if (p == '1') im.rect((x + i) * 2, (y + j) * 2, (x + i) * 2 + 1, (y + j) * 2 + 1, ink)
                x += g2[0].length + 1
            }
        }
        return im
    }

    companion object {
        fun parse(o: org.json.JSONObject): PriceBoard {
            val gr = o.getJSONArray("grades").let { a -> (0 until a.length()).map { a.getString(it) } }
            val rows = o.getJSONArray("rows").let { a -> (0 until a.length()).map { a.getInt(it) } }
            val art = o.getJSONArray("art")
            fun col(k: String) = (0xFF000000L or o.getString(k).removePrefix("#").toLong(16)).toInt()
            return PriceBoard(o.optString("node", "sign_price"), gr, art.getInt(0), art.getInt(1), col("paper"), col("ink"), rows,
                if (o.has("left")) o.getInt("left") else null, o.optString("font", "5x7"))
        }

        private val WIDE = mapOf(
            '0' to listOf("01110", "11011", "11111", "11011", "11011", "11011", "01110"), '1' to listOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
            '2' to listOf("01110", "11011", "00011", "00110", "01100", "11000", "11111"), '3' to listOf("11110", "00011", "00011", "01110", "00011", "00011", "11110"),
            '4' to listOf("11011", "11011", "11011", "11111", "00011", "00011", "00011"), '5' to listOf("11111", "11000", "11000", "11110", "00011", "00011", "11110"),
            '6' to listOf("01111", "11000", "11000", "11110", "11011", "11011", "01110"), '7' to listOf("11111", "00011", "00011", "00110", "00100", "01100", "01100"),
            '8' to listOf("01110", "11011", "11011", "01110", "11011", "11011", "01110"), '9' to listOf("01110", "11011", "11011", "01111", "00011", "00011", "11110"),
            '.' to listOf("00", "00", "00", "00", "00", "11", "11"),
        )
        private val NARROW = mapOf(
            '0' to listOf("111", "101", "101", "101", "101", "101", "111"), '1' to listOf("010", "110", "010", "010", "010", "010", "111"),
            '2' to listOf("111", "001", "001", "111", "100", "100", "111"), '3' to listOf("111", "001", "001", "111", "001", "001", "111"),
            '4' to listOf("101", "101", "101", "111", "001", "001", "001"), '5' to listOf("111", "100", "100", "111", "001", "001", "111"),
            '6' to listOf("111", "100", "100", "111", "101", "101", "111"), '7' to listOf("111", "001", "001", "010", "010", "010", "010"),
            '8' to listOf("111", "101", "101", "111", "101", "101", "111"), '9' to listOf("111", "101", "101", "111", "001", "001", "111"),
            '.' to listOf("0", "0", "0", "0", "0", "1", "1"),
        )
        private val SMALL = mapOf(
            '0' to listOf("111", "101", "101", "101", "111"), '1' to listOf("010", "110", "010", "010", "111"), '2' to listOf("111", "001", "111", "100", "111"),
            '3' to listOf("111", "001", "111", "001", "111"), '4' to listOf("101", "101", "111", "001", "001"), '5' to listOf("111", "100", "111", "001", "111"),
            '6' to listOf("111", "100", "111", "101", "111"), '7' to listOf("111", "001", "010", "010", "010"), '8' to listOf("111", "101", "111", "101", "111"),
            '9' to listOf("111", "101", "111", "001", "111"), '.' to listOf("0", "0", "0", "0", "1"),
        )
    }
}

/**
 * What a gallon cost (US dollars, nominal), by year: the national average for regular, roughly (a simple table, eased
 * between its years; 3% a year past its end), a premium grade and diesel from it, and a town's few cents either way.
 * Stations of the fifties and sixties priced in cents with a tenth ("29.9"), and so do these signs below a dollar.
 */
object GasPrices {
    /** (year, regular) pairs. */
    val TABLE: List<Pair<Int, Float>> = listOf(
        1935 to .19f, 1940 to .18f, 1945 to .21f, 1950 to .27f, 1955 to .29f, 1960 to .31f, 1965 to .31f, 1970 to .36f, 1973 to .39f,
        1974 to .53f, 1975 to .57f, 1978 to .63f, 1979 to .86f, 1980 to 1.19f, 1981 to 1.31f, 1982 to 1.22f, 1985 to 1.20f, 1986 to .93f,
        1990 to 1.16f, 1995 to 1.15f, 1998 to 1.06f, 2000 to 1.51f, 2003 to 1.59f, 2005 to 2.30f, 2006 to 2.59f, 2007 to 2.80f, 2008 to 3.27f,
        2009 to 2.35f, 2010 to 2.79f, 2011 to 3.53f, 2012 to 3.64f, 2014 to 3.36f, 2015 to 2.45f, 2016 to 2.14f, 2018 to 2.72f, 2019 to 2.60f,
        2020 to 2.17f, 2021 to 3.01f, 2022 to 3.95f, 2023 to 3.52f, 2024 to 3.30f, 2025 to 3.15f,
    )

    /** Regular in [year] (no town's offset). */
    fun regular(year: Int): Float {
        val t = TABLE
        if (year <= t.first().first) return t.first().second
        if (year >= t.last().first) return t.last().second * Math.pow(1.03, (year - t.last().first).toDouble()).toFloat()
        val i = t.indexOfLast { it.first <= year }
        val (y0, p0) = t[i]; val (y1, p1) = t[i + 1]
        return p0 + (p1 - p0) * (year - y0) / (y1 - y0).toFloat()
    }

    /** [grade] (regular, plus, premium, diesel) in [year] at a town of [seed], rounded to a tenth of a cent ending in 9. */
    fun price(year: Int, grade: String, seed: Int): Float {
        val r = regular(year)
        val p = when (grade) {
            "plus" -> r + if (year < 1980) .02f else .10f + (r - 1f).coerceAtLeast(0f) * .06f
            "premium" -> r + if (year < 1980) .04f else .20f + (r - 1f).coerceAtLeast(0f) * .10f
            "diesel" -> r * (if (year < 2004) .98f else 1.12f) + if (year in 2021..2023) .35f else 0f
            else -> r
        }
        // the town's few cents either way, and the price's 9/10 of a cent
        val k = ((seed * 31 + grade.hashCode()).let { it xor (it ushr 13) } and 0x7fffffff) % 5 - 2
        val cents = (p * 100f).roundToInt() + k * (if (year < 1975) 1 else 2)
        return (cents - 1).coerceAtLeast(1) / 100f + .009f
    }

    /** How a sign spells [price]: dollars ("3.49") or, below a dollar on a sign in tenths, cents ("29.9"). */
    fun text(price: Float, tenths: Boolean = true): String {
        val c10 = (price * 1000f).roundToInt()
        return if (price < 1f && tenths) "${c10 / 10}.${c10 % 10}" else "%.2f".format(java.util.Locale.ROOT, (c10 / 10) / 100f)
    }
}

/** A door of a landmark model in its frame: where it is (street units) and which way it faces out. */
class LandmarkDoor(val name: String, val x: Float, val z: Float, val outX: Float, val outZ: Float)

/**
 * A landmark built from its .glb: the parts ([model], the cinema's kind, in the frame: x along, y up, z out, the
 * building's front edge at z 0), the walk's boxes ([solids]: x0, z0, x1, z1 each, in the frame), its [doors], and the
 * lot's front edge ([front]: z of the most forward ground; the town puts it on the plot's front line).
 */
class LandmarkModel(val spec: LandmarkSpec, val model: CinemaModel, val solids: FloatArray, val doors: List<LandmarkDoor>, val front: Float, val names: List<String>)

object LandmarkKit {
    /** Street units in a metre: the guide's 0.0388 m texel is the buildings' [TEXEL]. */
    val UNITS_PER_METRE: Float = TEXEL / 0.0388f

    /** Below this (street units) a collision box is underfoot (a step, a lane bed); above [HEAD] it is overhead. */
    const val STEP = 14f
    const val HEAD = 34f

    /**
     * [glb] built as [spec] for the town's [year] ([seed] the town's: its prices): [keepLot] the optional lot group,
     * [hide] more nodes not drawn (and their collision boxes dropped), [info] the model-info.json text (tags the
     * collision boxes) or null.
     */
    fun build(glb: GlbFile, spec: LandmarkSpec, year: Int, seed: Int = 0, keepLot: Boolean = true, hide: Set<String> = emptySet(), info: String? = null): LandmarkModel {
        val U = UNITS_PER_METRE
        val hidden = spec.hidden + hide
        val lotNode = glb.node(spec.lot)
        fun skipped(n: GlbFile.Node): Boolean {
            val path = glb.path(n)
            if (path.any { it in hidden }) return true
            if (!keepLot && lotNode != null && glb.under(n, lotNode)) return true
            return false
        }
        fun collisionOnly(n: GlbFile.Node) = n.name == "collision" || n.extras?.optBoolean("collision_only") == true || n.extras?.has("render") == true && !n.extras.optBoolean("render", true)
        // the price panel's material, drawn for the year
        val priceMat = spec.price?.let { pb -> glb.node(pb.node)?.takeIf { it.mesh >= 0 }?.let { n -> glb.primitives(n.mesh).firstOrNull()?.material } }
        // every drawn triangle, by material
        class Acc { val pos = ArrayList<Float>(); val nrm = ArrayList<Float>(); val uv = ArrayList<Float>(); val idx = ArrayList<Int>() }
        val acc = LinkedHashMap<Int, Acc>()
        val colTris = ArrayList<FloatArray>()
        val tmp = FloatArray(3)
        for (n in glb.nodes) {
            if (n.mesh < 0) continue
            val col = collisionOnly(n)
            if (!col && skipped(n)) continue
            val flip = GlbFile.mirrors(n.world)
            for (p in glb.primitives(n.mesh)) {
                val nv = p.pos.size / 3
                val wp = FloatArray(nv * 3)
                for (v in 0 until nv) { GlbFile.point(n.world, p.pos[v * 3], p.pos[v * 3 + 1], p.pos[v * 3 + 2], wp, v * 3); for (c in 0..2) wp[v * 3 + c] *= U }
                if (col) { colTris += FloatArray(p.idx.size * 3) { k -> wp[p.idx[k / 3] * 3 + k % 3] }; continue }
                val a = acc.getOrPut(p.material) { Acc() }
                val base = a.pos.size / 3
                for (v in 0 until nv) {
                    for (c in 0..2) a.pos += wp[v * 3 + c]
                    if (p.nrm != null) {
                        GlbFile.dir(n.world, p.nrm[v * 3], p.nrm[v * 3 + 1], p.nrm[v * 3 + 2], tmp, 0)
                        val l = sqrt(tmp[0] * tmp[0] + tmp[1] * tmp[1] + tmp[2] * tmp[2]).coerceAtLeast(1e-6f)
                        for (c in 0..2) a.nrm += tmp[c] / l
                    } else for (c in 0..2) a.nrm += if (c == 1) 1f else 0f
                    a.uv += p.uv?.get(v * 2) ?: 0f; a.uv += p.uv?.get(v * 2 + 1) ?: 0f
                }
                var t = 0
                while (t + 2 < p.idx.size) {
                    if (flip) { a.idx += base + p.idx[t]; a.idx += base + p.idx[t + 2]; a.idx += base + p.idx[t + 1] } else for (k in 0..2) a.idx += base + p.idx[t + k]
                    t += 3
                }
            }
        }
        // the lot's ground on the town's: a forecourt laid below the model's zero (a kerb's height down, as these
        // models draw it) would sink under the town's ground, so the whole model is lifted until its lowest broad
        // ground (10 m2 or more of it facing up) stands a hair over the town's
        val lift = groundLift(acc.mapNotNull { (mi, a) -> if (glb.materials.getOrNull(mi)?.name?.startsWith("ground_") == true) a.pos.toFloatArray() to a.idx.toIntArray() else null })
        if (lift != 0f) {
            for (a in acc.values) for (i in 1 until a.pos.size step 3) a.pos[i] = a.pos[i] + lift
            for (t in colTris) for (i in 1 until t.size step 3) t[i] += lift
        }
        val parts = ArrayList<CinemaPart>(); val names = ArrayList<String>()
        val lights = ArrayList<CinemaLight>()
        var fx0 = Float.MAX_VALUE; var fz0 = Float.MAX_VALUE; var fx1 = -Float.MAX_VALUE; var fz1 = -Float.MAX_VALUE; var top = 0f
        var front = 0f
        for ((mi, a) in acc) {
            val mat = glb.materials.getOrNull(mi)
            val name = mat?.name ?: "untitled"
            val role = roleOf(name)
            val pos = a.pos.toFloatArray(); val nrm = a.nrm.toFloatArray(); var uv = a.uv.toFloatArray(); val idx = a.idx.toIntArray()
            // the picture: the model's own, ours in its place, or the year's prices
            var img: PixImg = mat?.let { pictureOf(glb, it) } ?: PixImg(1, 1).also { it.fill(-1) }
            val swatch = spec.swatches[name]
            if (swatch != null) {
                val s = Swatches.get(swatch)
                val kx = img.w.toFloat() / s.w; val ky = img.h.toFloat() / s.h
                uv = FloatArray(uv.size) { if (it % 2 == 0) uv[it] * kx else uv[it] * ky }
                img = s
            }
            if (mi == priceMat && spec.price != null) img = spec.price.picture(year, seed)
            // water (a pool's surface) is drawn solid: its see-through texels over deep water, and laid flush with the town's
            // ground (a pool is sunk below the model's zero, which the town's grass in the lot's hole would hide)
            if (name.startsWith("water_")) {
                for (i in img.px.indices) img.px[i] = blendOver(img.px[i], DEEP_WATER)
                for (i in 1 until pos.size step 3) pos[i] = WATER_LEVEL
            }
            when (role) {
                CinemaPart.Role.WINDOWS -> glass(img, spec.room == "hall", if (swatch != null) null else mat?.let { m -> if (m.emissiveImage >= 0) glb.image(m.emissiveImage) else null })
                CinemaPart.Role.SIGN -> glowMask(img, if (mi == priceMat) null else mat?.let { m -> if (m.emissiveImage >= 0) glb.image(m.emissiveImage) else null }, mat?.emissiveFactor ?: floatArrayOf(1f, 1f, 1f), false)
                else -> {}
            }
            // flat normals where the file gave none (a zero normal)
            val nv = pos.size / 3
            val tan = FloatArray(nv * 3)
            for (v in 0 until nv) {
                val ny = nrm[v * 3 + 1]
                if (abs(ny) > .7f) { tan[v * 3] = 1f } else { val l = sqrt(nrm[v * 3] * nrm[v * 3] + nrm[v * 3 + 2] * nrm[v * 3 + 2]).coerceAtLeast(1e-6f); tan[v * 3] = -nrm[v * 3 + 2] / l; tan[v * 3 + 2] = nrm[v * 3] / l }
            }
            val b = FloatArray(6).also { it[0] = Float.MAX_VALUE; it[1] = Float.MAX_VALUE; it[2] = Float.MAX_VALUE; it[3] = -Float.MAX_VALUE; it[4] = -Float.MAX_VALUE; it[5] = -Float.MAX_VALUE }
            for (v in 0 until nv) for (c in 0..2) { b[c] = min(b[c], pos[v * 3 + c]); b[c + 3] = max(b[c + 3], pos[v * 3 + c]) }
            fx0 = min(fx0, b[0]); fz0 = min(fz0, b[2]); fx1 = max(fx1, b[3]); fz1 = max(fz1, b[5]); top = max(top, b[4])
            if (name.startsWith("ground_")) front = max(front, b[5])
            val mesh = PropMesh(pos, uv, nrm, tan, idx, img.w, img.h, img.px, if (img.glows) img.mask else null, b)
            val flat = name.startsWith("ground_") || name.startsWith("floor_") || name.startsWith("water_")
            parts += CinemaPart(mesh, casts = !flat, tiled = true, role = role)
            names += name
            if (role == CinemaPart.Role.SIGN && mi != priceMat) lights += ceilingLights(pos, nrm, idx)
        }
        if (front == 0f) front = fz1
        // a pool of light on the ground under each lit ceiling (as the stores' lots: the light's speckle on the ground)
        val grounds = parts.zip(names).filter { (_, n) -> n.startsWith("ground_") }.map { it.first.mesh }
        pools(lights, grounds)?.let { parts += it; names += "glow_ground_pools" }
        val tags = info?.let { collisionTags(it) } ?: emptyList()
        // the model's own boxes, and what its drawn things put in a walker's way that the boxes leave out (the guide's
        // models box a pump's plinth, not the pump): the drawn parts cut at knee to head height as the stores' are,
        // the inside not filled (the boxes keep their doorways), glass left out (doors stand open in the boxes)
        val solids = solidsOf(colTris, tags, keepLot, hidden, lift) + drawnSolids(parts.zip(names).filter { (_, n) ->
            !n.startsWith("ground_") && !n.startsWith("floor_") && !n.startsWith("roof_") && !n.startsWith("glass_") }.map { it.first }, fx0, fz0, fx1, fz1)
        // (empty nodes only: a door's panel group, `door_panel_*`, is geometry, not an entrance)
        // (a door the info file marks `"external": false` joins two rooms: no entrance from the street, the interiors job's)
        val inner = info?.let { innerDoors(it) } ?: emptySet()
        val doors = glb.nodes.filter { n -> n.name.startsWith("door_") && n.mesh < 0 && glb.nodes.none { it.parent == n.index } && !skipped(n) && n.name !in inner }.map { n ->
            GlbFile.dir(n.world, 0f, 0f, 1f, tmp, 0)
            val l = sqrt(tmp[0] * tmp[0] + tmp[2] * tmp[2]).coerceAtLeast(1e-6f)
            LandmarkDoor(n.name, n.x * U, n.z * U, tmp[0] / l, tmp[2] / l)
        }
        val m = CinemaModel(CinemaEra.of(year), parts, floatArrayOf(fx0, fz0, fx1, fz1), top, spec.buildingW * U, spec.buildingD * U, lights)
        return LandmarkModel(spec, m, solids, doors, front, names)
    }

    /** How far to lift a model so its lowest broad up-facing ground ([grounds]: positions, indices) is [GROUND] up. */
    private fun groundLift(grounds: List<Pair<FloatArray, IntArray>>): Float {
        val area = HashMap<Int, Float>()
        for ((pos, idx) in grounds) {
            var t = 0
            while (t + 2 < idx.size) {
                val a = idx[t] * 3; val b = idx[t + 1] * 3; val c = idx[t + 2] * 3; t += 3
                val ux = pos[b] - pos[a]; val uy = pos[b + 1] - pos[a + 1]; val uz = pos[b + 2] - pos[a + 2]
                val vx = pos[c] - pos[a]; val vy = pos[c + 1] - pos[a + 1]; val vz = pos[c + 2] - pos[a + 2]
                val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
                val l = sqrt(nx * nx + ny * ny + nz * nz)
                if (l < 1e-6f || abs(ny) < .95f * l) continue
                val y = ((pos[a + 1] + pos[b + 1] + pos[c + 1]) / 3f * 4f).roundToInt()
                area[y] = (area[y] ?: 0f) + l / 2f
            }
        }
        val broad = 10f * UNITS_PER_METRE * UNITS_PER_METRE
        val low = area.filter { it.value >= broad }.keys.minOrNull() ?: return 0f
        return if (low / 4f < GROUND) GROUND - low / 4f else 0f
    }

    /** Where a landmark's lowest broad ground stands (street units over the town's ground). */
    const val GROUND = .3f

    /** A pool's water (`water_*`) is laid this high over the town's ground (under the paving's [GROUND]), and drawn over [DEEP_WATER]. */
    const val WATER_LEVEL = .15f
    /** An emission mask's lit texels: luminance over this (of 255; the funeral home's panes are 120-205, the door art 0). */
    private const val EMISSION_PANE = 40
    private const val DEEP_WATER = 0xFF1E4660.toInt()

    /** [c] (ARGB) made solid over the opaque [back]. */
    private fun blendOver(c: Int, back: Int): Int {
        val a = c ushr 24
        if (a >= 255) return c
        fun ch(s: Int) = (((c shr s) and 255) * a + ((back shr s) and 255) * (255 - a)) / 255
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** A material's role by its name (the guide's contract). */
    fun roleOf(name: String): CinemaPart.Role = when {
        name.startsWith("glass_") -> CinemaPart.Role.WINDOWS
        name.startsWith("glow_") || name.startsWith("sign_") -> CinemaPart.Role.SIGN
        else -> CinemaPart.Role.BODY
    }

    /** A material's base picture (its texture times its factor), or its factor as one texel. */
    private fun pictureOf(glb: GlbFile, m: GlbFile.Material): PixImg {
        val f = m.baseFactor
        fun srgb(l: Float) = (if (l <= .0031308f) l * 12.92f else 1.055f * Math.pow(l.toDouble(), 1 / 2.4).toFloat() - .055f).coerceIn(0f, 1f)
        if (m.baseImage < 0) {
            val c = ((f[3].coerceIn(0f, 1f) * 255).toInt() shl 24) or ((srgb(f[0]) * 255).toInt() shl 16) or ((srgb(f[1]) * 255).toInt() shl 8) or (srgb(f[2]) * 255).toInt()
            return PixImg(1, 1).also { it.fill(c) }
        }
        val src = glb.image(m.baseImage)
        val im = PixImg(src.w, src.h)
        val plain = f.all { it >= .999f }
        for (i in src.px.indices) {
            val p = src.px[i]
            im.px[i] = if (plain) p else {
                val a = ((p ushr 24) * f[3]).toInt(); val r = ((p shr 16 and 255) * srgb(f[0])).toInt(); val g = ((p shr 8 and 255) * srgb(f[1])).toInt(); val b = ((p and 255) * srgb(f[2])).toInt()
                (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return im
    }

    /**
     * Glass made solid (our material does not blend: see-through texels over a dark room) and marked to mirror the sky
     * by day and glow warm at night: its see-through texels, or (a pane painted opaque) all but its darkest frame texels,
     * or, when the material has an emissive picture of the same size (a mask: `glass_home` carries door art as well as
     * panes, its `pane-emission.png` is lit only where there is glass), exactly the texels that picture lights.
     */
    private fun glass(im: PixImg, hall: Boolean, emissive: Png.Image? = null) {
        // (a model that only copies its base picture as the emissive one has no mask: the pane rule below applies)
        val mask = emissive?.takeIf { it.w == im.w && it.h == im.h && it.px.indices.any { i -> it.px[i] != im.px[i] } }
        val lit = mask?.let { e -> BooleanArray(im.px.size) { val c = e.px[it]; ((c shr 16 and 255) * 3 + (c shr 8 and 255) * 4 + (c and 255)) / 8 > EMISSION_PANE } }
        val clear = BooleanArray(im.px.size) { (im.px[it] ushr 24) < 235 }
        val any = clear.any { it }
        val lum = IntArray(im.px.size) { val p = im.px[it]; (p shr 16 and 255) + (p shr 8 and 255) + (p and 255) }
        val frame = if (any) -1 else lum.sorted()[lum.size / 8].takeIf { it < 200 } ?: -1
        im.glow(0, 0, 0, 0, 0)
        for (i in im.px.indices) {
            val p = im.px[i]; val a = p ushr 24
            if (clear[i]) {
                val room = if (hall) hallBehind(i % im.w, i / im.w) else roomBehind(i % im.w, i / im.w)
                val r = ((p shr 16 and 255) * a + (room shr 16 and 255) * (255 - a)) / 255
                val g = ((p shr 8 and 255) * a + (room shr 8 and 255) * (255 - a)) / 255
                val b = ((p and 255) * a + (room and 255) * (255 - a)) / 255
                im.px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            val pane = if (lit != null) lit[i] else if (any) clear[i] else lum[i] > frame
            // the lamp's warmth over the room's own colours (blue a little over half), so a lit window shows its shelves
            im.mask[i] = if (pane) (255 shl 24) or (220 shl 16) or (255 shl 8) or 150 else 0
        }
    }

    /**
     * What a see-through pane shows of the room (our panes are solid): shelves of goods every 14 art pixels, a lit
     * edge on each, dim wall between, in whole art pixels.
     */
    private fun roomBehind(x: Int, y: Int): Int {
        val ax = x / 2; val ay = y / 2
        val band = Math.floorMod(ay, 14)
        val h = ((ax * 73856093) xor (ay * 19349663)).let { it xor (it ushr 15) } and 0xff
        return when {
            band == 0 -> (0xFF shl 24) or 0x707A8E
            band in 1..7 -> if (h < 120) GOODS[h % GOODS.size] else (0xFF shl 24) or 0x343A48
            band == 8 -> (0xFF shl 24) or 0x262B36
            else -> (0xFF shl 24) or 0x2E3442
        }
    }
    /** A hall's back wall through the glass: a lamp row, a warm wall, a counter or booth band low down (art pixels). */
    private fun hallBehind(x: Int, y: Int): Int {
        val ax = x / 2; val ay = Math.floorMod(y / 2, 40)
        return when {
            ay < 3 -> (0xFF shl 24) or 0x3A3440
            ay < 5 -> if (Math.floorMod(ax, 12) < 3) (0xFF shl 24) or 0xE8D8A8 else (0xFF shl 24) or 0x3A3440
            ay < 16 -> if (Math.floorMod(ax + ay, 7) == 0) (0xFF shl 24) or 0x6E5A50 else (0xFF shl 24) or 0x7A6458
            ay < 18 -> (0xFF shl 24) or 0xB0A898
            ay < 28 -> if (Math.floorMod(ax, 10) == 0) (0xFF shl 24) or 0x4A2226 else (0xFF shl 24) or 0x8E3A36
            else -> (0xFF shl 24) or 0x2A2630
        }
    }
    private val GOODS = intArrayOf(0xFFA14F44.toInt(), 0xFFD8B860.toInt(), 0xFF5A8E6A.toInt(), 0xFF4A6AA0.toInt(), 0xFFC8C0B0.toInt(), 0xFF8A5A8E.toInt())

    /** A sign's texels that shine: its emissive picture's bright texels (or all of it), in their own colours. */
    private fun glowMask(im: PixImg, emissive: Png.Image?, factor: FloatArray?, whole: Boolean) {
        im.glow(0, 0, 0, 0, 0)
        val strong = (factor?.maxOrNull() ?: 0f) > 0f
        for (i in im.px.indices) {
            val p = im.px[i]
            if (p ushr 24 < 128) { im.mask[i] = 0; continue }
            val e = if (emissive != null && emissive.w == im.w && emissive.h == im.h) emissive.px[i] else p
            val l = ((e shr 16 and 255) * 3 + (e shr 8 and 255) * 4 + (e and 255)) / 8
            val on = whole || (strong || emissive != null) && l > 150
            im.mask[i] = if (on) (255 shl 24) or (255 shl 16) or 255 else 0
        }
    }

    /** A real downward light under each lit ceiling (a sign part's faces looking down, a few metres up), one per 4 m cell. */
    private fun ceilingLights(pos: FloatArray, nrm: FloatArray, idx: IntArray): List<CinemaLight> {
        val cell = 4f * UNITS_PER_METRE
        val cells = HashMap<Long, FloatArray>()
        var t = 0
        while (t + 2 < idx.size) {
            val a = idx[t]; val b = idx[t + 1]; val c = idx[t + 2]; t += 3
            val ny = (nrm[a * 3 + 1] + nrm[b * 3 + 1] + nrm[c * 3 + 1]) / 3f
            val y = (pos[a * 3 + 1] + pos[b * 3 + 1] + pos[c * 3 + 1]) / 3f
            if (ny > -.8f || y < 60f) continue
            val x = (pos[a * 3] + pos[b * 3] + pos[c * 3]) / 3f; val z = (pos[a * 3 + 2] + pos[b * 3 + 2] + pos[c * 3 + 2]) / 3f
            val key = (kotlin.math.floor(x / cell).toLong() shl 32) or (kotlin.math.floor(z / cell).toLong() and 0xffffffffL)
            val s = cells.getOrPut(key) { FloatArray(4) }
            s[0] += x; s[1] += y; s[2] += z; s[3] += 1f
        }
        return cells.values.map { s -> CinemaLight(s[0] / s[3], s[1] / s[3] - 2f, s[2] / s[3], .95f, .96f, 1f, 60000f, 240f, down = true, cone = 1.3f) }
    }

    /**
     * The pools of light under [lights]: for each, the ground it falls on ([grounds]: the highest up-facing ground
     * triangle under it) gets a copy of itself laid a hair above, the ground's own texels at the ground's own places (so
     * by day it cannot be told from the ground), glowing from dusk in the lamp's colour over a dithered disc in whole art
     * pixels, strongest under the light. All pools share one picture (a cell each); null for none.
     */
    private fun pools(lights: List<CinemaLight>, grounds: List<PropMesh>): CinemaPart? {
        class Pool(val m: PropMesh, val y: Float, val iu: Int, val iv: Int, val cu: Float, val cv: Float, val U: FloatArray, val V: FloatArray)
        val radius = 2.8f * UNITS_PER_METRE
        val list = ArrayList<Pool>()
        for (l in lights) {
            val (m, t) = groundUnder(grounds, l.x, l.z, l.y - 30f) ?: continue
            val p = m.pos; val uv = m.uv; val i0 = m.idx[t]; val i1 = m.idx[t + 1]; val i2 = m.idx[t + 2]
            // the ground's picture as a function of x and z (u = a x + b z + c) from its triangle
            val x0 = p[i0 * 3]; val z0 = p[i0 * 3 + 2]; val x1 = p[i1 * 3]; val z1 = p[i1 * 3 + 2]; val x2 = p[i2 * 3]; val z2 = p[i2 * 3 + 2]
            val det = (x1 - x0) * (z2 - z0) - (x2 - x0) * (z1 - z0)
            if (abs(det) < 1e-3f) continue
            fun solve(w0: Float, w1: Float, w2: Float): FloatArray {
                val a = ((w1 - w0) * (z2 - z0) - (w2 - w0) * (z1 - z0)) / det
                val b = ((x1 - x0) * (w2 - w0) - (x2 - x0) * (w1 - w0)) / det
                return floatArrayOf(a, b, w0 - a * x0 - b * z0)
            }
            val U = solve(uv[i0 * 2], uv[i1 * 2], uv[i2 * 2]); val V = solve(uv[i0 * 2 + 1], uv[i1 * 2 + 1], uv[i2 * 2 + 1])
            // (a ground laid straight only, u along x and v along z, as these models lay their lots)
            if (abs(U[1]) > 1e-4f || abs(V[0]) > 1e-4f || abs(U[0]) < 1e-6f || abs(V[1]) < 1e-6f) continue
            // the pool's first texel on the ground's grid, and the light's place in texels from it
            val cu = (U[0] * l.x + U[2]) * m.texW; val cv = (V[1] * l.z + V[2]) * m.texH
            val half = radius / TEXEL
            val iu = kotlin.math.floor(cu - half).toInt(); val iv = kotlin.math.floor(cv - half).toInt()
            list += Pool(m, (p[i0 * 3 + 1] + p[i1 * 3 + 1] + p[i2 * 3 + 1]) / 3f + .4f, iu, iv, cu - iu, cv - iv, U, V)
        }
        if (list.isEmpty()) return null
        val cs = (kotlin.math.ceil(2f * radius / TEXEL).toInt() + 3) and 1.inv()
        val g = kotlin.math.ceil(sqrt(list.size.toFloat())).toInt()
        val w = cs * g; val h = cs * ((list.size + g - 1) / g)
        val im = PixImg(w, h)
        im.glow(0, 0, 0, 0, 0); im.mask[0] = 0
        val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
        val n = list.size
        val pos = FloatArray(n * 12); val uvs = FloatArray(n * 8); val nrm = FloatArray(n * 12); val tan = FloatArray(n * 12); val idx = IntArray(n * 6)
        val bd = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for ((k, q) in list.withIndex()) {
            val ox = (k % g) * cs; val oy = (k / g) * cs
            for (j in 0 until cs) for (i in 0 until cs) {
                val tu = Math.floorMod(q.iu + i, q.m.texW); val tv = Math.floorMod(q.iv + j, q.m.texH)
                im.px[(oy + j) * w + ox + i] = q.m.tex[tv * q.m.texW + tu]
                // the disc in whole art pixels of the ground's own grid
                val ai = Math.floorDiv(q.iu + i, 2); val aj = Math.floorDiv(q.iv + j, 2)
                val dx = ((ai * 2 + 1) - (q.iu + q.cu)) * TEXEL / radius; val dz = ((aj * 2 + 1) - (q.iv + q.cv)) * TEXEL / radius
                val t = (1f - sqrt(dx * dx + dz * dz)).coerceIn(0f, 1f)
                val on = t > 0f && i in 1 until cs - 1 && j in 1 until cs - 1 && sqrt(t) * 17f > bayer[Math.floorMod(aj, 4) * 4 + Math.floorMod(ai, 4)] + .5f
                im.mask[(oy + j) * w + ox + i] = if (on) (255 shl 24) or ((40 + 120 * t).toInt() shl 16) else 0
            }
            // the cell's corners where the ground has those texels: x from u, z from v
            fun xAt(tx: Int) = (tx.toFloat() / q.m.texW - q.U[2]) / q.U[0]
            fun zAt(ty: Int) = (ty.toFloat() / q.m.texH - q.V[2]) / q.V[1]
            val xs = floatArrayOf(xAt(q.iu), xAt(q.iu + cs), xAt(q.iu + cs), xAt(q.iu)); val zs = floatArrayOf(zAt(q.iv), zAt(q.iv), zAt(q.iv + cs), zAt(q.iv + cs))
            val us = floatArrayOf(ox.toFloat() / w, (ox + cs).toFloat() / w, (ox + cs).toFloat() / w, ox.toFloat() / w)
            val vs = floatArrayOf(oy.toFloat() / h, oy.toFloat() / h, (oy + cs).toFloat() / h, (oy + cs).toFloat() / h)
            for (v in 0..3) {
                pos[k * 12 + v * 3] = xs[v]; pos[k * 12 + v * 3 + 1] = q.y; pos[k * 12 + v * 3 + 2] = zs[v]
                uvs[k * 8 + v * 2] = us[v]; uvs[k * 8 + v * 2 + 1] = vs[v]
                nrm[k * 12 + v * 3 + 1] = 1f; tan[k * 12 + v * 3] = 1f
                bd[0] = min(bd[0], xs[v]); bd[3] = max(bd[3], xs[v]); bd[2] = min(bd[2], zs[v]); bd[5] = max(bd[5], zs[v])
            }
            bd[1] = min(bd[1], q.y); bd[4] = max(bd[4], q.y)
            val b = k * 4
            intArrayOf(b, b + 1, b + 2, b, b + 2, b + 3).copyInto(idx, k * 6)
        }
        return CinemaPart(PropMesh(pos, uvs, nrm, tan, idx, w, h, im.px, im.mask, bd), casts = false, tiled = false, role = CinemaPart.Role.SIGN)
    }

    /** [parts]' cuts between a step and a head ([StoreSolids]) over the model's bounds, unfilled, as x0, z0, x1, z1. */
    private fun drawnSolids(parts: List<CinemaPart>, x0: Float, z0: Float, x1: Float, z1: Float): FloatArray {
        if (parts.isEmpty()) return FloatArray(0)
        // (StoreSolids reads a plot: x about its middle, z from its front edge at 0 back to -deep)
        val shifted = parts.map { p ->
            val m = p.mesh
            val pos = m.pos.copyOf(); for (i in 2 until pos.size step 3) pos[i] -= z1
            val b = m.bounds.copyOf(); b[2] -= z1; b[5] -= z1
            CinemaPart(PropMesh(pos, m.uv, m.nrm, m.tan, m.idx, m.texW, m.texH, m.tex, m.mask, b), p.casts, p.tiled, p.role)
        }
        val lot = 2f * max(abs(x0), abs(x1)); val deep = z1 - z0
        val r = StoreSolids.of(listOf(CinemaModel(CinemaEra.MODERN, shifted, floatArrayOf(x0, z0 - z1, x1, 0f), 0f, lot, deep)), lot, deep, fill = false)
        for (i in 1 until r.size step 2) r[i] += z1
        return r
    }

    /** The highest up-facing ground triangle of [grounds] under ([x], [z]) below [below]: its mesh and first index. */
    private fun groundUnder(grounds: List<PropMesh>, x: Float, z: Float, below: Float): Pair<PropMesh, Int>? {
        var best = -Float.MAX_VALUE; var hit: Pair<PropMesh, Int>? = null
        for (m in grounds) {
            val p = m.pos; val idx = m.idx
            var t = 0
            while (t + 2 < idx.size) {
                val a = idx[t] * 3; val b = idx[t + 1] * 3; val c = idx[t + 2] * 3; val t0 = t; t += 3
                if (m.nrm[a + 1] < .9f) continue
                val y = (p[a + 1] + p[b + 1] + p[c + 1]) / 3f
                if (y <= best || y > below) continue
                fun side(i: Int, j: Int) = (p[j] - p[i]) * (z - p[i + 2]) - (p[j + 2] - p[i + 2]) * (x - p[i])
                val s1 = side(a, b); val s2 = side(b, c); val s3 = side(c, a)
                if ((s1 >= 0 && s2 >= 0 && s3 >= 0) || (s1 <= 0 && s2 <= 0 && s3 <= 0)) { best = y; hit = m to t0 }
            }
        }
        return hit
    }

    /** A collision box's tags from model-info.json: its centre in the model (metres, glTF axes), group, attachment. */
    class BoxTag(val x: Float, val y: Float, val z: Float, val group: String, val attachment: String, val sx: Float = 0f, val sy: Float = 0f, val sz: Float = 0f)

    /** The door nodes model-info.json marks as internal (`doors[].external` false). */
    fun innerDoors(info: String): Set<String> {
        val a = runCatching { org.json.JSONObject(info).optJSONArray("doors") }.getOrNull() ?: return emptySet()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.has("external") && !it.optBoolean("external", true) }.map { it.optString("node") }.toSet()
    }

    fun collisionTags(info: String): List<BoxTag> {
        val c = runCatching { org.json.JSONObject(info).optJSONObject("collision") }.getOrNull() ?: return emptyList()
        val a = c.optJSONArray("boxes_blender_z_up") ?: c.optJSONArray("boxes") ?: return emptyList()
        return (0 until a.length()).map { i ->
            val b = a.getJSONObject(i); val ce = b.getJSONArray("center")
            // Blender's (x, y, z) up z is glTF's (x, z, -y)
            val sz = b.optJSONArray("size")
            // (sizes likewise: Blender's (x, y, z) is glTF's (x, z, y))
            BoxTag(ce.getDouble(0).toFloat(), ce.getDouble(2).toFloat(), -ce.getDouble(1).toFloat(), b.optString("group"), b.optString("attachment"),
                sz?.optDouble(0)?.toFloat() ?: 0f, sz?.optDouble(2)?.toFloat() ?: 0f, sz?.optDouble(1)?.toFloat() ?: 0f)
        }
    }

    /**
     * The collision mesh's boxes (each a closed lump of triangles: its bounds) that a walker meets (between [STEP] and
     * [HEAD] above the ground), as x0, z0, x1, z1 in the frame; a lot box is dropped without the lot, a box attached to a
     * hidden node with it.
     */
    private fun solidsOf(tris: List<FloatArray>, tags: List<BoxTag>, keepLot: Boolean, hidden: Set<String>, lift: Float): FloatArray {
        val U = UNITS_PER_METRE
        // the info file's boxes when it lists them with sizes (the mesh's boxes touch at their corners, so lumps of
        // triangles cannot tell two walls meeting at a corner from one box)
        if (tags.isNotEmpty() && tags.all { it.sx > 0f && it.sy > 0f && it.sz > 0f }) {
            val out = ArrayList<Float>()
            for (t in tags) {
                if (!keepLot && t.group == "lot" || t.attachment in hidden) continue
                if ((t.y - t.sy / 2f) * U > HEAD || (t.y + t.sy / 2f) * U < STEP) continue
                out += (t.x - t.sx / 2f) * U; out += (t.z - t.sz / 2f) * U; out += (t.x + t.sx / 2f) * U; out += (t.z + t.sz / 2f) * U
            }
            return out.toFloatArray()
        }
        // lumps: triangles sharing a corner (by position, as exporters split corners per face)
        val pts = ArrayList<FloatArray>()
        for (t in tris) { var i = 0; while (i + 8 < t.size) { pts += t.copyOfRange(i, i + 9); i += 9 } }
        val parent = IntArray(pts.size) { it }
        fun find(i: Int): Int { var k = i; while (parent[k] != k) { parent[k] = parent[parent[k]]; k = parent[k] }; return k }
        val byCorner = HashMap<Long, Int>()
        for ((ti, t) in pts.withIndex()) for (v in 0..2) {
            val key = ((t[v * 3] * 20f).roundToInt().toLong() * 73856093L) xor ((t[v * 3 + 1] * 20f).roundToInt().toLong() * 19349663L) xor ((t[v * 3 + 2] * 20f).roundToInt().toLong() * 83492791L)
            val o = byCorner.putIfAbsent(key, ti)
            if (o != null) { val a = find(o); val b = find(ti); if (a != b) parent[a] = b }
        }
        val boxes = HashMap<Int, FloatArray>()
        for ((ti, t) in pts.withIndex()) {
            val bx = boxes.getOrPut(find(ti)) { floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE) }
            for (v in 0..2) for (c in 0..2) { bx[c] = min(bx[c], t[v * 3 + c]); bx[c + 3] = max(bx[c + 3], t[v * 3 + c]) }
        }
        val out = ArrayList<Float>()
        for (b in boxes.values) {
            if (b[1] - lift > HEAD || b[4] - lift < STEP) continue
            val cx = (b[0] + b[3]) / 2f / U; val cy = ((b[1] + b[4]) / 2f - lift) / U; val cz = (b[2] + b[5]) / 2f / U
            val tag = tags.minByOrNull { (it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) + (it.z - cz) * (it.z - cz) }
                ?.takeIf { abs(it.x - cx) < .08f && abs(it.y - cy) < .08f && abs(it.z - cz) < .08f }
            if (tag != null && !keepLot && tag.group == "lot") continue
            if (tag != null && tag.attachment in hidden) continue
            out += b[0]; out += b[2]; out += b[3]; out += b[5]
        }
        return out.toFloatArray()
    }
}
