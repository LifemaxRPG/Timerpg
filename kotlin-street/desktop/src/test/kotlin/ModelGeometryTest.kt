import com.lifemax.rpg.feature.habits.domain.BuildingLayout
import com.lifemax.rpg.feature.habits.domain.BuildingSpec
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
             "l": [0, 40, 59, 135], "lp": [0, 134, 0, 0, 58, 26, 58, 57], "r": [60, 40, 59, 135], "rp": [58, 134, 58, 0, 0, 26, 0, 57], "roof": 5},
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
        assertTrue(m.parts[3].card)
        assertNotNull(m.lamp); assertEquals("home", m.lights)
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
}
