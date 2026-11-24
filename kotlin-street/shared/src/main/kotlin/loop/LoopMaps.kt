package com.lifemax.rpg.feature.habits.loop

import com.lifemax.rpg.feature.habits.maps.MapRules
import com.lifemax.rpg.feature.habits.maps.MapTier
import com.lifemax.rpg.feature.habits.maps.OwnedMap

/** A map for sale at a shop (docs/MAPS.md "Shops sell the maps of their era"): what it is and its price in the era's cents (0: free). */
class MapOffer(val id: String, val tier: MapTier, val area: String, val label: String, val cents: Int, val text: String, val owned: Boolean)

/** The player's maps for the UI: the paper maps owned, whether the phone has GPS this year, the waypoint (street x, z), the offers by place. */
class MapsView(
    val owned: List<OwnedMap> = emptyList(),
    val gps: Boolean = false,
    val region: String = "",
    val waypoint: FloatArray? = null,
    val offers: Map<String, List<MapOffer>> = emptyMap(),
)

/**
 * Which maps each loop place sells (Kotlin's own stock, beside the economy's shelves; docs/MAPS.md): city maps at the
 * general store, the market (its newsstand rack), the drugstore, the gas station and the railway station; the regional
 * road map at the gas station (free in the road-map era, 1920-1980), the market and the general store; the world map at
 * the market and the station (the town has no bookshop yet). Prices: [MapRules.priceCents].
 */
object LoopMaps {
    /** The railway station's map rack (it is not a loop place: the depot's ticket window). */
    const val STATION = "station"

    fun tiersAt(placeId: String, shopKind: String?): List<MapTier> = when {
        placeId == STATION -> listOf(MapTier.CITY, MapTier.WORLD)
        shopKind == "gas station" -> listOf(MapTier.REGIONAL, MapTier.CITY)
        shopKind == "market" -> listOf(MapTier.CITY, MapTier.REGIONAL, MapTier.WORLD)
        shopKind == "general" || shopKind == "antiques" -> listOf(MapTier.CITY, MapTier.REGIONAL)
        shopKind == "pharmacy" -> listOf(MapTier.CITY)
        else -> emptyList()
    }

    fun area(tier: MapTier, townSeed: String, region: String): String = when (tier) {
        MapTier.CITY -> townSeed
        MapTier.REGIONAL -> region
        MapTier.WORLD -> OwnedMap.WORLD
    }

    fun label(tier: MapTier, townName: String, year: Int): String = when (tier) {
        MapTier.CITY -> "City map of $townName ($year)"
        MapTier.REGIONAL -> "Road map of the region ($year)"
        MapTier.WORLD -> "World map ($year)"
    }

    fun loadOwned(a: org.json.JSONArray?): List<OwnedMap> = a?.let { (0 until it.length()).mapNotNull { k -> OwnedMap.of(it.getJSONObject(k)) } }.orEmpty()

    /** Whether the player already holds this exact print (the same tier, area and year). */
    fun has(owned: List<OwnedMap>, tier: MapTier, area: String, year: Int) = owned.any { it.tier == tier && it.area == area && it.printYear == year }

}
