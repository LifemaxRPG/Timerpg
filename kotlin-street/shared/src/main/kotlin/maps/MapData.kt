package com.lifemax.rpg.feature.habits.maps

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import com.lifemax.rpg.feature.habits.domain.WorldSite
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * A map ready to draw: its picture ([image], made once from [raster]), the raster's frame (street units a pixel, the
 * top-left corner), its labels, its tier, look and print year.
 */
class MapLayer(val tier: MapTier, val style: MapStyle, val printYear: Int, whole: ImageBitmap?, val w: Int, val h: Int,
               val unitsPerPx: Float, val x0: Float, val z0: Float, val labels: List<MapLabel>, val background: Int,
               /** The places marked with icons (the city map's), most important first. */
               val pois: List<MapPoi> = emptyList(),
               /** The picture cut into squares ([MapTiles]: what the minimap draws), or null: only [image]. */
               val tiles: MapTiles? = null) {
    /** The whole picture (the full map's); a tiled layer's is put together from its tiles the first time it is asked for. */
    val image: ImageBitmap by lazy { whole ?: tiles!!.joined(w, h) }
    /** The labels, most important first (the order they claim room in when drawn). */
    val byPriority: List<MapLabel> = labels.sortedByDescending { it.priority }
    val x1: Float get() = x0 + w * unitsPerPx
    val z1: Float get() = z0 + h * unitsPerPx

    companion object {
        /** [background]: what lies past the picture's edge (the town's forest, the planet's sea): its corner pixel. */
        fun of(tier: MapTier, style: MapStyle, printYear: Int, r: MapRaster, labels: List<MapLabel>, pois: List<MapPoi> = emptyList(), tiled: Boolean = false) =
            if (tiled) MapLayer(tier, style, printYear, null, r.w, r.h, r.unitsPerPx, r.x0, r.z0, labels, r.px[0], pois, MapTiles.of(r))
            else MapLayer(tier, style, printYear, MapImages.of(r), r.w, r.h, r.unitsPerPx, r.x0, r.z0, labels, r.px[0], pois)
    }
}

/** Turning a [MapRaster] into an ImageBitmap, once per map. */
object MapImages {
    /**
     * The platform's fast way (the app: an android.graphics.Bitmap from the pixels; the desktop: Skia's), or null: then
     * the pixels are drawn as runs of one colour with plain rectangles (any platform, a few hundred thousand rectangles).
     */
    @Volatile var fast: ((IntArray, Int, Int) -> ImageBitmap)? = null

    fun of(r: MapRaster): ImageBitmap = of(r.px, r.w, r.h)

    fun of(px: IntArray, w: Int, h: Int): ImageBitmap {
        fast?.let { return it(px, w, h) }
        // (the game's own way to make a picture from pixels, where the platform has set it up)
        try { return com.lifemax.rpg.feature.habits.presentation.scene.PlatformImages.create(px, w, h, false) } catch (_: UninitializedPropertyAccessException) {}
        val img = ImageBitmap(w, h)
        val c = Canvas(img)
        val p = Paint().apply { isAntiAlias = false }
        for (y in 0 until h) {
            var x = 0
            val o = y * w
            while (x < w) {
                val col = px[o + x]
                var e = x + 1
                while (e < w && px[o + e] == col) e++
                p.color = Color(col)
                c.drawRect(x.toFloat(), y.toFloat(), e.toFloat(), (y + 1).toFloat(), p)
                x = e
            }
        }
        return img
    }
}

/**
 * A map's picture cut into squares of [SIZE] pixels ([cols] x [rows], row by row), for the minimap.
 *
 * 0.1.909's stutter while moving ("smooth, then every now and then it stutters for a moment": 290-430 ms frames, the
 * game's own work in them under 20 ms): each city zoom step was one picture, up to 5000 pixels a side (about 30 MB for
 * gamma at the closest step), drawn a little of at a time. A GPU takes a picture in whole the first time it is drawn,
 * and again whenever its cache has let it go; on Android that upload runs on the render thread, and the main thread
 * waits for it at its next frame. Moving changes the zoom with the speed, so steps came and went (three are kept), each
 * a 10-30 MB upload in one frame. Cut into squares, a frame draws the four to nine round the player, each 256 KB, and
 * moving brings in a square at a time.
 */
class MapTiles(val cols: Int, val rows: Int, private val tiles: Array<ImageBitmap>) {
    val count: Int get() = tiles.size
    fun at(col: Int, row: Int): ImageBitmap = tiles[row * cols + col]

    /** The whole picture again (the full map asks for it rarely: put together once). */
    fun joined(w: Int, h: Int): ImageBitmap {
        val img = ImageBitmap(w, h)
        val c = Canvas(img)
        val p = Paint().apply { isAntiAlias = false }
        for (r in 0 until rows) for (k in 0 until cols) c.drawImage(at(k, r), androidx.compose.ui.geometry.Offset(k * SIZE.toFloat(), r * SIZE.toFloat()), p)
        return img
    }

    companion object {
        const val SIZE = 256
        /** Off (-Dmaps.tiles=0, the desktop): each step one picture, as before (to compare). */
        @Volatile var on = System.getProperty("maps.tiles") != "0"

        fun of(r: MapRaster): MapTiles {
            val cols = (r.w + SIZE - 1) / SIZE; val rows = (r.h + SIZE - 1) / SIZE
            val buf = IntArray(SIZE * SIZE)
            val out = Array(cols * rows) { i ->
                val k = i % cols; val row = i / cols
                val x0 = k * SIZE; val y0 = row * SIZE
                val tw = minOf(SIZE, r.w - x0); val th = minOf(SIZE, r.h - y0)
                for (y in 0 until th) System.arraycopy(r.px, (y0 + y) * r.w + x0, buf, y * tw, tw)
                MapImages.of(if (tw * th == buf.size) buf else buf.copyOf(tw * th), tw, th)
            }
            return MapTiles(cols, rows, out)
        }
    }
}

/**
 * The maps of the town the player is in (docs/MAPS.md): the city map from the town's data and, where the planet is
 * loaded, the regional and world maps from the skeleton. Pictures are made on a worker thread of their own, never on
 * the UI thread: [layer] gives what is ready (null while a picture is being made; [version] moves when one is ready,
 * so the HUD recomposes). The GPS look and the paper look each have their own pictures; the planet's maps are made
 * per print year (they show the roads and towns of that year).
 */
class MapData(val city: TownMapModel, val site: WorldSite?, val townYear: Int) {
    private val ready = ConcurrentHashMap<String, MapLayer>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "maps").also { it.isDaemon = true; it.priority = Thread.MIN_PRIORITY } }
    /** Moves each time a picture is ready (Compose state). */
    var version by mutableIntStateOf(0); private set
    /** The region (landmass) the town stands on, the road maps' area. */
    val region: String = site?.let { PlanetMapModel.regionOf(it) } ?: "region:home"

    private fun key(tier: MapTier, style: MapStyle, year: Int) = "$tier|$style|" + if (tier == MapTier.CITY) "" else year

    /** The picture of [tier] in [style] printed in [year] if it is ready; else null, and it is being made. */
    fun layer(tier: MapTier, style0: MapStyle, year: Int): MapLayer? {
        if (tier != MapTier.CITY && site == null) return null
        // (the planet's maps have one look, printed or on the phone)
        val style = if (tier == MapTier.CITY) style0 else MapStyle.GPS
        val k = key(tier, style, year)
        ready[k]?.let { return it }
        if (pending.add(k)) worker.execute {
            runCatching { build(tier, style, year) }.onSuccess { ready[k] = it }.onFailure { onError?.invoke("map $k: ${it.javaClass.simpleName}: ${it.message}") }
            pending.remove(k)
            post { version++ }
        }
        return null
    }

    /** The same, made now on the calling thread (the tests, the desktop's renders). */
    fun layerNow(tier: MapTier, style0: MapStyle, year: Int): MapLayer? {
        if (tier != MapTier.CITY && site == null) return null
        val style = if (tier == MapTier.CITY) style0 else MapStyle.GPS
        val k = key(tier, style, year)
        return ready[k] ?: build(tier, style, year).also { ready[k] = it }
    }

    private fun build(tier: MapTier, style: MapStyle, year: Int): MapLayer = when (tier) {
        // (the town's data is the town's year: a city map printed in another year shows it as it is now, labelled with its year)
        MapTier.CITY -> MapLayer.of(tier, style, year, city.raster(style), city.labels, city.pois)
        MapTier.REGIONAL -> PlanetMapModel.regional(site!!, year).let { MapLayer.of(tier, style, year, it.raster, it.labels) }
        MapTier.WORLD -> PlanetMapModel.world(site!!, year).let { MapLayer.of(tier, style, year, it.raster, it.labels) }
    }

    // ------------------------------------------------------------------ the city map at each zoom

    private val stepUsed = ConcurrentHashMap<String, Long>()
    @Volatile private var useClock = 0L

    /**
     * The city map in [style] drawn at zoom step [step] ([CITY_STEPS]: metres a pixel), made once on the worker the
     * first time it is asked for; null until it is ready. Only the last [KEEP_STEPS] steps asked for are kept per look
     * (the closest is about 30 MB for gamma).
     */
    fun cityStep(step: Int, style: MapStyle): MapLayer? {
        val s = step.coerceIn(0, CITY_STEPS.size - 1)
        val k = stepKey(style, s)
        stepUsed[k] = ++useClock
        ready[k]?.let { return it }
        if (pending.add(k)) worker.execute {
            val t0 = System.nanoTime()
            runCatching { buildStep(style, s) }
                .onSuccess {
                    ready[k] = it; evict(style)
                    // (each picture made, timed: the next playtest's breadcrumbs say how often moving asks for one)
                    onBuilt?.invoke("minimap: zoom step $s ($style) made in %.0f ms on the maps' worker, %d x %d (%.1f MB), %s".format(java.util.Locale.US,
                        (System.nanoTime() - t0) / 1e6, it.w, it.h, it.w.toLong() * it.h * 4 / 1048576.0, it.tiles?.let { t -> "${t.count} tiles" } ?: "one picture"))
                }.onFailure { onError?.invoke("map $k: ${it.javaClass.simpleName}: ${it.message}") }
            pending.remove(k)
            post { version++ }
        }
        return null
    }

    /** The same, made now on the calling thread (the tests). */
    fun cityStepNow(step: Int, style: MapStyle): MapLayer {
        val s = step.coerceIn(0, CITY_STEPS.size - 1)
        val k = stepKey(style, s)
        return ready[k] ?: buildStep(style, s).also { ready[k] = it }
    }

    private fun stepKey(style: MapStyle, s: Int) = "CITY|$style|step$s"
    private fun buildStep(style: MapStyle, s: Int) =
        MapLayer.of(MapTier.CITY, style, townYear, city.raster(style, CITY_STEPS[s] * com.lifemax.rpg.feature.habits.vehicle.VehicleWorld.U), city.labels, city.pois, tiled = MapTiles.on)

    /**
     * The city map for drawing at [metresPerPx] metres a picture pixel: the step closest to it is asked for, and the
     * closest step that is ready now is given (any ready step beats none; the base picture last of all).
     */
    fun cityFor(metresPerPx: Float, style: MapStyle): MapLayer? {
        val want = closestStep(metresPerPx)
        cityStep(want, style)?.let { return it }
        var best: MapLayer? = null; var bestD = Float.MAX_VALUE
        for (i in CITY_STEPS.indices) {
            val l = ready[stepKey(style, i)] ?: continue
            val d = kotlin.math.abs(kotlin.math.ln(CITY_STEPS[i] / metresPerPx))
            if (d < bestD) { bestD = d; best = l }
        }
        return best ?: layer(MapTier.CITY, style, townYear)
    }

    private fun evict(style: MapStyle) {
        val keys = stepUsed.keys.filter { it.startsWith("CITY|$style|step") && ready.containsKey(it) }
        if (keys.size <= KEEP_STEPS) return
        for (k in keys.sortedBy { stepUsed[it] ?: 0L }.take(keys.size - KEEP_STEPS)) ready.remove(k)
    }

    fun close() { worker.shutdownNow() }

    companion object {
        /**
         * The city map's zoom steps, metres a picture pixel: each is drawn as its own picture, so each is crisp at its
         * scale (MapSettings.minimapZoom picks one; driving adds steps with the speed).
         */
        val CITY_STEPS = floatArrayOf(.45f, .65f, .9f, 1.3f, 1.9f, 2.8f)
        const val KEEP_STEPS = 3
        /** The step whose scale is closest to [metresPerPx]. */
        fun closestStep(metresPerPx: Float): Int {
            var bi = 0; var bd = Float.MAX_VALUE
            for (i in CITY_STEPS.indices) { val d = kotlin.math.abs(kotlin.math.ln(CITY_STEPS[i] / metresPerPx)); if (d < bd) { bd = d; bi = i } }
            return bi
        }
        /** Runs [f] where Compose state may be changed (the app: the main thread); the default runs it at once. */
        @Volatile var post: (() -> Unit) -> Unit = { it() }
        @Volatile var onError: ((String) -> Unit)? = null
        /** Told of each zoom step's picture made (any thread): the app's breadcrumbs. */
        @Volatile var onBuilt: ((String) -> Unit)? = null
    }
}

/**
 * The map settings the player chooses (kept by the app): the minimap north up (false) or turning with the camera (true),
 * its zoom step (0 the closest: [MINIMAP_RADIUS_M]), and whether it zooms out by itself with the speed when driving
 * ([autoZoom]).
 */
object MapSettings {
    var headingUp by mutableStateOf(false)
    var minimapZoom by mutableIntStateOf(2)
    var autoZoom by mutableStateOf(true)
    /** The minimap's zoom steps: how far it shows from the player to its rim, metres (the road and world maps: times their scale). */
    val MINIMAP_RADIUS_M = floatArrayOf(55f, 80f, 115f, 165f, 240f, 350f)
    val ZOOMS = MINIMAP_RADIUS_M
}
