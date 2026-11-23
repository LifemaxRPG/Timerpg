package com.timerpg.street

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownYards
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuilding
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuildingLoad
import com.lifemax.rpg.feature.habits.presentation.scene.TownPlay
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownArt

/** The walkable town's data as the app ships it (assets/town/<seed>/: street.json v2 and buildings/; assets/town/art/). */
class TownData(
    val seed: String,
    val town: Town,
    val yards: TownYards,
    /** The pack's pictures the ground, greenery and props ask for. */
    val art: TownArt,
    /** A building's art by its sprite (opened on the town's workers). */
    val building: (String) -> SpriteBuilding?,
    /** Each building's name and owner (street.json's shops), by sprite. */
    val names: Map<String, Pair<String, String>>,
    /** The town's kind ("Commuter suburb"). */
    val kind: String,
    /** The folder its files are in (assets), for the dialogue and the rooms. */
    val dir: String,
    /** The town's movie theater (graph.cinema), or null for a town without one. */
    val cinema: com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaSite? = null,
    /** The town's solid shapes for the open world's walk (buildings, fences, hedges, props; null if it could not be built). */
    val collision: com.lifemax.rpg.feature.habits.domain.TownCollision? = null,
    /** The town's store sites (graph.storeSites / stores), drawn by StoreKit in their slots; none for a town without. */
    val stores: List<com.lifemax.rpg.feature.habits.presentation.scene3d.StoreSite> = emptyList(),
    /** The farms round the town (graph.farms) and the farm pack's pictures and models (assets/farm), drawn by TownFarms. */
    val farms: com.lifemax.rpg.feature.habits.domain.TownFarmsData = com.lifemax.rpg.feature.habits.domain.TownFarmsData.NONE,
    val farmArt: com.lifemax.rpg.feature.habits.presentation.scene3d.FarmArt? = null,
    /** The railroad round the town (rail.json: docs/RAIL.md), or null for a town without one. */
    val rail: com.lifemax.rpg.feature.habits.domain.RailNet? = null,
    /** The hand-made landmark models (assets/landmarks: docs/LANDMARKS_GLB.md) and the ones the town places (graph.glbLandmarks). */
    val landmarks: com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkAssets? = null,
    /** The town placed on the planet (docs/WORLD_GEN.md W4: the baked skeleton, assets/world/<seed>/), or null where none is shipped. */
    val world: com.lifemax.rpg.feature.habits.domain.WorldSite? = null,
)

/**
 * Loading and playing the walkable town (TOWN.md T10). [load] reads and parses the town on a background thread
 * (street.json v2 with its graph and yards, the buildings' shapes), then starts [TownPlay] at the town's spawn (the civic
 * avenue); the Filament layer then builds the scene nearest the player first ([com.lifemax.rpg.feature.habits.presentation.scene3d.TownRun])
 * and calls [viewReady] once the first view is complete: until then the game shows its loading screen ([ready] false,
 * [progress]). Crash reports' breadcrumbs get the load's start and end.
 */
object TownSession {
    /** The town played unless the AI panel chose the old crossroads. */
    const val DEFAULT = "gamma"
    /** The planet's world seed (the baked skeleton the app ships: src/world/gen/hubs.ts PLANET_SEED). */
    const val WORLD = "alpha"
    /** The engine thread's building budget a frame (ms): while playing, and behind the loading screen. */
    const val BUDGET_MS = 4f
    const val LOADING_BUDGET_MS = 14f

    /** The town loaded (null: none, or still loading). */
    @Volatile var loaded: TownData? = null
        private set
    var loading by mutableStateOf(false)
        private set
    /** The first view is built: the loading screen goes. */
    var ready by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(0f)
    /** The whole town is built (the ground and every building): the dialogue model may load now. */
    @Volatile var built = false
    var error by mutableStateOf<String?>(null)
        private set
    /** How long the load took (ms): reading and parsing; then building the first view. */
    var loadMs = 0f; private set
    var firstViewMs = 0f; private set
    private var startedAt = 0L
    private var wanted: String? = null

    /** Loads town [seed] (assets/town/<seed>/) and starts playing it; nothing if it is loaded or loading already. */
    fun load(ctx: Context, seed: String) {
        if (wanted == seed && (loading || loaded != null)) return
        wanted = seed
        loading = true; ready = false; progress = 0f; error = null
        startedAt = System.nanoTime()
        val app = ctx.applicationContext
        com.timerpg.street.shot.Crumbs.add("town load start: $seed")
        Thread({
            val d = try { read(app, seed) } catch (t: Throwable) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (wanted == seed) { loading = false; error = "The town did not load: ${t.message ?: t.javaClass.simpleName}" }
                    com.timerpg.street.shot.Crumbs.add("town load failed: ${t.javaClass.simpleName}: ${t.message}")
                }
                return@Thread
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (wanted != seed) return@post
                loadMs = (System.nanoTime() - startedAt) / 1e6f
                loaded = d
                val w = TownPlay.start(d.town, d.collision, d.yards)
                // where the player was last time in this town (the world position), if anywhere
                restore(app, d.town.seed, w)
                loading = false
                com.timerpg.street.shot.Crumbs.add("town load end: $seed, ${d.town.rows.size} rows, ${d.town.rows.sumOf { it.buildings.size }} buildings, %.0f ms".format(java.util.Locale.US, loadMs))
            }
        }, "town-load").apply { isDaemon = true; priority = Thread.NORM_PRIORITY }.start()
    }

    /** Back to the old crossroads: the town is let go (where the player stood is kept for next time). */
    fun stop(ctx: Context) {
        if (wanted == null) return
        save(ctx)
        wanted = null
        TownPlay.stop()
        loaded = null; loading = false; ready = false; progress = 0f; built = false
        com.timerpg.street.shot.Crumbs.add("town unloaded")
    }

    /** A saved spot further than this (street units) from any street of the town is not this town's any more: the spawn. */
    private const val FAR_SAVE = 2000f

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("town", Context.MODE_PRIVATE)

    /**
     * Keeps where the player stands in the town being played (world position and heading, the way they face), so the
     * next start (after the app was closed or the town switched away and back) begins there, not at the spawn.
     */
    fun save(ctx: Context) {
        val w = TownPlay.walk ?: return
        // (and where that is on the planet: the world position and the hub's tile, docs/WORLD_GEN.md 1 "Saved positions")
        val world = loaded?.world?.takeIf { loaded?.town === w.town }?.save(w.wx, w.wz)?.encode()
        prefs(ctx).edit().putString("seed", w.town.seed).putFloat("wx", w.wx).putFloat("wz", w.wz)
            .putFloat("hx", w.hx).putFloat("hz", w.hz).putFloat("dir", com.lifemax.rpg.feature.habits.presentation.scene.GameState.dir)
            .putString("world", world).apply()
    }

    private fun restore(ctx: Context, seed: String, w: com.lifemax.rpg.feature.habits.presentation.scene.TownWalk) {
        val p = prefs(ctx)
        if (p.getString("seed", null) != seed) return
        // (the world save first: the planet position put back into this town's street units; else the town's own)
        val back = loaded?.world?.let { site -> com.lifemax.rpg.feature.habits.domain.WorldSave.decode(p.getString("world", null))?.let { site.restore(it) } }
        val x = back?.get(0) ?: p.getFloat("wx", Float.NaN); val z = back?.get(1) ?: p.getFloat("wz", Float.NaN)
        if (x.isNaN() || z.isNaN()) return
        // (never off the town: then the spawn as before; a spot a building now stands on, as a save from before the town was
        // replanned may be, goes to the nearest free ground: the open world's walk finds it (TownCollision.nearestClear), the
        // street view's walk the nearest pavement)
        val (row, _) = w.town.nearestRow(x, z) ?: return
        var far = Float.MAX_VALUE
        for (i in 0 until row.pts.size - 1) {
            val a = row.pts[i]; val b = row.pts[i + 1]
            val dx = b.x - a.x; val dz = b.z - a.z; val l2 = (dx * dx + dz * dz).coerceAtLeast(1e-6f)
            val t = (((x - a.x) * dx + (z - a.z) * dz) / l2).coerceIn(0f, 1f)
            far = minOf(far, kotlin.math.hypot(a.x + dx * t - x, a.z + dz * t - z))
        }
        if (far > FAR_SAVE) return
        com.lifemax.rpg.feature.habits.presentation.scene.GameState.dir = if (p.getFloat("dir", 1f) < 0f) -1f else 1f
        // (both walks: the open world's lands on the clear spot nearest, the street view's on the pavement)
        TownPlay.placeAt(x, z, p.getFloat("hx", w.hx), p.getFloat("hz", w.hz))
        com.timerpg.street.shot.Crumbs.add("town: back where the player stood, (${x.toInt()}, ${z.toInt()}), row ${w.row.id} at x ${w.x.toInt()}, lane ${w.lane}")
    }

    /** The Filament layer has built the first view ([ms] from making the scene). */
    fun viewReady(ms: Float) {
        if (ready) return
        firstViewMs = ms
        ready = true
        com.timerpg.street.shot.Crumbs.add("town first view ready: %.0f ms to build, %.0f ms since load start".format(java.util.Locale.US, ms, (System.nanoTime() - startedAt) / 1e6f))
    }

    /** The Filament layer went (a renderer switch, a crash): the next one builds the scene again behind the loading screen. */
    fun sceneGone() { if (loaded != null) { ready = false; progress = 0f; built = false } }

    private fun read(ctx: Context, seed: String): TownData {
        val dir = "town/$seed"
        val json = ctx.assets.open("$dir/street.json").bufferedReader().use { it.readText() }
        val town = Town.parse(json) ?: error("$dir/street.json has no graph")
        val yards = TownYards.parse(json)
        fun text(n: String): String? = try { ctx.assets.open("$dir/buildings/$n").bufferedReader().use { it.readText() } } catch (e: Exception) { null }
        val windows = SpriteBuildingLoad.windows(text("windows.json"))
        val houses = SpriteBuildingLoad.houses(text("houses.json"))
        val modelsText = text("models.json")
        val models = SpriteBuildingLoad.models(modelsText)
        val root = org.json.JSONObject(json)
        val mids = HashMap<String, Int>()
        val names = HashMap<String, Pair<String, String>>()
        root.optJSONArray("shops")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val sp = o.getString("sprite")
                val parts = o.optJSONArray("parts")
                mids[sp] = if (parts == null) 0 else (0 until parts.length()).count { parts.getJSONObject(it).getString("key").startsWith("middle_") }
                names[sp] = o.optString("name") to o.optString("owner")
            }
        }
        fun img(path: String): ImageBitmap? = try {
            ctx.assets.open(path).use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
        } catch (e: Exception) { null }
        val art = TownArt { path -> img("town/art/$path") }
        val building: (String) -> SpriteBuilding? = { sp -> SpriteBuildingLoad.load(sp, mids[sp] ?: 0, windows, houses, models) { img("$dir/buildings/$it.png") } }
        // the railroad (its track's bed kept clear of the forest before the collision and the ground are made)
        val rail = try { ctx.assets.open("$dir/rail.json").bufferedReader().use { it.readText() }.let { com.lifemax.rpg.feature.habits.domain.RailNet.parse(it) } } catch (e: Exception) { null }
        rail?.let { r -> town.keepClear = com.lifemax.rpg.feature.habits.domain.RailScene(r, town.year)::onBed
            com.timerpg.street.shot.Crumbs.add("rail: ${r.links.size} links, ${r.towns.size} towns, station ${r.status("0,0", town.year)}") }
        // the open world's solid shapes (built here, on the load's thread: a few hundred ms on a computer)
        val c0 = System.nanoTime()
        val collision = try {
            com.lifemax.rpg.feature.habits.domain.TownCollision.build(town, yards,
                com.lifemax.rpg.feature.habits.domain.ModelShape.parseAll(modelsText),
                com.lifemax.rpg.feature.habits.domain.CinemaSiteShape.parse(root),
                com.lifemax.rpg.feature.habits.domain.StoreLots.parse(root).map { it.sprite }.toSet())
        } catch (t: Throwable) {
            com.timerpg.street.shot.Crumbs.add("town collision failed: ${t.javaClass.simpleName}: ${t.message}"); null
        }
        collision?.let { com.timerpg.street.shot.Crumbs.add("town collision: ${it.size} shapes, %.0f ms".format(java.util.Locale.US, (System.nanoTime() - c0) / 1e6f)) }
        // the hand-made landmark models the town places (read from the assets on the town's workers when built)
        fun asset(p: String): ByteArray? = try { ctx.assets.open(p).use { it.readBytes() } } catch (e: Exception) { null }
        val landmarks = try {
            val placed = com.lifemax.rpg.feature.habits.domain.GlbLandmarks.parse(root)
            com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkAssets(
                com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkSpec.catalogue(asset("landmarks/landmarks.json")!!.decodeToString()), { p -> asset("landmarks/$p") }, placed)
                .also { com.timerpg.street.shot.Crumbs.add("town landmark models: ${placed.size} placed") }
        } catch (e: Exception) { com.timerpg.street.shot.Crumbs.add("town landmark models: none (${e.javaClass.simpleName})"); null }
        // the farms round the town: their pictures and models are the farm pack's (assets/farm)
        val farms = com.lifemax.rpg.feature.habits.domain.TownFarmsData.parse(root)
        fun farmText(n: String): String? = try { ctx.assets.open("farm/$n").bufferedReader().use { it.readText() } } catch (e: Exception) { null }
        val farmArt = com.lifemax.rpg.feature.habits.presentation.scene3d.FarmArt({ p -> img("farm/$p") }, { n -> farmText(n) }, { p -> img("town/art/$p") })
        if (!farms.isEmpty) com.timerpg.street.shot.Crumbs.add("town farms: ${farms.farms.size} farms, ${farms.fields.size} fields, ${farms.roads.size} roads")
        // the planet: the baked world skeleton and this town on its hub site (gamma is the capital); its far view is drawn round the town
        val world = try {
            val w0 = System.nanoTime()
            val sk = ctx.assets.open(com.lifemax.rpg.feature.habits.domain.WorldSite.asset(WORLD)).use { com.lifemax.rpg.feature.habits.domain.WorldSkeleton.read(it) }
            com.lifemax.rpg.feature.habits.domain.WorldSite.of(sk, town.seed, town.scale.toDouble()).also {
                com.timerpg.street.shot.Crumbs.add(if (it == null) "world: no hub has the plan ${town.seed}"
                    else "${it.report}, read in %.0f ms; ".format(java.util.Locale.US, (System.nanoTime() - w0) / 1e6f) + com.lifemax.rpg.feature.habits.domain.WorldMemory.line())
            }
        } catch (t: Throwable) { com.timerpg.street.shot.Crumbs.add("world: not loaded (${t.javaClass.simpleName}: ${t.message})"); null }
        return TownData(seed, town, yards, art, building, names, root.optString("type"), dir,
            com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaSite.parse(root), collision,
            com.lifemax.rpg.feature.habits.presentation.scene3d.StoreSite.parse(root), farms, farmArt, rail, landmarks, world)
    }
}
