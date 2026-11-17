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
import com.lifemax.rpg.feature.habits.presentation.scene.CornerTurn
import com.lifemax.rpg.feature.habits.presentation.scene.TurnArt
import com.lifemax.rpg.feature.habits.presentation.scene.TurnFacade
import com.lifemax.rpg.feature.habits.presentation.scene.TurnRows
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

/** One street of our town: its name, its buildings in order, and who owns or lives in each. */
private class StreetInfo(val name: String, val shops: List<HabitStack>, val owners: List<String>)

/** Our game's town streets, exported by tools/export-street.ts (falls back to a built-in street). */
private class StreetData(val town: String, val streets: List<StreetInfo>, val rows: TurnRows? = null)

private fun loadStreet(ctx: android.content.Context): StreetData = try {
    val j = org.json.JSONObject(ctx.assets.open("street.json").bufferedReader().use { it.readText() })
    val arr = j.getJSONArray("shops")
    fun img(n: String): androidx.compose.ui.graphics.ImageBitmap? = try {
        ctx.assets.open("buildings/$n.png").use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
    } catch (e: Exception) { null }
    // the real windows of each building (tools/make_buildings.py)
    val windows = HashMap<String, List<FloatArray>>()
    try {
        val wj = org.json.JSONObject(ctx.assets.open("buildings/windows.json").bufferedReader().use { it.readText() })
        for (key in wj.keys()) { val a = wj.getJSONArray(key); windows[key] = (0 until a.length()).map { k -> val r = a.getJSONArray(k); FloatArray(4) { r.getDouble(it).toFloat() } } }
    } catch (e: Exception) { }
    // every building once, by its sprite name
    class Entry(val stack: HabitStack, val owner: String, val art: SpriteBuilding?)
    val bySprite = LinkedHashMap<String, Entry>()
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        val id = o.getString("id"); val n = o.getString("sprite")
        val parts = o.getJSONArray("parts")
        val mids = (0 until parts.length()).count { parts.getJSONObject(it).getString("key").startsWith("middle_") }
        // OUR building, composed from our sprites (tools/make_buildings.py); a building whose art is missing keeps the
        // engine's painted version, and the rest of the street is unaffected
        val front = img("${n}_front"); val side = img("${n}_side"); val emis = img("${n}_emis")
        val art = if (front != null && side != null && emis != null)
            SpriteBuilding(front, side, emis, img("${n}_roof"), img("${n}_sign"), img("${n}_prop"), mids, windows[n].orEmpty(), windows[n + "_shop"].orEmpty(), img("${n}_glass"))
        else null
        if (art != null) SpriteArt.buildings[id] = art
        bySprite[n] = Entry(shop(id, o.getString("name"), "", HabitMaterial.CLAY, maxOf(1, mids)), o.optString("owner"), art)
    }
    fun names(a: org.json.JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
    val streetsJ = j.optJSONArray("streets")
    val streets = if (streetsJ != null) (0 until streetsJ.length()).mapNotNull { k ->
        val sj = streetsJ.getJSONObject(k)
        val es = names(sj.optJSONArray("buildings")).mapNotNull { bySprite[it] }
        if (es.isEmpty()) null else StreetInfo(sj.optString("name", "Street"), es.map { it.stack }, es.map { it.owner })
    } else {
        // an older street.json: one list, split in two
        val all = bySprite.values.toList(); val half = (all.size + 1) / 2
        listOf(all.take(half), all.drop(half)).filter { it.isNotEmpty() }.mapIndexed { k, es -> StreetInfo(if (k == 0) "Main Street" else "Side Street", es.map { it.stack }, es.map { it.owner }) }
    }
    // the crossroads, for the corner turn: the same pictures the street uses (decoded once)
    fun facades(list: List<String>) = list.mapNotNull { bySprite[it]?.art }.map { TurnFacade(it.front, it.side, it.emis, it.prop) }
    val jn = j.optJSONObject("junction")
    val rows = if (streetsJ != null && streetsJ.length() >= 2 && jn != null) TurnRows(
        facades(names(streetsJ.getJSONObject(0).optJSONArray("buildings"))), facades(names(jn.optJSONArray("mainEast"))),
        facades(names(streetsJ.getJSONObject(1).optJSONArray("buildings"))), facades(names(jn.optJSONArray("crossEast"))),
    ) else null
    if (streets.isEmpty()) StreetData("built-in street", listOf(StreetInfo("Main Street", SHOPS, emptyList())))
    else StreetData("${j.getString("town")} · ${j.getString("type")} · ${j.getInt("year")}", streets, rows)
} catch (e: Exception) {
    StreetData("built-in street", listOf(StreetInfo("Main Street", SHOPS, emptyList())))
}

@Composable
private fun GameScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val data = remember { loadStreet(ctx) }
    // the main street and the cross street; the corner turn leads from one to the other
    var streetNo by remember { mutableIntStateOf(0) }
    val street = data.streets[streetNo.coerceIn(0, data.streets.size - 1)]
    val shops = street.shops
    val owners = street.owners
    // the crossroads is at the far end of the main street, and at the start of the cross street
    GameState.cornerAtEnd = streetNo == 0
    var hour by remember { mutableFloatStateOf(17.6f) }
    var season by remember { mutableIntStateOf(1) }
    var weather by remember { mutableIntStateOf(0) }
    val p = remember(hour, data, season, weather, streetNo) {
        // the date sets the season (leaf, blossom, snow); the weather is forced
        WeatherOverride.forced = WEATHERS[weather].second
        HabitsProjection(
            day = SEASONS[season].second, hour = hour, who = "", whoHint = "", rough = false, built = 0, due = 0,
            reducedMotion = false, haptics = null, problem = "", limits = HabitLimits(40, 40, 40, 40, 40, 1, 12, 12),
            rules = HabitRules(10, 40, 100, 12), stacks = shops, lapse = emptyList(), tokens = emptyMap(), light = false,
        )
    }
    // the faked corner turn: our buildings on boxes around a crossroads, the camera swinging a quarter turn
    val turnArt = remember {
        fun img(n: String): androidx.compose.ui.graphics.ImageBitmap? = try { ctx.assets.open(n).use { BitmapFactory.decodeStream(it) }.asImageBitmap() } catch (e: Exception) { null }
        val rows = data.rows
        if (data.streets.size < 2) null
        else if (rows != null) TurnArt(emptyList(), emptyList(), img("props/lamp_1.png"), img("characters/char_02.png"), trees = (1..3).mapNotNull { img("props/tree_$it.png") }, bench = img("props/bench_1.png"), rows = rows)
        else {
            val ids = (0..8).filter { img("buildings/b${it}_front.png") != null && img("buildings/b${it}_side.png") != null }
            if (ids.isEmpty()) null else TurnArt(ids.map { img("buildings/b${it}_front.png")!! }, ids.map { img("buildings/b${it}_side.png")!! }, img("props/lamp_1.png"), img("characters/char_02.png"), ids.map { img("buildings/b${it}_emis.png") }, ids.map { img("buildings/b${it}_prop.png") }, (1..3).mapNotNull { img("props/tree_$it.png") }, img("props/bench_1.png"))
        }
    }
    var turn by remember { mutableFloatStateOf(-1f) }
    var turning by remember { mutableStateOf(false) }
    var fade by remember { mutableFloatStateOf(0f) }
    // which way the turn plays: forwards from the main street, backwards from the cross street
    var turnBack by remember { mutableStateOf(false) }
    LaunchedEffect(turning) {
        if (!turning) return@LaunchedEffect
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            val t = (androidx.compose.runtime.withFrameNanos { it } - start) / 1e9f
            if (t < 2.6f) { val q = (t / 2.4f).coerceAtMost(1f); turn = if (turnBack) 1f - q else q; fade = ((t - 2.0f) / .6f).coerceIn(0f, 1f) }
            else {
                if (turn >= 0f) {
                    turn = -1f
                    // round the corner: the start of the cross street, or back to the junction end of the main street
                    GameState.x = if (turnBack) 1e9f else 150f
                    streetNo = if (turnBack) 0 else 1
                    GameState.lane = 7f
                }
                fade = 1f - ((t - 2.6f) / .6f).coerceIn(0f, 1f)
                if (t >= 3.2f) break
            }
        }
        turning = false
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HabitsStage(
            p = p, ui = HabitsUi(focus = shops[0].id), visible = true, top = 0f, bottom = 0f, cover = 0f, inset = 0f,
            accent = Color(0xFF6FA8FF), actions = StageActions(), modifier = Modifier.fillMaxSize(),
        )
        Text("${street.name} · ${data.town}", 14.sp, Modifier.align(Alignment.TopStart).padding(14.dp).background(Color(0x99101420), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
        if (turn >= 0f && turnArt != null) {
            Box(Modifier.fillMaxSize()) {
                CornerTurn(turnArt, turn, Modifier.fillMaxSize(), hour, season, reverse = turnBack)
            }
        }
        if (fade > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = fade)))
        if (turnArt != null && !turning && GameState.atCorner) Chip(if (streetNo == 0) "↰ Turn onto ${data.streets.getOrNull(1)?.name ?: "the side street"}" else "↳ Back to ${data.streets[0].name}", false,
            Modifier.align(if (streetNo == 0) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 14.dp)) { turnBack = streetNo == 1; turning = true }
        // the shop the player is standing at
        val near = GameState.nearest
        if (near in shops.indices) {
            val st = shops[near]
            Text(st.cue + (owners.getOrNull(near)?.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: ""), 15.sp,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).background(Color(0x99101420), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 6.dp))
        }
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
private fun Chip(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.background(if (on) Color(0xCC3A6FD8) else Color(0x99101420), RoundedCornerShape(10.dp)).pointerInput(Unit) {
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
