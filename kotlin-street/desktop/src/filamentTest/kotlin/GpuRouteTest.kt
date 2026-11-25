import com.lifemax.rpg.feature.habits.presentation.scene3d.FilamentTown
import com.lifemax.rpg.feature.habits.presentation.scene3d.GpuAccount
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownBudget
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownFrame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/**
 * The streaming's radius budget (docs/STREAMING.md, MEMORY_AUDIT.md "The memory budget guard"): the capital built as the
 * open-world preview builds it with streaming on, then a fixed route through it played frame by frame at the clock's pace
 * (60 Hz, paced to the wall clock so the workers have the time they would have on the phone): a walk down the main street,
 * then a drive round the town (city speed, then fast) with the camera following. Every [SAMPLE] frames Filament's whole count
 * ([GpuAccount.categories], within ~2% of the phone's) is taken; it must never exceed [TownBudget.ROUTE_MB]. The CPU time
 * each frame spends streaming and building (the hitch a block coming in costs) is reported too, worst and 99th percentile.
 *
 * gradle :desktop:crossroadsTest --tests GpuRouteTest
 */
class GpuRouteTest {
    @Test fun theRouteStaysWithinTheRadiusBudget() {
        System.setProperty("gpuOnly", "1")
        // (ROUTE_STREAM=0 in the environment: the same route with the whole town held, to compare)
        if (System.getenv("ROUTE_STREAM") == "0") System.setProperty("stream", "0")
        // (ROUTE_STEP4=0: with the audit's step 4 off, the night masks whole, the vertices float, each lot's yard on its own
        // sheets: the same route to compare)
        val step4Off = System.getenv("ROUTE_STEP4") == "0"
        if (step4Off) for (k in listOf("maskPages", "quant", "yardAtlas")) System.setProperty(k, "0")
        GpuAccount.reset()
        val log = StringBuilder()
        var peak = 0.0; var peakAt = ""
        var ok = false
        openBuilt = { ft ->
            val total0 = total(ft)
            log.appendLine("after the build at the origin: %.1f MB; %s".format(total0, ft.streamReport))
            log.appendLine(GpuAccount.categoryLine(ft.gpuEstimate(2340, 1080)))
            val route = route(ft)
            log.appendLine("route: ${route.size} legs, %.0f street units".format(route.sumOf { it.len.toDouble() }))
            val w = 2340f; val h = 1080f
            var clock = 0.0
            val frameMs = ArrayList<Float>()
            val start = System.nanoTime()
            var f = 0
            for (leg in route) {
                var t = 0f
                while (t < leg.len) {
                    val x = leg.x0 + (leg.x1 - leg.x0) * t / leg.len; val z = leg.z0 + (leg.z1 - leg.z0) * t / leg.len
                    val yaw = Math.toDegrees(kotlin.math.atan2((leg.x1 - leg.x0).toDouble(), -(leg.z1 - leg.z0).toDouble())).toFloat()
                    val pose = TownFrame.orbit(x, 30f, z, yaw, 24f, if (leg.speed > 200f) 560f else 420f, w, h)
                    val t0 = System.nanoTime()
                    ft.buildNearest(x, z, 4f)
                    ft.frame(pose, 13f, clock)
                    frameMs += (System.nanoTime() - t0) / 1e6f
                    if (f % SAMPLE == 0) {
                        ft.engine.flushAndWait()
                        val mb = total(ft)
                        if (mb > peak) { peak = mb; peakAt = "frame $f (%.0f, %.0f) %s".format(x, z, leg.name) }
                        if (f % (SAMPLE * 10) == 0) log.appendLine("frame %5d %-12s (%6.0f, %6.0f) GPU %.1f MB; %s".format(f, leg.name, x, z, mb, ft.streamReport))
                    }
                    t += leg.speed / 60f
                    clock += 1.0 / 60.0
                    f++
                    // (paced to the clock: the workers get the wall time they would get on the phone)
                    val ahead = (clock * 1e3 - (System.nanoTime() - start) / 1e6).toLong()
                    if (ahead > 0) Thread.sleep(ahead)
                }
            }
            val sorted = frameMs.sorted()
            log.appendLine("frames %d; CPU ms a frame (frame + streaming + building, this machine): median %.2f, p99 %.2f, worst %.2f".format(
                frameMs.size, sorted[sorted.size / 2], sorted[(sorted.size * 99) / 100], sorted.last()))
            log.appendLine("peak GPU %.1f MB at %s (radius budget %.0f); %s".format(peak, peakAt, TownBudget.ROUTE_MB, ft.streamReport))
            log.appendLine(GpuAccount.categoryLine(ft.gpuEstimate(2340, 1080)))
            log.appendLine(com.lifemax.rpg.feature.habits.presentation.scene3d.BufferArena.stats().let { "buffer pages %d, %.1f MB, %.1f MB in use".format(it.pages, it.bytes / 1048576.0, it.used / 1048576.0) })
            ok = true
        }
        val out = File("build/gpu-route").also { it.mkdirs() }
        try { renderFilamentOpen(out.path) } finally { openBuilt = null; System.clearProperty("gpuOnly"); System.clearProperty("stream"); for (k in listOf("maskPages", "quant", "yardAtlas")) System.clearProperty(k) }
        File(out, if (step4Off) "route-step4off.txt" else "route.txt").writeText(log.toString())
        println("GPU along the route (desktop, Filament's own count):\n$log")
        assertTrue("the route was not played", ok)
        if (System.getenv("ROUTE_STREAM") == "0") return
        assertTrue("the route's resident GPU peaked at %.1f MB, over the radius budget %.0f (TownBudget.ROUTE_MB)".format(peak, TownBudget.ROUTE_MB), peak <= TownBudget.ROUTE_MB)
    }

    private class Leg(val name: String, val x0: Float, val z0: Float, val x1: Float, val z1: Float, val speed: Float) {
        val len: Float get() = hypot(x1 - x0, z1 - z0)
    }

    private fun total(ft: FilamentTown): Double =
        ((GpuAccount.categories(ft.gpuEstimate(2340, 1080))["total"] as? Map<*, *>)?.get("mb") as? Number)?.toDouble() ?: 0.0

    /**
     * The fixed route: down the main street on foot (60 units a second), then a loop round the town's middle half by car
     * (300, the far side at 600), from the town's own plan so it follows the capital as it grows.
     */
    private fun route(ft: FilamentTown): List<Leg> {
        val town = ft.town
        val main = town.rows.filter { town.road(it.road).name == "Main Street" }.maxByOrNull { it.buildings.size } ?: town.rows.first()
        val a = main.pointAt(0f, -30f); val b = main.pointAt(main.length, -30f)
        val xs = ft.slots.map { it.cx }.sorted(); val zs = ft.slots.map { it.cz }.sorted()
        val x0 = xs[xs.size / 8]; val x1 = xs[xs.size * 7 / 8]; val z0 = zs[zs.size / 8]; val z1 = zs[zs.size * 7 / 8]
        return listOf(
            Leg("walk", a.x, a.z, b.x, b.z, 60f),
            Leg("to-loop", b.x, b.z, x0, z0, 300f),
            Leg("drive-n", x0, z0, x1, z0, 300f),
            Leg("drive-e", x1, z0, x1, z1, 600f),
            Leg("drive-s", x1, z1, x0, z1, 600f),
            Leg("drive-w", x0, z1, x0, z0, 300f),
        )
    }

    companion object {
        /** Filament's count is taken every this many frames. */
        const val SAMPLE = 15
    }
}
