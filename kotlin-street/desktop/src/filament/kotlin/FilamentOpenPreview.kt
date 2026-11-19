import androidx.compose.ui.graphics.Color
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
 * The player stands where each view puts them (a stub of the open-world walk: a position and a heading, [TownWalk.placeAt]).
 * For each view open.txt gives the draws and triangles three ways: a free camera as before (no draw distance, every yard
 * near), with the draw distance only, and as drawn (draw distance and far yards). -Dshots=name,name renders only those.
 * Writes build/preview/filament-open/.
 */
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

    Filament.init()
    val engine = Engine.Builder().backend(Engine.Backend.OPENGL).config(com.lifemax.rpg.feature.habits.presentation.scene3d.TownEngine.config()).build()
    val mats = File("../app/src/main/assets/filament")
    val cinema = com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaSite.parse(root)
    val ft = FilamentTown(engine, { n -> File(mats, "$n.filamat").readBytes() }, town, yards, art, building, cinema = cinema)
    ft.nightBudgetNs = Long.MAX_VALUE
    val report = StringBuilder()
    report.appendLine("open world, town ${town.seed} ${town.year}: ${ft.slots.size} buildings, ${yards.yards.size} yards; far yards ${if (com.lifemax.rpg.feature.habits.presentation.scene3d.YardKitBuilder.far) "baked" else "off (-DtownLod=0)"}")
    ft.startGround()
    while (!ft.uploadGround(8f)) Thread.sleep(1)
    while (ft.pending > 0) { if (ft.buildNearest(0f, 0f, 8f) == 0) Thread.sleep(1) }
    report.appendLine("(software GL on the build server, not the S25: draws and triangles are what is sent, the times are not the phone's)")

    // the people to talk to (as the app picks them) and the player
    val dialogue = File("../app/src/main/assets/town/${town.seed}/dialogue.json")
    TownPlay.keepers = if (!dialogue.exists()) emptyMap() else run {
        val a = org.json.JSONObject(dialogue.readText()).getJSONArray("npcs")
        val m = LinkedHashMap<String, Int>()
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); m.putIfAbsent(o.getString("building"), o.getString("id").hashCode() and 0x7fffffff) }
        m
    }
    GameState.enabled = true; GameState.look = 1
    GameState.moveX = 0f; GameState.moveLane = 0f
    val walk = TownPlay.start(town)
    val run = TownRun(ft)
    // (the town is built already: this marks the first view ready, so the people stand)
    while (!run.build(walk.wx, walk.wz, 8f)) Thread.sleep(1)
    val sheets = SpriteSheets.sheets
    val shooter = Shooter(engine, ft)
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
        val t0 = System.nanoTime()
        // the same camera three ways: free as before (no draw distance, every yard near), the draw distance alone, as drawn
        val pose0 = run.orbit(walk, w.toFloat(), h.toFloat(), clock)
        fun variant(range: Boolean, lod: Boolean) = TownPose(pose0.ex, pose0.ey, pose0.ez, pose0.fx, pose0.fy, pose0.fz, pose0.fl, pose0.cx, pose0.cy, pose0.w, pose0.h,
            pose0.near, pose0.far, pose0.hazeFrom, pose0.haze, range = if (range) pose0.range else Float.MAX_VALUE, open = true, lodFar = if (lod) pose0.lodFar else Float.MAX_VALUE)
        ft.prepare(variant(false, false)); ft.frame(variant(false, false), hour); val free = stats()
        ft.frame(variant(true, false), hour); val ranged = stats()
        ft.prepare(pose0)
        val pose = run.draw(walk, w.toFloat(), h.toFloat(), hour, clock, sheets)
        val drawn = stats()
        // the sky as the stage lays it under the town: its gradient down to the horizon, the orb where the light comes from
        val sky = skyColors(hour).sky
        val hy = OrbitCamera.horizonY
        val span = h * .9f
        val orbXY = FloatArray(2)
        val key = com.lifemax.rpg.feature.habits.domain.SceneLight.key(hour)
        val orb = if (OrbitCamera.orbAt(hour, w.toFloat(), h.toFloat(), orbXY)) floatArrayOf(orbXY[0], orbXY[1], h * .035f, (if (key.cool > .5f) 0xE6EAF6 else 0xFFF0B8).toFloat()) else null
        val marks = ArrayList<Pair<androidx.compose.ui.geometry.Offset, Int>>()
        TownPlay.playerHead()?.let { marks += it to 0x40E0FF }
        shooter.save(File(dir, "$name.png"), w, h, hour, marks, skyRow = { y ->
            val t = ((y - (hy - span)) / span).coerceIn(0f, 1f) * (sky.size - 1)
            val i = t.toInt().coerceIn(0, maxOf(0, sky.size - 2))
            if (sky.size < 2) sky.firstOrNull() ?: Color.Gray else lerp(sky[i], sky[i + 1], (t - i).coerceIn(0f, 1f))
        }, orb = orb)
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
        view("main_high_13h", p.x, p.z, a.x, a.z, along + 135f, 58f, 1400f, 13f, "Main Street, zoomed out, looking down")
        view("main_close_13h", p.x, p.z, a.x, a.z, along + 180f, 10f, 150f, 13f, "Main Street, zoomed in, facing the player")
        view("main_through_13h", p.x, p.z, a.x, a.z, fronts + 180f, 20f, 600f, 13f, "Main Street, the camera behind the block (pulled in)")
        view("main_dusk_west", p.x, p.z, a.x, a.z, 270f, 16f, 500f, 19.3f, "Main Street, looking west at dusk")
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
        view("cinema_quarter_dusk", p.x, p.z, a.x, a.z, heading(n.x, n.z) - 40f, 20f, 700f, 19.3f, "the cinema, three-quarter at dusk")
        view("cinema_night", p.x, p.z, a.x, a.z, heading(n.x, n.z) + 25f, 16f, 600f, night, "the cinema at night")
    }
    // --- Woodside: behind the player on a quiet street
    run {
        val r = majority("woodside")
        val x = mid(r)
        val p = r.pointAt(x, 7f); val a = r.alongAt(x)
        view("woodside_behind_13h", p.x, p.z, a.x, a.z, heading(a.x, a.z) - 20f, 26f, 560f, 13f, "a Woodside street, behind the player")
    }
    File(dir, "open.txt").writeText(report.toString())
    print(report)
    TownPlay.stop()
    shooter.destroy()
    ft.destroy()
    engine.destroy()
}
