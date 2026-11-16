package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.presentation.scene.WeatherLayer
import com.lifemax.rpg.feature.habits.presentation.scene.WeatherSprites
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The weather's painted pieces are baked once from pure Kotlin: the same pixels every time, white
 * at every alpha so they take a tint, the rain tile seamless both ways, the splashes and puddles
 * clear at their borders, the mist clear along its top and dense at its foot, the whole inside its
 * budget.
 */
class WeatherSpritesTest {
    private fun alpha(c: Int) = c ushr 24

    private fun white(l: WeatherLayer) {
        assertEquals(l.w * l.h, l.pixels.size)
        assertTrue(l.pixels.all { alpha(it) == 0 || (it and 0xFFFFFF) == 0xFFFFFF })
    }

    private fun borderClear(l: WeatherLayer) {
        for (x in 0 until l.w) {
            assertEquals(0, alpha(l.pixels[x]))
            assertEquals(0, alpha(l.pixels[(l.h - 1) * l.w + x]))
        }
        for (y in 0 until l.h) {
            assertEquals(0, alpha(l.pixels[y * l.w]))
            assertEquals(0, alpha(l.pixels[y * l.w + l.w - 1]))
        }
    }

    @Test
    fun everyPieceIsTheSameEveryTimeAndWhite() {
        assertArrayEquals(WeatherSprites.streaks().pixels, WeatherSprites.streaks().pixels)
        assertArrayEquals(WeatherSprites.fog().pixels, WeatherSprites.fog().pixels)
        white(WeatherSprites.streaks())
        white(WeatherSprites.fog())
        for (f in 0 until WeatherSprites.SPLASH_FRAMES) {
            val a = WeatherSprites.splash(f)
            assertArrayEquals(a.pixels, WeatherSprites.splash(f).pixels)
            white(a)
        }
        for (v in 0 until WeatherSprites.PUDDLES) {
            val (fill, rim) = WeatherSprites.puddle(v)
            val again = WeatherSprites.puddle(v)
            assertArrayEquals(fill.pixels, again.first.pixels)
            assertArrayEquals(rim.pixels, again.second.pixels)
            white(fill)
            white(rim)
        }
    }

    @Test
    fun theRainTileIsFullOfSlantedStreaksAndRepeatsWithoutASeam() {
        val t = WeatherSprites.streaks()
        assertTrue(t.tile)
        assertEquals(WeatherSprites.STREAK_W, t.w)
        assertEquals(WeatherSprites.STREAK_H, t.h)
        val bright = t.pixels.count { alpha(it) > 60 }
        // some rain, but mostly air between the streaks
        assertTrue("bright $bright", bright > t.w * t.h / 60 && bright < t.w * t.h / 6)
        // a bright pixel's neighbour below and a little to the right is along the same streak
        // more often than the one below and to the left: the slant
        var right = 0
        var left = 0
        for (y in 0 until t.h - 2) for (x in 2 until t.w - 2) {
            if (alpha(t.pixels[y * t.w + x]) < 120) continue
            if (alpha(t.pixels[(y + 2) * t.w + x + 1]) > 60) right++
            if (alpha(t.pixels[(y + 2) * t.w + x - 1]) > 60) left++
        }
        assertTrue("right $right left $left", right > left * 2)
        // the left and right columns go on from each other, as do the top and bottom rows:
        // nothing is cut off at an edge (a streak drawn over the edge wraps round)
        var cut = 0
        for (y in 0 until t.h) {
            val l = alpha(t.pixels[y * t.w])
            val r = alpha(t.pixels[y * t.w + t.w - 1])
            if (
                (l > 120 && r == 0 && alpha(t.pixels[y * t.w + 1]) > 120) ||
                    (r > 120 && l == 0 && alpha(t.pixels[y * t.w + t.w - 2]) > 120)
            )
                cut++
        }
        assertTrue("cut $cut", cut < 3)
    }

    @Test
    fun splashesRiseThenDieAndStayInsideTheirFrame() {
        var lastTop = -1
        var lastSum = 0L
        for (f in 0 until WeatherSprites.SPLASH_FRAMES) {
            val s = WeatherSprites.splash(f)
            assertFalse(s.tile)
            borderClear(s)
            var top = s.h
            var sum = 0L
            for ((i, c) in s.pixels.withIndex()) if (alpha(c) > 40) {
                top = minOf(top, i / s.w)
                sum += alpha(c)
            }
            assertTrue(sum > 0)
            if (f == 1) assertTrue("rises", top < lastTop)
            if (f == WeatherSprites.SPLASH_FRAMES - 1) assertTrue("dies", sum < lastSum)
            lastTop = top
            lastSum = sum
        }
    }

    @Test
    fun puddlesAreSoftIrregularPoolsWithARimOnTheirFarEdge() {
        val fills = ArrayList<IntArray>()
        for (v in 0 until WeatherSprites.PUDDLES) {
            val (fill, rim) = WeatherSprites.puddle(v)
            borderClear(fill)
            borderClear(rim)
            val w = fill.w
            val h = fill.h
            // a pool in the middle, air round it
            assertTrue(alpha(fill.pixels[(h / 2) * w + w / 2]) > 240)
            val wet = fill.pixels.count { alpha(it) > 128 }
            assertTrue("wet $wet", wet > w * h / 8 && wet < w * h * 2 / 3)
            // soft: there are pixels part way between water and slab
            assertTrue(fill.pixels.count { alpha(it) in 40..215 } > 40)
            // the rim is thin and lies along the lower (far) edge, inside the pool
            var rimPx = 0
            var low = 0
            for ((i, c) in rim.pixels.withIndex()) if (alpha(c) > 80) {
                rimPx++
                if (i / w >= h / 2 - 1) low++
                assertTrue(alpha(fill.pixels[i]) > 30)
            }
            assertTrue("rim $rimPx", rimPx in 20..(w * 4))
            assertTrue("low $low of $rimPx", low > rimPx * .8f)
            // irregular: the top edge is not one height along the pool
            val tops =
                (0 until w).mapNotNull { x ->
                    (0 until h).firstOrNull { y -> alpha(fill.pixels[y * w + x]) > 128 }
                }
            assertTrue(tops.max() - tops.min() >= 3)
            fills.add(fill.pixels)
        }
        for (i in fills.indices) for (j in i + 1 until fills.size) assertFalse(
            fills[i].contentEquals(fills[j])
        )
    }

    @Test
    fun theMistIsClearAtItsTopDenseAtItsFootAndSeamlessSideways() {
        val f = WeatherSprites.fog()
        assertTrue(f.tile)
        val w = f.w
        val h = f.h
        for (x in 0 until w) assertEquals(0, alpha(f.pixels[x]))
        val foot = (0 until w).map { alpha(f.pixels[(h - 1) * w + it]) }.average()
        val mid = (0 until w).map { alpha(f.pixels[(h / 2) * w + it]) }.average()
        val high = (0 until w).map { alpha(f.pixels[(h / 8) * w + it]) }.average()
        assertTrue("foot $foot", foot > 200)
        assertTrue("mid $mid foot $foot", mid < foot && mid > 60)
        assertTrue("high $high", high < 60)
        // the top edge wanders
        val edges =
            (0 until w step 8).map { x ->
                (0 until h).firstOrNull { y -> alpha(f.pixels[y * w + x]) > 128 } ?: h
            }
        assertTrue(edges.max() - edges.min() > 8)
        // seamless: the first and last columns are near each other
        var diff = 0
        for (y in 0 until h) diff +=
            kotlin.math.abs(alpha(f.pixels[y * w]) - alpha(f.pixels[y * w + w - 1]))
        assertTrue("seam $diff", diff / h < 40)
    }

    @Test
    fun theWholeWeatherFitsItsBudget() {
        val bytes = WeatherSprites.bytes()
        assertEquals(
            WeatherSprites.streaks().bytes +
                (0 until WeatherSprites.SPLASH_FRAMES).sumOf { WeatherSprites.splash(it).bytes } +
                (0 until WeatherSprites.PUDDLES).sumOf {
                    val (a, b) = WeatherSprites.puddle(it)
                    a.bytes + b.bytes
                } +
                WeatherSprites.fog().bytes,
            bytes,
        )
        assertTrue("weather is $bytes bytes", bytes < 400 * 1024)
    }
}
