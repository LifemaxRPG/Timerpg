package com.timerpg.street.shot

import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * What the player draws on a screenshot before sending it: arrows, free-hand lines and circles, all in the picture's own
 * pixels (so the numbers in the JSON say where they point in the PNG). Pure geometry: the drawing itself is in ShotSheet.
 */
sealed class ShotMark(val color: Int) {
    class Arrow(color: Int, val x0: Float, val y0: Float, val x1: Float, val y1: Float) : ShotMark(color)
    /** A circle (an ellipse) in the box between two corners. */
    class Circle(color: Int, val x0: Float, val y0: Float, val x1: Float, val y1: Float) : ShotMark(color)
    class Pen(color: Int, val points: List<Pair<Float, Float>>) : ShotMark(color)
}

object ShotMarks {
    const val RED = 0xFFFF2A2A.toInt()
    const val YELLOW = 0xFFFFE02A.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    val COLORS = listOf(RED, YELLOW, WHITE)

    fun colorName(c: Int) = when (c) { RED -> "red"; YELLOW -> "yellow"; WHITE -> "white"; else -> "#%06x".format(c and 0xFFFFFF) }

    /** Line thickness in picture pixels: about 9 px on a 2340 px wide screenshot (never under 5). */
    fun stroke(imageWidth: Int): Float = maxOf(5f, imageWidth / 260f)

    /** An arrow drawn shorter than this (a tap, not a drag) is dropped. */
    fun minLength(stroke: Float) = stroke * 3f

    /**
     * The arrow's head as three points: the tip (the end where the finger lifted) and the two back corners; plus where
     * the shaft ends (the head's base middle). Null for a zero-length arrow.
     */
    class Head(val tipX: Float, val tipY: Float, val leftX: Float, val leftY: Float, val rightX: Float, val rightY: Float, val baseX: Float, val baseY: Float)

    fun head(x0: Float, y0: Float, x1: Float, y1: Float, stroke: Float): Head? {
        val dx = x1 - x0; val dy = y1 - y0
        val len = hypot(dx, dy)
        if (len < 1e-3f) return null
        val ux = dx / len; val uy = dy / len
        val hl = minOf(stroke * 5f, len * .6f)     // head length (a short arrow gets a smaller head)
        val hw = hl * .5f                          // half width
        val bx = x1 - ux * hl; val by = y1 - uy * hl
        return Head(x1, y1, bx - uy * hw, by + ux * hw, bx + uy * hw, by - ux * hw, bx, by)
    }

    /** A pen stroke keeps a point only when it moved at least [minStep] from the last one. */
    fun thin(points: List<Pair<Float, Float>>, minStep: Float): List<Pair<Float, Float>> {
        if (points.size < 3) return points
        val out = arrayListOf(points.first())
        for (i in 1 until points.size - 1) if (hypot(points[i].first - out.last().first, points[i].second - out.last().second) >= minStep) out += points[i]
        out += points.last()
        return out
    }

    private fun r(v: Float) = v.roundToInt()

    /** The marks for the JSON, in picture pixels (rounded): "arrow" has from and to (the head is at "to"). */
    fun toJson(marks: List<ShotMark>): List<Map<String, Any?>> = marks.map { m ->
        when (m) {
            is ShotMark.Arrow -> linkedMapOf("type" to "arrow", "color" to colorName(m.color),
                "from" to linkedMapOf("x" to r(m.x0), "y" to r(m.y0)), "to" to linkedMapOf("x" to r(m.x1), "y" to r(m.y1)))
            is ShotMark.Circle -> linkedMapOf("type" to "circle", "color" to colorName(m.color),
                "centre" to linkedMapOf("x" to r((m.x0 + m.x1) / 2), "y" to r((m.y0 + m.y1) / 2)),
                "radiusX" to r(kotlin.math.abs(m.x1 - m.x0) / 2), "radiusY" to r(kotlin.math.abs(m.y1 - m.y0) / 2))
            is ShotMark.Pen -> linkedMapOf("type" to "pen", "color" to colorName(m.color),
                "points" to m.points.map { listOf(r(it.first), r(it.second)) })
        }
    }

    /** The "annotations" section of the screenshot's JSON, or null when nothing was drawn. */
    fun section(marks: List<ShotMark>, width: Int, height: Int, markedFile: String, rawFile: String): Map<String, Any?>? {
        if (marks.isEmpty()) return null
        return linkedMapOf(
            "note" to "Drawn by the player on the screenshot; coordinates are pixels of the original image ($width x $height), origin top left. " +
                "An arrow points at its \"to\" end.",
            "imageWidth" to width, "imageHeight" to height, "markedImage" to markedFile, "rawImage" to rawFile,
            "marks" to toJson(marks),
        )
    }

    /** The unmarked picture's path beside the marked one: name.png -> name-raw.png. */
    fun rawPath(path: String): String = path.substringBeforeLast('.') + "-raw." + path.substringAfterLast('.')
}
