import com.lifemax.rpg.feature.habits.domain.ModelPart
import com.lifemax.rpg.feature.habits.domain.ModelShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rounded walls and the turned walls of a model (models.json's "cyl" and "wall" parts, the police station's). */
class ModelStripTest {
    private val json = """
        {"b1": {"kind": "police", "w": 672, "frontX": -224, "setback": 48, "lawn": "paving", "style": "natural", "roofTint": 5,
          "lamp": null, "lights": "lamp", "smoke": [], "fpx": 0,
          "parts": [
            {"t": "box", "x": [307, 604], "y": [0, 137], "d": [30, 272], "c": [1, 2, 3]},
            {"t": "cyl", "c": [604, 115], "r": [52, 85], "a": [0, 90], "y": [0, 137], "col": [7, 8], "ft": [0, 0, 108, 137], "sg": true},
            {"t": "cyl", "c": [604, 115], "r": [52, 85], "a": [0, 90], "y": [137, 152], "col": [7, 9], "ri": [26, 55]},
            {"t": "cyl", "c": [604, 115], "r": [39, 72], "a": [0, 90], "y": [152, 173], "col": [-1, 9], "card": true},
            {"t": "wall", "x": [247, 286], "d": [0, 18], "y": [0, 261], "c": [4], "ft": [0, 140, 39, 261]}]}}
    """.trimIndent()

    @Test fun parsesRoundedAndTurnedWalls() {
        val m = ModelShape.parseAll(json)["b1"]
        assertNotNull(m)
        val ps = m!!.parts
        assertEquals(5, ps.size)
        val c = ps[1]
        assertEquals(ModelPart.CYL, c.type)
        // its extent is its whole ellipse's
        assertEquals(552f, c.x0); assertEquals(656f, c.x1); assertEquals(30f, c.d0); assertEquals(200f, c.d1)
        val a = c.arc!!
        assertEquals(0f, a[4]); assertEquals(90f, a[5]); assertTrue(a[6].isNaN())
        assertTrue(c.sideGlow); assertEquals(7, c.top); assertEquals(108, c.ft!![2])
        assertEquals(26f, ps[2].arc!![6]); assertEquals(55f, ps[2].arc!![7])
        assertTrue(ps[3].card); assertEquals(-1, ps[3].top)
        val w = ps[4]
        assertEquals(ModelPart.WALL, w.type)
        assertEquals(247f, w.x0); assertEquals(18f, w.d1); assertEquals(4, w.front)
        // the walls are the boxes (the depth reaches the box's back)
        assertEquals(272f, m.depth)
    }
}
