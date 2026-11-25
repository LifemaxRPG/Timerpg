import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.lifemax.rpg.feature.habits.domain.GlbLandmarks
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownYards
import com.lifemax.rpg.feature.habits.maps.MapData
import com.lifemax.rpg.feature.habits.maps.MapNav
import com.lifemax.rpg.feature.habits.maps.MapPlayer
import com.lifemax.rpg.feature.habits.maps.MapStyle
import com.lifemax.rpg.feature.habits.maps.MapTiles
import com.lifemax.rpg.feature.habits.maps.MiniPainter
import com.lifemax.rpg.feature.habits.maps.TownMapModel
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The minimap's cost while moving (0.1.909's "smooth, then every now and then it stutters for a moment": 290-430 ms frames
 * while moving, the game's own work in them under 20 ms, the HUD recomposed). Measured on the desktop on the real capital
 * (gamma): each city zoom step's picture (made on the maps' worker), what a frame draws of it (a GPU takes a picture in
 * whole the first time it is drawn, or again after its cache let it go; on Android on the render thread, which the main
 * thread waits for), the GPS route the draw recomputes as the player moves, and the draw itself along a ride at walking,
 * bicycle and car speeds (the zoom moving with the speed). Once as 0.1.909 had it (a step one picture), once in squares.
 */
class MinimapCostTest {
    private val dir = File("../app/src/main/assets/town/gamma")
    private val root = JSONObject(File(dir, "street.json").readText())
    private val town = Town.parse(root)!!
    private val model: TownMapModel by lazy { TownMapModel.build(town, GlbLandmarks.parse(root), TownYards.parse(root), TownMapModel.shopNames(root),
        com.lifemax.rpg.feature.habits.domain.TownCollision.build(town, TownYards.parse(root), com.lifemax.rpg.feature.habits.domain.ModelShape.parseAll(File(dir, "buildings/models.json").readText()),
            com.lifemax.rpg.feature.habits.domain.CinemaSiteShape.parse(root), com.lifemax.rpg.feature.habits.domain.StoreLots.parse(root).map { it.sprite }.toSet())) }

    @Test fun whatAMovingMinimapCosts() {
        installDesktopImages()
        val before = ride(tiled = false)
        val after = ride(tiled = true)
        MapTiles.on = true
        println("MinimapCostTest: the biggest picture a frame drew: %.1f MB as one picture a step, %.2f MB in squares".format(before / 1048576.0, after / 1048576.0))
        // a picture the GPU takes in whole on the render thread: a frame's must stay small (a square), not a whole step
        assertTrue("a frame draws pictures of at most 1 MB each", after <= 1 shl 20)
        assertTrue("one picture a step was the big upload", before > 4 shl 20)
    }

    /** A ride across the capital with the minimap drawn every frame; the biggest picture a frame drew (bytes). */
    private fun ride(tiled: Boolean): Long {
        MapTiles.on = tiled
        val data = MapData(model, null, town.year)
        val how = if (tiled) "squares" else "one picture a step"
        for (i in MapData.CITY_STEPS.indices) {
            val t0 = System.nanoTime()
            val l = data.cityStepNow(i, MapStyle.GPS)
            println("MinimapCostTest ($how): step $i (%.2f m a px): %d x %d = %.1f MB, %s; made in %.0f ms".format(MapData.CITY_STEPS[i], l.w, l.h,
                l.w.toLong() * l.h * 4 / 1048576.0, l.tiles?.let { "${it.count} squares of ${MapTiles.SIZE} px" } ?: "one picture", (System.nanoTime() - t0) / 1e6))
        }
        // a ride down the capital's streets, the waypoint at the far end
        val pts = ArrayList<FloatArray>()
        for (row in town.rows.take(12)) for (k in 0..20) { val p = row.pointAt(row.length * k / 20f, 0f); pts += floatArrayOf(p.x, p.z) }
        val wp = pts.last()
        val nav = MapNav()
        val painter = MiniPainter()
        val px = 420
        val canvas = Canvas(ImageBitmap(px, px))
        val ds = CanvasDrawScope()
        val p = MapPlayer()
        val layers = { m: Float -> data.cityFor(m, MapStyle.GPS) }
        var routes = 0; var routeWorst = 0.0; var drawWorst = 0.0; var drawSum = 0.0; var frames = 0; var biggest = 0L
        val firstDrawn = HashSet<Any>(); var firstBytes = 0L
        for (speed in floatArrayOf(0f, 6f, 11f, 20f)) {
            for ((i, q) in pts.withIndex()) {
                // (several frames between points, the speed wobbling as a rider's does)
                for (f in 0 until 4) {
                    val sp = if (speed == 0f) 0f else speed * (1f + .35f * kotlin.math.sin((i * 4 + f) * .7f))
                    p.set(q[0], q[1], 0f, 30f, sp)
                    val beforeRoute = nav.route
                    val t0 = System.nanoTime()
                    nav.update(model, true, p.x, p.z, wp)
                    val tr = (System.nanoTime() - t0) / 1e6
                    if (nav.route !== beforeRoute) { routes++; routeWorst = maxOf(routeWorst, tr) }
                    val t1 = System.nanoTime()
                    ds.draw(Density(2.81f), LayoutDirection.Ltr, canvas, Size(px.toFloat(), px.toFloat())) {
                        painter.draw(this, layers, p, 2, true, true, true, wp, nav.route, nav.distanceM, emptyList(), frames * 16_666_667L, null)
                    }
                    val td = (System.nanoTime() - t1) / 1e6
                    if (frames > 20) { drawWorst = maxOf(drawWorst, td); drawSum += td }
                    frames++
                    // what the GPU would take in: each picture once, the first time it is drawn
                    for ((pic, bytes) in painter.drawnNow()) { biggest = maxOf(biggest, bytes); if (firstDrawn.add(pic)) firstBytes += bytes }
                }
            }
        }
        println("MinimapCostTest ($how): %d frames along %d points at 0, 6, 11 and 20 m/s: draw %.2f ms on average, worst %.2f; %d routes recomputed, worst %.2f ms; %d pictures first drawn, %.1f MB in all, the biggest %.2f MB"
            .format(frames, pts.size, drawSum / (frames - 21), drawWorst, routes, routeWorst, firstDrawn.size, firstBytes / 1048576.0, biggest / 1048576.0))
        data.close()
        return biggest
    }
}
