import com.lifemax.rpg.feature.habits.domain.Planet
import com.lifemax.rpg.feature.habits.domain.WorldMemory
import com.lifemax.rpg.feature.habits.domain.WorldPos
import com.lifemax.rpg.feature.habits.domain.WorldSkeleton
import com.lifemax.rpg.feature.habits.presentation.scene3d.FilamentTown
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownFrame
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownPose
import com.lifemax.rpg.feature.habits.presentation.scene3d.WorldFar
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * The open country's views (docs/WORLD_STREAM.md, step 1b): the capital built with the world round it, then the camera
 * jumped to each view along the drive to Evanvale ([WorldDrive]) and at the nearest coast, the world settled round it
 * (re-plan, near terrain, the country's trees and things), one render each (desktop, software GL: not S25 captures) into
 * build/world-views, with a line each in views.txt. Quicker than GpuWorldDriveTest (no drive): for judging the look.
 *
 * gradle :desktop:crossroadsTest --tests WorldViewsTest   (-DworldViews=name,name: only those; WORLD_VIEWS_OUT: elsewhere)
 */
class WorldViewsTest {
    @Test fun theOpenCountrysViews() {
        System.setProperty("gpuOnly", "1"); System.setProperty("world", "1")
        // (WORLD_OCEAN=0/1, WORLD_PROPS=0: the near ocean on or off, the country's trees and things off, for comparisons)
        System.getenv("WORLD_OCEAN")?.let { System.setProperty("worldOcean", it) }
        System.getenv("WORLD_PROPS")?.let { System.setProperty("worldProps", it) }
        val out = File(System.getenv("WORLD_VIEWS_OUT") ?: "build/world-views").also { it.mkdirs() }
        val log = StringBuilder()
        var ok = false
        openBuilt = { ft -> views(ft, out, log); ok = true }
        try { renderFilamentOpen(out.path) } finally { openBuilt = null; System.clearProperty("gpuOnly"); System.clearProperty("world") }
        File(out, "views.txt").writeText(log.toString())
        println("the open country's views:\n$log")
        check(ok) { "the views were not shot" }
    }

    private fun views(ft: FilamentTown, out: File, log: StringBuilder) {
        val far = openWorld ?: error("no world round the town (-Dworld=1)")
        val site = WorldDrive.site
        val u = site.frame.unitsPerMetre.toFloat()
        val pts = WorldDrive.drive.street(site).let { s -> DoubleArray(s.size) { s[it].toDouble() } }
        val w = 2340; val h = 1080
        val only = (System.getProperty("worldViews") ?: System.getenv("WORLD_VIEWS"))?.split(",")?.map { it.trim() }?.toSet()
        fun ground(sx: Float, sz: Float): Float = far.terrain?.heightLocal(sx / u.toDouble(), sz / u.toDouble())?.toFloat()?.times(u) ?: 0f
        val p = DoubleArray(4)
        var clock = 0.0
        fun frame(pose: TownPose, hour: Float) { ft.buildNearest(pose.ex, pose.ez, 4f); ft.frame(pose, hour, clock); clock += 1 / 60.0 }
        fun settle(pose: TownPose, hour: Float) {
            var k = 0
            fun settled(): Boolean {
                val m = far.plan?.middle ?: return false
                val at = site.frame.world(pose.ex.toDouble(), pose.ez.toDouble())
                return k > 60 && !far.streaming && far.near?.pending != true && far.props?.pending != true &&
                    maxOf(abs(m.dx(at)), abs(m.dz(at))) < 3400
            }
            while (!settled() && k < 30000) { frame(pose, hour); k++; if (k % 4 == 0) Thread.sleep(2) }
            log.appendLine("  settled in %d frames".format(k))
        }
        val shooter = Shooter(ft.engine, ft)
        fun shot(name: String, caption: String, pose: TownPose, hour: Float = 13f) {
            if (only != null && name !in only) return
            val t0 = System.nanoTime()
            settle(pose, hour)
            frame(pose, hour); frame(pose, hour)
            shooter.save(File(out, "$name.png"), 1170, 540, hour)
            log.appendLine("$name: $caption; draws %d, triangles %d; settled in %.1f s; %s".format(ft.stats.draws, ft.stats.triangles, (System.nanoTime() - t0) / 1e9, WorldMemory.line()))
            for (l in ft.extraReport) if (l.startsWith("world")) log.appendLine("    $l")
        }
        fun chase(km: Double, back: Boolean = false): TownPose {
            WorldDrive.along(pts, km * 1000 * u, p)
            val x = p[0].toFloat(); val z = p[1].toFloat()
            val yaw = Math.toDegrees(atan2(p[2], -p[3])).toFloat() + if (back) 180f else 0f
            return TownFrame.orbit(x, ground(x, z) + 1.4f * u, z, yaw, 12f, 520f, w.toFloat(), h.toFloat())
        }
        // the step 1 views
        run {
            WorldDrive.along(pts, 2500.0 * u, p)
            val x = p[0].toFloat(); val z = p[1].toFloat()
            val mx = ft.slots.map { it.cx }.sorted().let { it[it.size / 2] }; val mz = ft.slots.map { it.cz }.sorted().let { it[it.size / 2] }
            val yaw = Math.toDegrees(atan2((mx - x).toDouble(), -(mz - z).toDouble())).toFloat()
            shot("leaving_capital", "2.5 km out on the road, 30 m up, looking back at the capital's downtown (a 30 degree lens)", eye(x, ground(x, z) + 30f * u, z, yaw, 1.2f, 30f, w, h, u))
        }
        shot("open_country_12km", "12 km out of the capital, the chase camera", chase(12.0))
        shot("open_country_21km", "21 km out of the capital, the chase camera", chase(21.0))
        run {
            WorldDrive.along(pts, 21000.0 * u, p)
            val x = p[0].toFloat(); val z = p[1].toFloat()
            val yaw = Math.toDegrees(atan2(p[2], -p[3])).toFloat()
            shot("far_view_300m", "300 m over the road 21 km out, looking on along it", eye(x, ground(x, z) + 300f * u, z, yaw, 6f, 55f, w, h, u))
        }
        // the new views: roads through the country, a farm valley from a hillside, a beach
        for (km in listOf(6.0, 10.0, 14.0, 17.0)) shot("road_%02.0fkm".format(km), "the chase camera %.0f km out of the capital".format(km), chase(km))
        run {
            WorldDrive.along(pts, 16000.0 * u, p)
            val x = p[0].toFloat(); val z = p[1].toFloat()
            val yaw = Math.toDegrees(atan2(-p[3], -p[2])).toFloat()
            shot("farm_valley", "120 m up beside the road 16 km out, looking across the farms", eye(x, ground(x, z) + 120f * u, z, yaw, 12f, 50f, w, h, u))
        }
        for (km in listOf(5.0, 9.0, 16.0, 19.0)) {
            WorldDrive.along(pts, km * 1000 * u, p)
            // (40 m up beside the road, looking out across the land at right angles to it, a little down)
            val x = p[0].toFloat(); val z = p[1].toFloat()
            val yaw = Math.toDegrees(atan2(-p[3], -p[2])).toFloat()
            shot("valley_%02.0fkm".format(km), "40 m up beside the road %.0f km out, looking across the land".format(km), eye(x, ground(x, z) + 40f * u, z, yaw, 7f, 50f, w, h, u))
        }
        coast(site.skeleton, pts, u)?.let { c ->
            val ya = Math.toRadians(c[2].toDouble())
            val x = c[0] + (sin(ya) * 4000 * u).toFloat(); val z = c[1] - (cos(ya) * 4000 * u).toFloat()
            shot("coast_ocean", "a coast %.0f km from the capital, from 4 km out at sea, 60 m up, looking at the land".format(hypot(x, z) / u / 1000),
                eye(x, (far.plan?.seaLevel ?: 0f) * u + 60f * u, z, c[2] + 180f, 2f, 45f, w, h, u))
            // the beach: walk from the land cell towards the sea until the ground is at the sea, stand 2 m up a little inland, look along it
            val sea = far.plan?.seaLevel ?: 0f
            var bx = c[0]; var bz = c[1]
            val dx = sin(ya).toFloat(); val dz = -cos(ya).toFloat()
            // (settle round the shore first, so the terrain is the near one)
            settle(eye(bx, sea * u + 50f * u, bz, c[2], 10f, 50f, w, h, u), 13f)
            var k = 0
            bx -= dx * 800f * u; bz -= dz * 800f * u
            while (k < 4000 && ground(bx, bz) > sea * u + 0.8f * u) { bx += dx * 2f * u; bz += dz * 2f * u; k++ }
            val sx = bx; val sz = bz
            bx -= dx * 40f * u; bz -= dz * 40f * u
            shot("beach", "on the shore 55 m from the water, 4 m up, looking along the beach", eye(bx, ground(bx, bz) + 4f * u, bz, c[2] + 75f, 9f, 55f, w, h, u))
            shot("beach_sea", "the same spot looking out to sea", eye(bx, ground(bx, bz) + 4f * u, bz, c[2], 8f, 55f, w, h, u))
            shot("beach_surf", "18 m over the water's edge, looking along the shore and down at the surf", eye(sx - dx * 10f * u, sea * u + 18f * u, sz - dz * 10f * u, c[2] + 100f, 20f, 55f, w, h, u))
            // (the near ocean close up, as GpuWorldDriveTest's: 6 m over the waves 4 km out)
            if (far.ocean != null) shot("ocean_close", "6 m over the near ocean's waves, looking down 14 degrees", eye(x, (far.plan?.seaLevel ?: 0f) * u + 6f * u, z, c[2] + 150f, 14f, 55f, w, h, u))
        }
    }

    /** The nearest coast to the drive's end (street units x, z and a yaw looking out to sea), or null (GpuWorldDriveTest's). */
    private fun coast(sk: WorldSkeleton, pts: DoubleArray, u: Float): FloatArray? {
        val p = DoubleArray(4)
        WorldDrive.along(pts, 1e12, p)
        val f = WorldDrive.site.frame
        val wp = f.world(p[0], p[1])
        val ci = Math.floor(wp.x / Planet.CELL_M).toInt(); val cj = Math.floor(wp.z / Planet.CELL_M).toInt()
        for (r in 1..200) for (dj in -r..r) for (di in -r..r) {
            if (maxOf(abs(di), abs(dj)) != r) continue
            val i = ci + di; val j = cj + dj
            if (!sk.land(i, j) || sk.elev(i, j) > 40 || (-1..1).any { a -> (-1..1).any { b -> sk.land(i + a, j + b) && sk.elev(i + a, j + b) > 80 } }) continue
            for ((ox, oz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                if (!sk.land(i + ox * 2, j + oz * 2) && sk.biome(i + ox * 2, j + oz * 2) == 0 && !sk.land(i + ox * 4, j + oz * 4)) {
                    val at = WorldPos.ofCells(i + .5 - ox * .3, j + .5 - oz * .3)
                    val yaw = Math.toDegrees(atan2(ox.toDouble(), -oz.toDouble())).toFloat()
                    return floatArrayOf(f.streetX(at).toFloat(), f.streetZ(at).toFloat(), yaw)
                }
            }
        }
        return null
    }

    private fun eye(ex: Float, ey: Float, ez: Float, yaw: Float, pitch: Float, fov: Float, w: Int, h: Int, u: Float): TownPose {
        val ya = Math.toRadians(yaw.toDouble()); val pa = Math.toRadians(pitch.toDouble())
        val cp = cos(pa).toFloat(); val sp = sin(pa).toFloat()
        val fx = sin(ya).toFloat() * cp; val fy = -sp; val fz = -cos(ya).toFloat() * cp
        val fl = (h / 2f) / tan(Math.toRadians(fov / 2.0)).toFloat()
        return TownPose(ex, ey, ez, fx, fy, fz, fl, w / 2f, h / 2f, w.toFloat(), h.toFloat(), 4f, 1e8f, 0f, 0f, open = true, shadowFar = 300f * u)
    }
}
