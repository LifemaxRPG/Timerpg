package com.timerpg.street

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lifemax.rpg.feature.habits.loop.LoopGame
import com.lifemax.rpg.feature.habits.loop.LoopOfficerWalk
import com.lifemax.rpg.feature.habits.loop.LoopOutcome
import com.lifemax.rpg.feature.habits.loop.LoopPlace
import com.lifemax.rpg.feature.habits.loop.LoopRoutes
import com.lifemax.rpg.feature.habits.loop.LoopScene
import com.lifemax.rpg.feature.habits.loop.LoopSight
import com.lifemax.rpg.feature.habits.loop.LoopTown
import com.lifemax.rpg.feature.habits.loop.LoopView
import com.lifemax.rpg.feature.habits.presentation.scene.InteriorRoom
import com.lifemax.rpg.feature.habits.presentation.scene.TownPlay
import com.lifemax.rpg.feature.habits.simjs.SimJsThread
import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld
import java.io.File
import kotlin.math.hypot

/**
 * The first playable loop in the game (kotlin-street/docs/LOOP.md): when the town loads, the sim (assets/sim/sim.js,
 * Jeff's facade) starts in QuickJS on its own thread ([SimJsThread]) with `init` built from the town's own data
 * ([LoopTown]), or comes back from the last save (files/loop/<seed>.json, saved with the town's own save). Everything
 * the player does at the shops, the clock's hours, an officer's sighting and the arrest run there in bursts, never per
 * frame and never on the render thread; the HUD reads [view], [line] and [scene] (Compose state, set on the main thread).
 * Every loop event goes to the playtest recorder (PlayLog "loop").
 */
object LoopSession {
    @Volatile var town: LoopTown? = null; private set
    @Volatile private var game: LoopGame? = null
    private var sim: SimJsThread? = null
    private var seed: String? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** The wallet, the inventory and the debug lines (null until the sim has started). */
    var view by mutableStateOf<LoopView?>(null); private set
    /** The last action's line for the HUD, and when it came (it fades). */
    var line by mutableStateOf<String?>(null); private set
    var lineAt by mutableLongStateOf(0L); private set
    /** An arrest (or a stop) to show as a card: its line; the player is taken to the station when it is closed. */
    var scene by mutableStateOf<LoopOutcome?>(null); private set
    /** The sim moved the clock (a shift, a wait, a night in the cell): the game's clock goes to this hour. */
    var hourSet by mutableStateOf<Float?>(null)
    /** Something being done on the sim's thread (the buttons wait). */
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    private var routes: LoopRoutes? = null
    @Volatile private var walks: List<LoopOfficerWalk> = emptyList()
    private var lastHour = Float.NaN
    private val spottedAt = HashMap<String, Long>()

    private fun file(ctx: Context, s: String) = File(ctx.applicationContext.filesDir, "loop/$s.json")

    private fun log(name: String, detail: String) {
        com.timerpg.street.play.PlayLog.event("loop", name, 0, detail.take(4000))
    }

    /** Starts the loop for the loaded town [d] at the game's [hour]: the last save if there is one, else a new game. */
    fun start(ctx: Context, d: TownData, hour: Float) {
        if (seed == d.seed && (game != null || busy)) return
        stop(ctx, save = true)
        seed = d.seed
        busy = true; error = null
        val app = ctx.applicationContext
        val t = runCatching { SimJsThread() }.getOrElse { e -> busy = false; error = "the sim's engine did not load: ${e.message}"; com.timerpg.street.shot.Crumbs.add("loop: $error"); return }
        sim = t
        t.run { js ->
            try {
                val t0 = System.nanoTime()
                js.load(app.assets.open("sim/sim.js").bufferedReader().use { it.readText() })
                val street = org.json.JSONObject(app.assets.open("${d.dir}/street.json").bufferedReader().use { it.readText() })
                val dialogue = org.json.JSONObject(app.assets.open("${d.dir}/dialogue.json").bufferedReader().use { it.readText() })
                val lt = LoopTown.build(d.town, street, dialogue)
                val g = LoopGame(js, lt)
                g.onEvent = { n, o -> log(n, o.toString()) }
                val f = file(app, d.seed)
                val restored = f.isFile && runCatching { g.load(f.readText()) }.onFailure { e -> com.timerpg.street.shot.Crumbs.add("loop: the save did not load (${e.message}); a new game") }.isSuccess
                if (!restored) g.init(hour.toDouble())
                val ms = (System.nanoTime() - t0) / 1e6
                com.timerpg.street.shot.Crumbs.add("loop: ${if (restored) "restored" else "new game"} in ${d.seed}, ${lt.people.size} people, ${lt.places.count { it.kind != "home" }} places, %.0f ms (init %.0f ms)"
                    .format(java.util.Locale.US, ms, g.timings["init"]?.first ?: g.timings["load"]?.first ?: 0.0))
                com.timerpg.street.play.PlayLog.spanDone("loop.start", ms, d.seed)
                main.post {
                    town = lt; game = g; routes = LoopRoutes(lt)
                    busy = false
                    lastHour = g.hour.toFloat()
                    // the game's clock follows a restored sim's
                    if (restored) hourSet = g.hour.toFloat()
                    publish(g)
                }
            } catch (e: Throwable) {
                main.post { busy = false; error = "the loop did not start: ${e.javaClass.simpleName}: ${e.message}" }
                com.timerpg.street.shot.Crumbs.add("loop: start failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** Lets the loop go (the town unloaded), saving it first. */
    fun stop(ctx: Context, save: Boolean = true) {
        if (save) save(ctx)
        val t = sim
        sim = null; game = null; town = null; routes = null; walks = emptyList(); seed = null
        TownPlay.extras = emptyList()
        view = null
        t?.let { s -> Thread({ runCatching { s.close() } }, "sim-js-close").start() }
    }

    /** Saves the loop (on its thread, written to files/loop/<seed>.json), as TownSession.save keeps the player's spot. */
    fun save(ctx: Context) {
        val g = game ?: return; val s = sim ?: return; val sd = seed ?: return
        val app = ctx.applicationContext
        s.run { _ ->
            runCatching {
                val t0 = System.nanoTime()
                val text = g.save()
                val f = file(app, sd); f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".tmp"); tmp.writeText(text); tmp.renameTo(f)
                com.timerpg.street.play.PlayLog.spanDone("loop.save", (System.nanoTime() - t0) / 1e6, "${text.length / 1024} KB")
            }.onFailure { com.timerpg.street.shot.Crumbs.add("loop: save failed: ${it.message}") }
        }
    }

    private fun publish(g: LoopGame) {
        val v = g.view
        view = v
        // the officers' plans, walked on the roads (a plan that did not change keeps its walker where it is)
        val r = routes
        if (r != null) {
            val now = System.nanoTime()
            val old = walks.associateBy { it.officerId }
            walks = v.officers.filter { it.optString("assignment") != "off_duty" }.mapNotNull { o ->
                val id = o.optString("officerId")
                val ids = o.optJSONArray("routePlaceIds")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                if (ids.isEmpty()) return@mapNotNull null
                val reason = o.optString("reason")
                old[id]?.takeIf { it.reason == reason && it.assignment == o.optString("assignment") }
                    ?: LoopOfficerWalk(id, o.optString("assignment"), reason, r.path(ids), now)
            }
        }
    }

    /** Runs [block] on the sim's thread; [then] gets its outcome on the main thread. */
    private fun <T> run(what: String, block: (LoopGame) -> T, then: (T) -> Unit = {}) {
        val g = game ?: return; val s = sim ?: return
        busy = true
        s.run { _ ->
            val r = runCatching { block(g) }
            main.post {
                busy = false
                r.onSuccess { then(it) }.onFailure { e ->
                    line = "($what failed: ${e.message?.take(120)})"; lineAt = System.currentTimeMillis()
                    com.timerpg.street.shot.Crumbs.add("loop: $what failed: ${e.javaClass.simpleName}: ${e.message?.take(300)}")
                }
                publish(g)
            }
        }
    }

    /** The line shown at [at] has been up long enough. */
    fun lineSeen(at: Long) { if (lineAt == at) line = null }

    private fun shown(o: LoopOutcome?, ctx: Context) {
        if (o == null) return
        line = o.line + (o.wary?.let { "  $it" } ?: ""); lineAt = System.currentTimeMillis()
        if (o.hoursPassed > 0.0) hourSet = o.hour.toFloat()
        lastHour = o.hour.toFloat()
        if (o.moveTo != null) scene = o
        save(ctx)
    }

    /** The arrest card closed: the player stands at the police station's door, the clock where the sim left it. */
    fun sceneDone() {
        val o = scene ?: return
        scene = null
        val p = o.moveTo ?: return
        val t = town ?: return
        t.town.placement(p.sprite)?.let { (r, b) -> val n = r.normalAt(b.mid); TownPlay.placeAt(p.doorX, p.doorZ, -n.x, -n.z) }
        hourSet = o.hour.toFloat()
    }

    /**
     * The game's clock reads [hour]: the sim follows on each whole hour crossed (and to the minute before an action).
     * [wrap]: past midnight (a train's arrival).
     */
    fun clock(hour: Float, wrap: Boolean = false) {
        val g = game ?: return
        if (busy) return
        val last = lastHour
        if (!wrap && !last.isNaN() && (hour < last || kotlin.math.floor(hour) <= kotlin.math.floor(last))) return
        lastHour = hour
        run("the hour", { it.clockTo(hour.toDouble(), exact = false, wrap = wrap) })
    }

    fun waitHours(ctx: Context, h: Double) = run("waiting", { it.waitHours(h) }) { shown(it, ctx) }
    fun sleep(ctx: Context) = run("sleeping", { it.sleepUntil(7.0) }) { shown(it, ctx) }

    /** The loop's place at building [sprite] (a shop, the pawn shop, the station), if any. */
    fun placeAt(sprite: String?): LoopPlace? = sprite?.let { town?.placeBySprite?.get(it) }

    /**
     * The scene round the player now: inside [room] (the keeper at their counter, the player at [wx], [wy]) or outdoors
     * (the people drawn near, with line of sight on the walk's collision). On the main thread, as the walk's own
     * queries (the collision's scratch is one thread's).
     */
    fun sceneAt(place: LoopPlace, room: InteriorRoom?, wx: Float, wy: Float, hour: Float): LoopScene? {
        val t = town ?: return null
        if (room != null) {
            val (kx, ky) = room.keeperSpot()
            val lit = if (place.openAt(hour.toDouble())) .9 else .35
            return LoopSight.inside(place, kx, ky, room.exitX, room.exitY, wx, wy, lit, place.staff.drop(1).take(2))
        }
        val w = TownPlay.walk ?: return null
        return LoopSight.outdoors(t, TownSession.loaded?.collision, TownPlay.crowd, w.wx, w.wz)
    }

    fun buy(ctx: Context, place: LoopPlace, s: com.lifemax.rpg.feature.habits.loop.StockItem, sc: LoopScene, hour: Float) = run("buying", { it.buy(place, s, sc, hour.toDouble()) }) { shown(it, ctx) }
    fun take(ctx: Context, place: LoopPlace, s: com.lifemax.rpg.feature.habits.loop.StockItem, sc: LoopScene, hour: Float) = run("taking", { it.take(place, s, sc, hour.toDouble()) }) { shown(it, ctx) }
    fun sell(ctx: Context, place: LoopPlace, item: com.lifemax.rpg.feature.habits.loop.LoopItem, sc: LoopScene, hour: Float) = run("selling", { it.sell(place, item, sc, hour.toDouble()) }) { shown(it, ctx) }
    fun work(ctx: Context, place: LoopPlace, sc: LoopScene, hour: Float) = run("working", { it.work(place, sc, hour.toDouble()) }) { shown(it, ctx) }
    /** What the pawn shop pays (cents; the sim's own number, precomputed in the view: the UI thread never calls the sim). */
    fun pawnPrice(item: com.lifemax.rpg.feature.habits.loop.LoopItem): Int = view?.pawnOffers?.get("${item.key}|${item.stolen}") ?: 0
    fun pawnPriceText(item: com.lifemax.rpg.feature.habits.loop.LoopItem): String = view?.pawnOfferTexts?.get("${item.key}|${item.stolen}") ?: ""
    fun stockFor(place: LoopPlace): List<com.lifemax.rpg.feature.habits.loop.StockItem> = view?.stock?.get(place.id).orEmpty()

    /** The people at a building the player may talk to: their attitude facts looked up ahead of the conversation. */
    fun prefetch(ids: List<String>) { if (ids.isNotEmpty()) sim?.run { _ -> runCatching { game?.prefetch(ids) } } }

    /** What [personId] knows of the player, for the dialogue's fact sheet (cached; empty until looked up). */
    fun factsFor(personId: String): List<String> = game?.facts?.get(personId).orEmpty()

    // ------------------------------------------------------------------ the fences

    /** The fences standing at their spots at [hour] (their hours, 20:00-02:00), for TownPlay.extras: x, z, dx, dz, look, 1 (standing). */
    fun fenceFigures(hour: Float): List<FloatArray> = view?.fences.orEmpty().filter { it.atHours(hour.toDouble()) }
        .map { floatArrayOf(it.spotX, it.spotZ, it.facingX, it.facingZ, (it.personId.hashCode() and 0xffff).toFloat(), 1f) }

    /** Each fence at their spot now (name, x, z), for the label over their head. */
    fun fenceSpots(hour: Float): List<Triple<String, Float, Float>> = view?.fences.orEmpty().filter { it.atHours(hour.toDouble()) }.map { Triple(it.name, it.spotX, it.spotZ) }

    /** The fence the player stands by (within [FENCE_REACH] of their spot, in their hours), if any. */
    fun fenceNear(hour: Float, inside: Boolean): com.lifemax.rpg.feature.habits.loop.LoopFence? {
        if (inside || scene != null) return null
        val w = TownPlay.walk ?: return null
        return view?.fences.orEmpty().filter { it.atHours(hour.toDouble()) && hypot(it.spotX - w.wx, it.spotZ - w.wz) <= FENCE_REACH }
            .minByOrNull { hypot(it.spotX - w.wx, it.spotZ - w.wz) }
    }

    fun sellToFence(ctx: Context, fence: com.lifemax.rpg.feature.habits.loop.LoopFence, item: com.lifemax.rpg.feature.habits.loop.LoopItem, hour: Float) =
        run("selling", { it.sellToFence(fence, item, hour.toDouble()) }) { shown(it, ctx) }

    /** How near the player stands to a fence to deal (street units: 3 m). */
    val FENCE_REACH = 3f * VehicleWorld.U

    // ------------------------------------------------------------------ the officers

    private val tmp = FloatArray(4)

    /** The officers where their plans have them now, for TownPlay.extras (x, z, dx, dz, look). */
    fun officerFigures(nowNs: Long): List<FloatArray> = walks.map { w -> w.at(nowNs, tmp).let { floatArrayOf(it[0], it[1], it[2], it[3], (w.officerId.hashCode() and 0xffff).toFloat()) } }

    /** Each officer's spot now (id, x, z), for the HUD's label over their head. */
    fun officers(nowNs: Long): List<Triple<String, Float, Float>> = walks.map { w -> w.at(nowNs, tmp).let { Triple(w.officerId, it[0], it[1]) } }

    /**
     * Once a second: an officer within [SIGHT] of the player with nothing between them sees them (`spotted`, at most
     * every [AGAIN_MS] per officer); watching is shown, a stop or an arrest is the arrest scene. Not while inside, in a
     * car, or busy.
     */
    fun lookForPlayer(ctx: Context, hour: Float, inside: Boolean) {
        val t = town ?: return
        if (busy || inside || scene != null || game == null) return
        val w = TownPlay.walk ?: return
        val now = System.nanoTime()
        for ((id, x, z) in officers(now)) {
            val d = hypot(x - w.wx, z - w.wz)
            if (d > SIGHT) continue
            if (System.currentTimeMillis() - (spottedAt[id] ?: 0L) < AGAIN_MS) continue
            if (!LoopSight.clear(TownSession.loaded?.collision, x, z, w.wx, w.wz)) continue
            spottedAt[id] = System.currentTimeMillis()
            val light = LoopSight.lightAt(t, w.wx, w.wz, hour.toDouble())
            run("an officer", { it.spotted(id, d / VehicleWorld.U.toDouble(), light, hour.toDouble()) }) { shown(it, ctx) }
            return
        }
    }

    /** How far an officer sees the player (street units: 30 m). */
    val SIGHT = 30f * VehicleWorld.U
    private const val AGAIN_MS = 20_000L
}
