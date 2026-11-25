package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * The town's vertex layout, shared by the buildings, the models and every prop, yard and ground batch: position (three
 * floats), texture coordinate (two floats: exact texel edges for the nearest-filtered pixel art) and the tangent frame
 * as a quaternion in four 16-bit signed normalised shorts (as Filament's own glTF loader stores it;
 * [com.google.android.filament.MathUtils.packTangentFrame] makes it for that storage). 28 bytes a vertex, where four
 * floats for the frame made it 36: the town's 3.5 million vertices take some 95 MB of GPU memory instead of 121, with
 * the light worked out from the same frame to within 1/32767.
 */
internal object TownVertex {
    const val STRIDE = 28

    /** Writes one vertex at [b]'s position: ([x], [y], [z]), ([u], [v]), the tangent frame quaternion [q]. */
    fun put(b: ByteBuffer, x: Float, y: Float, z: Float, u: Float, v: Float, q: FloatArray) {
        b.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v)
        b.putShort(snorm(q[0])).putShort(snorm(q[1])).putShort(snorm(q[2])).putShort(snorm(q[3]))
    }

    private fun snorm(f: Float): Short = (f.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()

    /** A vertex buffer of [n] vertices in this layout ([account]: listed in [GpuAccount] under the caller). */
    fun buffer(engine: Engine, n: Int, account: Boolean = true): VertexBuffer = VertexBuffer.Builder().bufferCount(1).vertexCount(n)
        .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, STRIDE)
        .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 12, STRIDE)
        .attribute(VertexBuffer.VertexAttribute.TANGENTS, 0, VertexBuffer.AttributeType.SHORT4, 20, STRIDE)
        .normalized(VertexBuffer.VertexAttribute.TANGENTS)
        .build(engine).also { if (account) GpuAccount.addBuffer(it, n.toLong() * STRIDE) }

    // ------------------------------------------------------------------ quantised (MEMORY_AUDIT.md, G5, "Step 4 as built")

    /**
     * The quantised layout the shared pages ([BufferArena]) hold: position as three 16-bit unsigned normalised shorts in
     * the batch's own box (and a fourth, 1), texture coordinate as two in the batch's own range of them, the same tangent
     * quaternion: 20 bytes a vertex instead of 28. The materials (street_lit, street_albedo, shadow_proxy) scale them back
     * by the batch's `posMap`, `posScale` and `uvMap` ([Quant]); unset (0), they read the float layout as is.
     */
    const val QSTRIDE = 20
    /**
     * The coarsest step a quantised position may have along x and z (street units, ~21 a metre): 1/16 (a batch up to 4096
     * units across, within 1.5 mm), and up (1/64: up to 1024 units tall, within 0.4 mm, so things laid a hair over the
     * ground stay over it); a bigger batch stays float. Each axis has its own step, a power of two as fine as the batch's
     * extent along it allows (a flat batch's height is exact), and the batch's origin is a whole number of steps, so two
     * batches with the same steps put a shared corner on the same point.
     */
    const val QSTEP_MAX = 1f / 16f
    const val QSTEP_MAX_Y = 1f / 64f
    /** The fewest steps a texel of the batch's picture is cut into (texel edges themselves always fall on a step). */
    const val UV_STEPS_MIN = 16

    /**
     * (Measuring idea 5, docs/NOVEL_OPTIMIZATIONS.md) quantised vertices, and those whose tangent quaternion an 8-bit
     * signed normalised value would hold exactly ([exact8]: every component 0 or +-1, give or take packTangentFrame's
     * 1/32767 bias). Filament's vertex shader builds the normal from the quaternion without normalising it, so only
     * those frames (faces facing +-z in the world) read back the same light from 8 bits.
     */
    val quantized = java.util.concurrent.atomic.AtomicLong()
    val tangentExact8 = java.util.concurrent.atomic.AtomicLong()
    private fun exact8(t: Short): Boolean { val a = kotlin.math.abs(t.toInt()); return a <= 1 || a >= 32766 }

    /** Vertices left as floats by [quantize], by why: no picture size given, a box too big, texture coordinates too wide. */
    val rejected = java.util.concurrent.atomic.AtomicLongArray(3)

    /** A batch quantised: its [vb] in [QSTRIDE]'s layout, and how the materials read it back. */
    class Quant(val vb: ByteBuffer, posMap: FloatArray, posScale: FloatArray, uvMap: FloatArray) {
        /** How its materials read it back (what a slice keeps once uploaded: the vertices themselves are let go). */
        val map = QuantMap(posMap, posScale, uvMap)
        fun decode(mi: com.google.android.filament.MaterialInstance) = map.decode(mi)
    }

    /** A quantised batch's read-back: its materials' `posMap`, `posScale` and `uvMap`. */
    class QuantMap(val posMap: FloatArray, val posScale: FloatArray, val uvMap: FloatArray) {
        /**
         * Tells [mi] (street_lit, street_albedo or shadow_proxy) how to read this batch back: `posMap`, `posScale`, `uvMap`
         * (unset, a material reads floats). Every material instance drawing the batch must have it.
         */
        fun decode(mi: com.google.android.filament.MaterialInstance) {
            val m = mi.material
            if (m.hasParameter("posMap")) mi.setParameter("posMap", posMap[0], posMap[1], posMap[2], posMap[3])
            if (m.hasParameter("posScale")) mi.setParameter("posScale", posScale[0], posScale[1], posScale[2], posScale[3])
            if (m.hasParameter("uvMap")) mi.setParameter("uvMap", uvMap[0], uvMap[1], uvMap[2], uvMap[3])
        }
    }

    /**
     * [vb] ([n] vertices in the float layout, as [put] writes it) quantised for the pages, or null when it would not keep
     * the precision ([QSTEP_MAX], [UV_STEPS_MIN]): positions in the batch's box, texture coordinates on a grid of
     * 1 / ([texW] k) and 1 / ([texH] k), k a power of two (at least [UV_STEPS_MIN]) as fine as the batch's range of them
     * allows, so every texel edge of a [texW] x [texH] picture is a whole number of steps: exact, as the floats were.
     */
    fun quantize(vb: ByteBuffer, n: Int, texW: Int, texH: Int,
        /** Written over [vb] itself (its first 20 n bytes; the packing's own fresh buffer): no second buffer held meanwhile. */
        inPlace: Boolean = false): Quant? {
        if (n <= 0 || texW <= 0 || texH <= 0) { rejected.addAndGet(0, n.toLong()); return null }
        val lo = FloatArray(5) { Float.MAX_VALUE }; val hi = FloatArray(5) { -Float.MAX_VALUE }
        for (i in 0 until n) {
            val o = i * STRIDE
            for (c in 0 until 5) { val f = vb.getFloat(o + c * 4); if (f.isNaN() || f.isInfinite()) return null; if (f < lo[c]) lo[c] = f; if (f > hi[c]) hi[c] = f }
        }
        // each axis's step: the smallest power of two over which the box spans at most 65534 steps along it
        val step = FloatArray(3) { a ->
            var s = 1f / 1048576f
            while ((hi[a] - lo[a]) / s > 65534f) s *= 2f
            s
        }
        if (step[0] > QSTEP_MAX || step[2] > QSTEP_MAX || step[1] > QSTEP_MAX_Y) { rejected.addAndGet(1, n.toLong()); return null }
        val org = FloatArray(3) { kotlin.math.floor(lo[it] / step[it]) * step[it] }
        // the texture coordinates' steps: 1 / (size x k), k a power of two, as fine as fits
        val uvStep = FloatArray(2)
        val uv0 = FloatArray(2)
        for (a in 0 until 2) {
            val size = if (a == 0) texW else texH
            val range = (hi[3 + a] - lo[3 + a]) * size + 2f
            var k = 1 shl 16
            while (k > 1 && range * k > 65534f) k = k shr 1
            if (k < UV_STEPS_MIN) { rejected.addAndGet(2, n.toLong()); return null }
            uvStep[a] = 1f / (size.toFloat() * k)
            uv0[a] = kotlin.math.floor(lo[3 + a] / uvStep[a]) * uvStep[a]
        }
        val out = if (inPlace) vb.duplicate().order(ByteOrder.nativeOrder()).also { it.clear() } else TownMemory.direct(n * QSTRIDE).order(ByteOrder.nativeOrder())
        fun q(f: Float, o: Float, s: Float): Short = ((f - o) / s).roundToInt().coerceIn(0, 65535).toShort()
        for (i in 0 until n) {
            // (the vertex read whole before it is written: in place, its 20 bytes land at or before its own 28, never on a
            // later vertex's)
            val o = i * STRIDE
            val x = q(vb.getFloat(o), org[0], step[0]); val y = q(vb.getFloat(o + 4), org[1], step[1]); val z = q(vb.getFloat(o + 8), org[2], step[2])
            val u = q(vb.getFloat(o + 12), uv0[0], uvStep[0]); val v = q(vb.getFloat(o + 16), uv0[1], uvStep[1])
            val t0 = vb.getShort(o + 20); val t1 = vb.getShort(o + 22); val t2 = vb.getShort(o + 24); val t3 = vb.getShort(o + 26)
            if (exact8(t0) && exact8(t1) && exact8(t2) && exact8(t3)) tangentExact8.incrementAndGet()
            quantized.incrementAndGet()
            out.putShort(x).putShort(y).putShort(z).putShort((-1).toShort()).putShort(u).putShort(v).putShort(t0).putShort(t1).putShort(t2).putShort(t3)
        }
        out.flip()
        // (read back as a normalised short a = q / 65535: position = origin + a x step x 65535; the material's v is Filament's
        // flipped one, 1 - v, so the map gives 1 - v0 - a x range for it)
        val su = uvStep[0] * 65535f; val sv = uvStep[1] * 65535f
        return Quant(out, floatArrayOf(org[0], org[1], org[2], 1f), floatArrayOf(step[0] * 65535f, step[1] * 65535f, step[2] * 65535f, 0f),
            floatArrayOf(uv0[0], 1f - uv0[1] - sv, su, sv))
    }

    /** A vertex buffer of [n] vertices in the quantised layout ([QSTRIDE]). */
    fun quantBuffer(engine: Engine, n: Int): VertexBuffer = VertexBuffer.Builder().bufferCount(1).vertexCount(n)
        .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.USHORT4, 0, QSTRIDE)
        .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.USHORT2, 8, QSTRIDE)
        .attribute(VertexBuffer.VertexAttribute.TANGENTS, 0, VertexBuffer.AttributeType.SHORT4, 12, QSTRIDE)
        .normalized(VertexBuffer.VertexAttribute.POSITION).normalized(VertexBuffer.VertexAttribute.UV0).normalized(VertexBuffer.VertexAttribute.TANGENTS)
        .build(engine)

    /** Whether a batch of [vertices] can be indexed with 16-bit indices (half the bytes of 32-bit ones). */
    fun short(vertices: Int): Boolean = vertices <= 65536

    /** An index buffer of [n] indices, 16-bit when [short]. */
    fun indices(engine: Engine, n: Int, short: Boolean): IndexBuffer = IndexBuffer.Builder().indexCount(n)
        .bufferType(if (short) IndexBuffer.Builder.IndexType.USHORT else IndexBuffer.Builder.IndexType.UINT).build(engine)
        .also { GpuAccount.addBuffer(it, n.toLong() * if (short) 2 else 4) }
}
