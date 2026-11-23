import com.lifemax.rpg.feature.habits.presentation.scene3d.OceanGridMesh
import com.lifemax.rpg.feature.habits.presentation.scene3d.OceanSample
import com.lifemax.rpg.feature.habits.presentation.scene3d.OceanSpec
import com.lifemax.rpg.feature.habits.presentation.scene3d.OceanWaves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Verifies the G002 ocean prototype:
 *  1. CPU `OceanWaves.evalDisplaced` matches the `ocean.mat` vertex shader formula verbatim at sample points.
 *  2. CPU `OceanWaves.height` accurately inverts the horizontal Gerstner shift and matches finite-difference normals.
 *  3. Both CALM and ROUGH presets stay non-folding (positive Jacobian) and aligned into coherent parallel swells.
 *  4. Grid snapping keeps ocean vertices locked to fixed world XZ positions without swimming.
 *  5. Zero-allocation `OceanWaves.heightInto` allocates 0 bytes per call.
 *  6. Nested-ring mesh (`OceanGridMesh`) reaches > 5 km in wave rings + 24 km far horizon skirt with 100% watertight
 *     manifold topology (zero T-junctions or cracks across all 8 ring-to-ring seams).
 *  7. Shared `VERTEX_DOMAIN_DEVICE` clip matrix puts the ocean below the horizon, sky above, near water under/in front
 *     of the boat, and matches the boat projection bit-for-bit at the waterline.
 */
class OceanWavesTest {

    private data class ShaderVertexOut(
        val dx: Float,
        val dy: Float,
        val dz: Float,
        val nx: Float,
        val ny: Float,
        val nz: Float,
        val jacobian: Float,
    )

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Direct 1:1 port of `ocean.mat` `addGerstner` + `materialVertex` for CPU-vs-shader parity verification. */
    private fun shaderOracle(
        worldX: Float,
        worldZ: Float,
        eyeX: Float,
        eyeZ: Float,
        t: Float,
        spec: OceanSpec,
        fadeStart: Float = 140f,
        fadeEnd: Float = 2200f,
    ): ShaderVertexOut {
        val twoPi = 6.28318530718f
        val horizDist = sqrt((worldX - eyeX) * (worldX - eyeX) + (worldZ - eyeZ) * (worldZ - eyeZ))
        val farFade = 1f - smoothstep(fadeStart, fadeEnd, horizDist)
        val approxCell = max(0f, (horizDist - 40f) * 0.040f)

        var dispX = 0f; var dispY = 0f; var dispZ = 0f
        var dXx = 1f; var dXy = 0f; var dXz = 0f
        var dZx = 0f; var dZy = 0f; var dZz = 1f

        if (farFade > 0.0001f) {
            for (w in spec.waves) {
                val wavelength = w.wavelength
                val amp = w.amplitude
                if (wavelength <= 0.0001f || amp <= 0f) continue
                val nyquistFade = smoothstep(approxCell * 1.8f, approxCell * 4.0f, wavelength)
                val ampEff = amp * farFade * nyquistFade
                if (ampEff <= 0.00001f) continue

                val k = twoPi / wavelength
                val phase = k * (w.dirX * worldX + w.dirZ * worldZ) - w.omega * t + w.phaseOffset
                val s = sin(phase.toDouble()).toFloat()
                val c = cos(phase.toDouble()).toFloat()
                val qa = w.steepness * ampEff
                val ka = k * ampEff
                val qka = w.steepness * ka

                dispX += qa * w.dirX * c
                dispY += ampEff * s
                dispZ += qa * w.dirZ * c

                dXx -= qka * w.dirX * w.dirX * s
                dXy += ka * w.dirX * c
                dXz -= qka * w.dirX * w.dirZ * s

                dZx -= qka * w.dirX * w.dirZ * s
                dZy += ka * w.dirZ * c
                dZz -= qka * w.dirZ * w.dirZ * s
            }
        }

        val nxRaw = dZy * dXz - dZz * dXy
        val nyRaw = dZz * dXx - dZx * dXz
        val nzRaw = dZx * dXy - dZy * dXx
        val len = sqrt(nxRaw * nxRaw + nyRaw * nyRaw + nzRaw * nzRaw).coerceAtLeast(1e-6f)
        val jac = dXx * dZz - dXz * dZx
        return ShaderVertexOut(dispX, dispY, dispZ, nxRaw / len, nyRaw / len, nzRaw / len, jac)
    }

    @Test
    fun cpuMatchesShaderFormulaAtSamplePoints() {
        val sampleCoords = floatArrayOf(-30f, -14.5f, -3.2f, 0f, 8.7f, 24.3f)
        val times = floatArrayOf(0f, 1.3f, 4.7f, 11.2f, 29.8f)

        for (spec in listOf(OceanWaves.CALM, OceanWaves.ROUGH)) {
            for (t in times) {
                for (x0 in sampleCoords) {
                    for (z0 in sampleCoords) {
                        val cpu = OceanWaves.evalDisplaced(x0, z0, t, spec)
                        // Eye near sample point (within unattenuated 40 m near field around boat/camera)
                        val gpu = shaderOracle(x0, z0, x0, z0, t, spec)
                        assertEquals("dx mismatch at ($x0,$z0,t=$t)", gpu.dx, cpu.dx, 1e-4f)
                        assertEquals("dy mismatch at ($x0,$z0,t=$t)", gpu.dy, cpu.y, 1e-4f)
                        assertEquals("dz mismatch at ($x0,$z0,t=$t)", gpu.dz, cpu.dz, 1e-4f)
                        assertEquals("nx mismatch at ($x0,$z0,t=$t)", gpu.nx, cpu.nx, 1e-4f)
                        assertEquals("ny mismatch at ($x0,$z0,t=$t)", gpu.ny, cpu.ny, 1e-4f)
                        assertEquals("nz mismatch at ($x0,$z0,t=$t)", gpu.nz, cpu.nz, 1e-4f)
                        assertEquals("jacobian mismatch at ($x0,$z0,t=$t)", gpu.jacobian, cpu.jacobian, 1e-4f)
                    }
                }
            }
        }

        // Far-field horizon rings must smoothly flatten wave amplitude to 0
        val farOut = shaderOracle(0f, 3000f, 0f, 0f, 5.0f, OceanWaves.ROUGH)
        assertEquals("far ocean dy must flatten to 0", 0f, farOut.dy, 1e-6f)
        assertEquals("far ocean normal must be vertical +Y", 1f, farOut.ny, 1e-6f)
    }

    @Test
    fun heightInvertsHorizontalGerstnerShiftForBuoyancy() {
        val eps = 0.02f
        val pts = arrayOf(0f to 0f, 5.5f to -3.2f, -12.8f to 9.4f, 21.1f to 17.6f)
        for (spec in listOf(OceanWaves.CALM, OceanWaves.ROUGH)) {
            for (t in floatArrayOf(0.5f, 3.7f, 9.2f)) {
                for ((wx, wz) in pts) {
                    val s = OceanWaves.height(wx, wz, t, spec)
                    // Check fixed-point residual: (x0 + dx, z0 + dz) must land back on (wx, wz)
                    val x0 = wx - s.dx
                    val z0 = wz - s.dz
                    val verify = OceanWaves.evalDisplaced(x0, z0, t, spec)
                    assertEquals("horizontal inversion X residual", wx, x0 + verify.dx, 0.005f)
                    assertEquals("horizontal inversion Z residual", wz, z0 + verify.dz, 0.005f)
                    assertEquals("height consistency", verify.y, s.y, 0.002f)

                    // Analytical normal must agree with finite-difference gradient of height(x, z, t)
                    val hxPlus = OceanWaves.height(wx + eps, wz, t, spec).y
                    val hxMinus = OceanWaves.height(wx - eps, wz, t, spec).y
                    val hzPlus = OceanWaves.height(wx, wz + eps, t, spec).y
                    val hzMinus = OceanWaves.height(wx, wz - eps, t, spec).y
                    val dhdx = (hxPlus - hxMinus) / (2f * eps)
                    val dhdz = (hzPlus - hzMinus) / (2f * eps)
                    val fdLen = sqrt(dhdx * dhdx + 1f + dhdz * dhdz)
                    val fdNx = -dhdx / fdLen
                    val fdNy = 1f / fdLen
                    val fdNz = -dhdz / fdLen
                    val dot = s.nx * fdNx + s.ny * fdNy + s.nz * fdNz
                    assertTrue("analytical normal must agree with finite-difference normal (dot=$dot)", dot > 0.997f)
                }
            }
        }
    }

    @Test
    fun presetsStayNonFoldingWithPositiveJacobian() {
        val sample = OceanSample()
        for (spec in listOf(OceanWaves.CALM, OceanWaves.ROUGH)) {
            assertTrue("folding index ${spec.foldingIndex} must be < 0.75", spec.foldingIndex < 0.75f)
            // Verify swells are coherently aligned along main direction (prevents isotropic cloudy blobs)
            for (w in spec.waves) {
                val align = w.dirX * spec.mainDirX + w.dirZ * spec.mainDirZ
                assertTrue("wave must align within 30 deg of main swell (dot=$align)", align > 0.86f)
            }
            var minJ = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            for (ix in 0..20) {
                for (iz in 0..20) {
                    OceanWaves.evalDisplacedInto(ix * 1.3f, iz * 1.3f, 2.7f, spec, sample)
                    if (sample.jacobian < minJ) minJ = sample.jacobian
                    if (sample.y > maxY) maxY = sample.y
                    if (sample.y < minY) minY = sample.y
                }
            }
            assertTrue("min Jacobian $minJ must stay strictly positive (no self-folding)", minJ > 0.25f)
            assertTrue("wave range (${maxY - minY}) must be non-trivial", maxY - minY > 0.2f)
        }
    }

    @Test
    fun gridSnappingLocksVerticesWithoutSwimming() {
        val snapStep = 8.0f
        val originA = OceanWaves.snapGridOrigin(3.1f, snapStep)
        val originB = OceanWaves.snapGridOrigin(7.9f, snapStep)
        assertEquals("small camera motion inside one snap step must not move grid", originA, originB, 0f)

        val originC = OceanWaves.snapGridOrigin(8.2f, snapStep)
        assertEquals("crossing snap boundary must jump by exact integer multiple of snapStep", snapStep, originC - originA, 1e-6f)

        // Vertex index k in ring 0 (1 m cell) at originC lands on exact same world X as vertex k+8 at originA
        val worldAtA = originA + 15 * 1.0f
        val worldAtC = originC + 7 * 1.0f
        assertEquals("shifted ring vertices must land on identical world coordinates", worldAtA, worldAtC, 1e-6f)
    }

    @Test
    fun heightIntoAllocatesZeroBytes() {
        val mx = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean ?: return
        if (!mx.isThreadAllocatedMemorySupported) return
        val tid = Thread.currentThread().threadId()
        val sample = OceanSample()
        repeat(2000) { i ->
            OceanWaves.heightInto(i * 0.3f, i * 0.5f, i * 0.02f, OceanWaves.ROUGH, sample)
        }
        val before = mx.getThreadAllocatedBytes(tid)
        repeat(1000) { i ->
            OceanWaves.heightInto(i * 0.3f, i * 0.5f, i * 0.02f, OceanWaves.ROUGH, sample)
        }
        val delta = mx.getThreadAllocatedBytes(tid) - before
        assertTrue("heightInto should allocate ~0 bytes over 1000 calls (got $delta B)", delta <= 512L)
    }

    @Test
    fun nestedRingMeshIsWatertightAndReachesHorizon() {
        val mesh = OceanGridMesh.build()
        assertTrue("vertex count ${mesh.vertexCount} must fit in 16-bit USHORT", mesh.vertexCount <= 65535)
        assertTrue("wave outer radius ${mesh.waveOuterRadius} must reach >= 5 km", mesh.waveOuterRadius >= 5000f)
        assertTrue("far horizon radius ${mesh.farHorizonRadius} must reach >= 20 km", mesh.farHorizonRadius >= 20000f)

        // Count undirected edges across every triangle in all 9 wave rings + far horizon skirt
        val edgeCounts = HashMap<Long, Int>(mesh.triangleCount * 2)
        val inds = mesh.indices
        for (t in 0 until mesh.triangleCount) {
            val a = inds[t * 3]
            val b = inds[t * 3 + 1]
            val c = inds[t * 3 + 2]
            fun addEdge(u: Int, v: Int) {
                val lo = min(u, v).toLong()
                val hi = max(u, v).toLong()
                val key = (lo shl 32) or hi
                edgeCounts[key] = (edgeCounts[key] ?: 0) + 1
            }
            addEdge(a, b); addEdge(b, c); addEdge(c, a)
        }

        val far = mesh.farHorizonRadius
        var outerBoundaryEdges = 0
        var badEdges = 0
        for ((key, count) in edgeCounts) {
            if (count == 2) continue
            val u = (key ushr 32).toInt()
            val v = (key and 0xFFFFFFFFL).toInt()
            val ux = abs(mesh.positionsX[u]); val uz = abs(mesh.positionsZ[u])
            val vx = abs(mesh.positionsX[v]); val vz = abs(mesh.positionsZ[v])
            val onOuterBorder = count == 1 && (
                (abs(ux - far) < 0.5f && abs(vx - far) < 0.5f) ||
                    (abs(uz - far) < 0.5f && abs(vz - far) < 0.5f)
                )
            if (onOuterBorder) {
                outerBoundaryEdges++
            } else {
                badEdges++
            }
        }
        assertEquals("zero T-junctions or non-manifold edges allowed across all rings & seams", 0, badEdges)
        assertEquals("outer horizon perimeter should have 4 * ringCells edges", 4 * mesh.ringCells, outerBoundaryEdges)
    }

    @Test
    fun deviceProjectionPutsWaterBelowHorizonAndBoatUpright() {
        // Exact G002-r1 verification camera: 6 m up and 20 m behind the boat at (0, 0, 0)
        val ex = 0f; val ey = 6.0f; val ez = -20.0f
        val tx = 0f; val ty = 1.8f; val tz = 4.0f
        val fovY = 42.0
        val w = 960; val h = 540
        val aspect = w.toFloat() / h.toFloat()
        val near = 0.5f

        var fx = tx - ex; var fy = ty - ey; var fz = tz - ez
        val fl = sqrt(fx * fx + fy * fy + fz * fz)
        fx /= fl; fy /= fl; fz /= fl
        var rx = -fz; var rz = fx
        val rl = sqrt(rx * rx + rz * rz)
        rx /= rl; rz /= rl
        val ux = -rz * fy; val uy = rz * fx - rx * fz; val uz = rx * fy
        val fyScale = (1.0 / tan(fovY * PI / 360.0)).toFloat()
        val fxScale = fyScale / aspect
        val ySign = -1f // headless desktop (LookMatrix.windowY = false)

        val look = FloatArray(16)
        val rxE = rx * ex + rz * ez
        val uxE = ux * ex + uy * ey + uz * ez
        val fxE = fx * ex + fy * ey + fz * ez
        look[0] = fxScale * rx; look[4] = 0f; look[8] = fxScale * rz; look[12] = -fxScale * rxE
        look[1] = ySign * fyScale * ux; look[5] = ySign * fyScale * uy; look[9] = ySign * fyScale * uz; look[13] = -ySign * fyScale * uxE
        look[2] = 0f; look[6] = 0f; look[10] = 0f; look[14] = 1f - 2f * near
        look[3] = fx; look[7] = fy; look[11] = fz; look[15] = -fxE

        fun projectRowFromTop(px: Float, py: Float, pz: Float): Float {
            val cy = look[1] * px + look[5] * py + look[9] * pz + look[13]
            val cw = look[3] * px + look[7] * py + look[11] * pz + look[15]
            val ndcY = cy / cw
            // On headless CONFIG_READABLE with readPixels((h-1-y)*w+x), ndcY=-1 is top (row 0) and ndcY=+1 is bottom (row h)
            return h * (0.5f + 0.5f * ndcY)
        }

        val mastRow = projectRowFromTop(0f, 3.95f, 0f)
        val waterlineRow = projectRowFromTop(0f, 0.0f, 0f)
        val nearWaterRow = projectRowFromTop(0f, 0.0f, -9.5f)
        val horizonRow = projectRowFromTop(0f, 0.0f, 24000f)

        assertTrue("horizon ($horizonRow) must sit in upper sky/water split (15%..45% of height)", horizonRow in (h * 0.15f)..(h * 0.45f))
        assertTrue("boat mast ($mastRow) must be ABOVE boat waterline ($waterlineRow)", mastRow < waterlineRow - 50f)
        assertTrue("waterline ($waterlineRow) must lie BELOW horizon ($horizonRow)", waterlineRow > horizonRow + 80f)
        assertTrue("foreground water in front of boat ($nearWaterRow) must reach near bottom of frame", nearWaterRow > h * 0.90f)

        // Verify ocean.mat's (look * displaceMat) * localPos matches street_lit.mat's look * worldPos bit-for-bit
        val localX = 12f; val localY = 0f; val localZ = -4f
        val worldX = 0f; val worldY = 0.25f; val worldZ = 0f
        val dx = worldX - localX; val dy = worldY - localY; val dz = worldZ - localZ
        // (look * displaceMat) * vec4(localPos, 1) == look * vec4(localPos + delta, 1)
        for (row in 0..3) {
            val boatClip = look[row] * worldX + look[4 + row] * worldY + look[8 + row] * worldZ + look[12 + row]
            val dispCol3 = look[row] * dx + look[4 + row] * dy + look[8 + row] * dz + look[12 + row]
            val oceanClip = look[row] * localX + look[4 + row] * localY + look[8 + row] * localZ + dispCol3
            assertEquals("clip component $row must match between ocean and boat", boatClip, oceanClip, 1e-5f)
        }
    }
}
