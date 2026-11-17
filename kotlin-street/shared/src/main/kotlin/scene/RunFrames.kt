package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * The run, made from our characters' walk (the sheets have none). The art's legs are four pixels tall and a walking step
 * carries a planted foot 6.5 of the art's pixels: run at twice a walk's speed on those frames and nearly half the ground
 * slides under the feet. Seen from the side, the run's frames splay the legs instead (the reaching foot two pixels further
 * forward, the trailing one two further back, less higher up the leg), so a step carries the foot 10.5 pixels, about 1.6
 * times a walk's, as a runner's longer stride does; with [MoveTuning]'s stride the feet then slip no more than at a walk.
 * Where the legs part, the gap between them is left open with the art's outline drawn down each leg's inner side: the
 * art draws the legs as one block, which stretched across the gap read as a plank under the body, not two legs. Every
 * pixel stays on the art's grid. The front and back views (walking in and out) keep the walk's frames.
 */
object RunFrames {
    /** The sheets are the 16 x 32 art drawn twice the size: sheet pixels to one of the art's. */
    private const val ART = 2
    private const val CELL_W = 32
    private const val CELL_H = 64
    private const val ART_W = CELL_W / ART
    private const val VIEWS = 4
    private const val FRAMES = 6
    /** The sheet's walk row. */
    private const val WALK_ROW = 2
    /** The legs: the art's rows from here (a row higher on the raised frames 1 and 4), five of them down to the ground. */
    private const val LEGS_TOP = 27
    private const val LEG_ROWS = 5
    /** The column between the legs, seen from the right (view 0) and from the left (view 2, the art drawn the other way). */
    private const val SPLIT_RIGHT = 8
    private const val SPLIT_LEFT = 7
    /**
     * How far each leg moves out at each of its rows (art pixels, the same both ways), by frame: the reaching and pushing
     * frames (0 and 3) at the ground, the raised frames between (1 and 4, both feet off it) all down the leg; the passing
     * frames (2 and 5) keep the walk's.
     */
    private val SPLAY: Array<IntArray?> = arrayOf(
        intArrayOf(0, 0, 1, 2, 2), intArrayOf(0, 1, 1, 2, 2), null,
        intArrayOf(0, 0, 1, 2, 2), intArrayOf(0, 1, 1, 2, 2), null,
    )
    /** A planted foot's travel over a step (art pixels), the walk's and the run's, measured from the frames 0 to 3. */
    const val WALK_STEP = 6.5f
    const val RUN_STEP = 10.5f

    private val made = HashMap<ImageBitmap, ImageBitmap>()

    /** The run for [sheet]: one row of 4 views x 6 frames of 32 x 64, laid out as the sheet's walk row is. */
    fun of(sheet: ImageBitmap): ImageBitmap = made.getOrPut(sheet) { make(sheet) }

    private fun make(sheet: ImageBitmap): ImageBitmap {
        val out = ImageBitmap(VIEWS * FRAMES * CELL_W, CELL_H)
        val canvas = Canvas(out)
        val paint = Paint().apply { filterQuality = FilterQuality.None }
        val ink = Paint().apply { isAntiAlias = false }
        fun copy(sx: Int, sy: Int, w: Int, h: Int, dx: Int, dy: Int) {
            if (w > 0 && h > 0) canvas.drawImageRect(sheet, IntOffset(sx, sy), IntSize(w, h), IntOffset(dx, dy), IntSize(w, h), paint)
        }
        // one art pixel of the cell being made, filled with [color]
        fun dot(x: Int, y: Int, color: Int) {
            ink.color = Color(color)
            canvas.drawRect(x.toFloat(), y.toFloat(), (x + ART).toFloat(), (y + ART).toFloat(), ink)
        }
        val cell = IntArray(CELL_W * CELL_H)
        val sy = WALK_ROW * CELL_H
        for (view in 0 until VIEWS) for (f in 0 until FRAMES) {
            val x0 = (view * FRAMES + f) * CELL_W
            val splay = if (view == 1 || view == 3) null else SPLAY[f]
            if (splay == null) { copy(x0, sy, CELL_W, CELL_H, x0, 0); continue }
            val top = if (f == 1 || f == 4) LEGS_TOP - 1 else LEGS_TOP
            val split = if (view == 2) SPLIT_LEFT else SPLIT_RIGHT
            // the cell's pixels (an art pixel is the top left of its 2 x 2), and the legs' outline: their darkest pixel
            sheet.readPixels(cell, x0, sy, CELL_W, CELL_H)
            fun art(c: Int, r: Int) = cell[r * ART * CELL_W + c * ART]
            var outline = 0; var darkest = Int.MAX_VALUE
            for (r in top until top + LEG_ROWS) for (c in 0 until ART_W) {
                val p = art(c, r)
                if (p ushr 24 < 128) continue
                val lum = 3 * ((p shr 16) and 255) + 6 * ((p shr 8) and 255) + (p and 255)
                if (lum < darkest) { darkest = lum; outline = p }
            }
            // the body, as drawn
            copy(x0, sy, CELL_W, top * ART, x0, 0)
            for (i in 0 until LEG_ROWS) {
                val y = (top + i) * ART
                val s = splay[i]
                // behind the column between the legs: moved back
                copy(x0 + s * ART, sy + y, (split - s) * ART, ART, x0, y)
                // that column: as drawn where the legs do not part; where they do and it is drawn (the legs one block), the
                // gap left open with an outline down each leg's inner side; where it is clear, the gap clear
                if (s == 0) copy(x0 + split * ART, sy + y, ART, ART, x0 + split * ART, y)
                else if (art(split, top + i) ushr 24 >= 128) {
                    dot(x0 + (split - s) * ART, y, outline)
                    dot(x0 + (split + s) * ART, y, outline)
                }
                // from it on: moved forward
                copy(x0 + (split + 1) * ART, sy + y, (ART_W - split - 1 - s) * ART, ART, x0 + (split + 1 + s) * ART, y)
            }
            // under the legs (the raised frames' last row)
            val below = (top + LEG_ROWS) * ART
            copy(x0, sy + below, CELL_W, CELL_H - below, x0, below)
        }
        return out
    }
}
