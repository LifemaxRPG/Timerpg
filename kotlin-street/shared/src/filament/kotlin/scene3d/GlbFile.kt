package com.lifemax.rpg.feature.habits.presentation.scene3d

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import kotlin.math.abs

/*
 * A small reader for the glTF 2.0 binary (.glb) subset our hand-made landmark models use (docs/LANDMARKS_GLB.md):
 * nodes with translation/rotation/scale or a matrix, triangle meshes with POSITION, NORMAL and TEXCOORD_0 (float) and
 * 8/16/32-bit indices, materials with a base colour (texture and/or factor), an emissive texture/factor and an alpha
 * mode, embedded PNG images, and each node's and material's `extras`. Pure Kotlin (org.json, java.util.zip), so the
 * phone and the desktop preview read the same file the same way, on a worker. Filament's gltfio is not used: the
 * desktop's headless Filament has no gltfio, and the town wants the parts in its own lit material, merged by role,
 * with the collision mesh turned into walk shapes rather than drawn.
 */

/** A parsed .glb: its nodes (world matrices worked out), meshes, materials and images (decoded on demand). */
class GlbFile private constructor(private val json: org.json.JSONObject, private val bin: ByteBuffer) {

    /** One node: its [name], parent (-1 at the root), its matrix in the model (column-major 4x4), mesh (-1: none), extras. */
    class Node(val index: Int, val name: String, val parent: Int, val world: FloatArray, val mesh: Int, val extras: org.json.JSONObject?, val local: FloatArray = world) {
        /** Its place in the model (the matrix's translation). */
        val x: Float get() = world[12]
        val y: Float get() = world[13]
        val z: Float get() = world[14]
    }

    /** One triangle list: positions, normals, uvs (per vertex), indices, its material (-1: none). */
    class Primitive(val pos: FloatArray, val nrm: FloatArray?, val uv: FloatArray?, val idx: IntArray, val material: Int)

    class Material(
        val name: String,
        /** Base colour texture's image (-1: none) and factor (linear RGBA). */
        val baseImage: Int, val baseFactor: FloatArray,
        val emissiveImage: Int, val emissiveFactor: FloatArray,
        /** OPAQUE, MASK or BLEND. */
        val alphaMode: String,
        val extras: org.json.JSONObject?,
    )

    val nodes: List<Node>
    val materials: List<Material>
    private val meshPrims: List<List<Int>>
    private val imageCache = HashMap<Int, Png.Image>()

    init {
        val ns = json.optJSONArray("nodes") ?: org.json.JSONArray()
        val parent = IntArray(ns.length()) { -1 }
        for (i in 0 until ns.length()) ns.getJSONObject(i).optJSONArray("children")?.let { c -> for (k in 0 until c.length()) parent[c.getInt(k)] = i }
        val locals = Array(ns.length()) { i -> localMatrix(ns.getJSONObject(i)) }
        val world = arrayOfNulls<FloatArray>(ns.length())
        fun worldOf(i: Int): FloatArray = world[i] ?: run {
            val local = locals[i]
            (if (parent[i] < 0) local else mul(worldOf(parent[i]), local)).also { world[i] = it }
        }
        nodes = (0 until ns.length()).map { i ->
            val n = ns.getJSONObject(i)
            Node(i, n.optString("name"), parent[i], worldOf(i), n.optInt("mesh", -1), n.optJSONObject("extras"), locals[i])
        }
        val ms = json.optJSONArray("materials") ?: org.json.JSONArray()
        val tex = json.optJSONArray("textures") ?: org.json.JSONArray()
        fun image(info: org.json.JSONObject?): Int = info?.let { tex.optJSONObject(it.optInt("index", -1))?.optInt("source", -1) } ?: -1
        materials = (0 until ms.length()).map { i ->
            val m = ms.getJSONObject(i)
            val pbr = m.optJSONObject("pbrMetallicRoughness")
            val bf = pbr?.optJSONArray("baseColorFactor")?.let { a -> FloatArray(4) { a.optDouble(it, 1.0).toFloat() } } ?: floatArrayOf(1f, 1f, 1f, 1f)
            val ef = m.optJSONArray("emissiveFactor")?.let { a -> FloatArray(3) { a.optDouble(it, 0.0).toFloat() } } ?: floatArrayOf(0f, 0f, 0f)
            Material(m.optString("name"), image(pbr?.optJSONObject("baseColorTexture")), bf, image(m.optJSONObject("emissiveTexture")), ef,
                m.optString("alphaMode", "OPAQUE"), m.optJSONObject("extras"))
        }
        val meshes = json.optJSONArray("meshes") ?: org.json.JSONArray()
        meshPrims = (0 until meshes.length()).map { i -> (0 until (meshes.getJSONObject(i).optJSONArray("primitives")?.length() ?: 0)).toList() }
    }

    fun node(name: String): Node? = nodes.firstOrNull { it.name == name }

    /** Whether [n] is [ancestor] or lies under it. */
    fun under(n: Node, ancestor: Node): Boolean {
        var k = n.index
        while (k >= 0) { if (k == ancestor.index) return true; k = nodes[k].parent }
        return false
    }

    /** The names of [n] and every node above it, nearest first. */
    fun path(n: Node): List<String> {
        val out = ArrayList<String>(); var k = n.index
        while (k >= 0) { out += nodes[k].name; k = nodes[k].parent }
        return out
    }

    /** The triangle lists of mesh [mesh] (triangles only; other modes are skipped). */
    fun primitives(mesh: Int): List<Primitive> {
        val m = json.getJSONArray("meshes").getJSONObject(mesh)
        val ps = m.optJSONArray("primitives") ?: return emptyList()
        return (0 until ps.length()).mapNotNull { k ->
            val p = ps.getJSONObject(k)
            if (p.optInt("mode", 4) != 4) return@mapNotNull null
            val at = p.getJSONObject("attributes")
            val pos = floats(at.getInt("POSITION"))
            val nrm = if (at.has("NORMAL")) floats(at.getInt("NORMAL")) else null
            val uv = if (at.has("TEXCOORD_0")) floats(at.getInt("TEXCOORD_0")) else null
            val idx = if (p.has("indices")) ints(p.getInt("indices")) else IntArray(pos.size / 3) { it }
            Primitive(pos, nrm, uv, idx, p.optInt("material", -1))
        }
    }

    /** Image [i] decoded (cached). */
    fun image(i: Int): Png.Image = imageCache.getOrPut(i) {
        val im = json.getJSONArray("images").getJSONObject(i)
        val bv = json.getJSONArray("bufferViews").getJSONObject(im.getInt("bufferView"))
        val off = bv.optInt("byteOffset", 0); val len = bv.getInt("byteLength")
        val bytes = ByteArray(len)
        bin.duplicate().also { it.position(off) }.get(bytes)
        Png.decode(bytes)
    }

    val imageCount: Int get() = json.optJSONArray("images")?.length() ?: 0

    private fun view(acc: org.json.JSONObject): Triple<ByteBuffer, Int, Int> {
        val bv = json.getJSONArray("bufferViews").getJSONObject(acc.getInt("bufferView"))
        val b = bin.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        return Triple(b, bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0), bv.optInt("byteStride", 0))
    }

    private fun components(type: String) = when (type) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; "MAT4" -> 16; else -> error("accessor type $type") }

    private fun floats(i: Int): FloatArray {
        val a = json.getJSONArray("accessors").getJSONObject(i)
        require(a.getInt("componentType") == 5126) { "only float vertex attributes are read (accessor $i)" }
        val n = components(a.getString("type")); val count = a.getInt("count")
        val (b, off, stride0) = view(a)
        val stride = if (stride0 == 0) n * 4 else stride0
        return FloatArray(count * n) { k -> b.getFloat(off + (k / n) * stride + (k % n) * 4) }
    }

    private fun ints(i: Int): IntArray {
        val a = json.getJSONArray("accessors").getJSONObject(i)
        val count = a.getInt("count")
        val (b, off, stride0) = view(a)
        return when (a.getInt("componentType")) {
            5121 -> IntArray(count) { b.get(off + it * (if (stride0 == 0) 1 else stride0)).toInt() and 0xFF }
            5123 -> IntArray(count) { b.getShort(off + it * (if (stride0 == 0) 2 else stride0)).toInt() and 0xFFFF }
            5125 -> IntArray(count) { b.getInt(off + it * (if (stride0 == 0) 4 else stride0)) }
            else -> error("index type ${a.getInt("componentType")}")
        }
    }

    companion object {
        /** Parses [bytes] (a whole .glb). */
        fun read(bytes: ByteArray): GlbFile {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(b.getInt(0) == 0x46546C67) { "not a .glb (magic)" }
            require(b.getInt(4) == 2) { "glTF version ${b.getInt(4)}" }
            var at = 12
            var json: org.json.JSONObject? = null; var bin: ByteBuffer = ByteBuffer.allocate(0)
            while (at + 8 <= bytes.size) {
                val len = b.getInt(at); val type = b.getInt(at + 4)
                if (type == 0x4E4F534A) json = org.json.JSONObject(String(bytes, at + 8, len, Charsets.UTF_8))
                else if (type == 0x004E4942) bin = ByteBuffer.wrap(bytes, at + 8, len).slice().order(ByteOrder.LITTLE_ENDIAN)
                at += 8 + len
            }
            return GlbFile(json ?: error("no JSON chunk"), bin)
        }

        private fun localMatrix(n: org.json.JSONObject): FloatArray {
            n.optJSONArray("matrix")?.let { a -> return FloatArray(16) { a.getDouble(it).toFloat() } }
            val t = n.optJSONArray("translation"); val r = n.optJSONArray("rotation"); val s = n.optJSONArray("scale")
            val tx = t?.optDouble(0)?.toFloat() ?: 0f; val ty = t?.optDouble(1)?.toFloat() ?: 0f; val tz = t?.optDouble(2)?.toFloat() ?: 0f
            val qx = r?.optDouble(0)?.toFloat() ?: 0f; val qy = r?.optDouble(1)?.toFloat() ?: 0f; val qz = r?.optDouble(2)?.toFloat() ?: 0f; val qw = r?.optDouble(3)?.toFloat() ?: 1f
            val sx = s?.optDouble(0)?.toFloat() ?: 1f; val sy = s?.optDouble(1)?.toFloat() ?: 1f; val sz = s?.optDouble(2)?.toFloat() ?: 1f
            // column-major: R * S, then T
            val m = FloatArray(16)
            m[0] = (1 - 2 * (qy * qy + qz * qz)) * sx; m[1] = (2 * (qx * qy + qz * qw)) * sx; m[2] = (2 * (qx * qz - qy * qw)) * sx
            m[4] = (2 * (qx * qy - qz * qw)) * sy; m[5] = (1 - 2 * (qx * qx + qz * qz)) * sy; m[6] = (2 * (qy * qz + qx * qw)) * sy
            m[8] = (2 * (qx * qz + qy * qw)) * sz; m[9] = (2 * (qy * qz - qx * qw)) * sz; m[10] = (1 - 2 * (qx * qx + qy * qy)) * sz
            m[12] = tx; m[13] = ty; m[14] = tz; m[15] = 1f
            return m
        }

        /** a * b, column-major 4x4. */
        internal fun mul(a: FloatArray, b: FloatArray): FloatArray = FloatArray(16).also { mulInto(a, b, it) }

        /** a * b into [out] (must not alias [a] or [b]), column-major 4x4; allocates nothing. */
        internal fun mulInto(a: FloatArray, b: FloatArray, out: FloatArray) {
            for (c in 0..3) {
                val c4 = c * 4
                val b0 = b[c4]; val b1 = b[c4 + 1]; val b2 = b[c4 + 2]; val b3 = b[c4 + 3]
                out[c4]     = a[0] * b0 + a[4] * b1 + a[8]  * b2 + a[12] * b3
                out[c4 + 1] = a[1] * b0 + a[5] * b1 + a[9]  * b2 + a[13] * b3
                out[c4 + 2] = a[2] * b0 + a[6] * b1 + a[10] * b2 + a[14] * b3
                out[c4 + 3] = a[3] * b0 + a[7] * b1 + a[11] * b2 + a[15] * b3
            }
        }

        /** Inverse of a column-major 4x4 affine matrix (last row 0, 0, 0, 1); falls back to identity if singular. */
        internal fun invertAffine(m: FloatArray): FloatArray {
            val a00 = m[0]; val a10 = m[1]; val a20 = m[2]
            val a01 = m[4]; val a11 = m[5]; val a21 = m[6]
            val a02 = m[8]; val a12 = m[9]; val a22 = m[10]
            val c00 = a11 * a22 - a12 * a21
            val c01 = -(a10 * a22 - a12 * a20)
            val c02 = a10 * a21 - a11 * a20
            val det = a00 * c00 + a01 * c01 + a02 * c02
            if (abs(det) < 1e-12f) return floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            val inv = 1f / det
            val c10 = -(a01 * a22 - a02 * a21)
            val c11 = a00 * a22 - a02 * a20
            val c12 = -(a00 * a21 - a01 * a20)
            val c20 = a01 * a12 - a02 * a11
            val c21 = -(a00 * a12 - a02 * a10)
            val c22 = a00 * a11 - a01 * a10
            val o = FloatArray(16)
            o[0] = c00 * inv; o[1] = c01 * inv; o[2] = c02 * inv
            o[4] = c10 * inv; o[5] = c11 * inv; o[6] = c12 * inv
            o[8] = c20 * inv; o[9] = c21 * inv; o[10] = c22 * inv
            val tx = m[12]; val ty = m[13]; val tz = m[14]
            o[12] = -(o[0] * tx + o[4] * ty + o[8] * tz)
            o[13] = -(o[1] * tx + o[5] * ty + o[9] * tz)
            o[14] = -(o[2] * tx + o[6] * ty + o[10] * tz)
            o[15] = 1f
            return o
        }

        /** [m] applied to the point ([x], [y], [z]), into [out] at [o]. */
        internal fun point(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray, o: Int) {
            out[o] = m[0] * x + m[4] * y + m[8] * z + m[12]; out[o + 1] = m[1] * x + m[5] * y + m[9] * z + m[13]; out[o + 2] = m[2] * x + m[6] * y + m[10] * z + m[14]
        }

        /** [m]'s turn applied to the direction ([x], [y], [z]) (no translation; normalised by the caller). */
        internal fun dir(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray, o: Int) {
            out[o] = m[0] * x + m[4] * y + m[8] * z; out[o + 1] = m[1] * x + m[5] * y + m[9] * z; out[o + 2] = m[2] * x + m[6] * y + m[10] * z
        }

        /** Whether [m] flips handedness (a negative scale): its triangles' winding turns round. */
        internal fun mirrors(m: FloatArray): Boolean {
            val d = m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2])
            return d < 0f && abs(d) > 1e-9f
        }
    }
}

/** A small PNG decoder (8-bit grey, grey+alpha, RGB, RGBA, and palette images of 1-8 bits; not interlaced). */
object Png {
    /** ARGB pixels, rows top first. */
    class Image(val w: Int, val h: Int, val px: IntArray)

    fun decode(bytes: ByteArray): Image {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(bytes.size > 8 && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte()) { "not a PNG" }
        var at = 8
        var w = 0; var h = 0; var depth = 8; var type = 6; var interlace = 0
        var plte: IntArray? = null; var trns: ByteArray? = null
        val idat = ByteArrayOutputStream()
        while (at + 8 <= bytes.size) {
            val len = b.getInt(at); val t = String(bytes, at + 4, 4, Charsets.ISO_8859_1); val d = at + 8
            when (t) {
                "IHDR" -> { w = b.getInt(d); h = b.getInt(d + 4); depth = bytes[d + 8].toInt() and 255; type = bytes[d + 9].toInt() and 255; interlace = bytes[d + 12].toInt() }
                "PLTE" -> plte = IntArray(len / 3) { i -> (0xFF shl 24) or ((bytes[d + i * 3].toInt() and 255) shl 16) or ((bytes[d + i * 3 + 1].toInt() and 255) shl 8) or (bytes[d + i * 3 + 2].toInt() and 255) }
                "tRNS" -> trns = bytes.copyOfRange(d, d + len)
                "IDAT" -> idat.write(bytes, d, len)
                "IEND" -> break
            }
            at += 12 + len
        }
        require(interlace == 0) { "interlaced PNGs are not read" }
        require(depth == 8 || type == 3) { "only 8-bit PNGs (or palette) are read" }
        val ch = when (type) { 0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; 6 -> 4; else -> error("PNG colour type $type") }
        val bitsPx = ch * depth
        val stride = (w * bitsPx + 7) / 8
        val bpp = maxOf(1, bitsPx / 8)
        val raw = ByteArray((stride + 1) * h)
        Inflater().apply { setInput(idat.toByteArray()); var n = 0; while (n < raw.size && !finished()) { val k = inflate(raw, n, raw.size - n); if (k == 0 && (needsInput() || needsDictionary())) break; n += k }; end() }
        val cur = ByteArray(stride); val prev = ByteArray(stride)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val f = raw[y * (stride + 1)].toInt()
            for (i in 0 until stride) {
                val x = raw[y * (stride + 1) + 1 + i].toInt() and 255
                val a = if (i >= bpp) cur[i - bpp].toInt() and 255 else 0
                val up = prev[i].toInt() and 255
                val c = if (i >= bpp) prev[i - bpp].toInt() and 255 else 0
                cur[i] = (when (f) {
                    0 -> x; 1 -> x + a; 2 -> x + up; 3 -> x + (a + up) / 2
                    4 -> { val p = a + up - c; val pa = abs(p - a); val pb = abs(p - up); val pc = abs(p - c); x + if (pa <= pb && pa <= pc) a else if (pb <= pc) up else c }
                    else -> x
                } and 255).toByte()
            }
            for (x in 0 until w) {
                fun u(i: Int) = cur[i].toInt() and 255
                px[y * w + x] = when (type) {
                    0 -> { val g = u(x); (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
                    2 -> (0xFF shl 24) or (u(x * 3) shl 16) or (u(x * 3 + 1) shl 8) or u(x * 3 + 2)
                    4 -> { val g = u(x * 2); (u(x * 2 + 1) shl 24) or (g shl 16) or (g shl 8) or g }
                    6 -> (u(x * 4 + 3) shl 24) or (u(x * 4) shl 16) or (u(x * 4 + 1) shl 8) or u(x * 4 + 2)
                    else -> {
                        val per = 8 / depth; val byte = u(x / per); val shift = 8 - depth * (x % per + 1)
                        val i = (byte shr shift) and ((1 shl depth) - 1)
                        val c = plte?.getOrNull(i) ?: 0
                        val al = trns?.getOrNull(i)?.toInt()?.and(255) ?: 255
                        (al shl 24) or (c and 0xFFFFFF)
                    }
                }
            }
            System.arraycopy(cur, 0, prev, 0, stride)
        }
        return Image(w, h, px)
    }
}
