package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.Ambient
import com.lifemax.rpg.feature.habits.domain.Pass
import com.lifemax.rpg.feature.habits.presentation.scene.RoadSkySprites
import com.lifemax.rpg.feature.habits.presentation.scene.VehicleSprite
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The far road's vehicles, the light they throw and the meteor's streak are baked once from pure
 * Kotlin: the same pixels every time, clean at the border, inside their budget; and the meteor's
 * own curves ([Ambient]) stay in range: its head and train between 0 and 1 and over by the end of
 * the pass, its sparkles shed only where the head has been.
 */
class RoadSkySpritesTest {
    private val seed = HabitsScenarios.TODAY.hashCode()
    private val vehicles: List<VehicleSprite> by lazy {
        RoadSkySprites.VEHICLES.indices.map { RoadSkySprites.generate(it) }
    }

    private fun alpha(c: Int) = c ushr 24

    private fun lum(c: Int) =
        ((c shr 16 and 255) * .3f + (c shr 8 and 255) * .59f + (c and 255) * .11f)

    private fun borderClear(px: IntArray, w: Int, h: Int) {
        for (x in 0 until w) {
            assertEquals(0, alpha(px[x]))
            assertEquals(0, alpha(px[(h - 1) * w + x]))
        }
        for (y in 0 until h) {
            assertEquals(0, alpha(px[y * w]))
            assertEquals(0, alpha(px[y * w + w - 1]))
        }
    }

    @Test
    fun vehiclesAreTheSameEveryTimeAndAllDifferent() {
        for (i in RoadSkySprites.VEHICLES.indices) {
            val b = RoadSkySprites.generate(i)
            assertArrayEquals(vehicles[i].day, b.day)
            assertArrayEquals(vehicles[i].night, b.night)
            assertFalse(vehicles[i].day.contentEquals(vehicles[i].night))
        }
        for (i in vehicles.indices) for (j in i + 1 until vehicles.size) {
            if (vehicles[i].w == vehicles[j].w && vehicles[i].h == vehicles[j].h)
                assertFalse(vehicles[i].day.contentEquals(vehicles[j].day))
        }
    }

    @Test
    fun vehiclesStandOnTheRoadWithTheirLightsAtTheEndsAndAreDarkAtNight() {
        assertEquals(4, vehicles.size)
        for ((i, v) in vehicles.withIndex()) {
            val spec = RoadSkySprites.VEHICLES[i]
            assertEquals(spec.w * spec.h, v.day.size)
            assertEquals(spec.w * spec.h, v.night.size)
            borderClear(v.day, v.w, v.h)
            borderClear(v.night, v.w, v.h)
            // a few dp long on screen
            assertTrue(v.dpW in 8f..16f && v.dpH in 3f..8f)
            // the road is inside the picture, the lights above it at either end
            assertTrue(v.roadY > v.h / 2f && v.roadY < v.h)
            assertTrue(v.lightY < v.roadY && v.lightY > 0f)
            assertTrue(v.backX < v.w / 3f && v.frontX > v.w * 2f / 3f)
            // the tyres touch the road: something solid and dark just above it
            val y = (v.roadY - 1f).toInt()
            assertTrue(
                (0 until v.w).any { x ->
                    alpha(v.day[y * v.w + x]) > 200 && lum(v.day[y * v.w + x]) < 80f
                }
            )
            // and the night picture is the same shape, far darker
            var dayL = 0f
            var nightL = 0f
            var n = 0
            for (p in v.day.indices) {
                val a = alpha(v.day[p])
                assertEquals(a > 128, alpha(v.night[p]) > 128)
                if (a > 200) {
                    dayL += lum(v.day[p])
                    nightL += lum(v.night[p])
                    n++
                }
            }
            assertTrue(n > 100)
            assertTrue("day $dayL night $nightL", nightL < dayL * .45f)
            // the lenses are lit at night: a bright pixel at the headlight
            val lx = v.frontX.toInt()
            val ly = v.lightY.toInt()
            assertTrue(lum(v.night[ly * v.w + lx]) > 180f)
        }
    }

    @Test
    fun theGlowAndTheBeamAreSoftWhiteAndClearAtTheBorder() {
        val n = RoadSkySprites.GLOW
        val glow = RoadSkySprites.glow()
        assertArrayEquals(glow, RoadSkySprites.glow())
        borderClear(glow, n, n)
        val centre = alpha(glow[(n / 2) * n + n / 2])
        assertTrue(centre > 240)
        for ((i, c) in glow.withIndex()) {
            assertTrue(alpha(c) == 0 || (c and 0xFFFFFF) == 0xFFFFFF)
            assertTrue(alpha(c) <= centre)
            // round: the same at the mirrored pixel
            val x = i % n
            val y = i / n
            assertEquals(alpha(c), alpha(glow[y * n + (n - 1 - x)]))
            assertEquals(alpha(c), alpha(glow[(n - 1 - y) * n + x]))
        }
        val w = RoadSkySprites.BEAM_W
        val h = RoadSkySprites.BEAM_H
        val beam = RoadSkySprites.beam()
        assertArrayEquals(beam, RoadSkySprites.beam())
        borderClear(beam, w, h)
        assertTrue(beam.all { alpha(it) == 0 || (it and 0xFFFFFF) == 0xFFFFFF })
        // brightest near its origin on the left, fainter and wider further out
        val mid = h / 2
        fun column(x: Int) = (0 until h).count { alpha(beam[it * w + x]) > 12 }
        assertTrue(alpha(beam[mid * w + 12]) > alpha(beam[mid * w + 60]))
        assertTrue(alpha(beam[mid * w + 60]) > alpha(beam[mid * w + 110]))
        assertTrue(column(12) < column(60))
        assertEquals(0, alpha(beam[mid * w + 2]))
    }

    @Test
    fun theStreakIsHotAndThinAtTheHeadInAWiderHaloAndTapersToNothing() {
        val s = RoadSkySprites.streak()
        val again = RoadSkySprites.streak()
        assertArrayEquals(s.core, again.core)
        assertArrayEquals(s.halo, again.halo)
        borderClear(s.core, s.w, s.h)
        borderClear(s.halo, s.w, s.h)
        for (layer in listOf(s.core, s.halo)) assertTrue(
            layer.all { alpha(it) == 0 || (it and 0xFFFFFF) == 0xFFFFFF }
        )
        assertTrue(s.headX > s.w * .9f)
        fun peak(px: IntArray, x: Int) = (0 until s.h).maxOf { alpha(px[it * s.w + x]) }
        fun width(px: IntArray, x: Int) = (0 until s.h).count { alpha(px[it * s.w + x]) > 25 }
        val head = s.headX.toInt() - 4
        val mid = s.w / 2
        val tail = 20
        assertTrue(peak(s.core, head) > 230)
        assertTrue(peak(s.core, head) > peak(s.core, mid))
        assertTrue(peak(s.core, mid) > peak(s.core, tail))
        assertTrue(peak(s.core, tail) < 60)
        assertTrue(width(s.halo, head) > width(s.core, head) * 3 / 2)
        assertTrue(width(s.core, head) > width(s.core, mid))
        // the halo wraps round the head: there is glow a little ahead of it, none at the far edge
        assertTrue(peak(s.halo, s.headX.toInt() + 4) > 40)
        assertEquals(0, peak(s.halo, s.w - 1))
    }

    @Test
    fun thePoolFitsItsBudget() {
        val bytes = RoadSkySprites.bytes()
        assertEquals(
            vehicles.sumOf { it.w * it.h * 8 } +
                RoadSkySprites.GLOW * RoadSkySprites.GLOW * 4 +
                RoadSkySprites.BEAM_W * RoadSkySprites.BEAM_H * 4 +
                RoadSkySprites.STREAK_W * RoadSkySprites.STREAK_H * 8,
            bytes,
        )
        assertTrue("pool is $bytes bytes", bytes < 512 * 1024)
    }

    @Test
    fun theMeteorFlaresPeaksMidRunAndIsGoneWithItsTrainByTheEndOfThePass() {
        assertEquals(0f, Ambient.meteorHead(0f), 0f)
        assertEquals(0f, Ambient.meteorTrain(0f), 0f)
        assertEquals(0f, Ambient.meteorReach(0f), 0f)
        var best = 0f
        var bestT = 0f
        var lastReach = 0f
        for (i in 0..200) {
            val t = i / 200f
            val head = Ambient.meteorHead(t)
            val train = Ambient.meteorTrain(t)
            val reach = Ambient.meteorReach(t)
            assertTrue(head in 0f..1f && train in 0f..1f && reach in 0f..1f)
            assertTrue(reach >= lastReach)
            lastReach = reach
            if (head > best) {
                best = head
                bestT = t
            }
            if (t >= Ambient.METEOR_HEAD_END) assertEquals(0f, head, 0f)
        }
        assertEquals(1f, best, .01f)
        assertTrue("peak at $bestT", bestT in .25f..0.45f)
        assertEquals(1f, lastReach, 0f)
        // the train is full while the head burns, still there just after, and gone at the end
        assertTrue(Ambient.meteorTrain(.35f) > .95f)
        assertTrue(Ambient.meteorTrain(Ambient.METEOR_HEAD_END + .05f) > .3f)
        assertEquals(0f, Ambient.meteorTrain(1f), 0f)
        assertTrue(Ambient.SHOOT_SECONDS <= 2f)
    }

    @Test
    fun sparklesAreShedWhereTheHeadHasBeenAndFadeInTheirTime() {
        for (pass in 0 until 200) for (i in 0 until Ambient.SPARKS) {
            val birth = Ambient.sparkBirth(seed, pass, i)
            assertTrue(birth > 0f && birth + Ambient.SPARK_LIFE <= Ambient.METEOR_HEAD_END)
            assertTrue(Ambient.sparkSide(seed, pass, i) in -1f..1f)
            assertEquals(0f, Ambient.sparkAlpha(birth, birth - .01f), 0f)
            assertEquals(0f, Ambient.sparkAlpha(birth, birth), 0f)
            assertTrue(Ambient.sparkAlpha(birth, birth + Ambient.SPARK_LIFE * .3f) > .3f)
            assertEquals(0f, Ambient.sparkAlpha(birth, birth + Ambient.SPARK_LIFE), 1e-4f)
            for (k in 0..20) assertTrue(
                Ambient.sparkAlpha(birth, birth + Ambient.SPARK_LIFE * k / 20f) in 0f..1f
            )
        }
    }

    @Test
    fun eachPassHasItsOwnMeteorAndFireballsAreRare() {
        var fire = 0
        val sizes = HashSet<Float>()
        for (pass in 0 until 1000) {
            val size = Ambient.meteorSize(seed, pass)
            val bright = Ambient.meteorBright(seed, pass)
            assertTrue(size in .8f..2f && bright in .7f..1f)
            assertTrue(Ambient.meteorSlope(seed, pass) in .08f..0.22f)
            sizes += size
            if (Ambient.fireball(seed, pass)) {
                fire++
                assertEquals(1f, bright, 0f)
                assertTrue(size > 1.1f)
            }
        }
        assertTrue("fireballs $fire", fire in 60..200)
        assertTrue(sizes.size > 100)
        // the head stops where it goes out: the train fades in place
        val p = Pass()
        var t = 0f
        while (!Ambient.shootingStar(t, seed, p)) t += .01f
        val start = t - p.t * Ambient.SHOOT_SECONDS
        Ambient.shootingStar(start + Ambient.SHOOT_SECONDS * .75f, seed, p)
        val x = p.x
        val y = p.y
        Ambient.shootingStar(start + Ambient.SHOOT_SECONDS * .95f, seed, p)
        assertTrue(p.on)
        assertEquals(x, p.x, 1e-4f)
        assertEquals(y, p.y, 1e-4f)
    }

    @Test
    fun theFarRoadVariesItsVehiclesAndNowAndThenOneFollowsAnother() {
        val seen = HashSet<Int>()
        var follow = 0
        for (pass in 0 until 1000) {
            for (k in 0 until 2) {
                val v = Ambient.vehicle(seed, pass, k, 4)
                assertTrue(v in 0..3)
                seen += v
            }
            val f = Ambient.carFollow(seed, pass)
            assertTrue(f == 0f || f in .045f..0.08f)
            if (f > 0f) follow++
        }
        assertEquals(setOf(0, 1, 2, 3), seen)
        assertTrue("follow $follow", follow in 200..400)
        assertEquals(Ambient.vehicle(seed, 5, 0, 4), Ambient.vehicle(seed, 5, 0, 4))
    }
}
