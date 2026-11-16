package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.CycleRig
import com.lifemax.rpg.feature.habits.domain.DogRig
import com.lifemax.rpg.feature.habits.domain.FigBuilder
import com.lifemax.rpg.feature.habits.domain.Gait
import com.lifemax.rpg.feature.habits.domain.StreetGait
import com.lifemax.rpg.feature.habits.domain.StreetLife
import com.lifemax.rpg.feature.habits.domain.StreetLooks
import com.lifemax.rpg.feature.habits.domain.StreetRig
import com.lifemax.rpg.feature.habits.presentation.scene.CarArt
import com.lifemax.rpg.feature.habits.presentation.scene.StreetSprites
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreetSpritesTest {
    @Test
    fun theStreetWalkersStandAboutTwentySevenUnitsTallAndTheirFramesAreTheSameEveryTime() {
        for (look in 0 until StreetLooks.COUNT) {
            val h = StreetRig.height(look) * StreetLife.SIZE
            assertTrue("look $look is $h", h in 26f..30f)
            val a = StreetSprites.walker(look, 5).sprite
            val b = StreetSprites.walker(look, 5).sprite
            assertArrayEquals(a.pixels, b.pixels)
            assertEquals(a.ox, b.ox, 0f)
            // the picture stands on its feet: the origin is at its bottom, within its width
            assertTrue(a.h - a.oy < 4f && a.h - a.oy > -1f)
            assertTrue(a.ox > 0f && a.ox < a.w)
            // the body's frames stop at the neck: the head is a layer of its own
            assertTrue(
                "height ${a.h}",
                a.h in (20f * StreetSprites.PPU).toInt()..(28f * StreetSprites.PPU).toInt(),
            )
            assertTrue("width ${a.w}", a.w < 22f * StreetSprites.PPU)
        }
    }

    @Test
    fun thePlantedFootStaysWhereItLandsThroughTheStance() {
        // in the flat part of the near foot's stance the foot's point on the ground, with the
        // body's travel added, does not move
        for (look in 0 until StreetLooks.COUNT) {
            var ref = Float.NaN
            // phases 6/32 to 11/32 of the cycle, whatever the frame count
            val n = StreetGait.FRAMES
            for (f in 6 * n / 32..11 * n / 32) {
                val j = StreetRig.figure(look, f / StreetGait.FRAMES.toFloat()).joints
                val stride =
                    Gait.STRIDE * StreetLooks.ALL[look].height * StreetGait.style(look).stride
                val world = j.footNX + stride * f / StreetGait.FRAMES
                if (ref.isNaN()) ref = world
                assertEquals("look $look frame $f", ref, world, .12f)
                assertTrue(j.footNH < .3f)
            }
            // and the swinging foot clears the ground
            val swing = StreetRig.figure(look, .75f).joints
            assertTrue(swing.footNH > 1.2f)
        }
    }

    @Test
    fun theWalkIsAClosedLoopWithNoJumpBetweenFramesAndSettlesToStanding() {
        for (look in 0 until StreetLooks.COUNT) {
            var last = StreetRig.frame(look, StreetGait.FRAMES - 1).shapes
            for (f in 0 until StreetGait.FRAMES) {
                val cur = StreetRig.frame(look, f).shapes
                val a = FigBuilder.bounds(last)
                val b = FigBuilder.bounds(cur)
                for (i in 0 until 4) assertTrue(
                    "look $look frame $f edge $i",
                    abs(a[i] - b[i]) < 2.2f,
                )
                last = cur
            }
            val stand = StreetRig.frame(look, StreetRig.STAND_AT).joints
            assertTrue(abs(stand.footNX - stand.footFX) < 2.5f)
            assertTrue(stand.footNH < .3f && stand.footFH < .3f)
        }
        assertArrayEquals(StreetRig.STAND, StreetRig.SETTLE.last(), 1e-6f)
    }

    @Test
    fun theDogAndTheBicycleAreSmallAndDeterministicAndStandOnTheGround() {
        for (f in 0 until DogRig.TOTAL_FRAMES) {
            val b = FigBuilder.bounds(DogRig.frame(f))
            assertEquals(0f, b[3], 1e-3f)
            assertTrue("dog height ${-b[1]}", -b[1] in 5f..9f)
            assertTrue(b[2] - b[0] < 13f)
            assertArrayEquals(StreetSprites.dog(f).pixels, StreetSprites.dog(f).pixels)
        }
        for (f in 0 until StreetGait.CRANK_FRAMES) {
            val b = FigBuilder.bounds(CycleRig.frame(StreetSprites.RIDER, f))
            assertTrue("rider height ${-b[1]}", -b[1] in 20f..30f)
            assertTrue(b[2] - b[0] < 32f)
        }
        assertEquals(
            StreetGait.CRANK_TRAVEL,
            2f * Math.PI.toFloat() * StreetGait.WHEEL_R * 2.6f,
            1e-4f,
        )
    }

    @Test
    fun theNearCarsArePaintedWithLitLensesAtNightAndNothingOnTheBorder() {
        for (body in 0 until StreetLife.CAR_BODIES) {
            val c = CarArt.car(body)
            assertTrue(c.length in 50f..74f)
            assertTrue(c.wheelX0 < c.wheelX1)
            assertEquals(StreetLife.carWheel(body), c.wheelR, 1e-6f)
            for (layer in listOf(c.paint, c.day, c.night, c.lights)) {
                for (x in 0 until c.w) {
                    assertEquals(0, layer[x] ushr 24)
                    assertEquals(0, layer[(c.h - 1) * c.w + x] ushr 24)
                }
                for (y in 0 until c.h) {
                    assertEquals(0, layer[y * c.w] ushr 24)
                    assertEquals(0, layer[y * c.w + c.w - 1] ushr 24)
                }
            }
            // the head lamp's pixel is brighter by night than by day, the body darker
            val lampI = (c.lampY.toInt()) * c.w + (c.frontX - 2f * c.ppu).toInt()
            fun lum(p: Int) = (p shr 16 and 255) + (p shr 8 and 255) + (p and 255)
            assertTrue(lum(c.night[lampI]) > lum(c.day[lampI]))
            // the body is the whole silhouette in the grey, lighter up the flank than at the
            // sill; a paint by night is darker than by day
            val bodyI = (c.groundY - 8f * c.ppu).toInt() * c.w + c.w / 2
            val sillI = (c.groundY - 5f * c.ppu).toInt() * c.w + c.w / 2
            assertEquals(255, c.paint[bodyI] ushr 24)
            assertTrue(lum(c.paint[bodyI]) > lum(c.paint[sillI]))
            for (p in CarArt.PAINTS) assertTrue(lum(CarArt.nightPaint(p)) < lum(p) || lum(p) < 90)
            assertArrayEquals(c.day, CarArt.car(body).day)
        }
    }

    @Test
    fun theWheelsTurnRoundTheirMiddleAndEveryDesignIsDifferent() {
        val seen = HashSet<Int>()
        for (rim in 0 until CarArt.RIMS) {
            val w = CarArt.wheel(rim)
            assertEquals(w.w / 2f, w.ox, 1e-6f)
            assertEquals(w.h / 2f, w.oy, 1e-6f)
            // the tyre is round: opaque at its edge all the way round, clear in the corners
            assertEquals(0, w.pixels[0] ushr 24)
            seen.add(w.pixels.contentHashCode())
        }
        assertEquals(CarArt.RIMS, seen.size)
    }

    @Test
    fun theStreetSetFitsItsBudget() {
        val walkers = StreetSprites.walkerBytes()
        // the walk is the hardest thing on the street to make smooth: it gets the most frames
        // (six looks, the older two with their own short steps, and the kneel to a shoe), and
        // they are painted finer than the screen shows them, so they hold up when looked at close
        assertTrue("walkers are $walkers bytes", walkers < 20 * 1024 * 1024)
        assertTrue(StreetSprites.bytes() < 25 * 1024 * 1024)
    }
}
