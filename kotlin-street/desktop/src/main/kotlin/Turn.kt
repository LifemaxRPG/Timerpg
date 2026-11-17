@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import com.lifemax.rpg.feature.habits.presentation.scene.CornerTurn
import com.lifemax.rpg.feature.habits.presentation.scene.TurnArt
import com.lifemax.rpg.feature.habits.presentation.scene.TurnFacade
import com.lifemax.rpg.feature.habits.presentation.scene.TurnRows
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage

/** Renders frames of the faked corner turn (shared/scene/CornerTurn.kt) to PNG files. */
fun renderTurn(out: String, assets: String) {
    fun load(dir: String, name: String): ImageBitmap = SkiaImage.makeFromEncoded(File(File(assets, dir), name).readBytes()).toComposeImageBitmap()
    val art = TurnArt(
        (0..8).map { load("buildings", "b${it}_front.png") }, (0..8).map { load("buildings", "b${it}_side.png") },
        try { load("props", "lamp_1.png") } catch (e: Exception) { null },
        try { load("characters", "char_02.png") } catch (e: Exception) { null },
        (0..8).map { load("buildings", "b${it}_emis.png") },
        (0..8).map { try { load("buildings", "b${it}_prop.png") } catch (e: Exception) { null } },
        (1..3).map { load("props", "tree_$it.png") },
        try { load("props", "bench_1.png") } catch (e: Exception) { null },
        rows = turnRows(assets) { n -> try { load("buildings", n) } catch (e: Exception) { null } },
    )
    File(out).mkdirs()
    val frames = 16
    val hour = (System.getProperty("hour") ?: "14").toFloat()
    when (System.getProperty("wx")) {
        "rain" -> com.lifemax.rpg.feature.habits.domain.WeatherOverride.forced = com.lifemax.rpg.feature.habits.domain.WeatherState(.9f, .8f, .8f, 0f)
        "mist" -> com.lifemax.rpg.feature.habits.domain.WeatherOverride.forced = com.lifemax.rpg.feature.habits.domain.WeatherState(.4f, 0f, 0f, .75f)
        "overcast" -> com.lifemax.rpg.feature.habits.domain.WeatherOverride.forced = com.lifemax.rpg.feature.habits.domain.WeatherState(.85f, 0f, 0f, 0f)
    }
    val tag = (System.getProperty("wx") ?: "") + (System.getProperty("season")?.let { "s$it" } ?: "")
    for (i in 0 until frames) {
        val sc = ImageComposeScene(width = 960, height = 480, density = Density(1f)) { CornerTurn(art, i / (frames - 1f), Modifier.fillMaxSize(), hour, (System.getProperty("season") ?: "1").toInt()) }
        val img = sc.render(0L)
        File(out, if (hour == 14f && tag == "") "turn_%02d.png".format(i) else "turn_h${hour.toInt()}${tag}_%02d.png".format(i)).writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        sc.close()
        println("turn frame $i")
    }
}

/** The real crossroads from street.json (null for an older file without streets). */
fun turnRows(assets: String, img: (String) -> ImageBitmap?): TurnRows? {
    val f = File(assets, "street.json"); if (!f.exists()) return null
    val j = org.json.JSONObject(f.readText())
    val st = j.optJSONArray("streets") ?: return null
    val jn = j.optJSONObject("junction") ?: return null
    if (st.length() < 2) return null
    fun names(a: org.json.JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
    val houses = com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuildingLoad.houses(File(assets, "buildings/houses.json").takeIf { it.exists() }?.readText())
    fun facades(list: List<String>) = list.mapNotNull { n ->
        val fr = img("${n}_front.png"); val sd = img("${n}_side.png")
        if (fr == null || sd == null) null else com.lifemax.rpg.feature.habits.presentation.scene.turnFacade(fr, sd, img("${n}_emis.png"), img("${n}_prop.png"), houses[n], houses[n]?.let { img("${n}_slope.png") }, houses[n]?.let { img("${n}_back.png") },
            houses[n]?.let { img("${n}_fence.png") }, houses[n]?.let { img("${n}_fencesnow.png") }, houses[n]?.let { img("${n}_porch.png") })
    }
    return TurnRows(facades(names(st.getJSONObject(0).optJSONArray("buildings"))), facades(names(jn.optJSONArray("mainEast"))),
        facades(names(st.getJSONObject(1).optJSONArray("buildings"))), facades(names(jn.optJSONArray("crossEast"))))
}

/**
 * The turn as the app shows it: the stage drawing only its sky (panning with the turn) and the turn over it, framed by the
 * stage. Frames turnsky_NN.png.
 */
fun renderTurnOverStreet(out: String, assets: String) {
    fun load(dir: String, name: String): ImageBitmap = SkiaImage.makeFromEncoded(File(File(assets, dir), name).readBytes()).toComposeImageBitmap()
    val art = TurnArt(emptyList(), emptyList(),
        try { load("props", "lamp_1.png") } catch (e: Exception) { null },
        try { load("characters", "char_02.png") } catch (e: Exception) { null },
        trees = (1..3).map { load("props", "tree_$it.png") },
        bench = try { load("props", "bench_1.png") } catch (e: Exception) { null },
        rows = turnRows(assets) { n -> try { load("buildings", n) } catch (e: Exception) { null } })
    val hour = (System.getProperty("hour") ?: "16.8").toFloat()
    val first = streetOfShops().first().id
    com.lifemax.rpg.feature.habits.presentation.scene.GameState.x = 1e9f   // the junction end of the street
    val density = 2f
    File(out).mkdirs()
    val frames = 12
    for (i in 0 until frames) {
        val t = i / (frames - 1f)
        com.lifemax.rpg.feature.habits.presentation.scene.StageTuning.skyOnly = true
        val sc = ImageComposeScene(width = (780 * density).toInt(), height = (360 * density).toInt(), density = Density(density)) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                com.lifemax.rpg.feature.habits.presentation.scene.HabitsStage(
                    p = projection(hour, "2026-06-14"), ui = com.lifemax.rpg.feature.habits.presentation.HabitsUi(city = false, focus = first), visible = true,
                    top = 0f, bottom = 40f * density, cover = 0f, inset = 0f, accent = androidx.compose.ui.graphics.Color(0xFF6FA8FF),
                    actions = com.lifemax.rpg.feature.habits.presentation.scene.StageActions(), ambientAt = 100f,
                )
                CornerTurn(art, t, Modifier.fillMaxSize(), hour, 1, skyBehind = true)
            }
        }
        var nanos = 0L
        var img = sc.render(nanos)
        for (k in 0 until 10) {
            // the sky's pan follows the stage's framing as measured by the frame before
            val gs = com.lifemax.rpg.feature.habits.presentation.scene.GameState
            if (gs.framePx > 0f) gs.skyPan = com.lifemax.rpg.feature.habits.presentation.scene.turnSkyPan(t, com.lifemax.rpg.feature.habits.presentation.scene.TurnFrame(gs.frameGround, gs.frameHorizon, gs.framePx), density)
            nanos += 16_666_667L; Thread.sleep(30); img = sc.render(nanos)
        }
        File(out, "turnsky_%02d.png".format(i)).writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        sc.close()
        println("turnsky frame $i")
    }
    com.lifemax.rpg.feature.habits.presentation.scene.StageTuning.skyOnly = false
}

/** The 180-degree street crossing over the stage's sky (preview frames cross_NN.png). The far row is a stand-in (the cross street's homes). */
fun renderCrossing(out: String, assets: String) {
    fun load(dir: String, name: String): ImageBitmap = SkiaImage.makeFromEncoded(File(File(assets, dir), name).readBytes()).toComposeImageBitmap()
    val rows = turnRows(assets) { n -> try { load("buildings", n) } catch (e: Exception) { null } } ?: return
    val art = TurnArt(emptyList(), emptyList(), try { load("props", "lamp_1.png") } catch (e: Exception) { null },
        try { load("characters", "char_02.png") } catch (e: Exception) { null }, trees = (1..3).map { load("props", "tree_$it.png") })
    val hour = (System.getProperty("hour") ?: "16.8").toFloat()
    val first = streetOfShops().first().id
    val gs = com.lifemax.rpg.feature.habits.presentation.scene.GameState
    gs.x = 1e9f
    val density = 2f
    File(out).mkdirs()
    val frames = 12
    for (i in 0 until frames) {
        val t = i / (frames - 1f)
        com.lifemax.rpg.feature.habits.presentation.scene.StageTuning.skyOnly = true
        val sc = ImageComposeScene(width = (780 * density).toInt(), height = (360 * density).toInt(), density = Density(density)) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                com.lifemax.rpg.feature.habits.presentation.scene.HabitsStage(
                    p = projection(hour, "2026-06-14"), ui = com.lifemax.rpg.feature.habits.presentation.HabitsUi(city = false, focus = first), visible = true,
                    top = 0f, bottom = 40f * density, cover = 0f, inset = 0f, accent = androidx.compose.ui.graphics.Color(0xFF6FA8FF),
                    actions = com.lifemax.rpg.feature.habits.presentation.scene.StageActions(), ambientAt = 100f,
                )
                com.lifemax.rpg.feature.habits.presentation.scene.StreetCrossing(art, rows.mainWest, rows.crossWest, t, Modifier.fillMaxSize(), hour, 1, skyBehind = true)
            }
        }
        var nanos = 0L
        var img = sc.render(nanos)
        for (k in 0 until 10) {
            if (gs.framePx > 0f) gs.skyPan = com.lifemax.rpg.feature.habits.presentation.scene.crossingSkyPan(t, com.lifemax.rpg.feature.habits.presentation.scene.TurnFrame(gs.frameGround, gs.frameHorizon, gs.framePx), density)
            nanos += 16_666_667L; Thread.sleep(30); img = sc.render(nanos)
        }
        File(out, "cross_%02d.png".format(i)).writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        sc.close()
        println("cross frame $i")
    }
    com.lifemax.rpg.feature.habits.presentation.scene.StageTuning.skyOnly = false
    gs.skyPan = 0f
}
