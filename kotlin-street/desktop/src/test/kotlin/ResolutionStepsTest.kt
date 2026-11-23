import com.lifemax.rpg.feature.habits.presentation.scene.ResolutionSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The open world's automatic resolution in held steps (scene/ResolutionSteps.kt): no scale a frame, a step after seconds. */
class ResolutionStepsTest {
    @Test fun resolutionStepsHoldAndStep() {
        val r = ResolutionSteps()
        // a GPU just over budget, every frame a little different: one step down after a while, then held
        var changes = 0
        // (the GPU's time follows the pixels: the scale squared)
        repeat(60 * 10) { i -> if (r.feed((18f + (i % 7) * .3f) * r.scale * r.scale, 1f / 60f)) changes++ }
        assertEquals("one step down in 10 s", 1, changes)
        assertEquals(ResolutionSteps.LEVELS[1], r.scale, 1e-6f)
        // light again: back up only after seconds, once
        changes = 0
        repeat(60 * 3) { if (r.feed(8f, 1f / 60f)) changes++ }
        assertEquals("not straight back up", 0, changes)
        repeat(60 * 10) { if (r.feed(8f, 1f / 60f)) changes++ }
        assertEquals("back up", 1, changes)
        assertEquals(1f, r.scale, 1e-6f)
        // unknown GPU times change nothing
        repeat(600) { assertFalse(r.feed(-1f, 1f / 60f)) }
    }
}
