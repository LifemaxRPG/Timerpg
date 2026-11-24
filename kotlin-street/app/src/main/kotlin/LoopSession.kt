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
    /** When the last ride began (the screen fades from black over it). */
    var fadeAt by mutableLongStateOf(0L); private set

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
        com.lifemax.rpg.feature.habits.presentation.scene.GameState.walkScale = 1f
        ownedWorld.clear(); atLotFlag.clear(); lastPark.clear(); drivenCar = null
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

    /** Runs [block] on the sim's thread without the buttons waiting (a car's miles, a fall's bruise); the view is published after. */
    private fun quiet(block: (LoopGame) -> Unit) {
        val g = game ?: return; val s = sim ?: return
        g.walkActivity = activityNow()
        s.run { _ -> runCatching { block(g) }.onFailure { com.timerpg.street.shot.Crumbs.add("loop: ${it.javaClass.simpleName}: ${it.message?.take(200)}") }; main.post { publish(g) } }
    }

    private fun publish(g: LoopGame) {
        val v = g.view
        view = v
        // (the needs and the sky slow the walk)
        com.lifemax.rpg.feature.habits.presentation.scene.GameState.walkScale = v.walkMultiplier.toFloat().coerceIn(.3f, 1f)
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

    // ------------------------------------------------------------------ what the player is doing (for the needs)

    private var walkSec = 0f; private var runSec = 0f; private var idleSec = 0f

    /** Each frame in the open town: what the player did for [dt] seconds. The shares fade over about a minute; only arithmetic, nothing for the sim. */
    fun noteMotion(dt: Float, walking: Boolean, running: Boolean, inCar: Boolean) {
        val f = kotlin.math.exp(-dt / 60f)
        walkSec *= f; runSec *= f; idleSec *= f
        if (inCar) idleSec += dt else if (running && walking) runSec += dt else if (walking) walkSec += dt else idleSec += dt
    }

    private fun activityNow(): String {
        val total = walkSec + runSec + idleSec
        return when { total < 1f -> "idle"; runSec > .3f * total -> "running"; walkSec + runSec > .3f * total -> "walking"; else -> "idle" }
    }

    /** Runs [block] on the sim's thread; [then] gets its outcome on the main thread. */
    private fun <T> run(what: String, block: (LoopGame) -> T, then: (T) -> Unit = {}) {
        val g = game ?: return; val s = sim ?: return
        busy = true
        g.walkActivity = activityNow()
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
        // (a ride's end: the player stands at the stop, facing along the line, under a fade)
        o.moveToStop?.let { s ->
            val other = town?.lines?.firstOrNull { it.street == s.line }?.stops?.firstOrNull { it.id != s.id }
            val dx = (other?.x ?: (s.x + 1f)) - s.x; val dz = (other?.z ?: s.z) - s.z; val l = hypot(dx, dz).coerceAtLeast(1f)
            TownPlay.placeAt(s.x, s.z, dx / l, dz / l)
            fadeAt = System.currentTimeMillis()
        }
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
            val lit = if (view?.hours?.get(place.id)?.open != false) .9 else .35
            return LoopSight.inside(place, kx, ky, room.exitX, room.exitY, wx, wy, lit, place.staff.drop(1).take(2))
        }
        val w = TownPlay.walk ?: return null
        return LoopSight.outdoors(t, TownSession.loaded?.collision, TownPlay.crowd, w.wx, w.wz)
    }

    fun buy(ctx: Context, place: LoopPlace, s: com.lifemax.rpg.feature.habits.loop.StockItem, sc: LoopScene, hour: Float) = run("buying", { it.buy(place, s, sc, hour.toDouble()) }) { shown(it, ctx) }
    fun take(ctx: Context, place: LoopPlace, s: com.lifemax.rpg.feature.habits.loop.StockItem, sc: LoopScene, hour: Float) = run("taking", { it.take(place, s, sc, hour.toDouble()) }) { shown(it, ctx) }
    fun sell(ctx: Context, place: LoopPlace, item: com.lifemax.rpg.feature.habits.loop.LoopItem, sc: LoopScene, hour: Float) = run("selling", { it.sell(place, item, sc, hour.toDouble()) }) { shown(it, ctx) }
    fun work(ctx: Context, place: LoopPlace, sc: LoopScene, hour: Float) = run("working", { it.work(place, sc, hour.toDouble()) }) { shown(it, ctx) }
    /** Employment (src/jobs): apply for an opening, start the job's shift, quit; all on the sim's thread. */
    fun applyFor(ctx: Context, place: LoopPlace, openingId: String, hour: Float) = run("applying", { it.applyFor(place, openingId, hour.toDouble()) }) { shown(it, ctx) }
    fun startShift(ctx: Context, place: LoopPlace, sc: LoopScene, hour: Float) = run("working", { it.startShift(place, sc, hour.toDouble()) }) { shown(it, ctx) }
    fun quitJob(ctx: Context) = run("quitting", { it.quitJob() }) { shown(it, ctx) }
    /** What the pawn shop pays (cents; the sim's own number, precomputed in the view: the UI thread never calls the sim). */
    fun pawnPrice(item: com.lifemax.rpg.feature.habits.loop.LoopItem): Int = view?.pawnOffers?.get("${item.key}|${item.stolen}") ?: 0
    fun pawnPriceText(item: com.lifemax.rpg.feature.habits.loop.LoopItem): String = view?.pawnOfferTexts?.get("${item.key}|${item.stolen}") ?: ""
    fun stockFor(place: LoopPlace): List<com.lifemax.rpg.feature.habits.loop.StockItem> = view?.stock?.get(place.id).orEmpty()

    /** The people at a building the player may talk to: their attitude facts looked up ahead of the conversation. */
    fun prefetch(ids: List<String>) { if (ids.isNotEmpty()) sim?.run { _ -> runCatching { game?.prefetch(ids) } } }

    /** What [personId] knows of the player, for the dialogue's fact sheet (cached; empty until looked up). */
    fun factsFor(personId: String): List<String> = game?.facts?.get(personId).orEmpty()

    // ------------------------------------------------------------------ living costs and transit (docs/LOOP.md)

    /** How near the player stands to the boarding house's door or a stop to use it (street units: 8 m). */
    val NEAR_REACH = 8f * VehicleWorld.U

    /** The boarding house, when the player stands at its door; not inside or in a car. */
    fun lodgingNear(inside: Boolean): com.lifemax.rpg.feature.habits.loop.LoopLodging? {
        if (inside || scene != null) return null
        val l = town?.lodging ?: return null
        val w = TownPlay.walk ?: return null
        return l.takeIf { hypot(it.doorX - w.wx, it.doorZ - w.wz) <= NEAR_REACH }
    }

    /** The transit stop the player stands at, if any. */
    fun stopNear(inside: Boolean): com.lifemax.rpg.feature.habits.loop.LoopStop? {
        if (inside || scene != null) return null
        val t = town ?: return null
        val w = TownPlay.walk ?: return null
        return t.lines.flatMap { it.stops }.filter { hypot(it.x - w.wx, it.z - w.wz) <= NEAR_REACH }.minByOrNull { hypot(it.x - w.wx, it.z - w.wz) }
    }

    fun rentRoom(ctx: Context, hour: Float) = run("renting", { it.rentRoom(hour.toDouble()) }) { shown(it, ctx) }
    fun payRent(ctx: Context, hour: Float) = run("paying", { it.payRent(hour.toDouble()) }) { shown(it, ctx) }
    fun sleepInRoom(ctx: Context) = run("sleeping", { it.sleepInRoom() }) { shown(it, ctx) }
    fun stash(ctx: Context, item: com.lifemax.rpg.feature.habits.loop.LoopItem) = run("keeping", { it.stash(item) }) { shown(it, ctx) }
    fun retrieve(ctx: Context, item: com.lifemax.rpg.feature.habits.loop.LoopItem) = run("taking back", { it.retrieve(item) }) { shown(it, ctx) }
    fun ride(ctx: Context, from: com.lifemax.rpg.feature.habits.loop.LoopStop, to: com.lifemax.rpg.feature.habits.loop.LoopStop, hour: Float) =
        run("riding", { it.ride(from, to, hour.toDouble()) }) { shown(it, ctx) }

    /** What a train ticket for [km] costs (cents, from the view's line of `transit.fare('railway')`), null with no fare; the UI thread never calls the sim. */
    fun railFareCents(km: Double): Int? = view?.railFare?.let { Math.round(it[0] + it[1] * km).toInt().coerceAtLeast(1) }
    fun eraMoneyText(cents: Int): String = LoopGame.eraMoney(cents, town?.year ?: 2025)
    fun railFareText(km: Double): String? = railFareCents(km)?.let { LoopGame.eraMoney(it, town?.year ?: 2025) }

    /** Pays for the ticket (on the sim's thread); [onOk] runs on the main thread when it is bought (the train then leaves). */
    fun buyTicket(ctx: Context, km: Double, onOk: () -> Unit) = run("the ticket", { it.buyRailTicket(km) }) { shown(it, ctx); if (it.ok) onOk() }

    // ------------------------------------------------------------------ needs, weather and cars (docs/LOOP.md "Needs, weather and cars")

    fun eat(ctx: Context, place: LoopPlace, hour: Float) = run("eating", { it.eat(place, hour.toDouble()) }) { shown(it, ctx) }
    fun treat(ctx: Context, place: LoopPlace, kind: String, hour: Float) = run("treating", { it.treat(place, kind, hour.toDouble()) }) { shown(it, ctx) }
    fun sleepRough(ctx: Context) = run("sleeping", { it.sleepUntil(7.0, "bench") }) { shown(it, ctx) }
    fun takeBoard(ctx: Context) = run("eating", { it.takeBoard() }) { shown(it, ctx) }
    fun washUp(ctx: Context) = run("washing", { it.washUp() }) { shown(it, ctx) }

    /** A hard stop of the driven car (the speed lost, m/s): above 10 a bruise, above 16 a sprain. */
    fun hurtByCrash(drop: Float) { if (drop >= 10f) quiet { it.injure(if (drop >= 16f) "sprain" else "bruise", "a crash at ${(drop * 2.237f).toInt()} mph") } }

    /** A fall (the speed it landed at, m/s): above 6 a bruise, above 9 a sprain. */
    fun hurtByFall(speed: Float) { if (speed >= 6f) quiet { it.injure(if (speed >= 9f) "sprain" else "bruise", "a fall") } }

    /** Where the gas station is (the used-car lot and the pumps), if the town has one. */
    fun station(): LoopPlace? = town?.places?.firstOrNull { it.shopKind == "gas station" }

    fun buyCar(ctx: Context, place: LoopPlace, lotId: String, hour: Float) = run("buying", { it.buyCar(place, lotId, hour.toDouble()) }) { shown(it, ctx) }
    fun buyPaper(ctx: Context, place: LoopPlace, kind: String, hour: Float) = run("buying", { it.buyPaper(place, kind, hour.toDouble()) }) { shown(it, ctx) }
    fun refuel(ctx: Context, id: String, gallons: Double?) = run("fuelling", { it.refuel(id, gallons, carNear(id)) }) { shown(it, ctx) }
    fun serviceCar(ctx: Context, id: String) = run("servicing", { it.service(id, carNear(id)) }) { shown(it, ctx) }
    fun repairCar(ctx: Context, id: String) = run("repairing", { it.repair(id, carNear(id)) }) { shown(it, ctx) }
    fun callTow(ctx: Context, id: String) = run("towing", { it.callTow(id) }) { shown(it, ctx); TownPlay.cars?.let { w -> if (w.driven != null && w.canExit) w.exit() } }

    // the cars in the world: each owned car's drawn car, where it was parked, the miles to pass on
    private val ownedWorld = java.util.concurrent.ConcurrentHashMap<String, VehicleWorld.Car>()
    /** Whether the drawn car stands at the lot (a bought or a towed one) rather than where the player left it. */
    private val atLotFlag = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val lastPark = HashMap<String, Pair<Float, Float>>()
    private val triedAt = HashMap<String, Long>()
    private var pendingMiles = 0.0
    private var lastDrive: Triple<String, Float, Float>? = null
    /** The owned car the player is driving now (null on foot or in another car), for the HUD's fuel line and the breakdown card. */
    var drivenCar by mutableStateOf<com.lifemax.rpg.feature.habits.loop.OwnedCarView?>(null); private set

    private fun ownedViewOf(id: String) = view?.cars?.owned?.firstOrNull { it.id == id }

    /** Whether [id]'s car stands within [CAR_NEAR] of the pumps (or is parked at the lot): refuelling and service need it. */
    fun carNear(id: String): Boolean {
        val c = ownedWorld[id] ?: return ownedViewOf(id)?.atLot == true
        val st = station() ?: return false
        return hypot(c.x - st.doorX, c.z - st.doorZ) <= CAR_NEAR
    }
    val CAR_NEAR = 40f * VehicleWorld.U

    /**
     * Twice a second: the owned cars are put in the world (a new one at the lot beside the gas station, a towed one moved there, a saved one where
     * it was left), the driven one's engine, grip and miles passed to the loop (wear, fuel, breakdowns), a parked one's spot remembered. Called on
     * the main thread; the world's own changes are posted to its thread.
     */
    fun tickCars(ctx: Context) {
        val w = TownPlay.cars ?: return; val t = town ?: return; val v = view ?: return
        w.gripScale = v.sky?.grip?.toFloat() ?: 1f
        val now = System.currentTimeMillis()
        val st = station()
        for (o in v.cars.owned) {
            val have = ownedWorld[o.id]
            if (have != null && o.atLot && atLotFlag[o.id] == false) {
                // (towed: taken from where it broke down, put back at the lot)
                if (w.driven !== have) { w.post { w.retire(have) }; ownedWorld.remove(o.id); lastPark.remove(o.id) }
                continue
            }
            if (have != null) continue
            if (now - (triedAt[o.id] ?: 0L) < 4000L) continue
            triedAt[o.id] = now
            val spec = w.specs.firstOrNull { it.key == o.specKey } ?: continue
            val colour = spec.colours[Math.floorMod(o.colour, spec.colours.size)]
            val classic = spec.era(t.year) == com.lifemax.rpg.feature.habits.vehicle.VehicleEra.CLASSIC
            val yards = TownPlay.cars?.yards
            w.post {
                val spot = if (!o.atLot) com.lifemax.rpg.feature.habits.vehicle.VehicleSpots.Spot("owned:${o.id}", o.x, o.z, o.fx, o.fz, 99999f)
                    else st?.let { p -> w.town?.let { tw -> w.spawnSpot(tw, spec, p.doorX, p.doorZ, yards)?.let { sp -> com.lifemax.rpg.feature.habits.vehicle.VehicleSpots.Spot("owned:${o.id}", sp.x, sp.z, sp.fx, sp.fz, sp.length) } } }
                if (spot == null) { com.timerpg.street.shot.Crumbs.add("loop: no clear spot at the lot for ${o.label}"); return@post }
                val c = w.add(spec, colour, classic, spot)
                ownedWorld[o.id] = c; atLotFlag[o.id] = o.atLot
                lastPark[o.id] = c.x to c.z
                log("car_spawn", """{"car":"${o.id}","at":"${if (o.atLot) "lot" else "saved spot"}","model":"${o.specKey}"}""")
            }
        }
        // the driven car: its engine, and the miles it covers
        val driven = w.driven
        val id = driven?.let { d -> ownedWorld.entries.firstOrNull { it.value === d }?.key }
        if (id != null && w.driving) {
            val o = ownedViewOf(id)
            w.engineOff = o?.blocked != null
            drivenCar = o
            val prev = lastDrive
            if (prev != null && prev.first == id) {
                val d = hypot(driven.x - prev.second, driven.z - prev.third) / VehicleWorld.U
                if (d < 100f) pendingMiles += d / 1609.34
            }
            lastDrive = Triple(id, driven.x, driven.z)
            if (pendingMiles >= .05) { val m = pendingMiles; pendingMiles = 0.0; quiet { it.driveMiles(id, m) } }
        } else {
            w.engineOff = false
            drivenCar = null
            if (lastDrive != null) {
                val lid = lastDrive!!.first
                lastDrive = null
                if (pendingMiles > 0.001) { val m = pendingMiles; quiet { it.driveMiles(lid, m) } }
                pendingMiles = 0.0
            }
            // a car left on the street: where it stands is kept (saved with the loop)
            for ((cid, c) in ownedWorld) {
                if (c === driven) continue
                val p = lastPark[cid]
                if (p == null || hypot(c.x - p.first, c.z - p.second) > 3f * VehicleWorld.U) {
                    lastPark[cid] = c.x to c.z; atLotFlag[cid] = false
                    val x = c.x; val z = c.z; val fx = c.fx; val fz = c.fz
                    quiet { it.parkCar(cid, x, z, fx, fz) }
                }
            }
        }
    }

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
