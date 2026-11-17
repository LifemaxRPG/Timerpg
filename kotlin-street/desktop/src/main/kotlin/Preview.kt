@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

import com.lifemax.rpg.feature.habits.presentation.scene.junctionView
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import com.lifemax.rpg.feature.habits.domain.*
import com.lifemax.rpg.feature.habits.presentation.HabitsUi
import com.lifemax.rpg.feature.habits.presentation.scene.PlatformImages
import com.lifemax.rpg.feature.habits.presentation.scene.HabitsStage
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteSheets
import com.lifemax.rpg.feature.habits.presentation.scene.GameState
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteArt
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuilding
import com.lifemax.rpg.feature.habits.presentation.scene.StageTuning
import com.lifemax.rpg.feature.habits.presentation.scene.StageActions
import java.io.File
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Image as SkiaImage

/** Desktop stand-ins for the two Android-only image calls (see PlatformImages). */
fun installDesktopImages() {
    fun premulBytes(px: IntArray): ByteArray {
        val out = ByteArray(px.size * 4)
        for (i in px.indices) {
            val c = px[i]; val a = c ushr 24 and 255
            val r = (c shr 16 and 255) * a / 255; val g = (c shr 8 and 255) * a / 255; val b = (c and 255) * a / 255
            out[i * 4] = b.toByte(); out[i * 4 + 1] = g.toByte(); out[i * 4 + 2] = r.toByte(); out[i * 4 + 3] = a.toByte()
        }
        return out
    }
    PlatformImages.create = { px, w, h, _ ->
        val bm = Bitmap()
        bm.installPixels(ImageInfo.makeN32(w, h, ColorAlphaType.PREMUL), premulBytes(px), w * 4)
        bm.setImmutable()
        bm.asComposeImageBitmap()
    }
    PlatformImages.update = { img: ImageBitmap, px, w, h ->
        val bm = img.asSkiaBitmap()
        bm.installPixels(ImageInfo.makeN32(w, h, ColorAlphaType.PREMUL), premulBytes(px), w * 4)
        bm.notifyPixelsChanged()
    }
}

private fun floor(id: String, name: String, material: HabitMaterial, size: Int = 1) = HabitBlock(
    id = id, kind = HabitKind.BUILD, name = name, names = listOf(name, name, name), size = size, set = size, reps = 30,
    done = true, miss = 0, scar = false, material = material, left = 0, floors = 1, cleared = false, pair = "",
)

private fun building(id: String, shop: String, sign: String, material: HabitMaterial, floors: Int) = HabitStack(
    id = id, cue = shop, treat = sign, claimed = false, claimable = false, removable = false, next = "",
    blocks = (1..floors).map { floor("$id-$it", "Flat $it", material, if (it % 2 == 0) 2 else 1) },
)

/** The same exported town the Android app loads (app/src/main/assets/street.json), with our building sprites. */
fun streetOfShops(): List<HabitStack> {
    val dir = File("../app/src/main/assets").takeIf { File(it, "street.json").exists() } ?: File("app/src/main/assets")
    val arr = org.json.JSONObject(File(dir, "street.json").readText()).getJSONArray("shops")
    fun img(n: String): androidx.compose.ui.graphics.ImageBitmap? =
        File(dir, "buildings/$n.png").takeIf { it.exists() }?.let { SkiaImage.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
    // which buildings: one of the named streets (-Dstreet=0|1), else every building in the file
    val root = org.json.JSONObject(File(dir, "street.json").readText())
    val which = System.getProperty("street")?.toIntOrNull()
    val wanted: Set<String>? = root.optJSONArray("streets")?.let { st -> which?.let { k -> st.optJSONObject(k)?.optJSONArray("buildings")?.let { b -> (0 until b.length()).map { b.getString(it) } } } }?.toSet()
    val order = wanted?.let { w -> root.getJSONArray("streets").getJSONObject(which!!).getJSONArray("buildings").let { b -> (0 until b.length()).map { b.getString(it) } } }
    // the main street goes on past the junction (as in the app): the side street's mouth, then the buildings east of it
    val east = if (which == 0) root.optJSONObject("junction")?.optJSONArray("mainEast")?.let { b -> (0 until b.length()).map { b.getString(it) } }.orEmpty() else emptyList()
    val idx = (0 until arr.length()).filter { wanted == null || arr.getJSONObject(it).getString("sprite") in wanted }
        .sortedBy { order?.indexOf(arr.getJSONObject(it).getString("sprite")) ?: it }
    val eastIdx = east.mapNotNull { n -> (0 until arr.length()).firstOrNull { arr.getJSONObject(it).getString("sprite") == n } }
    fun one(i: Int): HabitStack {
        val o = arr.getJSONObject(i)
        val id = o.getString("id"); val n = o.getString("sprite")
        val mids = (0 until o.getJSONArray("parts").length()).count { o.getJSONArray("parts").getJSONObject(it).getString("key").startsWith("middle_") }
        if (SpriteArt.buildings[id] == null) img("${n}_front")?.let { front ->
            val wj = File(dir, "buildings/windows.json").takeIf { it.exists() }?.let { org.json.JSONObject(it.readText()) }
            val ws = wj?.optJSONArray(n)?.let { a -> (0 until a.length()).map { k -> val r = a.getJSONArray(k); FloatArray(4) { r.getDouble(it).toFloat() } } }.orEmpty()
            SpriteArt.buildings[id] = SpriteBuilding(front, img("${n}_side")!!, img("${n}_emis")!!, img("${n}_roof"), img("${n}_sign"), img("${n}_prop"), mids, ws, wj?.optJSONArray(n + "_shop")?.let { a -> (0 until a.length()).map { k -> val r = a.getJSONArray(k); FloatArray(4) { r.getDouble(it).toFloat() } } }.orEmpty(), img("${n}_glass"),
                wj?.optJSONArray(n + "_relief")?.optJSONArray(0)?.let { r -> FloatArray(r.length()) { r.getDouble(it).toFloat() } }?.takeIf { it.size >= 7 }, img("${n}_over"), img("${n}_lit"), img("${n}_shade"))
        }
        return building(id, o.getString("name"), "", HabitMaterial.CLAY, mids)
    }
    val west = idx.map { one(it) }
    if (eastIdx.isEmpty()) return west
    val after = eastIdx.map { one(it) }
    StageTuning.cornerIds = setOfNotNull(west.lastOrNull()?.id, after.firstOrNull()?.id)
    return west + building(com.lifemax.rpg.feature.habits.domain.JUNCTION_ID, "Orchard Lane", "", HabitMaterial.CLAY, 1) + after
}

fun fallbackShops() = listOf(
    building("bakery", "Garrity's Bakery", "FRESH BREAD", HabitMaterial.CLAY, 3),
    building("bank", "First Bank", "", HabitMaterial.STONE, 4),
    building("icecream", "Cone & Co", "ICE CREAM", HabitMaterial.CLAY, 2),
    building("butcher", "Larsen's Butcher", "", HabitMaterial.CLAY, 3),
    building("gym", "Iron Gym", "FITNESS", HabitMaterial.STONE, 3),
)

fun projection(hour: Float, day: String) = HabitsProjection(
    day = day, hour = hour, who = "", whoHint = "", rough = false, built = 0, due = 0, reducedMotion = true, haptics = null,
    problem = "", limits = HabitLimits(40, 40, 40, 40, 40, 1, 12, 12), rules = HabitRules(10, 40, 100, 12),
    stacks = streetOfShops(), lapse = emptyList(), tokens = emptyMap(), light = false,
)

fun render(path: String, w: Int, h: Int, hour: Float, day: String, focus: String, city: Boolean, bottom: Float = 0f, ambient: Float = 70f) {
    val density = 2f
    val scene = ImageComposeScene(width = (w * density).toInt(), height = (h * density).toInt(), density = Density(density)) {
        HabitsStage(
            p = projection(hour, day), ui = HabitsUi(city = city, focus = focus), visible = true, top = 0f, bottom = bottom * density, cover = 0f, inset = 0f,
            accent = Color(0xFF6FA8FF), actions = StageActions(), ambientAt = ambient,
        )
    }
    // let the baked sprites finish (they are made off the main thread) while frames tick
    var nanos = 0L
    val deadline = System.currentTimeMillis() + 90_000
    var img = scene.render(nanos)
    var settled = 0
    while (settled < 12 && System.currentTimeMillis() < deadline) {
        nanos += 16_666_667L; Thread.sleep(40)
        img = scene.render(nanos); settled++
    }
    File(path).also { it.parentFile.mkdirs() }.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    scene.close()
    println("wrote $path")
}

/** Our character sheets, read from the game's asset folder. */
fun loadSheets(dir: String) {
    SpriteSheets.sheets = (1..8).mapNotNull { n ->
        val f = File(dir, "char_0$n.png")
        if (f.exists()) SkiaImage.makeFromEncoded(f.readBytes()).toComposeImageBitmap() else null
    }
}

/** Our lamp, trees and bench (from the game's asset folder). */
fun loadProps(assets: String) {
    fun img(rel: String) = SkiaImage.makeFromEncoded(File(assets, rel).readBytes()).toComposeImageBitmap()
    SpriteArt.lamp = img("lamp/lamp_1.png")
    SpriteArt.bench = img("bench/bench_1.png")
    SpriteArt.trees = listOf("tree_1", "tree_2", "tree_3").map { img("tree/$it.png") }
}

/** Renders a short walk: the scene runs on, [GameState.moveX] set per phase, and a frame is saved every so often. */
fun renderWalk(prefix: String, w: Int, h: Int, hour: Float, focus: String, phases: List<Pair<Float, Int>>, saveEvery: Int) {
    val density = 2f
    val scene = ImageComposeScene(width = (w * density).toInt(), height = (h * density).toInt(), density = Density(density)) {
        HabitsStage(p = projection(hour, "2026-06-14"), ui = HabitsUi(focus = focus), visible = true, top = 0f, bottom = 40f * density, cover = 0f, inset = 0f,
            accent = Color(0xFF6FA8FF), actions = StageActions())
    }
    var nanos = 0L; var n = 0; var saved = 0
    // warm up (sprites bake off the main thread)
    repeat(40) { nanos += 33_333_333L; Thread.sleep(50); scene.render(nanos) }
    for ((move, frames) in phases) {
        GameState.moveX = move
        repeat(frames) {
            nanos += 33_333_333L; Thread.sleep(4)
            val img = scene.render(nanos); n++
            if (n % saveEvery == 0) {
                File("$prefix${saved++}.png").also { it.parentFile.mkdirs() }.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                println("walk frame $n x=${GameState.x} dir=${GameState.dir} walking=${GameState.walking}")
            }
        }
    }
    GameState.moveX = 0f
    scene.close()
}

fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    installDesktopImages()
    if (System.getProperty("mode") == "voice") { renderVoices(args.getOrElse(0) { "build/preview" }); return }
    if (System.getProperty("mode") == "cross") { loadSheets(args.getOrElse(1) { "../public/assets/characters" }); loadProps(File(args.getOrElse(1) { "../public/assets/characters" }).parentFile.path); GameState.enabled = true; GameState.look = 1; StageTuning.zoom = 0.2f; GameState.lane = 7f; renderCrossing(args.getOrElse(0) { "build/preview" }, File("../app/src/main/assets").absolutePath); return }
    if (System.getProperty("mode") == "turnsky") { loadSheets(args.getOrElse(1) { "../public/assets/characters" }); loadProps(File(args.getOrElse(1) { "../public/assets/characters" }).parentFile.path); GameState.enabled = true; GameState.look = 1; StageTuning.zoom = 0.2f; GameState.lane = 7f; renderTurnOverStreet(args.getOrElse(0) { "build/preview" }, File("../app/src/main/assets").absolutePath); return }
    if (System.getProperty("mode") == "turn") { renderTurn(args.getOrElse(0) { "build/preview" }, File("../app/src/main/assets").absolutePath); return }
    loadSheets(args.getOrElse(1) { "../public/assets/characters" })
    loadProps(File(args.getOrElse(1) { "../public/assets/characters" }).parentFile.path)
    val out = args.getOrElse(0) { "build/preview" }
    if (System.getProperty("onlyone") != null) {
        GameState.enabled = true; GameState.look = 1; StageTuning.zoom = 0.3f; GameState.x = (System.getProperty("x") ?: "330").toFloat(); GameState.lane = 7f
        val first = streetOfShops().first().id
        render("$out/dbg_${System.getProperty("skip", "none")}.png", 780, 360, 17.6f, "2026-06-14", first, false, bottom = 40f, ambient = 100f)
        return
    }
    GameState.enabled = true
    GameState.look = 1
    StageTuning.zoom = 0.2f
    val first = streetOfShops().first().id
    com.lifemax.rpg.feature.habits.presentation.scene.StageFx.make = { src -> SkiaFx(src) }
    val mode = System.getProperty("mode", "pano")
    if (mode == "walk") {
        GameState.x = 300f; GameState.lane = 7f; GameState.dir = 1f
        renderWalk("$out/walk_", 780, 360, 16.8f, first, listOf(1f to 90, 0f to 20, -1f to 90), 30)
        return
    }
    if (mode == "rooms") {
        GameState.x = 1500f; GameState.lane = 7f; GameState.dir = 1f
        StageTuning.zoom = 0.12f
        for ((tag, hour, amb) in listOf(Triple("day", 14.0f, 100f), Triple("dusk", 19.2f, 130f), Triple("night", 22.3f, 160f))) {
            render("$out/rm_$tag.png", 780, 360, hour, "2026-06-14", first, false, bottom = 40f, ambient = amb)
        }
        return
    }
    if (mode == "weather") {
        GameState.x = 1000f; GameState.lane = 7f; GameState.dir = 1f
        val cases = listOf(
            Triple("spring", "2026-04-20", null), Triple("autumn", "2026-10-20", null), Triple("winter", "2026-01-20", null),
            Triple("rain", "2026-06-14", com.lifemax.rpg.feature.habits.domain.WeatherState(.9f, .8f, .8f, 0f)),
            Triple("mist", "2026-06-14", com.lifemax.rpg.feature.habits.domain.WeatherState(.4f, 0f, 0f, .75f)),
            Triple("overcast", "2026-06-14", com.lifemax.rpg.feature.habits.domain.WeatherState(.85f, 0f, 0f, 0f)),
        )
        for ((tag, day, w) in cases) {
            com.lifemax.rpg.feature.habits.domain.WeatherOverride.forced = w
            render("$out/wx_$tag.png", 780, 360, 16.2f, day, first, false, bottom = 40f, ambient = 100f)
        }
        com.lifemax.rpg.feature.habits.domain.WeatherOverride.forced = null
        return
    }
    if (mode == "junction") {
        // walking past the junction: the side street seen through its mouth, from either side and straight on
        val assets = File("../app/src/main/assets").absolutePath
        fun load(dir: String, name: String): androidx.compose.ui.graphics.ImageBitmap = SkiaImage.makeFromEncoded(File(File(assets, dir), name).readBytes()).toComposeImageBitmap()
        val art = com.lifemax.rpg.feature.habits.presentation.scene.TurnArt(emptyList(), emptyList(),
            runCatching { load("props", "lamp_1.png") }.getOrNull(), null,
            trees = (1..3).map { load("props", "tree_$it.png") }, bench = runCatching { load("props", "bench_1.png") }.getOrNull(),
            rows = turnRows(assets) { n -> runCatching { load("buildings", n) }.getOrNull() })
        val tb = com.lifemax.rpg.feature.habits.presentation.scene.turnBuildings(art)
        StageTuning.junctionView = { dx ->
            if (GameState.framePx > 0f) junctionView(art, tb, dx, 16.8f, 1, com.lifemax.rpg.feature.habits.presentation.scene.TurnFrame(GameState.frameGround, GameState.frameHorizon, GameState.framePx))
        }
        val shops = streetOfShops()
        val j = shops.indexOfFirst { it.id == com.lifemax.rpg.feature.habits.domain.JUNCTION_ID }
        // the junction's middle along the street, as the stage measures it (one frame to find it)
        GameState.x = 1500f
        render("$out/junction_probe.png", 780, 360, 16.8f, "2026-06-14", first, false, bottom = 40f, ambient = 100f)
        val jx = GameState.junctionX
        for ((k, off) in listOf(-420f, -200f, 0f, 200f, 420f).withIndex()) {
            GameState.x = jx + off; GameState.lane = 7f; GameState.dir = 1f
            render("$out/junction_$k.png", 780, 360, 16.8f, "2026-06-14", first, false, bottom = 40f, ambient = 100f)
        }
        return
    }
    // the people to talk to, one at each building (the app takes them from dialogue.json)
    GameState.keepers = (0 until 12).map { it to it * 7919 }
    if (mode == "pano") {
        for (i in 0..8) {
            GameState.x = i * 380f; GameState.lane = 7f; GameState.dir = 1f
            render("$out/pan_$i.png", 780, 360, 16.8f, "2026-06-14", first, false, bottom = 40f, ambient = 100f)
        }
    }
}
