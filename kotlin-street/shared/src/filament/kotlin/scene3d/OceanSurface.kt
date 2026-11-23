package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lighting and flat stepped pixel-art water palette for one time of day (day, dusk, or night), uploaded to `ocean.mat`.
 */
class OceanPalette(
    val bandDeep: FloatArray,
    val bandMid: FloatArray,
    val bandLight: FloatArray,
    val bandCrest: FloatArray,
    val skyColor: FloatArray,
    val skyReflectStrength: Float,
    val horizonColor: FloatArray,
    val glintColor: FloatArray,
    val glintStrength: Float,
    val foamColor: FloatArray,
    val lightDir: FloatArray,
    val nightFactor: Float,
) {
    companion object {
        private fun norm3(x: Float, y: Float, z: Float): FloatArray {
            val l = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-6f)
            return floatArrayOf(x / l, y / l, z / l)
        }

        /** Bright sunlit open ocean: crisp turquoise-to-cobalt 4-band palette and warm white sun glint. */
        val DAY = OceanPalette(
            bandDeep = floatArrayOf(0.055f, 0.165f, 0.340f),
            bandMid = floatArrayOf(0.085f, 0.275f, 0.490f),
            bandLight = floatArrayOf(0.145f, 0.420f, 0.640f),
            bandCrest = floatArrayOf(0.255f, 0.565f, 0.745f),
            skyColor = floatArrayOf(0.55f, 0.78f, 0.96f),
            skyReflectStrength = 0.65f,
            horizonColor = floatArrayOf(0.68f, 0.84f, 0.96f),
            glintColor = floatArrayOf(1.00f, 0.98f, 0.86f),
            glintStrength = 0.95f,
            foamColor = floatArrayOf(0.95f, 0.97f, 0.99f),
            lightDir = norm3(0.32f, 0.52f, 0.79f),
            nightFactor = 0.0f,
        )

        /** Low-sun dusk sea: slate-violet troughs, warm teal midtones, coral sky reflection, molten gold sun ribbon. */
        val DUSK = OceanPalette(
            bandDeep = floatArrayOf(0.085f, 0.105f, 0.220f),
            bandMid = floatArrayOf(0.155f, 0.185f, 0.330f),
            bandLight = floatArrayOf(0.280f, 0.275f, 0.410f),
            bandCrest = floatArrayOf(0.470f, 0.350f, 0.420f),
            skyColor = floatArrayOf(0.92f, 0.52f, 0.36f),
            skyReflectStrength = 0.78f,
            horizonColor = floatArrayOf(0.90f, 0.55f, 0.38f),
            glintColor = floatArrayOf(1.00f, 0.74f, 0.32f),
            glintStrength = 1.00f,
            foamColor = floatArrayOf(0.94f, 0.82f, 0.76f),
            lightDir = norm3(0.18f, 0.17f, 0.97f),
            nightFactor = 0.25f,
        )

        /** Dark open sea at night: deep indigo-navy stepped bands with crisp cool silver-white moon glint. */
        val NIGHT = OceanPalette(
            bandDeep = floatArrayOf(0.015f, 0.030f, 0.075f),
            bandMid = floatArrayOf(0.028f, 0.055f, 0.125f),
            bandLight = floatArrayOf(0.048f, 0.090f, 0.190f),
            bandCrest = floatArrayOf(0.082f, 0.140f, 0.260f),
            skyColor = floatArrayOf(0.08f, 0.13f, 0.25f),
            skyReflectStrength = 0.55f,
            horizonColor = floatArrayOf(0.055f, 0.090f, 0.185f),
            glintColor = floatArrayOf(0.84f, 0.91f, 1.00f),
            glintStrength = 0.92f,
            foamColor = floatArrayOf(0.62f, 0.72f, 0.84f),
            lightDir = norm3(-0.14f, 0.26f, 0.95f),
            nightFactor = 1.0f,
        )

        /** Selects an ocean palette matching the game hour (0..24). */
        fun forHour(hour: Float): OceanPalette {
            val h = ((hour % 24f) + 24f) % 24f
            return when {
                h < 5.5f || h >= 20.5f -> NIGHT
                h < 7.5f || h >= 17.5f -> DUSK
                else -> DAY
            }
        }
    }
}

/**
 * Standalone Filament renderable for the Gerstner ocean surface (`ocean.mat`).
 *
 * Uploads a single uniform grid mesh once (`[gridCells] x [gridCells]` quads spaced by [cellSize] metres). Each frame,
 * [update] snaps the mesh origin to integer steps of [cellSize] around the camera `(camX, camZ)` via `TransformManager`
 * and updates wave/palette uniforms with zero per-frame heap allocations.
 */
class OceanSurface(
    private val engine: Engine,
    private val scene: Scene,
    material: Material,
    /** Number of grid cells along each axis (`160` -> 25,921 vertices, 51,200 triangles, 16-bit indices, 1 draw call). */
    val gridCells: Int = 160,
    /** Spacing between adjacent resting grid vertices in metres. */
    val cellSize: Float = 1.25f,
    /** Sea-level elevation in metres. */
    val seaLevelY: Float = 0f,
) {
    val vertexCount: Int = (gridCells + 1) * (gridCells + 1)
    val triangleCount: Int = gridCells * gridCells * 2
    val halfSpan: Float = gridCells * cellSize * 0.5f

    private val vb: VertexBuffer
    private val ib: IndexBuffer
    private val mi: MaterialInstance = material.createInstance()
    private val entity: Int = EntityManager.get().create()
    private val transformInstance: Int

    // Pre-allocated 4x4 translation matrix (column-major) for zero-allocation grid snapping
    private val modelMatrix = FloatArray(16).also {
        it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f
    }
    private var lastSpec: OceanSpec? = null
    private var lastPalette: OceanPalette? = null

    init {
        val vertsPerSide = gridCells + 1
        val posBytes = ByteBuffer.allocateDirect(vertexCount * 12).order(ByteOrder.nativeOrder())
        for (iz in 0 until vertsPerSide) {
            val z = -halfSpan + iz * cellSize
            for (ix in 0 until vertsPerSide) {
                val x = -halfSpan + ix * cellSize
                posBytes.putFloat(x).putFloat(0f).putFloat(z)
            }
        }
        posBytes.flip()

        vb = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(vertexCount)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .build(engine)
        vb.setBufferAt(engine, 0, posBytes)

        val indexCount = triangleCount * 3
        val useShort = vertexCount <= 65536
        val idxBytes = ByteBuffer.allocateDirect(indexCount * if (useShort) 2 else 4).order(ByteOrder.nativeOrder())
        for (iz in 0 until gridCells) {
            for (ix in 0 until gridCells) {
                val a = iz * vertsPerSide + ix
                val b = a + 1
                val c = a + vertsPerSide
                val d = c + 1
                if (useShort) {
                    idxBytes.putShort(a.toShort()).putShort(c.toShort()).putShort(b.toShort())
                    idxBytes.putShort(b.toShort()).putShort(c.toShort()).putShort(d.toShort())
                } else {
                    idxBytes.putInt(a).putInt(c).putInt(b)
                    idxBytes.putInt(b).putInt(c).putInt(d)
                }
            }
        }
        idxBytes.flip()

        ib = IndexBuffer.Builder()
            .indexCount(indexCount)
            .bufferType(if (useShort) IndexBuffer.Builder.IndexType.USHORT else IndexBuffer.Builder.IndexType.UINT)
            .build(engine)
        ib.setBuffer(engine, idxBytes)

        // Generous vertical margin in the local bounding box so displaced wave peaks never frustum-cull out
        val box = Box(0f, 0f, 0f, halfSpan + 4f, 8f, halfSpan + 4f)
        RenderableManager.Builder(1)
            .boundingBox(box)
            .culling(true)
            .castShadows(false)
            .receiveShadows(false)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, 0, indexCount)
            .material(0, mi)
            .build(engine, entity)

        val tm = engine.transformManager
        if (!tm.hasComponent(entity)) tm.create(entity)
        transformInstance = tm.getInstance(entity)
        scene.addEntity(entity)
    }

    /**
     * Updates grid snapping around camera `([camX], [camY], [camZ])`, wave time [timeSeconds], [spec], and [palette].
     * Allocates nothing per frame.
     */
    fun update(
        camX: Float,
        camY: Float,
        camZ: Float,
        timeSeconds: Float,
        spec: OceanSpec,
        palette: OceanPalette,
        artPixelCell: Float = 2.0f,
    ) {
        val snapX = OceanWaves.snapGridOrigin(camX, cellSize)
        val snapZ = OceanWaves.snapGridOrigin(camZ, cellSize)

        modelMatrix[12] = snapX
        modelMatrix[13] = seaLevelY
        modelMatrix[14] = snapZ
        if (transformInstance != 0) {
            engine.transformManager.setTransform(transformInstance, modelMatrix)
        }

        mi.setParameter("gridOrigin", snapX, seaLevelY, snapZ, cellSize)
        mi.setParameter("oceanTime", timeSeconds, spec.maxAmplitude, halfSpan * 0.9f, spec.roughness)
        mi.setParameter("eyePos", camX, camY, camZ, artPixelCell)

        if (lastSpec !== spec) {
            uploadWaves(spec)
            lastSpec = spec
        }
        if (lastPalette !== palette) {
            uploadPalette(palette, spec.foamThreshold)
            lastPalette = palette
        } else {
            // Keep foam threshold synced if spec changed while palette stayed the same
            val fc = palette.foamColor
            mi.setParameter("foamColor", fc[0], fc[1], fc[2], spec.foamThreshold)
        }
    }

    private fun uploadWaves(spec: OceanSpec) {
        for (i in 0 until OceanSpec.MAX_WAVES) {
            val aName = WAVE_A_NAMES[i]
            val bName = WAVE_B_NAMES[i]
            if (i < spec.waves.size) {
                val w = spec.waves[i]
                mi.setParameter(aName, w.dirX, w.dirZ, w.wavelength, w.amplitude)
                mi.setParameter(bName, w.steepness, w.omega, w.phaseOffset, 0f)
            } else {
                mi.setParameter(aName, 1f, 0f, 1f, 0f)
                mi.setParameter(bName, 0f, 0f, 0f, 0f)
            }
        }
    }

    private fun uploadPalette(p: OceanPalette, foamThreshold: Float) {
        mi.setParameter("lightDir", p.lightDir[0], p.lightDir[1], p.lightDir[2], p.nightFactor)
        mi.setParameter("glintColor", p.glintColor[0], p.glintColor[1], p.glintColor[2], p.glintStrength)
        mi.setParameter("skyColor", p.skyColor[0], p.skyColor[1], p.skyColor[2], p.skyReflectStrength)
        mi.setParameter("horizonColor", p.horizonColor[0], p.horizonColor[1], p.horizonColor[2], halfSpan * 0.94f)
        mi.setParameter("bandDeep", p.bandDeep[0], p.bandDeep[1], p.bandDeep[2], 1f)
        mi.setParameter("bandMid", p.bandMid[0], p.bandMid[1], p.bandMid[2], 1f)
        mi.setParameter("bandLight", p.bandLight[0], p.bandLight[1], p.bandLight[2], 1f)
        mi.setParameter("bandCrest", p.bandCrest[0], p.bandCrest[1], p.bandCrest[2], 1f)
        mi.setParameter("foamColor", p.foamColor[0], p.foamColor[1], p.foamColor[2], foamThreshold)
    }

    fun destroy() {
        scene.remove(entity)
        engine.destroyEntity(entity)
        EntityManager.get().destroy(entity)
        engine.destroyMaterialInstance(mi)
        engine.destroyVertexBuffer(vb)
        engine.destroyIndexBuffer(ib)
    }

    companion object {
        private val WAVE_A_NAMES = arrayOf("waveA0", "waveA1", "waveA2", "waveA3", "waveA4", "waveA5")
        private val WAVE_B_NAMES = arrayOf("waveB0", "waveB1", "waveB2", "waveB3", "waveB4", "waveB5")

        /**
         * Builds a column-major 4x4 boat model matrix floating at `([boatX], [boatZ])` using [OceanWaves.heightInto],
         * aligning the hull's up axis to a damped wave normal so the simple box boat heaves, pitches, and rolls naturally
         * without allocating.
         */
        fun boatTransformInto(
            boatX: Float,
            boatZ: Float,
            headingRad: Float,
            t: Float,
            spec: OceanSpec,
            sample: OceanSample,
            outMatrix: FloatArray,
            draftOffsetY: Float = 0.25f,
            tiltDamping: Float = 0.65f,
        ) {
            OceanWaves.heightInto(boatX, boatZ, t, spec, sample)
            // Damped surface normal for stable hull roll/pitch
            var uy = 1f + (sample.ny - 1f) * tiltDamping
            var ux = sample.nx * tiltDamping
            var uz = sample.nz * tiltDamping
            val ul = sqrt(ux * ux + uy * uy + uz * uz).coerceAtLeast(1e-6f)
            ux /= ul; uy /= ul; uz /= ul

            // Desired heading forward projected onto the wave tangent plane
            val hx = sin(headingRad.toDouble()).toFloat()
            val hz = cos(headingRad.toDouble()).toFloat()
            // right = normalize(up x forward) in right-handed Y-up
            var rx = uy * hz
            var ry = uz * hx - ux * hz
            var rz = -uy * hx
            val rl = sqrt(rx * rx + ry * ry + rz * rz).coerceAtLeast(1e-6f)
            rx /= rl; ry /= rl; rz /= rl
            // forward = right x up
            val fx = ry * uz - rz * uy
            val fy = rz * ux - rx * uz
            val fz = rx * uy - ry * ux

            outMatrix[0] = rx; outMatrix[1] = ry; outMatrix[2] = rz; outMatrix[3] = 0f
            outMatrix[4] = ux; outMatrix[5] = uy; outMatrix[6] = uz; outMatrix[7] = 0f
            outMatrix[8] = fx; outMatrix[9] = fy; outMatrix[10] = fz; outMatrix[11] = 0f
            outMatrix[12] = boatX
            outMatrix[13] = sample.y + draftOffsetY
            outMatrix[14] = boatZ
            outMatrix[15] = 1f
        }
    }
}
