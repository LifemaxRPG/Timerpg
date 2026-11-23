package com.lifemax.rpg.feature.habits.loop

import com.lifemax.rpg.feature.habits.domain.FurnitureKind
import com.lifemax.rpg.feature.habits.domain.NodeKind
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.presentation.scene.TownPlay
import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

/**
 * A place of the loop (docs/LOOP.md): a shop, the pawn shop, the police station or a home, by the building ([sprite])
 * the town draws it with. [kind] is the facade's place kind (src/loop/types.ts LoopPlaceKind); [shopKind] picks the
 * stock ([LoopItems.STOCK]). The door ([doorX], [doorZ], street units) is where the person who keeps it stands, on the
 * pavement in front (TownPlay.keeperSpot): the place's position for the sim. [staff]: who works there, the keeper first.
 */
class LoopPlace(
    val id: String,
    val sprite: String,
    val name: String,
    val kind: String,
    val shopKind: String,
    val doorX: Float,
    val doorZ: Float,
    val staff: List<String>,
    val open: Int,
    val close: Int,
) {
    val keeperId: String? get() = staff.firstOrNull()
    val isShop: Boolean get() = kind == "general_store" || kind == "market" || kind == "pawn_shop"
    /** Open at [hour] (0..24). */
    fun openAt(hour: Double): Boolean = if (kind == "police_station") true else (hour % 24.0).let { h -> h >= open && h < close }
}

/** One of the town's people as the loop knows them (from dialogue.json: the people the game draws and talks to). */
class LoopPerson(
    val id: String,
    val name: String,
    val first: String,
    val surname: String,
    val female: Boolean,
    val age: Int,
    /** The building they are found at (their home, or for a shop's owner the shop). */
    val building: String,
    val homePlaceId: String,
    var workplacePlaceId: String?,
    val spouseId: String?,
    val childIds: List<String>,
    val friends: List<Pair<String, Double>>,
    val charm: Double,
)

/** A node of the loop's street graph: the town's road junctions (and the police station's door), in street units. */
class LoopNode(val id: String, val x: Float, val z: Float, val kind: String)

/**
 * The capital's data turned into the loop facade's `init` (src/loop/types.ts LoopInit): the people of dialogue.json
 * (ids, ages, sexes, spouses and children from their family facts, coworkers, neighbours and housemates as friends),
 * the places (the general store's site, the market's, the pawn shop, the police station, the small shops that have an
 * owner, the homes), the street graph (the town's junctions and roads), the street lamps, the era. Positions go to the
 * sim in metres: street units / [VehicleWorld.U].
 *
 * What the town's data does not have is made up here, deterministically (listed in docs/LOOP.md "What is wired"): the
 * landmark stores have no owner in the export, so a townsperson who "works from home" is made their clerk; opening hours
 * by kind; traits only from the person's warmth (charm).
 */
class LoopTown(
    val town: Town,
    val seed: String,
    val year: Int,
    val townName: String,
    val jurisdictionId: String,
    val places: List<LoopPlace>,
    val people: List<LoopPerson>,
    val nodes: List<LoopNode>,
    /** Roads between nodes: from, to, length (m). */
    val edges: List<Triple<String, String, Double>>,
    /** Street lamps: x, z (street units). */
    val lamps: List<FloatArray>,
) {
    val placeById: Map<String, LoopPlace> = places.associateBy { it.id }
    val placeBySprite: Map<String, LoopPlace> = places.filter { it.kind != "home" }.associateBy { it.sprite }
    val personById: Map<String, LoopPerson> = people.associateBy { it.id }
    val station: LoopPlace? = places.firstOrNull { it.kind == "police_station" }
    /** The people found at each building (the first is the one standing at the door), shops' staff first. */
    val atBuilding: Map<String, List<String>> = HashMap<String, MutableList<String>>().also { m ->
        for (p in places) if (p.kind != "home" && p.staff.isNotEmpty()) m.getOrPut(p.sprite) { ArrayList() } += p.staff
        for (p in people) m.getOrPut(p.building) { ArrayList() }.let { if (p.id !in it) it += p.id }
    }
    /** The loop's clerks the town's export does not stand at the shop (the landmark stores'): building -> person. */
    val clerks: Map<String, String> = places.filter { it.kind != "home" && it.keeperId != null && personById[it.keeperId!!]?.building != it.sprite }
        .associate { it.sprite to it.keeperId!! }

    private val passerPool: List<String> = people.filter { p -> places.none { it.kind != "home" && it.keeperId == p.id } }.map { it.id }.ifEmpty { people.map { it.id } }

    /**
     * Who the anonymous passer [who] (TownPlay's passers: a seed per row and slot) is: one of the town's people, so a
     * witness has a memory and friends to tell (a placeholder: the street's passers are not yet the town's people walking
     * their day).
     */
    fun passerId(who: Int): String = passerPool[Math.floorMod(who, passerPool.size)]

    fun m(units: Float): Double = units / VehicleWorld.U.toDouble()

    /** The facade's `init` for a game starting on [startDay] at [startHour] with the player looking like [appearance]. */
    fun initJson(startHour: Double, startDay: Int = 1, playerName: String = "Alex Mercer", appearance: JSONObject = defaultAppearance()): JSONObject {
        val o = JSONObject()
        o.put("seed", "loop-$seed")
        o.put("year", year)
        o.put("startDay", startDay)
        o.put("startHour", startHour)
        o.put("startDayOfWeek", 0)
        o.put("playerId", PLAYER)
        o.put("playerName", playerName)
        o.put("playerAppearance", appearance)
        o.put("places", JSONArray().also { a ->
            for (p in places) a.put(JSONObject().put("id", p.id).put("name", p.name).put("kind", p.kind)
                .put("position", vec(p.doorX, p.doorZ)).put("jurisdictionId", jurisdictionId).put("townId", seed).also { j ->
                    p.keeperId?.let { j.put("ownerId", it) }
                    if (p.kind != "home" && p.kind != "police_station") j.put("openHours", JSONArray().put(p.open).put(p.close))
                    if (p.kind == "police_station") j.put("nodeId", STATION_NODE)
                })
        })
        o.put("streetGraph", JSONObject()
            .put("nodes", JSONArray().also { a ->
                for (n in nodes) a.put(JSONObject().put("id", n.id).put("position", vec(n.x, n.z)).put("kind", n.kind)
                    .put("jurisdictionId", jurisdictionId).put("streetLight", if (n.kind == "station") .85 else .6))
            })
            .put("edges", JSONArray().also { a -> for ((f, t, d) in edges) a.put(JSONObject().put("from", f).put("to", t).put("distance", round1(d))) }))
        o.put("streetLamps", JSONArray().also { a ->
            for (l in lamps) a.put(JSONObject().put("position", vec(l[0], l[1])).put("radius", 16).put("intensity", .75))
        })
        o.put("people", JSONArray().also { a ->
            for (p in people) a.put(JSONObject().put("id", p.id).put("first", p.first).put("surname", p.surname)
                .put("sex", if (p.female) "F" else "M").put("age", p.age).put("traits", JSONObject().put("charm", p.charm))
                .put("homePlaceId", p.homePlaceId).put("townId", seed).also { j ->
                    p.workplacePlaceId?.let { j.put("workplacePlaceId", it) }
                    p.spouseId?.let { j.put("spouseId", it) }
                    if (p.childIds.isNotEmpty()) j.put("childIds", JSONArray(p.childIds))
                    if (p.friends.isNotEmpty()) j.put("friends", JSONArray().also { f ->
                        for ((id, aff) in p.friends) f.put(JSONObject().put("personId", id).put("affinity", aff))
                    })
                })
        })
        return o
    }

    fun vec(x: Float, z: Float): JSONObject = JSONObject().put("x", round2(m(x))).put("y", round2(m(z)))

    companion object {
        const val PLAYER = "player"
        const val STATION_NODE = "node:station"

        fun defaultAppearance(clothing: String = "navy"): JSONObject =
            JSONObject().put("height", "average").put("build", "average").put("hair", "brown").put("clothingColor", clothing)

        private fun round1(d: Double) = Math.round(d * 10.0) / 10.0
        private fun round2(d: Double) = Math.round(d * 100.0) / 100.0

        private fun h(vararg k: Any): Int = k.joinToString("|").hashCode()

        /** The place of cell ([x], [y]) along a Hilbert curve over a 256 x 256 grid (neighbours on it are near). */
        private fun hilbert(x0: Int, y0: Int): Int {
            var x = x0.coerceIn(0, 255); var y = y0.coerceIn(0, 255); var d = 0
            var s = 128
            while (s > 0) {
                val rx = if (x and s > 0) 1 else 0; val ry = if (y and s > 0) 1 else 0
                d += s * s * ((3 * rx) xor ry)
                if (ry == 0) { if (rx == 1) { x = 255 - x; y = 255 - y }; val t = x; x = y; y = t }
                s = s shr 1
            }
            return d
        }

        /**
         * Builds the loop's town from the town's [street] (street.json v2's root) and [dialogue] (its dialogue.json),
         * with [town] parsed from the same street.json.
         */
        fun build(town: Town, street: JSONObject, dialogue: JSONObject): LoopTown {
            val seed = town.seed
            val year = town.year
            val g = street.optJSONObject("graph") ?: JSONObject()
            fun door(sprite: String): Pair<Float, Float>? = town.placement(sprite)?.let { (r, b) -> TownPlay.keeperSpot(r, b).let { it.x to it.z } }

            // ---- the people (dialogue.json v2: everyone at every building)
            class Raw(val o: JSONObject) {
                val id = o.optString("id"); val name = o.optString("name"); val building = o.optString("building")
                val role = o.optString("role"); val job = o.optString("job"); val buildingName = o.optString("buildingName")
                val row = o.opt("row")?.toString(); val rowIndex = o.optInt("rowIndex", -1)
                val family: String = o.optJSONArray("topics")?.let { ts -> (0 until ts.length()).map { ts.getJSONObject(it) }
                    .firstOrNull { it.optString("intent") == "family" }?.let { if (it.isNull("prompt")) null else it.optString("prompt") } } ?: ""
            }
            val raws = ArrayList<Raw>()
            dialogue.optJSONArray("npcs")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { if (it.optString("id").isNotEmpty()) raws += Raw(it) } }
            val byName = raws.groupBy { it.name }
            val byBuilding = raws.groupBy { it.building }

            // ---- the places
            val places = ArrayList<LoopPlace>()
            val worksFromHome = raws.filter { it.job == "works from home" }.sortedBy { it.id }.toMutableList()
            fun clerks(placeId: String, n: Int): List<String> {
                val out = ArrayList<String>()
                repeat(n) {
                    if (worksFromHome.isEmpty()) return out
                    val k = Math.floorMod(h(seed, placeId, it), worksFromHome.size)
                    out += worksFromHome.removeAt(k).id
                }
                return out
            }
            fun hours(kind: String): Pair<Int, Int> = when (kind) {
                "market" -> 7 to 21
                "pawn" -> 10 to 18
                "general", "antiques" -> 8 to 18
                "ice cream parlor" -> 11 to 21
                else -> 9 to 17
            }
            fun add(id: String, sprite: String, name: String, kind: String, shopKind: String, staff: List<String>) {
                val (x, z) = door(sprite) ?: return
                val (o, c) = hours(shopKind)
                places += LoopPlace(id, sprite, name, kind, shopKind, x, z, staff, o, c)
            }
            // the store sites (graph.stores): the general store and the market, as they are in the town's year
            val stores = g.optJSONArray("stores")
            val siteSprite = HashMap<String, String>()
            g.optJSONArray("landmarks")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { siteSprite[it.optString("id")] = it.optString("sprite") } }
            var market = false
            if (stores != null) for (i in 0 until stores.length()) {
                val s = stores.getJSONObject(i)
                val sprite = siteSprite[s.optString("landmark")] ?: continue
                val kind = s.optString("kind"); val state = s.optString("state")
                val reuse = s.optJSONObject("reuse")
                when {
                    state == "open" && kind == "general" -> add(s.optString("id"), sprite, s.optString("name"), "general_store", "general", clerks(s.optString("id"), 1))
                    state == "open" && kind in setOf("market", "supermarket", "superstore") && !market -> {
                        market = true
                        add(s.optString("id"), sprite, s.optString("name"), "market", "market", clerks(s.optString("id"), 3))
                    }
                    // (a store's building reused as another shop: the antiques dealer in the old general store)
                    state == "reused" && reuse != null && reuse.optString("kind") in setOf("antiques", "thrift", "hardware", "general") ->
                        add(s.optString("id"), sprite, reuse.optString("name"), "general_store", reuse.optString("kind").let { if (it == "antiques") it else "general" }, clerks(s.optString("id"), 1))
                }
            }
            // the hand-made landmarks: the pawn shop and the police station
            val glb = g.optJSONArray("glbLandmarks")
            if (glb != null) for (i in 0 until glb.length()) {
                val l = glb.getJSONObject(i)
                if (l.optString("state") != "open") continue
                when (l.optString("id")) {
                    "glb:pawn_shop" -> add("glb:pawn_shop", l.optString("sprite"), l.optString("name"), "pawn_shop", "pawn", clerks("glb:pawn_shop", 1))
                    "glb:police_station" -> add("glb:police_station", l.optString("sprite"), l.optString("name"), "police_station", "police", emptyList())
                }
            }
            // the small shops with someone keeping them (street.json shops: Keller's Bakery, Ingram's Toy Store ...)
            val shops = street.optJSONArray("shops")
            val shopByName = HashMap<String, String>()
            if (shops != null) for (i in 0 until shops.length()) {
                val s = shops.getJSONObject(i)
                val kind = s.optString("kind").lowercase()
                val sprite = s.optString("sprite")
                if (kind !in LoopItems.RETAIL || s.optBoolean("home")) continue
                val owner = byBuilding[sprite]?.firstOrNull { it.role == "owner" } ?: continue
                val staff = listOf(owner.id) + raws.filter { it.job == "works at ${s.optString("name")}" && it.id != owner.id }.map { it.id }
                val id = "shop:$sprite"
                add(id, sprite, s.optString("name"), "general_store", kind, staff.distinct())
                shopByName.putIfAbsent(s.optString("name"), id)
            }
            // the homes: every building someone lives at
            for ((sp, rs) in byBuilding) if (rs.any { it.role != "owner" }) add("home:$sp", sp, "home of ${rs.first { it.role != "owner" }.name}", "home", "home", emptyList())
            val placeIds = places.map { it.id }.toSet()
            val shopOfSprite = places.filter { it.kind != "home" }.associate { it.sprite to it.id }

            // ---- relationships: spouses and children from the family facts, then coworkers, neighbours, housemates
            val married = Regex("""Married to ([^.\n]+)\.""")
            val childrenRe = Regex("""Children: ([^.\n]+)\.""")
            val workAt = HashMap<String, String?>()
            for (r in raws) workAt[r.id] = when {
                r.role == "owner" -> shopOfSprite[r.building]
                r.job.startsWith("works at ") -> shopByName[r.job.removePrefix("works at ")]
                else -> null
            }
            // (the loop's clerks work at their store)
            for (p in places) for (s in p.staff) if (workAt[s] == null) workAt[s] = p.id
            val people = ArrayList<LoopPerson>()
            val byWork = raws.groupBy { workAt[it.id] }
            for (r in raws) {
                val spouse = married.find(r.family)?.groupValues?.get(1)?.trim()?.let { n -> byName[n]?.firstOrNull { it.building == r.building } ?: byName[n]?.firstOrNull() }?.id
                val kids = childrenRe.find(r.family)?.groupValues?.get(1)?.split(',', '&')?.map { it.trim().removePrefix("and ").trim() }?.mapNotNull { first ->
                    byBuilding[r.building]?.firstOrNull { it.id != r.id && it.name.substringBefore(' ') == first && it.id != spouse }?.id
                }.orEmpty()
                val friends = LinkedHashMap<String, Double>()
                // housemates (not the spouse or children: the facade knows those)
                for (o in byBuilding[r.building].orEmpty()) if (o.id != r.id && o.id != spouse && o.id !in kids && o.role != "owner" && r.role != "owner") friends[o.id] = .6
                // coworkers
                workAt[r.id]?.let { w -> for (o in byWork[w].orEmpty()) if (o.id != r.id) friends.putIfAbsent(o.id, .45) }
                // next-door neighbours (the same row, the building beside)
                if (r.row != null && r.rowIndex >= 0) for (o in raws) if (o.id != r.id && o.row == r.row && abs(o.rowIndex - r.rowIndex) == 1 && o.role != "owner") friends.putIfAbsent(o.id, .35)
                val home = if (r.role == "owner") shopOfSprite[r.building] ?: "home:${r.building}" else "home:${r.building}"
                val names = r.name.split(' ')
                people += LoopPerson(
                    id = r.id, name = r.name, first = r.o.optString("first").ifEmpty { names.first() }, surname = names.drop(1).joinToString(" ").ifEmpty { names.first() },
                    female = r.o.optString("sex") == "F", age = r.o.optInt("age", 30), building = r.building,
                    homePlaceId = if (home in placeIds) home else places.first().id, workplacePlaceId = workAt[r.id]?.takeIf { it in placeIds },
                    spouseId = spouse?.takeIf { it != r.id }, childIds = kids, friends = friends.entries.take(12).map { it.key to it.value },
                    charm = r.o.optDouble("warmth", .5).coerceIn(0.0, 1.0),
                )
            }

            // ---- the street graph: the town's junctions, numbered along a Hilbert curve so the facade's patrol beats
            // (runs of consecutive node ids) are a few neighbouring corners, not junctions scattered over the town
            val x0 = town.nodes.minOf { it.x }; val z0 = town.nodes.minOf { it.z }
            val span = maxOf(town.nodes.maxOf { it.x } - x0, town.nodes.maxOf { it.z } - z0).coerceAtLeast(1f)
            val sorted = town.nodes.sortedBy { n -> hilbert(((n.x - x0) / span * 255f).toInt(), ((n.z - z0) / span * 255f).toInt()) }
            val nodeId = HashMap<Int, String>()
            val nodes = ArrayList<LoopNode>()
            sorted.forEachIndexed { i, n ->
                val id = "node:%03d".format(i)
                nodeId[n.id] = id
                nodes += LoopNode(id, n.x, n.z, if (n.kind == NodeKind.CROSS || n.kind == NodeKind.TEE) "intersection" else "street")
            }
            val edges = ArrayList<Triple<String, String, Double>>()
            for (r in town.roads) {
                val a = nodeId[r.a] ?: continue; val b = nodeId[r.b] ?: continue
                var len = 0.0
                for (i in 1 until r.pts.size) len += hypot((r.pts[i].x - r.pts[i - 1].x).toDouble(), (r.pts[i].z - r.pts[i - 1].z).toDouble())
                edges += Triple(a, b, len / VehicleWorld.U)
            }
            // the police station's door, joined to the nearest junction
            places.firstOrNull { it.kind == "police_station" }?.let { s ->
                nodes += LoopNode(STATION_NODE, s.doorX, s.doorZ, "station")
                town.nodes.minByOrNull { hypot(it.x - s.doorX, it.z - s.doorZ) }?.let { n ->
                    edges += Triple(STATION_NODE, nodeId.getValue(n.id), hypot((n.x - s.doorX).toDouble(), (n.z - s.doorZ).toDouble()) / VehicleWorld.U)
                }
            }
            // ---- the lamps (the rows' furniture)
            val lamps = ArrayList<FloatArray>()
            for (r in town.rows) for (f in r.furniture) if (f.kind == FurnitureKind.LAMP) r.pointAt(f.x, 5f).let { lamps += floatArrayOf(it.x, it.z) }

            return LoopTown(town, seed, year, dialogue.optString("townName", seed), "jur:$seed", places, people, nodes, edges, lamps)
        }
    }
}
