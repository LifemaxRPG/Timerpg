import com.lifemax.rpg.feature.habits.presentation.scene3d.OceanSample
import com.lifemax.rpg.feature.habits.presentation.scene3d.OceanSpec
import com.lifemax.rpg.feature.habits.presentation.scene3d.OceanWaves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Verifies the G002 Gerstner ocean wave math:
 * - CPU [OceanWaves.evalDisplaced] matches the exact GLSL vertex shader formula in `filament/materials/ocean.mat`
 *   (ported verbatim below as an independent float-precision reference) across sample points, times, and sea states.
 * - [OceanWaves.height] accurately inverts horizontal Gerstner displacement so a boat / Jolt buoyancy probe at world
 *   `(x, z)` sits on the true displaced wave crest and receives the matching analytical unit normal.
 * - Both presets satisfy non-folding steepness bounds (`sum(k * Q * A) < 0.85`, positive Jacobian everywhere).
 * - Camera grid snapping locks every mesh vertex to identical world coordinates so the ocean surface never swims.
 * - Per-frame [OceanWaves.heightInto] allocates zero bytes.
 */
class OceanWavesTest {

    /**
     * Direct line-by-line Kotlin port of `addGerstner` + `materialVertex` from
     * `kotlin-street/filament/materials/ocean.mat` (32-bit float precision), used as the shader oracle.
     */
    private fun shaderOracle(x0: Float, z0: Float, t: Float, spec: OceanSpec): FloatArray {
        val twoPi = 6.28318530718f
        var dispX = 0f; var dispY = 0f; var dispZ = 0f
        var dXx = 1f; var dXy = 0f; var dXz = 0f
        var dZx = 0f; var dZy = 0f; var dZz = 1f

        for (w in spec.waves) {
            val wavelength = w.wavelength
            val amp = w.amplitude
            if (wavelength <= 0.0001f || amp <= 0f) continue
            val dirX = w.dirX
            val dirZ = w.dirZ
            val q = w.steepness
            val omega = w.omega
            val phase0 = w.phaseOffset

            val k = twoPi / wavelength
            val phase = k * (dirX * x0 + dirZ * z0) - omega * t + phase0
            val s = sin(phase)
            val c = cos(phase)

            val qa = q * amp
            val ka = k * amp
            val qka = q * ka

            dispX += qa * dirX * c
            dispY += amp * s
            dispZ += qa * dirZ * c

            dXx -= qka * dirX * dirX * s
            dXy += ka * dirX * c
            dXz -= qka * dirX * dirZ * s

            dZx -= qka * dirX * dirZ * s
            dZy += ka * dirZ * c
            dZz -= qka * dirZ * dirZ * s
        }

        // normalize(cross(dZ, dX))
        val cx = dZy * dXz - dZz * dXy
        val cy = dZz * dXx - dZx * dXz
        val cz = dZx * dXy - dZy * dXx
        val len = sqrt(cx * cx + cy * cy + cz * cz)
        val nx = cx / len
        val ny = cy / len
        val nz = cz / len
        val jacobian = dXx * dZz - dXz * dZx
        val crest = (1f - jacobian).coerceIn(0f, 1f)
        return floatArrayOf(x0 + dispX, dispY, z0 + dispZ, nx, ny, nz, jacobian, crest)
    }

    @Test fun cpuMatchesShaderFormulaAtSamplePoints() {
        val xs = floatArrayOf(-75f, -18.5f, 0f, 7.25f, 41.3f, 128.0f)
        val zs = floatArrayOf(-60f, -9.75f, 0f, 14.5f, 52.0f, -110.25f)
        val times = floatArrayOf(0f, 1.35f, 6.7f, 19.4f, 83.25f)

        for (spec in arrayOf(OceanWaves.CALM, OceanWaves.ROUGH)) {
            for (t in times) {
                for (x in xs) {
                    for (z in zs) {
                        val ref = shaderOracle(x, z, t, spec)
                        val got = OceanWaves.evalDisplaced(x, z, t, spec)
                        val tag = "${spec.name} ($x,$z,t=$t)"
                        assertEquals("$tag x", ref[0], got.x, 1e-4f)
                        assertEquals("$tag y", ref[1], got.y, 1e-4f)
                        assertEquals("$tag z", ref[2], got.z, 1e-4f)
                        assertEquals("$tag nx", ref[3], got.nx, 1e-4f)
                        assertEquals("$tag ny", ref[4], got.ny, 1e-4f)
                        assertEquals("$tag nz", ref[5], got.nz, 1e-4f)
                        assertEquals("$tag jacobian", ref[6], got.jacobian, 1e-4f)
                        assertEquals("$tag crest", ref[7], got.crest, 1e-4f)
                        // Unit normal pointing upward
                        val nLen = sqrt(got.nx * got.nx + got.ny * got.ny + got.nz * got.nz)
                        assertEquals("$tag unit normal", 1f, nLen, 1e-5f)
                        assertTrue("$tag normal points up", got.ny > 0.25f)
                    }
                }
            }
        }
    }

    @Test fun heightInvertsHorizontalGerstnerShiftForBuoyancy() {
        val xs = floatArrayOf(-30f, -4.5f, 0f, 11.2f, 47.8f)
        val zs = floatArrayOf(-24f, 0f, 8.3f, 31.6f)
        val times = floatArrayOf(0f, 2.5f, 11.1f)

        for (spec in arrayOf(OceanWaves.CALM, OceanWaves.ROUGH)) {
            for (t in times) {
                for (x in xs) {
                    for (z in zs) {
                        val s = OceanWaves.height(x, z, t, spec)
                        // The inverted resting point must land right on the queried world (x, z)
                        assertEquals("${spec.name} landed X at ($x,$z,$t)", x, s.x, 1e-2f)
                        assertEquals("${spec.name} landed Z at ($x,$z,$t)", z, s.z, 1e-2f)
                        // Height must lie within [-maxAmplitude, +maxAmplitude]
                        assertTrue("height bounded", abs(s.y) <= spec.maxAmplitude + 1e-4f)
                        // Finite-difference slope check against analytical normal at world (x, z)
                        val eps = 0.05f
                        val hxP = OceanWaves.height(x + eps, z, t, spec).y
                        val hxM = OceanWaves.height(x - eps, z, t, spec).y
                        val hzP = OceanWaves.height(x, z + eps, t, spec).y
                        val hzM = OceanWaves.height(x, z - eps, t, spec).y
                        val dydx = (hxP - hxM) / (2f * eps)
                        val dydz = (hzP - hzM) / (2f * eps)
                        val fdLen = sqrt(dydx * dydx + 1f + dydz * dydz)
                        val fdNx = -dydx / fdLen
                        val fdNy = 1f / fdLen
                        val fdNz = -dydz / fdLen
                        val dot = s.nx * fdNx + s.ny * fdNy + s.nz * fdNz
                        assertTrue("${spec.name} analytical normal agrees with finite-diff surface (dot=$dot)", dot > 0.998f)
                    }
                }
            }
        }
    }

    @Test fun presetsStayNonFoldingWithPositiveJacobian() {
        for (spec in arrayOf(OceanWaves.CALM, OceanWaves.ROUGH)) {
            assertTrue("${spec.name} wave count in 4..6", spec.waves.size in 4..6)
            assertTrue(
                "${spec.name} total steepness sum(kQA)=${spec.totalSteepnessProduct} must stay < 0.85",
                spec.totalSteepnessProduct < 0.85f,
            )
            var minJ = Float.MAX_VALUE
            var maxCrest = 0f
            for (ix in -10..10) {
                for (iz in -10..10) {
                    val s = OceanWaves.evalDisplaced(ix * 2.1f, iz * 2.1f, 3.7f, spec)
                    if (s.jacobian < minJ) minJ = s.jacobian
                    if (s.crest > maxCrest) maxCrest = s.crest
                }
            }
            assertTrue("${spec.name} never folds (minJ=$minJ)", minJ > 0.10f)
            assertTrue("${spec.name} rough waves produce measurable crest compression", maxCrest > 0.05f)
        }
        assertTrue("rough sea has higher maxAmplitude than calm", OceanWaves.ROUGH.maxAmplitude > OceanWaves.CALM.maxAmplitude * 3f)
    }

    @Test fun gridSnappingLocksVerticesWithoutSwimming() {
        val cell = 1.25f
        // Sub-cell camera motion keeps the snapped grid origin identical
        assertEquals(0f, OceanWaves.snapGridOrigin(0.0f, cell), 1e-6f)
        assertEquals(0f, OceanWaves.snapGridOrigin(0.6f, cell), 1e-6f)
        assertEquals(0f, OceanWaves.snapGridOrigin(1.249f, cell), 1e-6f)
        assertEquals(1.25f, OceanWaves.snapGridOrigin(1.25f, cell), 1e-6f)
        assertEquals(-1.25f, OceanWaves.snapGridOrigin(-0.01f, cell), 1e-6f)

        // When the grid origin shifts by one step, vertex (i - 1) in the new grid occupies the exact world XZ of vertex i
        val o0 = OceanWaves.snapGridOrigin(10.1f, cell)
        val o1 = OceanWaves.snapGridOrigin(11.4f, cell)
        assertEquals(cell, o1 - o0, 1e-6f)
        val sOld = OceanWaves.evalDisplaced(o0 + 5 * cell, 0f, 4.2f, OceanWaves.ROUGH)
        val sNew = OceanWaves.evalDisplaced(o1 + 4 * cell, 0f, 4.2f, OceanWaves.ROUGH)
        assertEquals(sOld.x, sNew.x, 1e-6f)
        assertEquals(sOld.y, sNew.y, 1e-6f)
        assertEquals(sOld.z, sNew.z, 1e-6f)
    }

    @Test fun heightIntoAllocatesZeroBytes() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val tid = Thread.currentThread().id
        val out = OceanSample()
        for (i in 0 until 5000) {
            OceanWaves.heightInto(i * 0.13f, -i * 0.09f, i * 0.016f, OceanWaves.ROUGH, out)
        }
        bean.getThreadAllocatedBytes(tid)
        val before = bean.getThreadAllocatedBytes(tid)
        for (i in 0 until 2000) {
            OceanWaves.heightInto(i * 0.17f, i * 0.11f, 10f + i * 0.016f, OceanWaves.ROUGH, out)
        }
        val after = bean.getThreadAllocatedBytes(tid)
        assertEquals("OceanWaves.heightInto must allocate 0 bytes across 2000 queries", 0L, after - before)
    }
}
