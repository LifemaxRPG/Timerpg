package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.ui.graphics.ImageBitmap
import com.lifemax.rpg.feature.habits.domain.HouseShape

/*
 * Our pitched-roof houses in the corner turn (CornerTurn.kt) and the street crossing: a box of its own size (its front
 * picture's width, not stretched to the slot's 224 pixels), standing back in its slot by its front yard (as far as the
 * slot is deep), as tall as its wall up to the eave's edge, its gable end cut down to the wall's rows, and its pitched
 * roof on top (TurnRoof.kt).
 */

/**
 * The turn's pictures for one of our buildings. For a pitched-roof house ([house], with its near and far slopes'
 * pictures): its gable end cut to the wall's rows (the roof's triangles are drawn by the turn), and its roof.
 */
fun turnFacade(
    front: ImageBitmap, side: ImageBitmap, emis: ImageBitmap?, prop: ImageBitmap?, house: HouseShape?,
    slope: ImageBitmap? = null, back: ImageBitmap? = null,
): TurnFacade {
    if (house == null) return TurnFacade(front, side, emis, prop)
    val rows = minOf(side.height, house.h.toInt())
    val px = IntArray(side.width * rows)
    side.readPixels(px, 0, side.height - rows, side.width, rows)
    // the gable's colour: the siding's, a quarter of the way along the end wall (clear of its window)
    val gable = androidx.compose.ui.graphics.Color(px[minOf(rows - 1, 24) * side.width + side.width / 5] or (0xFF shl 24))
    val roof = slope?.let { TurnRoofArt(house, it, back, gable) }
    return TurnFacade(front, PlatformImages.create(px, side.width, rows, false), emis, prop, house, roof)
}

/**
 * Where a house stands in the slot ([x0], [x1], [z0], [z1]) a building facing [frontN] would fill: across the front only
 * its picture's width, in the middle; back from the slot's front by its yard, as far as the slot's depth leaves room.
 */
internal fun houseSlot(h: HouseShape, picW: Float, x0: Float, x1: Float, z0: Float, z1: Float, frontN: Int): FloatArray {
    val alongX = frontN == 0 || frontN == 3
    val across = if (alongX) x1 - x0 else z1 - z0
    val deep = if (alongX) z1 - z0 else x1 - x0
    val pad = (across - picW) / 2f
    val back = minOf(h.setback, (deep - h.depth).coerceAtLeast(0f))
    val d = minOf(h.depth, deep)
    return when (frontN) {
        0 -> floatArrayOf(x0 + pad, x1 - pad, z0 + back, z0 + back + d)          // faces -Z: its front at z0
        3 -> floatArrayOf(x0 + pad, x1 - pad, z1 - back - d, z1 - back)          // faces +Z: its front at z1
        1 -> floatArrayOf(x1 - back - d, x1 - back, z0 + pad, z1 - pad)          // faces +X: its front at x1
        else -> floatArrayOf(x0 + back, x0 + back + d, z0 + pad, z1 - pad)       // faces -X: its front at x0
    }
}
