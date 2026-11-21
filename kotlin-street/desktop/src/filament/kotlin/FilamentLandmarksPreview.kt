import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.Material
import com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaLayer
import com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaLights
import com.lifemax.rpg.feature.habits.presentation.scene3d.GlbFile
import com.lifemax.rpg.feature.habits.presentation.scene.FrameWork
import com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkAnimator
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
    val movingDemo = System.getProperty("movingDemo")?.takeIf { it.isNotBlank() }
    if (movingDemo != null) {
        val movingOut = File(System.getProperty("movingOut")?.takeIf { it.isNotBlank() } ?: File(out, "moving").path)
        renderMovingDemo(File(movingDemo), movingOut)
        return
    }
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
        val anim = LandmarkAnimator.add(stage.props, layer, lm, at)
        val lamps = CinemaLights(engine, stage.scene).also { it.add(m.lights, at) }
        // the reference store to the left, its front on the line
        val rw = ref.block[2] - ref.block[0]
        val rx = fp[0] - rw / 2f - 160f - (ref.block[0] + ref.block[2]) / 2f
        layer.add(ref.model, floatArrayOf(rx, ref.block[1], 1f, 0f, 0f, -1f, 0f))
        var animHour = 13f
        stage.after = { dark -> anim?.update(animHour, 0.0, dark, active = true); layer.update(dark, 0.0); lamps.update(dark) }
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
            animHour = hour
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

/**
 * Desktop headless Filament demo & CPU benchmark for animated landmark nodes
 * (`-Dmode=filament-landmarks -DmovingDemo=/path/to/test-models -DmovingOut=/path/to/out`).
 * Loads the prototype Courthouse and Great Meridian models from [modelsDir] (kept outside the repo),
 * renders the courthouse clock at 3:00 and 4:30 (plus 1988 stopped at 4:17), renders a 4-frame night
 * strip of Great Meridian's rotating armillary rings and sweeping aviation beacon (plus a daytime
 * check and 12 armillary frames), and measures per-frame CPU cost and allocations with both active.
 */
private fun renderMovingDemo(modelsDir: File, outDir: File) {
    outDir.mkdirs()
    Filament.init()
    val engine = Engine.create(Engine.Backend.OPENGL)
    val assets = File(assetsDir(), "filament")
    fun mat(n: String): Material {
        val b = File(assets, "$n.filamat").readBytes()
        return Material.Builder().payload(ByteBuffer.allocateDirect(b.size).put(b).flip(), b.size).build(engine)
    }
    val lit = mat("street_lit")
    val proxy = mat("shadow_proxy")
    val U = LandmarkKit.UNITS_PER_METRE

    fun buildProto(sub: String, file: String, year: Int, kind: String, name: String, wM: Float, dM: Float): com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkModel {
        val dir = File(modelsDir, sub)
        val glb = GlbFile.read(File(dir, file).readBytes())
        val info = File(dir, "model-info.json").takeIf { it.exists() }?.readText()
        val spec = LandmarkSpec(
            kind = kind, name = name, file = file, info = "model-info.json", role = "civic",
            from = 1890, to = 2025, built = year, lotW = wM, lotD = dM, buildingW = wM * 0.6f, buildingD = dM * 0.6f,
        )
        return LandmarkKit.build(glb, spec, year, seed = 7, info = info)
    }

    val court1894 = buildProto("county-courthouse", "courthouse-1894.glb", 1894, "landmark_glb_courthouse", "County Courthouse", 88f, 88f)
    val court1988 = buildProto("county-courthouse", "courthouse-1988.glb", 1988, "landmark_glb_courthouse_1988", "County Courthouse (1988)", 88f, 88f)
    val meridian1926 = buildProto("great-meridian-monument", "great-meridian-1926.glb", 1926, "landmark_glb_meridian", "The Great Meridian Monument", 120f, 120f)

    // --- 1. Courthouse south clock dial: 3:00 (15:00 daylight), 4:30 (16:30 daylight), and 1988 stopped at 4:17 ---
    fun shootClock(lm: com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkModel, file: File, skyHour: Float, animHour: Float) {
        val stage = StoreStage(engine, lit, proxy, 960, 960, anisotropy = 1f)
        stage.view.setDynamicLightingOptions(10f, 6000f)
        val fp = lm.model.footprint
        stage.ground(fp[0] - 900f, fp[2] + 900f)
        field(stage, fp[0] - 900f, fp[2] + 900f, (fp[3] - fp[1]) + 1500f)
        val at = floatArrayOf(0f, lm.front, 1f, 0f, 0f, -1f, 0f)
        val layer = CinemaLayer(stage.props)
        layer.add(lm.model, at)
        val anim = LandmarkAnimator.add(stage.props, layer, lm, at)
        val lamps = CinemaLights(engine, stage.scene).also { it.add(lm.model.lights, at) }
        stage.after = { dark -> anim?.update(animHour, 0.0, dark, active = true); layer.update(dark, 0.0); lamps.update(dark) }
        val hand = lm.animated.firstOrNull { it.nodeName == "clock_hour_s" } ?: lm.animated.first { it.nodeName.startsWith("clock_hour") }
        val px = hand.restModel[12]
        val py = hand.restModel[13]
        val pz = lm.front - hand.restModel[14]
        val eye = floatArrayOf(px + 0.6f * U, py + 0.15f * U, pz - 8.2f * U)
        val look = floatArrayOf(px, py, pz)
        stage.shoot(file, skyHour, eye, look, fov = 30f)
        lamps.destroy()
        stage.destroy()
    }

    val f300 = File(outDir, "courthouse_clock_3_00.png")
    val f430 = File(outDir, "courthouse_clock_4_30.png")
    val f417 = File(outDir, "courthouse_1988_stopped_4_17.png")
    shootClock(court1894, f300, skyHour = 14.5f, animHour = 15.0f)
    shootClock(court1894, f430, skyHour = 14.5f, animHour = 16.5f)
    shootClock(court1988, f417, skyHour = 14.5f, animHour = 15.0f)
    stitchRow(
        listOf(
            f300 to "1894 Courthouse Clock — 3:00 (hour=15.0)",
            f430 to "1894 Courthouse Clock — 4:30 (hour=16.5)",
            f417 to "1988 Courthouse — Stopped at 4:17 (hour=15.0)",
        ),
        File(outDir, "courthouse_clock_comparison.png"),
    )

    // --- 2. Great Meridian Monument: 4-frame night strip (rings + sweeping beacon), close-up rings strip, 12 GIF frames, daytime check ---
    run {
        val lm = meridian1926
        val fp = lm.model.footprint
        val cz = lm.front - (-59.9848f * U)
        val at = floatArrayOf(0f, lm.front, 1f, 0f, 0f, -1f, 0f)

        // 2a. Full monument + beacon at night (4 frames, 720x900 each -> 2880x900 strip) + daytime check
        val nightStage = StoreStage(engine, lit, proxy, 720, 900, anisotropy = 1f)
        nightStage.view.setDynamicLightingOptions(10f, 10000f)
        nightStage.ground(fp[0] - 1200f, fp[2] + 1200f)
        field(nightStage, fp[0] - 1200f, fp[2] + 1200f, (fp[3] - fp[1]) + 2000f)
        val nightLayer = CinemaLayer(nightStage.props)
        nightLayer.add(lm.model, at)
        val nightAnim = LandmarkAnimator.add(nightStage.props, nightLayer, lm, at)
        val nightLamps = CinemaLights(engine, nightStage.scene).also { it.add(lm.model.lights, at) }
        var curHour = 0f
        var curSec = 0.0
        nightStage.after = { dark ->
            nightAnim?.update(curHour, curSec, dark, active = true)
            nightLayer.update(dark, curSec)
            nightLamps.update(dark)
        }
        val eyeFull = floatArrayOf(-76f * U, 74f * U, cz - 128f * U)
        val lookFull = floatArrayOf(0f, 62f * U, cz)
        val nightFrames = mutableListOf<Pair<File, String>>()
        val steps = listOf(0f to 0.0, 6f to 2.5, 12f to 5.0, 18f to 7.5)
        for ((idx, st) in steps.withIndex()) {
            curHour = st.first; curSec = st.second
            val outF = File(outDir, "meridian_night_f$idx.png")
            nightStage.shoot(outF, hour = 22.5f, eye = eyeFull, at = lookFull, fov = 56f)
            nightFrames += outF to "Frame ${idx + 1} — beacon t=${"%.1f".format(curSec)}s (${idx * 90}°), rings h=${curHour.toInt()}:00"
        }
        stitchRow(nightFrames, File(outDir, "meridian_night_strip.png"))
        // Daytime check: beacon must be hidden
        curHour = 13.0f; curSec = 2.5
        nightStage.shoot(File(outDir, "meridian_day_check.png"), hour = 13.0f, eye = eyeFull, at = lookFull, fov = 56f)
        nightLamps.destroy()
        nightStage.destroy()

        // 2b. Armillary sphere close-up at night (4-frame strip + 12 frames for optional GIF)
        val ringStage = StoreStage(engine, lit, proxy, 640, 640, anisotropy = 1f)
        ringStage.view.setDynamicLightingOptions(10f, 10000f)
        ringStage.ground(fp[0] - 1200f, fp[2] + 1200f)
        field(ringStage, fp[0] - 1200f, fp[2] + 1200f, (fp[3] - fp[1]) + 2000f)
        val ringLayer = CinemaLayer(ringStage.props)
        ringLayer.add(lm.model, at)
        val ringAnim = LandmarkAnimator.add(ringStage.props, ringLayer, lm, at)
        val ringLamps = CinemaLights(engine, ringStage.scene).also { it.add(lm.model.lights, at) }
        var rHour = 0f; var rSec = 0.0
        ringStage.after = { dark ->
            ringAnim?.update(rHour, rSec, dark, active = true)
            ringLayer.update(dark, rSec)
            ringLamps.update(dark)
        }
        val eyeRing = floatArrayOf(3.8f * U, 42.5f * U, cz - 29.0f * U)
        val lookRing = floatArrayOf(0f, 41.0f * U, cz)
        val gifDir = File(outDir, "rings_frames").also { it.mkdirs() }
        val ringStrip = mutableListOf<Pair<File, String>>()
        for (i in 0 until 12) {
            rHour = i * 2f
            rSec = i * (10.0 / 12.0)
            val f = File(gifDir, "ring_%02d.png".format(i))
            ringStage.shoot(f, hour = 22.5f, eye = eyeRing, at = lookRing, fov = 48f)
            if (i % 3 == 0) {
                ringStrip += f to "Armillary h=${rHour.toInt()}:00 (${(i * 30)}°)"
            }
        }
        stitchRow(ringStrip, File(outDir, "meridian_rings_strip.png"))
        ringLamps.destroy()
        ringStage.destroy()
    }

    // --- 3. Measured extra per-frame CPU cost with BOTH landmarks animating ---
    run {
        val benchStage = StoreStage(engine, lit, proxy, 1280, 720, anisotropy = 1f)
        benchStage.view.setDynamicLightingOptions(10f, 10000f)
        val layer = CinemaLayer(benchStage.props)
        val atCourt = floatArrayOf(-80f * U, court1894.front, 1f, 0f, 0f, -1f, 0f)
        val atMerid = floatArrayOf(+80f * U, meridian1926.front, 1f, 0f, 0f, -1f, 0f)
        layer.add(court1894.model, atCourt)
        layer.add(meridian1926.model, atMerid)
        val courtAnim = LandmarkAnimator.add(benchStage.props, layer, court1894, atCourt)!!
        val meridAnim = LandmarkAnimator.add(benchStage.props, layer, meridian1926, atMerid)!!
        // Trigger bells so all 5 animation kinds (clock, spin, sweep beam, swing bells, lift) are active
        courtAnim.ringBells(0.0, swings = 500)

        val look = benchStage.look
        val warm = 300
        val iters = 2000
        val dark = 1.0f // night: beacon active + lit materials active

        // Warmup
        for (i in 0 until warm) {
            val sec = i * (1.0 / 60.0)
            val hr = 22.0f + (sec / 120.0).toFloat()
            courtAnim.update(hr, sec, dark, active = true)
            meridAnim.update(hr, sec, dark, active = true)
            benchStage.props.update(look, dark)
            layer.update(dark, sec)
        }

        val tmb = java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        val tid = Thread.currentThread().id

        val samples = LongArray(iters)
        fun formatStats(label: String, allocBytes: Long, fwDelta: Long): String {
            samples.sort()
            var sum = 0L
            for (k in 0 until iters) sum += samples[k]
            val meanUs = (sum.toDouble() / iters) / 1000.0
            val p50Us = samples[iters / 2] / 1000.0
            val p95Us = samples[(iters * 95) / 100] / 1000.0
            val maxUs = samples[iters - 1] / 1000.0
            return "$label: mean=${"%.2f".format(meanUs)} us, p50=${"%.2f".format(p50Us)} us, p95=${"%.2f".format(p95Us)} us, max=${"%.2f".format(maxUs)} us | alloc=$allocBytes B over $iters frames (${"%.2f".format(allocBytes.toDouble() / iters)} B/frame) | GPU upload delta=${fwDelta} B"
        }

        val rBaseline = run {
            val a0 = tmb?.getThreadAllocatedBytes(tid) ?: -1L
            val fw0 = FrameWork.bytes
            var i = 0
            while (i < iters) {
                val sec = i * (1.0 / 60.0)
                val t0 = System.nanoTime()
                benchStage.props.update(look, dark)
                layer.update(dark, sec)
                samples[i] = System.nanoTime() - t0
                i++
            }
            val fw1 = FrameWork.bytes
            val a1 = tmb?.getThreadAllocatedBytes(tid) ?: -1L
            formatStats("Baseline (PropLayer + CinemaLayer only)", if (a0 >= 0 && a1 >= 0) a1 - a0 else -1L, fw1 - fw0)
        }
        val rAnimOnly = run {
            val a0 = tmb?.getThreadAllocatedBytes(tid) ?: -1L
            val fw0 = FrameWork.bytes
            var i = 0
            while (i < iters) {
                val sec = i * (1.0 / 60.0)
                val hr = 22.0f + (sec / 120.0).toFloat()
                val t0 = System.nanoTime()
                courtAnim.update(hr, sec, dark, active = true)
                meridAnim.update(hr, sec, dark, active = true)
                samples[i] = System.nanoTime() - t0
                i++
            }
            val fw1 = FrameWork.bytes
            val a1 = tmb?.getThreadAllocatedBytes(tid) ?: -1L
            formatStats("Extra cost (LandmarkAnimator.update x2: ${courtAnim.nodeCount + meridAnim.nodeCount} animated nodes)", if (a0 >= 0 && a1 >= 0) a1 - a0 else -1L, fw1 - fw0)
        }
        val rCombined = run {
            val a0 = tmb?.getThreadAllocatedBytes(tid) ?: -1L
            val fw0 = FrameWork.bytes
            var i = 0
            while (i < iters) {
                val sec = i * (1.0 / 60.0)
                val hr = 22.0f + (sec / 120.0).toFloat()
                val t0 = System.nanoTime()
                courtAnim.update(hr, sec, dark, active = true)
                meridAnim.update(hr, sec, dark, active = true)
                benchStage.props.update(look, dark)
                layer.update(dark, sec)
                samples[i] = System.nanoTime() - t0
                i++
            }
            val fw1 = FrameWork.bytes
            val a1 = tmb?.getThreadAllocatedBytes(tid) ?: -1L
            formatStats("Combined (Animators x2 + PropLayer + CinemaLayer)", if (a0 >= 0 && a1 >= 0) a1 - a0 else -1L, fw1 - fw0)
        }

        val summary = buildString {
            appendLine("=== Moving Landmarks Desktop Headless Benchmark ===")
            appendLine("Courthouse (1894): ${courtAnim.nodeCount} animated nodes (clock hands + swinging bells)")
            appendLine("Great Meridian (1926): ${meridAnim.nodeCount} animated nodes (armillary rings + night beacon + lift car)")
            appendLine("Warmup: $warm frames, Measured: $iters frames (night, all 5 animation kinds active)")
            appendLine(rBaseline)
            appendLine(rAnimOnly)
            appendLine(rCombined)
        }
        File(outDir, "moving_bench.txt").writeText(summary)
        print(summary)
        benchStage.destroy()
    }

    engine.destroyMaterial(lit)
    engine.destroyMaterial(proxy)
    engine.destroy()
}

/** Stitches [frames] (file to caption) side-by-side into [out] with a clean dark header bar. */
private fun stitchRow(frames: List<Pair<File, String>>, out: File) {
    if (frames.isEmpty()) return
    val imgs = frames.map { (f, cap) -> ImageIO.read(f) to cap }
    val w = imgs.first().first.width
    val h = imgs.first().first.height
    val bar = 44
    val gap = 8
    val totalW = imgs.size * w + (imgs.size - 1) * gap
    val canvas = java.awt.image.BufferedImage(totalW, h + bar, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val g = canvas.createGraphics()
    g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.color = java.awt.Color(18, 20, 26)
    g.fillRect(0, 0, totalW, h + bar)
    g.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 18)
    for ((idx, pair) in imgs.withIndex()) {
        val x = idx * (w + gap)
        g.drawImage(pair.first, x, bar, null)
        g.color = java.awt.Color(235, 240, 248)
        g.drawString(pair.second, x + 14, 29)
    }
    g.dispose()
    ImageIO.write(canvas, "png", out)
}
