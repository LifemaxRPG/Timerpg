package com.timerpg.street.audio

import com.lifemax.rpg.feature.habits.presentation.scene.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FootstepsTest {
    @Test
    fun theStepSoundsOfWhatIsUnderfoot() {
        assertEquals("concrete", Footsteps.surfaceName(Surface.HARD, false, false, false))
        assertEquals("asphalt", Footsteps.surfaceName(Surface.ASPHALT, false, false, false))
        assertEquals("grass", Footsteps.surfaceName(Surface.GRASS, false, false, false))
        assertEquals("gravel", Footsteps.surfaceName(Surface.GRAVEL, false, false, false))
        assertEquals("wood", Footsteps.surfaceName(Surface.WOOD, false, false, false))
        // indoors: the floor or the carpet, whatever the street outside is; winter's snow on all but the decks
        assertEquals("floor", Footsteps.surfaceName(Surface.GRASS, true, false, true))
        assertEquals("carpet", Footsteps.surfaceName(Surface.HARD, true, true, false))
        assertEquals("snow", Footsteps.surfaceName(Surface.ASPHALT, false, false, true))
        assertEquals("wood", Footsteps.surfaceName(Surface.WOOD, false, false, true))
        // every surface falls back to something a set has
        for (s in Footsteps.SURFACES) { val f = Footsteps.fallbacks(s); assertEquals(s, f[0]); assertTrue(f.size >= 2) }
        assertEquals("audio/step/a/grass_03.ogg", Footsteps.file("a", "grass", 2))
        assertTrue(Footsteps.SETS.any { it.id == Footsteps.DEFAULT })
        assertTrue(Footsteps.SETS.size >= 3)
    }

    @Test
    fun noMachineGunRepeats() {
        val p = Footsteps.Picker(3)
        val seen = ArrayList<Int>()
        repeat(400) { seen += p.next("concrete", 12, false).variation }
        for (i in 2 until seen.size) { assertNotEquals(seen[i], seen[i - 1]); assertNotEquals(seen[i], seen[i - 2]) }
        // every variation used
        assertEquals(12, seen.toSet().size)
        // two variations: alternate; one: always it
        val two = Footsteps.Picker(4); var last = -1
        repeat(20) { val v = two.next("grass", 2, false).variation; assertNotEquals(last, v); last = v }
        assertEquals(0, Footsteps.Picker(5).next("snow", 1, false).variation)
    }

    @Test
    fun feetAlternateWithJitter() {
        val p = Footsteps.Picker(9)
        val steps = (0 until 200).map { p.next("wood", 8, false) }
        for (i in 1 until steps.size) assertTrue(steps[i].pan * steps[i - 1].pan < 0f)
        val rates = steps.map { it.rate }
        assertTrue(rates.min() > .94f && rates.max() < 1.06f && rates.max() - rates.min() > .05f)
        val gains = steps.map { it.gain }
        assertTrue(gains.toSet().size > 50)
        // running lands harder
        val run = Footsteps.Picker(9).let { r -> (0 until 200).map { r.next("wood", 8, true).gain }.average() }
        assertTrue(run > gains.average() * 1.15)
    }

    @Test
    fun synthesizedStepsSoundOfTheirGround() {
        val s = StepSynth(1)
        val centroid = HashMap<String, Float>()
        for (surf in Footsteps.SURFACES) {
            val a = s.make(surf); val b = s.make(surf)
            val peak = a.maxOf { abs(it) }
            assertTrue("$surf peak $peak", peak > .05f && peak < 1f)
            assertTrue("$surf differs", a.indices.any { i -> i < b.size && abs(a[i] - b[i]) > 1e-3f })
            // the step's brightness: how often it crosses zero
            var z = 0; for (i in 1 until a.size) if ((a[i] >= 0f) != (a[i - 1] >= 0f)) z++
            centroid[surf] = z / (a.size / 48000f) / 2f
        }
        // a carpet is duller than a pavement, grass and gravel brighter than a carpet
        assertTrue("$centroid", centroid["carpet"]!! < centroid["concrete"]!!)
        assertTrue("$centroid", centroid["gravel"]!! > centroid["carpet"]!!)
        assertTrue("$centroid", centroid["grass"]!! > centroid["carpet"]!!)
    }
}
