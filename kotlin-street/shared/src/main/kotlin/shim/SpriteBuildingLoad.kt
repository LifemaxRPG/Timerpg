package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Reads one of our buildings as tools/make_buildings.py wrote it (buildings/bN_*.png and windows.json), the same way for
 * the app and the desktop preview: each only says how a picture is opened.
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
    fun load(sprite: String, middles: Int, windows: Map<String, List<FloatArray>>, img: (String) -> ImageBitmap?): SpriteBuilding? {
        val front = img("${sprite}_front") ?: return null
        val side = img("${sprite}_side") ?: return null
        val emis = img("${sprite}_emis") ?: return null
        return SpriteBuilding(
            front, side, emis, img("${sprite}_roof"), img("${sprite}_sign"), img("${sprite}_prop"), middles,
            windows[sprite].orEmpty(), windows[sprite + "_shop"].orEmpty(), img("${sprite}_glass"),
            windows[sprite + "_relief"]?.firstOrNull()?.takeIf { it.size >= 7 }, img("${sprite}_over"),
            img("${sprite}_lit"), img("${sprite}_shade"),
        )
    }
}
