import com.lifemax.rpg.feature.habits.presentation.scene.FramePace
import com.lifemax.rpg.feature.habits.presentation.scene.HeatSteps
import com.lifemax.rpg.feature.habits.presentation.scene.ResolutionSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Heat and battery as scheduling (scene/HeatPlan.kt, docs/HEAT.md): the frame pacing and the thermal governor's steps. */
class HeatPlanTest {
    @Test fun framePacing() {
        assertEquals(2, FramePace.interval(120f, 60))
        assertEquals(1, FramePace.interval(60f, 60))
        assertEquals(1, FramePace.interval(120f, 120))
        assertEquals(1, FramePace.interval(0f, 60))
        // the display mode: the rate itself, else a whole multiple of it, else the fastest
        assertEquals(60f, FramePace.pickRefresh(listOf(120f, 60f, 30f), 60)!!, 0f)
        assertEquals(120f, FramePace.pickRefresh(listOf(120f, 90f), 60)!!, 0f)
        assertEquals(90f, FramePace.pickRefresh(listOf(90f), 60)!!, 0f)
        assertEquals(120f, FramePace.pickRefresh(listOf(60f, 120f), 120)!!, 0f)
        // at 120 Hz for 60: a frame every other refresh, evenly (jitter of a few hundred microseconds never skips one)
        val p = 8_333_333L
        var last = 0L; var drawn = 0
        for (i in 0 until 120) {
            val v = 1_000_000_000L + i * p + (if (i % 3 == 0) 300_000L else -200_000L)
            if (FramePace.due(v, last, p, 2)) {
                if (last != 0L) assertEquals("every other refresh", (2 * p).toDouble(), (v - last).toDouble(), 1_000_000.0)
                last = v; drawn++
            }
        }
        assertEquals(60, drawn)
        // the governor's cap holds the target under the choice, never over it
        FramePace.chosen = 120; FramePace.heatCap = 60
        assertEquals(60, FramePace.target)
        FramePace.chosen = 60; FramePace.heatCap = 0
        assertEquals(60, FramePace.target)
    }

    @Test fun theGovernorStepsGraduallyAndComesBack() {
        val g = HeatSteps()
        // cool: nothing
        repeat(30) { assertFalse(g.feed(.4f, 0, 2f)) }
        assertEquals(0, g.level)
        // warming past the first line: one level after a few seconds, not at once
        assertFalse(g.feed(.7f, 0, 2f))
        var changed = 0
        repeat(5) { if (g.feed(.7f, 0, 2f)) changed++ }
        assertEquals(1, changed); assertEquals(1, g.level)
        // hotter: one more at a time, up to the top
        repeat(20) { g.feed(.99f, 0, 2f) }
        assertEquals(HeatSteps.MAX, g.level)
        // a brief dip changes nothing; cool for long enough: down one level at a time
        repeat(3) { assertFalse(g.feed(.5f, 0, 2f)) }
        assertEquals(HeatSteps.MAX, g.level)
        var steps = 0
        repeat(200) { if (g.feed(.3f, 0, 2f)) steps++ }
        assertEquals(0, g.level); assertEquals(HeatSteps.MAX, steps)
        // no headroom from the phone: its status sets the floor at once
        val h = HeatSteps()
        assertTrue(h.feed(Float.NaN, 2, 2f)); assertEquals(2, h.level)
        assertTrue(h.feed(Float.NaN, 3, 2f)); assertEquals(HeatSteps.MAX, h.level)
        // each plan only ever lowers: frame rate first, then resolution, then cascades
        for (l in 1..HeatSteps.MAX) {
            val a = HeatSteps.plan(l - 1); val b = HeatSteps.plan(l)
            assertTrue(b.resStep >= a.resStep && b.cascades <= a.cascades && b.rebake >= a.rebake)
        }
        assertEquals(60, HeatSteps.plan(1).fpsCap)
        assertEquals(0, HeatSteps.plan(1).resStep)
    }

    @Test fun theResolutionHoldsTheGovernorsStep() {
        val r = ResolutionSteps()
        assertTrue(r.setHeatStep(1)); assertEquals(ResolutionSteps.LEVELS[1], r.scale, 1e-6f)
        // a light GPU does not lift it over the governor's step
        repeat(60 * 20) { r.feed(4f, 1f / 60f) }
        assertEquals(ResolutionSteps.LEVELS[1], r.scale, 1e-6f)
        assertTrue(r.setHeatStep(0)); assertEquals(1f, r.scale, 1e-6f)
        // the budget follows the frame rate
        r.budgetMs = 8.3f
        repeat(60 * 10) { r.feed(10f * r.scale * r.scale, 1f / 60f) }
        assertTrue(r.scale < 1f)
    }
}
