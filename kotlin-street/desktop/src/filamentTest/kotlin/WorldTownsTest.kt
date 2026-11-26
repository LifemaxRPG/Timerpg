import com.lifemax.rpg.feature.habits.domain.Planet
import com.lifemax.rpg.feature.habits.domain.WorldMemory
import com.lifemax.rpg.feature.habits.domain.WorldPos
import com.lifemax.rpg.feature.habits.domain.WorldTownSet
import com.lifemax.rpg.feature.habits.presentation.scene3d.FilamentTown
import com.lifemax.rpg.feature.habits.presentation.scene3d.GpuAccount
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownFrame
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownPose
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * The generated towns' views (planet step 2, docs/TOWNS_EVERYWHERE.md "Streaming"): the capital built with the world and
 * the baked towns round it (kotlin-street/build-towns/alpha, tools/towns-bake.ts), then the camera jumped to each view along
 * the tour ([WorldDrive.drive] to Evanvale, [WorldDrive.tour] on to Roserdale and Chaley), the world and the nearest towns
 * settled round it (re-plan, near terrain, the guest towns built), one render each (desktop, software GL: not S25 captures)
 * into build/world-towns, with a line each in views.txt: GPU, the world's share, the guests and their build times.
 *
 * gradle :desktop:crossroadsTest --tests WorldTownsTest   (WORLD_VIEWS=name,name: only those; WORLD_VIEWS_OUT: elsewhere)
 */
class WorldTownsTest {
    @Test fun theGeneratedTownsViews() {
        System.setProperty("gpuOnly", "1"); System.setProperty("world", "1")
        GpuAccount.reset()
        val out = File(System.getenv("WORLD_VIEWS_OUT") ?: "build/world-towns").also { it.mkdirs() }
        val log = StringBuilder()
        var ok = false
        openBuilt = { ft -> views(ft, out, log); ok = true }
        try { renderFilamentOpen(out.path) } finally { openBuilt = null; System.clearProperty("gpuOnly"); System.clearProperty("world") }
        File(out, "views.txt").writeText(log.toString())
        println("the generated towns' views:\n$log")
        check(ok) { "the views were not shot" }
    }

    private fun total(ft: FilamentTown): Double =
        ((GpuAccount.categories(ft.gpuEstimate(2340, 1080))["total"] as? Map<*, *>)?.get("mb") as? Number)?.toDouble() ?: 0.0

    private fun views(ft: FilamentTown, out: File, log: StringBuilder) {
        val far = openWorld ?: error("no world round the town (-Dworld=1)")
        val towns = openTowns ?: error("no generated towns (bake them: tools/towns-bake.ts, see TOWNS_EVERYWHERE.md)")
        val site = WorldDrive.site
        val set = towns.set
        val u = site.frame.unitsPerMetre.toFloat()
        val pts = WorldDrive.whole(site)
        // (the pose's screen is the saved picture's: the shooter's swap chain is that size, and a bigger viewport would show only a corner)
        val w = 1170; val h = 540
        val only = System.getenv("WORLD_VIEWS")?.split(",")?.map { it.trim() }?.toSet()
        fun ground(sx: Float, sz: Float): Float = far.terrain?.groundLocal(sx / u.toDouble(), sz / u.toDouble())?.toFloat()?.times(u) ?: 0f
        var clock = 0.0
        fun frame(pose: TownPose, hour: Float) { ft.buildNearest(pose.ex, pose.ez, 4f); ft.frame(pose, hour, clock); clock += 1 / 60.0 }
        fun settle(pose: TownPose, hour: Float) {
            var k = 0
            fun settled(): Boolean {
                val m = far.plan?.middle ?: return false
                val at = site.frame.world(pose.ex.toDouble(), pose.ez.toDouble())
                return k > 120 && !far.streaming && far.near?.pending != true && far.props?.pending != true &&
                    maxOf(abs(m.dx(at)), abs(m.dz(at))) < 3400 && towns.guestList.all { it.builtMs > 0f } && towns.stopList.all { it.lm.done }
            }
            while (!settled() && k < 40000) { frame(pose, hour); k++; if (k % 4 == 0) Thread.sleep(2) }
            log.appendLine("  settled in %d frames".format(k))
        }
        val shooter = Shooter(ft.engine, ft)
        val sheet = ArrayList<String>()
        fun shot(name: String, caption: String, pose: TownPose, hour: Float = 13f) {
            if (only != null && name !in only) return
            val t0 = System.nanoTime()
            settle(pose, hour)
            frame(pose, hour); frame(pose, hour)
            ft.engine.flushAndWait()
            shooter.save(File(out, "$name.png"), 1170, 540, hour)
            sheet += "$name: $caption"
            log.appendLine("$name: $caption; draws %d, triangles %d; settled in %.1f s; GPU %.1f MB; %s".format(ft.stats.draws, ft.stats.triangles,
                (System.nanoTime() - t0) / 1e9, total(ft), WorldMemory.line()))
            log.appendLine("    " + towns.report)
            log.appendLine("    " + GpuAccount.categoryLine(ft.gpuEstimate(2340, 1080)))
            log.appendLine("    the towns' own: %.1f MB: %s".format(GpuAccount.townBytes() / 1048576.0, GpuAccount.townOwners()))
        }
        /** The point [s] along the tour (street units), its heading (dx, dz). */
        val p = DoubleArray(4)
        fun along(s: Double): FloatArray { WorldDrive.along(pts, s, p); return floatArrayOf(p[0].toFloat(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat()) }
        fun yawOf(dx: Float, dz: Float) = Math.toDegrees(atan2(dx.toDouble(), -dz.toDouble())).toFloat()
        val cum = run { var l = 0.0; DoubleArray(pts.size / 2) { k -> if (k > 0) l += hypot(pts[k * 2] - pts[k * 2 - 2], pts[k * 2 + 1] - pts[k * 2 - 1]); l } }
        /** The first point of the tour [m] metres from [t]'s ground (approaching it), as a distance along the tour. */
        fun before(t: WorldTownSet.Town, m: Double, from: Double = 0.0): Double? {
            var s = from
            while (s < cum.last()) {
                val a = along(s)
                val wp = site.frame.world(a[0].toDouble(), a[1].toDouble())
                if (t.outside(wp.x, wp.z) < m) return s
                s += 20 * u
            }
            return null
        }
        fun centreOf(t: WorldTownSet.Town): FloatArray {
            val c = t.world((t.px0 + t.px1) / 2, (t.py0 + t.py1) / 2)
            return floatArrayOf(site.frame.streetX(c).toFloat(), site.frame.streetZ(c).toFloat())
        }
        fun townNamed(n: String) = set.towns.first { it.name == n }
        log.appendLine("the tour: ${WorldDrive.drive}; then ${WorldDrive.tour.joinToString { it.toString() }}; ${"%.1f".format(cum.last() / u / 1000)} km in all")
        log.appendLine("baked towns: ${set.towns.joinToString { "$it ${it.buildings} buildings" }}")
        // 1. arriving in Evanvale: on the road 350 m before its ground, the chase camera, then at a driver's eye
        val ev = townNamed("Evanvale")
        val evc = centreOf(ev)
        /** A camera at ([x], [z]) [up] m over the ground looking at ([tx], [tz]) on the ground. */
        fun look(x: Float, z: Float, up: Float, tx: Float, tz: Float, fov: Float = 55f, range: Float = 3100f): TownPose {
            val ey = ground(x, z) + up * u
            val pitch = Math.toDegrees(atan2((ey - ground(tx, tz)).toDouble(), hypot(tx - x, tz - z).toDouble())).toFloat()
            return eye(x, ey, z, yawOf(tx - x, tz - z), pitch, fov, w, h, u, range)
        }
        // the approach (the owner's favourite of the first sheet: the country road through the woods, the power line beside it)
        before(ev, 350.0)?.let { s ->
            val a = along(s)
            val yaw = yawOf(a[2], a[3])
            shot("approach_evanvale", "the approach to Evanvale: the county road through the woods 350 m before its ground, the chase camera", TownFrame.orbit(a[0], ground(a[0], a[1]) + 1.4f * u, a[1], yaw, 12f, 520f, w.toFloat(), h.toFloat()))
            shot("approach_evanvale_low", "the same at a driver's eye, 1.3 m up", eye(a[0], ground(a[0], a[1]) + 1.3f * u, a[1], yaw, 2f, 55f, w, h, u))
        }
        before(ev, 400.0)?.let { s ->
            val a = along(s)
            shot("arrive_evanvale", "arriving in Evanvale (a town, 221 buildings): first sight from the road 400 m before its ground, 35 m up, looking at its middle", look(a[0], a[1], 35f, evc[0], evc[1], range = 9000f))
            val b = along(s + 250 * u)
            shot("arrive_evanvale_low", "150 m before its ground at a driver's eye (1.3 m), looking at the town", eye(b[0], ground(b[0], b[1]) + 1.3f * u, b[1], yawOf(evc[0] - b[0], evc[1] - b[1]), 1f, 55f, w, h, u))
        }
        // where the road meets the town: from 60 m up, 150 m back along the road, looking at its crossing of the ground's edge
        before(ev, 0.0)?.let { s ->
            val c = along(s)
            val a = along(s - 150 * u)
            shot("entry_evanvale", "the road into Evanvale: where the world's road meets the town's own country road at its ground's edge (60 m up, 150 m back)", look(a[0], a[1], 60f, c[0], c[1]))
        }
        run {
            val c = centreOf(ev)
            shot("evanvale_top", "Evanvale from 1.6 km straight up (its ground's edge, the roads in)", eye(c[0], ground(c[0], c[1]) + 1600f * u, c[1] + 1f, 0f, 89.5f, 60f, w, h, u, range = 1600f * u + 3000f))
            for (g in towns.guestList) { val ft2 = g.ft ?: continue; val fr = g.frame!!
                val mx = ft2.slots.map { it.cx }.average().toFloat(); val mz = ft2.slots.map { it.cz }.average().toFloat()
                val cc = centreOf(g.t)
                log.appendLine("    %s: frame deg %.1f O (%.0f, %.0f) k %.3f; slots' middle local (%.0f, %.0f) = host (%.0f, %.0f); plan centre host (%.0f, %.0f); plan %.0f..%.0f x %.0f..%.0f tiles".format(
                    g.t.name, g.t.deg, fr.ox, fr.oz, fr.k, mx, mz, fr.hostX(mx, mz), fr.hostZ(mx, mz), cc[0], cc[1], g.t.px0, g.t.px1, g.t.py0, g.t.py1)) }
        }
        run {
            val c = centreOf(ev)
            val yaw = (ev.deg + 90).toFloat()
            shot("evanvale_main", "in Evanvale, 25 m over the middle of its plan, looking along Main Street", eye(c[0], ground(c[0], c[1]) + 25f * u, c[1], yaw, 10f, 55f, w, h, u))
            val ya = Math.toRadians((ev.deg + 200))
            val ex = c[0] - (sin(ya) * 900 * u).toFloat(); val ez = c[1] + (cos(ya) * 900 * u).toFloat()
            shot("evanvale_aerial", "Evanvale from 450 m up, 900 m off, looking down at its middle", eye(ex, ground(c[0], c[1]) + 450f * u, ez, yawOf(c[0] - ex, c[1] - ez), 26f, 55f, w, h, u, range = 1000f * u))
            shot("evanvale_night", "the same at night (22:30)", eye(ex, ground(c[0], c[1]) + 450f * u, ez, yawOf(c[0] - ex, c[1] - ez), 26f, 55f, w, h, u, range = 1000f * u), 22.5f)
        }
        // 2. a village from the road: Roserdale 400 m ahead
        val ro = townNamed("Roserdale")
        before(ro, 300.0, before(ev, 0.0) ?: 0.0)?.let { s ->
            val a = along(s)
            val c = centreOf(ro)
            shot("village_from_road", "the village Roserdale (163 buildings) from the road 300 m before its ground, 25 m up, looking at it", look(a[0], a[1], 25f, c[0], c[1], range = 6000f))
        }
        // 5. a roadside site: the nearest to the tour whose model is drawn, from 45 m off its front, 6 m up
        run {
            val sites = towns.roadside.filter { it.kind == "diner" || it.kind == "gas_station" || it.kind == "motel" || it.kind == "truck stop" }
            var best: Pair<com.lifemax.rpg.feature.habits.domain.WorldRoadside.Site, Double>? = null
            var k = 0.0
            while (k < cum.last()) {
                val a = along(k)
                val wp = site.frame.world(a[0].toDouble(), a[1].toDouble())
                for (st in sites) { val d = hypot(Planet.wrapDx(st.x - wp.x), st.z - wp.z); if (d < (best?.second ?: 600.0)) best = st to d }
                k += 200 * u
            }
            best?.first?.let { st ->
                val sp = WorldPos(st.x, st.z)
                val sx = site.frame.streetX(sp).toFloat(); val sz = site.frame.streetZ(sp).toFloat()
                val ex = sx + (st.fx * 55 * u).toFloat() + (st.fz * 25 * u).toFloat(); val ez = sz + (st.fz * 55 * u).toFloat() - (st.fx * 25 * u).toFloat()
                shot("roadside_" + st.kind.replace(' ', '_'), "a roadside ${st.kind} (${st.model.removePrefix("landmark_glb_")}) beside the road %.1f km from the tour, from 60 m off its front, 7 m up".format(best!!.second / 1000),
                    eye(ex, ground(ex, ez) + 7f * u, ez, yawOf(sx - ex, sz - ez), 6f, 55f, w, h, u))
                shot("roadside_" + st.kind.replace(' ', '_') + "_night", "the same at night (22:30)", eye(ex, ground(ex, ez) + 7f * u, ez, yawOf(sx - ex, sz - ez), 6f, 55f, w, h, u), 22.5f)
            }
        }
        // 3. a town's lights at night from a hill: Chaley from the highest ground 2-3.5 km round it, 40 m up
        val ch = townNamed("Chaley")
        run {
            val c = centreOf(ch)
            var best = floatArrayOf(c[0] + 2500f * u, c[1], -1e9f)
            for (k in 0 until 48) for (r in listOf(2000f, 2700f, 3400f)) {
                val a = k / 48.0 * 2 * Math.PI
                val x = c[0] + (cos(a) * r * u).toFloat(); val z = c[1] + (sin(a) * r * u).toFloat()
                val g = ground(x, z)
                if (g > best[2]) best = floatArrayOf(x, z, g)
            }
            val yaw = yawOf(c[0] - best[0], c[1] - best[1])
            val drop = Math.toDegrees(atan2((best[2] + 40f * u - ground(c[0], c[1])).toDouble(), hypot(c[0] - best[0], c[1] - best[1]).toDouble())).toFloat()
            shot("town_night_hill", "Chaley (a town, ${ch.buildings} buildings) at night (22:30) from a hill %.1f km off, 40 m up".format(hypot(c[0] - best[0], c[1] - best[1]) / u / 1000),
                eye(best[0], best[2] + 40f * u, best[1], yaw, drop, 40f, w, h, u), 22.5f)
            shot("town_dusk_hill", "the same at dusk (19:20)", eye(best[0], best[2] + 40f * u, best[1], yaw, drop, 40f, w, h, u), 19.3f)
        }
        // 4. a hamlet from the road
        set.towns.filter { it.rank == "hamlet" }.mapNotNull { t -> before(t, 250.0)?.let { t to it } }.minByOrNull { it.second }?.let { (t, s) ->
            val a = along(s)
            val hc = centreOf(t)
            shot("hamlet_from_road", "the hamlet ${t.name} (${t.buildings} buildings) from the road 250 m before its ground, 20 m up, looking at it", look(a[0], a[1], 20f, hc[0], hc[1], range = 5000f))
        }
        log.appendLine("guests' times:"); for (l in towns.log) log.appendLine("  $l")
        File(out, "sheet.txt").writeText(sheet.joinToString("\n"))
    }

    /** A free camera with the open world's reach ([range]: past it the towns' far boxes, as the player's camera has it; street units). */
    private fun eye(ex: Float, ey: Float, ez: Float, yaw: Float, pitch: Float, fov: Float, w: Int, h: Int, u: Float, range: Float = 3100f): TownPose {
        val ya = Math.toRadians(yaw.toDouble()); val pa = Math.toRadians(pitch.toDouble())
        val cp = cos(pa).toFloat(); val sp = sin(pa).toFloat()
        val fx = sin(ya).toFloat() * cp; val fy = -sp; val fz = -cos(ya).toFloat() * cp
        val fl = (h / 2f) / tan(Math.toRadians(fov / 2.0)).toFloat()
        return TownPose(ex, ey, ez, fx, fy, fz, fl, w / 2f, h / 2f, w.toFloat(), h.toFloat(), 4f, 1e8f, range - 1000f, TownFrame.OPEN_HAZE_DENSITY, range = range, open = true,
            lodFar = range - 800f, shadowFar = 300f * u)
    }

    @Suppress("unused") private val keep = listOf(Planet.TILE_M, WorldPos(0.0, 0.0))
}
