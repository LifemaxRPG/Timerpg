package com.lifemax.rpg.feature.habits.domain

import org.json.JSONArray
import org.json.JSONObject

/**
 * Highways v2 as baked (skeleton version 4, src/world/gen/highways.ts and bake.ts; WORLD_GEN.md "Highways that evolve"):
 * every road segment with its whole life by year (a wagon track, dirt, gravel, paved, realigned, widened, bypassed, an
 * expressway, renumbered, handed to the county, abandoned and fading), the interchanges, the route numbers, the roadside
 * sites and the towns' bypass records. [Seg.at] gives a segment in one year: the network of a year is every segment's.
 * Engine-agnostic.
 */
class WorldHighways(
    val segs: List<Seg>,
    val interchanges: List<Interchange>,
    val routes: List<RouteNo>,
    val sites: List<Site>,
    val towns: List<TownRoads>,
) {
    /** Values from a year on: [years] ascending, [values] the same length. */
    class Steps<T>(val years: IntArray, val values: List<T>) {
        fun at(year: Int): T? { var v: T? = null; for (i in years.indices) { if (years[i] <= year) v = values[i] else break }; return v }
    }

    /** A segment's state in a year: its class ("track", "county", "highway", "expressway", "local", "frontage", "abandoned"). */
    class State(val cls: String, val surface: String, val lanes: Int, val divided: Boolean, val number: String?, /** 0 in use, to 1 as it fades */ val fade: Double)

    class Structure(val kind: String, val fromKm: Double, val toKm: Double, val depthM: Int)

    class Seg(
        val id: String,
        /** "original" (a piece of an old road), "realignment", "bypass", "expressway", "beltway", "radial", "connector", "frontage". */
        val kind: String,
        val mass: Int,
        val road: String?,
        val km: Double,
        val built: Int,
        val gone: Int?,
        val cls: Steps<String>,
        val surface: Steps<String>,
        val lanes: Steps<Int>,
        val divided: Int?,
        val number: Steps<String?>,
        val route: String?,
        val maxGrade: Double,
        /** Its path in the skeleton's points (first point, count). */
        val path: IntArray,
        val bridges: Int,
        val structures: List<Structure>,
    ) {
        val expressway: Boolean get() = kind == "expressway" || kind == "beltway" || kind == "radial"
        /** The segment in [year], or null when it is not there. */
        fun at(year: Int): State? {
            if (year < built) return null
            val s = surface.at(year) ?: "track"
            if (gone != null && year >= gone) {
                if (year >= gone + FADE_YEARS) return null
                return State("abandoned", if (s == "paved" || s == "concrete") s else "dirt", 0, false, null, (year - gone + 1.0) / FADE_YEARS)
            }
            return State(cls.at(year) ?: "track", s, lanes.at(year) ?: 1, divided != null && year >= divided, number.at(year), 0.0)
        }
    }

    class Interchange(
        val id: String, val kind: String, val type: String, val year: Int, val rebuiltYear: Int?, val rebuiltType: String?,
        val x: Double, val z: Double, val mass: Int, val segs: List<String>, val road: String?, val place: String?,
        /** Exit numbers by route designation. */
        val exits: Map<String, String>,
    ) { fun typeAt(year: Int): String = if (rebuiltYear != null && year >= rebuiltYear) rebuiltType!! else type }

    class RouteNo(val id: String, val system: String, val n: Int, val kind: String, val dir: String, val designation: String, val mass: Int, val parent: String?, val from: Int, val places: List<String>)

    class Model(val from: Int, val to: Int?, val id: String)
    class Site(val id: String, val kind: String, val x: Double, val z: Double, val mass: Int, val from: Int, val to: Int?, val seg: String?, val interchange: String?, val models: List<Model>) {
        fun open(year: Int): Boolean = year >= from && (to == null || year < to)
    }

    class TownRoads(val place: String, val mainStreet: String?, val bypassYear: Int?, val bypassBy: String?, val interchange: String?, val interchangeKm: Double?)

    fun seg(id: String): Seg? = segById[id]
    private val segById by lazy { segs.associateBy { it.id } }
    fun route(id: String): RouteNo? = routeById[id]
    private val routeById by lazy { routes.associateBy { it.id } }
    /** The network in [year]: every segment there with its state. */
    fun at(year: Int): List<Pair<Seg, State>> = segs.mapNotNull { s -> s.at(year)?.let { s to it } }

    companion object {
        const val FADE_YEARS = 25

        fun parse(o: JSONObject): WorldHighways {
            fun <T> arr(a: JSONArray?, f: (JSONObject) -> T): List<T> = a?.let { (0 until it.length()).map { i -> f(it.getJSONObject(i)) } } ?: emptyList()
            fun intOrNull(o: JSONObject, k: String): Int? = if (o.has(k) && !o.isNull(k)) o.getInt(k) else null
            fun strOrNull(o: JSONObject, k: String): String? = if (o.has(k) && !o.isNull(k)) o.getString(k) else null
            fun <T> steps(a: JSONArray, f: (JSONArray, Int) -> T): Steps<T> {
                val ys = IntArray(a.length()); val vs = ArrayList<T>(a.length())
                for (i in 0 until a.length()) { val p = a.getJSONArray(i); ys[i] = p.getInt(0); vs += f(p, 1) }
                return Steps(ys, vs)
            }
            val segs = arr(o.optJSONArray("segs")) { s ->
                val p = s.getJSONArray("path")
                Seg(s.getString("id"), s.getString("kind"), s.optInt("mass"), strOrNull(s, "road"), s.getDouble("km"), s.getInt("built"), intOrNull(s, "gone"),
                    steps(s.getJSONArray("cls")) { a, i -> a.getString(i) }, steps(s.getJSONArray("surface")) { a, i -> a.getString(i) },
                    steps(s.getJSONArray("lanes")) { a, i -> a.getInt(i) }, intOrNull(s, "divided"),
                    steps(s.getJSONArray("number")) { a, i -> if (a.isNull(i)) null else a.getString(i) }, strOrNull(s, "route"), s.optDouble("maxGrade", 0.0),
                    intArrayOf(p.getInt(0), p.getInt(1)), s.optJSONArray("crossings")?.length() ?: 0,
                    s.optJSONArray("structures")?.let { a -> (0 until a.length()).map { i -> val q = a.getJSONArray(i); Structure(q.getString(0), q.getDouble(1), q.getDouble(2), q.getInt(3)) } } ?: emptyList())
            }
            val ics = arr(o.optJSONArray("interchanges")) { q ->
                val rb = q.optJSONArray("rebuilt")
                val ex = q.optJSONObject("exits")
                Interchange(q.getString("id"), q.getString("kind"), q.getString("type"), q.getInt("year"), rb?.getInt(0), rb?.getString(1), q.getDouble("x"), q.getDouble("z"), q.optInt("mass"),
                    q.optJSONArray("segs")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(), strOrNull(q, "road"), strOrNull(q, "place"),
                    ex?.keys()?.asSequence()?.sorted()?.associateWith { ex.getString(it) } ?: emptyMap())
            }
            val routes = arr(o.optJSONArray("routes")) { r ->
                RouteNo(r.getString("id"), r.getString("system"), r.getInt("n"), r.getString("kind"), r.getString("dir"), r.getString("designation"), r.getInt("mass"), strOrNull(r, "parent"), r.getInt("from"),
                    r.optJSONArray("places")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList())
            }
            val sites = arr(o.optJSONArray("sites")) { q ->
                Site(q.getString("id"), q.getString("kind"), q.getDouble("x"), q.getDouble("z"), q.optInt("mass"), q.getInt("from"), intOrNull(q, "to"), strOrNull(q, "seg"), strOrNull(q, "interchange"),
                    arr(q.optJSONArray("models")) { m -> Model(m.getInt("from"), intOrNull(m, "to"), m.getString("id")) })
            }
            val towns = arr(o.optJSONArray("towns")) { t ->
                val b = t.optJSONObject("bypassed")
                TownRoads(t.getString("place"), strOrNull(t, "mainStreet"), b?.getInt("year"), b?.getString("by"), strOrNull(t, "interchange"), if (t.has("interchangeKm")) t.getDouble("interchangeKm") else null)
            }
            return WorldHighways(segs, ics, routes, sites, towns)
        }
    }
}
