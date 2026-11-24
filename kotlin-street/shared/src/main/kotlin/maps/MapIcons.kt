package com.lifemax.rpg.feature.habits.maps

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint

/*
 * The maps' pixel-art icons (docs/MAPS.md, "The minimap's look"): a badge for each kind of place, the player's home, the
 * waypoint's pin and the compass letters, each drawn once into a tiny picture of its own (11 x 11 or 5 x 5 pixels) and
 * scaled up on screen with nearest filtering: the same hard pixels at every size, nothing made per frame.
 */

/**
 * The kinds of place the maps mark with an icon, each with its badge colour, its 7 x 7 glyph and its [rank] (bigger
 * shows first and from further out: civic buildings and landmarks from the widest zoom, shops only close in).
 */
enum class PoiKind(val word: String, val badge: Int, val rank: Int, glyph: String) {
    HOME("Your room", 0xFF2E9BE8.toInt(), 100, "...X... ..XXX.. .XXXXX. XXXXXXX .X...X. .X.XXX. .X.XXX."),
    STATION("Station", 0xFF4D5F80.toInt(), 90, ".XXXXX. X.X.X.X XXXXXXX XXXXXXX XX.X.XX .X...X. X.....X"),
    POLICE("Police", 0xFF2F5FD0.toInt(), 85, "...X... ..XXX.. XXXXXXX .XXXXX. ..XXX.. .XX.XX. .X...X."),
    HEALTH("Doctor, drugstore", 0xFFD84545.toInt(), 70, "..XXX.. ..XXX.. XXXXXXX XXXXXXX XXXXXXX ..XXX.. ..XXX.."),
    FIRE("Fire station", 0xFFD0602F.toInt(), 60, "...X... ..XX... ..XXX.. .XXXXX. XXX.XXX XX...XX .XXXXX."),
    LANDMARK("Landmark", 0xFFA45FB8.toInt(), 65, "...X... ..XXX.. ..XXX.. ..XXX.. ..XXX.. .XXXXX. XXXXXXX"),
    GAS("Gas", 0xFFE07A1F.toInt(), 55, "XXXX... X..X.X. X..X..X XXXX..X XXXX..X XXXX.X. XXXX..."),
    BANK("Bank", 0xFF2E9A5A.toInt(), 50, "...X... .XXXXX. X..X... .XXXXX. ...X..X .XXXXX. ...X..."),
    POST("Post office", 0xFFC7962A.toInt(), 45, "....... XXXXXXX XX...XX X.X.X.X X..X..X XXXXXXX ......."),
    HOTEL("Hotel, lodging", 0xFF3E9C9C.toInt(), 45, "....... X...... X.XX... XXXXXXX XXXXXXX X.....X ......."),
    CHURCH("Church", 0xFF7F6AB8.toInt(), 35, "...X... ...X... .XXXXX. ...X... ...X... ...X... ...X..."),
    SCHOOL("School", 0xFFD9A02C.toInt(), 35, ".....XX ....XXX ...XXX. ..XXX.. .XXX... XX..... X......"),
    LIBRARY("Library", 0xFF5E9446.toInt(), 30, "....... XXX.XXX X.X.X.X XXX.XXX X.X.X.X XXXXXXX ......."),
    FUN("Cinema, nightlife", 0xFFC0479A.toInt(), 30, "...X... ...X... ..XXX.. XXXXXXX ..XXX.. ...X... ...X..."),
    FOOD("Food", 0xFFE0893F.toInt(), 20, "X.X..X. X.X..XX XXX..XX .X...XX .X...X. .X...X. .X...X."),
    GYM("Gym", 0xFF7A8290.toInt(), 15, "....... XX...XX XX...XX XXXXXXX XX...XX XX...XX ......."),
    SERVICE("Services", 0xFF5F7096.toInt(), 10, "...X... ....... ..XX... ...X... ...X... ...X... ..XXX.."),
    SHOP("Shop", 0xFFE5843F.toInt(), 12, "..XXX.. .X...X. XXXXXXX XXXXXXX XXXXXXX XXXXXXX .XXXXX.");

    /** The glyph's rows ('X' lit). */
    val rows: List<String> = glyph.split(' ')

    companion object {
        /**
         * The icon for a place of [kind] (street.json's shop kind or a landmark's kind) named [name]; null for homes and
         * for things that are not places to go (a car park).
         */
        fun of(kind: String, name: String): PoiKind? {
            val k = (kind + " " + name).lowercase()
            fun has(vararg w: String) = w.any { it in k }
            return when {
                has("home") && !has("funeral") -> null
                has("police") -> POLICE
                has("fire_station", "fire station") -> FIRE
                has("post_office", "post office") -> POST
                has("doctor", "drugstore", "pharmacy", "hospital", "clinic") -> HEALTH
                has("church", "chapel") -> CHURCH
                has("school", "elementary") -> SCHOOL
                has("library") -> LIBRARY
                has("hotel", "lodge", "motel", " inn") -> HOTEL
                has("gas_station", "gas station", "fuel") -> GAS
                has("bank") -> BANK
                has("cinema", "theater", "theatre", "video", "nightclub", "foundry", "ballpark", "baseball", "radio") -> FUN
                has("diner", "bakery", "ice cream", "butcher", "cafe", "larder", "brew", "carhop", "restaurant", "market", "grocer", "co-op", "pizza", "bar") -> FOOD
                has("gym") -> GYM
                has("clocktower", "clock tower", "arch", "rex", "water_tower", "water tower", "eyrie", "night_keeper", "night keeper", "monument", "statue", "elevator", "mill", "rest_area", "meridian") -> LANDMARK
                has("town_hall", "town hall", "city hall", "courthouse", "gazette", "funeral", "laundr", "barber", "salon", "nail", "law", "cell fix", "office") -> SERVICE
                has("extra") -> null
                else -> SHOP
            }
        }
    }
}

/** A place the maps mark: street ([x], [z]), its [kind] and [name]. */
class MapPoi(val x: Float, val z: Float, val kind: PoiKind, val name: String)

/** The pixel pictures, made once (the first use) and shared by the minimap, the full map and the legend. */
object MapIcons {
    const val BADGE = 11
    private val OUTLINE = 0xFF0E1118.toInt()
    private const val WHITE = -1

    /** Each kind's badge: a dark rim, the colour with a lit top row and a shaded bottom row, the white glyph. */
    val badges: Map<PoiKind, ImageBitmap> by lazy { PoiKind.entries.associateWith { badge(it.badge, it.rows) } }

    /** The waypoint's pin (11 x 15: a pink head with a white dot, a dark stem to the point). */
    val pin: ImageBitmap by lazy {
        picture(11, 15, arrayOf(
            "...OOOOO...", "..OPPPPPO..", ".OPPLLLPPO.", "OPPLWWWLPPO", "OPPLWWWLPPO", "OPPLWWWLPPO", "OPPPLLLPPPO", ".OPPPPPPPO.",
            "..OPPPPPO..", "...OPPPO...", "....OPO....", "....OPO....", "....OPO....", ".....O.....", "...........",
        ), mapOf('O' to OUTLINE, 'P' to 0xFFFF4FA3.toInt(), 'L' to 0xFFFF9ACB.toInt(), 'W' to WHITE))
    }

    /** The compass letters (5 x 5), lit [col] on clear. */
    fun letter(ch: Char, col: Int): ImageBitmap = picture(7, 7, LETTERS.getValue(ch).let { rows ->
        Array(7) { y -> if (y == 0 || y == 6) "......." else "." + rows[y - 1] + "." }
    }.let { r -> outlined(r) }, mapOf('X' to col, 'O' to OUTLINE))

    private val LETTERS = mapOf(
        'N' to arrayOf("X...X", "XX..X", "X.X.X", "X..XX", "X...X"),
        'E' to arrayOf("XXXXX", "X....", "XXXX.", "X....", "XXXXX"),
        'S' to arrayOf(".XXXX", "X....", ".XXX.", "....X", "XXXX."),
        'W' to arrayOf("X...X", "X...X", "X.X.X", "XX.XX", "X...X"),
    )

    /** Rows of 'X' get an 'O' rim round them (the letters' dark edge). */
    private fun outlined(r: Array<String>): Array<String> {
        val h = r.size; val w = r[0].length
        return Array(h) { y -> String(CharArray(w) { x ->
            if (r[y][x] == 'X') 'X'
            else if ((-1..1).any { dy -> (-1..1).any { dx -> r.getOrNull(y + dy)?.getOrNull(x + dx) == 'X' } }) 'O' else '.'
        }) }
    }

    private fun badge(col: Int, glyph: List<String>): ImageBitmap {
        val n = BADGE
        val px = IntArray(n * n)
        val light = MapRaster.tint(col, .28f); val dark = MapRaster.shade(col, .7f)
        for (y in 0 until n) for (x in 0 until n) {
            val corner = (x == 0 || x == n - 1) && (y == 0 || y == n - 1)
            val c = when {
                corner -> 0
                x == 0 || y == 0 || x == n - 1 || y == n - 1 -> OUTLINE
                (x == 1 || x == n - 2) && (y == 1 || y == n - 2) -> OUTLINE
                y == 1 -> light
                y == n - 2 -> dark
                else -> col
            }
            px[y * n + x] = c
        }
        for (y in 0 until 7) for (x in 0 until 7) if (glyph.getOrNull(y)?.getOrNull(x) == 'X') px[(y + 2) * n + x + 2] = WHITE
        return image(px, n, n)
    }

    private fun picture(w: Int, h: Int, rows: Array<String>, cols: Map<Char, Int>): ImageBitmap {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) px[y * w + x] = cols[rows.getOrNull(y)?.getOrNull(x) ?: '.'] ?: 0
        return image(px, w, h)
    }

    /** A tiny picture from pixels (the platform's way where it has one, else plain rectangles). */
    private fun image(px: IntArray, w: Int, h: Int): ImageBitmap {
        MapImages.fast?.let { return it(px, w, h) }
        try { return com.lifemax.rpg.feature.habits.presentation.scene.PlatformImages.create(px, w, h, false) } catch (_: UninitializedPropertyAccessException) {}
        val img = ImageBitmap(w, h)
        val c = Canvas(img)
        val p = Paint().apply { isAntiAlias = false }
        for (y in 0 until h) for (x in 0 until w) { val v = px[y * w + x]; if (v != 0) { p.color = Color(v); c.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, p) } }
        return img
    }
}
