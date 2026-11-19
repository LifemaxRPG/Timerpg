package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer
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

    /** A vertex buffer of [n] vertices in this layout. */
    fun buffer(engine: Engine, n: Int): VertexBuffer = VertexBuffer.Builder().bufferCount(1).vertexCount(n)
        .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, STRIDE)
        .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 12, STRIDE)
        .attribute(VertexBuffer.VertexAttribute.TANGENTS, 0, VertexBuffer.AttributeType.SHORT4, 20, STRIDE)
        .normalized(VertexBuffer.VertexAttribute.TANGENTS)
        .build(engine)

    /** Whether a batch of [vertices] can be indexed with 16-bit indices (half the bytes of 32-bit ones). */
    fun short(vertices: Int): Boolean = vertices <= 65536

    /** An index buffer of [n] indices, 16-bit when [short]. */
    fun indices(engine: Engine, n: Int, short: Boolean): IndexBuffer = IndexBuffer.Builder().indexCount(n)
        .bufferType(if (short) IndexBuffer.Builder.IndexType.USHORT else IndexBuffer.Builder.IndexType.UINT).build(engine)
}
