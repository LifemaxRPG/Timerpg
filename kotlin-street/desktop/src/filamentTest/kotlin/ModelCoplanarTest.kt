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
