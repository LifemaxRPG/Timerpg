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
class MapLayer(val tier: MapTier, val style: MapStyle, val printYear: Int, val image: ImageBitmap, val w: Int, val h: Int,
               val unitsPerPx: Float, val x0: Float, val z0: Float, val labels: List<MapLabel>, val background: Int) {
    /** The labels, most important first (the order they claim room in when drawn). */
    val byPriority: List<MapLabel> = labels.sortedByDescending { it.priority }
    val x1: Float get() = x0 + w * unitsPerPx
    val z1: Float get() = z0 + h * unitsPerPx

    companion object {
        /** [background]: what lies past the picture's edge (the town's forest, the planet's sea): its corner pixel. */
        fun of(tier: MapTier, style: MapStyle, printYear: Int, r: MapRaster, labels: List<MapLabel>) =
            MapLayer(tier, style, printYear, MapImages.of(r), r.w, r.h, r.unitsPerPx, r.x0, r.z0, labels, r.px[0])
    }
}

/** Turning a [MapRaster] into an ImageBitmap, once per map. */
object MapImages {
    /**
     * The platform's fast way (the app: an android.graphics.Bitmap from the pixels; the desktop: Skia's), or null: then
     * the pixels are drawn as runs of one colour with plain rectangles (any platform, a few hundred thousand rectangles).
     */
    @Volatile var fast: ((IntArray, Int, Int) -> ImageBitmap)? = null

    fun of(r: MapRaster): ImageBitmap {
        fast?.let { return it(r.px, r.w, r.h) }
        // (the game's own way to make a picture from pixels, where the platform has set it up)
        try { return com.lifemax.rpg.feature.habits.presentation.scene.PlatformImages.create(r.px, r.w, r.h, false) } catch (_: UninitializedPropertyAccessException) {}
        val img = ImageBitmap(r.w, r.h)
        val c = Canvas(img)
        val p = Paint().apply { isAntiAlias = false }
        for (y in 0 until r.h) {
            var x = 0
            val o = y * r.w
            while (x < r.w) {
                val col = r.px[o + x]
                var e = x + 1
                while (e < r.w && r.px[o + e] == col) e++
                p.color = Color(col)
                c.drawRect(x.toFloat(), y.toFloat(), e.toFloat(), (y + 1).toFloat(), p)
                x = e
            }
        }
        return img
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
        MapTier.CITY -> MapLayer.of(tier, style, year, city.raster(style), city.labels)
        MapTier.REGIONAL -> PlanetMapModel.regional(site!!, year).let { MapLayer.of(tier, style, year, it.raster, it.labels) }
        MapTier.WORLD -> PlanetMapModel.world(site!!, year).let { MapLayer.of(tier, style, year, it.raster, it.labels) }
    }

    fun close() { worker.shutdownNow() }

    companion object {
        /** Runs [f] where Compose state may be changed (the app: the main thread); the default runs it at once. */
        @Volatile var post: (() -> Unit) -> Unit = { it() }
        @Volatile var onError: ((String) -> Unit)? = null
    }
}

/** The map settings the player chooses (kept by the app): the minimap north up (false) or turning with the camera (true), and its zoom. */
object MapSettings {
    var headingUp by mutableStateOf(false)
    var minimapZoom by mutableIntStateOf(2)
    /** The minimap's zoom steps: street units an internal pixel, as multiples of the tier's base. */
    val ZOOMS = floatArrayOf(.5f, .75f, 1f, 1.5f, 2.2f, 3.2f)
}
