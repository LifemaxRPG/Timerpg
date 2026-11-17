package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.ui.graphics.ImageBitmap

/** Switches that turn the habit-tracker's scene into the game's street. */
object StageTuning {
    /**
     * Draws the side street into the gap where it opens off the main street (the app sets it: the corner turn's scene).
     * [dx]: the camera's place along the main street from the middle of the junction, in the turn's units (sprite pixels).
     */
    @Volatile var junctionView: (androidx.compose.ui.graphics.drawscope.DrawScope.(dx: Float) -> Unit)? = null
    /** The buildings either side of the junction: their side faces are the side street's walls, which its view draws. */
    @Volatile var cornerIds: Set<String> = emptySet()
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
    /**
     * The crossroads, when the game's street has one (the app or the preview sets it before the stage is shown): the
     * stage then lays out both streets as one place and draws whichever the player is on, the other seen through the
     * corner, and the walk round the corner between them, through one camera ([StageCamera]).
     */
    @Volatile var crossroads: CrossroadsStacks? = null
}

/**
 * The buildings of a crossroads, as the stage's stacks: [main] the main street with the side street's mouth as its
 * junction lot ([com.lifemax.rpg.feature.habits.domain.JUNCTION_ID]), [cross] the cross street from the main street
 * north, [east] the cross street's other side (may be empty).
 */
class CrossroadsStacks(
    val main: List<com.lifemax.rpg.feature.habits.domain.HabitStack>,
    val cross: List<com.lifemax.rpg.feature.habits.domain.HabitStack>,
    val east: List<com.lifemax.rpg.feature.habits.domain.HabitStack> = emptyList(),
)

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
    /**
     * A balcony the engine builds as boxes (the art's flat one is taken off [front]): x, y, w, h where the art had it
     * (front-sprite pixels), how far it stands out (pixels; the side face's 120 are the building's depth), its stone and
     * its floor colours (0xRRGGBB).
     */
    val balcony: FloatArray? = null,
    /** The storefront's sign where it hangs in front of [balcony], drawn again over it. */
    val overBalcony: ImageBitmap? = null,
    /**
     * Lighting maps from the front's depth (tools/make_lighting.py, Depth Anything V2): [lit] R, G, B how much brighter
     * each pixel is with the sun from the left, the right and above than a flat wall; [shade] the same darker, and in its
     * alpha the recesses (ambient occlusion).
     */
    val lit: ImageBitmap? = null,
    val shade: ImageBitmap? = null,
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
