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
    fun facades(list: List<String>) = list.mapNotNull { n ->
        val fr = img("${n}_front.png"); val sd = img("${n}_side.png")
        if (fr == null || sd == null) null else TurnFacade(fr, sd, img("${n}_emis.png"), img("${n}_prop.png"))
    }
    return TurnRows(facades(names(st.getJSONObject(0).optJSONArray("buildings"))), facades(names(jn.optJSONArray("mainEast"))),
        facades(names(st.getJSONObject(1).optJSONArray("buildings"))), facades(names(jn.optJSONArray("crossEast"))))
}
