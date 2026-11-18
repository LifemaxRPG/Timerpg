import com.lifemax.rpg.feature.habits.presentation.scene.StageCamera
import com.lifemax.rpg.feature.habits.presentation.scene3d.LookMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Filament trial's clip-space matrices: the screen is the classic's exactly (every look, any lean), and the depth
 * is in order along the camera's look and inside the depth range wherever the street can be.
 */
class LookMatrixTest {
    private val w = 2340f
    private val h = 1080f

    private fun camera(yaw: Float) = StageCamera().apply {
        cx = w / 2f; cy = 823.2f; unit = 2.4348f; eye = (896f - 823.2f) / 2.4348f; this.yaw = yaw; tx = 300f; tz = 0f
        update()
    }

    /** Clip (x, y, z', w) of world point p through look matrix [l], z' Filament's reversed depth over w. */
    private fun clip(l: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val c = FloatArray(4) { r -> l[r] * x + l[4 + r] * y + l[8 + r] * z + l[12 + r] }
        return floatArrayOf(c[0] / c[3], c[1] / c[3], (.5f - .5f * c[2]) / c[3], c[3])
    }

    @Test fun screenIsTheClassicsAtAnyLean() {
        for (yaw in listOf(0f, .3f, .9f, 1.5707964f)) for (lean in listOf(0f, .5f, 1f)) {
            val cam = camera(yaw)
            val m = FloatArray(12)
            cam.leaned(m, lean, .2f * cam.unit / 1f, -.1f * cam.unit, lean * .5f, 0f, .1f * cam.unit)
            val l = FloatArray(16)
            LookMatrix.of(m, cam, w, h, 300f, 50f, 40f, 1f, 0f, l)
            for ((x, y, z) in listOf(Triple(250f, 0f, 0f), Triple(400f, 120f, 90f), Triple(300f, 60f, 300f))) {
                val p = FloatArray(3)
                cam.project(m, x, y, z, p)
                if (p[2] < 30f) continue
                val c = clip(l, x, y, z)
                assertEquals("x yaw=$yaw lean=$lean", p[0], (c[0] + 1f) / 2f * w, .01f)
                assertEquals("y yaw=$yaw lean=$lean", p[1], (c[1] + 1f) / 2f * h, .01f)
            }
        }
    }

    @Test fun depthIsInOrderAndInRange() {
        for (lean in listOf(0f, .5f, 1f)) {
            val cam = camera(0f)
            val m = FloatArray(12)
            cam.leaned(m, lean, .2f * cam.unit, -.1f * cam.unit, 0f, 0f, 0f)
            val l = FloatArray(16)
            // a box's middle, as FilamentStreet picks it
            LookMatrix.of(m, cam, w, h, 300f, 50f, 50f, 1f, 0f, l)
            var last = Float.MAX_VALUE
            // from the near pavement to the far side of a deep building
            var z = -150f
            while (z <= 250f) {
                val d = clip(l, 300f, 40f, z)[2]
                assertTrue("lean=$lean z=$z depth $d in range", d > 0f && d <= 1f)
                assertTrue("lean=$lean z=$z nearer is greater", d < last)
                last = d; z += 10f
            }
        }
    }
}
