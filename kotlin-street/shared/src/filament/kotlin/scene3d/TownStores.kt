package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.Scene
import com.lifemax.rpg.feature.habits.domain.PlacementKind
import com.lifemax.rpg.feature.habits.domain.SolidKind
import com.lifemax.rpg.feature.habits.domain.TownCollision
import com.lifemax.rpg.feature.habits.domain.TownDoors
import com.lifemax.rpg.feature.habits.domain.TownPlace
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One store site of the town as street.json v2 exports it (docs/STORES.md): the row slot it stands in ([sprite], the
 * landmark's), its whole life ([site]: `graph.storeSites`, any year) and the export year's block ([info]: `graph.stores`,
 * the generator's own features and people).
 */
class StoreSite(val sprite: String, val landmark: String, val site: StoreSiteInfo?, val info: StoreInfo?) {
    /** The export's year (the block's), or 0. */
    val year: Int get() = info?.year ?: 0

    /**
     * What stands there in [year] at [hour] (0-24) of [weekday] (0 Sunday): the export's block in its own year, else
     * the site's life for that year; null before its store opens ([placeholder] then).
     */
    fun at(year: Int, hour: Float, weekday: Int): StoreInfo? =
        info?.takeIf { it.year == year }?.copy(hour = hour, weekday = weekday) ?: site?.at(year, hour, weekday)

    /** The field or building site that stands there before the store opens (null without the site's life). */
    fun placeholder(year: Int): CinemaModel? = site?.let { StoreSitePlaceholder.build(it, year) }

    companion object {
        /** Every store site in a street.json root (its `graph`) that has a slot on a row; none for a v1 file. */
        fun parse(root: org.json.JSONObject): List<StoreSite> {
            val g = root.optJSONObject("graph") ?: return emptyList()
            val town = root.optString("town")
            val sites = StoreSiteInfo.fromGraph(g).map { it.copy(town = it.town.ifBlank { town }) }
            val stores = g.optJSONArray("stores")
            val infos = HashMap<String, StoreInfo>()
            if (stores != null) for (i in 0 until stores.length()) {
                val o = stores.getJSONObject(i)
                StoreInfo.fromJson(o, town)?.let { infos[o.optString("landmark")] = it.copy(people = o.optInt("people")) }
            }
            val lms = g.optJSONArray("landmarks") ?: return emptyList()
            val out = ArrayList<StoreSite>()
            for (i in 0 until lms.length()) {
                val l = lms.getJSONObject(i)
                val id = l.optString("id")
                val site = sites.firstOrNull { it.landmark == id }
                val info = infos[id]
                if (site == null && info == null) continue
                val sprite = l.optString("sprite")
                if (sprite.isBlank()) continue
                out += StoreSite(sprite, id, site, info)
            }
            return out
        }

        fun parse(streetJson: String): List<StoreSite> = runCatching { parse(org.json.JSONObject(streetJson)) }.getOrDefault(emptyList())
    }
}

/**
 * A store in [FilamentTown] (docs/STORES.md "Hook"), as [TownCinema] does the theater: [StoreKit] built for [site] in the
 * town's [year] on a worker (its pictures painted, its parts packed, what a walker meets read off its geometry by
 * [StoreSolids]), then uploaded on the engine's thread into its own culled [PropLayer] standing in its slot's frame (the
 * plot's front on the row's building line, `footprint.width` along by `footprint.depth` back), lit by [CinemaLayer] and
 * [CinemaLights]. The parked cars are a layer of their own, rebuilt on a worker on the hour ([StoreCars], the day's
 * [weekday]) without touching the store. Once up, the store's solid shapes and its entrances go into the walk's
 * [collision] ([TownCollision.setExtra], [TownCollision.setExtraDoors]); the cars' too, each hour.
 */
internal class TownStore(
    private val engine: Engine, private val scene: Scene, private val lit: Material, private val proxy: Material, private val textures: StreetTextures,
    val site: StoreSite, private val place: TownPlace, private val setback: Float, private val row: String,
    private val year: Int, private val collision: TownCollision?,
) {
    /** The site (ground, grounds, signs at the road...) and the main block's own parts (cut away when in the camera's way). */
    private val layer = PropLayer(engine, scene, lit, proxy, sharedTextures = textures, cull = true)
    private val body = PropLayer(engine, scene, lit, proxy, sharedTextures = textures, cull = true)
    private val glow = CinemaLayer(layer)
    private val bodyGlow = CinemaLayer(body)
    private val lamps = CinemaLights(engine, scene)
    private var cars: PropLayer? = null
    private class Built(val parts: List<CinemaLayer.Packed>, val bodyParts: List<CinemaLayer.Packed>, val box: FloatArray, val lights: List<CinemaLight>, val info: StoreInfo?,
                        val bays: List<StoreBay>, val solids: FloatArray, val doors: List<FloatArray>, val block: FloatArray?)
    private class Cars(val key: Int, val parts: List<CinemaLayer.Packed>, val solids: FloatArray, val count: Int)
    private var job: java.util.concurrent.Future<Built>? = null
    private var carJob: java.util.concurrent.Future<Cars>? = null
    /** The hour and day the cars standing are for (-1: none yet), and the key of the job under way. */
    private var carKey = -1
    private var carWant = -1
    private var info: StoreInfo? = null
    private var bays: List<StoreBay> = emptyList()
    /** It is up (uploaded), or it failed (the town goes on without it). */
    var done = false; private set
    /** A sphere round it in the world (the plot until built), its height's middle. */
    var cx = 0f; var cy = 60f; var cz = 0f; var r = 0f
    /** The main block in the world, once built (x0, z0, x1, z1 of its ground box, and its height), for the camera's cut. */
    var block: FloatArray? = null; private set
    var shown = false; private set
    /** The cars standing now (for the preview's report). */
    var carCount = 0; private set
    /** What was built: the store's triangles and its solid shapes (for the preview's report). */
    var triangles = 0; private set
    var solidCount = 0; private set
    /** How far it is cut away for the open world's camera (1 whole), eased by [FilamentTown]. */
    var occFade = 1f
    var occluded = false
    /** Where the open world camera's line enters its main block (a share of the way from the player; MAX_VALUE: not in the way). */
    var occT = Float.MAX_VALUE

    /** Where the model's frame stands: its front's middle on the building line, along the street, out towards the road. */
    val at: FloatArray = StoreShapes.frameOf(place, setback)

    private val lot: Float = site.info?.lot ?: site.site?.width?.takeIf { it > 0f } ?: 1000f
    private val deep: Float = site.info?.deep ?: site.site?.depth?.takeIf { it > 0f } ?: 1000f

    init {
        val c = place.world(0f, setback + deep / 2f)
        cx = c.x; cz = c.z; r = hypot(lot / 2f, deep / 2f) + 60f
    }

    /** Starts building it on [pool] (once), at [priority] there ([TownWorkers]), the cars for [hour] of [weekday]. */
    fun start(pool: TownWorkers, priority: Float, hour: Float, weekday: Int) {
        if (job != null || done) return
        val a = at; val s = site; val y = year; val l = lot; val d = deep
        job = pool.submit(priority, "store", site.sprite, java.util.concurrent.Callable {
            val inf = s.at(y, hour, weekday)
            if (inf == null) {
                val m = s.placeholder(y) ?: CinemaModel(CinemaEra.PALACE, emptyList(), floatArrayOf(0f, 0f, 0f, 0f), 0f, l, d)
                Built(CinemaLayer.pack(m, a), emptyList(), box(m), m.lights, null, emptyList(), StoreSolids.world(StoreSolids.of(listOf(m), l, d), a), emptyList(), null)
            } else {
                val sm = StoreKit.buildWithSite(inf, cars = false)
                val m = sm.model
                val doors = StoreShapes.entrances(sm, inf)
                // (a small store's one group of details runs over its whole plot: all of it but the ground is cut together)
                val (bodyM, siteM) = if (inf.kind == StoreKind.GENERAL || inf.kind == StoreKind.MARKET) splitGround(m) else split(m, sm.block)
                Built(CinemaLayer.pack(siteM, a), CinemaLayer.pack(bodyM, a), box(m), m.lights, inf, sm.bays, StoreSolids.world(StoreSolids.of(listOf(m), l, d), a), doors, sm.block)
            }
        })
    }

    /** [m]'s parts that stand on the main block ([block]: x0, front z, x1, back z; its bounds within it, give or take) and the rest. */
    private fun split(m: CinemaModel, block: FloatArray): Pair<CinemaModel, CinemaModel> {
        val x0 = min(block[0], block[2]) - SPLIT_PAD; val x1 = max(block[0], block[2]) + SPLIT_PAD
        val z0 = min(block[1], block[3]) - SPLIT_PAD; val z1 = max(block[1], block[3]) + SPLIT_PAD
        val (on, off) = m.parts.partition { p -> val b = p.mesh.bounds; b[0] >= x0 && b[3] <= x1 && b[2] >= z0 && b[5] <= z1 && b[4] > 40f }
        return CinemaModel(m.era, on, m.footprint, m.height, m.width, m.depth) to CinemaModel(m.era, off, m.footprint, m.height, m.width, m.depth)
    }

    /** [m]'s parts standing up (cut away together when in the camera's way) and its flat ground. */
    private fun splitGround(m: CinemaModel): Pair<CinemaModel, CinemaModel> {
        val (on, off) = m.parts.partition { p -> p.mesh.bounds[4] > 6f }
        return CinemaModel(m.era, on, m.footprint, m.height, m.width, m.depth) to CinemaModel(m.era, off, m.footprint, m.height, m.width, m.depth)
    }

    private fun box(m: CinemaModel) = floatArrayOf(m.footprint[0], m.footprint[1], m.footprint[2], m.footprint[3], m.height)

    /** Uploads it if its worker is done (the engine's thread); true once it is up. */
    fun step(): Boolean {
        if (done) return true
        val f = job ?: return false
        if (!f.isDone) return false
        val b = try { f.get() } catch (e: java.util.concurrent.ExecutionException) {
            val c = e.cause ?: e
            if (c is VirtualMachineError) throw BuildFailure("building a store on a worker", c)
            done = true; job = null; return true
        }
        glow.upload(b.parts)
        bodyGlow.upload(b.bodyParts)
        lamps.add(b.lights, at)
        job = null
        info = b.info; bays = b.bays
        triangles = (b.parts + b.bodyParts).sumOf { it.packed.indices / 3 }
        solidCount = b.solids.size / 6
        val fp = b.box
        val mx = (fp[0] + fp[2]) / 2f; val mz = (fp[1] + fp[3]) / 2f
        cx = at[0] + at[2] * mx + at[4] * mz; cz = at[1] + at[3] * mx + at[5] * mz; cy = fp[4] / 2f
        r = sqrt((fp[2] - fp[0]) * (fp[2] - fp[0]) + (fp[3] - fp[1]) * (fp[3] - fp[1]) + fp[4] * fp[4]) / 2f
        b.block?.let { bl ->
            // the main block's ground box in the world (x0, front z, x1, back z in the model's frame), and its height
            val xs = floatArrayOf(bl[0], bl[2]); val zs = floatArrayOf(bl[1], bl[3])
            var x0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
            for (x in xs) for (z in zs) {
                val wx = at[0] + at[2] * x + at[4] * z; val wz = at[1] + at[3] * x + at[5] * z
                x0 = min(x0, wx); z0 = min(z0, wz); x1 = max(x1, wx); z1 = max(z1, wz)
            }
            block = floatArrayOf(x0, z0, x1, z1, fp[4].coerceAtMost(320f))
        }
        collision?.let { col ->
            col.setExtra(KEY + site.sprite, b.solids, SolidKind.BUILDING, site.sprite)
            col.setExtraDoors(KEY + site.sprite, StoreShapes.doorsAt(b.doors, at, site.sprite, row, col))
        }
        done = true
        return true
    }

    /** The cars for [hour] of [weekday]: rebuilt on [pool] when the hour or the day changes, uploaded when ready. */
    fun stepCars(pool: java.util.concurrent.ExecutorService, hour: Float, weekday: Int) {
        val inf = info ?: return
        if (bays.isEmpty()) return
        val h = floor(hour).toInt().coerceIn(0, 23)
        val key = weekday * 24 + h
        carJob?.let { f ->
            if (!f.isDone) return
            val c = try { f.get() } catch (e: java.util.concurrent.ExecutionException) {
                val cause = e.cause ?: e
                if (cause is VirtualMachineError) throw BuildFailure("parking a store's cars on a worker", cause)
                carJob = null; carKey = carWant; return
            }
            carJob = null
            val l = PropLayer(engine, scene, lit, proxy, sharedTextures = textures, cull = true)
            for (p in c.parts) l.upload(p.packed, p.casts, p.tiled)
            cars?.destroy()
            cars = l
            carKey = c.key; carCount = c.count
            collision?.setExtra(CARS + site.sprite, c.solids, SolidKind.PROP, site.sprite)
        }
        if (key == carKey || key == carWant && carJob != null) return
        carWant = key
        val a = at; val bs = bays; val l = lot; val d = deep
        val forHour = inf.copy(hour = h + .5f, weekday = weekday)
        carJob = pool.submit(java.util.concurrent.Callable {
            val m = StoreCars.build(forHour, bs)
            val n = min(StoreTraffic.shoppers(forHour), bs.count { !it.staff }) + min(StoreTraffic.staff(forHour), bs.size)
            Cars(key, CinemaLayer.pack(m, a), StoreSolids.world(StoreSolids.of(listOf(m), l, d), a), n)
        })
    }

    /** Whether the cars standing are those for [hour] of [weekday] (or it has none to park). */
    fun carsFor(hour: Float, weekday: Int): Boolean = done && (info == null || bays.isEmpty() || carKey == weekday * 24 + floor(hour).toInt().coerceIn(0, 23))

    /**
     * This frame through [pose] ([look] its matrix), [dark] how dark, [seconds] the clock; [cut] the street view's cut at
     * a world point (1: not cut). Returns its draws, triangles and shadow casters this frame (0s when not drawn).
     */
    fun frame(pose: TownPose, look: FloatArray, dark: Float, seconds: Double, cut: (Float, Float) -> Float, out: IntArray): IntArray {
        out.fill(0)
        if (!done) return out
        val fade = min(cut(cx, cz), pose.rangeFade(cx, cy, cz, r))
        val on = fade > .02f && pose.sees(cx, cy, cz, r)
        val near = fade > .5f && (on || pose.sees(cx, cy, cz, r + 300f))
        layer.update(look, dark, on, fade, near)
        if (on) glow.update(dark, seconds)
        // the main block: cut away while in the open world camera's way (its shadow kept, as a building's)
        val bf = min(fade, occFade)
        val bodyOn = on && bf > .02f
        body.update(look, dark, bodyOn, bf, near)
        if (bodyOn) { bodyGlow.update(dark, seconds); out[0] += body.draws; out[1] += body.triangles }
        if (near) out[2] += body.casters
        lamps.update(dark, on && near)
        // the cars: drawn while near enough to read (a car is a few pixels past [CAR_RANGE]); their shadows nearer
        val c = cars
        if (c != null) {
            val cd = pose.distance(cx, 20f, cz) - r
            val carsOn = on && cd < CAR_RANGE
            c.update(look, dark, carsOn, fade, carsOn && cd < CAR_SHADOWS)
            if (carsOn) { out[0] += c.draws; out[1] += c.triangles }
            if (carsOn && cd < CAR_SHADOWS) out[2] += c.casters
        }
        shown = on
        if (on) { out[0] += layer.draws; out[1] += layer.triangles }
        if (near) out[2] += layer.casters
        return out
    }

    fun destroy() {
        job?.cancel(true); carJob?.cancel(true)
        layer.destroy(); body.destroy(); cars?.destroy(); lamps.destroy()
        collision?.setExtra(KEY + site.sprite, null); collision?.setExtra(CARS + site.sprite, null); collision?.setExtraDoors(KEY + site.sprite, null)
    }

    companion object {
        const val KEY = StoreShapes.KEY
        const val CARS = StoreShapes.CARS
        /** How far past its edge a store's parked cars are drawn, and throw shadows (street units). */
        const val CAR_RANGE = 2400f
        const val CAR_SHADOWS = 1200f
        /** How far past the main block a part may reach and still be the block's (its canopies, vestibules, cornices). */
        const val SPLIT_PAD = 80f
    }
}

/** A store's place in the walk (shared by [TownStore] and the tests): its frame in its slot, its doors, the collision's keys. */
object StoreShapes {
        /** The store model's frame in a slot ([place], [setback]): its front's middle, along the street, out towards the road. */
        fun frameOf(place: TownPlace, setback: Float): FloatArray =
            place.world(0f, setback).let { p -> floatArrayOf(p.x, p.z, place.rx, place.rz, -place.nx, -place.nz, 0f) }

        /** Where a walker goes in ([StoreKit.StoreModel.doors]; the main block's front middle for a builder that names none); none when shut. */
        fun entrances(sm: StoreKit.StoreModel, info: StoreInfo): List<FloatArray> =
            if (info.state == StoreState.CLOSED) emptyList() else sm.doors.ifEmpty { listOf(floatArrayOf((sm.block[0] + sm.block[2]) / 2f, sm.block[1])) }

        /** [doors] (the model's frame) as the walk's doors in the world, each walked out until [col] has a walker clear. */
        fun doorsAt(doors: List<FloatArray>, at: FloatArray, sprite: String, row: String, col: TownCollision): List<com.lifemax.rpg.feature.habits.domain.TownDoor> = doors.map { d ->
            val wx = at[0] + at[2] * d[0] + at[4] * d[1]; val wz = at[1] + at[3] * d[0] + at[5] * d[1]
            TownDoors.standing(sprite, row, PlacementKind.LANDMARK, wx, wz, at[4], at[5], col)
        }

        /** The collision's keys for a store's site and for its parked cars ([TownCollision.setExtra]), + its sprite. */
        const val KEY = "store:"
        const val CARS = "cars:"
}
