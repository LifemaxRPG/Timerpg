package com.timerpg.street.dialogue

import org.json.JSONArray
import org.json.JSONObject

/** One thing the player can ask, with the game's own line for it and (if the model may voice it) the model's prompt. */
data class Topic(
    val intent: String,
    val label: String,
    /** How well the player is assumed to know them for this line (rumors and secrets are asked as a friend). */
    val familiarity: Int,
    /** The game's line (narrate / ask): shown at once, and whenever the model's reply is missing or rejected. */
    val template: String,
    /** The full ChatML prompt, or null if this intent is always the template. */
    val prompt: String?,
    /** Words the model's reply must contain; the speaker's full name, which it must not. */
    val must: List<String>,
    val self: String,
    /** Their last replies in this conversation (a free question's): the check turns down a reply that says one again. */
    val recent: List<String> = emptyList(),
)

/** Someone the player can talk to at a building. */
data class Npc(
    val id: String,
    val name: String,
    val first: String,
    val age: Int,
    /**
     * Sprite name of the building (as in street.json, v1 or v2: the key people are looked up by), its name, and on the v1
     * two streets the street (0 main, 1 cross) and its place along it (-1 for a building elsewhere in the town).
     */
    val building: String,
    val buildingName: String,
    val street: Int,
    val index: Int,
    /** owner (of the shop), head (of the household) or resident. */
    val role: String,
    val job: String,
    val topics: List<Topic>,
    /** For their voice: female or not, and how warm (0) gruff to (1) warm they are. */
    val female: Boolean = false,
    val warmth: Float = .5f,
    /** Whole town (dialogue.json v2): the row of the walkable town the building stands in (street.json v2 `rows[].id`)
     *  and its index along it, when the graph was known at export (null / -1 otherwise). */
    val row: String? = null,
    val rowIndex: Int = -1,
    /** The building's lot (the game's slot id) and its neighbourhood (id and name); empty in v1. */
    val lot: String = "",
    val neighbourhood: String = "",
    val neighbourhoodName: String = "",
)

/**
 * assets/dialogue.json, written by tools/export-dialogue.ts from our game logic: v1, the people of the two streets, or
 * v2 (`--all`), the people of every building of the town. Either way people are found by their building's sprite name.
 */
class DialogueData(
    val town: String,
    val year: Int,
    val townName: String,
    val streets: List<String>,
    val rules: String,
    val npcs: List<Npc>,
    /** 1: the two streets; 2: the whole town. */
    val version: Int = 1,
) {
    private val byBuilding: Map<String, List<Npc>> = npcs.groupBy { it.building }
    private val byRow: Map<String, List<Npc>> by lazy { npcs.filter { it.row != null }.groupBy { it.row!! } }

    /** The people to talk to at a building (by sprite name), the owner or household head first. */
    fun at(building: String?): List<Npc> = if (building == null) emptyList() else byBuilding[building].orEmpty()

    /** Every building (sprite name) that has someone to talk to. */
    val buildings: Set<String> get() = byBuilding.keys

    /** The people along a row of the walkable town (v2 with rows known), in order along it. */
    fun inRow(row: String): List<Npc> = byRow[row].orEmpty().sortedBy { it.rowIndex }

    companion object {
        /** Prompts longer than this cannot be right (the model's context is 1024 tokens); such topics use the template. */
        /** About 850 tokens: room for the reply in the 1024-token context. */
        private const val MAX_PROMPT = 3500

        /** Parses the file; a malformed NPC or topic is skipped, not fatal. Throws only if the file is not JSON at all. */
        fun parse(text: String): DialogueData {
            val j = JSONObject(text)
            val npcs = ArrayList<Npc>()
            val arr = j.optJSONArray("npcs") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                try {
                    val topics = ArrayList<Topic>()
                    val ts = o.optJSONArray("topics") ?: JSONArray()
                    for (k in 0 until ts.length()) {
                        val t = ts.optJSONObject(k) ?: continue
                        val template = t.optString("template")
                        if (template.isEmpty()) continue
                        // (org.json gives "null" for a JSON null through optString)
                        val prompt = if (t.isNull("prompt")) null else t.optString("prompt").takeIf { it.isNotEmpty() && it.length <= MAX_PROMPT }
                        topics += Topic(
                            intent = t.optString("intent", "chat"),
                            label = t.optString("label").ifEmpty { t.optString("intent", "Talk") },
                            familiarity = t.optInt("familiarity", 0),
                            template = template,
                            prompt = prompt,
                            must = strings(t.optJSONArray("must")),
                            self = t.optString("self"),
                        )
                    }
                    val name = o.optString("name")
                    if (name.isEmpty() || topics.isEmpty()) continue
                    npcs += Npc(
                        id = o.optString("id"),
                        name = name,
                        first = o.optString("first").ifEmpty { name.substringBefore(' ') },
                        age = o.optInt("age", 0),
                        building = o.optString("building"),
                        buildingName = o.optString("buildingName"),
                        street = o.optInt("street", 0),
                        index = o.optInt("index", 0),
                        role = o.optString("role"),
                        job = o.optString("job"),
                        topics = topics,
                        female = o.optString("sex") == "F",
                        warmth = o.optDouble("warmth", .5).toFloat(),
                        // a row id may be a number or a string in street.json v2; JSON null means not known yet
                        row = if (o.isNull("row")) null else o.opt("row")?.toString(),
                        rowIndex = if (o.isNull("rowIndex")) -1 else o.optInt("rowIndex", -1),
                        lot = o.optString("lot"),
                        neighbourhood = o.optString("neighbourhood"),
                        neighbourhoodName = o.optString("neighbourhoodName"),
                    )
                } catch (e: Exception) {
                    // skip this one
                }
            }
            return DialogueData(
                town = j.optString("town"),
                year = j.optInt("year", 0),
                townName = j.optString("townName"),
                streets = strings(j.optJSONArray("streets")),
                rules = j.optString("rules"),
                npcs = npcs,
                version = j.optInt("version", 1),
            )
        }

        private fun strings(a: JSONArray?): List<String> =
            if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.opt(it) as? String }
    }
}
