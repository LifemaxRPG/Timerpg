package com.lifemax.rpg.feature.habits.presentation.scene3d

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Single Gerstner wave component shared between the GPU vertex shader (`ocean.mat`) and CPU buoyancy queries.
 *
 * Wave equation for undisturbed world horizontal point `(x0, z0)` at time `t`:
 *   k = 2*pi / wavelength
 *   phase = k * (dirX * x0 + dirZ * z0) - omega * t + phaseOffset
 *   dx = steepness * amplitude * dirX * cos(phase)
 *   dy = amplitude * sin(phase)
 *   dz = steepness * amplitude * dirZ * cos(phase)
 */
data class GerstnerWave(
    val dirX: Float,
    val dirZ: Float,
    val wavelength: Float,
    val amplitude: Float,
    val steepness: Float,
    val speedScale: Float = 1.0f,
    val phaseOffset: Float = 0f,
) {
    /** Wave number `k = 2*pi / L` (rad/m). */
    val k: Float = (TWO_PI / wavelength.toDouble()).toFloat()

    /** Deep-water dispersion angular frequency `omega = sqrt(g * k) * speedScale` (rad/s). */
    val omega: Float = (sqrt(GRAVITY * k.toDouble()) * speedScale).toFloat()

    companion object {
        const val TWO_PI: Double = 2.0 * PI
        const val GRAVITY: Double = 9.81

        /** Creates a wave with unit-normalized horizontal travel direction `(dx, dz)`. */
        fun of(
            dx: Float,
            dz: Float,
            wavelength: Float,
            amplitude: Float,
            steepness: Float,
            speedScale: Float = 1.0f,
            phaseOffset: Float = 0f,
        ): GerstnerWave {
            val len = sqrt((dx * dx + dz * dz).toDouble()).coerceAtLeast(1e-6)
            return GerstnerWave(
                dirX = (dx / len).toFloat(),
                dirZ = (dz / len).toFloat(),
                wavelength = wavelength,
                amplitude = amplitude,
                steepness = steepness,
                speedScale = speedScale,
                phaseOffset = phaseOffset,
            )
        }
    }
}

/** Collection of 1..6 Gerstner waves plus surface look tuning shared by CPU and GPU. */
class OceanSpec(
    val waves: List<GerstnerWave>,
    /** 0 = glassy calm, 1 = stormy rough sea (controls whitecap density, wind streaks, and horizon bumps). */
    val roughness: Float,
    /** Whitecap onset threshold in [0, 1] (lower = more scattered whitecaps on crests). */
    val foamThreshold: Float,
) {
    init {
        require(waves.isNotEmpty() && waves.size <= MAX_WAVES) {
            "OceanSpec requires 1..$MAX_WAVES waves, got ${waves.size}"
        }
    }

    /** Sum of wave amplitudes (m), used by the shader to normalize 3D wave height. */
    val maxAmplitude: Float = waves.fold(0f) { acc, w -> acc + w.amplitude }.coerceAtLeast(0.05f)

    /** Sum of `k * Q * A` across all waves; must remain < 1.0 to avoid self-intersecting loops. */
    val foldingIndex: Float = waves.fold(0f) { acc, w -> acc + w.k * w.steepness * w.amplitude }

    /** Amplitude-weighted dominant swell travel direction X (unit vector with [mainDirZ]). */
    val mainDirX: Float

    /** Amplitude-weighted dominant swell travel direction Z (unit vector with [mainDirX]). */
    val mainDirZ: Float

    init {
        var sx = 0.0
        var sz = 0.0
        for (w in waves) {
            sx += w.dirX * w.amplitude
            sz += w.dirZ * w.amplitude
        }
        val sl = sqrt(sx * sx + sz * sz)
        if (sl > 1e-6) {
            mainDirX = (sx / sl).toFloat()
            mainDirZ = (sz / sl).toFloat()
        } else {
            mainDirX = 0f
            mainDirZ = -1f
        }
    }

    companion object {
        const val MAX_WAVES = 6
    }
}

/**
 * Result of sampling the ocean surface: displaced world height [y], unit surface normal ([nx], [ny], [nz]),
 * horizontal Gerstner displacement ([dx], [dz]), and horizontal Jacobian determinant [jacobian] (drops toward 0
 * at sharp wave crests).
 */
data class OceanSample(
    var y: Float = 0f,
    var nx: Float = 0f,
    var ny: Float = 1f,
    var nz: Float = 0f,
    var dx: Float = 0f,
    var dz: Float = 0f,
    var jacobian: Float = 1f,
    var crest: Float = 0f,
)

/**
 * Pure-Kotlin Gerstner wave evaluator matching `kotlin-street/filament/materials/ocean.mat` vertex-for-vertex.
 *
 * Uses a natural directional wave spectrum (primary swell along `-Z` interfered with oblique cross-swells at
 * ±24°..±46° and incommensurate wavelengths/dispersion speeds) so 3D waves break into organic, staggered,
 * rounded mounds that travel and morph dynamically over time rather than unbroken horizontal stripes.
 */
object OceanWaves {
    /**
     * Calm open sea: 6 gentle swells with natural directional spread (wavelengths 2.8..14.0 m,
     * `maxAmplitude = 0.456 m`, `foldingIndex ~= 0.14`).
     */
    val CALM: OceanSpec = OceanSpec(
        waves = listOf(
            GerstnerWave.of(0.00f, -1.00f, wavelength = 14.0f, amplitude = 0.160f, steepness = 0.42f, speedScale = 0.92f, phaseOffset = 0.2f),
            GerstnerWave.of(0.41f, -0.91f, wavelength = 9.8f, amplitude = 0.110f, steepness = 0.40f, speedScale = 0.98f, phaseOffset = 1.7f),
            GerstnerWave.of(-0.48f, -0.87f, wavelength = 7.4f, amplitude = 0.080f, steepness = 0.38f, speedScale = 1.04f, phaseOffset = 3.1f),
            GerstnerWave.of(0.66f, -0.75f, wavelength = 5.3f, amplitude = 0.052f, steepness = 0.34f, speedScale = 1.09f, phaseOffset = 4.6f),
            GerstnerWave.of(-0.69f, -0.72f, wavelength = 3.9f, amplitude = 0.034f, steepness = 0.30f, speedScale = 1.15f, phaseOffset = 0.9f),
            GerstnerWave.of(0.26f, -0.97f, wavelength = 2.8f, amplitude = 0.020f, steepness = 0.26f, speedScale = 1.22f, phaseOffset = 2.4f),
        ),
        roughness = 0.20f,
        foamThreshold = 0.62f,
    )

    /**
     * Rough open sea: 6 bold rolling 3D swells (wavelengths 3.4..19.5 m, `maxAmplitude = 1.91 m`, ~3.2 m peak-to-trough
     * near waves that visibly overlap water behind them and rise around the boat) while remaining strictly non-folding
     * (`foldingIndex ~= 0.65 < 0.75`).
     */
    val ROUGH: OceanSpec = OceanSpec(
        waves = listOf(
            GerstnerWave.of(0.00f, -1.00f, wavelength = 19.5f, amplitude = 0.800f, steepness = 0.66f, speedScale = 1.02f, phaseOffset = 0.4f),
            GerstnerWave.of(0.42f, -0.91f, wavelength = 13.6f, amplitude = 0.480f, steepness = 0.62f, speedScale = 1.07f, phaseOffset = 1.9f),
            GerstnerWave.of(-0.52f, -0.86f, wavelength = 9.6f, amplitude = 0.300f, steepness = 0.58f, speedScale = 1.12f, phaseOffset = 3.5f),
            GerstnerWave.of(0.64f, -0.77f, wavelength = 6.7f, amplitude = 0.170f, steepness = 0.52f, speedScale = 1.18f, phaseOffset = 5.1f),
            GerstnerWave.of(-0.72f, -0.69f, wavelength = 4.8f, amplitude = 0.105f, steepness = 0.46f, speedScale = 1.24f, phaseOffset = 0.8f),
            GerstnerWave.of(0.22f, -0.97f, wavelength = 3.4f, amplitude = 0.058f, steepness = 0.42f, speedScale = 1.30f, phaseOffset = 2.7f),
        ),
        roughness = 0.90f,
        foamThreshold = 0.38f,
    )

    /**
     * Snaps a camera coordinate to integer multiples of [snapStep] so the ocean grid's vertices always land on
     * identical world-space coordinates and never swim as the camera moves.
     */
    fun snapGridOrigin(cameraCoord: Float, snapStep: Float): Float {
        require(snapStep > 0f) { "snapStep must be positive" }
        return floor((cameraCoord / snapStep).toDouble()).toFloat() * snapStep
    }

    /** Allocates and returns the exact displaced wave sample at undisturbed grid position `(x0, z0)`. */
    fun evalDisplaced(
        x0: Float,
        z0: Float,
        t: Float,
        spec: OceanSpec = CALM,
        seaLevelY: Float = 0f,
    ): OceanSample = OceanSample().also { evalDisplacedInto(x0, z0, t, spec, it, seaLevelY) }

    /**
     * Zero-allocation version of [evalDisplaced], mirroring `ocean.mat`'s `addGerstner` vertex loop verbatim
     * (in the unattenuated near-field zone).
     */
    fun evalDisplacedInto(
        x0: Float,
        z0: Float,
        t: Float,
        spec: OceanSpec,
        out: OceanSample,
        seaLevelY: Float = 0f,
    ) {
        var dispX = 0f
        var dispY = 0f
        var dispZ = 0f
        var dXx = 1f; var dXy = 0f; var dXz = 0f
        var dZx = 0f; var dZy = 0f; var dZz = 1f

        val waves = spec.waves
        for (i in 0 until waves.size) {
            val w = waves[i]
            val amp = w.amplitude
            if (w.wavelength <= 1e-4f || amp <= 0f) continue
            val k = w.k
            val q = w.steepness
            val phase = k * (w.dirX * x0 + w.dirZ * z0) - w.omega * t + w.phaseOffset
            val s = sin(phase.toDouble()).toFloat()
            val c = cos(phase.toDouble()).toFloat()

            val qa = q * amp
            val ka = k * amp
            val qka = q * ka

            dispX += qa * w.dirX * c
            dispY += amp * s
            dispZ += qa * w.dirZ * c

            dXx -= qka * w.dirX * w.dirX * s
            dXy += ka * w.dirX * c
            dXz -= qka * w.dirX * w.dirZ * s

            dZx -= qka * w.dirX * w.dirZ * s
            dZy += ka * w.dirZ * c
            dZz -= qka * w.dirZ * w.dirZ * s
        }

        // Right-handed Y-up normal: cross(dZ, dX) = (+Z) x (+X) = +Y
        val nxRaw = dZy * dXz - dZz * dXy
        val nyRaw = dZz * dXx - dZx * dXz
        val nzRaw = dZx * dXy - dZy * dXx
        val invLen = 1f / sqrt((nxRaw * nxRaw + nyRaw * nyRaw + nzRaw * nzRaw).toDouble()).toFloat().coerceAtLeast(1e-6f)

        val jac = dXx * dZz - dXz * dZx
        out.y = seaLevelY + dispY
        out.nx = nxRaw * invLen
        out.ny = nyRaw * invLen
        out.nz = nzRaw * invLen
        out.dx = dispX
        out.dz = dispZ
        out.jacobian = jac
        out.crest = (1f - jac).coerceIn(0f, 1f)
    }

    /**
     * Returns wave height and normal at a fixed world horizontal coordinate `(x, z)` at time `t` (for Jolt buoyancy
     * and floating boats), inverting the Gerstner horizontal shift `(x0 + dx(x0, z0) = x, z0 + dz(x0, z0) = z)`.
     */
    fun height(
        x: Float,
        z: Float,
        t: Float,
        spec: OceanSpec = CALM,
        seaLevelY: Float = 0f,
        iterations: Int = 6,
    ): OceanSample = OceanSample().also { heightInto(x, z, t, spec, it, seaLevelY, iterations) }

    /** Zero-allocation version of [height] suitable for per-frame physics & buoyancy loops. */
    fun heightInto(
        x: Float,
        z: Float,
        t: Float,
        spec: OceanSpec,
        out: OceanSample,
        seaLevelY: Float = 0f,
        iterations: Int = 6,
    ) {
        var x0 = x
        var z0 = z
        for (iter in 0 until iterations) {
            evalDisplacedInto(x0, z0, t, spec, out, seaLevelY)
            x0 = x - out.dx
            z0 = z - out.dz
        }
        evalDisplacedInto(x0, z0, t, spec, out, seaLevelY)
    }
}

/**
 * Watertight conformal nested-ring ocean mesh generator (pure Kotlin so topology & horizon reach can be
 * verified directly by JVM unit tests without requiring native Filament).
 */
class OceanGridMesh(
    val positionsX: FloatArray,
    val positionsZ: FloatArray,
    val indices: IntArray,
    val ringCells: Int,
    val baseCellSize: Float,
    val ringCount: Int,
    val waveOuterRadius: Float,
    val farHorizonRadius: Float,
    val snapStep: Float,
) {
    val vertexCount: Int get() = positionsX.size
    val triangleCount: Int get() = indices.size / 3

    companion object {
        fun build(
            ringCells: Int = 56,
            baseCellSize: Float = 1.0f,
            ringCount: Int = 9,
            farHorizonRadius: Float = 24000f,
        ): OceanGridMesh {
            require(ringCells >= 8 && ringCells % 4 == 0) {
                "ringCells must be >= 8 and divisible by 4, got $ringCells"
            }
            require(baseCellSize > 0f) { "baseCellSize must be positive" }
            require(ringCount >= 1) { "ringCount must be >= 1" }

            val halfN = ringCells / 2
            val quarterN = ringCells / 4
            val waveOuter = halfN * baseCellSize * (1 shl (ringCount - 1))
            require(farHorizonRadius > waveOuter) {
                "farHorizonRadius ($farHorizonRadius) must exceed outermost wave ring ($waveOuter)"
            }

            val vertMap = HashMap<Long, Int>(32768)
            val vx = ArrayList<Float>(32768)
            val vz = ArrayList<Float>(32768)
            val idx = ArrayList<Int>(150000)

            fun getVert(x: Float, z: Float): Int {
                val qx = round(x * 16.0).toLong()
                val qz = round(z * 16.0).toLong()
                val key = (qx shl 32) xor (qz and 0xFFFFFFFFL)
                val existing = vertMap[key]
                if (existing != null) return existing
                val id = vx.size
                vx += x
                vz += z
                vertMap[key] = id
                return id
            }

            fun tri(a: Int, b: Int, c: Int) {
                idx += a
                idx += b
                idx += c
            }

            for (r in 0 until ringCount) {
                val cell = baseCellSize * (1 shl r)
                val stitchOuter = r < ringCount - 1
                val holeLo = quarterN
                val holeHi = ringCells - quarterN

                fun v(ix: Int, iz: Int): Int = getVert((ix - halfN) * cell, (iz - halfN) * cell)

                val minC = if (stitchOuter) 1 else 0
                val maxC = if (stitchOuter) ringCells - 1 else ringCells

                for (iz in minC until maxC) {
                    for (ix in minC until maxC) {
                        if (r > 0 && ix in holeLo until holeHi && iz in holeLo until holeHi) continue
                        val a = v(ix, iz)
                        val b = v(ix + 1, iz)
                        val c = v(ix, iz + 1)
                        val d = v(ix + 1, iz + 1)
                        tri(a, c, b)
                        tri(b, c, d)
                    }
                }

                if (stitchOuter) {
                    val n = ringCells
                    var i = 2
                    while (i <= n - 4) {
                        run {
                            val a = v(i, 0); val c = v(i + 2, 0)
                            val d = v(i, 1); val e = v(i + 1, 1); val f = v(i + 2, 1)
                            tri(a, d, e); tri(a, e, c); tri(c, e, f)
                        }
                        run {
                            val a = v(i, n); val c = v(i + 2, n)
                            val d = v(i, n - 1); val e = v(i + 1, n - 1); val f = v(i + 2, n - 1)
                            tri(d, a, e); tri(e, a, c); tri(e, c, f)
                        }
                        run {
                            val a = v(0, i); val c = v(0, i + 2)
                            val d = v(1, i); val e = v(1, i + 1); val f = v(1, i + 2)
                            tri(a, e, d); tri(a, c, e); tri(c, f, e)
                        }
                        run {
                            val a = v(n, i); val c = v(n, i + 2)
                            val d = v(n - 1, i); val e = v(n - 1, i + 1); val f = v(n - 1, i + 2)
                            tri(d, e, a); tri(e, c, a); tri(e, f, c)
                        }
                        i += 2
                    }

                    run {
                        val c00 = v(0, 0); val c20 = v(2, 0); val c02 = v(0, 2)
                        val i11 = v(1, 1); val i21 = v(2, 1); val i12 = v(1, 2)
                        tri(c00, i11, c20); tri(c20, i11, i21)
                        tri(c00, c02, i11); tri(c02, i12, i11)
                    }
                    run {
                        val c00 = v(n, 0); val c20 = v(n - 2, 0); val c02 = v(n, 2)
                        val i11 = v(n - 1, 1); val i21 = v(n - 2, 1); val i12 = v(n - 1, 2)
                        tri(c20, i11, c00); tri(i21, i11, c20)
                        tri(c00, i11, c02); tri(c02, i11, i12)
                    }
                    run {
                        val c00 = v(0, n); val c20 = v(2, n); val c02 = v(0, n - 2)
                        val i11 = v(1, n - 1); val i21 = v(2, n - 1); val i12 = v(1, n - 2)
                        tri(c00, c20, i11); tri(c20, i21, i11)
                        tri(c02, c00, i11); tri(i12, c02, i11)
                    }
                    run {
                        val c00 = v(n, n); val c20 = v(n - 2, n); val c02 = v(n, n - 2)
                        val i11 = v(n - 1, n - 1); val i21 = v(n - 2, n - 1); val i12 = v(n - 1, n - 2)
                        tri(c20, c00, i11); tri(i21, c20, i11)
                        tri(c00, c02, i11); tri(c02, i12, i11)
                    }
                }
            }

            val outerCell = baseCellSize * (1 shl (ringCount - 1))
            val farStep = (2f * farHorizonRadius) / ringCells
            fun innerEdge(ix: Int, iz: Int): Int = getVert((ix - halfN) * outerCell, (iz - halfN) * outerCell)
            fun farEdge(ix: Int, iz: Int): Int = getVert(-farHorizonRadius + ix * farStep, -farHorizonRadius + iz * farStep)

            for (i in 0 until ringCells) {
                run {
                    val i0 = innerEdge(i, 0); val i1 = innerEdge(i + 1, 0)
                    val f0 = farEdge(i, 0); val f1 = farEdge(i + 1, 0)
                    tri(f0, i0, f1); tri(f1, i0, i1)
                }
                run {
                    val i0 = innerEdge(i, ringCells); val i1 = innerEdge(i + 1, ringCells)
                    val f0 = farEdge(i, ringCells); val f1 = farEdge(i + 1, ringCells)
                    tri(i0, f0, i1); tri(i1, f0, f1)
                }
                run {
                    val i0 = innerEdge(0, i); val i1 = innerEdge(0, i + 1)
                    val f0 = farEdge(0, i); val f1 = farEdge(0, i + 1)
                    tri(f0, f1, i0); tri(i0, f1, i1)
                }
                run {
                    val i0 = innerEdge(ringCells, i); val i1 = innerEdge(ringCells, i + 1)
                    val f0 = farEdge(ringCells, i); val f1 = farEdge(ringCells, i + 1)
                    tri(i0, i1, f0); tri(f0, i1, f1)
                }
            }

            val px = FloatArray(vx.size) { vx[it] }
            val pz = FloatArray(vz.size) { vz[it] }
            val indArr = IntArray(idx.size) { idx[it] }
            val snapStep = baseCellSize * 8f

            return OceanGridMesh(
                positionsX = px,
                positionsZ = pz,
                indices = indArr,
                ringCells = ringCells,
                baseCellSize = baseCellSize,
                ringCount = ringCount,
                waveOuterRadius = waveOuter,
                farHorizonRadius = farHorizonRadius,
                snapStep = snapStep,
            )
        }
    }
}
