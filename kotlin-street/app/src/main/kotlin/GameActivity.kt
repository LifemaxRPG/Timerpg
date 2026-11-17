package com.timerpg.street

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.graphicsLayer
import com.lifemax.rpg.feature.habits.presentation.scene.turnSkyPan
import com.lifemax.rpg.feature.habits.presentation.scene.junctionView
import com.lifemax.rpg.feature.habits.presentation.scene.TurnFrame
import com.lifemax.rpg.feature.habits.presentation.scene.TurnFacade
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuildingLoad
import com.lifemax.rpg.feature.habits.presentation.scene.TurnRows
import com.lifemax.rpg.feature.habits.presentation.scene.HabitsStage
import com.lifemax.rpg.feature.habits.presentation.scene.PlatformImages
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteArt
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuilding
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteSheets
import com.lifemax.rpg.feature.habits.presentation.scene.StageTuning
import com.lifemax.rpg.feature.habits.presentation.scene.StageActions
import com.timerpg.street.dialogue.AiStatus
import com.timerpg.street.dialogue.DialogueEngine
import com.timerpg.street.dialogue.Line
import com.timerpg.street.dialogue.ModelState
import com.timerpg.street.dialogue.Npc
import com.timerpg.street.dialogue.Fillers
import com.timerpg.street.dialogue.Topic

class GameActivity : ComponentActivity() {
    /** NPC dialogue (template lines, and the language model's when it has one); its own worker thread, never this one. */
    private var dialogue: DialogueEngine? = null
    /** NPC voices (Kokoro and Supertonic), and the one-time model download. */
    private var voices: com.timerpg.street.voice.NpcVoiceService? = null
    private var sound: com.timerpg.street.audio.SoundScape? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReport.install(applicationContext)
        AndroidFx.install()
        // the Hexagon NPU's code (libggml-htp-v79.so) is in this app's native library folder: tell the DSP loader
        runCatching {
            val dsp = applicationInfo.nativeLibraryDir + ";/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp"
            android.system.Os.setenv("ADSP_LIBRARY_PATH", dsp, true)
        }
        // the NPU backend's settings (the tuned ones, or the one being tried while tuning): before the library loads
        NpuTune.apply(applicationContext)
        super.onCreate(savedInstanceState)
        // the scene's two platform-specific image calls
        PlatformImages.create = { px, w, h, mip ->
            Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).apply { setHasMipMap(mip) }.asImageBitmap()
        }
        PlatformImages.update = { img: ImageBitmap, px, w, h -> img.asAndroidBitmap().setPixels(px, 0, w, 0, 0, w, h) }
        immersive()
        // our characters: only the first 4 views x 6 frames (x < 768) of rows 0-2 (y < 192) are ever drawn, so only that is
        // decoded (about 5 MB of bitmaps instead of 75)
        SpriteSheets.sheets = (1..8).map { n ->
            assets.open("characters/char_0$n.png").use { s ->
                @Suppress("DEPRECATION") val d = android.graphics.BitmapRegionDecoder.newInstance(s, false)
                if (d == null) BitmapFactory.decodeStream(assets.open("characters/char_0$n.png"))
                else try { d.decodeRegion(android.graphics.Rect(0, 0, minOf(768, d.width), minOf(192, d.height)), null) } finally { d.recycle() }
            }.asImageBitmap()
        }
        // a recreated activity must not inherit a held direction
        GameState.moveX = 0f; GameState.moveLane = 0f
        // our lamp, trees and bench
        fun prop(n: String) = assets.open("props/$n.png").use { BitmapFactory.decodeStream(it) }.asImageBitmap()
        SpriteArt.lamp = prop("lamp_1"); SpriteArt.bench = prop("bench_1"); SpriteArt.trees = listOf("tree_1", "tree_2", "tree_3").map { prop(it) }
        GameState.enabled = true
        GameState.look = 1
        val engine = DialogueEngine.shared(applicationContext)
        dialogue = engine
        val v = com.timerpg.street.voice.NpcVoiceService(applicationContext)
        voices = v
        // the street's sound: ambience, music, footsteps; it dips while someone speaks
        val s = com.timerpg.street.audio.SoundScape(applicationContext)
        sound = s
        v.onSpeaking = { on -> s.duck(on) }
        // lines written ahead get their voices made ahead too
        engine.onWritten = { npc, text -> v.prepare(npc, text) }
        setContent { GameScreen(engine, v, s) }
    }

    /** The whole screen: no system bars (a swipe shows them for a moment), drawn into the camera cutout, never dimming. */
    private fun immersive() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (android.os.Build.VERSION.SDK_INT >= 28)
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onStart() {
        super.onStart()
        dialogue?.onForeground(true)
        sound?.start()
    }

    override fun onStop() {
        dialogue?.onForeground(false)
        sound?.stop()
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        dialogue?.onTrimMemory(level)
    }

    override fun onDestroy() {
        // the engine outlives a recreated activity, not a finished game
        voices?.close()
        if (isFinishing) DialogueEngine.release()
        super.onDestroy()
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

/** One street of our town: its name, its buildings in order, who owns or lives in each, and their sprite names (bN). */
private class StreetInfo(val name: String, val shops: List<HabitStack>, val owners: List<String>, val sprites: List<String> = emptyList())

/** Our game's town streets, exported by tools/export-street.ts (falls back to a built-in street). */
private class StreetData(val town: String, val streets: List<StreetInfo>, val rows: TurnRows? = null, val cornerIds: Set<String> = emptySet())

private fun loadStreet(ctx: android.content.Context): StreetData = try {
    val j = org.json.JSONObject(ctx.assets.open("street.json").bufferedReader().use { it.readText() })
    val arr = j.getJSONArray("shops")
    fun img(n: String): androidx.compose.ui.graphics.ImageBitmap? = try {
        ctx.assets.open("buildings/$n.png").use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
    } catch (e: Exception) { null }
    // the real windows of each building (tools/make_buildings.py)
    val windows = SpriteBuildingLoad.windows(try { ctx.assets.open("buildings/windows.json").bufferedReader().use { it.readText() } } catch (e: Exception) { null })
    // the pitched-roof houses' shapes (our mobile homes)
    val houses = SpriteBuildingLoad.houses(try { ctx.assets.open("buildings/houses.json").bufferedReader().use { it.readText() } } catch (e: Exception) { null })
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
        val art = SpriteBuildingLoad.load(n, mids, windows, houses) { img(it) }
        if (art != null) SpriteArt.buildings[id] = art
        bySprite[n] = Entry(shop(id, o.getString("name"), "", HabitMaterial.CLAY, maxOf(1, mids)), o.optString("owner"), art)
    }
    fun names(a: org.json.JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
    val streetsJ = j.optJSONArray("streets")
    val streets = if (streetsJ != null) (0 until streetsJ.length()).mapNotNull { k ->
        val sj = streetsJ.getJSONObject(k)
        val ns = names(sj.optJSONArray("buildings")).filter { bySprite[it] != null }
        val es = ns.map { bySprite[it]!! }
        if (es.isEmpty()) null else StreetInfo(sj.optString("name", "Street"), es.map { it.stack }, es.map { it.owner }, ns)
    } else {
        // an older street.json: one list, split in two
        val all = bySprite.entries.toList(); val half = (all.size + 1) / 2
        listOf(all.take(half), all.drop(half)).filter { it.isNotEmpty() }.mapIndexed { k, es -> StreetInfo(if (k == 0) "Main Street" else "Side Street", es.map { it.value.stack }, es.map { it.value.owner }, es.map { it.key }) }
    }
    // the crossroads, for the corner turn: the same pictures the street uses (decoded once)
    fun facades(list: List<String>) = list.mapNotNull { bySprite[it]?.art }.map { com.lifemax.rpg.feature.habits.presentation.scene.turnFacade(it.front, it.side, it.emis, it.prop, it.house?.shape, it.house?.slope, it.house?.back) }
    val jn = j.optJSONObject("junction")
    val rows = if (streetsJ != null && streetsJ.length() >= 2 && jn != null) TurnRows(
        facades(names(streetsJ.getJSONObject(0).optJSONArray("buildings"))), facades(names(jn.optJSONArray("mainEast"))),
        facades(names(streetsJ.getJSONObject(1).optJSONArray("buildings"))), facades(names(jn.optJSONArray("crossEast"))),
    ) else null
    // the main street does not end at the junction: the side street opens off it (a gap where it is seen, walked past or
    // turned into), and the main street goes on beyond with the buildings east of it
    var corners = emptySet<String>()
    val walked = if (jn != null && streets.size >= 2) streets.mapIndexed { k, st ->
        if (k != 0) st else {
            val east = names(jn.optJSONArray("mainEast")).filter { bySprite[it] != null }
            val mouth = shop(com.lifemax.rpg.feature.habits.domain.JUNCTION_ID, streets[1].name, "", HabitMaterial.CLAY, 1)
            corners = setOfNotNull(st.shops.lastOrNull()?.id, east.firstOrNull()?.let { bySprite[it]!!.stack.id })
            StreetInfo(st.name, st.shops + mouth + east.map { bySprite[it]!!.stack }, st.owners + "" + east.map { bySprite[it]!!.owner },
                st.sprites + com.lifemax.rpg.feature.habits.domain.JUNCTION_ID + east)
        }
    } else streets
    if (walked.isEmpty()) StreetData("built-in street", listOf(StreetInfo("Main Street", SHOPS, emptyList())))
    else StreetData("${j.getString("town")} · ${j.getString("type")} · ${j.getInt("year")}", walked, rows, corners)
} catch (e: Exception) {
    StreetData("built-in street", listOf(StreetInfo("Main Street", SHOPS, emptyList())))
}

@Composable
private fun GameScreen(engine: DialogueEngine, voices: com.timerpg.street.voice.NpcVoiceService, sound: com.timerpg.street.audio.SoundScape? = null) {
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
    // the side street seen through the junction's gap as the player walks the main street (the turn's scene, first frame)
    val turnScene = remember(turnArt) { turnArt?.let { com.lifemax.rpg.feature.habits.presentation.scene.turnBuildings(it) } }
    val viewNow = androidx.compose.runtime.rememberUpdatedState(hour to season)
    androidx.compose.runtime.DisposableEffect(turnArt, streetNo) {
        val art = turnArt; val tb = turnScene
        StageTuning.cornerIds = if (streetNo == 0) data.cornerIds else emptySet()
        StageTuning.junctionView = if (art == null || tb == null || streetNo != 0) null else { dx ->
            if (GameState.framePx > 0f) {
                val (h, s) = viewNow.value
                junctionView(art, tb, dx, h, s, TurnFrame(GameState.frameGround, GameState.frameHorizon, GameState.framePx))
            }
        }
        onDispose { StageTuning.junctionView = null; StageTuning.cornerIds = emptySet() }
    }
    // the turn starts from wherever the player stands in the junction's mouth, and swings round from there
    var turnDx by remember { mutableFloatStateOf(0f) }
    var turn by remember { mutableFloatStateOf(-1f) }
    var turning by remember { mutableStateOf(false) }
    var fade by remember { mutableFloatStateOf(0f) }
    // which way the turn plays: forwards from the main street, backwards from the cross street
    var turnBack by remember { mutableStateOf(false) }
    // the turn's own clock (for its rain and snow) and how much of it is shown (it fades in over the street)
    var turnClock by remember { mutableFloatStateOf(0f) }
    var turnAlpha by remember { mutableFloatStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    LaunchedEffect(turning) {
        if (!turning) return@LaunchedEffect
        val start = androidx.compose.runtime.withFrameNanos { it }
        try {
        while (true) {
            val t = (androidx.compose.runtime.withFrameNanos { it } - start) / 1e9f
            turnClock = t
            // the turn fades in over the live street, then the stage draws only its sky behind the turn, panning with it
            turnAlpha = (t / .35f).coerceIn(0f, 1f)
            StageTuning.skyOnly = turn >= 0f && t > .35f
            GameState.skyPan = if (StageTuning.skyOnly && GameState.framePx > 0f)
                turnSkyPan(turn.coerceIn(0f, 1f), TurnFrame(GameState.frameGround, GameState.frameHorizon, GameState.framePx), density) else 0f
            if (t < 2.6f) { val q = (t / 2.4f).coerceAtMost(1f); turn = if (turnBack) 1f - q else q; fade = ((t - 2.0f) / .6f).coerceIn(0f, 1f) }
            else {
                if (turn >= 0f) {
                    turn = -1f
                    // round the corner: the start of the cross street, or back to the junction end of the main street
                    GameState.x = if (turnBack) -1e9f else 150f
                    streetNo = if (turnBack) 0 else 1
                    GameState.lane = 7f
                }
                fade = 1f - ((t - 2.6f) / .6f).coerceIn(0f, 1f)
                if (t >= 3.2f) break
            }
        }
        } finally { StageTuning.skyOnly = false; GameState.skyPan = 0f }
        turning = false
    }
    // GameState is plain vars the stage updates every frame: copy them into Compose state so the HUD follows them
    var near by remember { mutableIntStateOf(GameState.nearest) }
    var atCorner by remember { mutableStateOf(GameState.atCorner) }
    LaunchedEffect(Unit) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            near = GameState.nearest
            atCorner = GameState.atCorner
        }
    }
    // dialogue: who is at the building the player stands at, the open conversation, the AI panel
    val people by engine.data.collectAsState()
    val ai by engine.status.collectAsState()
    var talk by remember { mutableStateOf<Talk?>(null) }
    var aiPanel by remember { mutableStateOf(false) }
    val here = street.sprites.getOrNull(near)
    val atHere = remember(people, here) { people?.at(here).orEmpty() }
    // everyone the player can talk to on this street stands at their building (the first of each building's people)
    LaunchedEffect(people, streetNo) {
        GameState.keepers = street.sprites.mapIndexedNotNull { i, sp ->
            people?.at(sp)?.firstOrNull()?.let { npc -> i to (npc.id.hashCode() and 0x7fffffff) }
        }
    }
    // lines are written ahead of time nearest the player first; re-sorted as they walk or turn the corner
    LaunchedEffect(streetNo, near) { engine.setPlayer(streetNo, near) }
    // the corner turn: no writing ahead while it plays (it needs the CPU), no conversation across it
    LaunchedEffect(turning) {
        if (turning) { talk?.close(voices); talk = null; engine.pause("turn") } else engine.resume("turn")
    }
    // a conversation open: the speech model gets ready (it loads only now, not when walking past people), and the lines
    // written ahead for others wait, so the phone's memory and the AI lane are the conversation's
    LaunchedEffect(talk != null) {
        if (talk != null) engine.pause("talking") else engine.resume("talking")
    }
    // walking away ends the conversation
    LaunchedEffect(here) {
        val t = talk
        if (t != null && t.npc.building != here) { t.close(voices); talk = null }
    }
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) engine.importModel(uri) }
    // the sound follows the hour, the weather and the season; footsteps while walking (the stride's own cadence)
    // the town's kind (harbor, farm, mill ...) picks its music
    val townKind = remember(data) { com.timerpg.street.audio.SoundRules.townOf(data.town) }
    LaunchedEffect(sound) {
        if (sound == null) return@LaunchedEffect
        var stepAt = 0f; var foot = 0
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            val w = WEATHERS[weather].second
            sound.set(com.timerpg.street.audio.SoundRules.at(hour, w?.rain ?: 0f, w?.cloudiness ?: 0f, w?.mist ?: 0f, season, townKind))
            if (GameState.walking) {
                // a step every ~0.62 street units of stride... measured by the distance walked
                if (GameState.travel - stepAt > 17f || GameState.travel < stepAt) {
                    stepAt = GameState.travel; foot = (foot + 1) % 4
                    val wet = (w?.wet ?: 0f) > .3f
                    sound.play(if (wet) "step_wet_${foot + 1}" else "step_${foot + 1}", .5f, if (foot % 2 == 0) -.15f else .15f)
                }
            }
        }
    }
    // the player's voice: hold the mic in a conversation, say something, let go
    val ears = remember { com.timerpg.street.voice.PlayerVoice(ctx.applicationContext) }
    // and what each person remembers of it
    val memory = remember { com.timerpg.street.dialogue.MemoryService(ctx.applicationContext) }
    // why the game stopped last time (if it crashed or was killed): shown once, with a copy button
    var lastCrash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        lastCrash = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CrashReport.takeLast(ctx.applicationContext) }
        // then every 3 s, what the game is doing (for a kill that leaves no stack)
        while (true) {
            val a = engine.status.value
            val what = "build ${BuildConfig.VERSION_NAME} · AI ${a.state} on ${listOf("CPU", "GPU", "NPU").getOrElse(a.runsOn) { "?" }} · lane ${com.timerpg.street.llm.AiLane.current ?: "idle"} · " +
                "mic ${ears.state} · ${if (talk != null) "talking" else "walking"} · street $streetNo building #$near"
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CrashReport.breadcrumb(ctx.applicationContext, what) }
            kotlinx.coroutines.delay(3000)
        }
    }
    // updates from inside the game: a quiet look at start
    val updater = remember { AppUpdater(ctx.applicationContext) }
    LaunchedEffect(Unit) { updater.check(quiet = true) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { ears.release(); memory.release() } }
    // what the people here say while they think, voiced ahead (so it plays at once)
    LaunchedEffect(atHere.firstOrNull()?.id) { atHere.firstOrNull()?.let { voices.prepareFillers(it, Fillers.all(it.warmth)) } }
    // the speech model loads when a conversation opens (not when walking past people)
    LaunchedEffect(talk != null) { if (talk != null) ears.prewarm() }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // the words to expect: the town's name and everyone's names, so the recognizer spells them as the game does
    val names = remember(engine.data.value) {
        engine.data.value?.let { d -> (listOf(d.townName) + d.npcs.map { it.name }).distinct().joinToString(", ") } ?: ""
    }
    // inside a building: the rooms our game logic planned and furnished (assets/interiors.json), by sprite name
    val rooms = remember {
        try {
            val all = com.lifemax.rpg.feature.habits.presentation.scene.InteriorRoom.parseAll(ctx.assets.open("interiors.json").bufferedReader().use { it.readText() })
            com.lifemax.rpg.feature.habits.presentation.scene.InteriorArt.images = (ctx.assets.list("interior") ?: emptyArray()).filter { it.endsWith(".png") }
                .associate { f -> f.removeSuffix(".png") to ctx.assets.open("interior/$f").use { BitmapFactory.decodeStream(it) }.asImageBitmap() }
            all
        } catch (e: Exception) { emptyMap() }
    }
    var inside by remember { mutableStateOf<com.lifemax.rpg.feature.habits.presentation.scene.InteriorRoom?>(null) }
    var walker by remember { mutableStateOf(com.lifemax.rpg.feature.habits.presentation.scene.RoomWalker(0f, 0f)) }
    var door by remember { mutableFloatStateOf(0f) }
    // going in or out: a short fade through black, the room (or the street) swapped at its darkest
    var doorTo by remember { mutableStateOf<Pair<Boolean, com.lifemax.rpg.feature.habits.presentation.scene.InteriorRoom?>?>(null) }
    LaunchedEffect(doorTo) {
        val go = doorTo ?: return@LaunchedEffect
        val start = androidx.compose.runtime.withFrameNanos { it }
        var swapped = false
        while (true) {
            val t = (androidx.compose.runtime.withFrameNanos { it } - start) / 1e9f
            door = if (t < .25f) t / .25f else (1f - (t - .25f) / .3f).coerceAtLeast(0f)
            if (!swapped && t >= .25f) {
                swapped = true
                GameState.moveX = 0f; GameState.moveLane = 0f
                val room = go.second
                if (go.first && room != null) { walker = com.lifemax.rpg.feature.habits.presentation.scene.RoomWalker(room.spawnX, room.spawnY); inside = room } else inside = null
                sound?.play("door_bell", .45f, 0f)
            }
            if (t >= .55f) break
        }
        door = 0f; doorTo = null
    }
    // walking out through the door leaves
    LaunchedEffect(inside) {
        val room = inside ?: return@LaunchedEffect
        while (inside === room) {
            androidx.compose.runtime.withFrameNanos { }
            if (doorTo == null && walker.y > room.exitY - 6f && kotlin.math.abs(walker.x - room.exitX) < 26f && GameState.moveLane > 0f) doorTo = false to null
        }
    }
    // the person who lives or works here, standing inside (the same look as at their door)
    val keeperSheet = remember(inside, near) {
        val sheets = SpriteSheets.sheets
        val seed = GameState.keepers.firstOrNull { it.first == near }?.second
        if (inside == null || seed == null || sheets.isEmpty()) null
        else {
            val mine = GameState.look.mod(sheets.size)
            val others = sheets.indices.filter { it != mine }
            if (others.isEmpty()) null else sheets[others[seed.mod(others.size)]]
        }
    }
    val keeperSpot = remember(inside) { inside?.keeperSpot() }
    var nearKeeper by remember { mutableStateOf(false) }
    LaunchedEffect(inside) {
        while (inside != null) {
            androidx.compose.runtime.withFrameNanos { }
            val k = keeperSpot ?: break
            val dx = walker.x - k.first; val dy = walker.y - k.second
            nearKeeper = dx * dx + dy * dy < 90f * 90f
        }
    }
    // tuning the NPU: each start loads the model on the NPU, runs the speed test twice, keeps the warm result, restarts
    val activity = ctx as? android.app.Activity
    val tuning = remember { NpuTune.active(ctx) }
    var tuneRuns by remember { mutableIntStateOf(0) }
    LaunchedEffect(ai.state, ai.speedTesting, ai.speed) {
        if (!tuning || activity == null) return@LaunchedEffect
        val step = NpuTune.step(ctx)
        when {
            ai.state == ModelState.ERROR -> NpuTune.record(activity, "could not load (${ai.detail.take(80)})", null)
            ai.state == ModelState.READY && ai.runsOn != 2 -> NpuTune.record(activity, "the NPU could not take the model (${ai.gpuNote ?: "fell back to the CPU"})", null)
            ai.state == ModelState.READY && !ai.speedTesting && ai.speed == null && tuneRuns == 0 -> { tuneRuns = 1; engine.speedTest() }
            ai.state == ModelState.READY && !ai.speedTesting && ai.speed != null && tuneRuns == 1 -> { tuneRuns = 2; engine.speedTest() }
            ai.state == ModelState.READY && !ai.speedTesting && ai.speed != null && tuneRuns == 2 -> {
                val r = ai.speed!!; val st = r.stats
                val gen = if (st.genMs > 0) st.genTokens * 1000f / st.genMs else 0f
                val pre = if (st.prefillMs > 0) (st.promptTokens - st.reusedTokens) * 1000f / st.prefillMs else 0f
                val ok = r.error == null && r.passed
                val text = if (r.error != null) "failed (${r.error.take(80)})"
                    else "%d ms a line · writing %.1f tok/s · prompt %.0f tok/s · %s%s".format(r.totalMs, gen, pre, if (ok) "reply passed" else "reply FAILED the check",
                        ai.loadInfo?.let { " · $it" } ?: "")
                NpuTune.record(activity, text, if (ok) r.totalMs else null)
            }
        }
        if (step < 0) return@LaunchedEffect
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val room = inside
        if (room != null) com.lifemax.rpg.feature.habits.presentation.scene.InteriorStage(room, walker, hour, keeperSheet, Modifier.fillMaxSize())
        else HabitsStage(
            p = p, ui = HabitsUi(focus = shops[0].id), visible = true, top = 0f, bottom = 0f, cover = 0f, inset = 0f,
            accent = Color(0xFF6FA8FF), actions = StageActions(), modifier = Modifier.fillMaxSize(),
        )
        Text("${street.name} · ${data.town}", 14.sp, Modifier.align(Alignment.TopStart).padding(14.dp).background(Color(0x99101420), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
        if (turn >= 0f && turnArt != null) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = turnAlpha }) {
                CornerTurn(turnArt, turn, Modifier.fillMaxSize(), hour, season, reverse = turnBack, clock = turnClock, skyBehind = true,
                    camX = { p -> val e = p * p * (3f - 2f * p); 100f + turnDx * (1f - e) })
            }
        }
        if (fade > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = fade)))
        if (door > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = door)))
        if (turnArt != null && !turning && atCorner && inside == null) Chip(if (streetNo == 0) "↰ Turn onto ${data.streets.getOrNull(1)?.name ?: "the side street"}" else "↳ Back to ${data.streets[0].name}", false,
            Modifier.align(if (streetNo == 0) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 14.dp)) {
                turnBack = streetNo == 1
                // the camera's place along the main street from the junction's middle (turn units), where the view is now
                turnDx = if (streetNo == 0 && !GameState.junctionX.isNaN()) (GameState.viewMiddle - GameState.junctionX) * 224f / 184f else 0f
                turning = true
            }
        // the shop the player is standing at
        if (inside != null) {
            Text(inside!!.title + (inside!!.use.takeIf { it != inside!!.title && it != "Home" }?.let { "  ·  $it" } ?: ""), 15.sp,
                Modifier.align(Alignment.TopCenter).padding(top = 62.dp).background(Color(0x99101420), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 6.dp))
        } else if (near in shops.indices) {
            val st = shops[near]
            Text(st.cue + (owners.getOrNull(near)?.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: ""), 15.sp,
                Modifier.align(Alignment.TopCenter).padding(top = 62.dp).background(Color(0x99101420), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 6.dp))
        }
        // someone to talk to here
        val open = talk
        if (open != null) {
            DialoguePanel(open, onTopic = { open.ask(engine, it, voices) }, onClose = { open.close(voices); talk = null },
                ears = ears,
                onTalk = { down ->
                    if (down) {
                        if (!ears.hasPermission()) askMic.launch(android.Manifest.permission.RECORD_AUDIO)
                        else { voices.stop(); engine.pause("listening"); ears.start() }
                    } else ears.stop(names) { said -> engine.resume("listening"); open.heardNote = ears.lastHeard; open.askSaid(engine, said, voices, memory) }
                    if (!down && ears.state == com.timerpg.street.voice.PlayerVoice.State.IDLE) engine.resume("listening")
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 92.dp))
        } else if (!turning && turn < 0f && doorTo == null) {
            // someone to talk to (inside: when standing near them); the way in and out
            val roomHere = street.sprites.getOrNull(near)?.let { rooms[it] }
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (inside == null || nearKeeper) for (npc in atHere) Chip("Talk to ${npc.first}", false) {
                    talk = Talk(npc).also { t -> npc.topics.firstOrNull()?.let { t.ask(engine, it, voices) } }
                }
                if (inside != null) Chip("Leave", false) { doorTo = false to null }
                else if (roomHere != null) Chip("Go inside", false) { doorTo = true to roomHere }
            }
        }
        lastCrash?.let { report ->
            Column(
                Modifier.align(Alignment.Center).widthIn(max = 560.dp).background(PANEL, RoundedCornerShape(14.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("The game stopped unexpectedly last time", 15.sp)
                Text(report.lines().take(14).joinToString("\n"), 11.sp, color = DIM)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Copy report", false) {
                        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Timerpg crash", report))
                    }
                    Chip("Close", false) { lastCrash = null }
                }
            }
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
        HoldPad("◀", "▶", Modifier.align(Alignment.BottomStart).padding(18.dp)) { GameState.moveX = it }
        HoldPad("▲", "▼", Modifier.align(Alignment.BottomEnd).padding(18.dp)) { GameState.moveLane = it }
        if (tuning) Column(
            Modifier.align(Alignment.Center).widthIn(max = 520.dp).background(PANEL, RoundedCornerShape(14.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val step = NpuTune.step(ctx)
            Text("Tuning the NPU: setting ${step + 1} of ${NpuTune.CONFIGS.size}", 15.sp)
            Text(NpuTune.CONFIGS.getOrNull(step)?.name ?: "", 13.sp)
            Text(when {
                ai.state != ModelState.READY -> "Loading the model on the NPU…"
                else -> "Speed test ${tuneRuns.coerceAtLeast(1)} of 2…"
            } + " The game restarts between settings; leave it running (about half a minute each).", 12.sp, color = DIM)
            for (line in NpuTune.results(ctx)) Text(line, 11.sp, color = DIM)
            Chip("Stop tuning", false) { activity?.let { NpuTune.cancel(it) } }
        }
        // the dialogue AI, under the street name (drawn last, so its panel is on top): its state at a glance; tap for the
        // model file and the speed numbers
        Column(Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 50.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(aiChipLabel(ai), aiPanel) { aiPanel = !aiPanel }
            updater.available?.let { r -> if (!aiPanel) Chip("⬆ Update: ${r.versionName}", false) { aiPanel = true } }
            if (aiPanel) AiPanel(ai, engine, voices, updater, onPick = {
                try { pickModel.launch(arrayOf("*/*")) } catch (e: Exception) { /* no document picker on this device */ }
            })
        }
    }
}

/** An open conversation: who, the topic asked, and the line on screen (updated as the model writes it). */
private class Talk(val npc: Npc) {
    var topic by mutableStateOf<Topic?>(null)
    var line by mutableStateOf<Line?>(null)
    private var request: DialogueEngine.Request? = null

    fun ask(engine: DialogueEngine, t: Topic, voices: com.timerpg.street.voice.NpcVoiceService? = null) {
        request?.close()
        topic = t
        said = null
        // a line is spoken once it is final (the model's when it has passed the check, else the game's own)
        var spoken: String? = null
        var early = false
        fun heard(l: Line) {
            if (!early && voices != null) early = earlyVoice(l, voices)
            if (l.final && l.text != spoken) { spoken = l.text; voices?.say(npc, l.text) }
        }
        val r = engine.ask(npc, t) { line = it; heard(it) }
        request = r
        line = r.first
        // the line is still being written: they say something while they think
        if (r.first?.final != true) filler(t.label, voices)
        r.first?.let { heard(it) }
    }

    private var fillerSeed = (npc.id.hashCode() and 0xffff)

    /** What they say at once while the real line is made (already voiced, else nothing). */
    private fun filler(question: String, voices: com.timerpg.street.voice.NpcVoiceService?) {
        if (voices == null) return
        val seed = fillerSeed++
        voices.sayFiller(npc, List(6) { Fillers.pick(question, npc.warmth, seed + it * 7) })
    }

    /**
     * The model's line still being written: once its first sentence is complete (the model has gone on past it) and
     * passes the same check as a whole line (bar the facts it must mention, which may come later), it is spoken at
     * once, and the finished line goes on from there. True once spoken.
     */
    private fun earlyVoice(l: Line, voices: com.timerpg.street.voice.NpcVoiceService): Boolean {
        if (l.final || !l.ai) return false
        val m = FIRST_SENTENCE.find(l.text) ?: return false
        val tp = topic ?: return false
        val ok = com.timerpg.street.llm.Dialogue.validate(m.groupValues[1], emptyList(), tp.self, tp.prompt ?: "") ?: return false
        voices.sayFirst(npc, ok)
        return true
    }

    /** What the player last said out loud, shown above the answer. */
    var said by mutableStateOf<String?>(null)
    /** How long each step of the last spoken question took (hearing, memories, the model), shown under the answer. */
    var timing by mutableStateOf<String?>(null)
    /** Set by the caller before [askSaid]: how the words were heard and how long it took. */
    var heardNote: String? = null

    /** The player said [text]: the person answers those words (the topic they are nearest is marked). */
    fun askSaid(engine: DialogueEngine, text: String, voices: com.timerpg.street.voice.NpcVoiceService? = null,
                memory: com.timerpg.street.dialogue.MemoryService? = null) {
        request?.close()
        said = text
        var spoken: String? = null
        val t0 = android.os.SystemClock.elapsedRealtime()
        var tAsk = 0L; var tFirst = 0L
        // they say the question back at once (voiced now, beside the model writing the answer), the answer after it
        val echo = com.timerpg.street.dialogue.Fillers.echo(text, npc.warmth, fillerSeed++)
        if (echo != null) voices?.sayEcho(npc, echo)
        val heardBy = heardNote
        var early = false
        fun heard(l: Line) {
            if (l.ai && tFirst == 0L && tAsk > 0L) tFirst = android.os.SystemClock.elapsedRealtime()
            if (!early && voices != null) early = earlyVoice(l, voices)
            if (!l.final || l.text == spoken) return
            spoken = l.text
            if (l.ai) {
                val end = android.os.SystemClock.elapsedRealtime()
                timing = listOfNotNull(heardBy, "memories %.1f s".format((tAsk - t0) / 1000.0),
                    "first words %.1f s".format((tFirst.coerceAtLeast(tAsk) - tAsk) / 1000.0), "line done %.1f s".format((end - tAsk) / 1000.0)).joinToString(" · ")
            }
            voices?.say(npc, l.text)
            // they remember what was said, and what they answered
            memory?.remember(npc.id, text, l.text)
        }
        fun go(remembered: List<String>) {
            if (said != text) return   // something else was asked meanwhile
            tAsk = android.os.SystemClock.elapsedRealtime()
            val (tp, r) = engine.askFree(npc, text, remembered) { line = it; heard(it) } ?: return
            topic = tp
            request = r
            line = r.first
            if (r.first?.final != true && echo == null) filler(text, voices)
            heard(r.first)
        }
        // what they remember that bears on it goes into their fact sheet first
        if (memory != null && memory.installed) memory.recall(npc.id, text) { go(it) } else go(emptyList())
    }

    fun close(voices: com.timerpg.street.voice.NpcVoiceService? = null) {
        request?.close()
        request = null
        voices?.stop()
    }
}

private val FIRST_SENTENCE = Regex("""^(.{8,}?[.!?])\s+\S""")
private val PANEL = Color(0xCC101420)
private val DIM = Color(0xFFB8C2D6)
private val AI_BLUE = Color(0xFF6FA8FF)

/** The conversation: who, where, what they said (and whose words: the model's or the game's), and what to ask next. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DialoguePanel(t: Talk, onTopic: (Topic) -> Unit, onClose: () -> Unit, modifier: Modifier,
                          ears: com.timerpg.street.voice.PlayerVoice? = null, onTalk: (Boolean) -> Unit = {}) {
    Column(
        modifier.widthIn(max = 560.dp).background(PANEL, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.npc.name, 16.sp)
                Text(t.npc.buildingName, 12.sp, color = DIM)
            }
            Spacer(Modifier.width(8.dp))
            Chip("✕", false, onClick = onClose)
        }
        t.said?.let { Text("You: \u201c$it\u201d", 13.sp, color = DIM) }
        t.line?.let { l ->
            Text(l.text, 15.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(if (l.ai) AI_BLUE else Color(0xFF8A93A6), CircleShape))
                Spacer(Modifier.width(5.dp))
                Text(l.note, 11.sp, color = DIM)
            }
        }
        t.timing?.let { Text(it, 11.sp, color = DIM) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (ears != null) MicButton(ears, onTalk)
            for (tp in t.npc.topics) Chip(chipLabel(tp), t.topic === tp) { onTopic(tp) }
        }
        ears?.problem?.let { Text(it, 11.sp, color = Color(0xFFFFB4A0)) }
        if (ears?.usingPhone == true) Text("Heard by the phone's own speech recognition (ours does not fit in memory beside the AI on the NPU).", 11.sp, color = DIM)
    }
}

/** Hold to talk: listening while held (a meter shows the voice), writing it down after. */
@Composable
private fun MicButton(ears: com.timerpg.street.voice.PlayerVoice, onTalk: (Boolean) -> Unit) {
    val st = ears.state
    val label = when (st) {
        com.timerpg.street.voice.PlayerVoice.State.LISTENING -> "● Listening…"
        com.timerpg.street.voice.PlayerVoice.State.THINKING -> "… Writing it down"
        com.timerpg.street.voice.PlayerVoice.State.LOADING -> "… Loading"
        else -> "🎤 Hold to talk"
    }
    val on = st == com.timerpg.street.voice.PlayerVoice.State.LISTENING
    Box(
        Modifier
            .background(if (on) Color(0xFF8A2E2E).copy(alpha = .55f + .45f * ears.level) else Color(0x33FFFFFF), RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    onTalk(true)
                    waitForUpOrCancellation()
                    onTalk(false)
                }
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) { Text(label, 13.sp) }
}

/** The game's menu labels are long for a landscape phone: short ones for the known topics, the game's for the rest. */
private fun chipLabel(t: Topic): String = when (t.intent) {
    "greet" -> "Hello"
    "chat" -> "Chat"
    "work" -> "Their work"
    "family" -> "Their family"
    "town" -> t.label.removePrefix("Ask about ")
    "rumors" -> "Heard anything?"
    "secret" -> "Any secrets?"
    else -> t.label
}

private fun aiChipLabel(s: AiStatus): String = when (s.state) {
    ModelState.STARTING -> "AI …"
    ModelState.NO_ENGINE -> "AI off"
    ModelState.NO_MODEL -> "AI: no model"
    ModelState.COPYING -> if (s.copyPercent >= 0) "AI: copying ${s.copyPercent}%" else "AI: copying ${s.copiedMb} MB"
    ModelState.LOADING -> "AI: loading…"
    ModelState.READY -> if (s.working != null) "AI ✓ writing" else "AI ✓"
    ModelState.UNLOADED -> "AI: unloaded"
    ModelState.ERROR -> "AI: error"
}

private fun f1(v: Float) = String.format(java.util.Locale.US, "%.1f", v)
private fun f0(v: Float) = String.format(java.util.Locale.US, "%.0f", v)

/** The model (its file and state) and the speed numbers. */
@Composable
private fun AiPanel(s: AiStatus, engine: DialogueEngine, voices: com.timerpg.street.voice.NpcVoiceService, updater: AppUpdater, onPick: () -> Unit) {
    Column(
        Modifier.width(340.dp).heightIn(max = 240.dp).background(PANEL, RoundedCornerShape(12.dp)).verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Dialogue AI", 15.sp)
        Text(
            when (s.state) {
                ModelState.STARTING -> "Starting…"
                ModelState.NO_ENGINE -> "No AI engine on this phone"
                ModelState.NO_MODEL -> "No model"
                ModelState.COPYING -> if (s.copyPercent >= 0) "Copying the model: ${s.copyPercent}%" else "Copying the model: ${s.copiedMb} MB"
                ModelState.LOADING -> "Loading the model…"
                ModelState.READY -> "Ready (${s.modelMb} MB model)"
                ModelState.UNLOADED -> "Unloaded (low memory)"
                ModelState.ERROR -> "Error"
            }, 13.sp,
        )
        if (s.detail.isNotEmpty()) Text(s.detail, 12.sp, color = DIM)
        s.gpuNote?.let { Text(it, 11.sp, color = Color(0xFFFFB4A0)) }
        // the phone's memory now, and what the AI lane is doing (to measure the models' footprint on a real phone)
        val ctx = androidx.compose.ui.platform.LocalContext.current
        var ram by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            val am = ctx.getSystemService(android.app.ActivityManager::class.java)
            while (true) {
                val mi = android.app.ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
                val mine = android.os.Debug.getPss() shr 10
                val busy = com.timerpg.street.llm.AiLane.current ?: "idle"
                ram = "RAM: ${mi.availMem shr 20} MB free of ${mi.totalMem shr 20} MB · the game ${mine} MB · AI: $busy"
                kotlinx.coroutines.delay(1000)
            }
        }
        Text(ram, 11.sp, color = DIM)
        // the game itself: this build, and a newer one if there is
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Build ${updater.current}", 11.sp, color = DIM)
            val r = updater.available
            when {
                updater.progress >= 0f -> Text("Downloading ${(updater.progress * 100).toInt()}%", 12.sp)
                r != null -> Chip("Get update ${r.versionName}", false) { updater.install(r) }
                else -> Chip("Check for update", false) { updater.check() }
            }
        }
        if (updater.note.isNotEmpty()) Text(updater.note, 11.sp, color = DIM)
        updater.available?.notes?.takeIf { it.isNotBlank() }?.let { Text(it, 11.sp, color = DIM) }
        if (s.state == ModelState.COPYING) {
            Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0x66FFFFFF), RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxWidth((s.copyPercent.coerceIn(0, 100) / 100f).coerceAtLeast(.02f)).height(4.dp).background(AI_BLUE, RoundedCornerShape(2.dp)))
            }
            Chip("Cancel", false) { engine.cancelImport() }
        } else if (s.state != ModelState.LOADING && s.state != ModelState.STARTING) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // (no point copying 400 MB for a build that cannot run it)
                if (s.state != ModelState.NO_ENGINE) Chip(if (s.hasModel) "Replace model…" else "Choose model file…", false, onClick = onPick)
                if (s.hasModel && (s.state == ModelState.ERROR || s.state == ModelState.UNLOADED)) Chip(if (s.state == ModelState.UNLOADED) "Load" else "Retry", false) { engine.reload() }
                if (s.hasModel) Chip("Remove", false) { engine.removeModel() }
            }
        }
        // the dialogue model and both voice engines, fetched once from Hugging Face
        Spacer(Modifier.height(2.dp))
        Text("Voices & AI download", 14.sp)
        val missing = remember(voices.downloading) { voices.missingMb }
        if (voices.downloading >= 0f) {
            Text(voices.downloadNote, 12.sp, color = DIM)
            Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0x66FFFFFF), RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxWidth(voices.downloading.coerceIn(.02f, 1f)).height(4.dp).background(AI_BLUE, RoundedCornerShape(2.dp)))
            }
            Chip("Pause", false) { voices.cancelDownload() }
        } else {
            if (voices.downloadNote.isNotEmpty()) Text(voices.downloadNote, 12.sp, color = DIM)
            if (missing > 0) Chip("Download everything: Qwen, voices, speech ($missing MB)", false) { voices.download { engine.reload() } }
            else Text("Qwen, Kokoro and Supertonic are installed.", 12.sp, color = DIM)
        }
        Chip(if (voices.enabled) "Voices: on" else "Voices: off", voices.enabled) { voices.enabled = !voices.enabled; if (!voices.enabled) voices.stop() }
        Spacer(Modifier.height(2.dp))
        Text("Speed", 14.sp)
        val st = s.stats
        Text("Threads ${st.threads} · model load ${f1(st.loadMs / 1000f)} s", 12.sp, color = DIM)
        Text("Last prompt ${st.promptTokens} tokens (${st.reusedTokens} reused) · prefill ${f0(st.prefillPerSec)} tok/s", 12.sp, color = DIM)
        Text("Generation ${st.genTokens} tokens · ${f1(st.genPerSec)} tok/s · first token ${st.firstTokenMs} ms", 12.sp, color = DIM)
        Text("Written ahead: ${s.lines} lines (${s.builtIn} kept built-in) · queued ${s.queued}" + (if (s.paused.isNotEmpty()) " · paused: ${s.paused}" else ""), 12.sp, color = DIM)
        s.pregenOff?.let { Text(it, 12.sp, color = DIM) }
        s.working?.let { Text("Writing: $it", 12.sp, color = DIM) }
        Chip(if (s.speedTesting) "Testing…" else "Speed test", s.speedTesting) { if (!s.speedTesting) engine.speedTest() }
        // where the dialogue model runs: the phone's GPU (Adreno) or its CPU; run the speed test each way to compare
        if (s.hasGpu || s.hasNpu) {
            val names = listOf("CPU", "GPU", "NPU")
            Chip("Runs on: ${names[s.runsOn.coerceIn(0, 2)]}", s.runsOn != 0) {
                // the next one this phone has: CPU, then the GPU, then the NPU
                val order = listOf(0) + (if (s.hasGpu) listOf(1) else emptyList()) + (if (s.hasNpu) listOf(2) else emptyList())
                engine.setDevice(order[(order.indexOf(s.runsOn).coerceAtLeast(0) + 1) % order.size])
            }
        }
        voices.lastTiming?.let { Text("Voice: $it", 12.sp, color = DIM) }
        s.rejected?.let { Text("Last line written again: $it", 12.sp, color = DIM) }
        run {
            var x by remember { mutableStateOf(voices.xnnpack) }
            Chip("Voice kernels: " + (if (x) "XNNPACK" else "standard"), x) { x = !x; voices.xnnpack = x }
            var o by remember { mutableIntStateOf(voices.only) }
            Chip("Voices: " + listOf("Kokoro + Supertonic", "Kokoro only", "Supertonic only")[o], o != 0) { o = (o + 1) % 3; voices.only = o }
        }
        // MTP: the model guesses words ahead and checks them in one pass
        run {
            var n by remember { mutableIntStateOf(engine.mtpWanted) }
            val st = s.stats
            Chip("Guess ahead (MTP): " + (if (n == 0) "off" else "$n words"), n > 0) { n = (n + 1) % 4; engine.setMtp(n) }
            if (st.drafted > 0) Text("MTP: %d of %d guesses right (%.0f%%)".format(st.accepted, st.drafted, 100f * st.accepted / st.drafted), 12.sp, color = DIM)
        }
        if (s.hasNpu) {
            val act = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
            val pc = androidx.compose.ui.platform.LocalContext.current
            Text("NPU setting: ${NpuTune.chosen(pc) ?: "default"}" + (s.loadInfo?.let { " · $it" } ?: ""), 12.sp, color = DIM)
            Chip("Tune the NPU (tries ${NpuTune.CONFIGS.size} settings, restarts the game)", false) { act?.let { NpuTune.begin(it) } }
            for (line in NpuTune.results(pc)) Text(line, 11.sp, color = DIM)
        }
        // the CPU's fast kernels copy the whole model into memory (faster prompts; speech and the voices may not fit)
        if (s.runsOn == 0) {
            var fast by remember { mutableStateOf(engine.fastCpuWanted) }
            Chip(if (fast) "CPU kernels: fast (model held in RAM)" else "CPU kernels: shared (model mapped)", fast) { fast = !fast; engine.setFastCpu(fast) }
        }
        s.speed?.let { r ->
            if (r.totalMs == 0L && r.error != null) Text(r.error, 12.sp, color = DIM)
            else {
                Text("${r.who} (${r.intent}): ${r.totalMs} ms, ${if (r.passed) "passed the check" else "rejected"}" + (r.error?.let { " · $it" } ?: ""), 12.sp)
                Text("${r.stats.promptTokens} prompt tokens (${r.stats.reusedTokens} reused) · prefill ${f0(r.stats.prefillPerSec)} tok/s · " +
                    "${r.stats.genTokens} tokens at ${f1(r.stats.genPerSec)} tok/s · first token ${r.stats.firstTokenMs} ms", 12.sp, color = DIM)
                if (r.reply.isNotEmpty()) Text("“${r.reply}”", 12.sp, color = DIM)
            }
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
    // the gesture handler outlives recompositions: always call the latest onClick (a reused chip may now mean someone else)
    val click by rememberUpdatedState(onClick)
    Box(
        modifier.background(if (on) Color(0xCC3A6FD8) else Color(0x99101420), RoundedCornerShape(10.dp)).pointerInput(Unit) {
            awaitEachGesture { awaitFirstDown(); waitForUpOrCancellation()?.let { click() } }
        }.padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, 13.sp) }
}

/**
 * Two keys under one thumb: the half the finger is over decides the direction (rolling from one key to the other switches,
 * drifting off the pad keeps going), and lifting the finger, or the pad going away, always stops.
 */
@Composable
private fun HoldPad(neg: String, pos: String, modifier: Modifier = Modifier, set: (Float) -> Unit) {
    Row(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val d = awaitFirstDown(requireUnconsumed = false)
                fun dirAt(x: Float) = if (x < size.width / 2f) -1f else 1f
                try {
                    set(dirAt(d.position.x))
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.lastOrNull { it.pressed } ?: break
                        set(dirAt(c.position.x))
                    }
                } finally { set(0f) }
            }
        },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) { PadKey(neg); PadKey(pos) }
}

@Composable
private fun PadKey(label: String) =
    Box(Modifier.size(64.dp).background(Color(0x99101420), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { Text(label, fontSize = 26.sp) }

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
private fun Text(s: String, fontSize: androidx.compose.ui.unit.TextUnit = 15.sp, modifier: Modifier = Modifier, color: Color = Color.White) =
    androidx.compose.foundation.text.BasicText(
        s, modifier,
        style = androidx.compose.ui.text.TextStyle(color = color, fontSize = fontSize, fontWeight = FontWeight.SemiBold),
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
