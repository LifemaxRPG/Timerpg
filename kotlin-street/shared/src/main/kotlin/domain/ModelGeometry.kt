package com.lifemax.rpg.feature.habits.domain

import kotlin.math.max

/*
 * Our villas and landmarks (the clock tower, the school) as shape with no drawing in it: what tools/make_buildings.py's
 * build_model cut from their sprites (buildings/models.json). Each is a list of parts drawn back to front: boxes, front
 * gables (the triangle end facing the street, the slopes running back from it), hipped roofs (four sloped faces and
 * a flat top) and railings (a handrail along a line, glass or nothing under it). Lengths are sprite pixels: x across the model's front picture (which stands [ModelShape.frontX] pixels into
 * the lot's 224-pixel frame), y up from the ground, d back from the model's front line (negative: in front of it), which
 * stands [ModelShape.setback] back from the pavement. Where a part wears a piece of art it is a rectangle of the
 * building's atlas picture (x, y, w, h), and its points are in that rectangle's own pixels.
 */

/** One part of a modelled building. */
class ModelPart(
    val type: Int,
    val x0: Float, val x1: Float,
    /** A box: its foot and top. A roof: its eave's and its ridge's heights. */
    val y0: Float, val y1: Float,
    /** A box: its front and back. A gable: its face and the ridge's back end. A hipped roof: its front and back eaves. */
    val d0: Float, val d1: Float,
    /** A box's top, front and side colours (0xRRGGBB; a top of -1 is not drawn: something stands on it), a roof's colour in [front]. */
    val top: Int, val front: Int, val side: Int,
    /** The atlas pieces a box's front (its left edge at [fx]: a front may be wider than the box, a gable over it), top and side wear. */
    val ft: IntArray? = null, val fx: Float = x0, val tt: IntArray? = null, val st: IntArray? = null,
    /** A thin upright panel (a railing) rather than a block. */
    val card: Boolean = false,
    /** A plain box drawn with the pixel art's dark outline. */
    val outline: Boolean = false,
    /** The front's window panes (x, y, w, h in its piece's pixels, then 1 for a square pane, 0 for a round one), or a gable face's. */
    val panes: List<FloatArray> = emptyList(),
    /** A roof's left and right slope or face pieces, and their four points each (see [ModelShape]). */
    val left: IntArray? = null, val leftP: FloatArray? = null,
    val right: IntArray? = null, val rightP: FloatArray? = null,
    /** A box whose side picture has windows that light at night (a railing's side has none: no light pass for it). */
    val sideGlow: Boolean = false,
    /**
     * The clock a box's front and sides wear, its hands drawn at the hour (the art's painted ones are taken off): its
     * hub's cell (x, y in the front piece's pixels, the cell's size: the art's doubled pixel), the minute and hour hands'
     * lengths in cells, and their ink, brown and gold (0xRRGGBB); or null.
     */
    val clock: IntArray? = null,
    /** A gable's rake boards' face and edge colours; where its eaves end at the back ([d1] unless it meets another roof). */
    val rake: Int = 0, val rakeEdge: Int = 0, val eaveBack: Float = d1,
    /**
     * A gable's roof edge colour (the art's row of it just above the face's rake ink), or -1. The face's rake is the
     * art's staircase of pixels and a slope's front edge a straight line from the eave to the apex: this fills between.
     */
    val lip: Int = -1,
    /** A gable's face piece standing on its front ([faceY] its foot's height, [fx] its left edge), if not a box's front. */
    val face: IntArray? = null, val faceY: Float = 0f,
    /** A hipped roof's front face piece (its rows the screen's, ridge to eave), its flat top's piece and extent. */
    val frontPic: IntArray? = null, val flat: IntArray? = null,
    val tx0: Float = 0f, val tx1: Float = 0f, val td0: Float = 0f, val td1: Float = 0f,
    /** A hipped roof's hips as the art draws them: the cap tiles along each and the crease beside them (0xRRGGBB), or null. */
    val hip: IntArray? = null,
    /**
     * A hipped roof's side faces with each course one colour along its depth ([left]'s and [right]'s points): for a view
     * that sees them so nearly edge on that their tiles' joints fall closer than a pixel or two apart.
     */
    val leftFlush: IntArray? = null, val rightFlush: IntArray? = null,
    /**
     * A gable's apex x when it is not in the middle: a single-pitch (lean-to) roof running back from the street has its
     * high edge at one side and only the slope from it: at [x1] its [left] slope (the terraced houses), at [x0] its
     * [right] one (the modern estate).
     */
    val apexX: Float = Float.NaN,
    /** A box's side that stands against another volume, which no view sees: 1 its left, 2 its right, 0 neither. */
    val hideSide: Int = 0,
    /**
     * A railing ([RAIL]): its handrail [railH] above the line from (x0, y0, d0) to (x1, y1, d1) (a stair's nosings, a
     * terrace's edge), [railW] thick in [front]'s colour; under it a glass panel in [side]'s colour at [top] of 255 (or
     * none: -1), and posts about every [posts] pixels along it (0: one at each end).
     */
    val railH: Float = 0f, val railW: Float = 2f, val posts: Float = 0f,
) {
    /** A gable's or a hipped roof's apex x. */
    val apex: Float get() = if (apexX.isNaN()) (x0 + x1) / 2f else apexX
    /** A single-pitch roof: its apex at one side. */
    val monopitch: Boolean get() = !apexX.isNaN() && (apexX <= x0 + .5f || apexX >= x1 - .5f)

    companion object {
        const val BOX = 0
        const val GABLE = 1
        const val HIP = 2
        const val RAIL = 3
    }
}

/** A modelled building (buildings/models.json). */
class ModelShape(
    /** "villa", "tower", "school" or "terraced". */
    val kind: String,
    /** Its front picture's width (pixels): a lot as wide, or the street's 224 if that is more. */
    val width: Float,
    val frontX: Float,
    val setback: Float,
    val parts: List<ModelPart>,
    /** Grass round it, else paving. */
    val grass: Boolean,
    /** Its yard's fence or hedge along the street: how far back from the pavement, and its picture's foot row. */
    val fenceD: Float, val fenceFoot: Float,
    val roofTint: Int,
    /** A lamp on its wall by the door (x, y, d), if it has one. */
    val lamp: FloatArray?,
    /** Whose hours its windows are lit on: "home", "shop" or "lamp" (all night). */
    val lights: String,
    /** The tops of its chimneys (x, d, y each), which smoke in the cold months. */
    val smoke: List<FloatArray>,
    /** Where its whole-front picture (SpriteBuilding.front: what the wet road mirrors) starts across its front. */
    val frontPicX: Float = 0f,
) {
    /** Its walls: the boxes that are its volumes (at least 100 deep), not its porch, steps, rails or chimneys. */
    private val walls: List<ModelPart> = parts.filter { it.type == ModelPart.BOX && !it.card && it.d1 - it.d0 >= 100f }
    /** The top of its walls, and its highest roof's ridge and that ridge's depth. */
    val wallTop: Float = walls.maxOfOrNull { it.y1 } ?: 0f
    private val tallestRoof: ModelPart? = parts.filter { it.type == ModelPart.GABLE || it.type == ModelPart.HIP }.maxByOrNull { it.y1 }
    val ridge: Float = max(tallestRoof?.y1 ?: 0f, parts.maxOfOrNull { if (it.type == ModelPart.BOX) it.y1 else 0f } ?: 0f)
    val ridgeD: Float = tallestRoof?.let { (it.d0 + it.d1) / 2f } ?: 0f
    /** Its walls' span across the front picture. */
    val wall0: Float = walls.minOfOrNull { it.x0 } ?: 0f
    val wall1: Float = walls.maxOfOrNull { it.x1 } ?: width
    /** How deep it is behind its front line. */
    val depth: Float = parts.maxOfOrNull { it.d1 } ?: 0f

    /**
     * For each part, the front gable whose eave boards are drawn right after it, or -1: the box the gable stands on,
     * drawn after the gable (its front carries the gable's face), whose side wall would otherwise paint over the boards
     * and the shade they throw on its top. [eaveLater] marks the gables whose eaves wait for such a box.
     */
    val eaveOn: IntArray = IntArray(parts.size) { -1 }
    val eaveLater: BooleanArray = BooleanArray(parts.size)

    init {
        for (j in parts.indices) {
            val g = parts[j]
            if (g.type != ModelPart.GABLE) continue
            for (i in j + 1 until parts.size) {
                val b = parts[i]
                val under = b.type == ModelPart.BOX && !b.card && kotlin.math.abs(b.y1 - g.y0) < .5f &&
                    b.x0 >= g.x0 - .5f && b.x1 <= g.x1 + .5f && b.d0 < g.eaveBack && b.d1 > g.d0
                if (under && eaveOn[i] < 0) { eaveOn[i] = j; eaveLater[j] = true; break }
            }
        }
    }

    /** How high its highest point shows above the pavement line (street units), lifted by how far back it stands. */
    val screenTop: Float get() {
        val k = BuildingSpec.W / 224f
        val lift = BuildingSpec.DY / HouseView.DEPTH
        var best = 0f
        for (p in parts) {
            val d = if (p.type == ModelPart.BOX) p.d1 else if (p.type == ModelPart.HIP) p.td1 else p.d1
            val top = if (p.type == ModelPart.RAIL) max(p.y0, p.y1) + p.railH else p.y1
            best = max(best, top * k + lift * (setback + d))
        }
        return best
    }

    companion object {
        /** Every model in models.json, by sprite name (bN). A malformed entry is skipped. */
        fun parseAll(text: String?): Map<String, ModelShape> {
            if (text == null) return emptyMap()
            val j = try { org.json.JSONObject(text) } catch (e: Exception) { return emptyMap() }
            val out = HashMap<String, ModelShape>()
            for (key in j.keys()) {
                val o = j.optJSONObject(key) ?: continue
                parse(o)?.let { out[key] = it }
            }
            return out
        }

        private fun org.json.JSONArray.f(i: Int): Float = getDouble(i).toFloat()
        private fun ints(a: org.json.JSONArray?): IntArray? = a?.let { IntArray(it.length()) { i -> it.getInt(i) } }
        private fun floats(a: org.json.JSONArray?): FloatArray? = a?.let { FloatArray(it.length()) { i -> it.getDouble(i).toFloat() } }

        fun parsePart(p: org.json.JSONObject): ModelPart {
            val x = p.getJSONArray("x"); val y = p.getJSONArray("y"); val d = p.getJSONArray("d")
            return when (p.getString("t")) {
                "box" -> {
                    val c = p.getJSONArray("c")
                    val pn = p.optJSONArray("panes")
                    ModelPart(ModelPart.BOX, x.f(0), x.f(1), y.f(0), y.f(1), d.f(0), d.f(1), c.getInt(0), c.getInt(1), c.getInt(2),
                        ints(p.optJSONArray("ft")), p.optDouble("fx", x.getDouble(0)).toFloat(), ints(p.optJSONArray("tt")), ints(p.optJSONArray("st")),
                        p.optBoolean("card"), p.optBoolean("out"),
                        if (pn == null) emptyList() else (0 until pn.length()).map { floats(pn.getJSONArray(it))!! },
                        sideGlow = p.optBoolean("sg"), clock = ints(p.optJSONArray("clock")),
                        hideSide = when (p.optString("hs")) { "l" -> 1; "r" -> 2; else -> 0 })
                }
                "gable" -> {
                    val rk = p.getJSONArray("rake")
                    val pn = p.optJSONArray("panes")
                    ModelPart(ModelPart.GABLE, x.f(0), x.f(1), y.f(0), y.f(1), d.f(0), d.f(1), -1, p.getInt("roof"), 0,
                        fx = p.optDouble("fx", x.getDouble(0)).toFloat(),
                        panes = if (pn == null) emptyList() else (0 until pn.length()).map { floats(pn.getJSONArray(it))!! },
                        left = ints(p.optJSONArray("l")), leftP = floats(p.optJSONArray("lp")),
                        right = ints(p.optJSONArray("r")), rightP = floats(p.optJSONArray("rp")),
                        apexX = p.optDouble("ax", Double.NaN).toFloat(),
                        rake = rk.getInt(0), rakeEdge = rk.getInt(1), eaveBack = p.optDouble("de", d.getDouble(1)).toFloat(),
                        lip = p.optInt("lip", -1),
                        face = ints(p.optJSONArray("face")), faceY = p.optDouble("fy", 0.0).toFloat())
                }
                "hip" -> {
                    val t = p.getJSONArray("top")
                    ModelPart(ModelPart.HIP, x.f(0), x.f(1), y.f(0), y.f(1), d.f(0), d.f(1), -1, p.getInt("roof"), 0,
                        left = ints(p.getJSONArray("l")), leftP = floats(p.getJSONArray("lp")),
                        right = ints(p.getJSONArray("r")), rightP = floats(p.getJSONArray("rp")),
                        frontPic = ints(p.getJSONArray("front")), flat = ints(p.optJSONArray("flat")),
                        tx0 = t.f(0), tx1 = t.f(1), td0 = t.f(2), td1 = t.f(3), hip = ints(p.optJSONArray("hip")),
                        leftFlush = ints(p.optJSONArray("la")), rightFlush = ints(p.optJSONArray("ra")))
                }
                "rail" -> {
                    val c = p.getJSONArray("c")
                    ModelPart(ModelPart.RAIL, x.f(0), x.f(1), y.f(0), y.f(1), d.f(0), d.f(1), p.optInt("ga", 0), c.getInt(0), c.getInt(1),
                        railH = p.getDouble("h").toFloat(), railW = p.optDouble("w", 2.0).toFloat(), posts = p.optDouble("posts", 0.0).toFloat())
                }
                else -> throw IllegalArgumentException("unknown part ${p.getString("t")}")
            }
        }

        fun parse(o: org.json.JSONObject): ModelShape? = try {
            val ps = o.getJSONArray("parts")
            val sm = o.optJSONArray("smoke")
            val fence = o.optJSONObject("fence")
            ModelShape(
                o.getString("kind"), o.getDouble("w").toFloat(), o.getDouble("frontX").toFloat(), o.getDouble("setback").toFloat(),
                (0 until ps.length()).map { parsePart(ps.getJSONObject(it)) },
                o.optString("lawn") == "grass",
                fence?.optDouble("d")?.toFloat() ?: 0f, fence?.optDouble("foot")?.toFloat() ?: 0f,
                o.optInt("roofTint"), floats(o.optJSONArray("lamp")), o.optString("lights", "home"),
                if (sm == null) emptyList() else (0 until sm.length()).map { floats(sm.getJSONArray(it))!! },
                o.optDouble("fpx", 0.0).toFloat(),
            )
        } catch (e: Exception) { null }
    }
}

/** Where a modelled building's points land on screen ([SetBackView]); one is [set] again for each building drawn. */
class ModelView : SetBackView() {
    /**
     * This view for model [shape] at side shift [s]; returns itself. [side] false: no side face shows (it stands at the
     * mouth of the side street, which the stage draws there).
     */
    fun set(shape: ModelShape, s: Float, unitPx: Float = 0f, originX: Float = 0f, side: Boolean = true): ModelView {
        place(shape.frontX, shape.setback, s, unitPx, originX)
        sideAllowed = side
        return this
    }
}

/** Which of a roof's faces the street's view sees (from a little above, leaning with the side shift). */
object RoofFaces {
    /**
     * Whether a front gable's slope shows: [q] its screen corners (the front eave, the apex, the ridge's back end, the
     * back eave; y down). It shows when its eave runs back on its outer side of the rake (seen from above); else the
     * ridge and the other slope hide it.
     */
    fun gableSlopeSeen(left: Boolean, q: FloatArray): Boolean {
        val cross = (q[2] - q[0]) * (q[7] - q[1]) - (q[3] - q[1]) * (q[6] - q[0])
        return if (left) cross < 0f else cross > 0f
    }

    /**
     * Whether a hipped roof's side face shows: [q] its screen corners (the front eave, the back eave, the top's back end,
     * its front end; y down). It shows when it winds the way a face seen from outside does on that side.
     */
    fun hipSideSeen(left: Boolean, q: FloatArray): Boolean {
        var area = 0f
        for (i in 0 until 4) { val j = (i + 1) % 4; area += q[2 * i] * q[2 * j + 1] - q[2 * j] * q[2 * i + 1] }
        return if (left) area > 0f else area < 0f
    }
}

/** The plane maths a roof's pieces are laid on the screen with: a quad of a picture onto a quad of the screen. */
object QuadMap {
    /**
     * Writes into [m] (3 by 3, row-major: x' = (m0 x + m1 y + m2) / (m6 x + m7 y + m8), y' likewise with m3..m5) the
     * projective map taking the unit square's corners (0,0), (1,0), (1,1), (0,1) to [q] (x0, y0 .. x3, y3). False when
     * the quad is degenerate.
     */
    fun square(q: FloatArray, m: DoubleArray): Boolean {
        val x0 = q[0].toDouble(); val y0 = q[1].toDouble(); val x1 = q[2].toDouble(); val y1 = q[3].toDouble()
        val x2 = q[4].toDouble(); val y2 = q[5].toDouble(); val x3 = q[6].toDouble(); val y3 = q[7].toDouble()
        val sx = x0 - x1 + x2 - x3; val sy = y0 - y1 + y2 - y3
        val dx1 = x1 - x2; val dx2 = x3 - x2; val dy1 = y1 - y2; val dy2 = y3 - y2
        val det = dx1 * dy2 - dx2 * dy1
        if (kotlin.math.abs(det) < 1e-9) return false
        val g = (sx * dy2 - dx2 * sy) / det
        val h = (dx1 * sy - sx * dy1) / det
        m[0] = x1 - x0 + g * x1; m[1] = x3 - x0 + h * x3; m[2] = x0
        m[3] = y1 - y0 + g * y1; m[4] = y3 - y0 + h * y3; m[5] = y0
        m[6] = g; m[7] = h; m[8] = 1.0
        return true
    }

    /**
     * The projective map taking the quad [from] (a picture's own pixels) onto [to] (the screen), both four corners in
     * order, into [out] (3 by 3, row-major). [tmpA] and [tmpB] are scratch (9 each). False when either is degenerate.
     */
    fun quadToQuad(from: FloatArray, to: FloatArray, out: DoubleArray, tmpA: DoubleArray, tmpB: DoubleArray): Boolean {
        if (!square(from, tmpA) || !square(to, tmpB)) return false
        // the inverse of the first (its adjugate: the scale cancels in a projective map)
        val a = tmpA
        val i0 = a[4] * a[8] - a[5] * a[7]; val i1 = a[2] * a[7] - a[1] * a[8]; val i2 = a[1] * a[5] - a[2] * a[4]
        val i3 = a[5] * a[6] - a[3] * a[8]; val i4 = a[0] * a[8] - a[2] * a[6]; val i5 = a[2] * a[3] - a[0] * a[5]
        val i6 = a[3] * a[7] - a[4] * a[6]; val i7 = a[1] * a[6] - a[0] * a[7]; val i8 = a[0] * a[4] - a[1] * a[3]
        val b = tmpB
        out[0] = b[0] * i0 + b[1] * i3 + b[2] * i6; out[1] = b[0] * i1 + b[1] * i4 + b[2] * i7; out[2] = b[0] * i2 + b[1] * i5 + b[2] * i8
        out[3] = b[3] * i0 + b[4] * i3 + b[5] * i6; out[4] = b[3] * i1 + b[4] * i4 + b[5] * i7; out[5] = b[3] * i2 + b[4] * i5 + b[5] * i8
        out[6] = b[6] * i0 + b[7] * i3 + b[8] * i6; out[7] = b[6] * i1 + b[7] * i4 + b[8] * i7; out[8] = b[6] * i2 + b[7] * i5 + b[8] * i8
        val n = out[8]
        if (kotlin.math.abs(n) < 1e-12) return false
        for (i in 0 until 9) out[i] /= n
        return true
    }

    /** Where [m] takes the point (x, y): its x and y into [p]. */
    fun apply(m: DoubleArray, x: Float, y: Float, p: FloatArray) {
        val w = m[6] * x + m[7] * y + m[8]
        p[0] = ((m[0] * x + m[1] * y + m[2]) / w).toFloat()
        p[1] = ((m[3] * x + m[4] * y + m[5]) / w).toFloat()
    }
}

/**
 * A clock's hands at an hour, as pixel art in the dial's own cells ([ModelPart.clock]): each hand a line of cells from
 * the hub, ink down its middle and brown either side, a gold cell at its tip (as the clock tower's art draws them), the
 * minute hand over the hour hand, and the hub over both (gold, a brown cross, ink corners). Painted into a square
 * picture [size] cells wide, the hub's cell in its middle.
 */
object ClockHands {
    /** The picture's width and height for a minute hand [minuteLen] cells long. */
    fun size(minuteLen: Int): Int = 2 * minuteLen + 3

    /**
     * The hands at [hour] (0..24, its fraction the minutes) into [px] (ARGB, [n] by [n], cleared first). [shaft] is
     * scratch, [n] by [n] (the painter keeps one, so a repaint allocates nothing).
     */
    fun paint(px: IntArray, n: Int, hour: Float, minuteLen: Int, hourLen: Int, ink: Int, brown: Int, gold: Int, shaft: BooleanArray = BooleanArray(n * n)) {
        px.fill(0)
        val h12 = ((hour % 12f) + 12f) % 12f
        hand(px, shaft, n, h12 / 12f, hourLen, ink, brown, gold)
        hand(px, shaft, n, h12 - kotlin.math.floor(h12), minuteLen, ink, brown, gold)
        val m = n / 2
        for (dy in -1..1) for (dx in -1..1) {
            px[(m + dy) * n + m + dx] = 0xFF000000.toInt() or when {
                dx == 0 && dy == 0 -> gold
                dx == 0 || dy == 0 -> brown
                else -> ink
            }
        }
    }

    /** One hand [turn] of the way round from twelve, [len] cells long. */
    private fun hand(px: IntArray, shaft: BooleanArray, n: Int, turn: Float, len: Int, ink: Int, brown: Int, gold: Int) {
        val a = turn * 2f * kotlin.math.PI.toFloat()
        val dx = kotlin.math.sin(a); val dy = -kotlin.math.cos(a)
        val m = n / 2
        shaft.fill(false, 0, n * n)
        var tip = -1
        for (s in 4..len * 4) {
            val t = s / 4f
            val x = kotlin.math.floor(.5f + dx * t).toInt(); val y = kotlin.math.floor(.5f + dy * t).toInt()
            if (kotlin.math.abs(x) <= 1 && kotlin.math.abs(y) <= 1) continue
            val i = (m + y) * n + m + x
            shaft[i] = true; tip = i
        }
        val b = 0xFF000000.toInt() or brown
        // brown round the shaft: its four neighbours, inside the picture, that are not shaft themselves
        for (i in 0 until n * n) {
            if (!shaft[i]) continue
            val x = i % n; val y = i / n
            if (x > 0 && !shaft[i - 1]) px[i - 1] = b
            if (x < n - 1 && !shaft[i + 1]) px[i + 1] = b
            if (y > 0 && !shaft[i - n]) px[i - n] = b
            if (y < n - 1 && !shaft[i + n]) px[i + n] = b
        }
        for (i in 0 until n * n) if (shaft[i]) px[i] = 0xFF000000.toInt() or ink
        if (tip >= 0) px[tip] = 0xFF000000.toInt() or gold
    }
}
