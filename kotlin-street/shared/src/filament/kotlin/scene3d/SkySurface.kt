package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Filament pixel-art sky dome (G016).
 *
 * Renders the full 360-degree world sky behind all town, terrain, and ocean geometry in **1 draw call**
 * using a single far-plane full-screen triangle (`vertexDomain : device`, `depthWrite : false`,
 * `depthCulling : true`, `priority(7)`).
 *
 * All textures (`cloudTex` 256x256 and `moonTex` 32x32) are baked deterministically once at init
 * and bound strictly with `MinFilter.NEAREST` / `MagFilter.NEAREST` (zero bilinear blur).
 * Per-frame [update] calls upload uniforms as primitive floats and allocate **0 bytes**.
 */
class SkySurface(
    private val engine: Engine,
    private val scene: Scene,
    material: Material,
) {
    val entity: Int = EntityManager.get().create()
    val materialInstance: MaterialInstance = material.createInstance()

    private val vertexBuffer: VertexBuffer
    private val indexBuffer: IndexBuffer
    private val cloudTexture: Texture
    private val moonTexture: Texture

    private val cloudSampler = TextureSampler(
        TextureSampler.MinFilter.NEAREST,
        TextureSampler.MagFilter.NEAREST,
        TextureSampler.WrapMode.REPEAT,
    )
    private val moonSampler = TextureSampler(
        TextureSampler.MinFilter.NEAREST,
        TextureSampler.MagFilter.NEAREST,
        TextureSampler.WrapMode.CLAMP_TO_EDGE,
    )

    init {
        val vbData = ByteBuffer.allocateDirect(3 * 3 * 4).order(ByteOrder.nativeOrder())
        vbData.putFloat(-1f); vbData.putFloat(-1f); vbData.putFloat(FAR_CLIP_Z)
        vbData.putFloat(3f); vbData.putFloat(-1f); vbData.putFloat(FAR_CLIP_Z)
        vbData.putFloat(-1f); vbData.putFloat(3f); vbData.putFloat(FAR_CLIP_Z)
        vbData.flip()

        vertexBuffer = VertexBuffer.Builder()
            .vertexCount(3)
            .bufferCount(1)
            .attribute(
                VertexBuffer.VertexAttribute.POSITION,
                0,
                VertexBuffer.AttributeType.FLOAT3,
                0,
                12,
            )
            .build(engine)
        vertexBuffer.setBufferAt(engine, 0, vbData)

        val ibData = ByteBuffer.allocateDirect(3 * 2).order(ByteOrder.nativeOrder())
        ibData.putShort(0); ibData.putShort(1); ibData.putShort(2)
        ibData.flip()

        indexBuffer = IndexBuffer.Builder()
            .indexCount(3)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        indexBuffer.setBuffer(engine, ibData)

        cloudTexture = buildCloudTexture(engine)
        moonTexture = buildMoonTexture(engine)
        materialInstance.setParameter("cloudTex", cloudTexture, cloudSampler)
        materialInstance.setParameter("moonTex", moonTexture, moonSampler)

        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, 0f, 1e6f, 1e6f, 1e6f))
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .priority(7)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer)
            .material(0, materialInstance)
            .build(engine, entity)

        scene.addEntity(entity)
    }

    /**
     * Uploads [state] and an arbitrary pinhole camera basis (compatible with both `TownPose`
     * off-center principal points and centered pinhole cameras) with **0 bytes allocated**.
     */
    fun update(
        state: SkyState,
        rx: Float, ry: Float, rz: Float,
        ux: Float, uy: Float, uz: Float,
        fx: Float, fy: Float, fz: Float,
        scaleX: Float,
        scaleY: Float,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        artCellPx: Float = 2f,
        windowY: Boolean = LookMatrix.windowY,
    ) {
        val mi = materialInstance
        val ySign = if (windowY) 1f else -1f

        mi.setParameter("camRight", rx, ry, rz, ySign)
        mi.setParameter("camUp", ux, uy, uz, artCellPx)
        mi.setParameter("camFwd", fx, fy, fz, state.timeSec)
        mi.setParameter("camLens", scaleX, scaleY, offsetX, offsetY)

        mi.setParameter("skyZenith", state.zenithLin[0], state.zenithLin[1], state.zenithLin[2], state.dark)
        mi.setParameter("skyMid", state.midLin[0], state.midLin[1], state.midLin[2], state.tone)
        mi.setParameter("skyHorizon", state.horizonLin[0], state.horizonLin[1], state.horizonLin[2], state.starBrightness)
        mi.setParameter("skyGlow", state.glowLin[0], state.glowLin[1], state.glowLin[2], state.glowAlpha)
        mi.setParameter("skyBelow", state.belowHorizonLin[0], state.belowHorizonLin[1], state.belowHorizonLin[2], state.mist)

        mi.setParameter("duskEmber", state.duskEmberLin[0], state.duskEmberLin[1], state.duskEmberLin[2], state.duskWeight)
        mi.setParameter("duskBelt", state.duskBeltLin[0], state.duskBeltLin[1], state.duskBeltLin[2], state.rain)
        mi.setParameter("overcastHi", state.overcastHiLin[0], state.overcastHiLin[1], state.overcastHiLin[2], state.overcast)
        mi.setParameter("overcastLo", state.overcastLoLin[0], state.overcastLoLin[1], state.overcastLoLin[2], state.cloudCoverage)
        mi.setParameter("mistColor", state.mistLin[0], state.mistLin[1], state.mistLin[2], state.winterCrisp)

        mi.setParameter("sunDir", state.sunDir[0], state.sunDir[1], state.sunDir[2], state.sunVisibility)
        mi.setParameter("sunColor", state.sunLin[0], state.sunLin[1], state.sunLin[2], state.haloAlpha)
        mi.setParameter("sunHalo", state.haloLin[0], state.haloLin[1], state.haloLin[2], state.sunRadiusRad)

        val phaseRad = (state.moonStep.toFloat() / 32f) * (2.0 * PI).toFloat()
        mi.setParameter("moonDir", state.moonDir[0], state.moonDir[1], state.moonDir[2], state.moonVisibility)
        mi.setParameter("moonColor", state.moonLin[0], state.moonLin[1], state.moonLin[2], phaseRad)
        mi.setParameter("moonEarth", state.moonEarthLin[0], state.moonEarthLin[1], state.moonEarthLin[2], state.moonRadiusRad)

        mi.setParameter("cloudLit", state.cloudLitLin[0], state.cloudLitLin[1], state.cloudLitLin[2], state.moonIllumination)
        mi.setParameter("cloudBody", state.cloudBodyLin[0], state.cloudBodyLin[1], state.cloudBodyLin[2], state.cloudUnderWarm)
        mi.setParameter("cloudShade", state.cloudShadeLin[0], state.cloudShadeLin[1], state.cloudShadeLin[2], state.cloudAlphaMax)
        mi.setParameter("cloudDrift", state.cloudDriftX, state.cloudDriftZ, state.highCloudDriftX, state.highCloudDriftZ)
    }

    /**
     * Convenience zero-allocation update for an `OrbitCamera`-style camera (`yawDeg` 0 north /
     * 90 east / 180 south / 270 west; `pitchDeg` positive down / negative up).
     */
    fun updateForCamera(
        state: SkyState,
        yawDeg: Float,
        pitchDeg: Float,
        fovYDeg: Float,
        aspect: Float,
        artCellPx: Float = 2f,
        windowY: Boolean = LookMatrix.windowY,
    ) {
        val ya = yawDeg * (PI / 180.0)
        val pa = pitchDeg * (PI / 180.0)
        val cp = cos(pa).toFloat()
        val sp = sin(pa).toFloat()
        val sy = sin(ya).toFloat()
        val cy = cos(ya).toFloat()

        val fx = sy * cp; val fy = -sp; val fz = -cy * cp
        val rx = cy; val ry = 0f; val rz = sy
        val ux = -rz * fy; val uy = rz * fx - rx * fz; val uz = rx * fy

        val scaleY = tan(fovYDeg * (PI / 360.0)).toFloat()
        val scaleX = scaleY * aspect
        update(state, rx, ry, rz, ux, uy, uz, fx, fy, fz, scaleX, scaleY, 0f, 0f, artCellPx, windowY)
    }

    /**
     * Convenience zero-allocation update for a look-at camera matching `FilamentOceanPreview.setCamera`.
     */
    fun updateForLookAt(
        state: SkyState,
        ex: Float, ey: Float, ez: Float,
        tx: Float, ty: Float, tz: Float,
        fovYDeg: Float,
        aspect: Float,
        artCellPx: Float = 2f,
        windowY: Boolean = LookMatrix.windowY,
    ) {
        var fx = tx - ex; var fy = ty - ey; var fz = tz - ez
        val fl = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-6f)
        fx /= fl; fy /= fl; fz /= fl

        var rx = -fz; val ry = 0f; var rz = fx
        val rl = sqrt(rx * rx + rz * rz).coerceAtLeast(1e-6f)
        rx /= rl; rz /= rl

        val ux = ry * fz - rz * fy
        val uy = rz * fx - rx * fz
        val uz = rx * fy - ry * fx

        val scaleY = tan(fovYDeg * (PI / 360.0)).toFloat()
        val scaleX = scaleY * aspect
        update(state, rx, ry, rz, ux, uy, uz, fx, fy, fz, scaleX, scaleY, 0f, 0f, artCellPx, windowY)
    }

    fun destroy() {
        scene.remove(entity)
        engine.destroyEntity(entity)
        EntityManager.get().destroy(entity)
        engine.destroyVertexBuffer(vertexBuffer)
        engine.destroyIndexBuffer(indexBuffer)
        engine.destroyTexture(cloudTexture)
        engine.destroyTexture(moonTexture)
        engine.destroyMaterialInstance(materialInstance)
    }

    companion object {
        private const val FAR_CLIP_Z = 0.999998f
        const val CLOUD_TEX_SIZE = 256
        const val MOON_TEX_SIZE = 32

        private fun buildMoonTexture(engine: Engine): Texture {
            val s = MOON_TEX_SIZE
            val buf = ByteBuffer.allocateDirect(s * s * 4).order(ByteOrder.nativeOrder())
            bakeMoonPixels(buf)
            buf.flip()
            val tex = Texture.Builder()
                .width(s).height(s).levels(1)
                .sampler(Texture.Sampler.SAMPLER_2D)
                .format(Texture.InternalFormat.RGBA8)
                .build(engine)
            tex.setImage(engine, 0, Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE, 1))
            return tex
        }

        private fun buildCloudTexture(engine: Engine): Texture {
            val s = CLOUD_TEX_SIZE
            val buf = ByteBuffer.allocateDirect(s * s * 4).order(ByteOrder.nativeOrder())
            bakeCloudPixels(buf)
            buf.flip()
            val tex = Texture.Builder()
                .width(s).height(s).levels(1)
                .sampler(Texture.Sampler.SAMPLER_2D)
                .format(Texture.InternalFormat.RGBA8)
                .build(engine)
            tex.setImage(engine, 0, Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE, 1))
            return tex
        }

        fun bakeMoonPixels(out: ByteBuffer) {
            val s = MOON_TEX_SIZE
            val cx = (s - 1) * 0.5f; val cy = (s - 1) * 0.5f
            val discR = 13.4f; val haloR = 15.5f
            val mariaX = floatArrayOf(-0.34f, -0.05f, 0.28f, 0.44f, -0.22f, 0.14f)
            val mariaY = floatArrayOf(-0.22f, -0.30f, -0.10f, 0.14f, 0.24f, 0.18f)
            val mariaR = floatArrayOf(0.36f, 0.26f, 0.30f, 0.22f, 0.24f, 0.20f)
            val mariaD = floatArrayOf(0.95f, 0.78f, 0.88f, 0.70f, 0.65f, 0.60f)

            for (y in 0 until s) {
                for (x in 0 until s) {
                    val dx = x - cx; val dy = y - cy
                    val r = sqrt(dx * dx + dy * dy)
                    val inDisc = r <= discR
                    val inHalo = !inDisc && r <= haloR
                    val nx = dx / discR; val ny = dy / discR
                    var mare = 0f
                    for (i in mariaX.indices) {
                        val mx = (nx - mariaX[i]) / mariaR[i]
                        val my = (ny - mariaY[i]) / (mariaR[i] * 0.85f)
                        val d2 = mx * mx + my * my
                        if (d2 < 1f) mare = max(mare, (1f - d2) * mariaD[i])
                    }
                    val tychoD = sqrt((nx - 0.08f) * (nx - 0.08f) + (ny - 0.56f) * (ny - 0.56f))
                    val copD = sqrt((nx + 0.18f) * (nx + 0.18f) + (ny + 0.02f) * (ny + 0.02f))
                    val brightSpot = (if (tychoD < 0.14f) 1f else 0f) + (if (copD < 0.11f) 0.7f else 0f)
                    val shade = when {
                        brightSpot > 0.5f -> 255
                        mare > 0.56f -> 136
                        mare > 0.26f -> 178
                        else -> 226
                    }
                    val rn = (r / discR).coerceIn(0f, 1f)
                    val relief = when {
                        rn < 0.58f -> 255
                        rn < 0.84f -> 226
                        else -> 196
                    }
                    out.put(if (inDisc) shade.toByte() else 0.toByte())
                    out.put(if (inDisc) 255.toByte() else 0.toByte())
                    out.put(if (inDisc) relief.toByte() else 0.toByte())
                    out.put(if (inHalo) 255.toByte() else 0.toByte())
                }
            }
        }

        fun bakeCloudPixels(out: ByteBuffer) {
            val s = CLOUD_TEX_SIZE
            val mass = FloatArray(s * s)
            val dome = FloatArray(s * s)
            val under = FloatArray(s * s)
            val high = FloatArray(s * s)

            for (y in 0 until s) {
                val fy = y.toFloat() / s
                val edgeFade = (min(fy, 1f - fy) / 0.12f).coerceIn(0f, 1f)
                for (x in 0 until s) {
                    val fx = x.toFloat() / s
                    val n1 = tileWave(fx, fy, 3f, 2f, 0.4f) * 0.46f +
                        tileWave(fx, fy, 6f, 4f, 1.9f) * 0.32f +
                        tileWave(fx, fy, 11f, 7f, 3.1f) * 0.22f
                    val idx = y * s + x
                    mass[idx] = ((0.22f + 0.36f * n1) * edgeFade).coerceIn(0f, 0.62f)
                    dome[idx] = (0.36f + 0.30f * n1).coerceIn(0.15f, 0.72f)
                    under[idx] = (0.32f + 0.42f * fy - 0.18f * n1).coerceIn(0.10f, 0.85f)

                    val c1 = tileWave(fx, fy, 2f, 6f, 1.1f) * 0.48f +
                        tileWave(fx, fy, 5f, 11f, 2.7f) * 0.32f +
                        tileWave(fx, fy, 9f, 16f, 4.3f) * 0.20f
                    high[idx] = ((0.28f + 0.62f * c1) * edgeFade).coerceIn(0f, 1f)
                }
            }

            var seed = 0x51A7C0DE
            fun nextF(): Float {
                seed = seed * 1664525 + 1013904223
                return ((seed ushr 8) and 0xFFFFFF) / 16777216f
            }

            val totalBanks = 29
            for (b in 0 until totalBanks) {
                val isPrimary = b < 5
                val slot = if (isPrimary) b else (b - 5)
                val slots = if (isPrimary) 5f else 24f
                val cx = ((slot.toFloat() + 0.22f + 0.56f * nextF()) / slots) * s
                val baseY = if (isPrimary) 128f + nextF() * 88f else 36f + nextF() * 184f
                val bankW = if (isPrimary) 34f + nextF() * 20f else 28f + nextF() * 24f
                val bankH = if (isPrimary) 9f + nextF() * 6f else 8f + nextF() * 7f
                val peakMass = if (isPrimary) 0.93f + nextF() * 0.07f else 0.58f + nextF() * 0.16f
                val puffCount = if (isPrimary) 5 + (b % 3) else 4 + (b % 3)

                for (p in 0 until puffCount) {
                    val u = if (puffCount == 1) 0f else (p.toFloat() / (puffCount - 1) - 0.5f) * 2f
                    val px = cx + u * bankW * 0.42f + (nextF() - 0.5f) * 3f
                    val arch = 1f - u * u * 0.76f
                    val pr = (bankH * (0.52f + 0.52f * arch)).coerceAtLeast(4.5f)
                    val py = baseY - pr * (0.42f + 0.22f * arch)
                    stampCumulusPuff(
                        mass, dome, under, s,
                        px, py, pr * 1.18f, pr * 0.94f,
                        baseY, bankH,
                        peakMass * (0.90f + 0.10f * arch),
                        (0.68f + 0.30f * arch).coerceIn(0f, 1f),
                    )
                }
                stampCumulusBase(mass, dome, under, s, cx, baseY, bankW * 0.52f, bankH * 0.36f, peakMass * 0.94f)
            }

            for (i in 0 until s * s) {
                out.put((quant(mass[i], 14) * 255f).toInt().coerceIn(0, 255).toByte())
                out.put((quant(dome[i], 8) * 255f).toInt().coerceIn(0, 255).toByte())
                out.put((quant(high[i], 8) * 255f).toInt().coerceIn(0, 255).toByte())
                out.put((quant(under[i], 6) * 255f).toInt().coerceIn(0, 255).toByte())
            }
        }

        private fun stampCumulusPuff(
            mass: FloatArray, dome: FloatArray, under: FloatArray, s: Int,
            cx: Float, cy: Float, rx: Float, ry: Float,
            flatBaseY: Float, bankH: Float, peakMass: Float, peakDome: Float,
        ) {
            val x0 = floor(cx - rx - 1f).toInt()
            val x1 = floor(cx + rx + 1f).toInt()
            val y0 = max(6, floor(cy - ry - 1f).toInt())
            val y1 = min(s - 7, floor(min(cy + ry + 1f, flatBaseY + 1f)).toInt())
            for (yi in y0..y1) {
                if (yi > flatBaseY) continue
                val dy = (yi - cy) / ry
                if (dy * dy > 1.0f) continue
                for (xi in x0..x1) {
                    val dx = (xi - cx) / rx
                    val d2 = dx * dx + dy * dy
                    if (d2 >= 1f) continue
                    val wx = ((xi % s) + s) % s
                    val idx = yi * s + wx
                    val m = peakMass * (0.82f + 0.18f * sqrt(1f - d2))
                    if (m > mass[idx]) mass[idx] = m
                    val hFrac = ((flatBaseY - yi) / max(bankH * 1.15f, 4f)).coerceIn(0f, 1f)
                    val dVal = peakDome * (0.42f + 0.58f * hFrac)
                    if (dVal > dome[idx]) dome[idx] = dVal
                    under[idx] = if (hFrac < 0.26f) 0.78f else (if (hFrac < 0.42f) 0.34f else 0.10f)
                }
            }
        }

        private fun stampCumulusBase(
            mass: FloatArray, dome: FloatArray, under: FloatArray, s: Int,
            cx: Float, flatBaseY: Float, halfW: Float, thickH: Float, peakMass: Float,
        ) {
            val x0 = floor(cx - halfW).toInt()
            val x1 = floor(cx + halfW).toInt()
            val y0 = max(6, floor(flatBaseY - thickH).toInt())
            val y1 = min(s - 7, floor(flatBaseY).toInt())
            for (yi in y0..y1) {
                val vy = ((flatBaseY - yi) / max(thickH, 1f)).coerceIn(0f, 1f)
                for (xi in x0..x1) {
                    val nx = abs(xi - cx) / max(halfW, 1f)
                    if (nx > 1f - 0.18f * (1f - vy)) continue
                    val wx = ((xi % s) + s) % s
                    val idx = yi * s + wx
                    if (peakMass > mass[idx]) mass[idx] = peakMass
                    if (vy < 0.55f) {
                        under[idx] = max(under[idx], 0.80f)
                        dome[idx] = min(dome[idx], 0.36f)
                    }
                }
            }
        }

        private fun tileWave(fx: Float, fy: Float, kx: Float, ky: Float, phase: Float): Float {
            val a = (fx * kx + fy * ky) * (2.0 * PI) + phase
            val b = (fx * ky - fy * kx) * (2.0 * PI) - phase * 0.7
            return (0.5 + 0.25 * sin(a) + 0.25 * cos(b)).toFloat()
        }

        private fun quant(v: Float, steps: Int): Float {
            val clamped = v.coerceIn(0f, 1f)
            return floor(clamped * steps + 0.5f) / steps
        }
    }
}
