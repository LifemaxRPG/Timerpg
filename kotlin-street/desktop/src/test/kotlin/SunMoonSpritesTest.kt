package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.MoonPhase
import com.lifemax.rpg.feature.habits.presentation.scene.MoonLook
import com.lifemax.rpg.feature.habits.presentation.scene.SkyLayer
import com.lifemax.rpg.feature.habits.presentation.scene.SunLook
import com.lifemax.rpg.feature.habits.presentation.scene.SunMoonSprites
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sun and the moon are painted once by pure Kotlin: the same pixels every time, nothing on a
 * sprite's border, the day sun light with no edge or ring in it, the low sun a flattened, redder
 * body, the moon's seas and ray craters where they are on the real moon, its phase right on known
 * dates, lit on the right while waxing with earthshine on its dark side, and the whole inside its
 * memory budget.
 */
class SunMoonSpritesTest {
    private val sun by lazy { SunMoonSprites.sun() }
    private val moon by lazy { SunMoonSprites.moon() }

    private fun sunLayers() =
        listOf(sun.core, sun.bloom, sun.sky, sun.rays, sun.disc, sun.discLight)

    private fun alpha(l: SkyLayer, x: Int, y: Int) = l.pixels[y * l.size + x] ushr 24

    private fun channel(l: SkyLayer, x: Int, y: Int, shift: Int) =
        (l.pixels[y * l.size + x] ushr shift) and 0xFF

    /** The pixel at ([dx], [dy]) dp from the middle. */
    private fun px(l: SkyLayer, dx: Float, dy: Float): Pair<Int, Int> {
        val mid = (l.size - 1) / 2f
        return (mid + dx * l.pxPerDp + .5f).toInt() to (mid + dy * l.pxPerDp + .5f).toInt()
    }

    /** The alpha at ([dx], [dy]) dp from the middle. */
    private fun at(l: SkyLayer, dx: Float, dy: Float): Int =
        px(l, dx, dy).let { (x, y) ->
            if (x !in 0 until l.size || y !in 0 until l.size) 0 else alpha(l, x, y)
        }

    /** The mean alpha (0..1) of a ring [r] dp from the middle, sampled round it. */
    private fun ring(l: SkyLayer, r: Float): Float {
        var sum = 0f
        for (k in 0 until 64) {
            val a = k * Math.PI.toFloat() / 32f
            sum += at(l, r * cos(a), r * sin(a))
        }
        return sum / 64f / 255f
    }

    private fun picture(step: Int) = SunMoonSprites.moonPicture(moon, step)

    /** The picture's [shift] channel (red: sunlit, green: the surface) at shares of the radius. */
    private fun moonAt(l: SkyLayer, u: Float, v: Float, shift: Int): Int {
        val r = SunMoonSprites.MOON_R
        return px(l, u * r, v * r).let { (x, y) -> channel(l, x, y, shift) }
    }

    @Test
    fun spritesAreTheSameEveryTime() {
        val again = SunMoonSprites.sun()
        assertArrayEquals(sun.core.pixels, again.core.pixels)
        assertArrayEquals(sun.sky.pixels, again.sky.pixels)
        assertArrayEquals(sun.rays.pixels, again.rays.pixels)
        assertArrayEquals(sun.disc.pixels, again.disc.pixels)
        val m = SunMoonSprites.moon()
        assertArrayEquals(moon.albedo, m.albedo, 0f)
        assertArrayEquals(moon.halo.pixels, m.halo.pixels)
        assertArrayEquals(picture(5).pixels, SunMoonSprites.moonPicture(m, 5).pixels)
    }

    @Test
    fun layersAreClearOfTheBorderAndTheLightIsWhite() {
        val pictures = listOf(picture(0), picture(8), picture(16))
        for (l in sunLayers() + moon.halo + pictures) {
            assertEquals(l.size * l.size, l.pixels.size)
            // (the rays are faint by design)
            assertTrue(l.pixels.any { (it ushr 24) > (if (l === sun.rays) 40 else 120) })
            for (i in 0 until l.size) {
                assertEquals(0, alpha(l, i, 0))
                assertEquals(0, alpha(l, i, l.size - 1))
                assertEquals(0, alpha(l, 0, i))
                assertEquals(0, alpha(l, l.size - 1, i))
            }
        }
        // the light is white at every alpha, so the hour tints it
        for (l in sunLayers() + moon.halo) assertTrue(
            l.pixels.all { (it ushr 24) == 0 || (it and 0xFFFFFF) == 0xFFFFFF }
        )
    }

    @Test
    fun daySunIsLightFallingFromItsMiddleWithNoRingOrEdge() {
        // the day's three layers together, as a profile out from the middle: always falling (no
        // ring, no rim: nowhere away from the middle brighter than nearer it), to nothing
        var last = Float.MAX_VALUE
        var r = 0f
        while (r < 220f) {
            val v = ring(sun.core, r) + ring(sun.bloom, r) + ring(sun.sky, r)
            assertTrue("rises at $r dp: $v after $last", v <= last + 2f / 255f)
            last = v
            r += .5f
        }
        assertTrue(last < .01f)
        // as pure functions, too: never rising, the white core inside the disc's size
        var prev = Float.MAX_VALUE
        var x = 0f
        while (x < 230f) {
            val v = SunMoonSprites.coreAt(x) + SunMoonSprites.bloomAt(x) + SunMoonSprites.skyAt(x)
            assertTrue(v <= prev)
            prev = v
            x += .25f
        }
        assertTrue(SunMoonSprites.coreAt(SunMoonSprites.SUN_R) < .3f)
        // the bloom reaches three to four times the core, the sky's light ten times that
        assertTrue(ring(sun.bloom, 20f) > ring(sun.core, 20f) * 3f)
        assertTrue(ring(sun.sky, 100f) > .1f && ring(sun.bloom, 40f) < .02f)
        // and no step anywhere: soft enough that a cloud's edge over it reads naturally
        for (l in listOf(sun.core, sun.bloom, sun.sky, sun.rays)) {
            var steepest = 0
            for (y in 0 until l.size) for (i in 0 until l.size - 1) steepest =
                maxOf(steepest, abs(alpha(l, i, y) - alpha(l, i + 1, y)))
            assertTrue("steepest $steepest", steepest < 40)
        }
    }

    @Test
    fun lowSunIsAFlattenedRedderBody() {
        val r = SunMoonSprites.DISC_R
        // solid inside, soft at the edge, and shorter than it is wide
        assertEquals(255, at(sun.disc, 0f, 0f))
        assertTrue(at(sun.disc, r * .95f, 0f) > 100)
        assertTrue(at(sun.disc, 0f, r * .95f) < at(sun.disc, r * .95f, 0f))
        assertEquals(0, at(sun.disc, 0f, r * SunMoonSprites.FLATTEN + 1.2f))
        // brighter in its middle than at its limb, and no ring: falling all the way out
        var prev = 256
        for (k in 0..40) {
            val v = at(sun.discLight, r * k / 40f * 1.1f, 0f)
            assertTrue(v <= prev + 1)
            prev = v
        }
        assertTrue(at(sun.discLight, 0f, 0f) > at(sun.discLight, r * .9f, 0f) + 60)
        // the dusk sun is redder than the day's, and comes on smoothly through the dusk hour
        val look = SunLook()
        look.tints(0f, 0x66FFF3C4, 0xFFFFF3C4.toInt())
        val dayCore = look.tint[SunLook.CORE]
        look.tints(1f, 0x66FFC896, 0xFFFFD9A8.toInt())
        val duskDisc = look.tint[SunLook.DISC]
        fun ratio(c: Int) = ((c ushr 16) and 0xFF) / (1f + (c and 0xFF))
        assertTrue(ratio(duskDisc) > ratio(dayCore) * 2f)
        var last = -1f
        for (k in 0..20) {
            look.alphas(k / 20f, 1f, 1f, 0f, 0f)
            assertTrue(look.alpha[SunLook.DISC] >= last)
            if (k > 0) assertTrue(abs(look.alpha[SunLook.DISC] - last) < .2f)
            last = look.alpha[SunLook.DISC]
        }
        look.alphas(0f, 1f, 0f, 0f, 0f)
        assertEquals(0f, look.alpha[SunLook.DISC], 0f)
        // the sun fades out into the haze after sunset rather than vanishing, and the moon in
        assertEquals(1f, SunLook.up(.55f), 0f)
        assertEquals(0f, SunLook.up(.775f), 1e-4f)
        assertEquals(0f, MoonLook.up(.775f), 1e-4f)
        assertEquals(1f, MoonLook.up(1f), 0f)
        // under overcast it is a pale brightness, not a body
        look.alphas(1f, 1f, 1f, 1f, 0f)
        assertTrue(look.alpha[SunLook.DISC] < .15f && look.alpha[SunLook.VEIL] > 0f)
    }

    @Test
    fun moonHasItsSeasAndRayCratersWhereTheyAre() {
        fun albedo(u: Float, v: Float): Float {
            val mid = (moon.size - 1) / 2f
            val r = SunMoonSprites.MOON_R * moon.pxPerDp
            return moon.albedo[
                    (mid + v * r + .5f).toInt() * moon.size + (mid + u * r + .5f).toInt()]
        }
        // north up, east (Crisium) on the right: Imbrium in the north-west, Serenitatis and
        // Tranquillitatis north-east of the middle, Crisium near the east limb, Nubium south-west
        // of the middle, all darker than the southern highlands
        val highland = albedo(.25f, .55f)
        for ((u, v) in
            listOf(
                -.23f to -.52f,
                .26f to -.45f,
                .5f to -.13f,
                .8f to -.28f,
                -.26f to .36f,
            )) assertTrue("sea at $u,$v", albedo(u, v) < highland - .15f)
        // Tycho, bright in the south; Copernicus brighter than the sea round it
        assertTrue(albedo(-.14f, .69f) > highland + .1f)
        assertTrue(albedo(-.34f, -.17f) > albedo(-.45f, -.05f))
        // the surface has fine texture, not flat fills
        var steps = 0
        for (k in 0 until 30) {
            val a = albedo(.1f + k * .02f, .5f)
            val b = albedo(.12f + k * .02f, .5f)
            if (abs(a - b) > .004f) steps++
        }
        assertTrue("texture $steps", steps > 15)
    }

    @Test
    fun moonPhaseIsRightOnKnownDates() {
        // full moon 2026-03-03 (11:38 UTC) and 2026-01-03; new moon 2026-03-19 and 2026-11-09
        assertEquals(.5f, MoonPhase.fraction("2026-03-03"), .035f)
        assertEquals(.5f, MoonPhase.fraction("2026-01-03"), .035f)
        fun fromNew(day: String) = MoonPhase.fraction(day).let { minOf(it, 1f - it) }
        assertTrue(fromNew("2026-03-19") < .035f)
        assertTrue(fromNew("2026-11-09") < .035f)
        assertTrue(fromNew("2000-01-06") < .035f)
        // a week after new the moon is waxing towards first quarter; a week after full, waning
        assertTrue(MoonPhase.fraction("2026-03-26") in 0.2f..0.3f)
        assertTrue(MoonPhase.fraction("2026-03-10") in 0.7f..0.8f)
        assertTrue(MoonPhase.fraction("2026-03-27") > MoonPhase.fraction("2026-03-26"))
        assertEquals(0f, MoonPhase.illumination(0f), 1e-6f)
        assertEquals(1f, MoonPhase.illumination(.5f), 1e-6f)
        assertEquals(.5f, MoonPhase.illumination(.25f), 1e-6f)
        assertEquals(16, MoonPhase.step(.5f))
        assertEquals(0, MoonPhase.step(.99f))
        assertEquals(8, MoonPhase.step(.25f))
        // a day that will not parse is a full moon, not a crash
        assertEquals(.5f, MoonPhase.fraction(""), 0f)
    }

    @Test
    fun terminatorIsOnTheRightSideWithEarthshineOnTheDark() {
        val red = 16
        val green = 8
        // first quarter: the right half lit, the left dark; last quarter the other way round
        val first = picture(MoonPhase.STEPS / 4)
        assertTrue(moonAt(first, .5f, 0f, red) > 50)
        assertTrue(moonAt(first, -.5f, 0f, red) < 5)
        val last = picture(MoonPhase.STEPS * 3 / 4)
        assertTrue(moonAt(last, -.5f, 0f, red) > 50)
        assertTrue(moonAt(last, .5f, 0f, red) < 5)
        // a waxing crescent is lit on the right edge only; a waning one on the left
        val crescent = picture(MoonPhase.STEPS / 8)
        assertTrue(moonAt(crescent, .9f, 0f, red) > 60)
        assertTrue(moonAt(crescent, 0f, 0f, red) < 5)
        val old = picture(MoonPhase.STEPS * 7 / 8)
        assertTrue(moonAt(old, -.9f, 0f, red) > 60)
        assertTrue(moonAt(old, 0f, 0f, red) < 5)
        // the dark side still shows its surface for the earthshine to light (more on a thin moon
        // than a fat one, none at new, when the sun's glare hides it), and the disc is whole
        assertTrue(moonAt(crescent, -.4f, 0f, green) > 40)
        assertEquals(255, at(crescent, -.5f * SunMoonSprites.MOON_R, 0f))
        val look = MoonLook()
        look.colours(MoonPhase.illumination(1f / 8f), 0f, 0xFF0B1330.toInt(), 0x2EE9EDFF)
        val thin = look.matrix[1]
        look.colours(MoonPhase.illumination(.25f), 0f, 0xFF0B1330.toInt(), 0x2EE9EDFF)
        assertTrue(thin > 0f && thin > look.matrix[1])
        look.colours(0f, 0f, 0xFF0B1330.toInt(), 0x2EE9EDFF)
        assertEquals(0f, look.matrix[1], 0f)
        // full: lit to the limb all round, the limb crisp
        val full = picture(MoonPhase.STEPS / 2)
        // the sunlit ground is as bright at every phase: a crescent's limb as the full moon's
        assertTrue(moonAt(crescent, .9f, 0f, red) > moonAt(full, .9f, 0f, red) * .85f)
        // and the earthshine is faint (about a tenth of that on a tenth-lit crescent) and gone
        // from a quarter on
        look.colours(.1f, 0f, 0xFF0B1330.toInt(), 0x2EE9EDFF)
        assertTrue(look.matrix[1] in .05f..0.12f)
        look.colours(.3f, 0f, 0xFF0B1330.toInt(), 0x2EE9EDFF)
        assertEquals(0f, look.matrix[1], .002f)
        for ((u, v) in listOf(.9f to 0f, -.9f to 0f, 0f to .9f, 0f to -.9f)) assertTrue(
            moonAt(full, u, v, red) > 60
        )
        val r = SunMoonSprites.MOON_R
        assertEquals(0, at(full, r + .5f, 0f))
        assertEquals(255, at(full, r - .5f, 0f))
        // the terminator is soft: from dark to half the light over several pixels, the soft
        // edge on the night side of it
        val (x0, y0) = px(first, -.3f * r, 0f)
        val (x1, _) = px(first, .3f * r, 0f)
        // (across a band of rows, so a crater rim catching the light does not count)
        fun across(x: Int) = (y0 - 8..y0 + 8).sumOf { channel(first, x, it, red) } / 17
        val half = across(x1) / 2
        val dawn = (x0..x1).first { across(it) > 3 }
        val bright = (x0..x1).first { across(it) > half }
        assertTrue("terminator ${bright - dawn} px", bright - dawn >= 3)
        assertTrue(dawn <= px(first, 0f, 0f).first)
        // the halo grows with the light, and is wider in mist
        look.alphas(1f, 1f, 0f, 0f)
        val fullHalo = look.halo
        val clearHaze = look.haze
        look.alphas(.1f, 1f, 0f, 0f)
        assertTrue(fullHalo > look.halo * 3f)
        look.alphas(1f, 1f, 0f, 1f)
        assertTrue(look.haze > clearHaze * 3f)
    }

    @Test
    fun poolIsInsideItsBudget() {
        val bytes = sun.bytes() + moon.bytes() + picture(0).bytes
        assertEquals(bytes, SunMoonSprites.bytes())
        assertTrue("sun and moon are $bytes bytes", bytes < 1024 * 1024)
        // the layers cover what they are drawn at: the moon at four or more pixels to a dp, the
        // sun's sky light wide
        assertTrue(moon.pxPerDp >= 4f)
        assertTrue(moon.size / moon.pxPerDp / 2f > SunMoonSprites.MOON_R + 1f)
        assertTrue(sun.disc.dp / 2f > SunMoonSprites.DISC_R + 1.5f)
        assertTrue(sun.sky.dp > 400f)
    }
}
