package com.lifemax.rpg.feature.habits.presentation.scene

/**
 * The colour matrix (4x5, rows R, G, B, A, the last column an offset out of 255) that dresses our summer-green trees for
 * the season, lit by ([lr], [lg], [lb]): orange as the leaves turn ([autumn]), pale and frosted under snow ([snow]), a
 * blush of pink at blossom time ([blossom]). The street ([BuildingPainter]) and the corner turn share it, so a tree looks
 * the same on both sides of the cut.
 */
fun seasonTreeMatrix(autumn: Float, snow: Float, blossom: Float, lr: Float = 1f, lg: Float = 1f, lb: Float = 1f): FloatArray {
    var r = floatArrayOf(1f, 0f, 0f, 0f, 0f)
    var g = floatArrayOf(0f, 1f, 0f, 0f, 0f)
    var b = floatArrayOf(0f, 0f, 1f, 0f, 0f)
    fun mix(t: Float, tr: FloatArray, tg: FloatArray, tb: FloatArray) {
        if (t <= 0f) return
        r = FloatArray(5) { r[it] + (tr[it] - r[it]) * t }
        g = FloatArray(5) { g[it] + (tg[it] - g[it]) * t }
        b = FloatArray(5) { b[it] + (tb[it] - b[it]) * t }
    }
    // autumn: the greens go to orange and amber
    mix(autumn, floatArrayOf(.45f, 1.0f, 0f, 0f, 0f), floatArrayOf(.12f, .62f, 0f, 0f, 0f), floatArrayOf(0f, 0f, .35f, 0f, 0f))
    // winter: frosted, pale and desaturated
    mix(snow * .85f, floatArrayOf(.38f, .38f, .2f, 0f, 70f), floatArrayOf(.36f, .4f, .22f, 0f, 74f), floatArrayOf(.34f, .38f, .3f, 0f, 82f))
    // blossom: pink among the green
    mix(blossom * .6f, floatArrayOf(1.0f, .55f, .2f, 0f, 20f), floatArrayOf(.1f, .75f, .15f, 0f, 8f), floatArrayOf(.25f, .25f, .8f, 0f, 14f))
    // the light scales each whole row, offset included, so a frosted tree darkens at night like everything else
    return floatArrayOf(
        r[0] * lr, r[1] * lr, r[2] * lr, 0f, r[4] * lr,
        g[0] * lg, g[1] * lg, g[2] * lg, 0f, g[4] * lg,
        b[0] * lb, b[1] * lb, b[2] * lb, 0f, b[4] * lb,
        0f, 0f, 0f, 1f, 0f,
    )
}

/** [m] seen through haze: the picture keeps (1 - [hz]) of itself and takes [hz] of the haze colour (out of 255). */
fun hazed(m: FloatArray, hz: Float, hr: Float, hg: Float, hb: Float): FloatArray {
    if (hz <= 0f) return m
    val k = 1f - hz
    val o = m.copyOf()
    for (row in 0 until 3) {
        for (col in 0 until 5) o[row * 5 + col] = m[row * 5 + col] * k
        o[row * 5 + 4] += floatArrayOf(hr, hg, hb)[row] * hz
    }
    return o
}
