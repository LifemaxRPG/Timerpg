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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lighting and 7-colour hue-shifted flat pixel-art water palette (stored in linear RGB for Filament `unlit` +
 * `LinearToneMapper`) for one time of day (day, dusk, or night), uploaded to `ocean.mat`.
 */
class OceanPalette(
    val colAbyss: FloatArray,
    val colTrough: FloatArray,
    val colShadowFace: FloatArray,
    val colBody: FloatArray,
    val colLitFace: FloatArray,
    val colTealCrest: FloatArray,
    val colLipHighlight: FloatArray,
    val skyColor: FloatArray,
    val skyReflectStrength: Float,
    /** Calm flat distant sea colour that the water transitions into toward the horizon. */
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

        /** Converts an sRGB hex colour (0xRRGGBB) to linear RGB so Filament's sRGB output hits the exact pixel-art hex. */
        private fun srgbHex(hex: Int): FloatArray {
            fun ch(shift: Int): Float {
                val c = ((hex shr shift) and 0xFF) / 255.0
                return (if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)).toFloat()
            }
            return floatArrayOf(ch(16), ch(8), ch(0))
        }

        /**
         * Sunlit 16-bit day ocean: 7 hue-shifted colours from deep indigo-navy troughs through ocean cobalt/cerulean
         * to sunlit turquoise shoulders and sea-glass aqua crest highlights, with modest high-sun midday sparkle.
         */
        val DAY = OceanPalette(
            colAbyss = srgbHex(0x0D1F4A),
            colTrough = srgbHex(0x15356B),
            colShadowFace = srgbHex(0x1D4F8A),
            colBody = srgbHex(0x246CA3),
            colLitFace = srgbHex(0x2E90B5),
            colTealCrest = srgbHex(0x4CC0C4),
            colLipHighlight = srgbHex(0x8CE2DC),
            skyColor = srgbHex(0x7EB8EB),
            skyReflectStrength = 0.42f,
            horizonSeaColor = srgbHex(0x2B75AB),
            glintColor = srgbHex(0xFFFCE6),
            glintStrength = 0.85f,
            foamColor = srgbHex(0xF4FAFF),
            // High midday sun (+29 deg elevation) -> modest sparse scatter, NOT a white sheet
            lightDir = norm3(0.20f, 0.48f, 0.85f),
            nightFactor = 0.0f,
        )

        /**
         * Low-sun dusk ocean: 7 hue-shifted colours from midnight plum-indigo troughs through dusty violet
         * to warm mauve-rose and glowing coral-amber sunlit shoulders, with a strong golden sunset road.
         */
        val DUSK = OceanPalette(
            colAbyss = srgbHex(0x14122E),
            colTrough = srgbHex(0x221D47),
            colShadowFace = srgbHex(0x332B5E),
            colBody = srgbHex(0x463A6E),
            colLitFace = srgbHex(0x78486B),
            colTealCrest = srgbHex(0xB86058),
            colLipHighlight = srgbHex(0xE8965A),
            skyColor = srgbHex(0xE0764A),
            skyReflectStrength = 0.58f,
            horizonSeaColor = srgbHex(0x4F3D66),
            glintColor = srgbHex(0xFFD466),
            glintStrength = 1.00f,
            foamColor = srgbHex(0xF2D2C4),
            // Low sunset sun (+5.5 deg elevation) visibly above the horizon
            lightDir = norm3(0.14f, 0.096f, 0.985f),
            nightFactor = 0.28f,
        )

        /**
         * Dark open night ocean: 7 hue-shifted colours from near-black abyssal navy through midnight indigo
         * to cool slate-blue and pale moonlit cyan ridges, with crisp broken silver-white moon sparkles.
         */
        val NIGHT = OceanPalette(
            colAbyss = srgbHex(0x060B1A),
            colTrough = srgbHex(0x0B1530),
            colShadowFace = srgbHex(0x112247),
            colBody = srgbHex(0x18325E),
            colLitFace = srgbHex(0x244978),
            colTealCrest = srgbHex(0x376591),
            colLipHighlight = srgbHex(0x5D8FB5),
            skyColor = srgbHex(0x1E355E),
            skyReflectStrength = 0.40f,
            horizonSeaColor = srgbHex(0x13264A),
            glintColor = srgbHex(0xE8F3FF),
            glintStrength = 0.92f,
            foamColor = srgbHex(0x9FB6D1),
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
        GpuAccount.addBuffer(vb, vertexCount * 12L)

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
        GpuAccount.addBuffer(ib, idxBytes.limit().toLong())

        // Default hull foam ring off until setHullFoam() is called
        mi.setParameter("hullPose", 0f, 0f, 1f, 0f)
        // The ocean's metres are the scene's world (the preview); WorldOcean places them in the town's street units
        setWorldOf(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))
        mi.setParameter("hullShape", 1f, 2f, 0.35f, 0f)

        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, 0f, farHorizonRadius, 20f, farHorizonRadius))
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
     * Allocates zero bytes per frame.
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
     * Enables a crisp 1-2 px waterline foam ring around a floating boat hull (broken on the lee side).
     * Allocates zero bytes per frame.
     */
    fun setHullFoam(
        boatX: Float,
        boatZ: Float,
        headingRad: Float,
        halfBeam: Float = 1.16f,
        halfLength: Float = 2.68f,
        foamRingWidth: Float = 0.36f,
    ) {
        val ch = cos(headingRad.toDouble()).toFloat()
        val sh = sin(headingRad.toDouble()).toFloat()
        mi.setParameter("hullPose", boatX, boatZ, ch, sh)
        mi.setParameter("hullShape", halfBeam, halfLength, foamRingWidth, 1f)
    }

    /** Disables the hull waterline foam ring. */
    fun clearHullFoam() {
        mi.setParameter("hullShape", 1f, 2f, 0.35f, 0f)
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
        mi.setParameter("horizonColor", p.horizonSeaColor[0], p.horizonSeaColor[1], p.horizonSeaColor[2], 420.0f)
        mi.setParameter("colAbyss", p.colAbyss[0], p.colAbyss[1], p.colAbyss[2], 1f)
        mi.setParameter("colTrough", p.colTrough[0], p.colTrough[1], p.colTrough[2], 1f)
        mi.setParameter("colShadowFace", p.colShadowFace[0], p.colShadowFace[1], p.colShadowFace[2], 1f)
        mi.setParameter("colBody", p.colBody[0], p.colBody[1], p.colBody[2], 1f)
        mi.setParameter("colLitFace", p.colLitFace[0], p.colLitFace[1], p.colLitFace[2], 1f)
        mi.setParameter("colTealCrest", p.colTealCrest[0], p.colTealCrest[1], p.colTealCrest[2], 1f)
        mi.setParameter("colLipHighlight", p.colLipHighlight[0], p.colLipHighlight[1], p.colLipHighlight[2], 1f)
        mi.setParameter("foamColor", p.foamColor[0], p.foamColor[1], p.foamColor[2], foamThreshold)
    }

    /** The ocean's frame (metres) to the scene's world (column-major): its fog and shading position (WorldOcean). */
    fun setWorldOf(m: FloatArray) { mi.setParameter("worldOf", MaterialInstance.FloatElement.MAT4, m, 0, 1) }

    /** Shows or hides it (the world's near ocean draws it only by the coast: WorldOcean). */
    fun setVisible(on: Boolean) {
        val rm = engine.renderableManager
        val ri = rm.getInstance(entity)
        if (ri != 0) rm.setLayerMask(ri, 0xff, if (on) 0xff else 0)
    }

    /** Its vertex and index buffers (bytes). */
    val bytes: Long get() = vertexCount * 12L + triangleCount * 3L * (if (vertexCount <= 65535) 2 else 4)

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
         * Builds a column-major 4x4 boat model matrix floating at `([boatX], [boatZ])` by sampling 4 hull buoyancy
         * points (bow, stern, port, starboard) via [OceanWaves.heightInto] with zero allocations.
         *
         * Averaging across the hull footprint prevents single-point steepness spikes, and [maxTiltDeg] caps roll/pitch
         * cleanly (default 14 deg) so the vessel rides rough swells realistically without excessive capsize tilt.
         * The resulting hull waterline height and clamped unit up vector are written back into [sample].
         */
        fun boatTransformInto(
            boatX: Float,
            boatZ: Float,
            headingRad: Float,
            t: Float,
            spec: OceanSpec,
            sample: OceanSample,
            outMatrix: FloatArray,
            draftOffsetY: Float = 0.04f,
            tiltDamping: Float = 0.55f,
            maxTiltDeg: Float = 14.0f,
            hullHalfLength: Float = 2.1f,
            hullHalfBeam: Float = 1.0f,
        ) {
            val hx = sin(headingRad.toDouble()).toFloat()
            val hz = cos(headingRad.toDouble()).toFloat()
            // Right-handed flat-water hull right axis: up(0,1,0) x forward(hx,0,hz) = (hz, 0, -hx)
            val flatRx = hz
            val flatRz = -hx

            // 4-point hull buoyancy probe (bow, stern, starboard, port) — zero allocations
            OceanWaves.heightInto(boatX + hx * hullHalfLength, boatZ + hz * hullHalfLength, t, spec, sample)
            val yBow = sample.y
            OceanWaves.heightInto(boatX - hx * hullHalfLength, boatZ - hz * hullHalfLength, t, spec, sample)
            val yStern = sample.y
            OceanWaves.heightInto(boatX + flatRx * hullHalfBeam, boatZ + flatRz * hullHalfBeam, t, spec, sample)
            val yStbd = sample.y
            OceanWaves.heightInto(boatX - flatRx * hullHalfBeam, boatZ - flatRz * hullHalfBeam, t, spec, sample)
            val yPort = sample.y

            val avgY = 0.25f * (yBow + yStern + yStbd + yPort)
            // Hull tangent vectors along forward and starboard
            val fdx = hx * (2f * hullHalfLength)
            val fdy = yBow - yStern
            val fdz = hz * (2f * hullHalfLength)
            val rdx = flatRx * (2f * hullHalfBeam)
            val rdy = yStbd - yPort
            val rdz = flatRz * (2f * hullHalfBeam)
            // Hull normal = forward x right (points +Y in right-handed coordinates)
            var rawNx = fdy * rdz - fdz * rdy
            var rawNy = fdz * rdx - fdx * rdz
            var rawNz = fdx * rdy - fdy * rdx
            val rawLen = sqrt(rawNx * rawNx + rawNy * rawNy + rawNz * rawNz).coerceAtLeast(1e-6f)
            rawNx /= rawLen; rawNy /= rawLen; rawNz /= rawLen

            // Apply tilt damping then enforce maxTiltDeg ceiling (keeps rough sea tilt realistic around 12-14 deg)
            var ux = rawNx * tiltDamping
            var uy = 1f + (rawNy - 1f) * tiltDamping
            var uz = rawNz * tiltDamping
            val horizLen = sqrt(ux * ux + uz * uz)
            val maxSin = sin(maxTiltDeg * PI / 180.0).toFloat()
            val maxCos = cos(maxTiltDeg * PI / 180.0).toFloat()
            val uLen = sqrt(ux * ux + uy * uy + uz * uz).coerceAtLeast(1e-6f)
            ux /= uLen; uy /= uLen; uz /= uLen
            val curSin = horizLen / uLen
            if (curSin > maxSin && horizLen > 1e-6f) {
                val scale = maxSin / curSin
                ux *= scale
                uz *= scale
                uy = maxCos
            }

            // Write effective hull waterline and clamped up normal back into sample for telemetry
            sample.y = avgY
            sample.nx = ux
            sample.ny = uy
            sample.nz = uz

            // right = normalize(up x forward)
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
            outMatrix[13] = avgY + draftOffsetY
            outMatrix[14] = boatZ
            outMatrix[15] = 1f
        }
    }
}
