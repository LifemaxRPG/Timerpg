package com.lifemax.rpg.feature.habits.presentation.scene3d

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Single Gerstner wave component matching `kotlin-street/filament/materials/ocean.mat`.
 *
 * Units are metres and seconds (1 world unit = 1 metre, deep-water gravity `g = 9.81 m/s^2`), ready for Jolt buoyancy.
 * Direction `(dirX, dirZ)` is normalized on construction.
 */
class GerstnerWave(
    dirX: Float,
    dirZ: Float,
    /** Crest-to-crest wavelength in metres (> 0). */
    val wavelength: Float,
    /** Half peak-to-trough vertical wave height in metres (>= 0). */
    val amplitude: Float,
    /** Gerstner horizontal steepness multiplier Q (>= 0; total `sum(k * Q * A)` must stay < 1 to avoid loops). */
    val steepness: Float,
    /** Angular speed in rad/s (defaults to deep-water dispersion `sqrt(g * 2*PI / wavelength)`). */
    val omega: Float = sqrt(GRAVITY * (TWO_PI_F / max(wavelength, 0.001f))),
    /** Phase offset in radians. */
    val phaseOffset: Float = 0f,
) {
    val dirX: Float
    val dirZ: Float

    init {
        val len = sqrt(dirX * dirX + dirZ * dirZ)
        if (len > 1e-6f) {
            this.dirX = dirX / len
            this.dirZ = dirZ / len
        } else {
            this.dirX = 1f
            this.dirZ = 0f
        }
    }

    /** Wave number `k = 2*PI / wavelength`. */
    val k: Float get() = TWO_PI_F / wavelength

    companion object {
        const val GRAVITY = 9.81f
        const val TWO_PI_F = (2.0 * PI).toFloat()
    }
}

/**
 * A complete sea-state wave spectrum (up to [MAX_WAVES] = 6 Gerstner components) plus roughness scalar (`0` calm .. `1`
 * rough) used identically by CPU buoyancy ([OceanWaves]) and the Filament shader (`ocean.mat`).
 */
class OceanSpec(
    val name: String,
    val waves: Array<GerstnerWave>,
    /** 0 = glassy calm, 1 = rough open sea (scales crest foam response in the shader). */
    val roughness: Float,
    /** Crest foam threshold in the fragment shader (`0..1`, higher = sparser foam). */
    val foamThreshold: Float,
) {
    init {
        require(waves.size <= MAX_WAVES) { "ocean.mat supports at most $MAX_WAVES Gerstner waves (got ${waves.size})" }
    }

    /** Sum of wave amplitudes (metres); bounds maximum vertical displacement `[-maxAmplitude, +maxAmplitude]`. */
    val maxAmplitude: Float = waves.fold(0f) { acc, w -> acc + w.amplitude }.coerceAtLeast(0.05f)

    /** Sum of `k * Q * A` across all components; strictly `< 1` guarantees non-folding, positive-Jacobian crests. */
    val totalSteepnessProduct: Float = waves.fold(0f) { acc, w -> acc + w.k * w.steepness * w.amplitude }

    companion object {
        const val MAX_WAVES = 6
    }
}

/**
 * Mutable ocean surface sample written by [OceanWaves.evalDisplacedInto] and [OceanWaves.heightInto] (zero per-frame
 * heap allocations on the game/physics thread).
 */
class OceanSample(
    /** Displaced world X coordinate (metres). */
    var x: Float = 0f,
    /** Displaced surface height Y (metres above sea level). */
    var y: Float = 0f,
    /** Displaced world Z coordinate (metres). */
    var z: Float = 0f,
    /** Unit surface normal X. */
    var nx: Float = 0f,
    /** Unit surface normal Y (> 0, pointing skyward). */
    var ny: Float = 1f,
    /** Unit surface normal Z. */
    var nz: Float = 0f,
    /** Horizontal Jacobian determinant (`1` flat, drops towards `0` at sharp crests). */
    var jacobian: Float = 1f,
    /** Crest compression metric `clamp(1 - jacobian, 0, 1)`. */
    var crest: Float = 0f,
)

/**
 * CPU-side Gerstner wave evaluator sharing the exact formula with `kotlin-street/filament/materials/ocean.mat`.
 *
 * - [evalDisplaced] / [evalDisplacedInto] evaluates the exact vertex shader displacement and analytical normal at
 *   resting grid coordinates `(x0, z0)` and time `t`.
 * - [height] / [heightInto] solves the horizontal Gerstner shift (fixed-point inversion) to return the true wave
 *   height and unit surface normal directly at world coordinates `(x, z)` for boat / Jolt buoyancy queries.
 * - [snapGridOrigin] snaps the ocean grid origin to integer steps of `cellSize` around the camera so vertices stay
 *   locked to world coordinates and never swim as the camera moves.
 */
object OceanWaves {
    private const val TWO_PI = 6.28318530718f

    /** Calm open sea: gentle rolling swells, minimal steepness, sparse sunlit crests. */
    val CALM = OceanSpec(
        name = "calm",
        waves = arrayOf(
            GerstnerWave(dirX = 0.88f, dirZ = 0.47f, wavelength = 28.0f, amplitude = 0.24f, steepness = 0.28f, phaseOffset = 0.3f),
            GerstnerWave(dirX = 0.42f, dirZ = -0.91f, wavelength = 17.5f, amplitude = 0.16f, steepness = 0.26f, phaseOffset = 1.7f),
            GerstnerWave(dirX = -0.76f, dirZ = 0.65f, wavelength = 11.2f, amplitude = 0.10f, steepness = 0.24f, phaseOffset = 2.9f),
            GerstnerWave(dirX = 0.96f, dirZ = -0.28f, wavelength = 6.8f, amplitude = 0.06f, steepness = 0.22f, phaseOffset = 0.8f),
            GerstnerWave(dirX = -0.25f, dirZ = -0.97f, wavelength = 4.3f, amplitude = 0.035f, steepness = 0.20f, phaseOffset = 4.1f),
            GerstnerWave(dirX = 0.61f, dirZ = 0.79f, wavelength = 2.6f, amplitude = 0.02f, steepness = 0.18f, phaseOffset = 5.2f),
        ),
        roughness = 0.15f,
        foamThreshold = 0.66f,
    )

    /** Rough open sea: tall trade-wind swells + cross-chop with crisp dithered whitecaps (non-folding: sum kQA < 0.75). */
    val ROUGH = OceanSpec(
        name = "rough",
        waves = arrayOf(
            GerstnerWave(dirX = 0.92f, dirZ = 0.39f, wavelength = 32.0f, amplitude = 1.05f, steepness = 0.55f, phaseOffset = 0.2f),
            GerstnerWave(dirX = 0.55f, dirZ = -0.84f, wavelength = 21.0f, amplitude = 0.68f, steepness = 0.52f, phaseOffset = 1.4f),
            GerstnerWave(dirX = -0.68f, dirZ = 0.73f, wavelength = 13.5f, amplitude = 0.42f, steepness = 0.48f, phaseOffset = 2.6f),
            GerstnerWave(dirX = 0.97f, dirZ = -0.24f, wavelength = 8.4f, amplitude = 0.24f, steepness = 0.44f, phaseOffset = 3.8f),
            GerstnerWave(dirX = -0.34f, dirZ = -0.94f, wavelength = 5.2f, amplitude = 0.14f, steepness = 0.40f, phaseOffset = 0.9f),
            GerstnerWave(dirX = 0.66f, dirZ = 0.75f, wavelength = 3.2f, amplitude = 0.08f, steepness = 0.36f, phaseOffset = 4.9f),
        ),
        roughness = 0.95f,
        foamThreshold = 0.34f,
    )

    /**
     * Snaps a world coordinate [camCoord] down to an integer multiple of [cellSize] so the moving ocean grid stays
     * phase-locked to world space (no vertex swimming).
     */
    fun snapGridOrigin(camCoord: Float, cellSize: Float): Float {
        require(cellSize > 0f) { "cellSize must be positive" }
        return floor((camCoord / cellSize).toDouble()).toFloat() * cellSize
    }

    /**
     * Exact CPU mirror of `ocean.mat`'s `materialVertex` Gerstner sum at resting horizontal coordinates `([x0], [z0])`
     * and time [t] (seconds). Writes displaced position, analytical unit normal, Jacobian, and crest factor into [out]
     * with zero allocations.
     */
    fun evalDisplacedInto(
        x0: Float,
        z0: Float,
        t: Float,
        spec: OceanSpec = CALM,
        out: OceanSample,
        seaLevelY: Float = 0f,
    ): OceanSample {
        var dispX = 0f
        var dispY = 0f
        var dispZ = 0f

        var dXx = 1f; var dXy = 0f; var dXz = 0f
        var dZx = 0f; var dZy = 0f; var dZz = 1f

        val waves = spec.waves
        for (i in 0 until waves.size) {
            val w = waves[i]
            val wavelength = w.wavelength
            val amp = w.amplitude
            if (wavelength <= 0.0001f || amp <= 0f) continue
            val dx = w.dirX
            val dz = w.dirZ
            val q = w.steepness
            val k = TWO_PI / wavelength
            val phase = k * (dx * x0 + dz * z0) - w.omega * t + w.phaseOffset
            val s = sin(phase.toDouble()).toFloat()
            val c = cos(phase.toDouble()).toFloat()

            val qa = q * amp
            val ka = k * amp
            val qka = q * ka

            dispX += qa * dx * c
            dispY += amp * s
            dispZ += qa * dz * c

            dXx -= qka * dx * dx * s
            dXy += ka * dx * c
            dXz -= qka * dx * dz * s

            dZx -= qka * dx * dz * s
            dZy += ka * dz * c
            dZz -= qka * dz * dz * s
        }

        // Right-handed Y-up normal: dZ x dX
        var nx = dZy * dXz - dZz * dXy
        var ny = dZz * dXx - dZx * dXz
        var nz = dZx * dXy - dZy * dXx
        val nLen = sqrt(nx * nx + ny * ny + nz * nz)
        if (nLen > 1e-6f) {
            nx /= nLen; ny /= nLen; nz /= nLen
        } else {
            nx = 0f; ny = 1f; nz = 0f
        }

        val jacobian = dXx * dZz - dXz * dZx
        val crest = (1f - jacobian).coerceIn(0f, 1f)

        out.x = x0 + dispX
        out.y = seaLevelY + dispY
        out.z = z0 + dispZ
        out.nx = nx
        out.ny = ny
        out.nz = nz
        out.jacobian = jacobian
        out.crest = crest
        return out
    }

    /** Allocating convenience wrapper around [evalDisplacedInto]. */
    fun evalDisplaced(
        x0: Float,
        z0: Float,
        t: Float,
        spec: OceanSpec = CALM,
        seaLevelY: Float = 0f,
    ): OceanSample = evalDisplacedInto(x0, z0, t, spec, OceanSample(), seaLevelY)

    /**
     * Evaluates the ocean surface height `out.y` and unit normal `(out.nx, out.ny, out.nz)` directly at world
     * coordinates `([x], [z])` and time [t] (seconds), inverting the Gerstner horizontal shift via fixed-point
     * iteration so a floating boat or Jolt buoyancy probe sits directly on the displaced crest rather than shifted off
     * it. Allocates nothing.
     */
    fun heightInto(
        x: Float,
        z: Float,
        t: Float,
        spec: OceanSpec = CALM,
        out: OceanSample,
        seaLevelY: Float = 0f,
        iterations: Int = 4,
    ): OceanSample {
        var rx = x
        var rz = z
        for (iter in 0 until iterations) {
            evalDisplacedInto(rx, rz, t, spec, out, seaLevelY)
            rx += x - out.x
            rz += z - out.z
        }
        return evalDisplacedInto(rx, rz, t, spec, out, seaLevelY)
    }

    /**
     * Returns the ocean surface height and analytical normal at world coordinates `([x], [z])` and time [t] (seconds)
     * for the given [spec] (defaults to [CALM]).
     */
    fun height(
        x: Float,
        z: Float,
        t: Float,
        spec: OceanSpec = CALM,
        seaLevelY: Float = 0f,
    ): OceanSample = heightInto(x, z, t, spec, OceanSample(), seaLevelY)
}
