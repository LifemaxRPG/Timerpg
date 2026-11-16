package com.lifemax.rpg.feature.habits

import androidx.compose.ui.graphics.toArgb
import com.lifemax.rpg.feature.habits.presentation.scene.CloudSprites
import com.lifemax.rpg.feature.habits.presentation.scene.MilkyWay
import com.lifemax.rpg.feature.habits.presentation.scene.SkyColors
import com.lifemax.rpg.feature.habits.presentation.scene.SkyDome
import com.lifemax.rpg.feature.habits.presentation.scene.skyColors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sky's own picture ([SkyDome]) is the air's light by the hour: deep blue overhead and pale at
 * the horizon by day, warm at the horizon and cool overhead at dusk, warmest under the sun, a dark
 * zenith over the city's glow at night, and the same pixels every time. The Milky Way's band fades
 * at its ends; a cloud's belly layer is lit low, not at its crown.
 */
class SkyDomeTest {
    private fun paint(hour: Float, right: Boolean = hour > 12f): IntArray {
        val s: SkyColors = skyColors(hour)
        val px = IntArray(SkyDome.W * SkyDome.H)
        SkyDome.paint(
            px,
            s.sky[0].toArgb(),
            s.sky[1].toArgb(),
            s.sky[2].toArgb(),
            s.glow.toArgb(),
            s.halo.toArgb(),
            s.dark,
            right,
        )
        return px
    }

    private fun r(c: Int) = (c ushr 16) and 255

    private fun g(c: Int) = (c ushr 8) and 255

    private fun b(c: Int) = c and 255

    private fun luma(c: Int) = .3f * r(c) + .59f * g(c) + .11f * b(c)

    private fun px(p: IntArray, x: Int, y: Int) = p[y * SkyDome.W + x]

    @Test
    fun theSameSkyEveryTimeAndOpaque() {
        for (h in listOf(3f, 6.5f, 12f, 19f, 20.5f)) {
            val a = paint(h)
            assertArrayEquals(a, paint(h))
            assertTrue(a.all { (it ushr 24) == 255 })
        }
    }

    @Test
    fun byDayDeepBlueOverheadPaleAtTheHorizonBrighterTowardsTheSun() {
        val p = paint(14f)
        val mid = SkyDome.W / 2
        val zenith = px(p, mid, 0)
        val horizon = px(p, mid, SkyDome.H - 1)
        assertTrue(b(zenith) > r(zenith) + 80)
        assertTrue(luma(horizon) > luma(zenith) + 60)
        // the sun on the right: the low sky is brighter there than on the left
        val y = SkyDome.H * 3 / 4
        assertTrue(luma(px(p, SkyDome.W - 1, y)) > luma(px(p, 0, y)))
    }

    @Test
    fun atDuskWarmAtTheHorizonUnderTheSunAndCoolOverhead() {
        val p = paint(19f)
        val zenith = px(p, SkyDome.W / 2, 0)
        assertTrue(b(zenith) > r(zenith))
        val sunSide = px(p, SkyDome.W - 1, SkyDome.H - 1)
        val farSide = px(p, 0, SkyDome.H - 1)
        assertTrue(r(sunSide) > b(sunSide) + 60)
        assertTrue(r(sunSide) + g(sunSide) > r(farSide) + g(farSide))
    }

    @Test
    fun atNightADarkZenithOverTheCitysGlow() {
        val p = paint(23f)
        val zenith = px(p, SkyDome.W / 2, 0)
        val horizon = px(p, SkyDome.W / 2, SkyDome.H - 1)
        assertTrue(luma(zenith) < 20f)
        assertTrue(luma(horizon) > luma(zenith) + 25f)
        // the city's glow is warm: red climbs more than blue towards the horizon
        assertTrue(r(horizon) - r(zenith) > b(horizon) - b(zenith) - 40)
    }

    @Test
    fun theSkyAtASharePicksTheMiddleColumn() {
        val p = paint(10f)
        assertEquals(px(p, SkyDome.W / 2, 0), SkyDome.at(p, 0f))
        assertEquals(px(p, SkyDome.W / 2, SkyDome.H - 1), SkyDome.at(p, 1f))
    }

    @Test
    fun theMilkyWayFadesAtItsEndsAndIsBrightAlongItsMiddle() {
        val p = MilkyWay.pixels()
        assertEquals(MilkyWay.W * MilkyWay.H, p.size)
        assertArrayEquals(p, MilkyWay.pixels())
        fun columnAlpha(x: Int) = (0 until MilkyWay.H).sumOf { (p[it * MilkyWay.W + x] ushr 24) }
        val middle = columnAlpha(MilkyWay.W / 2)
        assertTrue(middle > 0)
        assertEquals(0, columnAlpha(0))
        assertEquals(0, columnAlpha(MilkyWay.W - 1))
        // the band is soft: its top and bottom rows are nearly empty
        assertTrue((0 until MilkyWay.W).all { (p[it] ushr 24) < 40 })
    }

    @Test
    fun aCloudsBellyIsLitLowNotAtItsCrown() {
        for (i in CloudSprites.SPECS.indices) {
            val c = CloudSprites.generate(i)
            var upper = 0L
            var lower = 0L
            for (y in 0 until c.h) for (x in 0 until c.w) {
                val a = (c.under[y * c.w + x] ushr 24).toLong()
                if (y < c.h / 2) upper += a else lower += a
            }
            assertTrue("cloud $i: belly $lower, crown $upper", lower > upper)
        }
    }
}
