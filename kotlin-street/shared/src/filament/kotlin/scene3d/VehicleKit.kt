package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.lifemax.rpg.feature.habits.vehicle.VehicleSpec
import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld
import kotlin.math.abs
import kotlin.math.sqrt

/*
 * A car's .glb (ChatGPT's C011/C012 deliveries: docs/VEHICLES.md) built for the town's renderer, through the same GLB
 * reader and the same lit material as the landmarks ([GlbFile], [PropLayer]): nearest-sampled pixel art, lit and shadowed
 * like the buildings. Unlike a landmark it is cut into its moving parts, each baked in its own node's frame so the
 * renderer stands it there every frame from the physics: the wheels (spun, under their steering pivots, risen with the
 * suspension), the doors, hood and trunk (hinged), the steering wheel, and each lamp's lens (lit on its own: head, tail,
 * brake, turn, reverse). Everything else is one static body, merged by material. Paint: the `paint_primary` picture
 * recoloured to the car's colour (its own shading kept), glass made a dark tinted solid (our material does not blend).
 * A race build's liveries (the race Torrel's, C046) are decals under `livery_<id>_*` nodes: the one whose body colour
 * the car wears is drawn ([VehicleSpec.liveryFor]), the others left out.
 */

/** One part of a car: its meshes by material in its node's frame ([node] -1: the static body, in the car's frame). */
internal class VehiclePart(val node: Int, val name: String, val meshes: List<Pair<String, PropMesh>>, val lamp: String?)

/** A car model built ([VehicleKit.build]): its parts, its nodes' rest matrices (street units) and their parents. */
internal class VehicleModel(
    val spec: VehicleSpec,
    val parts: List<VehiclePart>,
    /** Every node's local rest matrix (column-major, translations in street units) and parent (-1 the root). */
    val local: Array<FloatArray>, val parent: IntArray, val names: Array<String>,
    val triangles: Int,
)

internal object VehicleKit {
    private val U = VehicleWorld.U

    /** Nodes that move on their own: hinged panels, the running gear, the steering wheel and the lamps' lenses. */
    fun moves(name: String): Boolean = name.startsWith("door_") || name == "hood" || name == "trunk" || name == "tailgate" ||
        name.startsWith("steer_") || name.startsWith("wheel_") || name == "steering_wheel" || name.startsWith("light_")

    /**
     * Builds [glb] as [spec]'s car painted [colour] (ARGB). [animated] false: the whole model one static body (the mid and
     * far models, which keep only their anchors).
     */
    fun build(glb: GlbFile, spec: VehicleSpec, colour: Int, animated: Boolean = true): VehicleModel {
        val n = glb.nodes.size
        val local = Array(n) { i -> glb.nodes[i].local.copyOf().also { it[12] *= U; it[13] *= U; it[14] *= U } }
        val parent = IntArray(n) { glb.nodes[it].parent }
        val names = Array(n) { glb.nodes[it].name }
        // each mesh's owner: its nearest moving ancestor (a lamp only for its glowing lens; its housing stays on the body)
        fun owner(i: Int, material: String): Int {
            if (!animated) return -1
            var k = i
            while (k >= 0) {
                val nm = names[k]
                if (moves(nm)) return if (nm.startsWith("light_") && !material.startsWith("glow_")) -1 else k
                k = parent[k]
            }
            return -1
        }
        class Acc { val pos = ArrayList<Float>(); val nrm = ArrayList<Float>(); val uv = ArrayList<Float>(); val idx = ArrayList<Int>() }
        val groups = LinkedHashMap<Int, LinkedHashMap<String, Acc>>()
        val inv = HashMap<Int, FloatArray>()
        val tmp = FloatArray(3)
        var tris = 0
        // a race build's liveries (decals under `livery_<id>_*` nodes): only the one the colour names, none for plain paint
        val livery = spec.liveryFor(colour)?.id
        fun liveryOf(i: Int): String? {
            var k = i
            while (k >= 0) { val nm = names[k]; if (nm.startsWith("livery_")) return nm.removePrefix("livery_").substringBefore('_'); k = parent[k] }
            return null
        }
        for (nd in glb.nodes) {
            if (nd.mesh < 0) continue
            if (nd.extras?.has("render") == true && !nd.extras.optBoolean("render", true)) continue
            val lv = liveryOf(nd.index)
            if (lv != null && lv != livery) continue
            for (p in glb.primitives(nd.mesh)) {
                val mat = glb.materials.getOrNull(p.material)?.name ?: "untitled"
                val o = owner(nd.index, mat)
                // the mesh into its owner's frame (metres): the body's is the model's own
                val m = if (o < 0) nd.world else GlbFile.mul(inv.getOrPut(o) { GlbFile.invertAffine(glb.nodes[o].world) }, nd.world)
                val flip = GlbFile.mirrors(m)
                val a = groups.getOrPut(o) { LinkedHashMap() }.getOrPut(mat) { Acc() }
                val base = a.pos.size / 3
                val nv = p.pos.size / 3
                for (v in 0 until nv) {
                    GlbFile.point(m, p.pos[v * 3], p.pos[v * 3 + 1], p.pos[v * 3 + 2], tmp, 0)
                    a.pos += tmp[0] * U; a.pos += tmp[1] * U; a.pos += tmp[2] * U
                    if (p.nrm != null) {
                        GlbFile.dir(m, p.nrm[v * 3], p.nrm[v * 3 + 1], p.nrm[v * 3 + 2], tmp, 0)
                        val l = sqrt(tmp[0] * tmp[0] + tmp[1] * tmp[1] + tmp[2] * tmp[2]).coerceAtLeast(1e-6f)
                        a.nrm += tmp[0] / l; a.nrm += tmp[1] / l; a.nrm += tmp[2] / l
                    } else { a.nrm += 0f; a.nrm += 1f; a.nrm += 0f }
                    a.uv += p.uv?.get(v * 2) ?: 0f; a.uv += p.uv?.get(v * 2 + 1) ?: 0f
                }
                var t = 0
                while (t + 2 < p.idx.size) {
                    if (flip) { a.idx += base + p.idx[t]; a.idx += base + p.idx[t + 2]; a.idx += base + p.idx[t + 1] } else for (k in 0..2) a.idx += base + p.idx[t + k]
                    t += 3; tris++
                }
            }
        }
        // the pictures once per material (the paint recoloured, the glass made solid)
        val pics = HashMap<String, PixImg>()
        fun picture(name: String): PixImg = pics.getOrPut(name) {
            val m = glb.materials.firstOrNull { it.name == name }
            val im = m?.let { pictureOf(glb, it) } ?: PixImg(1, 1).also { it.fill(-1) }
            when {
                name == "paint_primary" -> repaint(im, colour)
                name.startsWith("glass_") -> for (i in im.px.indices) im.px[i] = tintedGlass(im.px[i])
                // (a lens glows whole, in its own colours: its batch's glow is set by the car's lamps)
                name.startsWith("glow_") -> im.glow(0, 0, im.w - 1, im.h - 1, 255)
            }
            im
        }
        val parts = ArrayList<VehiclePart>()
        for ((o, byMat) in groups) {
            val meshes = byMat.map { (mat, a) -> mat to mesh(a.pos.toFloatArray(), a.nrm.toFloatArray(), a.uv.toFloatArray(), a.idx.toIntArray(), picture(mat)) }
            val name = if (o < 0) "body" else names[o]
            val lamp = if (o >= 0 && name.startsWith("light_")) spec.lamps.entries.firstOrNull { name in it.value }?.key ?: roleOfLamp(name) else null
            parts += VehiclePart(o, name, meshes, lamp)
        }
        return VehicleModel(spec, parts, local, parent, names, tris)
    }

    /** A lamp node's role by its name when model-info.json does not list it (light_head_l: head). */
    private fun roleOfLamp(name: String): String = name.removePrefix("light_").substringBefore('_').let { if (it == "turn") "turn" else it }

    /**
     * The body colour: each texel of the paint's picture keeps its shade (its brightness against the picture's mean) in
     * [colour]; transparent texels are left.
     */
    fun repaint(im: PixImg, colour: Int) {
        var sum = 0.0; var n = 0
        for (p in im.px) if (p ushr 24 > 128) { sum += lum(p); n++ }
        if (n == 0) return
        val mean = (sum / n).coerceAtLeast(1.0)
        val cr = colour shr 16 and 255; val cg = colour shr 8 and 255; val cb = colour and 255
        for (i in im.px.indices) {
            val p = im.px[i]
            if (p ushr 24 <= 128) continue
            val k = lum(p) / mean
            fun ch(c: Int) = (c * k).toInt().coerceIn(0, 255)
            im.px[i] = (p and 0xFF000000.toInt()) or (ch(cr) shl 16) or (ch(cg) shl 8) or ch(cb)
        }
    }

    private fun lum(p: Int): Double = .299 * (p shr 16 and 255) + .587 * (p shr 8 and 255) + .114 * (p and 255)

    /** A glass texel made solid over a dark cabin: the clearer it was, the more of the cabin shows. */
    private fun tintedGlass(p: Int): Int {
        val a = p ushr 24
        val back = CABIN
        fun ch(s: Int) = (((p shr s) and 255) * a + ((back shr s) and 255) * (255 - a)) / 255
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
    private const val CABIN = 0xFF1C2228.toInt()

    private fun mesh(pos: FloatArray, nrm: FloatArray, uv: FloatArray, idx: IntArray, img: PixImg): PropMesh {
        val nv = pos.size / 3
        val tan = FloatArray(nv * 3)
        for (v in 0 until nv) {
            val ny = nrm[v * 3 + 1]
            if (abs(ny) > .7f) tan[v * 3] = 1f else {
                val l = sqrt(nrm[v * 3] * nrm[v * 3] + nrm[v * 3 + 2] * nrm[v * 3 + 2]).coerceAtLeast(1e-6f)
                tan[v * 3] = -nrm[v * 3 + 2] / l; tan[v * 3 + 2] = nrm[v * 3] / l
            }
        }
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (v in 0 until nv) for (c in 0..2) { b[c] = minOf(b[c], pos[v * 3 + c]); b[c + 3] = maxOf(b[c + 3], pos[v * 3 + c]) }
        return PropMesh(pos, uv, nrm, tan, idx, img.w, img.h, img.px, if (img.glows) img.mask else null, b)
    }

    /** A material's base picture (its texture times its factor), as the landmarks read it. */
    private fun pictureOf(glb: GlbFile, m: GlbFile.Material): PixImg {
        val f = m.baseFactor
        fun srgb(l: Float) = (if (l <= .0031308f) l * 12.92f else 1.055f * Math.pow(l.toDouble(), 1 / 2.4).toFloat() - .055f).coerceIn(0f, 1f)
        if (m.baseImage < 0) {
            val c = ((f[3].coerceIn(0f, 1f) * 255).toInt() shl 24) or ((srgb(f[0]) * 255).toInt() shl 16) or ((srgb(f[1]) * 255).toInt() shl 8) or (srgb(f[2]) * 255).toInt()
            return PixImg(1, 1).also { it.fill(c) }
        }
        val src = glb.image(m.baseImage)
        val im = PixImg(src.w, src.h)
        val plain = f.all { it >= .999f }
        for (i in src.px.indices) {
            val p = src.px[i]
            im.px[i] = if (plain) p else {
                val a = ((p ushr 24) * f[3]).toInt(); val r = ((p shr 16 and 255) * srgb(f[0])).toInt(); val g = ((p shr 8 and 255) * srgb(f[1])).toInt(); val b = ((p and 255) * srgb(f[2])).toInt()
                (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return im
    }
}
