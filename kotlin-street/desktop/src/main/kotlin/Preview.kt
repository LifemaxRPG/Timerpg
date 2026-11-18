@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

import com.lifemax.rpg.feature.habits.presentation.scene.junctionView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
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
import kotlin.math.abs
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
        // as on Android, where a picture made from pixels (create) is immutable and setPixels on it throws: a picture to
        // be written again is made blank (ImageBitmap(w, h)); the preview fails here rather than on the phone
        check(!bm.isImmutable) { "PlatformImages.update on an immutable image: make it with ImageBitmap(w, h)" }
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

/** The app's asset folder (street.json, buildings/), or another laid out the same way (-Dassets=, e.g. a showcase street). */
fun assetsDir(): File = System.getProperty("assets")?.let { File(it) }
    ?: File("../app/src/main/assets").takeIf { File(it, "street.json").exists() } ?: File("app/src/main/assets")

/** The same exported town the Android app loads (app/src/main/assets/street.json), with our building sprites. */
fun streetOfShops(): List<HabitStack> {
    val dir = assetsDir()
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
    val windows = com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuildingLoad.windows(File(dir, "buildings/windows.json").takeIf { it.exists() }?.readText())
    val houses = com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuildingLoad.houses(File(dir, "buildings/houses.json").takeIf { it.exists() }?.readText())
    val models = com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuildingLoad.models(File(dir, "buildings/models.json").takeIf { it.exists() }?.readText())
    fun one(i: Int): HabitStack {
        val o = arr.getJSONObject(i)
        val id = o.getString("id"); val n = o.getString("sprite")
        val mids = (0 until o.getJSONArray("parts").length()).count { o.getJSONArray("parts").getJSONObject(it).getString("key").startsWith("middle_") }
        if (SpriteArt.buildings[id] == null)
            com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuildingLoad.load(n, mids, windows, houses, models) { img(it) }?.let { SpriteArt.buildings[id] = it }
        return building(id, o.getString("name"), "", HabitMaterial.CLAY, mids)
    }
    val west = idx.map { one(it) }
    if (eastIdx.isEmpty()) return west
    val after = eastIdx.map { one(it) }
    StageTuning.cornerIds = setOfNotNull(west.lastOrNull()?.id, after.firstOrNull()?.id)
    return west + building(com.lifemax.rpg.feature.habits.domain.JUNCTION_ID, "Orchard Lane", "", HabitMaterial.CLAY, 1) + after
}

/** The stacks of the named buildings (sprite names) in street.json, in that order, their art installed. */
fun stacksOf(names: List<String>): List<HabitStack> {
    val dir = File("../app/src/main/assets").takeIf { File(it, "street.json").exists() } ?: File("app/src/main/assets")
    val arr = org.json.JSONObject(File(dir, "street.json").readText()).getJSONArray("shops")
    fun img(n: String): androidx.compose.ui.graphics.ImageBitmap? =
        File(dir, "buildings/$n.png").takeIf { it.exists() }?.let { SkiaImage.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
    val wj = File(dir, "buildings/windows.json").takeIf { it.exists() }?.let { org.json.JSONObject(it.readText()) }
    fun rects(key: String) = wj?.optJSONArray(key)?.let { a -> (0 until a.length()).map { k -> val r = a.getJSONArray(k); FloatArray(4) { r.getDouble(it).toFloat() } } }.orEmpty()
    return names.mapNotNull { n ->
        val i = (0 until arr.length()).firstOrNull { arr.getJSONObject(it).getString("sprite") == n } ?: return@mapNotNull null
        val o = arr.getJSONObject(i)
        val id = o.getString("id")
        val mids = (0 until o.getJSONArray("parts").length()).count { o.getJSONArray("parts").getJSONObject(it).getString("key").startsWith("middle_") }
        if (SpriteArt.buildings[id] == null) img("${n}_front")?.let { front ->
            SpriteArt.buildings[id] = SpriteBuilding(front, img("${n}_side")!!, img("${n}_emis")!!, img("${n}_roof"), img("${n}_sign"), img("${n}_prop"), mids, rects(n), rects(n + "_shop"), img("${n}_glass"),
                wj?.optJSONArray(n + "_relief")?.optJSONArray(0)?.let { r -> FloatArray(r.length()) { r.getDouble(it).toFloat() } }?.takeIf { it.size >= 7 }, img("${n}_over"), img("${n}_lit"), img("${n}_shade"))
        }
        building(id, o.getString("name"), "", HabitMaterial.CLAY, mids)
    }
}

/**
 * The crossroads as the app loads it (GameActivity.loadStreet): the main street with the side street's mouth and the
 * buildings east of it, the cross street, and its other side. Null if street.json has no junction.
 */
fun loadCrossroads(): com.lifemax.rpg.feature.habits.presentation.scene.CrossroadsStacks? {
    val dir = File("../app/src/main/assets").takeIf { File(it, "street.json").exists() } ?: File("app/src/main/assets")
    val root = org.json.JSONObject(File(dir, "street.json").readText())
    val st = root.optJSONArray("streets") ?: return null
    val jn = root.optJSONObject("junction") ?: return null
    if (st.length() < 2) return null
    fun names(a: org.json.JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
    val west = stacksOf(names(st.getJSONObject(0).optJSONArray("buildings")))
    val east = stacksOf(names(jn.optJSONArray("mainEast")))
    val mouth = building(com.lifemax.rpg.feature.habits.domain.JUNCTION_ID, st.getJSONObject(1).optString("name", "Side Street"), "", HabitMaterial.CLAY, 1)
    return com.lifemax.rpg.feature.habits.presentation.scene.CrossroadsStacks(west + mouth + east,
        stacksOf(names(st.getJSONObject(1).optJSONArray("buildings"))), stacksOf(names(jn.optJSONArray("crossEast"))))
}

/** The preview's options: hour (-Dhour), weather (-Dwx clear|overcast|rain|mist), season (-Dseason spring|summer|autumn|winter or 0..3). */
fun previewHour(default: Float) = System.getProperty("hour")?.toFloatOrNull() ?: default
fun previewDay(): String = when (System.getProperty("season")?.lowercase()) {
    "spring", "0" -> "2026-04-20"; "autumn", "fall", "2" -> "2026-10-20"; "winter", "3" -> "2026-01-20"; else -> "2026-06-14"
}
fun previewWeather(): WeatherState? = when (System.getProperty("wx")?.lowercase()) {
    "rain" -> WeatherState(.9f, .8f, .8f, 0f); "mist" -> WeatherState(.4f, 0f, 0f, .75f); "overcast" -> WeatherState(.85f, 0f, 0f, 0f); else -> null
}
/** The screen: -Dsize=preview (780x360 dp at density 2) or phone (the S25 Ultra's 2340x1080 px at density 2.625). */
fun previewSize(): Triple<Float, Float, Float> = if (System.getProperty("size") == "phone") Triple(2340f / 2.625f, 1080f / 2.625f, 2.625f) else Triple(780f, 360f, 2f)

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
    stacks = StageTuning.crossroads?.main ?: streetOfShops(), lapse = emptyList(), tokens = emptyMap(), light = false,
)

fun render(path: String, w: Int, h: Int, hour: Float, day: String, focus: String, city: Boolean, bottom: Float = 0f, ambient: Float = 70f, density: Float = 2f, wd: Float = w.toFloat(), hd: Float = h.toFloat()) {
    val img = renderImage(w, h, hour, day, focus, city, bottom, ambient, density, wd, hd)
    File(path).also { it.parentFile.mkdirs() }.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    println("wrote $path")
}

/** One settled frame of the street (as [render] saves it); [wd] x [hd] dp at [density] (the phone's screen is not a whole number of dp). */
fun renderImage(w: Int, h: Int, hour: Float, day: String, focus: String, city: Boolean, bottom: Float = 0f, ambient: Float = 70f, density: Float = 2f, wd: Float = w.toFloat(), hd: Float = h.toFloat()): SkiaImage {
    val scene = ImageComposeScene(width = kotlin.math.round(wd * density).toInt(), height = kotlin.math.round(hd * density).toInt(), density = Density(density)) {
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
    scene.close()
    return img
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
    if (System.getProperty("mode") == "cross") { loadSheets(args.getOrElse(1) { "../public/assets/characters" }); loadProps(File(args.getOrElse(1) { "../public/assets/characters" }).parentFile.path); GameState.enabled = true; GameState.look = 1; StageTuning.zoom = 0.2f; GameState.lane = 7f; renderCrossing(args.getOrElse(0) { "build/preview" }, assetsDir().absolutePath); return }
    if (System.getProperty("mode") == "turnsky") { loadSheets(args.getOrElse(1) { "../public/assets/characters" }); loadProps(File(args.getOrElse(1) { "../public/assets/characters" }).parentFile.path); GameState.enabled = true; GameState.look = 1; StageTuning.zoom = 0.2f; GameState.lane = 7f; renderTurnOverStreet(args.getOrElse(0) { "build/preview" }, assetsDir().absolutePath); return }
    if (System.getProperty("mode") == "inside") { loadSheets(args.getOrElse(1) { "../public/assets/characters" }); renderInsides(args.getOrElse(0) { "build/preview" }); return }
    if (System.getProperty("mode") == "turn") { renderTurn(args.getOrElse(0) { "build/preview" }, assetsDir().absolutePath); return }
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
    if (mode == "move") { renderMove(out, first); return }
    if (mode == "stick") { renderStick(out); return }
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
        // after dark: the lamps on the wet ground, and fog under the lamps
        com.lifemax.rpg.feature.habits.domain.WeatherOverride.forced = com.lifemax.rpg.feature.habits.domain.WeatherState(.9f, .8f, .8f, 0f)
        render("$out/wx_rain_night.png", 780, 360, 21.5f, "2026-06-14", first, false, bottom = 40f, ambient = 160f)
        com.lifemax.rpg.feature.habits.domain.WeatherOverride.forced = com.lifemax.rpg.feature.habits.domain.WeatherState(.4f, 0f, .3f, .8f)
        render("$out/wx_mist_night.png", 780, 360, 21.5f, "2026-06-14", first, false, bottom = 40f, ambient = 160f)
        com.lifemax.rpg.feature.habits.domain.WeatherOverride.forced = null
        return
    }
    if (mode == "houses") { renderHouses(out, File(args.getOrElse(1) { "../public/assets/characters" }).parentFile.path); return }
    if (mode == "models") { renderHouses(out, File(args.getOrElse(1) { "../public/assets/characters" }).parentFile.path, h = (System.getProperty("tall")?.toIntOrNull() ?: 520), prefix = "models"); return }
    if (mode == "seam") { renderSeam(out); return }
    if (mode == "filament" || mode == "filament-albedo") {
        // (compiled in only when Filament's desktop build is there: see build.gradle.kts)
        val k = runCatching { Class.forName("FilamentPreviewKt") }.getOrNull()
            ?: error("Filament is not built in: run tools/build-filament.sh (or pass -Pfilament.dist=DIR)")
        k.getMethod("renderFilament", String::class.java, Boolean::class.javaPrimitiveType).invoke(null, out, mode == "filament-albedo")
        return
    }
    if (mode == "filament-props") {
        // the street props' lineup and placed streets (FilamentPropsPreview.kt; Filament's desktop build needed)
        val k = runCatching { Class.forName("FilamentPropsPreviewKt") }.getOrNull()
            ?: error("Filament is not built in: run tools/build-filament.sh (or pass -Pfilament.dist=DIR)")
        k.getMethod("renderProps", String::class.java).invoke(null, out)
        return
    }
    if (mode == "filament-yards") {
        // every yard item kind's lineup and three real yards of gamma (FilamentYardsPreview.kt; Filament's desktop build needed)
        val k = runCatching { Class.forName("FilamentYardsPreviewKt") }.getOrNull()
            ?: error("Filament is not built in: run tools/build-filament.sh (or pass -Pfilament.dist=DIR)")
        k.getMethod("renderYards", String::class.java).invoke(null, out)
        return
    }
    if (mode == "filament-models") {
        // our villas and landmarks in Filament, each from several views (compiled in only with Filament: see build.gradle.kts)
        val k = runCatching { Class.forName("FilamentModelsPreviewKt") }.getOrNull()
            ?: error("Filament is not built in: run tools/build-filament.sh (or pass -Pfilament.dist=DIR)")
        k.getMethod("renderFilamentModels", String::class.java).invoke(null, out)
        return
    }
    if (mode == "filament-town") {
        // the whole town (build-town/gamma) in Filament: aerials, the civic centre, street views (FilamentTownPreview.kt)
        val k = runCatching { Class.forName("FilamentTownPreviewKt") }.getOrNull()
            ?: error("Filament is not built in: run tools/build-filament.sh (or pass -Pfilament.dist=DIR)")
        k.getMethod("renderFilamentTown", String::class.java).invoke(null, out)
        return
    }
    if (mode == "junction") {
        // walking past the junction: the side street seen through its mouth, from either side and straight on
        StageTuning.crossroads = loadCrossroads()
        StageTuning.cornerIds = emptySet()
        GameState.street = 0
        GameState.keepers = (0 until 12).map { it to it * 7919 }
        GameState.keepersBy = mapOf(0 to GameState.keepers, 1 to (0 until 8).map { it to it * 104729 })
        val (w, h, dens) = previewSize()
        val hour = previewHour(16.8f)
        com.lifemax.rpg.feature.habits.domain.WeatherOverride.forced = previewWeather()
        val cr = StageTuning.crossroads!!
        // the mouth's middle along the main street, as the stage measures it (one frame to find it)
        GameState.x = 1500f
        render("$out/junction_probe.png", w.toInt(), h.toInt(), hour, previewDay(), cr.main.first().id, false, bottom = 40f, ambient = 100f, density = dens, wd = w, hd = h)
        val jx = GameState.junctionX
        val tag = System.getProperty("tag")?.let { "_$it" } ?: ""
        for ((k, off) in listOf(-420f, -300f, -200f, -100f, 0f, 100f, 200f, 300f, 420f).withIndex()) {
            GameState.x = jx + off; GameState.lane = 7f; GameState.dir = 1f
            render("$out/junction${tag}_$k.png", w.toInt(), h.toInt(), hour, previewDay(), cr.main.first().id, false, bottom = 40f, ambient = 100f, density = dens, wd = w, hd = h)
        }
        com.lifemax.rpg.feature.habits.domain.WeatherOverride.forced = null
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


/** The rooms inside the street's buildings (assets/interiors.json), at a few hours, the player by the door. */
fun renderInsides(out: String) {
    val assets = File("../app/src/main/assets").takeIf { File(it, "interiors.json").exists() } ?: File("app/src/main/assets")
    val rooms = com.lifemax.rpg.feature.habits.presentation.scene.InteriorRoom.parseAll(File(assets, "interiors.json").readText())
    com.lifemax.rpg.feature.habits.presentation.scene.InteriorArt.images = File(assets, "interior").listFiles()!!.filter { it.extension == "png" }
        .associate { it.nameWithoutExtension to SkiaImage.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
    GameState.look = 1
    val pick = (System.getProperty("rooms") ?: "b0,b5,b8,b17").split(",")
    for (id in pick) {
        val room = rooms[id] ?: continue
        for ((tag, hour) in listOf("day" to 13f, "dusk" to 19f, "night" to 22.5f)) {
            val w = com.lifemax.rpg.feature.habits.presentation.scene.RoomWalker(room.spawnX, room.spawnY - 40f)
            val scene = ImageComposeScene(width = 1560, height = 720, density = Density(2f)) {
                androidx.compose.foundation.Canvas(androidx.compose.ui.Modifier.fillMaxSize()) {
                    with(com.lifemax.rpg.feature.habits.presentation.scene.InteriorPainter) { draw(room, w, hour, SpriteSheets.sheets.getOrNull(3), room.keeperSpot(), 1f) }
                }
            }
            val img = scene.render(0L)
            File("$out/in_${id}_$tag.png").also { it.parentFile.mkdirs() }.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
        println("wrote $out/in_$id")
    }
}


/**
 * The movement on the phone's screen: the street at the S25's landscape size (832 x 384 dp at its default 2.8125, 2340 x
 * 1080 pixels; the app's zoom), stepped at 60 Hz through a stand, a walk (the stick 60% out), a run (at the rim), letting
 * go, a run left and a diagonal up and right. Saves six whole frames, a strip of the player every second frame through
 * the walk and the run (gait, lean, hop and the camera's lead), and a strip of each against the ground (the same patch of
 * pavement each frame: a planted foot should stay put), and logs the numbers every 0.1 s.
 */
fun renderMove(out: String, focus: String) {
    printMoveNumbers()
    val density = 2.8125f
    val w = 2340; val h = 1080
    StageTuning.zoom = .42f
    GameState.x = 900f; GameState.lane = 7f; GameState.dir = 1f
    GameState.keepers = (0 until 12).map { it to it * 7919 }
    val scene = ImageComposeScene(width = w, height = h, density = Density(density)) {
        HabitsStage(p = projection(16.8f, "2026-06-14"), ui = HabitsUi(focus = focus), visible = true, top = 0f, bottom = 0f, cover = 0f, inset = 0f,
            accent = Color(0xFF6FA8FF), actions = StageActions())
    }
    var nanos = 0L
    // warm up (sprites bake off the main thread), standing
    repeat(40) { nanos += 16_666_667L; Thread.sleep(40); scene.render(nanos) }
    class Phase(val name: String, val secs: Float, val mx: Float, val my: Float)
    val phases = listOf(
        Phase("stand", .25f, 0f, 0f), Phase("walk", 1.5f, .6f, 0f), Phase("run", 1.5f, 1f, 0f),
        Phase("let go", .5f, 0f, 0f), Phase("run left", 1f, -1f, 0f), Phase("up and right", .8f, .55f, -.55f),
    )
    val cropW = 150; val cropH = 150
    val strip = ArrayList<org.jetbrains.skia.Image>()
    val ground = HashMap<String, ArrayList<org.jetbrains.skia.Image>>()
    var t = 0f; var n = 0; var nextLog = 0f
    for (ph in phases) {
        GameState.moveX = ph.mx; GameState.moveLane = ph.my
        val frames = Math.round(ph.secs * 60f)
        var groundX = Float.NaN
        for (f in 0 until frames) {
            nanos += 16_666_667L; Thread.sleep(2)
            val img = scene.render(nanos); n++; t += 1f / 60f
            val unit = GameState.unit
            val lead = GameState.camera.snapped(unit)
            // where the player's feet are on the screen (the camera is the player plus its lead, away from the street's ends)
            val sx = w / 2f - lead * unit
            val sy = GameState.groundPx + GameState.lane * unit
            if (t >= nextLog - 1e-4f) {
                nextLog += .1f
                println("move t=%.2f %-12s stick=(%.2f, %.2f) x=%.1f vx=%.1f lane=%.2f frame=%d gait=%.2f lead=%.1f running=%b lean=%.1f footfalls=%d".format(
                    t, ph.name, ph.mx, ph.my, GameState.x, GameState.vx, GameState.lane, (GameState.gait * 6).toInt(), GameState.gait, GameState.camera.lead,
                    GameState.running, GameState.lean, GameState.footfalls))
            }
            fun crop(cx: Float, cy: Float): org.jetbrains.skia.Image {
                val bm = Bitmap(); bm.allocN32Pixels(cropW, cropH)
                val c = org.jetbrains.skia.Canvas(bm)
                c.drawImageRect(img, org.jetbrains.skia.Rect.makeXYWH(cx - cropW / 2f, cy - cropH + 20f, cropW.toFloat(), cropH.toFloat()),
                    org.jetbrains.skia.Rect.makeWH(cropW.toFloat(), cropH.toFloat()))
                return SkiaImage.makeFromBitmap(bm.also { it.setImmutable() })
            }
            if ((ph.name == "walk" || ph.name == "run") && f % 2 == 0) strip += crop(sx, sy)
            // against the ground: 14 frames in the middle of the walk and of the run, the same patch of pavement each time
            if ((ph.name == "walk" || ph.name == "run") && f >= frames / 2 && f < frames / 2 + 14) {
                if (groundX.isNaN()) groundX = GameState.drawnX()
                val gx = sx + (groundX - GameState.drawnX()) * unit
                ground.getOrPut(ph.name) { ArrayList() } += crop(gx, sy)
            }
            if (f == frames - 1 || (ph.name == "walk" || ph.name == "run") && f == frames / 2) {
                File("$out/move_${n}_${ph.name.replace(' ', '_')}.png").also { it.parentFile.mkdirs() }.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }
    }
    GameState.moveX = 0f; GameState.moveLane = 0f
    scene.close()
    fun sheet(name: String, crops: List<org.jetbrains.skia.Image>, cols: Int) {
        val rows = (crops.size + cols - 1) / cols
        val bm = Bitmap(); bm.allocN32Pixels(cols * cropW, rows * cropH)
        val c = org.jetbrains.skia.Canvas(bm)
        c.clear(0xFF202028.toInt())
        crops.forEachIndexed { i, im -> c.drawImage(im, (i % cols * cropW).toFloat(), (i / cols * cropH).toFloat()) }
        File("$out/$name.png").writeBytes(SkiaImage.makeFromBitmap(bm).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        println("wrote $out/$name.png (${crops.size} frames)")
    }
    sheet("move_strip", strip, 15)
    for ((k, v) in ground) sheet("move_ground_${k}", v, 7)
    renderMoveEnd(out, focus)
    // the run's frames as made from the first sheet (to compare with the art by eye, and pixel for pixel with a reference)
    SpriteSheets.sheets.firstOrNull()?.let { sh ->
        val run = com.lifemax.rpg.feature.habits.presentation.scene.RunFrames.of(sh).asSkiaBitmap()
        File("$out/run_frames_1.png").writeBytes(SkiaImage.makeFromBitmap(run).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        println("wrote $out/run_frames_1.png")
    }
}

/**
 * Running into the street's far end and back, at the phone's size and 60 Hz: the camera eases into the end (its speed
 * logged frame by frame, the largest change in one frame printed), and while it stands at the end the player is still
 * drawn every frame they move (each frame's picture is compared with the one before).
 */
fun renderMoveEnd(out: String, focus: String) {
    val density = 2.8125f
    val w = 2340; val h = 1080
    StageTuning.zoom = .42f
    GameState.x = 1e6f; GameState.lane = 7f; GameState.dir = 1f; GameState.camera.reset()
    val scene = ImageComposeScene(width = w, height = h, density = Density(density)) {
        HabitsStage(p = projection(16.8f, "2026-06-14"), ui = HabitsUi(focus = focus), visible = true, top = 0f, bottom = 0f, cover = 0f, inset = 0f,
            accent = Color(0xFF6FA8FF), actions = StageActions())
    }
    var nanos = 0L
    // the first frames find the street's end (a player far past it is put back as if round the corner, 150 short of it)
    repeat(3) { nanos += 16_666_667L; scene.render(nanos) }
    val end = GameState.x + 150f
    GameState.x = end - 1100f; GameState.camera.reset()
    repeat(40) { nanos += 16_666_667L; Thread.sleep(30); scene.render(nanos) }
    var lastView = GameState.viewMiddle; var lastSpeed = 0f; var worst = 0f; var worstAt = 0f
    var lastDrawn = GameState.drawnX(); var lastFrame = -1; var lastHash = 0; var missed = 0; var clampedMoves = 0
    var t = 0f
    for ((name, secs, mx) in listOf(Triple("run to the end", 8.5f, 1f), Triple("let go", .6f, 0f), Triple("run back", 3f, -1f))) {
        GameState.moveX = mx
        repeat(Math.round(secs * 60f)) { f ->
            nanos += 16_666_667L
            // the desktop scene resumes the game's frame loop a step out of time with its drawing; a second render at the
            // same moment (nothing moves in it) draws what that frame computed, as the phone does within one frame
            scene.render(nanos)
            val img = scene.render(nanos); t += 1f / 60f
            val v = GameState.viewMiddle
            val speed = (v - lastView) * 60f
            if (abs(speed - lastSpeed) > worst) { worst = abs(speed - lastSpeed); worstAt = t }
            // the camera standing while the player is drawn somewhere else (a whole pixel on, or another frame of the
            // legs): the picture must change
            val frame = (GameState.gait * 6f).toInt()
            val bm = Bitmap(); bm.allocN32Pixels(w, h); img.readPixels(bm)
            val hash = bm.readPixels()!!.contentHashCode()
            bm.close()
            if (v == lastView && (GameState.drawnX() != lastDrawn || frame != lastFrame)) {
                clampedMoves++
                if (hash == lastHash) missed++
            }
            lastHash = hash
            if (f % 6 == 0) println("end t=%.2f %-14s x=%.1f (end %.1f) vx=%.1f view=%.1f view speed=%.1f lead=%.1f".format(t, name, GameState.x, end, GameState.vx, v, speed, GameState.camera.lead))
            if (name == "run to the end" && f == Math.round(secs * 60f) - 1)
                File("$out/move_end.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            lastView = v; lastSpeed = speed; lastDrawn = GameState.drawnX(); lastFrame = frame
        }
    }
    GameState.moveX = 0f
    scene.close()
    println("numbers end: the largest change in the camera's speed in one frame %.1f units/s (at %.2f s); %d frames with the camera still and the player moving, %d of them not redrawn".format(worst, worstAt, clampedMoves, missed))
}

/** The movement's numbers from the integrator alone (no drawing): what the new speeds and easing come to. */
fun printMoveNumbers() {
    fun sim(hz: Int, plan: List<Pair<Float, Float>>, each: (Float, com.lifemax.rpg.feature.habits.presentation.scene.Locomotion, Float) -> Unit) {
        val m = com.lifemax.rpg.feature.habits.presentation.scene.Locomotion()
        var t = 0f; var x = 0f
        for ((secs, mx) in plan) repeat(Math.round(secs * hz)) {
            m.drive(1f / hz, mx, 0f); x += m.vx / hz; t += 1f / hz; m.advance(kotlin.math.abs(m.vx / hz)); each(t, m, x)
        }
    }
    val T = com.lifemax.rpg.feature.habits.presentation.scene.MoveTuning
    for (hz in listOf(120, 60)) {
        var walk90 = -1f; var run90 = -1f
        sim(hz, listOf(1f to .7f)) { t, m, _ -> if (walk90 < 0f && m.vx >= .9f * T.walk) walk90 = t }
        sim(hz, listOf(1f to 1f)) { t, m, _ -> if (run90 < 0f && m.vx >= .9f * T.run) run90 = t }
        var stopW = 0f; var stopWt = -1f; var x0 = 0f
        sim(hz, listOf(1f to .7f, 1f to 0f)) { t, m, x -> if (t <= 1f + 1e-4f) x0 = x else { stopW = x - x0; if (stopWt < 0f && m.vx == 0f) stopWt = t - 1f } }
        var stopR = 0f; var stopRt = -1f
        sim(hz, listOf(1f to 1f, 1f to 0f)) { t, m, x -> if (t <= 1f + 1e-4f) x0 = x else { stopR = x - x0; if (stopRt < 0f && m.vx == 0f) stopRt = t - 1f } }
        println("numbers %d Hz: 0 to 90%% of a walk %.3f s, of a run %.3f s; stop from a walk %.1f units (still at %.2f s), from a run %.1f units (still at %.2f s)".format(
            hz, walk90, run90, stopW, stopWt, stopR, stopRt))
    }
    // the camera after letting go: when the view (the player plus the lead) stops, and whether it ever moves back
    for (push in listOf(.7f, 1f)) {
        val m = com.lifemax.rpg.feature.habits.presentation.scene.Locomotion()
        val c = com.lifemax.rpg.feature.habits.presentation.scene.CameraLead()
        var x = 0f
        repeat(240) { m.drive(1f / 120f, push, 0f); x += m.vx / 120f; c.step(1f / 120f, m.vx, 961f) }
        var lastView = x + c.lead; var still = -1f; var playerStill = -1f; var back = 0f; var lastX = x
        for (i in 1..480) {
            m.drive(1f / 120f, 0f, 0f); x += m.vx / 120f; c.step(1f / 120f, m.vx, 961f)
            val v = x + c.lead
            back = maxOf(back, lastView - v)
            if (playerStill < 0f && x == lastX) playerStill = i / 120f
            if (still < 0f && abs(v - lastView) < 1e-4f) still = i / 120f
            lastView = v; lastX = x
        }
        println("numbers camera: letting go of a %s, the player is still at %.2f s, the view at %.2f s (lead held at %.1f units), moving back at most %.2f units".format(
            if (push < 1f) "walk" else "run", playerStill, still, c.lead, back))
    }
    val walkStride = T.stride; val runStride = T.stride * Math.pow((T.run / T.walk).toDouble(), T.stridePow.toDouble()).toFloat()
    val PX = com.lifemax.rpg.feature.habits.presentation.scene.SpriteArt.PIXEL
    val RF = com.lifemax.rpg.feature.habits.presentation.scene.RunFrames
    println("numbers legs: the art's planted foot carries %.1f of a walk cycle's %.1f units (%.0f%%), the run's frames %.1f of a run's %.1f (%.0f%%)".format(
        4f * RF.WALK_STEP * PX, walkStride, 400f * RF.WALK_STEP * PX / walkStride, 4f * RF.RUN_STEP * PX, runStride, 400f * RF.RUN_STEP * PX / runStride))
    println("numbers cadence: walk %.2f cycles/s (%.1f steps/s, %.1f sprite frames/s), run %.2f cycles/s (%.1f steps/s, %.1f frames/s), run stride %.1f units".format(
        T.walk / walkStride, 2 * T.walk / walkStride, 6 * T.walk / walkStride, T.run / runStride, 2 * T.run / runStride, 6 * T.run / runStride, runStride))
    println("numbers lanes: walk %.1f lanes/s, run %.1f lanes/s; kerb to wall (11 lanes) %.2f s walking, %.2f s running".format(
        T.walk * T.laneRatio, T.run * T.laneRatio, 11f / (T.walk * T.laneRatio), 11f / (T.run * T.laneRatio)))
}

/** The on-screen stick alone at the phone's size: at rest, pushed into the walk band, and at the rim (a run). */
fun renderStick(out: String) {
    val density = 2.8125f
    val w = 2340; val h = 1080
    val loco = com.lifemax.rpg.feature.habits.presentation.scene.Locomotion()
    val scene = ImageComposeScene(width = w, height = h, density = Density(density)) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().then(
            androidx.compose.ui.Modifier.background(Color(0xFF3A4A5A)))) {
            com.lifemax.rpg.feature.habits.presentation.scene.MoveStick(true, androidx.compose.ui.Modifier.fillMaxSize())
        }
    }
    var nanos = 0L
    fun frames(k: Int) = repeat(k) {
        nanos += 16_666_667L
        // the stage would latch the run from the push; here the stick's own push does it
        loco.targetSpeed(com.lifemax.rpg.feature.habits.presentation.scene.MoveInput.stick.amount)
        GameState.running = loco.running
        scene.render(nanos)
    }
    fun save(name: String) {
        val img = scene.render(nanos)
        File("$out/$name.png").also { it.parentFile.mkdirs() }.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        println("stick $name: moveX=%.3f moveLane=%.3f amount=%.2f running=%b".format(GameState.moveX, GameState.moveLane,
            com.lifemax.rpg.feature.habits.presentation.scene.MoveInput.stick.amount, GameState.running))
    }
    val dp = density
    fun at(xDp: Float, yDp: Float) = androidx.compose.ui.geometry.Offset(xDp * dp, yDp * dp)
    frames(30); save("stick_idle")
    // a thumb lands a little up and right of home, then pushes 32 dp right and a little up: a walk
    val t0 = 1000L
    scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, at(140f, 270f), timeMillis = t0, type = androidx.compose.ui.input.pointer.PointerType.Touch)
    frames(3)
    scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, at(170f, 262f), timeMillis = t0 + 50, type = androidx.compose.ui.input.pointer.PointerType.Touch)
    frames(20); save("stick_walk")
    // on to the rim and past it, up and right: a run
    scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, at(195f, 240f), timeMillis = t0 + 100, type = androidx.compose.ui.input.pointer.PointerType.Touch)
    frames(20); save("stick_run")
    scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, at(195f, 240f), timeMillis = t0 + 150, type = androidx.compose.ui.input.pointer.PointerType.Touch)
    frames(30); save("stick_released")
    // a thumb landing low in the corner (26 dp in, 12 up): a full push left or down fits in the room left, and the run's
    // ring comes in that way to match (a quarter ellipse each way)
    scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, at(26f, 372f), timeMillis = t0 + 2000, type = androidx.compose.ui.input.pointer.PointerType.Touch)
    frames(3)
    scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, at(30f, 368f), timeMillis = t0 + 2050, type = androidx.compose.ui.input.pointer.PointerType.Touch)
    frames(20); save("stick_edge")
    scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, at(30f, 368f), timeMillis = t0 + 2100, type = androidx.compose.ui.input.pointer.PointerType.Touch)
    frames(5)
    scene.close()
}
