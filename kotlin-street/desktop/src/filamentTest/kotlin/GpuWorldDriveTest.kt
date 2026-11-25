import com.lifemax.rpg.feature.habits.domain.Planet
import com.lifemax.rpg.feature.habits.domain.WorldMemory
import com.lifemax.rpg.feature.habits.domain.WorldPos
import com.lifemax.rpg.feature.habits.domain.WorldSkeleton
import com.lifemax.rpg.feature.habits.presentation.scene3d.FilamentTown
import com.lifemax.rpg.feature.habits.presentation.scene3d.GpuAccount
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownBudget
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownFrame
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownPose
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * The world streamer's drive (docs/WORLD_STREAM.md): the capital built as the open-world preview builds it with the world
 * round it (-Dworld=1: the far view, the near terrain, the horizon ring, the near ocean), then a car's chase camera driven
 * out of the capital along the road to the next town on the continent ([WorldDrive]) at 120 km/h, played frame by frame at
 * 60 Hz paced to the wall clock (the workers get the time they would on the phone), as GpuRouteTest plays the capital's
 * route. Every [SAMPLE] frames Filament's whole count and the world's own share are taken: the world must stay within
 * [TownBudget.WORLD_MB] and the whole within [TownBudget.WORLD_ROUTE_MB]. Each frame's CPU time (streaming, building and
 * the frame) is kept; the far view's re-plans, their swaps and upload times, and the near terrain's frames drawn coarser
 * than wanted are reported. Renders along the way (desktop, software GL: not S25 captures) go to build/world-drive.
 *
 * gradle :desktop:crossroadsTest --tests GpuWorldDriveTest   (WORLD_DRIVE_KM=20 in the environment: only the first 20 km)
 */
class GpuWorldDriveTest {
    @Test fun theWorldStreamsRoundADriveToTheNextTown() {
        System.setProperty("gpuOnly", "1"); System.setProperty("world", "1")
        GpuAccount.reset()
        val log = StringBuilder()
        var ok = false
        val out = File("build/world-drive").also { it.mkdirs() }
        var fail: String? = null
        openBuilt = { ft -> fail = drive(ft, out, log); ok = true }
        try { renderFilamentOpen(out.path) } finally { openBuilt = null; System.clearProperty("gpuOnly"); System.clearProperty("world") }
        File(out, "drive.txt").writeText(log.toString())
        println("the world drive (desktop, Filament's own count):\n$log")
        assertTrue("the drive was not played", ok)
        fail?.let { throw AssertionError(it) }
    }

    private fun total(ft: FilamentTown): Double =
        ((GpuAccount.categories(ft.gpuEstimate(2340, 1080))["total"] as? Map<*, *>)?.get("mb") as? Number)?.toDouble() ?: 0.0

    /** Plays the drive; returns a failure or null. */
    private fun drive(ft: FilamentTown, out: File, log: StringBuilder): String? {
        val far = openWorld ?: return "no world round the town (-Dworld=1)"
        val site = WorldDrive.site
        val d = WorldDrive.drive
        val u = site.frame.unitsPerMetre.toFloat()
        val pts = d.street(site).let { s -> DoubleArray(s.size) { s[it].toDouble() } }
        val total = run { var l = 0.0; for (k in 0 until pts.size / 2 - 1) l += hypot(pts[k * 2 + 2] - pts[k * 2], pts[k * 2 + 3] - pts[k * 2 + 1]); l }
        val km = System.getenv("WORLD_DRIVE_KM")?.toDoubleOrNull()
        val length = min(total, (km ?: 1e9) * 1000 * u)
        log.appendLine("the drive $d; driven %.1f km of it".format(length / u / 1000))
        val w = 2340f; val h = 1080f
        fun ground(sx: Float, sz: Float): Float = far.terrain?.heightLocal(sx / u.toDouble(), sz / u.toDouble())?.toFloat()?.times(u) ?: 0f
        val p = DoubleArray(4)
        fun chase(s: Double): TownPose {
            WorldDrive.along(pts, s, p)
            val x = p[0].toFloat(); val z = p[1].toFloat()
            val yaw = Math.toDegrees(atan2(p[2], -p[3])).toFloat()
            return TownFrame.orbit(x, ground(x, z) + 1.4f * u, z, yaw, 12f, 520f, w, h)
        }
        var clock = 0.0
        fun frame(pose: TownPose, hour: Float = 13f) { val e = pose; ft.buildNearest(e.ex, e.ez, 4f); ft.frame(pose, hour, clock) }
        // settle at the start (behind the loading screen, as the app's first view)
        val t0 = System.nanoTime()
        var n0 = 0
        while ((!far.ready || far.near?.pending == true) && n0 < 20000) { frame(chase(0.0)); clock += 1 / 60.0; n0++; if (n0 % 4 == 0) Thread.sleep(2) }
        log.appendLine("the world settled round the start in %.0f ms, %d frames; %s".format((System.nanoTime() - t0) / 1e6, n0, WorldMemory.line()))
        val start0 = total(ft)
        log.appendLine("at the start: GPU %.1f MB (the world %.1f)".format(start0, WorldMemory.gpu / 1048576.0))
        val shooter = Shooter(ft.engine, ft)
        val sheet = ArrayList<String>()
        fun shot(name: String, caption: String, pose: TownPose, hour: Float = 13f) {
            val ts = System.nanoTime()
            frame(pose, hour); frame(pose, hour)
            shooter.save(File(out, "$name.png"), 1170, 540, hour)
            sheet += "$name: $caption"
            log.appendLine("shot $name: $caption; draws %d, triangles %d; near terrain %d nodes".format(ft.stats.draws, ft.stats.triangles, far.near?.drawnNodes ?: 0))
            pausedMs += (System.nanoTime() - ts) / 1e6
        }
        // the drive
        val speedU = (120 / 3.6 * u)
        val frameMs = ArrayList<Float>()
        var peak = 0.0; var peakAt = ""; var peakWorld = 0.0
        var f = 0; var s = 0.0
        val start = System.nanoTime()
        val shots = listOf(2500.0, 12000.0, 21000.0).map { it * u }.toMutableList()
        var hill = false
        var gc0 = gcMs()
        while (s < length) {
            val pose = chase(s)
            val a = System.nanoTime()
            frame(pose)
            frameMs += (System.nanoTime() - a) / 1e6f
            if (f % SAMPLE == 0) {
                ft.engine.flushAndWait()
                val mb = total(ft); val wmb = WorldMemory.gpu / 1048576.0
                if (mb > peak) { peak = mb; peakAt = "%.1f km".format(s / u / 1000) }
                peakWorld = max(peakWorld, wmb)
                if (f % (SAMPLE * 240) == 0) log.appendLine("%6.1f km: GPU %.1f MB, the world %.1f MB; far view %d swaps; near %d nodes up, %d drawn; %s".format(
                    s / u / 1000, mb, wmb, far.swaps, WorldMemory.nearNodes, far.near?.drawnNodes ?: 0, if (far.ocean?.near == true) "the sea near" else "inland"))
            }
            // (renders along the way, the drive paused for them: the pause is not counted in the frames' times)
            if (shots.isNotEmpty() && s >= shots[0]) {
                val at = shots.removeAt(0)
                val k = at / u / 1000
                if (k < 3) {
                    // looking back at the capital as the car leaves it (towards the middle of its ground)
                    WorldDrive.along(pts, s, p)
                    val x = p[0].toFloat(); val z = p[1].toFloat()
                    val mx = ft.slots.map { it.cx }.sorted().let { it[it.size / 2] }; val mz = ft.slots.map { it.cz }.sorted().let { it[it.size / 2] }
                    val yaw = Math.toDegrees(atan2((mx - x).toDouble(), -(mz - z).toDouble())).toFloat()
                    shot("leaving_capital", "%.1f km out on the road, 30 m up, looking back at the capital's downtown (a 30 degree lens)".format(k), eye(x, ground(x, z) + 30f * u, z, yaw, 1.2f, 30f, w.toInt(), h.toInt(), u))
                } else shot("open_country_%02.0fkm".format(k), "%.0f km out of the capital, the chase camera at 120 km/h".format(k), chase(s))
                if (!hill && k > 18) {
                    hill = true
                    WorldDrive.along(pts, s, p)
                    val x = p[0].toFloat(); val z = p[1].toFloat()
                    val yaw = Math.toDegrees(atan2(p[2], -p[3])).toFloat()
                    shot("far_view_300m", "300 m over the road %.0f km out, looking on along it".format(k), eye(x, ground(x, z) + 300f * u, z, yaw, 6f, 55f, w.toInt(), h.toInt(), u))
                }
                gc0 = gcMs()
            }
            s += speedU / 60.0
            clock += 1.0 / 60.0
            f++
            val ahead = (clock * 1e3 - (System.nanoTime() - start) / 1e6 - pausedMs).toLong()
            if (ahead > 0) Thread.sleep(ahead)
        }
        val sorted = frameMs.sorted()
        fun q(k: Double) = sorted[((sorted.size - 1) * k).toInt()]
        log.appendLine("frames %d; CPU ms a frame (streaming, building and the frame; this machine): median %.2f, p99 %.2f, p99.9 %.2f, worst %.2f; over 16.7 ms %d, over 33 ms %d".format(
            sorted.size, q(.5), q(.99), q(.999), sorted.last(), frameMs.count { it > 16.7f }, frameMs.count { it > 33f }))
        val up = synchronized(far.uploadFrames) { far.uploadFrames.sorted() }
        log.appendLine("the far view: %d re-plans swapped in; planned in %s ms, packed in %s ms (a worker, each); upload and free a frame: median %.2f, worst %.2f ms over %d frames".format(
            far.swaps, far.planTimes.joinToString { "%.0f".format(it) }, far.packTimes.joinToString { "%.0f".format(it) }, if (up.isEmpty()) 0f else up[up.size / 2], up.lastOrNull() ?: 0f, up.size))
        log.appendLine("the near terrain: frames with a wanted node not yet up (drawn coarser) %d of %d; %s".format(far.near?.coarseFrames ?: 0, frameMs.size, far.near?.report))
        log.appendLine("peak GPU %.1f MB at %s (budget %.0f); the world's own peak %.1f MB (budget %.0f); %s".format(peak, peakAt, TownBudget.WORLD_ROUTE_MB, peakWorld, TownBudget.WORLD_MB, WorldMemory.line()))
        log.appendLine(GpuAccount.categoryLine(ft.gpuEstimate(2340, 1080)))
        // a coast with the ocean: the nearest coast to the end of the drive (a jump there, the world settled round it)
        coast(ft, far, site.skeleton, pts, length, u)?.let { c ->
            // (from 4 km out at sea, 60 m up, looking back at the land: the sea, the beach and the terrain behind it)
            val ya = Math.toRadians(c[2].toDouble())
            val x = c[0] + (kotlin.math.sin(ya) * 4000 * u).toFloat(); val z = c[1] - (kotlin.math.cos(ya) * 4000 * u).toFloat()
            val pose = eye(x, (far.plan?.seaLevel ?: 0f) * u + 60f * u, z, c[2] + 180f, 2f, 45f, w.toInt(), h.toInt(), u)
            var k = 0
            fun settled(): Boolean {
                val m = far.plan?.middle ?: return false
                val at = site.frame.world(x.toDouble(), z.toDouble())
                return k > 60 && !far.streaming && far.near?.pending != true && maxOf(kotlin.math.abs(m.dx(at)), kotlin.math.abs(m.dz(at))) < 3400
            }
            while (!settled() && k < 30000) { frame(pose); clock += 1 / 60.0; k++; if (k % 4 == 0) Thread.sleep(2) }
            log.appendLine("the jump to the coast settled in %d frames".format(k))
            shot("coast_ocean", "a coast %.0f km from the capital, from 4 km out at sea, 60 m up, looking at the land (%s)".format(hypot(x, z) / u / 1000, if (far.ocean != null) "the near ocean on" else "the far view's flat sea"), pose)
            // (with -DworldOcean=1 the near waves, and the same without them: a check the near ocean draws)
            if (far.ocean != null) {
                val close = eye(x, (far.plan?.seaLevel ?: 0f) * u + 6f * u, z, c[2] + 150f, 14f, 55f, w.toInt(), h.toInt(), u)
                shot("ocean_close", "6 m over the near ocean's waves, looking down 14 degrees", close)
                far.ocean?.off = true
                shot("ocean_close_off", "the same without the near ocean (the far view's flat sea)", close)
                far.ocean?.off = false
            }
            log.appendLine("at the coast: GPU %.1f MB, %s".format(total(ft), WorldMemory.line()))
        }
        File(out, "sheet.txt").writeText(sheet.joinToString("\n"))
        if (peakWorld > TownBudget.WORLD_MB) return "the world's GPU peaked at %.1f MB, over its budget %.0f (TownBudget.WORLD_MB)".format(peakWorld, TownBudget.WORLD_MB)
        if (peak > TownBudget.WORLD_ROUTE_MB) return "the drive's GPU peaked at %.1f MB, over its budget %.0f (TownBudget.WORLD_ROUTE_MB)".format(peak, TownBudget.WORLD_ROUTE_MB)
        if (far.swaps < 1) return "the far view never followed the drive"
        return null
    }

    private var pausedMs = 0.0

    /** The nearest coast to the drive's end (street units x, z and a yaw looking out to sea), or null. */
    private fun coast(ft: FilamentTown, far: com.lifemax.rpg.feature.habits.presentation.scene3d.WorldFar, sk: WorldSkeleton, pts: DoubleArray, length: Double, u: Float): FloatArray? {
        val p = DoubleArray(4)
        WorldDrive.along(pts, length, p)
        val f = WorldDrive.site.frame
        val wp = f.world(p[0], p[1])
        val ci = Math.floor(wp.x / Planet.CELL_M).toInt(); val cj = Math.floor(wp.z / Planet.CELL_M).toInt()
        for (r in 1..200) for (dj in -r..r) for (di in -r..r) {
            if (maxOf(kotlin.math.abs(di), kotlin.math.abs(dj)) != r) continue
            val i = ci + di; val j = cj + dj
            // (a low, gentle shore: land under 40 m with its neighbours under 80 m)
            if (!sk.land(i, j) || sk.elev(i, j) > 40 || (-1..1).any { a -> (-1..1).any { b -> sk.land(i + a, j + b) && sk.elev(i + a, j + b) > 80 } }) continue
            // a land cell with the open sea two cells off on some side: stand on it, look that way
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

    /** A camera of the open world's kind at ([ex], [ey], [ez]) looking [yaw] (0 north, clockwise), [pitch] down. */
    private fun eye(ex: Float, ey: Float, ez: Float, yaw: Float, pitch: Float, fov: Float, w: Int, h: Int, u: Float): TownPose {
        val ya = Math.toRadians(yaw.toDouble()); val pa = Math.toRadians(pitch.toDouble())
        val cp = cos(pa).toFloat(); val sp = sin(pa).toFloat()
        val fx = sin(ya).toFloat() * cp; val fy = -sp; val fz = -cos(ya).toFloat() * cp
        val fl = (h / 2f) / tan(Math.toRadians(fov / 2.0)).toFloat()
        return TownPose(ex, ey, ez, fx, fy, fz, fl, w / 2f, h / 2f, w.toFloat(), h.toFloat(), 4f, 1e8f, 0f, 0f, open = true, shadowFar = 300f * u)
    }

    private fun gcMs(): Long = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime.coerceAtLeast(0L) }

    companion object {
        const val SAMPLE = 15
    }
}
