import com.lifemax.rpg.feature.habits.domain.BuildingLayout
import com.lifemax.rpg.feature.habits.domain.BuildingSpec
import com.lifemax.rpg.feature.habits.domain.Hit
import com.lifemax.rpg.feature.habits.domain.HitKind
import com.lifemax.rpg.feature.habits.domain.HouseShape
import com.lifemax.rpg.feature.habits.domain.HouseShell
import com.lifemax.rpg.feature.habits.domain.ShellPart
import com.lifemax.rpg.feature.habits.domain.ShellQuad
import com.lifemax.rpg.feature.habits.domain.HouseView
import com.lifemax.rpg.feature.habits.domain.Piece
import com.lifemax.rpg.feature.habits.domain.PieceKind
import com.lifemax.rpg.feature.habits.domain.PixelRows
import com.lifemax.rpg.feature.habits.domain.hit
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test fun theShellsRoofMeetsItsWalls() {
        // (the 3D engines' house: HouseShell) the roof is a slab; the gables reach its underside, the walls' tops are
        // inside it, its boards and soffits close it all round, and it overhangs the walls at the eaves and the verges
        for ((_, h) in houses) {
            val pw = h.wall1 + h.wall0
            val qs = HouseShell.quads(h, pw, h.h + kotlin.math.ceil(h.rise))
            fun of(p: ShellPart) = qs.filter { it.part == p }
            fun corners(q: ShellQuad) = listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f).map { (a, b) -> FloatArray(3) { q.o[it] + q.u[it] * a + q.v[it] * b } }
            // the gables stand at the wall's ends, its whole depth
            assertEquals(listOf(h.wall0, h.wall1), of(ShellPart.GABLE).map { it.o[0] })
            for (g in of(ShellPart.GABLE)) assertEquals(h.depth, g.u[2], 1e-4f)
            // the walls' tops are inside the slab where they meet it (under its top, over its underside)
            assertTrue(h.h <= h.roofTop(0f) && h.h >= h.gableTop(0f))
            // every rake board and soffit lies on the slab's top or underside, between the eaves
            for (q in of(ShellPart.RAKE) + of(ShellPart.SOFFIT)) for (c in corners(q)) {
                val d = c[2]
                assertTrue("${q.part} at d $d", d >= -h.eaveOver - 1e-3f && d <= h.depth + h.eaveOver + 1e-3f)
                assertTrue("${q.part} corner (${c[0]}, ${c[1]}, $d)", abs(c[1] - h.roofTop(d)) < 1e-3f || abs(c[1] - h.gableTop(d)) < 1e-3f)
            }
            // the rake boards at the verges' edges, past the gables; the slopes as wide
            assertEquals(setOf(0f, pw), of(ShellPart.RAKE).map { it.o[0] }.toSet())
            for (sl in of(ShellPart.SLOPE)) { assertEquals(0f, sl.o[0]); assertEquals(pw, sl.u[0]) }
            // the soffits cover the underside from verge edge to verge edge at both eaves (no gap at a corner)
            for (e in floatArrayOf(-h.eaveOver, h.depth + h.eaveOver)) {
                val xs = of(ShellPart.SOFFIT).flatMap { q -> corners(q).filter { abs(it[2] - e) < 1e-3f }.map { it[0] } }
                assertEquals(0f, xs.min()); assertEquals(pw, xs.max())
            }
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

    @Test fun aWallSnappedWithItsPlaceAlongTheStreetLandsOnTheScreensPixels() {
        // the building's middle need not be on a whole pixel: the wall's offset takes that into account
        val h = houses["b9"]!!
        val unit = 2.4347825f
        for (at in floatArrayOf(0f, 103.37f, 2210.9f)) {
            val v = HouseView(h, 0f).set(h, 7.7f, unit, at)
            val wall = (v.x(0f, 0f) + at) * unit
            assertEquals(kotlin.math.round(wall), wall, 2e-2f)
        }
    }

    @Test fun aThinSteepBandIsFilledOnePixelSpanPerRowWithNoGaps() {
        // a rake board seen almost edge on: 0.3 units wide, 20 tall, leaning 4 units over its height
        val unit = 2.4347825f; val origin = 51.3f
        val rows = ArrayList<FloatArray>()
        PixelRows.quad(0f, -20f, .3f, -20f, 4.3f, 0f, 4f, 0f, unit, origin) { l, t, r, b -> rows += floatArrayOf(l, t, r, b) }
        assertTrue(rows.size >= 48)
        for (i in rows.indices) {
            val (l, t, r, b) = rows[i].toList()
            // whole screen pixels, at least one wide, one row tall
            assertEquals(kotlin.math.round((l + origin) * unit), (l + origin) * unit, 2e-3f)
            assertEquals(kotlin.math.round((r + origin) * unit), (r + origin) * unit, 2e-3f)
            assertTrue((r - l) * unit > .99f)
            assertEquals(1f, (b - t) * unit, 1e-3f)
            if (i > 0) {
                // each row the next one down, and touching the row above it (a stepped line, not dots)
                assertEquals(rows[i - 1][3], t, 1e-4f)
                assertTrue(l <= rows[i - 1][2] + 1e-4f && r >= rows[i - 1][0] - 1e-4f)
            }
        }
        // nothing outside the band's own rows
        PixelRows.quad(0f, 0f, 1f, 0f, 1f, .1f, 0f, .1f, unit, 0f) { _, _, _, _ -> throw AssertionError("a sliver between two rows' middles fills none") }
    }

    @Test fun aTapFindsAHouseWhereItStandsBackInItsYard() {
        // a house's parts as the layout gives them: the yard (and its porch or steps), the wall, the roof up to its ridge
        val back = 64f / HouseView.DEPTH; val deep = 80f / HouseView.DEPTH
        val l = BuildingLayout("h", listOf(
            Piece(PieceKind.LOBBY, "h", 0f, 5f, 130f, lean = back / 2f, deep = 0f, roofFace = false),
            Piece(PieceKind.LOBBY, "h", 5f, 70f, 130f, lean = back, deep = deep, roofFace = false),
            Piece(PieceKind.LOBBY, "h", 75f, 20f, 130f, lean = back + deep / 4f, deep = deep / 2f, roofFace = false),
        ), emptyList(), 95f, 75f, BuildingSpec.W + BuildingSpec.DX)
        val shift = 14f
        val ox = shift * back
        // the wall's right edge leans with the view: just inside it hits, and just outside the leaned wall it misses
        assertEquals(Hit(HitKind.LOBBY, "h"), l.hit(65f + ox - 1f, 40f, shift))
        assertEquals(Hit(HitKind.LOBBY, "h"), l.hit(-65f + ox + 1f, 40f, shift))
        assertNull(l.hit(-65f + 2f, 40f, shift))      // (the lot's own front would have taken it)
        // the yard in front of it (its porch, its steps)
        assertEquals(Hit(HitKind.LOBBY, "h"), l.hit(0f, 2f, shift))
        // no roof face over the ridge: the sky above it is not the house
        assertNull(l.hit(ox, 95f + 3f, shift))
        // the gable beside the wall, as deep as the house, not a shop's whole side
        assertEquals(Hit(HitKind.LOBBY, "h"), l.hit(65f + ox + 2f, 45f, shift))
        assertNull(l.hit(65f + ox + shift * deep + 1f, 60f, shift))
    }
}
