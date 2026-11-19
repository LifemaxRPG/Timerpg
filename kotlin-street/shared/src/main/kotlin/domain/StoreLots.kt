package com.lifemax.rpg.feature.habits.domain

/*
 * The town's store plots as the walk and the street's furniture need them (docs/STORES.md): which row slots are stores
 * (street.json v2's `graph.storeSites` / `graph.stores` and their landmarks), so the collision does not make the whole plot
 * one solid box, no door is guessed at the plot's front edge, and lamps, benches and props keep off their fronts. What is
 * solid on a store's plot, and where its doors are, comes from the store as built (scene3d's TownStores hands them to
 * [TownCollision.setExtra] and [TownCollision.setExtraDoors]).
 */

/** One store's plot: the row slot's [sprite], its site ([site]: generalstore, market, supermarket, superstore), its landmark id. */
class StoreLot(val sprite: String, val site: String, val landmark: String)

object StoreLots {
    /** The landmark kinds that are stores' sites. */
    val KINDS = setOf("generalstore", "market", "supermarket", "superstore")

    /** The store plots in a street.json root (a v1 file or a town without stores: none). */
    fun parse(root: org.json.JSONObject): List<StoreLot> {
        val g = root.optJSONObject("graph") ?: return emptyList()
        val lms = g.optJSONArray("landmarks") ?: return emptyList()
        val out = ArrayList<StoreLot>()
        for (i in 0 until lms.length()) {
            val l = lms.getJSONObject(i)
            val kind = l.optString("kind")
            if (kind !in KINDS && !l.optString("art").startsWith("landmark_store_")) continue
            val sprite = l.optString("sprite")
            if (sprite.isBlank()) continue
            out += StoreLot(sprite, kind, l.optString("id"))
        }
        return out
    }

    fun parse(streetJson: String): List<StoreLot> = runCatching { parse(org.json.JSONObject(streetJson)) }.getOrDefault(emptyList())

    /** The sprites of the store plots in [g] (a `graph` object). */
    internal fun spritesOf(g: org.json.JSONObject): Set<String> =
        parse(org.json.JSONObject().put("graph", g)).map { it.sprite }.toSet()
}
