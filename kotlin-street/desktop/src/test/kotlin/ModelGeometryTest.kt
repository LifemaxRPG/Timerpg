import com.lifemax.rpg.feature.habits.domain.BuildingLayout
import com.lifemax.rpg.feature.habits.domain.BuildingSpec
import com.lifemax.rpg.feature.habits.domain.ClockHands
import com.lifemax.rpg.feature.habits.domain.HouseShape
import com.lifemax.rpg.feature.habits.domain.HouseView
import com.lifemax.rpg.feature.habits.domain.JUNCTION_ID
import com.lifemax.rpg.feature.habits.domain.JUNCTION_LOT
import com.lifemax.rpg.feature.habits.domain.ModelPart
import com.lifemax.rpg.feature.habits.domain.ModelShape
import com.lifemax.rpg.feature.habits.domain.ModelView
import com.lifemax.rpg.feature.habits.domain.Piece
import com.lifemax.rpg.feature.habits.domain.PieceKind
import com.lifemax.rpg.feature.habits.domain.QuadMap
import com.lifemax.rpg.feature.habits.domain.RoofFaces
import com.lifemax.rpg.feature.habits.domain.streetLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Our villas' and landmarks' parts (domain/ModelGeometry.kt): models.json, their view, their roofs' faces, their lots. */
class ModelGeometryTest {
    /** A villa much as tools/make_buildings.py writes one (shortened): a wall, a front gable, a hipped roof, a card. */
    private val json = """
        {"b8": {"kind": "villa", "w": 256, "frontX": -16, "setback": 96, "lawn": "grass", "style": "formal",
          "fence": {"d": 3, "foot": 32}, "roofTint": 5, "lamp": [104, 40, 0], "lights": "home", "smoke": [], "fpx": 0,
          "parts": [
            {"t": "box", "x": [2, 254], "y": [12, 52], "d": [0, 167], "c": [-1, 1, 2], "ft": [0, 0, 252, 40], "st": [0, 41, 167, 40],
             "panes": [[61, 2, 18, 20, 1], [130, 0, 50, 18, 1]]},
            {"t": "gable", "x": [0, 256], "y": [98, 161], "d": [36, 171], "l": [0, 0, 128, 198], "lp": [0, 197, 128, 133, 128, -2, 0, 62],
             "r": [129, 0, 128, 198], "rp": [128, 197, 0, 133, 0, -2, 128, 62], "rake": [3, 4], "roof": 5},
            {"t": "hip", "x": [34, 220], "y": [322, 355], "d": [0, 234], "top": [92, 162, 58, 176], "front": [0, 0, 186, 39],
             "l": [0, 40, 59, 135], "lp": [0, 134, 0, 0, 58, 26, 58, 57], "r": [60, 40, 59, 135], "rp": [58, 134, 58, 0, 0, 26, 0, 57], "roof": 5,
             "hip": [6369858, 6369858], "la": [120, 40, 59, 135], "ra": [180, 40, 59, 135]},
            {"t": "box", "x": [2, 34], "y": [12, 36], "d": [-53, -53], "c": [1, 2, 3], "ft": [0, 90, 32, 24], "card": true}]},
         "bad": {"kind": "villa"}}
    """.trimIndent()

    private val models = ModelShape.parseAll(json)

    @Test fun parsesEveryWellFormedModelAndSkipsTheRest() {
        assertEquals(setOf("b8"), models.keys)
        val m = models["b8"]!!
        assertEquals("villa", m.kind); assertEquals(-16f, m.frontX); assertEquals(96f, m.setback); assertTrue(m.grass)
        assertEquals(4, m.parts.size)
        val wall = m.parts[0]
        assertEquals(ModelPart.BOX, wall.type); assertEquals(-1, wall.top); assertEquals(2, wall.panes.size)
        assertEquals(167, wall.st!![2]); assertEquals(2f, wall.fx)
        val gable = m.parts[1]
        assertEquals(ModelPart.GABLE, gable.type); assertEquals(128f, gable.apex); assertEquals(171f, gable.eaveBack)
        assertEquals(8, gable.leftP!!.size); assertNull(gable.face)
        val hip = m.parts[2]
        assertEquals(ModelPart.HIP, hip.type); assertEquals(58f, hip.td0); assertEquals(39, hip.frontPic!![3])
        // its hips' crease and cap, and its side faces each course one colour along the depth, for a view nearly edge on
        assertEquals(0x613242, hip.hip!![1]); assertEquals(120, hip.leftFlush!![0]); assertEquals(180, hip.rightFlush!![0])
        assertNull(gable.hip); assertNull(gable.leftFlush)
        assertTrue(m.parts[3].card)
        assertNotNull(m.lamp); assertEquals("home", m.lights)
    }

    @Test fun aModernEstatesPartsParse() {
        // a single-pitch roof high at its left end (its right slope only), an annex's box with the side against the
        // house hidden, a stair's glass railing, a box with both sides against other volumes
        val m = ModelShape.parseAll("""
            {"b9": {"kind": "estate", "w": 736, "frontX": -256, "setback": 88, "lawn": "grass", "lights": "home", "smoke": [],
              "parts": [
                {"t": "box", "x": [545, 706], "y": [0, 96], "d": [78, 285], "c": [-1, 1, 2], "ft": [0, 0, 161, 96], "hs": "l"},
                {"t": "gable", "x": [284, 720], "y": [105, 167], "d": [34, 285], "r": [0, 0, 436, 310], "rp": [435, 310, 0, 246, 0, 0, 435, 62],
                 "ax": 284, "rake": [3, 4], "roof": 5},
                {"t": "rail", "x": [5, 5], "y": [8, 104], "d": [20, 150], "h": 16, "w": 2, "c": [7, 8], "ga": 70, "posts": 0},
                {"t": "box", "x": [285, 545], "y": [0, 104], "d": [150, 285], "c": [-1, 1, 1], "hs": "lr"}]}}
        """.trimIndent())["b9"]!!
        val (annex, roof, rail, under) = m.parts
        assertEquals(1, annex.hideSide); assertEquals(3, under.hideSide)
        assertNull(roof.left); assertEquals(8, roof.rightP!!.size); assertTrue(roof.monopitch); assertEquals(284f, roof.apex)
        assertEquals(ModelPart.RAIL, rail.type); assertEquals(16f, rail.railH); assertEquals(70, rail.top); assertEquals(8, rail.side)
        // the railing is no roof: the ridge is the roof's
        assertEquals(167f, m.ridge)
    }

    @Test fun itsWallsRidgeAndHeightComeFromItsParts() {
        val m = models["b8"]!!
        // its walls are its deep volumes (here the first box), not its railing card
        assertEquals(52f, m.wallTop)
        assertEquals(355f, m.ridge)
        assertEquals(117f, m.ridgeD)
        assertEquals(2f, m.wall0); assertEquals(254f, m.wall1)
        val k = BuildingSpec.W / 224f
        assertEquals(355f * k + BuildingSpec.DY / HouseView.DEPTH * (96f + 176f), m.screenTop, 1e-3f)
    }

    @Test fun standsBackAsAHouseDoes() {
        // the same front line, set back as far, lands where a house's does: one oblique view for both
        val m = models["b8"]!!
        val h = HouseShape(
            "small", 1, -16f, 2f, 254f, 86f, 6f, 16f, 80f, 4f, 2f, 4f, 96f, 0f, 0f, 0f, 0f, 0f, 0, 0, 0, 0,
            emptyList(), false, emptyList(), true, "compact", 0,
        )
        for (s in floatArrayOf(-20f, 0f, 14f)) for (u in floatArrayOf(0f, 2.4347825f)) {
            val v = ModelView().set(m, s, u, .3f); val hv = HouseView(h, s, u).set(h, s, u, .3f)
            for (d in floatArrayOf(-53f, 0f, 167f)) {
                assertEquals(hv.x(10f, d), v.x(10f, d), 1e-4f)
                assertEquals(hv.y(40f, d), v.y(40f, d), 1e-4f)
            }
        }
    }

    @Test fun aPieceIsLaidCornerToCornerOnItsQuad() {
        val from = floatArrayOf(0f, 134f, 0f, 0f, 58f, 26f, 58f, 57f)
        val to = floatArrayOf(10f, 200f, 25f, 180f, 70f, 150f, 60f, 160f)
        val m = DoubleArray(9)
        assertTrue(QuadMap.quadToQuad(from, to, m, DoubleArray(9), DoubleArray(9)))
        val p = FloatArray(2)
        for (i in 0 until 4) {
            QuadMap.apply(m, from[2 * i], from[2 * i + 1], p)
            assertEquals(to[2 * i], p[0], 1e-3f); assertEquals(to[2 * i + 1], p[1], 1e-3f)
        }
        // a parallelogram onto a parallelogram is a plain skew: no perspective in it
        val pa = floatArrayOf(0f, 197f, 128f, 133f, 128f, -2f, 0f, 62f)
        val pb = floatArrayOf(5f, 50f, 60f, 20f, 64f, 14f, 9f, 44f)
        assertTrue(QuadMap.quadToQuad(pa, pb, m, DoubleArray(9), DoubleArray(9)))
        assertEquals(0.0, m[6], 1e-9); assertEquals(0.0, m[7], 1e-9)
        // a degenerate quad is refused
        assertFalse(QuadMap.quadToQuad(floatArrayOf(0f, 0f, 1f, 1f, 2f, 2f, 3f, 3f), pb, m, DoubleArray(9), DoubleArray(9)))
    }

    /** A front gable's slope's screen corners (the front eave, the apex, the ridge's back end, the back eave) at side shift [s]. */
    private fun slope(m: ModelShape, g: ModelPart, s: Float, left: Boolean): FloatArray {
        val v = ModelView().set(m, s)
        val xe = if (left) g.x0 else g.x1
        return floatArrayOf(v.x(xe, g.d0), v.y(g.y0, g.d0), v.x(g.apex, g.d0), v.y(g.y1, g.d0), v.x(g.apex, g.d1), v.y(g.y1, g.d1), v.x(xe, g.eaveBack), v.y(g.y0, g.eaveBack))
    }

    @Test fun theViewSeesBothSlopesFromInFrontAndHidesTheFarOneFromTheSide() {
        val m = models["b8"]!!
        val g = m.parts[1]
        // straight on, a little above: both slopes show as bands over their rakes
        assertTrue(RoofFaces.gableSlopeSeen(true, slope(m, g, 0f, true)))
        assertTrue(RoofFaces.gableSlopeSeen(false, slope(m, g, 0f, false)))
        // from far to the right, the right slope still shows and the left one is behind the ridge
        assertTrue(RoofFaces.gableSlopeSeen(false, slope(m, g, 60f, false)))
        assertFalse(RoofFaces.gableSlopeSeen(true, slope(m, g, 60f, true)))
        assertTrue(RoofFaces.gableSlopeSeen(true, slope(m, g, -60f, true)))
        assertFalse(RoofFaces.gableSlopeSeen(false, slope(m, g, -60f, false)))
    }

    @Test fun aHippedRoofsSideFacesShowAsWedgesFromInFront() {
        val m = models["b8"]!!
        val h = m.parts[2]
        fun side(s: Float, left: Boolean): FloatArray {
            val v = ModelView().set(m, s)
            val xe = if (left) h.x0 else h.x1; val xt = if (left) h.tx0 else h.tx1
            return floatArrayOf(v.x(xe, h.d0), v.y(h.y0, h.d0), v.x(xe, h.d1), v.y(h.y0, h.d1), v.x(xt, h.td1), v.y(h.y1, h.td1), v.x(xt, h.td0), v.y(h.y1, h.td0))
        }
        assertTrue(RoofFaces.hipSideSeen(true, side(0f, true)))
        assertTrue(RoofFaces.hipSideSeen(false, side(0f, false)))
        // seen from the right, the right face shows and the left is turned away
        assertTrue(RoofFaces.hipSideSeen(false, side(80f, false)))
        assertFalse(RoofFaces.hipSideSeen(true, side(80f, true)))
    }

    @Test fun aWideBuildingStandsInTheMiddleOfItsWiderLot() {
        val b = BuildingSpec
        fun lot(id: String, lot: Float, wide: Float = 0f) = BuildingLayout(id, listOf(Piece(PieceKind.LOBBY, id, 0f, 10f, b.W)), emptyList(), 10f, 10f, lot, wide)
        val shop = lot("a", b.W + b.DX)
        val villa = lot("v", 256f * b.W / 224f + b.DX, 256f * b.W / 224f)
        val junction = BuildingLayout(JUNCTION_ID, listOf(Piece(PieceKind.LOBBY, JUNCTION_ID, 0f, 0f, JUNCTION_LOT)), emptyList(), 0f, 0f, JUNCTION_LOT)
        val st = streetLayout(listOf(shop, villa, junction, shop))
        // a shop's middle is where it always was, a villa's in the middle of its own lot, and the next lot starts after it
        assertEquals(0f, st.centers[0], 1e-4f)
        assertEquals(st.lefts[1] + villa.wide / 2f, st.centers[1], 1e-4f)
        assertEquals(st.lefts[1] + villa.lot + b.GAP, st.lefts[2], 1e-4f)
        assertEquals(st.lefts[2] + b.W / 2f, st.centers[2], 1e-4f)
        // a street without wide buildings is laid out exactly as before
        val plain = streetLayout(listOf(shop, shop, shop))
        for (i in 0..2) assertEquals(i * (b.W + b.DX + b.GAP), plain.centers[i], 1e-3f)
    }
    @Test fun aGablesEaveBoardsWaitForTheWallThatCarriesItsFace() {
        // the villa's order: its main gable, then the upper storey whose front carries the gable's face (drawn after it,
        // its side wall would paint over the eave's board), then a box beside it that carries nothing
        val m = ModelShape.parseAll("""
            {"v": {"kind": "villa", "w": 256, "frontX": -16, "setback": 96, "lawn": "grass", "style": "formal", "fence": null,
              "roofTint": 5, "lamp": null, "lights": "home", "smoke": [], "fpx": 0, "parts": [
                {"t": "gable", "x": [0, 256], "y": [98, 161], "d": [36, 171], "l": [0, 0, 128, 198], "lp": [0, 197, 128, 133, 128, -2, 0, 62],
                 "r": [129, 0, 128, 198], "rp": [128, 197, 0, 133, 0, -2, 128, 62], "rake": [3, 4], "roof": 5, "lip": 6},
                {"t": "box", "x": [2, 254], "y": [58, 98], "d": [36, 167], "c": [-1, 1, 2], "ft": [0, 0, 256, 106], "fx": 0, "sg": true},
                {"t": "box", "x": [176, 240], "y": [58, 98], "d": [-8, -8], "c": [1, 2, 3], "card": true}]}}
        """.trimIndent())["v"]!!
        assertEquals(0, m.eaveOn[1]); assertEquals(-1, m.eaveOn[2]); assertEquals(-1, m.eaveOn[0])
        assertTrue(m.eaveLater[0]); assertFalse(m.eaveLater[1])
        assertEquals(6, m.parts[0].lip); assertTrue(m.parts[1].sideGlow); assertFalse(m.parts[2].sideGlow)
        assertNull(m.parts[1].clock)
        // at the mouth of the side street no side face shows, from either side
        for (s in floatArrayOf(-30f, 30f)) {
            assertTrue(ModelView().set(m, s).showsSide)
            assertFalse(ModelView().set(m, s, side = false).showsSide)
        }
    }

    @Test fun aClocksHandsTellTheHourInTheDialsCells() {
        val n = ClockHands.size(12)
        val px = IntArray(n * n)
        val ink = 0x3A3A50; val brown = 0x7E6151; val gold = 0xBDA766
        fun at(x: Int, y: Int) = px[(n / 2 + y) * n + n / 2 + x] and 0xFFFFFF
        fun empty(x: Int, y: Int) = px[(n / 2 + y) * n + n / 2 + x] == 0
        // three o'clock: the minute hand up (ink down its middle, brown round it, gold at its tip), the hour hand right
        ClockHands.paint(px, n, 15f, 12, 9, ink, brown, gold)
        assertEquals(gold, at(0, 0)); assertEquals(brown, at(0, -1)); assertEquals(ink, at(-1, -1))
        assertEquals(ink, at(0, -5)); assertEquals(brown, at(-1, -5)); assertEquals(brown, at(1, -5))
        assertEquals(gold, at(0, -12)); assertTrue(empty(0, 5))
        assertEquals(ink, at(5, 0)); assertEquals(gold, at(9, 0)); assertEquals(brown, at(10, 0)); assertTrue(empty(11, 0)); assertTrue(empty(-5, 0))
        // half past ten: the minute hand straight down, the hour hand between ten and eleven, up and to the left
        // (into a painter's scratch, as the street paints them again each minute: the same picture)
        val again = IntArray(n * n)
        ClockHands.paint(again, n, 15f, 12, 9, ink, brown, gold, BooleanArray(n * n))
        assertTrue(again.contentEquals(px))
        ClockHands.paint(px, n, 22.5f, 12, 9, ink, brown, gold)
        assertEquals(gold, at(0, 12)); assertTrue(empty(0, -6))
        var upLeft = 0
        for (y in -9..-2) for (x in -9..-2) if (at(x, y) == ink) upLeft++
        assertEquals(4, upLeft); assertEquals(gold, at(-6, -6)); assertTrue(empty(6, -6))
    }

    /** The civic buildings' parts: a box against its neighbour, a sloped canopy, a picture lying flat on a roof. */
    @Test fun parsesAbuttingSidesSlopedSlabsAndDecals() {
        val m = ModelShape.parse(org.json.JSONObject("""
            {"kind": "civic", "w": 256, "frontX": -16, "setback": 32, "lawn": "paving", "style": "natural", "fence": null,
             "roofTint": 5, "lamp": null, "lights": "shop", "smoke": [], "fpx": 0, "parts": [
              {"t": "box", "x": [0, 256], "y": [0, 194], "d": [0, 144], "c": [1, 2, 3], "hs": "r"},
              {"t": "slope", "x": [16, 240], "y": [74, 90], "d": [-18, 0], "c": [1, 2, 3], "th": 16, "ft": [0, 0, 224, 16], "tt": [0, 17, 224, 16]},
              {"t": "decal", "x": [320, 490], "y": [260.5, 260.5], "d": [-35, 136], "p": [0, 40, 171, 171]}]}
        """.trimIndent()))!!
        val (box, slab, pad) = m.parts
        assertEquals(2, box.hideSide)
        assertEquals(ModelPart.SLOPE, slab.type); assertEquals(16f, slab.thick); assertEquals(90f, slab.y1); assertEquals(17, slab.tt!![1])
        assertEquals(ModelPart.DECAL, pad.type); assertEquals(260.5f, pad.y0); assertEquals(171, pad.tt!![3])
        // neither is a roof: the highest roof is the box's top
        assertEquals(194f, m.ridge)
    }

    /** A chamfered corner: an upright prism over its plan, its bounds as x and d, the faces against other parts hidden. */
    @Test fun parsesPrisms() {
        val p = ModelShape.parsePart(org.json.JSONObject("""
            {"t": "prism", "pts": [[256, 0], [288, -96], [288, 0]], "x": [256, 288], "y": [0, 260], "d": [-96, 0],
             "c": [1, 2, 3], "ft": [0, 0, 101, 260], "hide": [1]}
        """.trimIndent()))
        assertEquals(ModelPart.PRISM, p.type); assertEquals(6, p.plan!!.size); assertEquals(-96f, p.plan!![3])
        assertEquals(-96f, p.d0); assertEquals(288f, p.x1); assertEquals(2, p.hidden); assertEquals(101, p.ft!![2])
    }
}
