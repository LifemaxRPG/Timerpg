package com.lifemax.rpg.feature.habits.domain

/*
 * The hand-made landmark models the town places (street.json v2's `graph.glbLandmarks`: tools/town-glb.ts,
 * docs/LANDMARKS_GLB.md "In the town"): which row slot each stands in, which model, and its state in the export's year.
 * Their plots are left open by the collision and the street's furniture as the stores' are ([StoreLots]); what is solid
 * there, and the doors, come from the model as built (scene3d's TownLandmark).
 */

/** A model standing in row slot [sprite] of [row]: [kind] (landmark_glb_*), its business's [name] and [state] (open, closed, reused), nodes to [hide]. */
class GlbPlaced(val sprite: String, val row: String, val kind: String, val name: String, val state: String, val hide: Set<String>, val opened: Int,
    /** Which painted variant of the model (the works' name). */
    val variant: Int = 0)

object GlbLandmarks {
    /** The models standing in a street.json root (none in a v1 file or a town without them). */
    fun parse(root: org.json.JSONObject): List<GlbPlaced> {
        val a = root.optJSONObject("graph")?.optJSONArray("glbLandmarks") ?: return emptyList()
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val h = o.optJSONArray("hide")
            GlbPlaced(o.getString("sprite"), o.optString("row"), o.getString("kind"), o.optString("name"), o.optString("state", "open"),
                h?.let { (0 until it.length()).map { k -> it.getString(k) }.toSet() } ?: emptySet(), o.optInt("opened"), o.optInt("variant"))
        }
    }

    fun parse(streetJson: String): List<GlbPlaced> = runCatching { parse(org.json.JSONObject(streetJson)) }.getOrDefault(emptyList())
}
