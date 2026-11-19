import com.lifemax.rpg.feature.habits.presentation.scene.GameState
import com.lifemax.rpg.feature.habits.presentation.scene.OrbitCamera
import com.lifemax.rpg.feature.habits.presentation.scene.TownPlay
import com.lifemax.rpg.feature.habits.presentation.scene3d.FilamentTown
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownRun
import java.io.File

/**
 * The frame benchmark (-Dmode=filament-open -Dshots=bench): a scripted walk and ride through gamma's busiest spots (Main
 * Street downtown by the towers and the town hall, Wharf Road, east along Main Street past the woods to the chapel's
 * lots) at 60 Hz through the game's own loop, as the app runs it each frame: [TownPlay.step], [TownRun.build] in the
 * app's walking budget (4 ms), [TownRun.draw] (the camera, the cutaway, the town's frame, the people), then Filament's
 * render off screen. The camera is turned only by drags, as a player does, every few seconds towards the way they go.
 *
 * bench.txt: per frame, the main thread's CPU time by part (p50, p95, max; the parts from FrameWork where the build has
 * it), the bytes allocated on it (mean, p95, max a frame, and by part), the frames over 8 / 12 / 16.7 ms of our own
 * work, and the software GL's time (a stand-in for the fill rate only: the build server's llvmpipe, not the S25's GPU).
 * -DbenchSecs=N for a longer run (default 40).
 */
private const val RENDER_EVERY = 20

internal fun benchWalk(dir: File, walk: com.lifemax.rpg.feature.habits.presentation.scene.OpenWalk, run: TownRun, ft: FilamentTown,
                       shooter: Shooter, sheets: List<androidx.compose.ui.graphics.ImageBitmap>) {
    val mx = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    val me = Thread.currentThread().id
    fun alloc() = mx.getThreadAllocatedBytes(me)
    // the parts, where this build has the frame-work counters (read by reflection, so the same bench runs on older builds)
    val fw = runCatching { Class.forName("com.lifemax.rpg.feature.habits.presentation.scene.FrameWork") }.getOrNull()
    val fwObj = fw?.getField("INSTANCE")?.get(null)
    val fwNs = fw?.getField("ns")?.get(null) as LongArray?
    val fwNames = (fw?.getMethod("getNAMES")?.invoke(fwObj) as Array<*>?)?.map { it.toString() }
    val fwReset = fw?.getMethod("reset")
    val rw = 780; val rh = 360
    val secs = System.getProperty("benchSecs")?.toFloatOrNull() ?: 40f
    val route = listOf(8619f to 4963f, 8900f to 5200f, 9291f to 5065f, 9800f to 4900f, 10538f to 4790f, 11500f to 5300f, 12564f to 5807f)
    val clear = FloatArray(2)
    val goals = route.map { (x, z) -> walk.collision.nearestClear(x, z, com.lifemax.rpg.feature.habits.presentation.scene.OpenWalk.RADIUS + 2f, clear); clear[0] to clear[1] }
    if (walk.riding) walk.dismount()
    walk.placeAt(goals[0].first, goals[0].second, 0f, 1f)
    OrbitCamera.set(OrbitCamera.headingOf(goals[1].first - walk.wx, goals[1].second - walk.wz), 12f, 420f)
    OrbitCamera.follow(walk.wx, walk.wz)
    var clock = 5000f
    run.orbit(walk, 2340f, 1080f, clock)
    // (warm: a few frames standing, so the first measured frames are not the JIT's)
    repeat(30) { TownPlay.step(1f / 60f); run.build(walk.wx, walk.wz, 4f); run.draw(walk, 2340f, 1080f, 17.6f, clock, sheets); clock += 1f / 60f }
    fwReset?.invoke(fwObj)
    val n = (secs * 60).toInt()
    val step = FloatArray(n); val build = FloatArray(n); val draw = FloatArray(n); val render = FloatArray(n); val gl = FloatArray(n); val total = FloatArray(n)
    val aStep = LongArray(n); val aBuild = LongArray(n); val aDraw = LongArray(n); val aAll = LongArray(n)
    val parts = Array(fwNames?.size ?: 0) { FloatArray(n) }
    val dt = 1f / 60f
    var g = 1
    var path: List<Pair<Float, Float>>? = null; var planned = -9f; var pi = 0
    var rode = false
    val log = StringBuilder()
    for (f in 0 until n) {
        val t = f * dt
        // (the second half on the bike)
        if (!rode && t >= secs / 2f) { rode = true; if (!walk.riding) TownPlay.toggleBike() }
        val goal = goals.getOrNull(g)
        if (goal != null) {
            val dx = goal.first - walk.wx; val dz = goal.second - walk.wz
            if (kotlin.math.hypot(dx, dz) < 30f) { g++; path = null; log.appendLine("  %5.1f s reached waypoint %d at (%.0f, %.0f)".format(t, g - 1, walk.wx, walk.wz)) }
            else {
                if (path == null || t - planned > 1f) { path = plan(walk.collision, walk.wx, walk.wz, goal.first, goal.second); planned = t; pi = 0 }
                val pp = path!!
                while (pi < pp.size - 1 && kotlin.math.hypot(pp[pi].first - walk.wx, pp[pi].second - walk.wz) < 14f) pi++
                val (tx, tz) = if (pp.isEmpty()) goal else pp[pi]
                val ex = tx - walk.wx; val ez = tz - walk.wz
                val e = kotlin.math.hypot(ex, ez).coerceAtLeast(1e-3f)
                val ux = ex / e; val uz = ez / e
                val fx = OrbitCamera.fx; val fz = OrbitCamera.fz
                GameState.moveX = ux * -fz + uz * fx
                GameState.moveLane = -(ux * fx + uz * fz)
                // (the player turns the camera now and then towards where they go: a drag over half a second)
                val want = OrbitCamera.headingOf(ux, uz)
                var dy = want - OrbitCamera.yaw; while (dy > 180f) dy -= 360f; while (dy < -180f) dy += 360f
                if ((f / 30) % 4 == 0 && kotlin.math.abs(dy) > 20f) OrbitCamera.drag(kotlin.math.sign(dy) * 12f, 0f, 2340f, 1080f)
            }
        } else { GameState.moveX = 0f; GameState.moveLane = 0f }
        val a0 = alloc(); val t0 = System.nanoTime()
        TownPlay.step(dt)
        val a1 = alloc(); val t1 = System.nanoTime()
        run.build(walk.wx, walk.wz, 4f)
        val a2 = alloc(); val t2 = System.nanoTime()
        run.draw(walk, 2340f, 1080f, 17.6f, clock, sheets)
        val a3 = alloc(); val t3 = System.nanoTime()
        // (Filament's render one frame in [RENDER_EVERY]: the build server's software GL draws a frame's four 2048 shadow
        // maps in about a second; the rest of the frame's CPU is ours and measured every frame)
        val (rms, gms) = if (f % RENDER_EVERY == 0) shooter.renderFrame(rw, rh) else 0f to -1f
        step[f] = (t1 - t0) / 1e6f; build[f] = (t2 - t1) / 1e6f; draw[f] = (t3 - t2) / 1e6f; render[f] = rms; gl[f] = gms
        total[f] = step[f] + build[f] + draw[f]
        aStep[f] = a1 - a0; aBuild[f] = a2 - a1; aDraw[f] = a3 - a2; aAll[f] = a3 - a0
        if (fwNs != null) { for (k in parts.indices) parts[k][f] = fwNs[k] / 1e6f; fwReset!!.invoke(fwObj) }
        clock += dt
        if (f % 60 == 0) log.appendLine("  %5.1f s at (%.0f, %.0f) %s, speed %.0f; draws %d, triangles %d".format(t, walk.wx, walk.wz, if (walk.riding) "riding" else "walking", walk.speed, ft.stats.draws, ft.stats.triangles))
    }
    GameState.moveX = 0f; GameState.moveLane = 0f
    if (walk.riding) TownPlay.dismount("bench")
    fun q(a: FloatArray): String { val s = a.sorted(); return "p50 %6.2f  p95 %6.2f  p99 %6.2f  max %6.2f".format(s[s.size / 2], s[s.size * 95 / 100], s[s.size * 99 / 100], s.last()) }
    fun qa(a: LongArray): String { val s = a.sorted(); return "mean %8.1f KB  p95 %8.1f KB  max %8.1f KB".format(a.average() / 1024, s[s.size * 95 / 100] / 1024.0, s.last() / 1024.0) }
    val out = StringBuilder()
    out.appendLine("frame bench: $n frames at 60 Hz (%.0f s, walking then riding) through gamma's busiest spots; reached %d of %d waypoints".format(secs, g - 1, goals.size - 1))
    out.appendLine("(the build server's CPU, 4 cores, and software GL: not the S25; compare before and after on the same machine)")
    out.appendLine("main thread CPU per frame (ms):")
    out.appendLine("  ours (step+build+draw)        " + q(total))
    out.appendLine("  step                          " + q(step))
    out.appendLine("  build                         " + q(build))
    out.appendLine("  draw (camera, cutaway, frame, people) " + q(draw))
    out.appendLine("  Filament render calls (1 frame in $RENDER_EVERY) " + q(render.filterIndexed { i, _ -> i % RENDER_EVERY == 0 }.toFloatArray()))
    if (fwNames != null) for (k in parts.indices) out.appendLine("    %-12s                " .format(fwNames[k]) + q(parts[k]))
    for (lim in listOf(8f, 12f, 16.7f)) out.appendLine("  frames over %.1f ms of our own work: %d".format(lim, total.count { it > lim }))
    out.appendLine("allocated on the main thread per frame:")
    out.appendLine("  all    " + qa(aAll))
    out.appendLine("  step   " + qa(aStep))
    out.appendLine("  build  " + qa(aBuild))
    out.appendLine("  draw   " + qa(aDraw))
    out.appendLine("software GL per frame at ${rw}x$rh (fill-rate stand-in, not the phone): " + q(gl.filter { it >= 0f }.toFloatArray()))
    out.append(log)
    File(dir, "bench.txt").writeText(out.toString())
    print(out)
}
