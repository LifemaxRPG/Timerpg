package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Reads one of our buildings as tools/make_buildings.py wrote it (buildings/bN_*.png, windows.json, houses.json and models.json), the
 * same way for the app and the desktop preview: each only says how a picture is opened.
 */
object SpriteBuildingLoad {
    /**
     * windows.json: every key's boxes (each a list of numbers). A malformed entry is skipped on its own, so one bad value
     * cannot lose every window in the file.
     */
    fun windows(text: String?): Map<String, List<FloatArray>> {
        if (text == null) return emptyMap()
        val j = try { org.json.JSONObject(text) } catch (e: Exception) { return emptyMap() }
        val out = HashMap<String, List<FloatArray>>()
        for (key in j.keys()) {
            val a = j.optJSONArray(key) ?: continue
            out[key] = (0 until a.length()).mapNotNull { k -> a.optJSONArray(k)?.let { r -> FloatArray(r.length()) { r.optDouble(it).toFloat() } } }
        }
        return out
    }

    /**
     * The building drawn as [sprite] (bN): null when its front, side or glow picture is missing (the engine then paints its
     * own building there, and the rest of the street is unaffected). [img] opens a picture by name (bN_front, ...) or
     * gives null; [middles] is how many 128-row middle floors its front has.
     */
    fun load(
        sprite: String, middles: Int, windows: Map<String, List<FloatArray>>,
        houses: Map<String, com.lifemax.rpg.feature.habits.domain.HouseShape> = emptyMap(),
        models: Map<String, com.lifemax.rpg.feature.habits.domain.ModelShape> = emptyMap(), img: (String) -> ImageBitmap?,
    ): SpriteBuilding? {
        val front = img("${sprite}_front") ?: return null
        val side = img("${sprite}_side") ?: return null
        val emis = img("${sprite}_emis") ?: return null
        val model = models[sprite]
        if (model != null) {
            // a villa or a landmark: without its atlas it cannot be built, and is better left to the engine
            val atlas = img("${sprite}_atlas") ?: return null
            val art = ModelArt(model, atlas, img("${sprite}_glow"), img("${sprite}_lawn"), img("${sprite}_fence"), img("${sprite}_fencesnow"))
            return SpriteBuilding(front, side, emis, null, null, null, 0, emptyList(), emptyList(), null, null, null, null, null, null, art)
        }
        val shape = houses[sprite]
        if (shape != null) {
            // a pitched-roof house: without its slope and ridge it cannot be built, and is better left to the engine
            val slope = img("${sprite}_slope") ?: return null
            val ridge = img("${sprite}_ridge") ?: return null
            val rows = com.lifemax.rpg.feature.habits.domain.HouseView.slopeRows(shape)
            // the rise was chosen so the slope shows one screen row per picture row; a changed view (BuildingSpec's W or
            // DY) needs tools/make_buildings.py run again
            if (kotlin.math.abs(rows - slope.height) > .05f) println("houses.json: $sprite's slope is ${slope.height} rows, the view makes it $rows: run make_buildings.py again")
            val art = HouseArt(shape, slope, ridge, img("${sprite}_back"), img("${sprite}_hitch"), img("${sprite}_lawn"), img("${sprite}_porch"), img("${sprite}_fence"), img("${sprite}_fencesnow"))
            return SpriteBuilding(front, side, emis, null, null, null, 0, emptyList(), emptyList(), null, null, null, null, null, art)
        }
        return SpriteBuilding(
            front, side, emis, img("${sprite}_roof"), img("${sprite}_sign"), img("${sprite}_prop"), middles,
            windows[sprite].orEmpty(), windows[sprite + "_shop"].orEmpty(), img("${sprite}_glass"),
            windows[sprite + "_relief"]?.firstOrNull()?.takeIf { it.size >= 7 }, img("${sprite}_over"),
            img("${sprite}_lit"), img("${sprite}_shade"),
            pitch = img("${sprite}_pitch"), pitchInfo = windows[sprite + "_pitch"]?.firstOrNull()?.takeIf { it.size >= 3 },
            postL = img("${sprite}_postl"), postR = img("${sprite}_postr"), gablePic = img("${sprite}_gablep"),
        )
    }

    /** models.json: the villas' and landmarks' parts by sprite name (none when the file is missing). */
    fun models(text: String?): Map<String, com.lifemax.rpg.feature.habits.domain.ModelShape> =
        com.lifemax.rpg.feature.habits.domain.ModelShape.parseAll(text)

    /** houses.json: the pitched-roof houses' shapes by sprite name (none when the file is missing). */
    fun houses(text: String?): Map<String, com.lifemax.rpg.feature.habits.domain.HouseShape> =
        com.lifemax.rpg.feature.habits.domain.HouseShape.parseAll(text)
}
