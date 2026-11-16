package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.Ambient
import com.lifemax.rpg.feature.habits.domain.BuildingSpec as B
import com.lifemax.rpg.feature.habits.presentation.scene.ForegroundKind
import com.lifemax.rpg.feature.habits.presentation.scene.ForegroundLayout
import com.lifemax.rpg.feature.habits.presentation.scene.ForegroundSprites
import com.lifemax.rpg.feature.habits.presentation.scene.SkylineSprites
import com.lifemax.rpg.feature.habits.presentation.scene.SkylineStrip
import kotlin.math.abs
import kotlin.math.max
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scene's depth: the far city's rows baked once as periodic strips (the same every time,
 * seamless where they wrap, softer the further the row, clear along their top, their feet solid,
 * their windows in one colour channel each, the whole inside its budget) and the near layer's
 * things (baked soft and clear at their borders) placed by pure arithmetic: deterministic, spaced
 * out, sliding faster than the street, never over the building in front.
 */
class DepthSpritesTest {
    private val rows = Array(SkylineSprites.ROWS.size) { SkylineSprites.generate(it) }

    private fun alpha(c: Int) = (c ushr 24) / 255f

    /** Channel [c] of a pixel, 0 to 1: 0 alpha, 1 red, 2 green, 3 blue. */
    private fun channel(p: Int, c: Int) = ((p ushr (24 - 8 * c)) and 0xFF) / 255f

    /** The steepest step in channel [c] between neighbouring pixels along a row, per dp. */
    private fun steepest(s: SkylineStrip, c: Int = 0): Float {
        var step = 0f
        for (y in 0 until s.h) for (x in 0 until s.w - 1) {
            val i = y * s.w + x
            step = max(step, abs(channel(s.pixels[i + 1], c) - channel(s.pixels[i], c)))
        }
        return step * s.ppd
    }

    @Test
    fun theRowsAreTheSameEveryTimeAndInsideTheirBudget() {
        for (r in rows.indices) {
            val again = SkylineSprites.generate(r)
            assertArrayEquals(rows[r].body.pixels, again.body.pixels)
            assertEquals(rows[r].windows == null, again.windows == null)
            rows[r].windows?.let { assertArrayEquals(it.pixels, again.windows!!.pixels) }
            assertEquals(rows[r].body.w * rows[r].body.h, rows[r].body.pixels.size)
        }
        // the furthest row is never lit, the nearer two are
        assertNull(rows[0].windows)
        assertNotNull(rows[1].windows)
        assertNotNull(rows[2].windows)
        val bytes = rows.sumOf { it.body.bytes + (it.windows?.bytes ?: 0) }
        assertEquals(bytes, SkylineSprites.bytes())
        assertTrue("far city is $bytes bytes", bytes < 620 * 1024)
        assertTrue(
            "near layer is ${ForegroundSprites.bytes()} bytes",
            ForegroundSprites.bytes() < 260 * 1024,
        )
    }

    @Test
    fun eachStripIsOneSpanWideAndSeamlessWhereItWraps() {
        for (row in rows) {
            for (s in listOfNotNull(row.body, row.windows)) {
                assertEquals((Ambient.SKYLINE_SPAN * s.ppd).toInt(), s.w)
                // the step across the seam is no steeper than the steepest step inside the strip
                for (c in 0 until 4) {
                    val inside = steepest(s, c) / s.ppd + .02f
                    for (y in 0 until s.h) {
                        val a = channel(s.pixels[y * s.w], c)
                        val b = channel(s.pixels[y * s.w + s.w - 1], c)
                        assertTrue("seam at row $y channel $c", abs(a - b) <= inside)
                    }
                }
            }
        }
    }

    @Test
    fun theFurtherTheRowTheSofterItsEdges() {
        val steps = rows.map { steepest(it.body) }
        // steps are per dp: the nearest row's edges are the sharpest, the furthest row's softest
        assertTrue("$steps", steps[0] < steps[1] && steps[1] < steps[2])
        assertTrue("$steps", steps[0] < .5f)
    }

    @Test
    fun theBodyIsClearAlongItsTopAndSolidAlongItsFoot() {
        for (r in rows.indices) {
            val s = rows[r].body
            for (x in 0 until s.w) assertEquals(0f, alpha(s.pixels[x]), 1e-3f)
            // the foot: every column covered, thinned only by the horizon's air at that height
            val air = 1f - SkylineSprites.haze(r, s.footDp + .5f / s.ppd)
            for (x in 0 until s.w) assertEquals(air, alpha(s.pixels[(s.h - 1) * s.w + x]), .03f)
            // something rises above the plain roofs: the strip is not empty under its margin
            assertTrue(s.pixels.count { alpha(it) > .3f } > s.w)
            // the roofline is broken: the top halves of the columns are not all the same
            val tops =
                (0 until s.w).map { x ->
                    (0 until s.h).firstOrNull { alpha(s.pixels[it * s.w + x]) > .3f } ?: s.h
                }
            assertTrue(tops.toSet().size > 12)
        }
    }

    @Test
    fun theWindowsAreOpaqueSpotsInOneChannelEach() {
        for (r in 1 until rows.size) {
            val s = rows[r].windows!!
            assertEquals(0f, s.footDp, 0f)
            assertTrue(s.topDp > SkylineSprites.ROWS[r].hi)
            var lit = 0
            for (p in s.pixels) {
                assertEquals(0xFF, p ushr 24)
                if (p and 0xFFFFFF != 0) lit++
            }
            // a modest share of the strip is window
            assertTrue(lit > s.w / 2)
            assertTrue(lit < s.w * s.h / 4)
            // the top row is clear (the highest window is below the strip's top)
            for (x in 0 until s.w) assertEquals(0, s.pixels[x] and 0xFFFFFF)
            // every building's windows are the same for the same row and building
            for (i in 0 until 5) assertArrayEquals(
                SkylineSprites.windows(r, i),
                SkylineSprites.windows(r, i),
                0f,
            )
        }
        assertEquals(0, SkylineSprites.channel(.2f))
        assertEquals(1, SkylineSprites.channel(.7f))
        assertEquals(2, SkylineSprites.channel(.9f))
    }

    @Test
    fun theHazeThickensTowardsTheHorizonAndWithDistance() {
        for (r in 0 until 3) {
            assertEquals(SkylineSprites.ROWS[r].hazeTop, SkylineSprites.haze(r, 200f), 1e-5f)
            assertEquals(SkylineSprites.ROWS[r].hazeFoot, SkylineSprites.haze(r, 0f), 1e-5f)
            var last = SkylineSprites.haze(r, 100f)
            for (dp in 95 downTo 0 step 5) {
                val h = SkylineSprites.haze(r, dp.toFloat())
                assertTrue(h >= last - 1e-5f)
                last = h
            }
        }
        for (dp in listOf(0f, 20f, 60f, 100f)) assertTrue(
            SkylineSprites.haze(0, dp) > SkylineSprites.haze(1, dp) &&
                SkylineSprites.haze(1, dp) > SkylineSprites.haze(2, dp)
        )
    }

    @Test
    fun theRowsStillCarryTheirLightsAndAntennas() {
        for (r in 0 until 3) {
            val warned = SkylineSprites.warned(r)
            assertEquals(if (r < 2) 3 else 0, warned.count { it })
            val row = SkylineSprites.buildings(r)
            for (i in row.indices) {
                if (warned[r.coerceAtMost(i)] && r < 2)
                    assertTrue(SkylineSprites.antenna(r, i, true) >= 6f)
                if (r == 2) assertEquals(0f, SkylineSprites.antenna(r, i, warned[i]), 0f)
            }
        }
    }

    // ---- the near layer

    @Test
    fun theNearThingsAreSoftWhiteAndClearAtTheirTop() {
        val sprites =
            listOf(ForegroundSprites.rail(), ForegroundSprites.bollards(), ForegroundSprites.post())
        for (s in sprites) {
            assertEquals(s.w * s.h, s.pixels.size)
            assertTrue(s.pixels.all { (it ushr 24) == 0 || (it and 0xFFFFFF) == 0xFFFFFF })
            for (x in 0 until s.w) assertEquals(0, s.pixels[x] ushr 24)
            // soft: no step between neighbours is a hard edge
            var step = 0
            for (i in 1 until s.pixels.size) if (i % s.w != 0)
                step = max(step, abs((s.pixels[i] ushr 24) - (s.pixels[i - 1] ushr 24)))
            assertTrue("step $step", step < 150)
            assertTrue(s.pixels.count { (it ushr 24) > 128 } > 20)
        }
        // the rail ends softly at both sides, the branch is cut by the top edge on purpose
        val rail = ForegroundSprites.rail()
        for (y in 0 until rail.h) {
            assertEquals(0, rail.pixels[y * rail.w] ushr 24)
            assertEquals(0, rail.pixels[y * rail.w + rail.w - 1] ushr 24)
        }
        val branch = ForegroundSprites.branch()
        assertArrayEquals(branch.pixels, ForegroundSprites.branch().pixels)
        assertTrue(branch.pixels.count { (it ushr 24) > 128 } > 200)
        assertEquals(
            ForegroundSprites.rail().bytes +
                ForegroundSprites.bollards().bytes +
                ForegroundSprites.post().bytes +
                branch.bytes,
            ForegroundSprites.bytes(),
        )
    }

    @Test
    fun theNearThingsArePlacedTheSameEveryTimeAndSpacedOut() {
        val seed = HabitsScenarios.TODAY.hashCode()
        var some = 0
        val kinds = HashSet<ForegroundKind>()
        for (k in -20..40) {
            assertEquals(ForegroundLayout.kind(seed, k), ForegroundLayout.kind(seed, k))
            assertEquals(ForegroundLayout.at(seed, k), ForegroundLayout.at(seed, k), 0f)
            // each stands in the middle of its own slot, so neighbours are at least two fifths of a
            // slot apart and the view is never cluttered
            val x = ForegroundLayout.at(seed, k)
            assertEquals(k, ForegroundLayout.slot(x))
            assertTrue(x >= k * ForegroundLayout.SLOT + ForegroundLayout.SLOT * .2f - 1e-3f)
            assertTrue(x <= (k + 1) * ForegroundLayout.SLOT - ForegroundLayout.SLOT * .2f + 1e-3f)
            if (k > -20)
                assertTrue(
                    x - ForegroundLayout.at(seed, k - 1) >= ForegroundLayout.SLOT * .4f - 1e-3f
                )
            ForegroundLayout.kind(seed, k)?.let {
                some++
                kinds += it
            }
        }
        assertTrue(some in 20..55)
        assertEquals(ForegroundKind.entries.toSet(), kinds)
        // another day, another street
        assertFalse(
            (0..40).all { ForegroundLayout.kind(seed, it) == ForegroundLayout.kind(seed + 1, it) }
        )
        // some trees have a branch hanging in, not all
        val branches = (-4 until 12).count { ForegroundLayout.hasBranch(seed, it) }
        assertTrue(branches in 3..13)
    }

    @Test
    fun theNearLayerSlidesFasterThanTheStreet() {
        val width = 1080f
        val unit = 3f
        val p = ForegroundLayout.PARALLAX
        assertTrue(p in 1.25f..1.4f)
        // a thing at the view's middle stays there; one off it moves PARALLAX times as far as the
        // street for a unit scrolled
        assertEquals(width / 2f, ForegroundLayout.screenX(100f, 100f, unit, width), 1e-3f)
        val a = ForegroundLayout.screenX(300f, 100f, unit, width)
        val b = ForegroundLayout.screenX(300f, 110f, unit, width)
        assertEquals(-10f * unit * p, b - a, 1e-3f)
        val streetMove = -10f * unit
        assertTrue(abs(b - a) > abs(streetMove))
        // the slots asked for cover what can show and a little more
        val slots = ForegroundLayout.slots(100f, unit, width, 400f)
        assertTrue(ForegroundLayout.slot(100f) in slots)
        assertTrue(slots.first <= ForegroundLayout.slot(100f - width / 2f / (unit * p)))
        assertTrue(slots.last >= ForegroundLayout.slot(100f + width / 2f / (unit * p)))
    }

    @Test
    fun nothingNearStandsOverTheFrontBuildingOrItsLobby() {
        val d = 2.625f
        val unit = 1.15f * d
        val width = 412f * d
        val soft = ForegroundLayout.SOFT * d
        val seed = 11
        // the front building in the middle of the view, its plaques and lobby in its face
        val center = width / 2f
        val ex0 = ForegroundLayout.frontLeft(center, unit)
        val ex1 = ForegroundLayout.frontRight(center, unit)
        assertTrue(ex0 < center - B.W / 2f * unit && ex1 > center + (B.W / 2f + B.DX) * unit)
        // wherever the street has scrolled, a post that reaches the face is faded out
        for (step in 0..400) {
            val middle = step * 7f - 1000f
            for (k in ForegroundLayout.slots(middle, unit, width, 100f)) {
                if (ForegroundLayout.kind(seed, k) != ForegroundKind.POST) continue
                val xs = ForegroundLayout.screenX(ForegroundLayout.at(seed, k), middle, unit, width)
                val pw = ForegroundSprites.POST_W * d
                val a =
                    ForegroundLayout.edge(xs, width) *
                        ForegroundLayout.clearance(xs - pw / 2f, xs + pw / 2f, ex0, ex1, soft)
                if (xs + pw / 2f > ex0 && xs - pw / 2f < ex1) assertEquals(0f, a, 0f)
                // and only ever shows at the sides
                if (a > 0f)
                    assertTrue(
                        xs < width * ForegroundLayout.EDGE_OUT ||
                            xs > width * (1f - ForegroundLayout.EDGE_OUT)
                    )
            }
            // a branch the same
            val bw = ForegroundSprites.BRANCH_W * d
            for (treeX in listOf(-150f, 0f, 125f, 400f)) {
                val xs = ForegroundLayout.screenX(treeX, middle, unit, width)
                val a =
                    ForegroundLayout.edge(xs, width) *
                        ForegroundLayout.clearance(xs - bw / 2f, xs + bw / 2f, ex0, ex1, soft)
                if (xs + bw / 2f > ex0 && xs - bw / 2f < ex1) assertEquals(0f, a, 0f)
            }
        }
        // the clearance itself: whole well clear, nothing touching, between over the margin
        assertEquals(1f, ForegroundLayout.clearance(0f, 10f, 100f, 200f, 12f), 0f)
        assertEquals(0f, ForegroundLayout.clearance(90f, 110f, 100f, 200f, 12f), 0f)
        assertEquals(.5f, ForegroundLayout.clearance(0f, 94f, 100f, 200f, 12f), 1e-5f)
        assertEquals(1f, ForegroundLayout.clearance(0f, 10f, 1f, 0f, 12f), 0f)
        // the railing and the bollards stand on the band's edge and never reach the pavement line
        val band = 1000f
        assertEquals(band - 40f, ForegroundLayout.railTop(band, 900f, 40f, d), 0f)
        val lowGround = band - 10f * d
        assertEquals(
            lowGround + ForegroundLayout.OVER_GROUND * d,
            ForegroundLayout.railTop(band, lowGround, 40f, d),
            0f,
        )
        // side things are whole at the edges and gone by the middle
        assertEquals(1f, ForegroundLayout.edge(10f, width), 0f)
        assertEquals(1f, ForegroundLayout.edge(width - 10f, width), 0f)
        assertEquals(0f, ForegroundLayout.edge(width / 2f, width), 0f)
    }
}
