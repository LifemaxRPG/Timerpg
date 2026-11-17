import com.lifemax.rpg.feature.habits.domain.BuildingSpec
import com.lifemax.rpg.feature.habits.domain.HouseShape
import com.lifemax.rpg.feature.habits.domain.HouseView
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Our pitched-roof houses' shape (domain/HouseGeometry.kt) against what tools/make_buildings.py measured. */
class HouseGeometryTest {
    /** A small and a medium mobile home as make_buildings.py writes them (their slope rows: 21 and 23). */
    private val json = """
        {"b9": {"tier": "small", "design": 7, "colour": "c9", "frontX": 32, "wall": [2, 158], "h": 86, "fascia": 6,
          "rise": 16.536, "depth": 80, "over": [4, 2], "slab": 4, "setback": 64,
          "flue": {"x": [75, 87], "d": [45.4, 55.4], "top": 112.536, "body": 1, "cap": 2}, "rake": [3, 4], "hitch": [158, 0],
          "panes": [[16, 20, 24, 24], [120, 20, 24, 24]], "glass": true, "lawn": "grass", "style": "compact", "roofTint": 5,
          "boxes": [{"x": [52, 108], "y": [10, 14], "z": [0, 64], "c": [1, 2, 3], "ft": [0, 12, 56, 6], "tt": [0, 18, 48, 6], "card": false},
                    {"x": [50, 108], "y": [14, 26], "z": [62, 64], "c": [1, 2, 3], "ft": [0, 0, 58, 12], "tt": null, "card": true}]},
         "b8": {"tier": "medium", "design": 2, "colour": "c9", "frontX": 0, "wall": [2, 222], "h": 86, "fascia": 6,
          "rise": 18.130, "depth": 88, "over": [4, 2], "slab": 4, "setback": 64,
          "flue": {"x": [75, 87], "d": [50.4, 60.4], "top": 114.13, "body": 1, "cap": 2}, "rake": [3, 4], "hitch": [222, 0],
          "panes": [[16, 22, 14, 20]], "glass": false, "lawn": "earth", "style": "formal", "roofTint": 5, "boxes": []},
         "bad": {"tier": "small"}}
    """.trimIndent()

    private val houses = HouseShape.parseAll(json)

    @Test fun parsesEveryWellFormedHouseAndSkipsTheRest() {
        assertEquals(setOf("b8", "b9"), houses.keys)
        val h = houses["b9"]!!
        assertEquals(7, h.design); assertEquals(32f, h.frontX); assertEquals(158f, h.wall1)
        assertEquals(2, h.boxes.size); assertTrue(h.boxes[1].card); assertEquals(56, h.boxes[0].frontTex!![2])
        assertTrue(h.grass); assertTrue(h.glass); assertFalse(houses["b8"]!!.grass)
        assertEquals(1, houses["b8"]!!.panes.size)
    }

    @Test fun theNearSlopeShowsOneScreenRowPerPictureRow() {
        // the rise was chosen so: rise + the run's depth rising with the view = the slope picture's rows
        assertEquals(21f, HouseView.slopeRows(houses["b9"]!!), .01f)
        assertEquals(23f, HouseView.slopeRows(houses["b8"]!!), .01f)
        for ((_, h) in houses) for (s in floatArrayOf(-14f, 0f, 9f, 14f)) {
            val v = HouseView(h, s)
            val onScreen = v.y(h.h, -h.eaveOver) - v.y(h.ridge, h.depth / 2f)
            assertEquals(HouseView.slopeRows(h) * v.k, onScreen, 1e-3f)
        }
    }

    @Test fun theGableMeetsTheRidge() {
        for ((_, h) in houses) for (s in floatArrayOf(-14f, 0f, 14f)) {
            val v = HouseView(h, s)
            // the roof slab's top over the gable at mid-depth is the ridge, where the slope's back edge is drawn
            assertEquals(h.ridge, h.roofTop(h.depth / 2f), 1e-4f)
            assertEquals(h.ridge - h.slab, h.gableTop(h.depth / 2f), 1e-4f)
            val x = if (s >= 0f) h.wall1 + h.verge else h.wall0 - h.verge
            assertEquals(v.x(x, h.depth / 2f), v.x(x, -h.eaveOver) + v.lean(h.depth / 2f + h.eaveOver), 1e-3f)
            assertEquals(v.y(h.ridge, h.depth / 2f), v.y(h.h, -h.eaveOver) - HouseView.slopeRows(h) * v.k, 1e-3f)
            // the eaves front and back are at the fascia's top
            assertEquals(h.h, h.roofTop(-h.eaveOver), 1e-4f); assertEquals(h.h, h.roofTop(h.depth + h.eaveOver), 1e-4f)
        }
    }

    @Test fun noGableShowsFaceOn() {
        val h = houses["b9"]!!
        assertFalse(HouseView(h, 0f).showsSide)
        assertTrue(HouseView(h, 14f).showsSide && HouseView(h, 14f).rightSide)
        assertTrue(HouseView(h, -14f).showsSide && !HouseView(h, -14f).rightSide)
        // and face on, depth only rises: nothing leans
        assertEquals(HouseView(h, 0f).x(10f, 0f), HouseView(h, 0f).x(10f, 80f), 0f)
    }

    @Test fun theHouseStandsBackInItsYardOnTheScreensPixels() {
        val h = houses["b9"]!!
        // 64 pixels back: up DY * 64 / 120 and over by the lean of that depth
        val v = HouseView(h, 14f)
        assertEquals(-BuildingSpec.DY * 64f / 120f, v.y(0f, 0f), 1e-4f)
        // snapped to whole screen pixels when the screen's scale is known
        val unit = 2.4347825f
        val snapped = HouseView(h, 11.3f, unit)
        assertTrue(abs(snapped.ox * unit - kotlin.math.round(snapped.ox * unit)) < 1e-3f)
        assertTrue(abs(snapped.oy * unit - kotlin.math.round(snapped.oy * unit)) < 1e-3f)
    }

    @Test fun oneViewSetAgainIsTheSameAsANewOne() {
        // the painter keeps one view and sets it for each house in turn
        val v = HouseView(houses["b8"]!!, -9f, 2.4f)
        for ((_, h) in houses) for (s in floatArrayOf(-14f, 0f, 11.3f)) {
            val fresh = HouseView(h, s, 2.4f)
            v.set(h, s, 2.4f)
            assertEquals(fresh.x(40f, 30f), v.x(40f, 30f), 0f)
            assertEquals(fresh.y(20f, 30f), v.y(20f, 30f), 0f)
            assertEquals(fresh.rightSide, v.rightSide)
        }
    }

    @Test fun thePorchIsDrawnBackToFrontFromEitherSide() {
        val h = houses["b9"]!!
        for (s in floatArrayOf(-14f, 0f, 14f)) {
            val o = h.boxesInOrder(s)
            assertEquals(h.boxes.size, o.size)
            // the deck before the railing standing on it
            assertTrue(o.indexOf(h.boxes[0]) < o.indexOf(h.boxes[1]))
        }
        // worked out once for each side
        assertTrue(h.boxesInOrder(3f) === h.boxesInOrder(14f))
    }

    @Test fun theHousesTopIsItsRidgeOrItsFlueOverItsYard() {
        for ((_, h) in houses) {
            val k = BuildingSpec.W / 224f
            val lift = BuildingSpec.DY / HouseView.DEPTH
            // on screen: the ridge cap at mid-depth, the flue's top behind it, both lifted by where they stand
            val ridge = (h.ridge + 2f) * k + lift * (h.setback + h.depth / 2f)
            val flue = h.flueTop * k + lift * (h.setback + h.flueD1)
            assertEquals(maxOf(ridge, flue), h.screenTop, 1e-4f)
            assertTrue(h.screenTop > h.h * k + lift * h.setback)
        }
    }

    @Test fun theCommittedHousesMatchTheView() {
        // the app's houses.json, if the street has houses: every slope picture must be as tall as the view makes it
        val dir = File("../app/src/main/assets/buildings").takeIf { it.exists() } ?: File("app/src/main/assets/buildings")
        val f = File(dir, "houses.json"); if (!f.exists()) return
        val all = HouseShape.parseAll(f.readText())
        assertTrue(all.isNotEmpty())
        for ((n, h) in all) {
            val slope = File(dir, "${n}_slope.png")
            assertTrue("$n has its slope", slope.exists())
            val rows = javax.imageio.ImageIO.read(slope).height
            assertEquals("$n's slope rows", rows.toFloat(), HouseView.slopeRows(h), .05f)
            assertNotNull(File(dir, "${n}_side.png").takeIf { it.exists() })
        }
    }
}
