package com.lifemax.rpg.feature.habits.domain

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The generated towns on the planet (docs/TOWNS_EVERYWHERE.md, "Streaming", planet step 2): the baker's manifest
 * (tools/towns-bake.ts `manifest.json`) read as plain data, engine-agnostic. Each baked place is a [Town]: its plan laid in
 * tiles (2.1 m; plan x east along Main Street, plan y south) and turned onto the planet by its frame ([Town.deg]: plan
 * east's heading, degrees clockwise from east, x east and z south), an anchor point of the plan ([Town.planX],
 * [Town.planY], tiles) standing [Town.worldX], [Town.worldZ] metres from the place's point. Its ground (the plan and its
 * farm ring, tiles) is levelled to [Town.heightM] (metres above sea level).
 *
 * What it answers, all without allocating (the world's terrain asks it for every vertex): which towns' grounds are near a
 * point ([near], an index of 4 km cells), how far a point is outside a town's ground ([Town.outside]: negative inside),
 * and a town's plan point on the planet ([Town.world]) and back ([Town.planX]...).
 *
 * The hand-made hubs (the capital) are not in it: they keep their own frames ([HubFrame]).
 */
class WorldTownSet(val towns: List<Town>) {
    /** One baked town. Positions in metres are absolute planet metres (x wraps). */
    class Town(
        val id: String, val name: String, val rank: String, val type: String, val seed: String,
        /** The place's point (planet metres). */
        val atX: Double, val atZ: Double,
        val deg: Double, val planX: Double, val planY: Double, val worldX: Double, val worldZ: Double,
        /** The ground (plan tiles: the plan and its farm ring), and the plan itself (tiles, without the ring). */
        val gx0: Double, val gy0: Double, val gx1: Double, val gy1: Double,
        val px0: Double, val py0: Double, val px1: Double, val py1: Double,
        /** The levelled ground's height (m above sea level). */
        val heightM: Double,
        /** The pack (relative to the manifest's folder). */
        val file: String,
        val bytes: Long,
        val buildings: Int,
        /** How far under its ground (outside its plan: the ring, or a roadside site's whole pad) the terrain stays (m). */
        val under: Double = TownGroundShape.UNDER_RING_M,
    ) {
        /** A baked town (a pack to stream in), not a roadside site's pad ([WorldRoadside.pads]). */
        val baked: Boolean get() = file.isNotEmpty()
        private val c = cos(Math.toRadians(deg)); private val s = sin(Math.toRadians(deg))
        /** Plan east and plan south on the planet (unit, x east z south). */
        val eastX: Double get() = c
        val eastZ: Double get() = s

        /** Planet metres east of the place's point, of plan tile ([px], [py]). */
        fun dx(px: Double, py: Double): Double = ((px - planX) * c - (py - planY) * s) * Planet.TILE_M + worldX
        fun dz(px: Double, py: Double): Double = ((px - planX) * s + (py - planY) * c) * Planet.TILE_M + worldZ
        /** The planet position of plan tile ([px], [py]). */
        fun world(px: Double, py: Double): WorldPos = WorldPos(Planet.wrapX(atX + dx(px, py)), atZ + dz(px, py))
        /** The plan tile x, y of planet metres ([x], [z]) (x wrapped near the town). */
        fun tileX(x: Double, z: Double): Double { val ex = Planet.wrapDx(x - atX) - worldX; val ez = z - atZ - worldZ; return planX + (ex * c + ez * s) / Planet.TILE_M }
        fun tileY(x: Double, z: Double): Double { val ex = Planet.wrapDx(x - atX) - worldX; val ez = z - atZ - worldZ; return planY + (-ex * s + ez * c) / Planet.TILE_M }

        /** The ground's middle on the planet, and its half diagonal (m). */
        val centre: WorldPos = world((gx0 + gx1) / 2, (gy0 + gy1) / 2)
        val radiusM: Double = hypot(gx1 - gx0, gy1 - gy0) / 2 * Planet.TILE_M

        /**
         * How far (m) planet point ([x], [z]) is outside the town's ground (the plan and its ring); negative inside (the
         * distance to the nearest edge, as [WorldFarPlan.outside] has it for the capital).
         */
        fun outside(x: Double, z: Double): Double = rectOut(x, z, gx0, gy0, gx1, gy1)
        /** The same for the plan alone (no farm ring): the town's own streets, lots and grass. */
        fun outsidePlan(x: Double, z: Double): Double = rectOut(x, z, px0, py0, px1, py1)

        private fun rectOut(x: Double, z: Double, x0: Double, y0: Double, x1: Double, y1: Double): Double {
            val tx = tileX(x, z); val ty = tileY(x, z)
            val ox = max(x0 - tx, tx - x1); val oy = max(y0 - ty, ty - y1)
            val d = if (ox <= 0 && oy <= 0) max(ox, oy) else hypot(max(ox, 0.0), max(oy, 0.0))
            return d * Planet.TILE_M
        }

        override fun toString(): String = "$name ($id, $rank)"
    }

    private val cells = HashMap<Long, MutableList<Town>>()
    private val byId = towns.associateBy { it.id }

    init {
        for (t in towns) {
            val r = t.radiusM + REACH_M
            val i0 = floor((t.centre.x - r) / CELL).toInt(); val i1 = floor((t.centre.x + r) / CELL).toInt()
            val j0 = floor((t.centre.z - r) / CELL).toInt(); val j1 = floor((t.centre.z + r) / CELL).toInt()
            for (j in j0..j1) for (i in i0..i1) cells.getOrPut(key(i, j)) { ArrayList(2) } += t
        }
    }

    private fun key(i: Int, j: Int): Long = (Math.floorMod(i, ROUND).toLong() shl 32) or (j.toLong() and 0xffffffffL)

    /** The towns whose ground lies within [REACH_M] of planet point ([x], [z]) (or nearer: possibly), or null when none. */
    fun near(x: Double, z: Double): List<Town>? = cells[key(floor(Planet.wrapX(x) / CELL).toInt(), floor(z / CELL).toInt())]

    /** The town [id], or null. */
    fun of(id: String): Town? = byId[id]

    /** The places with a town here (the far view leaves their stand-in blocks out). */
    val ids: Set<String> = towns.filter { it.baked }.map { it.id }.toSet()

    /** The town whose ground ([pad] m round it) holds planet point ([x], [z]), nearest-edge first; null: none. */
    fun at(x: Double, z: Double, pad: Double = 0.0): Town? {
        var best: Town? = null; var bd = pad
        for (t in near(x, z) ?: return null) { val d = t.outside(x, z); if (d <= bd) { bd = d; best = t } }
        return best
    }

    /** The towns within [m] metres of planet point [p] (their ground's nearest edge), nearest first. */
    fun within(p: WorldPos, m: Double): List<Town> = towns.mapNotNull { t -> t.outside(p.x, p.z).takeIf { it < m }?.let { t to it } }.sortedBy { it.second }.map { it.first }

    companion object {
        /** The index's cells (m) and how far past a town's ground it reaches (its terrain's blend: [WorldFarPlan.TOWN_BLEND_M]). */
        const val CELL = 4000.0
        const val REACH_M = 1200.0
        private val ROUND = Math.round(Planet.C / CELL).toInt()

        /** The baked towns of manifest.json ([json]); the places not baked, and the hand-made ones, are left out. */
        fun parse(json: String): WorldTownSet {
            val o = JSONObject(json)
            val a = o.getJSONArray("towns")
            val out = ArrayList<Town>()
            for (k in 0 until a.length()) {
                val r = a.getJSONObject(k)
                val f = r.optJSONObject("frame") ?: continue
                val file = r.optString("file").takeIf { it.isNotEmpty() } ?: continue
                val g = r.getJSONObject("ground")
                val at = r.getJSONArray("at")
                val pl = f.getJSONArray("plan"); val w = f.getJSONArray("world")
                val ring = -g.getDouble("x0")
                val h = r.optJSONObject("height")
                out += Town(r.getString("id"), r.optString("name"), r.optString("rank"), r.optString("type"), r.optString("seed"),
                    at.getDouble(0) * Planet.CELL_M, at.getDouble(1) * Planet.CELL_M,
                    f.getDouble("deg"), pl.getDouble(0), pl.getDouble(1), w.getDouble(0), w.getDouble(1),
                    g.getDouble("x0"), g.getDouble("y0"), g.getDouble("x1"), g.getDouble("y1"),
                    g.getDouble("x0") + ring, g.getDouble("y0") + ring, g.getDouble("x1") - ring, g.getDouble("y1") - ring,
                    h?.optDouble("m") ?: 0.0, file, r.optLong("bytes"), r.optJSONObject("counts")?.optInt("buildings") ?: 0)
            }
            return WorldTownSet(out)
        }

        /** None (no manifest). */
        val NONE = WorldTownSet(emptyList())
    }
}

/**
 * A baked town's pack (tools/towns-bake.ts, ".ttwn"): "TTWN", a version byte, the header's length (u32 LE), the header
 * (JSON: id and files with offset, size, raw size and encoding, offsets from the header's end), then the files: JSON
 * raw-deflated (java.util.zip's Inflater with nowrap), the pictures stored. [file] inflates one by its path.
 */
class TownPack(private val bytes: ByteArray) {
    class Entry(val path: String, val offset: Int, val size: Int, val raw: Int, val deflated: Boolean)

    val id: String
    val entries: Map<String, Entry>
    private val base: Int

    init {
        require(bytes.size > 9 && String(bytes, 0, 4, Charsets.US_ASCII) == "TTWN") { "not a town pack" }
        val n = (bytes[5].toInt() and 255) or ((bytes[6].toInt() and 255) shl 8) or ((bytes[7].toInt() and 255) shl 16) or ((bytes[8].toInt() and 255) shl 24)
        val head = JSONObject(String(bytes, 9, n, Charsets.UTF_8))
        id = head.getString("id")
        base = 9 + n
        val fs = head.getJSONArray("files")
        val m = LinkedHashMap<String, Entry>()
        for (k in 0 until fs.length()) {
            val f = fs.getJSONObject(k)
            m[f.getString("path")] = Entry(f.getString("path"), f.getInt("offset"), f.getInt("size"), f.optInt("raw", f.getInt("size")), f.optString("enc") == "deflate")
        }
        entries = m
    }

    /** File [path]'s bytes (inflated), or null if the pack has none. */
    fun file(path: String): ByteArray? {
        val e = entries[path] ?: return null
        if (!e.deflated) return bytes.copyOfRange(base + e.offset, base + e.offset + e.size)
        val inf = java.util.zip.Inflater(true)
        try {
            inf.setInput(bytes, base + e.offset, e.size)
            val out = ByteArray(e.raw)
            var got = 0
            while (got < out.size && !inf.finished()) { val k = inf.inflate(out, got, out.size - got); if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break; got += k }
            return if (got == out.size) out else out.copyOf(got)
        } finally { inf.end() }
    }

    fun text(path: String): String? = file(path)?.toString(Charsets.UTF_8)
}

/** The far view's flattening of the ground under and round a generated town (planet step 2): how far it reaches. */
object TownGroundShape {
    /** The ground is the town's height this far past its ground's edge (m), then blends to the land's own over [BLEND_M]. */
    const val FLAT_M = 40.0
    const val BLEND_M = 360.0
    /** How far under the town's own ground the terrain stays: under its plan (its streets and grass) and under its farm ring (m). */
    const val UNDER_PLAN_M = .6
    const val UNDER_RING_M = .05

    /**
     * The scene height (m above [baseElev]) of ground [h] (same) at planet point ([x], [z]) with the towns of [set]
     * flattened in: the nearest town's height under its ground, blending out to [h] past it.
     */
    fun shape(set: WorldTownSet, x: Double, z: Double, h: Double, baseElev: Double): Double {
        val list = set.near(x, z) ?: return h
        var best: WorldTownSet.Town? = null; var bd = Double.MAX_VALUE
        for (t in list) { val d = t.outside(x, z); if (d < bd) { bd = d; best = t } }
        val t = best ?: return h
        if (bd > FLAT_M + BLEND_M) return h
        val target = t.heightM - baseElev
        if (bd <= 0) return target - if (t.outsidePlan(x, z) <= 0) UNDER_PLAN_M else t.under
        val u = ((bd - FLAT_M) / BLEND_M).coerceIn(0.0, 1.0)
        val b = u * u * (3 - 2 * u)
        return (target - t.under) * (1 - b) + h * b
    }

    /** How far (m) planet point ([x], [z]) is outside the nearest town's ground (MAX_VALUE: none near). */
    fun outside(set: WorldTownSet, x: Double, z: Double): Double {
        val list = set.near(x, z) ?: return Double.MAX_VALUE
        var bd = Double.MAX_VALUE
        for (t in list) bd = min(bd, t.outside(x, z))
        return bd
    }

    @Suppress("unused") private fun unused(v: Double) = abs(v)
}
