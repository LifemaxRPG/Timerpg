package com.timerpg.street.shot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShotMarksTest {
    @Test
    fun arrowHeadIsAtTheEnd() {
        val h = ShotMarks.head(0f, 0f, 100f, 0f, 10f)!!
        assertEquals(100f, h.tipX, 1e-3f); assertEquals(0f, h.tipY, 1e-3f)
        assertEquals(50f, h.baseX, 1e-3f)                       // head length 5 x stroke
        assertEquals(h.leftY, -h.rightY, 1e-3f); assertEquals(25f, Math.abs(h.leftY), 1e-3f)
        assertNull(ShotMarks.head(5f, 5f, 5f, 5f, 10f))
        // a short arrow: the head is at most 60% of it
        assertEquals(6f, ShotMarks.head(0f, 0f, 0f, 10f, 10f)!!.let { 10f - it.baseY }, 1e-3f)
    }

    @Test
    fun strokeScalesWithTheImage() {
        assertEquals(9f, ShotMarks.stroke(2340), 0.01f)
        assertEquals(5f, ShotMarks.stroke(400), 0.01f)
    }

    @Test
    fun jsonHasImagePixels() {
        val marks = listOf(ShotMark.Arrow(ShotMarks.RED, 10.4f, 20.6f, 300f, 400f), ShotMark.Circle(ShotMarks.YELLOW, 100f, 100f, 200f, 160f),
            ShotMark.Pen(ShotMarks.WHITE, listOf(1f to 2f, 3f to 4f)))
        val s = ShotMarks.section(marks, 2340, 1080, "a.png", "a-raw.png")!!
        val o = org.json.JSONObject(Json.write(s))
        assertEquals(2340, o.getInt("imageWidth"))
        val arr = o.getJSONArray("marks")
        assertEquals(3, arr.length())
        val a = arr.getJSONObject(0)
        assertEquals("arrow", a.getString("type")); assertEquals("red", a.getString("color"))
        assertEquals(10, a.getJSONObject("from").getInt("x")); assertEquals(21, a.getJSONObject("from").getInt("y"))
        assertEquals(300, a.getJSONObject("to").getInt("x"))
        val c = arr.getJSONObject(1)
        assertEquals(150, c.getJSONObject("centre").getInt("x")); assertEquals(50, c.getInt("radiusX")); assertEquals(30, c.getInt("radiusY"))
        assertEquals(2, arr.getJSONObject(2).getJSONArray("points").length())
        assertNull(ShotMarks.section(emptyList(), 1, 1, "a", "b"))
    }

    @Test
    fun rawPathAndThinning() {
        assertEquals("screenshots/2026-11-18/140509-x-raw.png", ShotMarks.rawPath("screenshots/2026-11-18/140509-x.png"))
        val pts = (0..100).map { it.toFloat() to 0f }
        val t = ShotMarks.thin(pts, 10f)
        assertTrue(t.size in 8..12)
        assertEquals(pts.last(), t.last()); assertNotNull(t.first())
    }
}
