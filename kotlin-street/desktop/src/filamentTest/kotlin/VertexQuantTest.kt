import com.lifemax.rpg.feature.habits.presentation.scene3d.TownVertex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The quantised vertices (MEMORY_AUDIT.md, G5, "Step 4 as built"): read back the way street_lit.mat reads them
 * (normalised shorts times the batch's maps), every position is within half a step of where it was (a step at most
 * [TownVertex.QSTEP_MAX] across, [TownVertex.QSTEP_MAX_Y] up), every texel edge of the batch's picture comes back exactly
 * on its edge (the nearest-filtered pixel art samples the same texel), the tangent frame is the same bytes, and a batch
 * too big for 16 bits stays float.
 */
class VertexQuantTest {
    private fun batch(n: Int, f: (Int) -> FloatArray): ByteBuffer {
        val b = ByteBuffer.allocateDirect(n * TownVertex.STRIDE).order(ByteOrder.nativeOrder())
        val q = floatArrayOf(.1f, -.2f, .3f, .9f)
        for (i in 0 until n) { val v = f(i); TownVertex.put(b, v[0], v[1], v[2], v[3], v[4], q) }
        b.flip()
        return b
    }

    @Test fun positionsWithinHalfAStepAndTexelEdgesExact() {
        val rnd = java.util.Random(5)
        val w = 924; val h = 512
        val n = 4000
        // a building-sized batch far out at the town's edge, its texture coordinates on texel edges and in between
        val src = Array(n) { i ->
            val edge = i % 2 == 0
            floatArrayOf(15000f + rnd.nextFloat() * 700f, rnd.nextFloat() * 600f, -14000f + rnd.nextFloat() * 300f,
                if (edge) rnd.nextInt(w + 1) / w.toFloat() else rnd.nextFloat(), if (edge) rnd.nextInt(h + 1) / h.toFloat() else rnd.nextFloat())
        }
        val vb = batch(n) { src[it] }
        val q = TownVertex.quantize(vb, n, w, h)
        assertNotNull("a building-sized batch is quantised", q); q!!
        val pm = q.posMap; val ps = q.posScale; val um = q.uvMap
        assertEquals(1f, pm[3])
        val out = q.vb.duplicate().order(ByteOrder.nativeOrder())
        for (i in 0 until n) {
            val o = i * TownVertex.QSTRIDE
            fun a(k: Int) = (out.getShort(o + k * 2).toInt() and 0xffff) / 65535f
            for (c in 0 until 3) {
                val p = pm[c] + a(c) * ps[c]
                val step = ps[c] / 65535f
                assertTrue("axis $c step ${step}", step <= (if (c == 1) TownVertex.QSTEP_MAX_Y else TownVertex.QSTEP_MAX))
                // (half a step, and the float's own rounding at 15000 units)
                assertEquals("vertex $i axis $c", src[i][c], p, step / 2f + 0.004f)
            }
            assertEquals(65535, out.getShort(o + 6).toInt() and 0xffff)
            // texture coordinates as the material gets them: u, and Filament's flipped v
            val u = um[0] + a(4) * um[2]
            val vFlip = um[1] + (1f - a(5)) * um[3]
            if (i % 2 == 0) {
                // a texel edge: the same texel either side of it as from the float
                assertEquals("vertex $i u texel edge", Math.round(src[i][3] * w), Math.round(u * w))
                assertEquals("vertex $i u on its edge", src[i][3] * w, u * w, 1e-3f)
                assertEquals("vertex $i v on its edge", (1f - src[i][4]) * h, vFlip * h, 1e-3f)
            } else {
                assertEquals("vertex $i u", src[i][3], u, 1f / (w * TownVertex.UV_STEPS_MIN))
                assertEquals("vertex $i v", 1f - src[i][4], vFlip, 1f / (h * TownVertex.UV_STEPS_MIN))
            }
            // the tangent frame as it was
            for (k in 0 until 4) assertEquals(vb.getShort(i * TownVertex.STRIDE + 20 + k * 2), out.getShort(o + 12 + k * 2))
        }
    }

    @Test fun tooBigStaysFloat() {
        val n = 100
        // a batch 9000 units across: past 65534 steps of 1/16
        assertNull(TownVertex.quantize(batch(n) { i -> floatArrayOf(i * 90f, 0f, 0f, 0f, 0f) }, n, 64, 64))
        // no picture size given: float
        assertNull(TownVertex.quantize(batch(n) { i -> floatArrayOf(i.toFloat(), 0f, 0f, 0f, 0f) }, n, 0, 0))
        // texture coordinates repeating far too often for 16 sub-steps a texel
        assertNull(TownVertex.quantize(batch(n) { i -> floatArrayOf(i.toFloat(), 0f, 0f, i * 10f, 0f) }, n, 64, 64))
    }
}
