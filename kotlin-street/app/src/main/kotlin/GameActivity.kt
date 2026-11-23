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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
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
import com.lifemax.rpg.feature.habits.presentation.scene.MoveInput
import com.lifemax.rpg.feature.habits.presentation.scene.MoveStick
import com.lifemax.rpg.feature.habits.presentation.scene.CornerWalk
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuildingLoad
import androidx.compose.ui.graphics.graphicsLayer
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
    private var memoryListener: ((Int) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // the game's recent events for crash reports (last run's moved aside first), then the crash handler
        AppInit.basics(applicationContext)
        // the playtest recorder (docs/PLAYTEST.md): the session's timeline, its cold start by phase
        com.timerpg.street.play.PlayLog.init(applicationContext)
        com.timerpg.street.play.PlayLog.phase("activity.create")
        com.timerpg.street.shot.Crumbs.add("start: build ${BuildConfig.VERSION_NAME}")
        AndroidFx.install()
        // the game takes over from the overnight writer (it lets go of a model it loaded: the town is built first)
        com.timerpg.street.night.NightWriter.gameOpened()
        // the NPU's code path and its settings (the tuned ones, or the one being tried while tuning): before the library loads
        AppInit.native(applicationContext)
        super.onCreate(savedInstanceState)
        // the scene's two platform-specific image calls
        PlatformImages.create = { px, w, h, mip ->
            Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).apply { setHasMipMap(mip) }.asImageBitmap()
        }
        PlatformImages.update = { img: ImageBitmap, px, w, h -> img.asAndroidBitmap().setPixels(px, 0, w, 0, 0, w, h) }
        immersive()
        fastestRefresh()
        // our characters: only the first 4 views x 6 frames (x < 768) of rows 0-2 and the seated row 4 (y < 320: the rider
        // on the bike) are ever drawn, so only that is decoded (about 8 MB of bitmaps instead of 75)
        SpriteSheets.sheets = (1..8).map { n ->
            assets.open("characters/char_0$n.png").use { s ->
                @Suppress("DEPRECATION") val d = android.graphics.BitmapRegionDecoder.newInstance(s, false)
                if (d == null) BitmapFactory.decodeStream(assets.open("characters/char_0$n.png"))
                else try { d.decodeRegion(android.graphics.Rect(0, 0, minOf(768, d.width), minOf(320, d.height)), null) } finally { d.recycle() }
            }.asImageBitmap()
        }
        // the townspeople's own sheets (tools/make_townsfolk.py: 768 x 128 each, about 0.4 MB of bitmap apiece), read on
        // a thread (until they are in, people wear the old sheets)
        if (com.lifemax.rpg.feature.habits.presentation.scene.TownFolk.sheets.isEmpty()) Thread({
            runCatching {
                com.lifemax.rpg.feature.habits.presentation.scene.TownFolk.load(assets.open("characters/folk/folk.json").use { it.readBytes().decodeToString() }) { f ->
                    assets.open("characters/folk/$f").use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
                }
            }
        }, "townsfolk").start()
        // a recreated activity must not inherit a held direction
        MoveInput.releaseAll()
        // our lamp, trees and bench
        fun prop(n: String) = assets.open("props/$n.png").use { BitmapFactory.decodeStream(it) }.asImageBitmap()
        SpriteArt.lamp = prop("lamp_1"); SpriteArt.bench = prop("bench_1"); SpriteArt.trees = listOf("tree_1", "tree_2", "tree_3").map { prop(it) }
        GameState.enabled = true
        GameState.look = 1
        // which renderer draws the street (the AI panel's switch; the classic unless Filament was chosen)
        StreetRenderer.load(applicationContext)
        // the walkable town (TOWN.md T10): its people talk from the start, and its walk's events go to crash reports
        com.timerpg.street.dialogue.DialogueEngine.startDir = if (StreetRenderer.townMode) "town/${StreetRenderer.town}/" else ""
        com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.onEvent = { com.timerpg.street.shot.Crumbs.add(it) }
        // crash reports: how the last runs ended (Android's record), and any waiting sent to Claude; all in the background
        com.timerpg.street.shot.ShotUploader.onLaunch(applicationContext, StreetRenderer.lastStartDied || StreetRenderer.lastTownBuildDied)
        // the dialogue model loads only once the town is built (both crash reports of build 302: the 9B model coming in
        // beside the town's build, then the foreground game killed for memory; the build took 20-40 s against 3.5 on a
        // computer while the load ran)
        if (StreetRenderer.townMode) DialogueEngine.holdAtStart = TOWN_BUILD_HOLD
        val engine = DialogueEngine.shared(applicationContext)
        if (StreetRenderer.townMode && !TownSession.built) engine.holdLoad(TOWN_BUILD_HOLD)
        dialogue = engine
        val v = com.timerpg.street.voice.NpcVoiceService(applicationContext)
        voices = v
        // the street's sound: ambience, music, footsteps; it dips while someone speaks
        val s = com.timerpg.street.audio.SoundScape(applicationContext)
        sound = s
        v.onSpeaking = { on -> s.duck(on) }
        // lines written ahead get their voices made ahead too
        engine.onWritten = { npc, text -> v.prepare(npc, text) }
        // the phone's free memory, watched: short, the writing ahead stops and idle models go; critical, the dialogue
        // model goes too (Android 14+ sends a running app no warning of its own)
        com.timerpg.street.llm.MemoryWatch.start(applicationContext)
        val onMemory: (Int) -> Unit = { level -> engine.onMemoryLevel(level); if (level >= 1) v.trimForMemory() }
        memoryListener = onMemory
        com.timerpg.street.llm.MemoryWatch.listeners += onMemory
        setContent { GameScreen(engine, v, s) }
        com.timerpg.street.play.PlayLog.phase("activity.content")
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
        // a notification shade, a dialog, another app: the thumb's lift may never reach us, so nothing stays held
        else letGo()
    }

    override fun onPause() {
        getSystemService(android.hardware.input.InputManager::class.java)?.unregisterInputDeviceListener(devices)
        letGo()
        // (the always-listening mic closes)
        Foreground.resumed = false
        com.timerpg.street.shot.Crumbs.add("pause")
        // where the player stands in the town, for the next start
        TownSession.save(this)
        // Filament draws nothing in the background
        StreetRenderer.paused = true
        super.onPause()
    }

    /** Nothing held: the stick, the keys, and what the game pad last said (a later key must not bring back a stale push). */
    private fun letGo() {
        MoveInput.releaseAll(); keys.clear()
        padStick[0] = 0f; padStick[1] = 0f; hatX = 0f; hatY = 0f
    }

    /** Arrow, WASD and D-pad keys held now (a keyboard with DeX, a game pad's D-pad), and a game pad's left stick and hat. */
    private val keys = HashSet<Int>()
    private val padStick = FloatArray(2)
    private var hatX = 0f
    private var hatY = 0f

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val k = event.keyCode
        if (!com.timerpg.street.shot.ShotSettings.typing && (k in LEFT_KEYS || k in RIGHT_KEYS || k in UP_KEYS || k in DOWN_KEYS || k in RUN_KEYS)) {
            when (event.action) {
                android.view.KeyEvent.ACTION_DOWN -> keys += k
                android.view.KeyEvent.ACTION_UP -> keys -= k
            }
            padToInput()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        // touch-down to the next frame (the playtest recorder samples one every 2 s)
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN || ev.actionMasked == android.view.MotionEvent.ACTION_POINTER_DOWN)
            com.timerpg.street.play.PlayLog.touch()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK) && ev.action == android.view.MotionEvent.ACTION_MOVE) {
            // the pad's stick shaped as the screen's is (its dead zone, the pull to the axes); its hat as arrow keys
            com.lifemax.rpg.feature.habits.presentation.scene.Stick.shape(
                ev.getAxisValue(android.view.MotionEvent.AXIS_X), ev.getAxisValue(android.view.MotionEvent.AXIS_Y), padStick)
            hatX = ev.getAxisValue(android.view.MotionEvent.AXIS_HAT_X); hatY = ev.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y)
            padToInput()
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    /** Keys walk (a push in the walk band), with Shift or a shoulder button they run; a pad's stick pushes as far as it is pushed. */
    private fun padToInput() {
        var kx = hatX; var ky = hatY
        if (keys.any { it in LEFT_KEYS }) kx -= 1f
        if (keys.any { it in RIGHT_KEYS }) kx += 1f
        if (keys.any { it in UP_KEYS }) ky -= 1f
        if (keys.any { it in DOWN_KEYS }) ky += 1f
        val h = kotlin.math.hypot(kx, ky)
        if (h > .5f) {
            val push = if (keys.any { it in RUN_KEYS }) 1f else .7f
            MoveInput.pad(kx / h * push, ky / h * push)
        } else MoveInput.pad(padStick[0], padStick[1])
    }

    /**
     * A game pad or keyboard that goes away mid-push sends no last centred report: whatever it last said is forgotten, so
     * the player does not walk on by themselves (and the screen's stick is not outvoted by a pad that is gone).
     */
    private val devices = object : android.hardware.input.InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {}
        override fun onInputDeviceChanged(deviceId: Int) {}
        override fun onInputDeviceRemoved(deviceId: Int) {
            keys.clear(); padStick[0] = 0f; padStick[1] = 0f; hatX = 0f; hatY = 0f
            padToInput()
        }
    }

    override fun onResume() {
        super.onResume()
        Foreground.resumed = true
        com.timerpg.street.shot.Crumbs.add("resume")
        StreetRenderer.paused = false
        getSystemService(android.hardware.input.InputManager::class.java)?.registerInputDeviceListener(devices, null)
    }

    /** Asks for the display's fastest refresh at its current resolution (120 Hz on the S25); the system may still choose less. */
    private fun fastestRefresh() {
        @Suppress("DEPRECATION") val d = windowManager.defaultDisplay ?: return
        val now = d.mode
        val best = d.supportedModes.filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
        FrameStats.display = best.refreshRate
    }

    override fun onStart() {
        super.onStart()
        com.timerpg.street.night.NightWriter.gameOpened()
        dialogue?.onForeground(true)
        com.timerpg.street.play.PlayLog.foreground(true)
        sound?.start()
    }

    override fun onStop() {
        dialogue?.onForeground(false)
        com.timerpg.street.play.PlayLog.foreground(false)
        // out of sight: on the charger the overnight writer starts now (allowed while the game was just in front),
        // otherwise at the next charge
        com.timerpg.street.night.NightWriter.gameClosed(applicationContext)
        sound?.stop()
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.timerpg.street.shot.Crumbs.add("trim memory $level")
        dialogue?.onTrimMemory(level)
        com.timerpg.street.play.PlayLog.trim(level)
    }

    override fun onDestroy() {
        // the engine outlives a recreated activity, not a finished game
        voices?.close()
        memoryListener?.let { com.timerpg.street.llm.MemoryWatch.listeners -= it }
        // (unless the overnight writer has taken it over: it lets the model go itself)
        if (isFinishing && com.timerpg.street.night.NightWriter.session == null) DialogueEngine.release()
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
private class StreetData(val town: String, val streets: List<StreetInfo>, val crossroads: com.lifemax.rpg.feature.habits.presentation.scene.CrossroadsStacks? = null)

private fun loadStreet(ctx: android.content.Context): StreetData = try {
    val j = org.json.JSONObject(ctx.assets.open("street.json").bufferedReader().use { it.readText() })
    val arr = j.getJSONArray("shops")
    // (mipmapped: the side street and the walk round the corner see the pictures small and edge on)
    fun img(n: String): androidx.compose.ui.graphics.ImageBitmap? = try {
        ctx.assets.open("buildings/$n.png").use { BitmapFactory.decodeStream(it) }?.apply { setHasMipMap(true) }?.asImageBitmap()
    } catch (e: Exception) { null }
    // the real windows of each building (tools/make_buildings.py)
    val windows = SpriteBuildingLoad.windows(try { ctx.assets.open("buildings/windows.json").bufferedReader().use { it.readText() } } catch (e: Exception) { null })
    // the pitched-roof houses' shapes (our mobile homes)
    val houses = SpriteBuildingLoad.houses(try { ctx.assets.open("buildings/houses.json").bufferedReader().use { it.readText() } } catch (e: Exception) { null })
    val models = SpriteBuildingLoad.models(try { ctx.assets.open("buildings/models.json").bufferedReader().use { it.readText() } } catch (e: Exception) { null })
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
        val art = SpriteBuildingLoad.load(n, mids, windows, houses, models) { img(it) }
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
    val jn = j.optJSONObject("junction")
    // the main street does not end at the junction: the side street opens off it (a gap where it is seen, walked past or
    // turned into), and the main street goes on beyond with the buildings east of it. An older street.json has no
    // junction: the side street then opens at the main street's east end, with nothing past it and no far row
    val walked = if (streets.size >= 2) streets.mapIndexed { k, st ->
        if (k != 0) st else {
            val east = names(jn?.optJSONArray("mainEast")).filter { bySprite[it] != null }
            val mouth = shop(com.lifemax.rpg.feature.habits.domain.JUNCTION_ID, streets[1].name, "", HabitMaterial.CLAY, 1)
            StreetInfo(st.name, st.shops + mouth + east.map { bySprite[it]!!.stack }, st.owners + "" + east.map { bySprite[it]!!.owner },
                st.sprites + com.lifemax.rpg.feature.habits.domain.JUNCTION_ID + east)
        }
    } else streets
    if (walked.isEmpty()) StreetData("built-in street", listOf(StreetInfo("Main Street", SHOPS, emptyList())))
    else {
        // the crossroads as one place: the main street with the mouth, the cross street, and the cross street's other side
        val crossEast = names(jn?.optJSONArray("crossEast")).mapNotNull { bySprite[it]?.stack }
        val cr = if (walked.size >= 2) com.lifemax.rpg.feature.habits.presentation.scene.CrossroadsStacks(walked[0].shops, walked[1].shops, crossEast) else null
        StreetData("${j.getString("town")} · ${j.getString("type")} · ${j.getInt("year")}", walked, cr)
    }
} catch (e: Exception) {
    StreetData("built-in street", listOf(StreetInfo("Main Street", SHOPS, emptyList())))
}

@Composable
private fun GameScreen(engine: DialogueEngine, voices: com.timerpg.street.voice.NpcVoiceService, sound: com.timerpg.street.audio.SoundScape? = null) {
    // (the frame-spike profiler counts the HUD's recompositions: this screen's own and its texts')
    androidx.compose.runtime.SideEffect { FrameSpikes.recomposed++ }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val data = remember { loadStreet(ctx) }
    // the main street and the cross street: one place, the stage draws whichever the player is on and walks them round
    // the corner (the up key in the main street's mouth, or at the cross street's start); the HUD follows it
    var streetNo by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(data) {
        StageTuning.crossroads = data.crossroads
        StageTuning.cornerIds = emptySet()
        GameState.street = 0
        CornerWalk.reset()
        onDispose { StageTuning.crossroads = null }
    }
    val street = data.streets[streetNo.coerceIn(0, data.streets.size - 1)]
    // the walkable town (Filament on, the town chosen in the AI panel) or the old crossroads
    val townMode = StreetRenderer.townMode
    val townPlay = com.lifemax.rpg.feature.habits.presentation.scene.TownPlay
    // the town's state the HUD follows (copied each frame from the walk, as GameState is below)
    var townFront by remember { mutableStateOf<String?>(null) }
    // (whose people "Talk to" offers: the building in front in the street view; in the open world the nearest person to talk to)
    var townHere by remember { mutableStateOf<String?>(null) }
    var townStreet by remember { mutableStateOf<String?>(null) }
    var townHood by remember { mutableStateOf<String?>(null) }
    var townTransit by remember { mutableStateOf(false) }
    var townRows by remember { mutableIntStateOf(0) }
    var townCorner by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(townMode) {
        if (townMode) {
            TownSession.load(ctx, StreetRenderer.town)
            engine.useTown("town/${StreetRenderer.town}/")
        } else {
            TownSession.stop(ctx)
            engine.useTown("")
        }
    }
    val townData = TownSession.loaded.takeIf { townMode && !TownSession.loading }
    val townLoading = townMode && !TownSession.ready
    // while the town's first view is built: the player stays put (keys, pad and stick), and no lines are written ahead
    // (the dialogue model's load and its writing would compete with the build's workers and the engine's thread)
    LaunchedEffect(townLoading) {
        townPlay.frozen = townLoading
        if (townLoading) engine.pause("town-load") else engine.resume("town-load")
    }
    // the dialogue model's load waits for the whole town to be built (or 20 s past the first view, if the rest takes
    // longer), then a moment more for the last uploads to settle; on the crossroads it does not wait
    LaunchedEffect(townMode) {
        if (townMode) {
            var readyAt = 0L
            while (!TownSession.built) {
                if (TownSession.ready) { if (readyAt == 0L) readyAt = System.nanoTime() } else readyAt = 0L
                if (readyAt != 0L && System.nanoTime() - readyAt > 20_000_000_000L) break
                kotlinx.coroutines.delay(500)
            }
            kotlinx.coroutines.delay(2000)
        }
        engine.releaseLoad(TOWN_BUILD_HOLD)
    }
    val shops = street.shops
    val owners = street.owners
    // the crossroads is at the far end of the main street, and at the start of the cross street
    GameState.cornerAtEnd = streetNo == 0
    var hour by remember { mutableFloatStateOf(17.6f) }
    // the first playable loop (docs/LOOP.md, LoopSession): the sim starts with the town; it follows the clock on each
    // hour crossed, and moves it (a shift, a wait, a night in the cell)
    LaunchedEffect(townData) { if (townData != null) LoopSession.start(ctx, townData, hour) }
    LaunchedEffect(engine) { engine.loopFacts = { id -> LoopSession.factsFor(id) } }
    LaunchedEffect(hour) { if (townMode) LoopSession.clock(hour) }
    LoopSession.hourSet?.let { h -> LaunchedEffect(h) { hour = h.coerceIn(0f, LAST_MINUTE); LoopSession.hourSet = null } }
    var loopShop by remember { mutableStateOf(false) }
    var loopDebug by remember { mutableStateOf(false) }
    var ticketOpen by remember { mutableStateOf(false) }
    var season by remember { mutableIntStateOf(1) }
    var weather by remember { mutableIntStateOf(0) }
    // (the stage takes both streets from the crossroads; the projection is the main street's, so turning the corner
    // rebuilds nothing)
    val p = remember(hour, data, season, weather) {
        // the date sets the season (leaf, blossom, snow); the weather is forced
        WeatherOverride.forced = WEATHERS[weather].second
        HabitsProjection(
            day = SEASONS[season].second, hour = hour, who = "", whoHint = "", rough = false, built = 0, due = 0,
            reducedMotion = false, haptics = null, problem = "", limits = HabitLimits(40, 40, 40, 40, 40, 1, 12, 12),
            rules = HabitRules(10, 40, 100, 12), stacks = data.streets[0].shops, lapse = emptyList(), tokens = emptyMap(), light = false,
        )
    }
    // GameState is plain vars the stage updates every frame: copy them into Compose state so the HUD follows them
    var near by remember { mutableIntStateOf(GameState.nearest) }
    var atCorner by remember { mutableStateOf(GameState.atCorner) }
    // the frame drawn last: the speech bubbles follow people with it (read only where they are laid out, so the screen
    // is not recomposed every frame)
    val frameTick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var last = 0L
        var movedAt = 0L
        var movingNow = false
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            frameTick.longValue = now
            // how long each frame took, for the AI panel's frame line (is it really 120 Hz on the phone?)
            if (last != 0L) FrameStats.add((now - last) / 1e6f)
            last = now
            // moving (walking, a turn or a crossing): nothing new is written ahead until the player has stood a moment
            if (GameState.walking || (GameState.riding && GameState.motion.speed > 1f) || townPlay.walk?.transit != null ||
                (townPlay.active && com.lifemax.rpg.feature.habits.presentation.scene.OrbitCamera.open && com.lifemax.rpg.feature.habits.presentation.scene.OrbitCamera.moving)) { movedAt = now; if (!movingNow) { movingNow = true; engine.setMoving(true) } }
            else if (movingNow && now - movedAt > STILL_NS) { movingNow = false; engine.setMoving(false) }
            near = GameState.nearest
            atCorner = GameState.atCorner
            if (townMode) townPlay.extras = LoopSession.officerFigures(now)
            // the walk round the corner swaps the street in the frame it ends: the HUD, the people, the lines follow
            if (GameState.street != streetNo && !townPlay.active) streetNo = GameState.street
            val w = townPlay.walk
            if (w != null) {
                townFront = townPlay.frontSprite()
                townHere = townPlay.talkSprite()
                townTransit = w.transit != null
                if (townRows != townPlay.rowChanges || townStreet == null) {
                    townRows = townPlay.rowChanges
                    townStreet = townPlay.streetName()
                }
                townHood = townPlay.hoodName()
                // at a corner: the street a turn leads into from here
                townCorner = if (!GameState.atCorner) null else {
                    val r = w.row
                    val to = if (w.x > r.length / 2f) r.end.turnIn else r.start.turnIn
                    w.town.rowOrNull(to)?.let { w.town.road(it.road).name }
                }
            }
        }
    }
    // dialogue: who is at the building the player stands at, the open conversation, the AI panel
    val people by engine.data.collectAsState()
    val ai by engine.status.collectAsState()
    var talk by remember { mutableStateOf<Talk?>(null) }
    var aiPanel by remember { mutableStateOf(false) }
    val here = if (townMode) townHere else street.sprites.getOrNull(near)
    val loopTown = LoopSession.town
    val atHere = remember(people, here, loopTown) {
        val at = people?.at(here).orEmpty()
        // (a landmark store's clerk, made one by the loop: met there, as the shop's own person)
        val clerk = here?.let { loopTown?.clerks?.get(it) }?.takeIf { id -> at.none { it.id == id } }
            ?.let { id -> people?.npcs?.firstOrNull { it.id == id } }?.let { n -> loopTown?.placeBySprite?.get(here)?.let { p -> n.copy(building = here!!, buildingName = p.name) } }
        if (clerk != null) listOf(clerk) + at else at
    }
    LaunchedEffect(atHere) { LoopSession.prefetch(atHere.map { it.id }) }
    // everyone the player can talk to on this street stands at their building (the first of each building's people)
    LaunchedEffect(people, streetNo, townMode, loopTown) {
        // the town: everyone to talk to stands at their building's door, on the rows round the player
        townPlay.keepers = if (!townMode) emptyMap() else people?.buildings?.associateWith { sp -> people!!.at(sp).first().id.hashCode() and 0x7fffffff }.orEmpty() +
            LoopSession.town?.clerks.orEmpty().mapValues { (_, id) -> id.hashCode() and 0x7fffffff }
        // (their sex and age pick their look from the townspeople's sheets)
        townPlay.keeperTraits = if (!townMode) emptyMap() else people?.buildings?.associateWith { sp -> people!!.at(sp).first().let { it.age.coerceAtLeast(0) * 2 + (if (it.female) 1 else 0) } }.orEmpty()
        GameState.keepers = street.sprites.mapIndexedNotNull { i, sp ->
            people?.at(sp)?.firstOrNull()?.let { npc -> i to (npc.id.hashCode() and 0x7fffffff) }
        }
        // both streets' (the other is seen round the corner, and is there the moment the walk ends)
        GameState.keepersBy = data.streets.take(2).mapIndexed { k, st ->
            k to st.sprites.mapIndexedNotNull { i, sp -> people?.at(sp)?.firstOrNull()?.let { npc -> i to (npc.id.hashCode() and 0x7fffffff) } }
        }.toMap()
    }
    // lines are written ahead of time nearest the player first; re-sorted as they walk or turn the corner
    LaunchedEffect(streetNo, near, townMode, townRows, townData) {
        val w = townPlay.walk
        if (townMode && w != null) engine.setPlayerRow(townPlay.rowIndex(w.row), near, w.liveRows().map { townPlay.rowIndex(it) })
        else if (!townMode) engine.setPlayer(streetNo, near)
    }
    // the walk round the corner: no writing ahead while the camera turns (it needs the CPU), no conversation across it
    val turning = CornerWalk.active || (townMode && townTransit)
    LaunchedEffect(turning) {
        // (the town's walk tells its own turns and crossings: TownPlay's events)
        if (!townMode) com.timerpg.street.shot.Crumbs.add(if (turning) "corner turn start" else "corner turn end")
        if (turning) { talk?.close(voices); talk = null; engine.pause("turn") } else engine.resume("turn")
    }
    // a conversation open: the speech model gets ready (it loads only now, not when walking past people), and the lines
    // written ahead for others wait, so the phone's memory and the AI lane are the conversation's
    LaunchedEffect(talk != null) {
        if (talk != null) townPlay.dismount("talking")
        if (talk != null) engine.pause("talking") else engine.resume("talking")
    }
    // walking away ends the conversation (one started by voice: walking out of earshot, see below)
    LaunchedEffect(here) {
        val t = talk
        if (t != null && !t.byVoice && t.npc.building != here) { t.close(voices); talk = null }
    }
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) engine.importModel(uri) }
    // the sound follows the hour, the weather and the season; a footstep each time a foot is put down (the drawn legs'
    // own footfalls, on the street and in the rooms), a little louder running
    // the town's kind (harbor, farm, mill ...) picks its music
    val townKind = remember(data) { com.timerpg.street.audio.SoundRules.townOf(data.town) }
    LaunchedEffect(sound) {
        if (sound == null) return@LaunchedEffect
        var feet = GameState.footfalls; var foot = 0
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            val w = WEATHERS[weather].second
            sound.set(com.timerpg.street.audio.SoundRules.at(hour, w?.rain ?: 0f, w?.cloudiness ?: 0f, w?.mist ?: 0f, season, townKind))
            // the bicycle's tyres and freewheel follow the ride; no footsteps while riding (the ride adds none)
            townPlay.open?.let { o ->
                sound.bike.on = o.riding && townPlay.walk === o
                sound.bike.speed = o.bike.speed; sound.bike.pedalling = o.bike.pedalling; sound.bike.wheelRadius = o.bike.wheelRadius
                sound.bike.surface = if (season == 3 && GameState.surface != com.lifemax.rpg.feature.habits.presentation.scene.Surface.WOOD) com.lifemax.rpg.feature.habits.presentation.scene.Surface.GRASS else GameState.surface
            } ?: run { sound.bike.on = false }
            if (GameState.footfalls != feet) {
                val fresh = GameState.footfalls - feet
                feet = GameState.footfalls
                if (fresh > 0) {
                    foot = (foot + 1) % 5
                    // recorded steps by what is underfoot (the street, a lawn, a deck, a room's floor, snow in winter)
                    val set = com.lifemax.rpg.feature.habits.presentation.scene.Surface.sound(GameState.surface, GameState.indoors, GameState.carpet, season == 3)
                    // one foot a little softer than the other, as real steps are: the ear hears a stride (two steps) as one
                    // beat, not a patter of equal ones
                    val accent = if (foot % 2 == 0) 1f else .7f
                    sound.play("step_${set}_${foot + 1}", (if (GameState.running) .75f else .6f) * accent, if (foot % 2 == 0) -.12f else .12f)
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
            val what = "build ${BuildConfig.VERSION_NAME} · AI ${a.state} (${a.role?.name?.lowercase() ?: "none"}) on ${listOf("CPU", "GPU", "NPU").getOrElse(a.runsOn) { "?" }} · lane ${com.timerpg.street.llm.AiLane.current ?: "idle"} · " +
                "mic ${if (ears.always) "always" else ears.state} (${ears.hearer.name.lowercase()}) · ${if (talk != null) "talking" else "walking"} · street $streetNo building #$near"
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CrashReport.breadcrumb(ctx.applicationContext, what) }
            // and the whole game state, for a native crash's report on the next start
            com.timerpg.street.shot.GameSnapshot.now()?.let { com.timerpg.street.shot.Crumbs.saveState(it) }
            kotlinx.coroutines.delay(3000)
        }
    }
    // in the town, a frame line in the breadcrumbs every 10 s: the frame times, the dropped frames, Filament's CPU and GPU
    // times, the heat and the free memory (so the next report carries numbers, not only "it stuttered")
    LaunchedEffect(townMode) {
        if (!townMode) return@LaunchedEffect
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        FrameStats.window(); FilamentStats.window()
        while (true) {
            kotlinx.coroutines.delay(10_000)
            if (!TownSession.ready || StreetRenderer.paused) { FrameStats.window(); FilamentStats.window(); FrameSpikes.window(); continue }
            val heat = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 30) "thermal ${pm.currentThermalStatus}, headroom %.2f".format(java.util.Locale.US, pm.getThermalHeadroom(10))
                else "thermal ${pm.currentThermalStatus}"
            }.getOrDefault("thermal ?")
            val w = townPlay.walk
            com.timerpg.street.shot.Crumbs.add("frames 10 s: ${FrameStats.window()}; ${FilamentStats.window()}; $heat; " +
                "${com.timerpg.street.llm.MemoryWatch.freeMb} MB free; AI ${engine.status.value.state}${engine.status.value.working?.let { " writing" } ?: ""}" +
                " (held while moving: ${engine.held} lines, ${engine.heldMs / 1000} s in all)" +
                (if (w != null) "; ${if (GameState.walking) "walking" else "standing"} on ${w.row.id}" else ""))
            // what ran in the frames that came late (the frame-spike profiler)
            com.timerpg.street.shot.Crumbs.add("frame spikes 10 s: " + FrameSpikes.window())
        }
    }
    // where the memory goes, every 30 s in the breadcrumbs: each model resident and the game's own parts (the phone's
    // report of build 321 had 4.3 GB of the game's own with the town at 1.2 GB, and nothing to say what the rest was)
    LaunchedEffect(Unit) {
        var last = ""
        var n = 0
        while (true) {
            kotlinx.coroutines.delay(30_000)
            n++
            val m = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.timerpg.street.llm.ModelRoom.summary(ctx.applicationContext) }.getOrDefault("") }
            // (only when what is resident changed, or every 5 minutes)
            val key = m.substringBefore(" · game PSS")
            if (m.isNotEmpty() && (key != last || n % 10 == 0)) { last = key; com.timerpg.street.shot.Crumbs.add("memory: $m") }
        }
    }
    // updates from inside the game: a quiet look at start
    val updater = remember { AppUpdater(ctx.applicationContext) }
    LaunchedEffect(Unit) { updater.check(quiet = true) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        // memory short: the speech and memory models go unless at work (they load again when next needed)
        val trim: (Int) -> Unit = { level -> if (level >= 1) { ears.trimForMemory(); memory.trimForMemory() } }
        com.timerpg.street.llm.MemoryWatch.listeners += trim
        onDispose { com.timerpg.street.llm.MemoryWatch.listeners -= trim; ears.release(); memory.release() }
    }
    // what the people here say while they think, voiced ahead (so it plays at once)
    LaunchedEffect(atHere.firstOrNull()?.id, voices.enabled) { if (voices.enabled) atHere.firstOrNull()?.let { voices.prepareFillers(it, Fillers.all(it.warmth)) } }
    // the speech model loads when a conversation opens (not when walking past people)
    LaunchedEffect(talk != null) { if (talk != null) ears.prewarm() }
    // the settings' mic: always listening (kept), instead of holding the mic button in a conversation
    val micPrefs = remember { ctx.getSharedPreferences("mic", android.content.Context.MODE_PRIVATE) }
    var micAlways by remember { mutableStateOf(micPrefs.getBoolean("always", false)) }
    var micGranted by remember { mutableStateOf(ears.hasPermission()) }
    var wantAlways by remember { mutableStateOf(false) }
    fun setMicAlways(on: Boolean) { micAlways = on; micPrefs.edit().putBoolean("always", on).apply() }
    // Android will not ask again (two "no"s on Android 11+): the request returns "denied" at once and shows nothing, so
    // a tap opens the app's settings page instead, with a word on what to change there (voice/MicAsk.kt)
    var micBlocked by remember { mutableStateOf(micPrefs.getBoolean("blocked", false) && !ears.hasPermission()) }
    val micAsk = remember { longArrayOf(0L, 0L) }   // when the request went, and the rationale before it (1/0)
    fun micRationale() = (ctx as? android.app.Activity)?.shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO) == true
    fun openMicSettings() {
        com.timerpg.street.shot.Crumbs.add("mic: opening the app's settings (Android will not ask again)")
        android.widget.Toast.makeText(ctx, com.timerpg.street.voice.MicAsk.SETTINGS_HELP, android.widget.Toast.LENGTH_LONG).show()
        runCatching {
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", ctx.packageName, null)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { com.timerpg.street.shot.Crumbs.add("mic: settings did not open: ${it.javaClass.simpleName}") }
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val ms = android.os.SystemClock.elapsedRealtime() - micAsk[0]
        val before = micAsk[1] == 1L; val after = micRationale()
        val out = com.timerpg.street.voice.MicAsk.outcome(granted, before, after, ms)
        com.timerpg.street.shot.Crumbs.add("mic permission: $out (rationale $before to $after, answered in $ms ms)")
        micGranted = granted
        micBlocked = out == com.timerpg.street.voice.MicAsk.Outcome.BLOCKED
        micPrefs.edit().putBoolean("blocked", micBlocked).apply()
        if (wantAlways && granted) setMicAlways(true)
        wantAlways = false
        if (micBlocked) openMicSettings()
    }
    /** Asks for the microphone (or, when Android will not ask any more, opens the app's settings page). */
    fun requestMic() {
        if (ears.hasPermission()) { micGranted = true; micBlocked = false; return }
        if (micBlocked) { openMicSettings(); return }
        micAsk[0] = android.os.SystemClock.elapsedRealtime(); micAsk[1] = if (micRationale()) 1L else 0L
        com.timerpg.street.shot.Crumbs.add("mic: asking for the permission")
        runCatching { askMic.launch(android.Manifest.permission.RECORD_AUDIO) }
            .onFailure { com.timerpg.street.shot.Crumbs.add("mic: the request failed: ${it.javaClass.simpleName}"); openMicSettings() }
    }
    // (granted in the system's settings meanwhile: seen when the game comes back to the front)
    LaunchedEffect(Foreground.resumed) {
        if (Foreground.resumed) {
            micGranted = ears.hasPermission()
            if (micGranted && micBlocked) { micBlocked = false; micPrefs.edit().putBoolean("blocked", false).apply() }
            // (always listening was being switched on when the settings page opened instead of the dialog)
            if (micGranted && wantAlways) { setMicAlways(true); wantAlways = false }
        }
    }
    val onMicAlways: (Boolean) -> Unit = { on ->
        if (!on) setMicAlways(false)
        else if (ears.hasPermission()) { micGranted = true; setMicAlways(true) }
        else { wantAlways = true; requestMic() }
    }
    // the words to expect: the town's name and everyone's names, so the recognizer spells them as the game does
    val names = remember(engine.data.value) {
        engine.data.value?.let { d -> (listOf(d.townName) + d.npcs.map { it.name }).distinct().joinToString(", ") } ?: ""
    }
    // inside a building: the rooms our game logic planned and furnished (assets/interiors.json), by sprite name
    // (the walkable town's rooms are its own: assets/town/<seed>/interiors.json, the same furniture pictures)
    val roomsDir = if (townMode) "town/${StreetRenderer.town}/" else ""
    val rooms = remember(roomsDir) {
        try {
            val all = com.lifemax.rpg.feature.habits.presentation.scene.InteriorRoom.parseAll(ctx.assets.open("${roomsDir}interiors.json").bufferedReader().use { it.readText() })
            if (com.lifemax.rpg.feature.habits.presentation.scene.InteriorArt.images.isEmpty())
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
    // the bicycle: on it (kept in step with the ride, which a door, a conversation or the view switch also ends), and the
    // moment a tap found no room for it
    var bikeOn by remember { mutableStateOf(false) }
    var noRoom by remember { mutableStateOf(0L) }
    // the test button's car (with the 📷/🔖 test buttons): when it was tapped, and the car's name shown for a moment
    var carAskAt by remember { mutableStateOf(0L) }
    var carNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { if (bikeOn != GameState.riding) bikeOn = GameState.riding; kotlinx.coroutines.delay(150) } }
    // off the bike to go in at a door or to talk to someone
    LaunchedEffect(doorTo) { if (doorTo != null) townPlay.dismount("door") }
    LaunchedEffect(doorTo) {
        val go = doorTo ?: return@LaunchedEffect
        val start = androidx.compose.runtime.withFrameNanos { it }
        var swapped = false
        while (true) {
            val t = (androidx.compose.runtime.withFrameNanos { it } - start) / 1e9f
            door = if (t < .25f) t / .25f else (1f - (t - .25f) / .3f).coerceAtLeast(0f)
            if (!swapped && t >= .25f) {
                swapped = true
                // a thumb still on the stick does not walk the player straight back out: it counts again once it is back
                // in the middle (or lifted)
                MoveInput.holdUntilNeutral()
                // and the view is centred on them again (coming back out, the street is not still looking where they went in)
                GameState.camera.reset()
                val room = go.second
                if (go.first && room != null) { walker = com.lifemax.rpg.feature.habits.presentation.scene.RoomWalker(room.spawnX, room.spawnY); inside = room } else inside = null
                sound?.play("door_bell", .45f, 0f)
            }
            if (t >= .55f) break
        }
        door = 0f; doorTo = null
    }
    // walking out through the door leaves
    // the room's floor for the footsteps (some rooms carpeted, by the room)
    LaunchedEffect(inside) { GameState.indoors = inside != null; GameState.carpet = inside != null && (inside.hashCode() and 1) == 1 }
    LaunchedEffect(inside) {
        val room = inside ?: return@LaunchedEffect
        while (inside === room) {
            androidx.compose.runtime.withFrameNanos { }
            // a deliberate push down through the doorway (not a sideways wobble near it)
            if (doorTo == null && walker.y > room.exitY - 6f && kotlin.math.abs(walker.x - room.exitX) < 26f &&
                GameState.moveLane > .5f && GameState.moveLane > kotlin.math.abs(GameState.moveX)) doorTo = false to null
        }
    }
    // the person who lives or works here, standing inside (the same look as at their door)
    val keeperSheet = remember(inside, near) {
        val sheets = SpriteSheets.sheets
        val seed = if (townMode) here?.let { townPlay.keepers[it] } else GameState.keepers.firstOrNull { it.first == near }?.second
        if (inside == null || seed == null || sheets.isEmpty()) null
        else {
            val mine = GameState.look.mod(sheets.size)
            val others = sheets.indices.filter { it != mine }
            if (others.isEmpty()) null else sheets[others[seed.mod(others.size)]]
        }
    }
    val keeperSpot = remember(inside) { inside?.keeperSpot() }
    // the loop's place the player is at: the shop whose room they are in, or whose door they stand at
    val loopPlace = if (!townMode) null else LoopSession.placeAt(inside?.id ?: townFront ?: townHere)
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
    LaunchedEffect(townMode) {
        if (!townMode) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(1000)
            if (TownSession.ready) LoopSession.lookForPlayer(ctx, hour, inside != null || townPlay.inCar)
        }
    }
    // the back gesture closes what is open (the crash report, a conversation, the AI panel) or leaves the room; on the street
    // it takes two within two seconds to leave the game. Android holds the edge's back swipe off only over the bottom 200 dp,
    // so a left thumb landing higher on the very edge and pushing right to walk is a back swipe: it must not end the game
    // ---- "send screenshot to Claude": the camera button hides, the window is captured, then the sheet asks for a note
    var shooting by remember { mutableStateOf(false) }
    // "mark this" (the playtest recorder): the moment marked in the timeline with a system trace, then the screenshot's sheet
    var marking by remember { mutableStateOf(false) }
    var shot by remember { mutableStateOf<com.timerpg.street.shot.PendingShot?>(null) }
    LaunchedEffect(shooting) {
        if (!shooting) return@LaunchedEffect
        // a few frames, so the button is gone from what is on the screen
        repeat(3) { androidx.compose.runtime.withFrameNanos { } }
        val act = activity
        if (act == null) { shooting = false; return@LaunchedEffect }
        val game = com.timerpg.street.shot.GameSnapshot.now()
        val at = System.currentTimeMillis()
        com.timerpg.street.shot.ShotUploader.capture(act) { bmp, how ->
            shooting = false
            if (bmp != null) shot = com.timerpg.street.shot.PendingShot(bmp, at, how, game)
            else android.widget.Toast.makeText(ctx, "Screenshot failed: $how", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    var backAt by remember { mutableLongStateOf(0L) }
    var backHint by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler {
        val t = talk
        when {
            shot != null -> shot = null
            lastCrash != null -> lastCrash = null
            t != null -> { t.close(voices); talk = null }
            aiPanel -> aiPanel = false
            turning || doorTo != null -> {}
            inside != null -> doorTo = false to null
            System.nanoTime() - backAt < 2_000_000_000L -> { backHint = false; activity?.moveTaskToBack(true) }
            else -> { backAt = System.nanoTime(); backHint = true }
        }
    }
    LaunchedEffect(backHint, backAt) { if (backHint) { kotlinx.coroutines.delay(2000); backHint = false } }
    // the up key turns the corner only when nothing else holds the player: inside, a door, a conversation, the NPU tuning
    androidx.compose.runtime.SideEffect { CornerWalk.blocked = inside != null || doorTo != null || talk?.byVoice == false || tuning }
    // ---- always listening: someone near hears what the player says (see TalkRange), answering in a bubble over their head
    // the player's words over their head: (text, which utterance, final); while they talk, the phone's recognizer's words so far
    var playerSaid by remember { mutableStateOf<Triple<String, Long, Boolean>?>(null) }
    var partialKey by remember { mutableLongStateOf(0L) }
    var noOneNear by remember { mutableLongStateOf(0L) }
    val heardNow by rememberUpdatedState<(String) -> Unit> { said ->
        if (!turning && doorTo == null) {
            // (the words already shown while they talked keep their bubble: it only stops changing)
            playerSaid = Triple(said, partialKey.takeIf { it != 0L } ?: System.nanoTime(), true)
            partialKey = 0L
            // who is near enough to hear it: inside, the person here when standing by them; on the street, the nearest of
            // the people standing at their doors within earshot
            val target: Pair<Npc, Int>? = if (inside != null) atHere.firstOrNull()?.takeIf { nearKeeper }?.let { it to near }
            else if (townMode) {
                // the town: the nearest person at their door on this row, within earshot (TownPlay, from the walk's place)
                townPlay.nearestKeeper()?.let { sp -> people?.at(sp)?.firstOrNull()?.let { it to near } }
            } else {
                val doors = street.sprites.indices.mapNotNull { i -> people?.at(street.sprites[i])?.firstOrNull()?.let { i to it } }
                val k = com.lifemax.rpg.feature.habits.presentation.scene.TalkRange.nearest(GameState.x, GameState.lane, doors.map { it.first }, GameState.centers)
                doors.getOrNull(k)?.let { (i, npc) -> npc to i }
            }
            // (heard, but nobody to answer: a breadcrumb, so "it heard nothing" can be told from "nobody was near")
            if (target == null) { noOneNear = System.nanoTime(); com.timerpg.street.shot.Crumbs.add("mic: heard ${said.split(' ').size} words, no one near to hear them") }
            else {
                val (npc, door) = target
                val t = talk?.takeIf { it.npc.id == npc.id } ?: Talk(npc, byVoice = true, door = door, doorSprite = if (townMode) npc.building else null).also { talk?.close(voices); talk = it }
                t.heardNote = ears.lastHeard
                t.heardInfo = ears.lastHeardInfo
                t.askSaid(engine, said, voices, memory)
            }
        }
    }
    // the words so far, in the player's bubble while they talk ("": dropped, the bubble goes)
    val partialNow by rememberUpdatedState<(String) -> Unit> { words ->
        if (words.isEmpty()) { if (partialKey != 0L && playerSaid?.second == partialKey) playerSaid = null; partialKey = 0L }
        else if (!turning && doorTo == null) {
            if (partialKey == 0L) partialKey = System.nanoTime()
            playerSaid = Triple(words, partialKey, false)
        }
    }
    // listening while the game is in front, the mic is on in the settings and conversation is possible (not turning the
    // corner, not going through a door, not in a room with nobody in it)
    // (and not while the screenshot sheet is open: its mic button dictates a note there)
    val canListen = micAlways && micGranted && shot == null && Foreground.resumed && !turning && doorTo == null && lastCrash == null && !tuning &&
        (inside == null || atHere.isNotEmpty())
    LaunchedEffect(canListen) {
        com.timerpg.street.shot.Crumbs.add(if (canListen) "mic listening" else "mic off")
        if (canListen) ears.listen({ heardNow(it) }) { partialNow(it) }
        // switched off: the speech model goes now; only paused (the game in the background, a door): after a minute
        else ears.stopListening(release = !micAlways)
    }
    androidx.compose.runtime.SideEffect {
        ears.expect = names
        // an NPC's voice playing is not the player speaking
        ears.muted = voices.speaking
    }
    // a conversation started by voice ends when the player walks out of earshot (inside: away from the person)
    LaunchedEffect(talk) {
        val t = talk ?: return@LaunchedEffect
        if (!t.byVoice) return@LaunchedEffect
        while (talk === t) {
            androidx.compose.runtime.withFrameNanos { }
            val gone = if (inside != null) !nearKeeper
                else if (t.doorSprite != null) townPlay.fromPlayer(t.doorSprite) > com.lifemax.rpg.feature.habits.presentation.scene.TalkRange.leaveDistance
                else t.npc.street != streetNo || com.lifemax.rpg.feature.habits.presentation.scene.TalkRange.fromPlayer(t.door) > com.lifemax.rpg.feature.habits.presentation.scene.TalkRange.leaveDistance
            if (gone) { t.close(voices); talk = null }
        }
    }
    // what the game is showing, for the screenshot's JSON and crash reports (the AI panel's numbers where it has them)
    val snapshot by rememberUpdatedState<() -> Map<String, Any?>> {
        val h = hour.toInt(); val m = ((hour - h) * 60).toInt()
        val st = ai.stats
        linkedMapOf(
            "renderer" to if (StreetRenderer.filament) "Filament" else "classic",
            "camera" to StreetRenderer.camera,
            "view" to StreetRenderer.view,
            "orbit" to if (townMode && StreetRenderer.view == StreetRenderer.OPEN) com.lifemax.rpg.feature.habits.presentation.scene.OrbitCamera.let { c ->
                linkedMapOf("yaw" to c.yaw, "pitch" to c.pitch, "dist" to c.dist, "pulled" to c.pulled, "goalYaw" to c.goalYaw,
                    "goalPitch" to c.goalPitch, "goalDist" to c.goalDist, "tx" to c.tx, "tz" to c.tz, "line" to c.describe()) } else null,
            "rendererError" to StreetRenderer.error,
            "town" to data.town,
            "walkableTown" to if (!townMode) null else {
                // (also while it loads, before there is a walk: a crash or a hang in the load reports where it was)
                val w = townPlay.walk
                linkedMapOf<String, Any?>("seed" to StreetRenderer.town, "loading" to TownSession.loading, "ready" to TownSession.ready,
                    "progress" to TownSession.progress, "error" to TownSession.error, "loadMs" to TownSession.loadMs,
                    "firstViewMs" to TownSession.firstViewMs, "built" to FilamentStats.townBuilt, "memory" to FilamentStats.memory,
                    // (the GPU by part as Filament holds it now: textures and buffers by what they are for, MB and counts)
                    "gpuNow" to runCatching { com.lifemax.rpg.feature.habits.presentation.scene3d.GpuAccount.categories() }.getOrNull()).also { m ->
                    if (w != null) m.putAll(linkedMapOf("row" to w.row.id, "street" to townStreet, "neighbourhood" to townHood,
                        "x" to w.x, "lane" to w.lane, "wx" to w.wx, "wz" to w.wz, "heading" to w.heading, "front" to townFront,
                        "transit" to w.transit?.let { t -> "${t.kind} ${t.from.id}->${t.to.id} %.2f".format(java.util.Locale.US, t.progress) },
                        "liveRows" to w.liveRows().map { it.id }, "people" to townPlay.figureCount, "talkTo" to townHere,
                        "walk" to if (w is com.lifemax.rpg.feature.habits.presentation.scene.OpenWalk) "open" else "street"))
                    (w as? com.lifemax.rpg.feature.habits.presentation.scene.OpenWalk)?.let { o ->
                        m["open"] = linkedMapOf("door" to o.door?.let { d -> "${d.sprite} (${d.kind}) at (%.0f, %.0f)".format(java.util.Locale.US, d.x, d.z) },
                            "yard" to o.yard?.sprite, "speed" to o.speed, "blocked" to o.blockedShare, "cameraYaw" to o.cameraYaw,
                            "hx" to o.hx, "hz" to o.hz)
                    }
                }
            },
            "street" to linkedMapOf("index" to streetNo, "name" to street.name, "nearestBuilding" to near,
                "building" to shops.getOrNull(near)?.cue, "owner" to owners.getOrNull(near), "atCorner" to GameState.atCorner, "edge" to GameState.edge),
            "player" to linkedMapOf("x" to GameState.x, "lane" to GameState.lane, "street" to GameState.street, "dir" to GameState.dir,
                "walking" to GameState.walking, "running" to GameState.running, "viewMiddle" to GameState.viewMiddle),
            "turn" to linkedMapOf("active" to CornerWalk.active, "forward" to CornerWalk.forward, "p" to CornerWalk.p,
                "yaw" to CornerWalk.yaw, "ease" to CornerWalk.ease, "blocked" to CornerWalk.blocked),
            "inRoom" to (inside != null),
            "room" to inside?.let { linkedMapOf("id" to it.id, "title" to it.title, "use" to it.use) },
            "doorFade" to (doorTo != null),
            "time" to linkedMapOf("hour" to hour, "clock" to "%d:%02d".format(java.util.Locale.US, h, m),
                "season" to SEASON_NAMES.getOrNull(season), "weather" to WEATHERS.getOrNull(weather)?.first),
            "frames" to linkedMapOf("compose" to FrameStats.summary(), "filament" to if (StreetRenderer.filament) FilamentStats.summary() else null,
                "spikes" to FrameSpikes.last),
            "ai" to linkedMapOf("state" to ai.state.name, "detail" to ai.detail, "runsOn" to listOf("CPU", "GPU", "NPU").getOrNull(ai.runsOn),
                "modelMb" to ai.modelMb, "role" to ai.role?.name?.lowercase(), "forceBank" to ai.forceBank, "gpuNote" to ai.gpuNote, "npuWait" to ai.npuWait, "lane" to com.timerpg.street.llm.AiLane.current,
                "linesWritten" to ai.lines, "builtIn" to ai.builtIn, "queued" to ai.queued, "paused" to ai.paused, "working" to ai.working,
                "pregenOff" to ai.pregenOff, "prefillTokPerSec" to st.prefillPerSec, "genTokPerSec" to st.genPerSec, "firstTokenMs" to st.firstTokenMs,
                "loadInfo" to ai.loadInfo),
            "conversation" to talk?.let { linkedMapOf("npc" to it.npc.name, "byVoice" to it.byVoice, "topic" to it.topic?.label, "said" to it.said) },
            "voices" to linkedMapOf("enabled" to voices.enabled, "speaking" to voices.speaking, "only" to voices.only, "timing" to voices.lastTiming),
            "mic" to linkedMapOf("always" to micAlways, "granted" to micGranted, "listening" to canListen, "state" to ears.state.name,
                "hearer" to ears.hearer.name.lowercase(), "hearing" to ears.hearing, "writing" to ears.writing, "problem" to ears.problem, "loop" to ears.loopStatus()),
            "panels" to linkedMapOf("aiPanel" to aiPanel, "crashShown" to (lastCrash != null), "tuningNpu" to tuning),
        )
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.timerpg.street.shot.GameSnapshot.provider = { snapshot() }
        onDispose { com.timerpg.street.shot.GameSnapshot.provider = null }
    }
    // the playtest recorder's second: where the player is, what they do, the camera (read once a second on its thread)
    val playState by rememberUpdatedState<(com.timerpg.street.play.PlayState) -> Unit> { s ->
        val A = com.timerpg.street.play.Activity
        val w = if (townMode) townPlay.walk else null
        s.town = if (townMode) StreetRenderer.town else null
        s.street = if (townMode) townStreet else street.name
        s.hood = if (townMode) townHood else null
        if (w != null) {
            s.wx = w.wx; s.wz = w.wz
            townData?.world?.let { site -> val p = site.world(w.wx, w.wz); s.worldX = p.x; s.worldZ = p.z }
        }
        s.year = townData?.town?.year ?: 0
        s.hour = hour
        if (townMode && StreetRenderer.view == StreetRenderer.OPEN) com.lifemax.rpg.feature.habits.presentation.scene.OrbitCamera.let { c ->
            s.camDist = c.dist; s.camPitch = c.pitch; s.camYaw = c.yaw
            s.camHeight = (c.dist * kotlin.math.sin(Math.toRadians(c.pitch.toDouble()))).toFloat()
        }
        s.activity = when {
            StreetRenderer.paused -> A.PAUSED
            townMode && !TownSession.ready -> A.LOADING
            talk != null -> A.TALK
            inside != null -> A.INSIDE
            aiPanel || shot != null || ticketOpen -> A.MENU
            w?.transit != null || RailTrip.ride != null -> A.TRAIN
            GameState.riding -> A.RIDE
            GameState.walking -> A.WALK
            else -> A.STAND
        }
        s.people = if (townMode) townPlay.figureCount else -1
        s.talkingTo = talk?.npc?.id
        s.room = inside?.id
        s.ai = com.timerpg.street.llm.AiLane.current
        s.aiQueue = ai.queued
    }
    val playSettings by rememberUpdatedState<() -> Map<String, Any?>> {
        val q = com.lifemax.rpg.feature.habits.presentation.scene3d.TownQuality
        linkedMapOf("app" to com.timerpg.street.shot.DeviceFacts.app(),
            "renderer" to if (StreetRenderer.filament) "Filament" else "classic", "view" to StreetRenderer.view, "town" to StreetRenderer.town,
            "resolution" to if (q.resolution == q.RES_AUTO) "auto" else q.resolution, "shadowMap" to q.shadowMap, "cascades" to q.cascades,
            "astc" to StreetRenderer.astc, "grabWorld" to StreetRenderer.grabWorld, "bikeFollow" to StreetRenderer.bikeFollow,
            "displayHz" to FrameStats.display, "ai" to linkedMapOf("role" to ai.role?.name?.lowercase(), "runsOn" to listOf("CPU", "GPU", "NPU").getOrNull(ai.runsOn),
                "modelMb" to ai.modelMb, "npuTuned" to NpuTune.chosen(ctx), "pregenOff" to ai.pregenOff),
            "voices" to linkedMapOf("enabled" to voices.enabled, "only" to voices.only, "xnnpack" to voices.xnnpack),
            "mic" to linkedMapOf("always" to micAlways), "recordTalk" to com.timerpg.street.play.PlayLog.recordTalk(ctx))
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.timerpg.street.play.PlayLog.state = { s -> playState(s) }
        com.timerpg.street.play.PlayLog.settings = { playSettings() }
        onDispose { com.timerpg.street.play.PlayLog.state = null; com.timerpg.street.play.PlayLog.settings = null }
    }
    // the events crash reports keep (rare ones: never per frame)
    LaunchedEffect(inside?.id) { com.timerpg.street.shot.Crumbs.add(inside?.let { "room: ${it.title}" } ?: "street view") }
    LaunchedEffect(streetNo) { com.timerpg.street.shot.Crumbs.add("street $streetNo: ${street.name}") }
    LaunchedEffect(ai.state) { com.timerpg.street.shot.Crumbs.add("dialogue model: ${ai.state.name}") }
    LaunchedEffect(voices.enabled) { com.timerpg.street.shot.Crumbs.add(if (voices.enabled) "voices on" else "voices off") }
    LaunchedEffect(talk?.npc?.id) { com.timerpg.street.shot.Crumbs.add(talk?.let { "talking to ${it.npc.name}" } ?: "no conversation") }
    LaunchedEffect(aiPanel) { com.timerpg.street.shot.Crumbs.add(if (aiPanel) "AI panel open" else "AI panel closed") }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val room = inside
        if (room == null) HabitsStage(
            p = p, ui = HabitsUi(focus = data.streets[0].shops[0].id), visible = true, top = 0f, bottom = 0f, cover = 0f, inset = 0f,
            accent = Color(0xFF6FA8FF), actions = StageActions(), modifier = Modifier.fillMaxSize(),
        )
        // Filament draws the street (or the whole town) over the stage's sky. The town's scene is kept while inside a room
        // (drawn over it), so coming back out does not build the town again
        if (townMode) androidx.compose.runtime.key("town") { FilamentLayer(Modifier.fillMaxSize(), town = true) }
        else if (room == null && StreetRenderer.filament) androidx.compose.runtime.key("crossroads") { FilamentLayer(Modifier.fillMaxSize()) }
        if (room != null) com.lifemax.rpg.feature.habits.presentation.scene.InteriorStage(room, walker, hour, keeperSheet, Modifier.fillMaxSize())
        if (door > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = door)))
        // walking: one floating stick under the left thumb (it comes to where the thumb lands); under every button and
        // panel, which get their taps first. Still while a door fades or a panel covers the street; live through the walk
        // round the corner (pushing down walks it back)
        // the open world: a finger the stick does not take turns the camera round the player, two pinch it, a double tap
        // puts it behind them (OrbitGestures, chained outside the stick's own handler so the stick picks first)
        val stickOpen = doorTo == null && !aiPanel && lastCrash == null && !tuning && shot == null && !townLoading
        val orbiting by rememberUpdatedState(townMode && StreetRenderer.view == StreetRenderer.OPEN && inside == null && stickOpen && talk == null)
        MoveStick(enabled = stickOpen, Modifier.fillMaxSize().orbitGestures({ orbiting }, { townPlay.walk?.let { Math.toDegrees(it.heading.toDouble()).toFloat() } }),
            compact = townMode && StreetRenderer.view == StreetRenderer.OPEN && inside == null)
        // the stick's corner is not the system's back gesture (Android allows 200 dp of an edge: the bottom of it, where a
        // left thumb rests; a thumb landing higher on the very edge still swipes back, which the back handler above keeps
        // from leaving the game)
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(.45f).height(200.dp).systemGestureExclusion())
        // the bicycle (open world): one tap gets on or off, a long press rings the bell; just right of the stick's corner
        // (which takes a third of the width, at most 300 dp), low, where the left thumb reaches it from the stick
        // the cars (docs/VEHICLES.md): "Enter" by a parked car's door; in one, the pedals, the handbrake and "Exit"
        DriveHud(townMode && StreetRenderer.view == StreetRenderer.OPEN && inside == null && !townLoading && doorTo == null && talk == null)
        if (townMode && StreetRenderer.view == StreetRenderer.OPEN && inside == null && !townLoading && doorTo == null && !com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.inCar) {
            val conf = androidx.compose.ui.platform.LocalConfiguration.current
            val stickW = minOf(conf.screenWidthDp / 3, 300)
            Box(
                Modifier.align(Alignment.BottomStart).padding(start = (stickW + 12).dp, bottom = 22.dp).size(58.dp)
                    .background(if (bikeOn) AI_BLUE.copy(alpha = .85f) else Color(0x99101420), CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                val ok = townPlay.toggleBike()
                                bikeOn = ok
                                if (!ok && townPlay.canRide && !GameState.riding) noRoom = System.currentTimeMillis()
                            },
                            onLongPress = { if (GameState.riding) sound?.bell() },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) { Text(if (bikeOn) "🚲✓" else "🚲", if (bikeOn) 18.sp else 24.sp) }
        }
        // a test button (behind the 📷/🔖 switch): a car beside the player on the street, the next of the five each tap,
        // replacing the last one spawned (docs/VEHICLES.md); its name shown for a moment
        if (com.timerpg.street.shot.ShotSettings.buttonOn && townMode && StreetRenderer.view == StreetRenderer.OPEN && inside == null && !townLoading &&
            doorTo == null && !com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.inCar && com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.cars != null) {
            val conf = androidx.compose.ui.platform.LocalConfiguration.current
            val stickW = minOf(conf.screenWidthDp / 3, 300)
            Box(
                Modifier.align(Alignment.BottomStart).padding(start = (stickW + 12 + 58 + 10).dp, bottom = 22.dp).size(58.dp)
                    .background(Color(0x99101420), CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.cars?.let { it.requestSpawn(); carAskAt = System.currentTimeMillis() }
                        })
                    },
                contentAlignment = Alignment.Center,
            ) { Text("🚗", 24.sp) }
        }
        if (carAskAt != 0L) LaunchedEffect(carAskAt) {
            val vw = com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.cars
            val before = vw?.spawnCount ?: 0
            // (done at the game loop's next step)
            var waited = 0
            while (vw != null && vw.spawnCount == before && waited < 2000) { kotlinx.coroutines.delay(40); waited += 40 }
            carNote = vw?.spawnNote ?: "no cars in this town"
            com.timerpg.street.shot.Crumbs.add("car button: $carNote (${vw?.spawned?.spot ?: "none"})")
            kotlinx.coroutines.delay(1800)
            carNote = null
        }
        carNote?.let { Text("🚗 $it", 14.sp, Modifier.align(Alignment.BottomCenter).padding(bottom = 92.dp).background(Color(0x99101420), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 5.dp)) }
        if (noRoom != 0L) {
            Text("no room for the bike here", 12.sp, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp), color = DIM.copy(alpha = .8f))
            LaunchedEffect(noRoom) { kotlinx.coroutines.delay(1600); noRoom = 0L }
        }
        // the shop the player is standing at
        if (inside != null) {
            Text(inside!!.title + (inside!!.use.takeIf { it != inside!!.title && it != "Home" }?.let { "  ·  $it" } ?: ""), 15.sp,
                Modifier.align(Alignment.TopCenter).padding(top = 62.dp).background(Color(0x99101420), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 6.dp))
        } else if (townMode) {
            // the town: at a corner, the street a turn leads into; else the building in front and who keeps it
            val label = if (turning) null else if (atCorner && townCorner != null) "▲  $townCorner"
                else (townFront ?: townHere)?.let { sp -> townData?.names?.get(sp)?.let { (n, o) -> n + (o.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: "") } }
            if (label != null) Text(label, 15.sp,
                Modifier.align(Alignment.TopCenter).padding(top = 62.dp).background(Color(0x99101420), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 6.dp))
        } else if (atCorner && data.crossroads != null && !turning) {
            // at the corner: the other street, and the up key that turns into it (at the cross street's start walking on
            // against its end turns back too)
            Text(if (streetNo == 0) "▲  ${data.streets.getOrNull(1)?.name ?: "Side street"}" else "▲ ◀  ${data.streets[0].name}", 15.sp,
                Modifier.align(Alignment.TopCenter).padding(top = 62.dp).background(Color(0x99101420), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 6.dp))
        } else if (near in shops.indices && shops[near].id != com.lifemax.rpg.feature.habits.domain.JUNCTION_ID && !turning) {
            val st = shops[near]
            Text(st.cue + (owners.getOrNull(near)?.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: ""), 15.sp,
                Modifier.align(Alignment.TopCenter).padding(top = 62.dp).background(Color(0x99101420), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 6.dp))
        }
        // the loop's shop panel, the last action's line, the arrest card
        if (loopShop && loopPlace != null && townData != null) LoopShopPanel(loopPlace, townData.town.year, hour, onAction = { what, arg ->
            val sc = LoopSession.sceneAt(loopPlace, inside, walker.x, walker.y, hour)
            if (sc != null) when (what) {
                "buy" -> LoopSession.buy(ctx, loopPlace, arg as com.lifemax.rpg.feature.habits.loop.StockItem, sc, hour)
                "take" -> LoopSession.take(ctx, loopPlace, arg as com.lifemax.rpg.feature.habits.loop.StockItem, sc, hour)
                "sell" -> LoopSession.sell(ctx, loopPlace, arg as com.lifemax.rpg.feature.habits.loop.LoopItem, sc, hour)
                "work" -> LoopSession.work(ctx, loopPlace, sc, hour)
            }
            when (what) { "wait" -> LoopSession.waitHours(ctx, 1.0); "sleep" -> LoopSession.sleep(ctx) }
        }, onClose = { loopShop = false }, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp, bottom = 40.dp))
        if (loopPlace == null && loopShop) LaunchedEffect(Unit) { loopShop = false }
        LoopSession.line?.let { l ->
            Text(l, 14.sp, Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp, start = 24.dp, end = 24.dp).background(Color(0x99101420), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
            LaunchedEffect(LoopSession.lineAt) { val at = LoopSession.lineAt; kotlinx.coroutines.delay(6000); LoopSession.lineSeen(at) }
        }
        // (officers: a label over their heads while they are in view)
        if (townMode && inside == null) LoopOfficerLabels(frameTick)
        LoopArrestCard(Modifier.align(Alignment.Center).padding(16.dp))
        // someone to talk to here
        val open = talk
        // the speech bubbles: what the person said back (over their head) and the player's own words (over theirs)
        if (inside == null || keeperSpot != null) SpeechBubbles(open, playerSaid, frameTick, inside, keeperSpot, walker)
        if (open != null && !open.byVoice) {
            DialoguePanel(open, onTopic = { open.ask(engine, it, voices) }, onClose = { open.close(voices); talk = null },
                ears = ears, holdToTalk = !micAlways,
                onTalk = { down ->
                    if (down) {
                        if (!ears.hasPermission()) requestMic()
                        else { voices.stop(); engine.pause("listening"); ears.start() }
                    } else ears.stop(names) { said -> engine.resume("listening"); open.heardNote = ears.lastHeard; open.heardInfo = ears.lastHeardInfo; open.askSaid(engine, said, voices, memory) }
                    if (!down && ears.state == com.timerpg.street.voice.PlayerVoice.State.IDLE) engine.resume("listening")
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 92.dp))
        }
        if ((open == null || open.byVoice) && !turning && doorTo == null) {
            // someone to talk to (inside: when standing near them); the way in and out. Under the right thumb, as a game
            // pad's buttons are, clear of the stick's zone on the left
            // (the town: the door the player stands at, facing it; in the open world from any side of the building)
            val roomHere = (if (townMode) townFront else here)?.let { rooms[it] }
            Row(Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (inside == null || nearKeeper) for (npc in atHere) Chip("Talk to ${npc.first}", false) {
                    // a thumb on the stick stops walking (it would walk away and end the conversation) until it is moved again
                    MoveInput.holdUntilNeutral()
                    talk?.close(voices)
                    talk = Talk(npc).also { t -> npc.topics.firstOrNull()?.let { t.ask(engine, it, voices) } }
                }
                // the loop's shop here (docs/LOOP.md): buy, take, sell, work a shift
                if (townMode && loopPlace != null && LoopSession.view != null) Chip("🛒 ${loopPlace.name.take(22)}", loopShop) { loopShop = !loopShop; MoveInput.holdUntilNeutral() }
                if (inside != null) Chip("Leave", false) { doorTo = false to null }
                else if (roomHere != null) Chip("Go inside", false) { doorTo = true to roomHere }
                // the depot: the ticket window (docs/RAIL.md)
                val rail = townData?.rail
                val pw = com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.walk
                if (frameTick.longValue >= 0 && inside == null && rail != null && pw != null && RailTrip.ride == null && !ticketOpen && RailTrip.atDepot(rail, pw.wx, pw.wz))
                    Chip("Take the train", false) { MoveInput.holdUntilNeutral(); ticketOpen = true }
            }
        }
        townData?.rail?.let { rail ->
            if (ticketOpen) TicketWindow(rail, townData.town.year, hour, heard = emptyList(),
                onBoard = { route -> ticketOpen = false; RailTrip.request = com.lifemax.rpg.feature.habits.domain.RailRide(rail, route) },
                onClose = { ticketOpen = false }, modifier = Modifier.align(Alignment.Center).padding(16.dp))
            val ride = if (frameTick.longValue >= 0) RailTrip.ride else null
            if (ride != null) RideOverlay(rail, ride, RailTrip.progress, onDone = {
                // the clock moves on by the trip (the arrival's time of day); the ride's world goes
                val arrive = ride.route.legs?.lastOrNull()?.arrive
                if (arrive != null) { hour = ((arrive % 1440.0) / 60.0).toFloat(); LoopSession.clock(hour, wrap = true) }
                RailTrip.stop = true
            }, modifier = Modifier.align(Alignment.TopCenter).padding(top = 110.dp))
        }
        // the town being built (the first view nearest the player): a simple loading screen until it is ready
        if (townLoading && inside == null) Box(Modifier.fillMaxSize().background(Color(0xF0101420)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val err = TownSession.error
                Text(err ?: "Building ${townData?.town?.let { "the town ${it.seed}" } ?: "the town"}…", 18.sp)
                if (err == null) {
                    Box(Modifier.width(260.dp).height(8.dp).background(Color(0x55FFFFFF), RoundedCornerShape(4.dp))) {
                        Box(Modifier.fillMaxWidth(TownSession.progress.coerceIn(.02f, 1f)).fillMaxHeight().background(AI_BLUE, RoundedCornerShape(4.dp)))
                    }
                    Text(if (townData == null) "reading the town" else "the streets nearest you first", 12.sp, color = DIM)
                } else Chip("Back to the old crossroads", false) { StreetRenderer.setTown(ctx, StreetRenderer.CROSSROADS) }
            }
        }
        if (noOneNear != 0L) {
            Text("no one near enough to hear you", 12.sp, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp), color = DIM.copy(alpha = .7f))
            LaunchedEffect(noOneNear) { kotlinx.coroutines.delay(1800); noOneNear = 0L }
        }
        lastCrash?.let { report ->
            Column(
                Modifier.align(Alignment.Center).widthIn(max = 560.dp).background(PANEL, RoundedCornerShape(14.dp)).opaque().padding(14.dp),
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
            // (the slider's end, 24, is midnight: 12:00 AM, not noon)
            val h = hour.toInt() % 24; val m = ((hour - hour.toInt()) * 60).toInt()
            Text("%d:%02d %s".format(((h + 11) % 12) + 1, m, if (h < 12) "AM" else "PM"))
            Spacer(Modifier.width(10.dp))
            // (to 23:59: the slider's end at 24 reached the sky and the light as an hour past the day's end, a sunset at
            // midnight, while the clock read 12:00 PM)
            Slider(hour, { hour = it.coerceAtMost(LAST_MINUTE) }, 0f, LAST_MINUTE, Modifier.width(240.dp))
        }
        // the season and the weather
        Column(Modifier.align(Alignment.TopEnd).padding(10.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { SEASONS.forEachIndexed { i, (l, _) -> Chip(l, season == i) { season = i } } }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { WEATHERS.forEachIndexed { i, (l, _) -> Chip(l, weather == i) { weather = i } } }
        }
        if (tuning) Column(
            Modifier.align(Alignment.Center).widthIn(max = 520.dp).background(PANEL, RoundedCornerShape(14.dp)).opaque().padding(14.dp),
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
        if (backHint) Text("Back again to leave the game", 14.sp, Modifier.align(Alignment.Center).background(PANEL, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
        // the camera button (the AI panel's "Screenshots to Claude" switch): small, under the weather chips
        if (com.timerpg.street.shot.ShotSettings.buttonOn && !shooting && shot == null) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 92.dp, end = 12.dp).size(38.dp).background(Color(0x66101420), CircleShape)
                    .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); waitForUpOrCancellation()?.let { shooting = true } } },
                contentAlignment = Alignment.Center,
            ) { Text("📷", 16.sp) }
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 136.dp, end = 12.dp).size(38.dp).background(Color(0x66101420), CircleShape)
                    .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); waitForUpOrCancellation()?.let { com.timerpg.street.play.PlayLog.mark(); marking = true; shooting = true } } },
                contentAlignment = Alignment.Center,
            ) { Text("🔖", 16.sp) }
        }
        shot?.let { s -> com.timerpg.street.shot.ShotSheet(s, ears, onSend = { note, marks ->
            if (marking) com.timerpg.street.play.PlayLog.markNote(note)
            marking = false
            com.timerpg.street.shot.ShotUploader.sendShot(ctx, s.bmp, note, s.at, s.how, s.game, marks); shot = null
        }, onCancel = { marking = false; shot = null }) }
        // the top left, one column so nothing overlaps (drawn last, so the AI panel is on top): where the player is (kept
        // clear of the time bar in the middle: narrower, on two lines), then the dialogue AI's state at a glance (tap for
        // the model file and the speed numbers) and, in the open world, "Behind me" (out of the way of the right thumb's
        // drags and of the stick's corner), the always-listening mic under them
        val leftMax = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp / 2 - 196).coerceIn(150, 460).dp
        Column(Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.widthIn(max = leftMax).background(Color(0x99101420), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                if (townMode && townData != null) {
                    Text(listOfNotNull(townStreet, townHood).joinToString(" · ").ifEmpty { townData.town.seed }, 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text("${townData.town.seed} · ${townData.kind}", 11.sp, color = DIM, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                } else Text("${street.name} · ${data.town}", 14.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            if (townMode) LoopWallet(loopDebug) { loopDebug = !loopDebug }
            if (loopDebug && townMode) LoopDebugPanel()
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(aiChipLabel(ai), aiPanel) { aiPanel = !aiPanel }
                if (orbiting) Chip("Behind me", false) {
                    townPlay.walk?.let { com.lifemax.rpg.feature.habits.presentation.scene.OrbitCamera.recentre(Math.toDegrees(it.heading.toDouble()).toFloat()) }
                }
            }
            if (micAlways) MicLamp(ears, canListen, granted = micGranted, blocked = micBlocked) { requestMic() }
            updater.available?.let { r -> if (!aiPanel) Chip("⬆ Update: ${r.versionName}", false) { aiPanel = true } }
            if (aiPanel) AiPanel(ai, engine, voices, updater, micAlways, onMicAlways, ears, onPick = {
                try { pickModel.launch(arrayOf("*/*")) } catch (e: Exception) { /* no document picker on this device */ }
            })
        }
    }
}

/** An open conversation: who, the topic asked, and the line on screen (updated as the model writes it). */
private class Talk(val npc: Npc, val byVoice: Boolean = false, val door: Int = -1, val doorSprite: String? = null) {
    var topic by mutableStateOf<Topic?>(null)
    var line by mutableStateOf<Line?>(null)
    private var request: DialogueEngine.Request? = null

    /** The exchange under way, for the playtest recorder's conversation log (play/TalkTurn). */
    private var turn: com.timerpg.street.play.TalkTurn? = null

    private fun beginTurn(input: String, words: String): com.timerpg.street.play.TalkTurn {
        turn?.let { old -> if (!old.written) { old.why = "asked again before the reply"; old.finish(); com.timerpg.street.play.PlayLog.talk(old) } }
        return com.timerpg.street.play.TalkTurn(npc.id, npc.name, input, words).also { it.building = npc.buildingName; turn = it }
    }

    private fun finishTurn(tt: com.timerpg.street.play.TalkTurn, l: Line, engine: DialogueEngine) {
        if (tt.written) return
        val i = l.info
        tt.reply = l.text
        tt.source = com.timerpg.street.play.TalkTurn.sourceOf(i?.source ?: if (l.ai) "live" else "template")
        tt.model = i?.model
        if (i != null && i.source != "ready" && (i.firstTokenMs > 0 || i.restMs > 0)) {
            tt.unit = listOf("CPU", "GPU", "NPU").getOrNull(engine.status.value.runsOn)
            tt.waitMs = i.waitMs; tt.firstTokenMs = i.waitMs + i.firstTokenMs; tt.lastTokenMs = i.waitMs + i.firstTokenMs + i.restMs
            tt.tokens = i.tokens
        }
        tt.tries = i?.tries ?: 0
        tt.valid = when { i?.source == "live" -> true; i?.why?.contains("check") == true -> false; else -> null }
        tt.why = i?.why
        tt.finish()
        com.timerpg.street.play.PlayLog.talk(tt)
    }

    fun ask(engine: DialogueEngine, t: Topic, voices: com.timerpg.street.voice.NpcVoiceService? = null) {
        request?.close()
        topic = t
        said = null
        val tt = beginTurn("topic", t.label).also { it.intent = t.intent; it.topic = t.label; it.sheet = "${npc.id}/${t.intent}" }
        // a line is spoken once it is final (the model's when it has passed the check, else the game's own)
        var spoken: String? = null
        var early = false
        var r: DialogueEngine.Request? = null
        fun heard(l: Line) {
            if (!early && voices != null) early = earlyVoice(l, voices)
            if (l.final && l.text != spoken) {
                spoken = l.text; replies += l.text; voices?.say(npc, l.text)
                // the player's log (counts only): what they ask, and whether a line written ahead answered it
                engine.record(npc, t.intent, if (r?.ready == true) "ready" else if (l.ai) "live" else "template")
                finishTurn(tt, l, engine)
            }
        }
        val req = engine.ask(npc, t) { line = it; heard(it) }
        r = req
        request = req
        line = req.first
        // the line is still being written: they say something while they think
        if (req.first?.final != true) filler(t.label, voices)
        req.first?.let { heard(it) }
    }

    /** What they have said in this conversation, the latest last: a free question gets their last few so they say something new. */
    private val replies = ArrayList<String>()

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
        // (the line being written comes a whole sentence at a time, each already checked by the engine)
        val m = FIRST_SENTENCE.find(l.text) ?: WHOLE_SENTENCE.find(l.text) ?: return false
        val tp = topic ?: return false
        val ok = com.timerpg.street.llm.Dialogue.validate(m.groupValues[1], emptyList(), tp.self, tp.prompt ?: "", tp.recent) ?: return false
        voices.sayFirst(npc, ok)
        return true
    }

    /** How many times the player has spoken to them in this conversation (each answer's bubble is its own). */
    var asked by mutableIntStateOf(0)
    /** What the player last said out loud, shown above the answer. */
    var said by mutableStateOf<String?>(null)
    /** How long each step of the last spoken question took (hearing, memories, the model), shown under the answer. */
    var timing by mutableStateOf<String?>(null)
    /** Set by the caller before [askSaid]: how the words were heard and how long it took. */
    var heardNote: String? = null
    var heardInfo: com.timerpg.street.voice.PlayerVoice.Heard? = null

    /** The player said [text]: the person answers those words (the topic they are nearest is marked). */
    fun askSaid(engine: DialogueEngine, text: String, voices: com.timerpg.street.voice.NpcVoiceService? = null,
                memory: com.timerpg.street.dialogue.MemoryService? = null) {
        request?.close()
        said = text
        asked++
        // (a bubble shows the answer to these words only: not the last one while their memories are looked up)
        if (byVoice) line = null
        var spoken: String? = null
        val t0 = android.os.SystemClock.elapsedRealtime()
        var tAsk = 0L; var tFirst = 0L
        // they say the question back at once (voiced now, beside the model writing the answer), the answer after it
        val echo = com.timerpg.street.dialogue.Fillers.echo(text, npc.warmth, fillerSeed++)
        if (echo != null) voices?.sayEcho(npc, echo)
        val heardBy = heardNote
        val heardHow = heardInfo
        val tt = beginTurn(if (heardHow != null || heardBy != null) "spoken" else "typed", text)
        heardHow?.let { h -> tt.asrBy = h.by; tt.asrMs = h.afterEndMs; tt.asrAudioMs = h.audioMs; tt.asrConfidence = h.confidence }
        var early = false
        var asked: DialogueEngine.Request? = null
        var askedTopic: Topic? = null
        var vec: FloatArray? = null
        fun heard(l: Line) {
            if (l.ai && tFirst == 0L && tAsk > 0L) tFirst = android.os.SystemClock.elapsedRealtime()
            if (!early && voices != null) early = earlyVoice(l, voices)
            if (!l.final || l.text == spoken) return
            spoken = l.text; replies += l.text
            run {
                // the whole turn's timing, for the AI panel and the breadcrumbs: from the end of the player's speech to the
                // words written down, the memories looked up, the model's first token and the whole line, and who answered
                val end = android.os.SystemClock.elapsedRealtime()
                val after = heardHow?.afterEndMs ?: 0L
                val u = java.util.Locale.US
                val parts = listOfNotNull(heardHow?.label() ?: heardBy,
                    "memories %.1f s".format(u, (tAsk - t0).coerceAtLeast(0) / 1000.0),
                    "answer: " + (l.info?.label() ?: if (l.ai) "live" else "template"),
                    if (tFirst > 0 && !(l.info?.source == "ready")) "first words on screen %.1f s".format(u, (tFirst - tAsk).coerceAtLeast(0) / 1000.0) else null,
                    "whole line %.1f s after you stopped".format(u, (after + end - t0) / 1000.0))
                timing = parts.joinToString(" · ")
                VoiceTurns.last = "${npc.first}: $timing"
                com.timerpg.street.shot.Crumbs.add("voice turn (${npc.first}): $timing")
            }
            voices?.say(npc, l.text)
            // they remember what was said, and what they answered; the player's log keeps how it was answered (a line
            // written ahead, or live), which steers what is written overnight
            val how = if (asked?.ready == true) "ready" else if (l.ai) "live" else "template"
            val d = engine.data.value
            val intent = askedTopic?.intent ?: ""
            memory?.remember(npc.id, text, l.text, d?.town ?: "", d?.year ?: 0, intent, how, vec)
            engine.record(npc, intent, how)
            tt.intent = intent; tt.topic = askedTopic?.label; tt.sheet = "${npc.id}/$intent"
            finishTurn(tt, l, engine)
        }
        fun go(remembered: List<String>, v: FloatArray?) {
            if (said != text) return   // something else was asked meanwhile
            tAsk = android.os.SystemClock.elapsedRealtime()
            vec = v
            tt.memories = remembered.size
            val (tp, r) = engine.askFree(npc, text, remembered, v, replies.toList()) { line = it; heard(it) } ?: return
            asked = r; askedTopic = tp
            topic = tp
            request = r
            line = r.first
            if (r.first?.final != true && echo == null) filler(text, voices)
            heard(r.first)
        }
        // what they remember that bears on it goes into their fact sheet first
        if (memory != null && memory.installed) memory.recall(npc.id, text) { facts, v -> go(facts, v) } else go(emptyList(), null)
    }

    fun close(voices: com.timerpg.street.voice.NpcVoiceService? = null) {
        // walked away before the reply was final: the exchange is logged as such
        turn?.let { tt -> if (!tt.written) { tt.walkedAway = true; tt.reply = line?.text?.takeIf { line?.ai == true }; tt.finish(); com.timerpg.street.play.PlayLog.talk(tt) } }
        turn = null
        request?.close()
        request = null
        voices?.stop()
    }
}

private val WHOLE_SENTENCE = Regex("""^(.{8,}?[.!?]+)["”’')]*$""")

/** The last voice turn's timing (the AI panel shows it). */
private object VoiceTurns {
    var last by mutableStateOf<String?>(null)
}

private val FIRST_SENTENCE = Regex("""^(.{8,}?[.!?])\s+\S""")
private val PANEL = Color(0xCC101420)
private val DIM = Color(0xFFB8C2D6)

/**
 * Starts the NPU fit test from the AI panel: with the town loaded (when it is), then with it unloaded (the old crossroads
 * for the time of the test), then the town back and built before the dialogue model loads again.
 */
private fun startFitTest(ctx: android.content.Context) {
    val app = ctx.applicationContext
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    fun onMain(block: () -> Unit) { val l = java.util.concurrent.CountDownLatch(1); main.post { try { block() } finally { l.countDown() } }; l.await(5, java.util.concurrent.TimeUnit.SECONDS) }
    fun waitFor(ms: Long, cond: () -> Boolean) { val end = android.os.SystemClock.elapsedRealtime() + ms; while (!cond() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(250) }
    val town = StreetRenderer.town
    val withTown = StreetRenderer.townMode && TownSession.loaded != null
    val conditions = if (withTown) listOf("town", "no town") else listOf("no town")
    com.timerpg.street.llm.NpuFitTest.start(app, conditions, com.timerpg.street.llm.NpuFitTest.Hooks(
        prepare = { c ->
            when (c) {
                "town" -> if (TownSession.built) "the town built ($town)" else "the town loaded but not all built ($town)"
                else -> if (!withTown) "the town was not loaded when the test started" else {
                    onMain { StreetRenderer.setTown(app, StreetRenderer.CROSSROADS) }
                    waitFor(15_000) { TownSession.loaded == null }
                    // (its GPU memory and caches a moment to go)
                    Thread.sleep(8000)
                    "the town unloaded for the test (the old crossroads shown meanwhile)"
                }
            }
        },
        finish = {
            if (withTown && StreetRenderer.town != town) {
                onMain { StreetRenderer.setTown(app, town) }
                waitFor(120_000) { TownSession.built }
            }
        },
    ))
}
private val AI_BLUE = Color(0xFF6FA8FF)

/** The conversation: who, where, what they said (and whose words: the model's or the game's), and what to ask next. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DialoguePanel(t: Talk, onTopic: (Topic) -> Unit, onClose: () -> Unit, modifier: Modifier,
                          ears: com.timerpg.street.voice.PlayerVoice? = null, holdToTalk: Boolean = true, onTalk: (Boolean) -> Unit = {}) {
    Column(
        modifier.widthIn(max = 560.dp).background(PANEL, RoundedCornerShape(14.dp)).opaque().padding(horizontal = 14.dp, vertical = 10.dp),
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
            if (ears != null && holdToTalk) MicButton(ears, onTalk)
            if (!holdToTalk) Text("🎤 Just speak: the mic is listening", 12.sp, Modifier.padding(vertical = 6.dp), color = DIM)
            for (tp in t.npc.topics) Chip(chipLabel(tp), t.topic === tp) { onTopic(tp) }
        }
        ears?.problem?.let { Text(it, 11.sp, color = Color(0xFFFFB4A0)) }
        if (ears?.usingPhone == true) Text("Heard by the phone's own on-device speech recognition.", 11.sp, color = DIM)
    }
}

/** Whether the game is in front (resumed): the always-listening mic listens only then. */
private object Foreground {
    var resumed by mutableStateOf(false)
}

/** The always-listening mic at a glance: listening, hearing the player (with the meter), writing it down, paused. */
@Composable
private fun MicLamp(ears: com.timerpg.street.voice.PlayerVoice, live: Boolean, granted: Boolean = true, blocked: Boolean = false, onAllow: () -> Unit = {}) {
    if (!granted) {
        // always listening is on, but the game may not use the microphone: say so, and ask again on a tap (or, when
        // Android will not ask any more, open the app's settings page)
        Chip(com.timerpg.street.voice.MicAsk.label(blocked), false) { onAllow() }
        return
    }
    // words lost, or the recognizer kept stalling (PlayerVoice's watch): a tap starts listening again from scratch
    if (live && ears.needsRetry) {
        Chip("🎤 didn't catch that: tap to retry", false) { ears.retry() }
        return
    }
    val label = when {
        !live -> "🎤 paused"
        ears.writing > 0 -> "🎤 …"
        ears.hearing -> "🎤 hearing you"
        else -> "🎤 listening"
    }
    val on = live && ears.hearing
    Box(
        Modifier.background(if (on) Color(0xFF8A2E2E).copy(alpha = .55f + .45f * ears.level) else Color(0x99101420), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) { Text(label, 12.sp, color = if (live) Color.White else DIM) }
    ears.problem?.takeIf { live }?.let { Text(it, 11.sp, Modifier.widthIn(max = 260.dp), color = Color(0xFFFFB4A0)) }
}

/**
 * The bubbles of a conversation by voice: the person's answer over their head (thinking dots while it is written, then the
 * line, wrapped, fading after a time that grows with its length), and the player's own words over theirs for a moment.
 * They follow the people as the street scrolls ([frameTick]: read only while laying them out). On the street the heads
 * are where the stage put them ([com.lifemax.rpg.feature.habits.presentation.scene.TalkRange]); in a room, where it fits.
 */
@Composable
private fun SpeechBubbles(
    talk: Talk?, playerSaid: Triple<String, Long, Boolean>?, frameTick: androidx.compose.runtime.LongState,
    room: com.lifemax.rpg.feature.habits.presentation.scene.InteriorRoom?, keeperSpot: Pair<Float, Float>?,
    walker: com.lifemax.rpg.feature.habits.presentation.scene.RoomWalker,
) {
    val tr = com.lifemax.rpg.feature.habits.presentation.scene.TalkRange
    // where the person's bubble was placed last frame: the player's own keeps clear of it (side by side, else stacked)
    val theirs = remember { BubbleRect() }
    if (talk != null && talk.byVoice && talk.said != null) {
        val l = talk.line
        // the game's own line stands in while the model writes (it is not what they will say): dots until the model's words come
        val text = when { l == null -> "…"; l.ai || l.final -> l.text; else -> "…" }
        Bubble(text, l?.final == true, think = l?.final != true, keyOf = talk.npc.id + "#" + talk.asked, frameTick = frameTick, placed = theirs) { w, h ->
            if (room != null) keeperSpot?.let { tr.inRoom(room, it.first, it.second, w, h) }
            else if (talk.doorSprite != null) com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.keeperHead(talk.doorSprite)
            else tr.keeperHead(talk.door)
        }
    }
    if (playerSaid != null) {
        Bubble(playerSaid.first, playerSaid.third, think = false, keyOf = playerSaid.second.toString(), frameTick = frameTick, small = true, avoid = theirs) { w, h ->
            if (room != null) tr.inRoom(room, walker.x, walker.y, w, h)
            else if (com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.active) com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.playerHead()
            else tr.playerHead()
        }
    }
}

/** A bubble's place on the screen (px) at the frame time [tick] (ns) it was laid out (stale after 0.1 s: gone). */
private class BubbleRect { var x = 0; var y = 0; var w = 0; var h = 0; var tick = Long.MIN_VALUE }

/** Moves a [pw] x [ph] bubble at [x], [y] (on a [sw] x [sh] screen) off [o]: beside it on the head's side, else above or below it. */
private fun clearOf(o: BubbleRect, x: Int, y: Int, pw: Int, ph: Int, headX: Float, sw: Int, sh: Int): Pair<Int, Int> {
    val gap = 6
    fun hits(ax: Int, ay: Int) = ax < o.x + o.w + gap && ax + pw + gap > o.x && ay < o.y + o.h + gap && ay + ph + gap > o.y
    if (!hits(x, y)) return x to y
    val maxX = (sw - pw - 4).coerceAtLeast(4); val maxY = (sh - ph - 4).coerceAtLeast(4)
    // beside it, on the side this head is on (then the other side)
    val left = (o.x - gap - pw).coerceIn(4, maxX); val right = (o.x + o.w + gap).coerceIn(4, maxX)
    val sides = if (headX < o.x + o.w / 2f) listOf(left, right) else listOf(right, left)
    for (sx in sides) if (!hits(sx, y)) return sx to y
    // stacked: above it, else below it
    val above = o.y - gap - ph
    if (above >= 4) return x to above
    return x to (o.y + o.h + gap).coerceIn(4, maxY)
}

/**
 * One bubble over a head: [anchor] (the head on the screen, from the screen's size; null hides it) read each frame. Fades
 * in, and once [final], out again after a few seconds (more for a longer line). [think]: a thought bubble's trail of
 * dots under it (still thinking), else a speech bubble's tail. Square-edged and outlined, as the HUD's pixel art.
 */
@Composable
private fun Bubble(text: String, final: Boolean, think: Boolean, keyOf: String, frameTick: androidx.compose.runtime.LongState,
                   small: Boolean = false, placed: BubbleRect? = null, avoid: BubbleRect? = null,
                   anchor: (Float, Float) -> androidx.compose.ui.geometry.Offset?) {
    val alpha = remember(keyOf) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(keyOf, text, final) {
        alpha.animateTo(1f, androidx.compose.animation.core.tween(150))
        if (final) {
            val hold = if (small) (1500L + 40L * text.length).coerceAtMost(5000L) else (2500L + 55L * text.length).coerceAtMost(12_000L)
            kotlinx.coroutines.delay(hold)
            alpha.animateTo(0f, androidx.compose.animation.core.tween(600))
        }
    }
    if (alpha.value <= 0f && final) return
    val ink = Color(0xFF101420)
    val paper = if (small) Color(0xE6D8E2F2) else Color(0xF2F4F1E8)
    Column(
        Modifier.layout { measurable, constraints ->
            val w = constraints.maxWidth; val h = constraints.maxHeight
            val p = measurable.measure(androidx.compose.ui.unit.Constraints(maxWidth = w, maxHeight = h))
            val tick = frameTick.longValue   // laid out again every frame
            val at = anchor(w.toFloat(), h.toFloat())
            layout(w, h) {
                if (at != null) {
                    var x = (at.x - p.width / 2f).toInt().coerceIn(4, (w - p.width - 4).coerceAtLeast(4))
                    var y = (at.y - p.height).toInt().coerceIn(4, (h - p.height - 4).coerceAtLeast(4))
                    // (the other bubble, if it was up within the last 0.1 s: kept clear of)
                    if (avoid != null && tick - avoid.tick < 100_000_000L) clearOf(avoid, x, y, p.width, p.height, at.x, w, h).let { x = it.first; y = it.second }
                    p.place(x, y)
                    placed?.let { it.x = x; it.y = y; it.w = p.width; it.h = p.height; it.tick = tick }
                }
            }
        }.graphicsLayer { this.alpha = alpha.value },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.widthIn(max = if (small) 180.dp else 240.dp)
                .background(ink, RoundedCornerShape(3.dp)).padding(2.dp)
                .background(paper, RoundedCornerShape(2.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
        ) { Text(text, if (small) 11.sp else 13.sp, color = ink) }
        if (think) {
            // a thought's trail: two little squares, smaller towards the head
            Spacer(Modifier.height(2.dp))
            Box(Modifier.size(7.dp).background(ink).padding(1.5.dp).background(paper))
            Spacer(Modifier.height(2.dp))
            Box(Modifier.size(4.dp).background(ink))
        } else {
            // a speech bubble's tail: a stepped pixel wedge
            Box(Modifier.size(width = 10.dp, height = 3.dp).background(ink))
            Box(Modifier.size(width = 6.dp, height = 3.dp).background(ink))
            Box(Modifier.size(width = 2.dp, height = 3.dp).background(ink))
        }
        Spacer(Modifier.height(4.dp))
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
    ModelState.NO_MODEL -> if (s.needLive) "AI: light model missing" else "AI: no model"
    ModelState.COPYING -> if (s.copyPercent >= 0) "AI: copying ${s.copyPercent}%" else "AI: copying ${s.copiedMb} MB"
    // (in play, no room for the NPU copy: it never runs on the CPU there, and the chip says what it waits for)
    ModelState.WAITING -> s.npuWait?.let { "AI: $it" } ?: "AI: waiting"
    ModelState.LOADING -> "AI: loading…"
    ModelState.READY -> (if (s.working != null) "AI ✓ writing" else "AI ✓") + (if (s.runsOn == 0 && s.hasNpu) " (CPU)" else "")
    ModelState.UNLOADED -> "AI: unloaded"
    ModelState.ERROR -> "AI: error"
}

private fun f1(v: Float) = String.format(java.util.Locale.US, "%.1f", v)
private fun f0(v: Float) = String.format(java.util.Locale.US, "%.0f", v)

/** The model (its file and state) and the speed numbers. */
@Composable
private fun AiPanel(s: AiStatus, engine: DialogueEngine, voices: com.timerpg.street.voice.NpcVoiceService, updater: AppUpdater,
                    micAlways: Boolean, onMicAlways: (Boolean) -> Unit, ears: com.timerpg.street.voice.PlayerVoice, onPick: () -> Unit) {
    Column(
        Modifier.width(340.dp).heightIn(max = 240.dp).background(PANEL, RoundedCornerShape(12.dp)).opaque().verticalScroll(rememberScrollState())
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
                ModelState.WAITING -> "Waiting to load the model"
                ModelState.LOADING -> "Loading the model…"
                ModelState.READY -> "Ready (${s.modelMb} MB model)"
                ModelState.UNLOADED -> "Unloaded (low memory)"
                ModelState.ERROR -> "Error"
            }, 13.sp,
        )
        if (s.detail.isNotEmpty()) Text(s.detail, 12.sp, color = DIM)
        s.gpuNote?.let { Text(it, 11.sp, color = Color(0xFFFFB4A0)) }
        // in play the light model answers; without it nothing does (the main one does not fit beside the town)
        if (s.needLive) {
            Text("The light model for replies in play is not downloaded.", 12.sp, color = Color(0xFFFFB4A0))
            if (voices.downloading < 0f) Chip("Download the light model (${com.timerpg.street.dialogue.ModelFiles.LIVE_BYTES shr 20} MB)", false) { voices.downloadLive { engine.reload() } }
        }
        // the last thing said by voice: how long each step took and who answered
        VoiceTurns.last?.let { Text("Last voice turn: $it", 11.sp, color = DIM) }
        // the phone's memory now, and what the AI lane is doing (to measure the models' footprint on a real phone)
        val ctx = androidx.compose.ui.platform.LocalContext.current
        var ram by remember { mutableStateOf("") }
        var frames by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            val am = ctx.getSystemService(android.app.ActivityManager::class.java)
            while (true) {
                val mi = android.app.ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
                val mine = android.os.Debug.getPss() shr 10
                val busy = com.timerpg.street.llm.AiLane.current ?: "idle"
                // (the Java heap's limit and use, and the town build's peak: what the town's build has to fit in)
                val rt = Runtime.getRuntime()
                val tm = com.lifemax.rpg.feature.habits.presentation.scene3d.TownMemory
                val heap = "Java heap ${(rt.totalMemory() - rt.freeMemory()) shr 20}/${rt.maxMemory() shr 20} MB" +
                    if (tm.peakUsed > 0) " (town build peak ${tm.peakUsed shr 20} MB)" else ""
                ram = "RAM: ${mi.availMem shr 20} MB free of ${mi.totalMem shr 20} MB · the game ${mine} MB · $heap · AI: $busy"
                frames = FrameStats.summary()
                kotlinx.coroutines.delay(1000)
            }
        }
        Text(ram, 11.sp, color = DIM)
        // where the memory went: each model resident and the game's own parts (Debug.getMemoryInfo, off the main thread)
        var room by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            while (true) {
                room = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.timerpg.street.llm.ModelRoom.summary(ctx) }.getOrDefault("") }
                kotlinx.coroutines.delay(2000)
            }
        }
        if (room.isNotEmpty()) Text(room, 11.sp, color = DIM)
        Text(frames, 11.sp, color = DIM)
        // the renderer trial: the classic street or Filament, and how each runs here
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Renderer", 12.sp)
            Chip("Classic", !StreetRenderer.filament) { StreetRenderer.set(ctx, false) }
            Chip("Filament", StreetRenderer.filament) { StreetRenderer.error = null; StreetRenderer.set(ctx, true) }
        }
        // the walkable town or the old eta crossroads (for comparison); the town needs Filament
        if (StreetRenderer.filament) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Town", 12.sp)
            Chip(TownSession.DEFAULT, StreetRenderer.town != StreetRenderer.CROSSROADS) { StreetRenderer.setTown(ctx, TownSession.DEFAULT) }
            Chip("Old crossroads", StreetRenderer.town == StreetRenderer.CROSSROADS) { StreetRenderer.setTown(ctx, StreetRenderer.CROSSROADS) }
        }
        // the open world (the player turns the camera: one finger drags it round, two pinch it nearer) or the street view
        if (StreetRenderer.filament) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("View", 12.sp)
            Chip("Open world", StreetRenderer.view == StreetRenderer.OPEN) { StreetRenderer.setView(ctx, StreetRenderer.OPEN) }
            Chip("Street", StreetRenderer.view == StreetRenderer.STREET) { StreetRenderer.setView(ctx, StreetRenderer.STREET) }
        }
        // (the open world's drag: the camera turns the way the finger goes, or the world is grabbed and moves with it)
        if (StreetRenderer.filament && StreetRenderer.view == StreetRenderer.OPEN) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Drag", 12.sp)
            Chip("Turns the camera", !StreetRenderer.grabWorld) { StreetRenderer.setGrab(ctx, false) }
            Chip("Moves the world", StreetRenderer.grabWorld) { StreetRenderer.setGrab(ctx, true) }
        }
        // (on the bike the camera swings round behind the rider by itself, paused while the player turns it: the owner's
        // one exception to the camera rule; on foot it never turns by itself)
        if (StreetRenderer.filament && StreetRenderer.view == StreetRenderer.OPEN) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("On the bike", 12.sp)
            Chip("Camera follows", StreetRenderer.bikeFollow) { StreetRenderer.setBikeFollow(ctx, true) }
            Chip("Camera stays", !StreetRenderer.bikeFollow) { StreetRenderer.setBikeFollow(ctx, false) }
        }
        // (the town's drawing resolution: auto lowers it a little only while the GPU cannot keep up)
        if (StreetRenderer.filament) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Resolution", 12.sp)
            for ((r, label) in listOf(0f to "Auto", 1f to "Full", .85f to "85%", .75f to "75%"))
                Chip(label, StreetRenderer.resolution == r) { StreetRenderer.setResolution(ctx, r) }
        }
        // (the town's pictures: compressed ASTC, or exact RGBA8 as before, for the next town built)
        if (StreetRenderer.filament) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Textures", 12.sp)
            Chip("Compressed", StreetRenderer.astc) { StreetRenderer.setAstc(ctx, true) }
            Chip("Exact", !StreetRenderer.astc) { StreetRenderer.setAstc(ctx, false) }
        }
        // (the street view's camera heights)
        if (StreetRenderer.filament && StreetRenderer.view == StreetRenderer.STREET) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Camera", 12.sp)
            for ((name, label) in listOf("low" to "Low", "medium" to "Medium", "high" to "High"))
                Chip(label, StreetRenderer.camera == name) { StreetRenderer.setCamera(ctx, name) }
        }
        StreetRenderer.error?.let { Text(it, 11.sp, color = Color(0xFFFFB4A0)) }
        ClaudeSettings()
        var heat by remember { mutableStateOf("") }
        var fil by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            val pm = ctx.getSystemService(android.os.PowerManager::class.java)
            while (true) {
                heat = if (android.os.Build.VERSION.SDK_INT >= 30 && pm != null)
                    "Heat: status ${pm.currentThermalStatus} · headroom %.2f (1 = throttling)".format(java.util.Locale.US, pm.getThermalHeadroom(10))
                else ""
                fil = if (StreetRenderer.filament) FilamentStats.summary() else ""
                kotlinx.coroutines.delay(1000)
            }
        }
        if (fil.isNotEmpty()) Text(fil, 11.sp, color = DIM)
        if (heat.isNotEmpty()) Text(heat, 11.sp, color = DIM)
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
            // optional: two more versions of the same dialogue model, only for trying out speed-ups (not needed to play)
            var extrasNote by remember { mutableStateOf("") }
            val extrasMissing = remember(voices.downloading, extrasNote) { voices.extrasMissingMb }
            val extrasHere = remember(voices.downloading, extrasNote) { voices.extrasOnDeviceMb }
            Text("Optional speed-up versions of Qwen (NPU Q4_0 and MTP): " +
                (if (extrasMissing == 0L) "installed" else "not needed to play"), 12.sp, color = DIM)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (extrasMissing > 0) Chip("Download extras ($extrasMissing MB)", false) { voices.downloadExtras { engine.reload() } }
                if (extrasHere > 0) Chip("Remove extras ($extrasHere MB)", false) {
                    val freed = com.timerpg.street.dialogue.ModelDownloads.removeExtras(ctx); extrasNote = "Removed $freed MB"; engine.reload()
                }
            }
            if (extrasNote.isNotEmpty()) Text(extrasNote, 12.sp, color = DIM)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // off (the default) loads no voice and makes none ahead: switched off, they are let go at once
            Chip(if (voices.enabled) "Voices: on" else "Voices: off", voices.enabled) { voices.switchTo(!voices.enabled) }
            // always listening: say something near someone and they answer (hold to talk in a conversation otherwise)
            // (on, but the microphone not allowed: a tap asks for it rather than turning listening off)
            val micBlocked = micAlways && !ears.hasPermission()
            Chip(if (micBlocked) "Mic: always listening, not allowed: tap to allow" else if (micAlways) "Mic: always listening" else "Mic: hold to talk", micAlways) {
                onMicAlways(if (micBlocked) true else !micAlways)
            }
        }
        // who writes down what the player says: the phone's own on-device recognizer (words shown while you talk; ours
        // is then not loaded) or the game's speech model (small or large); the words never leave the phone either way
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val phone = ears.hearerWanted == com.timerpg.street.voice.PlayerVoice.Hearer.PHONE
            Chip(if (phone) "Speech: phone (on-device)" else "Speech: game model", phone) {
                ears.chooseHearer(if (phone) com.timerpg.street.voice.PlayerVoice.Hearer.GAME else com.timerpg.street.voice.PlayerVoice.Hearer.PHONE)
            }
            val small = ears.asrSize == com.timerpg.street.voice.PlayerVoice.AsrSize.SMALL
            if (ears.hearer == com.timerpg.street.voice.PlayerVoice.Hearer.GAME)
                Chip(if (small) "Game speech: small (fast)" else "Game speech: large (accurate)", !small) {
                    ears.chooseAsrSize(if (small) com.timerpg.street.voice.PlayerVoice.AsrSize.LARGE else com.timerpg.street.voice.PlayerVoice.AsrSize.SMALL)
                }
        }
        if (ears.hearerWanted == com.timerpg.street.voice.PlayerVoice.Hearer.PHONE && !ears.phoneAvailable)
            Text("This phone has no on-device speech recognition: the game's speech model hears you.", 11.sp, color = DIM)
        ears.phoneProblem?.let { Text(it, 11.sp, color = DIM) }
        ears.lastHeard?.let { Text("Speech: $it", 11.sp, color = DIM) }
        Spacer(Modifier.height(2.dp))
        Text("Speed", 14.sp)
        val st = s.stats
        Text("Threads ${st.threads} · model load ${f1(st.loadMs / 1000f)} s" + (if (s.workMb > 0) " · cache and buffers ${s.workMb} MB" else ""), 12.sp, color = DIM)
        Chip(if (engine.kvQ8Wanted) "Model cache: 8-bit (half the memory)" else "Model cache: 16-bit", engine.kvQ8Wanted && s.kvQ8) { engine.setKvQ8(!engine.kvQ8Wanted) }
        Text("Last prompt ${st.promptTokens} tokens (${st.reusedTokens} reused) · prefill ${f0(st.prefillPerSec)} tok/s", 12.sp, color = DIM)
        Text("Generation ${st.genTokens} tokens · ${f1(st.genPerSec)} tok/s · first token ${st.firstTokenMs} ms", 12.sp, color = DIM)
        Text("Written ahead: ${s.lines} lines (${s.builtIn} kept built-in) · queued ${s.queued}" + (if (s.paused.isNotEmpty()) " · paused: ${s.paused}" else ""), 12.sp, color = DIM)
        s.pregenOff?.let { Text(it, 12.sp, color = DIM) }
        s.working?.let { Text("Writing: $it", 12.sp, color = DIM) }
        NightPanel(s, engine)
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
        // which model answers in play (the light one on the NPU beside the town; the main one writes the bank), and a
        // switch to the main one for comparison
        if (s.hasLive) {
            val dev = listOf("CPU", "GPU", "NPU").getOrElse(s.runsOn) { "?" }
            Text(when (s.role) {
                com.timerpg.street.dialogue.ModelRole.LIVE -> "Live replies: the light model on the $dev"
                com.timerpg.street.dialogue.ModelRole.BANK -> "Live replies: the main model on the $dev"
                null -> "Live replies: no model loaded"
            } + " · needs ${s.liveNeedMb} MB free (light), ${s.bankNeedMb} MB (main)", 12.sp, color = DIM)
            Chip(if (s.forceBank) "Live replies: main model (comparison)" else "Live replies: light model", s.forceBank) { engine.setForceBank(!s.forceBank) }
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
        // the NPU fit test: does each model's copy load here, with the town and without it, and how fast (a report to Claude)
        run {
            val pc = androidx.compose.ui.platform.LocalContext.current
            val ft by com.timerpg.street.llm.NpuFitTest.status.collectAsState()
            Chip(if (ft.running) "NPU fit test: ${ft.progress ?: "…"}" else "NPU fit test (with the town, then without; a few minutes; sends a report)", ft.running) {
                if (!ft.running) startFitTest(pc)
            }
            ft.last?.let { Text("Fit test: $it", 11.sp, color = DIM) }
            var gate by remember { mutableStateOf(com.timerpg.street.llm.NpuGate.enabled(pc)) }
            Chip(if (gate) "NPU copy: counts memory Android frees on demand" else "NPU copy: only Android's free count", gate) {
                gate = !gate; com.timerpg.street.llm.NpuGate.setEnabled(pc, gate); engine.reload()
            }
            engine.gateText?.let { Text("Device gate: $it", 11.sp, color = DIM) }
            var gpuFb by remember { mutableStateOf(engine.gpuFallbackWanted) }
            Chip(if (gpuFb) "If the NPU fails: the GPU, then the CPU" else "If the NPU fails: the CPU", gpuFb) { gpuFb = !gpuFb; engine.setGpuFallback(gpuFb) }
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

/**
 * The AI panel's overnight writer: its switch, the bank (lines ready, variants, years, stale), last night's run, what
 * the player's conversations taught it (themes; questions answered by a ready line or live, by day), and what Android
 * needs allowed for it to start by itself (notifications, "Unrestricted" battery use).
 */
@Composable
private fun NightPanel(s: AiStatus, engine: DialogueEngine) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val nw = com.timerpg.street.night.NightWriter
    var on by remember { mutableStateOf(nw.enabled(ctx)) }
    var words by remember { mutableStateOf(nw.shareWords(ctx)) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(5000); tick++ } }
    Spacer(Modifier.height(2.dp))
    Text("Overnight writer", 14.sp)
    Chip("Write dialogue while charging: " + if (on) "on" else "off", on) { on = !on; nw.setEnabled(ctx, on) }
    // Writer mode: its own screen in its own process; the game closes so the main model gets its memory
    (ctx as? android.app.Activity)?.let { act -> Chip("Writer mode: close the game and write the bank now", false) { com.timerpg.street.night.WriterActivity.open(act) } }
    val b = s.bank
    Text("Bank: ${b.ready} of ${b.prompts} lines ready (${b.templates} built-in, ${b.missing} to write) · ${b.variants} lines with variants, " +
        "${b.variantsDone} prompts complete · stale ${b.stale} · years ${if (b.years.isEmpty()) "none yet" else b.years.joinToString()}", 12.sp, color = DIM)
    s.night?.let { Text("Now: ${it.label}", 12.sp) }
    val last = remember(tick) { nw.lastSummary(ctx) }
    Text("Last run: " + (last ?: "none yet"), 12.sp, color = DIM)
    // the writer's own word on why it is or is not writing (its process keeps it: a run with no line says why)
    remember(tick) { nw.status(ctx) }?.let { st ->
        Text(st.line(System.currentTimeMillis()), 12.sp, color = if (st.state == "writing" || st.state == "stopped") DIM else Color(0xFFFFC27A))
    }
    remember(tick) { com.timerpg.street.shot.UploadLog.entry(ctx, "lastError") }?.let { e ->
        Text("Last upload error: ${e.path} (${e.what})", 12.sp, color = Color(0xFFFFC27A))
    }
    // what the player's conversations taught it (counts and labels; the words stay on the phone)
    val steer = engine.steer
    if (steer.themes.isNotEmpty())
        Text("Your themes: " + steer.themes.take(5).joinToString(" · ") { "${it.label} (${it.count}${if (it.live > 0) ", ${it.live} live" else ""})" }, 12.sp, color = DIM)
    val days = remember(tick) { engine.answerDays(5) }
    if (days.isNotEmpty())
        Text("Answered ready / live: " + days.joinToString(" · ") { (d, a) -> "${d.takeLast(5)} ${a[0]}/${a[1]}" }, 12.sp, color = DIM)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!remember(tick) { nw.unrestricted(ctx) }) Chip("Let it start by itself (battery: Unrestricted)", false) { nw.askUnrestricted(ctx) }
        val act = ctx as? android.app.Activity
        if (android.os.Build.VERSION.SDK_INT >= 33 && act != null &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            Chip("Show its notification", false) { act.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41) }
    }
    Chip("Night reports: " + if (words) "with my words" else "counts only", words) { words = !words; nw.setShareWords(ctx, words) }
    // the playtest recorder's conversation log (talk/ on the reports branch: docs/PLAYTEST.md; on by default in the owner's builds)
    var talkRec by remember { mutableStateOf(com.timerpg.street.play.PlayLog.recordTalk(ctx)) }
    Chip("Record my conversations: " + if (talkRec) "on" else "off", talkRec) { talkRec = !talkRec; com.timerpg.street.play.PlayLog.setRecordTalk(ctx, talkRec) }
}

private val LEFT_KEYS = setOf(android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_A)
/** The hold on the dialogue model's load while the town is built ([DialogueEngine.holdLoad]). */
private const val TOWN_BUILD_HOLD = "town-build"
/** How long the player must stand before lines are written ahead again (ns). */
private const val STILL_NS = 1_500_000_000L
/** The time slider's end: 23:59. */
private const val LAST_MINUTE = 23f + 59f / 60f

private val RIGHT_KEYS = setOf(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_D)
private val UP_KEYS = setOf(android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_W)
private val DOWN_KEYS = setOf(android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_S)
private val RUN_KEYS = setOf(android.view.KeyEvent.KEYCODE_SHIFT_LEFT, android.view.KeyEvent.KEYCODE_SHIFT_RIGHT,
    android.view.KeyEvent.KEYCODE_BUTTON_R1, android.view.KeyEvent.KEYCODE_BUTTON_R2)

/** The last few seconds of frame times (ms), for the AI panel: is the street really drawn at the display's 120 Hz? */
private object FrameStats {
    private val ring = FloatArray(480)
    private var n = 0
    private var at = 0
    /** The refresh rate asked for (Hz), 0 if none was. */
    var display = 0f

    @Synchronized fun add(ms: Float) { ring[at] = ms; at = (at + 1) % ring.size; if (n < ring.size) n++; if (win.size < 4096) win += ms }

    /** The frames since the last call (the breadcrumbs' 10 s line): p50, p95, the longest, and the refreshes dropped. */
    private val win = ArrayList<Float>()
    @Synchronized fun window(): String {
        val s = win.sorted(); win.clear()
        if (s.isEmpty()) return "no frames"
        val period = if (display > 0f) 1000f / display else s[s.size / 2]
        // (each frame that took longer than a refresh and a half dropped that many refreshes)
        val dropped = s.sumOf { (it / period - .5f).toInt().coerceAtLeast(0).toInt() }
        return "frame p50 %.1f ms, p95 %.1f, max %.1f; %d frames, %d refreshes dropped (%.0f Hz)".format(java.util.Locale.US,
            s[s.size / 2], s[(s.size * 95 / 100).coerceAtMost(s.size - 1)], s.last(), s.size, dropped, if (display > 0f) display else 1000f / period)
    }

    @Synchronized fun summary(): String {
        if (n < 10) return "Frames: measuring…"
        val s = ring.copyOf(n).sorted()
        val p50 = s[n / 2]; val p95 = s[(n * 95) / 100]
        // a frame that took over one and a half times the usual one was late (one or more refreshes dropped)
        val late = s.count { it > p50 * 1.5f } * 100f / n
        return "Frames: %.1f ms (%.0f fps) · p95 %.1f ms · %.1f%% late".format(java.util.Locale.US, p50, 1000f / p50, p95, late) +
            (if (display > 0f) " · display %.0f Hz".format(java.util.Locale.US, display) else "")
    }
}

private val SEASON_NAMES = listOf("spring", "summer", "autumn", "winter")
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

/** A panel over the street takes the touches on it: none of them fall through to the stick under it. */
private fun Modifier.opaque(): Modifier = pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }

@Composable
private fun Text(s: String, fontSize: androidx.compose.ui.unit.TextUnit = 15.sp, modifier: Modifier = Modifier, color: Color = Color.White,
                 maxLines: Int = Int.MAX_VALUE, overflow: androidx.compose.ui.text.style.TextOverflow = androidx.compose.ui.text.style.TextOverflow.Clip) {
    androidx.compose.runtime.SideEffect { FrameSpikes.recomposed++ }
    androidx.compose.foundation.text.BasicText(
        s, modifier,
        style = androidx.compose.ui.text.TextStyle(color = color, fontSize = fontSize, fontWeight = FontWeight.SemiBold),
        overflow = overflow, maxLines = maxLines,
    )
}

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

/** A text field in the HUD's style; [secret] masks it (the token). While it has the focus the game's keys type into it. */
@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, secret: Boolean = false) {
    androidx.compose.foundation.text.BasicTextField(
        value, onChange,
        modifier.background(Color(0x33FFFFFF), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 6.dp)
            .onFocusChanged { com.timerpg.street.shot.ShotSettings.typing = it.isFocused },
        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
        singleLine = secret,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = if (secret) androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrectEnabled = false)
            else androidx.compose.foundation.text.KeyboardOptions.Default,
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, 13.sp, color = DIM.copy(alpha = .6f)); inner() } },
    )
}

/** The AI panel's "Screenshots to Claude" row: the camera button, the GitHub token, crash reports (long-press the title: test crashes). */
@Composable
private fun ClaudeSettings() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val set = com.timerpg.street.shot.ShotSettings
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { set.load(ctx.applicationContext) } }
    var tokenText by remember { mutableStateOf("") }
    var test by remember { mutableStateOf("") }
    var waiting by remember { mutableIntStateOf(0) }
    LaunchedEffect(set.hasToken, test) {
        waiting = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.timerpg.street.shot.Reports.pending(ctx).size }
    }
    Spacer(Modifier.height(2.dp))
    androidx.compose.foundation.text.BasicText("Screenshots to Claude",
        Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { set.debug = !set.debug }) },
        style = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip(if (set.buttonOn) "📷 Button: on" else "📷 Button: off", set.buttonOn) { set.setButton(ctx, !set.buttonOn) }
        Text(if (set.hasToken) "token saved" else "no token", 12.sp, color = if (set.hasToken) DIM else Color(0xFFFFB4A0))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Field(tokenText, { tokenText = it.trim() }, "GitHub token", Modifier.width(170.dp), secret = true)
        if (tokenText.isNotEmpty()) Chip("Save", false) { set.saveToken(ctx, tokenText); tokenText = ""; test = "" }
        else if (set.hasToken) Chip("Forget", false) { set.forgetToken(ctx); test = "" }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("Test upload", false) { test = "Testing…"; com.timerpg.street.shot.ShotUploader.testAccess(ctx) { test = it } }
        if (set.hasToken && waiting > 0) Chip("Send $waiting crash report${if (waiting > 1) "s" else ""}", false) {
            com.timerpg.street.shot.ShotUploader.sendReports(ctx, quiet = false); test = "Sending crash reports…"
        }
    }
    if (test.isNotEmpty()) Text(test, 11.sp, color = DIM)
    Chip("Send crash reports automatically: " + if (set.autoReports && set.hasToken) "on" else "off", set.autoReports && set.hasToken) {
        set.setAutoReports(ctx, !set.autoReports)
    }
    if (set.hasToken) Text("Token kept in ${set.tokenStore}", 11.sp, color = DIM)
    if (set.debug) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("Test crash (Kotlin)", false) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { throw RuntimeException("Test crash from the AI panel (Screenshots to Claude)") }
        }
        Chip("Test native crash", false) { android.os.Process.sendSignal(android.os.Process.myPid(), 11) }
    }
}
