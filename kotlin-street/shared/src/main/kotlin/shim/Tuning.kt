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
)

/** All of the game's own art that replaces the habit scene's procedural art, installed by the platform. */
object SpriteArt {
    /** Buildings by stack id. */
    val buildings = HashMap<String, SpriteBuilding>()
    var lamp: ImageBitmap? = null
    var trees: List<ImageBitmap> = emptyList()
    var bench: ImageBitmap? = null
    const val SRC_W = 224f
    const val MID_H = 128f
}
