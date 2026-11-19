import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.lifemax.rpg.feature.habits.domain.RailNet
import com.lifemax.rpg.feature.habits.domain.RailScene
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownYards
import com.lifemax.rpg.feature.habits.domain.TrainNow
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuilding
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuildingLoad
import com.lifemax.rpg.feature.habits.presentation.scene3d.FilamentTown
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownArt
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownFrame
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownPose
import java.io.File
import kotlin.math.atan2
import kotlin.math.hypot
import org.jetbrains.skia.Image as SkiaImage

/**
 * The railroad in the town (-Dmode=filament-rail; docs/RAIL.md): the app's town (assets/town/gamma) with its rail.json,
 * built as the game builds it, then: the level crossing on the road out of town with a train passing (day and night,
 * gates down), the depot (by era: a steam train in 1948, a diesel in 1960, the national service's in 1990 after the
 * depot shut, a modern train in 2020), the track from above, and the ride (the camera on the train, out of town). Each
 * view's year, hour and the trains standing go to rail.txt. -Dshots=name,name renders only those; -Dw= the width.
 * Writes build/preview/filament-rail/.
 */
fun renderFilamentRail(out: String) {
    val dir = File(out, "filament-rail").also { it.mkdirs() }
    val townDir = System.getProperty("town")?.let { File(it) } ?: File("../app/src/main/assets/town/gamma")
    val assets = File(System.getProperty("assetsRoot") ?: "../../public/assets")
    val json = File(townDir, "street.json").readText()
    val town = Town.parse(json) ?: error("no graph in $townDir/street.json")
    val yards = TownYards.parse(json)
    val root = org.json.JSONObject(json)
    val net = RailNet.parse(File(townDir, "rail.json").readText()) ?: error("no rail.json in $townDir")
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
    // (the track's bed kept clear of the forest before the collision and the ground are made, as the app does)
    town.keepClear = RailScene(net, town.year)::onBed
    val collision = com.lifemax.rpg.feature.habits.domain.TownCollision.build(town, yards,
        com.lifemax.rpg.feature.habits.domain.ModelShape.parseAll(File(bdir, "models.json").takeIf { it.exists() }?.readText()),
        com.lifemax.rpg.feature.habits.domain.CinemaSiteShape.parse(root), com.lifemax.rpg.feature.habits.domain.StoreLots.parse(root).map { it.sprite }.toSet())
    val ft = FilamentTown(engine, { n -> File(mats, "$n.filamat").readBytes() }, town, yards, art, building,
        cinema = com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaSite.parse(root),
        stores = com.lifemax.rpg.feature.habits.presentation.scene3d.StoreSite.parse(root), collision = collision, rail = net)
    ft.nightBudgetNs = Long.MAX_VALUE
    // the farms round the town (their country roads are the roads out of town the track crosses), in high summer
    val farmDir = File("../app/src/main/assets/farm")
    val farmArt = com.lifemax.rpg.feature.habits.presentation.scene3d.FarmArt({ p -> open(File(farmDir, p)) }, { p -> File(farmDir, p).takeIf { it.exists() }?.readText() }, { p -> open(File(assets, p)) })
    com.lifemax.rpg.feature.habits.presentation.scene3d.TownFarms.attach(ft, com.lifemax.rpg.feature.habits.domain.TownFarmsData.parse(root), farmArt, collision, day = 200)
    ft.startGround()
    while (!ft.uploadGround(8f)) Thread.sleep(1)
    while (ft.pending > 0) { if (ft.buildNearest(0f, 0f, 8f) == 0) Thread.sleep(1) }
    val shooter = Shooter(engine, ft)
    val report = StringBuilder("railroad of ${net.name} (${net.town}), world ${net.worldSeed}: ${net.links.size} links, ${net.towns.size} towns in the export\n")
    val only = System.getProperty("shots")?.split(",")?.map { it.trim() }?.toSet()
    fun want(name: String) = only == null || name in only || only.any { name.startsWith(it) }
    val w = System.getProperty("w")?.toIntOrNull() ?: 1280; val h = w * 9 / 16
    var clock = 100.0

    fun shoot(name: String, year: Int, minute: Double, pose: TownPose, what: String) {
        ft.setRailYear(year)
        val hour = (minute / 60.0).toFloat()
        // (two frames: the trains and the gates settle)
        ft.prepare(pose)
        ft.snapRail()
        repeat(30) { ft.frame(pose, hour, clock); clock += 1.0 / 30 }
        shooter.save(File(dir, "$name.png"), w, h, hour)
        val trains = ft.trainsNow.joinToString("; ") { t -> "${if (t.passenger) "passenger" else "freight"} ${t.cars.first()}+${t.cars.size - 1} on ${t.path.line} at %.0f".format(t.front) }
        report.appendLine("%-24s %d %02d:%02d  %s; draws %d, tris %d; trains: %s".format(name, year, (minute / 60).toInt(), (minute % 60).toInt(), what, ft.stats.draws, ft.stats.triangles, trains.ifEmpty { "none" }))
        println("  $name")
    }

    /** The first minute from [from] when [ok] holds of the year's trains (a minute's steps, then a tenth's). */
    fun findMinute(year: Int, from: Double, to: Double, ok: (List<TrainNow>) -> Boolean): Double? {
        val sc = RailScene(net, year)
        var m = from
        while (m < to) { if (ok(sc.trains(m))) return m; m += 0.1 }
        return null
    }

    // --- the level crossing on the road out of town: a train over it by day, at night with the gates down
    net.rail.crossings.firstOrNull()?.let { c ->
        val sc = RailScene(net, 2025)
        fun covers(ts: List<TrainNow>): Boolean = ts.any { t -> val (s, off) = t.path.project(c.x, c.z); val (lo, hi) = t.span; off < 120f && s in lo + 200f..hi - 200f }
        val yawTrack = Math.toDegrees(atan2(c.dx.toDouble(), -c.dz.toDouble())).toFloat()
        val day = findMinute(2025, 9 * 60.0, 18 * 60.0, ::covers)
        val night = findMinute(2025, 20.5 * 60, 24 * 60.0, ::covers) ?: findMinute(2025, 0.0, 5 * 60.0, ::covers)
        val before = day?.let { d -> (1..40).map { d - it * 0.1 }.firstOrNull { m -> !covers(sc.trains(m)) && sc.crossingActive(c, sc.trains(m)) } }
        if (want("crossing_empty_13h")) shoot("crossing_empty_13h", 2025, 13 * 60.0 + 7, TownFrame.orbit(c.x, 20f, c.z, yawTrack + 50f, 22f, 520f, w.toFloat(), h.toFloat()), "the crossing, no train")
        if (day != null && want("crossing_train_day")) shoot("crossing_train_day", 2025, day, TownFrame.orbit(c.x, 20f, c.z, yawTrack + 60f, 18f, 640f, w.toFloat(), h.toFloat()), "a train over the crossing")
        if (before != null && want("crossing_gates_down")) shoot("crossing_gates_down", 2025, before, TownFrame.orbit(c.x, 20f, c.z, yawTrack + 110f, 14f, 380f, w.toFloat(), h.toFloat()), "gates down, the train coming")
        if (night != null && want("crossing_train_night")) shoot("crossing_train_night", 2025, night, TownFrame.orbit(c.x, 20f, c.z, yawTrack + 60f, 18f, 640f, w.toFloat(), h.toFloat()), "a train over the crossing at night")
        if (want("crossing_1940")) shoot("crossing_1940", 1940, 13 * 60.0, TownFrame.orbit(c.x, 20f, c.z, yawTrack + 50f, 22f, 520f, w.toFloat(), h.toFloat()), "1940: crossbucks only")
    }

    // --- out in the farmland: the crossing on a country road nearest a field (crossbucks), the track along a field's fence,
    // a train passing the fields; from the open world's camera height, from either side of the track
    run {
        val fd = com.lifemax.rpg.feature.habits.domain.TownFarmsData.parse(root)
        fun fieldDist(x: Float, z: Float): Float = fd.fields.minOfOrNull { f -> val r = f.rect; hypot(maxOf(r[0] - x, 0f, x - r[2]), maxOf(r[1] - z, 0f, z - r[3])) } ?: Float.MAX_VALUE
        val ring = net.rail.crossings.filter { it.road >= town.roads.size }.minByOrNull { fieldDist(it.x, it.z) } ?: return@run
        val yawT = Math.toDegrees(atan2(ring.dx.toDouble(), -ring.dz.toDouble())).toFloat()
        for ((k, side) in listOf("a" to 90f, "b" to -90f)) {
            if (want("farm_crossing_$k")) shoot("farm_crossing_$k", 2025, 13 * 60.0 + 7, TownFrame.orbit(ring.x, 20f, ring.z, yawT + side + 25f, 16f, 640f, w.toFloat(), h.toFloat()), "the track over a country road among the farms")
        }
        // the biggest piece of a field the track splits (or trims), and the track beside it
        val piece = fd.fields.filter { it.id.contains('~') }.maxByOrNull { (it.rect[2] - it.rect[0]) * (it.rect[3] - it.rect[1]) } ?: return@run
        val x = (piece.rect[0] + piece.rect[2]) / 2f; val z = (piece.rect[1] + piece.rect[3]) / 2f
        val sc = RailScene(net, 2025)
        val path = sc.paths.first()
        val at = FloatArray(4); path.at(path.project(x, z).first, at)
        val yawF = Math.toDegrees(atan2(at[2].toDouble(), -at[3].toDouble())).toFloat()
        for ((k, side) in listOf("a" to 90f, "b" to -90f)) {
            if (want("track_fields_$k")) shoot("track_fields_$k", 2025, 11 * 60.0 + 3, TownFrame.orbit(at[0], 20f, at[1], yawF + side + 20f, 15f, 700f, w.toFloat(), h.toFloat()), "the track along a field's fence (${piece.id})")
        }
        if (want("train_farmland")) {
            fun by(ts: List<TrainNow>) = ts.any { t -> val (s, off) = t.path.project(at[0], at[1]); off < 150f && s in t.span.first + 100f..t.span.second - 100f }
            val m = findMinute(2025, 8 * 60.0, 19 * 60.0, ::by)
            if (m != null) for ((k, side) in listOf("a" to 90f, "b" to -90f)) shoot("train_farmland_$k", 2025, m, TownFrame.orbit(at[0], 30f, at[1], yawF + side + 30f, 14f, 900f, w.toFloat(), h.toFloat()), "a train passing the fields")
        }
    }

    // --- the depot by era, with the train standing at it (or passing, while the station is shut)
    net.rail.depot?.let { d ->
        val cx = (d.rect[0] + d.rect[2]) / 2f; val cz = (d.rect[1] + d.rect[3]) / 2f
        val face = Math.toDegrees(atan2((d.x - cx).toDouble(), -(d.z - cz).toDouble())).toFloat()
        val mx = (cx + d.x) / 2f; val mz = (cz + d.z) / 2f
        if (want("depot_plain_13h")) shoot("depot_plain_13h", 2025, 13 * 60.0 + 3, TownFrame.orbit(mx, 30f, mz, face + 180f + 35f, 20f, 620f, w.toFloat(), h.toFloat()), "the depot, from the track side")
        if (want("depot_back_13h")) shoot("depot_back_13h", 2025, 13 * 60.0 + 3, TownFrame.orbit(cx, 30f, cz, face - 30f, 22f, 560f, w.toFloat(), h.toFloat()), "the depot, from the street side")
        for ((year, label) in listOf(1948 to "steam", 1960 to "diesel", 1990 to "national", 2020 to "modern")) {
            val name = "depot_${year}_$label"
            if (!want(name)) continue
            val sc = RailScene(net, year)
            // a stopping train standing at the platform, else any train by the depot
            // (standing at the platform: where it is now and half a minute on)
            val m = findMinute(year, 9 * 60.0, 18 * 60.0) { ts -> ts.any { t -> t.passenger && t.stopping && t.span.let { (lo, hi) -> t.path.depotS in lo..hi } } }
                ?.let { m0 -> var m1 = m0; while (m1 < m0 + 10 && sc.trains(m1).zip(sc.trains(m1 + .3)).none { (a, b) -> a.passenger && a.front == b.front }) m1 += .1; m1 + .1 }
                ?: findMinute(year, 9 * 60.0, 18 * 60.0) { ts -> ts.any { t -> t.passenger && t.span.let { (lo, hi) -> t.path.depotS in lo + 300f..hi - 300f } } }
                ?: findMinute(year, 6 * 60.0, 22 * 60.0) { ts -> ts.any { t -> t.path.depotS in t.span.first..t.span.second } }
            if (m == null) { report.appendLine("$name: no train by the depot that day"); continue }
            shoot(name, year, m, TownFrame.orbit(mx, 30f, mz, face + 180f + 40f, 18f, 720f, w.toFloat(), h.toFloat()), "$label train at the depot (station ${sc.station})")
        }
        if (want("depot_night")) {
            val m = findMinute(2020, 19.5 * 60, 22.6 * 60) { ts -> ts.any { t -> t.passenger && t.path.depotS in t.span.first..t.span.second } } ?: (21.5 * 60)
            shoot("depot_night", 2020, m, TownFrame.orbit(mx, 30f, mz, face + 180f + 40f, 18f, 720f, w.toFloat(), h.toFloat()), "the depot at night")
        }
        if (want("depot_1985_shut")) shoot("depot_1985_shut", 1985, 13 * 60.0, TownFrame.orbit(mx, 30f, mz, face + 180f + 35f, 20f, 620f, w.toFloat(), h.toFloat()), "1985: the depot boarded up (no passenger trains stop)")
    }

    // --- from above: the line round the town, and the aerial with a freight train
    val p0 = net.rail.tracks.first()
    if (want("aerial_track")) {
        val r = net.home.plan!!.rect
        shoot("aerial_track", 2025, 12 * 60.0, TownFrame.aerial((r[0] + r[2]) / 2f, (r[1] + r[3]) / 2f + 1200f, 0f, 55f, 15000f, 40f, w.toFloat(), h.toFloat()), "the town and its line from above")
    }
    if (want("freight_passing")) {
        val m = findMinute(2025, 6 * 60.0, 20 * 60.0) { ts -> ts.any { t -> !t.passenger && t.span.let { (lo, hi) -> t.path.depotS in lo..hi } } }
        if (m != null) {
            val sc = RailScene(net, 2025)
            val t = sc.trains(m).first { !it.passenger }
            val at = FloatArray(4); t.path.at(t.carAt(t.cars.size / 2), at)
            shoot("freight_passing", 2025, m, TownFrame.orbit(at[0], 30f, at[1], Math.toDegrees(atan2(at[2].toDouble(), -at[3].toDouble())).toFloat() + 70f, 24f, 900f, w.toFloat(), h.toFloat()), "a freight train passing")
        }
    }
    // --- the ride: the train from the depot out through the countryside to another town, the camera beside it
    for ((year, dest) in listOf(2025 to "2,0", 1955 to "-2,0")) {
        val name = "ride_$year"
        if (!want(name)) continue
        val route = net.planRoute("0,0", dest, year, 9 * 60.0)
        val legs = route.legs?.filter { it.mode == "train" }.orEmpty()
        if (legs.isEmpty()) { report.appendLine("$name: no train to $dest (${route.reason})"); continue }
        val ride = com.lifemax.rpg.feature.habits.domain.RailRide(net, route)
        ft.setRailYear(year)
        ft.startRide(ride)
        report.appendLine("$name: ${net.home.name} to ${net.townOf(route.to)?.name}: ${legs.joinToString(" / ") { "${it.fromName} ${RailNet.clock(it.depart)} - ${it.toName} ${RailNet.clock(it.arrive)} (${it.km} km)" }}; fare $%.2f; path %.0f units, %.0f s on screen".format(route.fare, ride.path.length, ft.rideSeconds))
        for ((k, f) in listOf(.04f, .3f, .55f, .8f, 1f).withIndex()) {
            val pose = ft.ridePose(0f, w.toFloat(), h.toFloat(), yaw = if (k % 2 == 0) 70f else 115f, at = f * ft.rideSeconds)!!
            shoot("${name}_$k", year, legs.first().depart + f * (legs.last().arrive - legs.first().depart), pose, "the ride, ${(f * 100).toInt()}% of the way")
        }
        ft.stopRide()
    }
    if (p0.pts.size < 4) report.appendLine("(no track)")
    File(dir, "rail.txt").writeText(report.toString())
    println(report)
}

private fun abs(v: Float) = kotlin.math.abs(v)
