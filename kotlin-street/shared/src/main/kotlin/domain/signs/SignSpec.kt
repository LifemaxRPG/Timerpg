package com.lifemax.rpg.feature.habits.domain.signs

import org.json.JSONArray
import org.json.JSONObject

/**
 * Whether a building's signs receive dynamic owner names at runtime (`OWNER`: repeating shops,
 * diners, barbers, gas stations, funeral homes, motels, stores) or keep their baked names
 * (`FIXED`: unique landmarks, monuments, courthouse, ballpark, mini golf). Default is `FIXED`.
 */
enum class NamingMode {
    OWNER,
    FIXED;

    companion object {
        fun parse(raw: String?): NamingMode =
            if (raw != null && raw.trim().equals("owner", ignoreCase = true)) OWNER else FIXED
    }
}

/** Font face selection in a [SignSpec]; [AUTO] chooses by era year. */
enum class SignFace {
    AUTO,
    SERIF_BLOCK,
    ROUNDED,
    SANS;

    companion object {
        fun parse(raw: String?): SignFace = when (raw?.trim()?.lowercase()) {
            null, "", "auto" -> AUTO
            "serif", "serif-block", "serif_block", "block" -> SERIF_BLOCK
            "rounded", "round", "diner", "motel" -> ROUNDED
            "sans", "plain", "modern" -> SANS
            else -> AUTO
        }
    }
}

enum class SignAlign {
    LEFT,
    CENTER,
    RIGHT;

    companion object {
        fun parse(raw: String?): SignAlign = when (raw?.trim()?.lowercase()) {
            "left" -> LEFT
            "right" -> RIGHT
            else -> CENTER
        }
    }
}

/** Sign board rectangle `[x, y, width, height]` in texture texels. */
data class SignRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * Specification for one paintable sign region on a building's texture atlas.
 *
 * Colors ([ink], [paper], [outline], [glowColor]) are packed ARGB `0xAARRGGBB` integers,
 * matching `StreetTextures.argb`, `CinemaPosters`, and `PriceBoard` across `kotlin-street`.
 */
data class SignSpec(
    val id: String,
    val texture: String = DEFAULT_TEXTURE,
    val rect: SignRect,
    val lines: Int = 1,
    val maxChars: Int = 0,
    val face: SignFace = SignFace.AUTO,
    val ink: Int = DEFAULT_INK,
    val paper: Int = DEFAULT_PAPER,
    val outline: Int? = null,
    val align: SignAlign = SignAlign.CENTER,
    val glow: Boolean = false,
    val glowColor: Int = ink,
) {
    val clampedLines: Int get() = lines.coerceIn(1, 2)

    companion object {
        const val DEFAULT_TEXTURE = "signs.png"
        val DEFAULT_INK: Int = 0xFF1A1614L.toInt()
        val DEFAULT_PAPER: Int = 0xFFF2ECE0L.toInt()

        /**
         * Parses a color from `"#RRGGBB"`, `"#AARRGGBB"`, `"0xAARRGGBB"`, or `[r, g, b]` / `[r, g, b, a]`.
         * Returns `null` when missing or unparseable.
         */
        fun parseColor(value: Any?): Int? {
            when (value) {
                is Number -> return value.toInt()
                is String -> {
                    val s = value.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
                    if (s.length == 6) {
                        val rgb = s.toLongOrNull(16) ?: return null
                        return (0xFF000000L or rgb).toInt()
                    }
                    if (s.length == 8) {
                        val argb = s.toLongOrNull(16) ?: return null
                        return argb.toInt()
                    }
                    return null
                }
                is JSONArray -> {
                    if (value.length() < 3) return null
                    val r = value.optInt(0, 0).coerceIn(0, 255)
                    val g = value.optInt(1, 0).coerceIn(0, 255)
                    val b = value.optInt(2, 0).coerceIn(0, 255)
                    val a = if (value.length() >= 4) value.optInt(3, 255).coerceIn(0, 255) else 255
                    return (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                else -> return null
            }
        }

        private fun parseRect(arr: JSONArray?): SignRect? {
            if (arr == null || arr.length() < 4) return null
            val x = arr.optInt(0, -1)
            val y = arr.optInt(1, -1)
            val w = arr.optInt(2, 0)
            val h = arr.optInt(3, 0)
            if (x < 0 || y < 0 || w <= 0 || h <= 0) return null
            return SignRect(x, y, w, h)
        }

        fun fromJsonObject(obj: JSONObject, fallbackId: String = "sign"): SignSpec? {
            val rect = parseRect(obj.optJSONArray("rect") ?: obj.optJSONArray("rect_texels")) ?: return null
            val id = obj.optString("id", "").ifBlank { fallbackId }
            val texture = obj.optString("texture", "").ifBlank { DEFAULT_TEXTURE }
            val lines = obj.optInt("lines", 1).coerceIn(1, 2)
            val maxChars = obj.optInt("maxChars", obj.optInt("max_chars", 0)).coerceAtLeast(0)
            val face = SignFace.parse(if (obj.has("face")) obj.optString("face") else null)
            val ink = parseColor(obj.opt("ink")) ?: DEFAULT_INK
            val paper = parseColor(obj.opt("paper")) ?: DEFAULT_PAPER
            val outline = if (obj.has("outline") && !obj.isNull("outline")) parseColor(obj.opt("outline")) else null
            val align = SignAlign.parse(if (obj.has("align")) obj.optString("align") else null)
            val glow = obj.optBoolean("glow", false)
            val glowColor = parseColor(obj.opt("glowColor") ?: obj.opt("glow_color")) ?: ink
            return SignSpec(
                id = id,
                texture = texture,
                rect = rect,
                lines = lines,
                maxChars = maxChars,
                face = face,
                ink = ink,
                paper = paper,
                outline = outline,
                align = align,
                glow = glow,
                glowColor = glowColor,
            )
        }

        /**
         * Parses a `model-info.json` payload (reads top-level `"naming"` and `"signs"`, or falls back
         * to `"sign_panels"` when present). Missing `"naming"` defaults to [NamingMode.FIXED].
         */
        fun parseModelInfo(jsonText: String): ModelSignConfig {
            val root = JSONObject(jsonText)
            val naming = NamingMode.parse(if (root.has("naming")) root.optString("naming") else null)
            val out = ArrayList<SignSpec>()

            val signsArr = root.optJSONArray("signs")
            if (signsArr != null) {
                for (i in 0 until signsArr.length()) {
                    val item = signsArr.optJSONObject(i) ?: continue
                    val spec = fromJsonObject(item, "sign_$i")
                    if (spec != null) out.add(spec)
                }
            } else {
                val panels = root.optJSONObject("sign_panels")
                    ?: root.optJSONArray("variants")?.optJSONObject(0)?.optJSONObject("sign_panels")
                if (panels != null) {
                    val keys = panels.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val panelObj = panels.optJSONObject(key) ?: continue
                        val spec = fromJsonObject(panelObj, key)
                        if (spec != null) out.add(spec)
                    }
                }
            }
            return ModelSignConfig(naming, out)
        }

        /**
         * Parses the legacy `sign-regions.json` shape (`{ "name": [x, y, w, h], ... }`) with defaults.
         */
        fun parseSignRegions(
            jsonText: String,
            texture: String = DEFAULT_TEXTURE,
            naming: NamingMode = NamingMode.OWNER,
        ): ModelSignConfig {
            val root = JSONObject(jsonText)
            val out = ArrayList<SignSpec>()
            val keys = ArrayList<String>()
            val it = root.keys()
            while (it.hasNext()) keys.add(it.next())
            keys.sort()

            for (key in keys) {
                val arr = root.optJSONArray(key) ?: continue
                val rect = parseRect(arr) ?: continue
                val defaultLines = if (rect.height >= 26) 2 else 1
                out.add(
                    SignSpec(
                        id = key,
                        texture = texture,
                        rect = rect,
                        lines = defaultLines,
                    ),
                )
            }
            return ModelSignConfig(naming, out)
        }

        /**
         * Auto-detects `model-info.json` vs legacy `sign-regions.json` and parses into [ModelSignConfig].
         */
        fun parseJson(jsonText: String): ModelSignConfig {
            val root = JSONObject(jsonText)
            val looksLikeModelInfo =
                root.has("signs") ||
                    root.has("naming") ||
                    root.has("sign_panels") ||
                    root.has("model") ||
                    root.has("variants")
            return if (looksLikeModelInfo) parseModelInfo(jsonText) else parseSignRegions(jsonText)
        }
    }
}

/**
 * Parsed sign metadata for one building model.
 * Only models with `naming == NamingMode.OWNER` (`repaintable == true`) may have their signs repainted.
 */
data class ModelSignConfig(
    val naming: NamingMode = NamingMode.FIXED,
    val signs: List<SignSpec> = emptyList(),
) {
    val repaintable: Boolean get() = naming == NamingMode.OWNER

    fun signsForTexture(textureName: String): List<SignSpec> {
        if (!repaintable || signs.isEmpty()) return emptyList()
        return signs.filter { it.texture == textureName }
    }
}
