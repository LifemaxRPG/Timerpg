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
    }, site.origin)

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
            ft.prepare(p); ft.frame(p, hour)
            shooter.save(File(dir, "$name.png"), w, h, hour)
            val s = ft.stats
            report.appendLine("%-22s %s; draws %d, triangles %d; %.0f ms".format(name, caption, s.draws, s.triangles, (System.nanoTime() - t0) / 1e6))
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
        report.appendLine(far.report)
        report.appendLine(WorldMemory.line())
        File(dir, "world_shots.txt").writeText(sheet.joinToString("\n") { "${it.first}\t${it.second}" })
    }
}
