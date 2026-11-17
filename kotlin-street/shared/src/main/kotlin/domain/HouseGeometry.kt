package com.lifemax.rpg.feature.habits.domain

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.min

/*
 * One of our pitched-roof houses (a mobile home: houseSmall / houseMedium), as shape with no drawing in it: what
 * tools/make_buildings.py measured from its sprite (buildings/houses.json) and where each of its points lands on screen.
 *
 * Its own lengths are sprite pixels: x along the front in the front picture's pixels (the picture stands [HouseShape.frontX]
 * pixels into the lot's 224-pixel frame), y up from the ground, and depth either d behind the pavement line or z out of the
 * house's front wall (towards the viewer). The screen is the street's: units from the building's middle at the pavement
 * line, y down. The view is the engine's oblique one: a building's 224 pixels are [BuildingSpec.W] wide, and 120 pixels of
 * depth rise [BuildingSpec.DY] and lean by the side shift s ([Perspective.shift]) at full side. A house stands back from
 * the pavement in its own front yard, so it moves against the shops and lamps as the street scrolls (its depth shows).
 */

/** One box of a house's porch or steps (front-picture pixels; z out of the wall), its colours (0xRRGGBB) and pictures. */
class HouseBox(
    val x0: Float, val x1: Float, val y0: Float, val y1: Float, val z0: Float, val z1: Float,
    val top: Int, val front: Int, val side: Int,
    /** Where its front face's and its top's pictures are in the house's porch picture (x, y, w, h), if it has them. */
    val frontTex: IntArray? = null,
    val topTex: IntArray? = null,
    /** A thin upright panel (the porch's front railing) rather than a block. */
    val card: Boolean = false,
)

/** A house's shape (buildings/houses.json, written by tools/make_buildings.py's build_house). */
class HouseShape(
    val tier: String,
    val design: Int,
    /** Where its front picture stands in the lot's 224-pixel frame. */
    val frontX: Float,
    /** The wall's span across the front picture (the roof overhangs it by [verge] each side). */
    val wall0: Float,
    val wall1: Float,
    /** The front picture's height: the eave's edge ([fascia] rows), the wall, and under the floor. */
    val h: Float,
    val fascia: Float,
    /** How high the ridge stands over the eave, and how deep the house is (front to back). */
    val rise: Float,
    val depth: Float,
    /** The roof's overhang at the eaves and at the gables, and the roof slab's thickness. */
    val eaveOver: Float,
    val verge: Float,
    val slab: Float,
    /** How far back from the pavement the house stands (its front yard). */
    val setback: Float,
    /** The flue: its x span, its depth span (behind the front wall), its top's height, its colours. */
    val flueX0: Float, val flueX1: Float, val flueD0: Float, val flueD1: Float, val flueTop: Float,
    val flueBody: Int, val flueCap: Int,
    /** The rake boards' face and edge colours. */
    val rake: Int,
    val rakeEdge: Int,
    /** The window panes (x, y, w, h in front-picture pixels): glass with curtains ([glass]) or blinds. */
    val panes: List<FloatArray>,
    val glass: Boolean,
    /** The porch or the steps, back to front as built. */
    val boxes: List<HouseBox>,
    /** Grass round it, or the bare earth the art's shadow designs stand on. */
    val grass: Boolean,
    /** The yard's edge along the street (the game's: picket, hedge for a formal yard, nothing for a natural one). */
    val style: String,
    /** The roof's colour (0xRRGGBB), for what is drawn new on it. */
    val roofTint: Int,
    /** The fence (or hedge) along the yard's front: how far back from the pavement it stands, and its picture's foot row. */
    val fenceD: Float = 0f,
    val fenceFoot: Float = 0f,
) {
    /** The near slope's run: from the eave (overhanging the wall) back to the ridge at mid-depth. */
    val run: Float get() = depth / 2f + eaveOver

    /** The roof's pitch (radians). */
    val pitch: Float get() = atan(rise / run)

    /** The ridge's height. */
    val ridge: Float get() = h + rise

    /** The top of the roof slab over depth [dd] behind the front wall (from the front eave to the back one). */
    fun roofTop(dd: Float): Float {
        val m = min(dd + eaveOver, depth + eaveOver - dd)
        return h + rise * (m / run).coerceIn(0f, 1f)
    }

    /**
     * How high its highest point shows above the pavement line (street units): the ridge cap at mid-depth or the flue's
     * top further back, each lifted by how far back it stands.
     */
    val screenTop: Float get() {
        val k = BuildingSpec.W / 224f
        val lift = BuildingSpec.DY / HouseView.DEPTH
        return kotlin.math.max((ridge + 2f) * k + lift * (setback + depth / 2f), flueTop * k + lift * (setback + flueD1))
    }

    /** The gable's top edge (the end wall under the roof slab) at depth [dd] behind the front wall. */
    fun gableTop(dd: Float): Float = roofTop(dd) - slab

    /** The porch's or steps' boxes in the order they are drawn, back to front, seen from the right (side shift >= 0). */
    private val fromRight: List<HouseBox> by lazy { inOrder(true) }
    private val fromLeft: List<HouseBox> by lazy { inOrder(false) }

    /**
     * [boxes] back to front for side shift [s]: the order depends only on which side the view is from, so each is
     * worked out once and kept.
     */
    fun boxesInOrder(s: Float): List<HouseBox> = if (s >= 0f) fromRight else fromLeft

    private fun inOrder(right: Boolean): List<HouseBox> {
        val o = boxes.toMutableList()
        // a few boxes: the simplest sort that keeps every pair in order
        for (i in o.indices) for (j in i + 1 until o.size) {
            if (precedes(o[j], o[i], right) && !precedes(o[i], o[j], right)) { val t = o[i]; o[i] = o[j]; o[j] = t }
        }
        return o
    }

    companion object {
        /** Every house in houses.json, by sprite name (bN). A malformed entry is skipped. */
        fun parseAll(text: String?): Map<String, HouseShape> {
            if (text == null) return emptyMap()
            val j = try { org.json.JSONObject(text) } catch (e: Exception) { return emptyMap() }
            val out = HashMap<String, HouseShape>()
            for (key in j.keys()) {
                val o = j.optJSONObject(key) ?: continue
                parse(o)?.let { out[key] = it }
            }
            return out
        }

        private fun org.json.JSONArray.f(i: Int): Float = getDouble(i).toFloat()
        private fun ints(a: org.json.JSONArray?): IntArray? = a?.let { IntArray(it.length()) { i -> it.getInt(i) } }

        /** Which of two of a house's boxes must be drawn first: the one behind, else the one below, else the one away from the view. */
        fun precedes(p: HouseBox, q: HouseBox, fromRight: Boolean): Boolean {
            val e = .01f
            if (p.z1 <= q.z0 + e) return true
            if (q.z1 <= p.z0 + e) return false
            if (p.y1 <= q.y0 + e) return true
            if (q.y1 <= p.y0 + e) return false
            return if (fromRight) p.x1 <= q.x0 + e else p.x0 >= q.x1 - e
        }

        fun parse(o: org.json.JSONObject): HouseShape? = try {
            val wall = o.getJSONArray("wall"); val over = o.getJSONArray("over"); val fl = o.getJSONObject("flue")
            val fx = fl.getJSONArray("x"); val fd = fl.getJSONArray("d"); val rk = o.getJSONArray("rake")
            val ps = o.getJSONArray("panes")
            val bs = o.getJSONArray("boxes")
            HouseShape(
                o.getString("tier"), o.getInt("design"), o.getDouble("frontX").toFloat(), wall.f(0), wall.f(1),
                o.getDouble("h").toFloat(), o.getDouble("fascia").toFloat(), o.getDouble("rise").toFloat(), o.getDouble("depth").toFloat(),
                over.f(0), over.f(1), o.getDouble("slab").toFloat(), o.getDouble("setback").toFloat(),
                fx.f(0), fx.f(1), fd.f(0), fd.f(1), fl.getDouble("top").toFloat(), fl.getInt("body"), fl.getInt("cap"),
                rk.getInt(0), rk.getInt(1),
                (0 until ps.length()).map { i -> val p = ps.getJSONArray(i); FloatArray(4) { p.f(it) } },
                o.optBoolean("glass"),
                (0 until bs.length()).map { i ->
                    val b = bs.getJSONObject(i); val x = b.getJSONArray("x"); val y = b.getJSONArray("y"); val z = b.getJSONArray("z")
                    val c = b.getJSONArray("c")
                    HouseBox(x.f(0), x.f(1), y.f(0), y.f(1), z.f(0), z.f(1), c.getInt(0), c.getInt(1), c.getInt(2),
                        ints(b.optJSONArray("ft")), ints(b.optJSONArray("tt")), b.optBoolean("card"))
                },
                o.optString("lawn") == "grass", o.optString("style", "compact"), o.optInt("roofTint"),
                o.optJSONObject("fence")?.optDouble("d")?.toFloat() ?: 0f, o.optJSONObject("fence")?.optDouble("foot")?.toFloat() ?: 0f,
            )
        } catch (e: Exception) { null }
    }
}

/**
 * Where the points of something standing back from the pavement land on screen ([BuildingSpec]'s oblique view), drawn
 * with side shift [s]: a house in its yard ([HouseView]), a villa or a landmark ([ModelView]). x runs across its front
 * picture (which stands [frontX] pixels into the lot's 224-pixel frame), y up from the ground, dd back from its front
 * line, which stands [setback] back from the pavement. The offset of that line is snapped to whole screen pixels when
 * [unitPx] (screen pixels per unit) is known, as the camera is, so the art's pixels stay on the screen's grid.
 */
open class SetBackView {
    /** Units per sprite pixel. */
    val k: Float = BuildingSpec.W / 224f
    var s: Float = 0f; protected set
    var frontX: Float = 0f; protected set
    var setback: Float = 0f; protected set
    /** The front line's offset from where a shop's front stands (units): to the shown side and up. */
    var ox: Float = 0f; protected set
    var oy: Float = 0f; protected set

    /**
     * This view at side shift [s]. [originX]: how far right of a whole screen pixel the building's middle stands
     * (units), so its front lands on the screen's pixels, not only on whole pixels from its middle.
     */
    protected fun place(frontX: Float, setback: Float, s: Float, unitPx: Float, originX: Float) {
        this.frontX = frontX; this.setback = setback; this.s = s
        val leanBack = s * setback / HouseView.DEPTH
        val liftBack = BuildingSpec.DY * setback / HouseView.DEPTH
        ox = if (unitPx > 0f) kotlin.math.round((leanBack + originX) * unitPx) / unitPx - originX else leanBack
        oy = if (unitPx > 0f) -kotlin.math.round(liftBack * unitPx) / unitPx else -liftBack
    }

    /** Screen x of the point [x] across the front picture, [dd] behind the front line (negative: in front of it). */
    fun x(x: Float, dd: Float): Float = -BuildingSpec.W / 2f + (frontX + x) * k + ox + s * dd / HouseView.DEPTH

    /** Screen y of the point [y] up from the ground, [dd] behind the front line. */
    fun y(y: Float, dd: Float): Float = oy - y * k - BuildingSpec.DY * dd / HouseView.DEPTH

    /** How far [dd] of depth leans on screen (units). */
    fun lean(dd: Float): Float = s * dd / HouseView.DEPTH

    /** How far [dd] of depth rises on screen (units). */
    fun lift(dd: Float): Float = BuildingSpec.DY * dd / HouseView.DEPTH

    /** Whether a side face may show at all (not at the mouth of the side street). */
    protected var sideAllowed: Boolean = true

    /** Whether a side face shows (the side the view is from), and which: the right one when [s] > 0. */
    val showsSide: Boolean get() = sideAllowed && abs(s) > .02f
    val rightSide: Boolean get() = s > 0f
}

/**
 * Where a house's points land on screen, for a house drawn with side shift [s] ([SetBackView]: its front wall stands
 * [HouseShape.setback] back). One view can be [set] again for the next house, so the painter keeps one instead of
 * making one per house per frame.
 */
class HouseView(shape: HouseShape, s: Float, unitPx: Float = 0f) : SetBackView() {
    var shape: HouseShape = shape; private set

    init { set(shape, s, unitPx) }

    /** This view for house [shape] at side shift [s]; returns itself ([SetBackView.place]). */
    fun set(shape: HouseShape, s: Float, unitPx: Float = 0f, originX: Float = 0f): HouseView {
        this.shape = shape
        place(shape.frontX, shape.setback, s, unitPx, originX)
        return this
    }

    companion object {
        /** The depth (sprite pixels) whose lean at full side is the side shift itself (a shop's side face is this deep). */
        const val DEPTH = 120f

        /** How many sprite pixels high on screen the near slope is: its rise, and its run's depth rising with the view. */
        fun slopeRows(shape: HouseShape): Float =
            shape.rise + BuildingSpec.DY / (BuildingSpec.W / 224f) * shape.run / DEPTH
    }
}

/**
 * A thin convex quad filled on the screen's own pixel grid, one whole-pixel span per screen row. A house's gable end
 * is seen almost edge on, so its rake boards and the soffit under them are steep bands a pixel or two wide: drawn as
 * paths they come out as blended slivers with the sky between them; as spans they are pixel art's stepped lines, and
 * never thinner than one pixel, so nothing shows through.
 */
object PixelRows {
    /**
     * Calls [span] (left, top, right, bottom, in units) for each screen row whose middle the quad (a, b, c, d: its
     * corners in order) covers. [unitPx] is screen pixels per unit; [originX] is added to x before it is put on the
     * pixel grid (the units' x 0 stands [originX] units right of a whole screen pixel; their y 0 is on one). A row the
     * quad covers less than a pixel wide gets the pixel its middle is in.
     */
    inline fun quad(
        ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float,
        unitPx: Float, originX: Float, span: (Float, Float, Float, Float) -> Unit,
    ) {
        val top = min(min(ay, by), min(cy, dy)); val bottom = kotlin.math.max(kotlin.math.max(ay, by), kotlin.math.max(cy, dy))
        val r0 = kotlin.math.ceil(top * unitPx - .5f).toInt(); val r1 = kotlin.math.floor(bottom * unitPx - .5f).toInt()
        for (r in r0..r1) {
            val yc = (r + .5f) / unitPx
            val lo = min(min(cross(ax, ay, bx, by, yc, true), cross(bx, by, cx, cy, yc, true)), min(cross(cx, cy, dx, dy, yc, true), cross(dx, dy, ax, ay, yc, true)))
            val hi = kotlin.math.max(kotlin.math.max(cross(ax, ay, bx, by, yc, false), cross(bx, by, cx, cy, yc, false)), kotlin.math.max(cross(cx, cy, dx, dy, yc, false), cross(dx, dy, ax, ay, yc, false)))
            if (lo > hi) continue
            var l = kotlin.math.round((lo + originX) * unitPx); var rr = kotlin.math.round((hi + originX) * unitPx)
            if (rr <= l) { l = kotlin.math.floor(((lo + hi) / 2f + originX) * unitPx); rr = l + 1f }
            span(l / unitPx - originX, r / unitPx, rr / unitPx - originX, (r + 1) / unitPx)
        }
    }

    /**
     * Where the edge (p to q) crosses the row at [y]: its x, or (when it does not) +infinity for the [low] end and
     * -infinity for the high one, so it drops out of a min or a max. An edge lying along the row gives its own end.
     */
    @PublishedApi internal fun cross(px: Float, py: Float, qx: Float, qy: Float, y: Float, low: Boolean): Float {
        val miss = if (low) Float.POSITIVE_INFINITY else Float.NEGATIVE_INFINITY
        if ((y < py && y < qy) || (y > py && y > qy)) return miss
        if (py == qy) return if (low) min(px, qx) else kotlin.math.max(px, qx)
        return px + (qx - px) * (y - py) / (qy - py)
    }
}
