import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.hypot
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.lifemax.rpg.feature.habits.domain.Row
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownYards
import com.lifemax.rpg.feature.habits.presentation.scene.GameState
import com.lifemax.rpg.feature.habits.presentation.scene.OrbitCamera
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuilding
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuildingLoad
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteSheets
import com.lifemax.rpg.feature.habits.presentation.scene.TownPlay
import com.lifemax.rpg.feature.habits.presentation.scene.skyColors
import com.lifemax.rpg.feature.habits.presentation.scene3d.FilamentTown
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownArt
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownPose
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownRun
import java.io.File
import org.jetbrains.skia.Image as SkiaImage

/**
 * The open world (-Dmode=filament-open): the whole town (the app's assets/town/gamma, or -Dtown=DIR) built as the game builds it,
 * then seen through the player's orbit camera ([OrbitCamera], drawn by [TownRun] as the app draws it: following the
 * player, pulled in front of walls, the sun fixed in the world, far yards in their far version) from behind the player
 * at several yaws, pitches and zooms: on Main Street, on the civic avenue, in a Heights backyard, over the river, by
 * the cinema; by day and at night. The sky behind is laid by the camera's horizon with the sun or moon where the stage
 * draws it ([OrbitCamera.orbAt]), so the light and the orb can be checked against each other.
 *
 * The player is the game's open-world walk ([TownPlay.open], an [OpenWalk] against the town's collision), stood where each
 * view puts them ([OpenWalk.placeAt]: on the nearest clear spot). Then a scripted walk of 30 s through the app's own game
 * loop ([TownPlay.step], the stick relative to the camera, the camera turned and zoomed only by scripted drags and pinches)
 * from the spawn along the street, through a side gate into a back yard and out again: walk_XXs.png every 5 s and the
 * path in walk.txt (with a check that the camera never turned without a drag).
 * For each view open.txt gives the draws and triangles three ways: a free camera as before (no draw distance, every yard
 * near), with the draw distance only, and as drawn (draw distance and far yards). -Dshots=name,name renders only those.
 * Writes build/preview/filament-open/.
 */
/** Called with the open world's town once it is built (GpuBudgetTest reads its GPU account there; -DgpuOnly then stops). */
@Volatile var openBuilt: ((FilamentTown) -> Unit)? = null

fun renderFilamentOpen(out: String) {
    val dir = File(out, "filament-open").also { it.mkdirs() }
    // (the town the app ships, its cinema included; else the last tools/build-town.sh)
    val townDir = System.getProperty("town")?.let { File(it) }
        ?: File("../app/src/main/assets/town/gamma").takeIf { File(it, "street.json").exists() } ?: File("../build-town/gamma")
    val assets = File(System.getProperty("assetsRoot") ?: "../../public/assets")
    val json = File(townDir, "street.json").readText()
    val town = Town.parse(json) ?: error("$townDir/street.json has no graph (a v1 file): run tools/build-town.sh gamma")
    val yards = TownYards.parse(json)
    val root = org.json.JSONObject(json)
    val bdir = File(townDir, "buildings")
    val windows = SpriteBuildingLoad.windows(File(bdir, "windows.json").takeIf { it.exists() }?.readText())
    val houses = SpriteBuildingLoad.houses(File(bdir, "houses.json").takeIf { it.exists() }?.readText())
    val models = SpriteBuildingLoad.models(File(bdir, "models.json").takeIf { it.exists() }?.readText())
    val mids = HashMap<String, Int>()
    root.optJSONArray("shops")?.let { a ->
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val parts = o.optJSONArray("parts")
            mids[o.getString("sprite")] = if (parts == null) 0 else (0 until parts.length()).count { parts.getJSONObject(it).getString("key").startsWith("middle_") }
        }
    }
    fun open(f: File) = f.takeIf { it.exists() }?.let { SkiaImage.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
    val art = TownArt { path -> open(File(assets, path)) }
    val building: (String) -> SpriteBuilding? = { sp -> SpriteBuildingLoad.load(sp, mids[sp] ?: 0, windows, houses, models) { open(File(bdir, "$it.png")) } }

    // (-DtexStats=1: how many colours the pictures' blocks hold, by owner, in gpu.txt)
    com.lifemax.rpg.feature.habits.presentation.scene3d.GpuAccount.stats = System.getProperty("texStats") != null
    // (-Dastc=0: the pictures as RGBA8, as before ASTC, to compare)
    com.lifemax.rpg.feature.habits.presentation.scene3d.TownQuality.astc = System.getProperty("astc") != "0"
    // (-DastcCache=DIR: the encoded pictures kept there from run to run, as the app keeps them in its cache folder)
    System.getProperty("astcCache")?.let { com.lifemax.rpg.feature.habits.presentation.scene3d.TownQuality.astcCache = File(it) }
    com.lifemax.rpg.feature.habits.presentation.scene3d.TownQuality.maskFormats = System.getProperty("maskFormats") != "0"
    com.lifemax.rpg.feature.habits.presentation.scene3d.TownQuality.maskPages = System.getProperty("maskPages") != "0"
    // (-Dquant=0: the shared pages hold float vertices, 28 bytes, as before the quantised ones, to compare)
    com.lifemax.rpg.feature.habits.presentation.scene3d.BufferArena.quantize = System.getProperty("quant") != "0"
    // (-DyardRepeats=1: how much the near yards repeat, in town.txt: what instancing and a shared yard atlas could save)
    com.lifemax.rpg.feature.habits.presentation.scene3d.YardKit.Repeats.on = System.getProperty("yardRepeats") != null
    // (-DyardAtlas=0: each lot's yard on sheets of its own, as before the town's shared yard atlas, to compare)
    com.lifemax.rpg.feature.habits.presentation.scene3d.TownQuality.yardAtlas = System.getProperty("yardAtlas") != "0"
    System.getProperty("astcExact")?.toDoubleOrNull()?.let { com.lifemax.rpg.feature.habits.presentation.scene3d.TownQuality.astcExact = it }
    // (-Dmsaa=1: no multisampling, as before the setting; -Dmsaa=2/4/8 that many samples; docs/HEAT.md)
    System.getProperty("msaa")?.toIntOrNull()?.let { com.lifemax.rpg.feature.habits.presentation.scene3d.TownQuality.msaa = it }
    Filament.init()
    val engine = Engine.Builder().backend(Engine.Backend.OPENGL).config(com.lifemax.rpg.feature.habits.presentation.scene3d.TownEngine.config()).build()
    val mats = File("../app/src/main/assets/filament")
    val cinema = com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaSite.parse(root)
    // the railroad, as the app draws it (-Drail=0 leaves it out), its bed kept clear of the forest before the ground is made
    val railNet = File(townDir, "rail.json").takeIf { it.exists() && System.getProperty("rail") != "0" }?.let { com.lifemax.rpg.feature.habits.domain.RailNet.parse(it.readText()) }
    railNet?.let { town.keepClear = com.lifemax.rpg.feature.habits.domain.RailScene(it, town.year)::onBed }
    // the hand-made landmark models the town places (docs/LANDMARKS_GLB.md)
    val lmDir = File("../app/src/main/assets/landmarks")
    val landmarkAssets = com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkAssets(
        com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkSpec.catalogue(File(lmDir, "landmarks.json").readText()),
        // (-DglbFile=PATH reads every model's .glb from PATH instead: an older export to compare)
        { p -> System.getProperty("glbFile")?.takeIf { p.contains("foundry-factory") }?.let { File(it).readBytes() } ?: File(lmDir, p).takeIf { it.exists() }?.readBytes() },
        com.lifemax.rpg.feature.habits.domain.GlbLandmarks.parse(root).map { p ->
            // (-DglbAs=SPRITE:KIND:STATE:VARIANT stands another model, in another state, in a slot: a year the export is not)
            val o = System.getProperty("glbAs")?.split(":")?.takeIf { it.size == 4 && it[0] == p.sprite }
            if (o == null) p else com.lifemax.rpg.feature.habits.domain.GlbPlaced(p.sprite, p.row, o[1], p.name, o[2], emptySet(), p.opened, o[3].toInt())
        })
    val collision = com.lifemax.rpg.feature.habits.domain.TownCollision.build(town, yards,
        com.lifemax.rpg.feature.habits.domain.ModelShape.parseAll(File(bdir, "models.json").takeIf { it.exists() }?.readText()),
        com.lifemax.rpg.feature.habits.domain.CinemaSiteShape.parse(root), com.lifemax.rpg.feature.habits.domain.StoreLots.parse(root).map { it.sprite }.toSet())
    val storeSites = com.lifemax.rpg.feature.habits.presentation.scene3d.StoreSite.parse(root)
    com.lifemax.rpg.feature.habits.presentation.scene3d.FilamentTown.farLod = System.getProperty("townFar") != "0"
    // the sky: the town's own (SkySurface; -Dsky3d=0: none, see-through over -Dsky=stage's 2D Backdrop as before), its
    // weather (-DskyWeather=overcast, rain, mist, cloudy) and date (-DskyDay=yyyy-MM-dd: the moon's phase)
    FilamentTown.sky3d = System.getProperty("sky3d") != "0"
    // streaming (docs/STREAMING.md): only what is near the player resident in full; -Dstream=0 holds the whole town as before
    FilamentTown.stream = System.getProperty("stream") != "0"
    // the lights far off at night (docs/STREAMING.md); -DfarLights=0 leaves them out to compare
    FilamentTown.farLightsOn = System.getProperty("farLights") != "0"
    com.lifemax.rpg.feature.habits.presentation.scene3d.TownFar.slod = System.getProperty("slod") != "0"
    com.lifemax.rpg.feature.habits.presentation.scene3d.TreeImpostors.on = System.getProperty("impostors") != "0"
    val ft = FilamentTown(engine, { n -> File(mats, "$n.filamat").readBytes() }, town, yards, art, building, cinema = cinema,
        stores = storeSites, collision = collision, rail = railNet, landmarks = landmarkAssets)
    ft.weather = when (System.getProperty("skyWeather")) {
        "cloudy" -> com.lifemax.rpg.feature.habits.domain.WeatherState(.45f, 0f, 0f, 0f)
        "overcast" -> com.lifemax.rpg.feature.habits.domain.WeatherState(.85f, 0f, 0f, 0f)
        "rain" -> com.lifemax.rpg.feature.habits.domain.WeatherState(.9f, .8f, .8f, 0f)
        "mist" -> com.lifemax.rpg.feature.habits.domain.WeatherState(.4f, 0f, 0f, .75f)
        else -> com.lifemax.rpg.feature.habits.domain.WeatherState.CLEAR
    }
    ft.day = System.getProperty("skyDay") ?: "2026-07-10"
    ft.nightBudgetNs = Long.MAX_VALUE
    // the farms round the town, as the app attaches them (-Dfarms=0 leaves them out)
    if (System.getProperty("farms") != "0") {
        val farmDir = File("../app/src/main/assets/farm")
        val farmData = com.lifemax.rpg.feature.habits.domain.TownFarmsData.parse(root)
        if (!farmData.isEmpty) com.lifemax.rpg.feature.habits.presentation.scene3d.TownFarms.attach(ft, farmData,
            com.lifemax.rpg.feature.habits.presentation.scene3d.FarmArt({ p -> open(File(farmDir, p)) }, { p -> File(farmDir, p).takeIf { it.exists() }?.readText() }, { p -> open(File(assets, p)) }),
            collision, day = 200)
    }
    // (-Dworld=1: gamma on the planet, the world's far view round it: FilamentWorldPreview.kt)
    val worldSite = WorldPreview.site(town)
    val world = worldSite?.let { WorldPreview.attach(ft, town, it) }
    // (-DworldVis=<m>: the air's visibility at sea level, for the far view and the horizon ring; a clear day 90 km)
    System.getProperty("worldVis")?.toDoubleOrNull()?.let { ft.air = com.lifemax.rpg.feature.habits.domain.WorldAir(it) }
    // (a still picture shows everyone in its one frame)
    ft.sheetsOnWorker = false
    // (and the see-through cone open or shut at once in it)
    com.lifemax.rpg.feature.habits.presentation.scene3d.SeeThrough.ease = false
    val report = StringBuilder()
    report.appendLine("open world, town ${town.seed} ${town.year}: ${ft.slots.size} buildings, ${yards.yards.size} yards; far yards ${if (com.lifemax.rpg.feature.habits.presentation.scene3d.YardKitBuilder.far) "baked" else "off (-DtownLod=0)"}")
    val buildStart = System.nanoTime()
    ft.startGround()
    while (!ft.uploadGround(8f)) Thread.sleep(1)
    while (ft.pending > 0) { if (ft.buildNearest(0f, 0f, 8f) == 0) Thread.sleep(1) }
    ft.finishLevels()
    // (the whole town from the start of its ground to its last building, as built here: the build server's time, not the phone's)
    report.appendLine("town built in %.0f ms (wall clock, this machine)".format((System.nanoTime() - buildStart) / 1e6))
    report.appendLine(ft.farReport)
    for (l in ft.extraReport) report.appendLine(l)
    report.appendLine("(software GL on the build server, not the S25: draws and triangles are what is sent, the times are not the phone's)")
    ft.textureStats.let { (n, t, sh) -> report.appendLine("textures after the build: %d, %.1fM texels, %d shared; biggest: %s".format(n, t / 1e6, sh, ft.biggestTextures(10))) }
    // (the same pixels made a second time by another set of textures: each set shares by content within itself)
    report.appendLine("textures made again by another set: %d, %.1fM texels".format(com.lifemax.rpg.feature.habits.presentation.scene3d.StreetTextures.madeAgain.get(),
        com.lifemax.rpg.feature.habits.presentation.scene3d.StreetTextures.madeAgainTexels.get() / 1e6) +
        com.lifemax.rpg.feature.habits.presentation.scene3d.GpuAccount.duplicates().let { (n, b) -> "; textures holding a picture another one holds: %d, %.1f MB".format(n, b / 1048576.0) })
    // the GPU memory account (TOWN.md "GPU memory"): every texture by owner and format, the shadow map and targets
    report.appendLine(com.lifemax.rpg.feature.habits.presentation.scene3d.GpuAccount.line())
    // (the same by part, textures and buffers together, as the phone's screenshot reports it: "gpuNow", "gpuByPart")
    report.appendLine(com.lifemax.rpg.feature.habits.presentation.scene3d.GpuAccount.categoryLine(ft.gpuEstimate(2340, 1080)))
    // (and against the budgets, MEMORY_AUDIT.md's guard: GpuBudgetTest fails on any part over its budget)
    report.append("GPU budget by part:\n" + com.lifemax.rpg.feature.habits.presentation.scene3d.TownBudget.let { b -> b.table(b.check(
        com.lifemax.rpg.feature.habits.presentation.scene3d.GpuAccount.categories(ft.gpuEstimate(2340, 1080)))) })
    // (the vertices the shared pages hold quantised, 20 bytes, and those left as floats, 28: a batch too big or too
    // coarse for 16 bits, or with buffers of its own)
    com.lifemax.rpg.feature.habits.presentation.scene3d.BufferArena.let { ba ->
        val r = com.lifemax.rpg.feature.habits.presentation.scene3d.TownVertex.rejected
        report.appendLine("vertices uploaded: %.2f M quantised, %.2f M as floats (no picture size %.2f M, box too big %.2f M, texture coordinates too wide %.2f M)".format(
            ba.quantized.get() / 1e6, ba.unquantized.get() / 1e6, r.get(0) / 1e6, r.get(1) / 1e6, r.get(2) / 1e6))
    }
    if (com.lifemax.rpg.feature.habits.presentation.scene3d.YardKit.Repeats.on) report.appendLine(com.lifemax.rpg.feature.habits.presentation.scene3d.YardKit.Repeats.report())
    com.lifemax.rpg.feature.habits.presentation.scene3d.YardKit.sharedAtlas?.let { a -> report.appendLine("the yards' shared atlas: %d sheets, %.2f M texels".format(a.sheets.w.size, a.texels / 1e6)) }
    openBuilt?.invoke(ft)
    if (System.getProperty("gpuOnly") != null) {
        File(dir, "town.txt").writeText(report.toString()); println(report)
        println(com.lifemax.rpg.feature.habits.presentation.scene3d.GpuAccount.table(ft.gpuEstimate(2340, 1080)))
        println(com.lifemax.rpg.feature.habits.presentation.scene3d.GpuAccount.bufferTable())
        return
    }
    report.appendLine("ASTC encoding: %.1f s over all threads".format(com.lifemax.rpg.feature.habits.presentation.scene3d.Astc.nanos.get() / 1e9))
    report.appendLine("ASTC level 0: " + com.lifemax.rpg.feature.habits.presentation.scene3d.Astc.level0.line() + "; mip levels: " + com.lifemax.rpg.feature.habits.presentation.scene3d.Astc.mips.line() +
        "; pictures by exact blocks: " + com.lifemax.rpg.feature.habits.presentation.scene3d.Astc.histogram())
    report.appendLine("textures if only pictures this exact stayed ASTC: " + com.lifemax.rpg.feature.habits.presentation.scene3d.GpuAccount.policies(0.0, .8, .9, .95, .99, 1.0))
    report.appendLine("this GL's compressed formats: ASTC 4x4 " + com.google.android.filament.Texture.isTextureFormatSupported(engine, com.google.android.filament.Texture.InternalFormat.SRGB8_ALPHA8_ASTC_4x4) +
        ", ETC2 " + com.google.android.filament.Texture.isTextureFormatSupported(engine, com.google.android.filament.Texture.InternalFormat.ETC2_EAC_SRGBA8) +
        ", BC7 " + com.google.android.filament.Texture.isTextureFormatSupported(engine, com.google.android.filament.Texture.InternalFormat.SRGB_ALPHA_BPTC_UNORM))
    File(dir, "gpu.txt").writeText(com.lifemax.rpg.feature.habits.presentation.scene3d.GpuAccount.table(ft.gpuEstimate(2340, 1080)) +
        "\n(the shadow map at %d x %d cascades; targets and surfaces for a 2340 x 1080 view)\n".format(ft.shadowMap, ft.shadowCascades) +
        com.lifemax.rpg.feature.habits.presentation.scene3d.GpuAccount.statsReport())

    // the people to talk to (as the app picks them) and the player
    val dialogue = File("../app/src/main/assets/town/${town.seed}/dialogue.json")
    TownPlay.keepers = if (!dialogue.exists()) emptyMap() else run {
        val a = org.json.JSONObject(dialogue.readText()).getJSONArray("npcs")
        val m = LinkedHashMap<String, Int>()
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); m.putIfAbsent(o.getString("building"), o.getString("id").hashCode() and 0x7fffffff) }
        m
    }
    // (their sex and age, as the app hands them: the first person of each building)
    TownPlay.keeperTraits = if (!dialogue.exists()) emptyMap() else run {
        val a = org.json.JSONObject(dialogue.readText()).getJSONArray("npcs")
        val m = LinkedHashMap<String, Int>()
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); m.putIfAbsent(o.getString("building"), o.optInt("age").coerceAtLeast(0) * 2 + (if (o.optString("sex") == "F") 1 else 0)) }
        m
    }
    GameState.enabled = true; GameState.look = 1
    GameState.moveX = 0f; GameState.moveLane = 0f
    OrbitCamera.open = true
    report.appendLine("stores (${ft.storeYear.takeIf { it > 0 } ?: town.year}): " + ft.storeReport.joinToString("; ") + "; collision ${collision.size} + ${collision.extraSize} shapes, ${collision.extraDoors.size} store doors")
    val events = ArrayList<String>()
    TownPlay.onEvent = { events += it }
    TownPlay.start(town, collision, yards)
    val walk = TownPlay.open ?: error("no open walk")
    check(TownPlay.walk === walk)
    val run = TownRun(ft)
    // (the town is built already: this marks the first view ready, so the people stand)
    while (!run.build(walk.wx, walk.wz, 8f)) Thread.sleep(1)
    val sheets = SpriteSheets.sheets
    val shooter = Shooter(engine, ft)
    // (-Dcars=1: the cars in the capital instead of the open world's views: FilamentCarsPreview.kt)
    if (System.getProperty("cars") != null) { renderCarShots(File(out), ft, town, yards, collision, walk, run, shooter, System.getProperty("w")?.toIntOrNull() ?: 1600, (System.getProperty("w")?.toIntOrNull() ?: 1600) * 1080 / 2340); return }
    val only = System.getProperty("shots")?.split(",")?.map { it.trim() }?.toSet()
    fun want(name: String) = only == null || name in only || only.any { name.startsWith(it) }
    val w = (System.getProperty("w")?.toIntOrNull() ?: 2340); val h = w * 1080 / 2340
    val night = 22.5f
    var clock = 600f
    OrbitCamera.open = true

    fun stats(): String = ft.stats.let { s -> "draws %5d, triangles %8d, buildings %3d, casters %4d".format(s.draws, s.triangles, s.visible, s.casters) }

    /**
     * The player at ([x], [z]) heading ([hx], [hz]) (on the pavement: walking along it, [GameState.dir] for the street
     * view's own facing), the camera at [yaw], [pitch], [dist]; at [hour].
     */
    fun view(name: String, x: Float, z: Float, hx: Float, hz: Float, yaw: Float, pitch: Float, dist: Float, hour: Float, where: String) {
        if (!want(name)) return
        walk.placeAt(x, z, hx, hz)
        // (the street view's facing along the row: the player faces the way they head)
        val a = walk.row.alongAt(walk.x.coerceIn(0f, walk.row.length))
        GameState.dir = if (a.x * hx + a.z * hz >= 0f) 1f else -1f
        GameState.facing = 0
        OrbitCamera.set(yaw, pitch, dist)
        OrbitCamera.follow(walk.wx, walk.wz)
        // (a jump across town: what streaming wants here comes in first, as behind a loading screen)
        if (FilamentTown.stream) {
            ft.frame(run.orbit(walk, w.toFloat(), h.toFloat(), clock), hour, clock.toDouble())
            var tries = 0
            while (!ft.streamSettled) {
                if (ft.buildNearest(walk.wx, walk.wz, 8f) == 0) Thread.sleep(1)
                ft.frame(run.orbit(walk, w.toFloat(), h.toFloat(), clock), hour, clock.toDouble())
                if (++tries == 20000) { report.appendLine("  (streaming did not settle: ${ft.streamWhy})"); println("  streaming did not settle: ${ft.streamWhy}"); break }
            }
        }
        val t0 = System.nanoTime()
        // the same camera three ways: free as before (no draw distance, every yard near), the draw distance alone, as drawn
        val pose0 = run.orbit(walk, w.toFloat(), h.toFloat(), clock)
        fun variant(range: Boolean, lod: Boolean) = TownPose(pose0.ex, pose0.ey, pose0.ez, pose0.fx, pose0.fy, pose0.fz, pose0.fl, pose0.cx, pose0.cy, pose0.w, pose0.h,
            pose0.near, pose0.far, pose0.hazeFrom, pose0.haze, range = if (range) pose0.range else Float.MAX_VALUE, open = true, lodFar = if (lod) pose0.lodFar else Float.MAX_VALUE,
            shadowFar = pose0.shadowFar)
        ft.prepare(variant(false, false)); ft.frame(variant(false, false), hour); val free = stats()
        ft.frame(variant(true, false), hour); val ranged = stats()
        ft.prepare(pose0)
        val pose = run.draw(walk, w.toFloat(), h.toFloat(), hour, clock, sheets)
        val drawn = stats()
        if (System.getProperty("debugeye") != null) report.appendLine("  eye (%.0f, %.0f, %.0f) near: %s".format(pose.ex, pose.ey, pose.ez, ft.near(pose.ex, pose.ey, pose.ez)))
        // the sky as the stage lays it under the town: its gradient down to the horizon, the orb where the light comes from
        val orbXY = FloatArray(2)
        val key = com.lifemax.rpg.feature.habits.domain.SceneLight.key(hour)
        val orb = if (OrbitCamera.orbAt(hour, w.toFloat(), h.toFloat(), orbXY)) floatArrayOf(orbXY[0], orbXY[1], h * .035f, (if (key.cool > .5f) 0xE6EAF6 else 0xFFF0B8).toFloat()) else null
        val marks = ArrayList<Pair<androidx.compose.ui.geometry.Offset, Int>>()
        TownPlay.playerHead()?.let { marks += it to 0x40E0FF }
        val stage = stageSky(hour, w, h)
        shooter.save(File(dir, "$name.png"), w, h, hour, marks, skyRow = skyRow(hour, h), orb = if (stage != null) null else orb, skyImg = stage)
        if (System.getProperty("gpucost")?.let { gc -> gc == "all" || gc.split(",").any { name.startsWith(it) } } == true) report.appendLine(gpuCost(name, walk, run, ft, shooter, sheets, w, h, hour, clock))
        if (System.getProperty("streamDebug") != null) report.appendLine("    " + ft.sunInfo())
        if (System.getProperty("streamDebug") != null) for (sl in ft.slots) if (sl.isBuilt && hypot(sl.cx - walk.wx, sl.cz - walk.wz) > 2000f)
            report.appendLine("    slot %s at (%.0f, %.0f) cy %.0f r %.0f d %.0f built %s want %s known %s missing %s shown %s cell t %s".format(sl.b.sprite, sl.cx, sl.cz, sl.cy, sl.r,
                hypot(sl.cx - walk.wx, sl.cz - walk.wz), sl.isBuilt, sl.want, sl.known, sl.missing, sl.shown, ft.cellT(sl)))
        if (FilamentTown.stream) report.appendLine("%-26s %s; GPU now %.1f MB".format("", ft.streamReport,
            (com.lifemax.rpg.feature.habits.presentation.scene3d.GpuAccount.categories(ft.gpuEstimate(2340, 1080))["total"] as? Map<*, *>)?.get("mb") as? Number ?: 0.0))
        report.appendLine("%-26s %s; player (%.0f, %.0f) on %s (%s); %s".format(name, where, walk.wx, walk.wz, walk.row.id, TownPlay.streetName(), OrbitCamera.describe()))
        report.appendLine("%-26s free as before: %s | draw distance: %s | as drawn (+ far yards past %.0f): %s; people %d; %.0f ms; orb %s".format(
            "", free, ranged, pose.lodFar, drawn, run.peopleDrawn, (System.nanoTime() - t0) / 1e6, orb?.let { "(%.0f, %.0f)".format(it[0], it[1]) } ?: "behind"))
        println("  $name")
        clock += 1f
    }

    fun heading(x: Float, z: Float) = OrbitCamera.headingOf(x, z)
    fun mid(r: Row) = r.buildings.sortedBy { it.mid }.let { it[it.size / 2].mid }
    fun majority(hood: String): Row = town.rows.maxByOrNull { r -> r.buildings.count { it.neighbourhood == hood } * 10 + r.buildings.size }!!

    // --- Main Street: behind the player walking along it, square at the fronts, high, close, far, through the block
    run {
        val r = town.rows.filter { town.road(it.road).name == "Main Street" }.maxByOrNull { it.buildings.size }!!
        val x = mid(r)
        val p = r.pointAt(x, 7f); val a = r.alongAt(x); val n = r.normalAt(x)
        val along = heading(a.x, a.z); val fronts = heading(n.x, n.z)
        view("main_behind_13h", p.x, p.z, a.x, a.z, along, 18f, 420f, 13f, "Main Street, behind the player")
        view("main_behind_night", p.x, p.z, a.x, a.z, along, 18f, 420f, night, "Main Street, behind the player")
        view("main_fronts_13h", p.x, p.z, a.x, a.z, fronts, 14f, 380f, 13f, "Main Street, square at the fronts")
        view("main_quarter_13h", p.x, p.z, a.x, a.z, along + 40f, 32f, 700f, 13f, "Main Street, three-quarter from above")
        view("main_high_13h", p.x, p.z, a.x, a.z, along + 135f, 58f, OrbitCamera.DIST_MAX, 13f, "Main Street, zoomed out (as far as it goes), looking down")
        view("main_close_13h", p.x, p.z, a.x, a.z, along + 180f, 10f, 150f, 13f, "Main Street, zoomed in, facing the player")
        view("main_through_13h", p.x, p.z, a.x, a.z, fronts + 180f, 20f, 600f, 13f, "Main Street, the camera behind the block (the block cut away)")
        view("main_dusk_west", p.x, p.z, a.x, a.z, 270f, 16f, 500f, 19.3f, "Main Street, looking west at dusk")
    }
    // --- the planned town (docs/TOWN_PLAN.md): -Dshots=plan renders these. The start spot on Main Street before the civic
    // block, the river crossing downtown, the depot and the rail side, the highway strip, a street of the town's other
    // homes from the front and from a backyard, the whole downtown from above, the radio station; a county seat's
    // courthouse where the town has one (-Dtown=a county seat's folder)
    run {
        val g = root.getJSONObject("graph")
        fun mid4(a: org.json.JSONArray) = floatArrayOf(((a.getDouble(0) + a.getDouble(2)) / 2).toFloat(), ((a.getDouble(1) + a.getDouble(3)) / 2).toFloat())
        /** A view from the pavement nearest world point ([cx], [cz]), the camera turned [turn] from square at the fronts. */
        fun at(name: String, cx: Float, cz: Float, turn: Float, pitch: Float, dist: Float, hour: Float, where: String) {
            val (r, x) = town.nearestRow(cx, cz) ?: return
            val p = r.pointAt(x, 7f); val a = r.alongAt(x); val n = r.normalAt(x)
            view(name, p.x, p.z, a.x, a.z, heading(n.x, n.z) + turn, pitch, dist, hour, where)
        }
        val sp = g.getJSONObject("spawn")
        val sr = town.rows.first { it.id == sp.get("row").toString() }
        val sx = sp.getDouble("x").toFloat()
        val s0 = sr.pointAt(sx, 7f); val sa0 = sr.alongAt(sx); val sn = sr.normalAt(sx)
        // (the way the player faces at the start: along Main Street to the plaza)
        val sd = sp.optDouble("dir", 1.0).toFloat().let { if (it < 0f) -1f else 1f }
        val sa = com.lifemax.rpg.feature.habits.domain.TownPoint(sa0.x * sd, sa0.z * sd)
        view("plan_start", s0.x, s0.z, sa.x, sa.z, heading(sa.x, sa.z), OrbitCamera.PITCH0, OrbitCamera.DIST0, 13f, "the start view as the app opens it: behind the player, along Main Street to the plaza")
        // Main Street at a person's height: low behind the player, a little to the side, the street walls receding
        view("plan_main", s0.x, s0.z, sa.x, sa.z, heading(sa.x, sa.z) + 12f, OrbitCamera.PITCH_MIN, 160f, 13f, "Main Street at a person's height")
        // (downtown from above: over the civic block's plaza, looking along Main Street)
        val pzc = g.optJSONObject("plaza")?.getJSONArray("rect")?.let { mid4(it) } ?: floatArrayOf(s0.x, s0.z)
        view("plan_above", (pzc[0] + s0.x) / 2f, (pzc[1] + s0.z) / 2f, sa.x, sa.z, heading(sa.x, sa.z) + 35f, 48f, OrbitCamera.DIST_MAX, 13f, "the downtown from above, by the square")
        // the river: the bridge nearest the start spot
        g.optJSONArray("bridges")?.let { b -> (0 until b.length()).map { mid4(b.getJSONObject(it).getJSONArray("rect")) }.minByOrNull { hypot(it[0] - s0.x, it[1] - s0.z) } }
            ?.let { at("plan_river", it[0], it[1], 70f, 24f, 700f, 13f, "the river crossing downtown") }
        // the riverwalk along the river's banks through the core
        g.optJSONArray("lanes")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.getString("id").startsWith("riverwalk:") } }?.let { l ->
            val c = mid4(l.getJSONArray("rect"))
            view("plan_riverwalk", c[0], c[1], 0f, 1f, 150f, 18f, 520f, 13f, "the riverwalk through the core")
        }
        // the models: the depot's neighbours (the feed mill, the works), the strip (the motel, the lanes), the radio station
        val glb = g.optJSONArray("glbLandmarks")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
        fun model(k: String) = glb.firstOrNull { it.getString("kind").contains(k) }?.let { mid4(it.getJSONArray("rect")) }
        (model("feed_mill") ?: model("foundry"))?.let { at("plan_rail", it[0], it[1], 35f, 22f, 760f, 13f, "the rail side: depot street's foot, the feed mill and works") }
        (model("roadside_motel") ?: model("starling_lanes") ?: model("junction_diner") ?: model("reel_time_video") ?: model("coin_laundromat"))?.let { at("plan_strip", it[0], it[1], 40f, 20f, 760f, 13f, "the highway strip") }
        model("radio_station")?.let { at("plan_radio", it[0], it[1], 20f, 14f, 420f, 13f, "the radio station") }
        model("county_courthouse")?.let { at("plan_court", it[0], it[1], 15f, 18f, 760f, 13f, "the county courthouse on its square") }
        // a street of the town's other homes: the row with most of them; from the street, then from a backyard
        val fillers = root.optJSONArray("shops")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optBoolean("filler") && it.optBoolean("home") }.map { it.getString("sprite") }.toSet() } ?: emptySet()
        town.rows.maxByOrNull { r -> r.buildings.count { it.sprite in fillers } }?.let { r ->
            val b = r.buildings.filter { it.sprite in fillers }.sortedBy { it.mid }.let { it[it.size / 2] }
            val p = r.pointAt(b.mid, 7f); val a = r.alongAt(b.mid); val n = r.normalAt(b.mid)
            view("plan_homes_front", p.x, p.z, a.x, a.z, heading(n.x, n.z) + 30f, 18f, 560f, 13f, "a street of the town's other homes")
            val lots = g.getJSONArray("lots").let { l -> (0 until l.length()).map { l.getJSONObject(it) } }
            lots.firstOrNull { it.getString("sprite") == b.sprite }?.let { l ->
                val c = mid4(l.getJSONArray("rect"))
                // (the backyard: beyond the house, out from the street)
                val bx = c[0] + n.x * 260f; val bz = c[1] + n.z * 260f
                view("plan_homes_back", bx, bz, -n.x, -n.z, heading(-n.x, -n.z) + 20f, 22f, 520f, 13f, "the same homes from their backyards")
            }
        }
    }

    // --- -Dat=x,z,yaw,pitch,dist[,hour]: the player at a world spot with the camera as a phone's game state has it (its
    // walkableTown wx, wz and orbit), e.g. to see a reported spot as the player saw it; -Dshots=at
    System.getProperty("at")?.split(",")?.mapNotNull { it.trim().toFloatOrNull() }?.takeIf { it.size >= 5 }?.let { a ->
        // (every landmark model up first: their solids and their cut are what such a spot is about)
        while (!ft.landmarkReport.all { !it.contains(": building") }) { run.build(a[0], a[1], 8f); Thread.sleep(1) }
        view("at_spot", a[0], a[1], 1f, 0f, a[2], a[3], a[4], a.getOrElse(5) { 17.6f }, "the spot asked for (-Dat)")
        // (-DlevelProbe=dx,dz,len: the height fields' spans every 2 units from the spot along (dx, dz), into levels.txt)
        System.getProperty("levelProbe")?.split(",")?.mapNotNull { it.trim().toFloatOrNull() }?.takeIf { it.size == 3 }?.let { q ->
            val sb = StringBuilder()
            var d = 0f
            while (d <= q[2]) {
                val x = a[0] + q[0] * d; val z = a[1] + q[1] * d
                sb.appendLine("%6.1f (%.1f, %.1f) floor@0 %.1f: %s".format(d, x, z, collision.floorAt(x, z, 0f), collision.levels.mapNotNull { it.describe(x, z) }.joinToString(" | ")))
                d += 2f
            }
            File(dir, "levels.txt").writeText(sb.toString())
        }
        // (-Dseq=frames,every,hx,hz[,camYawRate]: then play on from there as the app does, frame by frame at 60 Hz (no
        // unbudgeted prepare), the stick pushing the player towards world (hx, hz) (0,0: standing), the camera turned by
        // camYawRate degrees a second; every [every]th frame saved as seq_NNN.png, to see what changes between frames)
        System.getProperty("seq")?.split(",")?.mapNotNull { it.trim().toFloatOrNull() }?.takeIf { it.size >= 4 }?.let { q ->
            val hour = a.getOrElse(5) { 17.6f }
            val frames = q[0].toInt(); val every = q[1].toInt().coerceAtLeast(1)
            val ux = q[2]; val uz = q[3]; val yawRate = q.getOrElse(4) { 0f }
            val dt = 1f / 60f
            if (System.getProperty("seqNoShadow") != null) ft.view.isShadowingEnabled = false
            ft.sheetsOnWorker = true
            com.lifemax.rpg.feature.habits.presentation.scene3d.SeeThrough.ease = true
            val log = StringBuilder()
            for (f in 0 until frames) {
                val fx = OrbitCamera.fx; val fz = OrbitCamera.fz
                GameState.moveX = ux * -fz + uz * fx
                GameState.moveLane = -(ux * fx + uz * fz)
                if (yawRate != 0f) OrbitCamera.set(OrbitCamera.yaw + yawRate * dt, OrbitCamera.pitch, OrbitCamera.dist)
                TownPlay.step(dt)
                run.build(walk.wx, walk.wz, 4f)
                run.draw(walk, w.toFloat(), h.toFloat(), hour, clock, sheets)
                // (-DseqScales=1,.9,.8: the drawing resolution frame by frame, as the automatic resolution moves it)
                System.getProperty("seqScales")?.split(",")?.mapNotNull { it.trim().toFloatOrNull() }?.takeIf { it.isNotEmpty() }?.let { sc ->
                    val k = sc[f % sc.size]
                    ft.view.dynamicResolutionOptions = com.google.android.filament.View.DynamicResolutionOptions().apply {
                        enabled = k < 1f; minScale = k; maxScale = k; quality = com.google.android.filament.View.QualityLevel.HIGH; sharpness = .9f; homogeneousScaling = true }
                }
                // (-DseqCount=N: every Nth frame the GPU objects held and the process's resident memory, to catch a
                // per-frame creation: textures, buffers, the shared pages and their ranges)
                System.getProperty("seqCount")?.toIntOrNull()?.takeIf { it > 0 && f % it == 0 }?.let {
                    val st = com.lifemax.rpg.feature.habits.presentation.scene3d.BufferArena.stats()
                    val rss = runCatching { File("/proc/self/status").readLines().first { l -> l.startsWith("VmRSS") }.filter(Char::isDigit).toLong() / 1024 }.getOrDefault(-1L)
                    log.appendLine("count %04d player (%.1f, %.1f, y %.1f) textures %d buffers %d pages %d rss %d MB".format(f, walk.wx, walk.wz, walk.wy,
                        com.lifemax.rpg.feature.habits.presentation.scene3d.GpuAccount.entries().size,
                        com.lifemax.rpg.feature.habits.presentation.scene3d.GpuAccount.bufferEntries().size, st.pages, rss))
                }
                if (f % every == 0) {
                    val stage = stageSky(hour, w, h)
                    shooter.save(File(dir, "seq_%03d.png".format(f)), w, h, hour, emptyList(), skyRow = skyRow(hour, h), skyImg = stage)
                    log.appendLine("seq_%03d player (%.1f, %.1f, y %.1f) %s".format(f, walk.wx, walk.wz, walk.wy, OrbitCamera.describe()))
                }
                clock += dt
            }
            GameState.moveX = 0f; GameState.moveLane = 0f
            // (-DseqViews=yaw:pitch:dist;...: where the walk ended, from other sides, as seq_view_N.png)
            System.getProperty("seqViews")?.split(";")?.forEachIndexed { k, v ->
                val q2 = v.split(":").mapNotNull { it.trim().toFloatOrNull() }
                if (q2.size < 3) return@forEachIndexed
                OrbitCamera.set(q2[0], q2[1], q2[2])
                repeat(20) { TownPlay.step(dt); run.build(walk.wx, walk.wz, 4f); run.draw(walk, w.toFloat(), h.toFloat(), hour, clock, sheets); clock += dt }
                shooter.save(File(dir, "seq_view_$k.png"), w, h, hour, emptyList(), skyRow = skyRow(hour, h), skyImg = stageSky(hour, w, h))
                log.appendLine("seq_view_$k player (%.1f, %.1f, y %.1f) %s".format(walk.wx, walk.wz, walk.wy, OrbitCamera.describe()))
            }
            File(dir, "seq.txt").writeText(log.toString())
        }
    }
    // --- downtown: the tallest building with nobody in it (a tower behind the main street, else the town hall), from the
    // pavement in front of its block, at the camera's usual height, zoomed out, at night, and from across town (its far box
    // on the skyline); -Dshots=downtown renders only these
    // (-Dspot=x,z: the building nearest that point instead, so a town's years can be shot from the same place)
    val spot = System.getProperty("spot")?.split(",")?.mapNotNull { it.trim().toFloatOrNull() }?.takeIf { it.size == 2 }
    (if (spot != null) ft.slots.filter { (it.isBuilt || it.known) && town.road(it.row.road).name == "Main Street" }.minByOrNull { hypot(it.cx - spot[0], it.cz - spot[1]) }
     else ft.slots.filter { it.b.kind == com.lifemax.rpg.feature.habits.domain.PlacementKind.EXTRA && (it.isBuilt || it.known) }.maxByOrNull { it.cy })?.let { s ->
        val r = s.row
        val x = s.b.mid.coerceIn(40f, r.length - 40f)
        val p = r.pointAt(x, 7f); val a = r.alongAt(x); val n = r.normalAt(x)
        val inn = heading(n.x, n.z)
        report.appendLine("downtown: ${s.b.sprite} on ${r.id} (${town.road(r.road).name}), its middle ${"%.0f".format(s.cy)} up; ${ft.farReport}")
        view("downtown_street_13h", p.x, p.z, a.x, a.z, inn - 20f, 4f, OrbitCamera.DIST_MIN, 13f, "downtown, from the pavement")
        view("downtown_camera_13h", p.x, p.z, a.x, a.z, inn - 30f, 20f, 520f, 13f, "downtown, the camera's usual height")
        view("downtown_along_13h", p.x, p.z, a.x, a.z, heading(a.x, a.z), 14f, 600f, 13f, "downtown, along the main street")
        view("downtown_high_13h", p.x, p.z, a.x, a.z, inn + 30f, 50f, OrbitCamera.DIST_MAX, 13f, "downtown, zoomed out")
        view("downtown_dusk", p.x, p.z, a.x, a.z, inn - 30f, 16f, 600f, 19.3f, "downtown at dusk")
        view("downtown_night", p.x, p.z, a.x, a.z, inn - 30f, 16f, 600f, night, "downtown at night")
        // across town: the player 3500 units out on the far side, looking back at the centre
        val c = collision
        val cx = s.cx; val cz = s.cz
        val ox = (cx + (if (cx > (c.minX + c.maxX) / 2f) -3500f else 3500f)).coerceIn(c.minX + 400f, c.maxX - 400f)
        val oz = cz + 1200f
        val back = heading(cx - ox, cz - oz)
        view("downtown_skyline_13h", ox, oz, (cx - ox), (cz - oz), back, 6f, 300f, 13f, "the downtown from across town (far boxes)")
        view("downtown_skyline_dusk", ox, oz, (cx - ox), (cz - oz), back, 6f, 300f, 19.3f, "the downtown from across town at dusk")
        view("downtown_skyline_night", ox, oz, (cx - ox), (cz - oz), back, 6f, 300f, night, "the downtown from across town at night")
        view("downtown_wide_13h", ox, oz, (cx - ox), (cz - oz), back, 24f, OrbitCamera.DIST_MAX, 13f, "a wide view over the town, towards downtown")
    }
    // --- the pack's other buildings with nobody in them: the first of each kind (the town hall, the chapel, a car park)
    root.optJSONObject("graph")?.optJSONArray("extras")?.let { xs ->
        val seen = HashSet<String>()
        for (i in 0 until xs.length()) {
            val o = xs.getJSONObject(i); val kind = if (o.getString("id").contains(":lot:")) "stripshop" else o.getString("kind")
            if (!seen.add(kind)) continue
            val s = ft.slots.firstOrNull { it.b.sprite == o.getString("sprite") } ?: continue
            val r = s.row; val x = s.b.mid.coerceIn(40f, r.length - 40f)
            val p = r.pointAt(x, 7f); val a = r.alongAt(x); val n = r.normalAt(x)
            view("extra_${kind}_13h", p.x, p.z, a.x, a.z, heading(n.x, n.z) - 25f, 22f, 560f, 13f, "the ${o.getString("name")} (${s.b.sprite})")
            view("extra_${kind}_night", p.x, p.z, a.x, a.z, heading(n.x, n.z) + 20f, 16f, 520f, night, "the ${o.getString("name")} at night")
        }
    }
    // --- the civic avenue (the spawn): along it, and at the school across it
    run {
        val r = town.row(town.spawn.row)
        val p = r.pointAt(town.spawn.x, 7f); val a = r.alongAt(town.spawn.x); val n = r.normalAt(town.spawn.x)
        view("civic_behind_13h", p.x, p.z, a.x, a.z, heading(a.x, a.z), 24f, 520f, 13f, "the civic avenue (spawn), behind the player")
        view("civic_across_13h", p.x, p.z, a.x, a.z, heading(-n.x, -n.z), 22f, 600f, 13f, "the civic avenue, across the road")
        view("civic_back_night", p.x, p.z, a.x, a.z, heading(-a.x, -a.z) + 30f, 30f, 800f, night, "the civic avenue at night, looking back")
    }
    // --- a Heights backyard: the player on the back lawn, the camera behind them looking at the house's back, then above
    run {
        val y = yards.of("b69") ?: yards.yards.first { it.neighbourhood == "heights" && it.back != null }
        val r = town.row(y.row)
        val b = r.buildings.first { it.sprite == y.sprite }
        val n = r.normalAt(b.mid)
        val back = y.back ?: y.parcel
        val px = (back[0] + back[2]) / 2f; val pz = (back[1] + back[3]) / 2f
        view("heights_yard_13h", px, pz, -n.x, -n.z, heading(-n.x, -n.z), 22f, 420f, 13f, "a Heights backyard (${y.sprite}), at the house's back")
        view("heights_yard_side_13h", px, pz, -n.x, -n.z, heading(-n.x, -n.z) + 70f, 28f, 600f, 13f, "a Heights backyard, along the backs")
        view("heights_yard_above_13h", px, pz, -n.x, -n.z, heading(n.x, n.z), 52f, 1100f, 13f, "the Heights backyards from above")
        view("heights_yard_night", px, pz, -n.x, -n.z, heading(-n.x, -n.z) + 20f, 26f, 520f, night, "a Heights backyard at night")
    }
    // --- over the river: on a bridge, looking up and down the river
    town.bridges.firstOrNull()?.let { br ->
        val bx = (br.rect[0] + br.rect[2]) / 2f; val bz = (br.rect[1] + br.rect[3]) / 2f
        // (a bridge running east-west crosses a river running north-south)
        val (hx, hz) = if (br.horizontal) 1f to 0f else 0f to 1f
        val river = if (br.horizontal) 0f else 90f
        view("river_up_13h", bx, bz, hx, hz, river, 22f, 650f, 13f, "on a bridge, up the river")
        view("river_down_13h", bx, bz, hx, hz, river + 180f, 34f, 900f, 13f, "on a bridge, down the river")
        view("river_night", bx, bz, hx, hz, river + 150f, 26f, 700f, night, "on a bridge at night")
    }
    // --- by the cinema: in front of it, three-quarter, at night
    cinema?.let { c -> ft.slots.firstOrNull { it.b.sprite == c.sprite } }?.let { s ->
        val r = s.row
        val x = s.b.mid.coerceIn(40f, r.length - 40f)
        val p = r.pointAt(x, 7f); val a = r.alongAt(x); val n = r.normalAt(x)
        view("cinema_front_13h", p.x, p.z, a.x, a.z, heading(n.x, n.z), 12f, 520f, 13f, "in front of the cinema")
        view("cinema_quarter_13h", p.x, p.z, a.x, a.z, heading(n.x, n.z) - 40f, 20f, 700f, 13f, "the cinema, three-quarter")
        view("cinema_quarter_dusk", p.x, p.z, a.x, a.z, heading(n.x, n.z) - 40f, 20f, 700f, 19.3f, "the cinema, three-quarter at dusk")
        view("cinema_night", p.x, p.z, a.x, a.z, heading(n.x, n.z) + 25f, 16f, 600f, night, "the cinema at night")
    }
    // --- the hand-made landmark models (docs/LANDMARKS_GLB.md): each from the street at a person's height, three-quarter,
    // from behind, from above and at night; the railroad's model depot from its street side, its platform and above
    report.appendLine("landmark models: " + ft.landmarkReport.joinToString("; "))
    for (sp in landmarkAssets.placed.map { it.sprite } + "rail-depot") {
        val f = ft.landmarkFrame(sp) ?: continue
        val kind = if (sp == "rail-depot") "depot" else landmarkAssets.placed.first { it.sprite == sp }.kind.removePrefix("landmark_glb_")
        val spec = landmarkAssets.catalogue.firstOrNull { it.kind == (if (sp == "rail-depot") "landmark_glb_premier_railroad_depot" else "landmark_glb_$kind") } ?: continue
        val ox = f[0]; val oz = f[1]; val xx = f[2]; val xz = f[3]; val zx = f[4]; val zz = f[5]
        val lot = ft.landmarkLot(sp) ?: continue
        // (the lot's front edge, its back, its middle along the model's z; the player before its front, behind its back)
        val zf = lot[3]; val zb = lot[1]; val xm = (lot[0] + lot[2]) / 2f
        fun at(x: Float, z: Float) = floatArrayOf(ox + xx * x + zx * z, oz + xz * x + zz * z)
        val fwd = heading(-zx, -zz); val back = heading(zx, zz)
        val n = "glb_$kind${System.getProperty("glbTag") ?: ""}"
        val pf = at(xm, zf + 90f); val pb = at(xm, zb - 90f)
        view("${n}_front_13h", pf[0], pf[1], xx, xz, fwd, 6f, 420f + (lot[2] - lot[0]) * .6f, 13f, "$kind, from the street at a person's height")
        view("${n}_quarter_13h", pf[0], pf[1], xx, xz, fwd - 35f, 24f, 700f + (lot[2] - lot[0]) * .6f, 13f, "$kind, three-quarter")
        view("${n}_back_13h", pb[0], pb[1], xx, xz, back, 35f, 650f + (lot[2] - lot[0]) * .5f, 13f, "$kind, from behind and above")
        view("${n}_above_13h", pf[0], pf[1], xx, xz, fwd + 25f, 62f, OrbitCamera.DIST_MAX, 13f, "$kind, from above")
        view("${n}_night", pf[0], pf[1], xx, xz, fwd + 20f, 12f, 600f + (lot[2] - lot[0]) * .5f, night, "$kind at night")
        // (the funeral home: the street at a person's height close in, day and night, and its sign, a low double-faced board at
        // x 5.6..9.3 m whose front face is at z 1.56 m, seen by the player standing before it)
        // (a street-wall shop: the sidewalk before its facade, the neighbours either side in the frame)
        if (kind.startsWith("five_and_dime") || kind.startsWith("barber_") || kind.startsWith("salon_") || kind.startsWith("pawn_shop")) {
            val pw = at(xm, zf + 50f)
            view("${n}_wall_13h", pw[0], pw[1], xx, xz, fwd, 5f, 230f, 13f, "$kind, from the Main Street sidewalk before its facade")
        }
        if (kind == "funeral_home") {
            val u = com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkKit.UNITS_PER_METRE
            val ps = at(xm, zf + 60f); val sg = at(11.4f * u, 4.2f * u)
            view("${n}_street_13h", ps[0], ps[1], xx, xz, fwd, 4f, 300f, 13f, "$kind, from the street, close, at a person's height")
            view("${n}_street_night", ps[0], ps[1], xx, xz, fwd, 4f, 300f, night, "$kind at night, from the street, close")
            view("${n}_sign_13h", sg[0], sg[1], xx, xz, fwd, 2f, 95f, 13f, "$kind, the sign")
            view("${n}_sign_night", sg[0], sg[1], xx, xz, fwd, 2f, 95f, night, "$kind, the sign at night")
        }
    }
    // --- the stores (docs/STORES.md), each year of -DstoresYears (default 1955, 1975, 1995, 2025): the player before each
    // store's door (or the plot's front, before it opens) facing it, the camera low behind them (as near the player's
    // height as it goes), at its usual height by day and at night, and up high over the edge of town
    run {
        val years = (System.getProperty("storesYears") ?: "1955,1975,1995,2025").split(",").mapNotNull { it.trim().toIntOrNull() }
        if (only != null && only.none { it.startsWith("store") }) return@run
        for (yr in years) {
            ft.storeYear = yr
            while (ft.pending > 0) { if (ft.buildNearest(0f, 0f, 8f) == 0) Thread.sleep(1) }
            ft.finishLevels()
            report.appendLine("stores in $yr: " + ft.storeReport.joinToString("; ") + "; collision + ${collision.extraSize} shapes, ${collision.extraDoors.size} store doors")
            for (st in storeSites) {
                val s = ft.slots.firstOrNull { it.b.sprite == st.sprite } ?: continue
                val r = s.row
                val n = r.normalAt(s.b.mid)
                val inn = heading(n.x, n.z)
                val door = collision.extraDoors.firstOrNull { it.sprite == st.sprite }
                val back = when (st.site?.site ?: st.info?.kind?.name?.lowercase()) { "generalstore", "general" -> 70f; "market" -> 260f; "supermarket" -> 420f; else -> 620f }
                val (px, pz) = if (door != null) (door.x + door.outX * back) to (door.z + door.outZ * back)
                    else r.pointAt(s.b.mid, 6f).let { it.x to it.z }
                val tag = st.landmark.removePrefix("landmark:")
                for (h in listOf(13f, night)) {
                    if (h != 13f && door == null && st.at(yr, h, ft.weekday) == null) continue
                    val hn = if (h == 13f) "13h" else "night"
                    while (!ft.parkStores(h)) Thread.sleep(2)
                    view("store_${tag}_${yr}_low_$hn", px, pz, n.x, n.z, inn, 10f, OrbitCamera.DIST_MIN, h, "$tag in $yr, from the player's height")
                    view("store_${tag}_${yr}_$hn", px, pz, n.x, n.z, inn - 25f, 24f, 560f, h, "$tag in $yr, the camera's usual height")
                }
                // behind the main block, the camera on its far side: the block between is cut away
                val bk = ft.storeBlock(st.sprite)
                if (yr == years.last() && bk != null && door != null) {
                    val bx = (bk[0] + bk[2]) / 2f; val bz = (bk[1] + bk[3]) / 2f
                    // (the block's back: half its depth along the plot's way in, past the middle)
                    val half = abs((bk[2] - bk[0]) * n.x) / 2f + abs((bk[3] - bk[1]) * n.z) / 2f
                    val qx = bx + n.x * (half + 90f); val qz = bz + n.z * (half + 90f)
                    while (!ft.parkStores(13f)) Thread.sleep(2)
                    view("store_${tag}_${yr}_behind", qx, qz, -n.x, -n.z, inn, 22f, 620f, 13f, "$tag in $yr, behind it, the camera over the block (cut away)")
                    view("store_${tag}_${yr}_back", qx, qz, n.x, n.z, inn + 180f, 22f, 620f, 13f, "$tag in $yr, its back")
                }
                if (st.site?.site == "superstore" || st.site?.site == "supermarket") {
                    while (!ft.parkStores(13f)) Thread.sleep(2)
                    view("store_${tag}_${yr}_high", px, pz, n.x, n.z, inn + 35f, 50f, OrbitCamera.DIST_MAX, 13f, "$tag in $yr, zoomed out")
                }
            }
        }
        ft.storeYear = 0
        while (ft.pending > 0) { if (ft.buildNearest(0f, 0f, 8f) == 0) Thread.sleep(1) }
        ft.finishLevels()
    }
    // --- Woodside: behind the player on a quiet street
    run {
        val r = majority("woodside")
        val x = mid(r)
        val p = r.pointAt(x, 7f); val a = r.alongAt(x)
        view("woodside_behind_13h", p.x, p.z, a.x, a.z, heading(a.x, a.z) - 20f, 26f, 560f, 13f, "a Woodside street, behind the player")
    }
    // --- the checks: views where the camera met trouble before (gates, a camera behind a fence or in a block, people from
    // four sides, the widest views, the world's edge), and a back yard street from a low camera
    run {
        for (sp in listOf("b8", "b69", "b51", "b80")) { val y = yards.of(sp) ?: continue
            val g = y.items.firstOrNull { it.kind == "sideGate" } ?: y.items.firstOrNull { it.kind == "gate" } ?: continue
            val gx = (g.rect[0] + g.rect[2]) / 2f; val gz = (g.rect[1] + g.rect[3]) / 2f
            view("chk_gate_$sp", gx, gz, g.faceX, g.faceZ, heading(-g.faceX, -g.faceZ) + 35f, 22f, 200f, 13f, "in the gate of $sp")
            view("chk_gate_${sp}_b", gx, gz, g.faceX, g.faceZ, heading(g.faceX, g.faceZ) + 35f, 22f, 200f, 13f, "in the gate of $sp, from the other side")
        }
        val r = town.rows.filter { town.road(it.road).name == "Main Street" }.maxByOrNull { it.buildings.size }!!
        val x = mid(r)
        val p = r.pointAt(x, 7f); val a = r.alongAt(x); val n = r.normalAt(x)
        val along = heading(a.x, a.z); val fronts = heading(n.x, n.z)
        for (k in 0 until 4) view("chk_people_%d".format(k * 90), p.x, p.z, a.x, a.z, along + k * 90f, 16f, 220f, 13f, "people from four sides, close")
        for (k in 0 until 4) view("chk_wide_main_%d".format(k * 90), p.x, p.z, a.x, a.z, along + k * 90f + 45f, 30f, OrbitCamera.DIST_MAX, 13f, "Main Street, widest")
        view("chk_shopbacks", p.x, p.z, a.x, a.z, fronts, 22f, 700f, 13f, "Main Street, from behind the shops")
        val y = yards.of("b69") ?: yards.yards.first { it.neighbourhood == "heights" && it.back != null }
        val back = y.back ?: y.parcel
        val px = (back[0] + back[2]) / 2f; val pz = (back[1] + back[3]) / 2f
        view("chk_heights_close_fence", px, pz, 0f, -1f, 0f, 10f, 140f, 13f, "low and close in a back yard")
        view("chk_heights_close_fence2", px, pz, 0f, -1f, 180f, 10f, 140f, 13f, "low and close in a back yard, the other way")
        for (k in 0 until 4) view("chk_wide_heights_%d".format(k * 90), px, pz, 0f, -1f, k * 90f + 45f, 30f, OrbitCamera.DIST_MAX, 13f, "the Heights, widest")
        // a back yard street from a low camera: along the backs of a row of Heights houses
        val hr = town.row(y.row); val hb = hr.buildings.first { it.sprite == y.sprite }; val ha = hr.alongAt(hb.mid)
        // (stood in the open, away from the yard's trees and sheds: under one the camera would cut the yard's tall things away)
        var bx = px; var bz = pz; var bd = -1f
        var sx = back[0] + 12f
        while (sx < back[2] - 12f) { var sz = back[1] + 12f; while (sz < back[3] - 12f) {
            val d = y.items.filter { com.lifemax.rpg.feature.habits.presentation.scene3d.FilamentTown.tallHeight(it.kind, it.zone) > 0f }
                .minOfOrNull { val r = it.rect; kotlin.math.hypot(maxOf(r[0] - sx, 0f, sx - r[2]), maxOf(r[1] - sz, 0f, sz - r[3])) } ?: 999f
            if (d > bd && !collision.blocked(sx, sz, 8f)) { bd = d; bx = sx; bz = sz }
            sz += 8f }; sx += 8f }
        view("backyards_low_13h", bx, bz, ha.x, ha.z, heading(ha.x, ha.z), 11f, 320f, 13f, "along the back yards, a low camera")
        val c = collision
        view("chk_world_edge", c.minX + 300f, (c.minZ + c.maxZ) / 2f, -1f, 0f, 270f, 20f, 700f, 13f, "the world's west edge")
        view("chk_world_edge_n", (c.minX + c.maxX) / 2f, c.minZ + 300f, 0f, -1f, 0f, 20f, 700f, 13f, "the world's north edge")
    }
    // --- the owner's screenshots of build 0.1.338 (the S25, 2026-11-19), 17:36 in summer. gamma was exported again with
    // the stores since, so the spots are the same kind of place in today's town: a street looking along it at the
    // horizon, a clearing in the woods, and outside a back hedge of a lot with a shed, trampoline and play set
    // (medium:80,101, its hedge on the west: the shots' cameras turned by the same 90 degrees)
    view("fb_skyline", 4059.2f, 3885.7f, -.3858f, -.9226f, 11.7f, 13.43f, 420f, 17.6f, "005025: a street, the horizon (no painted city)")
    view("fb_trees", 2200f, 7000f, .3657f, .9307f, -139.8f, 10f, 420f, 17.6f, "005324: in the woods (trees and bushes)")
    view("fb_trees_close", 2200f, 7000f, .3657f, .9307f, -139.8f, 12f, 200f, 17.6f, "005324's woods, zoomed in")
    view("fb_yard_a", 2576f, 3674f, 0f, -1f, 124.1f, 22.43f, 420f, 17.6f, "005359: outside a back hedge, the yard behind the player")
    view("fb_yard_b", 2576f, 3674f, 0f, -1f, 98.5f, 20.1f, 420f, 17.6f, "005414: the same spot, the camera turned 25 degrees")
    view("fb_yard_c", 2576f, 3674f, 0f, -1f, 150f, 22f, 420f, 17.6f, "the same spot, the camera turned the other way")
    // --- the bicycle (docs/TOWN.md "Open world: the bicycle"): each era's bike from several sides, leaning into a turn,
    // at night with its lamp, then a ride through the game loop (TownPlay.step at 60 Hz) rendered each second
    run {
        if (only == null || only.none { it.startsWith("bike") }) return@run
        val r = town.rows.filter { town.road(it.road).name == "Main Street" }.maxByOrNull { it.buildings.size }!!
        val x = mid(r)
        // (in the road's near lane, clear of the pavement's passers and furniture)
        val p = r.pointAt(x + 60f, 34f); val a = r.alongAt(x)
        val along = heading(a.x, a.z)
        fun bikeView(name: String, style: com.lifemax.rpg.feature.habits.presentation.scene.BikeStyle, yaw: Float, pitch: Float, dist: Float, hour: Float,
                     lean: Float = 0f, steer: Float = 0f, crank: Float = .6f, speed: Float = 0f) {
            if (!want(name)) return
            if (walk.riding) walk.dismount()
            walk.placeAt(p.x, p.z, a.x, a.z)
            check(walk.mount()) { "no room for the bike at $name" }
            val b = walk.bike
            b.style = style; b.lean = lean; b.steer = steer; b.crank = crank; b.wheel = crank * 2.4f; b.speed = speed; b.pedalling = speed > 0f
            OrbitCamera.set(yaw, pitch, dist); OrbitCamera.follow(walk.wx, walk.wz)
            val pose0 = run.orbit(walk, w.toFloat(), h.toFloat(), clock)
            ft.prepare(pose0)
            run.draw(walk, w.toFloat(), h.toFloat(), hour, clock, sheets)
            shooter.save(File(dir, "$name.png"), w, h, hour, emptyList(), skyRow = skyRow(hour, h), skyImg = stageSky(hour, w, h))
            report.appendLine("%-26s the %s on Main Street; %s".format(name, style.label, OrbitCamera.describe()))
            println("  $name")
            clock += 1f
        }
        for (st in com.lifemax.rpg.feature.habits.presentation.scene.BikeStyle.values()) {
            val n = st.name.lowercase()
            bikeView("bike_${n}_side", st, along + 90f, 8f, 140f, 13f)
            bikeView("bike_${n}_quarter", st, along + 40f, 14f, 140f, 13f)
            bikeView("bike_${n}_behind", st, along, 12f, 150f, 13f)
            bikeView("bike_${n}_front", st, along + 180f, 10f, 140f, 13f)
            bikeView("bike_${n}_above", st, along + 120f, 50f, 160f, 13f)
        }
        val st0 = walk.bike.style
        bikeView("bike_lean_behind", st0, along, 12f, 190f, 13f, lean = .45f, steer = -.25f, speed = 250f)
        bikeView("bike_lean_quarter", st0, along + 30f, 14f, 170f, 13f, lean = -.4f, steer = .2f, speed = 250f)
        bikeView("bike_night", st0, along + 60f, 14f, 200f, night)
        bikeView("bike_street", st0, along + 25f, 20f, 480f, 17.6f)
        if (want("bike_ride")) {
            if (walk.riding) walk.dismount()
            walk.placeAt(p.x, p.z, a.x, a.z)
            OrbitCamera.set(along, 13f, 190f); OrbitCamera.follow(walk.wx, walk.wz)
            run.orbit(walk, w.toFloat(), h.toFloat(), clock)
            TownPlay.toggleBike()
            val dt = 1f / 60f
            var t = 0f
            for (frame in 1..(60 * 8)) {
                // a full push up the street (the sprint after 0.8 s), a gentle bend out towards the middle of the road, then let go: it coasts
                val tt = frame * dt
                GameState.moveX = if (tt in 2.5f..3.3f) .4f else 0f
                GameState.moveLane = if (tt < 4.5f) -1f else 0f
                TownPlay.step(dt)
                clock += dt
                run.orbit(walk, w.toFloat(), h.toFloat(), clock)
                if (frame % 60 == 0) {
                    val pose0 = run.orbit(walk, w.toFloat(), h.toFloat(), clock)
                    ft.prepare(pose0)
                    run.draw(walk, w.toFloat(), h.toFloat(), 13f, clock, sheets)
                    val name = "bike_ride_%02ds".format(frame / 60)
                    shooter.save(File(dir, "$name.png"), w, h, 13f, emptyList(), skyRow = skyRow(13f, h), skyImg = stageSky(13f, w, h))
                    report.appendLine("%-26s %.0f units/s, lean %.0f deg, sprint %s, at (%.0f, %.0f)".format(name, walk.bike.speed, Math.toDegrees(walk.bike.lean.toDouble()), walk.bike.sprinting, walk.wx, walk.wz))
                    println("  $name")
                }
                t += dt
            }
            GameState.moveX = 0f; GameState.moveLane = 0f
            TownPlay.dismount("preview")
        }
        if (walk.riding) walk.dismount()
    }
    // --- the owner's screenshots of build 0.1.367 (the S25, 2026-11-19, 17:36 in summer): the same spots and cameras
    view("fb2_crowd", 5038.5f, 2519.9f, -1f, .008f, 89.54f, 11.23f, 420f, 17.6f, "015754: King Street, a quiet street of houses at 5:36 pm")
    view("fb2_crowd_main", 5038.5f, 2519.9f, -1f, .008f, 89.54f, 30f, 900f, 17.6f, "015754's spot from high up")
    view("fb2_chapel", 11557f, 5279f, .9155f, .4023f, -18.99f, 10f, 420f, 17.6f, "015958: Cornerstone Chapel (the 1960s market reused)")
    view("fb2_chapel_close", 11557f, 5279f, .9155f, .4023f, -18.99f, 14f, 200f, 17.6f, "015958's chapel, zoomed in")
    view("fb2_store_side", 14255f, 6145.8f, -.3415f, .9399f, -62.78f, 10f, 420f, 17.6f, "020039: the shut supermarket from the side")
    view("fb2_store_back", 14087.6f, 5816.6f, -.7994f, .6007f, -119.62f, 10f, 420f, 17.6f, "020054: the supermarket's back and side")
    view("fb2_store_high", 14087.6f, 5816.6f, -.7994f, .6007f, -119.62f, 35f, 700f, 13f, "020054's spot from above, noon")
    view("fb2_woods", 18926.5f, 5174.3f, .564f, .8258f, -20.7f, 15.14f, 420f, 17.6f, "020240: in the woods (the player must show)")
    view("fb2_woods_low", 18926.5f, 5174.3f, .564f, .8258f, 60f, 10f, 300f, 17.6f, "020240's woods, another way, low")
    // build 0.1.367 (the S25, 2026-11-19, 17:36 summer): the horizon's thin bright line and streaks (GPU p50 22 ms there),
    // and the forest's trees cut in half far off
    view("fb_horizon", 3219.6f, 4148.5f, 0f, -1f, 23.38f, 11.85f, 420f, 17.6f, "015705: Church Street, the horizon")
    view("fb_trees_half", 14316.6f, 5916.4f, 0f, 1f, 153.57f, 10f, 420f, 17.6f, "020102: by the woods, trees far off")
    // build 0.1.385 (the S25, 2026-11-19, 17:36 summer): stutter, things faded as the player walks by, the camera in a
    // tower, ghostly mid-distance buildings and half-drawn far trees; the same spots and cameras (-Dshots=fb3)
    view("fb3_053921", 12564.6f, 5807.3f, -0.7840f, -0.6208f, -60.70f, 10.00f, 420f, 17.6f, "053921: Main Street, trees far off half drawn, pale towers")
    view("fb3_054003", 10538.4f, 4790.9f, -0.8802f, 0.4746f, -24.51f, 12.94f, 420f, 17.6f, "054003: Main Street, everything walked by fades out")
    view("fb3_054054", 9291.6f, 5065.1f, 0.1848f, -0.9828f, -142.58f, 10.48f, 420f, 17.6f, "054054: Wharf Road, the camera inside a tower")
    view("fb3_054115", 8619.4f, 4963.3f, -0.9946f, -0.1038f, -84.08f, 12.09f, 420f, 17.6f, "054115: Main Street, texture glitching (mid-distance towers)")
    view("fb3_054136", 8622.1f, 4969.3f, 0.2605f, 0.9655f, -84.08f, 12.09f, 420f, 17.6f, "054136: Main Street")
    // build 0.1.389 (the S25, 120 Hz, GPU p50 46-48 ms there): King Street by a facade-kit tower, on the bike (the ride's
    // camera stands back: 546 for a 420 zoom), the cone cutting the tower beside the player (-Dshots=fb4)
    view("fb4_061255", 3471.5f, 2636.4f, 0.0571f, 0.9984f, -120.90f, 27.41f, 546f, 17.6f, "061255: King Street, the tower beside the player fading")
    view("fb4_061348", 3553.0f, 2648.5f, 0.9953f, -0.0965f, -168.12f, 10.77f, 546f, 17.6f, "061348: King Street, the tower and the green house")
    // build 0.1.402 (the S25, GPU p50 28-41 ms in ordinary views; farms and downtowns in): -Dshots=fb5
    view("fb5_075343", 7491.5f, 6740.2f, 0.9860f, -0.1670f, 80.47f, 13.34f, 437f, 9.12f, "075343: Spring Lane, the line on the horizon")
    view("fb5_075402", 7491.5f, 6740.2f, 0.9860f, -0.1670f, -50.63f, 10.86f, 720f, 0.0f, "075402: Spring Lane at midnight, the high rises")
    view("fb5_075443", 8565.3f, 6579.6f, 0.1582f, 0.9874f, 17.83f, 10.31f, 720f, 0.0f, "075443: Spring Lane, the trees at the town's edge")
    view("fb5_075528", 9078.7f, 6759.1f, -0.9001f, 0.4358f, 69.44f, 11.60f, 720f, 0.0f, "075528: Spring Lane, buildings cut too easily")
    view("fb5_074956", 1407.5f, 3857.5f, 0.5477f, -0.8367f, -98.07f, 14.15f, 568f, 19.17f, "074956: Larch Road at dusk")
    view("fb3_054141", 8615.3f, 4968.6f, -0.9973f, -0.0729f, -84.08f, 12.09f, 420f, 17.6f, "054141: Main Street")
    // build 0.1.389 (the S25, 2026-11-19, 17:36 summer): King Street in Oak Heights, the apartment block whose ground floor was
    // jumbled (and too tall for the street) and the terraced house all one green next to it
    view("fb3_king", 3553f, 2648.5f, .9953f, -.0965f, -168.12f, 10.77f, 420f, 17.6f, "061348: King Street, Oak Heights")
    view("fb3_king_close", 3553f, 2648.5f, .9953f, -.0965f, -168.12f, 8f, 220f, 17.6f, "061348's spot, zoomed in")
    view("fb3_king_high", 3553f, 2648.5f, .9953f, -.0965f, -150f, 30f, 700f, 13f, "061348's spot from above, noon")
    view("fb3_haddad", 8488.4f, 2446.3f, -.0828f, .9966f, -39.82f, 10f, 420f, 17.6f, "074755: King Street, Riverside (the Haddads' block)")
    view("fb3_larch", 1407.5f, 3857.5f, .5477f, -.8367f, -98.07f, 14.15f, 437f, 19.2f, "074956: Larch Road, the terraced house across the road")
    view("fb3_trailers", 4617.1f, 7643.4f, -.9836f, -.1802f, 69.24f, 10f, 437f, 13f, "075232: Elm Street, Old Town, the mobile homes")
    view("fb3_king_back", 3553f, 2648.5f, .9953f, -.0965f, 20f, 18f, 600f, 13f, "061348's spot, the other way (the backs)")
    if (worldSite != null && world != null) WorldPreview.shots(dir, ft, shooter, town, worldSite, world, report, ::want, w, h)
    kotlin.run {
        // (idea 4, docs/NOVEL_OPTIMIZATIONS.md: the material instances alive, as the primitives of every renderable in the scene: each wears its own)
        val rm = engine.renderableManager; var rend = 0; var prims = 0L
        ft.scene.forEach { e -> val ri = rm.getInstance(e); if (ri != 0) { rend++; prims += rm.getPrimitiveCount(ri) } }
        report.appendLine("material instances in the scene (one a primitive): %d over %d renderables".format(prims, rend))
    }
    report.appendLine(com.lifemax.rpg.feature.habits.presentation.scene3d.Cutouts.report())
    com.lifemax.rpg.feature.habits.presentation.scene3d.TownVertex.let { tv -> report.appendLine("tangent frames exact in 8 bits: %d of %d quantised vertices".format(tv.tangentExact8.get(), tv.quantized.get())) }
    File(dir, "open.txt").writeText(report.toString())
    print(report)
    if (only != null && "bench" in only) {
        // (as the app plays: sheets read on workers, the cone eased)
        ft.sheetsOnWorker = true
        ft.nightBudgetNs = FilamentTown.NIGHT_BUDGET_NS
        com.lifemax.rpg.feature.habits.presentation.scene3d.SeeThrough.ease = true
        benchWalk(dir, walk, run, ft, shooter, sheets)
        // (the shared buffer pages after a walk and a ride of chunks, cells and yards coming and going: how fragmented)
        com.lifemax.rpg.feature.habits.presentation.scene3d.BufferArena.stats().let { a ->
            File(dir, "bench.txt").appendText("buffer pages after the bench: %d pages, %.1f MB held, %.1f MB in use\n".format(a.pages, a.bytes / 1048576.0, a.used / 1048576.0)) }
    }
    if (want("walk")) scriptedWalk(dir, town, yards, walk, run, shooter, sheets, w, h, events)
    TownPlay.stop()
    TownPlay.onEvent = null
    shooter.destroy()
    ft.destroy()
    com.lifemax.rpg.feature.habits.presentation.scene3d.BufferArena.release(engine)
    engine.destroy()
}

/**
 * 30 s of play through the game loop as the app runs it ([TownPlay.step] at 60 Hz, then [TownRun.draw]): an autopilot
 * pushes the stick towards each waypoint relative to where the camera looks (the spawn's street, the front of the
 * nearest house with a side gate, its gate, its back yard, back out and across the road), and the "player" turns the
 * camera only by drags and pinches at set times. Every 5 s a render; walk.txt has the path each half second, the
 * breadcrumbs, and whether the camera's yaw ever changed between drags.
 */
private fun scriptedWalk(
    dir: File, town: Town, yards: TownYards, walk: com.lifemax.rpg.feature.habits.presentation.scene.OpenWalk, run: TownRun, shooter: Shooter,
    sheets: List<androidx.compose.ui.graphics.ImageBitmap>, w: Int, h: Int, events: MutableList<String>,
) {
    val log = StringBuilder()
    events.clear()
    walk.spawn()
    val sp = town.row(town.spawn.row)
    val a0 = sp.alongAt(town.spawn.x)
    OrbitCamera.reset(walk.wx, walk.wz, OrbitCamera.headingOf(a0.x, a0.z))
    // the nearest house with a side gate and a back yard
    val y = yards.yards.filter { it.back != null && it.items.any { i -> i.kind == "sideGate" } }
        .minByOrNull { val c = it.house; kotlin.math.hypot((c[0] + c[2]) / 2f - walk.wx, (c[1] + c[3]) / 2f - walk.wz) }!!
    val r = town.row(y.row)
    val b = r.buildings.first { it.sprite == y.sprite }
    val gate = y.items.first { it.kind == "sideGate" }.rect
    val back = y.back!!
    val n = r.normalAt(b.mid)
    fun mid(q: FloatArray) = ((q[0] + q[2]) / 2f) to ((q[1] + q[3]) / 2f)
    val front = r.pointAt(b.mid, 6f)
    val (gx, gz) = mid(gate)
    // (just outside the gate on the front's side, then just inside it on the back's)
    val goals = ArrayList<Pair<String, Pair<Float, Float>>>()
    goals += "the pavement in front of ${y.sprite}" to (front.x to front.z)
    goals += "outside its side gate" to ((gx - n.x * 30f) to (gz - n.z * 30f))
    goals += "through the gate" to ((gx + n.x * 30f) to (gz + n.z * 30f))
    goals += "its back yard" to mid(back)
    goals += "back through the gate" to ((gx - n.x * 30f) to (gz - n.z * 30f))
    val across = r.pointAt(b.mid + 120f, -6f)
    goals += "across the road" to (across.x to across.z)
    // (each on the clear spot nearest it: a yard's middle may hold a shed)
    val clear = FloatArray(2)
    for (i in goals.indices) { val (nm, q) = goals[i]; walk.collision.nearestClear(q.first, q.second, com.lifemax.rpg.feature.habits.presentation.scene.OpenWalk.RADIUS + 2f, clear); goals[i] = nm to (clear[0] to clear[1]) }
    log.appendLine("scripted walk: from the spawn (${walk.wx.toInt()}, ${walk.wz.toInt()}) on ${walk.row.id} to ${y.sprite}'s back yard by its side gate (${gx.toInt()}, ${gz.toInt()}) and across the road")
    val dt = 1f / 60f
    var t = 0f
    var clock = 1000f
    var g = 0
    var lastYaw = OrbitCamera.yaw
    var turnedAlone = 0
    var maxStep = 0f
    var inside = 0
    // the player's own camera input: (start s, end s, drag px x, drag px y, pinch a frame)
    class Input(val from: Float, val to: Float, val dx: Float, val dy: Float, val pinch: Float)
    val inputs = listOf(
        Input(3f, 3.6f, 22f, 0f, 1f),      // turn right a little
        Input(9f, 9.5f, -30f, 0f, 1f),     // turn left
        Input(14f, 14.6f, 0f, 9f, 1f),     // look further down
        Input(18f, 18.5f, 0f, 0f, .985f),  // zoom out (fingers together: a pinch under 1)
        Input(23f, 23.8f, 40f, -4f, 1f),   // round to the side
    )
    var shot = 0
    var path: List<Pair<Float, Float>>? = null
    var planned = 0f
    var pi = 0
    while (t <= 30.001f) {
        val inp = inputs.firstOrNull { t >= it.from && t < it.to }
        if (inp != null) { if (inp.dx != 0f || inp.dy != 0f) OrbitCamera.drag(inp.dx, inp.dy, w.toFloat(), h.toFloat()); if (inp.pinch != 1f) OrbitCamera.pinch(inp.pinch) }
        // the stick: towards the goal, relative to the camera
        val goal = goals.getOrNull(g)
        if (goal != null) {
            val dx = goal.second.first - walk.wx; val dz = goal.second.second - walk.wz
            val d = kotlin.math.hypot(dx, dz)
            if (d < 14f) { log.appendLine("  %5.1f s reached %s at (%.0f, %.0f)".format(t, goal.first, walk.wx, walk.wz)); g++; path = null }
            else {
                // (as a player steers: round what stands in the way, by a path over the collision's grid, replanned each second)
                if (path == null || t - planned > 1f) { path = plan(walk.collision, walk.wx, walk.wz, goal.second.first, goal.second.second); planned = t; pi = 0 }
                val pp = path!!
                while (pi < pp.size - 1 && kotlin.math.hypot(pp[pi].first - walk.wx, pp[pi].second - walk.wz) < 10f) pi++
                val (tx, tz) = if (pp.isEmpty()) goal.second else pp[pi]
                val ex = tx - walk.wx; val ez = tz - walk.wz
                val e = kotlin.math.hypot(ex, ez).coerceAtLeast(1e-3f)
                val ux = ex / e; val uz = ez / e
                val fx = OrbitCamera.fx; val fz = OrbitCamera.fz
                GameState.moveX = ux * -fz + uz * fx
                GameState.moveLane = -(ux * fx + uz * fz)
            }
        }
        if (goals.getOrNull(g) == null) { GameState.moveX = 0f; GameState.moveLane = 0f }
        val px = walk.wx; val pz = walk.wz
        TownPlay.step(dt)
        maxStep = maxOf(maxStep, kotlin.math.hypot(walk.wx - px, walk.wz - pz))
        if (walk.collision.blocked(walk.wx, walk.wz, com.lifemax.rpg.feature.habits.presentation.scene.OpenWalk.RADIUS - .5f)) inside++
        val drawNow = t >= shot * 5f - 1e-4f
        if (drawNow) {
            val hour = 13f + t / 30f * 4f
            run.build(walk.wx, walk.wz, 50f)
            run.draw(walk, w.toFloat(), h.toFloat(), hour, clock, sheets)
            val marks = ArrayList<Pair<androidx.compose.ui.geometry.Offset, Int>>()
            TownPlay.playerHead()?.let { marks += it to 0x40E0FF }
            walk.door?.let { d -> TownPlay.headOnScreen(d.x, d.z)?.let { marks += it to 0xFFB040 } }
            TownPlay.talkSprite()?.let { s -> TownPlay.keeperHead(s)?.let { marks += it to 0x60FF60 } }
            shooter.save(File(dir, "walk_%02ds.png".format(shot * 5)), w, h, hour, marks, skyRow = skyRow(hour, h))
            log.appendLine("  %5.1f s shot walk_%02ds: (%.0f, %.0f) on %s (%s, %s), yard %s, door %s, talk %s; %s".format(t, shot * 5, walk.wx, walk.wz,
                walk.row.id, TownPlay.streetName(), TownPlay.hoodName(), walk.yard?.sprite ?: "-", walk.door?.sprite ?: "-", TownPlay.talkSprite() ?: "-", OrbitCamera.describe()))
            shot++
        } else run.orbit(walk, w.toFloat(), h.toFloat(), clock)
        // (the camera's yaw moves only while a drag is easing in: any change well after the last input is the camera
        // turning by itself)
        val settled = inputs.none { t >= it.from && t < it.to + .6f }
        if (settled && kotlin.math.abs(OrbitCamera.yaw - lastYaw) > 1e-3f) turnedAlone++
        lastYaw = OrbitCamera.yaw
        if (((t * 60f).toInt() % 30) == 0) log.appendLine("  %5.1f s (%.0f, %.0f) speed %.0f blocked %.2f row %s yard %s door %s; yaw %.1f pitch %.1f dist %.0f (pulled %.0f)".format(
            t, walk.wx, walk.wz, walk.speed, walk.blockedShare, walk.row.id, walk.yard?.sprite ?: "-", walk.door?.sprite ?: "-", OrbitCamera.yaw, OrbitCamera.pitch, OrbitCamera.dist, OrbitCamera.pulled))
        t += dt; clock += dt
    }
    GameState.moveX = 0f; GameState.moveLane = 0f
    log.appendLine("reached ${g} of ${goals.size} goals; frames the camera turned with no input: $turnedAlone; frames inside something: $inside; largest step %.2f (a run frame is %.2f)".format(maxStep, 150f / 60f))
    log.appendLine("breadcrumbs:")
    for (e in events) log.appendLine("  $e")
    File(dir, "walk.txt").writeText(log.toString())
    print(log)
}

/**
 * The sky as the stage lays it under the town for [hour] on a screen [h] tall: its gradient down to the horizon where the
 * orbit camera last put it ([OrbitCamera.horizonY]).
 */
/**
 * With -Dsky=stage: the stage's own sky as the app lays it under the town in the open world (HabitsStage with only its
 * sky: the clouds, the sun or moon, the horizon's glow; the painted city is left out there), at the phone's density, for
 * [Shooter.save] to put behind the town. Null otherwise (the plain gradient).
 */
private fun stageSky(hour: Float, w: Int, h: Int): java.awt.image.BufferedImage? {
    if (System.getProperty("sky") != "stage") return null
    com.lifemax.rpg.feature.habits.presentation.scene.StageTuning.skyOnly = true
    val d = 2.8125f
    val img = renderImage(w, h, hour, "2026-07-10", streetOfShops().first().id, false, density = d, wd = w / d, hd = h / d)
    val png = img.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes
    return javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(png))
}

internal fun skyRow(hour: Float, h: Int): (Int) -> Color {
    val sky = skyColors(hour).sky
    val hy = OrbitCamera.horizonY
    val span = h * .9f
    return { y ->
        val t = ((y.toFloat() - (hy - span)) / span).coerceIn(0f, 1f) * (sky.size - 1)
        val i = t.toInt().coerceIn(0, maxOf(0, sky.size - 2))
        if (sky.size < 2) sky.firstOrNull() ?: Color.Gray else lerp(sky[i], sky[i + 1], (t - i).coerceIn(0f, 1f))
    }
}

/**
 * A walkable path from ([x0], [z0]) to ([x1], [z1]) over [c]'s grid of 6 units (a walker's radius clear), 8-connected, in a
 * box round both padded by 400; the straight line if none is found. Every few cells, so the walker cuts the corners a grid makes.
 */
internal fun plan(c: com.lifemax.rpg.feature.habits.domain.TownCollision, x0: Float, z0: Float, x1: Float, z1: Float): List<Pair<Float, Float>> {
    val s = 6f; val pad = 400f
    val ox = minOf(x0, x1) - pad; val oz = minOf(z0, z1) - pad
    val nx = ((kotlin.math.abs(x1 - x0) + 2 * pad) / s).toInt() + 1
    val nz = ((kotlin.math.abs(z1 - z0) + 2 * pad) / s).toInt() + 1
    val r = com.lifemax.rpg.feature.habits.presentation.scene.OpenWalk.RADIUS + .5f
    fun cell(x: Float, z: Float) = ((z - oz) / s).toInt().coerceIn(0, nz - 1) * nx + ((x - ox) / s).toInt().coerceIn(0, nx - 1)
    val start = cell(x0, z0); val end = cell(x1, z1)
    val from = IntArray(nx * nz) { -1 }
    val dist = FloatArray(nx * nz) { Float.MAX_VALUE }
    val q = java.util.PriorityQueue<Pair<Float, Int>>(compareBy { it.first })
    dist[start] = 0f; from[start] = start; q += 0f to start
    val gx = ((x1 - ox) / s); val gz = ((z1 - oz) / s)
    while (q.isNotEmpty()) {
        val (_, k) = q.poll()
        if (k == end) break
        val cx = k % nx; val cz = k / nx
        for (dz in -1..1) for (dx in -1..1) {
            if (dx == 0 && dz == 0) continue
            val ax = cx + dx; val az = cz + dz
            if (ax !in 0 until nx || az !in 0 until nz) continue
            val n = az * nx + ax
            val nd = dist[k] + if (dx != 0 && dz != 0) 1.414f else 1f
            if (nd >= dist[n]) continue
            if (n != end && c.blocked(ox + (ax + .5f) * s, oz + (az + .5f) * s, r)) { dist[n] = -1f; continue }
            dist[n] = nd; from[n] = k
            q += (nd + kotlin.math.hypot(ax - gx, az - gz)) to n
        }
    }
    if (from[end] < 0) return listOf(x1 to z1)
    val out = ArrayList<Pair<Float, Float>>()
    var k = end
    var i = 0
    while (k != start) { if (i++ % 3 == 0) out += (ox + (k % nx + .5f) * s) to (oz + (k / nx + .5f) * s); k = from[k] }
    out.reverse()
    out += x1 to z1
    return out
}

/**
 * What a view costs the GPU, part by part (-Dgpucost=name,name or all): the software GL's time to draw the frame as it
 * stands, then with one thing changed at a time (the see-through cone open as before 0.1.390, the haze off, the shadows
 * off, post-processing off, the far boxes, buildings, yards, greenery or ground left out). The build server's llvmpipe
 * shades on the CPU, so the times stand in for the fill rate and the shading's cost, not for the S25's GPU: the shares
 * are what to read.
 */
internal fun gpuCost(name: String, walk: com.lifemax.rpg.feature.habits.presentation.scene.OpenWalk, run: TownRun, ft: FilamentTown, shooter: Shooter,
                     sheets: List<androidx.compose.ui.graphics.ImageBitmap>, w: Int, h: Int, hour: Float, clock: Float): String {
    val st = com.lifemax.rpg.feature.habits.presentation.scene3d.SeeThrough
    fun redraw() { run.draw(walk, w.toFloat(), h.toFloat(), hour, clock, sheets) }
    fun gl(): Float { repeat(2) { shooter.renderFrame(w, h) }; val ts = (0 until 5).map { shooter.renderFrame(w, h).second }.sorted(); return ts[2] }
    // (a still view's first frames still upload the trees' detail and the far cells, two a frame: settled first, so each
    // change is measured against the same scene; the base measured again at the end says how steady the times are)
    repeat(120) { redraw() }
    val base = gl()
    val out = StringBuilder("%-26s software GL %.0f ms at %dx%d (%d draws, %d triangles, %d casters); changed one at a time:".format(name, base, w, h, ft.stats.draws, ft.stats.triangles, ft.stats.casters))
    fun part(label: String, set: () -> Unit, unset: () -> Unit) {
        set(); redraw(); val t = gl(); unset(); redraw()
        out.append(" %s %+.0f%%;".format(label, (t - base) / base * 100f))
    }
    part("cone always open (as 0.1.385)", { st.always = true }, { st.always = false })
    part("no haze", { ft.hazeOff = true }, { ft.hazeOff = false })
    part("no shadows", { ft.view.isShadowingEnabled = false }, { ft.view.isShadowingEnabled = true })
    part("no post", { ft.view.isPostProcessingEnabled = false }, { ft.view.isPostProcessingEnabled = true })
    for ((label, bit) in listOf("no far boxes" to FilamentTown.HIDE_FAR, "no buildings" to FilamentTown.HIDE_BUILDINGS, "no yards" to FilamentTown.HIDE_YARDS,
            "no greenery/props" to FilamentTown.HIDE_GREEN, "no ground" to FilamentTown.HIDE_GROUND))
        part(label, { ft.debugHide = bit }, { ft.debugHide = 0 })
    out.append(" (the base again: %.0f ms)".format(gl()))
    return out.toString()
}
