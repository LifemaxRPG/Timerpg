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
 * Lighting and flat 16-bit pixel-art water palette (linear RGB for Filament `unlit` + `LinearToneMapper`)
 * for one time of day (day, dusk, or night), uploaded to `ocean.mat`.
 */
class OceanPalette(
    val bandDeep: FloatArray,
    val bandMid: FloatArray,
    val bandLight: FloatArray,
    val bandCrest: FloatArray,
    val skyColor: FloatArray,
    val skyReflectStrength: Float,
    /** Calm flat distant sea colour that the water settles into toward the horizon. */
    val horizonSeaColor: FloatArray,
    val glintColor: FloatArray,
    val glintStrength: Float,
    val foamColor: FloatArray,
    /** Unit vector pointing TOWARDS the sun (day/dusk) or moon (night). */
    val lightDir: FloatArray,
    val nightFactor: Float,
) {
    companion object {
        private fun norm3(x: Float, y: Float, z: Float): FloatArray {
            val l = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-6f)
            return floatArrayOf(x / l, y / l, z / l)
        }

        /** Bright sunlit 16-bit open ocean: crisp cobalt troughs, cerulean body, turquoise faces, pale aqua ridges. */
        val DAY = OceanPalette(
            bandDeep = floatArrayOf(0.028f, 0.115f, 0.285f),
            bandMid = floatArrayOf(0.055f, 0.215f, 0.445f),
            bandLight = floatArrayOf(0.110f, 0.365f, 0.595f),
            bandCrest = floatArrayOf(0.285f, 0.610f, 0.785f),
            skyColor = floatArrayOf(0.42f, 0.68f, 0.92f),
            skyReflectStrength = 0.50f,
            horizonSeaColor = floatArrayOf(0.105f, 0.285f, 0.510f),
            glintColor = floatArrayOf(1.00f, 0.98f, 0.86f),
            glintStrength = 0.95f,
            foamColor = floatArrayOf(0.95f, 0.97f, 0.99f),
            // High midday sun (+25 deg elevation) in front-left over the ocean
            lightDir = norm3(0.20f, 0.43f, 0.88f),
            nightFactor = 0.0f,
        )

        /** Low-sun dusk sea: deep slate-violet troughs, warm indigo body, coral-lit faces, molten gold sun ribbon. */
        val DUSK = OceanPalette(
            bandDeep = floatArrayOf(0.045f, 0.055f, 0.145f),
            bandMid = floatArrayOf(0.105f, 0.115f, 0.245f),
            bandLight = floatArrayOf(0.240f, 0.195f, 0.310f),
            bandCrest = floatArrayOf(0.560f, 0.340f, 0.295f),
            skyColor = floatArrayOf(0.86f, 0.40f, 0.26f),
            skyReflectStrength = 0.65f,
            horizonSeaColor = floatArrayOf(0.210f, 0.165f, 0.275f),
            glintColor = floatArrayOf(1.00f, 0.72f, 0.28f),
            glintStrength = 1.00f,
            foamColor = floatArrayOf(0.95f, 0.80f, 0.72f),
            // Low sunset sun (+5.5 deg elevation) visibly above the horizon
            lightDir = norm3(0.14f, 0.096f, 0.985f),
            nightFactor = 0.28f,
        )

        /** Dark open sea at night: midnight-indigo stepped bands with crisp cool silver-white moon sparkles. */
        val NIGHT = OceanPalette(
            bandDeep = floatArrayOf(0.008f, 0.016f, 0.048f),
            bandMid = floatArrayOf(0.016f, 0.032f, 0.088f),
            bandLight = floatArrayOf(0.034f, 0.064f, 0.145f),
            bandCrest = floatArrayOf(0.075f, 0.125f, 0.235f),
            skyColor = floatArrayOf(0.05f, 0.09f, 0.19f),
            skyReflectStrength = 0.45f,
            horizonSeaColor = floatArrayOf(0.018f, 0.036f, 0.092f),
            glintColor = floatArrayOf(0.86f, 0.92f, 1.00f),
            glintStrength = 0.94f,
            foamColor = floatArrayOf(0.58f, 0.68f, 0.80f),
            // Moon (+6.8 deg elevation) visibly above the horizon
            lightDir = norm3(-0.14f, 0.118f, 0.983f),
            nightFactor = 1.0f,
        )

        /** Selects an ocean palette matching the game hour (0..24). */
        fun forHour(hour: Float): OceanPalette {
            val h = ((hour % 24f) + 24f) % 24f
            return when {
                h in 7.5f..17.2f -> DAY
                h in 5.5f..7.5f || h in 17.2f..20.2f -> DUSK
                else -> NIGHT
            }
        }
    }
}

/**
 * Filament renderable wrapper for the G002 pixel-art ocean prototype.
 *
 * Uses a single static watertight nested-ring mesh ([OceanGridMesh]) covering ~7.2 km in progressively coarser
 * Gerstner wave rings plus a flat far-horizon skirt out to 24 km in **1 draw call**.
 *
 * Uses `vertexDomain : device` with `materialParams.look` (matching `street_lit.mat` / `LookMatrix`) so the
 * ocean always shares exact Y orientation and reversed-Z depth with boats and town geometry on both headless
 * desktop and Android `TextureView`.
 */
class OceanSurface(
    private val engine: Engine,
    private val scene: Scene,
    material: Material,
    val ringCells: Int = 56,
    val baseCellSize: Float = 1.0f,
    val ringCount: Int = 9,
    val farHorizonRadius: Float = 24000f,
    val seaLevelY: Float = 0f,
) {
    val mesh: OceanGridMesh = OceanGridMesh.build(
        ringCells = ringCells,
        baseCellSize = baseCellSize,
        ringCount = ringCount,
        farHorizonRadius = farHorizonRadius,
    )
    val vertexCount: Int get() = mesh.vertexCount
    val triangleCount: Int get() = mesh.triangleCount
    val snapStep: Float get() = mesh.snapStep

    private val vb: VertexBuffer
    private val ib: IndexBuffer
    private val mi: MaterialInstance = material.createInstance()
    private val entity: Int = EntityManager.get().create()

    private var lastSpec: OceanSpec? = null
    private var lastPalette: OceanPalette? = null

    init {
        val posBytes = ByteBuffer.allocateDirect(vertexCount * 12).order(ByteOrder.nativeOrder())
        val px = mesh.positionsX
        val pz = mesh.positionsZ
        for (i in 0 until vertexCount) {
            posBytes.putFloat(px[i]).putFloat(0f).putFloat(pz[i])
        }
        posBytes.flip()

        vb = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(vertexCount)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .build(engine)
        vb.setBufferAt(engine, 0, posBytes)

        val indexCount = mesh.indices.size
        val useShort = vertexCount <= 65536
        val idxBytes = ByteBuffer.allocateDirect(indexCount * if (useShort) 2 else 4).order(ByteOrder.nativeOrder())
        val ind = mesh.indices
        if (useShort) {
            for (i in 0 until indexCount) idxBytes.putShort(ind[i].toShort())
        } else {
            for (i in 0 until indexCount) idxBytes.putInt(ind[i])
        }
        idxBytes.flip()

        ib = IndexBuffer.Builder()
            .indexCount(indexCount)
            .bufferType(if (useShort) IndexBuffer.Builder.IndexType.USHORT else IndexBuffer.Builder.IndexType.UINT)
            .build(engine)
        ib.setBuffer(engine, idxBytes)

        // Default hull foam ring off until setHullFoam() is called
        mi.setParameter("hullPose", 0f, 0f, 1f, 0f)
        mi.setParameter("hullShape", 1f, 2f, 0.5f, 0f)

        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, 0f, farHorizonRadius, 16f, farHorizonRadius))
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, 0, indexCount)
            .material(0, mi)
            .build(engine, entity)

        scene.addEntity(entity)
    }

    /**
     * Uploads the camera's `VERTEX_DOMAIN_DEVICE` clip-space matrix `look` (matching `street_lit.mat` / `LookMatrix`),
     * updates grid snapping around `([camX], [camY], [camZ])`, wave time [timeSeconds], [spec], and [palette].
     * Allocates nothing per frame.
     */
    fun update(
        lookMatrix: FloatArray,
        camX: Float,
        camY: Float,
        camZ: Float,
        timeSeconds: Float,
        spec: OceanSpec,
        palette: OceanPalette,
        artPixelCell: Float = 2.0f,
    ) {
        val snapX = OceanWaves.snapGridOrigin(camX, snapStep)
        val snapZ = OceanWaves.snapGridOrigin(camZ, snapStep)

        mi.setParameter("look", MaterialInstance.FloatElement.MAT4, lookMatrix, 0, 1)
        mi.setParameter("gridOrigin", snapX, seaLevelY, snapZ, snapStep)
        mi.setParameter("oceanTime", timeSeconds, spec.maxAmplitude, spec.mainDirX, spec.mainDirZ)
        mi.setParameter("waveParams", spec.roughness, 140.0f, 2200.0f, artPixelCell)
        mi.setParameter("eyePos", camX, camY, camZ, 0f)

        if (lastSpec !== spec) {
            uploadWaves(spec)
            lastSpec = spec
        }
        if (lastPalette !== palette) {
            uploadPalette(palette, spec.foamThreshold)
            lastPalette = palette
        } else {
            val fc = palette.foamColor
            mi.setParameter("foamColor", fc[0], fc[1], fc[2], spec.foamThreshold)
        }
    }

    /**
     * Enables a thin pixel-art waterline foam ring where a floating boat hull meets the ocean surface.
     * Allocates nothing per frame.
     */
    fun setHullFoam(
        boatX: Float,
        boatZ: Float,
        headingRad: Float,
        halfBeam: Float = 1.15f,
        halfLength: Float = 2.65f,
        foamRingWidth: Float = 0.65f,
    ) {
        val ch = cos(headingRad.toDouble()).toFloat()
        val sh = sin(headingRad.toDouble()).toFloat()
        mi.setParameter("hullPose", boatX, boatZ, ch, sh)
        mi.setParameter("hullShape", halfBeam, halfLength, foamRingWidth, 1f)
    }

    /** Disables the hull waterline foam ring. */
    fun clearHullFoam() {
        mi.setParameter("hullShape", 1f, 2f, 0.5f, 0f)
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
        mi.setParameter("horizonColor", p.horizonSeaColor[0], p.horizonSeaColor[1], p.horizonSeaColor[2], 520.0f)
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
         * aligning the hull's up axis to a damped wave normal so the simple box boat sits in the water and heaves,
         * pitches, and rolls naturally without allocating.
         */
        fun boatTransformInto(
            boatX: Float,
            boatZ: Float,
            headingRad: Float,
            t: Float,
            spec: OceanSpec,
            sample: OceanSample,
            outMatrix: FloatArray,
            draftOffsetY: Float = 0.05f,
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
