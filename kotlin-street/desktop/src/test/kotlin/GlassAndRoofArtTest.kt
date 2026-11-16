package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.presentation.scene.AwningArt
import com.lifemax.rpg.feature.habits.presentation.scene.GlassMap
import com.lifemax.rpg.feature.habits.presentation.scene.GlassSprites
import com.lifemax.rpg.feature.habits.presentation.scene.RoofArt
import com.lifemax.rpg.feature.habits.presentation.scene.skyColors
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The glass's reflections, the awning's canvas and the roof's furniture: baked the same every time,
 * inside their budget, and each doing what it is for.
 */
class GlassAndRoofArtTest {
    private fun ch(c: Int, shift: Int) = (c shr shift) and 255

    @Test
    fun theReflectionsAreBakedOnceTheSameSeamlessAndAsWeights() {
        val sky = GlassSprites.sky()
        assertArrayEquals(sky.pixels, GlassSprites.sky().pixels)
        assertEquals(GlassSprites.W * GlassSprites.SKY_H, sky.pixels.size)
        // opaque, and its weights never add up to more than the whole pixel
        assertTrue(sky.pixels.all { (it ushr 24) == 255 })
        assertTrue(sky.pixels.all { ch(it, 16) + ch(it, 8) + ch(it, 0) <= 257 })
        // clouds in it, the horizon's band low in it, clear sky at its top
        assertTrue(sky.pixels.count { ch(it, 16) > 80 } > sky.pixels.size / 50)
        val w = sky.w
        assertTrue(
            (0 until w).all {
                ch(sky.pixels[(sky.h - 1) * w + it], 0) > 120 ||
                    ch(sky.pixels[(sky.h - 1) * w + it], 16) > 40
            }
        )
        assertTrue(
            (0 until w).all {
                ch(sky.pixels[it], 16) + ch(sky.pixels[it], 8) + ch(sky.pixels[it], 0) < 30
            }
        )
        // round: the last column runs into the first
        val seam =
            (0 until sky.h).maxOf { y ->
                abs(ch(sky.pixels[y * w + w - 1], 16) - ch(sky.pixels[y * w], 16))
            }
        assertTrue("seam $seam", seam < 24)

        val st = GlassSprites.street()
        assertArrayEquals(st.pixels, GlassSprites.street().pixels)
        // clear at its top (it is clamped above), the street solid at its foot
        assertTrue((0 until st.w).all { st.pixels[it] == 0 })
        assertTrue((0 until st.w).all { (st.pixels[(st.h - 1) * st.w + it] ushr 24) > 240 })
        // windows lit at night, the lamps' lenses, the windows' glass
        assertTrue(st.pixels.count { ch(it, 8) > 150 } > 100)
        assertTrue(st.pixels.any { ch(it, 0) > 150 })
        assertTrue(st.pixels.count { ch(it, 16) > 90 } > 500)

        for (k in 0 until GlassSprites.INTERIORS) {
            val r = GlassSprites.interior(k)
            assertTrue(r.pixels.all { (it ushr 24) == 255 })
        }
        val g = GlassSprites.glint()
        // the glint's rim is clear, so a clamped brush adds nothing past it
        for (i in 0 until g.w) {
            assertEquals(0, g.pixels[i])
            assertEquals(0, g.pixels[(g.h - 1) * g.w + i])
        }
        assertTrue((g.pixels[(g.h / 2) * g.w + g.w / 2] ushr 24) > 240)
    }

    @Test
    fun eachPaneMirrorsItsOwnPartOfTheStreetAndItSlidesAsTheViewPans() {
        val a = FloatArray(5)
        val b = FloatArray(5)
        // the view pans: the reflection slides against the facade
        GlassMap.place(100f, 80f, 17f, 37f, 7, 0f, 0f, 0f, a)
        GlassMap.place(100f, 80f, 17f, 37f, 7, 60f, 0f, 0f, b)
        assertNotEquals(a[1], b[1], .5f)
        // a pane higher up sees higher: the strips' row at its top is further up
        GlassMap.place(100f, 260f, 17f, 37f, 7, 0f, 0f, 0f, b)
        assertTrue(b[3] < a[3] - 30f)
        // neighbours are turned a hair differently
        val tilts = (0 until 12).map { GlassMap.tilt(it * 7 + 3) }
        assertTrue(tilts.maxOrNull()!! - tilts.minOrNull()!! > .5f)
        // only some panes show a room behind them
        val rooms = (0 until 200).count { GlassMap.interior(it) >= 0 }
        assertTrue("$rooms", rooms in 60..140)
        // the clouds drift with the clock
        GlassMap.place(100f, 80f, 17f, 37f, 7, 0f, 0f, 10f, b)
        assertNotEquals(a[2], b[2], .5f)
        assertEquals(a[1], b[1], 1e-3f)
    }

    @Test
    fun theGlassTakesTheHoursColoursAndTheNightsLights() {
        val day = FloatArray(24)
        val night = FloatArray(24)
        GlassMap.colours(skyColors(12f), day)
        GlassMap.colours(skyColors(23f), night)
        // the sky overhead is blue by day and far darker at night
        assertTrue(day[11] > day[9] + .1f)
        assertTrue(night[9] + night[10] + night[11] < (day[9] + day[10] + day[11]) / 2f)
        // the windows lit across the street are warm at night and stand out of the dark
        assertTrue(night[15] > night[17] + .2f)
        assertTrue(night[15] > night[21] + .3f)
        // the lamps' lenses too
        assertTrue(night[18] > .7f)
    }

    @Test
    fun theAwningIsCanvasOnAFrameColouredPerShop() {
        val c = AwningArt.canopy()
        val v = AwningArt.valance()
        assertArrayEquals(c.pixels, AwningArt.canopy().pixels)
        assertEquals(AwningArt.W * AwningArt.CANOPY_PX, c.pixels.size)
        // both stripes, cream and colour, in the cloth
        assertTrue(c.pixels.count { (it ushr 24) > 200 && ch(it, 0) > 100 } > c.pixels.size / 6)
        assertTrue(c.pixels.count { (it ushr 24) > 200 && ch(it, 16) > 100 } > c.pixels.size / 6)
        // the cloth sags between its arms and the hem is scalloped below it
        assertTrue(AwningArt.front(0f + 11.375f - 92f + 1f) > AwningArt.front(-92f + 1f) + .5f)
        val hems = (0 until 180).map { AwningArt.hem(-90f + it) }
        assertTrue(hems.maxOrNull()!! - hems.minOrNull()!! > 3f)
        assertTrue(v.pixels.count { (it ushr 24) > 200 } > v.pixels.size / 3)
        // the colour matrix gives the shop's colour for a fully coloured, unlit pixel
        val m = FloatArray(20)
        AwningArt.matrix(.2f, .5f, .6f, 1f, 1f, 1f, m)
        assertEquals(.2f * 255f, m[0] * 255f + m[4], .01f)
        assertEquals(.6f * 255f, m[10] * 255f + m[14], .01f)
        assertEquals(1f, m[18], 0f)
    }

    @Test
    fun theWaterTowerIsLitFromTheSunsSideAndTheWholePoolIsInsideItsBudget() {
        val sky = RoofArt.tank(0)
        val left = RoofArt.tank(-1)
        val right = RoofArt.tank(1)
        assertArrayEquals(sky.pixels, RoofArt.tank(0).pixels)
        fun bright(p: IntArray, w: Int, from: Int, to: Int): Long {
            var s = 0L
            for (k in p.indices) {
                val x = k % w
                if (x in from until to && (p[k] ushr 24) > 200) s += ch(p[k], 16) + ch(p[k], 8)
            }
            return s
        }
        val w = left.w
        assertTrue(bright(left.pixels, w, 0, w / 2) > bright(right.pixels, w, 0, w / 2))
        assertTrue(bright(right.pixels, w, w / 2, w) > bright(left.pixels, w, w / 2, w))
        // the same silhouette in all three (the painter mixes them)
        for (k in sky.pixels.indices) assertEquals(sky.pixels[k] ushr 30, left.pixels[k] ushr 30)
        for (p in
            listOf(RoofArt.hvac(), RoofArt.dish(), RoofArt.stack(), RoofArt.planter())) assertTrue(
            p.pixels.count { (it ushr 24) > 200 } > p.pixels.size / 8
        )
        val bytes = GlassSprites.bytes() + RoofArt.bytes() + AwningArt.bytes()
        assertTrue("$bytes", bytes < 900_000)
        assertEquals(
            3 * sky.w * sky.h * 4 +
                listOf(RoofArt.hvac(), RoofArt.dish(), RoofArt.stack(), RoofArt.planter()).sumOf {
                    it.w * it.h * 4
                },
            RoofArt.bytes(),
        )
    }
}
