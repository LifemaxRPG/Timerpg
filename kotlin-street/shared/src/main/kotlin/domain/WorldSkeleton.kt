package com.lifemax.rpg.feature.habits.domain

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * The baked world skeleton (WORLD_GEN.md section 2, "The skeleton"; the format is src/world/gen/bake.ts, versions 2-3),
 * read whole from its asset (content/world/<seed>/skeleton.twsk.gz, shipped as assets/world/<seed>/). The atlas grids the
 * app needs (by default height, flags, biome, flow, landmass, crop and snowline: [DEFAULT_GRIDS]; the monthly climate stays on disk until
 * something asks for it) and every vector and fact of the JSON header: places with the hubs' pins, roads, rail lines,
 * rivers with their true paths (hub ends on their pins, the capital's river bent through its river pins: paths.ts), sea
 * routes, lakes, and (version 3) the natural wonders with their parameters. Engine-agnostic: no Filament, no Android.
 *
 * Positions are in atlas cell units (x east, wrapping at [W]; z south) as the TypeScript keeps them; [Planet] turns them
 * into metres ([WorldPos]).
 */
class WorldSkeleton private constructor(
    val seed: String,
    val version: Int,
    val places: List<Place>,
    val roads: List<Route>,
    val railLines: List<Route>,
    val rivers: List<River>,
    val sea: List<Route>,
    /** The natural wonders (version 3; empty in older skeletons): WORLD_GEN.md "Natural wonders". */
    val wonders: List<Wonder>,
    private val grids: Map<String, Any>,
    private val pts: IntArray,
    /** Bytes held by the grids and the paths (the RAM line's world share). */
    val gridBytes: Long,
    /** An estimate of what the vectors and facts take on the heap (twice their JSON's bytes). */
    val factsBytes: Long,
) {
    class Pin(val kind: String, val name: String, val x: Double, val z: Double, val ox: Int, val oz: Int, val dxM: Double, val dzM: Double)
    /** A hub's authored plan's frame: its size and centre in tiles, its farm ring (tiles) and the tile's metres. */
    class PlanTiles(val w: Int, val h: Int, val cx: Double, val cy: Double, val ring: Int, val tileM: Double)
    class Place(
        val id: String, val kind: String, val type: String, val rank: String, val x: Double, val z: Double, val mass: Int,
        val seed: String, val name: String, val founded: Int, val pins: List<Pin>, val plan: String?, val planTiles: PlanTiles?,
        /** A hub's pad (m). */
        val padW: Double, val padH: Double,
        /** A mountain place's setting ("alpine", "pass", "mining"; version 3) and a mining town's ore, or null. */
        val setting: String? = null, val ore: String? = null,
    ) { val hub: Boolean get() = kind == "hub" }
    /**
     * A natural wonder: its stable id (`w:<kind>:<n>`), kind ("waterfall", "blossom valley"...), tier ("common", "uncommon",
     * "rare", "legendary"), name, position in atlas cell units, landmass, extent (km) and fame, and the parameters a renderer
     * needs ([params]: numbers as Double, strings, booleans, lists of them), as src/world/gen/wonders.ts documents per kind.
     */
    class Wonder(val id: String, val kind: String, val tier: String, val name: String, val x: Double, val z: Double, val mass: Int,
        val extentKm: Double, val fame: String, val params: Map<String, Any>) {
        fun num(key: String): Double? = params[key] as? Double
        fun str(key: String): String? = params[key] as? String
    }
    /** A road, rail line or sea route: its id, class, the places it joins, its years, its path (first point, points). */
    class Route(val id: String, val cls: String, val a: String?, val b: String?, val built: Int, val expressway: Int?, val stations: List<String>, val path: IntArray?)
    class River(val flow: Int, val order: Int, val end: String, val path: IntArray?)

    fun place(id: String): Place? = byId[id]
    private val byId = places.associateBy { it.id }
    /** The capital (the first hub, `h:capital`). */
    val capital: Place get() = places.first { it.type == "capital" }

    /** Points of a path: [path] (first point, count) as x, z pairs in atlas cell units (x unwrapped along the line). */
    fun points(path: IntArray?): DoubleArray {
        if (path == null) return DoubleArray(0)
        val out = DoubleArray(path[1] * 2)
        for (i in 0 until path[1] * 2) out[i] = pts[path[0] * 2 + i] / PTS_SCALE
        return out
    }

    fun has(grid: String): Boolean = grid in grids
    private fun short(name: String): ShortArray = grids[name] as? ShortArray ?: error("the skeleton's $name grid was not kept")
    private fun bytes(name: String): ByteArray = grids[name] as? ByteArray ?: error("the skeleton's $name grid was not kept")

    private fun cell(i: Int, j: Int): Int = j.coerceIn(0, H - 1) * W + Math.floorMod(i, W)
    /** Metres above sea level of cell ([i], [j]) (x wraps, z clamps). */
    fun elev(i: Int, j: Int): Int = short("elev")[cell(i, j)].toInt()
    fun flags(i: Int, j: Int): Int = bytes("flags")[cell(i, j)].toInt() and 255
    fun land(i: Int, j: Int): Boolean = flags(i, j) and 1 != 0
    fun biome(i: Int, j: Int): Int = bytes("biome")[cell(i, j)].toInt() and 255
    fun mass(i: Int, j: Int): Int = short("mass")[cell(i, j)].toInt() and 0xffff
    /** The raw value of grid [name] at cell index [c] (unsigned where the grid is), for checks. */
    fun raw(name: String, c: Int): Int = when (val g = grids[name]) {
        is ShortArray -> if (name in UNSIGNED) g[c].toInt() and 0xffff else g[c].toInt()
        is ByteArray -> if (name in UNSIGNED) g[c].toInt() and 255 else g[c].toInt()
        else -> error("the skeleton's $name grid was not kept")
    }

    /**
     * The height (m) at ([x], [z]) in atlas cell units: bilinear between the cells' centres (x wraps). The same function
     * every far tier samples, so they agree where they meet.
     */
    fun height(x: Double, z: Double): Double {
        val u = x - .5; val v = (z - .5).coerceIn(0.0, H - 1.0)
        val i = floor(u).toInt(); val j = floor(v).toInt().coerceAtMost(H - 2)
        val fu = u - i; val fv = v - j
        val e = short("elev")
        fun at(ii: Int, jj: Int) = e[jj * W + Math.floorMod(ii, W)].toDouble()
        val a = at(i, j); val b = at(i + 1, j); val c = at(i, j + 1); val d = at(i + 1, j + 1)
        return (a + (b - a) * fu) + ((c + (d - c) * fu) - (a + (b - a) * fu)) * fv
    }

    companion object {
        const val W = 1856
        const val H = 928
        const val CELL_M = 1075.2
        const val PTS_SCALE = 524288.0
        /** The grids kept by default (about 17 MB): what the far view, the near terrain (crops, snowline), the hubs and the facts read. */
        val DEFAULT_GRIDS = setOf("elev", "flags", "biome", "flow", "mass", "crop", "snowline")
        private val UNSIGNED = setOf("flags", "biome", "koppen", "crop", "flow", "steady", "mass", "lake", "range")
        /** The biomes in the skeleton's order (src/world/gen/climate.ts BIOMES). */
        val BIOMES = listOf("ocean", "ice", "tundra", "taiga", "temperate forest", "temperate rainforest", "prairie", "steppe", "desert", "savanna",
            "tropical forest", "rainforest", "alpine", "marsh", "lake", "beach", "rock", "chaparral", "glacier")

        /** Reads a skeleton from [input] (gzipped when it starts with gzip's magic), keeping the grids in [keep]. */
        fun read(input: InputStream, keep: Set<String> = DEFAULT_GRIDS): WorldSkeleton {
            val buf = java.io.BufferedInputStream(input, 1 shl 16)
            buf.mark(2)
            val gz = buf.read() == 0x1f && buf.read() == 0x8b
            buf.reset()
            val inp = DataInputStream(if (gz) java.io.BufferedInputStream(java.util.zip.GZIPInputStream(buf, 1 shl 16), 1 shl 16) else buf)
            val head = ByteArray(12); inp.readFully(head)
            if (String(head, 0, 4, Charsets.US_ASCII) != "TWSK") error("not a world skeleton")
            val hb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val version = hb.getInt(4); val len = hb.getInt(8)
            if (version < 2) error("world skeleton version $version: W4 reads version 2 (rebake: kotlin-street/tools/bake-world.ts)")
            val jb = ByteArray(len); inp.readFully(jb)
            val json = JSONObject(String(jb, Charsets.UTF_8))
            var at = (12L + len + 3) and 3L.inv()
            skip(inp, at - 12 - len)
            val start = at
            val sections = json.getJSONArray("sections")
            val grids = HashMap<String, Any>()
            var pts = IntArray(0)
            var held = 0L
            val order = (0 until sections.length()).map { sections.getJSONObject(it) }.sortedBy { it.getLong("offset") }
            for (s in order) {
                val name = s.getString("name"); val type = s.getString("type"); val n = s.getInt("length")
                val size = when (type) { "i8", "u8" -> 1; "i16", "u16" -> 2; else -> 4 }
                val off = start + s.getLong("offset")
                skip(inp, off - at); at = off
                val want = name == "pts" || name in keep
                if (!want) continue
                val raw = ByteArray(n * size); inp.readFully(raw); at += raw.size
                val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                when {
                    name == "pts" -> { pts = IntArray(n).also { bb.asIntBuffer().get(it) }; held += n * 4L }
                    size == 1 -> { grids[name] = raw; held += raw.size }
                    size == 2 -> { grids[name] = ShortArray(n).also { bb.asShortBuffer().get(it) }; held += n * 2L }
                    else -> { grids[name] = IntArray(n).also { bb.asIntBuffer().get(it) }; held += n * 4L }
                }
            }
            fun ints(a: JSONArray?): IntArray? = a?.let { IntArray(it.length()) { i -> it.getInt(i) } }
            fun strs(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map { i -> it.getString(i) } } ?: emptyList()
            fun intOrNull(o: JSONObject, k: String): Int? = if (o.has(k) && !o.isNull(k)) o.getInt(k) else null
            val places = json.getJSONArray("places").let { a -> (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val pins = o.optJSONArray("pins")?.let { pa -> (0 until pa.length()).map { k -> val p = pa.getJSONObject(k)
                    Pin(p.getString("kind"), p.getString("name"), p.getDouble("x"), p.getDouble("z"), p.getInt("ox"), p.getInt("oz"), p.optDouble("dx", 0.0), p.optDouble("dz", 0.0)) } } ?: emptyList()
                val pt = o.optJSONObject("planTiles")?.let { PlanTiles(it.getInt("w"), it.getInt("h"), it.getDouble("cx"), it.getDouble("cy"), it.getInt("ring"), it.getDouble("tileM")) }
                val pad = o.optJSONObject("padM")
                Place(o.getString("id"), o.getString("kind"), o.getString("type"), o.getString("rank"), o.getDouble("x"), o.getDouble("z"), o.getInt("mass"),
                    o.getString("seed"), o.getString("name"), o.getInt("founded"), pins, if (o.isNull("plan")) null else o.optString("plan").ifEmpty { null }, pt,
                    pad?.getDouble("w") ?: 0.0, pad?.getDouble("h") ?: 0.0, o.optString("setting").ifEmpty { null }, o.optString("ore").ifEmpty { null })
            } }
            val roads = json.getJSONArray("roads").let { a -> (0 until a.length()).map { i -> val o = a.getJSONObject(i)
                Route(o.getString("id"), o.getString("cls"), o.optString("a"), if (o.isNull("b")) null else o.getString("b"), o.getInt("built"), intOrNull(o, "expressway"), emptyList(), ints(o.optJSONArray("path"))) } }
            val rail = json.getJSONArray("railLines").let { a -> (0 until a.length()).map { i -> val o = a.getJSONObject(i)
                val st = strs(o.optJSONArray("stations"))
                Route(o.getString("id"), o.getString("kind"), st.firstOrNull(), st.lastOrNull(), 0, null, st, ints(o.optJSONArray("path"))) } }
            val rivers = json.getJSONArray("rivers").let { a -> (0 until a.length()).map { i -> val o = a.getJSONObject(i)
                River(o.getInt("flow"), o.getInt("order"), o.getString("end"), ints(o.optJSONArray("path"))) } }
            val sea = json.getJSONArray("sea").let { a -> (0 until a.length()).map { i -> val o = a.getJSONObject(i)
                Route(o.optString("id"), o.optString("kind"), o.optString("a"), o.optString("b"), 0, null, emptyList(), null) } }
            // (version 3: the wonders; an older skeleton has none, and a section this reader does not know is skipped above)
            val wonders = json.optJSONArray("wonders")?.let { a -> (0 until a.length()).map { i -> val o = a.getJSONObject(i)
                Wonder(o.getString("id"), o.getString("kind"), o.getString("tier"), o.getString("name"), o.getDouble("x"), o.getDouble("z"), o.optInt("mass"),
                    o.optDouble("extentKm", 0.0), o.optString("fame"), o.optJSONObject("params")?.let { paramsOf(it) } ?: emptyMap())
            } } ?: emptyList()
            return WorldSkeleton(json.getString("seed"), version, places, roads, rail, rivers, sea, wonders, grids, pts, held, len * 2L)
        }

        private fun valueOf(v: Any?): Any = when (v) {
            is Number -> v.toDouble()
            is JSONArray -> (0 until v.length()).map { valueOf(v.get(it)) }
            is JSONObject -> paramsOf(v)
            null -> ""
            else -> if (v == JSONObject.NULL) "" else v
        }
        private fun paramsOf(o: JSONObject): Map<String, Any> = o.keys().asSequence().sorted().associateWith { valueOf(o.get(it)) }

        private fun skip(inp: DataInputStream, n: Long) {
            var left = n
            while (left > 0) { val k = inp.skip(left); if (k <= 0) { if (inp.read() < 0) error("the skeleton ends early"); left-- } else left -= k }
        }
    }
}
