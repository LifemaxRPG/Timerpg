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

/**
 * The town's lights far off at night (docs/STREAMING.md, "Distant lights"; GTA V's night city): past the detail line a lit
 * window is not a window but a dot of light, and a street lamp past the few real point lights the same. Every one of them
 * is a quad at the light's place, made a fixed number of screen pixels big in the vertex stage (`far_lights.mat`: a 2 x 2
 * art-pixel core with a stepped halo, nearest, no blur), added over the scene in one draw. They come in past [nearWindows]
 * / [nearLamps] from the eye (where the real windows and the lamps' own light hand over), go with the dark (the same curve
 * as the windows' glow), and thin in their own haze, lighter than the town's: a city's lights read through the evening air
 * from across town, as its walls do not.
 *
 * The lights are given once ([set]); a lamp the cars knock down is left out the next time they are given.
 */
internal class FarLights(private val engine: Engine, private val scene: Scene, material: Material) {
    private val mi: MaterialInstance = material.createInstance()
    private var entity = 0
    private var vb: VertexBuffer? = null
    private var ib: IndexBuffer? = null
    /** How many lights are drawn. */
    var count = 0; private set
    /** Their GPU bytes. */
    var bytes = 0L; private set

    /**
     * The lights: [xyz] places (x, y, z each), [rgb] their colours (0xRRGGBB, sRGB), [lamp] which of them are street lamps
     * (the rest windows).
     */
    fun set(xyz: FloatArray, rgb: IntArray, lamp: BooleanArray) {
        clear()
        val n = rgb.size
        if (n == 0) return
        val nv = n * 4
        val v = ByteBuffer.allocateDirect(nv * STRIDE).order(ByteOrder.nativeOrder())
        val wide = nv > 65535
        val idx = ByteBuffer.allocateDirect(n * 6 * (if (wide) 4 else 2)).order(ByteOrder.nativeOrder())
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        for (i in 0 until n) {
            val x = xyz[i * 3]; val y = xyz[i * 3 + 1]; val z = xyz[i * 3 + 2]
            x0 = minOf(x0, x); y0 = minOf(y0, y); z0 = minOf(z0, z); x1 = maxOf(x1, x); y1 = maxOf(y1, y); z1 = maxOf(z1, z)
            val c = rgb[i]
            val a = if (lamp[i]) 128 else 255
            for (k in 0 until 4) {
                v.putFloat(x); v.putFloat(y); v.putFloat(z)
                v.putFloat(if (k == 1 || k == 2) R else -R); v.putFloat(if (k >= 2) R else -R)
                v.put(((c shr 16) and 255).toByte()); v.put(((c shr 8) and 255).toByte()); v.put((c and 255).toByte()); v.put(a.toByte())
            }
            val b = i * 4
            for (q in intArrayOf(0, 1, 2, 0, 2, 3)) if (wide) idx.putInt(b + q) else idx.putShort((b + q).toShort())
        }
        v.flip(); idx.flip()
        val vbuf = VertexBuffer.Builder().bufferCount(1).vertexCount(nv)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 12, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 0, VertexBuffer.AttributeType.UBYTE4, 20, STRIDE)
            .normalized(VertexBuffer.VertexAttribute.COLOR)
            .build(engine)
        vbuf.setBufferAt(engine, 0, v)
        val ibuf = IndexBuffer.Builder().indexCount(n * 6).bufferType(if (wide) IndexBuffer.Builder.IndexType.UINT else IndexBuffer.Builder.IndexType.USHORT).build(engine)
        ibuf.setBuffer(engine, idx)
        bytes = nv.toLong() * STRIDE + n * 6L * (if (wide) 4 else 2)
        GpuAccount.addBuffer(vbuf, bytes)
        val e = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(Box((x0 + x1) / 2f, (y0 + y1) / 2f, (z0 + z1) / 2f, (x1 - x0) / 2f + 10f, (y1 - y0) / 2f + 10f, (z1 - z0) / 2f + 10f))
            .culling(false).castShadows(false).receiveShadows(false).priority(6)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vbuf, ibuf, 0, n * 6).material(0, mi).build(engine, e)
        scene.addEntity(e); TownRoot.adopt(engine, e)
        entity = e; vb = vbuf; ib = ibuf; count = n
    }

    /**
     * This frame: [look] the camera's clip matrix, the eye at [pose], [on] how far the night is on (0 day, 1 night), the
     * windows' dots coming in from [nearWindows] and the lamps' from [nearLamps] (street units from the eye).
     */
    fun frame(pose: TownPose, look: FloatArray, on: Float, nearWindows: Float, nearLamps: Float, shown: Boolean) {
        val e = entity
        if (e == 0) return
        val rm = engine.renderableManager
        val ri = rm.getInstance(e)
        val draw = shown && on > .01f
        if (ri != 0) rm.setLayerMask(ri, 0xff, if (draw) 1 else 0)
        if (!draw) return
        mi.setParameter("look", MaterialInstance.FloatElement.MAT4, look, 0, 1)
        mi.setParameter("px", 2f / pose.w, 2f / pose.h, SCALE * pose.h / 1080f, 0f)
        mi.setParameter("tint", TINT[0], TINT[1], TINT[2], on)
        mi.setParameter("near", nearWindows, nearWindows + FADE_IN, nearLamps, nearLamps + FADE_IN)
        mi.setParameter("haze", HAZE, HAZE_FLOOR, 0f, 0f)
        mi.setParameter("eye", pose.ex, pose.ey, pose.ez, 0f)
    }

    private fun clear() {
        if (entity != 0) { scene.remove(entity); engine.destroyEntity(entity); EntityManager.get().destroy(entity); entity = 0 }
        vb?.let { engine.destroyVertexBuffer(it) }; vb = null
        ib?.let { engine.destroyIndexBuffer(it) }; ib = null
        count = 0; bytes = 0L
    }

    fun destroy() { clear(); if (PropLayer.deferDestroy) Graveyard.bury(engine, mi) else engine.destroyMaterialInstance(mi) }

    companion object {
        /** A vertex: position (float3), its corner's offset in art pixels (float2), colour and kind (ubyte4). */
        const val STRIDE = 24
        /** The quad's half size in art pixels (the core is 2 x 2, the halo two steps round it). */
        const val R = 3f
        /** Screen pixels an art pixel is on a 1080-tall screen (the street's own `cell`). */
        const val SCALE = 1.5f
        /** How far past their start the dots are whole (street units). */
        const val FADE_IN = 400f
        /** The dots' own haze (per street unit) and the least of them it leaves. */
        const val HAZE = .00007f
        const val HAZE_FLOOR = .2f
        /** The lights' strength (linear, times each light's colour): bright enough to bloom white at their core. */
        val TINT = floatArrayOf(1.25f, 1.1f, .9f)
    }
}
