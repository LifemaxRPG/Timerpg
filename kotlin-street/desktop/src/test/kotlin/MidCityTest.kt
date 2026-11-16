package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.Season
import com.lifemax.rpg.feature.habits.domain.SeasonState
import com.lifemax.rpg.feature.habits.presentation.scene.MidCitySprites as M
import com.lifemax.rpg.feature.habits.presentation.scene.MidLook
import com.lifemax.rpg.feature.habits.presentation.scene.MidStrip
import com.lifemax.rpg.feature.habits.presentation.scene.skyColors
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The middle distance: three periodic strips between the horizon and the street, baked the same
 * every time, seamless where they wrap, solid where they must hide the bare ground, inside their
 * budget, with their windows, lights and masks in the channels the drawing reads; and the look of
 * the hour put on by plain arithmetic (the city lit by day, its windows coming on through the dusk,
 * thinning after midnight, the furthest layer the haziest).
 */
class MidCityTest {
    private val layers = Array(M.LAYERS.size) { M.generate(it) }

    private fun alpha(p: Int) = (p ushr 24) / 255f

    private fun strips() = layers.flatMap { listOfNotNull(it.body, it.windows, it.glow, it.masks) }

    @Test
    fun theLayersAreTheSameEveryTimeAndInsideTheirBudget() {
        for (l in layers.indices) {
            val again = M.generate(l)
            assertArrayEquals(layers[l].body.pixels, again.body.pixels)
            assertArrayEquals(layers[l].masks.pixels, again.masks.pixels)
            layers[l].windows?.let { assertArrayEquals(it.pixels, again.windows!!.pixels) }
            layers[l].glow?.let { assertArrayEquals(it.pixels, again.glow!!.pixels) }
        }
        // the far layer has windows but no lamps; the near kerb lamps but no windows
        assertNotNull(layers[M.FAR].windows)
        assertNull(layers[M.FAR].glow)
        assertNotNull(layers[M.BLOCKS].windows)
        assertNotNull(layers[M.BLOCKS].glow)
        assertNull(layers[M.KERB].windows)
        val bytes = strips().sumOf { it.bytes }
        assertEquals(bytes, M.bytes())
        assertTrue("middle distance is $bytes bytes", bytes < 2_000_000)
        // (under the sky's own far city's three times; see the brief's middle-distance section)
    }

    @Test
    fun eachStripIsOneSpanWideAndSeamlessWhereItWraps() {
        for (s in strips()) {
            assertEquals(Math.round(M.SPAN * s.ppd), s.w)
            assertEquals(s.w * s.h, s.pixels.size)
            // nothing is cut at the seam: the columns either side of it differ no more than the
            // two sides of the strip's sharpest edge between neighbouring columns
            fun steps(a: Int, b: Int) =
                (0 until s.h).count { y ->
                    diff(s.pixels[y * s.w + a], s.pixels[y * s.w + b]) > 140
                }
            val across = steps(s.w - 1, 0)
            val worst = (1 until s.w).maxOf { steps(it - 1, it) }
            assertTrue("seam of ${s.w}x${s.h}: $across over $worst", across <= worst)
        }
    }

    private fun diff(a: Int, b: Int): Int {
        var d = 0
        for (sh in intArrayOf(24, 16, 8, 0)) d += abs(((a ushr sh) and 255) - ((b ushr sh) and 255))
        return d
    }

    @Test
    fun theBandIsCoveredDownToTheStreetAndOpenAtTheTop() {
        // the near kerb is solid from its pavement down past the street's line: no bare ground
        val kerb = layers[M.KERB].body
        val from = ((24f - kerb.topDp) * kerb.ppd).toInt()
        for (y in from until kerb.h) for (x in 0 until kerb.w) assertTrue(
            "kerb $x,$y",
            alpha(kerb.pixels[y * kerb.w + x]) > .98f,
        )
        assertTrue(kerb.topDp + kerb.h / kerb.ppd >= M.BAND)
        // the blocks are solid from the district's feet down to the road's near edge
        val blocks = layers[M.BLOCKS].body
        val y0 = ((M.FAR_FOOT + 1f - blocks.topDp) * blocks.ppd).toInt()
        val y1 = ((M.ROAD_BOTTOM - blocks.topDp) * blocks.ppd).toInt()
        for (y in y0 until y1) for (x in 0 until blocks.w) assertTrue(
            "blocks $x,$y",
            alpha(blocks.pixels[y * blocks.w + x]) > .98f,
        )
        // the district is solid at its foot, clear along its top row
        val far = layers[M.FAR].body
        for (x in 0 until far.w) {
            assertTrue(alpha(far.pixels[(far.h - 1) * far.w + x]) > .98f)
            assertEquals(0f, alpha(far.pixels[x]), .01f)
        }
        // each roofline is broken: the columns' tops are many
        for (s in listOf(far, blocks)) {
            val tops =
                (0 until s.w).map { x ->
                    (0 until s.h).first { alpha(s.pixels[it * s.w + x]) > .5f }
                }
            assertTrue(tops.toSet().size > 20)
        }
    }

    @Test
    fun theChannelStripsAreOpaqueAndHoldWhatTheDrawingReads() {
        for (l in layers) for (s in listOfNotNull(l.windows, l.glow, l.masks)) for (p in
            s.pixels) assertEquals(0xFF, p ushr 24)
        fun count(s: MidStrip, sh: Int, over: Int = 128) =
            s.pixels.count { ((it shr sh) and 255) > over }
        // plenty of windows, a modest share of each strip
        for (l in 0 until 2) {
            val w = layers[l].windows!!
            val lit = count(w, 16)
            assertTrue("$lit", lit > w.w)
            assertTrue("$lit", lit < w.w * w.h / 3)
            // a window's order never exceeds its coverage
            for (p in w.pixels) assertTrue(((p shr 8) and 255) <= ((p shr 16) and 255) + 2)
        }
        // lamps on both pavements, foliage and snow in the blocks and on the kerb
        assertTrue(count(layers[M.BLOCKS].glow!!, 16, 60) > 20)
        assertTrue(count(layers[M.KERB].glow!!, 16, 60) > 20)
        for (l in 1 until 3) {
            assertTrue(count(layers[l].masks, 16) > 100)
            assertTrue(count(layers[l].masks, 8) > 100)
        }
        // the signals and the vents are where the side streets and the roofs are
        assertTrue(M.signals.size >= 8 && M.signals.size % 2 == 0)
        for (i in 0 until M.signals.size / 2) assertTrue(M.signals[i * 2] in 0f..M.SPAN)
        assertTrue(M.vents.size >= 4 && M.vents.size % 2 == 0)
        for (i in 0 until M.vents.size / 2) assertTrue(M.vents[i * 2 + 1] < M.BLOCK_FOOT)
    }

    @Test
    fun theNearerTheLayerTheFasterItSlidesAndTheLessHazeItHas() {
        assertTrue(M.drift(0) < M.drift(1) && M.drift(1) < M.drift(2) && M.drift(2) < 1f)
        for (hour in floatArrayOf(7f, 14f, 19f, 23f)) {
            val sky = skyColors(hour)
            val h = FloatArray(3) { MidLook.haze(it, sky, 0f, 0f) }
            assertTrue("$hour ${h.toList()}", h[0] > h[1] && h[1] > h[2])
            // mist thickens it, most for the furthest
            val m = FloatArray(3) { MidLook.haze(it, sky, 0f, 1f) }
            for (l in 0 until 3) assertTrue(m[l] >= h[l])
            assertTrue(m[0] - h[0] > m[2] - h[2])
        }
        // the cars' lanes are on the road, the walkers on the pavements
        assertTrue(
            M.ROAD_TOP < M.LANE_LEFT && M.LANE_LEFT < M.LANE_RIGHT && M.LANE_RIGHT < M.ROAD_BOTTOM
        )
        assertTrue(M.FAR_WALK in M.BLOCK_FOOT..M.FAR_KERB)
        assertTrue(M.NEAR_WALK in M.ROAD_BOTTOM..M.BAND)
    }

    @Test
    fun theWindowsComeOnThroughTheDuskAndThinAfterMidnight() {
        assertEquals(0f, MidLook.share(14f), 0f)
        assertEquals(0f, MidLook.share(10f), 0f)
        assertTrue(MidLook.share(18f) > 0f)
        assertTrue(MidLook.share(18f) < MidLook.share(19.5f))
        assertTrue(MidLook.share(21f) > .55f)
        assertTrue(MidLook.share(3f) < MidLook.share(21f))
        assertTrue(MidLook.share(3f) > .15f)
        // nothing jumps from one minute to the next
        var h = 0f
        while (h < 24f) {
            assertTrue("$h", abs(MidLook.share(h + 1f / 60f) - MidLook.share(h)) < .02f)
            h += 1f / 60f
        }
        // the light by day is near white, at night a dim blue
        val day = MidLook.light(skyColors(14f), 0f, 0f)
        val night = MidLook.light(skyColors(23f), 0f, 0f)
        assertTrue(day.all { it > .9f })
        assertTrue(night.all { it < .3f } && night[2] > night[0])
    }

    @Test
    fun theLeavesFollowTheSeason() {
        val summer = MidLook.foliage(Season.SUMMER)
        val autumn = MidLook.foliage(Season.atDay(300f, 2026))
        // autumn is warmer: more red than green on the lit side
        assertTrue(autumn.second[0] > autumn.second[1] * .9f)
        assertTrue(summer.second[1] > summer.second[0])
        val winter = SeasonState(0f, 0f, 0f, 0f, 1f, 0f, 20)
        assertEquals(0f, MidLook.leafAlpha(winter), 0f)
        assertEquals(1f, MidLook.leafAlpha(Season.SUMMER), 0f)
    }
}
