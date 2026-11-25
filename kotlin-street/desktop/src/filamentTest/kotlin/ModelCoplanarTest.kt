import androidx.compose.ui.graphics.toComposeImageBitmap
import com.lifemax.rpg.feature.habits.domain.ModelShape
import com.lifemax.rpg.feature.habits.presentation.scene.ModelArt
import com.lifemax.rpg.feature.habits.presentation.scene3d.FilamentModels
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import org.jetbrains.skia.Image as SkiaImage

/**
 * Flicker from the modelled buildings' own faces (0.1.930, 085016: the porch's small gable on villa_4): every model of the
 * capital and the farms meshed as the town meshes it ([FilamentModels.Mesher]), and any two of its triangles that face the
 * same way in the same plane (within a thousandth of a pixel: closer than the part order's hundredth) and overlap by
 * more than a quarter of a pixel are a z-fight. The capital's villas must have none.
 *
 * gradle :desktop:crossroadsTest --tests ModelCoplanarTest
 */
class ModelCoplanarTest {
    private val assets = listOf(File("app/src/main/assets"), File("../app/src/main/assets")).first { it.isDirectory }
    private fun open(f: File) = f.takeIf { it.exists() }?.let { SkiaImage.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }

    private class Tri(val p: Array<DoubleArray>, val n: DoubleArray, val off: Double, val key: String)

    /** The fights of one model: "key a / key b at (x, y, d), n (nx, ny, nd), overlap A px²". */
    private fun fights(art: ModelArt): List<String> {
        val m = FilamentModels.Mesher(art)
        m.all()
        val tris = ArrayList<Tri>()
        for ((k, g) in m.groups) {
            for (t in 0 until g.ni / 3) {
                val p = Array(3) { j -> val o = g.idx[t * 3 + j] * 11; doubleArrayOf(g.v[o].toDouble(), g.v[o + 1].toDouble(), g.v[o + 2].toDouble()) }
                val e1 = DoubleArray(3) { p[1][it] - p[0][it] }; val e2 = DoubleArray(3) { p[2][it] - p[0][it] }
                val c = doubleArrayOf(e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0])
                val l = sqrt(c[0] * c[0] + c[1] * c[1] + c[2] * c[2])
                if (l < 1e-6) continue
                val n = DoubleArray(3) { c[it] / l }
                // (the side a face is seen from: its vertex normal's)
                val o0 = g.idx[t * 3] * 11
                if (n[0] * g.v[o0 + 5] + n[1] * g.v[o0 + 6] + n[2] * g.v[o0 + 7] < 0) for (i in 0..2) n[i] = -n[i]
                tris += Tri(p, n, n[0] * p[0][0] + n[1] * p[0][1] + n[2] * p[0][2], k.toString().take(40))
            }
        }
        val out = ArrayList<String>()
        val byPlane = tris.groupBy { t -> listOf(Math.round(t.n[0] * 1000), Math.round(t.n[1] * 1000), Math.round(t.n[2] * 1000), Math.round(t.off * 100)) }
        for ((_, ts) in byPlane) for (i in ts.indices) for (j in i + 1 until ts.size) {
            val a = ts[i]; val b = ts[j]
            if (abs(a.off - b.off) > 1e-3 || a.n[0] * b.n[0] + a.n[1] * b.n[1] + a.n[2] * b.n[2] < .9999) continue
            val area = overlap(a, b)
            if (area > .25) {
                val c = DoubleArray(3) { k -> (a.p[0][k] + a.p[1][k] + a.p[2][k]) / 3 }
                out += "%s / %s at (%.1f, %.1f, %.1f), n (%.2f, %.2f, %.2f), overlap %.1f px²".format(java.util.Locale.US, a.key, b.key, c[0], c[1], c[2], a.n[0], a.n[1], a.n[2], area)
            }
        }
        return out
    }

    /** The area two coplanar triangles share, in the plane's own axes. */
    private fun overlap(a: Tri, b: Tri): Double {
        val ax = (0..2).maxByOrNull { abs(a.n[it]) }!!
        val (u, v) = listOf(0, 1, 2).filter { it != ax }
        fun flat(t: Tri) = t.p.map { doubleArrayOf(it[u], it[v]) }
        var poly = flat(a)
        val clip = flat(b).let { c -> if (area(c) < 0) c.reversed() else c }
        if (area(poly) < 0) poly = poly.reversed()
        for (i in clip.indices) {
            val s = clip[i]; val e = clip[(i + 1) % clip.size]
            fun inside(q: DoubleArray) = (e[0] - s[0]) * (q[1] - s[1]) - (e[1] - s[1]) * (q[0] - s[0]) >= -1e-9
            val res = ArrayList<DoubleArray>()
            for (k in poly.indices) {
                val p = poly[k]; val q = poly[(k + 1) % poly.size]
                val pi = inside(p); val qi = inside(q)
                if (pi) res += p
                if (pi != qi) {
                    val dx = q[0] - p[0]; val dy = q[1] - p[1]
                    val den = (e[0] - s[0]) * dy - (e[1] - s[1]) * dx
                    if (abs(den) > 1e-12) {
                        val t = ((e[1] - s[1]) * (p[0] - s[0]) - (e[0] - s[0]) * (p[1] - s[1])) / den
                        res += doubleArrayOf(p[0] + t * dx, p[1] + t * dy)
                    }
                }
            }
            poly = res
            if (poly.size < 3) return 0.0
        }
        // (the plane's area: the projection's over the normal's component along the dropped axis)
        return abs(area(poly)) / abs(a.n[ax])
    }

    private fun area(p: List<DoubleArray>): Double {
        var s = 0.0
        for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; s += a[0] * b[1] - b[0] * a[1] }
        return s / 2
    }

    /**
     * The fights of a landmark's .glb as LandmarkKit draws it (every node's mesh at its world place, the collision-only
     * nodes left out): two faces facing the same way within a millimetre of one plane, sharing more than 10 cm². Faces
     * turned down within half a metre of the ground (a slab's underside on the plaza: never seen) are not counted.
     * "node/material / node/material at (x, y, z) m, n (nx, ny, nz), overlap A m²".
     */
    private fun landmarkFights(file: File): List<String> {
        val glb = com.lifemax.rpg.feature.habits.presentation.scene3d.GlbFile.read(file.readBytes())
        val tris = ArrayList<Tri>()
        for (n in glb.nodes) {
            if (n.mesh < 0) continue
            if (n.name == "collision" || n.extras?.optBoolean("collision_only") == true || (n.extras?.has("render") == true && !n.extras.optBoolean("render", true))) continue
            val m = n.world
            for (p in glb.primitives(n.mesh)) {
                val key = n.name + "/" + (glb.materials.getOrNull(p.material)?.name ?: "-")
                fun w(v: Int) = DoubleArray(3) { c -> (m[c] * p.pos[v * 3] + m[4 + c] * p.pos[v * 3 + 1] + m[8 + c] * p.pos[v * 3 + 2] + m[12 + c]).toDouble() }
                var t = 0
                while (t + 2 < p.idx.size) {
                    val q = arrayOf(w(p.idx[t]), w(p.idx[t + 1]), w(p.idx[t + 2])); t += 3
                    val e1 = DoubleArray(3) { q[1][it] - q[0][it] }; val e2 = DoubleArray(3) { q[2][it] - q[0][it] }
                    val c = doubleArrayOf(e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0])
                    val l = sqrt(c[0] * c[0] + c[1] * c[1] + c[2] * c[2])
                    if (l < 1e-9) continue
                    val nn = DoubleArray(3) { c[it] / l }
                    if (nn[1] < -.9 && q.all { it[1] < .5 }) continue
                    tris += Tri(q, nn, nn[0] * q[0][0] + nn[1] * q[0][1] + nn[2] * q[0][2], key)
                }
            }
        }
        val buckets = HashMap<List<Long>, MutableList<Int>>()
        for ((i, t) in tris.withIndex()) buckets.getOrPut(listOf(Math.round(t.n[0] * 500), Math.round(t.n[1] * 500), Math.round(t.n[2] * 500), Math.floorDiv(Math.round(t.off * 1000), 1L))) { ArrayList() } += i
        val found = LinkedHashMap<String, DoubleArray>()
        for ((k, ids) in buckets) {
            val near = ids + (buckets[listOf(k[0], k[1], k[2], k[3] + 1)] ?: emptyList())
            for (i in ids) for (j in near) {
                if (j <= i && j in ids) continue
                val a = tris[i]; val b = tris[j]
                if (abs(a.off - b.off) > 1e-3 || a.n[0] * b.n[0] + a.n[1] * b.n[1] + a.n[2] * b.n[2] < .9999) continue
                val area = overlap(a, b)
                if (area > 1e-3) {
                    val f = found.getOrPut("${a.key} / ${b.key}") { doubleArrayOf(0.0, 0.0, 0.0, 0.0) }
                    f[0] += area; for (c in 0..2) f[c + 1] = (a.p[0][c] + a.p[1][c] + a.p[2][c]) / 3
                }
            }
        }
        return found.entries.filter { it.value[0] > .01 }.map { (k, v) -> "%s at (%.1f, %.1f, %.1f) m, overlap %.3f m²".format(java.util.Locale.US, k, v[1], v[2], v[3], v[0]) }
    }

    /**
     * The landmarks whose faces were separated (0.1.976, 141429-141456: the flicker where the Great Meridian's pale
     * limestone meets its dark granite pier, and its frieze speckled with the brick behind it): no two faces of theirs
     * fight in one plane. The rest of the landmarks are listed (not failed): kotlin-street/tools/glb_coplanar.py
     * separates a model's faces the same way, the material drawn later over the earlier as the game already shows it.
     */
    @Test fun theSeparatedLandmarksHaveNoFacesFightingInOnePlane() {
        val root = File(assets, "landmarks")
        val separated = listOf("great-meridian-1926", "great-meridian-1941", "great-meridian-1976", "great-meridian-2000", "great-meridian-mid", "great-meridian-far")
            .map { File(root, "great-meridian-monument/$it.glb") }
        val bad = LinkedHashMap<String, List<String>>()
        for (f in separated) { val fs = landmarkFights(f); if (fs.isNotEmpty()) bad[f.name] = fs }
        for ((k, fs) in bad) println("$k: ${fs.size} fights\n  " + fs.take(8).joinToString("\n  "))
        // the others, for the record
        val rest = root.listFiles()!!.filter { it.isDirectory }.flatMap { d -> d.listFiles()!!.filter { it.name.endsWith(".glb") && it !in separated } }.sortedBy { it.path }
        for (f in rest) landmarkFights(f).takeIf { it.isNotEmpty() }?.let { println("not yet separated: ${f.parentFile.name}/${f.name}: ${it.size} part pairs fighting") }
        assertEquals("separated landmarks with faces fighting in one plane: " + bad.keys, 0, bad.size)
    }

    @Test fun theVillasHaveNoFacesFightingInOnePlane() {
        com.google.android.filament.Filament.init()
        val dir = File(assets, "town/gamma/buildings")
        val shapes = ModelShape.parseAll(File(dir, "models.json").readText())
        val all = LinkedHashMap<String, List<String>>()
        // (one model of each villa art: every b### of a villa wears one of a few)
        for ((key, shape) in shapes) {
            if (shape.kind != "villa") continue
            val atlas = open(File(dir, "${key}_atlas.png")) ?: continue
            val f = fights(ModelArt(shape, atlas, null, null))
            if (f.isNotEmpty()) all[key] = f
        }
        for ((k, f) in all) println("$k: ${f.size} fights\n  " + f.take(8).joinToString("\n  "))
        println("villas with fighting faces: ${all.size}")
        assertEquals("villas with faces fighting in one plane: " + all.keys.take(20), 0, all.size)
    }
}
