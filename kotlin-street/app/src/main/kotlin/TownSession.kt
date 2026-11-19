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

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("town", Context.MODE_PRIVATE)

    /**
     * Keeps where the player stands in the town being played (world position and heading, the way they face), so the
     * next start (after the app was closed or the town switched away and back) begins there, not at the spawn.
     */
    fun save(ctx: Context) {
        val w = TownPlay.walk ?: return
        prefs(ctx).edit().putString("seed", w.town.seed).putFloat("wx", w.wx).putFloat("wz", w.wz)
            .putFloat("hx", w.hx).putFloat("hz", w.hz).putFloat("dir", com.lifemax.rpg.feature.habits.presentation.scene.GameState.dir).apply()
    }

    private fun restore(ctx: Context, seed: String, w: com.lifemax.rpg.feature.habits.presentation.scene.TownWalk) {
        val p = prefs(ctx)
        if (p.getString("seed", null) != seed) return
        val x = p.getFloat("wx", Float.NaN); val z = p.getFloat("wz", Float.NaN)
        if (x.isNaN() || z.isNaN()) return
        // (never inside a building, nor off the town: then the spawn as before)
        val near = w.town.nearestRow(x, z)?.first ?: return
        if (w.blockedAt(x, z, near)) return
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
        // the farms round the town: their pictures and models are the farm pack's (assets/farm)
        val farms = com.lifemax.rpg.feature.habits.domain.TownFarmsData.parse(root)
        fun farmText(n: String): String? = try { ctx.assets.open("farm/$n").bufferedReader().use { it.readText() } } catch (e: Exception) { null }
        val farmArt = com.lifemax.rpg.feature.habits.presentation.scene3d.FarmArt({ p -> img("farm/$p") }, { n -> farmText(n) }, { p -> img("town/art/$p") })
        if (!farms.isEmpty) com.timerpg.street.shot.Crumbs.add("town farms: ${farms.farms.size} farms, ${farms.fields.size} fields, ${farms.roads.size} roads")
        return TownData(seed, town, yards, art, building, names, root.optString("type"), dir,
            com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaSite.parse(root), collision,
            com.lifemax.rpg.feature.habits.presentation.scene3d.StoreSite.parse(root), farms, farmArt)
    }
}
