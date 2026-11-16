package com.lifemax.rpg.feature.habits.presentation.scene

import kotlin.math.max

/**
 * One bird's outline, seen from below: a small body with a head and a fanned tail, and two wings
 * that bend at the wrist, the hand (the primaries) curling down on the downstroke and folding a
 * little on the upstroke. Pure geometry, written as path commands into a float array so the painter
 * fills a [androidx.compose.ui.graphics.Path] from it without allocating and a test can read it: a
 * command code, then its points ([MOVE] x y, [LINE] x y, [CUBIC] x1 y1 x2 y2 x y, [CLOSE]).
 */
internal object BirdShape {
    const val MOVE = 0f
    const val LINE = 1f
    const val CUBIC = 2f
    const val CLOSE = 3f

    /** Floats enough for one bird. */
    const val CAPACITY = 160

    /** The wing's span from the body's centre, in dp, for a bird of size 1. */
    const val SPAN = 7.2f

    /**
     * Writes the bird centred on ([cx], [cy]) into [out] and returns how many floats it wrote.
     * [flap] is the wings' position, -1 down to 1 up; [size] its scale (1 is a near bird); [dir] 1
     * flying right, -1 left (the head leans that way).
     */
    fun build(cx: Float, cy: Float, flap: Float, size: Float, dir: Float, out: FloatArray): Int {
        var n = 0
        fun move(x: Float, y: Float) {
            out[n++] = MOVE
            out[n++] = cx + x * size
            out[n++] = cy + y * size
        }
        fun line(x: Float, y: Float) {
            out[n++] = LINE
            out[n++] = cx + x * size
            out[n++] = cy + y * size
        }
        fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) {
            out[n++] = CUBIC
            out[n++] = cx + x1 * size
            out[n++] = cy + y1 * size
            out[n++] = cx + x2 * size
            out[n++] = cy + y2 * size
            out[n++] = cx + x * size
            out[n++] = cy + y * size
        }
        fun close() {
            out[n++] = CLOSE
        }
        val f = flap.coerceIn(-1f, 1f)
        val down = max(0f, -f)
        val up = max(0f, f)
        // the body: a lens, head forward (up the picture, leaning the way it flies), the tail a
        // short fan behind; it rides up a little on the downstroke
        val lean = .3f * dir
        val ride = -.18f * f
        move(lean + .02f, -1.15f + ride)
        cubic(lean + .55f, -1.1f + ride, .62f, .1f + ride, .5f, .9f + ride)
        // the tail fan
        line(.85f - lean * .4f, 1.75f + ride)
        line(0f - lean * .5f, 1.5f + ride)
        line(-.85f - lean * .4f, 1.75f + ride)
        line(-.5f, .9f + ride)
        cubic(-.62f, .1f + ride, lean - .55f, -1.1f + ride, lean - .02f, -1.15f + ride)
        close()
        for (k in 0 until 2) {
            val s = 1f - 2f * k
            // the wrist, half way out, and the tip; the hand curls down on the downstroke and folds
            // in on the upstroke
            val wx = 3.3f
            val wy = -up * 2.05f + down * 1.5f - .1f
            val tx = SPAN - .85f * up - .45f * down
            val ty = -up * 4.7f + down * 3.1f + .3f + .9f * down * down + .55f * up * up
            move(s * .45f, -.3f + ride)
            // the leading edge: shoulder to wrist, then the hand out to the tip
            cubic(s * 1.3f, -.55f - f * .55f, s * 2.4f, wy - .3f, s * wx, wy)
            cubic(s * 4.4f, wy - .4f - up * .2f, s * 6.1f, (wy + ty) / 2f - .35f, s * tx, ty)
            // and the trailing edge back: the primaries, the bulge of the secondaries, the body
            cubic(
                s * (tx - 1.1f),
                ty + .55f + .35f * down,
                s * 4.7f,
                wy + .95f + .2f * down,
                s * (wx + .1f),
                wy + 1.05f,
            )
            cubic(s * 2.3f, wy + 1.3f + .1f * f, s * 1.15f, .95f + ride, s * .45f, .9f + ride)
            close()
        }
        return n
    }
}
