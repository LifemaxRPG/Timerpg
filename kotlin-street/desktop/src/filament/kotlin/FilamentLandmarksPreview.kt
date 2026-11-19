import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.Material
import com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaLayer
import com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaLights
import com.lifemax.rpg.feature.habits.presentation.scene3d.GlbFile
import com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkKit
import com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkSpec
import com.lifemax.rpg.feature.habits.presentation.scene3d.PropLayer
import com.lifemax.rpg.feature.habits.presentation.scene3d.RoomKit
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreInfo
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreKind
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreKit
import java.io.File
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import kotlin.math.atan2
import kotlin.math.max

/**
 * The hand-made landmark models' desktop renders (-Dmode=filament-landmarks; docs/LANDMARKS_GLB.md): each model of
 * assets/landmarks/landmarks.json built by [LandmarkKit] for its year, on its lot by the road, with two people for scale
 * and one of our pack stores beside it (its front on the same line), from the front, three-quarters, behind, above and
 * a person's height by day, and the front, three-quarters and a person's height at dusk and night; its collision boxes
 * drawn as a plan over the above view (`*_collision.png`). -Dlandmarks=diner,gas (names or parts of kinds) for some;
 * -Dyear= for another year (the price board). Writes build/preview/landmarks/ and landmarks.txt.
 */
fun renderLandmarks(out: String) {
    val dir = File(out, "landmarks").also { it.mkdirs() }
    val root = File(assetsDir(), "landmarks")
    val specs = LandmarkSpec.catalogue(File(root, "landmarks.json").readText())
    val only = System.getProperty("landmarks")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
    val chosen = specs.filter { s -> only == null || only.any { s.kind.contains(it) || s.file.contains(it) } }
    val chars = File(System.getProperty("chars") ?: File(assetsDir(), "characters").path)
    Filament.init()
    val engine = Engine.create(Engine.Backend.OPENGL)
    val assets = File(assetsDir(), "filament")
    fun mat(n: String): Material { val b = File(assets, "$n.filamat").readBytes(); return Material.Builder().payload(ByteBuffer.allocateDirect(b.size).put(b).flip(), b.size).build(engine) }
    val lit = mat("street_lit"); val proxy = mat("shadow_proxy")
    val report = StringBuilder()
    // two people for scale (the characters' front view, 32 x 64 art)
    val people = listOf("char_01.png", "char_07.png").mapNotNull { n -> File(chars, n).takeIf { it.exists() }?.let { ImageIO.read(it) } }
        .map { s -> RoomKit.card(RoomKit.pix(32, 64, s.getRGB(3 * 6 * 32, 64, 32, 64, null, 0, 32))) }
    // one of our own buildings beside it, for scale: a pack market (the same row's frontage)
    val ref = StoreKit.buildBare(StoreInfo.sample(StoreKind.MARKET, 1962, seed = 3).copy(style = "pack:market_m_5"))
    for (spec in chosen) {
        val year = System.getProperty("year")?.toIntOrNull() ?: spec.built
        val t0 = System.nanoTime()
        val glb = GlbFile.read(File(root, spec.file).readBytes())
        val info = spec.info?.let { File(root, it) }?.takeIf { it.exists() }?.readText()
        val lm = LandmarkKit.build(glb, spec, year, seed = 7, info = info)
        val ms = (System.nanoTime() - t0) / 1e6
        val m = lm.model
        val U = LandmarkKit.UNITS_PER_METRE
        val fp = m.footprint
        val tag = spec.kind.removePrefix("landmark_glb_")
        report.appendLine("$tag (${spec.name}, $year): ${m.parts.size} parts, ${m.triangles} triangles, ${m.lights.size} lights, pictures ${m.bytes / 1024} KiB, " +
            "built in ${"%.0f".format(ms)} ms; footprint x ${"%.1f".format(fp[0] / U)}..${"%.1f".format(fp[2] / U)} m, z ${"%.1f".format(fp[1] / U)}..${"%.1f".format(fp[3] / U)} m, " +
            "${"%.1f".format(m.height / U)} m tall (${m.height.toInt()} units), lot front z ${"%.1f".format(lm.front / U)} m; ${lm.solids.size / 4} collision boxes; doors " +
            lm.doors.joinToString { "${it.name} (${"%.1f".format(it.x / U)}, ${"%.1f".format(it.z / U)}) out (${"%.0f".format(it.outX)}, ${"%.0f".format(it.outZ)})" } +
            "; materials ${lm.names.zip(m.parts).joinToString(" ") { (n, p) -> "$n:${p.role.name.lowercase()}:${p.mesh.texW}x${p.mesh.texH}${if (p.mesh.mask != null) "+mask" else ""}" }}")
        val w = 1600; val h = 900
        // (anisotropy 1: the desktop's software GL blurs a magnified picture with it; the phone does not)
        val stage = StoreStage(engine, lit, proxy, w, h, anisotropy = 1f)
        // (the lights' grid reaches the whole lot: Filament's default stops at 100 units, a few metres of ours)
        stage.view.setDynamicLightingOptions(10f, 6000f)
        val L = fp[2] - fp[0]; val D = fp[3] - fp[1]
        val x0 = -L - 900f; val x1 = L + 900f
        stage.ground(x0, x1)
        field(stage, x0, x1, D + 1500f)
        // the model: its lot's front edge on the street's building line (world z 0), the model's +z out to the road (-z)
        val at = floatArrayOf(0f, lm.front, 1f, 0f, 0f, -1f, 0f)
        val layer = CinemaLayer(stage.props)
        layer.add(m, at)
        val lamps = CinemaLights(engine, stage.scene).also { it.add(m.lights, at) }
        // the reference store to the left, its front on the line
        val rw = ref.block[2] - ref.block[0]
        val rx = fp[0] - rw / 2f - 160f - (ref.block[0] + ref.block[2]) / 2f
        layer.add(ref.model, floatArrayOf(rx, ref.block[1], 1f, 0f, 0f, -1f, 0f))
        stage.after = { dark -> layer.update(dark, 0.0); lamps.update(dark) }
        // the world: model (x, z) -> (x, front - z)
        fun wz(mz: Float) = lm.front - mz
        val door = lm.doors.firstOrNull { it.name == "door_main" } ?: lm.doors.firstOrNull()
        val dx = door?.x ?: 0f; val dz = wz(door?.z ?: 0f)
        val cxm = (fp[0] + fp[2]) / 2f; val czw = wz((fp[1] + fp[3]) / 2f)
        val bz = wz(-spec.buildingD * U / 2f)
        val top = m.height
        // (a tall tower needs the cameras further out: its height counts like a side)
        val tall = top > 1.5f * max(L, D)
        val span = if (tall) top * 1.35f else max(L, D)
        val aim = if (tall) .55f else .25f
        val shots = listOf(
            "front" to Triple(floatArrayOf(cxm, 90f + span * .05f, -span * .9f - 200f), floatArrayOf(cxm, top * (if (tall) .55f else .35f), bz), 50f),
            "quarter" to Triple(floatArrayOf(cxm - span * .95f - 150f, 220f + span * .3f, -span * .75f - 120f), floatArrayOf(cxm, top * aim, czw), 52f),
            "behind" to Triple(floatArrayOf(cxm + span * .85f + 150f, 230f + span * .3f, wz(fp[1]) + span * .7f + 200f), floatArrayOf(cxm, top * aim, czw), 52f),
            "above" to Triple(floatArrayOf(cxm - span * .1f, span * 1.25f + 400f, czw - span * .45f - 150f), floatArrayOf(cxm, 0f, czw), 52f),
            // a person's eyes (1.6 m) some 7 m out from the main door, a little to one side, looking at it
            "person" to personEye(lm, door, dx, dz),
            // the strip centre's storefront: a person's eyes (1.6 m) 13.5 m out in the car park, looking along the two bays of the store
            "store" to Triple(floatArrayOf(dx + .85f * U, 1.6f * U, dz - 13.5f * U), floatArrayOf(dx + .85f * U, 2.2f * U, dz), 68f),
        )
        val strip = spec.kind.contains("reel_time_video")
        val hours = listOf(13f to listOf("front", "quarter", "behind", "above", "person") + (if (strip) listOf("store") else emptyList()), 19.3f to listOf("front", "quarter", "person"),
            22.5f to listOf("front", "quarter", "person") + (if (strip) listOf("store") else emptyList()))
        for ((hour, names) in hours) for (sn in names) {
            val (eye, look, fov) = shots.first { it.first == sn }.second
            // the people face the camera: one at the main door, one by the lot's front corner
            val faces = PropLayer(engine, stage.scene, lit, proxy, anisotropy = 1f)
            if (people.isNotEmpty() && sn != "store") {
                val spots = listOf(floatArrayOf(dx - 70f, dz - 40f), floatArrayOf(fp[0] + 120f, -20f))
                for ((k, s) in spots.withIndex()) {
                    val turn = atan2(eye[0] - s[0], -(eye[2] - s[1]))
                    faces.addMesh(people[k % people.size], listOf(floatArrayOf(s[0], s[1], 1f, 0f, 0f, -1f, turn)), casts = true)
                }
            }
            val after0 = stage.after
            stage.after = { dark -> after0(dark); faces.update(stage.look, dark) }
            val ht = "%.1f".format(hour).replace(".0", "").replace(".", "_")
            stage.shoot(File(dir, "${tag}_${sn}_$ht.png"), hour, eye, look, fov)
            stage.after = after0
            faces.destroy()
        }
        // the collision boxes as a plan over the above view: red boxes, blue doors (model frame -> the picture's pixels: drawn top-down)
        plan(File(dir, "${tag}_collision.png"), lm, fp)
        lamps.destroy()
        stage.destroy()
    }
    engine.destroyMaterial(lit); engine.destroyMaterial(proxy)
    engine.destroy()
    File(dir, "landmarks.txt").writeText(report.toString())
    print(report)
}

/**
 * A person's eyes (1.6 m) some 5-9 m out from [door] with nothing solid between them and it (pumps, signs), looking at
 * it; ([dx], [dz]) the door in the world (model z -> world front - z).
 */
private fun personEye(lm: com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkModel, door: com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkDoor?, dx: Float, dz: Float): Triple<FloatArray, FloatArray, Float> {
    val mx = door?.x ?: 0f; val mz = door?.z ?: 0f
    fun clear(ex: Float, ez: Float): Boolean {
        var i = 0
        while (i + 3 < lm.solids.size) {
            val a = lm.solids
            for (k in 1..19) { val t = k / 20f; val x = ex + (mx - ex) * t; val z = ez + (mz - ez) * t; if (x > a[i] - 8f && x < a[i + 2] + 8f && z > a[i + 1] - 8f && z < a[i + 3] + 8f) return false }
            i += 4
        }
        return true
    }
    for (d in listOf(150f, 110f, 190f)) for (side in listOf(50f, -50f, 110f, -110f, 0f)) {
        val ex = mx + side; val ez = mz + d
        if (clear(ex, ez)) return Triple(floatArrayOf(ex, 34f, lm.front - ez), floatArrayOf(dx - side * .1f, 50f, dz + 40f), 62f)
    }
    return Triple(floatArrayOf(dx + 50f, 34f, dz - 150f), floatArrayOf(dx - 5f, 50f, dz + 40f), 62f)
}

/** A top-down plan of [lm]: its parts' triangles in grey, its collision boxes in red, its doors in blue. */
private fun plan(file: File, lm: com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkModel, fp: FloatArray) {
    val pad = 40f
    val s = 900f / max(fp[2] - fp[0], fp[3] - fp[1])
    val w = ((fp[2] - fp[0]) * s + pad * 2).toInt(); val h = ((fp[3] - fp[1]) * s + pad * 2).toInt()
    val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.color = java.awt.Color(240, 240, 235); g.fillRect(0, 0, w, h)
    // x right as seen from the street, the road at the bottom (model +z)
    fun px(x: Float) = ((x - fp[0]) * s + pad).toInt()
    fun pz(z: Float) = ((z - fp[1]) * s + pad).toInt()
    g.color = java.awt.Color(150, 150, 150, 90)
    for (p in lm.model.parts) {
        val pos = p.mesh.pos; val idx = p.mesh.idx
        var t = 0
        while (t + 2 < idx.size) {
            val xs = IntArray(3) { px(pos[idx[t + it] * 3]) }; val zs = IntArray(3) { pz(pos[idx[t + it] * 3 + 2]) }
            g.drawPolygon(xs, zs, 3); t += 3
        }
    }
    g.color = java.awt.Color(210, 40, 40, 150)
    var i = 0
    while (i + 3 < lm.solids.size) {
        val a = lm.solids
        g.fillRect(px(a[i]), pz(a[i + 1]), max(1, ((a[i + 2] - a[i]) * s).toInt()), max(1, ((a[i + 3] - a[i + 1]) * s).toInt())); i += 4
    }
    g.color = java.awt.Color(30, 80, 220)
    for (d in lm.doors) { g.fillOval(px(d.x) - 6, pz(d.z) - 6, 12, 12); g.drawLine(px(d.x), pz(d.z), px(d.x + d.outX * 40f), pz(d.z + d.outZ * 40f)) }
    g.dispose()
    ImageIO.write(img, "png", file)
}
