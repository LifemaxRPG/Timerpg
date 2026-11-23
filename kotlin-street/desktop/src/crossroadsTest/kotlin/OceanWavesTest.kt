package com.lifemax.rpg.feature.habits.presentation.scene3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class OceanWavesTest {

    @Test
    fun heightAtMatchesDisplacedPointAcrossCalmAndRough() {
        val sample = OceanSample()
        val verify = OceanSample()
        for (spec in listOf(OceanWaves.CALM, OceanWaves.ROUGH)) {
            for (t in floatArrayOf(0f, 1.37f, 4.25f, 11.8f)) {
                for (x in floatArrayOf(-12.5f, 0f, 3.7f, 19.2f)) {
                    for (z in floatArrayOf(-8.3f, 0f, 5.4f, 14.1f)) {
                        OceanWaves.heightInto(x, z, t, spec, sample)

                        // Verify normal is unit length and points upward
                        val nLen = sqrt(sample.nx * sample.nx + sample.ny * sample.ny + sample.nz * sample.nz)
                        assertEquals("normal must be unit length", 1.0f, nLen, 1e-4f)
                        assertTrue("normal must point upward (ny=${sample.ny})", sample.ny > 0.35f)
                        assertTrue("jacobian must remain positive (j=${sample.jacobian})", sample.jacobian > 0.05f)

                        // Independent forward check: start from the undisplaced point and evaluate directly
                        OceanWaves.evalDisplacedInto(x, z, t, spec, verify)
                        // And verify heightInto at the displaced (x, z) recovers the exact same y
                        OceanWaves.heightInto(x + verify.dx, z + verify.dz, t, spec, sample)
                        assertEquals(
                            "heightInto(displaced.x, displaced.z) must recover exact displaced.y for roughness=${spec.roughness}",
                            verify.y,
                            sample.y,
                            2e-3f,
                        )
                        assertEquals("normal X must match", verify.nx, sample.nx, 5e-3f)
                        assertEquals("normal Y must match", verify.ny, sample.ny, 5e-3f)
                        assertEquals("normal Z must match", verify.nz, sample.nz, 5e-3f)
                    }
                }
            }
        }
    }

    @Test
    fun normalMatchesFiniteDifferenceOfHeightField() {
        val eps = 0.02f
        val c = OceanSample()
        val px = OceanSample()
        val mx = OceanSample()
        val pz = OceanSample()
        val mz = OceanSample()

        for (spec in listOf(OceanWaves.CALM, OceanWaves.ROUGH)) {
            val t = 3.14f
            for ((x, z) in listOf(0f to 0f, 4.2f to -3.1f, -6.5f to 7.8f)) {
                OceanWaves.heightInto(x, z, t, spec, c)
                OceanWaves.heightInto(x + eps, z, t, spec, px)
                OceanWaves.heightInto(x - eps, z, t, spec, mx)
                OceanWaves.heightInto(x, z + eps, t, spec, pz)
                OceanWaves.heightInto(x, z - eps, t, spec, mz)

                val dydx = (px.y - mx.y) / (2f * eps)
                val dydz = (pz.y - mz.y) / (2f * eps)
                val invLen = 1f / sqrt(dydx * dydx + 1f + dydz * dydz)
                val fdNx = -dydx * invLen
                val fdNy = invLen
                val fdNz = -dydz * invLen

                assertEquals("nx vs finite diff (roughness=${spec.roughness})", fdNx, c.nx, 1.5e-2f)
                assertEquals("ny vs finite diff (roughness=${spec.roughness})", fdNy, c.ny, 1.5e-2f)
                assertEquals("nz vs finite diff (roughness=${spec.roughness})", fdNz, c.nz, 1.5e-2f)
            }
        }
    }

    @Test
    fun presetsStayNonFoldingWithPositiveJacobian() {
        val sample = OceanSample()
        for (spec in listOf(OceanWaves.CALM, OceanWaves.ROUGH)) {
            assertTrue("folding index ${spec.foldingIndex} must be < 0.75", spec.foldingIndex < 0.75f)
            // Verify swells travel in the forward hemisphere (+/- 50 deg) with enough cross-swell spread
            // to sculpt staggered 3D mounds rather than flat parallel computer stripes.
            var minAlign = 1f
            for (w in spec.waves) {
                val align = w.dirX * spec.mainDirX + w.dirZ * spec.mainDirZ
                if (align < minAlign) minAlign = align
                assertTrue("wave must travel within 50 deg of main swell (dot=$align)", align > 0.64f)
            }
            assertTrue(
                "cross-swell directional spread must exist (minAlign=$minAlign < 0.90) to break horizontal stripes",
                minAlign < 0.90f,
            )
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
            assertTrue("min Jacobian $minJ must stay strictly positive (no self-folding)", minJ > 0.20f)
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
        val worldAtA = originA + 15 * 1.0f
        val worldAtC = originC + 7 * 1.0f
        assertEquals("shifted ring vertices must land on identical world coordinates", worldAtA, worldAtC, 1e-6f)
    }

    @Test
    fun heightIntoAllocatesZeroBytes() {
        val mx = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean ?: return
        if (!mx.isThreadAllocatedMemorySupported) return
        val sample = OceanSample()
        // Pre-warm both the wave evaluator and the scalar MXBean query so the measured window allocates 0 B
        repeat(4000) { i ->
            OceanWaves.heightInto(i * 0.3f, i * 0.5f, i * 0.02f, OceanWaves.ROUGH, sample)
        }
        mx.currentThreadAllocatedBytes
        val before = mx.currentThreadAllocatedBytes
        repeat(2000) { i ->
            OceanWaves.heightInto(i * 0.3f, i * 0.5f, i * 0.02f, OceanWaves.ROUGH, sample)
        }
        val delta = mx.currentThreadAllocatedBytes - before
        assertEquals("heightInto must allocate 0 bytes over 2000 calls (got $delta B)", 0L, delta)
    }

    @Test
    fun nestedRingMeshIsWatertightAndReachesHorizon() {
        val mesh = OceanGridMesh.build()
        assertTrue("vertex count ${mesh.vertexCount} must fit in 16-bit USHORT", mesh.vertexCount <= 65535)
        assertTrue("wave outer radius ${mesh.waveOuterRadius} must reach >= 5 km", mesh.waveOuterRadius >= 5000f)
        assertTrue("far horizon radius ${mesh.farHorizonRadius} must reach >= 20 km", mesh.farHorizonRadius >= 20000f)

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
        val ySign = -1f

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

        val localX = 12f; val localY = 0f; val localZ = -4f
        val worldX = 0f; val worldY = 0.25f; val worldZ = 0f
        val dx = worldX - localX; val dy = worldY - localY; val dz = worldZ - localZ
        for (row in 0..3) {
            val boatClip = look[row] * worldX + look[4 + row] * worldY + look[8 + row] * worldZ + look[12 + row]
            val dispCol3 = look[row] * dx + look[4 + row] * dy + look[8 + row] * dz + look[12 + row]
            val oceanClip = look[row] * localX + look[4 + row] * localY + look[8 + row] * localZ + dispCol3
            assertEquals("clip component $row must match between ocean and boat", boatClip, oceanClip, 1e-5f)
        }
    }

    @Test
    fun boatTiltIsCappedAt14DegOnRoughSeaWhileStillHeaving() {
        val sample = OceanSample()
        val headingRad = 0.38f
        val hx = sin(headingRad.toDouble()).toFloat()
        val hz = cos(headingRad.toDouble()).toFloat()
        val flatRx = hz
        val flatRz = -hx
        val halfL = 2.1f
        val halfW = 1.0f
        val tiltDamping = 0.55f
        val maxTiltDeg = 14.0f
        val maxSin = sin(maxTiltDeg * PI / 180.0).toFloat()
        val maxCos = cos(maxTiltDeg * PI / 180.0).toFloat()

        var maxObservedTiltDeg = 0f
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        for (step in 0 until 120) {
            val t = step * 0.15f
            OceanWaves.heightInto(hx * halfL, hz * halfL, t, OceanWaves.ROUGH, sample); val yBow = sample.y
            OceanWaves.heightInto(-hx * halfL, -hz * halfL, t, OceanWaves.ROUGH, sample); val yStern = sample.y
            OceanWaves.heightInto(flatRx * halfW, flatRz * halfW, t, OceanWaves.ROUGH, sample); val yStbd = sample.y
            OceanWaves.heightInto(-flatRx * halfW, -flatRz * halfW, t, OceanWaves.ROUGH, sample); val yPort = sample.y
            val avgY = 0.25f * (yBow + yStern + yStbd + yPort)
            if (avgY < minY) minY = avgY
            if (avgY > maxY) maxY = avgY

            val fdx = hx * (2f * halfL); val fdy = yBow - yStern; val fdz = hz * (2f * halfL)
            val rdx = flatRx * (2f * halfW); val rdy = yStbd - yPort; val rdz = flatRz * (2f * halfW)
            var nx = fdy * rdz - fdz * rdy
            var ny = fdz * rdx - fdx * rdz
            var nz = fdx * rdy - fdy * rdx
            val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
            nx /= nl; ny /= nl; nz /= nl

            var ux = nx * tiltDamping
            var uy = 1f + (ny - 1f) * tiltDamping
            var uz = nz * tiltDamping
            val horizLen = sqrt(ux * ux + uz * uz)
            val uLen = sqrt(ux * ux + uy * uy + uz * uz).coerceAtLeast(1e-6f)
            ux /= uLen; uy /= uLen; uz /= uLen
            val curSin = horizLen / uLen
            if (curSin > maxSin && horizLen > 1e-6f) {
                val scale = maxSin / curSin
                ux *= scale; uz *= scale; uy = maxCos
            }
            val tiltDeg = (acos(uy.coerceIn(-1f, 1f).toDouble()) * 180.0 / PI).toFloat()
            if (tiltDeg > maxObservedTiltDeg) maxObservedTiltDeg = tiltDeg
        }

        assertTrue("rough sea boat tilt ($maxObservedTiltDeg deg) must be capped <= 14.1 deg", maxObservedTiltDeg <= 14.1f)
        assertTrue("rough sea boat must still visibly heave (range=${maxY - minY} m > 1.0 m)", (maxY - minY) > 1.0f)
        assertTrue("rough sea boat must visibly rock (maxTilt=$maxObservedTiltDeg deg > 4.0 deg)", maxObservedTiltDeg > 4.0f)
    }

    @Test
    fun glintCoverageMatchesG002r2Limits() {
        // Exact CPU port of ocean.mat's glint stage on the G002-r1/r2 verification camera (0, 6, -20) -> (0, 1.8, 4).
        fun fract(x: Float): Float = x - floor(x)
        fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
        fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t
        fun hash21(px0: Float, py0: Float): Float {
            var px = fract(px0 * 123.34f)
            var py = fract(py0 * 456.21f)
            val d = px * (px + 45.32f) + py * (py + 45.32f)
            px += d; py += d
            return fract(px * py)
        }
        fun vnoise(px: Float, py: Float): Float {
            val ix = floor(px); val iy = floor(py)
            var fx = fract(px); var fy = fract(py)
            fx = fx * fx * (3f - 2f * fx)
            fy = fy * fy * (3f - 2f * fy)
            val a = hash21(ix, iy)
            val b = hash21(ix + 1f, iy)
            val c = hash21(ix, iy + 1f)
            val d = hash21(ix + 1f, iy + 1f)
            return mix(mix(a, b, fx), mix(c, d, fx), fy)
        }
        fun norm3(x: Float, y: Float, z: Float): FloatArray {
            val l = sqrt(x * x + y * y + z * z)
            return floatArrayOf(x / l, y / l, z / l)
        }

        val ex = 0f; val ey = 6.0f; val ez = -20.0f
        val tx = 0f; val ty = 1.8f; val tz = 4.0f
        val w = 240; val h = 135
        val aspect = w.toFloat() / h.toFloat()
        val tanHalfFov = tan(42.0 * PI / 360.0).toFloat()

        var fx = tx - ex; var fy = ty - ey; var fz = tz - ez
        val fl = sqrt(fx * fx + fy * fy + fz * fz)
        fx /= fl; fy /= fl; fz /= fl
        var rx = -fz; var rz = fx
        val rl = sqrt(rx * rx + rz * rz)
        rx /= rl; rz /= rl
        val ux = -rz * fy; val uy = rz * fx - rx * fz; val uz = rx * fy

        data class Case(val label: String, val spec: OceanSpec, val lightDir: FloatArray, val glintStr: Float, val night: Float, val minPct: Float, val maxPct: Float)
        val cases = listOf(
            Case("day_calm", OceanWaves.CALM, norm3(0.20f, 0.48f, 0.85f), 0.85f, 0.00f, 1.0f, 7.5f),
            Case("day_rough", OceanWaves.ROUGH, norm3(0.20f, 0.48f, 0.85f), 0.85f, 0.00f, 1.0f, 7.5f),
            Case("dusk_calm", OceanWaves.CALM, norm3(0.14f, 0.096f, 0.985f), 1.00f, 0.28f, 3.0f, 18.0f),
            Case("dusk_rough", OceanWaves.ROUGH, norm3(0.14f, 0.096f, 0.985f), 1.00f, 0.28f, 2.5f, 18.0f),
            Case("night_calm", OceanWaves.CALM, norm3(-0.14f, 0.118f, 0.983f), 0.92f, 1.00f, 1.5f, 10.0f),
            Case("night_rough", OceanWaves.ROUGH, norm3(-0.14f, 0.118f, 0.983f), 0.92f, 1.00f, 1.5f, 10.0f),
        )

        val sample = OceanSample()
        val t = 3.2f
        val report = StringBuilder()

        for (c in cases) {
            val lx = c.lightDir[0]; val ly = c.lightDir[1]; val lz = c.lightDir[2]
            val lHlen = sqrt(lx * lx + lz * lz).coerceAtLeast(1e-4f)
            val lHx = lx / lHlen; val lHz = lz / lHlen
            val swellX = c.spec.mainDirX; val swellZ = c.spec.mainDirZ
            val perpX = -swellZ; val perpZ = swellX

            var waterPx = 0
            var glintPx = 0
            var maxRunFrac = 0f

            for (py in 0 until h) {
                val ndcY = 1f - 2f * ((py + 0.5f) / h.toFloat())
                var curRun = 0
                var maxRunRow = 0
                for (px in 0 until w) {
                    val ndcX = 2f * ((px + 0.5f) / w.toFloat()) - 1f
                    var dx = fx + rx * (ndcX * aspect * tanHalfFov) + ux * (ndcY * tanHalfFov)
                    var dy = fy + uy * (ndcY * tanHalfFov)
                    var dz = fz + rz * (ndcX * aspect * tanHalfFov) + uz * (ndcY * tanHalfFov)
                    val dl = sqrt(dx * dx + dy * dy + dz * dz)
                    dx /= dl; dy /= dl; dz /= dl
                    if (dy >= -1e-4f) {
                        curRun = 0
                        continue
                    }
                    val hitT = -ey / dy
                    if (hitT > 24000f) {
                        curRun = 0
                        continue
                    }
                    waterPx++
                    val wx = ex + dx * hitT
                    val wz = ez + dz * hitT
                    val dHx = wx - ex; val dHz = wz - ez
                    val dist = sqrt(dHx * dHx + dHz * dHz).coerceAtLeast(1e-4f)
                    val viewHx = dHx / dist; val viewHz = dHz / dist

                    // Evaluate Gerstner normal with same farFade as ocean.mat
                    val farFade = 1f - smoothstep(140f, 2200f, dist)
                    var nx = 0f; var ny = 1f; var nz = 0f
                    var wy = 0f
                    if (farFade > 0.001f && dist < 1400f) {
                        OceanWaves.heightInto(wx, wz, t, c.spec, sample)
                        wy = sample.y * farFade
                        nx = sample.nx * farFade
                        ny = mix(1f, sample.ny, farFade)
                        nz = sample.nz * farFade
                        val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
                        nx /= nl; ny /= nl; nz /= nl
                    }

                    val vx0 = ex - wx; val vy0 = ey - wy; val vz0 = ez - wz
                    val vl = sqrt(vx0 * vx0 + vy0 * vy0 + vz0 * vz0).coerceAtLeast(1e-6f)
                    val vx = vx0 / vl; val vy = vy0 / vl; val vz = vz0 / vl

                    val along = wx * swellX + wz * swellZ
                    val across = wx * perpX + wz * perpZ
                    val azimForward = viewHx * lHx + viewHz * lHz
                    var isGlint = false
                    if (c.glintStr > 0.01f && azimForward > 0.5f) {
                        val nightOnly = smoothstep(0.5f, 1.0f, c.night)
                        val elevScale = (1f - (ly - 0.095f) * 1.90f).coerceIn(0.26f, 1.0f) * mix(1.0f, 0.66f, nightOnly)
                        val azimErr = abs(viewHx * lHz - viewHz * lHx)
                        val distT = smoothstep(14f, 480f, dist)
                        val baseHalf = mix(0.42f, 0.052f, distT) * mix(0.48f, 1.0f, elevScale) * c.glintStr
                        val ragged = 0.42f + 0.85f * vnoise(along * 0.22f - t * 0.35f, across * 0.09f + 2.1f)
                        val funnel = 1f - smoothstep(baseHalf * 0.25f, baseHalf * ragged, azimErr)
                        if (funnel > 0.05f) {
                            var hx0 = vx + lx; var hy0 = vy + ly; var hz0 = vz + lz
                            val hl = sqrt(hx0 * hx0 + hy0 * hy0 + hz0 * hz0).coerceAtLeast(1e-6f)
                            hx0 /= hl; hy0 /= hl; hz0 /= hl
                            val ndoth = max(nx * hx0 + ny * hy0 + nz * hz0, 0f)
                            val facetBase = mix(0.986f, 0.940f, elevScale * (0.55f + 0.45f * distT))
                            val facetThresh = facetBase - 0.048f * c.spec.roughness
                            val dashA = vnoise(across * 1.15f + t * 0.28f, along * 2.85f - t * 2.50f)
                            val dashB = vnoise(across * 2.65f - t * 0.45f, along * 5.60f - t * 4.10f)
                            val dashVal = dashA * 0.55f + dashB * 0.45f
                            val dashGate = mix(0.60f, 0.510f, elevScale) - 0.068f * distT * elevScale
                            val facetHit = smoothstep(facetThresh - 0.012f, facetThresh + 0.010f, ndoth)
                            val lowHorizon = smoothstep(160f, 760f, dist) * smoothstep(0.24f, 0.08f, ly) *
                                (1f - smoothstep(0.012f, 0.044f * ragged, azimErr))
                            val hit = max(facetHit * funnel, lowHorizon * mix(0.92f, 0.62f, nightOnly))
                            if (hit > 0.30f && dashVal > dashGate - 0.09f * lowHorizon) {
                                isGlint = true
                            }
                        }
                    }

                    if (isGlint) {
                        glintPx++
                        curRun++
                        if (curRun > maxRunRow) maxRunRow = curRun
                    } else {
                        curRun = 0
                    }
                }
                val rowFrac = maxRunRow.toFloat() / w.toFloat()
                if (rowFrac > maxRunFrac) maxRunFrac = rowFrac
            }

            val pct = 100f * glintPx.toFloat() / waterPx.coerceAtLeast(1).toFloat()
            report.append("${c.label}=%.2f%% (maxRowRun=%.1f%%) ".format(pct, maxRunFrac * 100f))
            println("GLINT_ORACLE ${c.label}: belowHorizon=%.2f%% maxRowRun=%.1f%%".format(pct, maxRunFrac * 100f))
            assertTrue(
                "${c.label} glint " + "%.2f%%".format(pct) + " outside target [${c.minPct}%, ${c.maxPct}%] ($report)",
                pct in c.minPct..c.maxPct,
            )
            assertTrue(
                "${c.label} max horizontal glint run %.1f%% must be < 22%% (no solid sheet/rectangle)".format(maxRunFrac * 100f),
                maxRunFrac < 0.22f,
            )
        }
    }
}
