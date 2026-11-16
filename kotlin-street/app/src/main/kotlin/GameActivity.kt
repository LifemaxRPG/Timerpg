package com.timerpg.street

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifemax.rpg.feature.habits.domain.HabitBlock
import com.lifemax.rpg.feature.habits.domain.HabitKind
import com.lifemax.rpg.feature.habits.domain.HabitLimits
import com.lifemax.rpg.feature.habits.domain.HabitMaterial
import com.lifemax.rpg.feature.habits.domain.HabitRules
import com.lifemax.rpg.feature.habits.domain.HabitStack
import com.lifemax.rpg.feature.habits.domain.HabitsProjection
import com.lifemax.rpg.feature.habits.domain.WeatherOverride
import com.lifemax.rpg.feature.habits.domain.WeatherState
import com.lifemax.rpg.feature.habits.presentation.HabitsUi
import com.lifemax.rpg.feature.habits.presentation.scene.GameState
import com.lifemax.rpg.feature.habits.presentation.scene.HabitsStage
import com.lifemax.rpg.feature.habits.presentation.scene.PlatformImages
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteArt
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuilding
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteSheets
import com.lifemax.rpg.feature.habits.presentation.scene.StageTuning
import com.lifemax.rpg.feature.habits.presentation.scene.StageActions

class GameActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the scene's two platform-specific image calls
        PlatformImages.create = { px, w, h, mip ->
            Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).apply { setHasMipMap(mip) }.asImageBitmap()
        }
        PlatformImages.update = { img: ImageBitmap, px, w, h -> img.asAndroidBitmap().setPixels(px, 0, w, 0, 0, w, h) }
        // our characters
        SpriteSheets.sheets = (1..8).map { n ->
            assets.open("characters/char_0$n.png").use { BitmapFactory.decodeStream(it) }.asImageBitmap()
        }
        // our lamp, trees and bench
        fun prop(n: String) = assets.open("props/$n.png").use { BitmapFactory.decodeStream(it) }.asImageBitmap()
        SpriteArt.lamp = prop("lamp_1"); SpriteArt.bench = prop("bench_1"); SpriteArt.trees = listOf("tree_1", "tree_2", "tree_3").map { prop(it) }
        GameState.enabled = true
        GameState.look = 1
        setContent { GameScreen() }
    }
}

private fun floor(id: String, material: HabitMaterial, size: Int) = HabitBlock(
    id = id, kind = HabitKind.BUILD, name = "", names = listOf("", "", ""), size = size, set = size, reps = 30,
    done = true, miss = 0, scar = false, material = material, left = 0, floors = 1, cleared = false, pair = "",
)

private fun shop(id: String, name: String, sign: String, material: HabitMaterial, floors: Int) = HabitStack(
    id = id, cue = name, treat = sign, claimed = false, claimable = false, removable = false, next = "",
    blocks = (1..floors).map { floor("$id-$it", material, if (it % 2 == 0) 2 else 1) },
)

/** The street of shops (from our town data: names and floors; see tools/export-street.mjs). */
private val SHOPS = listOf(
    shop("bakery", "Garrity's Bakery", "FRESH BREAD", HabitMaterial.CLAY, 3),
    shop("bank", "First Bank", "", HabitMaterial.STONE, 4),
    shop("icecream", "Cone & Co", "ICE CREAM", HabitMaterial.CLAY, 2),
    shop("butcher", "Larsen's Butcher", "", HabitMaterial.CLAY, 3),
    shop("gym", "Iron Gym", "FITNESS", HabitMaterial.STONE, 3),
    shop("music", "Marlowe Music", "RECORDS", HabitMaterial.CLAY, 2),
    shop("dress", "Nolan's Dresses", "", HabitMaterial.STONE, 3),
    shop("toys", "Little Ones Toys", "TOYS", HabitMaterial.CLAY, 2),
)

/** Our game's town street, exported by tools/export-street.ts (falls back to a built-in street). */
private class StreetData(val title: String, val shops: List<HabitStack>)

private fun loadStreet(ctx: android.content.Context): StreetData = try {
    val j = org.json.JSONObject(ctx.assets.open("street.json").bufferedReader().use { it.readText() })
    val arr = j.getJSONArray("shops")
    fun img(n: String): androidx.compose.ui.graphics.ImageBitmap? = try {
        ctx.assets.open("buildings/$n.png").use { BitmapFactory.decodeStream(it) }.asImageBitmap()
    } catch (e: Exception) { null }
    // the real windows of each building (tools/make_buildings.py)
    val windows = HashMap<String, List<FloatArray>>()
    try {
        val wj = org.json.JSONObject(ctx.assets.open("buildings/windows.json").bufferedReader().use { it.readText() })
        for (key in wj.keys()) { val a = wj.getJSONArray(key); windows[key] = (0 until a.length()).map { k -> val r = a.getJSONArray(k); FloatArray(4) { r.getDouble(it).toFloat() } } }
    } catch (e: Exception) { }
    val shops = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val id = o.getString("id"); val n = o.getString("sprite")
        val parts = o.getJSONArray("parts")
        val mids = (0 until parts.length()).count { parts.getJSONObject(it).getString("key").startsWith("middle_") }
        // OUR building, composed from our sprites (tools/make_buildings.py)
        img("${n}_front")?.let { front ->
            SpriteArt.buildings[id] = SpriteBuilding(front, img("${n}_side")!!, img("${n}_emis")!!, img("${n}_roof"), img("${n}_sign"), img("${n}_prop"), mids, windows[n].orEmpty())
        }
        shop(id, o.getString("name"), "", HabitMaterial.CLAY, mids)
    }
    StreetData("${j.getString("town")} · ${j.getString("type")} · ${j.getInt("year")}", shops)
} catch (e: Exception) {
    StreetData("built-in street", SHOPS)
}

@Composable
private fun GameScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val data = remember { loadStreet(ctx) }
    var hour by remember { mutableFloatStateOf(17.6f) }
    var season by remember { mutableIntStateOf(1) }
    var weather by remember { mutableIntStateOf(0) }
    val p = remember(hour, data, season, weather) {
        // the date sets the season (leaf, blossom, snow); the weather is forced
        WeatherOverride.forced = WEATHERS[weather].second
        HabitsProjection(
            day = SEASONS[season].second, hour = hour, who = "", whoHint = "", rough = false, built = 0, due = 0,
            reducedMotion = false, haptics = null, problem = "", limits = HabitLimits(40, 40, 40, 40, 40, 1, 12, 12),
            rules = HabitRules(10, 40, 100, 12), stacks = data.shops, lapse = emptyList(), tokens = emptyMap(), light = false,
        )
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HabitsStage(
            p = p, ui = HabitsUi(focus = data.shops[0].id), visible = true, top = 0f, bottom = 0f, cover = 0f, inset = 0f,
            accent = Color(0xFF6FA8FF), actions = StageActions(), modifier = Modifier.fillMaxSize(),
        )
        Text(data.title, 14.sp, Modifier.align(Alignment.TopStart).padding(14.dp).background(Color(0x99101420), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
        // time of day
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = 10.dp).background(Color(0x99101420), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val h = hour.toInt(); val m = ((hour - h) * 60).toInt()
            Text("%d:%02d %s".format(((h + 11) % 12) + 1, m, if (h < 12) "AM" else "PM"))
            Spacer(Modifier.width(10.dp))
            Slider(hour, { hour = it }, 0f, 24f, Modifier.width(240.dp))
        }
        // the season and the weather
        Column(Modifier.align(Alignment.TopEnd).padding(10.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { SEASONS.forEachIndexed { i, (l, _) -> Chip(l, season == i) { season = i } } }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { WEATHERS.forEachIndexed { i, (l, _) -> Chip(l, weather == i) { weather = i } } }
        }
        // walking: left and right; in and out across the pavement
        Row(Modifier.align(Alignment.BottomStart).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HoldButton("◀") { GameState.moveX = if (it) -1f else 0f }
            HoldButton("▶") { GameState.moveX = if (it) 1f else 0f }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HoldButton("▲") { GameState.moveLane = if (it) -1f else 0f }
            HoldButton("▼") { GameState.moveLane = if (it) 1f else 0f }
        }
    }
}

private val SEASONS = listOf("🌸" to "2026-04-20", "☀" to "2026-06-14", "🍂" to "2026-10-20", "❄" to "2026-01-20")
private val WEATHERS = listOf<Pair<String, WeatherState?>>(
    "Clear" to null,
    "Overcast" to WeatherState(.85f, 0f, 0f, 0f),
    "Rain" to WeatherState(.9f, .8f, .8f, 0f),
    "Mist" to WeatherState(.4f, 0f, 0f, .75f),
)

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.background(if (on) Color(0xCC3A6FD8) else Color(0x99101420), RoundedCornerShape(10.dp)).pointerInput(Unit) {
            awaitEachGesture { awaitFirstDown(); waitForUpOrCancellation()?.let { onClick() } }
        }.padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, 13.sp) }
}

/** A button that is "on" while a finger is on it. */
@Composable
private fun HoldButton(label: String, onHeld: (Boolean) -> Unit) {
    Box(
        Modifier.size(64.dp).background(Color(0x99101420), RoundedCornerShape(16.dp)).pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(); onHeld(true)
                waitForUpOrCancellation(); onHeld(false)
            }
        },
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 26.sp) }
}

@Composable
private fun Text(s: String, fontSize: androidx.compose.ui.unit.TextUnit = 15.sp, modifier: Modifier = Modifier) =
    androidx.compose.foundation.text.BasicText(
        s, modifier,
        style = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = fontSize, fontWeight = FontWeight.SemiBold),
    )

@Composable
private fun Slider(value: Float, onChange: (Float) -> Unit, from: Float, to: Float, modifier: Modifier) {
    // a plain slider: drag across the track
    Box(
        modifier.height(36.dp).pointerInput(Unit) {
            awaitEachGesture {
                val d = awaitFirstDown()
                onChange(from + (to - from) * (d.position.x / size.width).coerceIn(0f, 1f))
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull() ?: break
                    if (!c.pressed) break
                    onChange(from + (to - from) * (c.position.x / size.width).coerceIn(0f, 1f))
                    c.consume()
                }
            }
        },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0x66FFFFFF), RoundedCornerShape(2.dp)))
        Box(
            Modifier.offset(x = ((value - from) / (to - from) * 1f).dp * 0f).fillMaxWidth(((value - from) / (to - from)).coerceIn(0.02f, 1f)).height(4.dp)
                .background(Color(0xFF6FA8FF), RoundedCornerShape(2.dp)),
        )
    }
}
