import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.WorldFarPlan
import com.lifemax.rpg.feature.habits.domain.WorldMemory
import com.lifemax.rpg.feature.habits.domain.WorldPos
import com.lifemax.rpg.feature.habits.domain.WorldSite
import com.lifemax.rpg.feature.habits.domain.WorldSkeleton
import com.lifemax.rpg.feature.habits.presentation.scene3d.FilamentTown
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownPose
import com.lifemax.rpg.feature.habits.presentation.scene3d.WorldFar
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * The world round the capital (WORLD_GEN.md phase W4), in the open world's preview with -Dworld=1: gamma placed on the
 * planet (the baked skeleton, content/world/alpha), the far view ([WorldFar]) attached before the town is built, then
 * -Dshots=world renders the views that judge it: the capital from high above with the world to the horizon, the town's
 * edge where it meets the countryside, the river leaving town, the rail line leaving toward the next hub, and a hill's
 * view of the distant towns. Free cameras, not the player's: a thin haze for the far distances.
 */
internal object WorldPreview {
    /** The skeleton read and gamma placed on it, or null without -Dworld=1. */
    fun site(town: Town): WorldSite? {
        if (System.getProperty("world") != "1") return null
        val f = File(System.getProperty("worldFile") ?: "../../content/world/alpha/skeleton.twsk.gz")
        val t0 = System.nanoTime()
        val sk = f.inputStream().use { WorldSkeleton.read(it) }
        val site = WorldSite.of(sk, town.seed, town.scale.toDouble()) ?: error("no hub of the skeleton has the plan ${town.seed}")
        println("  world: read in %.0f ms; %s".format((System.nanoTime() - t0) / 1e6, site.report))
        return site
    }

    fun attach(ft: FilamentTown, town: Town, site: WorldSite): WorldFar = WorldFar.attach(ft, {
        val t0 = System.nanoTime()
        site.plan(town.year).also { println("  world: far view planned in %.0f ms: %s".format((System.nanoTime() - t0) / 1e6, it.report)) }
    }, site.origin, near = System.getProperty("worldNear") != "0")

    /** A free camera over ([tx], [ty], [tz]) (street units), [yaw] (0 north, clockwise), [pitch] down, [dist] back, [fov] tall. */
    private fun pose(tx: Float, ty: Float, tz: Float, yaw: Float, pitch: Float, dist: Float, fov: Float, w: Int, h: Int, far: Float, hazeKm: Float): TownPose {
        val ya = Math.toRadians(yaw.toDouble()); val pa = Math.toRadians(pitch.toDouble())
        val cp = cos(pa).toFloat(); val sp = sin(pa).toFloat()
        val fx = sin(ya).toFloat() * cp; val fy = -sp; val fz = -cos(ya).toFloat() * cp
        val fl = (h / 2f) / tan(Math.toRadians(fov / 2.0)).toFloat()
        val ex = tx - fx * dist; val ey = ty - fy * dist; val ez = tz - fz * dist
        val u = 44.5f / 2.1f
        // (a thin haze: [hazeKm] km for a third of the light, uniform; the shadows on the town only)
        return TownPose(ex, ey, ez, fx, fy, fz, fl, w / 2f, h / 2f, w.toFloat(), h.toFloat(), max(4f, dist * .01f), far, 2000f * u, 1f / (hazeKm * 1000f * u * 2.5f),
            shadowFar = max(3000f, dist * 2f))
    }

    /**
     * A camera of the open world's kind ([TownPose.open]: the sun fixed in the world, the air's own haze with the world
     * round the town) at ([ex], [ey], [ez]) (street units), looking [yaw] degrees (0 north, clockwise) and [pitch] down,
     * [fov] tall; the whole town drawn (no range), the shadows [shadowM] deep.
     */
    private fun eyePose(ex: Float, ey: Float, ez: Float, yaw: Float, pitch: Float, fov: Float, w: Int, h: Int, far: Float, u: Float, shadowM: Float = 300f): TownPose {
        val ya = Math.toRadians(yaw.toDouble()); val pa = Math.toRadians(pitch.toDouble())
        val cp = cos(pa).toFloat(); val sp = sin(pa).toFloat()
        val fx = sin(ya).toFloat() * cp; val fy = -sp; val fz = -cos(ya).toFloat() * cp
        val fl = (h / 2f) / tan(Math.toRadians(fov / 2.0)).toFloat()
        return TownPose(ex, ey, ez, fx, fy, fz, fl, w / 2f, h / 2f, w.toFloat(), h.toFloat(), 4f, far, 0f, 0f, open = true, shadowFar = shadowM * u)
    }

    /**
     * W5a's views (-Dshots=w5): the near terrain from the ground at a person's height looking out of town, from a bike's
     * height on a country road, from 200 m over the farmland, a cliff close up, and from 120 m over the town's edge looking
     * out past where the near terrain hands over to the far view (4 km).
     */
    private fun w5(shot: (String, String, TownPose, Float) -> Unit, town: Town, site: WorldSite, plan: WorldFarPlan, far: WorldFar, f: com.lifemax.rpg.feature.habits.domain.HubFrame,
                   g: FloatArray, u: Float, w: Int, h: Int, reach: Float, mx: Float, mz: Float) {
        val near = far.near ?: return
        val t = near.terrain ?: run { near.settle(eyePose(mx, 2000f, mz, 0f, 30f, 50f, w, h, reach, u)); near.terrain } ?: return
        // (street units of the town's frame are plan-local metres times u)
        fun ground(sx: Float, sz: Float): Float = (t.heightLocal(sx / u.toDouble(), sz / u.toDouble()) * u).toFloat()
        val tg = com.lifemax.rpg.feature.habits.presentation.scene3d.TownGround.boundsOf(town)
        fun yawTo2(dx: Float, dz: Float) = yawTo(dx.toDouble(), dz.toDouble())
        val hour = 15f
        // 1. a person at the town's east edge (on its grass, 25 m in), looking east out of town along the highway's line
        val east = site.hub.pins.first { it.kind == "highway" && it.name == "east" }.let { WorldPos.ofCells(it.x, it.z) }.let { floatArrayOf(f.streetX(it).toFloat(), f.streetZ(it).toFloat()) }
        run {
            val x = tg[2] - 25f * u; val z = east[1] + 30f * u
            shot("w5_ground", "a person at the town's east edge, 1.7 m up, looking east out of town", eyePose(x, ground(x, z) + 1.7f * u, z, 92f, 1.5f, 55f, w, h, reach, u), hour)
        }
        // 2. a bike on a country road: a county road or highway 1.5-3 km out of town, 1.5 m up, looking along it away from town
        run {
            val lines = plan.lines.filter { it.kind == WorldFarPlan.COUNTY || it.kind == WorldFarPlan.HIGHWAY }
            var best: FloatArray? = null
            for (l in lines.sortedBy { it.kind }) {
                val p = l.pts
                for (k in 0 until p.size / 2 - 1) {
                    val x0 = p[k * 2]; val z0 = p[k * 2 + 1]; val x1 = p[k * 2 + 2]; val z1 = p[k * 2 + 3]
                    val o = plan.outside(x0, z0)
                    if (o !in 1500.0..3000.0 || hypot(x1 - x0, z1 - z0) < 80) continue
                    val away = plan.outside(x1, z1) > o
                    val dx = if (away) x1 - x0 else x0 - x1; val dz = if (away) z1 - z0 else z0 - z1
                    best = floatArrayOf(((x0 + x1) / 2).toFloat() * u, ((z0 + z1) / 2).toFloat() * u, dx.toFloat(), dz.toFloat(), l.kind.toFloat())
                    break
                }
                if (best != null) break
            }
            best?.let { b ->
                val yaw = yawTo2(b[2], b[3])
                val kind = if (b[4].toInt() == WorldFarPlan.HIGHWAY) "the highway" else "a county road"
                shot("w5_bike", "a bike's height (1.5 m) on $kind %.1f km out of town, looking along it".format(plan.outside(b[0] / u.toDouble(), b[1] / u.toDouble()) / 1000),
                    eyePose(b[0], ground(b[0], b[1]) + 1.5f * u, b[1], yaw, 2f, 55f, w, h, reach, u), hour)
            }
        }
        // 3. 200 m over the farmland: the sector 1.5-3 km out with the most fields
        run {
            var bestN = -1; var at = floatArrayOf(0f, 0f, 0f)
            for (s in 0 until 16) {
                val a = Math.toRadians(s * 22.5)
                var n = 0
                var cand = floatArrayOf(0f, 0f, 0f)
                for (r in listOf(1600.0, 2000.0, 2400.0, 2800.0)) {
                    val cxm = (mx / u) + sin(a) * (r + (g[2] - g[0]) / u / 2); val czm = (mz / u) - cos(a) * (r + (g[3] - g[1]) / u / 2)
                    val p = com.lifemax.rpg.feature.habits.domain.WorldTerrain.Point()
                    t.pointAt((plan.origin.x + cxm) / com.lifemax.rpg.feature.habits.domain.Planet.CELL_M, (plan.origin.z + czm) / com.lifemax.rpg.feature.habits.domain.Planet.CELL_M, cxm, czm, p)
                    val mat = t.material((plan.origin.x + cxm) / com.lifemax.rpg.feature.habits.domain.Planet.CELL_M, (plan.origin.z + czm) / com.lifemax.rpg.feature.habits.domain.Planet.CELL_M, cxm, czm, p, 0.0)
                    if (mat == com.lifemax.rpg.feature.habits.domain.WorldTerrain.FARM_X || mat == com.lifemax.rpg.feature.habits.domain.WorldTerrain.FARM_Z || mat == com.lifemax.rpg.feature.habits.domain.WorldTerrain.DIRT) n++
                    if (r == 2000.0) cand = floatArrayOf(cxm.toFloat() * u, czm.toFloat() * u, (s * 22.5).toFloat())
                }
                if (n > bestN) { bestN = n; at = cand }
            }
            val yaw = at[2]
            val back = 420f * u
            val ex = at[0] - sin(Math.toRadians(yaw.toDouble())).toFloat() * back; val ez = at[1] + cos(Math.toRadians(yaw.toDouble())).toFloat() * back
            val ey = ground(ex, ez) + 200f * u
            val pitch = Math.toDegrees(kotlin.math.atan2((ey - ground(at[0], at[1])).toDouble(), back.toDouble())).toFloat()
            shot("w5_farm200", "200 m over the farmland %s of town, looking %s".format(compass(yaw.toDouble()), compass(yaw.toDouble())), eyePose(ex, ey, ez, yaw, pitch, 55f, w, h, reach, u, 600f), hour)
        }
        // 4. a cliff close up: the steepest ground found 1.2-7 km out (a coarse search of the near terrain's reach)
        run {
            val l0 = plan.levels.first()
            var best = -1.0; var bx = 0.0; var bz = 0.0
            val cm = com.lifemax.rpg.feature.habits.domain.Planet.CELL_M
            val pt = com.lifemax.rpg.feature.habits.domain.WorldTerrain.Point()
            var x = l0.ox + 2500.0
            while (x < l0.ox + l0.n * l0.spacing - 2500.0) {
                var z = l0.oz + 2500.0
                while (z < l0.oz + l0.n * l0.spacing - 2500.0) {
                    if (plan.outside(x, z) > 1200.0) {
                        // (the cliff as the ground has it: calmed near the roads and rivers)
                        val c = t.pointAt((plan.origin.x + x) / cm, (plan.origin.z + z) / cm, x, z, pt).cliff
                        if (c > best) { best = c; bx = x; bz = z }
                    }
                    z += 60.0
                }
                x += 60.0
            }
            // the camera downhill of it, 70 m off, 6 m up, looking up at the face
            val e = .5 / cm
            val gx = t.smooth((plan.origin.x + bx) / cm + e, (plan.origin.z + bz) / cm) - t.smooth((plan.origin.x + bx) / cm - e, (plan.origin.z + bz) / cm)
            val gz = t.smooth((plan.origin.x + bx) / cm, (plan.origin.z + bz) / cm + e) - t.smooth((plan.origin.x + bx) / cm, (plan.origin.z + bz) / cm - e)
            val gl = hypot(gx, gz).coerceAtLeast(1e-9)
            val cx = (bx - gx / gl * 75.0).toFloat() * u; val cz = (bz - gz / gl * 75.0).toFloat() * u
            val tx = bx.toFloat() * u; val tz = bz.toFloat() * u
            val ey = ground(cx, cz) + 6f * u
            val ty = ground(tx, tz)
            val pitch = -Math.toDegrees(kotlin.math.atan2((ty - ey).toDouble(), 75.0 * u)).toFloat()
            // (the hour whose sun rakes the face from one side, about 55 degrees off its downhill way: its ledges and risers show)
            val face = Math.toDegrees(kotlin.math.atan2(-gx, gz)).let { (it + 360) % 360 }
            val lit = (8..17).minByOrNull { hr -> val az = com.lifemax.rpg.feature.habits.presentation.scene.OrbitCamera.sunAzimuth(hr.toFloat(), false).toDouble(); val d = abs(((az - face + 540) % 360) - 180); abs(d - 55) }!!.toFloat()
            shot("w5_cliff", "a cliff close up (the most cliff found 1.2-7 km out: %.2f, %.1f km %s of town), from 75 m downhill at %.0f h".format(best, plan.outside(bx, bz) / 1000, compass(Math.toDegrees(kotlin.math.atan2(bx - mx / u, -(bz - mz / u))).let { (it + 360) % 360 }), lit),
                eyePose(cx, ey, cz, yawTo2(tx - cx, tz - cz), pitch, 60f, w, h, reach, u, 200f), lit)
            // and the same cliff from higher and further, to see it in its ground
            val cx2 = (bx - gx / gl * 260.0).toFloat() * u; val cz2 = (bz - gz / gl * 260.0).toFloat() * u
            val ey2 = ground(cx2, cz2) + 60f * u
            shot("w5_cliff_wide", "the same cliff from 260 m off, 60 m up", eyePose(cx2, ey2, cz2, yawTo2(tx - cx2, tz - cz2), Math.toDegrees(kotlin.math.atan2((ey2 - ty).toDouble(), 260.0 * u)).toFloat(), 55f, w, h, reach, u, 500f), lit)
        }
        // 5. 120 m over the town's south-east corner, looking out across the near terrain to where the far view takes over (3-4 km)
        run {
            val x = g[2] + 100f * u; val z = g[3] + 100f * u
            shot("w5_4km", "120 m over the town's south-east corner looking south-east: the near terrain to about 4 km, the far view beyond", eyePose(x, ground(x, z) + 120f * u, z, 135f, 6f, 60f, w, h, reach, u, 600f), hour)
            shot("w5_4km_far", "the same with the near terrain hidden: the far view alone (W4 with W5a's materials)", eyePose(x, ground(x, z) + 120f * u, z, 135f, 6f, 60f, w, h, reach, u, 600f).also { near.hidden = true }, hour)
            near.hidden = false
        }
    }

    private fun compass(deg: Double): String = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")[(((deg + 22.5) % 360) / 45).toInt() % 8]

    private fun yawTo(dx: Double, dz: Double): Float = Math.toDegrees(atan2(dx, -dz)).toFloat()

    fun shots(dir: File, ft: FilamentTown, shooter: Shooter, town: Town, site: WorldSite, far: WorldFar, report: StringBuilder, want: (String) -> Boolean, w: Int, h: Int) {
        val f = site.frame; val sk = site.skeleton
        val plan = far.plan ?: error("the far view was not built")
        val u = f.unitsPerMetre.toFloat()
        val reach = (plan.reach * u * 1.02).toFloat()
        val g = f.ground
        val mx = (g[0] + g[2]) / 2f; val mz = (g[1] + g[3]) / 2f
        fun street(x: Double, z: Double) = WorldPos.ofCells(x, z).let { floatArrayOf(f.streetX(it).toFloat(), f.streetZ(it).toFloat()) }
        fun ground(sx: Float, sz: Float) = plan.surface(sx / u.toDouble(), sz / u.toDouble()) * u
        val sheet = ArrayList<Pair<String, String>>()
        // (no townspeople: they stand for the player's camera, not these)
        ft.people(emptyList(), 0, emptyList())
        fun shot(name: String, caption: String, p: TownPose, hour: Float = 15f) {
            if (!want(name)) return
            val t0 = System.nanoTime()
            // (the near terrain made for this camera at once, as the game's streaming would have it by the time it got there)
            far.near?.settle(p)
            val t1 = System.nanoTime()
            ft.prepare(p); ft.frame(p, hour)
            shooter.save(File(dir, "$name.png"), w, h, hour)
            val s = ft.stats
            report.appendLine("%-22s %s; draws %d, triangles %d (near terrain %d nodes, %d triangles; settled in %.0f ms); %.0f ms".format(name, caption, s.draws, s.triangles,
                far.near?.drawnNodes ?: 0, far.near?.drawnTriangles ?: 0, (t1 - t0) / 1e6, (System.nanoTime() - t0) / 1e6))
            sheet += name to caption
            println("  $name")
        }
        // 1. the capital from high above, the world to the horizon (looking north-east over the town)
        shot("world_above", "the capital from 4 km up, the world to the horizon",
            pose(mx, 0f, mz, 35f, 24f, 10_000f * u, 55f, w, h, reach, 90f))
        // 2. the town's edge where it meets the countryside: east, by the highway out, from 150 m
        val east = site.hub.pins.first { it.kind == "highway" && it.name == "east" }.let { street(it.x, it.z) }
        val ex = g[2] + 900f * u
        shot("world_edge", "the town's east edge and the country past it, by the highway out",
            pose(ex, ground(ex, east[1]), east[1], 250f, 14f, 1800f * u, 55f, w, h, reach, 40f))
        // 3. the river leaving town: the south pin's way, 2 km down the river, looking back up it to the town
        val river = sk.rivers.first { rv -> sk.points(rv.path).let { p -> (0 until p.size / 2).any { k -> hypot(p[k * 2] - site.hub.x, p[k * 2 + 1] - site.hub.z) < .4 } } }
        val rp = sk.points(river.path).let { p -> (0 until p.size / 2).map { street(p[it * 2], p[it * 2 + 1]) } }
        val out = rp.firstOrNull { it[1] > g[3] + 2500f * u } ?: rp.last()
        shot("world_river", "the river leaving town to the south, looking back up it to the town",
            pose(out[0], ground(out[0], out[1]), out[1], yawTo((mx - out[0]).toDouble(), (mz - out[1]).toDouble()), 22f, 2600f * u, 55f, w, h, reach, 40f))
        // 4. the rail line leaving toward the next hub (the main line out of the east portal)
        val line = sk.railLines.filter { site.hub.id in it.stations && it.cls == "main" }.maxByOrNull { sk.points(it.path).let { p -> if (it.a == site.hub.id) p[2] else p[p.size - 2] } } ?: sk.railLines.first { site.hub.id in it.stations }
        val lp = sk.points(line.path).let { p -> (0 until p.size / 2).map { street(p[it * 2], p[it * 2 + 1]) } }.let { if (line.a == site.hub.id) it else it.reversed() }
        val far1 = lp.firstOrNull { hypot(it[0] - lp[0][0], it[1] - lp[0][1]) > 6000f * u } ?: lp.last()
        val to = sk.place(if (line.a == site.hub.id) line.b!! else line.a!!)
        shot("world_rail", "the main line out of the east portal toward ${to?.name ?: "the next hub"} (${line.id})",
            pose(lp[0][0], 0f, lp[0][1], yawTo((far1[0] - lp[0][0]).toDouble(), (far1[1] - lp[0][1]).toDouble()), 16f, 1500f * u, 55f, w, h, reach, 40f))
        // 5. a high camera over the town's edge looking out the way most of the other towns lie (8-40 km), and a long lens
        // on the nearest big town
        fun look(name: String, caption: String, fromX: Float, fromZ: Float, toX: Float, toZ: Float, up: Float, fov: Float, hour: Float) {
            val d = hypot(toX - fromX, toZ - fromZ).coerceAtLeast(1f)
            val eyeY = ground(fromX, fromZ) + up * u
            val ty = ground(toX, toZ) + 20f * u
            val pitch = Math.toDegrees(kotlin.math.atan2((eyeY - ty).toDouble(), d.toDouble())).toFloat()
            shot(name, caption, pose(toX, ty, toZ, yawTo((toX - fromX).toDouble(), (toZ - fromZ).toDouble()), pitch, hypot(d, eyeY - ty), fov, w, h, reach, 70f), hour)
        }
        val sectors = IntArray(16)
        for (b in plan.boxes) {
            val d = hypot(b.x - mx / u, b.z - mz / u)
            if (d in 8000f..40000f) sectors[(((Math.toDegrees(kotlin.math.atan2(b.x - mx / u.toDouble(), -(b.z - mz / u.toDouble()))) + 360.0) % 360.0) / 22.5).toInt() % 16]++
        }
        val sector = sectors.indices.maxByOrNull { sectors[it] }!!
        val az = Math.toRadians(sector * 22.5 + 11.25)
        val dirX = sin(az).toFloat(); val dirZ = -cos(az).toFloat()
        val fromX = mx + dirX * (g[2] - g[0]) / 2f; val fromZ = mz + dirZ * (g[3] - g[1]) / 2f
        look("world_high", "a camera 700 m over the town's edge, looking %s across the plain (%d town blocks that way 8-40 km off)".format(compass(sector * 22.5 + 11.25), sectors[sector]),
            fromX, fromZ, fromX + dirX * 22_000f * u, fromZ + dirZ * 22_000f * u, 700f, 40f, 15f)
        look("world_high_dusk", "the same at dusk: the towns' lit windows", fromX, fromZ, fromX + dirX * 22_000f * u, fromZ + dirZ * 22_000f * u, 700f, 40f, 19.6f)
        val big = sk.places.filter { it.id != site.hub.id && (it.kind == "hub" || it.rank == "major" || it.rank == "town") }.map { it to street(it.x, it.z) }
            .filter { hypot(it.second[0] - mx, it.second[1] - mz) / u in 12_000f..60_000f }.minByOrNull { hypot(it.second[0] - mx, it.second[1] - mz) }
        big?.let { (p, at) ->
            val dx = at[0] - mx; val dz = at[1] - mz; val dd = hypot(dx, dz)
            val fx = mx + dx / dd * ((g[2] - g[0]) / 2f); val fz = mz + dz / dd * ((g[3] - g[1]) / 2f)
            look("world_towns", "${p.name} (a ${p.type} town, %.0f km off) from the capital's edge, 250 m up (a 7 degree lens)".format(dd / u / 1000), fx, fz, at[0], at[1], 250f, 7f, 15f)
        }
        w5(::shot, town, site, plan, far, f, g, u, w, h, reach, mx, mz)
        report.appendLine(far.report)
        far.near?.let { report.appendLine(it.report) }
        report.appendLine(WorldMemory.line())
        File(dir, "world_shots.txt").writeText(sheet.joinToString("\n") { "${it.first}\t${it.second}" })
    }
}
