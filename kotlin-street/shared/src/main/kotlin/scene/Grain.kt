package com.lifemax.rpg.feature.habits.presentation.scene

/**
 * Fine film grain: a square tile of black and white specks (their alpha is the grain), balanced so
 * it neither lightens nor darkens, made once and laid over the scene at 3% or so. It breaks up the
 * banding of the sky's gradients the way a camera's noise does. The same tile every time.
 */
internal object Grain {
    const val SIZE = 128

    fun pixels(): IntArray =
        IntArray(SIZE * SIZE) {
            val x = it % SIZE
            val y = it / SIZE
            // two octaves of per-pixel noise: mostly fine, a little clumped
            val n =
                Noise.value(x * 1000f + .37f, y * 1000f + .11f, 5) * .65f +
                    Noise.value(x * 1000f + 3.1f, y * 1000f + 7.9f, 9) * .35f
            val v = (n - .5f) * 2f
            val a = (kotlin.math.abs(v) * 255f).toInt().coerceIn(0, 255)
            if (v > 0f) Px.argb(a, 255, 255, 255) else Px.argb(a, 0, 0, 0)
        }
}
