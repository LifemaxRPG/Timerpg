package com.lifemax.rpg.feature.habits.maps

import org.json.JSONObject

/** The three tiers of map (docs/MAPS.md): the world, a region (a continent), one town. Ordered from the least detail. */
enum class MapTier(val word: String) {
    WORLD("World map"), REGIONAL("Road map"), CITY("City map");

    companion object { fun of(s: String): MapTier? = entries.firstOrNull { it.name.equals(s, ignoreCase = true) } }
}

/**
 * A paper map the player owns: its [tier], the [area] it covers (a town's seed for a city map, a region's id for a road
 * map, [WORLD] for the world) and the year it was printed in ([printYear]: it shows the world as it was then). Saved in
 * the loop's save (LoopGame "maps").
 */
data class OwnedMap(val tier: MapTier, val area: String, val printYear: Int, val label: String) {
    val id: String get() = "${tier.name.lowercase()}|$area|$printYear"
    fun json(): JSONObject = JSONObject().put("tier", tier.name).put("area", area).put("printYear", printYear).put("label", label)

    companion object {
        const val WORLD = "world"
        fun of(o: JSONObject): OwnedMap? = MapTier.of(o.optString("tier"))?.let { OwnedMap(it, o.optString("area"), o.optInt("printYear"), o.optString("label")) }
    }
}

/** Where the player is, for the map rules: the town they are in (null outside every town), its region, and whether at sea. */
class MapHere(val town: String?, val region: String, val atSea: Boolean = false, val onNamedStreet: Boolean = false)

/**
 * What the player's maps give them here and now (docs/MAPS.md "The rules"), worked out by [MapRules.access]:
 * - [gps]: the phone holds every tier everywhere, with live position and a route line;
 * - [tiers]: the tiers the player can open here (GPS: all three; else the paper maps that cover here), each with the
 *   year its map was printed ([printYear]; GPS: the current year);
 * - [minimap]: the tier the minimap draws (the best of [tiers] for where they are), or null: only the compass;
 * - [showPosition]: whether the player's own dot is drawn (GPS always; on paper only where they can tell where they are);
 * - [route]: a route line along the roads to the waypoint (GPS), else a straight bearing only.
 */
class MapAccess(
    val gps: Boolean,
    val tiers: Map<MapTier, Int>,
    val minimap: MapTier?,
    val showPosition: Boolean,
) {
    val route: Boolean get() = gps
    val compassOnly: Boolean get() = minimap == null
    fun printYear(t: MapTier): Int? = tiers[t]
    /** The tier the full map opens on: the minimap's, else the best one owned. */
    val opens: MapTier? get() = minimap ?: tiers.keys.maxByOrNull { it.ordinal }
}

object MapRules {
    /**
     * The first year the player's phone has GPS maps (docs/MAPS.md): 2008, when phone maps with live GPS position became
     * common. Consumer GPS units for the car came about 2000, but the player carries a phone, not a dashboard unit (a car
     * GPS could be an item for 2000-2007 later). Before this year there is no GPS: paper maps only.
     */
    const val GPS_YEAR = 2008
    /** The road-map era (docs/MAPS.md): gas stations gave regional road maps away from about 1920 to about 1980. */
    const val FREE_ROAD_MAPS_FROM = 1920
    const val FREE_ROAD_MAPS_TO = 1980

    fun gps(year: Int): Boolean = year >= GPS_YEAR
    fun freeRoadMaps(year: Int): Boolean = year in FREE_ROAD_MAPS_FROM..FREE_ROAD_MAPS_TO

    /** Whether [m] covers [here]. */
    fun covers(m: OwnedMap, here: MapHere): Boolean = when (m.tier) {
        MapTier.CITY -> here.town != null && m.area == here.town
        MapTier.REGIONAL -> m.area == here.region
        MapTier.WORLD -> true
    }

    /**
     * The map rules for [year] with the paper maps [owned], the player [here]. The minimap draws the closest tier for
     * where they are: the city map in town, the road map on the road, the world map at sea (with GPS the phone has
     * them all). On paper the position shows only where the player can tell where they are: in town, on a street whose
     * sign they can read ([MapHere.onNamedStreet]: any road of the town, since its signs are readable), and nowhere on
     * the road or at sea yet (the last road sign passed comes with travel between towns).
     */
    fun access(year: Int, owned: List<OwnedMap>, here: MapHere): MapAccess {
        if (gps(year)) {
            val all = MapTier.entries.associateWith { year }
            val mini = when { here.atSea -> MapTier.WORLD; here.town != null -> MapTier.CITY; else -> MapTier.REGIONAL }
            return MapAccess(true, all, mini, true)
        }
        // the newest print of each tier that covers here
        val tiers = HashMap<MapTier, Int>()
        for (m in owned) if (covers(m, here)) tiers[m.tier] = maxOf(tiers[m.tier] ?: Int.MIN_VALUE, m.printYear)
        val want = when { here.atSea -> listOf(MapTier.WORLD); here.town != null -> listOf(MapTier.CITY, MapTier.REGIONAL); else -> listOf(MapTier.REGIONAL, MapTier.WORLD) }
        val mini = want.firstOrNull { it in tiers }
        val pos = mini != null && here.town != null && here.onNamedStreet && mini != MapTier.WORLD
        return MapAccess(false, tiers, mini, pos)
    }

    /**
     * What a map costs in [year]'s cents at the shop (docs/MAPS.md's 1952 prices: world $1.50, road map 25c, city 35c),
     * scaled by the economy's price index ([indexYear] / [index1952]) and rounded to the era's coin (a cent before 1980,
     * else five cents); 0 for a road map at a gas station in the road-map era.
     */
    fun priceCents(tier: MapTier, year: Int, indexYear: Double, index1952: Double, atGasStation: Boolean): Int {
        if (tier == MapTier.REGIONAL && atGasStation && freeRoadMaps(year)) return 0
        val base = when (tier) { MapTier.WORLD -> 150; MapTier.REGIONAL -> 25; MapTier.CITY -> 35 }
        val c = base * indexYear / index1952.coerceAtLeast(1e-6)
        val step = if (year < 1980) 1 else 5
        return maxOf(step, (Math.round(c / step) * step).toInt())
    }
}
