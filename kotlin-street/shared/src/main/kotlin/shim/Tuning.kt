package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.ui.graphics.ImageBitmap

/** Switches that turn the habit-tracker's scene into the game's street. */
object StageTuning {
    /** The habit-name plaques on every floor. Off for the game. */
    var plaques = false
    /** The dashed "FOR RENT" outline above the front building. Off for the game. */
    var ghost = false
    /** The shop names printed under the pavement (the habit tab's street names). Off for the game. */
    var streetNames = false
    /** A fixed zoom between one building (0) and the whole street (1); null follows the habit UI. */
    var zoom: Float? = 0.42f
    /** Draw the street's people as our pixel-art characters (HD-2D style) instead of the procedural ones. */
    var pixelPeople = true
    /** Cars, dogs and bicycles: we have no art for them yet. */
    var cars = false
    /** Snap the zoom so every sprite pixel is a whole (even) number of screen pixels, and the camera to whole pixels. */
    var pixelPerfect = true
    /** Furnished rooms and people behind the upper windows (the habit scene's). Off: the windows are plain reflective glass, until our own 2D interiors go in. */
    var interiors = false
    /** Draw only the sky, clouds and distant city (the corner turn draws the street over it, the same sky behind). */
    @Volatile var skyOnly = false
    /** The habit scene's out-of-focus foreground (railings, sign posts, tree branches sliding past the camera). Its art, not ours: off. */
    var foreground = false
    /** How much bigger than the city-scale passers the people are drawn (a game wants a readable character). */
    var personScale = 3.0f
}

/** Our characters' sheets (32x64 frames, 6 per direction; row 1 idle, row 2 walk), installed by the platform. */
object SpriteSheets {
    var sheets: List<ImageBitmap> = emptyList()
}

/** One of our buildings, composed offline from our sprites (see tools/make_buildings.py). */
class SpriteBuilding(
    val front: ImageBitmap,
    val side: ImageBitmap,
    val emis: ImageBitmap,
    val roof: ImageBitmap?,
    val sign: ImageBitmap?,
    val prop: ImageBitmap?,
    /** How many 128-row middle floors the front has (the rest is the storefront). */
    val middles: Int,
    /** The real windows (x, y, w, h in front-sprite pixels): their glass is cut out of [front]; the engine draws the rooms behind. */
    val windows: List<FloatArray> = emptyList(),
    /** The shopfront glass (x, y, w, h): not cut out (the display shows through); the engine lays a faint reflection over it. */
    val shop: List<FloatArray> = emptyList(),
    /** A mask (white where the shopfront has real glass) to lay that reflection through. */
    val glass: ImageBitmap? = null,
    /** What sticks out of the front (a balcony), cut off it into its own front-sized picture, or null. */
    val relief: ImageBitmap? = null,
    /** How far [relief] stands out from the wall, in sprite pixels (the side face's 120 pixels are the box's depth). */
    val reliefDepth: Float = 0f,
)

/** All of the game's own art that replaces the habit scene's procedural art, installed by the platform. */
object SpriteArt {
    /** Buildings by stack id. */
    val buildings = HashMap<String, SpriteBuilding>()
    var lamp: ImageBitmap? = null
    var trees: List<ImageBitmap> = emptyList()
    var bench: ImageBitmap? = null
    const val SRC_W = 224f
    /** Street units per sprite pixel (a building's front is B.W units for its 224 pixels). */
    val PIXEL: Float get() = com.lifemax.rpg.feature.habits.domain.BuildingSpec.W / SRC_W

    /** The highest point of any of our buildings above the pavement, in street units: its front, then its sign or rooftop billboard. */
    val tallest: Float get() {
        var best = 0f
        for (b in buildings.values) {
            val over = b.sign?.height?.toFloat() ?: b.prop?.let { it.height + com.lifemax.rpg.feature.habits.domain.BuildingSpec.DY * .5f / PIXEL } ?: 0f
            best = maxOf(best, (b.front.height + over) * PIXEL)
        }
        return best
    }
    const val MID_H = 128f
}
