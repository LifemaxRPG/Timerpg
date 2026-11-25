import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.lifemax.rpg.feature.habits.domain.Farm
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownFarmsData
import com.lifemax.rpg.feature.habits.domain.TownYards
import com.lifemax.rpg.feature.habits.presentation.scene.GameState
import com.lifemax.rpg.feature.habits.presentation.scene.OrbitCamera
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuilding
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuildingLoad
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteSheets
import com.lifemax.rpg.feature.habits.presentation.scene.TownPlay
import com.lifemax.rpg.feature.habits.presentation.scene3d.FarmArt
import com.lifemax.rpg.feature.habits.presentation.scene3d.FilamentTown
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownArt
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownFarms
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownFrame
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownRun
import java.io.File
import kotlin.math.hypot
import org.jetbrains.skia.Image as SkiaImage

/**
 * The farms round the town (-Dmode=filament-farms): the whole town (the app's assets/town/gamma, or -Dtown=DIR, e.g. a 1955
 * export) built as the game builds it with its farms ([TownFarms], from assets/farm), seen through the open world's camera
 * and from above: a farmstead from its four sides and from above; one farm's fields through the year (spring, summer,
 * autumn, winter under snow); the town's edge with farms from the player's height and zoomed out; the whole farmland from
 * high up. farms.txt gives each view's draws and triangles and the farms' report. -Dshots=name,name renders only those;
 * -DfarmTag=xyz adds a tag to the file names (a 1955 run next to a 2025 one). Writes build/preview/filament-farms/.
 */
fun renderFilamentFarms(out: String) {
    val dir = File(out, "filament-farms").also { it.mkdirs() }
    // (-DmaskPages=0: the night masks atlas-sized, as before the window pages, to compare)
    com.lifemax.rpg.feature.habits.presentation.scene3d.TownQuality.maskPages = System.getProperty("maskPages") != "0"
    // (-Dquant=0: the shared pages hold float vertices, 28 bytes, as before the quantised ones, to compare)
    com.lifemax.rpg.feature.habits.presentation.scene3d.BufferArena.quantize = System.getProperty("quant") != "0"
    val townDir = System.getProperty("town")?.let { File(it) }
        ?: File("../app/src/main/assets/town/gamma").takeIf { File(it, "street.json").exists() } ?: File("../build-town/gamma")
    val tag = System.getProperty("farmTag")?.let { "_$it" } ?: ""
    val assets = File(System.getProperty("assetsRoot") ?: "../../public/assets")
    val farmDir = File("../app/src/main/assets/farm")
    val json = File(townDir, "street.json").readText()
    val town = Town.parse(json) ?: error("$townDir/street.json has no graph")
    val yards = TownYards.parse(json)
    val root = org.json.JSONObject(json)
    val bdir = File(townDir, "buildings")
    val windows = SpriteBuildingLoad.windows(File(bdir, "windows.json").takeIf { it.exists() }?.readText())
    val houses = SpriteBuildingLoad.houses(File(bdir, "houses.json").takeIf { it.exists() }?.readText())
    val models = SpriteBuildingLoad.models(File(bdir, "models.json").takeIf { it.exists() }?.readText())
    val mids = HashMap<String, Int>()
    root.optJSONArray("shops")?.let { a ->
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i); val parts = o.optJSONArray("parts")
            mids[o.getString("sprite")] = if (parts == null) 0 else (0 until parts.length()).count { parts.getJSONObject(it).getString("key").startsWith("middle_") }
        }
    }
    fun open(f: File) = f.takeIf { it.exists() }?.let { SkiaImage.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
    val art = TownArt { path -> open(File(assets, path)) }
    val building: (String) -> SpriteBuilding? = { sp -> SpriteBuildingLoad.load(sp, mids[sp] ?: 0, windows, houses, models) { open(File(bdir, "$it.png")) } }
    val farmData = TownFarmsData.parse(root)
    require(!farmData.isEmpty) { "$townDir/street.json has no graph.farms: export it again (tools/build-town.sh)" }

    Filament.init()
    val engine = Engine.Builder().backend(Engine.Backend.OPENGL).config(com.lifemax.rpg.feature.habits.presentation.scene3d.TownEngine.config()).build()
    val mats = File("../app/src/main/assets/filament")
    val collision = com.lifemax.rpg.feature.habits.domain.TownCollision.build(town, yards,
        com.lifemax.rpg.feature.habits.domain.ModelShape.parseAll(File(bdir, "models.json").takeIf { it.exists() }?.readText()),
        com.lifemax.rpg.feature.habits.domain.CinemaSiteShape.parse(root), com.lifemax.rpg.feature.habits.domain.StoreLots.parse(root).map { it.sprite }.toSet())
    val ft = FilamentTown(engine, { n -> File(mats, "$n.filamat").readBytes() }, town, yards, art, building,
        cinema = com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaSite.parse(root),
        stores = com.lifemax.rpg.feature.habits.presentation.scene3d.StoreSite.parse(root), collision = collision)
    ft.nightBudgetNs = Long.MAX_VALUE
    val farmArt = FarmArt({ p -> open(File(farmDir, p)) }, { p -> File(farmDir, p).takeIf { it.exists() }?.readText() }, { p -> open(File(assets, p)) })
    val farms = TownFarms.attach(ft, farmData, farmArt, collision, day = 200) ?: error("no farms")
    val report = StringBuilder()
    report.appendLine("farms preview, town ${town.seed} ${town.year}: ${farmData.farms.size} farms, ${farmData.fields.size} fields in the export (all years), ${farmData.roads.size} country roads")
    val t0 = System.nanoTime()
    ft.startGround()
    while (!ft.uploadGround(8f)) Thread.sleep(1)
    while (ft.pending > 0) { if (ft.buildNearest(0f, 0f, 8f) == 0) Thread.sleep(1) }
    report.appendLine("built in %.1f s (software GL on the build server, not the S25)".format((System.nanoTime() - t0) / 1e9))
    report.appendLine(farms.report)
    report.appendLine("collision: ${collision.size} + ${collision.extraSize} shapes, ${collision.extraDoors.size} extra doors")

    GameState.enabled = true; GameState.look = 1
    GameState.moveX = 0f; GameState.moveLane = 0f
    OrbitCamera.open = true
    TownPlay.keepers = emptyMap()
    TownPlay.start(town, collision, yards)
    val walk = TownPlay.open ?: error("no open walk")
    val run = TownRun(ft)
    while (!run.build(walk.wx, walk.wz, 8f)) Thread.sleep(1)
    val sheets = SpriteSheets.sheets
    val shooter = Shooter(engine, ft)
    val only = System.getProperty("shots")?.split(",")?.map { it.trim() }?.toSet()
    fun want(name: String) = only == null || name in only || only.any { name.startsWith(it) }
    val w = (System.getProperty("w")?.toIntOrNull() ?: 1600); val h = w * 1080 / 2340
    var clock = 600f
    fun stats(): String = ft.stats.let { s -> "draws %5d, triangles %8d, casters %4d".format(s.draws, s.triangles, s.casters) }
    fun settle(day: Int) {
        if (farms.day != day) farms.day = day
        while (ft.pending > 0) { if (ft.buildNearest(walk.wx, walk.wz, 8f) == 0) Thread.sleep(1) }
    }

    /** The player at ([x], [z]) facing ([hx], [hz]), the orbit camera at [yaw], [pitch], [dist], at [hour] on [day]. */
    fun view(name: String, x: Float, z: Float, hx: Float, hz: Float, yaw: Float, pitch: Float, dist: Float, hour: Float, day: Int, where: String) {
        if (!want(name)) return
        settle(day)
        walk.placeAt(x, z, hx, hz)
        GameState.facing = 0
        OrbitCamera.set(yaw, pitch, dist)
        OrbitCamera.follow(walk.wx, walk.wz)
        run.orbit(walk, w.toFloat(), h.toFloat(), clock)
        val pose = run.draw(walk, w.toFloat(), h.toFloat(), hour, clock, sheets)
        ft.prepare(pose)
        run.draw(walk, w.toFloat(), h.toFloat(), hour, clock + .5f, sheets)
        shooter.save(File(dir, "$name$tag.png"), w, h, hour, skyRow = skyRow(hour, h))
        report.appendLine("%-24s %s; player (%.0f, %.0f), day %d: %s".format(name, where, walk.wx, walk.wz, day, stats()))
        println("  $name$tag")
        clock += 1f
    }

    /** A free camera over ([x], [z]). */
    fun above(name: String, x: Float, z: Float, yaw: Float, pitch: Float, dist: Float, hour: Float, day: Int, where: String) {
        if (!want(name)) return
        settle(day)
        val pose = TownFrame.aerial(x, z, yaw, pitch, dist, 40f, w.toFloat(), h.toFloat())
        ft.prepare(pose)
        ft.frame(pose, hour, clock.toDouble())
        shooter.save(File(dir, "$name$tag.png"), w, h, hour)
        report.appendLine("%-24s %s; day %d: %s".format(name, where, day, stats()))
        println("  $name$tag")
        clock += 1f
    }

    // the farm to look at: a working farm with a few fields nearest the town's spawn
    val spawnX = walk.wx; val spawnZ = walk.wz
    fun mid(f: Farm) = floatArrayOf((f.rect[0] + f.rect[2]) / 2f, (f.rect[1] + f.rect[3]) / 2f)
    val farm = farmData.farms.filter { f -> farmData.fields.count { it.farm == f.id && it.farmedIn(town.year) } >= 3 }
        .minByOrNull { val m = mid(it); hypot(m[0] - spawnX, m[1] - spawnZ) } ?: farmData.farms.first()
    val fm = mid(farm)
    // its front faces the road: the way out of the yard towards it
    val (fx, fz) = when (farm.front) { "N" -> 0f to -1f; "S" -> 0f to 1f; "W" -> -1f to 0f; else -> 1f to 0f }
    val frontYaw = OrbitCamera.headingOf(-fx, -fz)
    report.appendLine("farm ${farm.id} (${farm.family}, founded ${farm.founded}) at (%.0f, %.0f), front %s".format(fm[0], fm[1], farm.front))
    // (the player in the farmyard, by the lane)
    val gr = farm.ground ?: farm.rect
    val px = (gr[0] + gr[2]) / 2f; val pz = (gr[1] + gr[3]) / 2f
    // (each from a side: the player stands just outside the yard on the camera's side, looking over it, so nothing of the
    // farm stands between the camera and the player to be cut away)
    val half = kotlin.math.max(farm.rect[2] - farm.rect[0], farm.rect[3] - farm.rect[1]) / 2f
    fun side(name: String, yaw: Float, pitch: Float, dist: Float, hour: Float, where: String, out: Float = half + 90f) {
        val a = Math.toRadians(yaw.toDouble()); val lx = kotlin.math.sin(a).toFloat(); val lz = -kotlin.math.cos(a).toFloat()
        view(name, px - lx * out, pz - lz * out, lx, lz, yaw, pitch, dist, hour, 200, where)
    }
    side("farmstead_front", frontYaw, 24f, 640f, 13f, "the farmstead from the road")
    side("farmstead_left", frontYaw + 90f, 24f, 640f, 13f, "from its left")
    side("farmstead_right", frontYaw - 90f, 24f, 640f, 13f, "from its right")
    side("farmstead_back", frontYaw + 180f, 26f, 700f, 13f, "from behind (the windbreak)")
    side("farmstead_close", frontYaw + 25f, 10f, 260f, 16f, "close, at a person's height", out = half - 120f)
    side("farmstead_dusk", frontYaw - 35f, 18f, 560f, 19.6f, "dusk")
    side("farmstead_night", frontYaw + 20f, 20f, 560f, 22.5f, "night")
    above("farmstead_above", fm[0], fm[1], frontYaw + 20f, 55f, 2200f, 13f, 200, "from above")

    // one farm's fields through the year: the player at the gate of its biggest field
    val field = farmData.fields.filter { it.farm == farm.id && it.farmedIn(town.year) && it.use == "grain" }.maxByOrNull { (it.rect[2] - it.rect[0]) * (it.rect[3] - it.rect[1]) }
        ?: farmData.fields.first { it.farm == farm.id && it.farmedIn(town.year) }
    val g = com.lifemax.rpg.feature.habits.domain.FarmSolids.gate(field)
    val fyaw = OrbitCamera.headingOf(-g[2], -g[3])
    report.appendLine("field ${field.id}: ${field.use}, crop ${field.cropIn(town.year)} this year, rows along ${if (field.rowsX) "x" else "z"}")
    for ((nm, day) in listOf("spring" to 135, "early_summer" to 175, "summer" to 215, "autumn" to 275, "late_autumn" to 300, "winter" to 20)) {
        view("field_$nm", g[0] + g[2] * 40f, g[1] + g[3] * 40f, -g[2], -g[3], fyaw, 20f, 520f, 13f, day, "field ${field.id} in $nm")
    }
    view("field_summer_low", g[0] + g[2] * 40f, g[1] + g[3] * 40f, -g[2], -g[3], fyaw, 8f, 200f, 15f, 215, "low over the crop in summer")
    above("fields_summer_above", (field.rect[0] + field.rect[2]) / 2f, (field.rect[1] + field.rect[3]) / 2f, fyaw, 50f, 1800f, 13f, 215, "the fields from above in summer")
    above("fields_autumn_above", (field.rect[0] + field.rect[2]) / 2f, (field.rect[1] + field.rect[3]) / 2f, fyaw, 50f, 1800f, 13f, 290, "the fields from above in autumn")
    above("fields_winter_above", (field.rect[0] + field.rect[2]) / 2f, (field.rect[1] + field.rect[3]) / 2f, fyaw, 50f, 1800f, 13f, 20, "the fields from above in winter")

    // the town's edge: on the road out, looking back at the town over the farms; zoomed out; the whole farmland
    val spoke = farmData.roads.filter { it.kind == "spoke" }.maxByOrNull { hypot(it.bx - it.ax, it.bz - it.az) }!!
    val ux = (spoke.bx - spoke.ax) / hypot(spoke.bx - spoke.ax, spoke.bz - spoke.az); val uz = (spoke.bz - spoke.az) / hypot(spoke.bx - spoke.ax, spoke.bz - spoke.az)
    val ex = spoke.ax + ux * 2600f; val ez = spoke.az + uz * 2600f
    val back = OrbitCamera.headingOf(-ux, -uz)
    view("edge_road", ex, ez, -ux, -uz, back, 14f, 420f, 13f, 215, "on ${spoke.id} looking back at the town")
    view("edge_high", ex, ez, -ux, -uz, back + 25f, 34f, 900f, 13f, 215, "the town's edge, zoomed out")
    above("edge_above", spoke.ax + ux * 1800f, spoke.az + uz * 1800f, back + 20f, 38f, 5200f, 13f, 215, "the town's edge from above")
    val e = farmData.extent
    above("farmland_all", (e[0] + e[2]) / 2f, (e[1] + e[3]) / 2f, 15f, 55f, 26000f, 13f, 215, "the whole farmland")
    above("town_inner_fields", (town.nodes.minOf { it.x } + town.nodes.maxOf { it.x }) / 2f, (town.nodes.minOf { it.z } + town.nodes.maxOf { it.z }) / 2f, 0f, 60f, 14000f, 13f, 215, "the town and its edge fields")
    // a hay meadow just mown, its round bales lying
    farmData.fields.filter { it.use == "hay" && it.farmedIn(town.year) }.minByOrNull { hypot((it.rect[0] + it.rect[2]) / 2f - fm[0], (it.rect[1] + it.rect[3]) / 2f - fm[1]) }?.let { hf ->
        val hg = com.lifemax.rpg.feature.habits.domain.FarmSolids.gate(hf)
        view("hay_bales", hg[0] - hg[2] * 200f, hg[1] - hg[3] * 200f, -hg[2], -hg[3], OrbitCamera.headingOf(-hg[2], -hg[3]) + 20f, 22f, 560f, 15f, 172, "hay ${hf.id} mown in June, bales lying")
        view("hay_meadow", hg[0] - hg[2] * 200f, hg[1] - hg[3] * 200f, -hg[2], -hg[3], OrbitCamera.headingOf(-hg[2], -hg[3]) + 20f, 22f, 560f, 15f, 150, "hay ${hf.id} growing in May")
    }
    // a field the town built over (the same camera in a 1955 export shows it farmed): from above and from the ground
    farmData.fields.filter { it.becomes == "houses" }.maxByOrNull { (it.rect[2] - it.rect[0]) * (it.rect[3] - it.rect[1]) }?.let { sf ->
        val cx = (sf.rect[0] + sf.rect[2]) / 2f; val cz = (sf.rect[1] + sf.rect[3]) / 2f
        report.appendLine("sold field ${sf.id}: farmed from ${sf.from}, built over in ${sf.sold} (${sf.becomes}); farmed in ${town.year}: ${sf.farmedIn(town.year)}")
        above("sold_field_above", cx, cz, 200f, 50f, 3200f, 13f, 215, "field ${sf.id}, built over in ${sf.sold}")
        above("sold_field_wide", cx, cz, 200f, 45f, 7000f, 13f, 215, "the town's edge round ${sf.id}")
    }
    // a stand by the road in summer
    farmData.farms.firstOrNull { it.produce }?.let { pf ->
        val st = pf.things.first { it.summer }
        view("stand_summer", st.x + st.fx * 120f, st.z + st.fz * 120f, -st.fx, -st.fz, OrbitCamera.headingOf(-st.fx, -st.fz) + 25f, 14f, 380f, 11f, 200, "a roadside stand in July")
    }

    File(dir, "farms$tag.txt").writeText(report.toString())
    println(report)
    shooter.destroy()
    ft.destroy()
    com.lifemax.rpg.feature.habits.presentation.scene3d.BufferArena.release(engine)
    engine.destroy()
}
