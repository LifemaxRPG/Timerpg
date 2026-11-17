package com.lifemax.rpg.feature.habits.domain

import kotlin.math.max

/*
 * Our villas and landmarks (the clock tower, the school) as shape with no drawing in it: what tools/make_buildings.py's
 * build_model cut from their sprites (buildings/models.json). Each is a list of parts drawn back to front: boxes, front
 * gables (the triangle end facing the street, the slopes running back from it) and hipped roofs (four sloped faces and
 * a flat top). Lengths are sprite pixels: x across the model's front picture (which stands [ModelShape.frontX] pixels into
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
    /** A gable's rake boards' face and edge colours; where its eaves end at the back ([d1] unless it meets another roof). */
    val rake: Int = 0, val rakeEdge: Int = 0, val eaveBack: Float = d1,
    /** A gable's face piece standing on its front ([faceY] its foot's height, [fx] its left edge), if not a box's front. */
    val face: IntArray? = null, val faceY: Float = 0f,
    /** A hipped roof's front face piece (its rows the screen's, ridge to eave), its flat top's piece and extent. */
    val frontPic: IntArray? = null, val flat: IntArray? = null,
    val tx0: Float = 0f, val tx1: Float = 0f, val td0: Float = 0f, val td1: Float = 0f,
) {
    /** A gable's or a hipped roof's apex x. */
    val apex: Float get() = (x0 + x1) / 2f

    companion object {
        const val BOX = 0
        const val GABLE = 1
        const val HIP = 2
    }
}

/** A modelled building (buildings/models.json). */
class ModelShape(
    /** "villa", "tower" or "school". */
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
    private val tallestRoof: ModelPart? = parts.filter { it.type != ModelPart.BOX }.maxByOrNull { it.y1 }
    val ridge: Float = max(tallestRoof?.y1 ?: 0f, parts.maxOfOrNull { if (it.type == ModelPart.BOX) it.y1 else 0f } ?: 0f)
    val ridgeD: Float = tallestRoof?.let { (it.d0 + it.d1) / 2f } ?: 0f
    /** Its walls' span across the front picture. */
    val wall0: Float = walls.minOfOrNull { it.x0 } ?: 0f
    val wall1: Float = walls.maxOfOrNull { it.x1 } ?: width
    /** How deep it is behind its front line. */
    val depth: Float = parts.maxOfOrNull { it.d1 } ?: 0f

    /** How high its highest point shows above the pavement line (street units), lifted by how far back it stands. */
    val screenTop: Float get() {
        val k = BuildingSpec.W / 224f
        val lift = BuildingSpec.DY / HouseView.DEPTH
        var best = 0f
        for (p in parts) {
            val d = if (p.type == ModelPart.BOX) p.d1 else if (p.type == ModelPart.HIP) p.td1 else p.d1
            best = max(best, p.y1 * k + lift * (setback + d))
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
                        if (pn == null) emptyList() else (0 until pn.length()).map { floats(pn.getJSONArray(it))!! })
                }
                "gable" -> {
                    val rk = p.getJSONArray("rake")
                    val pn = p.optJSONArray("panes")
                    ModelPart(ModelPart.GABLE, x.f(0), x.f(1), y.f(0), y.f(1), d.f(0), d.f(1), -1, p.getInt("roof"), 0,
                        fx = p.optDouble("fx", x.getDouble(0)).toFloat(),
                        panes = if (pn == null) emptyList() else (0 until pn.length()).map { floats(pn.getJSONArray(it))!! },
                        left = ints(p.getJSONArray("l")), leftP = floats(p.getJSONArray("lp")),
                        right = ints(p.getJSONArray("r")), rightP = floats(p.getJSONArray("rp")),
                        rake = rk.getInt(0), rakeEdge = rk.getInt(1), eaveBack = p.optDouble("de", d.getDouble(1)).toFloat(),
                        face = ints(p.optJSONArray("face")), faceY = p.optDouble("fy", 0.0).toFloat())
                }
                "hip" -> {
                    val t = p.getJSONArray("top")
                    ModelPart(ModelPart.HIP, x.f(0), x.f(1), y.f(0), y.f(1), d.f(0), d.f(1), -1, p.getInt("roof"), 0,
                        left = ints(p.getJSONArray("l")), leftP = floats(p.getJSONArray("lp")),
                        right = ints(p.getJSONArray("r")), rightP = floats(p.getJSONArray("rp")),
                        frontPic = ints(p.getJSONArray("front")), flat = ints(p.optJSONArray("flat")),
                        tx0 = t.f(0), tx1 = t.f(1), td0 = t.f(2), td1 = t.f(3))
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
    /** This view for model [shape] at side shift [s]; returns itself. */
    fun set(shape: ModelShape, s: Float, unitPx: Float = 0f, originX: Float = 0f): ModelView {
        place(shape.frontX, shape.setback, s, unitPx, originX)
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
